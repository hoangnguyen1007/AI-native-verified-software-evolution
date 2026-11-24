package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringSatSoundnessTest {
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("frontend.test", "1");
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, PROVIDER, List.of());
    private static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/spring-soundness.git");

    @Test
    void frameworkGenerationInfersBoot27JavaxWhenSourceNamespaceHintIsJavax() {
        var emptyFramework = new SpringFrameworkEvidence(false, Optional.of(ContentDigest.sha256Utf8("cp")), List.of());

        var javax = FrameworkGeneration.from(emptyFramework, Optional.empty(),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED,
                Optional.of(FrameworkGeneration.Namespace.JAVAX));
        assertTrue(javax.consistent());
        assertEquals(Optional.of("5.3.31"), javax.frameworkVersion());
        assertEquals(Optional.of("2.7.18"), javax.bootVersion());
        assertEquals(FrameworkGeneration.Namespace.JAVAX, javax.namespace());
        assertEquals(FrameworkGeneration.OverridePolicy.ALLOW, javax.overridePolicy());
        assertEquals(FrameworkGeneration.MetadataPolicy.BOTH, javax.metadataPolicy());

        var javaxExplicitDeny = FrameworkGeneration.from(emptyFramework, Optional.of(false),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED,
                Optional.of(FrameworkGeneration.Namespace.JAVAX));
        assertEquals(FrameworkGeneration.OverridePolicy.DENY, javaxExplicitDeny.overridePolicy());

        var defaultThreeArg = FrameworkGeneration.from(emptyFramework, Optional.empty(),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertTrue(defaultThreeArg.consistent());
        assertEquals(Optional.of("6.2.0"), defaultThreeArg.frameworkVersion());
        assertEquals(Optional.of("3.4.0"), defaultThreeArg.bootVersion());
        assertEquals(FrameworkGeneration.Namespace.JAKARTA, defaultThreeArg.namespace());
        assertEquals(FrameworkGeneration.OverridePolicy.DENY, defaultThreeArg.overridePolicy());
        assertEquals(FrameworkGeneration.MetadataPolicy.IMPORTS, defaultThreeArg.metadataPolicy());
        assertNotEquals(javax.evidence(), defaultThreeArg.evidence());
    }

    @Test
    void sourceScopedAnnotationsGuardAgainstNonSpringImportsAndTrackNamespaceHint() {
        String nonSpringSrc = """
                package demo;
                import lombok.Value;
                import org.junit.jupiter.api.Order;
                import org.apache.dubbo.config.annotation.Service;
                import javax.inject.Scope;
                import jakarta.inject.Qualifier;
                @Value
                @Order(1)
                @Service
                @Scope
                @Qualifier
                public class NonSpring {}
                """;
        String wildcardNonSpringSrc = """
                package demo;
                import org.junit.jupiter.api.*;
                @Order(2)
                public class WildcardOrder {}
                """;
        String realSpringJavaxSrc = """
                package demo;
                import org.springframework.beans.factory.annotation.Value;
                import javax.inject.Inject;
                import javax.servlet.http.HttpServletRequest;
                @Value("${app.flag}")
                public class JavaxBean {
                    @Inject Object dep;
                }
                """;
        var fixture = buildFixture(Map.of(
                "src/main/java/demo/NonSpring.java", nonSpringSrc,
                "src/main/java/demo/WildcardOrder.java", wildcardNonSpringSrc,
                "src/main/java/demo/JavaxBean.java", realSpringJavaxSrc),
                List.of(
                        new AnnSpec("src/main/java/demo/NonSpring.java", "demo", "NonSpring", "@Value", 7),
                        new AnnSpec("src/main/java/demo/NonSpring.java", "demo", "NonSpring", "@Order(1)", 8),
                        new AnnSpec("src/main/java/demo/NonSpring.java", "demo", "NonSpring", "@Service", 9),
                        new AnnSpec("src/main/java/demo/NonSpring.java", "demo", "NonSpring", "@Scope", 10),
                        new AnnSpec("src/main/java/demo/NonSpring.java", "demo", "NonSpring", "@Qualifier", 11),
                        new AnnSpec("src/main/java/demo/WildcardOrder.java", "demo", "WildcardOrder", "@Order(2)", 3),
                        new AnnSpec("src/main/java/demo/JavaxBean.java", "demo", "JavaxBean", "@Value(\"${app.flag}\")", 5),
                        new AnnSpec("src/main/java/demo/JavaxBean.java", "demo", "JavaxBean", "@Inject", 7)),
                List.of());

        var emptyFramework = new SpringFrameworkEvidence(false, Optional.of(fixture.request().plan().classpathManifest()), List.of());
        var evidence = new SpringSourceEvidence(fixture.request(), fixture.frontend(), emptyFramework,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);

        var nonSpringId = fixture.typeId("demo", "NonSpring");
        assertTrue(evidence.annotations(nonSpringId).stream().allMatch(a -> a.name().isEmpty()),
                () -> "Non-Spring explicit imports must not be misclassified as Spring: " + evidence.annotations(nonSpringId));

        var wildcardId = fixture.typeId("demo", "WildcardOrder");
        assertTrue(evidence.annotations(wildcardId).stream().allMatch(a -> a.name().isEmpty()),
                () -> "Non-Spring wildcard import org.junit.jupiter.api.* must guard @Order: " + evidence.annotations(wildcardId));

        var javaxId = fixture.typeId("demo", "JavaxBean");
        var javaxNames = evidence.annotations(javaxId).stream().flatMap(a -> a.name().stream()).toList();
        assertTrue(javaxNames.contains("org.springframework.beans.factory.annotation.Value"));
        assertTrue(javaxNames.contains("javax.inject.Inject"),
                () -> "Explicit javax.inject.Inject must retain javax namespace, got: " + javaxNames);
        assertFalse(javaxNames.contains("jakarta.inject.Inject"));
        // Mixed javax + jakarta in the same fixture must remain honestly ambiguous (empty), never guess.
        assertEquals(Optional.empty(), evidence.observedNamespaceHint());

        // Pure javax EE fixture yields JAVAX hint.
        var pureJavaxFixture = buildFixture(Map.of("src/main/java/demo/JavaxBean.java", realSpringJavaxSrc),
                List.of(
                        new AnnSpec("src/main/java/demo/JavaxBean.java", "demo", "JavaxBean", "@Value(\"${app.flag}\")", 5),
                        new AnnSpec("src/main/java/demo/JavaxBean.java", "demo", "JavaxBean", "@Inject", 7)),
                List.of());
        var pureJavaxEvidence = new SpringSourceEvidence(pureJavaxFixture.request(), pureJavaxFixture.frontend(),
                new SpringFrameworkEvidence(false, Optional.of(pureJavaxFixture.request().plan().classpathManifest()), List.of()),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(Optional.of(FrameworkGeneration.Namespace.JAVAX), pureJavaxEvidence.observedNamespaceHint());

        // Pure jakarta fixture yields JAKARTA hint.
        String pureJakartaSrc = """
                package demo;
                import jakarta.inject.Inject;
                public class JakartaBean {
                    @Inject Object dep;
                }
                """;
        var pureJakartaFixture = buildFixture(Map.of("src/main/java/demo/JakartaBean.java", pureJakartaSrc),
                List.of(new AnnSpec("src/main/java/demo/JakartaBean.java", "demo", "JakartaBean", "@Inject", 4)),
                List.of());
        var pureJakartaEvidence = new SpringSourceEvidence(pureJakartaFixture.request(), pureJakartaFixture.frontend(),
                new SpringFrameworkEvidence(false, Optional.of(pureJakartaFixture.request().plan().classpathManifest()), List.of()),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(Optional.of(FrameworkGeneration.Namespace.JAKARTA), pureJakartaEvidence.observedNamespaceHint());

        // JDK javax.sql.* and JSR-305 javax.annotation.Nullable must NOT trigger a JAVAX EE hint.
        String jdkOnlySrc = """
                package demo;
                import javax.sql.DataSource;
                import javax.annotation.Nullable;
                public class JdkOnly {}
                """;
        var jdkFixture = buildFixture(Map.of("src/main/java/demo/JdkOnly.java", jdkOnlySrc), List.of(), List.of());
        var jdkEvidence = new SpringSourceEvidence(jdkFixture.request(), jdkFixture.frontend(),
                new SpringFrameworkEvidence(false, Optional.of(jdkFixture.request().plan().classpathManifest()), List.of()),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(Optional.empty(), jdkEvidence.observedNamespaceHint());
    }

    @Test
    void compatibleModeSuppressesOnlyUnresolvedDiagnosticsNeverSyntaxOrErrorDiagnostics() {
        String src = "package demo;\npublic class Sample {}\n";
        var unresolvedWarn = new DiagnosticSpec("src/main/java/demo/Sample.java", DiagnosticSeverity.WARNING, "java.unresolved");
        var syntaxErr = new DiagnosticSpec("src/main/java/demo/Sample.java", DiagnosticSeverity.ERROR, "java.syntax-error");
        var unresolvedErr = new DiagnosticSpec("src/main/java/demo/Sample.java", DiagnosticSeverity.ERROR, "java.unresolved");

        var fWarn = buildFixture(Map.of("src/main/java/demo/Sample.java", src), List.of(), List.of(unresolvedWarn));
        var eWarn = new SpringSourceEvidence(fWarn.request(), fWarn.frontend(),
                new SpringFrameworkEvidence(false, Optional.of(fWarn.request().plan().classpathManifest()), List.of()),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertTrue(eWarn.sourceHierarchy(fWarn.typeId("demo", "Sample"), 8).complete(),
                "WARNING java.unresolved should be suppressed in source-scoped mode");

        var fSyntax = buildFixture(Map.of("src/main/java/demo/Sample.java", src), List.of(), List.of(syntaxErr));
        var eSyntax = new SpringSourceEvidence(fSyntax.request(), fSyntax.frontend(),
                new SpringFrameworkEvidence(false, Optional.of(fSyntax.request().plan().classpathManifest()), List.of()),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(SpringSourceEvidence.HierarchyProblem.SOURCE_TYPES_INCOMPLETE,
                eSyntax.sourceHierarchy(fSyntax.typeId("demo", "Sample"), 8).problem(),
                "Syntax/ERROR diagnostics must never be suppressed");

        var fErr = buildFixture(Map.of("src/main/java/demo/Sample.java", src), List.of(), List.of(unresolvedErr));
        var eErr = new SpringSourceEvidence(fErr.request(), fErr.frontend(),
                new SpringFrameworkEvidence(false, Optional.of(fErr.request().plan().classpathManifest()), List.of()),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(SpringSourceEvidence.HierarchyProblem.SOURCE_TYPES_INCOMPLETE,
                eErr.sourceHierarchy(fErr.typeId("demo", "Sample"), 8).problem(),
                "ERROR-severity diagnostics must never be suppressed");
    }

    @Test
    void componentScanOnlySynthesizesRootWhenBootOrEnableAutoConfigurationPresent() {
        String utilSrc = """
                package demo;
                import org.springframework.stereotype.Service;
                @Service
                public class PlainService {}
                """;
        var utilFixture = buildFixture(
                Map.of("src/main/java/demo/PlainService.java", utilSrc),
                List.of(new AnnSpec("src/main/java/demo/PlainService.java", "demo", "PlainService", "@Service", 3)),
                List.of());
        var utilFramework = new SpringFrameworkEvidence(false, Optional.of(utilFixture.request().plan().classpathManifest()), List.of());
        var utilScan = new ComponentScanIngestion().scan(utilFixture.request(), utilFixture.frontend(), utilFramework, 100,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(ComponentScanIngestion.Status.OUTSIDE_ROOT, utilScan.rows().getFirst().status(),
                "Non-Boot utility module without @SpringBootApplication/@EnableAutoConfiguration/@ComponentScan must not synthesize root scan");

        String autoCfgSrc = """
                package demo;
                import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
                import org.springframework.stereotype.Service;
                @EnableAutoConfiguration
                @Service
                public class BootService {}
                """;
        var bootFixture = buildFixture(
                Map.of("src/main/java/demo/BootService.java", autoCfgSrc),
                List.of(
                        new AnnSpec("src/main/java/demo/BootService.java", "demo", "BootService", "@EnableAutoConfiguration", 4),
                        new AnnSpec("src/main/java/demo/BootService.java", "demo", "BootService", "@Service", 5)),
                List.of());
        var bootFramework = new SpringFrameworkEvidence(false, Optional.of(bootFixture.request().plan().classpathManifest()), List.of());
        var bootScan = new ComponentScanIngestion().scan(bootFixture.request(), bootFixture.frontend(), bootFramework, 100,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);
        assertEquals(ComponentScanIngestion.Status.INCLUDED, bootScan.rows().getFirst().status(),
                "@EnableAutoConfiguration should synthesize default root scan in source-scoped mode");
    }

    @Test
    void sourceToSpringPlanAcceptsLargeEnterpriseLimitWithoutOverflow() {
        String bootSrc = """
                package demo;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class App {}
                """;
        var fixture = buildFixture(
                Map.of("src/main/java/demo/App.java", bootSrc),
                List.of(new AnnSpec("src/main/java/demo/App.java", "demo", "App", "@SpringBootApplication", 3)),
                List.of());
        var framework = new SpringFrameworkEvidence(false, Optional.of(fixture.request().plan().classpathManifest()), List.of());
        var mode = SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED;
        var scan = new ComponentScanIngestion().scan(fixture.request(), fixture.frontend(), framework, 50_000, mode);
        var ctors = new ConstructorInjectionIngestion().ingest(fixture.frontend(), scan, framework, 100, mode);
        var source = new SpringSourceEvidence(fixture.request(), fixture.frontend(), framework, mode);
        var build = SpringBuildContext.fromExactRequest(fixture.request(), ContentDigest.sha256Utf8("build"));
        var ev = new ConditionEvidence.Derived(List.of(source.identity()), SourceToSpringPlan.PROVIDER, "test-scope");

        var plan = assertDoesNotThrow(() -> SourceToSpringPlan.normalizeAutoScoped(
                build, source, scan, ctors, "test-container", ev, mode, 50_000));
        assertNotNull(plan.binding());
        assertEquals(50_000, plan.binding().limits().maxDependencies());
    }

    private record AnnSpec(String path, String pkg, String simple, String spelling, int line) {}
    private record DiagnosticSpec(String path, DiagnosticSeverity severity, String code) {}
    private record BuiltFixture(FrontendRequest request, FrontendResult frontend, Map<String, EntityIdentity> types) {
        EntityIdentity typeId(String pkg, String simple) { return Objects.requireNonNull(types.get(pkg + "." + simple)); }
    }

    private static BuiltFixture buildFixture(Map<String, String> files, List<AnnSpec> anns, List<DiagnosticSpec> diags) {
        var module = ModuleDescriptor.create(REPO, ".", "app");
        var docs = new ArrayList<SourceDocument>();
        var snapshotFiles = new ArrayList<SnapshotFile>();
        var inputs = new ArrayList<SourceInput>();
        var docByPath = new HashMap<String, SourceDocument>();
        for (var e : new TreeMap<>(files).entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var doc = SourceDocument.create(REPO, module, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN);
            docs.add(doc);
            snapshotFiles.add(SnapshotFile.from(doc));
            inputs.add(new SourceInput(doc, bytes));
            docByPath.put(e.getKey(), doc);
        }
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, snapshotFiles, docs);
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jbase"), Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false, ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, docs, platform);
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("comp"));
        var manifest = AnalysisManifest.create(PROVIDER, snapshot, List.of(module), List.of(platform.entry()),
                AnalysisConfiguration.create(PROVIDER, options), comp, comp, comp);
        var request = new FrontendRequest(manifest, module.identity(), SourceClassification.MAIN, plan, inputs, platform, List.of());

        var declarations = new ArrayList<DeclarationRecord>();
        var typeDeclarations = new ArrayList<TypeDeclarationRecord>();
        var typesByName = new HashMap<String, EntityIdentity>();
        for (var e : new TreeMap<>(files).entrySet()) {
            var doc = docByPath.get(e.getKey());
            String fileName = e.getKey().substring(e.getKey().lastIndexOf('/') + 1);
            String simple = fileName.substring(0, fileName.length() - 5);
            String pkg = "demo";
            int lines = e.getValue().split("\\R", -1).length;
            var span = new SourceSpan(doc.identity(), 1, 1, Math.max(1, lines), 1);
            var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, simple).canonicalName(), Optional.of(span));
            String header = "public class " + simple + " {}";
            declarations.add(new DeclarationRecord(entity, header, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
            typeDeclarations.add(new TypeDeclarationRecord(entity.identity(), TypeDeclarationRecord.Kind.CLASS, false, true));
            typesByName.put(pkg + "." + simple, entity.identity());
        }

        var annotations = new ArrayList<AnnotationUseRecord>();
        int idx = 0;
        for (var a : anns) {
            var doc = docByPath.get(a.path());
            var owner = typesByName.get(a.pkg() + "." + a.simple());
            var span = new SourceSpan(doc.identity(), a.line(), 1, a.line(), Math.max(2, a.spelling().length()));
            String canonical = "java:v1:[\"annotation-use\",\"" + a.pkg() + "." + a.simple() + "\"," + (idx++) + "]";
            annotations.add(new AnnotationUseRecord(canonical, owner, span, "declaration-syntax", a.spelling()));
        }

        var diagnostics = new ArrayList<Diagnostic>();
        for (var d : diags) {
            var doc = docByPath.get(d.path());
            var span = new SourceSpan(doc.identity(), 2, 1, 2, 10);
            diagnostics.add(new Diagnostic(d.severity(), d.code(), "test diagnostic", Optional.of(span), Map.of()));
        }

        var outcomes = docs.stream().map(d -> new SourceOutcome(d.identity(), SourceOutcome.State.PROCESSED, List.of())).toList();
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                .toList();
        var frontend = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.COMPLETED,
                declarations, List.of(), List.of(), outcomes, coverage, diagnostics, List.of(), annotations,
                List.of(), typeDeclarations);
        return new BuiltFixture(request, frontend, typesByName);
    }
}
