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
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
@TestProfile(FirstSampleWindow1TestProfile.class)
public class KafkaSynthClientTest {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

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
        var brokerId = awaitBrokerIdForPartition(0);

        var mapping = partitionRebalancer.getPartitionsByBroker();

        assertThat(mapping).containsKey(brokerId);
        assertThat(mapping.get(brokerId)).containsExactly(0);
    }

    @Test
    @DisplayName("End-to-end latency metrics are recorded")
    public void testEndToEndLatencyRecorded() {
        var brokerId = awaitBrokerIdForPartition(0);

        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(DEFAULT_TIMEOUT);

        awaitMetricsContain(
                "synth_client_e2e_latency_ms{broker=\"" + brokerId + "\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.5\"}",
                "synth_client_e2e_latency_ms{broker=\"" + brokerId + "\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.9\"}",
                "synth_client_e2e_latency_ms{broker=\"" + brokerId + "\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.95\"}",
                "synth_client_e2e_latency_ms{broker=\"" + brokerId + "\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.99\"}"
        );

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Send-error-rate metrics are recorded")
    public void testSendErrorRateRecorded() {
        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(DEFAULT_TIMEOUT);

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).contains("synth_client_producer_error_rate{rack=\"dc1\"} 0.0");

        lifecycle.shutdown();
    }

    @Test
    @DisplayName("Ack latency metrics are recorded")
    public void testAckLatencyRecorded() {
        var brokerId = awaitBrokerIdForPartition(0);

        kafkaCompanion.consumeWithDeserializers(StringDeserializer.class)
                .fromTopics(config.topic(), 30)
                .awaitCompletion(DEFAULT_TIMEOUT);

        awaitMetricsContain(
                "synth_client_ack_latency_ms{broker=\"" + brokerId + "\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.5\"}",
                "synth_client_ack_latency_ms{broker=\"" + brokerId + "\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.9\"}",
                "synth_client_ack_latency_ms{broker=\"" + brokerId + "\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.95\"}",
                "synth_client_ack_latency_ms{broker=\"" + brokerId + "\",partition=\"0\",rack=\"dc1\",topic=\"demo.prod.app.kafka-synth.messages\",viaBrokerRack=\"unknown\",quantile=\"0.99\"}"
        );

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
        kafkaSynthClient.setPayloadSize(8);
        assertThat(kafkaSynthClient.getPayloadSize()).isEqualTo(8);
        // now let's test that the payload size is reconfigured
        kafkaSynthClient.setPayloadSize(10);
        assertThat(kafkaSynthClient.getPayloadSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("Command reconfigures payload size")
    public void testCommandReconfiguresPayloadSize() {
        kafkaSynthClient.setPayloadSize(8);
        assertThat(kafkaSynthClient.getPayloadSize()).isEqualTo(8);
        commandService.maybeHandleCommand("{\"adjustPayloadSize\":{\"newSize\":10}}");
        assertThat(kafkaSynthClient.getPayloadSize()).isEqualTo(10);
    }

    private int awaitBrokerIdForPartition(int partition) {
        await().atMost(DEFAULT_TIMEOUT).until(() -> partitionRebalancer.getBrokerIdForPartition(partition).isPresent());
        return partitionRebalancer.getBrokerIdForPartition(partition).orElseThrow();
    }

    private void awaitMetricsContain(String... expectedMetrics) {
        await().atMost(DEFAULT_TIMEOUT).untilAsserted(() -> {
            var metrics = RestAssured.get("/q/metrics").asString();
            for (var expectedMetric : expectedMetrics) {
                assertThat(metrics).contains(expectedMetric);
            }
        });
    }

}
