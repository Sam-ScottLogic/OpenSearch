/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.routing;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;
import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RecoverySource.SnapshotRecoverySource;
import org.opensearch.cluster.routing.UnassignedInfo.AllocationStatus;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.FailedShard;
import org.opensearch.common.UUIDs;
import org.opensearch.core.common.io.stream.ByteBufferStreamInput;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.core.index.Index;
import org.opensearch.repositories.IndexId;
import org.opensearch.snapshots.Snapshot;
import org.opensearch.snapshots.SnapshotId;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.opensearch.cluster.routing.ShardRoutingState.STARTED;
import static org.opensearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

public class UnassignedInfoTests extends OpenSearchAllocationTestCase {

    public void testReasonOrdinalOrder() {
        UnassignedInfo.Reason[] order = new UnassignedInfo.Reason[] {
            UnassignedInfo.Reason.INDEX_CREATED,
            UnassignedInfo.Reason.CLUSTER_RECOVERED,
            UnassignedInfo.Reason.INDEX_REOPENED,
            UnassignedInfo.Reason.DANGLING_INDEX_IMPORTED,
            UnassignedInfo.Reason.NEW_INDEX_RESTORED,
            UnassignedInfo.Reason.EXISTING_INDEX_RESTORED,
            UnassignedInfo.Reason.REPLICA_ADDED,
            UnassignedInfo.Reason.ALLOCATION_FAILED,
            UnassignedInfo.Reason.NODE_LEFT,
            UnassignedInfo.Reason.REROUTE_CANCELLED,
            UnassignedInfo.Reason.REINITIALIZED,
            UnassignedInfo.Reason.REALLOCATED_REPLICA,
            UnassignedInfo.Reason.PRIMARY_FAILED,
            UnassignedInfo.Reason.FORCED_EMPTY_PRIMARY,
            UnassignedInfo.Reason.MANUAL_ALLOCATION,
            UnassignedInfo.Reason.INDEX_CLOSED, };
        for (int i = 0; i < order.length; i++) {
            assertThat(order[i].ordinal(), equalTo(i));
        }
        assertThat(UnassignedInfo.Reason.values().length, equalTo(order.length));
    }

    public void testSerialization() throws Exception {
        UnassignedInfo.Reason reason = RandomPicks.randomFrom(random(), UnassignedInfo.Reason.values());
        int failedAllocations = randomIntBetween(1, 100);
        Set<String> failedNodes = IntStream.range(0, between(0, failedAllocations))
            .mapToObj(n -> "failed-node-" + n)
            .collect(Collectors.toSet());
        UnassignedInfo meta = reason == UnassignedInfo.Reason.ALLOCATION_FAILED
            ? new UnassignedInfo(
                reason,
                randomBoolean() ? randomAlphaOfLength(4) : null,
                null,
                failedAllocations,
                System.nanoTime(),
                System.currentTimeMillis(),
                false,
                AllocationStatus.NO_ATTEMPT,
                failedNodes
            )
            : new UnassignedInfo(reason, randomBoolean() ? randomAlphaOfLength(4) : null);
        BytesStreamOutput out = new BytesStreamOutput();
        meta.writeTo(out);
        out.close();

        UnassignedInfo read = new UnassignedInfo(out.bytes().streamInput());
        assertThat(read.getReason(), equalTo(meta.getReason()));
        assertThat(read.getUnassignedTimeInMillis(), equalTo(meta.getUnassignedTimeInMillis()));
        assertThat(read.getMessage(), equalTo(meta.getMessage()));
        assertThat(read.getDetails(), equalTo(meta.getDetails()));
        assertThat(read.getNumFailedAllocations(), equalTo(meta.getNumFailedAllocations()));
        assertThat(read.getFailedNodeIds(), equalTo(meta.getFailedNodeIds()));
    }

