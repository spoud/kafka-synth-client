package io.spoud;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
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

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
@TestProfile(IgnoreRecordsTestProfile.class)
public class KafkaSynthClientRecordIgnoreTest {
    @InjectKafkaCompanion
    KafkaCompanion kafkaCompanion;

    @Inject
    SynthClientConfig config;

    @Inject
    ConsumerLifecycle lifecycle;

    @Test
    @DisplayName("End-to-end latency is not recorded after first n messages")
    public void testEndToEndLatencyNotRecorded() {
        kafkaCompanion.consumeWithDeserializers(ByteArrayDeserializer.class)
                .fromTopics(config.topic(), 3)
                .awaitCompletion(Duration.ofMillis(5000));

        var metrics = RestAssured.get("/q/metrics").asString();

        assertThat(metrics).doesNotContain("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",quantile=\"0.5\"}");
        assertThat(metrics).doesNotContain("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",quantile=\"0.9\"}");
        assertThat(metrics).doesNotContain("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",quantile=\"0.95\"}");
        assertThat(metrics).doesNotContain("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",quantile=\"0.99\"}");

        // ...but eventually they should appear
        await().atMost(Duration.ofMillis(2000))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() ->
                        assertThat(RestAssured.get("/q/metrics").asString()).contains("synth_client_e2e_latency_ms{broker=\"0\",fromRack=\"dc1\",partition=\"0\",toRack=\"dc1\",quantile=\"0.5\"}")
                );

        lifecycle.shutdown();
    }
}
