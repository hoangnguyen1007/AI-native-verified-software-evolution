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
    private static final class FixedSdk {
        static final PlatformInput INPUT=acquire();
        private static PlatformInput acquire(){
            Path modules=Path.of(System.getProperty("java.home")).resolve("lib/modules");
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");
                try(var in=new java.security.DigestInputStream(Files.newInputStream(modules),digest)){in.transferTo(java.io.OutputStream.nullOutputStream());}
                return PlatformInput.create(Runtime.version().feature(),Runtime.version().toString(),System.getProperty("java.vendor"),List.of(new PlatformInput.Artifact(
                        "lib/modules",new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest())),modules,PlatformInput.Format.RUNTIME_MODULES)));
            }catch(java.io.IOException failure){throw new java.io.UncheckedIOException(failure);}
            catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        }
    }
    static FrontendRequest request(String source) { return request(Map.of("fixture/C.java", source), List.of()); }
    static FrontendRequest request(Map<String, String> texts, List<BinaryInput> dependencies) {
            var platform = FixedSdk.INPUT;
            var sources = texts.entrySet().stream().map(e -> {
                byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
                return new SourceInput(SourceDocument.create(REPO, MODULE, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN), bytes);
            }).toList();
            return request(sources, platform, dependencies);
    }
    static FrontendRequest request(List<SourceInput> sources, PlatformInput platform, List<BinaryInput> dependencies) {
        return request(sources, platform, dependencies, FrontendPlan.legacy());
    }
    static FrontendRequest request(List<SourceInput> sources, PlatformInput platform, List<BinaryInput> dependencies,
            FrontendPlan plan) {
        var documents = sources.stream().map(SourceInput::document).toList();
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, documents.stream().map(SnapshotFile::from).toList(), documents);
        var entries = new ArrayList<ClasspathEntry>(); entries.add(platform.entry()); dependencies.forEach(d -> entries.add(d.entry()));
        var component = new ManifestComponent(new VersionedIdentifier("test.fixture", "1"), ContentDigest.sha256Utf8("test-fixture-components-v1"));
        var options=new TreeMap<>(FrontendRequest.options(plan,MODULE.identity(),SourceClassification.MAIN,documents,platform));options.putAll(FrontendRequest.binaryOriginOptions(dependencies));
        var manifest = AnalysisManifest.create(new VersionedIdentifier("analysis.manifest", "1"), snapshot, List.of(MODULE), entries,
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"),
                        options),
                component, component, component);
        return new FrontendRequest(manifest, MODULE.identity(), SourceClassification.MAIN, plan,
                sources, platform, dependencies);
    }
}
