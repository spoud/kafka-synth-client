package io.spoud;

import io.micrometer.core.instrument.*;
import io.quarkus.logging.Log;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.PartitionRebalancer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToDoubleFunction;

@ApplicationScoped
public class MetricService {

    public static final String E2E_METER_NAME = "synth-client.e2e.latency";
    public static final String ACK_METER_NAME = "synth-client.ack.latency";
    public static final String TIME_SINCE_LAST_CONSUMPTION_METER_NAME = "synth-client.time-since-last-consumption";

    public static final String TAG_TOPIC = "topic";
    public static final String TAG_PARTITION = "partition";
    public static final String TAG_BROKER = "broker";
    public static final String TAG_TO_RACK = "toRack";
    public static final String TAG_FROM_RACK = "fromRack";
    public static final String TAG_BROKER_RACK = "viaBrokerRack";
    public static final String TAG_RACK = "rack";

    private List<Long> e2eLatencyInitialBuffer = new ArrayList<>();
    private List<Long> ackLatencyInitialBuffer = new ArrayList<>();

    private final MeterRegistry meterRegistry;
    private final PartitionRebalancer partitionRebalancer;
    private final Map<PartitionRackPair, WrappedDistributionSummary> e2eLatencies = new HashMap<>();
    private final Map<Integer, WrappedDistributionSummary> ackLatenciesByPartition = new HashMap<>();
    private final SynthClientConfig config;
    private final String kafkaClientId;
    private final AtomicReference<Instant> lastConsumptionTime = new AtomicReference<>(Instant.now());
    private final Map<Integer, Long> messagesConsumedPerPartition = new HashMap<>();

    public MetricService(MeterRegistry meterRegistry,
                         PartitionRebalancer partitionRebalancer,
                         SynthClientConfig config,
                         @ConfigProperty(name = "kafka.client.id") String kafkaClientId) {
        this.meterRegistry = meterRegistry;
        this.partitionRebalancer = partitionRebalancer;
        this.config = config;
        this.kafkaClientId = kafkaClientId;
        TimeGauge.builder(TIME_SINCE_LAST_CONSUMPTION_METER_NAME, this, TimeUnit.MILLISECONDS,
                        MetricService::getMillisecondsSinceLastConsumption)
                .tag(TAG_RACK, config.rack())
                .register(meterRegistry);
    }

    public <T> void addGauge(String name, Tags tags, T stateObject, ToDoubleFunction<T> valueFunction) {
        meterRegistry.gauge(name, tags, stateObject, valueFunction);
    }

    public long getMillisecondsSinceLastConsumption() {
        return Duration.between(lastConsumptionTime.get(), Instant.now()).toMillis();
    }

    public void recordConsumptionTime() {
        lastConsumptionTime.updateAndGet(inst -> Instant.now().isAfter(inst) ? Instant.now() : inst);
    }

    synchronized public void recordLatency(String topic, int partition, long latencyMs, String fromRack) {
        Log.debugv("Latency for partition {0}: {1}ms", partition, latencyMs);
        if (partitionRebalancer.isInitialRefreshPending()) {
            Log.info("Ignoring latencies as the initial partition assignment is not done yet");
            return;
        }
        String broker = partitionRebalancer.getBrokerIdForPartition(partition)
                .map(String::valueOf)
                .orElse("unknown");
        String partitionLeaderRack = partitionRebalancer.getRackOfPartitionLeader(partition);
        long recordsSeen = messagesConsumedPerPartition.computeIfAbsent(partition, (k) -> (long) 0);
        messagesConsumedPerPartition.put(partition, Math.max(1, recordsSeen + 1));
        if (recordsSeen < config.messages().ignoreFirstNMessages()) {
            Log.debugv("Ignoring latency for partition {0} as we have seen only {1} / {2} records",
                    partition, recordsSeen, config.messages().ignoreFirstNMessages());
            return;
        }
        var key = new PartitionRackPair(partition, fromRack);
        var e2eLatency = e2eLatencies
                .computeIfAbsent(key, (k) -> genE2eSummary(topic, partition, broker, fromRack, partitionLeaderRack));
        if (!e2eLatency.broker().equals(broker)) {
            // broker changed, recreate the distribution summary
            meterRegistry.remove(e2eLatency.distributionSummary());
            e2eLatency = genE2eSummary(topic, partition, broker, fromRack, partitionLeaderRack);
            e2eLatencies.put(key, e2eLatency);
        }
        if (e2eLatencyInitialBuffer.size() < config.minSamplesFirstWindow()) {
            e2eLatencyInitialBuffer.add(latencyMs);
            if (e2eLatencyInitialBuffer.size() == config.minSamplesFirstWindow()) {
                Log.info("Initial e2e latencies recorded");
                Log.debugf("Initial e2e latencies recorded for partition %s %s", partition, e2eLatencyInitialBuffer);
                WrappedDistributionSummary finalE2eLatency = e2eLatency;
                e2eLatencyInitialBuffer.forEach(latency -> finalE2eLatency.distributionSummary().record(latency));
            }
        } else {
            e2eLatency.distributionSummary().record(latencyMs);
        }
    }

