package io.spoud.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.KafkaAdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.LongSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class KafkaFactory {

    @Inject
    @ConfigProperty(name = "kafka")
    Map<String, String> config;

    @Produces
    AdminClient getAdmin() {
        return KafkaAdminClient.create(getKafkaConfig(AdminClientConfig.configNames()));
    }

    public KafkaConsumer<Long, byte[]> createConsumer() {
        Map<String, Object> config = getKafkaConfig(ConsumerConfig.configNames());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new KafkaConsumer<>(config);
    }

    public KafkaProducer<Long, byte[]> createProducer() {
        Map<String, Object> config = getKafkaConfig(ProducerConfig.configNames());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, LongSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaProducer<>(config);
    }

    Map<String, Object> getKafkaConfig(Set<String> keys) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, String> entry : config.entrySet()) {
            if (keys.contains(entry.getKey())) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        return copy;
    }


}
