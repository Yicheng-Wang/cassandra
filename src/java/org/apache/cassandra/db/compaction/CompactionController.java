/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.compaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;

import com.google.common.base.Predicates;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.AbstractCompactionController;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.db.memtable.Memtable;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.schema.CompactionParams.TombstoneOption;
import org.apache.cassandra.utils.OverlapIterator;
import org.apache.cassandra.utils.concurrent.Refs;

import static org.apache.cassandra.config.CassandraRelevantProperties.NEVER_PURGE_TOMBSTONES;
import static org.apache.cassandra.db.lifecycle.SSTableIntervalTree.buildIntervals;

/**
 * Manage compaction options.
 */
public class CompactionController extends AbstractCompactionController
{
    private static final Logger logger = LoggerFactory.getLogger(CompactionController.class);
    static final boolean NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE = NEVER_PURGE_TOMBSTONES.getBoolean();

    private final boolean compactingRepaired;
    // note that overlapIterator and overlappingSSTables will be null if NEVER_PURGE_TOMBSTONES is set - this is a
    // good thing so that noone starts using them and thinks that if overlappingSSTables is empty, there
    // is no overlap.
    private Refs<SSTableReader> overlappingSSTables;
    private OverlapIterator<PartitionPosition, SSTableReader> overlapIterator;
    private final Iterable<SSTableReader> compacting;
    private final RateLimiter limiter;
    private final long minTimestamp;
    final Map<SSTableReader, FileDataInput> openDataFiles = new HashMap<>();

    protected CompactionController(ColumnFamilyStore cfs, long maxValue)
    {
        this(cfs, null, maxValue);
    }

    public CompactionController(ColumnFamilyStore cfs, Set<SSTableReader> compacting, long gcBefore)
    {
        this(cfs, compacting, gcBefore, null,
             cfs.getCompactionStrategyManager().getCompactionParams().tombstoneOption());
    }

    public CompactionController(ColumnFamilyStore cfs, Set<SSTableReader> compacting, long gcBefore, RateLimiter limiter, TombstoneOption tombstoneOption)
    {
        //When making changes to the method, be aware that some of the state of the controller may still be uninitialized
        //(e.g. TWCS sets up the value of ignoreOverlaps() after this completes)
        super(cfs, gcBefore, tombstoneOption);
        this.compacting = compacting;
        this.limiter = limiter;
        compactingRepaired = compacting != null && compacting.stream().allMatch(SSTableReader::isRepaired);
        this.minTimestamp = compacting != null && !compacting.isEmpty()       // check needed for test
                          ? compacting.stream().mapToLong(SSTableReader::getMinTimestamp).min().getAsLong()
                          : 0;
        refreshOverlaps();
        if (NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE)
            logger.warn("You are running with -D{}=true, this is dangerous!", NEVER_PURGE_TOMBSTONES.getKey());
    }

    public void maybeRefreshOverlaps()
    {
        if (NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE)
        {
            logger.debug("not refreshing overlaps - running with -D{}=true", NEVER_PURGE_TOMBSTONES.getKey());
            return;
        }

        if (cfs.getNeverPurgeTombstones())
        {
            logger.debug("not refreshing overlaps for {}.{} - neverPurgeTombstones is enabled", cfs.getKeyspaceName(), cfs.getTableName());
            return;
        }

        if (overlappingSSTables == null || overlappingSSTables.stream().anyMatch(SSTableReader::isMarkedCompacted))
            refreshOverlaps();
    }

    void refreshOverlaps()
    {
        if (NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE || cfs.getNeverPurgeTombstones())
            return;

        if (this.overlappingSSTables != null)
            close();

        if (compacting == null)
            overlappingSSTables = Refs.tryRef(Collections.<SSTableReader>emptyList());
        else
            overlappingSSTables = cfs.getAndReferenceOverlappingLiveSSTables(compacting);
        this.overlapIterator = new OverlapIterator<>(buildIntervals(overlappingSSTables));
    }

    public Set<SSTableReader> getFullyExpiredSSTables()
    {
        return getFullyExpiredSSTables(cfs, compacting, overlappingSSTables, gcBefore, ignoreOverlaps());
    }

