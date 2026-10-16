package com.evolution.backend.query;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.backend.analysis.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LocalAnalysisRunnerTest {
    @TempDir Path directory;
    private static final String POLICY = """
            schema: architecture-policy:v1
            id: test.backend
            version: "1"
            rules:
              - {id: api.no-data, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], from: {packages: [api]}, to: {packages: [data]}}
            """;
    private LocalAnalysisRunner runner(boolean broken,boolean missingPlatform,String policy) throws Exception {
        var source = Files.createDirectory(directory.resolve("source"));
        Files.createDirectories(source.resolve("src/main/java/api")); Files.createDirectories(source.resolve("src/main/java/data"));
        Files.writeString(source.resolve("src/main/java/api/Controller.java"),"package api; public class Controller { private data.Repository repository; " + (broken ? "private missing.Library unknown;" : "") + " }\n");
        Files.writeString(source.resolve("src/main/java/data/Repository.java"),"package data; public class Repository {}\n");
        var cache = Files.createDirectory(directory.resolve("cache"));
        return new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,RepositoryIdentity.fromCanonicalCoordinate("https://example.test/backend-fixture.git"),
                missingPlatform ? directory.resolve("missing-jdk") : Path.of(System.getProperty("java.home")),21,cache,Optional.of("caller-revision"),true,false,List.of(),
                Optional.of(new LocalAnalysisRunner.PolicyInput(policy.getBytes(StandardCharsets.UTF_8),Scope.TYPE)),LocalAnalysisRunner.SpringInput.absent()));
    }
    static AnalysisJobs.View finishedReal(AnalysisJobs jobs,String id) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < end) { var view = jobs.get(UUID.fromString(id)); if (AnalysisJobs.terminal(view.state())) return view; Thread.sleep(20); }
        throw new AssertionError("Real-source analysis exceeded the bounded test deadline");
    }
    @Test void actualSourceToGraphReportsQueryAndReplayRemainCanonical() throws Exception {
        var runner = runner(false,false,POLICY); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("local")),store,reports,new AnalysisJobs.Limits(1,1,4,90_000,100))) {
            var api = new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs);
            var first = finishedReal(jobs,jobs.submit("local","first").job().id());
            assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED,AnalysisJobs.State.PARTIAL).contains(first.state()),() -> CanonicalJson.write(first));
            var result = first.result().orElseThrow(); var graph = store.load(result.graph().orElseThrow()).orElseThrow();
            assertFalse(result.analyses().isEmpty()); assertEquals(2,graph.nodes().stream().filter(n -> n.kind().equals("TYPE")).count());
            assertFalse(result.reports().isEmpty());
            for (var reportId : result.reports()) {
                var report = reports.get(reportId).orElseThrow();
                assertFalse(report.evaluation().findings().isEmpty()); assertEquals(graph.identity(),report.evaluation().graph());
                var response = AnalysisJobApiTest.call(api,"POST","/api/v1/graphs/"+graph.identity().value()+"/reports/"+reportId.value()+"/assessment","{}");
                assertEquals(200,response.status()); assertEquals(CanonicalJson.write(report.assessment().orElseThrow()),CanonicalJson.write(AnalysisJobApiTest.data(response)));
            }
            assertEquals(200,AnalysisJobApiTest.call(api,"GET","/api/v1/graphs/"+graph.identity().value(),"").status());
            var second = finishedReal(jobs,jobs.submit("local","second").job().id());
            assertEquals(first.result(),second.result()); assertEquals(first.state(),second.state());
            assertEquals(List.of(AnalysisJobs.Stage.QUEUED,AnalysisJobs.Stage.ACQUISITION,AnalysisJobs.Stage.PLATFORM,AnalysisJobs.Stage.ANALYSIS,
                    AnalysisJobs.Stage.GRAPH,AnalysisJobs.Stage.POLICY,AnalysisJobs.Stage.PUBLICATION,AnalysisJobs.Stage.FINISHED),second.events().stream().map(AnalysisJobs.Event::stage).toList());
        }
    }
    @Test void missingClasspathKeepsTypedGapsAndNeverProducesACompleteJob() throws Exception {
        var runner = runner(true,false,POLICY); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("partial")),store,reports,new AnalysisJobs.Limits(1,1,4,90_000,100))) {
            var view = finishedReal(jobs,jobs.submit("partial","once").job().id()); assertEquals(AnalysisJobs.State.PARTIAL,view.state(),() -> CanonicalJson.write(view));
            var result = view.result().orElseThrow(); assertTrue(result.gapCount() > 0); assertTrue(result.graphGapCount() > 0); assertTrue(result.jobGapCount() > 0);
            var page = jobs.gaps(UUID.fromString(view.id()),1,Optional.empty()); assertEquals(1,page.items().size()); assertTrue(page.continuation().isPresent());
            var seen = new HashSet<String>();
            do { page.items().forEach(g -> assertTrue(seen.add(g.gapIdentity().value()))); if (page.continuation().isEmpty()) break; page = jobs.gaps(UUID.fromString(view.id()),1,page.continuation()); } while (true);
            assertEquals(result.jobGapCount(),seen.size());
            for (var reportId : result.reports()) { var score = reports.get(reportId).orElseThrow().assessment().orElseThrow(); assertEquals(AssessmentStatus.WITHHELD,score.status()); assertTrue(score.score().isEmpty()); }
        }
    }
    @Test void unavailablePlatformPreservesStructuralGraphAndAcquisitionReasons() throws Exception {
        var runner = runner(false,true,POLICY); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("partial")),store,reports,new AnalysisJobs.Limits(1,1,4,90_000,100))) {
            var view = finishedReal(jobs,jobs.submit("partial","once").job().id()); assertEquals(AnalysisJobs.State.FAILED,view.state(),() -> CanonicalJson.write(view));
            var result = view.result().orElseThrow(); assertTrue(result.graph().isPresent()); assertTrue(result.gapCount() > 0); assertTrue(result.analyses().isEmpty());
            assertTrue(jobs.gaps(UUID.fromString(view.id()),50,Optional.empty()).items().stream().anyMatch(g -> g.detectingProvider().id().contains("platform")));
            assertFalse(CanonicalJson.write(view).contains(directory.toString()));
        }
    }
    @Test void invalidPolicyRemainsWithheldWithAnExplicitCompilerGap() throws Exception {
        var runner = runner(false,false,"schema: invalid\n"); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("invalid-policy")),store,reports,new AnalysisJobs.Limits(1,1,4,90_000,100))) {
            var view = finishedReal(jobs,jobs.submit("invalid-policy","once").job().id()); assertEquals(AnalysisJobs.State.PARTIAL,view.state(),() -> CanonicalJson.write(view));
            assertFalse(view.result().orElseThrow().reports().isEmpty());
            for (var id : view.result().orElseThrow().reports()) { var report = reports.get(id).orElseThrow(); assertEquals(AssessmentStatus.WITHHELD,report.evaluation().status()); assertEquals(AssessmentStatus.WITHHELD,report.assessment().orElseThrow().status()); }
            assertTrue(jobs.gaps(UUID.fromString(view.id()),50,Optional.empty()).items().stream().anyMatch(g -> g.detectingProvider().id().equals("policy.compiler")));
        }
    }
    @Test void passiveMavenSourceSelectionKeepsProductionTypesAndRecordsTestExclusion() throws Exception {
        var runner = runner(false,false,POLICY); var source = directory.resolve("source");
        Files.writeString(source.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>app</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>
                """);
        Files.createDirectories(source.resolve("src/test/java")); Files.writeString(source.resolve("src/test/java/IgnoredTest.java"),"public class IgnoredTest {}\n");
        var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        try (var jobs = new AnalysisJobs(List.of(runner.registration("maven")),store,reports,new AnalysisJobs.Limits(1,1,4,90_000,100))) {
            var view = finishedReal(jobs,jobs.submit("maven","once").job().id()); assertTrue(Set.of(AnalysisJobs.State.SUCCEEDED,AnalysisJobs.State.PARTIAL).contains(view.state()),() -> CanonicalJson.write(view));
            var graph = store.load(view.result().orElseThrow().graph().orElseThrow()).orElseThrow();
            assertEquals(2,graph.nodes().stream().filter(n -> n.kind().equals("TYPE")).count());
            assertTrue(graph.evidence().stream().anyMatch(e -> e.family().equals("repository.acquisition") && e.canonicalJson().contains("src/test")));
        }
    }
    @Test void failedAcquisitionKeepsTypedProviderProblemsWithoutInventingASnapshot() throws Exception {
        var source = directory.resolve("absent-source"); var store = new InMemoryGraphStore(); var reports = new PublishedReports();
        var runner = new LocalAnalysisRunner(new LocalAnalysisRunner.Input(source,RepositoryIdentity.fromCanonicalCoordinate("urn:test:unavailable"),Path.of(System.getProperty("java.home")),21,directory,
                Optional.empty(),true,false,List.of(),Optional.empty(),LocalAnalysisRunner.SpringInput.absent()));
        try (var jobs = new AnalysisJobs(List.of(runner.registration("unavailable")),store,reports,AnalysisJobsTest.limits())) {
            var view = AnalysisJobsTest.finished(jobs,jobs.submit("unavailable","once").job().id()); assertEquals(AnalysisJobs.State.FAILED,view.state());
            var result = view.result().orElseThrow(); assertTrue(result.snapshot().isEmpty()); assertTrue(result.graph().isEmpty()); assertTrue(result.analyses().isEmpty()); assertEquals(1,result.inputProblemCount());
            var api = new ArchitectureQueryApi(store,reports,ApiLimits.defaults(),jobs);
            var response = AnalysisJobApiTest.call(api,"POST","/api/v1/analysis/jobs/"+view.id()+"/input-diagnostics","{}"); assertEquals(200,response.status());
            var item = GraphJson.object(GraphJson.array(AnalysisJobApiTest.data(response).get("items")).getFirst());
            assertEquals(result.acquisition().orElseThrow().value(),item.get("acquisition"));
            assertEquals("ROOT_NOT_FOUND",GraphJson.object(item.get("problem")).get("reason")); assertFalse(CanonicalJson.write(item).contains(source.toString()));
        }
    }
}
