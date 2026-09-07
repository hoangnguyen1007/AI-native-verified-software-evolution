package com.evolution.analysis.acquisition;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryAcquisitionContractTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/acquisition.git");

    @Test
    void requestsAndPoliciesRejectImplicitOrUnboundedSelections() {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(0, 1, 1, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(1, 0, 1, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(1, 1, 0, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(1, 1, 1, 0, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(1, 1, 1, 1, 0, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(1, 1, 1, 1, 1,
                List.of("../outside")));
        assertThrows(IllegalArgumentException.class, () -> new RepositoryAcquisitionPolicy(1, 1, 1, 1, 1,
                List.of("target", "target")));

        var policy = new RepositoryAcquisitionPolicy(10, 10, 100, 10, 5, List.of(".git", "target"));
        var request = new RepositoryAcquisitionRequest(REPOSITORY, Optional.of("abc123"), true, "pom.xml", policy);
        assertEquals(List.of(".git", "target"), request.policy().excludedPaths());
        assertEquals(request.identity(), new RepositoryAcquisitionRequest(
                REPOSITORY, Optional.of("abc123"), true, "pom.xml", policy).identity());
        assertNotEquals(request.identity(), new RepositoryAcquisitionRequest(
                REPOSITORY, Optional.of("abc124"), true, "pom.xml", policy).identity());
    }

    @Test
    void acquiredBytesAreImmutableAndDigestBound() {
        byte[] original = "class A {}".getBytes(StandardCharsets.UTF_8);
        var file = new AcquiredFile("src/A.java", original);
        ContentDigest digest = file.contentDigest();
        original[0] = 'X';
        byte[] exposed = file.bytes();
        exposed[0] = 'Y';

        assertEquals("class A {}", new String(file.bytes(), StandardCharsets.UTF_8));
        assertEquals(digest, ContentDigest.sha256(file.bytes()));
        assertEquals(10, file.size());
    }

    @Test
    void repositoryRootIsAValidCandidateSourceRoot() {
        var claim = new CandidateSourceOwnership.Claim(
                ModuleIdentity.from(REPOSITORY, "."),
                SourcePlanModel.Kind.MAIN,
                ".",
                List.of());
        assertEquals(".", claim.sourceRoot());
    }
}