    /**
     * Finds expired sstables
     *
     * works something like this;
     * 1. find "global" minTimestamp of overlapping sstables, compacting sstables and memtables containing any non-expired data
     * 2. build a list of fully expired candidates
     * 3. check if the candidates to be dropped actually can be dropped {@code (maxTimestamp < global minTimestamp)}
     *    - if not droppable, remove from candidates
     * 4. return candidates.
     *
     * @param cfStore
     * @param compacting we take the drop-candidates from this set, it is usually the sstables included in the compaction
     * @param overlapping the sstables that overlap the ones in compacting.
     * @param gcBefore
     * @param ignoreOverlaps don't check if data shadows/overlaps any data in other sstables
     * @return
     */
    public static Set<SSTableReader> getFullyExpiredSSTables(ColumnFamilyStore cfStore,
                                                             Iterable<SSTableReader> compacting,
                                                             Iterable<SSTableReader> overlapping,
                                                             long gcBefore,
                                                             boolean ignoreOverlaps)
    {
        logger.trace("Checking droppable sstables in {}", cfStore);

        if (NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE || compacting == null || cfStore.getNeverPurgeTombstones() || overlapping == null)
            return Collections.emptySet();

        if (cfStore.getCompactionStrategyManager().onlyPurgeRepairedTombstones() && !Iterables.all(compacting, SSTableReader::isRepaired))
            return Collections.emptySet();

        if (ignoreOverlaps)
        {
            Set<SSTableReader> fullyExpired = new HashSet<>();
            for (SSTableReader candidate : compacting)
            {
                if (candidate.getMaxLocalDeletionTime() < gcBefore)
                {
                    fullyExpired.add(candidate);
                    logger.trace("Dropping overlap ignored expired SSTable {} (maxLocalDeletionTime={}, gcBefore={})",
                                 candidate, candidate.getMaxLocalDeletionTime(), gcBefore);
                }
            }
            return fullyExpired;
        }

        List<SSTableReader> candidates = new ArrayList<>();
        long minTimestamp = Long.MAX_VALUE;

        for (SSTableReader sstable : overlapping)
        {
            // Overlapping might include fully expired sstables. What we care about here is
            // the min timestamp of the overlapping sstables that actually contain live data.
            if (sstable.getMaxLocalDeletionTime() >= gcBefore)
                minTimestamp = Math.min(minTimestamp, sstable.getMinTimestamp());
        }

        for (SSTableReader candidate : compacting)
        {
            if (candidate.getMaxLocalDeletionTime() < gcBefore)
                candidates.add(candidate);
            else
                minTimestamp = Math.min(minTimestamp, candidate.getMinTimestamp());
        }

        for (Memtable memtable : cfStore.getTracker().getView().getAllMemtables())
        {
            if (memtable.getMinTimestamp() != Memtable.NO_MIN_TIMESTAMP)
                minTimestamp = Math.min(minTimestamp, memtable.getMinTimestamp());
        }

        // At this point, minTimestamp denotes the lowest timestamp of any relevant
        // SSTable or Memtable that contains a constructive value. candidates contains all the
        // candidates with no constructive values. The ones out of these that have
        // (getMaxTimestamp() < minTimestamp) serve no purpose anymore.

        Iterator<SSTableReader> iterator = candidates.iterator();
        while (iterator.hasNext())
        {
            SSTableReader candidate = iterator.next();
            if (candidate.getMaxTimestamp() >= minTimestamp)
            {
                iterator.remove();
            }
            else
            {
               logger.trace("Dropping expired SSTable {} (maxLocalDeletionTime={}, gcBefore={})",
                        candidate, candidate.getMaxLocalDeletionTime(), gcBefore);
            }
        }
        return new HashSet<>(candidates);
    }

    public static Set<SSTableReader> getFullyExpiredSSTables(ColumnFamilyStore cfStore,
                                                             Iterable<SSTableReader> compacting,
                                                             Iterable<SSTableReader> overlapping,
                                                             long gcBefore)
    {
        return getFullyExpiredSSTables(cfStore, compacting, overlapping, gcBefore, false);
    }

