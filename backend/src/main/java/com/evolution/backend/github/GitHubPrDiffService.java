package com.evolution.backend.github;

import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.AnalysisMode;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.query.ArchitectureInsightsAdvisor;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.PublishedReports;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 1-click GitHub Pull Request and branch-to-branch Architecture Evolution Diff orchestrator.
 *
 * <p>Analyzes base and head refs through {@link AnalysisJobs} (reusing incremental mirrors and
 * canonical publication stores), computes Architecture Score v2 deltas across D1-D7, tracks
 * semantic gap deltas and changed-file blast radius, renders a Markdown review report, and gates
 * PR comment publication behind explicit user confirmation + pinned {@code diffDigest}.
 */
public final class GitHubPrDiffService {
    public static final String SCHEMA = "github.pr-architecture-diff:v1";
    private static final int MAX_CACHED_DIFFS = 64;
    private static final long JOB_AWAIT_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(15);

    public record DimensionDelta(
            String id,
            String displayName,
            Optional<BigDecimal> baseScore,
            Optional<BigDecimal> headScore,
            Optional<BigDecimal> delta,
            String baseStatus,
            String headStatus) {
    }

    public record BlastRadiusEntry(
            String filePath,
            String symbol,
            String symbolLabel,
            int directDependents,
            int transitiveDependents,
            boolean truncated) {
        public BlastRadiusEntry(String filePath, String symbol, int directDependents, int transitiveDependents, boolean truncated) {
            this(filePath, symbol, symbol, directDependents, transitiveDependents, truncated);
        }

        public BlastRadiusEntry {
            symbolLabel = (symbolLabel == null || symbolLabel.isBlank()) ? symbol : symbolLabel;
        }
    }

    public record ViolationsDelta(int resolved, int introduced, int persisted) {
        public static ViolationsDelta empty() {
            return new ViolationsDelta(0, 0, 0);
        }

        public Map<String, Object> toMap() {
            return Map.of("resolved", resolved, "introduced", introduced, "persisted", persisted);
        }
    }

    public record GapDelta(
            int baseTotalGaps,
            int headTotalGaps,
            int deltaGaps,
            Map<String, Integer> addedByReason,
            Map<String, Integer> resolvedByReason) {
    }

    public record PrFileSnapshot(int pullNumber, String title, List<String> changedFiles) {
        public PrFileSnapshot {
            title = title == null ? "" : title;
            changedFiles = List.copyOf(changedFiles == null ? List.of() : changedFiles);
        }
    }

    public record TouchedCommunity(
            String communityId,
            String label,
            String dominantPackage,
            int memberCount,
            BigDecimal cohesion,
            List<String> changedFiles) {
        public TouchedCommunity {
            changedFiles = List.copyOf(changedFiles);
        }
    }

    public record PeerPrOverlap(
            int peerPullNumber,
            String peerTitle,
            List<String> sharedCommunityIds,
            List<String> sharedCommunityLabels,
            int directFileOverlaps,
            boolean semanticOnlyOverlap,
            String riskLevel) {
        public PeerPrOverlap {
            sharedCommunityIds = List.copyOf(sharedCommunityIds);
            sharedCommunityLabels = List.copyOf(sharedCommunityLabels);
        }
    }

    public record CommunityOverlapReport(
            int touchedCommunityCount,
            List<TouchedCommunity> touchedCommunities,
            List<PeerPrOverlap> peerPrOverlaps,
            String riskLevel,
            String summary) {
        public static CommunityOverlapReport empty() {
            return new CommunityOverlapReport(0, List.of(), List.of(), "LOW", "No Leiden community overlap detected.");
        }

        public CommunityOverlapReport {
            touchedCommunities = List.copyOf(touchedCommunities == null ? List.of() : touchedCommunities);
            peerPrOverlaps = List.copyOf(peerPrOverlaps == null ? List.of() : peerPrOverlaps);
            riskLevel = riskLevel == null ? "LOW" : riskLevel;
            summary = summary == null ? "" : summary;
        }
    }

    public record PrDiffResult(
            String schema,
            String diffId,
            ContentDigest diffDigest,
            String setup,
            String repository,
            OptionalInt pullNumber,
            Optional<String> pullTitle,
            Optional<String> pullUrl,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            String baseJobId,
            String headJobId,
            ContentDigest baseGraphId,
            ContentDigest headGraphId,
            Optional<BigDecimal> baseScore,
            Optional<BigDecimal> headScore,
            Optional<BigDecimal> scoreDelta,
            String baseHealthBand,
            String headHealthBand,
            String baseConfidenceTier,
            String headConfidenceTier,
            List<DimensionDelta> dimensionDeltas,
            GapDelta gapDelta,
            List<String> changedFiles,
            List<BlastRadiusEntry> blastRadius,
            String markdownReport,
            Instant generatedAt,
            CommunityOverlapReport communityOverlap,
            ViolationsDelta violationsDelta) {
        public PrDiffResult(
                String schema,
                String diffId,
                ContentDigest diffDigest,
                String setup,
                String repository,
                OptionalInt pullNumber,
                Optional<String> pullTitle,
                Optional<String> pullUrl,
                String baseRef,
                String baseSha,
                String headRef,
                String headSha,
                String baseJobId,
                String headJobId,
                ContentDigest baseGraphId,
                ContentDigest headGraphId,
                Optional<BigDecimal> baseScore,
                Optional<BigDecimal> headScore,
                Optional<BigDecimal> scoreDelta,
                String baseHealthBand,
                String headHealthBand,
                String baseConfidenceTier,
                String headConfidenceTier,
                List<DimensionDelta> dimensionDeltas,
                GapDelta gapDelta,
                List<String> changedFiles,
                List<BlastRadiusEntry> blastRadius,
                String markdownReport,
                Instant generatedAt) {
            this(schema, diffId, diffDigest, setup, repository, pullNumber, pullTitle, pullUrl,
                    baseRef, baseSha, headRef, headSha, baseJobId, headJobId, baseGraphId, headGraphId,
                    baseScore, headScore, scoreDelta, baseHealthBand, headHealthBand, baseConfidenceTier,
                    headConfidenceTier, dimensionDeltas, gapDelta, changedFiles, blastRadius, markdownReport,
                    generatedAt, CommunityOverlapReport.empty(), ViolationsDelta.empty());
        }

        public PrDiffResult(
                String schema,
                String diffId,
                ContentDigest diffDigest,
                String setup,
                String repository,
                OptionalInt pullNumber,
                Optional<String> pullTitle,
                Optional<String> pullUrl,
                String baseRef,
                String baseSha,
                String headRef,
                String headSha,
                String baseJobId,
                String headJobId,
                ContentDigest baseGraphId,
                ContentDigest headGraphId,
                Optional<BigDecimal> baseScore,
                Optional<BigDecimal> headScore,
                Optional<BigDecimal> scoreDelta,
                String baseHealthBand,
                String headHealthBand,
                String baseConfidenceTier,
                String headConfidenceTier,
                List<DimensionDelta> dimensionDeltas,
                GapDelta gapDelta,
                List<String> changedFiles,
                List<BlastRadiusEntry> blastRadius,
                String markdownReport,
                Instant generatedAt,
                CommunityOverlapReport communityOverlap) {
            this(schema, diffId, diffDigest, setup, repository, pullNumber, pullTitle, pullUrl,
                    baseRef, baseSha, headRef, headSha, baseJobId, headJobId, baseGraphId, headGraphId,
                    baseScore, headScore, scoreDelta, baseHealthBand, headHealthBand, baseConfidenceTier,
                    headConfidenceTier, dimensionDeltas, gapDelta, changedFiles, blastRadius, markdownReport,
                    generatedAt, communityOverlap, ViolationsDelta.empty());
        }

        public PrDiffResult {
            communityOverlap = communityOverlap == null ? CommunityOverlapReport.empty() : communityOverlap;
            violationsDelta = violationsDelta == null ? ViolationsDelta.empty() : violationsDelta;
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("schema", schema);
            map.put("diffId", diffId);
            map.put("diffDigest", diffDigest.value());
            map.put("setup", setup);
            map.put("repository", repository);
            map.put("pullNumber", pullNumber.isPresent() ? pullNumber.getAsInt() : null);
            map.put("pullTitle", pullTitle.orElse(null));
            map.put("pullUrl", pullUrl.orElse(null));
            map.put("baseRef", baseRef);
            map.put("baseSha", baseSha);
            map.put("headRef", headRef);
            map.put("headSha", headSha);
            map.put("baseJobId", baseJobId);
            map.put("headJobId", headJobId);
            map.put("baseGraphId", baseGraphId.value());
            map.put("headGraphId", headGraphId.value());
            map.put("baseScore", baseScore.orElse(null));
            map.put("headScore", headScore.orElse(null));
            map.put("scoreDelta", scoreDelta.orElse(null));
            map.put("baseHealthBand", baseHealthBand);
            map.put("headHealthBand", headHealthBand);
            map.put("baseConfidenceTier", baseConfidenceTier);
            map.put("headConfidenceTier", headConfidenceTier);
            map.put("dimensionDeltas", dimensionDeltas);
            map.put("gapDelta", gapDelta);
            map.put("changedFiles", changedFiles);
            map.put("blastRadius", blastRadius);
            map.put("communityOverlap", communityOverlap);
            map.put("markdownReport", markdownReport);
            map.put("reviewMarkdown", markdownReport);
            map.put("status", "COMPLETE");
            String verdict = scoreDelta.isPresent()
                    ? (scoreDelta.orElseThrow().signum() < 0 ? "REGRESSION"
                            : (scoreDelta.orElseThrow().signum() > 0 ? "IMPROVED" : "CLEAN"))
                    : "COMPLETE";
            map.put("verdict", verdict);
            map.put("summary", "Compared " + baseRef + " -> " + headRef
                    + (scoreDelta.isPresent() ? " (Score v2 delta " + (scoreDelta.orElseThrow().signum() >= 0 ? "+" : "") + scoreDelta.orElseThrow() + ")" : "")
                    + ", capability gaps " + gapDelta.baseTotalGaps() + " -> " + gapDelta.headTotalGaps() + ".");
            var scoreV2Delta = new LinkedHashMap<String, Object>();
            scoreV2Delta.put("before", baseScore.orElse(null));
            scoreV2Delta.put("after", headScore.orElse(null));
            scoreV2Delta.put("delta", scoreDelta.orElse(null));
            scoreV2Delta.put("beforeBand", baseHealthBand);
            scoreV2Delta.put("afterBand", headHealthBand);
            var uiDims = new ArrayList<Map<String, Object>>(dimensionDeltas.size());
            for (var d : dimensionDeltas) {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", d.id());
                row.put("displayName", d.displayName());
                row.put("before", d.baseScore().orElse(null));
                row.put("after", d.headScore().orElse(null));
                row.put("delta", d.delta().orElse(null));
                uiDims.add(row);
            }
            scoreV2Delta.put("dimensions", uiDims);
            map.put("scoreV2Delta", scoreV2Delta);
            map.put("violationsDelta", violationsDelta.toMap());
            map.put("capabilityGapsDelta", Map.of(
                    "before", gapDelta.baseTotalGaps(),
                    "after", gapDelta.headTotalGaps(),
                    "delta", gapDelta.deltaGaps()));
            map.put("generatedAt", generatedAt);
            return map;
        }
    }

