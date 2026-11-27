package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.ArchitectureMetricsService;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.query.repair.RepairVerificationJobs;
import com.evolution.backend.query.repair.RepairVerificationReport;
import com.evolution.backend.query.repair.SnapshotTreeMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

final class RepairVerificationReportTest {
    @TempDir Path temp;

    @Test
    void buildsBeforeVsAfterScoreStructuralSandboxAndMarkdownReport() {
        // Baseline graph has a 2-node cycle A <-> B on canonical graph.depends-on edges; candidate breaks B -> A!
        var baseFixture = new QueryFixture().node("ServiceA").node("ServiceB");
        addDependsEdge(baseFixture, "ServiceA", "ServiceB");
        addDependsEdge(baseFixture, "ServiceB", "ServiceA");
        var candFixture = new QueryFixture().node("ServiceA").node("ServiceB");
        addDependsEdge(candFixture, "ServiceA", "ServiceB");

        var baseGraph = baseFixture.graph();
        var candGraph = candFixture.graph();
        var baseQueries = new ArchitectureMetricsService(baseGraph).queries();
        var candQueries = new ArchitectureMetricsService(candGraph).queries();

        var beforeDigest = ContentDigest.sha256Utf8("class ServiceB { ServiceA a; }");
        var afterDigest = ContentDigest.sha256Utf8("class ServiceB {}");
        String patch = RepairPatch.file("src/ServiceB.java", "class ServiceB { ServiceA a; }\n", "class ServiceB {}\n");
        var pin = new FindingDiagnosis.Pin(baseGraph.snapshot(), Optional.empty(), baseGraph.identity(),
                baseFixture.context, beforeDigest, beforeDigest, beforeDigest);
        var proof = new FindingDiagnosis.Proof(new VersionedIdentifier("no-cycles", "1"),
                ArchitectureQuery.Scope.TYPE, "ServiceB", "ServiceA", "ServiceB -> ServiceA",
                List.of("graph.depends-on"), ArchitectureQuery.Classification.MUST, beforeDigest);
        var candidate = new CapturedRepair.Candidate(pin, beforeDigest, proof, List.of(),
                CapturedRepair.Strategy.AI, Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(new CapturedRepair.Edit("src/ServiceB.java", beforeDigest, afterDigest, beforeDigest, List.of())),
                List.of(), ContentDigest.sha256Utf8(patch), patch, "UNVERIFIED");
        var proposal = new CapturedRepair.Proposal(CanonicalJson.sha256(List.of(CapturedRepair.VERSION, candidate)), candidate);

        var plan = new CapturedRepairVerification.Plan(
                proposal.identity(), candidate.patchDigest(), beforeDigest, beforeDigest,
                new CapturedRepairVerification.TreeSummary(baseGraph.snapshot(), 2),
                new CapturedRepairVerification.TreeSummary(candGraph.snapshot(), 2),
                new CapturedRepairVerification.TreeSummary(baseGraph.snapshot(), 2),
                List.of(new CapturedRepairVerification.EditPin("src/ServiceB.java", beforeDigest, afterDigest)),
                List.of("app"), List.of("baseline-and-patch", "candidate-compile", "candidate-test"));
        var isolated = new CapturedRepairVerification.IsolatedProof(
                beforeDigest, beforeDigest, beforeDigest, "maven", List.of("app"),
                0, 0, 12, 0, 0, beforeDigest, 8, List.of("candidate.compile", "candidate.test"),
                "QEMU_IN_DOCKER", List.of(), "");
        var verif = new CapturedRepairVerification.Result(
                CapturedRepairVerification.VERSION, CapturedRepairVerification.Verdict.VERIFIED,
                proposal.identity(), plan.identity(), plan,
                List.of(new CapturedRepairVerification.Check("isolated-build-and-test",
                        CapturedRepairVerification.Status.PASSED, "ISOLATED_VM_BUILD_AND_TESTS_PASSED", Optional.of(beforeDigest))),
                Optional.empty(), Optional.empty(), Optional.of(isolated), patch);

        var report = RepairVerificationReport.build(proposal, verif, baseGraph, Optional.of(candGraph),
                Optional.empty(), Optional.empty(), baseQueries, Optional.of(candQueries));

        assertEquals(RepairVerificationReport.VERSION, report.version());
        assertEquals(CapturedRepairVerification.Verdict.VERIFIED, report.verdict());
        assertEquals(RepairVerificationReport.TrustLabel.ISOLATED_VM_VERIFIED, report.trustLabel());
        assertTrue(report.structuralDelta().isPresent());
        assertEquals(1, report.structuralDelta().orElseThrow().beforeTangleCount());
        assertEquals(0, report.structuralDelta().orElseThrow().afterTangleCount());
        assertTrue(report.sandbox().executed());
        assertEquals(12, report.sandbox().testsRun());
        assertTrue(report.markdownReport().contains("# Verified Repair Impact Report"));
        assertTrue(report.markdownReport().contains("ISOLATED_VM_VERIFIED"));
        assertTrue(report.markdownReport().contains("## 1. Architecture Health Score v2"));
        assertTrue(report.markdownReport().contains("## 6. Verified Unified Diff"));
    }