    /**
     * @param key
     * @return a predicate for whether tombstones marked for deletion at the given time for the given partition are
     * purgeable; we calculate this by checking whether the deletion time is less than the min timestamp of all SSTables
     * containing his partition and not participating in the compaction. This means there isn't any data in those
     * sstables that might still need to be suppressed by a tombstone at this timestamp.
     */
    @Override
    public LongPredicate getPurgeEvaluator(DecoratedKey key)
    {
        if (NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE || !compactingRepaired() || cfs.getNeverPurgeTombstones() || overlapIterator == null)
            return time -> false;

        overlapIterator.update(key);
        Set<SSTableReader> filteredSSTables = overlapIterator.overlaps();
        Iterable<Memtable> memtables = cfs.getTracker().getView().getAllMemtables();
        long minTimestampSeen = Long.MAX_VALUE;
        boolean hasTimestamp = false;

        for (SSTableReader sstable: filteredSSTables)
        {
            if (sstable.mayContainAssumingKeyIsInRange(key))
            {
                minTimestampSeen = Math.min(minTimestampSeen, sstable.getMinTimestamp());
                hasTimestamp = true;
            }
        }

        for (Memtable memtable : memtables)
        {
            if (memtable.getMinTimestamp() != Memtable.NO_MIN_TIMESTAMP)
            {
                if (memtable.rowIterator(key) != null)
                {
                    minTimestampSeen = Math.min(minTimestampSeen, memtable.getMinTimestamp());
                    hasTimestamp = true;
                }
            }
        }

        if (!hasTimestamp)
            return time -> true;
        else
        {
            final long finalTimestamp = minTimestampSeen;
            return time -> time < finalTimestamp;
        }
    }

    public void close()
    {
        if (overlappingSSTables != null)
            overlappingSSTables.release();

        FileUtils.closeQuietly(openDataFiles.values());
        openDataFiles.clear();
    }

    public boolean compactingRepaired()
    {
        return !cfs.getCompactionStrategyManager().onlyPurgeRepairedTombstones() || compactingRepaired;
    }

    boolean provideTombstoneSources()
    {
        return tombstoneOption != TombstoneOption.NONE;
    }

    // caller must close iterators
    public Iterable<UnfilteredRowIterator> shadowSources(DecoratedKey key, boolean tombstoneOnly)
    {
        if (!provideTombstoneSources() || !compactingRepaired() || NEVER_PURGE_TOMBSTONES_PROPERTY_VALUE || cfs.getNeverPurgeTombstones())
            return null;
        overlapIterator.update(key);
        return Iterables.filter(Iterables.transform(overlapIterator.overlaps(),
                                                    reader -> getShadowIterator(reader, key, tombstoneOnly)),
                                Predicates.notNull());
    }

    @SuppressWarnings("resource") // caller to close
    private UnfilteredRowIterator getShadowIterator(SSTableReader reader, DecoratedKey key, boolean tombstoneOnly)
    {
        if (reader.isMarkedSuspect() ||
            reader.getMaxTimestamp() <= minTimestamp ||
            tombstoneOnly && !reader.mayHaveTombstones())
            return null;
        long position = reader.getPosition(key, SSTableReader.Operator.EQ);
        if (position < 0)
            return null;
        FileDataInput dfile = openDataFiles.computeIfAbsent(reader, this::openDataFile);
        return reader.simpleIterator(dfile, key, position, tombstoneOnly);
    }

    /**
     * Is overlapped sstables ignored
     *
     * Control whether or not we are taking into account overlapping sstables when looking for fully expired sstables.
     * In order to reduce the amount of work needed, we look for sstables that can be dropped instead of compacted.
     * As a safeguard mechanism, for each time range of data in a sstable, we are checking globally to see if all data
     * of this time range is fully expired before considering to drop the sstable.
     * This strategy can retain for a long time a lot of sstables on disk (see CASSANDRA-13418) so this option
     * control whether or not this check should be ignored.
     *
     * Do NOT call this method in the CompactionController constructor
     *
     * @return false by default
     */
    protected boolean ignoreOverlaps()
    {
        return false;
    }

    private FileDataInput openDataFile(SSTableReader reader)
    {
        return limiter != null ? reader.openDataReader(limiter) : reader.openDataReader();
    }
}
