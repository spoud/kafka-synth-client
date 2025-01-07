package io.spoud;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.TimeGauge;
import io.quarkus.logging.Log;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.PartitionRebalancer;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@ApplicationScoped
public class MetricService {

    public static final String E2E_METER_NAME = "synth-client.e2e.latency";
    public static final String ACK_METER_NAME = "synth-client.ack.latency";
    public static final String PRODUCE_ERROR_RATE_METER_NAME = "synth-client.producer.error-rate";
    public static final String TIME_SINCE_LAST_CONSUMPTION_METER_NAME = "synth-client.time-since-last-consumption";

    private static final String TAG_PARTITION = "partition";
    private static final String TAG_BROKER = "broker";
    private static final String TAG_TO_RACK = "toRack";
    private static final String TAG_FROM_RACK = "fromRack";
    private static final String TAG_RACK = "rack";

    private final MeterRegistry meterRegistry;
    private final PartitionRebalancer partitionRebalancer;
    private final Map<PartitionRackPair, WrappedDistributionSummary> e2eLatencies = new HashMap<>();
    private final Map<Integer, WrappedDistributionSummary> ackLatenciesByPartition = new HashMap<>();
    private final SynthClientConfig config;
    private final String kafkaClientId;
    private final AtomicReference<Instant> lastConsumptionTime = new AtomicReference<>(Instant.now());

    public MetricService(MeterRegistry meterRegistry,
                         PartitionRebalancer partitionRebalancer,
                         SynthClientConfig config,
                         @ConfigProperty(name = "kafka.client.id") String kafkaClientId) {
        this.meterRegistry = meterRegistry;
        this.partitionRebalancer = partitionRebalancer;
        this.config = config;
        this.kafkaClientId = kafkaClientId;
        meterRegistry.gauge(PRODUCE_ERROR_RATE_METER_NAME, Tags.of(TAG_RACK, config.rack()), this, MetricService::getProduceErrorRate);
        TimeGauge.builder(TIME_SINCE_LAST_CONSUMPTION_METER_NAME, this, TimeUnit.MILLISECONDS,
                        MetricService::getMillisecondsSinceLastConsumption)
                .tag(TAG_RACK, config.rack())
                .register(meterRegistry);
    }

    public double getProduceErrorRate() {
        try {
            String mbeanName = String.format("kafka.producer:client-id=%s,type=producer-mxetrics", kafkaClientId);
            ObjectName objectName = new ObjectName(mbeanName);
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            return (double) mBeanServer.getAttribute(objectName, "record-error-rate");
        } catch (Exception e) {
            Log.error("Error retrieving error rate", e);
            Log.debugv("Available MBeans: {0}",
                    getAvailableMBeanNames().stream()
                            .map(ObjectName::toString)
                            .collect(Collectors.joining("\n\t- ", "\n", "")));
            return -1;
        }
    }

    public long getMillisecondsSinceLastConsumption() {
        return Duration.between(lastConsumptionTime.get(), Instant.now()).toMillis();
    }

    public void recordConsumptionTime() {
        lastConsumptionTime.updateAndGet(inst -> Instant.now().isAfter(inst) ? Instant.now() : inst);
    }

    synchronized public void recordLatency(String topic, int partition, long latencyMs, String fromRack) {
        Log.debugv("Latency for partition {0}: {1}ms", partition, latencyMs);
        String broker = partitionRebalancer.getBrokerIdForPartition(topic, partition)
                .map(String::valueOf)
                .orElse("unknown");
        var key = new PartitionRackPair(partition, fromRack);
        var e2eLatency = e2eLatencies
                .computeIfAbsent(key, (k) -> genE2eSummary(partition, broker, fromRack));
        if (!e2eLatency.broker().equals(broker)) {
            // broker changed, recreate the distribution summary
            meterRegistry.remove(e2eLatency.distributionSummary());
            e2eLatency = genE2eSummary(partition, broker, fromRack);
            e2eLatencies.put(key, e2eLatency);
        }
        e2eLatency.distributionSummary().record(latencyMs);
    }

    public void recordAckLatency(String topic, int partition, Duration between) {
        Log.debugv("Ack latency for partition {0}: {1}ms", partition, between.toMillis());
        String broker = partitionRebalancer.getBrokerIdForPartition(topic, partition)
                .map(String::valueOf)
                .orElse("unknown");
        var ackLatency = ackLatenciesByPartition.computeIfAbsent(partition, (k) -> genAckSummary(partition, broker));
        if (!ackLatency.broker().equals(broker)) {
            // broker changed, recreate the distribution summary
            meterRegistry.remove(ackLatency.distributionSummary());
            ackLatency = genAckSummary(partition, broker);
            ackLatenciesByPartition.put(partition, ackLatency);
        }
        ackLatency.distributionSummary().record(between.toMillis());
    }

    private WrappedDistributionSummary genAckSummary(int partition, String broker) {
        return new WrappedDistributionSummary(DistributionSummary
                .builder(ACK_METER_NAME)
                .baseUnit("ms")
                .tag(TAG_PARTITION, String.valueOf(partition))
                .tag(TAG_BROKER, broker)
                .tag(TAG_RACK, config.rack())
                .description("Ack latency of the synthetic client")
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(10_000.0)
                .publishPercentiles(0.5, 0.8, 0.9, 0.95, 0.99)
                .register(meterRegistry), broker);
    }

    private WrappedDistributionSummary genE2eSummary(int partition, String broker, String fromRack) {
        return new WrappedDistributionSummary(DistributionSummary
                .builder(E2E_METER_NAME)
                .baseUnit("ms")
                .tag(TAG_PARTITION, String.valueOf(partition))
                .tag(TAG_BROKER, broker)
                .tag(TAG_TO_RACK, config.rack())
                .tag(TAG_FROM_RACK, fromRack)
                .description("End-to-end latency of the synthetic client")
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(10_000.0)
                .publishPercentiles(0.5, 0.8, 0.9, 0.95, 0.99)
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
