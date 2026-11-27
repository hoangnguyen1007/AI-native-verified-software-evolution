package com.evolution.backend.analysis;

import java.util.*;

/**
 * Honest incremental delta re-analysis telemetry surfaced across Job API, CLI, and Workbench UI.
 * Every field reports directly observed reuse/recomputation counts; {@code timeSavedEstimated}
 * explicitly marks whether {@code timeSavedEstimateMs} is an estimate rather than a measured baseline.
 */
public record IncrementalSummary(
        String mode,
        String fallbackReason,
        Optional<String> baseSnapshotId,
        Optional<String> baseGraphId,
        Optional<String> baseJobId,
        long addedFiles,
        long modifiedFiles,
        long deletedFiles,
        long unchangedFiles,
        long reusedFiles,
        int reusedBuildRoots,
        int recomputedBuildRoots,
        int abiEarlyCutoffCount,
        int impactedDependentsCount,
        int reextractedJavaFiles,
        int reusedJavaFiles,
        int reusedModules,
        int recomputedModules,
        int reusedSpringContexts,
        int recomputedSpringContexts,
        long polyglotCacheHits,
        long polyglotCacheMisses,
        int reusedGraphContexts,
        int rebuiltGraphContexts,
        long timeSavedEstimateMs,
        boolean timeSavedEstimated) {

    public static final String MODE_COLD = "COLD";
    public static final String MODE_INCREMENTAL = "INCREMENTAL";
    public static final String MODE_NO_CHANGE = "NO_CHANGE";
    public static final String MODE_FALLBACK_FULL = "FALLBACK_FULL";

    public IncrementalSummary {
        mode = (mode == null || mode.isBlank()) ? MODE_COLD : mode;
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
        baseSnapshotId = baseSnapshotId == null ? Optional.empty() : baseSnapshotId;
        baseGraphId = baseGraphId == null ? Optional.empty() : baseGraphId;
        baseJobId = baseJobId == null ? Optional.empty() : baseJobId;
    }

    public static IncrementalSummary cold(String reason) {
        return new IncrementalSummary(
                MODE_COLD, reason == null ? "" : reason,
                Optional.empty(), Optional.empty(), Optional.empty(),
                0L, 0L, 0L, 0L, 0L,
                0, 0,
                0, 0, 0, 0,
                0, 0,
                0, 0,
                0L, 0L,
                0, 0,
                0L, false);
    }

    public static IncrementalSummary fallbackFull(
            String fallbackReason,
            Optional<String> baseSnapshotId,
            Optional<String> baseGraphId,
            Optional<String> baseJobId) {
        return new IncrementalSummary(
                MODE_FALLBACK_FULL, fallbackReason == null ? "" : fallbackReason,
                baseSnapshotId, baseGraphId, baseJobId,
                0L, 0L, 0L, 0L, 0L,
                0, 0,
                0, 0, 0, 0,
                0, 0,
                0, 0,
                0L, 0L,
                0, 0,
                0L, false);
    }

    public Map<String, Object> toMap() {
        var map = new LinkedHashMap<String, Object>();
        map.put("mode", mode);
        map.put("fallbackReason", fallbackReason);
        map.put("baseSnapshotId", baseSnapshotId);
        map.put("baseGraphId", baseGraphId);
        map.put("baseJobId", baseJobId);
        map.put("addedFiles", addedFiles);
        map.put("modifiedFiles", modifiedFiles);
        map.put("deletedFiles", deletedFiles);
        map.put("unchangedFiles", unchangedFiles);
        map.put("reusedFiles", reusedFiles);
        map.put("reusedBuildRoots", reusedBuildRoots);
        map.put("recomputedBuildRoots", recomputedBuildRoots);
        map.put("abiEarlyCutoffCount", abiEarlyCutoffCount);
        map.put("impactedDependentsCount", impactedDependentsCount);
        map.put("reextractedJavaFiles", reextractedJavaFiles);
        map.put("reusedJavaFiles", reusedJavaFiles);
        map.put("reusedModules", reusedModules);
        map.put("recomputedModules", recomputedModules);
        map.put("reusedSpringContexts", reusedSpringContexts);
        map.put("recomputedSpringContexts", recomputedSpringContexts);
        map.put("polyglotCacheHits", polyglotCacheHits);
        map.put("polyglotCacheMisses", polyglotCacheMisses);
        map.put("reusedGraphContexts", reusedGraphContexts);
        map.put("rebuiltGraphContexts", rebuiltGraphContexts);
        map.put("timeSavedEstimateMs", timeSavedEstimateMs);
        map.put("timeSavedEstimated", timeSavedEstimated);
        return Collections.unmodifiableMap(map);
    }
}
