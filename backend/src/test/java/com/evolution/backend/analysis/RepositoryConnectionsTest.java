package com.evolution.backend.analysis;

import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.backend.query.PublishedReports;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryConnectionsTest {
    @TempDir Path root;
    @Test void connectsActualLocalDirectoriesAndReusesTheSameSelection() throws Exception {
        var source = Files.createDirectory(root.resolve("application"));
        var cache = Files.createDirectory(root.resolve("cache"));
        try (var jobs = new AnalysisJobs(List.of(),new InMemoryGraphStore(),new PublishedReports(),AnalysisJobs.Limits.defaults())) {
            var connections = new RepositoryConnections(jobs,Path.of(System.getProperty("java.home")),21,cache,root,Optional.empty());
            var first = connections.connect(source.toString(),Optional.empty());
            assertEquals("application",first.label());
            assertEquals("LOCAL",first.kind());
            assertEquals(first,connections.connect(source.toString(),Optional.empty()));
            assertEquals(1,jobs.setups().size());
            assertThrows(IllegalArgumentException.class,() -> connections.connect(root.resolve("missing").toString(),Optional.empty()));
            assertThrows(IllegalArgumentException.class,() -> connections.connect(root.resolve("cache").toString(),Optional.of("x".repeat(262145))));
        }
    }
    @Test void rejectsCredentialedLocalNetworkAndExecutableRemoteOperands() {
        for (var source : List.of("http://github.com/team/repo","https://user:secret@github.com/team/repo",
                "ssh://git@github.com/team/repo","https://127.0.0.1/repo","https://github.com:444/team/repo",
                "https://github.com/team/repo?token=secret","https://github.com/team/../repo","https://github.com/team/repo#main",
                "https://github.com/team/repo%2fother","git@github.com:team/repo")) {
            assertThrows(IllegalArgumentException.class,() -> RepositoryConnections.remote(source),source);
        }
        assertEquals("https://github.com/team/repo",RepositoryConnections.remote("https://github.com/team/repo"));
        assertEquals("https://self-hosted.example/repo",RepositoryConnections.remote("https://self-hosted.example/repo"));
        assertEquals("https://gitlab.com/team/sub/repo.git",RepositoryConnections.remote("https://gitlab.com/team/sub/repo.git"));
    }
    @Test void unavailableGitRetainsDeterministicAcquisitionDiagnosticsWithoutInventingASnapshot() throws Exception {
        try (var jobs=new AnalysisJobs(List.of(),new InMemoryGraphStore(),new PublishedReports(),AnalysisJobs.Limits.defaults())) {
            var connections=new RepositoryConnections(jobs,Path.of(System.getProperty("java.home")),21,root,root,Optional.empty(),root.resolve("missing-git").toString());
            var selection=connections.connect("https://github.com/team/repo",Optional.empty());
            AnalysisJobs.Result previous=null;
            for (String key : List.of("first","replay")) {
                var job=jobs.submit(selection.setup(),key).job(); long end=System.nanoTime()+5_000_000_000L;
                while (!AnalysisJobs.terminal(job.state()) && System.nanoTime()<end) { Thread.sleep(10); job=jobs.get(UUID.fromString(job.id())); }
                assertEquals(AnalysisJobs.State.FAILED,job.state());
                var result=job.result().orElseThrow(); assertTrue(result.graph().isEmpty()); assertTrue(result.snapshot().isEmpty());
                assertEquals(1,result.inputProblemCount());
                var problems=jobs.inputProblems(UUID.fromString(job.id()),50,Optional.empty());
                assertEquals("source.git-cas",problems.items().getFirst().provider().id());
                assertEquals("m8.4-v2",problems.items().getFirst().provider().version());
                assertEquals("GIT_ACQUISITION_UNAVAILABLE",problems.items().getFirst().problem().subject());
                if (previous!=null) assertEquals(previous,result); previous=result;
            }
        }
    }
    @Test void configuredInternalHttpsEndpointKeepsItsExactPathAndPort()throws Exception{
        var policy=new com.evolution.analysis.filesystem.GitHostPolicy(Set.of("internal.example"),Map.of(),host->new java.net.InetAddress[]{java.net.InetAddress.getLoopbackAddress()},uri->Optional.empty());
        try(var jobs=new AnalysisJobs(List.of(),new InMemoryGraphStore(),new PublishedReports(),AnalysisJobs.Limits.defaults())){
            var connections=new RepositoryConnections(jobs,Path.of(System.getProperty("java.home")),21,root,root,Optional.empty(),"git",policy);
            String source="https://internal.example:8443/repositories/project";
            assertEquals(com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate(source),connections.connect(source,Optional.empty()).repository());
        }
    }
}
