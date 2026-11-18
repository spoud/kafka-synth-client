package io.spoud.kafka;

import io.spoud.config.SynthClientConfig;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterPartitionReassignmentsResult;
import org.apache.kafka.clients.admin.ElectLeadersResult;
import org.apache.kafka.clients.admin.NewPartitionReassignment;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.ElectionType;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.errors.ClusterAuthorizationException;
import org.apache.kafka.common.errors.PreferredLeaderNotAvailableException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PartitionRebalancerTest {
    private static final String TOPIC = "synth";
    private static final Node BROKER_1 = new Node(1, "b1", 9092, "rack-a");
    private static final Node BROKER_2 = new Node(2, "b2", 9092, "rack-a");
    private static final Node BROKER_3 = new Node(3, "b3", 9092, "rack-b");
    private static final List<Node> NODES = List.of(BROKER_1, BROKER_2, BROKER_3);
    private static final TopicPartition PARTITION_0 = new TopicPartition(TOPIC, 0);

    // the situation from issue #231: broker 2 is a replica of partitions 0 and 1 but leads none of them
    private static final TopicDescription BROKER_2_LEADS_NOTHING = new TopicDescription(TOPIC, false, List.of(
            new TopicPartitionInfo(0, BROKER_1, List.of(BROKER_1, BROKER_2), List.of(BROKER_1, BROKER_2)),
            new TopicPartitionInfo(1, BROKER_3, List.of(BROKER_3, BROKER_2), List.of(BROKER_3, BROKER_2)),
            new TopicPartitionInfo(2, BROKER_1, List.of(BROKER_1, BROKER_3), List.of(BROKER_1, BROKER_3))));

    private PartitionRebalancer rebalancer;
    private AdminClient adminClient;
    private MessageProducer producer;
    private SynthClientConfig config;

    @BeforeEach
    void setUp() {
        adminClient = mock(AdminClient.class);
        producer = mock(MessageProducer.class);
        config = mock(SynthClientConfig.class);
        when(config.topic()).thenReturn(TOPIC);
        when(config.rebalancePartitions()).thenReturn(true);
        rebalancer = new PartitionRebalancer();
        rebalancer.adminClient = adminClient;
        rebalancer.producer = producer;
        rebalancer.config = config;
    }

    @Test
    @DisplayName("A missing ALTER permission stops the rebalancing instead of retrying and recreating the producer")
    void stopsAfterAuthorizationFailure() {
        alterReassignmentsReturns(failedFuture(new ClusterAuthorizationException("Cluster authorization failed.")));

        rebalancer.reassignPartitionsToBrokers(BROKER_2_LEADS_NOTHING, NODES);
        rebalancer.reassignPartitionsToBrokers(BROKER_2_LEADS_NOTHING, NODES);

        verify(adminClient, times(1)).alterPartitionReassignments(anyMap());
        verify(adminClient, never()).electLeaders(any(), any());
        verify(producer, never()).recreateProducer();
        assertThat(rebalancer.getPartitionsByBroker())
                .containsEntry(1, List.of(0, 2))
                .containsEntry(2, List.of())
                .containsEntry(3, List.of(1));
    }

    @Test
    @DisplayName("A successful reassignment is followed by a preferred leader election and one producer restart")
    void electsPreferredLeaderAfterReassignment() {
        alterReassignmentsReturns(KafkaFuture.completedFuture(null));
        electLeadersReturns(Map.of(PARTITION_0, Optional.empty()));

        rebalancer.reassignPartitionsToBrokers(BROKER_2_LEADS_NOTHING, NODES);

        // broker 2 is already a replica of partition 0, so only the order changes
        verify(adminClient).alterPartitionReassignments(argThat(reassignments -> reassignments.size() == 1
                && reassignments.get(PARTITION_0).map(NewPartitionReassignment::targetReplicas).equals(Optional.of(List.of(2, 1)))));
        verify(adminClient).electLeaders(ElectionType.PREFERRED, Set.of(PARTITION_0));
        verify(producer, times(1)).recreateProducer();
        assertThat(rebalancer.getPartitionsByBroker())
                .containsEntry(1, List.of(2))
                .containsEntry(2, List.of(0))
                .containsEntry(3, List.of(1));
    }

    @Test
    @DisplayName("The producer is not recreated while the new leader cannot take over yet")
    void keepsProducerWhenElectionFails() {
        alterReassignmentsReturns(KafkaFuture.completedFuture(null));
        electLeadersReturns(Map.of(PARTITION_0, Optional.of(new PreferredLeaderNotAvailableException("not in sync"))));

        rebalancer.reassignPartitionsToBrokers(BROKER_2_LEADS_NOTHING, NODES);

        verify(producer, never()).recreateProducer();
        assertThat(rebalancer.getPartitionsByBroker()).containsEntry(2, List.of());
    }

    @Test
    @DisplayName("Nothing is changed when every broker already leads a partition")
    void leavesBalancedTopicAlone() {
        var balanced = new TopicDescription(TOPIC, false, List.of(
                new TopicPartitionInfo(0, BROKER_1, List.of(BROKER_1, BROKER_2), List.of(BROKER_1, BROKER_2)),
                new TopicPartitionInfo(1, BROKER_2, List.of(BROKER_2, BROKER_3), List.of(BROKER_2, BROKER_3)),
                new TopicPartitionInfo(2, BROKER_3, List.of(BROKER_3, BROKER_1), List.of(BROKER_3, BROKER_1))));

        rebalancer.reassignPartitionsToBrokers(balanced, NODES);

        verify(adminClient, never()).alterPartitionReassignments(anyMap());
        verify(producer, never()).recreateProducer();
    }

    @Test
    @DisplayName("Rebalancing can be turned off")
    void respectsDisabledRebalancing() {
        when(config.rebalancePartitions()).thenReturn(false);

        rebalancer.reassignPartitionsToBrokers(BROKER_2_LEADS_NOTHING, NODES);

        verify(adminClient, never()).alterPartitionReassignments(anyMap());
        verify(producer, never()).recreateProducer();
        assertThat(rebalancer.getPartitionsByBroker()).containsEntry(2, List.of());
    }

    @Test
    @DisplayName("The new leader is put first and the current replicas are kept")
    void genReassignmentKeepsReplicas() {
        assertThat(rebalancer.genReassignment(BROKER_2, List.of(BROKER_1, BROKER_2)).targetReplicas()).containsExactly(2, 1);
        assertThat(rebalancer.genReassignment(BROKER_2, List.of(BROKER_1, BROKER_3)).targetReplicas()).containsExactly(2, 1);
    }

    private void alterReassignmentsReturns(KafkaFuture<Void> future) {
        var result = mock(AlterPartitionReassignmentsResult.class);
        when(result.all()).thenReturn(future);
        when(adminClient.alterPartitionReassignments(anyMap())).thenReturn(result);
    }

    private void electLeadersReturns(Map<TopicPartition, Optional<Throwable>> results) {
        var result = mock(ElectLeadersResult.class);
        when(result.partitions()).thenReturn(KafkaFuture.completedFuture(results));
        when(adminClient.electLeaders(eq(ElectionType.PREFERRED), any())).thenReturn(result);
    }

    private static <T> KafkaFuture<T> failedFuture(Throwable throwable) {
        var future = new KafkaFutureImpl<T>();
        future.completeExceptionally(throwable);
        return future;
    }
}
