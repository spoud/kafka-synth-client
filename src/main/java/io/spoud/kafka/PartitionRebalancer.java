package io.spoud.kafka;

import io.quarkus.logging.Log;
import io.quarkus.scheduler.Scheduled;
import io.smallrye.faulttolerance.api.ExponentialBackoff;
import io.spoud.config.SynthClientConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@ApplicationScoped
public class PartitionRebalancer {

    @Inject
    SynthClientConfig config;

    @Inject
    AdminClient adminClient;

    @Inject
    MessageProducer producer;

    boolean refreshPartitionsEnabled = true;
    AtomicBoolean initialRefreshDone = new AtomicBoolean(false);
    Map<Integer, List<Integer>> partitionsByBroker = new ConcurrentHashMap<>();
    Map<Integer, String> rackByPartition = new ConcurrentHashMap<>();

    public boolean isInitialRefreshDone() {
        return initialRefreshDone.get();
    }

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

    // TODO what about the topic ?
    public Optional<Integer> getBrokerIdForPartition(String topic, int partition) {
        for (var entry : getPartitionsByBroker().entrySet()) {
            var brokerId = entry.getKey();
            var partitions = entry.getValue();
            if (partitions.contains(partition)) {
                return Optional.of(brokerId);
            }
        }

        return Optional.empty();
    }

    public String getRackOfPartitionLeader(int partition) {
        return rackByPartition.getOrDefault(partition, "unknown");
    }

    private void recalculateRacksByPartition() {
        try {
            adminClient.describeCluster()
                .nodes().whenComplete(
                    (nodes, throwable) -> {
                        if (throwable != null) {
                            Log.error("Failed to get nodes", throwable);
                            throw new RuntimeException("Failed to get nodes", throwable);
                        }
                        var nodeRacks = nodes.stream().collect(Collectors.toMap(Node::id, n -> Optional.ofNullable(n.rack())));
                        for (var entry : partitionsByBroker.entrySet()) {
                            var broker = entry.getKey();
                            var partitions = entry.getValue();
                            var rack = nodeRacks.get(broker).orElse("unknown");
                            for (var partition : partitions) {
                                rackByPartition.put(partition, rack);
                            }
                        }
                    }
                );
        } catch (Exception e) {
            Log.warn("Failed to get racks of partition leaders.", e);
        }
    }

    int getTopicReplicationFactor(TopicDescription topicDescription) {
        return topicDescription.partitions().get(0).replicas().size();
    }

    public Map<Integer, List<Integer>> getPartitionsByBroker() {
        return Collections.unmodifiableMap(partitionsByBroker);
    }

    void reassignPartitionsToBrokers(TopicDescription topicDescription, Collection<Node> nodes) {
        // build a map of broker -> list of partitions
        var brokers = new ArrayList<>(nodes);
        partitionsByBroker = new ConcurrentHashMap<>();
        for (var partition : topicDescription.partitions()) {
            var leader = partition.leader();
            if (leader == null) {
                Log.warnv("Partition {0} has no leader", partition.partition());
                continue;
            }
            partitionsByBroker.computeIfAbsent(leader.id(), (k) -> new ArrayList<>()).add(partition.partition());
        }
        // print partitions by broker
        for (var entry : partitionsByBroker.entrySet()) {
            Log.infov("Broker {0} has partitions {1}", entry.getKey(), entry.getValue());
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
            var tp = new TopicPartition(config.topic(), partitionToGive);

            var assignment = genReassignment(poorBroker, getTopicReplicationFactor(topicDescription), nodes);
            try {
                adminClient.alterPartitionReassignments(Map.of(tp, Optional.of(assignment))).all().getNow(null);
            } catch (Exception e) {
                Log.errorv(e, "Failed to reassign partition {0} to broker {1}", partitionToGive, poorBroker.id());
                break;
            }

            if (partitionsByBroker.get(richBroker.id()).size() < 2) {
                j--;
            }
            i++;
        }
        for (var entry : partitionsByBroker.entrySet()) {
            Log.infov("New assignment: Broker {0} has partitions {1}", entry.getKey(), entry.getValue());
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
        initialRefreshDone.set(true);
    }

    @Fallback(fallbackMethod = "refreshPartitionsFallback")
    @ExponentialBackoff(maxDelay = 30000)
    @Retry(maxRetries = 3)
    @Scheduled(every = "60s", delay = 5L, delayUnit = TimeUnit.SECONDS, concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
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
                throw new RuntimeException("Failed to get cluster nodes", throwable);
            }
            Log.info("Cluster has " + nodes.size() + " nodes");
            // make sure that the topic has at least as many partitions as there are brokers
            var description = adminClient.describeTopics(List.of(config.topic()));
            description.allTopicNames().whenComplete((topics, throwable1) -> {
                if (throwable1 != null) {
                    throw new RuntimeException("Failed to get topic names", throwable1);
                }
                var topicDescr = topics.get(config.topic());
                Log.infov("Topic {0} has {1} partitions", config.topic(), topicDescr.partitions().size());
                if (topicDescr.partitions().size() < nodes.size()) {
                    var partitionsToCreate = nodes.size() - topicDescr.partitions().size();
                    Log.infov("Will create {0} additional partitions", partitionsToCreate);
                    try {
                        adminClient.createPartitions(Map.of(config.topic(), NewPartitions.increaseTo(nodes.size()))).all().getNow(null);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to create partitions", e);
                    }
                }

                // make sure that each broker is a leader for at least one partition
                reassignPartitionsToBrokers(topicDescr, nodes);
                recalculateRacksByPartition();
                Log.debugv("Partition refresh done");
                initialRefreshDone.set(true);
            });
        });
    }
}
