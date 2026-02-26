package io.spoud;

import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.KafkaFactory;
import io.spoud.kafka.MessageConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Default;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

@Liveness
@Default
@ApplicationScoped
public class ConsumerLifecycle implements HealthCheck {
    private final ExecutorService executorService;
    private final List<MessageConsumer> consumers;

    public ConsumerLifecycle(SynthClientConfig config,
                             KafkaFactory kafkaFactory,
                             MetricService metricService,
                             AdvertisedListenerRepository advertisedListenerRepository,
                             TimeService timeService) {
        this.executorService = Executors.newFixedThreadPool(config.consumersCount(), new NamedThreadFactory("kafka-consumer"));
        this.consumers = IntStream.range(0, config.consumersCount())
                .mapToObj(i -> new MessageConsumer(i, kafkaFactory, config, metricService, timeService, advertisedListenerRepository))
                .toList();
    }

    @Startup
    void start() {
        consumers.forEach(executorService::submit);
    }

    @Shutdown
    void shutdown() {
        consumers.forEach(MessageConsumer::close);
        executorService.shutdown();
    }

    @Override
    public HealthCheckResponse call() {
        List<HealthCheckResponse> checks = consumers.stream().map(MessageConsumer::call).toList();
        HashMap<String, Object> data = new HashMap<>();
        HealthCheckResponse.Status overallStatus = HealthCheckResponse.Status.UP;
        for (HealthCheckResponse check : checks) {
            if (check.getStatus() == HealthCheckResponse.Status.DOWN) {
                overallStatus = HealthCheckResponse.Status.DOWN;
            }
            data.put(check.getName(), String.format("status: %s, data: %s", check.getStatus(), check.getData()));
        }
        return new HealthCheckResponse("Consumers running", overallStatus, Optional.of(data));

    }
}
