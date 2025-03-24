package io.spoud.kafka;

import io.quarkus.logging.Log;
import io.spoud.MetricService;
import io.spoud.TimeService;
import io.spoud.config.SynthClientConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import jakarta.inject.Qualifier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

@Liveness
@Default
@ApplicationScoped
public class MessageProducer implements HealthCheck {
    private final KafkaFactory kafkaFactory;
    private final MetricService metricService;
    private final TimeService timeService;
    private final SynthClientConfig config;
    private final AtomicReference<Instant> lastMessage = new AtomicReference<>(Instant.now());
    private KafkaProducer<Long, byte[]> producer;

    public static final String HEADER_RACK = "rack";

    public MessageProducer(KafkaFactory kafkaFactory, SynthClientConfig config,
                           MetricService metricService, TimeService timeService) {
        this.kafkaFactory = kafkaFactory;
        this.config = config;
        this.metricService = metricService;
        this.timeService = timeService;
        producer = kafkaFactory.createProducer();
    }

    public void recreateProducer() {
        Log.info("Recreating Kafka producer");
        var oldProducer = producer;
        producer = kafkaFactory.createProducer();
        oldProducer.close();
    }

    public void send(Long key, byte[] value) {
        Instant send = Instant.now();
        var record = new ProducerRecord<>(config.topic(), null, timeService.currentTimeMillis(), key, value);
        record.headers().add(HEADER_RACK, config.rack().getBytes());
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                Log.error("Failed to send message", exception);
            } else {
                Instant ack = Instant.now();
                lastMessage.set(ack);
                metricService.recordAckLatency(metadata.topic(), metadata.partition(), Duration.between(send, ack));
            }
        });
    }

    @Override
    public HealthCheckResponse call() {
        return lastMessage.get().isAfter(Instant.now().minus(1, ChronoUnit.MINUTES))
                ? HealthCheckResponse.up("Producer is running")
                : HealthCheckResponse.down("Producer is not running");
    }
}
