package com.evolution.backend.query.repair;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.query.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Rich, evidence-backed, human-readable verification report comparing baseline vs candidate
 * snapshots across Architecture Health Score v2 (D1-D7 + Analysis Confidence), structural metrics
 * (cycles, blast radius, SDP, hotspots), itemized policy findings, capability gap delta,
 * isolated sandbox build/test/failing-test evidence, and honest trust labels.
 */
public final class RepairVerificationReport {
    public static final String VERSION = "architecture.repair-verification-report:v2.1";

    public enum TrustLabel {
        ISOLATED_VM_VERIFIED,
        ISOLATED_CONTAINER_VERIFIED,
        ISOLATED_VM_REJECTED,
        ISOLATED_CONTAINER_REJECTED,
        ISOLATED_VM_UNVERIFIED,
        ISOLATED_CONTAINER_UNVERIFIED,
        PASSIVE_REANALYSIS_ONLY,
        SYNTAX_ONLY
    }

    public record RoundSummary(
            int round,
            ContentDigest proposalId,
            ContentDigest patchDigest,
            boolean syntaxPassed,
            int compileExit,
            int testExit,
            int testsRun,
            int testsFailed,
            String verdict,
            String behavioralVerdict,
            String springPreservationStatus,
            int toolCallsCount,
            List<RepairAgentTools.ToolTraceEntry> toolTrace,
            String feedbackSummary,
            long durationMs) {
        public RoundSummary {
            toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
            feedbackSummary = feedbackSummary == null ? ""
                    : com.evolution.backend.query.llm.LlmTransport.redactSecrets(feedbackSummary).text();
        }
    }

    public record DimensionDelta(
            String id,
            String displayName,
            Optional<BigDecimal> beforeScore,
            Optional<BigDecimal> afterScore,
            Optional<BigDecimal> delta,
            String beforeStatus,
            String afterStatus) {}

    public record ScoreDelta(
            Optional<BigDecimal> beforeScore,
            Optional<BigDecimal> afterScore,
            Optional<BigDecimal> delta,
            Optional<BigDecimal> beforeLowerBound,
            Optional<BigDecimal> afterLowerBound,
            String beforeBand,
            String afterBand,
            BigDecimal beforeConfidence,
            BigDecimal afterConfidence,
            String beforeConfidenceTier,
            String afterConfidenceTier,
            List<DimensionDelta> dimensions) {
        public ScoreDelta { dimensions = List.copyOf(dimensions); }
    }

    public record StructuralDelta(
            int beforeTangleCount,
            int afterTangleCount,
            int beforeTangledEntities,
            int afterTangledEntities,
            int beforeMaxBlastRadius,
            int afterMaxBlastRadius,
            int beforeSdpViolations,
            int afterSdpViolations,
            BigDecimal beforeAvgInstability,
            BigDecimal afterAvgInstability,
            BigDecimal beforeDistanceFromMainSeq,
            BigDecimal afterDistanceFromMainSeq) {}

    public record FindingItem(
            String ruleId,
            String ruleVersion,
            String kind,
            String classification,
            List<String> path) {
        public FindingItem { path = List.copyOf(path); }
    }

    public record ViolationDelta(
            List<FindingItem> resolved,
            List<FindingItem> persisted,
            List<FindingItem> introduced,
            int unknownBefore,
            int unknownAfter) {
        public ViolationDelta {
            resolved = List.copyOf(resolved);
            persisted = List.copyOf(persisted);
            introduced = List.copyOf(introduced);
        }
    }

    public record CapabilityGapDelta(
            int beforeCount,
            int afterCount,
            int delta,
            Map<String, Integer> beforeByReason,
            Map<String, Integer> afterByReason) {
        public CapabilityGapDelta {
            beforeByReason = Collections.unmodifiableMap(new TreeMap<>(beforeByReason));
            afterByReason = Collections.unmodifiableMap(new TreeMap<>(afterByReason));
        }
    }

    public record SandboxEvidence(
            boolean executed,
            String isolationMode,
            String buildTool,
            List<String> modules,
            int compileExit,
            int testExit,
            int testsRun,
            int testsFailed,
            int testsErrors,
            int classCount,
            List<CapturedRepairExecution.TestFailure> failingTests,
            String logTail,
            Optional<ContentDigest> captureDigest,
            Optional<ContentDigest> imageDigest,
            Optional<ContentDigest> inputsDigest) {
        public SandboxEvidence {
            modules = List.copyOf(modules);
            failingTests = List.copyOf(failingTests);
        }
    }

    public record Provenance(
            ContentDigest proposalIdentity,
            ContentDigest patchDigest,
            ContentDigest baselineGraph,
            Optional<ContentDigest> candidateGraph,
            ContentDigest planIdentity,
            String strategy,
            Optional<String> recipeId,
            List<CapturedRepairVerification.EditPin> edits) {
        public Provenance { edits = List.copyOf(edits); }
    }

