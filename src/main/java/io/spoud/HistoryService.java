package io.spoud;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.spoud.config.SynthClientConfig;
import jakarta.inject.Singleton;
import jakarta.ws.rs.*;
import org.duckdb.DuckDBConnection;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

@Singleton
@Path("/history")
@Produces("application/json")
public class HistoryService {
    private final MetricService metricService;
    private final TimeService timeService;
    private final DuckDBConnection conn;
    private final Duration retentionTime;

    public HistoryService(MetricService metricService,
                          TimeService timeService,
                          SynthClientConfig config) throws ClassNotFoundException, SQLException {
        this.metricService = metricService;
        this.timeService = timeService;
        this.retentionTime = config.historyRetentionPeriod();
        Class.forName("org.duckdb.DuckDBDriver"); // make sure that the driver is registered
        conn = (DuckDBConnection) DriverManager.getConnection(config.historyDatabasePath());
        conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS e2e_latencies (timestamp TIMESTAMPTZ, from_rack VARCHAR, to_rack VARCHAR, broker_rack VARCHAR, latency_ms REAL, percentile INT);
                """);
        conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS ack_latencies (timestamp TIMESTAMPTZ, rack VARCHAR, broker_rack VARCHAR, latency_ms REAL, percentile INT);
                """);
    }

    @Scheduled(every = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void recordSnapshot() {
        int rowCount = 0;
        try (var appender = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "e2e_latencies")) {
            for (var summary : metricService.getE2ELatencies()) {
                var snap = summary.distributionSummary().takeSnapshot();
                var fromRack = summary.distributionSummary().getId().getTag(MetricService.TAG_FROM_RACK);
                var toRack = summary.distributionSummary().getId().getTag(MetricService.TAG_TO_RACK);
                var brokerRack = summary.distributionSummary().getId().getTag(MetricService.TAG_BROKER_RACK);
                for (var v : snap.percentileValues()) {
                    Log.debugf("From `%s` via `%s` to `%s` %.2fth pct. = %.2fms", fromRack, brokerRack, toRack, 100. * v.percentile(), v.value());
                    appender.beginRow();
                    appender.append(timeService.now());
                    appender.append(fromRack);
                    appender.append(toRack);
                    appender.append(brokerRack);
                    appender.append((float)v.value());
                    appender.append((int) (100. * v.percentile()));
                    appender.endRow();
                    rowCount++;
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to record snapshot", e);
            rowCount = 0;
        }
        Log.debugf("%d e2e latency rows successfully saved to history", rowCount);
        rowCount = 0;
        try (var appender = conn.createAppender(DuckDBConnection.DEFAULT_SCHEMA, "ack_latencies")) {
            for (var summary : metricService.getAckLatencies()) {
                var snap = summary.distributionSummary().takeSnapshot();
                var rack = summary.distributionSummary().getId().getTag(MetricService.TAG_RACK);
                var brokerRack = summary.distributionSummary().getId().getTag(MetricService.TAG_BROKER_RACK);
                for (var v : snap.percentileValues()) {
                    Log.debugf("Ack latency for rack `%s` via `%s` %.2fth pct. = %.2fms", rack, brokerRack, 100. * v.percentile(), v.value());
                    appender.beginRow();
                    appender.append(timeService.now());
                    appender.append(rack);
                    appender.append(brokerRack);
                    appender.append((float) v.value());
                    appender.append((int) (100. * v.percentile()));
                    appender.endRow();
                    rowCount++;
                }
            }
        } catch (SQLException e) {
            Log.error("Failed to record ack latency snapshot", e);
            rowCount = 0;
        }
        Log.debugf("%d ack latency rows successfully saved to history", rowCount);
    }

    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void cleanUpHistory() {
        cleanUpTable("e2e_latencies");
        cleanUpTable("ack_latencies");
    }

    private void cleanUpTable(String table) {
        var cutoff = timeService.now().minus(retentionTime);
        try {
            var stmt = conn.prepareStatement("DELETE FROM " + table + " WHERE timestamp < ?");
            stmt.setObject(1, cutoff);
            var deleted = stmt.executeUpdate();
            Log.infof("Deleted %d rows older than %s from %s", deleted, retentionTime, table);
        } catch (SQLException e) {
            Log.errorf("Failed to clean up history for table %s", table, e);
        }
    }

    public record MessagePath(String fromRack, String toRack, String viaBrokerRack, float latestP99latency, float latestP99AckLatency) {
    }

    @GET
    @Path("/message-paths")
    public List<MessagePath> getMessagePaths() throws SQLException {
        var paths = conn.createStatement().executeQuery("""
                SELECT e2e.from_rack, e2e.to_rack, e2e.broker_rack, last(e2e.latency_ms ORDER BY e2e.timestamp ASC) AS latest_p99_latency, last(ack.latency_ms ORDER BY ack.timestamp ASC) AS latest_p99_ack_latency
                FROM e2e_latencies e2e
                LEFT JOIN ack_latencies ack ON e2e.broker_rack = ack.broker_rack AND e2e.from_rack = ack.rack AND ack.percentile = e2e.percentile
                WHERE e2e.percentile = 99
                GROUP BY e2e.from_rack, e2e.to_rack, e2e.broker_rack
                """);
        var result = new ArrayList<MessagePath>();
        while (paths.next()) {
            result.add(new MessagePath(paths.getString(1), paths.getString(2), paths.getString(3), paths.getFloat(4), paths.getFloat(5)));
        }
        return result;
    }

    public record LatencySummary(long[] timestamps, Map<String, Double[]> percentiles) {
    }

    @GET
    @Path("/e2e-latencies/{from}/{via}/{to}")
    public LatencySummary getE2ELatencies(@PathParam("from") String from,
                                          @PathParam("via") String via,
                                          @PathParam("to") String to,
                                          @QueryParam("interval_start") Instant start,
                                          @QueryParam("interval_end") Instant end) throws SQLException {
        var startTime = (start != null ? start : timeService.now().minusHours(1).toInstant()).atOffset(ZoneOffset.UTC);
        var endTime = (end != null ? end : timeService.now().toInstant()).atOffset(ZoneOffset.UTC);
        var timeBucketWidthSeconds = 15 * (Math.ceil(Duration.between(startTime, endTime).toMinutes() / 60.));
        try (var stmt = conn.prepareStatement("""
                SELECT percentile, array_agg((EXTRACT(EPOCH FROM tb) * 1000)::BIGINT), array_agg(latency_ms)
                FROM (
                    SELECT time_bucket(INTERVAL '%d SECONDS', timestamp) AS tb, MAX(latency_ms) AS latency_ms, percentile
                    FROM e2e_latencies
                    WHERE from_rack = ? AND to_rack = ? AND broker_rack = ? AND timestamp >= ? AND timestamp <= ?
                    GROUP BY tb, percentile
                    ORDER BY tb ASC
                )
                GROUP BY percentile
                """.formatted((int)timeBucketWidthSeconds))) {
            stmt.setString(1, from);
            stmt.setString(2, to);
            stmt.setString(3, via);
            stmt.setObject(4, startTime);
            stmt.setObject(5, endTime);
            var paths = stmt.executeQuery();
            long[] timestamps = new long[]{};
            Map<String, Double[]> percentiles = new HashMap<>();
            while (paths.next()) {
                var percentile = paths.getInt(1);
                timestamps = Arrays.stream((Object[])paths.getArray(2).getArray()).mapToLong(e -> (Long) e).toArray();
                var latencies = Arrays.stream((Object[])paths.getArray(3).getArray()).mapToDouble(e -> ((Float)e).doubleValue()).toArray();
                percentiles.put(Integer.toString(percentile), Arrays.stream(latencies).boxed().toArray(Double[]::new));
            }
            return new LatencySummary(timestamps, percentiles);
        }
    }

    @GET
    @Path("/ack-latencies/{rack}/{via}")
    public LatencySummary getAckLatencies(@PathParam("rack") String rack,
                                                @PathParam("via") String via,
                                                @QueryParam("interval_start") Instant start,
                                                @QueryParam("interval_end") Instant end) throws SQLException {
        var endTime = (end != null ? end : timeService.now().toInstant()).atOffset(ZoneOffset.UTC);
        var startTime = (start != null ? start : endTime.minusHours(1).toInstant()).atOffset(ZoneOffset.UTC);
        var timeBucketWidthSeconds = 15 * (Math.ceil(Duration.between(startTime, endTime).toMinutes() / 60.));
        try (var stmt = conn.prepareStatement("""
                SELECT percentile, array_agg((EXTRACT(EPOCH FROM tb) * 1000)::BIGINT), array_agg(latency_ms)
                FROM (
                    SELECT time_bucket(INTERVAL '%d SECONDS', timestamp) AS tb, MAX(latency_ms) AS latency_ms, percentile
                    FROM ack_latencies
                    WHERE rack = ? AND broker_rack = ? AND timestamp >= ? AND timestamp <= ?
                    GROUP BY tb, percentile
                    ORDER BY tb ASC
                )
                GROUP BY percentile
                """.formatted((int)timeBucketWidthSeconds))) {
            stmt.setString(1, rack);
            stmt.setString(2, via);
            stmt.setObject(3, startTime);
            stmt.setObject(4, endTime);
            var paths = stmt.executeQuery();
            long[] timestamps = new long[]{};
            Map<String, Double[]> percentiles = new HashMap<>();
            while (paths.next()) {
                var percentile = paths.getInt(1);
                timestamps = Arrays.stream((Object[])paths.getArray(2).getArray()).mapToLong(e -> (Long) e).toArray();
                var latencies = Arrays.stream((Object[])paths.getArray(3).getArray()).mapToDouble(e -> ((Float)e).doubleValue()).toArray();
                percentiles.put(Integer.toString(percentile), Arrays.stream(latencies).boxed().toArray(Double[]::new));
            }
            return new LatencySummary(timestamps, percentiles);
        }
    }
}
