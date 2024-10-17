package io.spoud;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PartitionRebalancer {
    @ConfigProperty(name = "mp.messaging.incoming.msg-in.topic")
    String topic;

    @Inject
    AdminClient adminClient;

    @Inject
    WrappedKafkaProducer producer;

    boolean refreshPartitionsEnabled = true;

    NewPartitionReassignment genReassignment(Node leader, int replicationFactor, Collection<Node> nodes) {
        var replicaCandidates = nodes.stream()
                .filter(n -> n.id() != leader.id())
                .collect(ArrayList<Node>::new, ArrayList::add, ArrayList::addAll);
        Collections.shuffle(replicaCandidates);
        var replicaIds = new ArrayList<Integer>();
        replicaIds.add(leader.id());
        // add some random brokers as replicas
        for (var i = 0; i < replicationFactor - 1; i++) {
            replicaIds.add(replicaCandidates.get(i).id());
        }
        return new NewPartitionReassignment(replicaIds);
    }

    int getTopicReplicationFactor(TopicDescription topicDescription) {
        return topicDescription.partitions().get(0).replicas().size();
    }

    public String getTopic() {
        return topic;
    }

    public Map<Integer, List<Integer>> getPartitionsByBroker() {
        return new HashMap<>(partitionsByBroker); // return a copy
    }

    Map<Integer, List<Integer>> partitionsByBroker = new ConcurrentHashMap<>();

    void reassignPartitionsToBrokers(TopicDescription topicDescription, Collection<Node> nodes) {
        // build a map of broker -> list of partitions
        var brokers = new ArrayList<>(nodes);
        partitionsByBroker = new HashMap<>();
        for (var partition : topicDescription.partitions()) {
            var leader = partition.leader();
            if (leader == null) {
                Log.warn("Partition " + partition.partition() + " has no leader");
                continue;
            }
            partitionsByBroker.computeIfAbsent(leader.id(), (k) -> new ArrayList<>()).add(partition.partition());
        }
        // print partitions by broker
        for (var entry : partitionsByBroker.entrySet()) {
            Log.info("Broker " + entry.getKey() + " has partitions " + entry.getValue());
        }
        // sort the brokers by the number of partitions they are a leader for
        brokers.sort(Comparator.comparingLong(n -> partitionsByBroker.computeIfAbsent(n.id(), (k) -> new ArrayList<>()).size()));
        var i = 0;
        var j = brokers.size() - 1;
        if (partitionsByBroker.get(brokers.get(i).id()).size() > 0) {
            // each broker has at least one partition, nothing to do
            Log.debug("Each broker has at least one partition. Will not reassign partitions");
            return;
        }
        while (i < j) {
            var poorBroker = brokers.get(i);
            var richBroker = brokers.get(j);
            if (partitionsByBroker.get(poorBroker.id()).size() > 0) {
                // the poorest broker has at least one partition, meaning that each broker has at least one partition
                break;
            }
            var partitionToGive = partitionsByBroker.get(richBroker.id()).remove(0);
            partitionsByBroker.get(poorBroker.id()).add(partitionToGive);
            var tp = new TopicPartition(topic, partitionToGive);

            var assignment = genReassignment(poorBroker, getTopicReplicationFactor(topicDescription), nodes);
            try {
                adminClient.alterPartitionReassignments(Map.of(tp, Optional.of(assignment))).all().getNow(null);
            } catch (Exception e) {
                Log.error("Failed to reassign partition " + partitionToGive + " to broker " + poorBroker.id(), e);
                break;
            }

            if (partitionsByBroker.get(richBroker.id()).size() < 2) {
                j--;
            }
            i++;
        }
        for (var entry : partitionsByBroker.entrySet()) {
            Log.info("New assignment: Broker " + entry.getKey() + " has partitions " + entry.getValue());
        }
        producer.recreateProducer(); // recreate the producer to make sure that it is aware of the new partitions
    }

    @PostConstruct
    void init() {
        refreshPartitions();
    }

    void refreshPartitionsFallback() {
        Log.error("Failed to refresh partitions after 3 retries, disabling rebalancing feature");
        refreshPartitionsEnabled = false;
    }

    @Fallback(fallbackMethod = "refreshPartitionsFallback")
    @ExponentialBackoff(maxDelay = 30000)
    @Retry(maxRetries = 3)
    @Scheduled(every = "60s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refreshPartitions() {
        if (!refreshPartitionsEnabled) {
            return;
        }
        Log.info("Rebalancing partitions to make sure that the E2E test hits each node");
        // figure out how many brokers there are
        var cluster = adminClient.describeCluster();
        cluster.nodes().whenComplete((nodes, throwable) -> {
            if (throwable != null) {
                Log.error("Failed to get cluster nodes", throwable);
                return;
            }
            Log.info("Cluster has " + nodes.size() + " nodes");
            // make sure that the topic has at least as many partitions as there are brokers
            var description = adminClient.describeTopics(List.of(topic));
            description.allTopicNames().whenComplete((topics, throwable1) -> {
                if (throwable1 != null) {
                    Log.error("Failed to get topic names", throwable1);
                    return;
                }
                var topicDescr = topics.get(topic);
                Log.info("Topic " + topic + " has " + topicDescr.partitions().size() + " partitions");
                if (topicDescr.partitions().size() < nodes.size()) {
                    var partitionsToCreate = nodes.size() - topicDescr.partitions().size();
                    Log.info("Will create " + partitionsToCreate + " additional partitions");
                    try {
                        adminClient.createPartitions(Map.of(topic, NewPartitions.increaseTo(nodes.size()))).all().getNow(null);
                    } catch (Exception e) {
                        Log.error("Failed to create partitions", e);
                    }
                }

                // make sure that each broker is a leader for at least one partition
                reassignPartitionsToBrokers(topicDescr, nodes);
            });
        });
    }
}
