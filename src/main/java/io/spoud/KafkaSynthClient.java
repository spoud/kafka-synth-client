package io.spoud;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

@ApplicationScoped
public class KafkaSynthClient {
    private final PartitionRebalancer partitionRebalancer;
    private final RandomGenerator randomGenerator = RandomGenerator.getDefault();
    private final byte[] message;
    private final WrappedKafkaProducer producer;
    private final MeterRegistry meterRegistry;
    private final int messagesPerSecond;

    public static final String METER_NAME = "synth-client.e2e.latency";

    public KafkaSynthClient(
            @ConfigProperty(name = "message.size.bytes", defaultValue = "8") int messageSize,
            @ConfigProperty(name = "messages.per.second", defaultValue = "1") int messagesPerSecond,
            PartitionRebalancer partitionRebalancer,
            MeterRegistry meterRegistry,
            WrappedKafkaProducer producer) {
        this.partitionRebalancer = partitionRebalancer;
        this.message = new byte[messageSize];
        this.meterRegistry = meterRegistry;
        this.producer = producer;
        this.messagesPerSecond = Math.max(messagesPerSecond, 1);
    }

    Map<Integer, DistributionSummary> partitionLatencies = new HashMap<>();

    void recordLatency(long latency, int partition) {
        Integer broker = null;
        for (var entry : partitionRebalancer.getPartitionsByBroker().entrySet()) {
            var brokerId = entry.getKey();
            var partitions = entry.getValue();
            if (partitions.contains(partition)) {
                broker = brokerId;
                break;
            }
        }
        Log.debug("Latency for partition " + partition + " on broker " + broker + ": " + latency + "ms");
        Integer finalBroker = broker;
        var e2eLatency = partitionLatencies.computeIfAbsent(finalBroker, (k) -> DistributionSummary
                .builder(METER_NAME)
                .baseUnit("ms")
                .tag("broker", finalBroker != null ? finalBroker.toString() : "unknown")
                .description("End-to-end latency of the synthetic client")
                .minimumExpectedValue(1.0)
                .maximumExpectedValue(10_000.0)
                .publishPercentiles(0.5, 0.8, 0.9, 0.95, 0.99)
                .register(meterRegistry));
        e2eLatency.record(latency);
    }


    @Scheduled(every = "1s")
    void produceMessage() {
        for (int i = 0; i < messagesPerSecond; i++) {
            var r = new ProducerRecord<>(partitionRebalancer.getTopic(), randomGenerator.nextLong(), message);
            producer.send(r);
        }
    }

    AtomicInteger counter = new AtomicInteger(0);

    @Incoming("msg-in")
    public void recordLatency(ConsumerRecord<String, String> message) {
        long produceTime = message.timestamp();
        long consumeTime = System.currentTimeMillis();
        recordLatency(consumeTime - produceTime, message.partition());
        if (counter.incrementAndGet() % 100 == 0) {
            Log.info("Processed " + counter.get() + " messages");
        }
    }
}
