package com.evolution.analysis.spring.universal;

import com.evolution.analysis.assessment.v2.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.readiness.ReadinessDelta;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Stage-4 regression tests for Priority 0/1/2 fixes in analyzer/. */
class SpringAndCoreHardeningTest {
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("frontend.test", "1");
    private static final Derivation DIRECT = new Derivation(DerivationKind.DIRECT, PROVIDER, List.of());
    private static final RepositoryIdentity REPO =
            RepositoryIdentity.fromCanonicalCoordinate("https://example.test/core-hardening.git");

    @Test
    void serviceImplementingPlainProjectInterfaceClosesHierarchyWhileDefaultMethodStaysUnsupported() {
        var module = ModuleDescriptor.create(REPO, ".", "app");
        String ifaceSrc = "package demo;\npublic interface OrderService { String find(long id); }\n";
        String defaultIfaceSrc = "package demo;\npublic interface DefaultIface { default String extra() { return \"x\"; } }\n";
        String genericIfaceSrc = "package demo;\npublic interface GenericMarker<T> {}\n";
        String implSrc = "package demo;\nimport org.springframework.stereotype.Service;\n@Service\npublic class OrderServiceImpl implements OrderService {}\n";
        String badImplSrc = "package demo;\nimport org.springframework.stereotype.Service;\n@Service\npublic class BadServiceImpl implements DefaultIface {}\n";
        String rawGenericImplSrc = "package demo;\nimport org.springframework.stereotype.Service;\n@Service\npublic class RawGenericImpl implements GenericMarker {}\n";

        var docs = new ArrayList<SourceDocument>();
        var files = new ArrayList<SnapshotFile>();
        var inputs = new ArrayList<SourceInput>();
        var docByPath = new HashMap<String, SourceDocument>();
        for (var e : new TreeMap<>(Map.of(
                "src/main/java/demo/OrderService.java", ifaceSrc,
                "src/main/java/demo/DefaultIface.java", defaultIfaceSrc,
                "src/main/java/demo/GenericMarker.java", genericIfaceSrc,
                "src/main/java/demo/OrderServiceImpl.java", implSrc,
                "src/main/java/demo/BadServiceImpl.java", badImplSrc,
                "src/main/java/demo/RawGenericImpl.java", rawGenericImplSrc)).entrySet()) {
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            var doc = SourceDocument.create(REPO, module, e.getKey(), ContentDigest.sha256(b), SourceClassification.MAIN);
            docs.add(doc);
            files.add(SnapshotFile.from(doc));
            inputs.add(new SourceInput(doc, b));
            docByPath.put(e.getKey(), doc);
        }
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false, files, docs);
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jbase"),
                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, docs, platform);
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("comp"));
        var manifest = AnalysisManifest.create(PROVIDER, snapshot, List.of(module), List.of(platform.entry()),
                AnalysisConfiguration.create(PROVIDER, options), comp, comp, comp);
        var request = new FrontendRequest(manifest, module.identity(), SourceClassification.MAIN, plan, inputs, platform, List.of());

        var scope = EntityScope.project(module.identity());
        var orderIface = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "OrderService").canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/OrderService.java").identity(), 2, 1, 2, 55)));
        var orderMethod = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.METHOD,
                JavaSymbolName.method(JavaSymbolName.topLevelType("demo", "OrderService"), "find", List.of(ErasedType.primitive("long"))).canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/OrderService.java").identity(), 2, 33, 2, 52)));
        var defaultIface = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "DefaultIface").canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/DefaultIface.java").identity(), 2, 1, 2, 75)));
        var defaultMethod = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.METHOD,
                JavaSymbolName.method(JavaSymbolName.topLevelType("demo", "DefaultIface"), "extra", List.of()).canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/DefaultIface.java").identity(), 2, 33, 2, 72)));
        var genericIface = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "GenericMarker").canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/GenericMarker.java").identity(), 2, 1, 2, 36)));
        var impl = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "OrderServiceImpl").canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/OrderServiceImpl.java").identity(), 4, 1, 4, 56)));
        var badImpl = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "BadServiceImpl").canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/BadServiceImpl.java").identity(), 4, 1, 4, 54)));
        var rawGenericImpl = Entity.create(EntityOrigin.PROJECT, scope, EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "RawGenericImpl").canonicalName(),
                Optional.of(new SourceSpan(docByPath.get("src/main/java/demo/RawGenericImpl.java").identity(), 4, 1, 4, 55)));

        var declarations = List.of(
                new DeclarationRecord(orderIface, "public interface OrderService { String find(long id); }", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(orderMethod, "String find(long id);", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(defaultIface, "public interface DefaultIface { default String extra() { return \"x\"; } }", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(defaultMethod, "default String extra() { return \"x\"; }", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(genericIface, "public interface GenericMarker<T> {}", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(impl, "public class OrderServiceImpl implements OrderService {}", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(badImpl, "public class BadServiceImpl implements DefaultIface {}", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                new DeclarationRecord(rawGenericImpl, "public class RawGenericImpl implements GenericMarker {}", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
        var typeDecls = List.of(
                new TypeDeclarationRecord(orderIface.identity(), TypeDeclarationRecord.Kind.INTERFACE, false, true),
                new TypeDeclarationRecord(defaultIface.identity(), TypeDeclarationRecord.Kind.INTERFACE, false, true),
                new TypeDeclarationRecord(genericIface.identity(), TypeDeclarationRecord.Kind.INTERFACE, false, true),
                new TypeDeclarationRecord(impl.identity(), TypeDeclarationRecord.Kind.CLASS, false, true),
                new TypeDeclarationRecord(badImpl.identity(), TypeDeclarationRecord.Kind.CLASS, false, true),
                new TypeDeclarationRecord(rawGenericImpl.identity(), TypeDeclarationRecord.Kind.CLASS, false, true));
        var implRel = SemanticRelationship.create(impl.identity(), new RelationshipKind("java.implements"),
                new RelationshipTarget.Resolved(orderIface.identity()));
        var badRel = SemanticRelationship.create(badImpl.identity(), new RelationshipKind("java.implements"),
                new RelationshipTarget.Resolved(defaultIface.identity()));
        var rawGenericRel = SemanticRelationship.create(rawGenericImpl.identity(), new RelationshipKind("java.implements"),
                new RelationshipTarget.Resolved(genericIface.identity()));
        var dec1 = SemanticRelationship.create(orderIface.identity(), new RelationshipKind("java.declares"),
                new RelationshipTarget.Resolved(orderMethod.identity()));
        var dec2 = SemanticRelationship.create(defaultIface.identity(), new RelationshipKind("java.declares"),
                new RelationshipTarget.Resolved(defaultMethod.identity()));
        var occurrences = List.of(
                RelationshipOccurrence.create(implRel, impl.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                RelationshipOccurrence.create(badRel, badImpl.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                RelationshipOccurrence.create(rawGenericRel, rawGenericImpl.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                RelationshipOccurrence.create(dec1, orderMethod.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()),
                RelationshipOccurrence.create(dec2, defaultMethod.declaration().orElseThrow(), 0, SemanticStatus.RESOLVED, DIRECT, List.of(), List.of()));
        var typeUses = List.of(
                new TypeUseRecord(Optional.of(impl.identity()), new RelationshipKind("java.implements"),
                        impl.declaration().orElseThrow(),
                        new JavaType(JavaType.Kind.DECLARED, "OrderService", Optional.of(orderIface.identity()),
                                List.of(), Optional.empty(), SemanticStatus.RESOLVED), false),
                new TypeUseRecord(Optional.of(badImpl.identity()), new RelationshipKind("java.implements"),
                        badImpl.declaration().orElseThrow(),
                        new JavaType(JavaType.Kind.DECLARED, "DefaultIface", Optional.of(defaultIface.identity()),
                                List.of(), Optional.empty(), SemanticStatus.RESOLVED), false),
                new TypeUseRecord(Optional.of(rawGenericImpl.identity()), new RelationshipKind("java.implements"),
                        rawGenericImpl.declaration().orElseThrow(),
                        new JavaType(JavaType.Kind.DECLARED, "GenericMarker", Optional.of(genericIface.identity()),
                                List.of(), Optional.empty(), SemanticStatus.RESOLVED), false));
        var observations = occurrences.stream()
                .map(occ -> new ObservationRecord(
                        occ.span().document(),
                        occ.relationship().kind(),
                        Optional.of(occ.span()),
                        SemanticStatus.RESOLVED,
                        ObservationRecord.EvidenceState.VERIFIED,
                        ObservationRecord.EvidenceState.VERIFIED,
                        Optional.of(occ.identity()),
                        occ.relationship().kind().value(),
                        List.of()))
                .toList();
        var outcomes = docs.stream().map(d -> new SourceOutcome(d.identity(), SourceOutcome.State.PROCESSED, List.of())).toList();
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> {
                    String kindName = "java." + c;
                    int count = kindName.equals("java.implements") ? 3 : kindName.equals("java.declares") ? 2 : 0;
                    return new CategoryCoverage(new RelationshipKind(kindName), CategoryCoverage.Support.IMPLEMENTED, count, count, 0);
                })
                .toList();
        var frontend = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.COMPLETED,
                declarations, occurrences, observations, outcomes, coverage, List.of(), typeUses, List.of(), List.of(), typeDecls);

        var framework = new SpringFrameworkEvidence(false, Optional.of(plan.classpathManifest()), List.of());
        var evidence = new SpringSourceEvidence(request, frontend, framework,
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED);

        var ok = evidence.sourceHierarchy(impl.identity(), 8);
        assertEquals(SpringSourceEvidence.HierarchyProblem.NONE, ok.problem(),
                "@Service implementing plain contract interface must close cleanly without false SOURCE_SHAPE_UNSUPPORTED");

        var bad = evidence.sourceHierarchy(badImpl.identity(), 8);
        assertEquals(SpringSourceEvidence.HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED, bad.problem(),
                "Interface with default method must remain conservatively SOURCE_SHAPE_UNSUPPORTED");

        var rawGeneric = evidence.sourceHierarchy(rawGenericImpl.identity(), 8);
        assertEquals(SpringSourceEvidence.HierarchyProblem.SOURCE_SHAPE_UNSUPPORTED, rawGeneric.problem(),
                "Raw implementation of generic interface must remain conservatively SOURCE_SHAPE_UNSUPPORTED");
    }

    @Test
    void buildObservationsAndWebConditionsLowerDeterministicallyWithoutFakingMissingResources() {
        var module = ModuleDescriptor.create(REPO, ".", "app");
        String src = """
                package demo;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.boot.autoconfigure.condition.*;
                import org.springframework.boot.system.JavaVersion;
                @SpringBootApplication
                @ConditionalOnJava(value = JavaVersion.SEVENTEEN)
                @ConditionalOnResource(resources = "classpath:config/app.yml")
                @ConditionalOnResource(resources = "classpath:config/not-in-snapshot.yml")
                @ConditionalOnClass(DemoPresent.class)
                @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
                @ConditionalOnNotWebApplication
                public class DemoPresent {}
                """;
        byte[] srcBytes = src.getBytes(StandardCharsets.UTF_8);
        var doc = SourceDocument.create(REPO, module, "src/main/java/demo/DemoPresent.java",
                ContentDigest.sha256(srcBytes), SourceClassification.MAIN);
        byte[] resBytes = "server:\n  port: 8080\n".getBytes(StandardCharsets.UTF_8);
        var resFile = new SnapshotFile("src/main/resources/config/app.yml", ContentDigest.sha256(resBytes));
        var snapshot = RepositorySnapshot.create(REPO, Optional.empty(), false,
                List.of(SnapshotFile.from(doc), resFile), List.of(doc));
        var platform = PlatformInput.create(21, "21", "test", List.of(
                new PlatformInput.Artifact("java.base.jmod", ContentDigest.sha256Utf8("jbase"),
                        Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var plan = new FrontendPlan(Optional.of(21), Optional.of(21), false,
                ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, List.of(doc), platform);
        var comp = new ManifestComponent(PROVIDER, ContentDigest.sha256Utf8("comp"));
        var manifest = AnalysisManifest.create(PROVIDER, snapshot, List.of(module), List.of(platform.entry()),
                AnalysisConfiguration.create(PROVIDER, options), comp, comp, comp);
        var request = new FrontendRequest(manifest, module.identity(), SourceClassification.MAIN, plan,
                List.of(new SourceInput(doc, srcBytes)), platform, List.of());

        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "DemoPresent").canonicalName(),
                Optional.of(new SourceSpan(doc.identity(), 12, 1, 12, 28)));
        var anns = List.of(
                ann(doc, entity.identity(), 5, 0, "@SpringBootApplication"),
                ann(doc, entity.identity(), 6, 1, "@ConditionalOnJava(value = JavaVersion.SEVENTEEN)"),
                ann(doc, entity.identity(), 7, 2, "@ConditionalOnResource(resources = \"classpath:config/app.yml\")"),
                ann(doc, entity.identity(), 8, 3, "@ConditionalOnResource(resources = \"classpath:config/not-in-snapshot.yml\")"),
                ann(doc, entity.identity(), 9, 4, "@ConditionalOnClass(DemoPresent.class)"),
                ann(doc, entity.identity(), 10, 5, "@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)"),
                ann(doc, entity.identity(), 11, 6, "@ConditionalOnNotWebApplication"));
        var annOccurrences = new ArrayList<RelationshipOccurrence>();
        var annObservations = new ArrayList<ObservationRecord>();
        var unresolvedUnc = new Uncertainty("java.unresolved-annotation", "source-scoped test fixture", List.of("spring-framework-classpath"));
        for (int i = 0; i < anns.size(); i++) {
            var a = anns.get(i);
            var rel = SemanticRelationship.create(entity.identity(), new RelationshipKind("java.annotated-with"),
                    new RelationshipTarget.Unresolved(a.spelling()));
            var occ = RelationshipOccurrence.create(rel, a.span(), 0, SemanticStatus.UNRESOLVED, DIRECT,
                    List.of(unresolvedUnc), List.of());
            annOccurrences.add(occ);
            annObservations.add(new ObservationRecord(
                    a.span().document(),
                    new RelationshipKind("java.annotated-with"),
                    Optional.of(a.span()),
                    SemanticStatus.UNRESOLVED,
                    ObservationRecord.EvidenceState.MISSING,
                    ObservationRecord.EvidenceState.VERIFIED,
                    Optional.of(occ.identity()),
                    a.spelling(),
                    List.of()));
        }
        var frontend = new FrontendResult(manifest.identity(), PROVIDER, FrontendResult.State.COMPLETED,
                List.of(new DeclarationRecord(entity, "public class DemoPresent {}", SemanticStatus.RESOLVED, DIRECT, List.of(), List.of())),
                annOccurrences, annObservations,
                List.of(new SourceOutcome(doc.identity(), SourceOutcome.State.PROCESSED, List.of())),
                FrontendRequest.CATEGORIES.stream()
                        .map(c -> {
                            int n = c.equals("annotated-with") ? anns.size() : 0;
                            return new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, n, n, 0);
                        })
                        .toList(),
                List.of(), List.of(), anns, List.of(),
                List.of(new TypeDeclarationRecord(entity.identity(), TypeDeclarationRecord.Kind.CLASS, false, true)));

        var mode = SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED;
        var framework = new SpringFrameworkEvidence(false, Optional.of(plan.classpathManifest()), List.of());
        var scan = new ComponentScanIngestion().scan(request, frontend, framework, 100, mode);
        var ctors = new ConstructorInjectionIngestion().ingest(frontend, scan, framework, 100, mode);
        var source = new SpringSourceEvidence(request, frontend, framework, mode);
        var build = SpringBuildContext.fromExactRequest(request, ContentDigest.sha256Utf8("build"));
        var ev = new ConditionEvidence.Derived(List.of(source.identity()), SourceToSpringPlan.PROVIDER, "scope");

        var result = SourceToSpringPlan.normalizeAutoScoped(build, source, scan, ctors, "app", ev, mode, 100);
        var lowering = result.conditions();
        // 5 conditions lowered (Java>=17, Resource present, Class present, Web SERVLET, NotWeb NONE); 1 absent resource stays OPAQUE
        assertEquals(5, lowering.coverage().loweredRows(), () -> "Expected 5 lowered rows: " + lowering.rows() + " issues=" + lowering.issues());
        assertEquals(1, lowering.coverage().opaqueRows(), "Missing classpath resource must remain honestly OPAQUE");
        assertTrue(lowering.occurrences().stream().anyMatch(o ->
                o.expression().operand() instanceof ConditionExpression.Build b
                        && b.predicate() == ConditionExpression.BuildPredicate.JAVA_VERSION
                        && b.observedValue() == LogicalValue.TRUE));
        assertTrue(lowering.occurrences().stream().anyMatch(o ->
                o.expression().operand() instanceof ConditionExpression.Build b
                        && b.predicate() == ConditionExpression.BuildPredicate.RESOURCE_PRESENT
                        && b.observedValue() == LogicalValue.TRUE));
        assertTrue(lowering.occurrences().stream().anyMatch(o ->
                o.expression().operand() instanceof ConditionExpression.Build b
                        && b.predicate() == ConditionExpression.BuildPredicate.CLASS_PRESENT
                        && b.observedValue() == LogicalValue.TRUE));
        assertTrue(lowering.occurrences().stream().anyMatch(o ->
                o.expression().operand() instanceof ConditionExpression.Web w && w.mode() == FiniteDomain.WebMode.SERVLET));
        assertTrue(lowering.occurrences().stream().anyMatch(o ->
                o.expression().operand() instanceof ConditionExpression.Web w && w.mode() == FiniteDomain.WebMode.NONE));
    }

    @Test
    void canonicalJsonBorrowSha256IsReentrantSafe() {
        MessageDigest outer = CanonicalJson.borrowSha256();
        try {
            outer.update("outer-prefix-".getBytes(StandardCharsets.UTF_8));
            MessageDigest inner = CanonicalJson.borrowSha256();
            try {
                assertNotSame(outer, inner, "Nested borrowSha256 must not alias active outer MessageDigest");
                inner.update("inner-payload".getBytes(StandardCharsets.UTF_8));
                assertEquals(ContentDigest.sha256Utf8("inner-payload").value(),
                        "sha256:" + HexFormat.of().formatHex(inner.digest()));
            } finally {
                CanonicalJson.releaseSha256(inner);
            }
            outer.update("outer-suffix".getBytes(StandardCharsets.UTF_8));
            assertEquals(ContentDigest.sha256Utf8("outer-prefix-outer-suffix").value(),
                    "sha256:" + HexFormat.of().formatHex(outer.digest()));
        } finally {
            CanonicalJson.releaseSha256(outer);
        }
    }

    @Test
    void readinessDeltaClassifiesBuildSrcBuildLogicLocalJarsProtoAndAidlAsD3Build() {
        for (String path : List.of(
                "buildSrc/src/main/kotlin/Conventions.kt",
                "sub/build-logic/build.gradle.kts",
                "libs/vendor.jar",
                "app/libs/sdk.aar",
                "proto/orders.proto",
                "app/src/main/aidl/com/acme/IService.aidl")) {
            assertEquals(ReadinessDelta.DurabilityClass.D3_BUILD, ReadinessDelta.classifyPath(path), path);
            assertTrue(ReadinessDelta.isReadinessMarker(path), path);
            assertFalse(ReadinessDelta.isReadinessNeutralModification(path), path);
        }
    }

    @Test
    void incrementalGraphProjectorHandlesOver64KiBSpellingAndSeparatesFastArchitectureKey() {
        var module = ModuleDescriptor.create(REPO, ".", "app");
        var doc = SourceDocument.create(REPO, module, "src/main/java/demo/Big.java",
                ContentDigest.sha256Utf8("big"), SourceClassification.MAIN);
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("demo", "Big").canonicalName(),
                Optional.of(new SourceSpan(doc.identity(), 1, 1, 1, 10)));
        var frontend = new FrontendResult(
                new AnalysisIdentity("analysis:sha256:" + "a".repeat(64)),
                PROVIDER, FrontendResult.State.COMPLETED,
                List.of(new DeclarationRecord(entity, "class Big { String s = \"" + "Z".repeat(70_000) + "\"; }",
                        SemanticStatus.RESOLVED, DIRECT, List.of(), List.of())),
                List.of(), List.of(),
                List.of(new SourceOutcome(doc.identity(), SourceOutcome.State.PROCESSED, List.of())),
                FrontendRequest.CATEGORIES.stream()
                        .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                        .toList(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        ContentDigest fullKey = assertDoesNotThrow(() -> IncrementalGraphProjector.frontendStructuralKey(frontend, false));
        ContentDigest fastKey = assertDoesNotThrow(() -> IncrementalGraphProjector.frontendStructuralKey(frontend, true));
        assertNotEquals(fullKey, fastKey, "fastArchitecture flag must partition IncrementalGraphProjector structural keys");
    }

    @Test
    void scoringV2LayeringSelfLoopBoundsPolyglotImportsAndScaleLatency() {
        // 1. Layering fixes: MapStruct != DATA, MyBatis == DATA, core.api == PORT
        assertEquals(Optional.empty(), InferredLayerClassifier.classifyStereotype("org.mapstruct.Mapper"));
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_DATA),
                InferredLayerClassifier.classifyStereotype("org.apache.ibatis.annotations.Mapper"));
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_PORT),
                InferredLayerClassifier.classifyPackageName("com.acme.core.api"));
        assertEquals(Optional.of(InferredLayerClassifier.LAYER_PORT),
                InferredLayerClassifier.classifyPackageName("com.acme.domain.api"));

        // 2. Self-loop tangleEdgeRatio stays bounded in [0, 1]
        var n0 = new StructuralTopologyAnalyzer.NodeInfo("n0", "A", "p0", "demo", "m0", "app", "CLASS", false, 1, Set.of(), List.of());
        var n1 = new StructuralTopologyAnalyzer.NodeInfo("n1", "B", "p0", "demo", "m0", "app", "CLASS", false, 1, Set.of(), List.of());
        var selfAndNormal = new StructuralTopologyAnalyzer.StructuralGraph(
                List.of(n0, n1),
                List.of(
                        new StructuralTopologyAnalyzer.EdgeInfo("e0", "n0", "n0", List.of()),
                        new StructuralTopologyAnalyzer.EdgeInfo("e1", "n1", "n1", List.of()),
                        new StructuralTopologyAnalyzer.EdgeInfo("e2", "n0", "n1", List.of())));
        var topo = StructuralTopologyAnalyzer.analyze(selfAndNormal, 5, () -> {});
        assertTrue(topo.tangleEdgeRatio().isPresent());
        assertTrue(topo.tangleEdgeRatio().orElseThrow().compareTo(java.math.BigDecimal.ONE) <= 0,
                "tangleEdgeRatio must never exceed 1.0 when self-loops are present: " + topo.tangleEdgeRatio());

        // 3. Polyglot local import resolution (Python ..models, TS @/services, ESM .js->.ts, Go module suffix)
        var rootId = new SnapshotIdentity("snapshot:sha256:" + "b".repeat(64));
        var ctxId = KnowledgeGraph.derivedIdentity("polyglot-context", List.of("mixed"));
        var ctxEv = GraphEvidence.capture("polyglot.context", Map.of("language", "mixed"));
        var polyCtx = new KnowledgeGraph.Context(ctxId, rootId, Optional.empty(), ctxEv.identity());
        var graphNodes = new ArrayList<KnowledgeGraph.Node>();
        var graphEvs = new ArrayList<GraphEvidence>();
        graphEvs.add(ctxEv);
        addPolySyntax(ctxId, graphNodes, graphEvs, "pkg/sub/service.py", "IMPORT", "..models.user");
        addPolySyntax(ctxId, graphNodes, graphEvs, "pkg/models/user.py", "CLASS", "User");
        addPolySyntax(ctxId, graphNodes, graphEvs, "src/pages/home.ts", "IMPORT", "@/services/auth");
        addPolySyntax(ctxId, graphNodes, graphEvs, "src/services/auth.ts", "IMPORT", "./util.js");
        addPolySyntax(ctxId, graphNodes, graphEvs, "src/services/util.ts", "FUNCTION", "helper");
        addPolySyntax(ctxId, graphNodes, graphEvs, "cmd/api/main.go", "IMPORT", "github.com/acme/svc/internal/auth");
        addPolySyntax(ctxId, graphNodes, graphEvs, "internal/auth/handler.go", "FUNCTION", "Handle");

        var kg = new KnowledgeGraph(rootId, List.of(polyCtx), graphNodes, List.of(), graphEvs);
        var polyGraph = PolyglotArchitectureAnalyzer.extractPolyglotStructuralGraph(kg, ctxId, () -> {});
        assertEquals(7, polyGraph.nodes().size());
        assertEquals(4, polyGraph.edges().size(),
                "Expected 4 resolved local imports (Python relative, TS @/ alias, ESM .js->.ts, Go module suffix): " + polyGraph.edges());

        // 4. Synthetic scale check: 3,000 nodes + 6,000 edges stays fast and linear
        var scaleNodes = new ArrayList<StructuralTopologyAnalyzer.NodeInfo>(3000);
        var scaleEdges = new ArrayList<StructuralTopologyAnalyzer.EdgeInfo>(6000);
        for (int i = 0; i < 3000; i++) {
            int pkg = i / 30;
            scaleNodes.add(new StructuralTopologyAnalyzer.NodeInfo(
                    "t" + i, "Type" + i, "p" + pkg, "com.acme.pkg" + pkg, "m0", "app", "CLASS", false, 4, Set.of(), List.of()));
            if (i > 0) {
                scaleEdges.add(new StructuralTopologyAnalyzer.EdgeInfo("ea" + i, "t" + i, "t" + (i - 1), List.of()));
            }
            if (i >= 5) {
                scaleEdges.add(new StructuralTopologyAnalyzer.EdgeInfo("eb" + i, "t" + i, "t" + (i - 5), List.of()));
            }
        }
        long t0 = System.nanoTime();
        var scaleRes = StructuralTopologyAnalyzer.analyze(
                new StructuralTopologyAnalyzer.StructuralGraph(scaleNodes, scaleEdges), 5, () -> {});
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertEquals(3000, scaleRes.typeCount());
        assertTrue(elapsedMs < 10_000, "3000-node StructuralTopologyAnalyzer took too long: " + elapsedMs + "ms");
    }

    private static void addPolySyntax(
            String ctxId, List<KnowledgeGraph.Node> nodes, List<GraphEvidence> evs,
            String path, String category, String name) {
        var ev = GraphEvidence.capture("polyglot.source-syntax", Map.of(
                "path", path,
                "fact", Map.of("category", category, "name", name)));
        evs.add(ev);
        String nid = KnowledgeGraph.derivedIdentity("psyn", List.of(ctxId, path, category, name));
        nodes.add(new KnowledgeGraph.Node(nid, ctxId, category, List.of(ev.identity())));
    }

    private static AnnotationUseRecord ann(SourceDocument doc, EntityIdentity owner, int line, int idx, String spelling) {
        return new AnnotationUseRecord(
                "java:v1:[\"annotation-use\",\"demo.DemoPresent\"," + idx + "]",
                owner,
                new SourceSpan(doc.identity(), line, 1, line, Math.max(2, spelling.length())),
                "declaration-syntax",
                spelling);
    }
}
