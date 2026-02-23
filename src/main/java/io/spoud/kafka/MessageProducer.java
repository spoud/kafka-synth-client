package io.spoud.kafka;

import io.micrometer.core.instrument.Tags;
import io.quarkus.logging.Log;
import io.spoud.MetricService;
import io.spoud.TimeService;
import io.spoud.config.SynthClientConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.MetricName;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static io.spoud.MetricService.TAG_RACK;

@Liveness
@Default
@ApplicationScoped
public class MessageProducer implements HealthCheck {
    public static final String PRODUCE_ERROR_RATE_METER_NAME = "synth-client.producer.error-rate";

    private final KafkaFactory kafkaFactory;
    private final MetricService metricService;
    private final TimeService timeService;
    private final SynthClientConfig config;
    private final AtomicReference<Instant> lastMessage = new AtomicReference<>(Instant.now());
    private final String clientId;
    private KafkaProducer<Long, byte[]> producer;

    public static final String HEADER_RACK = "rack";

    public MessageProducer(KafkaFactory kafkaFactory, SynthClientConfig config,
                           MetricService metricService, TimeService timeService,
                           @ConfigProperty(name = "kafka.client.id") String kafkaClientId) {
        this.kafkaFactory = kafkaFactory;
        this.config = config;
        this.metricService = metricService;
        this.timeService = timeService;
        this.clientId = kafkaClientId;
        producer = kafkaFactory.createProducer();
        metricService.addGauge(PRODUCE_ERROR_RATE_METER_NAME, Tags.of(TAG_RACK, config.rack()), this, MessageProducer::getSendErrorRate);
    }

    public double getSendErrorRate() {
        var metricName = new MetricName("record-error-rate",
                "producer-metrics",
                "The average per-second number of record sends that resulted in errors",
                Map.of("client-id", clientId));
        return Optional.of(producer)
                .map(KafkaProducer::metrics)
                .map(m -> m.get(metricName))
                .stream()
                .mapToDouble((m) -> (double)m.metricValue())
                .findFirst()
                .orElseGet(() -> {
                    Log.warn("send-error-rate metric not available");
                    return -1.0;
                });
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
                metricService.recordProducedFailure();
            } else {
                Instant ack = Instant.now();
                lastMessage.set(ack);
                metricService.recordAckLatency(metadata.topic(), metadata.partition(), Duration.between(send, ack));
                metricService.recordProducedSuccess();
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
