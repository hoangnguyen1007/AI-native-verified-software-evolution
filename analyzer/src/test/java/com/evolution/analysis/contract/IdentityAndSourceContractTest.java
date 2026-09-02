package com.evolution.analysis.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.contract.source.SnapshotFile;
import com.evolution.analysis.contract.source.SourceSpan;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IdentityAndSourceContractTest {

    @Test
    void repositoryIdentityHasAStableGoldenValue() {
        assertEquals(
                "repository:sha256:0a98d9ce7629974142838c8611196506990eb604ea12eaf0822637bf992728b4",
                ContractFixtures.REPOSITORY.value());
    }

    @Test
    void identityDerivationSeparatesComponentsInsteadOfConcatenatingThem() {
        EntityIdentity first = EntityIdentity.from(
                EntityOrigin.DEPENDENCY,
                EntityScope.external(
                        EntityOrigin.DEPENDENCY,
                        "example:ab",
                        ContentDigest.sha256Utf8("scope-one")),
                EntityKind.TYPE,
                "c");
        EntityIdentity second = EntityIdentity.from(
                EntityOrigin.DEPENDENCY,
                EntityScope.external(
                        EntityOrigin.DEPENDENCY,
                        "example:a",
                        ContentDigest.sha256Utf8("scope-two")),
                EntityKind.TYPE,
                "bc");

        assertNotEquals(first, second);
    }

    @Test
    void entityScopeRequiresTypedStableInputs() {
        assertEquals(
                ContractFixtures.moduleA().identity().value(),
                EntityScope.project(ContractFixtures.moduleA().identity()).value());
        assertThrows(
                IllegalArgumentException.class,
                () -> EntityScope.external(
                        EntityOrigin.PROJECT,
                        "module-a",
                        ContentDigest.sha256Utf8("module-a")));
    }

    @Test
    void canonicalRepositoryCoordinatesMustAlreadyBeCanonical() {
        assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryIdentity.fromCanonicalCoordinate(" https://example.test/repo.git"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryIdentity.fromCanonicalCoordinate("HTTPS://EXAMPLE.TEST/repo.git"));
    }

    @Test
    void repositoryRelativePathsRejectPlatformAndTraversalAmbiguity() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleDescriptor.create(
                        ContractFixtures.REPOSITORY, "module-a\\src", "Module A"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleDescriptor.create(
                        ContractFixtures.REPOSITORY, "../module-a", "Module A"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ModuleDescriptor.create(
                        ContractFixtures.REPOSITORY, "/module-a", "Module A"));
    }

    @Test
    void snapshotIdentityIsContentAddressedAndDocumentOrderIndependent() {
        SourceDocument changedSource = SourceDocument.create(
                ContractFixtures.REPOSITORY,
                ContractFixtures.moduleA(),
                ContractFixtures.sourceB().path(),
                ContentDigest.sha256Utf8("changed"),
                ContractFixtures.sourceB().classification());
        RepositorySnapshot forward = RepositorySnapshot.create(
                ContractFixtures.REPOSITORY,
                Optional.of("abc123"),
                false,
                ContractFixtures.inventory(
                        List.of(ContractFixtures.sourceA(), ContractFixtures.sourceB())),
                List.of(ContractFixtures.sourceA(), ContractFixtures.sourceB()));
        RepositorySnapshot reverse = RepositorySnapshot.create(
                ContractFixtures.REPOSITORY,
                Optional.of("different-label"),
                true,
                ContractFixtures.inventory(
                        List.of(ContractFixtures.sourceB(), ContractFixtures.sourceA())),
                List.of(ContractFixtures.sourceB(), ContractFixtures.sourceA()));
        RepositorySnapshot changed = RepositorySnapshot.create(
                ContractFixtures.REPOSITORY,
                Optional.of("abc123"),
                false,
                ContractFixtures.inventory(List.of(ContractFixtures.sourceA(), changedSource)),
                List.of(ContractFixtures.sourceA(), changedSource));

        assertEquals(forward.identity(), reverse.identity());
        assertEquals(forward.contentDigest(), reverse.contentDigest());
        assertNotEquals(forward.identity(), changed.identity());
    }

    @Test
    void snapshotIdentityIncludesNonSourceRepositoryFiles() {
        List<SnapshotFile> firstInventory = List.of(
                SnapshotFile.from(ContractFixtures.sourceA()),
                new SnapshotFile("pom.xml", ContentDigest.sha256Utf8("version-one")));
        List<SnapshotFile> changedInventory = List.of(
                SnapshotFile.from(ContractFixtures.sourceA()),
                new SnapshotFile("pom.xml", ContentDigest.sha256Utf8("version-two")));

        RepositorySnapshot first = RepositorySnapshot.create(
                ContractFixtures.REPOSITORY,
                Optional.of("abc123"),
                false,
                firstInventory,
                List.of(ContractFixtures.sourceA()));
        RepositorySnapshot changed = RepositorySnapshot.create(
                ContractFixtures.REPOSITORY,
                Optional.of("abc123"),
                false,
                changedInventory,
                List.of(ContractFixtures.sourceA()));

        assertNotEquals(first.identity(), changed.identity());
    }

    @Test
    void completeSourceSpanUsesOneBasedEndExclusiveCoordinates() {
        SourceSpan span = new SourceSpan(ContractFixtures.sourceA().identity(), 3, 5, 4, 1);

        assertEquals(3, span.startLine());
        assertEquals(1, span.endColumn());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceSpan(ContractFixtures.sourceA().identity(), 0, 1, 1, 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceSpan(ContractFixtures.sourceA().identity(), 2, 4, 2, 4));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceSpan(ContractFixtures.sourceA().identity(), 2, 4, 1, 8));
    }
}