    public record Report(
            String version,
            ContentDigest reportIdentity,
            CapturedRepairVerification.Verdict verdict,
            TrustLabel trustLabel,
            String executiveSummary,
            ScoreDelta scoreDelta,
            Optional<StructuralDelta> structuralDelta,
            ViolationDelta violationDelta,
            CapabilityGapDelta capabilityGapDelta,
            SandboxEvidence sandbox,
            List<CapturedRepairVerification.Check> checks,
            Provenance provenance,
            String patch,
            String markdownReport,
            Optional<BehavioralDiffService.BehavioralDiffReport> behavioralDiff,
            Optional<SpringPreservationChecker.SpringPreservationReport> springPreservation,
            List<RoundSummary> rounds) {
        public Report(
                String version,
                ContentDigest reportIdentity,
                CapturedRepairVerification.Verdict verdict,
                TrustLabel trustLabel,
                String executiveSummary,
                ScoreDelta scoreDelta,
                Optional<StructuralDelta> structuralDelta,
                ViolationDelta violationDelta,
                CapabilityGapDelta capabilityGapDelta,
                SandboxEvidence sandbox,
                List<CapturedRepairVerification.Check> checks,
                Provenance provenance,
                String patch,
                String markdownReport) {
            this(version, reportIdentity, verdict, trustLabel, executiveSummary, scoreDelta, structuralDelta,
                    violationDelta, capabilityGapDelta, sandbox, checks, provenance, patch, markdownReport,
                    Optional.empty(), Optional.empty(), List.of());
        }
        public Report {
            checks = List.copyOf(checks);
            behavioralDiff = behavioralDiff == null ? Optional.empty() : behavioralDiff;
            springPreservation = springPreservation == null ? Optional.empty() : springPreservation;
            rounds = rounds == null ? List.of() : List.copyOf(rounds);
        }
    }

    private RepairVerificationReport() {}

    public static Report build(
            CapturedRepair.Proposal proposal,
            CapturedRepairVerification.Result verification,
            KnowledgeGraph baselineGraph,
            Optional<KnowledgeGraph> candidateGraph,
            Optional<PublishedReports.Report> baselineReport,
            Optional<PublishedReports.Report> candidateReport,
            ArchitectureQueryService baselineQueries,
            Optional<ArchitectureQueryService> candidateQueries) {
        return build(proposal, verification, baselineGraph, candidateGraph, baselineReport, candidateReport,
                baselineQueries, candidateQueries, Optional.empty(), Optional.empty(), List.of());
    }

    public static Report build(
            CapturedRepair.Proposal proposal,
            CapturedRepairVerification.Result verification,
            KnowledgeGraph baselineGraph,
            Optional<KnowledgeGraph> candidateGraph,
            Optional<PublishedReports.Report> baselineReport,
            Optional<PublishedReports.Report> candidateReport,
            ArchitectureQueryService baselineQueries,
            Optional<ArchitectureQueryService> candidateQueries,
            Optional<BehavioralDiffService.BehavioralDiffReport> behavioralDiffOverride,
            Optional<SpringPreservationChecker.SpringPreservationReport> springPreservationOverride,
            List<RoundSummary> rounds) {
        Objects.requireNonNull(proposal);
        Objects.requireNonNull(verification);
        Objects.requireNonNull(baselineGraph);
        Objects.requireNonNull(baselineQueries);

        String context = proposal.candidate().baseline().context();
        var assessmentSvc = new ArchitectureAssessmentV2Service();
        var basePolicy = baselineReport.map(PublishedReports.Report::evaluation);
        var basePolicyId = baselineReport.map(PublishedReports.Report::identity);
        var baseScore = assessContext(assessmentSvc, baselineQueries, baselineGraph, context, basePolicy, basePolicyId);

        ArchitectureAssessmentV2.Result candScore = null;
        String candContext = candidateReport.map(r -> r.evaluation().context())
                .orElseGet(() -> candidateGraph
                        .map(cg -> resolveMatchingCandidateContext(baselineGraph, context, cg))
                        .orElse(context));
        if (candidateGraph.isPresent() && candidateQueries.isPresent()) {
            var cg = candidateGraph.orElseThrow();
            var cq = candidateQueries.orElseThrow();
            String resolvedCandCtx = cg.contexts().stream().anyMatch(c -> c.identity().equals(candContext))
                    ? candContext : cg.snapshot().value();
            candScore = assessContext(assessmentSvc, cq, cg, resolvedCandCtx,
                    candidateReport.map(PublishedReports.Report::evaluation),
                    candidateReport.map(PublishedReports.Report::identity));
        }

        var scoreDelta = computeScoreDelta(baseScore, candScore);
        var structuralDelta = computeStructuralDelta(baselineGraph, candidateGraph, baselineQueries, candidateQueries,
                context, candContext, baselineReport, candidateReport);
        var violationDelta = computeViolationDelta(baselineReport, candidateReport);
        var gapDelta = computeGapDelta(baselineGraph, candidateGraph, context, candContext);
        var sandbox = buildSandboxEvidence(verification.isolatedProof());
        var trust = determineTrustLabel(verification, candidateGraph.isPresent());
        var effectiveBehavioral = (behavioralDiffOverride != null && behavioralDiffOverride.isPresent())
                ? behavioralDiffOverride
                : Optional.of(BehavioralDiffService.evaluate(verification.isolatedProof(), List.of()));
        var effectiveSpring = (springPreservationOverride != null && springPreservationOverride.isPresent())
                ? springPreservationOverride
                : Optional.of(SpringPreservationChecker.compare(baselineGraph, candidateGraph));
        List<RoundSummary> safeRounds = rounds == null ? List.of() : List.copyOf(rounds);

        var provenance = new Provenance(
                proposal.identity(),
                proposal.candidate().patchDigest(),
                baselineGraph.identity(),
                candidateGraph.map(KnowledgeGraph::identity),
                verification.planIdentity(),
                proposal.candidate().strategy().name(),
                proposal.candidate().recipe().map(JavaRepairRecipes.Spec::id),
                verification.plan().edits());

        String execSummary = buildExecutiveSummary(verification.verdict(), trust, scoreDelta, violationDelta, sandbox,
                effectiveBehavioral, effectiveSpring);
        String markdown = renderMarkdown(verification.verdict(), trust, execSummary, scoreDelta, structuralDelta,
                violationDelta, gapDelta, sandbox, effectiveBehavioral, effectiveSpring, safeRounds,
                verification.checks(), provenance, proposal.candidate().patch());

        ContentDigest reportIdentity = CanonicalJson.sha256(List.of(
                VERSION, verification.verdict(), trust, provenance, scoreDelta, violationDelta, gapDelta,
                effectiveBehavioral, effectiveSpring));

        return new Report(VERSION, reportIdentity, verification.verdict(), trust, execSummary,
                scoreDelta, structuralDelta, violationDelta, gapDelta, sandbox,
                verification.checks(), provenance, proposal.candidate().patch(), markdown,
                effectiveBehavioral, effectiveSpring, safeRounds);
    }

