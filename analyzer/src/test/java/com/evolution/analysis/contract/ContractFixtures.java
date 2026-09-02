package com.evolution.analysis.contract;

import com.evolution.analysis.contract.analysis.AnalysisConfiguration;
import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.analysis.ManifestComponent;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceDocument;
import com.evolution.analysis.contract.source.SnapshotFile;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ContractFixtures {

    static final RepositoryIdentity REPOSITORY =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/repo.git");

    private ContractFixtures() {}

    static ModuleDescriptor moduleA() {
        return ModuleDescriptor.create(REPOSITORY, "module-a", "Module A");
    }

    static SourceDocument sourceA() {
        return SourceDocument.create(
                REPOSITORY,
                moduleA(),
                "module-a/src/main/java/example/A.java",
                ContentDigest.sha256Utf8("package example; class A {}\n"),
                SourceClassification.MAIN);
    }

    static SourceDocument sourceB() {
        return SourceDocument.create(
                REPOSITORY,
                moduleA(),
                "module-a/src/main/java/example/B.java",
                ContentDigest.sha256Utf8("package example; class B {}\n"),
                SourceClassification.MAIN);
    }

    static AnalysisConfiguration configuration(Map<String, String> values) {
        return AnalysisConfiguration.create(
                new VersionedIdentifier("analysis.configuration", "1"), values);
    }

    static AnalysisManifest manifest(
            List<SourceDocument> documents,
            List<ClasspathEntry> classpath,
            Map<String, String> configurationValues) {
        RepositorySnapshot snapshot = RepositorySnapshot.create(
                REPOSITORY,
                Optional.of("abc123"),
                false,
                inventory(documents),
                documents);
        return AnalysisManifest.create(
                new VersionedIdentifier("analysis.manifest", "1"),
                snapshot,
                List.of(moduleA()),
                classpath,
                configuration(configurationValues),
                new ManifestComponent(
                        new VersionedIdentifier("tool.analyzer", "0.1.0"),
                        ContentDigest.sha256Utf8("analyzer-binary")),
                new ManifestComponent(
                        new VersionedIdentifier("policy.rules", "none"),
                        ContentDigest.sha256Utf8("no-rules")),
                new ManifestComponent(
                        new VersionedIdentifier("graph.schema", "1"),
                        ContentDigest.sha256Utf8("graph-schema-v1")));
    }

    static ClasspathEntry dependency(String coordinate, String content) {
        return new ClasspathEntry(
                ClasspathEntryKind.DEPENDENCY,
                coordinate,
                ContentDigest.sha256Utf8(content));
    }

    static List<SnapshotFile> inventory(List<SourceDocument> documents) {
        return documents.stream().map(SnapshotFile::from).toList();
    }
}
