package com.evolution.analysis.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RootModuleTest {
    @Test
    void realRepositoryRootCanOwnSourcesWithoutInventingADirectory() {
        var repository = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/root.git");
        var root = ModuleDescriptor.create(repository, ".", "Root");
        var source = SourceDocument.create(repository, root, "src/main/java/Main.java",
                ContentDigest.sha256Utf8("class Main {}"), SourceClassification.MAIN);
        var snapshot = RepositorySnapshot.create(repository, Optional.empty(), false,
                List.of(SnapshotFile.from(source)), List.of(source));

        assertEquals(".", root.path());
        // Independently hashed from the documented M1 tuple and the explicit M3 root marker.
        assertEquals("module:sha256:c266f5f678d344db1ca0bb90a995cace2cd6ad92721b812f3aa227ac6a5799b2", root.identity().value());
        assertEquals(root.identity(), snapshot.documents().getFirst().module());
        assertNotEquals(root.identity(), ModuleIdentity.from(repository, "root"));
        assertThrows(IllegalArgumentException.class, () -> new SnapshotFile(".", source.contentDigest()));
        assertThrows(IllegalArgumentException.class,
                () -> ModuleDescriptor.create(repository, "./child", "Child"));
    }
}
