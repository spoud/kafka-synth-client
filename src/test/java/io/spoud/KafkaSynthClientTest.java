package io.spoud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.restassured.RestAssured;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import io.spoud.config.SynthClientConfig;
import jakarta.inject.Inject;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
public class KafkaSynthClientTest {
    @InjectKafkaCompanion
    KafkaCompanion kafkaCompanion;

    @Inject
    PartitionRebalancer partitionRebalancer;

    @Inject
    SynthClientConfig config;

    @Test
    @DisplayName("Broker->Partition mapping is successfully generated")
    public void testPartitionBrokerMappingGenerated() {
        await().atMost(Duration.ofSeconds(5)).until(() -> partitionRebalancer.getPartitionsByBroker().size() > 0);

        var mapping = partitionRebalancer.getPartitionsByBroker();

        // we have only one broker in the test environment, so we expect the topic's one partition to be assigned to broker 0
        assertTrue(mapping.containsKey(0));
        assertEquals(1, mapping.get(0).size());
        assertEquals(0, mapping.get(0).get(0));
    }

    @Test
    @DisplayName("End-to-end latency metrics are recorded")
    public void testEndToEndLatencyRecorded() {
        kafkaCompanion.consumeWithDeserializers(ByteArrayDeserializer.class)
                .fromTopics(config.topic(), 3)
                .awaitCompletion(Duration.ofSeconds(5));

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",partition=\"0\",quantile=\"0.5\"}");
        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",partition=\"0\",quantile=\"0.5\"}");
        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",partition=\"0\",quantile=\"0.95\"}");
        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",partition=\"0\",quantile=\"0.99\"}");
    }
}
