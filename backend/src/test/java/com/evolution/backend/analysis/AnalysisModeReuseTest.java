package com.evolution.backend.analysis;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.filesystem.FileGraphStore;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.PublishedReports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisModeReuseTest {

    @Test
    void fastArchitectureAndFullModesMaintainIsolatedReuseKeys(@TempDir Path temp) throws Exception {
        Path graphsDir = Files.createDirectory(temp.resolve("graphs"));
        Path jobsDir = Files.createDirectory(temp.resolve("jobs"));
        var graphs = new FileGraphStore(graphsDir, 16_000_000L, new GraphJson.StreamLimits(16_000_000L, 128, 16_000_000L));
        var reports = new PublishedReports();
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/mode-reuse.git");

        var snapFast = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap-fast"));
        var evFast = GraphEvidence.capture("fixture.context", Map.of("mode", "FAST_ARCHITECTURE"));
        var fastGraph = new KnowledgeGraph(snapFast,
                List.of(new KnowledgeGraph.Context(snapFast.value(), snapFast, Optional.empty(), evFast.identity())),
                List.of(), List.of(), List.of(evFast));

        var snapFull = SnapshotIdentity.from(repoId, ContentDigest.sha256Utf8("snap-full"));
        var evFull = GraphEvidence.capture("fixture.context", Map.of("mode", "FULL"));
        var fullGraph = new KnowledgeGraph(snapFull,
                List.of(new KnowledgeGraph.Context(snapFull.value(), snapFull, Optional.empty(), evFull.identity())),
                List.of(), List.of(), List.of(evFull));

        var verifiedInputs = ContentDigest.sha256Utf8("same-source-tree");
        var setup = new AnalysisJobs.Setup("app", repoId, 21, false, Optional.empty(), Optional.empty());
        AnalysisJobs.Runner runner = ctrl -> {
            ctrl.stage(AnalysisJobs.Stage.ACQUISITION);
            ctrl.beginReuseCheck();
            var reused = ctrl.reuse(verifiedInputs);
            if (reused.isPresent()) return reused.orElseThrow();
            ctrl.stage(AnalysisJobs.Stage.ANALYSIS);
            var selected = ctrl.mode().isFastArchitecture() ? fastGraph : fullGraph;
            return new AnalysisJobs.Outcome(AnalysisJobs.Completion.COMPLETE, Optional.of(selected), List.of(), List.of(),
                    Optional.of(ContentDigest.sha256Utf8("acq")), Optional.of(ContentDigest.sha256Utf8("src")));
        };

        try (var disk = new AnalysisJobDiskStore(jobsDir);
             var jobs = new AnalysisJobs(List.of(new AnalysisJobs.Registration(setup, runner)),
                graphs, reports, new AnalysisJobs.Limits(1, 4, 16, 30_000, 1_000), disk)) {
            var f1 = await(jobs, jobs.submit("app", "fast-1", false, AnalysisMode.FAST_ARCHITECTURE).job().id());
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.NO_MATCH), f1.reuseDecision());

            var f2 = await(jobs, jobs.submit("app", "fast-2", false, AnalysisMode.FAST_ARCHITECTURE).job().id());
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.REUSED), f2.reuseDecision());
            assertEquals(Optional.of(fastGraph.identity()), f2.result().orElseThrow().graph());
            assertTrue(jobs.telemetry(UUID.fromString(f2.id())).reused());

            var full1 = await(jobs, jobs.submit("app", "full-1", false, AnalysisMode.FULL).job().id());
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.NO_MATCH), full1.reuseDecision());
            assertEquals(Optional.of(fullGraph.identity()), full1.result().orElseThrow().graph());

            var full2 = await(jobs, jobs.submit("app", "full-2", false, AnalysisMode.FULL).job().id());
            assertEquals(Optional.of(AnalysisJobs.ReuseDecision.REUSED), full2.reuseDecision());
            assertEquals(Optional.of(fullGraph.identity()), full2.result().orElseThrow().graph());
        }
    }

    private static AnalysisJobs.View await(AnalysisJobs jobs, String id) throws InterruptedException {
        var uuid = UUID.fromString(id);
        for (int i = 0; i < 400; i++) {
            var v = jobs.get(uuid);
            if (AnalysisJobs.terminal(v.state())) return v;
            Thread.sleep(20);
        }
        fail("Timed out: " + id);
        return null;
    }
}
