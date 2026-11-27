package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalReadinessAndGraphDeltaTest {
    private static final VersionedIdentifier VER = new VersionedIdentifier("test.delta", "1");
    private static final RepositoryIdentity REPO = RepositoryIdentity.fromCanonicalCoordinate("urn:test:readiness-graph-delta");

    @Test
    void durabilityClassifierSeparatesD3D2D1D0Correctly() {
        assertEquals(ReadinessDelta.DurabilityClass.D3_BUILD, ReadinessDelta.classifyPath("pom.xml"));
        assertEquals(ReadinessDelta.DurabilityClass.D3_BUILD, ReadinessDelta.classifyPath("app/build.gradle.kts"));
        assertEquals(ReadinessDelta.DurabilityClass.D3_BUILD, ReadinessDelta.classifyPath("lombok.config"));
        assertEquals(ReadinessDelta.DurabilityClass.D3_BUILD, ReadinessDelta.classifyPath("src/main/java/module-info.java"));
        assertEquals(ReadinessDelta.DurabilityClass.D2_SPRING_CONFIG, ReadinessDelta.classifyPath("src/main/resources/application.yml"));
        assertEquals(ReadinessDelta.DurabilityClass.D2_SPRING_CONFIG, ReadinessDelta.classifyPath("src/main/resources/application-prod.properties"));
        assertEquals(ReadinessDelta.DurabilityClass.D2_SPRING_CONFIG, ReadinessDelta.classifyPath("src/main/resources/META-INF/spring.factories"));
        assertEquals(ReadinessDelta.DurabilityClass.D1_JAVA_SOURCE, ReadinessDelta.classifyPath("src/main/java/com/acme/App.java"));
        assertEquals(ReadinessDelta.DurabilityClass.D1_JAVA_SOURCE, ReadinessDelta.classifyPath("src/main/kotlin/com/acme/App.kt"));
        assertEquals(ReadinessDelta.DurabilityClass.D0_POLYGLOT_OTHER, ReadinessDelta.classifyPath("web/src/client.ts"));
        assertEquals(ReadinessDelta.DurabilityClass.D0_POLYGLOT_OTHER, ReadinessDelta.classifyPath("README.md"));
    }

    @Test
    void readinessDeltaFastRebindMatchesFreshReadinessPlanBitForBit() throws Exception {
        var cas = new MemContentStore();
        var invA = new MemInventory();
        var invB = new MemInventory();
        byte[] pom = "<project><modelVersion>4.0.0</modelVersion><groupId>t</groupId><artifactId>app</artifactId><version>1.0</version></project>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] javaV1 = "package t; class App { int x() { return 1; } }".getBytes(StandardCharsets.UTF_8);
        byte[] javaV2 = "package t; class App { int x() { return 999; } }".getBytes(StandardCharsets.UTF_8);
        byte[] ts = "export const ok = true;\n".getBytes(StandardCharsets.UTF_8);

        ContentHandle hPom = cas.put(new ByteArrayInputStream(pom), () -> false);
        ContentHandle hJ1 = cas.put(new ByteArrayInputStream(javaV1), () -> false);
        ContentHandle hJ2 = cas.put(new ByteArrayInputStream(javaV2), () -> false);
        ContentHandle hTs = cas.put(new ByteArrayInputStream(ts), () -> false);

        ContentDigest sel = ContentDigest.sha256Utf8("sel");
        invA.seal("a", REPO, sel, List.of(
                fileEntry("pom.xml", hPom),
                fileEntry("src/main/java/t/App.java", hJ1),
                fileEntry("web/app.ts", hTs)));
        invB.seal("b", REPO, sel, List.of(
                fileEntry("pom.xml", hPom),
                fileEntry("src/main/java/t/App.java", hJ2),
                fileEntry("web/app.ts", hTs)));

        var planner = new ReadinessPlanner(List.of(new PassiveReadinessDiscovery()));
        var policy = ReadinessPlanner.Policy.comprehensive();
        ReadinessPlan planA = planner.plan("a", invA, cas, policy, () -> false);
        ReadinessPlan freshPlanB = planner.plan("b", invB, cas, policy, () -> false);

        // D1 .java modification only -> fast rebind MUST be allowed and bit-for-bit identical!
        assertTrue(ReadinessDelta.canFastRebind(planA, invB.manifest("b").orElseThrow(),
                List.of(), List.of("src/main/java/t/App.java"), List.of()));
        ReadinessPlan reboundPlanB = ReadinessDelta.rebindPlan(planA, invB.manifest("b").orElseThrow());
        assertEquals(freshPlanB.identity(), reboundPlanB.identity());
        assertEquals(freshPlanB, reboundPlanB);

        // D3 pom.xml modification or added/deleted files MUST reject fast rebind conservatively!
        assertFalse(ReadinessDelta.canFastRebind(planA, invB.manifest("b").orElseThrow(),
                List.of(), List.of("pom.xml"), List.of()));
        assertFalse(ReadinessDelta.canFastRebind(planA, invB.manifest("b").orElseThrow(),
                List.of("src/main/java/t/New.java"), List.of(), List.of()));
        assertFalse(ReadinessDelta.canFastRebind(planA, invB.manifest("b").orElseThrow(),
                List.of(), List.of("src/main/java/module-info.java"), List.of()));
    }

    @Test
    void incrementalGraphProjectorProducesBitForBitIdenticalGraphAcrossSnapshots() {
        var projector = new IncrementalGraphProjector();
        var builder = new CanonicalGraphBuilder();

        // Snapshot A: mod-a, mod-b, mod-c
        var inputA = syntheticMultiModuleGraphInput("package a; class App { int x() { return 1; } }");
        KnowledgeGraph graphA = builder.build(inputA, List.of(), false, projector);
        assertEquals(0, projector.stats().reusedContexts());
        assertEquals(3, projector.stats().rebuiltContexts());

        // Snapshot B: mod-a body-only edit with identical FrontendResult -> all 3 contexts reuse template!
        projector.resetStats();
        var inputBBody = syntheticMultiModuleGraphInput("package a; class App { int x() { return 42; } }", 20);
        KnowledgeGraph incBodyB = builder.build(inputBBody, List.of(), false, projector);
        KnowledgeGraph cleanBodyB = builder.build(inputBBody, List.of(), false, null);
        assertEquals(3, projector.stats().reusedContexts());
        assertEquals(0, projector.stats().rebuiltContexts());
        assertEquals(cleanBodyB.identity(), incBodyB.identity());

        // Snapshot C: mod-a declaration span changes -> mod-b & mod-c reuse templates (2), only mod-a rebuilds (1)!
        projector.resetStats();
        var inputC = syntheticMultiModuleGraphInput("package a; class App { int x() { return 42; } int y() { return 7; } }", 35);
        KnowledgeGraph incrementalGraphC = builder.build(inputC, List.of(), false, projector);
        KnowledgeGraph cleanGraphC = builder.build(inputC, List.of(), false, null);

        assertEquals(2, projector.stats().reusedContexts(), "Unchanged mod-b and mod-c must reuse graph templates");
        assertEquals(1, projector.stats().rebuiltContexts(), "Only modified mod-a should rebuild graph template");
        assertTrue(projector.stats().reusedSourceContents() >= 2, "Unchanged source files must reuse source.content Base64 evidence");
        assertEquals(cleanGraphC.identity(), incrementalGraphC.identity());
        assertEquals(cleanGraphC.nodes().size(), incrementalGraphC.nodes().size());
        assertEquals(cleanGraphC.edges().size(), incrementalGraphC.edges().size());
        assertEquals(cleanGraphC.evidence().size(), incrementalGraphC.evidence().size());
        assertNotEquals(graphA.identity(), incrementalGraphC.identity());
    }

    @Test
    void diskSpillHeuristicTriggersAtLoweredBounds() {
        assertFalse(UniversalArchitecturePipeline.shouldUseDiskSpill(15_999_999L, 399, 7));
        assertTrue(UniversalArchitecturePipeline.shouldUseDiskSpill(16_000_000L, 10, 1));
        assertTrue(UniversalArchitecturePipeline.shouldUseDiskSpill(1_000L, 400, 1));
        assertTrue(UniversalArchitecturePipeline.shouldUseDiskSpill(1_000L, 10, 8));
    }

    private static CanonicalGraphInput syntheticMultiModuleGraphInput(String modACode) {
        return syntheticMultiModuleGraphInput(modACode, 20);
    }

    private static CanonicalGraphInput syntheticMultiModuleGraphInput(String modACode, int modAEndCol) {
        var modA = ModuleDescriptor.create(REPO, "mod-a", "mod-a");
        var modB = ModuleDescriptor.create(REPO, "mod-b", "mod-b");
        var modC = ModuleDescriptor.create(REPO, "mod-c", "mod-c");
        var rootMod = ModuleDescriptor.create(REPO, "root", ".");
        var entries = new LinkedHashMap<String, String>();
        entries.put("mod-a/src/main/java/a/App.java", modACode);
        entries.put("mod-b/src/main/java/b/Lib.java", "package b; public class Lib {}");
        entries.put("mod-c/src/main/java/c/Util.java", "package c; public class Util {}");

        var rawFiles = new TreeMap<String, SourceInput>();
        var snapshotFiles = new ArrayList<SnapshotFile>();
        var rawDocs = new ArrayList<SourceDocument>();
        for (var e : entries.entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var doc = SourceDocument.create(REPO, rootMod, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN);
            rawFiles.put(e.getKey(), new SourceInput(doc, bytes));
            rawDocs.add(doc);
            snapshotFiles.add(SnapshotFile.from(doc));
        }
        var rawInputs = new RepositoryInputs(
                RepositorySnapshot.create(REPO, Optional.empty(), false, snapshotFiles, rawDocs), rawFiles);
        var buildModules = new ArrayList<UniversalBuildModel.Module>();
        for (var m : List.of(modA, modB, modC)) {
            var java21 = new SourcePlanModel.Setting("java.release", Optional.of("21"), Optional.of("21"),
                    SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var utf8 = new SourcePlanModel.Setting("encoding", Optional.of("UTF-8"), Optional.of("UTF-8"),
                    SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var mainRoot = new SourcePlanModel.Setting("sourceRoot", Optional.of(m.path() + "/src/main/java"),
                    Optional.of(m.path() + "/src/main/java"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var testRoot = new SourcePlanModel.Setting("testRoot", Optional.of(m.path() + "/src/test/java"),
                    Optional.of(m.path() + "/src/test/java"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var outDir = new SourcePlanModel.Setting("output", Optional.of(m.path() + "/target/classes"),
                    Optional.of(m.path() + "/target/classes"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var testOutDir = new SourcePlanModel.Setting("testOutput", Optional.of(m.path() + "/target/test-classes"),
                    Optional.of(m.path() + "/target/test-classes"), SourcePlanModel.Status.DEFAULT, SourcePlanModel.Origin.MAVEN_CONVENTION, List.of());
            var mainSet = new SourcePlanModel.SourceSetPlan(m.identity(), SourcePlanModel.Kind.MAIN,
                    List.of(mainRoot), List.of(), outDir, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
            var testSet = new SourcePlanModel.SourceSetPlan(m.identity(), SourcePlanModel.Kind.TEST,
                    List.of(testRoot), List.of(), testOutDir, Map.of(), java21, java21, java21, utf8, List.of(), List.of());
            buildModules.add(new UniversalBuildModel.Module(m, UniversalBuildModel.Tool.PLAIN_JAVA, Optional.empty(),
                    Optional.empty(), new SourcePlanModel(List.of(mainSet, testSet), List.of()), List.of(), List.of()));
        }
        var build = new UniversalBuildModel(ContentDigest.sha256Utf8("b"), rawInputs.identity(), buildModules, List.of(), List.of(), List.of());
        var platform = PlatformInput.create(21, "21", "jdk", List.of(
                new PlatformInput.Artifact("java.base", ContentDigest.sha256Utf8("jb"), java.nio.file.Path.of("java.base.jmod"), PlatformInput.Format.JMOD)));
        var resolutions = Map.of(
                new UniversalSourceIngestion.SourceSet(modA.identity(), SourcePlanModel.Kind.MAIN),
                new UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()),
                new UniversalSourceIngestion.SourceSet(modB.identity(), SourcePlanModel.Kind.MAIN),
                new UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()),
                new UniversalSourceIngestion.SourceSet(modC.identity(), SourcePlanModel.Kind.MAIN),
                new UniversalSourceIngestion.Resolution(platform, List.of(), platform.entry().contentDigest()));
        var comp = new ManifestComponent(VER, ContentDigest.sha256Utf8("c"));
        var sourceIngestion = new UniversalSourceIngestion().assemble(rawInputs, build, resolutions, comp, comp, KnowledgeGraph.schemaComponent());
        var direct = new Derivation(DerivationKind.DIRECT, VER, List.of());
        var coverage = FrontendRequest.CATEGORIES.stream()
                .map(c -> new CategoryCoverage(new RelationshipKind("java." + c), CategoryCoverage.Support.IMPLEMENTED, 0, 0, 0)).toList();
        var units = new ArrayList<UniversalIngestionPipeline.Unit>();
        for (var outcome : sourceIngestion.outcomes()) {
            if (outcome.sourceSet().kind() != SourcePlanModel.Kind.MAIN) {
                units.add(new UniversalIngestionPipeline.Unit(outcome.sourceSet(), UniversalIngestionPipeline.Status.NOT_ANALYZED,
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
                continue;
            }
            var req = outcome.request().orElseThrow();
            var src = req.sources().getFirst();
            String pkg = req.module().equals(modA.identity()) ? "a" : req.module().equals(modB.identity()) ? "b" : "c";
            String simple = src.document().path().substring(src.document().path().lastIndexOf('/') + 1, src.document().path().length() - 5);
            int endCol = req.module().equals(modA.identity()) ? modAEndCol : 20;
            var span = new SourceSpan(src.document().identity(), 1, 1, 1, endCol);
            var entity = Entity.create(EntityOrigin.PROJECT, EntityScope.project(req.module()), EntityKind.TYPE,
                    JavaSymbolName.topLevelType(pkg, simple).canonicalName(), Optional.of(span));
            var decl = new DeclarationRecord(entity, simple, SemanticStatus.RESOLVED, direct, List.of(), List.of());
            var fr = new FrontendResult(req.manifest().identity(), VER, FrontendResult.State.COMPLETED,
                    List.of(decl), List.of(), List.of(),
                    List.of(new SourceOutcome(src.document().identity(), SourceOutcome.State.PROCESSED, List.of())),
                    coverage, List.of());
            units.add(new UniversalIngestionPipeline.Unit(outcome.sourceSet(), UniversalIngestionPipeline.Status.ANALYZED,
                    Optional.of(fr), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));
        }
        var pipelineResult = new UniversalIngestionPipeline.Result(ContentDigest.sha256Utf8("pipe-" + modACode),
                sourceIngestion, units, List.of(), List.of());
        return new CanonicalGraphInput(rawInputs, Optional.of(build), Optional.of(pipelineResult), List.of(), List.of(), List.of());
    }

    private static InventoryEntry fileEntry(String path, ContentHandle h) {
        return new InventoryEntry(LogicalPath.utf8(path), InventoryEntry.Kind.FILE, "100644",
                InventoryEntry.classify(path), InventoryEntry.Disposition.ACQUIRED, Optional.of(h),
                Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    }

    private static final class MemContentStore implements RepositoryContentStore {
        private final Map<ContentHandle, byte[]> blobs = new HashMap<>();
        @Override public ContentHandle put(InputStream input, java.util.function.BooleanSupplier cancelled) throws IOException {
            byte[] bytes = input.readAllBytes();
            var h = new ContentHandle(ContentDigest.sha256(bytes), bytes.length);
            blobs.put(h, bytes);
            return h;
        }
        @Override public InputStream open(ContentHandle handle) throws IOException {
            byte[] b = blobs.get(handle);
            if (b == null) throw new IOException("MISSING");
            return new ByteArrayInputStream(b);
        }
        @Override public InputStream range(ContentHandle handle, long offset, long length) throws IOException {
            byte[] b = blobs.get(handle);
            if (b == null) throw new IOException("MISSING");
            return new ByteArrayInputStream(b, (int) offset, (int) length);
        }
        @Override public boolean verify(ContentHandle handle) { return blobs.containsKey(handle); }
    }

    private static final class MemInventory implements InventoryReader {
        private final Map<String, List<InventoryEntry>> entriesByRev = new HashMap<>();
        private final Map<String, InventoryManifest> manifests = new HashMap<>();
        void seal(String rev, RepositoryIdentity repo, ContentDigest sel, List<InventoryEntry> entries) {
            var sorted = entries.stream().sorted(Comparator.comparing(e -> e.path().key())).toList();
            entriesByRev.put(rev, sorted);
            long files = sorted.stream().filter(e -> e.kind() == InventoryEntry.Kind.FILE).count();
            long bytes = sorted.stream().mapToLong(e -> e.content().map(ContentHandle::size).orElse(0L)).sum();
            ContentDigest inv = ContentDigest.sha256Utf8(com.evolution.analysis.contract.serialization.CanonicalJson.write(sorted));
            manifests.put(rev, new InventoryManifest(InventoryManifest.SCHEMA, repo, sel, inv, sorted.size(), files, bytes, 0L,
                    Optional.empty(), Optional.empty()));
        }
        @Override public Optional<InventoryManifest> manifest(String revision) { return Optional.ofNullable(manifests.get(revision)); }
        @Override public Page page(String revision, int size, Optional<String> cursor) {
            var list = entriesByRev.getOrDefault(revision, List.of());
            return cursor.isPresent() ? new Page(List.of(), Optional.empty()) : new Page(list, Optional.empty());
        }
    }
}
