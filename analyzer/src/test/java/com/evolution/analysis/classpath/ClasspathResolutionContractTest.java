package com.evolution.analysis.classpath;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.buildmodel.MavenCoordinate;
import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.classpath.ExactClasspathResult.*;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ClasspathResolutionContractTest {
    private static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate(
            "https://example.test/classpath-contract.git");
    private static final ModuleIdentity MODULE = ModuleIdentity.from(REPOSITORY, ".");

    @Test
    void policiesRejectUnboundedOrImpossibleResourceLimits() {
        assertThrows(IllegalArgumentException.class, () -> policy(0, 2, 10, 10, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 0, 10, 10, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 2, 0, 10, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 2, 10, 0, 20, 2));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 2, 10, 10, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 2, 10, 10, 20, 0));
        assertThrows(IllegalArgumentException.class, () -> policy(1, 2, 10, 10, 20, 129));
    }

    @Test
    void manifestIdentityBindsOrderScopeEvidenceAndProblems() {
        var firstCoordinate = new ArtifactCoordinate(new MavenCoordinate("demo", "first", "1"), "jar", "");
        var secondCoordinate = new ArtifactCoordinate(new MavenCoordinate("demo", "second", "1"), "jar", "tests");
        var firstDigest = ContentDigest.sha256Utf8("first");
        var secondDigest = ContentDigest.sha256Utf8("second");
        var first = entry(firstCoordinate, DependencyScope.COMPILE, firstDigest, 0, true, 1);
        var second = entry(secondCoordinate, DependencyScope.RUNTIME, secondDigest, 1, false, 2);
        var complete = Manifest.create(MODULE, SourcePlanModel.Kind.TEST,
                List.of(first, second), List.of(), List.of(), List.of());

        assertEquals(Status.COMPLETE, complete.status());
        assertEquals(List.of(first.classpathEntry(), second.classpathEntry()), complete.classpath());
        assertEquals(complete.identity(), Manifest.create(MODULE, SourcePlanModel.Kind.TEST,
                List.of(first, second), List.of(), List.of(), List.of()).identity());
        assertNotEquals(complete.identity(), Manifest.create(MODULE, SourcePlanModel.Kind.TEST,
                List.of(second, first), List.of(), List.of(), List.of()).identity());

        var problem = new Problem(Reason.MISSING_ARTIFACT, firstCoordinate.notation(),
                Requirement.DEPENDENCY_ARTIFACT, List.of());
        var partial = Manifest.create(MODULE, SourcePlanModel.Kind.TEST,
                List.of(first, second), List.of(), List.of(), List.of(problem));
        assertEquals(Status.PARTIAL, partial.status());
        assertNotEquals(complete.identity(), partial.identity());
    }

    @Test
    void entriesCannotMispairCoordinatesDigestsOrPhysicalEvidence() {
        var coordinate = new ArtifactCoordinate(new MavenCoordinate("demo", "lib", "1"), "jar", "");
        var digest = ContentDigest.sha256Utf8("jar");
        var evidence = new Evidence("cache:" + coordinate.repositoryPath(), digest);
        var classpath = coordinate.classpathEntry(digest);

        assertThrows(IllegalArgumentException.class, () -> new Entry(
                coordinate,
                DependencyScope.COMPILE,
                new ClasspathEntry(classpath.kind(), "wrong", digest),
                coordinate.repositoryPath(),
                0,
                true,
                1,
                List.of(evidence)));
        assertThrows(IllegalArgumentException.class, () -> new Entry(
                coordinate,
                DependencyScope.COMPILE,
                coordinate.classpathEntry(ContentDigest.sha256Utf8("different")),
                coordinate.repositoryPath(),
                0,
                true,
                1,
                List.of(evidence)));
        assertThrows(IllegalArgumentException.class, () -> new Entry(
                coordinate,
                DependencyScope.COMPILE,
                classpath,
                "elsewhere.jar",
                0,
                true,
                1,
                List.of(evidence)));
    }

    private static Entry entry(
            ArtifactCoordinate coordinate, DependencyScope scope, ContentDigest digest, int order, boolean direct, int depth) {
        return new Entry(
                coordinate,
                scope,
                coordinate.classpathEntry(digest),
                coordinate.repositoryPath(),
                order,
                direct,
                depth,
                List.of(new Evidence("cache:" + coordinate.repositoryPath(), digest)));
    }

    private static ClasspathResolutionPolicy policy(
            int coordinates, int files, long pomBytes, long artifactBytes, long totalBytes, int depth) {
        return new ClasspathResolutionPolicy(coordinates, files, pomBytes, artifactBytes, totalBytes, depth);
    }
}