    public void recordAckLatency(String topic, int partition, Duration between) {
        Log.debugv("Ack latency for partition {0}: {1}ms", partition, between.toMillis());
        if (partitionRebalancer.isInitialRefreshPending()) {
            Log.info("Ignoring ack latency as the initial partition assignment is not done yet");
            return;
        }
        String broker = partitionRebalancer.getBrokerIdForPartition(partition)
                .map(String::valueOf)
                .orElse("unknown");
        String partitionLeaderRack = partitionRebalancer.getRackOfPartitionLeader(partition);
        var ackLatency = ackLatenciesByPartition.computeIfAbsent(partition, (k) -> genAckSummary(topic, partition, broker, partitionLeaderRack));
        if (!ackLatency.broker().equals(broker)) {
            // broker changed, recreate the distribution summary
            meterRegistry.remove(ackLatency.distributionSummary());
            ackLatency = genAckSummary(topic, partition, broker, partitionLeaderRack);
            ackLatenciesByPartition.put(partition, ackLatency);
        }
        if (ackLatencyInitialBuffer.size() < config.minSamplesFirstWindow()) {
            ackLatencyInitialBuffer.add(between.toMillis());
            if (ackLatencyInitialBuffer.size() == config.minSamplesFirstWindow()) {
                Log.info("Initial ack latencies recorded");
                Log.debugf("Initial ack latencies recorded for partition %s %s", partition, ackLatencyInitialBuffer);
                WrappedDistributionSummary finalAckLatency = ackLatency;
                ackLatencyInitialBuffer.forEach(latency -> finalAckLatency.distributionSummary().record(latency));
            }
        } else {
            ackLatency.distributionSummary().record(between.toMillis());
        }

    }

    private WrappedDistributionSummary genAckSummary(String topic, int partition, String broker, String brokerRack) {
        return new WrappedDistributionSummary(DistributionSummary
                .builder(ACK_METER_NAME)
                .baseUnit("ms")
                .tag(TAG_TOPIC, topic)
                .tag(TAG_PARTITION, String.valueOf(partition))
                .tag(TAG_BROKER, broker)
                .tag(TAG_RACK, config.rack())
                .tag(TAG_BROKER_RACK, brokerRack)
                .description("Ack latency of the synthetic client")
                .minimumExpectedValue(config.expectedMinLatency())
                .maximumExpectedValue(config.expectedMaxLatency())
                .publishPercentiles(0.5, 0.8, 0.9, 0.95, 0.99)
                .publishPercentileHistogram(config.publishHistogramBuckets())
                .distributionStatisticExpiry(config.samplingTimeWindow())
                .register(meterRegistry), broker);
    }

    private WrappedDistributionSummary genE2eSummary(String topic, int partition, String broker, String fromRack, String brokerRack) {
        return new WrappedDistributionSummary(DistributionSummary
                .builder(E2E_METER_NAME)
                .baseUnit("ms")
                .tag(TAG_TOPIC, topic)
                .tag(TAG_PARTITION, String.valueOf(partition))
                .tag(TAG_BROKER, broker)
                .tag(TAG_TO_RACK, config.rack())
                .tag(TAG_FROM_RACK, fromRack)
                .tag(TAG_BROKER_RACK, brokerRack)
                .description("End-to-end latency of the synthetic client")
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(10_000.0)
                .publishPercentiles(0.5, 0.8, 0.9, 0.95, 0.99)
                .publishPercentileHistogram(config.publishHistogramBuckets())
                .distributionStatisticExpiry(config.samplingTimeWindow())
                .register(meterRegistry), broker);
    }

    private Collection<ObjectName> getAvailableMBeanNames() {
        try {
            return ManagementFactory.getPlatformMBeanServer().queryNames(null, null);
        } catch (Exception e) {
            Log.warn("Failed to retrieve MBean names", e);
            return List.of();
        }
    }

    private record WrappedDistributionSummary(DistributionSummary distributionSummary, String broker) {
    }

    private record PartitionRackPair(int partition, String rack) {
    }
}
