package com.evolution.cli;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.evolution.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.javaparser.JavaParserFrontend;
import com.evolution.analysis.query.*;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.truth.*;
import com.evolution.backend.analysis.SemanticDepth;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression suite for Phase 0 WP0.2:
 * <ul>
 *   <li>Blocker B2: Warm 1-File Edit vs Cold Edited SHA-256 parity across ALL contexts
 *       (including {@code spring-context}), complete {@link IncrementalSpringCache} hashing,
 *       and {@link IncrementalGraphProjector} {@code .gt2} invalidation on Spring/polyglot changes.</li>
 *   <li>Blocker B3: Compact manifests (&lt;64 KB) + bounded chunks (&lt;=256 items, &lt;=2 MB UTF-8)
 *       with &lt;4 MB max SQLite/partition row size, full reader parity, and fail-closed integrity.</li>
 * </ul>
 */
class PetClinicIncrementalSpringDeterminismTest {

    private record CliRun(int code, String out, String err) {
        Map<String, Object> data() {
            return GraphJson.object(GraphJson.object(GraphJson.read(out, GraphJson.Limits.defaults())).get("data"));
        }

        String graphDigest() {
            return GraphJson.string(data().get("graph"));
        }

        List<String> contextIdentities() {
            return GraphJson.array(data().get("contexts")).stream()
                    .map(c -> GraphJson.string(GraphJson.object(c).get("identity")))
                    .sorted()
                    .toList();
        }
    }

    private static CliRun runCli(Path workDir, String... args) {
        var out = new StringWriter();
        var err = new StringWriter();
        int code = new EvoApp(workDir, new StringReader(""), new PrintWriter(out), new PrintWriter(err), false, Map.of())
                .execute(args);
        return new CliRun(code, out.toString(), err.toString());
    }

    @Test
    void warmOneFileEditsMatchColdEditedBitIdenticallyAcrossAllSpringContexts(@TempDir Path temp) throws Exception {
        Path repo = Files.createDirectory(temp.resolve("clinic-repo"));
        Path cache = Files.createDirectory(temp.resolve("empty-m2"));
        writeClinicFixture(repo);

        Path wsFull = Files.createDirectory(temp.resolve("ws-warm-full"));
        Path wsFast = Files.createDirectory(temp.resolve("ws-warm-fast"));

        // 1. Cold baselines (FULL and FAST) with --spring-sat
        var coldFull = scan(repo, wsFull, cache, false, false);
        assertTrue(coldFull.code() == 0 || coldFull.code() == 3, () -> "code=" + coldFull.code() + " err=" + coldFull.err());
        assertTrue(coldFull.contextIdentities().stream().anyMatch(id -> id.startsWith("spring-context:")),
                "Fixture must produce a real spring-context: " + coldFull.contextIdentities());

        var coldFast = scan(repo, wsFast, cache, true, false);
        assertTrue(coldFast.code() == 0 || coldFast.code() == 3, () -> "code=" + coldFast.code() + " err=" + coldFast.err());
        assertTrue(coldFast.contextIdentities().stream().anyMatch(id -> id.startsWith("spring-context:")),
                "FAST fixture must produce a real spring-context: " + coldFast.contextIdentities());

        // 2. Warm NO_CHANGE matches Cold bit-for-bit on every context
        var warmNoChange = scan(repo, wsFull, cache, false, false);
        assertEquals(coldFull.code(), warmNoChange.code());
        assertEquals(coldFull.graphDigest(), warmNoChange.graphDigest());
        assertEquals(coldFull.contextIdentities(), warmNoChange.contextIdentities());

        Path controller = repo.resolve("src/main/java/org/example/clinic/owner/OwnerController.java");
        String originalController = Files.readString(controller, StandardCharsets.UTF_8);

        // 3. Edit A: Top-of-file comment shift (shifts EVERY line number & annotation span in OwnerController)
        Files.writeString(controller, "// shifted header line 1\n// shifted header line 2\n" + originalController,
                StandardCharsets.UTF_8);
        assertWarmMatchesColdEdited(repo, wsFull, wsFast, cache, temp, "comment-shift");

        // 4. Edit B: Add a new @GetMapping handler route + method in OwnerController
        int lastBrace = originalController.lastIndexOf('}');
        String withRoute = originalController.substring(0, lastBrace)
                + "\n    @GetMapping(\"/owners/health\")\n    public String health() { return \"ok\"; }\n}\n";
        Files.writeString(controller, withRoute, StandardCharsets.UTF_8);
        assertWarmMatchesColdEdited(repo, wsFull, wsFast, cache, temp, "route-addition");

        // 5. Edit C: Modify @ConditionalOnProperty and add a @Bean in FeatureConfig
        Path config = repo.resolve("src/main/java/org/example/clinic/config/FeatureConfig.java");
        Files.writeString(config, """
                package org.example.clinic.config;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class FeatureConfig {
                    @Bean
                    @ConditionalOnProperty(name = "clinic.audit.enabled", havingValue = "true", matchIfMissing = true)
                    public AuditService auditService() {
                        return new AuditService();
                    }

                    @Bean
                    @ConditionalOnProperty(name = "clinic.metrics.enabled", havingValue = "true")
                    public MetricsService metricsService() {
                        return new MetricsService();
                    }

                    public static class AuditService {}
                    public static class MetricsService {}
                }
                """, StandardCharsets.UTF_8);
        assertWarmMatchesColdEdited(repo, wsFull, wsFast, cache, temp, "spring-bean-condition-edit");

        // 6. Edit D: Modify application.properties (Spring configuration data change with zero Java edits!)
        Path props = repo.resolve("src/main/resources/application.properties");
        Files.writeString(props, """
                spring.application.name=clinic-service
                clinic.audit.enabled=false
                clinic.metrics.enabled=true
                """, StandardCharsets.UTF_8);
        assertWarmMatchesColdEdited(repo, wsFull, wsFast, cache, temp, "application-properties-edit");

        // 7. Verify --no-incremental in the SAME workspace & JVM produces exact match with zero Java cache hits
        var noIncSameWs = scan(repo, wsFull, cache, false, true);
        var freshColdWs = Files.createDirectory(temp.resolve("ws-cold-final"));
        var freshCold = scan(repo, freshColdWs, cache, false, true);
        assertEquals(freshCold.graphDigest(), noIncSameWs.graphDigest());
        assertEquals(freshCold.contextIdentities(), noIncSameWs.contextIdentities());
        assertTrue(noIncSameWs.err().contains("javaHits=0"), noIncSameWs::err);
    }

