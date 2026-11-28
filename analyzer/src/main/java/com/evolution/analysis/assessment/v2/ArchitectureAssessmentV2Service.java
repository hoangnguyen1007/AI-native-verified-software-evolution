package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.assessment.AssessmentGaps;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.ArchitecturePolicy;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.math.BigDecimal;
import java.util.*;

/**
 * Storage-neutral orchestration service for Architecture Scoring v2 ({@code architecture.score-formula:v2.0}).
 *
 * <p>Supports single-context evaluation (Java {@code analysis.manifest}, Spring {@code spring.context},
 * Polyglot {@code polyglot.context}) and Whole-Repository size-weighted multi-context evaluation
 * ({@code snapshot:...}) with strict Architecture Health vs Analysis Confidence separation,
 * conservative downward uncertainty bounds, and zero double-counting when custom policies are supplied.
 */
public final class ArchitectureAssessmentV2Service {
    private static final GraphJson.Limits READ_LIMITS = new GraphJson.Limits(2_000_000, 64, 50_000);
    private static final int DEFAULT_MAX_WITNESSES = 10;
    private static final BigDecimal HIGH_CONFIDENCE_FLOOR = new BigDecimal("90");
    private static final BigDecimal MODERATE_CONFIDENCE_FLOOR = new BigDecimal("70");
    private static final BigDecimal LOW_CONFIDENCE_FLOOR = new BigDecimal("50");
    private static final BigDecimal HIGH_PARSE_FLOOR = new BigDecimal("0.98");
    private static final BigDecimal HIGH_INTERNAL_FLOOR = new BigDecimal("0.90");
    private static final BigDecimal SPRING_BLEND = new BigDecimal("0.60");
    private static final BigDecimal POLYGLOT_BLEND = new BigDecimal("0.40");

    public static final class Interrupted extends RuntimeException {
        public final StopReason reason;
        public Interrupted(StopReason reason) {
            super("Architecture assessment v2 interrupted: " + reason);
            this.reason = reason;
        }
    }

    /** Convenience entry point using {@link ArchitectureScoreModelV2#standard()} and no custom policy. */
    public ArchitectureAssessmentV2.Result assess(ArchitectureQueryService queries, String contextId) {
        return assess(queries, contextId, Optional.empty(), Optional.empty(), ArchitectureScoreModelV2.standard());
    }

    /**
     * Evaluates Architecture Health v2 for either the Whole Repository ({@code snapshot:...})
     * or a specific Java, Spring, or Polyglot context.
     */
    public ArchitectureAssessmentV2.Result assess(
            ArchitectureQueryService queries,
            String contextId,
            Optional<PolicyEvaluation.Result> customPolicy,
            Optional<ContentDigest> policyReportDigest,
            ArchitectureScoreModelV2 model) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(contextId, "contextId");
        Objects.requireNonNull(customPolicy, "customPolicy");
        Objects.requireNonNull(policyReportDigest, "policyReportDigest");
        Objects.requireNonNull(model, "model");

