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

package org.opensearch.index.engine;

import org.apache.lucene.document.LongPoint;
import org.apache.lucene.search.PointRangeQuery;
import org.apache.lucene.search.Query;
import org.opensearch.core.common.lease.Releasable;
import org.opensearch.index.seqno.RetentionLease;
import org.opensearch.index.seqno.RetentionLeases;
import org.opensearch.test.OpenSearchTestCase;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.opensearch.index.seqno.SequenceNumbers.NO_OPS_PERFORMED;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class SoftDeletesPolicyTests extends OpenSearchTestCase {

    /**
     * Makes sure we won't advance the retained seq# if the retention lock is held
     */
    public void testSoftDeletesRetentionLock() {
        long retainedOps = between(0, 10000);
        AtomicLong globalCheckpoint = new AtomicLong(NO_OPS_PERFORMED);
        final AtomicLong[] retainingSequenceNumbers = new AtomicLong[randomIntBetween(0, 8)];
        for (int i = 0; i < retainingSequenceNumbers.length; i++) {
            retainingSequenceNumbers[i] = new AtomicLong();
        }
        final Supplier<RetentionLeases> retentionLeasesSupplier = () -> {
            final List<RetentionLease> leases = new ArrayList<>(retainingSequenceNumbers.length);
            for (int i = 0; i < retainingSequenceNumbers.length; i++) {
                leases.add(new RetentionLease(Integer.toString(i), retainingSequenceNumbers[i].get(), 0L, "test"));
            }
            return new RetentionLeases(1, 1, leases);
        };
        long safeCommitCheckpoint = globalCheckpoint.get();
        SoftDeletesPolicy policy = new SoftDeletesPolicy(globalCheckpoint::get, between(1, 10000), retainedOps, retentionLeasesSupplier);
        long minRetainedSeqNo = policy.getMinRetainedSeqNo();
        List<Releasable> locks = new ArrayList<>();
        int iters = scaledRandomIntBetween(10, 1000);
        for (int i = 0; i < iters; i++) {
            if (randomBoolean()) {
                locks.add(policy.acquireRetentionLock());
            }
            // Advances the global checkpoint and the local checkpoint of a safe commit
            globalCheckpoint.addAndGet(between(0, 1000));
            for (final AtomicLong retainingSequenceNumber : retainingSequenceNumbers) {
                retainingSequenceNumber.set(randomLongBetween(retainingSequenceNumber.get(), Math.max(globalCheckpoint.get(), 0L)));
            }
            safeCommitCheckpoint = randomLongBetween(safeCommitCheckpoint, globalCheckpoint.get());
            policy.setLocalCheckpointOfSafeCommit(safeCommitCheckpoint);
            if (rarely()) {
                retainedOps = between(0, 10000);
                policy.setRetentionOperations(retainedOps);
            }
            // Release some locks
            List<Releasable> releasingLocks = randomSubsetOf(locks);
            locks.removeAll(releasingLocks);
            releasingLocks.forEach(Releasable::close);

            // getting the query has side effects, updating the internal state of the policy
            final Query query = policy.getRetentionQuery();
            assertThat(query, instanceOf(PointRangeQuery.class));
            final PointRangeQuery retentionQuery = (PointRangeQuery) query;

            // we only expose the minimum sequence number to the merge policy if the retention lock is not held
            if (locks.isEmpty()) {
                final long minimumRetainingSequenceNumber = Arrays.stream(retainingSequenceNumbers)
                    .mapToLong(AtomicLong::get)
                    .min()
                    .orElse(Long.MAX_VALUE);
                long retainedSeqNo = Math.min(
                    1 + safeCommitCheckpoint,
                    Math.min(minimumRetainingSequenceNumber, 1 + globalCheckpoint.get() - retainedOps)
                );
                minRetainedSeqNo = Math.max(minRetainedSeqNo, retainedSeqNo);
            }
            assertThat(retentionQuery.getNumDims(), equalTo(1));
            assertThat(LongPoint.decodeDimension(retentionQuery.getLowerPoint(), 0), equalTo(minRetainedSeqNo));
            assertThat(LongPoint.decodeDimension(retentionQuery.getUpperPoint(), 0), equalTo(Long.MAX_VALUE));
            assertThat(policy.getMinRetainedSeqNo(), equalTo(minRetainedSeqNo));
        }

        locks.forEach(Releasable::close);
        final long minimumRetainingSequenceNumber = Arrays.stream(retainingSequenceNumbers)
            .mapToLong(AtomicLong::get)
            .min()
            .orElse(Long.MAX_VALUE);
        long retainedSeqNo = Math.min(
            1 + safeCommitCheckpoint,
            Math.min(minimumRetainingSequenceNumber, 1 + globalCheckpoint.get() - retainedOps)
        );
        minRetainedSeqNo = Math.max(minRetainedSeqNo, retainedSeqNo);
        assertThat(policy.getMinRetainedSeqNo(), equalTo(minRetainedSeqNo));
    }

    public void testWhenGlobalCheckpointDictatesThePolicy() {
        final int retentionOperations = randomIntBetween(0, 1024);
        final AtomicLong globalCheckpoint = new AtomicLong(randomLongBetween(0, Long.MAX_VALUE - 2));
        final Collection<RetentionLease> leases = new ArrayList<>();
        final int numberOfLeases = randomIntBetween(0, 16);
        for (int i = 0; i < numberOfLeases; i++) {
            // setup leases where the minimum retained sequence number is more than the policy dictated by the global checkpoint
            leases.add(
                new RetentionLease(
                    Integer.toString(i),
                    randomLongBetween(1 + globalCheckpoint.get() - retentionOperations + 1, Long.MAX_VALUE),
                    randomNonNegativeLong(),
                    "test"
                )
            );
        }
        final long primaryTerm = randomNonNegativeLong();
        final long version = randomNonNegativeLong();
        final Supplier<RetentionLeases> leasesSupplier = () -> new RetentionLeases(
            primaryTerm,
            version,
            Collections.unmodifiableCollection(new ArrayList<>(leases))
        );
        final SoftDeletesPolicy policy = new SoftDeletesPolicy(globalCheckpoint::get, 0, retentionOperations, leasesSupplier);
        // set the local checkpoint of the safe commit to more than the policy dicated by the global checkpoint
        final long localCheckpointOfSafeCommit = randomLongBetween(1 + globalCheckpoint.get() - retentionOperations + 1, Long.MAX_VALUE);
        policy.setLocalCheckpointOfSafeCommit(localCheckpointOfSafeCommit);
        assertThat(policy.getMinRetainedSeqNo(), equalTo(1 + globalCheckpoint.get() - retentionOperations));
    }

    public void testWhenLocalCheckpointOfSafeCommitDictatesThePolicy() {
        final int retentionOperations = randomIntBetween(0, 1024);
        final long localCheckpointOfSafeCommit = randomLongBetween(-1, Long.MAX_VALUE - retentionOperations - 1);
        final AtomicLong globalCheckpoint = new AtomicLong(
            randomLongBetween(Math.max(0, localCheckpointOfSafeCommit + retentionOperations), Long.MAX_VALUE - 1)
        );
        final Collection<RetentionLease> leases = new ArrayList<>();
        final int numberOfLeases = randomIntBetween(0, 16);
        for (int i = 0; i < numberOfLeases; i++) {
            leases.add(
                new RetentionLease(
                    Integer.toString(i),
                    randomLongBetween(1 + localCheckpointOfSafeCommit + 1, Long.MAX_VALUE), // leases are for more than the local checkpoint
                    randomNonNegativeLong(),
                    "test"
                )
            );
        }
        final long primaryTerm = randomNonNegativeLong();
        final long version = randomNonNegativeLong();
        final Supplier<RetentionLeases> leasesSupplier = () -> new RetentionLeases(
            primaryTerm,
            version,
            Collections.unmodifiableCollection(new ArrayList<>(leases))
        );

        final SoftDeletesPolicy policy = new SoftDeletesPolicy(globalCheckpoint::get, 0, retentionOperations, leasesSupplier);
        policy.setLocalCheckpointOfSafeCommit(localCheckpointOfSafeCommit);
        assertThat(policy.getMinRetainedSeqNo(), equalTo(1 + localCheckpointOfSafeCommit));
    }

    public void testWhenRetentionLeasesDictateThePolicy() {
        final int retentionOperations = randomIntBetween(0, 1024);
        final Collection<RetentionLease> leases = new ArrayList<>();
        final int numberOfLeases = randomIntBetween(1, 16);
        for (int i = 0; i < numberOfLeases; i++) {
            leases.add(
                new RetentionLease(
                    Integer.toString(i),
                    randomLongBetween(0, Long.MAX_VALUE - retentionOperations - 1),
                    randomNonNegativeLong(),
                    "test"
                )
            );
        }
        final OptionalLong minimumRetainingSequenceNumber = leases.stream().mapToLong(RetentionLease::retainingSequenceNumber).min();
        assert minimumRetainingSequenceNumber.isPresent() : leases;
        final long localCheckpointOfSafeCommit = randomLongBetween(minimumRetainingSequenceNumber.getAsLong(), Long.MAX_VALUE - 1);
        final AtomicLong globalCheckpoint = new AtomicLong(
            randomLongBetween(minimumRetainingSequenceNumber.getAsLong() + retentionOperations, Long.MAX_VALUE - 1)
        );
        final long primaryTerm = randomNonNegativeLong();
        final long version = randomNonNegativeLong();
        final Supplier<RetentionLeases> leasesSupplier = () -> new RetentionLeases(
            primaryTerm,
            version,
            Collections.unmodifiableCollection(new ArrayList<>(leases))
        );
        final SoftDeletesPolicy policy = new SoftDeletesPolicy(globalCheckpoint::get, 0, retentionOperations, leasesSupplier);
        policy.setLocalCheckpointOfSafeCommit(localCheckpointOfSafeCommit);
        assertThat(policy.getMinRetainedSeqNo(), equalTo(minimumRetainingSequenceNumber.getAsLong()));
    }

}
