package com.evolution.analysis.input;

import com.evolution.analysis.buildmodel.SourcePlanModel;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.condition.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

/** Authored in-memory M3 contract inputs; does not pretend to acquire external artifacts. */
public final class ConditionTestInputs {
    public static final RepositoryIdentity REPOSITORY = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/m4b1.git");
    public static final ModuleDescriptor MODULE = ModuleDescriptor.create(REPOSITORY, ".", "fixture");
    private static final String SOURCE = "package fixture;\nclass Config {\n  String value;\n}\n";
    public static final SourceDocument DOCUMENT = SourceDocument.create(REPOSITORY, MODULE, "Config.java",
            ContentDigest.sha256Utf8(SOURCE), SourceClassification.MAIN);
    public static final ConditionEvidence EVIDENCE = new ConditionEvidence.Source(DOCUMENT.identity(), DOCUMENT.contentDigest(),
            Optional.of(new SourceSpan(DOCUMENT.identity(), 1, 1, 4, 2)), 0);
    private ConditionTestInputs() {}

    public static SpringBuildContext context() { return context(List.of("a", "b"), "fixture-locator", 21); }
    public static SpringBuildContext context(List<String> dependencyOrder, String locator, int release) {
        var assembly = assembly(dependencyOrder, locator, release);
        return SpringBuildContext.from(assembly, MODULE.identity(), SourcePlanModel.Kind.MAIN);
    }
    public static FrontendAssemblyResult assembly(List<String> dependencyOrder, String locator, int release) {
        var platform = PlatformInput.create(release, release + "-fixture", "Authored fixture", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("platform"),
                        Path.of(locator, "java.base.jmod"), PlatformInput.Format.JMOD)));
        List<BinaryInput> dependencies = dependencyOrder.stream().map(name -> new BinaryInput(
                new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "fixture:" + name + ":1", ContentDigest.sha256Utf8(name)),
                Path.of(locator, name + ".jar"))).toList();
        var plan = new FrontendPlan(Optional.of(release), Optional.of(release), false,
                ContentDigest.sha256Utf8("classpath-fixture"), ContentDigest.sha256Utf8("decoding-fixture"));
        var snapshot = RepositorySnapshot.create(REPOSITORY, Optional.empty(), false, List.of(SnapshotFile.from(DOCUMENT)), List.of(DOCUMENT));
        List<ClasspathEntry> entries = new ArrayList<>(List.of(platform.entry()));
        dependencies.forEach(dependency -> entries.add(dependency.entry()));
        var component = new ManifestComponent(new VersionedIdentifier("fixture.component", "1"), ContentDigest.sha256Utf8("component"));
        var manifest = AnalysisManifest.create(new VersionedIdentifier("analysis.manifest", "1"), snapshot,
                List.of(MODULE), entries, AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"),
                        FrontendRequest.options(plan, MODULE.identity(), SourceClassification.MAIN, List.of(DOCUMENT), platform)),
                component, component, component);
        var request = new FrontendRequest(manifest, MODULE.identity(), SourceClassification.MAIN, plan,
                List.of(new SourceInput(DOCUMENT, SOURCE.getBytes(StandardCharsets.UTF_8))), platform, dependencies);
        return FrontendAssemblyResult.create(ContentDigest.sha256Utf8("assembly-input-fixture"), List.of(
                new FrontendAssemblyResult.Outcome(MODULE.identity(), SourcePlanModel.Kind.MAIN,
                        FrontendAssemblyResult.Status.ASSEMBLED, Optional.of(request), List.of())));
    }
}
