package com.evolution.backend.github;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.auth.RequestContext;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.GitHubProvenance;
import com.evolution.backend.query.PublishedReports;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.RepairProposalStore;
import com.evolution.backend.query.RepairSource;
import com.evolution.backend.query.repair.PrSuggestionFormatter;
import com.evolution.backend.query.repair.RepairVerificationReport;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Smart Pull Request dashboard, Architecture Quality Gate, and two-phase confirmed
 * inline diff review publisher with GitHub {@code suggestion} blocks and CODEOWNERS attribution.
 */
public final class GitHubPrReviewService {
    public static final String PLAN_SCHEMA = "github.pr-inline-review-plan:v1";
    private static final long PLAN_TTL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_PENDING_PLANS = 64;

    public record PreviewRequest(
            Optional<String> diffId,
            Optional<ContentDigest> graphId,
            Optional<ContentDigest> reportId,
            Optional<ContentDigest> baseGraphId,
            Optional<ContentDigest> proposalId,
            GitHubQualityGate.Policy qualityGatePolicy,
            boolean includeSuggestions,
            boolean postCommitStatus,
            boolean postCheckRun) {
    }

    public record ReviewPlan(
            String schema,
            ContentDigest reviewPlanDigest,
            String confirmationToken,
            Instant expiresAt,
            String repository,
            int pullNumber,
            String headSha,
            String baseRef,
            String headRef,
            GitHubQualityGate.Result qualityGate,
            String suggestedEvent,
            String bodyMarkdown,
            List<PrSuggestionFormatter.GitHubReviewComment> inlineComments,
            List<PrSuggestionFormatter.FindingContext> outOfDiffFindings,
            Map<String, List<String>> ownersByPath,
            boolean postCommitStatus,
            boolean postCheckRun) {

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("schema", schema);
            map.put("reviewPlanDigest", reviewPlanDigest.value());
            map.put("confirmationToken", confirmationToken);
            map.put("expiresAt", expiresAt);
            map.put("repository", repository);
            map.put("pullNumber", pullNumber);
            map.put("headSha", headSha);
            map.put("baseRef", baseRef);
            map.put("headRef", headRef);
            map.put("qualityGate", qualityGate.toView());
            map.put("suggestedEvent", suggestedEvent);
            map.put("bodyMarkdown", bodyMarkdown);
            map.put("inlineComments", inlineComments.stream().map(PrSuggestionFormatter.GitHubReviewComment::toPreviewView).toList());
            map.put("inlineCommentCount", inlineComments.size());
            map.put("suggestionCount", inlineComments.stream().filter(PrSuggestionFormatter.GitHubReviewComment::hasSuggestion).count());
            map.put("outOfDiffCount", outOfDiffFindings.size());
            map.put("ownersByPath", ownersByPath);
            map.put("postCommitStatus", postCommitStatus);
            map.put("postCheckRun", postCheckRun);
            return map;
        }
    }

    public record PublishRequest(
            String confirmationToken,
            ContentDigest reviewPlanDigest,
            boolean confirmedByUser,
            Optional<String> eventOverride,
            Optional<String> bodyOverride,
            Optional<Boolean> postCommitStatusOverride,
            Optional<Boolean> postCheckRunOverride) {
    }

    public record PublishReceipt(
            long reviewId,
            String htmlUrl,
            String state,
            int inlineCommentsPosted,
            boolean commitStatusPosted,
            Optional<Long> checkRunId,
            String qualityGateVerdict) {
        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("reviewId", reviewId);
            map.put("htmlUrl", htmlUrl);
            map.put("state", state);
            map.put("inlineCommentsPosted", inlineCommentsPosted);
            map.put("commitStatusPosted", commitStatusPosted);
            checkRunId.ifPresent(id -> map.put("checkRunId", id));
            map.put("qualityGateVerdict", qualityGateVerdict);
            return map;
        }
    }

    public static final class ReviewException extends RuntimeException {
        private final int status;
        private final String code;

        public ReviewException(int status, String code, String message) {
            super(GitHubTokenBroker.redact(message));
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }

    private record PendingReviewPlan(
            String ownerUserId,
            String setupId,
            GitHubCoordinates.Coordinates coords,
            ReviewPlan plan,
            long expiresAtMs) {
    }

    private final GitHubRestClient restClient;
    private final GitHubPrDiffService prDiffService;
    private final RepositoryConnections connections;
    private final GraphStore graphs;
    private final PublishedReports reports;
    private final RepairProposalStore proposalStore;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, PendingReviewPlan> pendingPlans = Collections.synchronizedMap(
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PendingReviewPlan> eldest) {
                    return size() > MAX_PENDING_PLANS;
                }
            });

    public GitHubPrReviewService(
            GitHubRestClient restClient,
            GitHubPrDiffService prDiffService,
            RepositoryConnections connections,
            GraphStore graphs,
            PublishedReports reports,
            RepairProposalStore proposalStore) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        this.prDiffService = prDiffService;
        this.connections = connections;
        this.graphs = graphs;
        this.reports = reports;
        this.proposalStore = proposalStore;
    }

    /** Enriched Pull Request list combining GitHub PR metadata, CI state, and latest Evo Quality Gate verdict. */
    public Map<String, Object> enrichedPulls(String setupId, GitHubCoordinates.Coordinates coords,
            String state, BooleanSupplier cancelled) {
        var pulls = restClient.pulls(coords, state, 25, cancelled);
        var items = new ArrayList<Map<String, Object>>(pulls.size());
        for (var pr : pulls) {
            String ciState = "unknown";
            if (pr.headSha() != null && !pr.headSha().isBlank()) {
                try {
                    ciState = restClient.combinedStatus(coords, pr.headSha(), cancelled).state();
                } catch (GitHubRestClient.GitHubApiException ignored) {
                    ciState = "unknown";
                }
            }
            var qg = new LinkedHashMap<String, Object>();
            Optional<GitHubPrDiffService.PrDiffResult> diffOpt = prDiffService == null
                    ? Optional.empty()
                    : prDiffService.latestForPull(setupId, pr.number(), pr.headSha());
            if (diffOpt.isPresent()) {
                var diff = diffOpt.orElseThrow();
                var evaluated = GitHubQualityGate.evaluate(
                        GitHubQualityGate.Policy.defaults(),
                        diff.baseScore(), diff.headScore(), 0, 0, 0);
                qg.put("verdict", evaluated.verdict());
                qg.put("summary", evaluated.summary());
                qg.put("scoreDelta", diff.scoreDelta().orElse(null));
                qg.put("baseScore", diff.baseScore().orElse(null));
                qg.put("headScore", diff.headScore().orElse(null));
                qg.put("newMustViolations", 0);
                qg.put("newMayViolations", 0);
                qg.put("diffId", diff.diffId());
            } else {
                qg.put("verdict", "NOT_ANALYZED");
                qg.put("summary", "Not analyzed yet — click Analyze PR Diff to evaluate Quality Gate.");
                qg.put("scoreDelta", null);
                qg.put("newMustViolations", 0);
                qg.put("newMayViolations", 0);
            }
            var row = new LinkedHashMap<String, Object>();
            row.put("number", pr.number());
            row.put("title", pr.title());
            row.put("state", pr.state());
            row.put("draft", pr.draft());
            row.put("htmlUrl", pr.htmlUrl());
            row.put("authorLogin", pr.authorLogin());
            row.put("authorAvatarUrl", pr.authorAvatarUrl());
            row.put("baseRef", pr.baseRef());
            row.put("baseSha", pr.baseSha());
            row.put("headRef", pr.headRef());
            row.put("headSha", pr.headSha());
            row.put("updatedAt", pr.updatedAt());
            row.put("changedFiles", pr.changedFiles());
            row.put("additions", pr.additions());
            row.put("deletions", pr.deletions());
            row.put("ciState", ciState);
            row.put("qualityGate", qg);
            items.add(row);
        }
        return Map.of("repository", coords.fullName(), "items", items);
    }

    /** Step 1: Read-only PR inline review preview with hunk anchoring, suggestion blocks, and Quality Gate. */
    public ReviewPlan previewReview(
            String setupId,
            GitHubCoordinates.Coordinates coords,
            int pullNumber,
            PreviewRequest req,
            BooleanSupplier cancelled) {
        int validPr = GitHubCoordinates.validatePullNumber(pullNumber);
        var pr = restClient.pull(coords, validPr, cancelled);
        var prFiles = restClient.pullFiles(coords, validPr, 100, cancelled);

        var fileDiffs = new LinkedHashMap<String, PrSuggestionFormatter.PrFileDiff>();
        for (var f : prFiles) {
            if (f.filename() != null && !f.filename().isBlank()) {
                var parsed = PrSuggestionFormatter.parseFilePatch(f.filename(), f.status(), f.patch());
                fileDiffs.put(parsed.path(), parsed);
            }
        }

        Optional<GitHubPrDiffService.PrDiffResult> diffOpt = req.diffId().isPresent() && prDiffService != null
                ? prDiffService.get(req.diffId().orElseThrow())
                : (prDiffService != null ? prDiffService.latestForPull(setupId, validPr, pr.headSha()) : Optional.empty());

        Optional<ContentDigest> headGraphId = req.graphId().or(() -> diffOpt.map(GitHubPrDiffService.PrDiffResult::headGraphId));
        Optional<ContentDigest> baseGraphId = req.baseGraphId().or(() -> diffOpt.map(GitHubPrDiffService.PrDiffResult::baseGraphId));
        Optional<KnowledgeGraph> headGraph = headGraphId.flatMap(id -> graphs == null ? Optional.empty() : graphs.load(id));
        Optional<KnowledgeGraph> baseGraph = baseGraphId.flatMap(id -> graphs == null ? Optional.empty() : graphs.load(id));

        GitHubProvenance.CodeownersIndex codeowners = headGraph
                .map(GitHubProvenance.CodeownersIndex::fromGraph)
                .orElse(GitHubProvenance.CodeownersIndex.EMPTY);

        var ownersByPath = new TreeMap<String, List<String>>();
        for (String path : fileDiffs.keySet()) {
            var owners = codeowners.ownersOf(path);
            if (!owners.isEmpty()) {
                ownersByPath.put(path, owners);
            }
        }

        // Gather findings from published policy report and map exact spans + permalinks + CODEOWNERS
        var findings = new ArrayList<PrSuggestionFormatter.FindingContext>();
        int mustCount = 0;
        int mayCount = 0;
        if (req.reportId().isPresent() && reports != null && headGraph.isPresent()) {
            var reportOpt = reports.get(req.reportId().orElseThrow());
            if (reportOpt.isPresent()) {
                var eval = reportOpt.orElseThrow().evaluation();
                var graph = headGraph.orElseThrow();
                for (var finding : eval.findings()) {
                    var header = finding.rule();
                    var bundle = finding.bundle();
                    String src = bundle.path().isEmpty() ? "" : bundle.path().getFirst();
                    String dst = bundle.path().size() > 1 ? bundle.path().getLast() : src;
                    String relKind = bundle.relationships().isEmpty() || bundle.relationships().getFirst().kinds().isEmpty()
                            ? header.relationships().iterator().next()
                            : bundle.relationships().getFirst().kinds().getFirst();
                    String severity = finding.classification() == ArchitectureQuery.Classification.MUST ? "ERROR" : "WARNING";
                    if ("ERROR".equals(severity)) mustCount++;
                    else mayCount++;
                    var loc = locateFindingSpan(graph, finding, src);
                    String path = loc.path();
                    List<String> owners = path.isEmpty() ? List.of() : codeowners.ownersOf(path);
                    if (!path.isEmpty() && !owners.isEmpty()) {
                        ownersByPath.put(path, owners);
                    }
                    Optional<String> permalink = path.isEmpty() ? Optional.empty()
                            : GitHubProvenance.blobPermalink(coords.host(), coords.owner(), coords.repo(),
                                    pr.headSha(), path, loc.startLine(), loc.endLine());
                    findings.add(new PrSuggestionFormatter.FindingContext(
                            header.id() + "@" + header.version(),
                            severity,
                            header.id() + ": " + src + " -> " + dst,
                            "Violation (" + finding.classification().name() + ") on edge `" + relKind
                                    + "` from `" + src + "` to `" + dst + "`.",
                            path,
                            loc.startLine(),
                            loc.endLine(),
                            owners,
                            permalink));
                }
            }
        }

        // Resolve optional stored repair proposal for clickable GitHub ```suggestion blocks
        Optional<CapturedRepair.Proposal> proposalOpt = req.proposalId()
                .flatMap(id -> proposalStore == null ? Optional.empty() : proposalStore.get(id));

        var inlineComments = new ArrayList<PrSuggestionFormatter.GitHubReviewComment>();
        if (req.includeSuggestions() && proposalOpt.isPresent() && headGraph.isPresent()) {
            var proposal = proposalOpt.orElseThrow();
            var graph = headGraph.orElseThrow();
            for (var edit : proposal.candidate().edits()) {
                if (edit.isCreate() || edit.isDelete()) continue;
                String normPath = CapturedFileIndex.normalizePath(edit.path());
                var fileDiff = fileDiffs.get(normPath);
                if (fileDiff == null) continue;
                Optional<String> beforeText = readCapturedSourceText(graph, normPath);
                if (beforeText.isEmpty()) continue;
                Optional<String> afterText = applySingleFilePatchText(beforeText.orElseThrow(), normPath, proposal.candidate().patch());
                if (afterText.isEmpty()) continue;
                List<String> owners = codeowners.ownersOf(normPath);
                var matchingFinding = findings.stream()
                        .filter(f -> f.path().equals(fileDiff.path()))
                        .findFirst()
                        .orElse(new PrSuggestionFormatter.FindingContext(
                                proposal.candidate().affectedScope().rule().id(),
                                "ERROR",
                                "Suggested Architecture Repair",
                                "Syntax-checked repair proposal `" + proposal.identity().value().substring(0, 19) + "`.",
                                fileDiff.path(), 1, 1, owners,
                                GitHubProvenance.blobPermalink(coords.host(), coords.owner(), coords.repo(),
                                        pr.headSha(), fileDiff.path(), 1, 1)));
                PrSuggestionFormatter.formatEditSuggestion(
                        fileDiff, normPath, beforeText.orElseThrow(), afterText.orElseThrow(),
                        matchingFinding, Optional.ofNullable(proposal.candidate().verification()))
                        .ifPresent(inlineComments::add);
            }
        }

        // Anchor remaining diff-resident findings as inline comments
        var commentedKeys = new HashSet<String>();
        for (var c : inlineComments) {
            commentedKeys.add(c.path() + ":" + c.ruleId().orElse(""));
        }
        for (var f : findings) {
            if (f.path().isEmpty()) continue;
            var fileDiff = fileDiffs.get(f.path());
            if (fileDiff == null) continue;
            if (commentedKeys.contains(f.path() + ":" + f.ruleId())) continue;
            PrSuggestionFormatter.formatFindingComment(fileDiff, f).ifPresent(c -> {
                inlineComments.add(c);
                commentedKeys.add(f.path() + ":" + f.ruleId());
            });
        }

        // Score v2 evaluation for Quality Gate
        Optional<BigDecimal> baseScore = diffOpt.flatMap(GitHubPrDiffService.PrDiffResult::baseScore)
                .or(() -> baseGraph.flatMap(g -> computeOverallScore(g, cancelled)));
        Optional<BigDecimal> headScore = diffOpt.flatMap(GitHubPrDiffService.PrDiffResult::headScore)
                .or(() -> headGraph.flatMap(g -> computeOverallScore(g, cancelled)));
        var qualityGate = GitHubQualityGate.evaluate(
                req.qualityGatePolicy(), baseScore, headScore, mustCount, mayCount, 0);

        Optional<RepairVerificationReport.ScoreDelta> scoreDelta = (baseScore.isPresent() && headScore.isPresent())
                ? Optional.of(new RepairVerificationReport.ScoreDelta(
                        baseScore, headScore,
                        Optional.of(headScore.orElseThrow().subtract(baseScore.orElseThrow()).setScale(2, RoundingMode.HALF_UP)),
                        baseScore, headScore, "EVALUATED", "EVALUATED",
                        BigDecimal.ONE, BigDecimal.ONE, "HIGH", "HIGH", List.of()))
                : Optional.empty();

        var executive = PrSuggestionFormatter.buildExecutiveReview(
                coords.owner(), coords.repo(), validPr, pr.headSha(), pr.baseRef(), pr.headRef(),
                qualityGate.toPrSummary(), scoreDelta, Optional.empty(),
                findings, inlineComments, ownersByPath, "PASSIVE_STATIC_ANALYSIS", Optional.empty(), cancelled);

        String suggestedEvent = "FAILED".equals(qualityGate.verdict()) ? "REQUEST_CHANGES" : "COMMENT";
        var preimage = new LinkedHashMap<String, Object>();
        preimage.put("schema", PLAN_SCHEMA);
        preimage.put("repository", coords.fullName());
        preimage.put("pullNumber", validPr);
        preimage.put("headSha", pr.headSha());
        preimage.put("verdict", qualityGate.verdict());
        preimage.put("inlineCommentCount", inlineComments.size());
        preimage.put("bodyDigest", ContentDigest.sha256Utf8(executive.markdownBody()).value());
        ContentDigest planDigest = ContentDigest.sha256Utf8(CanonicalJson.write(preimage));

        String token = randomTokenHex();
        long expiresAtMs = System.currentTimeMillis() + PLAN_TTL_MS;
        var plan = new ReviewPlan(
                PLAN_SCHEMA,
                planDigest,
                token,
                Instant.ofEpochMilli(expiresAtMs),
                coords.fullName(),
                validPr,
                pr.headSha(),
                pr.baseRef(),
                pr.headRef(),
                qualityGate,
                suggestedEvent,
                executive.markdownBody(),
                executive.inlineComments(),
                executive.outOfDiffFindings(),
                executive.ownersByPath(),
                req.postCommitStatus(),
                req.postCheckRun());
        purgeExpired();
        pendingPlans.put(token, new PendingReviewPlan(
                RequestContext.currentUserId(), setupId, coords, plan, expiresAtMs));
        return plan;
    }

    /** Step 2: Confirmed GitHub Pull Request Review + optional Commit Status / Check Run publication. */
    public PublishReceipt publishReview(
            String setupId,
            int pullNumber,
            PublishRequest req,
            BooleanSupplier cancelled) {
        if (!req.confirmedByUser()) {
            throw new ReviewException(400, "EXPLICIT_USER_CONFIRMATION_REQUIRED",
                    "confirmedByUser=true is mandatory before posting a GitHub Pull Request review.");
        }
        if (req.confirmationToken() == null || req.confirmationToken().isBlank() || req.reviewPlanDigest() == null) {
            throw new ReviewException(400, "INVALID_CONFIRMATION", "confirmationToken and reviewPlanDigest are required.");
        }
        purgeExpired();
        String caller = RequestContext.currentUserId();
        PendingReviewPlan pending;
        synchronized (pendingPlans) {
            PendingReviewPlan existing = pendingPlans.get(req.confirmationToken());
            if (existing == null || System.currentTimeMillis() > existing.expiresAtMs()) {
                pendingPlans.remove(req.confirmationToken());
                throw new ReviewException(409, "CONFIRMATION_TOKEN_INVALID_OR_EXPIRED",
                        "Review confirmation token is unknown, expired, or already used. Run preview again.");
            }
            if (!existing.ownerUserId().equals(caller)) {
                throw new ReviewException(403, "ACCESS_DENIED",
                        "Review confirmation token belongs to another user session.");
            }
            if (!existing.setupId().equals(setupId) || existing.plan().pullNumber() != pullNumber) {
                throw new ReviewException(409, "REVIEW_TARGET_MISMATCH",
                        "Review confirmation token does not match this setup and pull request.");
            }
            if (!existing.plan().reviewPlanDigest().equals(req.reviewPlanDigest())) {
                throw new ReviewException(409, "REVIEW_PLAN_DIGEST_MISMATCH",
                        "reviewPlanDigest does not match the prepared review plan.");
            }
            pending = pendingPlans.remove(req.confirmationToken());
        }

        var plan = pending.plan();
        String event = req.eventOverride().filter(s -> !s.isBlank()).orElse(plan.suggestedEvent());
        String body = req.bodyOverride().filter(s -> !s.isBlank()).orElse(plan.bodyMarkdown());
        var apiComments = plan.inlineComments().stream()
                .map(PrSuggestionFormatter.GitHubReviewComment::toGitHubApiComment)
                .toList();

        var receipt = restClient.createPullReview(
                pending.coords(), plan.pullNumber(), plan.headSha(), body, event, apiComments, cancelled);

        boolean postStatus = req.postCommitStatusOverride().orElse(plan.postCommitStatus());
        boolean statusPosted = false;
        if (postStatus && plan.headSha() != null && !plan.headSha().isBlank()) {
            restClient.createCommitStatus(
                    pending.coords(),
                    plan.headSha(),
                    plan.qualityGate().toCommitStatusState(),
                    "evo/architecture-gate",
                    plan.qualityGate().summary(),
                    Optional.empty(),
                    cancelled);
            statusPosted = true;
        }

        boolean postCheck = req.postCheckRunOverride().orElse(plan.postCheckRun());
        Optional<Long> checkRunId = Optional.empty();
        if (postCheck && plan.headSha() != null && !plan.headSha().isBlank()) {
            var annotations = new ArrayList<Map<String, Object>>();
            for (var c : plan.inlineComments()) {
                var ann = new LinkedHashMap<String, Object>();
                ann.put("path", c.path());
                ann.put("start_line", c.startLine().orElse(c.line()));
                ann.put("end_line", c.line());
                ann.put("annotation_level", "ERROR".equalsIgnoreCase(c.severity().orElse("WARNING")) ? "failure" : "warning");
                ann.put("message", c.ruleId().orElse("architecture.rule") + " finding");
                annotations.add(ann);
            }
            var created = restClient.createCheckRun(
                    pending.coords(),
                    "Evo Architecture Quality Gate",
                    plan.headSha(),
                    "completed",
                    plan.qualityGate().toCheckRunConclusion(),
                    plan.qualityGate().verdict() + " — Evo Architecture Gate",
                    plan.qualityGate().summary(),
                    annotations,
                    cancelled);
            checkRunId = Optional.of(created.id());
        }

        return new PublishReceipt(
                receipt.id(),
                receipt.htmlUrl(),
                receipt.state(),
                apiComments.size(),
                statusPosted,
                checkRunId,
                plan.qualityGate().verdict());
    }

    private record FindingLoc(String path, int startLine, int endLine) {}

    private static FindingLoc locateFindingSpan(KnowledgeGraph graph, PolicyEvaluation.Finding finding, String sourceNodeId) {
        var evidenceById = new HashMap<ContentDigest, GraphEvidence>();
        for (var ev : graph.evidence()) evidenceById.put(ev.identity(), ev);
        for (var se : finding.bundle().sources()) {
            var fromEv = parseSpanFromEvidence(evidenceById.get(se.evidence()));
            if (fromEv != null && !fromEv.path().isEmpty()) {
                var span = se.span();
                int s = Math.max(1, span.startLine());
                int e = Math.max(s, span.endLine());
                return new FindingLoc(fromEv.path(), s, e);
            }
        }
        for (var ref : finding.bundle().evidence()) {
            var loc = parseSpanFromEvidence(evidenceById.get(ref.identity()));
            if (loc != null) return loc;
        }
        if (sourceNodeId != null && !sourceNodeId.isBlank()) {
            for (var node : graph.nodes()) {
                if (node.identity().equals(sourceNodeId)) {
                    for (var evId : node.evidence()) {
                        var loc = parseSpanFromEvidence(evidenceById.get(evId));
                        if (loc != null) return loc;
                    }
                }
            }
        }
        return new FindingLoc("", 1, 1);
    }

    private static FindingLoc parseSpanFromEvidence(GraphEvidence ev) {
        if (ev == null) return null;
        try {
            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(),
                    new GraphJson.Limits(1024 * 1024, 24, 50_000)));
            Object spanObj = obj.get("span");
            if (spanObj instanceof Map<?, ?> m) {
                var span = GraphJson.object(m);
                String path = span.get("path") instanceof String s ? CapturedFileIndex.normalizePath(s) : "";
                int start = span.get("startLine") instanceof Number n ? n.intValue() : 1;
                int end = span.get("endLine") instanceof Number n ? n.intValue() : start;
                if (!path.isEmpty()) {
                    return new FindingLoc(path, Math.max(1, start), Math.max(start, end));
                }
            }
            if (obj.get("path") instanceof String p && !p.isBlank()) {
                int start = obj.get("startLine") instanceof Number n ? n.intValue() : 1;
                int end = obj.get("endLine") instanceof Number n ? n.intValue() : start;
                return new FindingLoc(CapturedFileIndex.normalizePath(p), Math.max(1, start), Math.max(start, end));
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static Optional<String> readCapturedSourceText(KnowledgeGraph graph, String normPath) {
        var evidenceById = new HashMap<ContentDigest, GraphEvidence>();
        for (var ev : graph.evidence()) evidenceById.put(ev.identity(), ev);
        for (var ev : graph.evidence()) {
            if ("source.bytes".equals(ev.family())) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(),
                            new GraphJson.Limits(2 * 1024 * 1024, 16, 50_000)));
                    String p = obj.get("path") instanceof String s ? s
                            : (obj.get("document") instanceof Map<?, ?> m && m.get("path") instanceof String dp ? dp : null);
                    if (p != null && normPath.equals(CapturedFileIndex.normalizePath(p))) {
                        String b64 = obj.get("bytesBase64") instanceof String s ? s
                                : (obj.get("base64") instanceof String l ? l : null);
                        if (b64 != null && !b64.isBlank()) {
                            return Optional.of(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
                        }
                        if (obj.get("contentEvidence") instanceof String refId) {
                            var ce = evidenceById.get(new ContentDigest(refId));
                            if (ce != null) {
                                var ceObj = GraphJson.object(GraphJson.read(ce.canonicalJson(),
                                        new GraphJson.Limits(2 * 1024 * 1024, 16, 50_000)));
                                String ceB64 = ceObj.get("bytesBase64") instanceof String s ? s
                                        : (ceObj.get("base64") instanceof String l ? l : null);
                                if (ceB64 != null && !ceB64.isBlank()) {
                                    return Optional.of(new String(Base64.getDecoder().decode(ceB64), StandardCharsets.UTF_8));
                                }
                            }
                        }
                    }
                } catch (RuntimeException ignored) {}
            } else if ("source.content".equals(ev.family())) {
                try {
                    var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(),
                            new GraphJson.Limits(2 * 1024 * 1024, 16, 50_000)));
                    if (obj.get("path") instanceof String p && normPath.equals(CapturedFileIndex.normalizePath(p))) {
                        if (obj.get("text") instanceof String t) return Optional.of(t);
                        if (obj.get("content") instanceof String c) return Optional.of(c);
                    }
                } catch (RuntimeException ignored) {}
            }
        }
        return Optional.empty();
    }

    private static Optional<String> applySingleFilePatchText(String beforeText, String path, String unifiedDiff) {
        try {
            String norm = CapturedFileIndex.normalizePath(path);
            Map<String, String> applied = RepairPatch.applyAll(Map.of(norm, beforeText), unifiedDiff);
            return Optional.ofNullable(applied.get(norm));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static Optional<BigDecimal> computeOverallScore(KnowledgeGraph graph, BooleanSupplier cancelled) {
        try {
            BooleanSupplier cancelCheck = cancelled == null ? () -> false : cancelled;
            var guard = ArchitectureQueryService.operationalGuard(cancelCheck,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(30), 8_000_000);
            var queries = new ArchitectureMetricsService(graph, ArchitectureMetric.Limits.defaults(), guard,
                    Optional.empty(), List.of()).queries();
            return new ArchitectureAssessmentV2Service()
                    .assessRepository(queries, Map.of(), Optional.empty(), ArchitectureScoreModelV2.standard())
                    .score();
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        synchronized (pendingPlans) {
            pendingPlans.values().removeIf(p -> now > p.expiresAtMs());
        }
    }

    private String randomTokenHex() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
