package com.evolution.analysis.ingestion;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryIntakeDecoderTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/intake.git");
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("test.acquirer", "1");

    @Test void completeAcquisitionDecodesSelectedTextAndAccountsForMalformedBytes() {
        var files = List.of(new AcquiredFile("build.gradle", "plugins { java }".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("src/main/java/A.java", "class A {}".getBytes(StandardCharsets.UTF_8)),
                new AcquiredFile("src/main/java/B.java", new byte[] {(byte) 0xc3, (byte) 0x28}),
                new AcquiredFile("lib/blob.bin", new byte[] {0, 1, 2}));
        var acquisition = complete(files);
        var result = new RepositoryInputDecoder().decode(acquisition, new RepositoryInputDecoder.Policy("UTF-8", 20, 1000));
        var inputs = result.inputs().orElseThrow();
        assertEquals(4, inputs.snapshot().files().size());
        assertTrue(inputs.files().containsKey("src/main/java/A.java"));
        assertFalse(inputs.files().containsKey("src/main/java/B.java"));
        assertFalse(inputs.files().containsKey("lib/blob.bin"));
        assertTrue(result.issues().stream().anyMatch(i -> i.subject().equals("src/main/java/B.java")));
        assertEquals(4, result.inventory().observedFiles().size());
        assertEquals(result.identity(), new RepositoryInputDecoder().decode(acquisition,
                new RepositoryInputDecoder.Policy("UTF-8", 20, 1000)).identity());
    }

    @Test void partialAcquisitionNeverBecomesAnExactSnapshot() {
        var partial = new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                request(), PROVIDER, RepositoryAcquisitionResult.Completion.PARTIAL, Optional.empty(),
                List.of(new AcquiredFile("A.java", "class A {}".getBytes(StandardCharsets.UTF_8))),
                List.of("."), List.of(new RepositoryAcquisitionResult.Problem(
                        RepositoryAcquisitionResult.Reason.UNVISITED_REGION, ".",
                        RepositoryAcquisitionResult.Requirement.ACQUISITION_POLICY)), List.of(), List.of());
        var result = new RepositoryInputDecoder().decode(partial,
                new RepositoryInputDecoder.Policy("UTF-8", 20, 1000));
        assertTrue(result.inputs().isEmpty());
        assertEquals(1, result.inventory().observedFiles().size());
        assertTrue(result.inventory().unobservedFileCount().isEmpty());
    }

    private static RepositoryAcquisitionResult complete(List<AcquiredFile> files) {
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                files.stream().map(f -> new SnapshotFile(f.path(), f.contentDigest())).toList(), List.of());
        return new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,
                request(), PROVIDER, RepositoryAcquisitionResult.Completion.COMPLETE,
                Optional.of(snapshot), files, List.of("."), List.of(), List.of(), List.of());
    }
    private static RepositoryAcquisitionRequest request() {
        return new RepositoryAcquisitionRequest(REPOSITORY, Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(100, 100, 1000, 10000, 8, List.of()));
    }
}
