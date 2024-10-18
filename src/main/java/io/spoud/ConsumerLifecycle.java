package io.spoud;

import io.micrometer.core.instrument.util.NamedThreadFactory;
import io.quarkus.runtime.Shutdown;
import io.quarkus.runtime.Startup;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.KafkaFactory;
import io.spoud.kafka.MessageConsumer;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

@ApplicationScoped
public class ConsumerLifecycle {
    private final ExecutorService executorService;
    private final List<MessageConsumer> consumers;

    public ConsumerLifecycle(SynthClientConfig config, KafkaFactory kafkaFactory, MetricService metricService) {
        this.executorService = Executors.newFixedThreadPool(config.consumersCount(), new NamedThreadFactory("kafka-consumer"));
        this.consumers = IntStream.range(0, config.consumersCount())
                .mapToObj(i -> new MessageConsumer(kafkaFactory, config, metricService))
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
}
