package io.spoud.kafka;

import io.quarkus.logging.Log;
import io.spoud.MetricService;
import io.spoud.TimeService;
import io.spoud.config.SynthClientConfig;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.time.Duration;
import java.time.Instant;

@ApplicationScoped
public class MessageProducer {
    private final KafkaFactory kafkaFactory;
    private final MetricService metricService;
    private final TimeService timeService;
    private final SynthClientConfig config;
    private KafkaProducer<Long, byte[]> producer;

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
        // TODO metric for ACK time
        Instant send = Instant.now();
        var record = new ProducerRecord<>(config.topic(), null, timeService.currentTimeMillis(), key, value);
        producer.send(record, (metadata, exception) -> {
            if (exception != null) {
                Log.error("Failed to send message", exception);
            } else {
                Instant ack = Instant.now();
                metricService.recordAckLatency(metadata.topic(), metadata.partition(), Duration.between(send, ack));
            }
        });
    }
}
