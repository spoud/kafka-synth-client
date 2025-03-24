package io.spoud.kafka;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.Node;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;

@Liveness
public class KafkaHealthCheck implements HealthCheck {

  @Inject
  AdminClient adminClient;

  @PreDestroy
  void stop() {
    adminClient.close();
  }

  @Override
  public HealthCheckResponse call() {
    HealthCheckResponseBuilder builder =
        HealthCheckResponse.named("Kafka connection health check").up();
    try {
      StringBuilder nodes = new StringBuilder();
      for (Node node : adminClient.describeCluster().nodes().get()) {
        if (nodes.length() > 0) {
          nodes.append(',');
        }
        nodes.append(node.host()).append(':').append(node.port());
      }
      return builder.withData("nodes", nodes.toString()).build();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return builder.down().withData("reason", e.getMessage()).build();
    } catch (Exception e) {
      return builder.down().withData("reason", e.getMessage()).build();
    }
  }
}
