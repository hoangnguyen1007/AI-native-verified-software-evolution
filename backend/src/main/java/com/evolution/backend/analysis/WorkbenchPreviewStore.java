package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphManifest;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Ephemeral, strictly in-memory {@link GraphStore} overlay holding active Stage 1 / Stage 2
 * preliminary preview graphs.
 *
 * <p>Invariants:
 * <ul>
 *   <li>NEVER writes {@code .graph.json} or any file to disk.</li>
 *   <li>{@link #verifyPublished(ContentDigest, BooleanSupplier)} ALWAYS returns {@code false} so
 *       a preliminary graph can never satisfy canonical result reuse or authoritative publication.</li>
 *   <li>Bounded to {@code maxActiveJobs} (default 2) with a configurable post-completion grace
 *       period (default 15s) before transitioning to {@link LookupState#SUPERSEDED} (HTTP 410).</li>
 * </ul>
 */
public final class WorkbenchPreviewStore implements GraphStore {
    public static final int DEFAULT_MAX_ACTIVE_JOBS = 2;
    public static final long DEFAULT_GRACE_PERIOD_MILLIS = 15_000L;
    private static final int MAX_TOMBSTONES = 128;
    private static final int MAX_SUPERSEDED_HOPS = 16;

    public enum LookupState {
        ACTIVE,
        SUPERSEDED,
        ABSENT
    }

    public record PreviewMeta(
            String jobId,
            ContentDigest graphId,
            AnalysisJobs.TruthTier truthTier,
            int completedContexts,
            int totalContexts,
            List<String> pendingModules,
            Instant publishedAt,
            Optional<ContentDigest> supersededByGraphId) {
        public PreviewMeta {
            Objects.requireNonNull(jobId, "jobId");
            Objects.requireNonNull(graphId, "graphId");
            Objects.requireNonNull(truthTier, "truthTier");
            pendingModules = List.copyOf(pendingModules == null ? List.of() : pendingModules);
            Objects.requireNonNull(publishedAt, "publishedAt");
            Objects.requireNonNull(supersededByGraphId, "supersededByGraphId");
        }
    }

    private static final class Entry {
        final KnowledgeGraph graph;
        PreviewMeta meta;
        long expiresAtMillis; // 0 = active (no expiration yet)

        Entry(KnowledgeGraph graph, PreviewMeta meta) {
            this.graph = graph;
            this.meta = meta;
            this.expiresAtMillis = 0L;
        }
    }

    private final int maxActiveJobs;
    private final long gracePeriodMillis;
    private final LongSupplier clockMillis;

    // Access-ordered/insertion-ordered active previews keyed by jobId (bounded by maxActiveJobs)
    private final LinkedHashMap<String, Entry> byJob = new LinkedHashMap<>();
    private final LinkedHashMap<ContentDigest, Entry> byGraph = new LinkedHashMap<>();
    // Bounded tombstones for evicted/superseded preview graph IDs -> replacement graph ID if known
    private final LinkedHashMap<ContentDigest, Optional<ContentDigest>> superseded = new LinkedHashMap<>(32, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ContentDigest, Optional<ContentDigest>> eldest) {
            return size() > MAX_TOMBSTONES;
        }
    };

    public WorkbenchPreviewStore() {
        this(DEFAULT_MAX_ACTIVE_JOBS, DEFAULT_GRACE_PERIOD_MILLIS, System::currentTimeMillis);
    }

    public WorkbenchPreviewStore(int maxActiveJobs, long gracePeriodMillis) {
        this(maxActiveJobs, gracePeriodMillis, System::currentTimeMillis);
    }

    public WorkbenchPreviewStore(int maxActiveJobs, long gracePeriodMillis, LongSupplier clockMillis) {
        if (maxActiveJobs < 1 || maxActiveJobs > 16) {
            throw new IllegalArgumentException("maxActiveJobs must be between 1 and 16");
        }
        if (gracePeriodMillis < 0L || gracePeriodMillis > 300_000L) {
            throw new IllegalArgumentException("gracePeriodMillis must be between 0 and 300000");
        }
        this.maxActiveJobs = maxActiveJobs;
        this.gracePeriodMillis = gracePeriodMillis;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public synchronized PreviewMeta putPreview(
            String jobId,
            KnowledgeGraph graph,
            AnalysisJobs.TruthTier truthTier,
            int completedContexts,
            int totalContexts,
            List<String> pendingModules) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(truthTier, "truthTier");
        if (truthTier != AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE
                && truthTier != AnalysisJobs.TruthTier.PARTIAL_CONTEXTS) {
            throw new IllegalArgumentException("Preview store only accepts non-authoritative preview tiers: " + truthTier);
        }
        purgeExpiredLocked();

        var previous = byJob.remove(jobId);
        if (previous != null) {
            Optional<ContentDigest> replacement = previous.meta.graphId().equals(graph.identity())
                    ? Optional.empty()
                    : Optional.of(graph.identity());
            releaseGraphReferenceLocked(previous, replacement, !previous.meta.graphId().equals(graph.identity()));
        }

        // Evict oldest entry when capacity would be exceeded; prefer entries already in grace period.
        while (byJob.size() >= maxActiveJobs) {
            String victimJobId = selectEvictionVictimLocked();
            evictJobInternalLocked(victimJobId, Optional.empty());
        }

        superseded.remove(graph.identity());
        var meta = new PreviewMeta(
                jobId,
                graph.identity(),
                truthTier,
                Math.max(0, completedContexts),
                Math.max(0, totalContexts),
                pendingModules == null ? List.of() : List.copyOf(pendingModules),
                Instant.ofEpochMilli(clockMillis.getAsLong()),
                Optional.empty());
        var entry = new Entry(graph, meta);
        byJob.put(jobId, entry);
        byGraph.put(graph.identity(), entry);
        return meta;
    }

    public synchronized void markJobFinished(String jobId, Optional<ContentDigest> authoritativeGraphId) {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(authoritativeGraphId, "authoritativeGraphId");
        purgeExpiredLocked();
        var entry = byJob.get(jobId);
        if (entry == null) {
            return;
        }
        entry.meta = new PreviewMeta(
                entry.meta.jobId(),
                entry.meta.graphId(),
                entry.meta.truthTier(),
                entry.meta.completedContexts(),
                entry.meta.totalContexts(),
                entry.meta.pendingModules(),
                entry.meta.publishedAt(),
                authoritativeGraphId);
        if (gracePeriodMillis == 0L) {
            evictJobInternalLocked(jobId, authoritativeGraphId);
        } else {
            entry.expiresAtMillis = clockMillis.getAsLong() + gracePeriodMillis;
        }
    }

    public synchronized void evictJob(String jobId) {
        if (jobId == null) {
            return;
        }
        purgeExpiredLocked();
        evictJobInternalLocked(jobId, Optional.empty());
    }

    @Override
    public void save(KnowledgeGraph graph, BooleanSupplier cancelled) {
        throw new UnsupportedOperationException(
                "WorkbenchPreviewStore is ephemeral; use putPreview(...) for non-authoritative preview graphs");
    }

    @Override
    public synchronized Optional<KnowledgeGraph> load(ContentDigest identity) {
        Objects.requireNonNull(identity, "identity");
        purgeExpiredLocked();
        var entry = byGraph.get(identity);
        return entry == null ? Optional.empty() : Optional.of(entry.graph);
    }

    @Override
    public synchronized Optional<GraphManifest> manifest(ContentDigest identity) {
        return load(identity).map(KnowledgeGraph::manifest);
    }

    @Override
    public synchronized boolean available(ContentDigest identity) {
        Objects.requireNonNull(identity, "identity");
        purgeExpiredLocked();
        return byGraph.containsKey(identity);
    }

    /**
     * Hard truthfulness invariant: preliminary preview graphs are NEVER verified canonical
     * publications and MUST NEVER satisfy durable result reuse.
     */
    @Override
    public boolean verifyPublished(ContentDigest identity, BooleanSupplier cancelled) {
        return false;
    }

    public synchronized boolean isPreview(ContentDigest graphId) {
        Objects.requireNonNull(graphId, "graphId");
        purgeExpiredLocked();
        return byGraph.containsKey(graphId) || superseded.containsKey(graphId);
    }

    public synchronized LookupState lookupState(ContentDigest graphId) {
        Objects.requireNonNull(graphId, "graphId");
        purgeExpiredLocked();
        if (byGraph.containsKey(graphId)) {
            return LookupState.ACTIVE;
        }
        if (superseded.containsKey(graphId)) {
            return LookupState.SUPERSEDED;
        }
        return LookupState.ABSENT;
    }

    public synchronized Optional<PreviewMeta> previewMeta(ContentDigest graphId) {
        Objects.requireNonNull(graphId, "graphId");
        purgeExpiredLocked();
        var entry = byGraph.get(graphId);
        return entry == null ? Optional.empty() : Optional.of(entry.meta);
    }

    public synchronized Optional<PreviewMeta> latestForJob(String jobId) {
        Objects.requireNonNull(jobId, "jobId");
        purgeExpiredLocked();
        var entry = byJob.get(jobId);
        return entry == null ? Optional.empty() : Optional.of(entry.meta);
    }

    public synchronized Optional<ContentDigest> supersededBy(ContentDigest graphId) {
        Objects.requireNonNull(graphId, "graphId");
        purgeExpiredLocked();
        Optional<ContentDigest> first = immediateReplacementLocked(graphId);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        Set<ContentDigest> visited = new HashSet<>();
        visited.add(graphId);
        ContentDigest current = first.orElseThrow();
        for (int hop = 0; hop < MAX_SUPERSEDED_HOPS; hop++) {
            if (!visited.add(current)) {
                return Optional.empty();
            }
            Optional<ContentDigest> onward = immediateReplacementLocked(current);
            if (onward.isEmpty()) {
                // Never hand back a tombstoned intermediate preview that has no replacement.
                return superseded.containsKey(current) && !byGraph.containsKey(current)
                        ? Optional.empty()
                        : Optional.of(current);
            }
            current = onward.orElseThrow();
        }
        return superseded.containsKey(current) && !byGraph.containsKey(current)
                ? Optional.empty()
                : Optional.of(current);
    }

    private Optional<ContentDigest> immediateReplacementLocked(ContentDigest graphId) {
        var entry = byGraph.get(graphId);
        if (entry != null) {
            return entry.meta.supersededByGraphId();
        }
        return superseded.getOrDefault(graphId, Optional.empty());
    }

    public synchronized int activePreviewCount() {
        purgeExpiredLocked();
        return byGraph.size();
    }

    public synchronized void clear() {
        for (var entry : byJob.values()) {
            superseded.put(entry.meta.graphId(), entry.meta.supersededByGraphId());
        }
        byJob.clear();
        byGraph.clear();
    }

    private String selectEvictionVictimLocked() {
        // Prefer an entry already in its grace period; otherwise evict the oldest active job.
        for (var e : byJob.entrySet()) {
            if (e.getValue().expiresAtMillis > 0L) {
                return e.getKey();
            }
        }
        return byJob.keySet().iterator().next();
    }

    private void evictJobInternalLocked(String jobId, Optional<ContentDigest> replacementOverride) {
        var removed = byJob.remove(jobId);
        if (removed != null) {
            var replacement = replacementOverride.isPresent()
                    ? replacementOverride
                    : removed.meta.supersededByGraphId();
            releaseGraphReferenceLocked(removed, replacement, true);
        }
    }

    private void purgeExpiredLocked() {
        long now = clockMillis.getAsLong();
        Iterator<Map.Entry<String, Entry>> it = byJob.entrySet().iterator();
        var expired = new java.util.ArrayList<Entry>();
        while (it.hasNext()) {
            var next = it.next();
            var entry = next.getValue();
            if (entry.expiresAtMillis > 0L && now >= entry.expiresAtMillis) {
                it.remove();
                expired.add(entry);
            }
        }
        for (var entry : expired) {
            releaseGraphReferenceLocked(entry, entry.meta.supersededByGraphId(), true);
        }
    }

    private void releaseGraphReferenceLocked(Entry removed, Optional<ContentDigest> replacement, boolean recordTombstone) {
        ContentDigest graphId = removed.meta.graphId();
        Entry otherOwner = null;
        for (var active : byJob.values()) {
            if (active.meta.graphId().equals(graphId)) {
                otherOwner = active;
                break;
            }
        }
        if (otherOwner != null) {
            byGraph.put(graphId, otherOwner);
            return;
        }
        byGraph.remove(graphId, removed);
        if (recordTombstone && !byGraph.containsKey(graphId)) {
            superseded.put(graphId, replacement);
        }
    }
}
