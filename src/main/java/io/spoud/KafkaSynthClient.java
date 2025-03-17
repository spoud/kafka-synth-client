package io.spoud;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.spoud.config.SynthClientConfig;
import io.spoud.kafka.MessageProducer;
import io.spoud.kafka.PartitionRebalancer;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.errors.TopicExistsException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;

@ApplicationScoped
public class KafkaSynthClient {
    private final RandomGenerator randomGenerator = RandomGenerator.getDefault();
    private final byte[] message;
    private final MessageProducer producer;
    private final int messagesPerSecond;
    private final AdminClient adminClient;
    private final SynthClientConfig config;
    private AtomicBoolean waitForTopicCreated = new AtomicBoolean(true);

    public KafkaSynthClient(
            SynthClientConfig config,
            PartitionRebalancer partitionRebalancer,
            MeterRegistry meterRegistry,
            MessageProducer producer,
            AdminClient adminClient) {
        this.config = config;
        this.message = new byte[config.messages().messageSizeBytes()];
        this.producer = producer;
        this.messagesPerSecond = Math.max(config.messages().messagesPerSecond(), 1);
        this.adminClient = adminClient;
    }

    public void start(@Observes  @Priority(Interceptor.Priority.APPLICATION - 1)  StartupEvent event) {
        if (config.autoCreateTopic()) {
            Log.infof("Creating topic %s", config.topic());
            this.adminClient.listTopics().names().whenComplete((topics, throwable) -> {
                if (throwable != null) {
                    throw new RuntimeException("Failed to list topics", throwable);
                }
                if (!topics.contains(config.topic())) {
                    adminClient.describeCluster().nodes().whenComplete((nodes, t) -> {
                        if (t != null) {
                            throw new RuntimeException("Failed to get nodes", t);
                        }
                        this.adminClient.createTopics(List.of(new NewTopic(config.topic(), nodes.size(), (short) nodes.size()).configs(
                                Map.of(TopicConfig.RETENTION_MS_CONFIG, "3600000")
                        ))).all().whenComplete((v, t1) -> {
                            if (t1 != null) {
                                // if there is auto.create concurrently happening, we might get a TopicExistsException
                                if(t1 instanceof TopicExistsException) {
                                    waitForTopicCreated.set(false);
                                    Log.infof("Topic already exists %s", config.topic());
                                } else {
                                    throw new RuntimeException("Failed to create topic", t1);
                                }
                            } else {
                                Log.infof("Topic created %s", config.topic());
                                waitForTopicCreated.set(false);
                            }
                        });
                    });
                } else {
                    waitForToicCreated.set(false);
                    Log.infof("Topic already exists %s", config.topic());
                }
            });
        } else {
            waitForToicCreated.set(false);
        }
    }

    // TODO this sounds sketchy, we have no guarantee that the scheduler will run every seconds
    @Scheduled(every = "1s")
    void produceMessage() {
        if (waitForToicCreated.get()) {
            return;
        }
        for (int i = 0; i < messagesPerSecond; i++) {
            producer.send(randomGenerator.nextLong(), message);
        }
    }
}
