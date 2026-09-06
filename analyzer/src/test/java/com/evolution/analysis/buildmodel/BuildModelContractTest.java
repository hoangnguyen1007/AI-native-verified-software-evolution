package com.evolution.analysis.buildmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;

class BuildModelContractTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/build.git");

    @Test
    void inputBytesAndMutablePolicyCollectionsCannotChangeAnExistingRequest() {
        byte[] original = "<project/>".getBytes(StandardCharsets.UTF_8);
        var input = new PomInput(original);
        var poms = new HashMap<>(Map.of("pom.xml", input));
        var profiles = new ArrayList<>(List.of("selected"));
        var properties = new HashMap<>(Map.of("flag", ""));
        var policy = new BuildModelPolicy(profiles, List.of(), properties, 1000, 20, 20);
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false,
                List.of(new SnapshotFile("pom.xml", input.digest())), List.of());
        var request = new BuildModelRequest(snapshot, "pom.xml", poms, Map.of(), policy);
        ContentDigest identity = request.identity();
        original[0] = 'X';
        input.bytes()[0] = 'Y';
        poms.clear();
        profiles.clear();
        properties.clear();
        assertEquals("<project/>", new String(input.bytes(), StandardCharsets.UTF_8));
        assertEquals(identity, request.identity());
        assertEquals(List.of("selected"), policy.activeProfiles());
        assertEquals("", policy.userProperties().get("flag"));
    }

    @Test
    void unsupportedCoordinatesAndContradictoryPoliciesFailAtTheInputBoundary() {
        for (String version : List.of("../1", "${version}", "[1,2)", "https://host", "")) {
            assertThrows(IllegalArgumentException.class, () -> new MavenCoordinate("demo", "lib", version));
        }
        assertThrows(IllegalArgumentException.class, () -> new BuildModelPolicy(List.of("a"), List.of("a"), Map.of(), 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BuildModelPolicy(List.of(), List.of(), Map.of(), 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BuildModelPolicy(List.of(), List.of(), Map.of(), 1, 1, 129));
    }

    @Test
    void outputCannotInventASuccessfulReadOrDetachAModuleFromItsPom() {
        assertThrows(IllegalArgumentException.class, () -> new BuildModelResult.Attempt("pom.xml",
                BuildModelResult.AttemptKind.MODULE, "workspace:pom.xml", BuildModelResult.Outcome.SUCCEEDED, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new BuildModelResult.ModuleModel(
                ModuleDescriptor.create(REPOSITORY, "child", "child"), "pom.xml", Optional.empty(), Optional.empty()));
    }
}
