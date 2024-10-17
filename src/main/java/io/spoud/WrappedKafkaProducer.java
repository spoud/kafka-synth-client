package io.spoud;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Map;

@ApplicationScoped
public class WrappedKafkaProducer {
    private KafkaProducer<Long, byte[]> producer;
    private final Map<String, Object> config;

    public WrappedKafkaProducer(@Identifier("default-kafka-broker") Map<String, Object> config) {
        this.config = config;
        producer = new KafkaProducer<>(config);
    }

    public void recreateProducer() {
        Log.info("Recreating Kafka producer");
        var oldProducer = producer;
        producer = new KafkaProducer<>(config); // don't wait for the old producer to close, continue with the new one, while the old one is closing
        oldProducer.close();
    }

    public void send(ProducerRecord<Long, byte[]> record) {
        producer.send(record);
    }
}
