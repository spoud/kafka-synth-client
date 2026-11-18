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
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.ElectionNotNeededException;
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
import java.util.concurrent.CompletionException;
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

    private boolean refreshPartitionsEnabled = true;
    private volatile boolean rebalancingAllowed = true;
    private List<Integer> unmeasuredBrokersWarned = List.of();
    private final AtomicBoolean initialRefreshDone = new AtomicBoolean(false);
    private final Map<Integer, List<Integer>> partitionsByBroker = new ConcurrentHashMap<>();
    private final Map<Integer, String> rackByPartition = new ConcurrentHashMap<>();

    public boolean isInitialRefreshPending() {
        return !initialRefreshDone.get();
    }

    /**
     * Puts the new leader first and keeps the current replicas, so that a broker which is already a replica only needs
     * a reorder (no data movement), and repeating the reassignment on the next refresh targets the same replica set.
     */
    NewPartitionReassignment genReassignment(Node leader, List<Node> currentReplicas) {
        var replicaIds = new ArrayList<Integer>();
        replicaIds.add(leader.id());
        for (var replica : currentReplicas) {
            if (replicaIds.size() < currentReplicas.size() && replica.id() != leader.id()) {
                replicaIds.add(replica.id());
            }
        }
        return new NewPartitionReassignment(replicaIds);
    }

    public Optional<Integer> getBrokerIdForPartition(int partition) {
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

    public Map<Integer, List<Integer>> getPartitionsByBroker() {
        return Collections.unmodifiableMap(partitionsByBroker);
    }

    void reassignPartitionsToBrokers(TopicDescription topicDescription, Collection<Node> nodes) {
        // build a map of broker -> list of partitions it currently leads
        var brokers = new ArrayList<>(nodes);
        final var newPartitionAssignment = new HashMap<Integer, List<Integer>>();
        for (var broker : brokers) {
            newPartitionAssignment.put(broker.id(), new ArrayList<>());
        }
        for (var partition : topicDescription.partitions()) {
            var leader = partition.leader();
            if (leader == null) {
                Log.warnv("Partition {0} has no leader", partition.partition());
                continue;
            }
            newPartitionAssignment.computeIfAbsent(leader.id(), (k) -> new ArrayList<>()).add(partition.partition());
        }
        // print partitions by broker
        for (var entry : newPartitionAssignment.entrySet()) {
            Log.infov("Broker {0} has partitions {1}", entry.getKey(), entry.getValue());
        }
        // the metrics are labeled with the actual leaders, so record them before attempting any change
        partitionsByBroker.putAll(copyOf(newPartitionAssignment));
        if (!config.rebalancePartitions() || !rebalancingAllowed) {
            warnAboutUnmeasuredBrokers(newPartitionAssignment);
            return;
        }

        var partitionInfos = topicDescription.partitions().stream()
                .collect(Collectors.toMap(TopicPartitionInfo::partition, p -> p));
        var reassignments = new HashMap<TopicPartition, Optional<NewPartitionReassignment>>();
        var newLeaders = new HashMap<Integer, Integer>();
        // sort the brokers by the number of partitions they are a leader for
        brokers.sort(Comparator.comparingLong(n -> newPartitionAssignment.get(n.id()).size()));
        var i = 0;
        var j = brokers.size() - 1;
        while (i < j) {
            var poorBroker = brokers.get(i);
            var richBroker = brokers.get(j);
            if (!newPartitionAssignment.get(poorBroker.id()).isEmpty()) {
                // the poorest broker has at least one partition, meaning that each broker has at least one partition
                break;
            }
            var partitionToGive = newPartitionAssignment.get(richBroker.id()).removeFirst();
            newPartitionAssignment.get(poorBroker.id()).add(partitionToGive);
            var tp = new TopicPartition(config.topic(), partitionToGive);
            reassignments.put(tp, Optional.of(genReassignment(poorBroker, partitionInfos.get(partitionToGive).replicas())));
            newLeaders.put(partitionToGive, poorBroker.id());

            if (newPartitionAssignment.get(richBroker.id()).size() < 2) {
                j--;
            }
            i++;
        }
        if (reassignments.isEmpty()) {
            Log.debug("Each broker has at least one partition. Will not reassign partitions");
            return;
        }

        Log.infov("Moving leadership of partitions to brokers {0}", newLeaders);
        adminClient.alterPartitionReassignments(reassignments).all().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                handleRebalanceFailure("reassign partitions", throwable);
                return;
            }
            electPreferredLeaders(newLeaders);
        });
    }

    /**
     * A reassignment only changes the replica order. Leadership moves to the new first replica with a preferred
     * leader election, which fails while that replica is still catching up. The next refresh then tries again.
     */
    private void electPreferredLeaders(Map<Integer, Integer> newLeaders) {
        var partitions = newLeaders.keySet().stream()
                .map(p -> new TopicPartition(config.topic(), p))
                .collect(Collectors.toSet());
        adminClient.electLeaders(ElectionType.PREFERRED, partitions).partitions().whenComplete((results, throwable) -> {
            if (throwable != null) {
                handleRebalanceFailure("elect preferred leaders", throwable);
                return;
            }
            var moved = new HashMap<Integer, List<Integer>>(copyOf(partitionsByBroker));
            var changed = false;
            for (var result : results.entrySet()) {
                Integer partition = result.getKey().partition(); // boxed, so that remove() removes the value and not an index
                var error = result.getValue();
                if (error.isPresent() && !(error.get() instanceof ElectionNotNeededException)) {
                    Log.infov("Leadership of partition {0} not moved yet: {1}", partition, error.get().getMessage());
                    continue;
                }
                moved.values().forEach(leaderPartitions -> leaderPartitions.remove(partition));
                moved.computeIfAbsent(newLeaders.get(partition), (k) -> new ArrayList<>()).add(partition);
                changed |= error.isEmpty();
            }
            for (var entry : moved.entrySet()) {
                Log.infov("New assignment: Broker {0} has partitions {1}", entry.getKey(), entry.getValue());
            }
            partitionsByBroker.putAll(moved);
            if (changed) {
                producer.recreateProducer(); // recreate the producer to make sure that it is aware of the new leaders
            }
        });
    }

    /**
     * Logged only when the set of brokers changes, so that a permanently unbalanced topic does not warn every minute.
     */
    private void warnAboutUnmeasuredBrokers(Map<Integer, List<Integer>> partitionsByLeader) {
        var unmeasured = partitionsByLeader.entrySet().stream()
                .filter(e -> e.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        if (!unmeasured.isEmpty() && !unmeasured.equals(unmeasuredBrokersWarned)) {
            Log.warnv("Brokers {0} lead no partition of topic {1} and partition rebalancing is off, so there are no "
                    + "synth_client_ack_latency_ms and synth_client_e2e_latency_ms series for them", unmeasured, config.topic());
        }
        unmeasuredBrokersWarned = unmeasured;
    }

    private void handleRebalanceFailure(String action, Throwable throwable) {
        var cause = (throwable instanceof CompletionException || throwable instanceof ExecutionException) && throwable.getCause() != null
                ? throwable.getCause()
                : throwable;
        if (cause instanceof AuthorizationException) {
            rebalancingAllowed = false;
            Log.warnv("Not allowed to {0} ({1}). Partition rebalancing is disabled until restart, so brokers that lead no partition of topic {2} are not measured. "
                    + "Grant ALTER on the cluster, move the leaders manually, or set synth-client.rebalance-partitions=false.",
                    action, cause.getClass().getSimpleName(), config.topic());
        } else {
            Log.errorv(cause, "Failed to {0}, will retry on the next refresh", action);
        }
    }

    private static Map<Integer, List<Integer>> copyOf(Map<Integer, List<Integer>> assignment) {
        var copy = new HashMap<Integer, List<Integer>>();
        assignment.forEach((broker, partitions) -> copy.put(broker, new ArrayList<>(partitions)));
        return copy;
    }

    @PostConstruct
    void init() {
        if (!config.rebalancePartitions()) {
            Log.info("Partition rebalancing is disabled");
        }
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
        Log.info("Refreshing partition leaders to make sure that the E2E test hits each node");
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
                    adminClient.createPartitions(Map.of(config.topic(), NewPartitions.increaseTo(nodes.size()))).all().whenComplete((ignored, throwable2) -> {
                        if (throwable2 != null) {
                            Log.errorv(throwable2, "Failed to create partitions");
                            return;
                        }
                        producer.recreateProducer(); // make sure that the producer is aware of the new partitions
                    });
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