    private static ArchitectureAssessmentV2.Result assessContext(
            ArchitectureAssessmentV2Service svc, ArchitectureQueryService queries, KnowledgeGraph graph,
            String ctx, Optional<PolicyEvaluation.Result> policy, Optional<ContentDigest> policyId) {
        if (ctx.equals(graph.snapshot().value())) {
            var map = new LinkedHashMap<String, PolicyEvaluation.Result>();
            policy.ifPresent(p -> map.put(p.context(), p));
            return svc.assessRepository(queries, map, policyId, ArchitectureScoreModelV2.standard());
        }
        return svc.assess(queries, ctx, policy, policyId, ArchitectureScoreModelV2.standard());
    }

    private static ScoreDelta computeScoreDelta(ArchitectureAssessmentV2.Result before, ArchitectureAssessmentV2.Result after) {
        var bScore = before.score().map(s -> s.setScale(2, RoundingMode.HALF_EVEN));
        var aScore = after == null ? Optional.<BigDecimal>empty() : after.score().map(s -> s.setScale(2, RoundingMode.HALF_EVEN));
        var delta = (bScore.isPresent() && aScore.isPresent())
                ? Optional.of(aScore.orElseThrow().subtract(bScore.orElseThrow()).setScale(2, RoundingMode.HALF_EVEN))
                : Optional.<BigDecimal>empty();

        var afterDims = new LinkedHashMap<String, ArchitectureAssessmentV2.DimensionResult>();
        if (after != null) {
            for (var d : after.dimensions()) afterDims.put(d.id(), d);
        }
        var dimDeltas = new ArrayList<DimensionDelta>();
        for (var bd : before.dimensions()) {
            var ad = afterDims.get(bd.id());
            var bs = bd.score().map(s -> s.setScale(2, RoundingMode.HALF_EVEN));
            var as = ad == null ? Optional.<BigDecimal>empty() : ad.score().map(s -> s.setScale(2, RoundingMode.HALF_EVEN));
            var dd = (bs.isPresent() && as.isPresent())
                    ? Optional.of(as.orElseThrow().subtract(bs.orElseThrow()).setScale(2, RoundingMode.HALF_EVEN))
                    : Optional.<BigDecimal>empty();
            dimDeltas.add(new DimensionDelta(bd.id(), bd.displayName(), bs, as, dd,
                    bd.status().name(), ad == null ? "NOT_RUN" : ad.status().name()));
        }

        return new ScoreDelta(
                bScore, aScore, delta,
                before.conservativeLowerBound().map(s -> s.setScale(2, RoundingMode.HALF_EVEN)),
                after == null ? Optional.empty() : after.conservativeLowerBound().map(s -> s.setScale(2, RoundingMode.HALF_EVEN)),
                before.healthBand(), after == null ? "NOT_RUN" : after.healthBand(),
                before.confidence().confidenceScore().setScale(2, RoundingMode.HALF_EVEN),
                after == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_EVEN)
                        : after.confidence().confidenceScore().setScale(2, RoundingMode.HALF_EVEN),
                before.confidence().tier().name(),
                after == null ? "NOT_RUN" : after.confidence().tier().name(),
                dimDeltas);
    }

    private static Optional<StructuralDelta> computeStructuralDelta(
            KnowledgeGraph baseGraph, Optional<KnowledgeGraph> candGraph,
            ArchitectureQueryService baseQueries, Optional<ArchitectureQueryService> candQueries,
            String baseCtx, String candCtx,
            Optional<PublishedReports.Report> baseRep, Optional<PublishedReports.Report> candRep) {
        if (candGraph.isEmpty() || candQueries.isEmpty()) return Optional.empty();
        try {
            var baseReq = new Request(baseGraph.identity(), baseCtx, Scope.TYPE, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var bSummary = ArchitectureInsightsAdvisor.analyze(baseQueries, baseReq, baseRep, 5).summary();

            var cg = candGraph.orElseThrow();
            String resolvedCand = cg.contexts().stream().anyMatch(c -> c.identity().equals(candCtx)) ? candCtx : cg.snapshot().value();
            var candReq = new Request(cg.identity(), resolvedCand, Scope.TYPE, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var aSummary = ArchitectureInsightsAdvisor.analyze(candQueries.orElseThrow(), candReq, candRep, 5).summary();
            return Optional.of(new StructuralDelta(
                    bSummary.tangleCount(), aSummary.tangleCount(),
                    bSummary.tangledEntityCount(), aSummary.tangledEntityCount(),
                    bSummary.maxBlastRadius(), aSummary.maxBlastRadius(),
                    bSummary.edgeSdpViolationCount(), aSummary.edgeSdpViolationCount(),
                    bSummary.averageInstability(), aSummary.averageInstability(),
                    bSummary.distanceFromMainSequence(), aSummary.distanceFromMainSequence()));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static ViolationDelta computeViolationDelta(Optional<PublishedReports.Report> before,
                                                        Optional<PublishedReports.Report> after) {
        var beforeByKey = new LinkedHashMap<String, FindingItem>();
        int unknownBefore = 0;
        if (before.isPresent()) {
            for (var f : before.orElseThrow().evaluation().findings()) {
                var item = toFindingItem(f);
                beforeByKey.putIfAbsent(findingKey(item), item);
                if ("UNKNOWN".equals(item.classification())) unknownBefore++;
            }
        }
        var afterByKey = new LinkedHashMap<String, FindingItem>();
        int unknownAfter = 0;
        if (after.isPresent()) {
            for (var f : after.orElseThrow().evaluation().findings()) {
                var item = toFindingItem(f);
                afterByKey.putIfAbsent(findingKey(item), item);
                if ("UNKNOWN".equals(item.classification())) unknownAfter++;
            }
        }
        if (after.isEmpty()) {
            return new ViolationDelta(List.of(), List.copyOf(beforeByKey.values()), List.of(), unknownBefore, unknownBefore);
        }
        var resolved = new ArrayList<FindingItem>();
        var persisted = new ArrayList<FindingItem>();
        var introduced = new ArrayList<FindingItem>();
        for (var e : beforeByKey.entrySet()) {
            if (afterByKey.containsKey(e.getKey())) persisted.add(e.getValue());
            else resolved.add(e.getValue());
        }
        for (var e : afterByKey.entrySet()) {
            if (!beforeByKey.containsKey(e.getKey())) introduced.add(e.getValue());
        }
        return new ViolationDelta(resolved, persisted, introduced, unknownBefore, unknownAfter);
    }

    private static FindingItem toFindingItem(PolicyEvaluation.Finding f) {
        return new FindingItem(f.rule().id(), f.rule().version(), f.kind().name(),
                f.classification().name(), f.bundle().path());
    }

    private static String findingKey(FindingItem item) {
        return item.ruleId() + ":" + item.ruleVersion() + "|" + item.path();
    }

    private static CapabilityGapDelta computeGapDelta(KnowledgeGraph before, Optional<KnowledgeGraph> after,
                                                      String baseCtx, String candCtx) {
        var bCounts = countGapReasons(before, baseCtx);
        int bTotal = bCounts.values().stream().mapToInt(Integer::intValue).sum();
        if (after.isEmpty()) {
            return new CapabilityGapDelta(bTotal, bTotal, 0, bCounts, bCounts);
        }
        var aCounts = countGapReasons(after.orElseThrow(), candCtx);
        int aTotal = aCounts.values().stream().mapToInt(Integer::intValue).sum();
        return new CapabilityGapDelta(bTotal, aTotal, aTotal - bTotal, bCounts, aCounts);
    }

    private static Map<String, Integer> countGapReasons(KnowledgeGraph graph, String ctx) {
        var counts = new TreeMap<String, Integer>();
        var contexts = new LinkedHashSet<>(List.of(ctx, graph.snapshot().value()));
        for (String c : contexts) {
            for (var n : graph.nodes(c)) {
                if (!"CAPABILITY_GAP".equals(n.kind()) && !"PROVIDER_CONFLICT".equals(n.kind())) continue;
                String reason = "UNKNOWN";
                for (var ref : n.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev != null && "evidence.gap".equals(ev.family())) {
                        try {
                            var obj = com.evolution.analysis.graph.GraphJson.object(
                                    com.evolution.analysis.graph.GraphJson.read(ev.canonicalJson(), com.evolution.analysis.graph.GraphJson.Limits.defaults()));
                            if (obj.get("reasonCode") instanceof String r && !r.isBlank()) reason = r;
                        } catch (RuntimeException ignored) {}
                        break;
                    }
                }
                counts.merge(reason, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static String resolveMatchingCandidateContext(KnowledgeGraph baseGraph, String baseCtx, KnowledgeGraph candGraph) {
        if (baseCtx == null || baseCtx.equals(baseGraph.snapshot().value())) {
            return candGraph.snapshot().value();
        }
        if (candGraph.contexts().stream().anyMatch(c -> c.identity().equals(baseCtx))) {
            return baseCtx;
        }
        String baseScope = contextScopeKey(baseGraph, baseCtx);
        if (baseScope != null) {
            for (var c : candGraph.contexts()) {
                if (c.analysis().isEmpty()) continue;
                if (baseScope.equals(contextScopeKey(candGraph, c.identity()))) {
                    return c.identity();
                }
            }
        }
        return candGraph.contexts().stream()
                .filter(c -> c.analysis().isPresent())
                .max(Comparator.comparingInt(c -> candGraph.nodes(c.identity()).size()))
                .map(KnowledgeGraph.Context::identity)
                .orElse(candGraph.snapshot().value());
    }

    private static String contextScopeKey(KnowledgeGraph graph, String contextId) {
        for (var ctx : graph.contexts()) {
            if (!ctx.identity().equals(contextId) || ctx.analysis().isEmpty()) continue;
            var manifest = graph.evidence(ctx.evidence()).filter(e -> "analysis.manifest".equals(e.family())).orElse(null);
            if (manifest != null) {
                try {
                    var obj = com.evolution.analysis.graph.GraphJson.object(
                            com.evolution.analysis.graph.GraphJson.read(manifest.canonicalJson(),
                                    com.evolution.analysis.graph.GraphJson.Limits.defaults()));
                    var cfg = com.evolution.analysis.graph.GraphJson.object(obj.get("configuration"));
                    var values = com.evolution.analysis.graph.GraphJson.object(cfg.get("values"));
                    return "JAVA|" + Objects.toString(values.get("java.module"), "")
                            + "|" + Objects.toString(values.get("java.source-set"), "");
                } catch (RuntimeException ignored) {}
            }
            var spring = graph.evidence(ctx.evidence()).filter(e -> "spring.context".equals(e.family())).orElse(null);
            if (spring != null) {
                String parent = contextScopeKey(graph, ctx.analysis().orElseThrow().value());
                if (parent != null && parent.startsWith("JAVA|")) {
                    return "SPRING|" + parent.substring(5);
                }
            }
        }
        return null;
    }

    private static SandboxEvidence buildSandboxEvidence(Optional<CapturedRepairVerification.IsolatedProof> proof) {
        if (proof.isEmpty()) {
            return new SandboxEvidence(false, "NONE", "none", List.of(), -1, -1, 0, 0, 0, 0,
                    List.of(), "", Optional.empty(), Optional.empty(), Optional.empty());
        }
        var p = proof.orElseThrow();
        var sanitizedFailures = p.failingTests().stream()
                .map(ft -> new CapturedRepairExecution.TestFailure(
                        ft.className(), ft.methodName(), ft.kind(),
                        ft.message() == null || ft.message().isEmpty() ? ""
                                : com.evolution.backend.query.llm.LlmTransport.redactSecrets(ft.message()).text()))
                .toList();
        String sanitizedLogTail = p.logTail() == null || p.logTail().isEmpty() ? ""
                : com.evolution.backend.query.llm.LlmTransport.redactSecrets(p.logTail()).text();
        return new SandboxEvidence(true, p.isolationMode(), p.buildTool(), p.modules(),
                p.compileExit(), p.testExit(), p.testsRun(), p.testsFailed(), p.testsErrors(), p.classCount(),
                sanitizedFailures, sanitizedLogTail, Optional.of(p.capture()), Optional.of(p.image()), Optional.of(p.inputs()));
    }

    private static TrustLabel determineTrustLabel(CapturedRepairVerification.Result verification, boolean hasCandidateGraph) {
        if (verification.isolatedProof().isPresent()) {
            var proof = verification.isolatedProof().orElseThrow();
            boolean isContainer = "DOCKER_HARDENED_CONTAINER".equalsIgnoreCase(proof.isolationMode());
            boolean testsSkipped = proof.testsSkipped()
                    || verification.checks().stream()
                    .anyMatch(c -> "ISOLATED_BUILD_PASSED_TESTS_SKIPPED".equals(c.reason()))
                    || (proof.compileExit() == 0 && proof.testsRun() == 0 && !proof.checks().contains("candidate.test"));
            if (verification.verdict() == CapturedRepairVerification.Verdict.VERIFIED && !testsSkipped) {
                return isContainer ? TrustLabel.ISOLATED_CONTAINER_VERIFIED : TrustLabel.ISOLATED_VM_VERIFIED;
            }
            if (verification.verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                return isContainer ? TrustLabel.ISOLATED_CONTAINER_REJECTED : TrustLabel.ISOLATED_VM_REJECTED;
            }
            return isContainer ? TrustLabel.ISOLATED_CONTAINER_UNVERIFIED : TrustLabel.ISOLATED_VM_UNVERIFIED;
        }
        if (hasCandidateGraph || verification.candidateComparison().isPresent()) {
            return TrustLabel.PASSIVE_REANALYSIS_ONLY;
        }
        return TrustLabel.SYNTAX_ONLY;
    }

    private static String buildExecutiveSummary(
            CapturedRepairVerification.Verdict verdict,
            TrustLabel trust,
            ScoreDelta score,
            ViolationDelta violations,
            SandboxEvidence sandbox,
            Optional<BehavioralDiffService.BehavioralDiffReport> behavioralDiff,
            Optional<SpringPreservationChecker.SpringPreservationReport> springPreservation) {
        var sb = new StringBuilder();
        sb.append("Verdict: ").append(verdict.name()).append(" (Trust: ").append(trust.name()).append("). ");
        if (score.beforeScore().isPresent() && score.afterScore().isPresent() && score.delta().isPresent()) {
            BigDecimal d = score.delta().orElseThrow();
            String sign = d.signum() > 0 ? "+" : "";
            sb.append("Architecture Health Score v2: ").append(score.beforeScore().orElseThrow())
                    .append(" -> ").append(score.afterScore().orElseThrow())
                    .append(" (").append(sign).append(d).append("). ");
        }
        sb.append("Findings: ").append(violations.resolved().size()).append(" resolved, ")
                .append(violations.persisted().size()).append(" persisted, ")
                .append(violations.introduced().size()).append(" introduced. ");
        if (sandbox.executed()) {
            sb.append("Sandbox (").append(sandbox.isolationMode()).append(", ").append(sandbox.buildTool())
                    .append("): compileExit=").append(sandbox.compileExit())
                    .append(", testExit=").append(sandbox.testExit())
                    .append(", tests=").append(sandbox.testsRun())
                    .append(" (failed=").append(sandbox.testsFailed())
                    .append(", errors=").append(sandbox.testsErrors()).append("). ");
        } else {
            sb.append("Isolated sandbox build/test was not executed; verification cannot be upgraded to VERIFIED without isolated execution evidence. ");
        }
        behavioralDiff.ifPresent(b -> sb.append("Behavioral Diff: ").append(b.verdict().name()).append(". "));
        springPreservation.ifPresent(sp -> {
            if (sp.status() != SpringPreservationChecker.Status.NOT_APPLICABLE) {
                sb.append("Spring Truth Regions: ").append(sp.status().name()).append(".");
            }
        });
        return sb.toString().strip();
    }

    private static String renderMarkdown(
            CapturedRepairVerification.Verdict verdict,
            TrustLabel trust,
            String summary,
            ScoreDelta score,
            Optional<StructuralDelta> structural,
            ViolationDelta violations,
            CapabilityGapDelta gaps,
            SandboxEvidence sandbox,
            Optional<BehavioralDiffService.BehavioralDiffReport> behavioralDiff,
            Optional<SpringPreservationChecker.SpringPreservationReport> springPreservation,
            List<RoundSummary> rounds,
            List<CapturedRepairVerification.Check> checks,
            Provenance prov,
            String patch) {
        var md = new StringBuilder();
        md.append("# Verified Repair Impact Report\n\n");
        md.append("- **Verdict:** `").append(verdict.name()).append("`\n");
        md.append("- **Trust Level:** `").append(trust.name()).append("`\n");
        behavioralDiff.ifPresent(b -> md.append("- **Behavioral Verdict:** `").append(b.verdict().name()).append("`\n"));
        springPreservation.ifPresent(sp -> md.append("- **Spring Truth-Region Preservation:** `").append(sp.status().name()).append("`\n"));
        md.append("- **Proposal:** `").append(prov.proposalIdentity().value()).append("` (strategy: `")
                .append(prov.strategy()).append("`").append(prov.recipeId().map(r -> ", recipe: `" + r + "`").orElse("")).append(")\n");
        md.append("- **Patch Digest:** `").append(prov.patchDigest().value()).append("` (").append(prov.edits().size()).append(" file(s))\n\n");
        md.append("> ").append(summary).append("\n\n");

        md.append("## 1. Architecture Health Score v2 (Before vs After)\n\n");
        md.append("| Metric | Before | After | Delta |\n|---|---:|---:|---:|\n");
        md.append("| **Overall Health Score** | ").append(fmtOpt(score.beforeScore())).append(" (").append(score.beforeBand()).append(") | ")
                .append(fmtOpt(score.afterScore())).append(" (").append(score.afterBand()).append(") | ")
                .append(fmtDelta(score.delta())).append(" |\n");
        md.append("| Conservative Lower Bound | ").append(fmtOpt(score.beforeLowerBound())).append(" | ")
                .append(fmtOpt(score.afterLowerBound())).append(" | — |\n");
        md.append("| Analysis Confidence | ").append(score.beforeConfidence()).append(" (`").append(score.beforeConfidenceTier()).append("`) | ")
                .append(score.afterConfidence()).append(" (`").append(score.afterConfidenceTier()).append("`) | — |\n");
        for (var dim : score.dimensions()) {
            md.append("| ").append(dim.id()).append(" ").append(dim.displayName())
                    .append(" | ").append(fmtOpt(dim.beforeScore()))
                    .append(" | ").append(fmtOpt(dim.afterScore()))
                    .append(" | ").append(fmtDelta(dim.delta())).append(" |\n");
        }
        md.append("\n");

        if (structural.isPresent()) {
            var st = structural.orElseThrow();
            md.append("## 2. Structural & Coupling Metrics\n\n");
            md.append("| Metric | Before | After | Delta |\n|---|---:|---:|---:|\n");
            md.append("| Dependency Cycles (Tangles) | ").append(st.beforeTangleCount()).append(" | ").append(st.afterTangleCount()).append(" | ").append(fmtIntDelta(st.afterTangleCount() - st.beforeTangleCount())).append(" |\n");
            md.append("| Tangled Entities | ").append(st.beforeTangledEntities()).append(" | ").append(st.afterTangledEntities()).append(" | ").append(fmtIntDelta(st.afterTangledEntities() - st.beforeTangledEntities())).append(" |\n");
            md.append("| Max Blast Radius | ").append(st.beforeMaxBlastRadius()).append(" | ").append(st.afterMaxBlastRadius()).append(" | ").append(fmtIntDelta(st.afterMaxBlastRadius() - st.beforeMaxBlastRadius())).append(" |\n");
            md.append("| Edge-Level SDP Violations | ").append(st.beforeSdpViolations()).append(" | ").append(st.afterSdpViolations()).append(" | ").append(fmtIntDelta(st.afterSdpViolations() - st.beforeSdpViolations())).append(" |\n");
            md.append("| Average Instability (I) | ").append(st.beforeAvgInstability()).append(" | ").append(st.afterAvgInstability()).append(" | — |\n");
            md.append("| Distance from Main Sequence (D) | ").append(st.beforeDistanceFromMainSeq()).append(" | ").append(st.afterDistanceFromMainSeq()).append(" | — |\n\n");
        }

        md.append("## 3. Policy Violations & Capability Gaps\n\n");
        md.append("- **Resolved (").append(violations.resolved().size()).append("):** ");
        md.append(violations.resolved().isEmpty() ? "None\n" : "\n");
        for (var f : violations.resolved()) md.append("  - `").append(f.ruleId()).append("` (").append(f.classification()).append("): ").append(String.join(" -> ", f.path())).append("\n");
        md.append("- **Introduced Regressions (").append(violations.introduced().size()).append("):** ");
        md.append(violations.introduced().isEmpty() ? "None\n" : "\n");
        for (var f : violations.introduced()) md.append("  - `").append(f.ruleId()).append("` (").append(f.classification()).append("): ").append(String.join(" -> ", f.path())).append("\n");
        md.append("- **Persisted (").append(violations.persisted().size()).append(")**\n");
        md.append("- **Capability Gaps:** ").append(gaps.beforeCount()).append(" -> ").append(gaps.afterCount())
                .append(" (").append(fmtIntDelta(gaps.delta())).append(")\n\n");

        md.append("## 4. Isolated Sandbox Build & Test Verification\n\n");
        if (sandbox.executed()) {
            md.append("- **Isolation Mode:** `").append(sandbox.isolationMode()).append("` (zero host target execution)\n");
            md.append("- **Build Tool / Modules:** `").append(sandbox.buildTool()).append("` · `").append(String.join(", ", sandbox.modules())).append("`\n");
            md.append("- **Compile Exit:** `").append(sandbox.compileExit()).append("` · **Test Exit:** `").append(sandbox.testExit()).append("`\n");
            md.append("- **Tests:** ").append(sandbox.testsRun()).append(" run, ").append(sandbox.testsFailed())
                    .append(" failed, ").append(sandbox.testsErrors()).append(" errors · **Compiled Classes:** ").append(sandbox.classCount()).append("\n");
            if (!sandbox.failingTests().isEmpty()) {
                md.append("\n### Failing Tests\n");
                for (var ft : sandbox.failingTests()) {
                    md.append("- `").append(ft.className()).append("#").append(ft.methodName()).append("` (")
                            .append(ft.kind()).append("): ").append(ft.message()).append("\n");
                }
            }
            if (!sandbox.logTail().isBlank()) {
                String safeLog = com.evolution.backend.query.llm.LlmTransport.redactSecrets(sandbox.logTail()).text();
                md.append("\n### Sanitized Build/Test Failure Log Tail\n```text\n").append(safeLog).append("\n```\n");
            }
        } else {
            md.append("- Isolated sandbox execution was **NOT RUN** (`").append(trust.name()).append("`).\n");
        }

        if (behavioralDiff.isPresent()) {
            var bd = behavioralDiff.orElseThrow();
            md.append("\n### 4b. 3-State Behavioral Differential Check\n\n");
            md.append("- **Behavioral Verdict:** `").append(bd.verdict().name()).append("`\n");
            md.append("- **Executed Checks:** ").append(bd.executedChecksCount())
                    .append(" (baseline: ").append(bd.baselineTestsRun()).append(" run / ").append(bd.baselineTestsFailed()).append(" failed")
                    .append(" · candidate: ").append(bd.candidateTestsRun()).append(" run / ").append(bd.candidateTestsFailed()).append(" failed)\n");
            md.append("- **Lineage Digest:** `").append(bd.lineageDigest().value()).append("`\n");
            md.append("- **Summary:** ").append(bd.summary()).append("\n");
            if (!bd.witnesses().isEmpty()) {
                md.append("\n| Witness | Language | Test Path | Base -> Cand Exit | Intent | Output SHA-256 |\n|---|---|---|---:|---|---|\n");
                for (var w : bd.witnesses()) {
                    md.append("| `").append(w.witnessId()).append("` | `").append(w.language()).append("` | `")
                            .append(w.testPath()).append("` | `").append(w.baseExit()).append(" -> ").append(w.candExit())
                            .append("` | `").append(w.intentClassification().name()).append("` | `")
                            .append(w.outputSha256().value()).append("` |\n");
                }
            }
        }

        if (springPreservation.isPresent()) {
            var sp = springPreservation.orElseThrow();
            md.append("\n### 4c. Spring Truth-Region Preservation Check\n\n");
            md.append("- **Status:** `").append(sp.status().name()).append("`\n");
            md.append("- **Conditional Facts:** ").append(sp.preservedFactCount()).append("/").append(sp.baselineFactCount())
                    .append(" preserved (candidate total: ").append(sp.candidateFactCount()).append(")\n");
            if (sp.baselineWorldCount() > 0 || sp.candidateWorldCount() > 0) {
                md.append("- **Modeled Configuration Worlds:** ").append(sp.preservedWorldCount()).append("/").append(sp.baselineWorldCount()).append(" preserved\n");
            }
            md.append("- **Summary:** ").append(sp.summary()).append("\n");
            if (!sp.drifts().isEmpty()) {
                md.append("\n| Symbol | Kind | Before | After | Severity |\n|---|---|---|---|---|\n");
                for (var d : sp.drifts()) {
                    md.append("| `").append(d.symbolLabel()).append("` | `").append(d.factKind()).append("` | `")
                            .append(d.beforeClassification()).append("` | `").append(d.afterClassification())
                            .append("` | `").append(d.severity()).append("` |\n");
                }
            }
        }

        if (!rounds.isEmpty()) {
            md.append("\n### 4d. Closed-Loop Repair Rounds (").append(rounds.size()).append(")\n\n");
            md.append("| Round | Proposal | Tool Calls | Compile / Test | Verdict | Behavioral | Spring |\n|---:|---|---:|---|---|---|---|\n");
            for (var r : rounds) {
                md.append("| ").append(r.round()).append(" | `").append(r.proposalId().value().substring(0, Math.min(19, r.proposalId().value().length())))
                        .append("` | ").append(r.toolCallsCount()).append(" | `").append(r.compileExit()).append(" / ").append(r.testExit())
                        .append("` | `").append(r.verdict()).append("` | `").append(r.behavioralVerdict())
                        .append("` | `").append(r.springPreservationStatus()).append("` |\n");
            }
        }

        md.append("\n## 5. Verification Gates\n\n");
        md.append("| Gate | Status | Reason |\n|---|---|---|\n");
        for (var c : checks) {
            md.append("| `").append(c.id()).append("` | `").append(c.status().name()).append("` | `").append(c.reason()).append("` |\n");
        }

        String safePatch = (patch == null || patch.isEmpty()) ? ""
                : com.evolution.backend.query.llm.LlmTransport.redactSecrets(patch).text();
        md.append("\n## 6. Verified Unified Diff\n\n```diff\n").append(safePatch);
        if (!safePatch.isEmpty() && !safePatch.endsWith("\n")) md.append('\n');
        md.append("```\n");
        return md.toString();
    }

    private static String fmtOpt(Optional<BigDecimal> v) { return v.map(BigDecimal::toPlainString).orElse("N/A"); }
    private static String fmtDelta(Optional<BigDecimal> v) {
        if (v.isEmpty()) return "—";
        BigDecimal d = v.orElseThrow();
        return (d.signum() > 0 ? "+" : "") + d.toPlainString();
    }
    private static String fmtIntDelta(int d) { return (d > 0 ? "+" : "") + d; }
}
