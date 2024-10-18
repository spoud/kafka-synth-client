package io.spoud;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.MessageProducer;
import io.spoud.kafka.PartitionRebalancer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.random.RandomGenerator;

@ApplicationScoped
public class KafkaSynthClient {
    private final RandomGenerator randomGenerator = RandomGenerator.getDefault();
    private final byte[] message;
    private final MessageProducer producer;
    private final int messagesPerSecond;

    public KafkaSynthClient(
            SynthClientConfig config,
            PartitionRebalancer partitionRebalancer,
            MeterRegistry meterRegistry,
            MessageProducer producer) {
        this.message = new byte[config.messages().messageSizeBytes()];
        this.producer = producer;
        this.messagesPerSecond = Math.max(config.messages().messagesPerSecond(), 1);
    }

    // TODO this sounds sketchy, we have no guarantee that the scheduler will run every seconds
    @Scheduled(every = "1s")
    void produceMessage() {
        for (int i = 0; i < messagesPerSecond; i++) {
            producer.send(randomGenerator.nextLong(), message);
        }
    }
}
