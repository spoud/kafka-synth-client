package io.spoud;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.PartitionRebalancer;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class MetricService {

    public static final String METER_NAME = "synth-client.e2e.latency";

    private final MeterRegistry meterRegistry;
    private final PartitionRebalancer partitionRebalancer;
    private final Map<Integer, DistributionSummary> partitionLatencies = new HashMap<>();
    private final SynthClientConfig config;

    public MetricService(MeterRegistry meterRegistry,
                         PartitionRebalancer partitionRebalancer,
                         SynthClientConfig config) {
        this.meterRegistry = meterRegistry;
        this.partitionRebalancer = partitionRebalancer;
        this.config = config;
    }

    synchronized public void recordLatency(String topic, int partition, long latencyMs, String fromRack) {
        Log.debugv("Latency for partition {0}: {1}ms", partition, latencyMs);
        String broker = partitionRebalancer.getBrokerIdForPartition(topic, partition)
                .map(String::valueOf)
                .orElse("unknown");
        DistributionSummary e2eLatency = partitionLatencies.computeIfAbsent(partition, (k) -> DistributionSummary
                .builder(METER_NAME)
                .baseUnit("ms")
                .tag("partition", String.valueOf(partition))
                .tag("broker", broker)
                .tag("to", config.rack())
                .tag("from", fromRack)
                .description("End-to-end latency of the synthetic client")
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(10_000.0)
                .publishPercentiles(0.5, 0.8, 0.9, 0.95, 0.99)
                .register(meterRegistry));
        e2eLatency.record(latencyMs);
    }

    public void recordAckLatency(String topic, int partition, Duration between) {
        // TODO metric for ACK time
    }
}
