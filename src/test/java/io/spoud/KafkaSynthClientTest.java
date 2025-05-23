package io.spoud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.restassured.RestAssured;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.PartitionRebalancer;
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
@TestProfile(FirstSampleWindow1TestProfile.class)
public class KafkaSynthClientTest {
    @InjectKafkaCompanion
    KafkaCompanion kafkaCompanion;

    @Inject
    PartitionRebalancer partitionRebalancer;

    @Inject
    SynthClientConfig config;

    @Inject
    ConsumerLifecycle lifecycle;

    @Test
    @DisplayName("Broker->Partition mapping is successfully generated")
    public void testPartitionBrokerMappingGenerated() {
        await().atMost(Duration.ofSeconds(5)).until(() -> !partitionRebalancer.getPartitionsByBroker().isEmpty());

        var mapping = partitionRebalancer.getPartitionsByBroker();

        // we have only one broker in the test environment, so we expect the topic's one partition to be assigned to broker 0
        assertTrue(mapping.containsKey(0));
        assertEquals(1, mapping.get(0).size());
        assertEquals(0, mapping.get(0).getFirst());
    }

    @Test
    @DisplayName("End-to-end latency metrics are recorded")
    public void testEndToEndLatencyRecorded() {
        kafkaCompanion.consumeWithDeserializers(ByteArrayDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.5\"}");
        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.9\"}");
        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.95\"}");
        assertThat(metrics).contains("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.99\"}");

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Send-error-rate metrics are recorded")
    public void testSendErrorRateRecorded() {
        kafkaCompanion.consumeWithDeserializers(ByteArrayDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).contains("synth_client_producer_error_rate{rack=\"dc1\"} 0.0");

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Ack latency metrics are recorded")
    public void testAckLatencyRecorded() {
        kafkaCompanion.consumeWithDeserializers(ByteArrayDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).contains("synth_client_ack_latency_ms{broker=\"0\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.5\"}");
        assertThat(metrics).contains("synth_client_ack_latency_ms{broker=\"0\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.9\"}");
        assertThat(metrics).contains("synth_client_ack_latency_ms{broker=\"0\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.95\"}");
        assertThat(metrics).contains("synth_client_ack_latency_ms{broker=\"0\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.99\"}");

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Producer error rate is recorded")
    public void testProducerErrorRateRecorded() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var metrics = RestAssured.get("/q/metrics").asString();
            assertThat(metrics).contains("synth_client_producer_error_rate{rack=\"dc1\"}");
            // -1 signifies that the metric could not be retrieved
            assertThat(metrics).doesNotContain("synth_client_producer_error_rate{rack=\"dc1\"} -1");
        });
    }

    @Test
    @DisplayName("Time since last consumption recorded")
    public void testTimeSinceLastConsumptionRecorded() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var metrics = RestAssured.get("/q/metrics").asString();
            assertThat(metrics).contains("synth_client_time_since_last_consumption_seconds{rack=\"dc1\"}");
        });
    }
}
