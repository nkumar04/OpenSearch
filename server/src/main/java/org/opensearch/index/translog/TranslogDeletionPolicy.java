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

package org.opensearch.index.translog;

import org.apache.lucene.util.Counter;
import org.opensearch.core.Assertions;
import org.opensearch.core.common.lease.Releasable;
import org.opensearch.index.seqno.SequenceNumbers;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Defines a translog deletion policy
 *
 * @opensearch.internal
 */
public abstract class TranslogDeletionPolicy {

    private final Map<Object, RuntimeException> openTranslogRef;

    public void assertNoOpenTranslogRefs() {
        if (openTranslogRef != null && openTranslogRef.isEmpty() == false) {
            AssertionError e = new AssertionError("not all translog generations have been released");
            openTranslogRef.values().forEach(e::addSuppressed);
            throw e;
        }
    }

    /**
     * Records how many retention locks are held against each
     * translog generation
     */
    private final Map<Long, Counter> translogRefCounts = new HashMap<>();
    private long localCheckpointOfSafeCommit = SequenceNumbers.NO_OPS_PERFORMED;

    public TranslogDeletionPolicy() {
        if (Assertions.ENABLED) {
            openTranslogRef = new ConcurrentHashMap<>();
        } else {
            openTranslogRef = null;
        }
    }

    public synchronized void setLocalCheckpointOfSafeCommit(long newCheckpoint) {
        if (newCheckpoint < this.localCheckpointOfSafeCommit) {
            throw new IllegalArgumentException(
                "local checkpoint of the safe commit can't go backwards: "
                    + "current ["
                    + this.localCheckpointOfSafeCommit
                    + "] new ["
                    + newCheckpoint
                    + "]"
            );
        }
        this.localCheckpointOfSafeCommit = newCheckpoint;
    }

    public abstract void setRetentionSizeInBytes(long bytes);

    public abstract void setRetentionAgeInMillis(long ageInMillis);

    protected abstract void setRetentionTotalFiles(int retentionTotalFiles);

    /**
     * acquires the basis generation for a new snapshot. Any translog generation above, and including, the returned generation
     * will not be deleted until the returned {@link Releasable} is closed.
     */
    synchronized Releasable acquireTranslogGen(final long translogGen) {
        translogRefCounts.computeIfAbsent(translogGen, l -> Counter.newCounter(false)).addAndGet(1);
        final AtomicBoolean closed = new AtomicBoolean();
        assert assertAddTranslogRef(closed);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                releaseTranslogGen(translogGen);
                assert assertRemoveTranslogRef(closed);
            }
        };
    }

    private boolean assertAddTranslogRef(Object reference) {
        final RuntimeException existing = openTranslogRef.put(reference, new RuntimeException());
        if (existing != null) {
            throw new AssertionError("double adding of closing reference", existing);
        }
        return true;
    }

    private boolean assertRemoveTranslogRef(Object reference) {
        return openTranslogRef.remove(reference) != null;
    }

    /** returns the number of generations that were acquired for snapshots */
    synchronized int pendingTranslogRefCount() {
        return translogRefCounts.size();
    }

    /**
     * releases a generation that was acquired by {@link #acquireTranslogGen(long)}
     */
    private synchronized void releaseTranslogGen(long translogGen) {
        Counter current = translogRefCounts.get(translogGen);
        if (current == null || current.get() <= 0) {
            throw new IllegalArgumentException("translog gen [" + translogGen + "] wasn't acquired");
        }
        if (current.addAndGet(-1) == 0) {
            translogRefCounts.remove(translogGen);
        }
    }

    /**
     * returns the minimum translog generation that is still required by the system. Any generation below
     * the returned value may be safely deleted
     *
     * @param readers current translog readers
     * @param writer  current translog writer
     */
    public abstract long minTranslogGenRequired(List<TranslogReader> readers, TranslogWriter writer) throws IOException;

    public static long getMinTranslogGenBySize(List<TranslogReader> readers, TranslogWriter writer, long retentionSizeInBytes) {
        if (retentionSizeInBytes >= 0) {
            long totalSize = writer.sizeInBytes();
            long minGen = writer.getGeneration();
            for (int i = readers.size() - 1; i >= 0 && totalSize < retentionSizeInBytes; i--) {
                final TranslogReader reader = readers.get(i);
                totalSize += reader.sizeInBytes();
                minGen = reader.getGeneration();
            }
            return minGen;
        } else {
            return Long.MIN_VALUE;
        }
    }

    public static long getMinTranslogGenByAge(List<TranslogReader> readers, TranslogWriter writer, long maxRetentionAgeInMillis, long now)
        throws IOException {
        if (maxRetentionAgeInMillis >= 0) {
            for (TranslogReader reader : readers) {
                if (now - reader.getLastModifiedTime() <= maxRetentionAgeInMillis) {
                    return reader.getGeneration();
                }
            }
            return writer.getGeneration();
        } else {
            return Long.MIN_VALUE;
        }
    }

    public static long getMinTranslogGenByTotalFiles(List<TranslogReader> readers, TranslogWriter writer, final int maxTotalFiles) {
        long minGen = writer.generation;
        int totalFiles = 1; // for the current writer
        for (int i = readers.size() - 1; i >= 0 && totalFiles < maxTotalFiles; i--) {
            totalFiles++;
            minGen = readers.get(i).generation;
        }
        return minGen;
    }

    protected long currentTime() {
        return System.currentTimeMillis();
    }

    protected long getMinTranslogGenRequiredByLocks() {
        return translogRefCounts.keySet().stream().reduce(Math::min).orElse(Long.MAX_VALUE);
    }

    /**
     * Returns the local checkpoint of the safe commit. This value is used to calculate the min required generation for recovery.
     */
    public synchronized long getLocalCheckpointOfSafeCommit() {
        return localCheckpointOfSafeCommit;
    }

    synchronized long getTranslogRefCount(long gen) {
        final Counter counter = translogRefCounts.get(gen);
        return counter == null ? 0 : counter.get();
    }
}