        if (contextId.equals(queries.graph().snapshot().value())) {
            var policyMap = customPolicy.map(p -> Map.of(p.context(), p)).orElse(Map.of());
            return assessRepository(queries, policyMap, policyReportDigest, model);
        }
        return assessContext(queries, contextId, customPolicy, policyReportDigest, model);
    }

    /** Pre-indexed per-graph evidence lookup avoiding repeated O(|Evidence|) scans across contexts (S2). */
    private static final class GraphEvidenceIndex {
        final Map<String, String[]> typeShapes;
        final Map<String, GraphEvidence> frontendIndexByAnalysis;
        final Map<String, KnowledgeGraph.Context> contextById;

        GraphEvidenceIndex(KnowledgeGraph graph) {
            this.typeShapes = new HashMap<>();
            this.frontendIndexByAnalysis = new HashMap<>();
            this.contextById = new HashMap<>(graph.contexts().size() * 2);
            for (var ctx : graph.contexts()) {
                this.contextById.put(ctx.identity(), ctx);
            }
            for (var ev : graph.evidence()) {
                String fam = ev.family();
                if ("java.type-shape".equals(fam)) {
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                        if (obj.get("type") instanceof String tid) {
                            String kind = obj.get("kind") instanceof String s ? s : "CLASS";
                            String abs = Boolean.TRUE.equals(obj.get("abstractType")) ? "true" : "false";
                            typeShapes.put(tid, new String[]{kind, abs});
                        }
                    } catch (RuntimeException ignored) {}
                } else if ("frontend.result".equals(fam) || "frontend.result-index".equals(fam)) {
                    String matchedAnalysis = extractTopLevelJsonString(ev.canonicalJson(), "analysis");
                    if (matchedAnalysis != null) {
                        frontendIndexByAnalysis.putIfAbsent(matchedAnalysis, ev);
                    }
                }
            }
        }
    }

    /** Evaluates a single Java, Spring, or Polyglot context. */
    public ArchitectureAssessmentV2.Result assessContext(
            ArchitectureQueryService queries,
            String contextId,
            Optional<PolicyEvaluation.Result> customPolicy,
            Optional<ContentDigest> policyReportDigest,
            ArchitectureScoreModelV2 model) {
        return assessContextInternal(queries, contextId, customPolicy, policyReportDigest, model, null, null);
    }

    private ArchitectureAssessmentV2.Result assessContextInternal(
            ArchitectureQueryService queries,
            String contextId,
            Optional<PolicyEvaluation.Result> customPolicy,
            Optional<ContentDigest> policyReportDigest,
            ArchitectureScoreModelV2 model,
            GraphEvidenceIndex sharedIndex,
            StructuralTopologyAnalyzer.StructuralGraph precomputedPolyglotGraph) {
        Objects.requireNonNull(queries);
        Objects.requireNonNull(contextId);
        Objects.requireNonNull(customPolicy);
        Objects.requireNonNull(policyReportDigest);
        Objects.requireNonNull(model);

        var graph = queries.graph();
        var evIndex = sharedIndex != null ? sharedIndex : new GraphEvidenceIndex(graph);
        var ctx = evIndex.contextById.get(contextId);
        if (ctx == null) {
            throw new IllegalArgumentException("Unknown graph context: " + contextId);
        }
        var ctxEvidence = graph.evidence(ctx.evidence()).orElseThrow();
        String family = ctxEvidence.family();

        if ("repository.snapshot".equals(family) || "repository.inventory-snapshot".equals(family)) {
            var map = customPolicy.map(p -> Map.of(p.context(), p)).orElse(Map.of());
            return assessRepository(queries, map, policyReportDigest, model);
        }

        Runnable checkpoint = () -> {
            var stop = queries.assessmentStopReason();
            if (stop != StopReason.NONE) throw new Interrupted(stop);
        };

        try {
            checkpoint.run();
            Optional<String> springCtxId = "spring.context".equals(family) ? Optional.of(contextId) : Optional.empty();
            Optional<String> javaCtxId = switch (family) {
                case "analysis.manifest" -> Optional.of(contextId);
                case "spring.context" -> resolveLinkedJavaContext(graph, ctx);
                default -> "polyglot.context".equals(family) ? Optional.empty() : Optional.of(contextId);
            };
            boolean isPolyglot = "polyglot.context".equals(family);
            String scopeMode = isPolyglot ? "POLYGLOT_CONTEXT"
                    : springCtxId.isPresent() ? "SPRING_CONTEXT" : "JAVA_CONTEXT";

            // Extract structural graph
            StructuralTopologyAnalyzer.StructuralGraph structGraph;
            String evidenceTier;
            if (isPolyglot) {
                structGraph = precomputedPolyglotGraph != null
                        ? precomputedPolyglotGraph
                        : PolyglotArchitectureAnalyzer.extractPolyglotStructuralGraph(graph, contextId, checkpoint);
                evidenceTier = "SOURCE_SYNTAX";
            } else if (javaCtxId.isPresent()) {
                String jCtx = javaCtxId.orElseThrow();
                structGraph = extractJavaStructuralGraph(queries, jCtx, evIndex, checkpoint);
                evidenceTier = detectJavaEvidenceTier(graph, jCtx, evIndex);
            } else {
                structGraph = new StructuralTopologyAnalyzer.StructuralGraph(List.of(), List.of());
                evidenceTier = "SPRING_ONLY";
            }

            var topo = StructuralTopologyAnalyzer.analyze(structGraph, DEFAULT_MAX_WITNESSES, checkpoint);
            var spring = SpringArchitectureAnalyzer.analyze(queries, springCtxId, javaCtxId, DEFAULT_MAX_WITNESSES, checkpoint);
            var crossSvc = PolyglotArchitectureAnalyzer.CrossServiceMetrics.notApplicable();

            // Independent Analysis Confidence & Coverage calculation
            var confidence = computeContextConfidence(
                    graph, contextId, javaCtxId, springCtxId, evidenceTier, spring, model, evIndex);

            return assembleResult(
                    graph, contextId, scopeMode, model, topo, spring, crossSvc,
                    customPolicy, policyReportDigest, confidence, List.of(),
                    new EvidenceContext(graph.snapshot(), ctx.analysis()), List.of());
        } catch (Interrupted stopped) {
            return withheldInterrupted(graph, contextId, "CONTEXT", model, stopped.reason,
                    new EvidenceContext(graph.snapshot(), ctx.analysis()));
        }
    }

    /**
     * Evaluates Whole-Repository Architecture Health v2 by aggregating all MAIN production
     * contexts (size-weighted by analyzed entity count) alongside repository-level cross-service
     * topology and multi-module build cyclicity.
     */
    public ArchitectureAssessmentV2.Result assessRepository(
            ArchitectureQueryService queries,
            Map<String, PolicyEvaluation.Result> customPoliciesByContext,
            Optional<ContentDigest> policyReportDigest,
            ArchitectureScoreModelV2 model) {
        Objects.requireNonNull(queries);
        Objects.requireNonNull(customPoliciesByContext);
        Objects.requireNonNull(policyReportDigest);
        Objects.requireNonNull(model);

        var graph = queries.graph();
        String root = graph.snapshot().value();
        var evidenceCtx = EvidenceContext.forSnapshot(graph.snapshot());
        Runnable checkpoint = () -> {
            var stop = queries.assessmentStopReason();
            if (stop != StopReason.NONE) throw new Interrupted(stop);
        };

        try {
            checkpoint.run();
            var sharedIndex = new GraphEvidenceIndex(graph);
            // Select MAIN production contexts (exclude TEST source sets; preserve ALL linked Spring contexts!)
            var springByJava = new HashMap<String, String>();
            var standaloneSpring = new ArrayList<KnowledgeGraph.Context>();
            var javaMainContexts = new ArrayList<KnowledgeGraph.Context>();
            var polyglotContexts = new ArrayList<KnowledgeGraph.Context>();

            for (var ctx : graph.contexts()) {
                if (ctx.identity().equals(root)) continue;
                var ev = graph.evidence(ctx.evidence()).orElse(null);
                if (ev == null) continue;
                String fam = ev.family();
                if ("analysis.manifest".equals(fam)) {
                    if (!isTestOnlyContext(ev)) javaMainContexts.add(ctx);
                } else if ("spring.context".equals(fam)) {
                    var linked = resolveLinkedJavaContext(graph, ctx);
                    if (linked.isPresent()) {
                        if (springByJava.putIfAbsent(linked.orElseThrow(), ctx.identity()) != null) {
                            // Never silently drop a 2nd Spring context attached to the same Java context.
                            standaloneSpring.add(ctx);
                        }
                    } else {
                        standaloneSpring.add(ctx);
                    }
                } else if ("polyglot.context".equals(fam)) {
                    polyglotContexts.add(ctx);
                } else if (!"repository.snapshot".equals(fam) && !"repository.inventory-snapshot".equals(fam)) {
                    javaMainContexts.add(ctx);
                }
            }

            // Evaluate each eligible context
            var contextResults = new ArrayList<ArchitectureAssessmentV2.Result>();
            var contextSummaries = new ArrayList<ArchitectureAssessmentV2.ContextScoreSummary>();
            var contextEntityWeights = new ArrayList<Integer>();

            for (var jCtx : javaMainContexts) {
                checkpoint.run();
                Optional<String> pairedSpring = Optional.ofNullable(springByJava.get(jCtx.identity()));
                String targetId = pairedSpring.orElse(jCtx.identity());
                var policy = Optional.ofNullable(customPoliciesByContext.get(targetId))
                        .or(() -> Optional.ofNullable(customPoliciesByContext.get(jCtx.identity())));
                var res = assessContextInternal(queries, targetId, policy, policyReportDigest, model, sharedIndex, null);
                int entCount = countContextTypes(graph, jCtx.identity());
                if (entCount > 0 || res.score().isPresent()) {
                    contextResults.add(res);
                    contextEntityWeights.add(Math.max(1, entCount));
                    contextSummaries.add(new ArchitectureAssessmentV2.ContextScoreSummary(
                            targetId, contextDisplayLabel(graph, jCtx),
                            pairedSpring.isPresent() ? "spring" : "java",
                            entCount, res.status(), res.score(), res.conservativeLowerBound(), res.confidence().tier()));
                }
            }
            for (var sCtx : standaloneSpring) {
                checkpoint.run();
                var policy = Optional.ofNullable(customPoliciesByContext.get(sCtx.identity()));
                var res = assessContextInternal(queries, sCtx.identity(), policy, policyReportDigest, model, sharedIndex, null);
                contextResults.add(res);
                contextEntityWeights.add(1);
                contextSummaries.add(new ArchitectureAssessmentV2.ContextScoreSummary(
                        sCtx.identity(), contextDisplayLabel(graph, sCtx), "spring",
                        0, res.status(), res.score(), res.conservativeLowerBound(), res.confidence().tier()));
            }
            for (var pCtx : polyglotContexts) {
                checkpoint.run();
                // Extract structural graph ONCE and skip trivial <2-file contexts before running topology analysis (S2).
                var pGraph = PolyglotArchitectureAnalyzer.extractPolyglotStructuralGraph(graph, pCtx.identity(), checkpoint);
                int fileCount = pGraph.nodes().size();
                if (fileCount >= 2) {
                    var res = assessContextInternal(queries, pCtx.identity(), Optional.empty(), Optional.empty(), model, sharedIndex, pGraph);
                    contextResults.add(res);
                    contextEntityWeights.add(fileCount);
                    contextSummaries.add(new ArchitectureAssessmentV2.ContextScoreSummary(
                            pCtx.identity(), contextDisplayLabel(graph, pCtx), "polyglot",
                            fileCount, res.status(), res.score(), res.conservativeLowerBound(), res.confidence().tier()));
                }
            }

            // Repo-level cross-service polyglot topology
            var crossSvc = PolyglotArchitectureAnalyzer.analyzeCrossService(graph, DEFAULT_MAX_WITNESSES, checkpoint);

            if (contextResults.isEmpty() && !crossSvc.applicable()) {
                var emptyConfidence = new ArchitectureAssessmentV2.AnalysisConfidenceV2(
                        BigDecimal.ZERO, ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, "NONE",
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty(),
                        countGaps(graph, root), Map.of(),
                        List.of("No analyzable production source contexts found in snapshot."));
                return new ArchitectureAssessmentV2.Result(
                        queries.graphIdentity(), root, "WHOLE_REPOSITORY",
                        ArchitectureScoreModelV2.FORMULA, model.identity(), ArchitectureScoreModelV2.CALIBRATION,
                        AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                        "Not Applicable", BigDecimal.ZERO, List.of(), emptyConfidence, Optional.empty(),
                        List.of(), policyReportDigest, List.of(),
                        List.of("NO_ANALYZABLE_PRODUCTION_CONTEXTS"), Optional.empty());
            }

            // Aggregate per-dimension scores weighted by context production entity size (preserving non-renormalization of WITHHELD!)
            var aggDimensions = new ArrayList<ArchitectureAssessmentV2.DimensionResult>();
            var reasons = new TreeSet<String>();
            var allGaps = new TreeSet<CapabilityGapRecord>();
            boolean anyWithheldApplicable = false;

            // Aggregate confidence weighted by context size
            var aggConfidence = aggregateRepositoryConfidence(graph, root, contextResults, contextEntityWeights, model);
            BigDecimal u = BigDecimal.ONE.subtract(aggConfidence.internalEdgeCompleteness());

            BigDecimal weightedScore = BigDecimal.ZERO;
            BigDecimal weightedLower = BigDecimal.ZERO;
            BigDecimal applicableWeight = BigDecimal.ZERO;

            for (var dimSpec : model.dimensions()) {
                if (dimSpec.id().equals(ArchitectureScoreModelV2.DIM_FRAMEWORK_POLYGLOT) && crossSvc.applicable()) {
                    // Combine context-level Spring D6 (if any) with repo-level Cross-Service D6 Part B
                    var ctxD6 = aggregateDimensionAcrossContexts(dimSpec, contextResults, contextEntityWeights);
                    var polyDim = buildPolyglotOnlyD6(dimSpec, crossSvc, u, model);
                    var mergedD6 = mergeSpringAndPolyglotD6(dimSpec, ctxD6, polyDim, u, model);
                    aggDimensions.add(mergedD6);
                    allGaps.addAll(mergedD6.gaps());
                    if (mergedD6.status() == AssessmentStatus.WITHHELD) anyWithheldApplicable = true;
                    else if (mergedD6.score().isPresent()) {
                        weightedScore = weightedScore.add(mergedD6.score().orElseThrow().multiply(dimSpec.weight()));
                        weightedLower = weightedLower.add(mergedD6.conservativeLowerBound().orElseThrow().multiply(dimSpec.weight()));
                        applicableWeight = applicableWeight.add(dimSpec.weight());
                    }
                    continue;
                }
                var dim = aggregateDimensionAcrossContexts(dimSpec, contextResults, contextEntityWeights);
                aggDimensions.add(dim);
                allGaps.addAll(dim.gaps());
                if (dim.status() == AssessmentStatus.WITHHELD) anyWithheldApplicable = true;
                else if (dim.score().isPresent()) {
                    weightedScore = weightedScore.add(dim.score().orElseThrow().multiply(dimSpec.weight()));
                    weightedLower = weightedLower.add(dim.conservativeLowerBound().orElseThrow().multiply(dimSpec.weight()));
                    applicableWeight = applicableWeight.add(dimSpec.weight());
                }
            }

            // Pick richest inferred layer map from largest Java context if present
            Optional<InferredLayerClassifier.LayerMap> repoLayers = Optional.empty();
            int bestLayerSize = -1;
            for (int i = 0; i < contextResults.size(); i++) {
                var lm = contextResults.get(i).inferredLayers();
                if (lm.isPresent() && !lm.orElseThrow().layers().isEmpty() && contextEntityWeights.get(i) > bestLayerSize) {
                    bestLayerSize = contextEntityWeights.get(i);
                    repoLayers = lm;
                }
            }

            AssessmentStatus overallStatus;
            Optional<BigDecimal> overallScore;
            Optional<BigDecimal> overallLower;
            BigDecimal overallDelta;

            if (anyWithheldApplicable || aggConfidence.tier() == ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT) {
                overallStatus = AssessmentStatus.WITHHELD;
                overallScore = Optional.empty();
                overallLower = Optional.empty();
                overallDelta = BigDecimal.ZERO;
                reasons.add("REQUIRED_EVIDENCE_BELOW_CONFIDENCE_FLOOR");
            } else if (applicableWeight.signum() == 0) {
                overallStatus = AssessmentStatus.NOT_APPLICABLE;
                overallScore = Optional.empty();
                overallLower = Optional.empty();
                overallDelta = BigDecimal.ZERO;
                reasons.add("NO_APPLICABLE_DIMENSIONS");
            } else {
                BigDecimal obs = CalibrationCurve.roundDisplay(CalibrationCurve.divide(weightedScore, applicableWeight));
                BigDecimal low = CalibrationCurve.roundDisplay(CalibrationCurve.divide(weightedLower, applicableWeight)).min(obs);
                overallScore = Optional.of(obs);
                overallLower = Optional.of(low);
                overallDelta = CalibrationCurve.normalize(obs.subtract(low));
                boolean allComplete = aggConfidence.tier() == ArchitectureAssessmentV2.ConfidenceTier.HIGH
                        && aggConfidence.openGapCount() == 0
                        && overallDelta.signum() == 0
                        && aggDimensions.stream().allMatch(d -> d.status() == AssessmentStatus.COMPLETE || d.status() == AssessmentStatus.NOT_APPLICABLE);
                overallStatus = allComplete ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
                if (overallStatus == AssessmentStatus.PARTIAL) reasons.add("PARTIAL_EVIDENCE_QUALIFIED");
            }
            reasons.add("SIZE_WEIGHTED_MULTI_CONTEXT_AGGREGATION");
            if (crossSvc.applicable()) reasons.add("CROSS_SERVICE_STATIC_CANDIDATE_TOPOLOGY_INCLUDED");

            contextSummaries.sort(Comparator
                    .comparing((ArchitectureAssessmentV2.ContextScoreSummary s) -> s.score().orElse(CalibrationCurve.HUNDRED))
                    .thenComparing(ArchitectureAssessmentV2.ContextScoreSummary::contextId));

            return new ArchitectureAssessmentV2.Result(
                    queries.graphIdentity(), root, "WHOLE_REPOSITORY",
                    ArchitectureScoreModelV2.FORMULA, model.identity(), ArchitectureScoreModelV2.CALIBRATION,
                    overallStatus, overallScore, overallLower, overallDelta,
                    ArchitectureAssessmentV2.bandFor(overallScore, aggConfidence.tier()),
                    applicableWeight, aggDimensions, aggConfidence, repoLayers, contextSummaries,
                    policyReportDigest, List.copyOf(allGaps), List.copyOf(reasons), Optional.empty());
        } catch (Interrupted stopped) {
            return withheldInterrupted(graph, root, "WHOLE_REPOSITORY", model, stopped.reason, evidenceCtx);
        }
    }

    // ─── Dimension Assembly ───

    private ArchitectureAssessmentV2.Result assembleResult(
            KnowledgeGraph graph,
            String contextId,
            String scopeMode,
            ArchitectureScoreModelV2 model,
            StructuralTopologyAnalyzer.Metrics topo,
            SpringArchitectureAnalyzer.SpringMetrics spring,
            PolyglotArchitectureAnalyzer.CrossServiceMetrics crossSvc,
            Optional<PolicyEvaluation.Result> customPolicy,
            Optional<ContentDigest> policyReportDigest,
            ArchitectureAssessmentV2.AnalysisConfidenceV2 confidence,
            List<ArchitectureAssessmentV2.ContextScoreSummary> contextBreakdown,
            EvidenceContext evidenceCtx,
            List<CapabilityGapRecord> extraGaps) {

        boolean insufficient = confidence.tier() == ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT;
        BigDecimal u = BigDecimal.ONE.subtract(confidence.internalEdgeCompleteness());
        BigDecimal uSpring = confidence.springSpaceDefiniteness().map(BigDecimal.ONE::subtract).orElse(BigDecimal.ZERO);

        boolean explicitBoundaryPolicy = customPolicy.map(p -> p.rules().stream().anyMatch(r ->
                Set.of(ArchitecturePolicy.Kind.FORBIDDEN_DEPENDENCY, ArchitecturePolicy.Kind.BOUNDARY,
                        ArchitecturePolicy.Kind.LAYER_BOUNDARY).contains(r.rule().kind()))).orElse(false);

        var dims = new ArrayList<ArchitectureAssessmentV2.DimensionResult>();
        var allGaps = new TreeSet<>(extraGaps);
        var reasons = new TreeSet<String>();
        boolean anyWithheld = insufficient;

        BigDecimal weightedScore = BigDecimal.ZERO;
        BigDecimal weightedLower = BigDecimal.ZERO;
        BigDecimal applicableWeight = BigDecimal.ZERO;

        for (var dimSpec : model.dimensions()) {
            var dimResult = switch (dimSpec.id()) {
                case ArchitectureScoreModelV2.DIM_ACYCLIC ->
                        buildD1(dimSpec, topo, insufficient, u, model);
                case ArchitectureScoreModelV2.DIM_MODULARITY ->
                        buildD2(dimSpec, topo, insufficient, u, model);
                case ArchitectureScoreModelV2.DIM_COUPLING ->
                        buildD3(dimSpec, topo, insufficient, u, model);
                case ArchitectureScoreModelV2.DIM_HIERARCHY ->
                        buildD4(dimSpec, topo, explicitBoundaryPolicy, insufficient, u, model);
                case ArchitectureScoreModelV2.DIM_HOTSPOTS ->
                        buildD5(dimSpec, topo, insufficient, u, model);
                case ArchitectureScoreModelV2.DIM_FRAMEWORK_POLYGLOT ->
                        buildD6(dimSpec, spring, crossSvc, insufficient, u.max(uSpring), model);
                case ArchitectureScoreModelV2.DIM_POLICY ->
                        buildD7(dimSpec, customPolicy, topo.typeCount(), insufficient, model, evidenceCtx);
                default -> throw new IllegalStateException("Unexpected dimension: " + dimSpec.id());
            };
            dims.add(dimResult);
            allGaps.addAll(dimResult.gaps());
            if (dimResult.status() == AssessmentStatus.WITHHELD) {
                anyWithheld = true;
            } else if (dimResult.score().isPresent()) {
                weightedScore = weightedScore.add(dimResult.score().orElseThrow().multiply(dimSpec.weight()));
                weightedLower = weightedLower.add(dimResult.conservativeLowerBound().orElseThrow().multiply(dimSpec.weight()));
                applicableWeight = applicableWeight.add(dimSpec.weight());
            }
        }

        AssessmentStatus status;
        Optional<BigDecimal> score;
        Optional<BigDecimal> lower;
        BigDecimal delta;

        if (anyWithheld) {
            status = AssessmentStatus.WITHHELD;
            score = Optional.empty();
            lower = Optional.empty();
            delta = BigDecimal.ZERO;
            reasons.add(insufficient ? "EVIDENCE_BELOW_CONFIDENCE_FLOOR" : "DIMENSION_WITHHELD");
            allGaps.add(AssessmentGaps.create(evidenceCtx, ArchitectureAssessmentV2.VERSION, graph.identity(),
                    "INSUFFICIENT_EVIDENCE_FOR_V2_SCORE", confidence.tier().name(),
                    AffectedOutput.Kind.ASSESSMENT, "assessment.architecture-health-v2"));
        } else if (applicableWeight.signum() == 0) {
            status = AssessmentStatus.NOT_APPLICABLE;
            score = Optional.empty();
            lower = Optional.empty();
            delta = BigDecimal.ZERO;
            reasons.add("NO_APPLICABLE_DIMENSIONS");
        } else {
            BigDecimal obs = CalibrationCurve.roundDisplay(CalibrationCurve.divide(weightedScore, applicableWeight));
            BigDecimal low = CalibrationCurve.roundDisplay(CalibrationCurve.divide(weightedLower, applicableWeight)).min(obs);
            score = Optional.of(obs);
            lower = Optional.of(low);
            delta = CalibrationCurve.normalize(obs.subtract(low));
            boolean complete = confidence.tier() == ArchitectureAssessmentV2.ConfidenceTier.HIGH
                    && confidence.openGapCount() == 0
                    && delta.signum() == 0
                    && allGaps.isEmpty()
                    && dims.stream().allMatch(d -> d.status() == AssessmentStatus.COMPLETE || d.status() == AssessmentStatus.NOT_APPLICABLE);
            status = complete ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
            if (status == AssessmentStatus.PARTIAL) reasons.add("PARTIAL_EVIDENCE_QUALIFIED");
        }

        if (customPolicy.isEmpty()) reasons.add("NO_CUSTOM_POLICY_STRUCTURAL_AND_INFERRED_ACTIVE");
        if (explicitBoundaryPolicy) reasons.add("EXPLICIT_BOUNDARY_POLICY_SUPERSEDES_INFERRED_LAYERING");
        if (spring.applicable()) reasons.add("SPRING_ARCHITECTURE_EVALUATED");

        Optional<InferredLayerClassifier.LayerMap> layersOpt = topo.inferredLayers().layerMap().layers().isEmpty()
                ? Optional.empty() : Optional.of(topo.inferredLayers().layerMap());

        return new ArchitectureAssessmentV2.Result(
                graph.identity(), contextId, scopeMode,
                ArchitectureScoreModelV2.FORMULA, model.identity(), ArchitectureScoreModelV2.CALIBRATION,
                status, score, lower, delta,
                ArchitectureAssessmentV2.bandFor(score, confidence.tier()),
                applicableWeight, dims, confidence, layersOpt, contextBreakdown,
                policyReportDigest, List.copyOf(allGaps), List.copyOf(reasons), Optional.empty());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD1(
            ArchitectureScoreModelV2.DimensionSpec spec,
            StructuralTopologyAnalyzer.Metrics m,
            boolean insufficient, BigDecimal u, ArchitectureScoreModelV2 model) {
        var subs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D1_PACKAGE_RC, m.packageRelativeCyclicity(),
                m.cyclicPackageSccCount() > 0, insufficient,
                m.packageRelativeCyclicity().map(v -> "Package Relative Cyclicity=" + CalibrationCurve.roundDisplay(v)
                        + " (" + m.cyclicPackageSccCount() + " cyclic package SCCs across " + m.packageCount() + " packages)")
                        .orElse("Fewer than 2 packages in production scope."),
                "Break package cycles by extracting shared interfaces/DTOs into a neutral contract package or inverting dependencies.",
                m.packageCycleWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D1_TYPE_RC, m.typeRelativeCyclicity(),
                m.cyclicTypeSccCount() > 0, insufficient,
                m.typeRelativeCyclicity().map(v -> "Type Relative Cyclicity=" + CalibrationCurve.roundDisplay(v)
                        + " (" + m.cyclicTypeSccCount() + " cyclic type SCCs across " + m.typeCount() + " types)")
                        .orElse("Fewer than 2 types in production scope."),
                "Decouple cyclic classes via Dependency Inversion (extract interface) or domain events.",
                m.typeCycleWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D1_MODULE_RC, m.moduleRelativeCyclicity(),
                m.cyclicModuleSccCount() > 0, insufficient,
                m.moduleRelativeCyclicity().map(v -> "Module Relative Cyclicity=" + CalibrationCurve.roundDisplay(v)
                        + " (" + m.cyclicModuleSccCount() + " cyclic module SCCs across " + m.moduleCount() + " modules)")
                        .orElse("Single-module context; module cyclicity not applicable."),
                "Eliminate cross-module cycles immediately by introducing an API/SPI module.",
                m.moduleCycleWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D1_TANGLE_EDGE, m.tangleEdgeRatio(),
                false, insufficient,
                m.tangleEdgeRatio().map(v -> CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED))
                        + "% of internal dependencies are trapped inside cyclic SCCs.")
                        .orElse("No internal dependency edges to assess tangle density."),
                "Sever lowest-support feedback edges inside the largest cyclic SCCs first.",
                List.of()));
        return finalizeDimension(spec, subs, insufficient, u, model, List.of(), List.of());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD2(
            ArchitectureScoreModelV2.DimensionSpec spec,
            StructuralTopologyAnalyzer.Metrics m,
            boolean insufficient, BigDecimal u, ArchitectureScoreModelV2 model) {
        var subs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D2_DECOUPLING_LEVEL, m.decouplingLevel(),
                false, insufficient,
                m.decouplingLevel().map(v -> "DRH Decoupling Level=" + CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED))
                        + "% (OSS benchmark median ~59%, top-tier >=75%).")
                        .orElse("Fewer than 2 types in production scope."),
                "Partition large interdependent clusters behind stable interfaces so leaf modules can evolve independently.",
                m.decouplingWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D2_ENCAPSULATION, m.encapsulationRatio(),
                false, insufficient,
                m.encapsulationRatio().map(v -> CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED))
                        + "% of implementation-package types are encapsulated from external package references.")
                        .orElse("No multi-type implementation packages to measure encapsulation."),
                "Restrict implementation helpers to package-private visibility or internal subpackages behind facade APIs.",
                m.encapsulationWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D2_TURBOMQ, m.packageTurboMq(),
                false, insufficient,
                m.packageTurboMq().map(v -> "Normalized Package TurboMQ=" + CalibrationCurve.roundDisplay(v)
                        + " (cohesion vs inter-package coupling ratio).")
                        .orElse("No active intermediate packages with both intra- and cross-package edges."),
                "Co-locate tightly collaborating types into cohesive feature modules and reduce cross-package chatter.",
                m.turboMqWitnesses()));
        return finalizeDimension(spec, subs, insufficient, u, model, List.of(), List.of());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD3(
            ArchitectureScoreModelV2.DimensionSpec spec,
            StructuralTopologyAnalyzer.Metrics m,
            boolean insufficient, BigDecimal u, ArchitectureScoreModelV2 model) {
        var subs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        // Blended NCCD: score Type and Package NCCD independently when both exist so clean classes don't hide tangled packages
        var nccdSpec = requireSub(spec, ArchitectureScoreModelV2.SUB_D3_NCCD);
        ArchitectureAssessmentV2.SubMetricResult nccdSub;
        if (m.typeNccd().isEmpty()) {
            nccdSub = naSub(nccdSpec, "Fewer than 2 types in production scope.");
        } else if (insufficient) {
            nccdSub = withheldSub(nccdSpec, "Evidence below confidence floor.");
        } else {
            BigDecimal tScore = nccdSpec.curve().evaluate(m.typeNccd().orElseThrow());
            BigDecimal finalScore = m.packageNccd().isPresent()
                    ? CalibrationCurve.divide(tScore.add(nccdSpec.curve().evaluate(m.packageNccd().orElseThrow())), BigDecimal.valueOf(2))
                    : tScore;
            String expl = "Type NCCD=" + CalibrationCurve.roundDisplay(m.typeNccd().orElseThrow())
                    + m.packageNccd().map(p -> ", Package NCCD=" + CalibrationCurve.roundDisplay(p)).orElse("")
                    + " (1.0 = balanced binary tree; <=2.5 healthy; >5.0 eroded).";
            nccdSub = new ArchitectureAssessmentV2.SubMetricResult(
                    nccdSpec.id(), nccdSpec.displayName(), nccdSpec.unit(), nccdSpec.subWeight(),
                    AssessmentStatus.COMPLETE, m.blendedNccdRaw(), Optional.of(CalibrationCurve.roundDisplay(finalScore)),
                    nccdSpec.curve(), expl,
                    "Flatten deep transitive dependency chains and break cyclic cores to bring NCCD below 2.5.",
                    List.of());
        }
        subs.add(nccdSub);

        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D3_PROPAGATION_COST, m.normalizedPropagationCost(),
                false, insufficient,
                m.normalizedPropagationCost().map(v -> "Size-normalized Excess Propagation Cost="
                        + CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED)) + "%"
                        + m.rawPropagationCost().map(r -> " (raw PC=" + CalibrationCurve.roundDisplay(r.multiply(CalibrationCurve.HUNDRED)) + "%)").orElse(""))
                        .orElse("Fewer than 2 types in production scope."),
                "Reduce transitive fan-out and cyclic core size so changes stay localized.",
                List.of()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D3_EFF_COUPLING, m.excessInternalFanOut(),
                false, insufficient,
                m.excessInternalFanOut().map(v -> "Excess internal fan-out index=" + CalibrationCurve.roundDisplay(v)
                        + " (types exceeding 10 direct project dependencies: " + m.highFanOutWitnesses().size() + "+).")
                        .orElse("Fewer than 2 types in production scope."),
                "Decompose orchestrator classes with >10 direct project dependencies into focused collaborators.",
                m.highFanOutWitnesses()));
        return finalizeDimension(spec, subs, insufficient, u, model, List.of(), List.of());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD4(
            ArchitectureScoreModelV2.DimensionSpec spec,
            StructuralTopologyAnalyzer.Metrics m,
            boolean explicitBoundaryPolicy,
            boolean insufficient, BigDecimal u, ArchitectureScoreModelV2 model) {
        var subs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        var reasons = new ArrayList<String>();
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D4_SDP, m.sdpViolationRatio(),
                false, insufficient,
                m.sdpViolationRatio().map(v -> m.sdpViolationCount() + " Stable Dependencies Principle violations ("
                        + CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED)) + "% of edges point from stable to unstable).")
                        .orElse("No inter-component edges to assess SDP."),
                "Apply Dependency Inversion so stable packages depend on interfaces rather than volatile implementations.",
                m.sdpWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D4_MAIN_SEQUENCE, m.mainSequenceExcessDistance(),
                false, insufficient,
                m.mainSequenceExcessDistance().map(v -> "Size-weighted excess distance from Main Sequence="
                        + CalibrationCurve.roundDisplay(v) + " (tolerance band D<=0.35).")
                        .orElse("No eligible non-value packages to evaluate Main Sequence distance."),
                "Introduce abstractions in rigid concrete Zone-of-Pain hubs or prune unused Zone-of-Uselessness interfaces.",
                m.mainSequenceWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D4_DSM_BACK_EDGES, m.dsmBackEdgeRatio(),
                false, insufficient,
                m.dsmBackEdgeRatio().map(v -> m.dsmBackEdgeCount() + " DSM levelization back-edges ("
                        + CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED)) + "% of inter-package edges).")
                        .orElse("No inter-package edges to construct DSM levelization."),
                "Eliminate upward DSM back-edges so package dependencies form a strict top-down hierarchy.",
                m.dsmBackEdgeWitnesses()));

        var layerSpec = requireSub(spec, ArchitectureScoreModelV2.SUB_D4_INFERRED_LAYERING);
        if (explicitBoundaryPolicy) {
            subs.add(naSub(layerSpec, "Superseded by explicit owner-authored boundary/layer rules in D7 (zero double-counting)."));
            reasons.add("INFERRED_LAYERING_SUPERSEDED_BY_EXPLICIT_POLICY");
        } else {
            var la = m.inferredLayers();
            var witnesses = la.topViolations().stream()
                    .map(v -> new ArchitectureAssessmentV2.EvidenceWitness(
                            "INFERRED_LAYER_" + v.violationKind(), v.edgeIdentity(),
                            v.sourceLabel() + " [" + v.fromLayer() + "] -> " + v.targetLabel() + " [" + v.toLayer() + "]",
                            v.violationKind() + " (weight=" + v.weight() + ") [INFERRED_LAYER_CONVENTION]",
                            v.evidence()))
                    .toList();
            subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D4_INFERRED_LAYERING, la.violationRatio(),
                    false, insufficient,
                    la.violationRatio().map(v -> "Detected " + la.layerMap().detectedStyle()
                            + ": " + la.upwardLeakCount() + " upward leaks, " + la.layerSkipCount() + " layer skips across "
                            + la.crossLayerEdges() + " cross-layer edges [INFERRED_LAYER_CONVENTION].")
                            .orElse("Fewer than 2 recognizable architectural layers or no cross-layer edges."),
                    "Route calls downward through application/port abstractions; upload a custom architecture-policy:v1 YAML to customize layer rules.",
                    witnesses));
            if (la.violationRatio().isPresent()) reasons.add("INFERRED_LAYER_CONVENTION_ACTIVE");
        }
        return finalizeDimension(spec, subs, insufficient, u, model, reasons, List.of());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD5(
            ArchitectureScoreModelV2.DimensionSpec spec,
            StructuralTopologyAnalyzer.Metrics m,
            boolean insufficient, BigDecimal u, ArchitectureScoreModelV2 model) {
        var subs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D5_CROSSING_HUBS, m.crossingHubDensity(),
                false, insufficient,
                m.crossingHubDensity().map(v -> m.crossingHubCount() + " Crossing Hubs detected (high fan-in AND high fan-out; density="
                        + CalibrationCurve.roundDisplay(v) + ").")
                        .orElse("Fewer than 2 types in production scope."),
                "Split Crossing hub classes that accumulate both heavy incoming callers and heavy outgoing dependencies.",
                m.crossingHubWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D5_FAT_COMPONENTS, m.fatComponentRatio(),
                false, insufficient,
                m.fatComponentRatio().map(v -> "Fat component excess ratio=" + CalibrationCurve.roundDisplay(v)
                        + " (types >30 members / packages >35 types).")
                        .orElse("Fewer than 2 types in production scope."),
                "Extract cohesive sub-services from oversized classes and split fat packages along bounded-context lines.",
                m.fatComponentWitnesses()));
        subs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D5_GINI, m.couplingGini(),
                false, insufficient,
                m.couplingGini().map(v -> "Coupling Gini coefficient=" + CalibrationCurve.roundDisplay(v)
                        + " (<=0.55 normal OO Pareto skew; >0.75 centralized bottleneck).")
                        .orElse("Fewer than 8 connected types for statistically meaningful Gini skew."),
                "Distribute orchestration responsibilities away from monolithic hub classes.",
                List.of()));
        return finalizeDimension(spec, subs, insufficient, u, model, List.of(), List.of());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD6(
            ArchitectureScoreModelV2.DimensionSpec spec,
            SpringArchitectureAnalyzer.SpringMetrics spring,
            PolyglotArchitectureAnalyzer.CrossServiceMetrics poly,
            boolean insufficient, BigDecimal uEffective, ArchitectureScoreModelV2 model) {
        if (!spring.applicable() && !poly.applicable()) {
            var naSubs = spec.subMetrics().stream()
                    .map(s -> naSub(s, "No Spring wiring or cross-service polyglot topology observed in this context."))
                    .toList();
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    naSubs, List.of("NO_SPRING_OR_CROSS_SERVICE_SIGNALS"), List.of());
        }

        var springSubs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        var polySubs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        var reasons = new ArrayList<String>();

        if (spring.applicable()) {
            reasons.add(spring.satBacked() ? "SPRING_SAT_BACKED_ANALYSIS" : "SPRING_SOURCE_WIRING_ANALYSIS");
            springSubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_SPRING_FIELD_INJ, spring.fieldInjectionRatio(),
                    false, insufficient,
                    spring.fieldInjectionRatio().map(v -> spring.fieldInjectionCount() + " of " + spring.totalDiSiteCount()
                            + " DI sites use field injection (" + CalibrationCurve.roundDisplay(v.multiply(CalibrationCurve.HUNDRED)) + "%).")
                            .orElse("No DI sites observed."),
                    "Convert @Autowired/@Inject fields to constructor injection with final fields for immutability and testability.",
                    spring.fieldInjectionWitnesses()));
            springSubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_SPRING_BEAN_CYCLES, spring.beanRelativeCyclicity(),
                    (spring.mustCyclicBeanCount() + spring.feasibleMayOnlyCyclicBeanCount()) > 0, insufficient,
                    spring.beanRelativeCyclicity().map(v -> "Spring bean RC=" + CalibrationCurve.roundDisplay(v)
                            + " (" + spring.mustCyclicBeanCount() + " MUST cyclic beans, "
                            + spring.feasibleMayOnlyCyclicBeanCount() + " jointly feasible MAY-only cyclic beans).")
                            .orElse("No Spring bean binding graph available in this context."),
                    "Break circular bean injection by extracting a shared collaborator or publishing Spring application events.",
                    spring.beanCycleWitnesses()));
            springSubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_SPRING_WIRING, spring.wiringIssueRatio(),
                    false, insufficient,
                    spring.wiringIssueRatio().map(v -> spring.ambiguousOrUnsatisfiedSites() + " of " + spring.evaluatedInjectionUses()
                            + " evaluated required injection uses are ambiguous or unsatisfied.")
                            .orElse("No definite Spring injection uses evaluated."),
                    "Disambiguate multi-candidate injections with @Qualifier/@Primary and verify missing bean registrations.",
                    spring.wiringWitnesses()));
            springSubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_SPRING_BYPASS, spring.controllerBypassRatio(),
                    false, insufficient,
                    spring.controllerBypassRatio().map(v -> spring.bypassingControllers() + " of " + spring.totalControllers()
                            + " controllers depend directly on repositories bypassing @Service.")
                            .orElse("Requires Controller, Service, and Repository stereotypes."),
                    "Move persistence orchestration out of web controllers into transactional @Service facades.",
                    spring.bypassWitnesses()));
        }

        if (poly.applicable()) {
            reasons.add("POLYGLOT_STATIC_CANDIDATE_TOPOLOGY_RUNTIME_UNOBSERVED");
            polySubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_POLYGLOT_CYCLES, poly.syncRelativeCyclicity(),
                    poly.cyclicServiceSccCount() > 0, insufficient,
                    poly.syncRelativeCyclicity().map(v -> "Synchronous service RC=" + CalibrationCurve.roundDisplay(v)
                            + " (" + poly.cyclicServiceSccCount() + " cyclic service SCCs across " + poly.serviceCount() + " services) [STATIC_CANDIDATE].")
                            .orElse("No synchronous cross-service edges."),
                    "Break synchronous service cycles using asynchronous domain events or read-model replication.",
                    poly.cycleWitnesses()));
            polySubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_POLYGLOT_COUPLING, poly.syncCouplingExcess(),
                    false, insufficient,
                    poly.syncCouplingExcess().map(v -> "Sync coupling excess=" + CalibrationCurve.roundDisplay(v)
                            + " (" + poly.syncEdgeCount() + " sync edges, " + poly.asyncEdgeCount() + " async event edges) [STATIC_CANDIDATE].")
                            .orElse("No cross-service edges."),
                    "Reduce synchronous fan-out (>3 downstream services) via event choreography or BFF aggregation.",
                    poly.couplingWitnesses()));
            polySubs.add(evalSub(spec, ArchitectureScoreModelV2.SUB_D6_POLYGLOT_CONTRACTS, poly.contractHygieneRisk(),
                    false, insufficient,
                    poly.contractHygieneRisk().map(v -> poly.ambiguousRouteCount() + " ambiguous multi-handler routes across "
                            + poly.matchedHttpCallCount() + " matched calls; formal schemas=" + poly.formalSchemasPresent() + ".")
                            .orElse("No matched internal cross-service HTTP calls."),
                    "Publish explicit OpenAPI/Protobuf/GraphQL schemas for cross-service APIs and resolve conflicting route mappings.",
                    poly.contractWitnesses()));
        }

        if (insufficient) {
            var allSubs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
            allSubs.addAll(springSubs);
            allSubs.addAll(polySubs);
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    allSubs, List.of("EVIDENCE_BELOW_CONFIDENCE_FLOOR"), List.of());
        }

        Optional<BigDecimal> springScore = weightedSubAverage(springSubs);
        Optional<BigDecimal> polyScore = weightedSubAverage(polySubs);
        var combinedSubs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();
        combinedSubs.addAll(springSubs);
        combinedSubs.addAll(polySubs);

        Optional<BigDecimal> dimScore;
        if (springScore.isPresent() && polyScore.isPresent()) {
            dimScore = Optional.of(CalibrationCurve.roundDisplay(
                    SPRING_BLEND.multiply(springScore.orElseThrow()).add(POLYGLOT_BLEND.multiply(polyScore.orElseThrow()))));
        } else if (springScore.isPresent()) {
            dimScore = springScore.map(CalibrationCurve::roundDisplay);
        } else {
            dimScore = polyScore.map(CalibrationCurve::roundDisplay);
        }

        if (dimScore.isEmpty()) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    combinedSubs, reasons, List.of());
        }
        BigDecimal obs = dimScore.orElseThrow();
        BigDecimal delta = CalibrationCurve.roundDisplay(model.uncertaintySlope().multiply(uEffective).min(obs));
        BigDecimal low = CalibrationCurve.normalize(obs.subtract(delta));
        AssessmentStatus status = delta.signum() == 0 && !poly.applicable()
                ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        return new ArchitectureAssessmentV2.DimensionResult(
                spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                status, Optional.of(obs), Optional.of(low), delta, combinedSubs, reasons, List.of());
    }

    private ArchitectureAssessmentV2.DimensionResult buildD7(
            ArchitectureScoreModelV2.DimensionSpec spec,
            Optional<PolicyEvaluation.Result> customPolicy,
            int entityScopeCount,
            boolean insufficient,
            ArchitectureScoreModelV2 model,
            EvidenceContext evidenceCtx) {
        var subSpec = requireSub(spec, ArchitectureScoreModelV2.SUB_D7_POLICY_EXPOSURE);
        if (customPolicy.isEmpty()) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    List.of(naSub(subSpec, "No explicit user policy YAML supplied — excluded cleanly from applicable weight.")),
                    List.of("NO_EXPLICIT_USER_POLICY_SUPPLIED"), List.of());
        }

        var eval = customPolicy.orElseThrow();
        if (insufficient || eval.rules().isEmpty()) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    List.of(withheldSub(subSpec, "Policy evaluation has no compiled rules or insufficient evidence.")),
                    List.of("POLICY_EVIDENCE_INCOMPLETE"), eval.gaps());
        }

        // Count UNKNOWN rules — never treat UNKNOWN as passing!
        int totalRules = eval.rules().size();
        int unknownRules = 0;
        BigDecimal unknownWorstCaseRaw = BigDecimal.ZERO;
        var rootMaxWeights = new TreeMap<ContentDigest, BigDecimal>();
        var rootWitness = new TreeMap<ContentDigest, ArchitectureAssessmentV2.EvidenceWitness>();

        for (var rule : eval.rules()) {
            if (rule.classification() == Classification.UNKNOWN || rule.status() == AssessmentStatus.WITHHELD
                    || !rule.predicate().complete()) {
                unknownRules++;
                unknownWorstCaseRaw = unknownWorstCaseRaw.add(
                        model.policySeverityWeights().get(rule.rule().header().severity().name()));
            }
            for (var f : rule.findings()) {
                if (f.classification() != Classification.MUST && f.classification() != Classification.MAY) continue;
                var rootKey = policyRootKey(f);
                BigDecimal w = model.policySeverityWeights().get(f.rule().severity().name());
                rootMaxWeights.merge(rootKey, w, BigDecimal::max);
                rootWitness.putIfAbsent(rootKey, new ArchitectureAssessmentV2.EvidenceWitness(
                        "POLICY_" + f.rule().severity().name() + "_" + f.classification().name(),
                        f.identity().value(),
                        f.rule().id() + " (" + f.kind().name() + ")",
                        "Quantifier=" + f.classification().name() + ", path length=" + f.bundle().path().size(),
                        List.of(f.bundle().identity())));
            }
        }

        // Withhold D7 if >50% of declared rules are UNKNOWN
        if (unknownRules * 2 > totalRules) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    List.of(withheldSub(subSpec, unknownRules + " of " + totalRules + " policy rules have UNKNOWN verdict.")),
                    List.of("MAJORITY_POLICY_RULES_UNKNOWN"), eval.gaps());
        }

        BigDecimal rawPenaltySum = rootMaxWeights.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal logScale = CalibrationCurve.strictLog10(Math.max(10, entityScopeCount)).max(BigDecimal.ONE);
        BigDecimal normalizedPenalty = CalibrationCurve.divide(rawPenaltySum, logScale).min(CalibrationCurve.HUNDRED);
        BigDecimal obsScore = CalibrationCurve.roundDisplay(CalibrationCurve.HUNDRED.subtract(normalizedPenalty));

        BigDecimal worstExtra = unknownWorstCaseRaw.signum() == 0 ? BigDecimal.ZERO
                : CalibrationCurve.divide(unknownWorstCaseRaw, logScale);
        BigDecimal worstPenalty = normalizedPenalty.add(worstExtra).min(CalibrationCurve.HUNDRED);
        BigDecimal lowerScore = CalibrationCurve.roundDisplay(CalibrationCurve.HUNDRED.subtract(worstPenalty)).min(obsScore);
        BigDecimal delta = CalibrationCurve.normalize(obsScore.subtract(lowerScore));

        var witnesses = rootWitness.values().stream().limit(DEFAULT_MAX_WITNESSES).toList();
        AssessmentStatus status = unknownRules == 0 && eval.gaps().isEmpty()
                ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        var reasons = new ArrayList<String>();
        reasons.add("POTENTIAL_EXPOSURE_MUST_AND_MAY_FULL_SEVERITY");
        if (unknownRules > 0) reasons.add("UNKNOWN_POLICY_RULES_BOUNDED_IN_LOWER_SCORE");

        var sub = new ArchitectureAssessmentV2.SubMetricResult(
                subSpec.id(), subSpec.displayName(), subSpec.unit(), subSpec.subWeight(), status,
                Optional.of(CalibrationCurve.roundDisplay(normalizedPenalty)), Optional.of(obsScore),
                subSpec.curve(),
                rootMaxWeights.size() + " distinct violated roots across " + totalRules + " rules"
                        + (unknownRules > 0 ? " (" + unknownRules + " UNKNOWN rules bounded downward)" : "") + ".",
                "Resolve highest-severity policy findings in the Violations tab and verify with isolated repair.",
                witnesses);

        return new ArchitectureAssessmentV2.DimensionResult(
                spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                status, Optional.of(obsScore), Optional.of(lowerScore), delta,
                List.of(sub), reasons, eval.gaps());
    }

    private ArchitectureAssessmentV2.DimensionResult finalizeDimension(
            ArchitectureScoreModelV2.DimensionSpec spec,
            List<ArchitectureAssessmentV2.SubMetricResult> subs,
            boolean insufficient,
            BigDecimal u,
            ArchitectureScoreModelV2 model,
            List<String> extraReasons,
            List<CapabilityGapRecord> gaps) {
        if (insufficient) {
            var reasons = new ArrayList<>(extraReasons);
            reasons.add("EVIDENCE_BELOW_CONFIDENCE_FLOOR");
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    subs, reasons, gaps);
        }
        Optional<BigDecimal> avg = weightedSubAverage(subs);
        if (avg.isEmpty()) {
            var reasons = new ArrayList<>(extraReasons);
            reasons.add("NO_APPLICABLE_SUB_METRICS");
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    subs, reasons, gaps);
        }
        BigDecimal obs = CalibrationCurve.roundDisplay(avg.orElseThrow());
        BigDecimal delta = CalibrationCurve.roundDisplay(model.uncertaintySlope().multiply(u).min(obs));
        BigDecimal low = CalibrationCurve.normalize(obs.subtract(delta));
        AssessmentStatus status = delta.signum() == 0 && gaps.isEmpty()
                ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        return new ArchitectureAssessmentV2.DimensionResult(
                spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                status, Optional.of(obs), Optional.of(low), delta, subs, extraReasons, gaps);
    }

    private static Optional<BigDecimal> weightedSubAverage(List<ArchitectureAssessmentV2.SubMetricResult> subs) {
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (var s : subs) {
            if (s.score().isPresent()) {
                num = num.add(s.score().orElseThrow().multiply(s.subWeight()));
                den = den.add(s.subWeight());
            }
        }
        return den.signum() == 0 ? Optional.empty() : Optional.of(CalibrationCurve.divide(num, den));
    }

    private static ArchitectureAssessmentV2.SubMetricResult evalSub(
            ArchitectureScoreModelV2.DimensionSpec dim,
            String subId,
            Optional<BigDecimal> raw,
            boolean hasDefect,
            boolean insufficient,
            String explanation,
            String remediation,
            List<ArchitectureAssessmentV2.EvidenceWitness> witnesses) {
        var spec = requireSub(dim, subId);
        if (raw.isEmpty()) return naSub(spec, explanation);
        if (insufficient) return withheldSub(spec, "Evidence below confidence floor.");
        BigDecimal val = raw.orElseThrow();
        BigDecimal score = CalibrationCurve.roundDisplay(spec.curve().evaluate(val, hasDefect));
        return new ArchitectureAssessmentV2.SubMetricResult(
                spec.id(), spec.displayName(), spec.unit(), spec.subWeight(),
                AssessmentStatus.COMPLETE, Optional.of(CalibrationCurve.round(val, 6)), Optional.of(score),
                spec.curve(), explanation, remediation, witnesses);
    }

    private static ArchitectureAssessmentV2.SubMetricResult naSub(ArchitectureScoreModelV2.SubMetricSpec spec, String reason) {
        return new ArchitectureAssessmentV2.SubMetricResult(
                spec.id(), spec.displayName(), spec.unit(), spec.subWeight(),
                AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(),
                spec.curve(), reason, "", List.of());
    }

    private static ArchitectureAssessmentV2.SubMetricResult withheldSub(ArchitectureScoreModelV2.SubMetricSpec spec, String reason) {
        return new ArchitectureAssessmentV2.SubMetricResult(
                spec.id(), spec.displayName(), spec.unit(), spec.subWeight(),
                AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(),
                spec.curve(), reason, "", List.of());
    }

    private static ArchitectureScoreModelV2.SubMetricSpec requireSub(ArchitectureScoreModelV2.DimensionSpec dim, String id) {
        return dim.subMetrics().stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing sub-metric " + id + " in " + dim.id()));
    }

    // ─── Graph Extraction & Confidence Computation ───

    private static StructuralTopologyAnalyzer.StructuralGraph extractJavaStructuralGraph(
            ArchitectureQueryService queries, String javaContextId, GraphEvidenceIndex evIndex, Runnable checkpoint) {
        var graph = queries.graph();
        var req = new Request(queries.graphIdentity(), javaContextId, Scope.TYPE, Projection.BOUNDED_UNION,
                Direction.OUTGOING, Set.of("graph.depends-on"),
                new Population(Set.of("TYPE"), Set.of(), Set.of("PROJECT"), Set.of()),
                false, Optional.empty(), Limits.defaults());
        var projected = queries.metricProjection(req);
        checkpoint.run();
        var mp = projected.items().isEmpty()
                ? new MetricProjection(List.of(), List.of(), List.of(), List.of())
                : projected.items().getFirst();

        var typeShapes = evIndex.typeShapes;

        var typeToPkgId = new HashMap<String, String>();
        var pkgToModId = new HashMap<String, String>();
        var memberCounts = new HashMap<String, Integer>();
        var annByNodeId = new HashMap<String, String>();
        var typeAnnotations = new HashMap<String, Set<String>>();
        var pkgNames = new HashMap<String, String>();
        var modNames = new HashMap<String, String>();

        for (var node : graph.nodes(javaContextId)) {
            if ("ANNOTATION_USE".equals(node.kind())) {
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev != null && "java.annotation-use".equals(ev.family())) {
                        try {
                            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                            String name = obj.get("annotationName") instanceof String s ? s
                                    : obj.get("spelling") instanceof String s2 ? s2 : "";
                            if (!name.isEmpty()) annByNodeId.put(node.identity(), name);
                        } catch (RuntimeException ignored) {}
                    }
                }
            } else if ("MODULE".equals(node.kind())) {
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev != null && ("module.descriptor".equals(ev.family()) || "build.module".equals(ev.family()))) {
                        try {
                            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                            var desc = obj.get("descriptor") instanceof Map<?, ?> d ? GraphJson.object(d) : obj;
                            if (desc.get("path") instanceof String p && !p.isBlank()) modNames.put(node.identity(), p);
                        } catch (RuntimeException ignored) {}
                    }
                }
            }
        }

        var nodeKindById = new HashMap<String, String>();
        for (var node : graph.nodes(javaContextId)) nodeKindById.put(node.identity(), node.kind());

        for (var edge : graph.edges(javaContextId)) {
            if (edge.target().state() != KnowledgeGraph.TargetState.RESOLVED) continue;
            String tgt = edge.target().identities().getFirst();
            if ("graph.contains".equals(edge.kind())) {
                String srcKind = nodeKindById.get(edge.source());
                String tgtKind = nodeKindById.get(tgt);
                if ("PACKAGE".equals(srcKind) && "TYPE".equals(tgtKind)) {
                    typeToPkgId.put(tgt, edge.source());
                } else if ("MODULE".equals(srcKind) && "PACKAGE".equals(tgtKind)) {
                    pkgToModId.put(tgt, edge.source());
                }
            } else if ("graph.declares".equals(edge.kind())) {
                String tgtKind = nodeKindById.get(tgt);
                if (Set.of("METHOD", "FIELD", "CONSTRUCTOR").contains(tgtKind)) {
                    memberCounts.merge(edge.source(), 1, Integer::sum);
                }
            } else if ("graph.has-annotation-use".equals(edge.kind())) {
                String ann = annByNodeId.get(tgt);
                if (ann != null) {
                    typeAnnotations.computeIfAbsent(edge.source(), k -> new TreeSet<>()).add(ann);
                }
            }
        }

        var nodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>();
        var keptIds = new HashSet<String>();
        for (var ev : mp.entities()) {
            if (ev.origin().isPresent() && !"PROJECT".equals(ev.origin().orElseThrow())) continue;
            if (ev.sourceSet().isPresent() && "TEST".equals(ev.sourceSet().orElseThrow())) continue;
            keptIds.add(ev.identity());
            String label = ev.qualifiedName().or(ev::spelling).or(ev::canonicalName).orElse(ev.identity());
            String pkgId = typeToPkgId.getOrDefault(ev.identity(), "");
            String pkgName = extractPackageName(ev.qualifiedName().orElse(""), label);
            if (!pkgId.isEmpty() && !pkgName.isEmpty()) pkgNames.putIfAbsent(pkgId, pkgName);
            String modId = ev.module().orElseGet(() -> pkgToModId.getOrDefault(pkgId, ""));
            String modName = modNames.getOrDefault(modId, modId);
            String[] shape = typeShapes.get(ev.identity());
            String kind = shape != null ? shape[0] : "CLASS";
            boolean abs = shape != null && ("true".equals(shape[1]) || "INTERFACE".equals(kind) || "ANNOTATION".equals(kind));
            nodes.add(new StructuralTopologyAnalyzer.NodeInfo(
                    ev.identity(), label, pkgId, pkgName, modId, modName, kind, abs,
                    memberCounts.getOrDefault(ev.identity(), 0),
                    typeAnnotations.getOrDefault(ev.identity(), Set.of()),
                    ev.evidence()));
        }

        var edges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>();
        for (var edge : mp.edges()) {
            if (!edge.definite()) continue;
            if (!keptIds.contains(edge.source()) || !keptIds.contains(edge.target())) continue;
            edges.add(new StructuralTopologyAnalyzer.EdgeInfo(edge.identity(), edge.source(), edge.target(), edge.evidence()));
        }
        return new StructuralTopologyAnalyzer.StructuralGraph(nodes, edges);
    }

    private static ArchitectureAssessmentV2.AnalysisConfidenceV2 computeContextConfidence(
            KnowledgeGraph graph,
            String contextId,
            Optional<String> javaCtxId,
            Optional<String> springCtxId,
            String evidenceTier,
            SpringArchitectureAnalyzer.SpringMetrics spring,
            ArchitectureScoreModelV2 model,
            GraphEvidenceIndex evIndex) {

        long processedSources = 0;
        long totalSources = 0;
        boolean sourceIndexUnreadable = false;
        long resolvedObs = 0;
        long attemptedObs = 0;
        long semanticResolved = 0;
        long semanticUnresolvedOrAmbiguous = 0;
        long fallbackResolved = 0;
        long fallbackUnresolvedOrAmbiguous = 0;

        String inspectCtx = javaCtxId.orElse(contextId);
        // 1. Parse completeness from pre-indexed frontend.result-index / frontend.result (O(1) lookup, S2).
        var ctxObj = evIndex.contextById.get(inspectCtx);
        if (ctxObj != null && ctxObj.analysis().isPresent()) {
            String analysisId = ctxObj.analysis().orElseThrow().value();
            var ev = evIndex.frontendIndexByAnalysis.get(analysisId);
            if (ev != null) {
                String raw = ev.canonicalJson();
                String sourcesSlice = extractTopLevelJsonSlice(raw, "sources");
                if (sourcesSlice != null) {
                    try {
                        int maxChars = Math.max(2_000_000, sourcesSlice.length() + 1024);
                        int maxValues = Math.max(50_000, sourcesSlice.length() / 8 + 1024);
                        var sources = GraphJson.array(GraphJson.read(sourcesSlice, new GraphJson.Limits(maxChars, 32, maxValues)));
                        for (Object s : sources) {
                            totalSources++;
                            if (s instanceof Map<?, ?> m) {
                                // Both PROCESSED and PARTIAL indicate the source parsed cleanly (PARTIAL still has
                                // unresolved/ambiguous references, which are strictly gated by internalEdgeCompleteness
                                // and overallSemanticResolution). Actual parse/skip failures (ERROR, REJECTED,
                                // NOT_PROCESSED, UNSUPPORTED) still lower parseCompleteness toward the 0.60 WITHHELD floor.
                                Object st = m.get("state");
                                if ("PROCESSED".equals(st) || "PARTIAL".equals(st)) {
                                    processedSources++;
                                }
                            }
                        }
                    } catch (RuntimeException unreadable) {
                        sourceIndexUnreadable = true;
                    }
                } else {
                    String sourcePagesSlice = extractTopLevelJsonSlice(raw, "sourcePages");
                    if (sourcePagesSlice == null) {
                        sourceIndexUnreadable = true;
                    } else {
                        try {
                            long[] counts = { 0L, 0L };
                            var pageRefs = GraphJson.array(GraphJson.read(sourcePagesSlice, READ_LIMITS));
                            collectPagedSourceCounts(graph, pageRefs, counts, 0);
                            totalSources = counts[0];
                            processedSources = counts[1];
                        } catch (RuntimeException unreadable) {
                            sourceIndexUnreadable = true;
                        }
                    }
                }
            }
        }

        // 2. Semantic observation & relationship resolution completeness
        for (var node : graph.nodes(inspectCtx)) {
            if ("OBSERVATION".equals(node.kind())) {
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null || !"java.observation".equals(ev.family())) continue;
                    try {
                        var row = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                        attemptedObs++;
                        if ("RESOLVED".equals(row.get("attribution"))) resolvedObs++;
                    } catch (RuntimeException ignored) {}
                }
            }
        }

        // Measure resolution completeness strictly over semantic dependency edges (excluding structural ownership
        // edges like java.declares / java.has-parameter and derived graph.* projections so resolved edges are never
        // double-counted, and counting CANDIDATES in the unresolved/ambiguous denominator). Fall back to
        // graph.depends-on only when zero resolved semantic dependency edges exist (synthetic unit fixtures).
        for (var edge : graph.edges(inspectCtx)) {
            String kind = edge.kind();
            var state = edge.target().state();
            if (kind.startsWith("graph.")) {
                if ("graph.depends-on".equals(kind)) {
                    if (state == KnowledgeGraph.TargetState.RESOLVED) {
                        fallbackResolved++;
                    } else if (state == KnowledgeGraph.TargetState.UNRESOLVED
                            || state == KnowledgeGraph.TargetState.CANDIDATES) {
                        fallbackUnresolvedOrAmbiguous++;
                    }
                }
                continue;
            }
            if ("java.declares".equals(kind) || "java.has-parameter".equals(kind)) {
                continue;
            }
            if (state == KnowledgeGraph.TargetState.RESOLVED) {
                semanticResolved++;
            } else if (state == KnowledgeGraph.TargetState.UNRESOLVED
                    || state == KnowledgeGraph.TargetState.CANDIDATES) {
                semanticUnresolvedOrAmbiguous++;
            }
        }
        long resolvedInternal;
        long unresolvedInternal;
        if (semanticResolved > 0) {
            resolvedInternal = semanticResolved;
            unresolvedInternal = semanticUnresolvedOrAmbiguous;
        } else if (fallbackResolved > 0) {
            resolvedInternal = fallbackResolved;
            unresolvedInternal = semanticUnresolvedOrAmbiguous + fallbackUnresolvedOrAmbiguous;
        } else {
            resolvedInternal = 0;
            unresolvedInternal = semanticUnresolvedOrAmbiguous + fallbackUnresolvedOrAmbiguous;
        }

        // 3. Gap counting & obligation bucketing
        var gapBuckets = new TreeMap<String, Long>();
        long openGaps = 0;
        var relevantContexts = new HashSet<String>();
        relevantContexts.add(contextId);
        javaCtxId.ifPresent(relevantContexts::add);
        springCtxId.ifPresent(relevantContexts::add);
        for (String cid : relevantContexts) {
            for (var node : graph.nodes(cid)) {
                if (!"CAPABILITY_GAP".equals(node.kind()) && !"PROVIDER_CONFLICT".equals(node.kind())) continue;
                openGaps++;
                String bucket = classifyGapBucket(graph, node);
                gapBuckets.merge(bucket, 1L, Long::sum);
            }
        }

        BigDecimal parseComp = sourceIndexUnreadable
                ? BigDecimal.ZERO
                : (totalSources == 0 ? BigDecimal.ONE : CalibrationCurve.divide(processedSources, totalSources));
        BigDecimal overallRes = attemptedObs == 0
                ? (resolvedInternal + unresolvedInternal == 0 ? BigDecimal.ONE
                        : CalibrationCurve.divide(resolvedInternal, resolvedInternal + unresolvedInternal))
                : CalibrationCurve.divide(resolvedObs, attemptedObs);
        BigDecimal internalComp = (resolvedInternal + unresolvedInternal) == 0
                ? overallRes
                : CalibrationCurve.divide(resolvedInternal, resolvedInternal + unresolvedInternal);
        BigDecimal buildComp = gapBuckets.getOrDefault("environment", 0L) == 0
                ? BigDecimal.ONE
                : new BigDecimal("0.85");

        // Weighted confidence score (0..100)
        BigDecimal wParse = new BigDecimal("0.25");
        BigDecimal wInternal = new BigDecimal("0.35");
        BigDecimal wOverall = new BigDecimal("0.20");
        BigDecimal wBuild = new BigDecimal("0.10");
        BigDecimal wSpring = new BigDecimal("0.10");
        BigDecimal weighted;
        if (spring.spaceDefiniteness().isPresent()) {
            weighted = parseComp.multiply(wParse)
                    .add(internalComp.multiply(wInternal))
                    .add(overallRes.multiply(wOverall))
                    .add(buildComp.multiply(wBuild))
                    .add(spring.spaceDefiniteness().orElseThrow().multiply(wSpring));
        } else {
            BigDecimal sumW = wParse.add(wInternal).add(wOverall).add(wBuild);
            weighted = CalibrationCurve.divide(
                    parseComp.multiply(wParse).add(internalComp.multiply(wInternal))
                            .add(overallRes.multiply(wOverall)).add(buildComp.multiply(wBuild)),
                    sumW);
        }
        BigDecimal confScore = CalibrationCurve.roundDisplay(weighted.multiply(CalibrationCurve.HUNDRED));

        var limitations = new ArrayList<String>();
        if (sourceIndexUnreadable) {
            limitations.add("Frontend source outcome index for this context could not be parsed; parse completeness cannot be established.");
        }
        ArchitectureAssessmentV2.ConfidenceTier tier;
        if (parseComp.compareTo(model.minParseCompleteness()) < 0
                || internalComp.compareTo(model.minInternalEdgeCompleteness()) < 0
                || confScore.compareTo(LOW_CONFIDENCE_FLOOR) < 0) {
            tier = ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT;
            limitations.add("Evidence completeness fell below the hard withholding floor (parse>=60%, internal-resolution>=50%).");
        } else if ("SOURCE_SYNTAX".equals(evidenceTier)) {
            tier = confScore.compareTo(MODERATE_CONFIDENCE_FLOOR) >= 0
                    ? ArchitectureAssessmentV2.ConfidenceTier.MODERATE
                    : ArchitectureAssessmentV2.ConfidenceTier.LOW;
            confScore = confScore.min(new BigDecimal("85"));
            limitations.add("Polyglot syntax-only extraction: capped at MODERATE confidence without native type-checker resolution.");
        } else if ("FAST_ARCHITECTURE".equals(evidenceTier)) {
            tier = confScore.compareTo(MODERATE_CONFIDENCE_FLOOR) >= 0
                    ? ArchitectureAssessmentV2.ConfidenceTier.MODERATE
                    : ArchitectureAssessmentV2.ConfidenceTier.LOW;
            limitations.add("Analyzed under FAST_ARCHITECTURE profile; run FULL semantic analysis for maximum precision.");
        } else if (confScore.compareTo(HIGH_CONFIDENCE_FLOOR) >= 0
                && parseComp.compareTo(HIGH_PARSE_FLOOR) >= 0
                && internalComp.compareTo(HIGH_INTERNAL_FLOOR) >= 0) {
            tier = ArchitectureAssessmentV2.ConfidenceTier.HIGH;
        } else if (confScore.compareTo(MODERATE_CONFIDENCE_FLOOR) >= 0) {
            tier = ArchitectureAssessmentV2.ConfidenceTier.MODERATE;
        } else {
            tier = ArchitectureAssessmentV2.ConfidenceTier.LOW;
            limitations.add("Low evidence confidence; observed score is an upper bound qualified by a downward uncertainty interval.");
        }
        if (openGaps > 0) {
            limitations.add(openGaps + " open capability gap(s) recorded; unresolved references are never counted as clean dependencies.");
        }

        return new ArchitectureAssessmentV2.AnalysisConfidenceV2(
                confScore, tier, evidenceTier, parseComp, internalComp, overallRes, buildComp,
                spring.spaceDefiniteness(), openGaps, gapBuckets, limitations);
    }

    private static ArchitectureAssessmentV2.AnalysisConfidenceV2 aggregateRepositoryConfidence(
            KnowledgeGraph graph,
            String root,
            List<ArchitectureAssessmentV2.Result> results,
            List<Integer> weights,
            ArchitectureScoreModelV2 model) {
        if (results.isEmpty()) {
            return new ArchitectureAssessmentV2.AnalysisConfidenceV2(
                    new BigDecimal("70"), ArchitectureAssessmentV2.ConfidenceTier.MODERATE, "STATIC_CANDIDATE",
                    BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, Optional.empty(),
                    countGaps(graph, root), Map.of(),
                    List.of("Cross-service static candidate topology only (runtimeObserved: false)."));
        }
        long totalW = 0;
        BigDecimal confSum = BigDecimal.ZERO, parseSum = BigDecimal.ZERO, intSum = BigDecimal.ZERO,
                ovSum = BigDecimal.ZERO, bldSum = BigDecimal.ZERO;
        BigDecimal springSum = BigDecimal.ZERO;
        long springW = 0;
        long gaps = countGaps(graph, root);
        var buckets = new TreeMap<String, Long>();
        boolean anyInsufficient = false;
        boolean allSyntaxOnly = true;

        for (int i = 0; i < results.size(); i++) {
            int w = weights.get(i);
            var c = results.get(i).confidence();
            totalW += w;
            BigDecimal bw = BigDecimal.valueOf(w);
            confSum = confSum.add(c.confidenceScore().multiply(bw));
            parseSum = parseSum.add(c.parseCompleteness().multiply(bw));
            intSum = intSum.add(c.internalEdgeCompleteness().multiply(bw));
            ovSum = ovSum.add(c.overallSemanticResolution().multiply(bw));
            bldSum = bldSum.add(c.buildCompleteness().multiply(bw));
            if (c.springSpaceDefiniteness().isPresent()) {
                springSum = springSum.add(c.springSpaceDefiniteness().orElseThrow().multiply(bw));
                springW += w;
            }
            gaps += c.openGapCount();
            c.gapBuckets().forEach((k, v) -> buckets.merge(k, v, Long::sum));
            if (c.tier() == ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT) anyInsufficient = true;
            if (!"SOURCE_SYNTAX".equals(c.evidenceTier())) allSyntaxOnly = false;
        }
        BigDecimal tw = BigDecimal.valueOf(Math.max(1, totalW));
        BigDecimal conf = CalibrationCurve.roundDisplay(CalibrationCurve.divide(confSum, tw));
        BigDecimal parse = CalibrationCurve.divide(parseSum, tw);
        BigDecimal internal = CalibrationCurve.divide(intSum, tw);
        BigDecimal overall = CalibrationCurve.divide(ovSum, tw);
        BigDecimal build = CalibrationCurve.divide(bldSum, tw);
        Optional<BigDecimal> springDef = springW == 0 ? Optional.empty()
                : Optional.of(CalibrationCurve.divide(springSum, BigDecimal.valueOf(springW)));

        ArchitectureAssessmentV2.ConfidenceTier tier;
        if (anyInsufficient || parse.compareTo(model.minParseCompleteness()) < 0
                || internal.compareTo(model.minInternalEdgeCompleteness()) < 0) {
            tier = ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT;
        } else if (allSyntaxOnly) {
            tier = conf.compareTo(MODERATE_CONFIDENCE_FLOOR) >= 0
                    ? ArchitectureAssessmentV2.ConfidenceTier.MODERATE
                    : ArchitectureAssessmentV2.ConfidenceTier.LOW;
        } else if (conf.compareTo(HIGH_CONFIDENCE_FLOOR) >= 0
                && parse.compareTo(HIGH_PARSE_FLOOR) >= 0
                && internal.compareTo(HIGH_INTERNAL_FLOOR) >= 0) {
            tier = ArchitectureAssessmentV2.ConfidenceTier.HIGH;
        } else if (conf.compareTo(MODERATE_CONFIDENCE_FLOOR) >= 0) {
            tier = ArchitectureAssessmentV2.ConfidenceTier.MODERATE;
        } else {
            tier = ArchitectureAssessmentV2.ConfidenceTier.LOW;
        }
        return new ArchitectureAssessmentV2.AnalysisConfidenceV2(
                conf, tier, allSyntaxOnly ? "SOURCE_SYNTAX" : "MULTI_CONTEXT_SEMANTIC",
                parse, internal, overall, build, springDef, gaps, buckets,
                List.of("Size-weighted aggregation across " + results.size() + " production context(s)."));
    }

    private ArchitectureAssessmentV2.DimensionResult aggregateDimensionAcrossContexts(
            ArchitectureScoreModelV2.DimensionSpec spec,
            List<ArchitectureAssessmentV2.Result> results,
            List<Integer> weights) {
        BigDecimal scoreNum = BigDecimal.ZERO;
        BigDecimal lowerNum = BigDecimal.ZERO;
        long totalW = 0;
        boolean anyWithheld = false;
        var gaps = new TreeSet<CapabilityGapRecord>();
        var reasons = new TreeSet<String>();
        var subSample = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>();

        for (int i = 0; i < results.size(); i++) {
            var opt = results.get(i).dimension(spec.id());
            if (opt.isEmpty()) continue;
            var d = opt.orElseThrow();
            gaps.addAll(d.gaps());
            if (d.status() == AssessmentStatus.WITHHELD) {
                anyWithheld = true;
            } else if (d.score().isPresent()) {
                BigDecimal w = BigDecimal.valueOf(weights.get(i));
                scoreNum = scoreNum.add(d.score().orElseThrow().multiply(w));
                lowerNum = lowerNum.add(d.conservativeLowerBound().orElseThrow().multiply(w));
                totalW += weights.get(i);
                if (subSample.isEmpty()) subSample.addAll(d.subMetrics());
            }
        }
        if (anyWithheld) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    List.of(), List.of("CONTEXT_DIMENSION_WITHHELD"), List.copyOf(gaps));
        }
        if (totalW == 0) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.NOT_APPLICABLE, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    List.of(), List.of("NOT_APPLICABLE_IN_ALL_CONTEXTS"), List.copyOf(gaps));
        }
        BigDecimal obs = CalibrationCurve.roundDisplay(CalibrationCurve.divide(scoreNum, BigDecimal.valueOf(totalW)));
        BigDecimal low = CalibrationCurve.roundDisplay(CalibrationCurve.divide(lowerNum, BigDecimal.valueOf(totalW))).min(obs);
        BigDecimal delta = CalibrationCurve.normalize(obs.subtract(low));
        reasons.add("SIZE_WEIGHTED_CONTEXT_AVERAGE");
        AssessmentStatus status = delta.signum() == 0 && gaps.isEmpty() ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;
        return new ArchitectureAssessmentV2.DimensionResult(
                spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                status, Optional.of(obs), Optional.of(low), delta, subSample, List.copyOf(reasons), List.copyOf(gaps));
    }

    private ArchitectureAssessmentV2.DimensionResult buildPolyglotOnlyD6(
            ArchitectureScoreModelV2.DimensionSpec spec,
            PolyglotArchitectureAnalyzer.CrossServiceMetrics poly,
            BigDecimal u, ArchitectureScoreModelV2 model) {
        return buildD6(spec, SpringArchitectureAnalyzer.SpringMetrics.notApplicable(), poly, false, u, model);
    }

    private static ArchitectureAssessmentV2.DimensionResult mergeSpringAndPolyglotD6(
            ArchitectureScoreModelV2.DimensionSpec spec,
            ArchitectureAssessmentV2.DimensionResult springDim,
            ArchitectureAssessmentV2.DimensionResult polyDim,
            BigDecimal u, ArchitectureScoreModelV2 model) {
        if (springDim.status() == AssessmentStatus.WITHHELD || polyDim.status() == AssessmentStatus.WITHHELD) {
            return new ArchitectureAssessmentV2.DimensionResult(
                    spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                    AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                    List.of(), List.of("FRAMEWORK_OR_POLYGLOT_WITHHELD"), List.of());
        }
        if (springDim.score().isEmpty()) return polyDim;
        if (polyDim.score().isEmpty()) return springDim;
        BigDecimal obs = CalibrationCurve.roundDisplay(
                SPRING_BLEND.multiply(springDim.score().orElseThrow()).add(POLYGLOT_BLEND.multiply(polyDim.score().orElseThrow())));
        BigDecimal low = CalibrationCurve.roundDisplay(
                SPRING_BLEND.multiply(springDim.conservativeLowerBound().orElseThrow())
                        .add(POLYGLOT_BLEND.multiply(polyDim.conservativeLowerBound().orElseThrow()))).min(obs);
        var subs = new ArrayList<ArchitectureAssessmentV2.SubMetricResult>(springDim.subMetrics());
        subs.addAll(polyDim.subMetrics());
        var reasons = new TreeSet<>(springDim.reasons());
        reasons.addAll(polyDim.reasons());
        return new ArchitectureAssessmentV2.DimensionResult(
                spec.id(), spec.displayName(), spec.iso25010Mapping(), spec.weight(),
                AssessmentStatus.PARTIAL, Optional.of(obs), Optional.of(low),
                CalibrationCurve.normalize(obs.subtract(low)), subs, List.copyOf(reasons), List.of());
    }

    private static ArchitectureAssessmentV2.Result withheldInterrupted(
            KnowledgeGraph graph, String contextId, String scopeMode,
            ArchitectureScoreModelV2 model, StopReason stop, EvidenceContext evCtx) {
        var gap = AssessmentGaps.create(evCtx, ArchitectureAssessmentV2.VERSION, graph.identity(),
                "ASSESSMENT_" + stop.name(), stop.name(),
                AffectedOutput.Kind.ASSESSMENT, "assessment.architecture-health-v2");
        var conf = new ArchitectureAssessmentV2.AnalysisConfidenceV2(
                BigDecimal.ZERO, ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, "INTERRUPTED",
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty(),
                1, Map.of("boundary", 1L), List.of("Assessment interrupted: " + stop.name()));
        return new ArchitectureAssessmentV2.Result(
                graph.identity(), contextId, scopeMode,
                ArchitectureScoreModelV2.FORMULA, model.identity(), ArchitectureScoreModelV2.CALIBRATION,
                AssessmentStatus.WITHHELD, Optional.empty(), Optional.empty(), BigDecimal.ZERO,
                "Withheld", BigDecimal.ZERO, List.of(), conf, Optional.empty(), List.of(),
                Optional.empty(), List.of(gap), List.of("ASSESSMENT_" + stop.name()), Optional.empty());
    }

    // ─── Helper Utilities ───

    private static ContentDigest policyRootKey(PolicyEvaluation.Finding finding) {
        var directed = finding.bundle().relationships().stream()
                .map(e -> List.of(e.source(), e.target(), e.kinds()))
                .sorted(Comparator.comparing(CanonicalJson::write))
                .toList();
        return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(
                "v2-policy-root", finding.rule().scope(),
                finding.kind() == ArchitecturePolicy.Kind.ACYCLIC ? "cycle" : "dependency", directed)));
    }

    private static Optional<String> resolveLinkedJavaContext(KnowledgeGraph graph, KnowledgeGraph.Context springCtx) {
        var ev = graph.evidence(springCtx.evidence()).orElse(null);
        if (ev != null) {
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                if (obj.get("analysis") instanceof String analysisId) {
                    if (graph.contexts().stream().anyMatch(c -> c.identity().equals(analysisId))) {
                        return Optional.of(analysisId);
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        return springCtx.analysis().map(a -> a.value())
                .filter(id -> graph.contexts().stream().anyMatch(c -> c.identity().equals(id)));
    }

    private static String detectJavaEvidenceTier(KnowledgeGraph graph, String javaContextId, GraphEvidenceIndex evIndex) {
        var ctx = evIndex.contextById.get(javaContextId);
        if (ctx != null) {
            var ev = graph.evidence(ctx.evidence()).orElse(null);
            if (ev != null && ev.canonicalJson().contains("FAST_ARCHITECTURE")) return "FAST_ARCHITECTURE";
        }
        return "FULL_SEMANTIC";
    }

    private static boolean isTestOnlyContext(GraphEvidence manifestEvidence) {
        try {
            var obj = GraphJson.object(GraphJson.read(manifestEvidence.canonicalJson(), READ_LIMITS));
            if (obj.get("configuration") instanceof Map<?, ?> cfg
                    && GraphJson.object(cfg).get("values") instanceof Map<?, ?> vals) {
                Object ss = vals.get("java.source-set");
                return "TEST".equals(ss) || "GENERATED_TEST".equals(ss);
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private static int countContextTypes(KnowledgeGraph graph, String contextId) {
        int c = 0;
        for (var n : graph.nodes(contextId)) {
            if ("TYPE".equals(n.kind())) c++;
        }
        return c;
    }

    private static long countGaps(KnowledgeGraph graph, String contextId) {
        long c = 0;
        for (var n : graph.nodes(contextId)) {
            if ("CAPABILITY_GAP".equals(n.kind()) || "PROVIDER_CONFLICT".equals(n.kind())) c++;
        }
        return c;
    }

    private static void collectPagedSourceCounts(KnowledgeGraph graph, List<Object> pageRefs, long[] counts, int depth) {
        if (depth > 8) throw new IllegalArgumentException("Page depth limit");
        for (Object refObj : pageRefs) {
            var pageEv = graph.evidence(new ContentDigest(GraphJson.string(refObj)))
                    .orElseThrow(() -> new IllegalArgumentException("Missing source page"));
            var pageObj = GraphJson.object(GraphJson.read(pageEv.canonicalJson(), READ_LIMITS));
            if ("frontend.result-source-page.dir".equals(pageEv.family())) {
                collectPagedSourceCounts(graph, GraphJson.array(pageObj.get("pages")), counts, depth + 1);
            } else if ("frontend.result-source-page".equals(pageEv.family())) {
                for (Object s : GraphJson.array(pageObj.get("sources"))) {
                    counts[0]++;
                    if (s instanceof Map<?, ?> m) {
                        Object st = m.get("state");
                        if ("PROCESSED".equals(st) || "PARTIAL".equals(st)) {
                            counts[1]++;
                        }
                    }
                }
            } else {
                throw new IllegalArgumentException("Unexpected source page family: " + pageEv.family());
            }
        }
    }

    /**
     * Extracts the raw JSON value slice of a single top-level field from a canonical JSON object
     * without materializing sibling huge arrays (e.g., 50k+ occurrence digests in frontend.result-index).
     */
    private static String extractTopLevelJsonSlice(String json, String fieldName) {
        if (json == null) return null;
        int len = json.length();
        int i = skipWs(json, 0, len);
        if (i >= len || json.charAt(i) != '{') return null;
        i++;
        while (i < len) {
            i = skipWs(json, i, len);
            if (i >= len || json.charAt(i) == '}') return null;
            if (json.charAt(i) == ',') {
                i++;
                continue;
            }
            if (json.charAt(i) != '"') return null;
            int keyEnd = findJsonStringEnd(json, i, len);
            if (keyEnd < 0) return null;
            String key = unescapeSimpleJsonString(json, i + 1, keyEnd);
            i = skipWs(json, keyEnd + 1, len);
            if (i >= len || json.charAt(i) != ':') return null;
            i = skipWs(json, i + 1, len);
            int valEnd = findJsonValueEnd(json, i, len);
            if (valEnd < 0) return null;
            if (fieldName.equals(key)) {
                return json.substring(i, valEnd);
            }
            i = valEnd;
        }
        return null;
    }

    private static String extractTopLevelJsonString(String json, String fieldName) {
        String slice = extractTopLevelJsonSlice(json, fieldName);
        if (slice == null || slice.length() < 2 || slice.charAt(0) != '"' || slice.charAt(slice.length() - 1) != '"') {
            return null;
        }
        try {
            return GraphJson.string(GraphJson.read(slice, READ_LIMITS));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static int skipWs(String s, int i, int len) {
        while (i < len) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\r' && c != '\n') break;
            i++;
        }
        return i;
    }

    private static int findJsonStringEnd(String s, int quoteStart, int len) {
        int i = quoteStart + 1;
        while (i < len) {
            char c = s.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') return i;
            i++;
        }
        return -1;
    }

    private static String unescapeSimpleJsonString(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            if (s.charAt(i) == '\\') {
                try {
                    return GraphJson.string(GraphJson.read(s.substring(start - 1, end + 1), READ_LIMITS));
                } catch (RuntimeException ignored) {
                    return "";
                }
            }
        }
        return s.substring(start, end);
    }

    private static int findJsonValueEnd(String s, int start, int len) {
        if (start >= len) return -1;
        char c = s.charAt(start);
        if (c == '"') {
            int end = findJsonStringEnd(s, start, len);
            return end < 0 ? -1 : end + 1;
        }
        if (c == '{' || c == '[') {
            int depth = 0;
            boolean inStr = false;
            for (int i = start; i < len; i++) {
                char ch = s.charAt(i);
                if (inStr) {
                    if (ch == '\\') i++;
                    else if (ch == '"') inStr = false;
                    continue;
                }
                if (ch == '"') {
                    inStr = true;
                } else if (ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) return i + 1;
                }
            }
            return -1;
        }
        int i = start;
        while (i < len) {
            char ch = s.charAt(i);
            if (ch == ',' || ch == '}' || ch == ']' || ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                return i;
            }
            i++;
        }
        return len;
    }

    private static String classifyGapBucket(KnowledgeGraph graph, KnowledgeGraph.Node gapNode) {
        for (var ref : gapNode.evidence()) {
            var ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"evidence.gap".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                String code = String.valueOf(obj.getOrDefault("reasonCode", "")).toUpperCase(Locale.ROOT);
                if (code.startsWith("MISSING_") || code.startsWith("ARTIFACT_") || code.startsWith("GRADLE_")
                        || code.startsWith("NATIVE_") || code.startsWith("PROTOBUF_GEN") || code.startsWith("GENERATED_")) {
                    return "environment";
                }
                if (code.startsWith("AUTHORITY") || code.startsWith("POLICY") || code.startsWith("DISCLOSURE")
                        || code.startsWith("REMOTE_") || code.startsWith("AUTH")) {
                    return "authority";
                }
                if (code.startsWith("SYNTAX_") || code.startsWith("DYNAMIC_") || code.startsWith("OPAQUE_")
                        || code.startsWith("UNSUPPORTED_") || code.startsWith("HTTP_") || code.startsWith("GRPC_")) {
                    return "source";
                }
            } catch (RuntimeException ignored) {}
        }
        return "boundary";
    }

    private static String extractPackageName(String qualifiedName, String label) {
        String source = !qualifiedName.isBlank() ? qualifiedName : label;
        int dot = source.lastIndexOf('.');
        if (dot <= 0) return "";
        String prefix = source.substring(0, dot);
        // Strip outer class prefix for nested classes (com.foo.Outer.Inner -> com.foo)
        while (!prefix.isEmpty()) {
            int last = prefix.lastIndexOf('.');
            String tail = last >= 0 ? prefix.substring(last + 1) : prefix;
            if (!tail.isEmpty() && Character.isUpperCase(tail.charAt(0))) {
                prefix = last > 0 ? prefix.substring(0, last) : "";
            } else {
                break;
            }
        }
        return prefix;
    }

    private static String contextDisplayLabel(KnowledgeGraph graph, KnowledgeGraph.Context ctx) {
        var ev = graph.evidence(ctx.evidence()).orElse(null);
        if (ev != null) {
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                if (obj.get("request") instanceof Map<?, ?> req) {
                    var r = GraphJson.object(req);
                    String mod = r.get("module") instanceof String s ? s : "";
                    String lang = r.get("language") instanceof String s ? s : "";
                    if (!mod.isEmpty() && !lang.isEmpty()) return mod + " · " + lang;
                    if (!lang.isEmpty()) return lang;
                }
                if (obj.get("modules") instanceof List<?> mods && mods.size() == 1 && mods.getFirst() instanceof Map<?, ?> m) {
                    Object p = m.get("path");
                    if (p instanceof String s && !s.isBlank()) return ".".equals(s) ? "Root module" : s;
                }
            } catch (RuntimeException ignored) {}
        }
        return ctx.identity().length() > 28 ? ctx.identity().substring(0, 28) + "..." : ctx.identity();
    }

    /**
     * WP4.2: Computes the deterministic Bansiya & Davis QMOOD snapshot (11 OO properties + 6 attributes
     * including {@code Understandability}, {@code Flexibility}, {@code Reusability}) for a graph context,
     * paired with exact Tarjan SCC cycle count from {@link StructuralTopologyAnalyzer}.
     */
    public QmoodQualityModel.QmoodSnapshot assessQmood(ArchitectureQueryService queries, String contextId) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(contextId, "contextId");
        var res = assess(queries, contextId);
        var topo = computeStructuralMetrics(queries, contextId);
        int sccCount = topo.cyclicPackageSccCount() + topo.cyclicTypeSccCount();
        double score = res.score().map(BigDecimal::doubleValue).orElse(0.0);
        String qmoodCtx = resolveStructuralContextId(queries.graph(), contextId);
        return QmoodQualityModel.fromGraphContext(queries.graph(), qmoodCtx, sccCount, score);
    }

    /**
     * WP4.2: Builds a strictly numeric {@link QmoodQualityModel.MetricsOnlyBrief} for the RefAgent-style
     * Context-Aware Planner (containing ZERO external smell-tool labels) using exact Tarjan SCC,
     * coupling, and TurboMQ modularity metrics from {@link StructuralTopologyAnalyzer}.
     */
    public QmoodQualityModel.MetricsOnlyBrief buildMetricsOnlyBrief(ArchitectureQueryService queries, String contextId) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(contextId, "contextId");
        var res = assess(queries, contextId);
        var topo = computeStructuralMetrics(queries, contextId);
        int sccCount = topo.cyclicPackageSccCount() + topo.cyclicTypeSccCount();
        int cyclicTypes = topo.typeRelativeCyclicity()
                .map(rc -> (int) Math.round(rc.doubleValue() * topo.typeCount()))
                .orElse(topo.typeCycleWitnesses().size());
        double meanCoupling = topo.typeCount() > 0
                ? (double) topo.internalTypeEdgeCount() / topo.typeCount()
                : 0.0;
        double modularity = topo.packageTurboMq().map(BigDecimal::doubleValue).orElse(0.0);
        String qmoodCtx = resolveStructuralContextId(queries.graph(), contextId);
        return QmoodQualityModel.buildMetricsOnlyBrief(queries, qmoodCtx, res, sccCount, cyclicTypes, meanCoupling, modularity);
    }

    private StructuralTopologyAnalyzer.Metrics computeStructuralMetrics(ArchitectureQueryService queries, String contextId) {
        var graph = queries.graph();
        var evIndex = new GraphEvidenceIndex(graph);
        var ctx = evIndex.contextById.get(contextId);
        Runnable checkpoint = () -> {
            var stop = queries.assessmentStopReason();
            if (stop != StopReason.NONE) throw new Interrupted(stop);
        };
        if (ctx == null) {
            return StructuralTopologyAnalyzer.analyze(
                    new StructuralTopologyAnalyzer.StructuralGraph(List.of(), List.of()), DEFAULT_MAX_WITNESSES, checkpoint);
        }
        String family = graph.evidence(ctx.evidence()).map(GraphEvidence::family).orElse("");
        StructuralTopologyAnalyzer.StructuralGraph structGraph;
        if ("polyglot.context".equals(family)) {
            structGraph = PolyglotArchitectureAnalyzer.extractPolyglotStructuralGraph(graph, contextId, checkpoint);
        } else {
            String targetCtx = "spring.context".equals(family)
                    ? resolveLinkedJavaContext(graph, ctx).orElse("")
                    : contextId;
            structGraph = targetCtx.isEmpty()
                    ? new StructuralTopologyAnalyzer.StructuralGraph(List.of(), List.of())
                    : extractJavaStructuralGraph(queries, targetCtx, evIndex, checkpoint);
        }
        return StructuralTopologyAnalyzer.analyze(structGraph, DEFAULT_MAX_WITNESSES, checkpoint);
    }

    private String resolveStructuralContextId(KnowledgeGraph graph, String contextId) {
        for (var ctx : graph.contexts()) {
            if (!ctx.identity().equals(contextId)) continue;
            String family = graph.evidence(ctx.evidence()).map(GraphEvidence::family).orElse("");
            if ("spring.context".equals(family)) {
                return resolveLinkedJavaContext(graph, ctx).orElse(contextId);
            }
            return contextId;
        }
        return contextId;
    }
}
