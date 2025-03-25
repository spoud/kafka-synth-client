package io.spoud;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
public class HealthCheckTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("Health check reports OK")
    public void testEndToEndLatencyRecorded() throws IOException {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(RestAssured.get("/q/health").statusCode()).isEqualTo(200));
        var healthCheckStr = RestAssured.get("/q/health").asString();
        var healthCheck = MAPPER.readTree(healthCheckStr);
        assertThat(healthCheck.get("status").asText()).isEqualTo("UP");
        assertThat(healthCheck.get("checks").isArray()).isTrue();
        assertThat(healthCheck.get("checks").size()).isGreaterThan(1);
        List<JsonNode> healthChecks = new ArrayList<>();
        for (JsonNode check : healthCheck.get("checks")) {
            healthChecks.add(check);
        }
        assertThat(healthChecks).extracting((n) -> n.get("status").asText()).containsOnly("UP");
        assertThat(healthChecks).extracting((n) -> n.get("name").asText()).containsOnlyOnce("Producer is running", "Consumers running");
    }
}