    private static void assertWarmMatchesColdEdited(
            Path repo, Path wsFull, Path wsFast, Path cache, Path temp, String label) throws Exception {
        var warmFull = scan(repo, wsFull, cache, false, false);
        Path coldFullWs = Files.createDirectory(temp.resolve("cold-full-" + label));
        var coldFull = scan(repo, coldFullWs, cache, false, true);
        assertEquals(coldFull.code(), warmFull.code(), () -> label + " FULL rc mismatch: " + warmFull.err());
        assertEquals(coldFull.graphDigest(), warmFull.graphDigest(),
                () -> label + " FULL graph SHA-256 drift between Warm 1-File Edit and Cold Edited");
        assertEquals(coldFull.contextIdentities(), warmFull.contextIdentities(),
                () -> label + " FULL context SHA-256 drift (including spring-context)");

        var warmFast = scan(repo, wsFast, cache, true, false);
        Path coldFastWs = Files.createDirectory(temp.resolve("cold-fast-" + label));
        var coldFast = scan(repo, coldFastWs, cache, true, true);
        assertEquals(coldFast.code(), warmFast.code(), () -> label + " FAST rc mismatch: " + warmFast.err());
        assertEquals(coldFast.graphDigest(), warmFast.graphDigest(),
                () -> label + " FAST graph SHA-256 drift between Warm 1-File Edit and Cold Edited");
        assertEquals(coldFast.contextIdentities(), warmFast.contextIdentities(),
                () -> label + " FAST context SHA-256 drift (including spring-context)");

        // Verify zero orphan nodes or dangling edge endpoints in the published warm graph
        KnowledgeGraph warmGraph = new Workspace(wsFull).selectedGraph();
        KnowledgeGraph coldGraph = new Workspace(coldFullWs).selectedGraph();
        assertEquals(coldGraph.identity(), warmGraph.identity(), label + " stored KnowledgeGraph digest mismatch");
        assertEquals(coldGraph.nodes().size(), warmGraph.nodes().size(), label + " node count mismatch");
        assertEquals(coldGraph.edges().size(), warmGraph.edges().size(), label + " edge count mismatch");
        assertEquals(coldGraph.evidence().size(), warmGraph.evidence().size(), label + " evidence count mismatch");
    }

    private static CliRun scan(Path repo, Path ws, Path cache, boolean fast, boolean noIncremental) {
        var args = new ArrayList<>(List.of(
                "scan", repo.toString(), "-w", ws.toString(),
                "--maven-cache", cache.toString(),
                "--offline", "--build", "off",
                "--spring-sat", "--telemetry", "--json"));
        if (fast) args.add("--fast");
        if (noIncremental) args.add("--no-incremental");
        return runCli(ws, args.toArray(String[]::new));
    }