    public void testIndexCreated() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(randomIntBetween(1, 3))
                    .numberOfReplicas(randomIntBetween(0, 3))
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index("test")).build())
            .build();
        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertThat(shard.unassignedInfo().getReason(), equalTo(UnassignedInfo.Reason.INDEX_CREATED));
        }
    }

    public void testClusterRecovered() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(randomIntBetween(1, 3))
                    .numberOfReplicas(randomIntBetween(0, 3))
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsRecovery(metadata.index("test")).build())
            .build();
        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertThat(shard.unassignedInfo().getReason(), equalTo(UnassignedInfo.Reason.CLUSTER_RECOVERED));
        }
    }

    public void testIndexReopened() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(randomIntBetween(1, 3))
                    .numberOfReplicas(randomIntBetween(0, 3))
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsFromCloseToOpen(metadata.index("test")).build())
            .build();
        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertThat(shard.unassignedInfo().getReason(), equalTo(UnassignedInfo.Reason.INDEX_REOPENED));
        }
    }

    public void testNewIndexRestored() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(randomIntBetween(1, 3))
                    .numberOfReplicas(randomIntBetween(0, 3))
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(
                RoutingTable.builder()
                    .addAsNewRestore(
                        metadata.index("test"),
                        new SnapshotRecoverySource(
                            UUIDs.randomBase64UUID(),
                            new Snapshot("rep1", new SnapshotId("snp1", UUIDs.randomBase64UUID())),
                            Version.CURRENT,
                            new IndexId("test", UUIDs.randomBase64UUID(random()))
                        ),
                        new IntHashSet()
                    )
                    .build()
            )
            .build();
        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertThat(shard.unassignedInfo().getReason(), equalTo(UnassignedInfo.Reason.NEW_INDEX_RESTORED));
        }
    }

    public void testExistingIndexRestored() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(randomIntBetween(1, 3))
                    .numberOfReplicas(randomIntBetween(0, 3))
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(
                RoutingTable.builder()
                    .addAsRestore(
                        metadata.index("test"),
                        new SnapshotRecoverySource(
                            UUIDs.randomBase64UUID(),
                            new Snapshot("rep1", new SnapshotId("snp1", UUIDs.randomBase64UUID())),
                            Version.CURRENT,
                            new IndexId("test", UUIDs.randomBase64UUID(random()))
                        )
                    )
                    .build()
            )
            .build();
        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertThat(shard.unassignedInfo().getReason(), equalTo(UnassignedInfo.Reason.EXISTING_INDEX_RESTORED));
        }
    }

    public void testDanglingIndexImported() {
        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test")
                    .settings(settings(Version.CURRENT))
                    .numberOfShards(randomIntBetween(1, 3))
                    .numberOfReplicas(randomIntBetween(0, 3))
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsFromDangling(metadata.index("test")).build())
            .build();
        for (ShardRouting shard : clusterState.getRoutingNodes().shardsWithState(UNASSIGNED)) {
            assertThat(shard.unassignedInfo().getReason(), equalTo(UnassignedInfo.Reason.DANGLING_INDEX_IMPORTED));
        }
    }

    public void testReplicaAdded() {
        AllocationService allocation = createAllocationService();
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0))
            .build();
        final Index index = metadata.index("test").getIndex();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index(index)).build())
            .build();
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();
        clusterState = allocation.reroute(clusterState, "reroute");
        // starting primaries
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        IndexRoutingTable.Builder builder = IndexRoutingTable.builder(index);
        for (IndexShardRoutingTable indexShardRoutingTable : clusterState.routingTable().index(index)) {
            builder.addIndexShard(indexShardRoutingTable);
        }
        builder.addReplica();
        clusterState = ClusterState.builder(clusterState)
            .routingTable(RoutingTable.builder(clusterState.routingTable()).add(builder).build())
            .build();
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo(), notNullValue());
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getReason(),
            equalTo(UnassignedInfo.Reason.REPLICA_ADDED)
        );
    }

    /**
     * The unassigned meta is kept when a shard goes to INITIALIZING, but cleared when it moves to STARTED.
     */
    public void testStateTransitionMetaHandling() {
        ShardRouting shard = TestShardRouting.newShardRouting(
            "test",
            1,
            null,
            null,
            true,
            ShardRoutingState.UNASSIGNED,
            new UnassignedInfo(UnassignedInfo.Reason.INDEX_CREATED, null)
        );
        assertThat(shard.unassignedInfo(), notNullValue());
        shard = shard.initialize("test_node", null, -1);
        assertThat(shard.state(), equalTo(ShardRoutingState.INITIALIZING));
        assertThat(shard.unassignedInfo(), notNullValue());
        shard = shard.moveToStarted();
        assertThat(shard.state(), equalTo(ShardRoutingState.STARTED));
        assertThat(shard.unassignedInfo(), nullValue());
    }

    /**
     * Tests that during reroute when a node is detected as leaving the cluster, the right unassigned meta is set
     */
    public void testNodeLeave() {
        AllocationService allocation = createAllocationService();
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index("test")).build())
            .build();
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = allocation.reroute(clusterState, "reroute");
        // starting primaries
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        // starting replicas
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(false));
        // remove node2 and reroute
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).remove("node2")).build();
        clusterState = allocation.disassociateDeadNodes(clusterState, true, "reroute");
        // verify that NODE_LEAVE is the reason for meta
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(true));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo(), notNullValue());
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getReason(),
            equalTo(UnassignedInfo.Reason.NODE_LEFT)
        );
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getUnassignedTimeInMillis(),
            greaterThan(0L)
        );
    }

    /**
     * Verifies that when a shard fails, reason is properly set and details are preserved.
     */
    public void testFailedShard() {
        AllocationService allocation = createAllocationService();
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index("test")).build())
            .build();
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = allocation.reroute(clusterState, "reroute");
        // starting primaries
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        // starting replicas
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(false));
        // fail shard
        ShardRouting shardToFail = clusterState.getRoutingNodes().shardsWithState(STARTED).get(0);
        clusterState = allocation.applyFailedShards(
            clusterState,
            Collections.singletonList(new FailedShard(shardToFail, "test fail", null, randomBoolean()))
        );
        // verify the reason and details
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(true));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).size(), equalTo(1));
        assertThat(clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo(), notNullValue());
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getReason(),
            equalTo(UnassignedInfo.Reason.ALLOCATION_FAILED)
        );
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getMessage(),
            equalTo("failed shard on node [" + shardToFail.currentNodeId() + "]: test fail")
        );
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getDetails(),
            equalTo("failed shard on node [" + shardToFail.currentNodeId() + "]: test fail")
        );
        assertThat(
            clusterState.getRoutingNodes().shardsWithState(UNASSIGNED).get(0).unassignedInfo().getUnassignedTimeInMillis(),
            greaterThan(0L)
        );
    }

    /**
     * Verifies that delayed allocation calculation are correct.
     */
    public void testRemainingDelayCalculation() throws Exception {
        final long baseTime = System.nanoTime();
        UnassignedInfo unassignedInfo = new UnassignedInfo(
            UnassignedInfo.Reason.NODE_LEFT,
            "test",
            null,
            0,
            baseTime,
            System.currentTimeMillis(),
            randomBoolean(),
            AllocationStatus.NO_ATTEMPT,
            Collections.emptySet()
        );
        final long totalDelayNanos = TimeValue.timeValueMillis(10).nanos();
        final Settings indexSettings = Settings.builder()
            .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), TimeValue.timeValueNanos(totalDelayNanos))
            .build();
        long delay = unassignedInfo.getRemainingDelay(baseTime, indexSettings);
        assertThat(delay, equalTo(totalDelayNanos));
        long delta1 = randomIntBetween(1, (int) (totalDelayNanos - 1));
        delay = unassignedInfo.getRemainingDelay(baseTime + delta1, indexSettings);
        assertThat(delay, equalTo(totalDelayNanos - delta1));
        delay = unassignedInfo.getRemainingDelay(baseTime + totalDelayNanos, indexSettings);
        assertThat(delay, equalTo(0L));
        delay = unassignedInfo.getRemainingDelay(baseTime + totalDelayNanos + randomIntBetween(1, 20), indexSettings);
        assertThat(delay, equalTo(0L));
    }

    public void testNumberOfDelayedUnassigned() throws Exception {
        MockAllocationService allocation = createAllocationService(Settings.EMPTY, new DelayedShardsMockGatewayAllocator());
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test1").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .put(IndexMetadata.builder("test2").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(1))
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index("test1")).addAsNew(metadata.index("test2")).build())
            .build();
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = allocation.reroute(clusterState, "reroute");
        assertThat(UnassignedInfo.getNumberOfDelayedUnassigned(clusterState), equalTo(0));
        // starting primaries
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        // starting replicas
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(false));
        // remove node2 and reroute
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).remove("node2")).build();
        // make sure both replicas are marked as delayed (i.e. not reallocated)
        clusterState = allocation.disassociateDeadNodes(clusterState, true, "reroute");
        assertThat(clusterState.toString(), UnassignedInfo.getNumberOfDelayedUnassigned(clusterState), equalTo(2));
    }

    public void testFindNextDelayedAllocation() {
        MockAllocationService allocation = createAllocationService(Settings.EMPTY, new DelayedShardsMockGatewayAllocator());
        final TimeValue delayTest1 = TimeValue.timeValueMillis(randomIntBetween(1, 200));
        final TimeValue delayTest2 = TimeValue.timeValueMillis(randomIntBetween(1, 200));
        final long expectMinDelaySettingsNanos = Math.min(delayTest1.nanos(), delayTest2.nanos());

        Metadata metadata = Metadata.builder()
            .put(
                IndexMetadata.builder("test1")
                    .settings(settings(Version.CURRENT).put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), delayTest1))
                    .numberOfShards(1)
                    .numberOfReplicas(1)
            )
            .put(
                IndexMetadata.builder("test2")
                    .settings(settings(Version.CURRENT).put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), delayTest2))
                    .numberOfShards(1)
                    .numberOfReplicas(1)
            )
            .build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata)
            .routingTable(RoutingTable.builder().addAsNew(metadata.index("test1")).addAsNew(metadata.index("test2")).build())
            .build();
        clusterState = ClusterState.builder(clusterState)
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        clusterState = allocation.reroute(clusterState, "reroute");
        assertThat(UnassignedInfo.getNumberOfDelayedUnassigned(clusterState), equalTo(0));
        // starting primaries
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        // starting replicas
        clusterState = startInitializingShardsAndReroute(allocation, clusterState);
        assertThat(clusterState.getRoutingNodes().unassigned().size() > 0, equalTo(false));
        // remove node2 and reroute
        final long baseTime = System.nanoTime();
        allocation.setNanoTimeOverride(baseTime);
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder(clusterState.nodes()).remove("node2")).build();
        clusterState = allocation.disassociateDeadNodes(clusterState, true, "reroute");

        final long delta = randomBoolean() ? 0 : randomInt((int) expectMinDelaySettingsNanos - 1);

        if (delta > 0) {
            allocation.setNanoTimeOverride(baseTime + delta);
            clusterState = allocation.reroute(clusterState, "time moved");
        }

        assertThat(UnassignedInfo.findNextDelayedAllocation(baseTime + delta, clusterState), equalTo(expectMinDelaySettingsNanos - delta));
    }

    public void testAllocationStatusSerialization() throws IOException {
        for (AllocationStatus allocationStatus : AllocationStatus.values()) {
            BytesStreamOutput out = new BytesStreamOutput();
            allocationStatus.writeTo(out);
            ByteBufferStreamInput in = new ByteBufferStreamInput(ByteBuffer.wrap(out.bytes().toBytesRef().bytes));
            AllocationStatus readStatus = AllocationStatus.readFrom(in);
            assertThat(readStatus, equalTo(allocationStatus));
        }
    }
}
