package com.evolution.backend.analysis;

import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.filesystem.GitHostPolicy;
import com.evolution.analysis.graph.InMemoryGraphStore;
import com.evolution.backend.query.PublishedReports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkbenchArtifactPolicyTest {
    @TempDir Path root;

    @Test void workbenchUsesMultiRegistryDefaultPolicyAndRejectsUnapprovedEndpointForms() {
        var defaultPolicy = WorkbenchApiMain.artifactPolicy(" ").orElseThrow();
        assertEquals(List.of(
                com.evolution.analysis.dependency.DependencyAcquisitionPolicy.MAVEN_CENTRAL,
                WorkbenchApiMain.GOOGLE_MAVEN,
                WorkbenchApiMain.GRADLE_PLUGIN_PORTAL,
                WorkbenchApiMain.JITPACK), defaultPolicy.remoteRepositories());
        assertEquals(4096, defaultPolicy.maxCoordinates());
        assertEquals(2_147_483_648L, defaultPolicy.maxTotalBytes());
        assertEquals(8, defaultPolicy.maxConcurrentDownloads());
        assertTrue(WorkbenchApiMain.artifactPolicy("offline").isEmpty());
        assertTrue(WorkbenchApiMain.artifactPolicy("none").isEmpty());
        assertEquals(2_147_483_648L, LocalAnalysisRunner.CACHE_ONLY.maxTotalBytes());
        assertTrue(LocalAnalysisRunner.CACHE_ONLY.remoteRepositories().isEmpty());
        for (String endpoint : List.of("http://repo.example.test/", "https://user@repo.example.test/",
                "https://repo.example.test/?value=1", "https://repo.example.test/missing-slash")) {
            assertThrows(IllegalArgumentException.class, () -> WorkbenchApiMain.artifactPolicy(endpoint));
        }
        var first = URI.create("https://repo.example.test/maven2/");
        var second = URI.create("https://mirror.example.test/maven2/");
        var policy = WorkbenchApiMain.artifactPolicy(first + "  " + second).orElseThrow();
        assertEquals(List.of(first,second),policy.remoteRepositories());
        assertNotEquals(policy.identity(),WorkbenchApiMain.artifactPolicy(second + " " + first).orElseThrow().identity());
    }

    @Test void launcherAndBothConnectionKindsBindTheSameArtifactPolicy() throws Exception {
        var source = Files.createDirectory(root.resolve("source"));
        var jdk = Path.of(System.getProperty("java.home"));
        var cache = Files.createDirectory(root.resolve("cache"));
        var state = Files.createDirectory(root.resolve("analysis"));
        var policy = WorkbenchApiMain.artifactPolicy("https://repo.example.test/maven2/");
        var input = new LocalAnalysisRunner.Input(source,RepositoryIdentity.fromCanonicalCoordinate("urn:test:source"),jdk,21,cache,
                Optional.empty(),true,false,List.of(),Optional.empty(),LocalAnalysisRunner.SpringInput.absent());
        var launcher = new LocalAnalysisRunner(input,state.resolve("acquisition"),policy).registration("local");
        try (var jobs = new AnalysisJobs(List.of(launcher),new InMemoryGraphStore(),new PublishedReports(),AnalysisJobs.Limits.defaults())) {
            var connections = new RepositoryConnections(jobs,jdk,21,cache,state,Optional.empty(),"git",new GitHostPolicy(),policy);
            var local = connections.connect(source.toString(),Optional.empty());
            var git = connections.connect("https://github.com/example/sample.git",Optional.empty());
            assertEquals(3,jobs.setups().size());
            assertEquals(List.of(local.setup(),git.setup()).stream().sorted().toList(),
                    jobs.setups().stream().map(AnalysisJobs.Setup::id).filter(id -> !id.equals("local")).sorted().toList());
            for (var setup : jobs.setups()) assertEquals(policy.map(configured -> configured.identity()),setup.artifactPolicy());
        }
    }
}
