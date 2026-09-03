package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class TestInputs {
    static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m2-fixture.git");
    static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPO, "fixture", "fixture");
    static FrontendRequest request(String source) { return request(Map.of("fixture/C.java", source), List.of()); }
    static FrontendRequest request(Map<String, String> texts, List<BinaryInput> dependencies) {
        try {
            Path home = Path.of(System.getProperty("java.home"));
            var platform = new PlatformInput(new ClasspathEntry(ClasspathEntryKind.JDK_MODULE,
                    "jdk-runtime:" + Runtime.version(), ContentDigest.sha256(Files.readAllBytes(home.resolve("lib/modules")))), home);
            var sources = texts.entrySet().stream().map(e -> {
                byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
                return new SourceInput(SourceDocument.create(REPO, MODULE, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN), bytes);
            }).toList();
            return request(sources, platform, dependencies);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    static FrontendRequest request(List<SourceInput> sources, PlatformInput platform, List<BinaryInput> dependencies) {
        var documents = sources.stream().map(SourceInput::document).toList();
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, documents.stream().map(SnapshotFile::from).toList(), documents);
        var entries = new ArrayList<ClasspathEntry>(); entries.add(platform.entry()); dependencies.forEach(d -> entries.add(d.entry()));
        var component = new ManifestComponent(new VersionedIdentifier("test.fixture", "1"), ContentDigest.sha256Utf8("test-fixture-components-v1"));
        var manifest = AnalysisManifest.create(new VersionedIdentifier("analysis.manifest", "1"), snapshot, List.of(MODULE), entries,
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"), FrontendRequest.options(MODULE.identity(), SourceClassification.MAIN, documents)), component, component, component);
        return new FrontendRequest(manifest, MODULE.identity(), SourceClassification.MAIN, sources, platform, dependencies);
    }
}
