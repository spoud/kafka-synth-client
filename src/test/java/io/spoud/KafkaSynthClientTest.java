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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
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

    @Inject
    KafkaSynthClient kafkaSynthClient;

    @Inject
    CommandService commandService;

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
        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
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
        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).contains("synth_client_producer_error_rate{rack=\"dc1\"} 0.0");

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Ack latency metrics are recorded")
    public void testAckLatencyRecorded() {
        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
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

    @Test
    @DisplayName("Records produced counter is recorded")
    public void testRecordsProducedCounterRecorded() {
        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var metrics = RestAssured.get("/q/metrics").asString();
            assertThat(metrics).contains("synth_client_producer_records_produced_total{rack=\"dc1\"}");
            assertThat(metrics).doesNotContain("synth_client_producer_records_produced_total{rack=\"dc1\"} 0.0");
        });

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Records failed counter is recorded")
    public void testRecordsFailedCounterRecorded() {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var metrics = RestAssured.get("/q/metrics").asString();
            assertThat(metrics).contains("synth_client_producer_records_failed_total{rack=\"dc1\"}");
        });
    }

    @Test
    @DisplayName("Payload size is configurable at runtime")
    public void testPayloadSizeIsReconfigured() {
        var res = kafkaCompanion.<Long, String>consumeWithDeserializers(LongDeserializer.class, StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));
        for (ConsumerRecord<Long, String> record : res.getRecords()) {
            assertThat(record.value().length()).isEqualTo(8); // 8 is the default payload size
        };
        // now let's test that the payload size is reconfigured
        kafkaSynthClient.setPayloadSize(10);
        res = kafkaCompanion.<Long, String>consumeWithDeserializers(LongDeserializer.class, StringDeserializer.class)
                .fromTopics(config.topic(), 50)
                .awaitCompletion(Duration.ofSeconds(10));
        // we expect the payload size to change to 10 at some point (perhaps not immediately)
        var maxLength = res.getRecords().stream().mapToLong(r -> r.value().length()).max()
                .orElseThrow();
        assertThat(maxLength).isEqualTo(10);
    }

    @Test
    @DisplayName("Command reconfigures payload size")
    public void testCommandReconfiguresPayloadSize() {
        var res = kafkaCompanion.<Long, String>consumeWithDeserializers(LongDeserializer.class, StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(Duration.ofSeconds(5));
        for (ConsumerRecord<Long, String> record : res.getRecords()) {
            assertThat(record.value().length()).isEqualTo(8); // 8 is the default payload size
        };
        // now let's issue a command to change the payload size and ensure that it propagates
        commandService.issueCommand(new CommandService.Command(new CommandService.AdjustPayloadSizeCommand(10)));
        res = kafkaCompanion.<Long, String>consumeWithDeserializers(LongDeserializer.class, StringDeserializer.class)
                .fromTopics(config.topic(), 50)
                .awaitCompletion(Duration.ofSeconds(10));
        // we expect the payload size to change to 10 at some point (perhaps not immediately due to this being an asynchronous operation)
        var maxLength = res.getRecords().stream().mapToLong(r -> r.value().length()).max()
                .orElseThrow();
        assertThat(maxLength).isEqualTo(10);
    }
}