    private record OwnedDiff(String ownerUserId, PrDiffResult result) {
    }

    private final GitHubRestClient restClient;
    private final AnalysisJobs jobs;
    private final RepositoryConnections connections;
    private final GraphStore graphs;
    private final PublishedReports reports;
    private final Path diffStoreDir;
    private final Map<String, OwnedDiff> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, OwnedDiff> eldest) {
                    return size() > MAX_CACHED_DIFFS;
                }
            });

    public GitHubPrDiffService(
            GitHubRestClient restClient,
            AnalysisJobs jobs,
            RepositoryConnections connections,
            GraphStore graphs,
            PublishedReports reports,
            Path diffStoreDir) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.reports = Objects.requireNonNull(reports, "reports");
        this.diffStoreDir = diffStoreDir;
    }

    public PrDiffResult compare(
            String setupId,
            OptionalInt pullNumber,
            Optional<String> baseRefOverride,
            Optional<String> headRefOverride,
            AnalysisMode mode,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(setupId, "setupId");
        Objects.requireNonNull(pullNumber, "pullNumber");
        Objects.requireNonNull(mode, "mode");
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;

        String remoteUrl = connections.remoteUrl(setupId)
                .orElseThrow(() -> new IllegalArgumentException("Connection is not a remote GitHub repository"));
        var coords = GitHubCoordinates.parse(remoteUrl);

        String baseRef;
        String baseSha;
        String headRef;
        String headSha;
        String fetchBaseRef;
        String fetchHeadRef;
        Optional<String> pullTitle = Optional.empty();
        Optional<String> pullUrl = Optional.empty();
        var changedFiles = new ArrayList<String>();
        var openPeerPrs = new ArrayList<PrFileSnapshot>();

        if (pullNumber.isPresent()) {
            int prNum = GitHubCoordinates.validatePullNumber(pullNumber.getAsInt());
            var pr = restClient.pull(coords, prNum, cancelCheck);
            baseRef = pr.baseRef();
            baseSha = pr.baseSha();
            headRef = pr.headRef();
            headSha = pr.headSha();
            fetchBaseRef = baseSha.isBlank() ? baseRef : baseSha;
            fetchHeadRef = pr.isFork() ? ("refs/pull/" + prNum + "/head") : (headSha.isBlank() ? headRef : headSha);
            pullTitle = Optional.ofNullable(pr.title());
            pullUrl = Optional.ofNullable(pr.htmlUrl());
            try {
                for (var f : restClient.pullFiles(coords, prNum, 100, cancelCheck)) {
                    if (!f.filename().isBlank()) {
                        changedFiles.add(f.filename());
                    }
                }
            } catch (GitHubRestClient.GitHubApiException ignored) {
                // Best-effort PR changed files list.
            }
            // Best-effort fetch of up to 5 open peer PRs for Leiden semantic community overlap detection
            try {
                for (var peer : restClient.pulls(coords, "open", 6, cancelCheck)) {
                    if (cancelCheck.getAsBoolean() || openPeerPrs.size() >= 5) break;
                    if (peer.number() == prNum) continue;
                    try {
                        var peerFiles = restClient.pullFiles(coords, peer.number(), 100, cancelCheck).stream()
                                .map(GitHubRestClient.PullFileInfo::filename)
                                .filter(fn -> fn != null && !fn.isBlank())
                                .toList();
                        if (!peerFiles.isEmpty()) {
                            openPeerPrs.add(new PrFileSnapshot(peer.number(), peer.title(), peerFiles));
                        }
                    } catch (RuntimeException ignored) {
                        // Skip unreadable peer PR files cleanly.
                    }
                }
            } catch (RuntimeException ignored) {
                // Peer PR overlap enrichment is strictly best-effort.
            }
        } else {
            if (headRefOverride == null || headRefOverride.isEmpty() || headRefOverride.orElseThrow().isBlank()) {
                throw new IllegalArgumentException("Either pullNumber or headRef is required");
            }
            headRef = GitHubCoordinates.validateRef(headRefOverride.orElseThrow());
            if (baseRefOverride != null && baseRefOverride.isPresent() && !baseRefOverride.orElseThrow().isBlank()) {
                baseRef = GitHubCoordinates.validateRef(baseRefOverride.orElseThrow());
            } else {
                baseRef = restClient.repository(coords, cancelCheck).defaultBranch();
            }
            baseSha = resolveCommitSha(coords, baseRef, cancelCheck).orElse(baseRef);
            headSha = resolveCommitSha(coords, headRef, cancelCheck).orElse(headRef);
            fetchBaseRef = baseRef;
            fetchHeadRef = headRef;
        }

        // Submit deterministic idempotent jobs for base & head refs using incremental mirror
        String baseKey = jobKey("gh-base", setupId, fetchBaseRef, mode);
        String headKey = jobKey("gh-head", setupId, fetchHeadRef, mode);
        var baseJob = jobs.submit(setupId, baseKey, false, mode, Optional.of(fetchBaseRef)).job();
        var headJob = jobs.submit(setupId, headKey, false, mode, Optional.of(fetchHeadRef)).job();

        var baseDone = awaitTerminal(UUID.fromString(baseJob.id()), cancelCheck);
        var headDone = awaitTerminal(UUID.fromString(headJob.id()), cancelCheck);

        ContentDigest baseGraphId = baseDone.result().flatMap(AnalysisJobs.Result::graph)
                .orElseThrow(() -> new IllegalStateException("BASE_ANALYSIS_FAILED:" + baseDone.state()));
        ContentDigest headGraphId = headDone.result().flatMap(AnalysisJobs.Result::graph)
                .orElseThrow(() -> new IllegalStateException("HEAD_ANALYSIS_FAILED:" + headDone.state()));

        return compareGraphs(setupId, coords.fullName(), pullNumber, pullTitle, pullUrl,
                baseRef, baseSha, headRef, headSha, baseDone.id(), headDone.id(),
                baseGraphId, headGraphId, changedFiles, openPeerPrs, cancelCheck);
    }

    /**
     * Direct graph-to-graph comparison helper usable by both live PR comparison and CLI/offline tests.
     */
    public PrDiffResult compareGraphs(
            String setupId,
            String repoFullName,
            OptionalInt pullNumber,
            Optional<String> pullTitle,
            Optional<String> pullUrl,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            String baseJobId,
            String headJobId,
            ContentDigest baseGraphId,
            ContentDigest headGraphId,
            List<String> changedFiles,
            BooleanSupplier cancelled) {
        return compareGraphs(setupId, repoFullName, pullNumber, pullTitle, pullUrl,
                baseRef, baseSha, headRef, headSha, baseJobId, headJobId,
                baseGraphId, headGraphId, changedFiles, List.of(), cancelled);
    }

    /**
     * Direct graph-to-graph comparison helper supporting concurrent open peer PRs for Leiden
     * semantic community overlap detection.
     */
    public PrDiffResult compareGraphs(
            String setupId,
            String repoFullName,
            OptionalInt pullNumber,
            Optional<String> pullTitle,
            Optional<String> pullUrl,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            String baseJobId,
            String headJobId,
            ContentDigest baseGraphId,
            ContentDigest headGraphId,
            List<String> changedFiles,
            List<PrFileSnapshot> openPeerPrs,
            BooleanSupplier cancelled) {
        BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
        var baseGraph = graphs.load(baseGraphId)
                .orElseThrow(() -> new IllegalStateException("BASE_GRAPH_UNAVAILABLE"));
        var headGraph = graphs.load(headGraphId)
                .orElseThrow(() -> new IllegalStateException("HEAD_GRAPH_UNAVAILABLE"));

        var guard = ArchitectureQueryService.operationalGuard(cancelCheck,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(120), 8_000_000);
        var baseQueries = new ArchitectureMetricsService(baseGraph, ArchitectureMetric.Limits.defaults(), guard,
                Optional.empty(), List.of()).queries();
        var headQueries = new ArchitectureMetricsService(headGraph, ArchitectureMetric.Limits.defaults(), guard,
                Optional.empty(), List.of()).queries();

        var scorer = new ArchitectureAssessmentV2Service();
        var model = ArchitectureScoreModelV2.standard();
        var baseAssess = scorer.assessRepository(baseQueries, Map.of(), Optional.empty(), model);
        var headAssess = scorer.assessRepository(headQueries, Map.of(), Optional.empty(), model);

        Optional<BigDecimal> scoreDelta = (baseAssess.score().isPresent() && headAssess.score().isPresent())
                ? Optional.of(headAssess.score().orElseThrow().subtract(baseAssess.score().orElseThrow())
                        .setScale(2, RoundingMode.HALF_UP))
                : Optional.empty();

        var dimDeltas = computeDimensionDeltas(baseAssess, headAssess);
        var gapDelta = computeGapDelta(baseGraph, headGraph);
        var violationsDelta = computeViolationsDelta(baseGraph, headGraph, baseGraphId, headGraphId);
        List<String> effectiveChanged = (changedFiles == null || changedFiles.isEmpty())
                ? diffSnapshotChangedFiles(baseGraph, headGraph)
                : changedFiles;
        var safeChanged = effectiveChanged.stream()
                .filter(f -> f != null && !f.isBlank())
                .map(GitHubPrDiffService::normalizeGitPath)
                .distinct()
                .sorted()
                .toList();
        var blastRadius = computeBlastRadius(headGraph, headQueries, safeChanged, cancelCheck);
        var communityOverlap = analyzeCommunityOverlap(headGraph, headQueries, safeChanged,
                openPeerPrs == null ? List.of() : openPeerPrs);

        var canonicalPayload = new LinkedHashMap<String, Object>();
        canonicalPayload.put("schema", SCHEMA);
        canonicalPayload.put("repository", repoFullName);
        canonicalPayload.put("pullNumber", pullNumber.isPresent() ? pullNumber.getAsInt() : null);
        canonicalPayload.put("baseRef", baseRef);
        canonicalPayload.put("baseSha", baseSha);
        canonicalPayload.put("headRef", headRef);
        canonicalPayload.put("headSha", headSha);
        canonicalPayload.put("baseGraphId", baseGraphId.value());
        canonicalPayload.put("headGraphId", headGraphId.value());
        canonicalPayload.put("baseScore", baseAssess.score().orElse(null));
        canonicalPayload.put("headScore", headAssess.score().orElse(null));
        canonicalPayload.put("dimensionDeltas", dimDeltas);
        canonicalPayload.put("gapDelta", gapDelta);
        canonicalPayload.put("violationsDelta", violationsDelta);
        canonicalPayload.put("blastRadius", blastRadius);
        canonicalPayload.put("communityOverlap", communityOverlap);
        ContentDigest diffDigest = ContentDigest.sha256Utf8(CanonicalJson.write(canonicalPayload));
        String diffId = diffDigest.value().substring(7, 31);

        String markdown = renderMarkdown(repoFullName, pullNumber, pullTitle, baseRef, baseSha, headRef, headSha,
                baseGraphId, headGraphId, baseAssess, headAssess, scoreDelta, dimDeltas, gapDelta,
                violationsDelta, safeChanged, blastRadius, communityOverlap, diffDigest);

        var result = new PrDiffResult(
                SCHEMA,
                diffId,
                diffDigest,
                setupId,
                repoFullName,
                pullNumber,
                pullTitle == null ? Optional.empty() : pullTitle,
                pullUrl == null ? Optional.empty() : pullUrl,
                baseRef,
                baseSha,
                headRef,
                headSha,
                baseJobId,
                headJobId,
                baseGraphId,
                headGraphId,
                baseAssess.score(),
                headAssess.score(),
                scoreDelta,
                baseAssess.healthBand(),
                headAssess.healthBand(),
                baseAssess.confidence().tier().name(),
                headAssess.confidence().tier().name(),
                dimDeltas,
                gapDelta,
                safeChanged,
                blastRadius,
                markdown,
                Instant.now(),
                communityOverlap,
                violationsDelta);

        String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        cache.put(cacheKey(ownerUserId, diffId), new OwnedDiff(ownerUserId, result));
        persistDiffReceipt(result);
        return result;
    }

    private static String cacheKey(String ownerUserId, String diffId) {
        return (ownerUserId == null || ownerUserId.isBlank()
                ? com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID
                : ownerUserId) + "\0" + diffId;
    }

    public Optional<PrDiffResult> get(String diffId) {
        if (diffId == null || !diffId.matches("[a-f0-9]{16,64}")) {
            return Optional.empty();
        }
        String caller = com.evolution.backend.auth.RequestContext.currentUserId();
        OwnedDiff owned = cache.get(cacheKey(caller, diffId));
        if (owned != null) {
            return Optional.of(owned.result());
        }
        synchronized (cache) {
            for (OwnedDiff entry : cache.values()) {
                if (entry.result().diffId().equals(diffId)) {
                    if (!entry.ownerUserId().equals(caller)) {
                        throw new SecurityException("ACCESS_DENIED");
                    }
                    return Optional.of(entry.result());
                }
            }
        }
        return Optional.empty();
    }

    public Optional<PrDiffResult> latestForPull(String setupId, int pullNumber, String headSha) {
        String caller = com.evolution.backend.auth.RequestContext.currentUserId();
        PrDiffResult best = null;
        synchronized (cache) {
            for (OwnedDiff entry : cache.values()) {
                if (!entry.ownerUserId().equals(caller)) continue;
                var r = entry.result();
                if (!r.setup().equals(setupId)) continue;
                boolean matchesPr = r.pullNumber().isPresent() && r.pullNumber().getAsInt() == pullNumber;
                boolean matchesSha = headSha != null && !headSha.isBlank() && headSha.equals(r.headSha());
                if (matchesPr || matchesSha) {
                    if (best == null || r.generatedAt().isAfter(best.generatedAt())) {
                        best = r;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Posts the pinned Architecture Evolution Diff review comment to a GitHub Pull Request.
     * Strictly requires explicit {@code confirmedByUser == true} and matching {@code expectedDiffDigest}.
     */
    public GitHubRestClient.CommentReceipt postReviewComment(
            String setupId,
            int pullNumber,
            String diffId,
            ContentDigest expectedDiffDigest,
            boolean confirmedByUser,
            BooleanSupplier cancelled) {
        if (!confirmedByUser) {
            throw new IllegalArgumentException("EXPLICIT_USER_CONFIRMATION_REQUIRED");
        }
        Objects.requireNonNull(expectedDiffDigest, "expectedDiffDigest");
        var diff = get(diffId).orElseThrow(() -> new IllegalArgumentException("DIFF_NOT_FOUND"));
        if (!diff.diffDigest().equals(expectedDiffDigest)) {
            throw new IllegalStateException("DIFF_DIGEST_MISMATCH");
        }
        if (!diff.setup().equals(setupId)) {
            throw new IllegalStateException("DIFF_SETUP_MISMATCH");
        }
        String remoteUrl = connections.remoteUrl(setupId)
                .orElseThrow(() -> new IllegalArgumentException("Connection is not a remote GitHub repository"));
        var coords = GitHubCoordinates.parse(remoteUrl);
        return restClient.createOrUpdatePrComment(coords, pullNumber, diff.markdownReport(), cancelled);
    }

    /**
     * Posts a user-reviewed and explicitly confirmed Markdown architecture review comment to a PR.
     * Enforces {@code confirmedByUser == true} and idempotent Evo review marker.
     */
    public GitHubRestClient.CommentReceipt postConfirmedReviewComment(
            String setupId,
            int pullNumber,
            Optional<String> diffId,
            Optional<ContentDigest> expectedDiffDigest,
            Optional<String> bodyMarkdown,
            boolean confirmedByUser,
            BooleanSupplier cancelled) {
        if (!confirmedByUser) {
            throw new IllegalArgumentException("EXPLICIT_USER_CONFIRMATION_REQUIRED");
        }
        String markdown;
        if (expectedDiffDigest != null && expectedDiffDigest.isPresent()) {
            String id = diffId.orElseThrow(() -> new IllegalArgumentException("DIFF_ID_REQUIRED"));
            var diff = get(id).orElseThrow(() -> new IllegalArgumentException("DIFF_NOT_FOUND"));
            if (!diff.diffDigest().equals(expectedDiffDigest.orElseThrow())) {
                throw new IllegalStateException("DIFF_DIGEST_MISMATCH");
            }
            if (!diff.setup().equals(setupId)) {
                throw new IllegalStateException("DIFF_SETUP_MISMATCH");
            }
            markdown = bodyMarkdown.filter(s -> !s.isBlank()).orElse(diff.markdownReport());
        } else if (bodyMarkdown != null && bodyMarkdown.isPresent() && !bodyMarkdown.orElseThrow().isBlank()) {
            markdown = bodyMarkdown.orElseThrow();
        } else if (diffId != null && diffId.isPresent()) {
            var diff = get(diffId.orElseThrow()).orElseThrow(() -> new IllegalArgumentException("DIFF_NOT_FOUND"));
            if (!diff.setup().equals(setupId)) {
                throw new IllegalStateException("DIFF_SETUP_MISMATCH");
            }
            markdown = diff.markdownReport();
        } else {
            throw new IllegalArgumentException("REVIEW_COMMENT_BODY_OR_DIFF_REQUIRED");
        }
        String remoteUrl = connections.remoteUrl(setupId)
                .orElseThrow(() -> new IllegalArgumentException("Connection is not a remote GitHub repository"));
        var coords = GitHubCoordinates.parse(remoteUrl);
        return restClient.createOrUpdatePrComment(coords, pullNumber, markdown, cancelled);
    }

    private Optional<String> resolveCommitSha(GitHubCoordinates.Coordinates coords, String ref,
            BooleanSupplier cancelled) {
        try {
            var list = restClient.commits(coords, Optional.of(ref), 1, cancelled);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst().sha());
        } catch (GitHubRestClient.GitHubApiException unavailable) {
            return Optional.empty();
        }
    }

    private AnalysisJobs.View awaitTerminal(UUID jobId, BooleanSupplier cancelled) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JOB_AWAIT_TIMEOUT_MS);
        while (System.nanoTime() < deadline) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("REQUEST_CANCELLED");
            }
            var view = jobs.get(jobId);
            if (AnalysisJobs.terminal(view.state())) {
                return view;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("REQUEST_CANCELLED");
            }
        }
        throw new IllegalStateException("ANALYSIS_AWAIT_TIMEOUT");
    }

    private static List<DimensionDelta> computeDimensionDeltas(
            ArchitectureAssessmentV2.Result base,
            ArchitectureAssessmentV2.Result head) {
        var baseMap = new LinkedHashMap<String, ArchitectureAssessmentV2.DimensionResult>();
        for (var d : base.dimensions()) baseMap.put(d.id(), d);
        var headMap = new LinkedHashMap<String, ArchitectureAssessmentV2.DimensionResult>();
        for (var d : head.dimensions()) headMap.put(d.id(), d);

        var keys = new LinkedHashSet<String>();
        keys.addAll(baseMap.keySet());
        keys.addAll(headMap.keySet());

        var out = new ArrayList<DimensionDelta>(keys.size());
        for (String id : keys) {
            var b = baseMap.get(id);
            var h = headMap.get(id);
            Optional<BigDecimal> bs = b == null ? Optional.empty() : b.score();
            Optional<BigDecimal> hs = h == null ? Optional.empty() : h.score();
            Optional<BigDecimal> delta = (bs.isPresent() && hs.isPresent())
                    ? Optional.of(hs.orElseThrow().subtract(bs.orElseThrow()).setScale(2, RoundingMode.HALF_UP))
                    : Optional.empty();
            String displayName = h != null ? h.displayName() : (b != null ? b.displayName() : id);
            out.add(new DimensionDelta(id, displayName, bs, hs, delta,
                    b == null ? "ABSENT" : b.status().name(),
                    h == null ? "ABSENT" : h.status().name()));
        }
        return List.copyOf(out);
    }

    private static GapDelta computeGapDelta(KnowledgeGraph baseGraph, KnowledgeGraph headGraph) {
        var baseReasons = countGapReasons(baseGraph);
        var headReasons = countGapReasons(headGraph);
        int baseTotal = baseReasons.values().stream().mapToInt(Integer::intValue).sum();
        int headTotal = headReasons.values().stream().mapToInt(Integer::intValue).sum();
        var added = new TreeMap<String, Integer>();
        var resolved = new TreeMap<String, Integer>();
        var allReasons = new TreeSet<String>();
        allReasons.addAll(baseReasons.keySet());
        allReasons.addAll(headReasons.keySet());
        for (String reason : allReasons) {
            int diff = headReasons.getOrDefault(reason, 0) - baseReasons.getOrDefault(reason, 0);
            if (diff > 0) added.put(reason, diff);
            else if (diff < 0) resolved.put(reason, -diff);
        }
        return new GapDelta(baseTotal, headTotal, headTotal - baseTotal,
                Collections.unmodifiableMap(added), Collections.unmodifiableMap(resolved));
    }

    private static Map<String, Integer> countGapReasons(KnowledgeGraph graph) {
        var counts = new TreeMap<String, Integer>();
        var seen = new HashSet<String>();
        for (var node : graph.nodes()) {
            if (!"CAPABILITY_GAP".equals(node.kind()) || !seen.add(node.identity())) {
                continue;
            }
            String reason = "UNKNOWN_GAP";
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev != null && "evidence.gap".equals(ev.family())) {
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                        if (obj.get("reasonCode") instanceof String rc && !rc.isBlank()) {
                            reason = rc;
                            break;
                        }
                    } catch (RuntimeException ignored) {}
                }
            }
            counts.merge(reason, 1, Integer::sum);
        }
        return counts;
    }

    private ViolationsDelta computeViolationsDelta(
            KnowledgeGraph baseGraph, KnowledgeGraph headGraph,
            ContentDigest baseGraphId, ContentDigest headGraphId) {
        if (reports == null) return ViolationsDelta.empty();
        Map<ContentDigest, Set<String>> baseByCompilation = collectReportFindingsByCompilation(baseGraph, baseGraphId);
        Map<ContentDigest, Set<String>> headByCompilation = collectReportFindingsByCompilation(headGraph, headGraphId);
        if (baseByCompilation.isEmpty() || headByCompilation.isEmpty()) {
            return ViolationsDelta.empty();
        }
        var sharedCompilations = new LinkedHashSet<>(baseByCompilation.keySet());
        sharedCompilations.retainAll(headByCompilation.keySet());
        if (sharedCompilations.isEmpty()) {
            return ViolationsDelta.empty();
        }
        int resolved = 0;
        int introduced = 0;
        int persisted = 0;
        for (ContentDigest compilation : sharedCompilations) {
            Set<String> baseKeys = baseByCompilation.getOrDefault(compilation, Set.of());
            Set<String> headKeys = headByCompilation.getOrDefault(compilation, Set.of());
            for (String k : baseKeys) {
                if (headKeys.contains(k)) persisted++;
                else resolved++;
            }
            for (String k : headKeys) {
                if (!baseKeys.contains(k)) introduced++;
            }
        }
        return new ViolationsDelta(resolved, introduced, persisted);
    }

    private Map<ContentDigest, Set<String>> collectReportFindingsByCompilation(KnowledgeGraph graph, ContentDigest graphId) {
        var byCompilation = new LinkedHashMap<ContentDigest, Set<String>>();
        var labelCache = new HashMap<String, String>();
        for (var reportId : reports.forGraph(graphId)) {
            var repOpt = reports.get(reportId);
            if (repOpt.isEmpty()) continue;
            var eval = repOpt.orElseThrow().evaluation();
            var keys = byCompilation.computeIfAbsent(eval.compilation(), k -> new LinkedHashSet<>());
            for (var f : eval.findings()) {
                var readablePath = new ArrayList<String>(f.bundle().path().size());
                for (String id : f.bundle().path()) {
                    readablePath.add(labelCache.computeIfAbsent(id, k -> resolveNodeLabelById(graph, k)));
                }
                keys.add(f.rule().id() + ":" + f.rule().version() + "|" + f.kind().name() + "|" + readablePath);
            }
        }
        return byCompilation;
    }

    private static List<String> diffSnapshotChangedFiles(KnowledgeGraph baseGraph, KnowledgeGraph headGraph) {
        var baseFiles = readSnapshotFileDigests(baseGraph);
        var headFiles = readSnapshotFileDigests(headGraph);
        if (baseFiles.isEmpty() && headFiles.isEmpty()) return List.of();
        var changed = new TreeSet<String>();
        for (var e : headFiles.entrySet()) {
            String prev = baseFiles.get(e.getKey());
            if (prev == null || !prev.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String removed : baseFiles.keySet()) {
            if (!headFiles.containsKey(removed)) {
                changed.add(removed);
            }
        }
        return List.copyOf(changed);
    }

    private static Map<String, String> readSnapshotFileDigests(KnowledgeGraph graph) {
        var out = new HashMap<String, String>();
        var limits = new GraphJson.Limits(4_000_000, 32, 200_000);
        for (var ev : graph.evidence()) {
            if (!"repository.snapshot".equals(ev.family())) continue;
            try {
                var raw = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                if (raw.get("files") instanceof List<?> files) {
                    for (Object item : files) {
                        var row = GraphJson.object(item);
                        if (row.get("path") instanceof String p && row.get("contentDigest") instanceof String d) {
                            out.put(normalizeGitPath(CapturedFileIndex.normalizePath(p)), d);
                        }
                    }
                }
                break;
            } catch (RuntimeException ignored) {}
        }
        // Complement/fallback via CapturedFileIndex (covers polyglot inventory entries and large/compact snapshots)
        try {
            for (var entry : CapturedFileIndex.forGraph(graph).entries()) {
                String p = normalizeGitPath(entry.path());
                entry.digest().ifPresent(d -> out.putIfAbsent(p, d.value()));
            }
        } catch (RuntimeException ignored) {}
        return out;
    }

    private static List<BlastRadiusEntry> computeBlastRadius(
            KnowledgeGraph headGraph,
            ArchitectureQueryService queries,
            List<String> changedFiles,
            BooleanSupplier cancelled) {
        if (changedFiles.isEmpty()) {
            return List.of();
        }
        var changedSet = new HashSet<String>();
        for (String cf : changedFiles) {
            if (cf != null && !cf.isBlank()) changedSet.add(normalizeGitPath(cf));
        }
        var docIdToPath = buildDocIdToPathIndex(headGraph);
        var matchedSymbols = new LinkedHashMap<String, String>(); // nodeIdentity -> filePath
        var contextByNode = new HashMap<String, String>();
        var labelByNode = new HashMap<String, String>();
        var isPolyglotOrSnapshot = new HashSet<String>();

        Set<String> blastKinds = Set.of(
                "TYPE", "CLASS", "INTERFACE", "RECORD", "ENUM",
                "POLYGLOT_TYPE", "POLYGLOT_CLASS", "POLYGLOT_FUNCTION", "POLYGLOT_SYMBOL",
                "SERVICE_ENDPOINT", "FUNCTION");

        // Include analysis contexts (Java + Polyglot) AND snapshot context
        for (var ctx : headGraph.contexts()) {
            boolean snapshotCtx = ctx.identity().equals(headGraph.snapshot().value());
            for (var node : headGraph.nodes(ctx.identity())) {
                if (!blastKinds.contains(node.kind())) continue;
                String path = extractNodeSourcePath(headGraph, node, docIdToPath);
                if (path != null && changedSet.contains(normalizeGitPath(path))) {
                    if (matchedSymbols.putIfAbsent(node.identity(), path) == null) {
                        contextByNode.put(node.identity(), ctx.identity());
                        labelByNode.put(node.identity(), extractNodeLabel(headGraph, node));
                        if (snapshotCtx || node.kind().startsWith("POLYGLOT_") || "FUNCTION".equals(node.kind())
                                || "SERVICE_ENDPOINT".equals(node.kind())) {
                            isPolyglotOrSnapshot.add(node.identity());
                        }
                    }
                }
            }
        }

        var entries = new ArrayList<BlastRadiusEntry>();
        for (var entry : matchedSymbols.entrySet()) {
            if (cancelled.getAsBoolean() || entries.size() >= 25) break;
            String symbol = entry.getKey();
            String ctxId = contextByNode.get(symbol);
            String displayLabel = labelByNode.getOrDefault(symbol, symbol);
            boolean poly = isPolyglotOrSnapshot.contains(symbol);
            try {
                var scope = poly ? ArchitectureQuery.Scope.ENTITY : ArchitectureQuery.Scope.TYPE;
                var rels = poly
                        ? Set.of("graph.depends-on", "java.calls", "CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_EVENT",
                                "polyglot.imports", "polyglot.service-call.candidate", "polyglot.event-flow.candidate")
                        : Set.of("graph.depends-on");
                var req = new ArchitectureQuery.Request(
                        headGraph.identity(), ctxId, scope,
                        ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.INCOMING,
                        rels, ArchitectureQuery.Population.all(),
                        false, Optional.empty(), ArchitectureQuery.Limits.defaults());
                var impact = queries.impact(req, symbol);
                int direct = (int) impact.items().stream().filter(i -> i.edges().size() == 1).count();
                int total = impact.items().size();
                entries.add(new BlastRadiusEntry(entry.getValue(), symbol, displayLabel, direct, total, impact.bounds().truncated()));
            } catch (RuntimeException ignored) {
                // Skip non-queryable symbols cleanly.
            }
        }
        entries.sort(Comparator.comparingInt(BlastRadiusEntry::transitiveDependents).reversed()
                .thenComparing(BlastRadiusEntry::symbolLabel));
        return List.copyOf(entries);
    }

    private static String resolveNodeLabelById(KnowledgeGraph graph, String nodeId) {
        for (var ctx : graph.contexts()) {
            for (var n : graph.nodes(ctx.identity())) {
                if (n.identity().equals(nodeId)) {
                    return extractNodeLabel(graph, n);
                }
            }
        }
        return nodeId;
    }

    private static String extractNodeLabel(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        var limits = new GraphJson.Limits(256_000, 32, 16_000);
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                if ("java.declaration".equals(ev.family()) && obj.get("entity") instanceof Map<?, ?> ent) {
                    if (ent.get("qualifiedName") instanceof String q && !q.isBlank()) return q;
                    if (ent.get("canonicalName") instanceof String cn && !cn.isBlank()) {
                        return com.evolution.backend.query.RepoMapBuilder.readableCanonicalName(cn);
                    }
                    if (ent.get("name") instanceof String nm && !nm.isBlank()) return nm;
                } else if ("polyglot.source-syntax".equals(ev.family())) {
                    Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                    if (fields.get("qualifiedName") instanceof String q && !q.isBlank()) return q;
                    if (fields.get("name") instanceof String nm && !nm.isBlank()) return nm;
                    if (fields.get("symbol") instanceof String s && !s.isBlank()) return s;
                } else if ("spring.candidate".equals(ev.family())
                        && obj.get("declaredNameKey") instanceof Map<?, ?> dk
                        && dk.get("primary") instanceof String p && !p.isBlank()) {
                    return p;
                } else if ("spring.endpoint-declaration".equals(ev.family())
                        && obj.get("route") instanceof Map<?, ?> r && r.get("path") instanceof String rp) {
                    return rp;
                }
            } catch (RuntimeException ignored) {}
        }
        return node.identity();
    }

    private static Map<String, String> buildDocIdToPathIndex(KnowledgeGraph graph) {
        var docIdToPath = new HashMap<String, String>();
        var limits = new GraphJson.Limits(512_000, 32, 32_000);
        for (var ctx : graph.contexts()) {
            for (var node : graph.nodes(ctx.identity())) {
                if ("SOURCE_DOCUMENT".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null) continue;
                        try {
                            if ("source.document".equals(ev.family())) {
                                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                                if (obj.get("path") instanceof String p && !p.isBlank()) {
                                    docIdToPath.putIfAbsent(node.identity(), normalizeGitPath(CapturedFileIndex.normalizePath(p)));
                                }
                            } else if ("source.bytes".equals(ev.family())) {
                                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                                if (obj.get("document") instanceof Map<?, ?> d && d.get("path") instanceof String p && !p.isBlank()) {
                                    docIdToPath.putIfAbsent(node.identity(), normalizeGitPath(CapturedFileIndex.normalizePath(p)));
                                }
                            }
                        } catch (RuntimeException ignored) {}
                    }
                } else if ("INVENTORY_ENTRY".equals(node.kind())) {
                    for (var ref : node.evidence()) {
                        var ev = graph.evidence(ref).orElse(null);
                        if (ev == null || !"polyglot.inventory-entry".equals(ev.family())) continue;
                        try {
                            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                            if (obj.get("fields") instanceof Map<?, ?> fields && fields.get("rawPath") instanceof String rawB64) {
                                new LogicalPath(rawB64).utf8().ifPresent(p ->
                                        docIdToPath.putIfAbsent(node.identity(), normalizeGitPath(CapturedFileIndex.normalizePath(p))));
                            }
                        } catch (RuntimeException ignored) {}
                    }
                }
            }
        }
        return docIdToPath;
    }

    private static String extractNodeSourcePath(KnowledgeGraph graph, KnowledgeGraph.Node node,
            Map<String, String> docIdToPath) {
        var limits = new GraphJson.Limits(512_000, 32, 32_000);
        for (var ref : node.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                switch (ev.family()) {
                    case "java.declaration" -> {
                        if (obj.get("entity") instanceof Map<?, ?> ent
                                && ent.get("declaration") instanceof Map<?, ?> span
                                && span.get("document") instanceof String docId) {
                            String resolved = docIdToPath.get(docId);
                            if (resolved != null && !resolved.isBlank()) return resolved;
                        }
                        if (obj.get("declaration") instanceof Map<?, ?> span
                                && span.get("document") instanceof String docId) {
                            String resolved = docIdToPath.get(docId);
                            if (resolved != null && !resolved.isBlank()) return resolved;
                        }
                    }
                    case "spring.candidate", "spring.endpoint-declaration" -> {
                        if (obj.get("span") instanceof Map<?, ?> span
                                && span.get("document") instanceof String docId) {
                            String resolved = docIdToPath.get(docId);
                            if (resolved != null && !resolved.isBlank()) return resolved;
                        }
                    }
                    case "polyglot.source-syntax" -> {
                        Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                        if (fields.get("path") instanceof String p && !p.isBlank()) {
                            return normalizeGitPath(CapturedFileIndex.normalizePath(p));
                        }
                        if (fields.get("rawPath") instanceof String rawB64) {
                            var utf8 = new LogicalPath(rawB64).utf8();
                            if (utf8.isPresent() && !utf8.orElseThrow().isBlank()) {
                                return normalizeGitPath(CapturedFileIndex.normalizePath(utf8.orElseThrow()));
                            }
                        }
                    }
                    default -> {}
                }
                if (obj.get("sourcePath") instanceof String sp && !sp.isBlank()) {
                    return normalizeGitPath(sp);
                }
                if (obj.get("path") instanceof String p && !p.isBlank()) {
                    return normalizeGitPath(p);
                }
                if (obj.get("source") instanceof Map<?, ?> src && src.get("path") instanceof String p && !p.isBlank()) {
                    return normalizeGitPath(p);
                }
            } catch (RuntimeException ignored) {}
        }
        return null;
    }

    /**
     * Detects architectural Leiden community blast radius and semantic conflict risk with peer PRs
     * touching the same emergent community even when changed file paths do not directly intersect.
     */
    public static CommunityOverlapReport analyzeCommunityOverlap(
            KnowledgeGraph headGraph,
            ArchitectureQueryService headQueries,
            List<String> changedFiles,
            List<PrFileSnapshot> openPeerPrs) {
        if (headGraph == null || headQueries == null || changedFiles == null || changedFiles.isEmpty()) {
            return CommunityOverlapReport.empty();
        }
        var docIdToPath = buildDocIdToPathIndex(headGraph);
        var pathToCommunities = new LinkedHashMap<String, Set<String>>();
        var communityInfo = new LinkedHashMap<String, LeidenCommunityDetector.EmergentCommunity>();
        Set<String> metadataKinds = Set.of("REPOSITORY", "SOURCE_DOCUMENT", "INVENTORY_ENTRY", "CAPABILITY_GAP", "MODULE", "PACKAGE");
        Set<String> polyglotRelations = Set.of(
                "graph.depends-on", "polyglot.imports", "CALLS_SERVICE", "INVOKES_RPC", "PUBLISHES_EVENT",
                "polyglot.service-call.candidate", "polyglot.event-flow.candidate");

        // Include all analysis contexts (Java + Polyglot), plus snapshot context ONLY when it carries
        // architectural dependency edges & non-metadata symbols (e.g. polyglot / cross-service graphs).
        record ContextLeiden(KnowledgeGraph.Context context, LeidenCommunityDetector.Result leiden) {}
        var detectedPerContext = new ArrayList<ContextLeiden>();
        for (var ctx : headGraph.contexts()) {
            boolean isSnapshot = ctx.identity().equals(headGraph.snapshot().value());
            if (isSnapshot) {
                boolean hasArchNodes = headGraph.nodes(ctx.identity()).stream()
                        .anyMatch(n -> !metadataKinds.contains(n.kind()));
                boolean hasArchEdges = headGraph.edges(ctx.identity()).stream()
                        .anyMatch(e -> polyglotRelations.contains(e.kind()));
                if (!hasArchNodes || !hasArchEdges) {
                    continue;
                }
            }
            var scope = isSnapshot ? ArchitectureQuery.Scope.ENTITY : ArchitectureQuery.Scope.TYPE;
            var relations = isSnapshot ? polyglotRelations : Set.of("graph.depends-on");
            var req = new ArchitectureQuery.Request(
                    headGraph.identity(), ctx.identity(), scope,
                    ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                    relations, ArchitectureQuery.Population.all(),
                    false, Optional.empty(), ArchitectureQuery.Limits.defaults());
            LeidenCommunityDetector.Result leiden;
            try {
                leiden = ArchitectureInsightsAdvisor.detectCommunities(headQueries, req);
                if (leiden.communities().isEmpty() && !isSnapshot) {
                    // Polyglot analysis contexts frequently declare ENTITY/POLYGLOT_* nodes rather than Java TYPE nodes
                    var entityReq = new ArchitectureQuery.Request(
                            headGraph.identity(), ctx.identity(), ArchitectureQuery.Scope.ENTITY,
                            ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                            polyglotRelations, ArchitectureQuery.Population.all(),
                            false, Optional.empty(), ArchitectureQuery.Limits.defaults());
                    leiden = ArchitectureInsightsAdvisor.detectCommunities(headQueries, entityReq);
                }
            } catch (RuntimeException ignored) {
                continue;
            }
            if (!leiden.communities().isEmpty()) {
                detectedPerContext.add(new ContextLeiden(ctx, leiden));
            }
        }
        boolean scopeByContext = detectedPerContext.size() > 1;
        for (var entry : detectedPerContext) {
            var ctx = entry.context();
            var leiden = entry.leiden();
            String ctxScope = scopeByContext
                    ? ContentDigest.sha256Utf8(ctx.identity()).value().substring(7, 15) + ":"
                    : "";
            for (var c : leiden.communities()) {
                String scopedId = ctxScope + c.id();
                communityInfo.putIfAbsent(scopedId, scopeByContext
                        ? new LeidenCommunityDetector.EmergentCommunity(
                                scopedId, c.memberSetDigest(), c.label(), c.dominantPackage(),
                                c.hubIdentity(), c.hubLabel(), c.memberCount(),
                                c.internalEdgeCount(), c.externalEdgeCount(), c.cohesion(),
                                c.misplacedCount(), c.bridgeEdgeCount(), c.members())
                        : c);
            }
            for (var node : headGraph.nodes(ctx.identity())) {
                if (metadataKinds.contains(node.kind())) continue;
                String rawCid = leiden.communityByNode().get(node.identity());
                if (rawCid == null) continue;
                String cid = ctxScope + rawCid;
                String path = extractNodeSourcePath(headGraph, node, docIdToPath);
                if (path != null && !path.isBlank()) {
                    pathToCommunities.computeIfAbsent(normalizeGitPath(path), k -> new LinkedHashSet<>()).add(cid);
                }
            }
        }
        return computeOverlapFromMap(pathToCommunities, communityInfo, changedFiles, openPeerPrs);
    }

    /** Pure deterministic overlap calculator (also directly testable with pre-computed Leiden results). */
    public static CommunityOverlapReport computeOverlapFromMap(
            Map<String, Set<String>> pathToCommunities,
            Map<String, LeidenCommunityDetector.EmergentCommunity> communityInfo,
            List<String> changedFiles,
            List<PrFileSnapshot> openPeerPrs) {
        if (changedFiles == null || changedFiles.isEmpty() || pathToCommunities == null || pathToCommunities.isEmpty()) {
            return CommunityOverlapReport.empty();
        }
        var normChanged = new LinkedHashSet<String>();
        for (String f : changedFiles) {
            if (f != null && !f.isBlank()) normChanged.add(normalizeGitPath(f));
        }

        var filesByComm = new LinkedHashMap<String, List<String>>();
        for (String file : normChanged) {
            var comms = resolveFileCommunities(pathToCommunities, file);
            for (String cid : comms) {
                filesByComm.computeIfAbsent(cid, k -> new ArrayList<>()).add(file);
            }
        }
        if (filesByComm.isEmpty()) {
            return CommunityOverlapReport.empty();
        }

        var touched = new ArrayList<TouchedCommunity>();
        for (var e : filesByComm.entrySet()) {
            var info = communityInfo.get(e.getKey());
            String label = info != null ? info.label() : e.getKey();
            String domPkg = info != null ? info.dominantPackage() : "";
            int members = info != null ? info.memberCount() : e.getValue().size();
            BigDecimal coh = info != null ? info.cohesion() : BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            touched.add(new TouchedCommunity(e.getKey(), label, domPkg, members, coh,
                    e.getValue().stream().distinct().sorted().toList()));
        }
        touched.sort(Comparator.comparingInt((TouchedCommunity tc) -> tc.changedFiles().size()).reversed()
                .thenComparing(TouchedCommunity::communityId));

        var peerOverlaps = new ArrayList<PeerPrOverlap>();
        if (openPeerPrs != null) {
            for (var peer : openPeerPrs) {
                if (peer == null || peer.changedFiles().isEmpty()) continue;
                var peerFiles = new LinkedHashSet<String>();
                var peerComms = new LinkedHashSet<String>();
                for (String pf : peer.changedFiles()) {
                    if (pf == null || pf.isBlank()) continue;
                    String npf = normalizeGitPath(pf);
                    peerFiles.add(npf);
                    peerComms.addAll(resolveFileCommunities(pathToCommunities, npf));
                }
                var sharedComms = new ArrayList<String>();
                for (String cid : filesByComm.keySet()) {
                    if (peerComms.contains(cid)) sharedComms.add(cid);
                }
                if (sharedComms.isEmpty()) continue;
                Collections.sort(sharedComms);
                int directFiles = 0;
                for (String cf : normChanged) {
                    if (peerFiles.contains(cf)) directFiles++;
                }
                boolean semanticOnly = directFiles == 0;
                var sharedLabels = sharedComms.stream()
                        .map(cid -> communityInfo.containsKey(cid) ? communityInfo.get(cid).label() : cid)
                        .toList();
                String peerRisk = (sharedComms.size() >= 2 || directFiles > 0) ? "HIGH" : "MEDIUM";
                peerOverlaps.add(new PeerPrOverlap(
                        peer.pullNumber(), peer.title(), sharedComms, sharedLabels,
                        directFiles, semanticOnly, peerRisk));
            }
            peerOverlaps.sort(Comparator.comparingInt((PeerPrOverlap o) -> o.sharedCommunityIds().size()).reversed()
                    .thenComparingInt(PeerPrOverlap::peerPullNumber));
        }

        String riskLevel;
        if (peerOverlaps.stream().anyMatch(p -> "HIGH".equals(p.riskLevel())) || touched.size() >= 4) {
            riskLevel = "HIGH";
        } else if (!peerOverlaps.isEmpty() || touched.size() >= 2) {
            riskLevel = "MEDIUM";
        } else {
            riskLevel = "LOW";
        }

        var summary = new StringBuilder();
        summary.append("Touches ").append(touched.size()).append(" architectural communit")
                .append(touched.size() == 1 ? "y" : "ies");
        if (!peerOverlaps.isEmpty()) {
            long semOnly = peerOverlaps.stream().filter(PeerPrOverlap::semanticOnlyOverlap).count();
            summary.append("; overlaps with ").append(peerOverlaps.size()).append(" open PR(s)");
            if (semOnly > 0) {
                summary.append(" (").append(semOnly).append(" semantic-only without file collisions)");
            }
        }
        summary.append('.');

        return new CommunityOverlapReport(touched.size(), touched, peerOverlaps, riskLevel, summary.toString());
    }

    private static Set<String> resolveFileCommunities(Map<String, Set<String>> pathToCommunities, String file) {
        var direct = pathToCommunities.get(file);
        if (direct != null) return direct;
        String suffix = "/" + file;
        var found = new LinkedHashSet<String>();
        for (var e : pathToCommunities.entrySet()) {
            if (e.getKey().endsWith(suffix) || file.endsWith("/" + e.getKey())) {
                found.addAll(e.getValue());
            }
        }
        return found;
    }

    private static String normalizeGitPath(String raw) {
        String s = raw.strip().replace('\\', '/');
        while (s.startsWith("./")) s = s.substring(2);
        while (s.startsWith("/")) s = s.substring(1);
        return s;
    }

    private static String renderMarkdown(
            String repoFullName,
            OptionalInt pullNumber,
            Optional<String> pullTitle,
            String baseRef,
            String baseSha,
            String headRef,
            String headSha,
            ContentDigest baseGraphId,
            ContentDigest headGraphId,
            ArchitectureAssessmentV2.Result baseAssess,
            ArchitectureAssessmentV2.Result headAssess,
            Optional<BigDecimal> scoreDelta,
            List<DimensionDelta> dimDeltas,
            GapDelta gapDelta,
            ViolationsDelta violationsDelta,
            List<String> changedFiles,
            List<BlastRadiusEntry> blastRadius,
            CommunityOverlapReport communityOverlap,
            ContentDigest diffDigest) {
        var md = new StringBuilder();
        md.append(GitHubRestClient.COMMENT_MARKER).append('\n');
        md.append("## Architecture Evolution Diff — ").append(repoFullName);
        if (pullNumber.isPresent()) {
            md.append(" (#").append(pullNumber.getAsInt()).append(')');
        }
        md.append("\n\n");
        pullTitle.filter(t -> !t.isBlank()).ifPresent(t -> md.append("**Pull Request:** ").append(t).append("\n"));
        md.append("**Base:** `").append(baseRef).append("` (`").append(shortSha(baseSha)).append("`)  \n");
        md.append("**Head:** `").append(headRef).append("` (`").append(shortSha(headSha)).append("`)\n\n");

        md.append("### Architecture Health Score v2\n");
        md.append("| Metric | Base | Head | Delta |\n|---|---:|---:|---:|\n");
        md.append("| **Overall Health** | ")
                .append(fmtScore(baseAssess.score())).append(" (").append(baseAssess.healthBand()).append(") | ")
                .append(fmtScore(headAssess.score())).append(" (").append(headAssess.healthBand()).append(") | ")
                .append(fmtDelta(scoreDelta)).append(" |\n");
        md.append("| **Analysis Confidence** | ")
                .append(baseAssess.confidence().tier()).append(" (").append(baseAssess.confidence().confidenceScore()).append(") | ")
                .append(headAssess.confidence().tier()).append(" (").append(headAssess.confidence().confidenceScore()).append(") | — |\n");
        md.append("| **Capability Gaps** | ")
                .append(gapDelta.baseTotalGaps()).append(" | ")
                .append(gapDelta.headTotalGaps()).append(" | ")
                .append(gapDelta.deltaGaps() > 0 ? "+" + gapDelta.deltaGaps() : String.valueOf(gapDelta.deltaGaps()))
                .append(" |\n");
        if (violationsDelta != null && (violationsDelta.resolved() > 0 || violationsDelta.introduced() > 0 || violationsDelta.persisted() > 0)) {
            int baseV = violationsDelta.resolved() + violationsDelta.persisted();
            int headV = violationsDelta.introduced() + violationsDelta.persisted();
            int dv = headV - baseV;
            md.append("| **Policy Violations** | ")
                    .append(baseV).append(" | ")
                    .append(headV).append(" | ")
                    .append(dv > 0 ? "+" + dv : String.valueOf(dv))
                    .append(" (").append(violationsDelta.resolved()).append(" resolved, ")
                    .append(violationsDelta.introduced()).append(" introduced) |\n");
        }
        md.append('\n');

        if (!dimDeltas.isEmpty()) {
            md.append("### Dimension Breakdown (D1–D7)\n");
            md.append("| Dimension | Base | Head | Delta |\n|---|---:|---:|---:|\n");
            for (var d : dimDeltas) {
                md.append("| `").append(d.id()).append("` ").append(d.displayName())
                        .append(" | ").append(fmtScore(d.baseScore()))
                        .append(" | ").append(fmtScore(d.headScore()))
                        .append(" | ").append(fmtDelta(d.delta())).append(" |\n");
            }
            md.append('\n');
        }

        if (!blastRadius.isEmpty()) {
            md.append("### Blast Radius (Impacted Symbols from Changed Files)\n");
            md.append("| File | Symbol | Direct Dependents | Transitive Dependents |\n|---|---|---:|---:|\n");
            for (var b : blastRadius.stream().limit(12).toList()) {
                md.append("| `").append(b.filePath()).append("` | `").append(b.symbolLabel())
                        .append("` | ").append(b.directDependents())
                        .append(" | ").append(b.transitiveDependents())
                        .append(b.truncated() ? "+" : "").append(" |\n");
            }
            md.append('\n');
        } else if (!changedFiles.isEmpty()) {
            md.append("### Changed Files\n");
            md.append(changedFiles.size()).append(" file(s) changed in this PR.\n\n");
        }

        if (communityOverlap != null && communityOverlap.touchedCommunityCount() > 0) {
            md.append("### Architectural Community Overlap Risk (`").append(communityOverlap.riskLevel()).append("`)\n");
            md.append(communityOverlap.summary()).append("\n\n");
            md.append("| Community | Dominant Package | Cohesion | Changed Files |\n|---|---|---:|---:|\n");
            for (var tc : communityOverlap.touchedCommunities().stream().limit(8).toList()) {
                md.append("| ").append(tc.label()).append(" | `").append(tc.dominantPackage())
                        .append("` | ").append(tc.cohesion()).append(" | ").append(tc.changedFiles().size()).append(" |\n");
            }
            if (!communityOverlap.peerPrOverlaps().isEmpty()) {
                md.append("\n**Semantic Peer-PR Overlaps:**\n");
                for (var po : communityOverlap.peerPrOverlaps().stream().limit(5).toList()) {
                    md.append("- PR #").append(po.peerPullNumber()).append(" (").append(po.riskLevel()).append("): ")
                            .append(String.join(", ", po.sharedCommunityLabels()))
                            .append(po.semanticOnlyOverlap() ? " *(semantic-only: 0 direct file conflicts)*" : "")
                            .append('\n');
                }
            }
            md.append('\n');
        }

        md.append("<details><summary>Cryptographic Evidence Provenance</summary>\n\n");
        md.append("- **Diff Digest:** `").append(diffDigest.value()).append("`\n");
        md.append("- **Base Graph:** `").append(baseGraphId.value()).append("`\n");
        md.append("- **Head Graph:** `").append(headGraphId.value()).append("`\n");
        md.append("- **Formula:** `").append(headAssess.formula()).append("`\n");
        md.append("</details>\n");
        return md.toString();
    }

    private void persistDiffReceipt(PrDiffResult result) {
        if (diffStoreDir == null) return;
        try {
            Files.createDirectories(diffStoreDir);
            Path target = diffStoreDir.resolve(result.diffId() + ".diff.json");
            byte[] bytes = CanonicalJson.write(result.toView()).getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException ignored) {
            // Operational diff cache is best-effort.
        }
    }

    private static String jobKey(String prefix, String setupId, String ref, AnalysisMode mode) {
        String hash = ContentDigest.sha256Utf8(prefix + "|" + setupId + "|" + ref + "|" + mode.name())
                .value().substring(7, 31);
        return prefix + "-" + hash;
    }

    private static String shortSha(String sha) {
        return sha != null && sha.length() > 10 ? sha.substring(0, 10) : (sha == null ? "" : sha);
    }

    private static String fmtScore(Optional<BigDecimal> score) {
        return score.map(s -> s.setScale(1, RoundingMode.HALF_UP).toPlainString()).orElse("Withheld");
    }

    private static String fmtDelta(Optional<BigDecimal> delta) {
        if (delta.isEmpty()) return "—";
        var v = delta.orElseThrow();
        return (v.signum() > 0 ? "+" : "") + v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