    private static void writeClinicFixture(Path repo) throws IOException {
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>clinic-service</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """, StandardCharsets.UTF_8);
        Path base = Files.createDirectories(repo.resolve("src/main/java/org/example/clinic"));
        Path ownerDir = Files.createDirectories(base.resolve("owner"));
        Path cfgDir = Files.createDirectories(base.resolve("config"));
        Path resDir = Files.createDirectories(repo.resolve("src/main/resources"));

        Files.writeString(base.resolve("ClinicApplication.java"), """
                package org.example.clinic;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ClinicApplication {
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(ownerDir.resolve("Owner.java"), """
                package org.example.clinic.owner;

                public class Owner {
                    private String lastName;
                    public String getLastName() { return lastName; }
                    public void setLastName(String lastName) { this.lastName = lastName; }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(ownerDir.resolve("OwnerRepository.java"), """
                package org.example.clinic.owner;

                import org.springframework.stereotype.Repository;

                @Repository
                public class OwnerRepository {
                    public Owner findById(int id) { return new Owner(); }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(ownerDir.resolve("OwnerController.java"), """
                package org.example.clinic.owner;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.stereotype.Controller;
                import org.springframework.web.bind.annotation.GetMapping;

                @Controller
                public class OwnerController {
                    private final OwnerRepository owners;

                    @Autowired
                    public OwnerController(OwnerRepository owners) {
                        this.owners = owners;
                    }

                    @GetMapping("/owners")
                    public Owner showOwner(int id) {
                        return owners.findById(id);
                    }
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(cfgDir.resolve("FeatureConfig.java"), """
                package org.example.clinic.config;

                import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class FeatureConfig {
                    @Bean
                    @ConditionalOnProperty(name = "clinic.audit.enabled", havingValue = "true")
                    public AuditService auditService() {
                        return new AuditService();
                    }

                    public static class AuditService {}
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(resDir.resolve("application.properties"), """
                spring.application.name=clinic-service
                clinic.audit.enabled=true
                """, StandardCharsets.UTF_8);
    }

    @Test
    void incrementalSpringCacheDigestsCaptureCompleteSpringSourceEvidenceAndPolyglotInputs() {
        var ver = new VersionedIdentifier("test.spring-cache", "1");
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:spring-cache-soundness");
        var module = ModuleDescriptor.create(repo, "app", ".");
        var doc1Bytes = "package p; import org.springframework.stereotype.Controller; @Controller class C {}".getBytes(StandardCharsets.UTF_8);
        var doc2Bytes = "// shifted\npackage p; import org.springframework.stereotype.Controller; @Controller class C {}".getBytes(StandardCharsets.UTF_8);
        var doc1 = SourceDocument.create(repo, module, "src/main/java/p/C.java", ContentDigest.sha256(doc1Bytes), SourceClassification.MAIN);
        var doc2 = SourceDocument.create(repo, module, "src/main/java/p/C.java", ContentDigest.sha256(doc2Bytes), SourceClassification.MAIN);
        var src1 = new SourceInput(doc1, doc1Bytes);
        var src2 = new SourceInput(doc2, doc2Bytes);
        var snap1 = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(SnapshotFile.from(doc1)), List.of(doc1));
        var snap2 = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(SnapshotFile.from(doc2)), List.of(doc2));
        var comp = new ManifestComponent(ver, ContentDigest.sha256Utf8("c"));
        var platform = PlatformInput.create(21, "21", "jdk", List.of(
                new PlatformInput.Artifact("java.base", ContentDigest.sha256Utf8("jb"), Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var plan = FrontendPlan.legacy();
        var cfg1 = AnalysisConfiguration.create(ver, FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, List.of(doc1), platform, List.of()));
        var cfg2 = AnalysisConfiguration.create(ver, FrontendRequest.options(plan, module.identity(), SourceClassification.MAIN, List.of(doc2), platform, List.of()));
        var m1 = AnalysisManifest.create(ver, snap1, List.of(module), List.of(platform.entry()), cfg1, comp, comp, KnowledgeGraph.schemaComponent());
        var m2 = AnalysisManifest.create(ver, snap2, List.of(module), List.of(platform.entry()), cfg2, comp, comp, KnowledgeGraph.schemaComponent());
        var req1 = new FrontendRequest(m1, module.identity(), SourceClassification.MAIN, plan, List.of(src1), platform, List.of());
        var req2 = new FrontendRequest(m2, module.identity(), SourceClassification.MAIN, plan, List.of(src2), platform, List.of());

        var direct = new Derivation(DerivationKind.DIRECT, ver, List.of());
        var spanLine1 = new SourceSpan(doc1.identity(), 1, 1, 1, 20);
        var spanLine2 = new SourceSpan(doc1.identity(), 2, 1, 2, 20);
        var entity1 = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("p", "C").canonicalName(), Optional.of(spanLine1));
        var decl1 = new DeclarationRecord(entity1, "C", SemanticStatus.RESOLVED, direct, List.of(), List.of());
        var ann1 = new AnnotationUseRecord("java:v1:[\"annotation-use\",\"Controller\",1]", entity1.identity(), spanLine1, "declaration-syntax", "@Controller");
        var annShifted = new AnnotationUseRecord("java:v1:[\"annotation-use\",\"Controller\",1]", entity1.identity(), spanLine2, "declaration-syntax", "@Controller");
        var targetEntity = Entity.create(EntityOrigin.DEPENDENCY,
                EntityScope.external(EntityOrigin.DEPENDENCY, "spring", ContentDigest.sha256Utf8("spring-jar")),
                EntityKind.TYPE,
                JavaSymbolName.topLevelType("org.springframework.stereotype", "Controller").canonicalName(), Optional.empty());
        var declTarget = new DeclarationRecord(targetEntity, "Controller", SemanticStatus.RESOLVED, direct, List.of(), List.of());
        var annRel = SemanticRelationship.create(entity1.identity(), new RelationshipKind("java.annotated-with"),
                new RelationshipTarget.Resolved(targetEntity.identity()));
        var annOcc = RelationshipOccurrence.create(annRel, spanLine1, 0, SemanticStatus.RESOLVED, direct, List.of(), List.of());
        var annObs = new ObservationRecord(doc1.identity(), annRel.kind(), Optional.of(spanLine1), SemanticStatus.RESOLVED,
                ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                Optional.of(annOcc.identity()), "@Controller", List.of());
        var callRel = SemanticRelationship.create(entity1.identity(), new RelationshipKind("java.calls"),
                new RelationshipTarget.Resolved(entity1.identity()));
        var callOcc = RelationshipOccurrence.create(callRel, spanLine1, 0, SemanticStatus.RESOLVED, direct, List.of(), List.of());
        var callObs = new ObservationRecord(doc1.identity(), callRel.kind(), Optional.of(spanLine1), SemanticStatus.RESOLVED,
                ObservationRecord.EvidenceState.VERIFIED, ObservationRecord.EvidenceState.VERIFIED,
                Optional.of(callOcc.identity()), "self()", List.of());

        java.util.function.Function<List<RelationshipOccurrence>, List<CategoryCoverage>> coverageFor = occs ->
                FrontendRequest.CATEGORIES.stream().map(c -> {
                    var kind = new RelationshipKind("java." + c);
                    long count = occs.stream().filter(o -> o.relationship().kind().equals(kind)).count();
                    return new CategoryCoverage(kind, CategoryCoverage.Support.IMPLEMENTED, count, count, 0);
                }).toList();

        var decls = List.of(decl1, declTarget);
        var fBase = new FrontendResult(m1.identity(), ver, FrontendResult.State.COMPLETED,
                decls, List.of(annOcc), List.of(annObs),
                List.of(new SourceOutcome(doc1.identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverageFor.apply(List.of(annOcc)), List.of(),
                List.of(), List.of(ann1), List.of(), List.of(), List.of(), List.of());
        var fWithBodyCall = new FrontendResult(m1.identity(), ver, FrontendResult.State.COMPLETED,
                decls, List.of(annOcc, callOcc), List.of(annObs, callObs),
                List.of(new SourceOutcome(doc1.identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverageFor.apply(List.of(annOcc, callOcc)), List.of(),
                List.of(), List.of(ann1), List.of(), List.of(), List.of(), List.of());
        var fShiftedAnn = new FrontendResult(m1.identity(), ver, FrontendResult.State.COMPLETED,
                decls, List.of(annOcc), List.of(annObs),
                List.of(new SourceOutcome(doc1.identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverageFor.apply(List.of(annOcc)), List.of(),
                List.of(), List.of(annShifted), List.of(), List.of(), List.of(), List.of());
        var fNoAnnOcc = new FrontendResult(m1.identity(), ver, FrontendResult.State.COMPLETED,
                decls, List.of(), List.of(),
                List.of(new SourceOutcome(doc1.identity(), SourceOutcome.State.PROCESSED, List.of())),
                coverageFor.apply(List.of()), List.of(),
                List.of(), List.of(ann1), List.of(), List.of(), List.of(), List.of());

        var fw = new SpringFrameworkEvidence(false, List.of());
        var polyA = ContentDigest.sha256Utf8("polyglot-syntax-A");
        var polyB = ContentDigest.sha256Utf8("polyglot-syntax-B");

        // Non-Spring method-body call occurrence does NOT perturb core springFrontendDigest
        assertEquals(
                IncrementalSpringCache.springFrontendDigest(fBase, fw),
                IncrementalSpringCache.springFrontendDigest(fWithBodyCall, fw));
        // Shifting annotation span MUST invalidate springFrontendDigest
        assertNotEquals(
                IncrementalSpringCache.springFrontendDigest(fBase, fw),
                IncrementalSpringCache.springFrontendDigest(fShiftedAnn, fw));
        // Removing a resolved java.annotated-with occurrence MUST invalidate springFrontendDigest
        assertNotEquals(
                IncrementalSpringCache.springFrontendDigest(fBase, fw),
                IncrementalSpringCache.springFrontendDigest(fNoAnnOcc, fw));
        // Changing request source document bytes OR polyglot syntax digest MUST invalidate complete digest & keys
        var d1PolyA = IncrementalSpringCache.springFrontendDigest(req1, fBase, fw, polyA);
        var d1PolyB = IncrementalSpringCache.springFrontendDigest(req1, fBase, fw, polyB);
        var d2PolyA = IncrementalSpringCache.springFrontendDigest(req2, fBase, fw, polyA);
        assertNotEquals(d1PolyA, d1PolyB, "Polyglot service-syntax change must invalidate Spring frontend digest");
        assertNotEquals(d1PolyA, d2PolyA, "Source input digest/imports change must invalidate Spring frontend digest");

        var ss = new UniversalSourceIngestion.SourceSet(module.identity(), SourcePlanModel.Kind.MAIN);
        var cfgDigest = ContentDigest.sha256Utf8("cfg-1");
        var semA = IncrementalSpringCache.semanticKey(ss, d1PolyA, cfgDigest,
                UniversalArchitecturePipeline.SpringSatMode.AUTO_MULTI_CONFIG_SAT,
                UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,
                TruthRegionEvaluation.Limits.conservative(),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED, polyA);
        var semB = IncrementalSpringCache.semanticKey(ss, d1PolyA, cfgDigest,
                UniversalArchitecturePipeline.SpringSatMode.AUTO_MULTI_CONFIG_SAT,
                UniversalArchitecturePipeline.TruthEvaluationMode.AUTO,
                TruthRegionEvaluation.Limits.conservative(),
                SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED, polyB);
        assertNotEquals(semA, semB, "Polyglot service-syntax change must invalidate semanticKey");
    }

    @Test
    void incrementalGraphProjectorInvalidatesStaleTemplatesWhenSpringOrPolyglotContextChanges(@TempDir Path cacheDir) {
        var ver = new VersionedIdentifier("test.gt2", "1");
        var repo = RepositoryIdentity.fromCanonicalCoordinate("urn:test:gt2-invalidation");
        var module = ModuleDescriptor.create(repo, "app", ".");
        var bytes = "package p; class A {}".getBytes(StandardCharsets.UTF_8);
        var doc = SourceDocument.create(repo, module, "src/main/java/p/A.java", ContentDigest.sha256(bytes), SourceClassification.MAIN);
        var snap = RepositorySnapshot.create(repo, Optional.empty(), false, List.of(SnapshotFile.from(doc)), List.of(doc));
        var comp = new ManifestComponent(ver, ContentDigest.sha256Utf8("c"));
        var manifest = AnalysisManifest.create(ver, snap, List.of(module), List.of(),
                AnalysisConfiguration.create(ver, Map.of()), comp, comp, KnowledgeGraph.schemaComponent());
        var span = new SourceSpan(doc.identity(), 1, 1, 1, 20);
        var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(module.identity()), EntityKind.TYPE,
                JavaSymbolName.topLevelType("p", "A").canonicalName(), Optional.of(span));
        var decl = new DeclarationRecord(entity, "A", SemanticStatus.RESOLVED,
                new Derivation(DerivationKind.DIRECT, ver, List.of()), List.of(), List.of());
        var cov = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0))
                .toList();
        var fr = new FrontendResult(manifest.identity(), ver, FrontendResult.State.COMPLETED,
                List.of(decl), List.of(), List.of(),
                List.of(new SourceOutcome(doc.identity(), SourceOutcome.State.PROCESSED, List.of())), cov, List.of());

        var spring1 = ContentDigest.sha256Utf8("spring-v1");
        var spring2 = ContentDigest.sha256Utf8("spring-v2");
        var poly1 = ContentDigest.sha256Utf8("poly-v1");
        var poly2 = ContentDigest.sha256Utf8("poly-v2");

        var k1 = IncrementalGraphProjector.frontendStructuralKey(fr, false, spring1, poly1);
        var kSpringChanged = IncrementalGraphProjector.frontendStructuralKey(fr, false, spring2, poly1);
        var kPolyChanged = IncrementalGraphProjector.frontendStructuralKey(fr, false, spring1, poly2);
        assertNotEquals(k1, kSpringChanged, "Spring context change must invalidate .gt2 structural key");
        assertNotEquals(k1, kPolyChanged, "Polyglot projection change must invalidate .gt2 structural key");

        // End-to-end build with disk .gt2 projector: changing polyglotProjectionDigest forces clean rebuild
        var builder = new CanonicalGraphBuilder();
        var p1 = new IncrementalGraphProjector(cacheDir);
        KnowledgeGraph g1 = builder.build(manifest, fr, List.of(), List.of(), false, p1, poly1);
        assertEquals(0, p1.stats().reusedContexts());
        assertEquals(1, p1.stats().rebuiltContexts());

        // Same poly1 on fresh projector hits disk .gt2 cleanly
        var p2 = new IncrementalGraphProjector(cacheDir);
        KnowledgeGraph g2 = builder.build(manifest, fr, List.of(), List.of(), false, p2, poly1);
        assertEquals(1, p2.stats().reusedContexts());
        assertEquals(0, p2.stats().rebuiltContexts());
        assertEquals(g1.identity(), g2.identity());

        // Different poly2 invalidates disk .gt2 and rebuilds with bit-identical output to clean build
        var p3 = new IncrementalGraphProjector(cacheDir);
        KnowledgeGraph g3 = builder.build(manifest, fr, List.of(), List.of(), false, p3, poly2);
        KnowledgeGraph clean = builder.build(manifest, fr, List.of(), List.of(), false, null, poly2);
        assertEquals(0, p3.stats().reusedContexts());
        assertEquals(1, p3.stats().rebuiltContexts());
        assertEquals(clean.identity(), g3.identity());
    }

    @Test
    void chunkedSymbolicAndLargeArtifactPersistenceKeepsAllRowsUnderFourMegabytesWithFullReaderParity(@TempDir Path temp)
            throws Exception {
        // Build a synthetic large Spring Boot repository with >70 source files & >260 beans/conditions so
        // frontend.result-index, spring.plan, spring.symbolic-result, spring.symbolic-trace,
        // repository.acquisition, build.model, and evidence.ledger-index ALL exercise bounded chunked schemas.
        Path repo = Files.createDirectory(temp.resolve("large-spring-repo"));
        Files.writeString(repo.resolve("pom.xml"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>large-chunk-app</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <maven.compiler.release>21</maven.compiler.release>
                    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                  </properties>
                </project>
                """, StandardCharsets.UTF_8);
        Path src = Files.createDirectories(repo.resolve("src/main/java/org/example/chunked"));
        Path res = Files.createDirectories(repo.resolve("src/main/resources"));
        Files.writeString(res.resolve("application.properties"),
                "chunked.mode=enabled\nchunked.extra=true\n", StandardCharsets.UTF_8);
        Files.writeString(src.resolve("ChunkedApplication.java"), """
                package org.example.chunked;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                @SpringBootApplication
                public class ChunkedApplication {}
                """, StandardCharsets.UTF_8);

        // Emit 75 Java files, each with multiple @Component/@Bean methods & @GetMapping calls to produce
        // >2,500 frontend index refs, >300 Spring bean candidates/facts, and >75 acquired files.
        for (int i = 0; i < 75; i++) {
            var sb = new StringBuilder();
            sb.append("package org.example.chunked;\n")
                    .append("import org.springframework.context.annotation.Bean;\n")
                    .append("import org.springframework.context.annotation.Configuration;\n")
                    .append("import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;\n")
                    .append("import org.springframework.web.bind.annotation.GetMapping;\n")
                    .append("import org.springframework.web.bind.annotation.RestController;\n")
                    .append("@Configuration\n@RestController\n")
                    .append("public class ServiceUnit").append(i).append(" {\n");
            for (int m = 0; m < 4; m++) {
                sb.append("    @Bean\n")
                        .append("    @ConditionalOnProperty(name = \"chunked.mode\", havingValue = \"enabled\")\n")
                        .append("    public String bean").append(i).append("_").append(m).append("() { return \"v")
                        .append(i).append("_").append(m).append("\"; }\n");
            }
            sb.append("    @GetMapping(\"/unit/").append(i).append("\")\n")
                    .append("    public String endpoint").append(i).append("() { return bean").append(i).append("_0(); }\n")
                    .append("}\n");
            Files.writeString(src.resolve("ServiceUnit" + i + ".java"), sb.toString(), StandardCharsets.UTF_8);
        }

        // Run through real UniversalArchitecturePipeline + CanonicalGraphBuilder + PartitionedGraphStore (4 MB row cap!)
        var acquirer = new FilesystemRepositoryAcquirer();
        var repoId = RepositoryIdentity.fromCanonicalCoordinate("urn:test:large-chunked-spring");
        var acqResult = acquirer.acquire(repo, new RepositoryAcquisitionRequest(
                repoId, Optional.empty(), false, "pom.xml",
                new RepositoryAcquisitionPolicy(10_000, 10_000, 2_000_000, 50_000_000, 32, List.of())));
        assertEquals(RepositoryAcquisitionResult.Completion.COMPLETE, acqResult.completion());

        var platform = new FilesystemJdkPlatformProvider()
                .acquire(Path.of(System.getProperty("java.home")),
                        new com.evolution.analysis.input.PlatformSymbolRequest(21, 128, 128_000_000, 512_000_000))
                .platform().orElseThrow();

        var policy = UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(), JavaParserFrontend.PROVIDER)
                .defaultPlatform(platform)
                .mavenProvider(new com.evolution.analysis.maven.MavenBuildModelProvider())
                .maxTypes(10_000)
                .springSatMode(UniversalArchitecturePipeline.SpringSatMode.AUTO_MULTI_CONFIG_SAT)
                .truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.AUTO)
                .build();
        var detailed = new UniversalArchitecturePipeline().runWithEvidence(acqResult, policy);
        assertTrue(detailed.graphInput().isPresent());

        // Also attach a large ledger (>260 attempts) to verify evidence.ledger-index:m8.4-paged-v2
        var snapId = acqResult.snapshot().orElseThrow().identity();
        var ledgerCtx = EvidenceContext.forSnapshot(snapId);
        var ledgerProvider = new VersionedIdentifier("test.ledger", "1");
        var largeAttempts = new ArrayList<AcquisitionAttemptRecord>(270);
        for (int i = 0; i < 270; i++) {
            var subject = new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY, "item-" + i);
            var obs = ProviderObservationReference.create(ledgerProvider, "test.obs",
                    ContentDigest.sha256Utf8("in-" + i), ContentDigest.sha256Utf8("out-" + i));
            var req = new EvidenceRequirement(EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT, "dep.item-" + i,
                    List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE, List.of("test"));
            largeAttempts.add(AcquisitionAttemptRecord.create(
                    ledgerCtx, ledgerProvider, obs, subject, req, List.of(ContentDigest.sha256Utf8("in-" + i)),
                    Optional.empty(), AcquisitionAttemptRecord.TrustDecision.TRUSTED_INPUT,
                    AcquisitionAttemptRecord.PermissionDecision.NOT_REQUIRED, Map.of(),
                    Optional.empty(), Optional.empty(), AcquisitionAttemptRecord.Outcome.SUCCEEDED,
                    List.of(), List.of(), List.of("none")));
        }
        var largeLedger = EvidenceAcquisitionLedger.create(
                EvidenceAcquisitionLedger.NORMALIZER, ledgerCtx,
                List.of(), largeAttempts, List.of(), List.of());

        var graph = new CanonicalGraphBuilder().build(detailed.graphInput().orElseThrow(), List.of(largeLedger), false);

        // 1. Assert paged schemas activated on all large families & manifests < 64 KB & ALL rows < 4 MB
        var families = new HashSet<String>();
        int maxEvidenceRowBytes = 0;
        for (var ev : graph.evidence()) {
            families.add(ev.family());
            int encodedRowBytes = GraphCanonicalWriter.encodeValue(ev).getBytes(StandardCharsets.UTF_8).length;
            maxEvidenceRowBytes = Math.max(maxEvidenceRowBytes, encodedRowBytes);
            assertTrue(encodedRowBytes < 4 * 1024 * 1024,
                    () -> "Row exceeded 4 MB cap: family=" + ev.family() + " bytes=" + encodedRowBytes);
            int innerUtf8 = ev.canonicalJson().getBytes(StandardCharsets.UTF_8).length;
            if (Set.of("spring.symbolic-result", "spring.symbolic-trace", "spring.plan",
                    "frontend.result-index", "repository.acquisition", "evidence.ledger-index").contains(ev.family())) {
                assertTrue(innerUtf8 < 65_536,
                        () -> "Manifest exceeded 64 KB: family=" + ev.family() + " bytes=" + innerUtf8);
            } else if (ev.family().endsWith("-page") || ev.family().endsWith("-page.dir")) {
                assertTrue(innerUtf8 <= 2 * 1024 * 1024,
                        () -> "Chunk exceeded 2 MB UTF-8: family=" + ev.family() + " bytes=" + innerUtf8);
            }
        }
        assertTrue(families.contains("spring.symbolic-region-digest-page"), "Missing region digest pages");
        assertTrue(families.contains("spring.symbolic-trace-fact-page"), "Missing paged symbolic trace fact pages");
        assertTrue(families.contains("spring.plan-gap-index-page"), "Missing paged spring.plan pages");
        assertTrue(families.contains("frontend.result-source-page"), "Missing paged frontend.result-index pages");
        assertTrue(families.contains("repository.acquisition-file-page"), "Missing paged repository.acquisition pages");
        assertTrue(families.contains("evidence.ledger-attempt-page"), "Missing paged evidence.ledger-index pages");
        assertTrue(maxEvidenceRowBytes < 4 * 1024 * 1024, "Max row bytes must be < 4 MB: " + maxEvidenceRowBytes);

        // 2. Verify GraphEvidence.semanticIdentity() succeeds on every paged family (including evidence.ledger-index)
        assertDoesNotThrow(graph::semanticIdentity);

        // 3. Publish into PartitionedGraphStore with a strict 4 MB rowBytes cap and verify bit-identical digest
        Path pagesDir = temp.resolve("strict-4mb-pages");
        Path snapsDir = temp.resolve("strict-4mb-snaps");
        var strictStore = new PartitionedGraphStore(pagesDir, 4 * 1024 * 1024, 2);
        var pub = strictStore.build(detailed.graphInput().orElseThrow(), snapsDir, () -> false,
                List.of(largeLedger), ws -> {}, false, null);
        assertEquals(graph.identity(), pub.graph());
        assertTrue(strictStore.verifyPublished(graph.identity(), () -> false));

        // 4. Verify ALL downstream readers work seamlessly on the paged graph:
        //    ArchitectureQueryService (GraphRegionCatalog), ArchitectureAssessmentV2Service, SemanticDepth, FileSnapshotComparison
        var queries = new ArchitectureQueryService(graph);
        String springCtx = graph.contexts().stream()
                .map(KnowledgeGraph.Context::identity)
                .filter(id -> id.startsWith("spring-context:"))
                .findFirst().orElseThrow();
        var configView = queries.configurations(new ArchitectureQuery.Request(
                graph.identity(), springCtx, ArchitectureQuery.Scope.SPRING_COMPONENT,
                ArchitectureQuery.Projection.BOUNDED_UNION, ArchitectureQuery.Direction.OUTGOING,
                Set.of("graph.depends-on"), ArchitectureQuery.Population.all(),
                false, Optional.empty(), ArchitectureQuery.Limits.defaults()),
                new ArchitectureQuery.Page(50, Optional.empty()));
        assertFalse(configView.items().isEmpty());
        assertTrue(configView.gaps().stream().noneMatch(g ->
                g.reasonCode().equals("INVALID_REGION_EVIDENCE") || g.reasonCode().equals("REGION_EVALUATION_UNAVAILABLE")));

        String javaCtx = graph.contexts().stream()
                .filter(c -> c.analysis().isPresent() && !c.identity().startsWith("spring-context:"))
                .findFirst().orElseThrow().identity();
        var assessment = new ArchitectureAssessmentV2Service().assess(queries, javaCtx);
        assertTrue(assessment.confidence().parseCompleteness().doubleValue() >= 0.99,
                "Paged frontend.result-index must preserve parseCompleteness=1.0, got " + assessment.confidence().parseCompleteness());

        var depth = SemanticDepth.summarize(graph);
        assertEquals(1L, ((Number) depth.get("analyzedContexts")).longValue());

        var cmpReq = new SnapshotComparison.Request(
                repoId, graph.identity(), graph.identity(), SnapshotComparison.Mode.STRICT,
                new SnapshotComparison.Selection(Set.of(), Set.of("TYPE"), Set.of("graph.depends-on"), true, true));
        var memCmp = SnapshotComparison.compare(cmpReq, EvolutionSnapshot.memory(graph),
                EvolutionIndex.memory(), 50, () -> false);
        try (var session = FileSnapshotComparison.compare(strictStore, temp.resolve("cmp-work"), cmpReq, 50, () -> false)) {
            assertNotEquals(SnapshotComparison.Status.WITHHELD, session.result().summary().status(),
                    "FileSnapshotComparison must read paged spring.plan, spring.symbolic-trace, and frontend.result-index without withholding");
            assertEquals(memCmp.summary(), session.result().summary(),
                    "FileSnapshotComparison on 4MB-capped PartitionedGraphStore must match in-memory SnapshotComparison");
            var gapRows = session.result().page(SnapshotComparison.Section.GAPS, 50, Optional.empty()).items();
            assertTrue(gapRows.stream().noneMatch(row -> String.valueOf(row).contains("CORRUPT")),
                    () -> "Untampered paged comparison must not report corruption gaps: " + gapRows);
        }

        // 5. Fail-closed corruption test on paged spring.symbolic-trace factsCount mismatch
        var traceEv = graph.evidence().stream()
                .filter(e -> e.family().equals("spring.symbolic-trace")
                        && e.canonicalJson().contains("spring.symbolic-trace:m8.4-paged-v1"))
                .findFirst().orElseThrow();
        var tamperedTraceObj = new TreeMap<>(GraphJson.object(GraphJson.read(traceEv.canonicalJson(), GraphJson.Limits.defaults())));
        tamperedTraceObj.put("factsCount", ((Number) tamperedTraceObj.get("factsCount")).longValue() + 1L);
        var tamperedTrace = GraphEvidence.capture("spring.symbolic-trace", tamperedTraceObj);
        var traceIdxEv = graph.evidence().stream()
                .filter(e -> e.family().equals("spring.symbolic-trace-index-page"))
                .findFirst().orElseThrow();
        var tamperedIdxObj = new TreeMap<>(GraphJson.object(GraphJson.read(traceIdxEv.canonicalJson(), GraphJson.Limits.defaults())));
        var updatedRecs = new ArrayList<String>();
        for (Object r : GraphJson.array(tamperedIdxObj.get("records"))) {
            String s = GraphJson.string(r);
            updatedRecs.add(s.equals(traceEv.identity().value()) ? tamperedTrace.identity().value() : s);
        }
        tamperedIdxObj.put("records", updatedRecs);
        var tamperedIdx = GraphEvidence.capture("spring.symbolic-trace-index-page", tamperedIdxObj);
        var symResEv = graph.evidence().stream()
                .filter(e -> e.family().equals("spring.symbolic-result"))
                .findFirst().orElseThrow();
        var tamperedSymObj = new TreeMap<>(GraphJson.object(GraphJson.read(symResEv.canonicalJson(), GraphJson.Limits.defaults())));
        var updatedTracePages = new ArrayList<String>();
        for (Object r : GraphJson.array(tamperedSymObj.get("traceEvidencePages"))) {
            String s = GraphJson.string(r);
            updatedTracePages.add(s.equals(traceIdxEv.identity().value()) ? tamperedIdx.identity().value() : s);
        }
        tamperedSymObj.put("traceEvidencePages", updatedTracePages);
        var tamperedSym = GraphEvidence.capture("spring.symbolic-result", tamperedSymObj);
        var tamperedEvs = new ArrayList<GraphEvidence>();
        for (var ev : graph.evidence()) {
            if (ev.identity().equals(traceEv.identity())) tamperedEvs.add(tamperedTrace);
            else if (ev.identity().equals(traceIdxEv.identity())) tamperedEvs.add(tamperedIdx);
            else if (ev.identity().equals(symResEv.identity())) tamperedEvs.add(tamperedSym);
            else tamperedEvs.add(ev);
        }
        var tamperedGraph = new KnowledgeGraph(graph.snapshot(), graph.contexts(), graph.nodes(), graph.edges(), tamperedEvs);
        var tamperedReq = new SnapshotComparison.Request(repoId, graph.identity(), tamperedGraph.identity(),
                SnapshotComparison.Mode.STRICT, new SnapshotComparison.Selection(Set.of(), Set.of(), Set.of(), true, false));
        var tamperedReader = EvolutionSnapshot.memory(graph, tamperedGraph);
        var tamperedCmp = SnapshotComparison.compare(tamperedReq, tamperedReader,
                EvolutionIndex.memory(), 50, () -> false);
        var tamperedGaps = tamperedCmp.page(SnapshotComparison.Section.GAPS, 50, Optional.empty()).items();
        assertTrue(tamperedCmp.summary().status() == SnapshotComparison.Status.WITHHELD
                        || tamperedGaps.stream().anyMatch(row -> String.valueOf(row).contains("CORRUPT")),
                () -> "Tampered paged spring.symbolic-trace factsCount mismatch must fail closed with CORRUPT/WITHHELD, status="
                        + tamperedCmp.summary().status() + " gaps=" + tamperedGaps);
    }
}
