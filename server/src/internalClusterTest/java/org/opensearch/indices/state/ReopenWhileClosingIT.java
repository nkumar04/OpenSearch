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
 *    http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.indices.state;

import org.opensearch.action.ActionFuture;
import org.opensearch.action.admin.indices.close.CloseIndexResponse;
import org.opensearch.action.admin.indices.close.TransportVerifyShardBeforeCloseAction;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.common.Glob;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.concurrent.RunOnce;
import org.opensearch.core.common.lease.Releasable;
import org.opensearch.core.common.Strings;
import org.opensearch.plugins.Plugin;
import org.opensearch.test.OpenSearchIntegTestCase;
import org.opensearch.test.transport.MockTransportService;
import org.opensearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonList;
import static org.opensearch.cluster.metadata.MetadataIndexStateService.INDEX_CLOSED_BLOCK_ID;
import static org.opensearch.indices.state.CloseIndexIT.assertIndexIsClosed;
import static org.opensearch.indices.state.CloseIndexIT.assertIndexIsOpened;
import static org.opensearch.test.hamcrest.OpenSearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@OpenSearchIntegTestCase.ClusterScope(scope = OpenSearchIntegTestCase.Scope.TEST)
public class ReopenWhileClosingIT extends OpenSearchIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return singletonList(MockTransportService.TestPlugin.class);
    }

    @Override
    protected int minimumNumberOfShards() {
        return 2;
    }

    public void testReopenDuringClose() throws Exception {
        List<String> dataOnlyNodes = internalCluster().startDataOnlyNodes(randomIntBetween(2, 3));
        final String indexName = "test";
        createIndexWithDocs(indexName, dataOnlyNodes);

        ensureYellowAndNoInitializingShards(indexName);

        final CountDownLatch block = new CountDownLatch(1);
        final Releasable releaseBlock = interceptVerifyShardBeforeCloseActions(indexName, block::countDown);

        ActionFuture<CloseIndexResponse> closeIndexResponse = client().admin().indices().prepareClose(indexName).execute();
        assertTrue("Waiting for index to have a closing blocked", block.await(60, TimeUnit.SECONDS));
        assertIndexIsBlocked(indexName);
        assertFalse(closeIndexResponse.isDone());

        assertAcked(client().admin().indices().prepareOpen(indexName));

        releaseBlock.close();
        assertFalse(closeIndexResponse.get().isAcknowledged());
        assertIndexIsOpened(indexName);
    }

    public void testReopenDuringCloseOnMultipleIndices() throws Exception {
        List<String> dataOnlyNodes = internalCluster().startDataOnlyNodes(randomIntBetween(2, 3));
        final List<String> indices = new ArrayList<>();
        for (int i = 0; i < randomIntBetween(2, 10); i++) {
            indices.add("index-" + i);
            createIndexWithDocs(indices.get(i), dataOnlyNodes);
        }

        ensureYellowAndNoInitializingShards(indices.toArray(Strings.EMPTY_ARRAY));

        final CountDownLatch block = new CountDownLatch(1);
        final Releasable releaseBlock = interceptVerifyShardBeforeCloseActions(randomFrom(indices), block::countDown);

        ActionFuture<CloseIndexResponse> closeIndexResponse = client().admin().indices().prepareClose("index-*").execute();
        assertTrue("Waiting for index to have a closing blocked", block.await(60, TimeUnit.SECONDS));
        assertFalse(closeIndexResponse.isDone());
        indices.forEach(ReopenWhileClosingIT::assertIndexIsBlocked);

        final List<String> reopenedIndices = randomSubsetOf(randomIntBetween(1, indices.size()), indices);
        assertAcked(client().admin().indices().prepareOpen(reopenedIndices.toArray(Strings.EMPTY_ARRAY)));

        releaseBlock.close();
        assertFalse(closeIndexResponse.get().isAcknowledged());

        indices.forEach(index -> {
            if (reopenedIndices.contains(index)) {
                assertIndexIsOpened(index);
            } else {
                assertIndexIsClosed(index);
            }
        });
    }

    private void createIndexWithDocs(final String indexName, final Collection<String> dataOnlyNodes) {
        createIndex(
            indexName,
            Settings.builder().put(indexSettings()).put("index.routing.allocation.include._name", String.join(",", dataOnlyNodes)).build()
        );
        final int nbDocs = scaledRandomIntBetween(1, 100);
        for (int i = 0; i < nbDocs; i++) {
            index(indexName, "_doc", String.valueOf(i), "num", i);
        }
        assertIndexIsOpened(indexName);
    }

    /**
     * Intercepts and blocks the {@link TransportVerifyShardBeforeCloseAction} executed for the given index pattern.
     */
    private Releasable interceptVerifyShardBeforeCloseActions(final String indexPattern, final Runnable onIntercept) {
        final MockTransportService mockTransportService = (MockTransportService) internalCluster().getInstance(
            TransportService.class,
            internalCluster().getClusterManagerName()
        );

        final CountDownLatch release = new CountDownLatch(1);
        for (DiscoveryNode node : internalCluster().clusterService().state().getNodes()) {
            mockTransportService.addSendBehavior(
                internalCluster().getInstance(TransportService.class, node.getName()),
                (connection, requestId, action, request, options) -> {
                    if (action.startsWith(TransportVerifyShardBeforeCloseAction.NAME)) {
                        if (request instanceof TransportVerifyShardBeforeCloseAction.ShardRequest) {
                            final String index = ((TransportVerifyShardBeforeCloseAction.ShardRequest) request).shardId().getIndexName();
                            if (Glob.globMatch(indexPattern, index)) {
                                logger.info("request {} intercepted for index {}", requestId, index);
                                onIntercept.run();
                                try {
                                    release.await();
                                    logger.info("request {} released for index {}", requestId, index);
                                } catch (final InterruptedException e) {
                                    throw new AssertionError(e);
                                }
                            }
                        }

                    }
                    connection.sendRequest(requestId, action, request, options);
                }
            );
        }
        final RunOnce releaseOnce = new RunOnce(release::countDown);
        return releaseOnce::run;
    }

    private static void assertIndexIsBlocked(final String... indices) {
        final ClusterState clusterState = client().admin().cluster().prepareState().get().getState();
        for (String index : indices) {
            assertThat(clusterState.metadata().indices().get(index).getState(), is(IndexMetadata.State.OPEN));
            assertThat(clusterState.routingTable().index(index), notNullValue());
            assertThat(
                "Index " + index + " must have only 1 block with [id=" + INDEX_CLOSED_BLOCK_ID + "]",
                clusterState.blocks()
                    .indices()
                    .getOrDefault(index, emptySet())
                    .stream()
                    .filter(clusterBlock -> clusterBlock.id() == INDEX_CLOSED_BLOCK_ID)
                    .count(),
                equalTo(1L)
            );
        }
    }
}