    @Test
    void materializerReconstructsSnapshotFromStreamingContentStoreWithoutLocalCheckout() throws Exception {
        Path casDir = temp.resolve("cas");
        var cas = new StreamingContentStore(casDir);
        byte[] pomBytes = "<project><artifactId>demo</artifactId></project>\n".getBytes(StandardCharsets.UTF_8);
        byte[] javaBytes = "package demo;\npublic class Main {}\n".getBytes(StandardCharsets.UTF_8);
        var pomHandle = cas.put(new ByteArrayInputStream(pomBytes), () -> false);
        var javaHandle = cas.put(new ByteArrayInputStream(javaBytes), () -> false);

        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://github.com/example/remote-repo.git");
        var files = List.of(
                new SnapshotFile("pom.xml", pomHandle.digest()),
                new SnapshotFile("src/main/java/demo/Main.java", javaHandle.digest()));
        var snapshot = RepositorySnapshot.create(repoId, Optional.of("deadbeef"), false, files, List.of());

        var snapEv = GraphEvidence.capture("repository.snapshot", Map.of(
                "repository", repoId.value(),
                "revision", "deadbeef",
                "identity", snapshot.identity().value(),
                "dirty", false,
                "files", List.of(
                        Map.of("path", "pom.xml", "contentDigest", pomHandle.digest().value()),
                        Map.of("path", "src/main/java/demo/Main.java", "contentDigest", javaHandle.digest().value()))));
        var c1 = GraphEvidence.capture("source.content", Map.of("contentDigest", pomHandle.digest().value(), "size", pomHandle.size()));
        var c2 = GraphEvidence.capture("source.content", Map.of("contentDigest", javaHandle.digest().value(), "size", javaHandle.size()));
        var baseEv = GraphEvidence.capture("test.base", Map.of("ok", true));
        var graph = new KnowledgeGraph(snapshot.identity(),
                List.of(new KnowledgeGraph.Context(snapshot.identity().value(), snapshot.identity(), Optional.empty(), baseEv.identity())),
                List.of(), List.of(), List.of(baseEv, snapEv, c1, c2));

        Path outDir = temp.resolve("materialized");
        var result = SnapshotTreeMaterializer.materialize(graph, Optional.of(cas), Optional.empty(), outDir, () -> false);
        assertTrue(result.complete(), () -> "Missing: " + result.missingPaths());
        assertEquals(2, result.materializedFiles());
        assertEquals("CAS_CONTENT_STORE", result.source());
        assertArrayEquals(pomBytes, Files.readAllBytes(outDir.resolve("pom.xml")));
        assertArrayEquals(javaBytes, Files.readAllBytes(outDir.resolve("src/main/java/demo/Main.java")));
    }

