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
import org.apache.kafka.common.config.ConfigResource;
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
    private final AtomicBoolean waitForTopicCreated = new AtomicBoolean(true);

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
            adminClient.describeCluster().nodes().whenComplete((nodes, t) -> {
                Log.debugf("Nodes %s", nodes);
                if (t != null) {
                    throw new RuntimeException("Failed to get nodes", t);
                }
                short replicationFactor = (short) Math.max(Math.min(config.topicReplicationFactor(), nodes.size()), 1);
                Log.debugf("ReplicationFactor calculated %s", replicationFactor);
                if(config.topicReplicationFactor() <= 0) {
                    Log.infof("Replication factor set to %s assuming number of nodes %s", config.topicReplicationFactor(), nodes.size());
                    replicationFactor = (short) nodes.size();
                }
                short finalReplicationFactor = replicationFactor;
                this.adminClient.listTopics().names().whenComplete((topics, throwable) -> {
                    if (throwable != null) {
                        throw new RuntimeException("Failed to list topics", throwable);
                    }
                    if (!topics.contains(config.topic())) {

                            int minInSyncReplicas = finalReplicationFactor -1;
                            this.adminClient.createTopics(List.of(new NewTopic(config.topic(), nodes.size(), finalReplicationFactor).configs(
                                    Map.of(TopicConfig.RETENTION_MS_CONFIG, "3600000", TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(minInSyncReplicas))
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
                    } else {
                        waitForTopicCreated.set(false);
                        Log.infof("Topic already exists %s", config.topic());
                        // check replication factor and min in sync replicas config for topic
                        adminClient.describeTopics(List.of(config.topic())).allTopicNames().whenComplete((v, t1) -> {
                            if (t1 != null) {
                                throw new RuntimeException("Failed to describe topic", t1);
                            }
                            var replicationFactorConfig = v.get(config.topic()).partitions().getFirst().replicas().size();
                            if( replicationFactorConfig != finalReplicationFactor) {
                                Log.errorf("Replication factor for topic %s is %s, expected %s", config.topic(), replicationFactorConfig, finalReplicationFactor);
                            }

                        });

                        ConfigResource cr = new ConfigResource(ConfigResource.Type.TOPIC, config.topic());
                        adminClient.describeConfigs(List.of(cr)).all().whenComplete((configEntries, t1) -> {
                            if (t1 != null) {
                                throw new RuntimeException("Failed to get topic config", t1);
                            }
                            var minInSyncReplicasConfig = configEntries.get(cr).get(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG);
                            if(minInSyncReplicasConfig == null || Integer.parseInt(minInSyncReplicasConfig.value()) != finalReplicationFactor -1) {
                                Log.errorf("Min in sync replicas for topic %s is %s, expected %s", config.topic(), minInSyncReplicasConfig, finalReplicationFactor -1);
                            }
                        });

                    }
                });
            });
        } else {
            waitForTopicCreated.set(false);
        }
    }

    // TODO this sounds sketchy, we have no guarantee that the scheduler will run every seconds
    @Scheduled(every = "1s")
    void produceMessage() {
        if (waitForTopicCreated.get()) {
            return;
        }
        for (int i = 0; i < messagesPerSecond; i++) {
            producer.send(randomGenerator.nextLong(), message);
        }
    }
}
