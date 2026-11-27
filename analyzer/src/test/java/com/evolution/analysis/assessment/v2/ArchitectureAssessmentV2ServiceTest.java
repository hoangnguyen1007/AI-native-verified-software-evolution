package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.evidence.EvidenceRequirement;
import com.evolution.analysis.evidence.EvidenceSubject;
import com.evolution.analysis.evidence.ProviderObservationReference;
import com.evolution.analysis.evidence.AffectedOutput;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArchitectureAssessmentV2ServiceTest {

    @Test void sizeInvarianceScoresCleanLayeredReposFairlyFrom8To320Types() {
        var scores = new ArrayList<BigDecimal>();
        for (int slices : List.of(2, 20, 80)) { // 8, 80, 320 types across bounded clean layered packages
            var fx = cleanLayeredFixture(slices);
            var res = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(fx.graph()), fx.context);
            assertEquals(AssessmentStatus.COMPLETE, res.status(), res.reasons().toString());
            assertEquals(ArchitectureAssessmentV2.ConfidenceTier.HIGH, res.confidence().tier());
            assertEquals(0, BigDecimal.ZERO.compareTo(res.uncertaintyDelta()));
            assertTrue(res.score().orElseThrow().compareTo(new BigDecimal("90")) >= 0,
                    "Clean " + (slices * 4) + "-type repo scored unexpectedly low: " + res.score());
            scores.add(res.score().orElseThrow());
        }
        BigDecimal max = scores.stream().max(BigDecimal::compareTo).orElseThrow();
        BigDecimal min = scores.stream().min(BigDecimal::compareTo).orElseThrow();
        assertTrue(max.subtract(min).compareTo(new BigDecimal("8")) < 0,
                "Score spread across 8/80/320 isomorphic clean repos should be < 8, was " + scores);

        // Verify that dumping 80 classes into single oversized packages triggers D5 fat-package penalty
        var fatPkgFx = new GraphBuilder();
        for (int i = 0; i < 80; i++) {
            fatPkgFx.type("com.example.web", "Controller" + i, "CLASS", false, 6);
            fatPkgFx.type("com.example.application", "Service" + i, "CLASS", false, 8);
            fatPkgFx.edge("Controller" + i, "Service" + i);
        }
        var fatRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(fatPkgFx.graph()), fatPkgFx.context);
        var d5 = fatRes.dimension(ArchitectureScoreModelV2.DIM_HOTSPOTS).orElseThrow();
        assertTrue(d5.score().orElseThrow().compareTo(new BigDecimal("80")) < 0,
                "Oversized 80-type packages must trigger D5 fat-package penalty, got " + d5.score());
    }

    @Test void bigBallOfMudScoresDrasticallyBelowCleanArchitecture() {
        var clean = cleanLayeredFixture(15); // 60 types
        var cleanRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(clean.graph()), clean.context);

        var mud = bigBallOfMudFixture(48); // 48 tangled types across 6 cyclic packages + fat types + upward leaks
        var mudRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(mud.graph()), mud.context);

        assertTrue(cleanRes.score().orElseThrow().compareTo(new BigDecimal("90")) >= 0, "Clean score=" + cleanRes.score());
        assertTrue(mudRes.score().orElseThrow().compareTo(new BigDecimal("35")) <= 0, "Mud score=" + mudRes.score());
        assertTrue(mudRes.dimension(ArchitectureScoreModelV2.DIM_ACYCLIC).orElseThrow().score().orElseThrow()
                .compareTo(new BigDecimal("35")) < 0);
        assertTrue(mudRes.dimension(ArchitectureScoreModelV2.DIM_MODULARITY).orElseThrow().score().orElseThrow()
                .compareTo(new BigDecimal("35")) < 0);
        assertTrue(mudRes.dimension(ArchitectureScoreModelV2.DIM_COUPLING).orElseThrow().score().orElseThrow()
                .compareTo(new BigDecimal("40")) < 0);
    }

    @Test void architecturalRegressionsMonotonicallyDecreaseScores() {
        var baseline = cleanLayeredFixture(10); // 40 types
        var svc = new ArchitectureAssessmentV2Service();
        var baseRes = svc.assess(new ArchitectureQueryService(baseline.graph()), baseline.context);
        BigDecimal baseScore = baseRes.score().orElseThrow();

        // Mutation 1: Inject package cycle (domain -> web, plus service -> web)
        var cyclic = cleanLayeredFixture(10);
        for (int i = 0; i < 4; i++) {
            cyclic.edge("Entity" + i, "Controller" + i);
        }
        var cyclicRes = svc.assess(new ArchitectureQueryService(cyclic.graph()), cyclic.context);
        assertTrue(cyclicRes.score().orElseThrow().compareTo(baseScore) < 0,
                "Adding package cycle + upward leaks must lower score");
        assertTrue(cyclicRes.dimension(ArchitectureScoreModelV2.DIM_ACYCLIC).orElseThrow().score().orElseThrow()
                .compareTo(baseRes.dimension(ArchitectureScoreModelV2.DIM_ACYCLIC).orElseThrow().score().orElseThrow()) < 0);
        assertTrue(cyclicRes.dimension(ArchitectureScoreModelV2.DIM_HIERARCHY).orElseThrow().score().orElseThrow()
                .compareTo(baseRes.dimension(ArchitectureScoreModelV2.DIM_HIERARCHY).orElseThrow().score().orElseThrow()) < 0);

        // Mutation 2: Add Web -> Repository layer skips
        var skip = cleanLayeredFixture(10);
        for (int i = 0; i < 6; i++) {
            skip.edge("Controller" + i, "Repo" + i);
        }
        var skipRes = svc.assess(new ArchitectureQueryService(skip.graph()), skip.context);
        assertTrue(skipRes.score().orElseThrow().compareTo(baseScore) < 0,
                "Adding Web->Repository shortcuts must lower score");
    }

    @Test void missingPolicyIsNotApplicableAndCustomBoundaryPolicyPreventsDoubleCounting() {
        var fx = cleanLayeredFixture(6);
        fx.edge("Controller0", "Repo0");
        fx.proveEdges();
        var graph = fx.graph();
        var queries = new ArchitectureQueryService(graph);
        var svc = new ArchitectureAssessmentV2Service();

        // Without custom policy: D7 = NOT_APPLICABLE (never 100!), D4 inferred-layering active
        var noPolicy = svc.assess(queries, fx.context);
        var d7NoPol = noPolicy.dimension(ArchitectureScoreModelV2.DIM_POLICY).orElseThrow();
        assertEquals(AssessmentStatus.NOT_APPLICABLE, d7NoPol.status());
        assertTrue(d7NoPol.score().isEmpty());
        var d4NoPol = noPolicy.dimension(ArchitectureScoreModelV2.DIM_HIERARCHY).orElseThrow();
        var inferredSubNoPol = d4NoPol.subMetrics().stream()
                .filter(s -> s.id().equals(ArchitectureScoreModelV2.SUB_D4_INFERRED_LAYERING)).findFirst().orElseThrow();
        assertEquals(AssessmentStatus.COMPLETE, inferredSubNoPol.status());

        // With explicit custom boundary policy: D7 active with violation penalty, D4 inferred-layering superseded!
        String yaml = """
                schema: architecture-policy:v1
                id: custom.layering
                version: "1"
                rules:
                  - id: web.no-repo
                    version: "1"
                    kind: FORBIDDEN_DEPENDENCY
                    severity: ERROR
                    scope: TYPE
                    relationships: [graph.depends-on]
                    from: {packages: [com.example.web.**]}
                    to: {packages: [com.example.repository.**]}
                """;
        var compiled = new PolicyCompiler().compile(yaml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(fx.snapshot), PolicyCompiler.Limits.defaults());
        var plan = new PolicyGraphMapper().bind(compiled, queries, fx.context, PolicyGraphMapper.Limits.defaults());
        var eval = new PolicyEvaluator().evaluate(compiled, plan, queries, PolicyEvaluation.Limits.defaults());

        var withPolicy = svc.assess(queries, fx.context, Optional.of(eval), Optional.empty(), ArchitectureScoreModelV2.standard());
        var d7WithPol = withPolicy.dimension(ArchitectureScoreModelV2.DIM_POLICY).orElseThrow();
        assertEquals(AssessmentStatus.COMPLETE, d7WithPol.status());
        assertTrue(d7WithPol.score().orElseThrow().compareTo(new BigDecimal("100")) < 0);
        var d4WithPol = withPolicy.dimension(ArchitectureScoreModelV2.DIM_HIERARCHY).orElseThrow();
        var inferredSubWithPol = d4WithPol.subMetrics().stream()
                .filter(s -> s.id().equals(ArchitectureScoreModelV2.SUB_D4_INFERRED_LAYERING)).findFirst().orElseThrow();
        assertEquals(AssessmentStatus.NOT_APPLICABLE, inferredSubWithPol.status(),
                "Inferred layering must yield to explicit user boundary policy to avoid double-counting");
    }

    @Test void evidenceLossNeverInflatesHealthAndTriggersDownwardBoundsThenWithheld() {
        var svc = new ArchitectureAssessmentV2Service();

        // 100% resolved -> COMPLETE, HIGH, delta = 0
        var full = cleanLayeredFixture(6);
        var fullRes = svc.assess(new ArchitectureQueryService(full.graph()), full.context);
        assertEquals(AssessmentStatus.COMPLETE, fullRes.status());
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.HIGH, fullRes.confidence().tier());
        assertEquals(0, BigDecimal.ZERO.compareTo(fullRes.uncertaintyDelta()));

        // ~75% internal resolution (add unresolved edges + gap) -> PARTIAL, MODERATE, H_lower < H_obs
        var partial = cleanLayeredFixture(6);
        partial.addUnresolvedEdges(6); // 18 resolved, 6 unresolved => 75%
        partial.addGap("SEMANTIC_UNRESOLVED");
        var partialRes = svc.assess(new ArchitectureQueryService(partial.graph()), partial.context);
        assertEquals(AssessmentStatus.PARTIAL, partialRes.status());
        assertTrue(partialRes.uncertaintyDelta().signum() > 0, "Partial coverage must widen downward uncertainty");
        assertTrue(partialRes.conservativeLowerBound().orElseThrow().compareTo(partialRes.score().orElseThrow()) < 0);
        assertTrue(partialRes.conservativeLowerBound().orElseThrow().compareTo(fullRes.conservativeLowerBound().orElseThrow()) < 0);

        // ~40% internal resolution (< 50% hard floor) -> WITHHELD, INSUFFICIENT, score empty
        var broken = cleanLayeredFixture(6);
        broken.addUnresolvedEdges(30); // 18 resolved, 30 unresolved => 37.5%
        broken.addGap("MISSING_POM");
        var brokenRes = svc.assess(new ArchitectureQueryService(broken.graph()), broken.context);
        assertEquals(AssessmentStatus.WITHHELD, brokenRes.status());
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, brokenRes.confidence().tier());
        assertTrue(brokenRes.score().isEmpty());
        assertTrue(brokenRes.conservativeLowerBound().isEmpty());

        // Operational cancellation guard strictly returns WITHHELD
        var cancelledQueries = new ArchitectureQueryService(full.graph(), () -> ArchitectureQuery.StopReason.CANCELLED, Optional.empty());
        var cancelledRes = svc.assess(cancelledQueries, full.context);
        assertEquals(AssessmentStatus.WITHHELD, cancelledRes.status());
        assertTrue(cancelledRes.score().isEmpty());
        assertTrue(cancelledRes.reasons().contains("ASSESSMENT_CANCELLED"));
    }

    @Test void largeFrontendResultIndexExceedingDefaultReadLimitsEnforcesParseCompletenessWithholdingFloor() {
        var fx = cleanLayeredFixture(6);
        var analysisId = AnalysisIdentity.fromCanonicalManifestInputs("large-module-parse-floor");
        String ctxId = analysisId.value();

        // Build >55,000 JSON values (>3.5 MB) in frontend.result-index (exceeding READ_LIMITS 2MB / 50k values)
        // with only 5/10 sources PROCESSED (parseCompleteness = 0.50 < 0.60 floor).
        var hugeOccurrences = new ArrayList<String>(55_000);
        String dummyDigest = ContentDigest.sha256Utf8("occ").value();
        for (int i = 0; i < 55_000; i++) {
            hugeOccurrences.add(dummyDigest);
        }
        var sources = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 10; i++) {
            sources.add(Map.of(
                    "document", "src/File" + i + ".java",
                    "state", i < 5 ? "PROCESSED" : "FAILED",
                    "evidence", dummyDigest));
        }
        var indexPayload = new TreeMap<String, Object>();
        indexPayload.put("analysis", ctxId);
        indexPayload.put("occurrences", hugeOccurrences);
        indexPayload.put("sources", sources);
        var indexEv = GraphEvidence.capture("frontend.result-index", indexPayload);
        assertTrue(indexEv.canonicalJson().length() > 2_000_000, "Fixture must exceed 2MB READ_LIMITS");

        var remappedNodes = fx.nodes.stream()
                .map(n -> new KnowledgeGraph.Node(n.identity(), ctxId, n.kind(), n.evidence())).toList();
        var remappedEdges = fx.edges.stream()
                .map(e -> new KnowledgeGraph.Edge(e.identity(), ctxId, e.kind(), e.source(), e.target(), e.evidence(), e.region())).toList();
        var allEv = new ArrayList<>(fx.evidence);
        allEv.add(indexEv);
        var graph = new KnowledgeGraph(
                fx.snapshot,
                List.of(
                        new KnowledgeGraph.Context(fx.snapshot.value(), fx.snapshot, Optional.empty(), fx.snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, fx.snapshot, Optional.of(analysisId), fx.manifestEv.identity())),
                remappedNodes, remappedEdges, allEv);

        var res = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(graph), ctxId);
        assertEquals(0, new BigDecimal("0.5").compareTo(res.confidence().parseCompleteness()),
                "Large frontend.result-index must accurately compute 5/10 = 0.50 parseCompleteness, got " + res.confidence().parseCompleteness());
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, res.confidence().tier());
        assertEquals(AssessmentStatus.WITHHELD, res.status());
        assertTrue(res.score().isEmpty(), "Failed parse floor (<0.60) must withhold numeric score");

        // Also verify unreadable/corrupt frontend.result-index for analysisId NEVER defaults parseCompleteness to 1.0
        var corruptIndexEv = GraphEvidence.capture("frontend.result-index", Map.of("analysis", ctxId, "sources", "NOT_AN_ARRAY"));
        var corruptEv = new ArrayList<>(fx.evidence);
        corruptEv.add(corruptIndexEv);
        var corruptGraph = new KnowledgeGraph(
                fx.snapshot,
                List.of(
                        new KnowledgeGraph.Context(fx.snapshot.value(), fx.snapshot, Optional.empty(), fx.snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, fx.snapshot, Optional.of(analysisId), fx.manifestEv.identity())),
                remappedNodes, remappedEdges, corruptEv);
        var corruptRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(corruptGraph), ctxId);
        assertEquals(0, BigDecimal.ZERO.compareTo(corruptRes.confidence().parseCompleteness()));
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, corruptRes.confidence().tier());
        assertEquals(AssessmentStatus.WITHHELD, corruptRes.status());
        assertTrue(corruptRes.score().isEmpty());
    }

    @Test void partialSourcesCountAsParsedWhileErrorAndRejectedTripWithholdingFloor() {
        var fx = cleanLayeredFixture(6);
        var analysisId = AnalysisIdentity.fromCanonicalManifestInputs("partial-source-outcome-analysis");
        String ctxId = analysisId.value();

        // 1. Cleanly parsed files with unresolved third-party imports emit SourceOutcome.State.PARTIAL.
        // 2 PROCESSED + 10 PARTIAL => parseCompleteness = 12/12 = 1.0, must NOT be falsely WITHHELD when
        // internalEdgeCompleteness >= 0.50.
        var partialSources = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 12; i++) {
            partialSources.add(Map.of(
                    "path", "src/main/java/com/acme/Svc" + i + ".java",
                    "state", i < 2 ? "PROCESSED" : "PARTIAL"));
        }
        var partialIndexEv = GraphEvidence.capture("frontend.result-index",
                Map.of("analysis", ctxId, "sources", partialSources));
        var remappedNodes = fx.nodes.stream()
                .map(n -> new KnowledgeGraph.Node(n.identity(), ctxId, n.kind(), n.evidence())).toList();
        var remappedEdges = fx.edges.stream()
                .map(e -> new KnowledgeGraph.Edge(e.identity(), ctxId, e.kind(), e.source(), e.target(), e.evidence(), e.region())).toList();
        var evWithPartial = new ArrayList<>(fx.evidence);
        evWithPartial.add(partialIndexEv);
        var partialGraph = new KnowledgeGraph(
                fx.snapshot,
                List.of(
                        new KnowledgeGraph.Context(fx.snapshot.value(), fx.snapshot, Optional.empty(), fx.snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, fx.snapshot, Optional.of(analysisId), fx.manifestEv.identity())),
                remappedNodes, remappedEdges, evWithPartial);

        var partialRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(partialGraph), ctxId);
        assertEquals(0, BigDecimal.ONE.compareTo(partialRes.confidence().parseCompleteness()),
                "PROCESSED + PARTIAL sources must yield parseCompleteness = 1.0, got " + partialRes.confidence().parseCompleteness());
        assertNotEquals(AssessmentStatus.WITHHELD, partialRes.status(),
                "Cleanly parsed PARTIAL sources with healthy resolution must not be falsely WITHHELD");
        assertTrue(partialRes.score().isPresent(), "Numeric Architecture Health score must be present");

        // 2. Unresolved references inside PARTIAL files remain strictly floor-gated by internalEdgeCompleteness < 0.50.
        var lowResEdges = new ArrayList<>(remappedEdges);
        String srcId = fx.types.get("Controller0");
        String dstId = fx.types.get("Service0");
        for (int i = 0; i < 20; i++) {
            lowResEdges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("partial-ok", List.of(i)), ctxId, "java.calls", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dstId), Optional.empty()),
                    List.of(fx.manifestEv.identity()), Optional.empty()));
        }
        for (int i = 0; i < 30; i++) {
            lowResEdges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("partial-unres", List.of(i)), ctxId, "java.calls", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.UNRESOLVED, List.of(), Optional.of("ext.Lib#m" + i)),
                    List.of(fx.manifestEv.identity()), Optional.empty()));
        }
        var lowResGraph = new KnowledgeGraph(
                fx.snapshot,
                List.of(
                        new KnowledgeGraph.Context(fx.snapshot.value(), fx.snapshot, Optional.empty(), fx.snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, fx.snapshot, Optional.of(analysisId), fx.manifestEv.identity())),
                remappedNodes, lowResEdges, evWithPartial);
        var lowRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(lowResGraph), ctxId);
        assertEquals(0, BigDecimal.ONE.compareTo(lowRes.confidence().parseCompleteness()));
        assertEquals(0, new BigDecimal("0.4").compareTo(lowRes.confidence().internalEdgeCompleteness()));
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, lowRes.confidence().tier());
        assertEquals(AssessmentStatus.WITHHELD, lowRes.status());
        assertTrue(lowRes.score().isEmpty());

        // 3. Actual parse/skip failures (ERROR, REJECTED, NOT_PROCESSED, UNSUPPORTED) still trip < 0.60 WITHHELD floor.
        var failingSources = List.of(
                Map.of("path", "src/A0.java", "state", "PROCESSED"),
                Map.of("path", "src/A1.java", "state", "PROCESSED"),
                Map.of("path", "src/A2.java", "state", "PARTIAL"),
                Map.of("path", "src/A3.java", "state", "PARTIAL"),
                Map.of("path", "src/A4.java", "state", "PARTIAL"),
                Map.of("path", "src/A5.java", "state", "ERROR"),
                Map.of("path", "src/A6.java", "state", "ERROR"),
                Map.of("path", "src/A7.java", "state", "REJECTED"),
                Map.of("path", "src/A8.java", "state", "NOT_PROCESSED"),
                Map.of("path", "src/A9.java", "state", "UNSUPPORTED"));
        var failingIndexEv = GraphEvidence.capture("frontend.result-index",
                Map.of("analysis", ctxId, "sources", failingSources));
        var evWithFailing = new ArrayList<>(fx.evidence);
        evWithFailing.add(failingIndexEv);
        var failingGraph = new KnowledgeGraph(
                fx.snapshot,
                List.of(
                        new KnowledgeGraph.Context(fx.snapshot.value(), fx.snapshot, Optional.empty(), fx.snapEv.identity()),
                        new KnowledgeGraph.Context(ctxId, fx.snapshot, Optional.of(analysisId), fx.manifestEv.identity())),
                remappedNodes, remappedEdges, evWithFailing);
        var failingRes = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(failingGraph), ctxId);
        assertEquals(0, new BigDecimal("0.5").compareTo(failingRes.confidence().parseCompleteness()),
                "2 PROCESSED + 3 PARTIAL out of 10 sources must yield 0.50 parseCompleteness, got " + failingRes.confidence().parseCompleteness());
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, failingRes.confidence().tier());
        assertEquals(AssessmentStatus.WITHHELD, failingRes.status());
        assertTrue(failingRes.score().isEmpty());
    }

    @Test void javaDeclaresDoubleCountedDependsOnAndCandidatesNeverInflateInternalEdgeCompleteness() {
        var fx = cleanLayeredFixture(6);
        String srcId = fx.types.get("Controller0");
        String dstId = fx.types.get("Service0");
        var evId = fx.manifestEv.identity();

        // Add realistic CanonicalGraphBuilder noise: 200 RESOLVED java.declares + 50 RESOLVED java.has-parameter
        for (int i = 0; i < 200; i++) {
            fx.edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("jdecl", List.of(i)), fx.context, "java.declares", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dstId), Optional.empty()),
                    List.of(evId), Optional.empty()));
        }
        for (int i = 0; i < 50; i++) {
            fx.edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("jparam", List.of(i)), fx.context, "java.has-parameter", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dstId), Optional.empty()),
                    List.of(evId), Optional.empty()));
        }
        // Add 40 RESOLVED java.calls + 40 derived RESOLVED graph.depends-on (must NOT double-count!)
        // + 50 UNRESOLVED java.calls + 10 CANDIDATES java.calls => true semantic resolution = 40 / 100 = 0.40 (< 0.50 floor!)
        for (int i = 0; i < 40; i++) {
            fx.edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("jcall-ok", List.of(i)), fx.context, "java.calls", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dstId), Optional.empty()),
                    List.of(evId), Optional.empty()));
            fx.edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("gdep-dup", List.of(i)), fx.context, "graph.depends-on", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dstId), Optional.empty()),
                    List.of(evId), Optional.empty()));
        }
        for (int i = 0; i < 50; i++) {
            fx.edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("jcall-unres", List.of(i)), fx.context, "java.calls", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.UNRESOLVED, List.of(), Optional.of("missing.A#m" + i)),
                    List.of(evId), Optional.empty()));
        }
        for (int i = 0; i < 10; i++) {
            fx.edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("jcall-cand", List.of(i)), fx.context, "java.calls", srcId,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.CANDIDATES, List.of(dstId, fx.types.get("Entity0")), Optional.empty()),
                    List.of(evId), Optional.empty()));
        }

        var res = new ArchitectureAssessmentV2Service().assess(new ArchitectureQueryService(fx.graph()), fx.context);
        assertEquals(0, new BigDecimal("0.4").compareTo(res.confidence().internalEdgeCompleteness()),
                "Expected exact 40/100 = 0.40 internalEdgeCompleteness without java.declares or graph.depends-on inflation, got "
                        + res.confidence().internalEdgeCompleteness());
        assertEquals(ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, res.confidence().tier());
        assertEquals(AssessmentStatus.WITHHELD, res.status());
        assertTrue(res.score().isEmpty(), "40% resolved semantic edges (< 50% floor) must be WITHHELD");
    }

    @Test void determinismAcrossLocaleTimezoneTelemetryAndGraphRoundTrip() {
        var fx = cleanLayeredFixture(8);
        var graph = fx.graph();
        var svc = new ArchitectureAssessmentV2Service();
        var baseline = svc.assess(new ArchitectureQueryService(graph), fx.context);

        assertEquals(baseline.identity(), baseline.withComputedAt(Instant.parse("2026-01-01T00:00:00Z")).identity());
        assertFalse(CanonicalJson.write(baseline).isEmpty());

        var restored = new GraphSnapshotCodec().decode(new GraphSnapshotCodec().encode(graph), GraphJson.Limits.defaults());
        var locale = Locale.getDefault();
        var tz = TimeZone.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
            var replayed = svc.assess(new ArchitectureQueryService(restored), fx.context);
            assertEquals(baseline.identity(), replayed.identity());
            assertEquals(baseline, replayed);
        } finally {
            Locale.setDefault(locale);
            TimeZone.setDefault(tz);
        }
    }

    // ─── Fixture Builder ───

    static GraphBuilder cleanLayeredFixture(int slices) {
        var b = new GraphBuilder();
        for (int i = 0; i < slices; i++) {
            // Partition into bounded ~10-slice feature groups so large repos stay properly modularized per package
            String group = slices <= 15 ? "com.example" : "com.example.mod" + (i / 10);
            b.type(group + ".web", "Controller" + i, "CLASS", false, 6);
            b.type(group + ".application", "Service" + i, "CLASS", false, 8);
            b.type(group + ".domain", "Entity" + i, "CLASS", false, 6);
            b.type(group + ".repository", "Repo" + i, "INTERFACE", true, 4);
            b.edge("Controller" + i, "Service" + i);
            b.edge("Service" + i, "Entity" + i);
            b.edge("Service" + i, "Repo" + i);
            b.edge("Repo" + i, "Entity" + i);
        }
        return b;
    }

    static GraphBuilder bigBallOfMudFixture(int count) {
        var b = new GraphBuilder();
        String[] pkgs = {
                "com.mud.web", "com.mud.application", "com.mud.domain",
                "com.mud.repository", "com.mud.adapter", "com.mud.port"
        };
        for (int i = 0; i < count; i++) {
            // Make every class fat (>45 members)
            b.type(pkgs[i % pkgs.length], "God" + i, "CLASS", false, 50);
        }
        // Create giant all-layer cyclic tangle + high fan-out + upward leaks
        for (int i = 0; i < count; i++) {
            for (int hop = 1; hop <= 12; hop++) {
                b.edge("God" + i, "God" + ((i + hop) % count));
            }
        }
        return b;
    }

    static final class GraphBuilder {
        final RepositoryIdentity repository = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/v2score.git");
        final SnapshotIdentity snapshot = SnapshotIdentity.from(repository, ContentDigest.sha256Utf8("v2-snapshot"));
        final String context = KnowledgeGraph.derivedIdentity("analysis", List.of("v2-test"));
        final ModuleIdentity module = ModuleIdentity.from(repository, ".");
        final GraphEvidence manifestEv = GraphEvidence.capture("analysis.manifest", Map.of("modules", List.of(Map.of("identity", module, "path", "."))));
        final GraphEvidence snapEv = GraphEvidence.capture("repository.snapshot", Map.of("snapshot", snapshot));
        final List<GraphEvidence> evidence = new ArrayList<>(List.of(manifestEv, snapEv));
        final List<KnowledgeGraph.Node> nodes = new ArrayList<>();
        final List<KnowledgeGraph.Edge> edges = new ArrayList<>();
        final Map<String, String> types = new TreeMap<>();
        final Set<String> createdPkgs = new HashSet<>();
        boolean moduleCreated;

        GraphBuilder type(String pkg, String name, String kind, boolean abs, int members) {
            if (!moduleCreated) {
                var modProof = GraphEvidence.capture("module.descriptor", Map.of("identity", module, "path", "."));
                evidence.add(modProof);
                nodes.add(new KnowledgeGraph.Node(module.value(), context, "MODULE", List.of(modProof.identity())));
                moduleCreated = true;
            }
            var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, name).canonicalName(), Optional.empty());
            var declEv = GraphEvidence.capture("java.declaration", Map.of("entity", entity, "spelling", name, "status", "RESOLVED"));
            var shapeEv = GraphEvidence.capture("java.type-shape", Map.of("type", entity.identity().value(), "kind", kind, "abstractType", abs));
            evidence.add(declEv);
            evidence.add(shapeEv);

            String pkgId = EntityIdentity.from(EntityOrigin.PROJECT, EntityScope.project(module), EntityKind.PACKAGE,
                    JavaSymbolName.packageName(pkg).canonicalName()).value();
            if (createdPkgs.add(pkgId)) {
                nodes.add(new KnowledgeGraph.Node(pkgId, context, "PACKAGE", List.of(declEv.identity())));
                link("graph.contains", module.value(), pkgId, declEv.identity());
            }
            nodes.add(new KnowledgeGraph.Node(entity.identity().value(), context, "TYPE", List.of(declEv.identity(), shapeEv.identity())));
            link("graph.contains", pkgId, entity.identity().value(), declEv.identity());
            types.put(name, entity.identity().value());

            for (int m = 0; m < members; m++) {
                String memId = KnowledgeGraph.derivedIdentity("member", List.of(entity.identity().value(), m));
                nodes.add(new KnowledgeGraph.Node(memId, context, "METHOD", List.of(declEv.identity())));
                link("graph.declares", entity.identity().value(), memId, declEv.identity());
            }
            return this;
        }

        GraphBuilder edge(String from, String to) {
            link("graph.depends-on", types.get(from), types.get(to), manifestEv.identity());
            return this;
        }

        void addUnresolvedEdges(int count) {
            String firstType = types.values().iterator().next();
            for (int i = 0; i < count; i++) {
                edges.add(new KnowledgeGraph.Edge(
                        KnowledgeGraph.derivedIdentity("unresolved-edge", List.of(edges.size(), i)),
                        context, "java.calls", firstType,
                        new KnowledgeGraph.Target(KnowledgeGraph.TargetState.UNRESOLVED, List.of(), Optional.of("missing.Lib#m" + i)),
                        List.of(manifestEv.identity()), Optional.empty()));
            }
        }

        void addGap(String reasonCode) {
            var evCtx = EvidenceContext.forSnapshot(snapshot);
            var prov = new com.evolution.analysis.contract.common.VersionedIdentifier("test.provider", "1");
            var obs = ProviderObservationReference.create(prov, "test.obs", manifestEv.identity(), ContentDigest.sha256Utf8(reasonCode + nodes.size()));
            var subj = EvidenceSubject.observation(obs.identity());
            var req = new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT, "test.dependency", List.of(subj),
                    EvidenceRequirement.AuthorizationClass.PASSIVE, List.of("Supply jar"));
            var gap = CapabilityGapRecord.create(evCtx, prov, "test.cat", reasonCode, subj, List.of(),
                    List.of(obs), List.of(req), List.of(), List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION, "graph.canonical")),
                    List.of(), List.of(), List.of("limitation"));
            var gapEv = GraphEvidence.capture("evidence.gap", gap);
            evidence.add(gapEv);
            nodes.add(new KnowledgeGraph.Node(gap.gapIdentity().value(), context, "CAPABILITY_GAP", List.of(gapEv.identity())));
        }

        void proveEdges() {
            for (int i = 0; i < edges.size(); i++) {
                var e = edges.get(i);
                if (!"graph.depends-on".equals(e.kind())) continue;
                var doc = SourceDocumentIdentity.from(repository, "src/Edge" + i + ".java");
                var digest = ContentDigest.sha256Utf8("class Edge" + i + " {}");
                var docEv = GraphEvidence.capture("source.document", Map.of("identity", doc, "contentDigest", digest,
                        "module", module, "classification", "MAIN"));
                var occEv = GraphEvidence.capture("java.occurrence", Map.of("status", "RESOLVED", "derivation", "DIRECT",
                        "span", new SourceSpan(doc, 1, 1, 1, 10)));
                evidence.add(docEv);
                evidence.add(occEv);
                nodes.add(new KnowledgeGraph.Node(doc.value(), context, "SOURCE_DOCUMENT", List.of(docEv.identity())));
                edges.set(i, new KnowledgeGraph.Edge(e.identity(), e.context(), e.kind(), e.source(), e.target(),
                        List.of(occEv.identity()), e.region()));
            }
        }

        private void link(String kind, String src, String dst, ContentDigest ev) {
            edges.add(new KnowledgeGraph.Edge(
                    KnowledgeGraph.derivedIdentity("edge", List.of(kind, src, dst, edges.size())),
                    context, kind, src,
                    new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(dst), Optional.empty()),
                    List.of(ev), Optional.empty()));
        }

        KnowledgeGraph graph() {
            var contexts = List.of(
                    new KnowledgeGraph.Context(snapshot.value(), snapshot, Optional.empty(), snapEv.identity()),
                    new KnowledgeGraph.Context(context, snapshot, Optional.empty(), manifestEv.identity()));
            return new KnowledgeGraph(snapshot, contexts, nodes, edges, evidence);
        }
    }
}