    @Test
    void verificationJobsStreamsStagesAndServesMarkdownReport() throws Exception {
        try (var jobs = new RepairVerificationJobs(1)) {
            var proposalId = ContentDigest.sha256Utf8("job-prop");
            var seenStages = new CopyOnWriteArrayList<RepairVerificationJobs.Stage>();
            var submitted = jobs.submit(proposalId, (progress, cancelled) -> {
                progress.stage(RepairVerificationJobs.Stage.STAGING, "Materializing baseline tree");
                progress.stage(RepairVerificationJobs.Stage.PASSIVE_REANALYSIS, "Running passive reanalysis");
                progress.stage(RepairVerificationJobs.Stage.IMPACT_SCORING, "Computing Score v2 delta");
                var dummyReport = new RepairVerificationReport.Report(
                        RepairVerificationReport.VERSION, proposalId, CapturedRepairVerification.Verdict.UNVERIFIED,
                        RepairVerificationReport.TrustLabel.PASSIVE_REANALYSIS_ONLY, "Done",
                        new RepairVerificationReport.ScoreDelta(Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), "N/A", "N/A",
                                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "LOW", "LOW", List.of()),
                        Optional.empty(),
                        new RepairVerificationReport.ViolationDelta(List.of(), List.of(), List.of(), 0, 0),
                        new RepairVerificationReport.CapabilityGapDelta(0, 0, 0, Map.of(), Map.of()),
                        new RepairVerificationReport.SandboxEvidence(false, "NONE", "none", List.of(), -1, -1, 0, 0, 0, 0,
                                List.of(), "", Optional.empty(), Optional.empty(), Optional.empty()),
                        List.of(),
                        new RepairVerificationReport.Provenance(proposalId, proposalId, proposalId, Optional.empty(),
                                proposalId, "AI", Optional.empty(), List.of()),
                        "", "# Verified Repair Impact Report\nOK\n");
                return new RepairVerificationJobs.ExecutionOutcome(null, dummyReport);
            });
            try (var sub = jobs.subscribe(submitted.jobId(), 0, ev -> seenStages.add(ev.stage()))) {
                long deadline = System.currentTimeMillis() + 5_000L;
                while (System.currentTimeMillis() < deadline) {
                    var view = jobs.get(submitted.jobId()).orElseThrow();
                    if (view.terminal()) break;
                    Thread.sleep(20);
                }
            }
            var finalView = jobs.get(submitted.jobId()).orElseThrow();
            assertEquals(RepairVerificationJobs.Stage.REPORT_READY, finalView.stage());
            assertTrue(seenStages.contains(RepairVerificationJobs.Stage.REPORT_READY));
            assertTrue(jobs.reportMarkdown(submitted.jobId()).orElse("").contains("# Verified Repair Impact Report"));
        }
    }

    @Test
    void verificationJobsEnforcesStrictMultiUserOwnerIsolationAcrossAllOperations() throws Exception {
        try (var jobs = new RepairVerificationJobs(1)) {
            var proposalId = ContentDigest.sha256Utf8("tenant-job-prop");
            var latch = new java.util.concurrent.CountDownLatch(1);
            var aliceJob = jobs.submit("gh:42", proposalId, (progress, cancelled) -> {
                progress.stage(RepairVerificationJobs.Stage.STAGING, "Alice staging");
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                var dummyReport = new RepairVerificationReport.Report(
                        RepairVerificationReport.VERSION, proposalId, CapturedRepairVerification.Verdict.VERIFIED,
                        RepairVerificationReport.TrustLabel.ISOLATED_VM_VERIFIED, "Alice OK",
                        new RepairVerificationReport.ScoreDelta(Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), "N/A", "N/A",
                                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, "HIGH", "HIGH", List.of()),
                        Optional.empty(),
                        new RepairVerificationReport.ViolationDelta(List.of(), List.of(), List.of(), 0, 0),
                        new RepairVerificationReport.CapabilityGapDelta(0, 0, 0, Map.of(), Map.of()),
                        new RepairVerificationReport.SandboxEvidence(true, "QEMU_IN_DOCKER", "maven", List.of("."), 0, 0, 1, 0, 0, 1,
                                List.of(), "", Optional.empty(), Optional.empty(), Optional.empty()),
                        List.of(),
                        new RepairVerificationReport.Provenance(proposalId, proposalId, proposalId, Optional.empty(),
                                proposalId, "AI", Optional.empty(), List.of()),
                        "", "# Alice Private Report\n");
                return new RepairVerificationJobs.ExecutionOutcome(null, dummyReport);
            });
            assertEquals("gh:42", aliceJob.ownerUserId());

            // Bob cannot list, get, subscribe, cancel, or read markdown of Alice's job!
            assertEquals(1, jobs.list("gh:42").size());
            assertTrue(jobs.list("gh:99").isEmpty(), "Bob must not see Alice's repair jobs in list()");
            assertThrows(com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException.class,
                    () -> jobs.get("gh:99", aliceJob.jobId()));
            assertThrows(com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException.class,
                    () -> jobs.reportMarkdown("gh:99", aliceJob.jobId()));
            assertThrows(com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException.class,
                    () -> jobs.cancel("gh:99", aliceJob.jobId()));
            assertThrows(com.evolution.backend.query.repair.SafePatchApplier.AccessDeniedException.class,
                    () -> jobs.subscribe("gh:99", aliceJob.jobId(), 0, ev -> {}));

            // Denied cancel MUST NOT cancel Alice's running job!
            latch.countDown();
            var aliceSeen = new CopyOnWriteArrayList<RepairVerificationJobs.Stage>();
            try (var sub = jobs.subscribe("gh:42", aliceJob.jobId(), 0, ev -> aliceSeen.add(ev.stage()))) {
                long deadline = System.currentTimeMillis() + 5_000L;
                while (System.currentTimeMillis() < deadline) {
                    if (jobs.get("gh:42", aliceJob.jobId()).orElseThrow().terminal()) break;
                    Thread.sleep(20);
                }
            }
            var finalAlice = jobs.get("gh:42", aliceJob.jobId()).orElseThrow();
            assertEquals(RepairVerificationJobs.Stage.REPORT_READY, finalAlice.stage());
            assertTrue(aliceSeen.contains(RepairVerificationJobs.Stage.REPORT_READY));
            assertTrue(jobs.reportMarkdown("gh:42", aliceJob.jobId()).orElse("").contains("# Alice Private Report"));
        }
    }

    private static void addDependsEdge(QueryFixture fixture, String from, String to) {
        fixture.edges.add(new KnowledgeGraph.Edge(
                QueryFixture.id("dep-" + from + "-" + to),
                fixture.context,
                "graph.depends-on",
                QueryFixture.id(from),
                new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED, List.of(QueryFixture.id(to)), Optional.empty()),
                List.of(fixture.base.identity()),
                Optional.empty()));
    }
}
