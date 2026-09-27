package com.evolution.analysis.acquisition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryUnderstandingContractTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/inventory.git");
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("test.acquirer", "1");

    @Test void partialPolyglotInventoryKeepsObservedFilesAndOpenFrontierSeparate() {
        var files = List.of(
                file("app/src/main/java/App.java", "class App {}"),
                file("app/src/test/java/AppTest.java", "class AppTest {}"),
                file("frontend/index.js", "export const x = 1;"),
                file("vendor/lib/Type.kt", "class Type"),
                file("generated/Model.java", "class Model {}"));
        var partial = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                request(), PROVIDER, RepositoryAcquisitionResult.Completion.PARTIAL, Optional.empty(),
                files, List.of(".", "app", "frontend", "vendor", "generated"),
                List.of(new RepositoryAcquisitionResult.Problem(
                        RepositoryAcquisitionResult.Reason.DIRECTORY_READ_FAILED, "unreadable",
                        RepositoryAcquisitionResult.Requirement.FILESYSTEM_READ)),
                List.of(), List.of());
        var inventory = RepositoryUnderstandingInventory.from(partial);
        assertEquals(5, inventory.observedFiles().size());
        assertEquals(1, inventory.frontiers().size());
        assertEquals(RepositoryUnderstandingInventory.JavaApplicability.APPLICABLE,
                inventory.javaApplicability());
        assertEquals(RepositoryUnderstandingInventory.Role.TEST,
                inventory.observedFiles().stream().filter(f -> f.path().endsWith("AppTest.java"))
                        .findFirst().orElseThrow().role());
        assertEquals(RepositoryUnderstandingInventory.Language.JAVASCRIPT,
                inventory.observedFiles().stream().filter(f -> f.path().endsWith("index.js"))
                        .findFirst().orElseThrow().language());
        assertTrue(inventory.unobservedFileCount().isEmpty());
    }

    @Test void completeNonJavaIsNotJavaArchitectureSuccess() {
        var file = file("README.md", "documentation");
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                List.of(new com.evolution.analysis.contract.source.SnapshotFile(file.path(), file.contentDigest())),
                List.of());
        var complete = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                request(), PROVIDER, RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(snapshot), List.of(file), List.of("."), List.of(), List.of(), List.of());
        var inventory = RepositoryUnderstandingInventory.from(complete);
        assertEquals(RepositoryUnderstandingInventory.JavaApplicability.NOT_APPLICABLE,
                inventory.javaApplicability());
        assertEquals(0, inventory.unobservedFileCount().orElseThrow());
        assertEquals(inventory.identity(), RepositoryUnderstandingInventory.from(complete).identity());
    }

    @Test void caseCollisionsAndLfsPointersRemainExplicit() {
        var files = List.of(file("A.java", "class A {}"), file("a.java", "class a {}"),
                file("src/Remote.java", "version https://git-lfs.github.com/spec/v1\n"));
        var partial = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                request(), PROVIDER, RepositoryAcquisitionResult.Completion.PARTIAL, Optional.empty(),
                files, List.of("."), List.of(new RepositoryAcquisitionResult.Problem(
                        RepositoryAcquisitionResult.Reason.FILE_COUNT_LIMIT, "more",
                        RepositoryAcquisitionResult.Requirement.ACQUISITION_POLICY)), List.of(), List.of());
        var inventory = RepositoryUnderstandingInventory.from(partial);
        assertEquals(List.of("A.java", "a.java"), inventory.caseCollisions().getFirst());
        assertEquals(RepositoryUnderstandingInventory.Availability.LFS_POINTER,
                inventory.observedFiles().stream().filter(f -> f.path().equals("src/Remote.java"))
                        .findFirst().orElseThrow().availability());
        assertTrue(inventory.unobservedFileCount().isEmpty());
    }

    private static RepositoryAcquisitionRequest request() {
        return new RepositoryAcquisitionRequest(REPOSITORY, Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1000, 10000, 8, List.of()));
    }
    private static AcquiredFile file(String path, String content) {
        return new AcquiredFile(path, content.getBytes(StandardCharsets.UTF_8));
    }
}
