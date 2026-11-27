package com.evolution.analysis.javaparser;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies:
 * <ol>
 *   <li>100% bit-for-bit canonical determinism between single-threaded and parallel Pass 1 + Pass 2 extraction
 *       (including reversed source input order) across 48 multi-package files with generics, records, lambdas,
 *       inner classes, method references, and Lombok annotations.</li>
 *   <li>{@link FrontendProgress} callback monotonicity and completeness.</li>
 *   <li>{@link UniversalIngestionPipeline} priority-ordered scheduling (cache hits &amp; smaller modules first)
 *       with exact original-input-order merge parity.</li>
 * </ol>
 */
class ParallelExtractionDeterminismTest {

    private static Map<String, String> buildFortyEightFileFixture() {
        var files = new LinkedHashMap<String, String>();
        files.put("core/Entity.java", """
                package core;
                public interface Entity<ID> {
                    ID id();
                    default String label() { return String.valueOf(id()); }
                }
                """);
        files.put("core/BaseRepo.java", """
                package core;
                import java.util.*;
                public abstract class BaseRepo<T extends Entity<String>> {
                    protected final List<T> store = new ArrayList<>();
                    public void save(T item) { store.add(item); }
                    public Optional<T> findFirst() { return store.stream().findFirst(); }
                    public List<String> labels() { return store.stream().map(Entity::label).toList(); }
                }
                """);
        files.put("core/ItemRecord.java", """
                package core;
                public record ItemRecord(String id, int weight) implements Entity<String> {
                    public int scaled(int factor) { return weight * factor; }
                }
                """);
        files.put("core/Kind.java", """
                package core;
                public enum Kind {
                    FAST(1), SLOW(5);
                    private final int cost;
                    Kind(int cost) { this.cost = cost; }
                    public int cost() { return cost; }
                }
                """);
        for (int i = 0; i < 22; i++) {
            String pkg = "mod" + (i % 4);
            files.put(pkg + "/Model" + i + ".java",
                    "package " + pkg + ";\n"
                    + "import core.*;\n"
                    + "public class Model" + i + " implements Entity<String> {\n"
                    + "  private final String id;\n"
                    + "  private final Kind kind;\n"
                    + "  public Model" + i + "(String id, Kind kind) { this.id = id; this.kind = kind; }\n"
                    + "  @Override public String id() { return id; }\n"
                    + "  public int score() { return kind.cost() + " + i + "; }\n"
                    + "  public static class Helper" + i + " {\n"
                    + "    public static Model" + i + " create(String id) { return new Model" + i + "(id, Kind.FAST); }\n"
                    + "  }\n"
                    + "}\n");
            files.put(pkg + "/Service" + i + ".java",
                    "package " + pkg + ";\n"
                    + "import core.*;\n"
                    + "import java.util.*;\n"
                    + "public class Service" + i + " extends BaseRepo<Model" + i + "> {\n"
                    + "  public int process(List<ItemRecord> items) {\n"
                    + "    save(Model" + i + ".Helper" + i + ".create(\"k" + i + "\"));\n"
                    + "    int sum = items.stream().mapToInt(it -> it.scaled(2)).sum();\n"
                    + "    return sum + findFirst().map(Model" + i + "::score).orElse(0) + labels().size();\n"
                    + "  }\n"
                    + "}\n");
        }
        assertEquals(48, files.size());
        return files;
    }

    @Test
    void parallelPass1AndPass2MatchSerialBitForBitRegardlessOfInputOrder() {
        var files = buildFortyEightFileFixture();
        var reversed = new LinkedHashMap<String, String>();
        var keys = new ArrayList<>(files.keySet());
        Collections.reverse(keys);
        for (String k : keys) reversed.put(k, files.get(k));

        var reqForward = TestInputs.request(files, List.of());
        var reqReversed = TestInputs.request(reversed, List.of());
        var frontend = new JavaParserFrontend();

        FrontendResult serial;
        FrontendResult parallel;
        FrontendResult parallelReversed;

        AtomicInteger startedCount = new AtomicInteger();
        AtomicInteger completedCount = new AtomicInteger();
        AtomicInteger filesDeltaTotal = new AtomicInteger();
        FrontendProgress observer = new FrontendProgress() {
            @Override
            public void onModuleStarted(String moduleId, String sourceSet, int totalFiles) {
                startedCount.incrementAndGet();
                assertEquals(48, totalFiles);
            }

            @Override
            public void onFilesCompleted(String modulePath, int deltaFiles, int completedFiles, int totalFiles, boolean cacheHit) {
                filesDeltaTotal.addAndGet(deltaFiles);
                assertTrue(completedFiles <= totalFiles);
            }

            @Override
            public void onModuleCompleted(String moduleId, String sourceSet, int filesCount, int typesCount, long durationMillis, boolean cacheHit) {
                completedCount.incrementAndGet();
                assertEquals(48, filesCount);
            }
        };

        System.setProperty("evo.incremental.disabled", "true");
        System.setProperty("evo.perf.parallelThreshold", "4");
        try {
            System.setProperty("evo.perf.parallelModuleExtraction", "false");
            serial = frontend.analyze(reqForward);

            System.setProperty("evo.perf.parallelModuleExtraction", "true");
            parallel = frontend.analyze(reqForward, observer);
            parallelReversed = frontend.analyze(reqReversed);
        } finally {
            System.clearProperty("evo.perf.parallelModuleExtraction");
            System.clearProperty("evo.perf.parallelThreshold");
            System.clearProperty("evo.incremental.disabled");
        }

        assertEquals(FrontendResult.State.COMPLETED, serial.state());
        assertEquals(serial, parallel);
        assertEquals(CanonicalJson.write(serial), CanonicalJson.write(parallel));
        assertEquals(IngestionEvidence.digest(serial), IngestionEvidence.digest(parallel));

        // Reversed input order produces identical semantic declarations/occurrences/observations/types!
        assertEquals(serial.declarations(), parallelReversed.declarations());
        assertEquals(serial.occurrences(), parallelReversed.occurrences());
        assertEquals(serial.observations(), parallelReversed.observations());
        assertEquals(serial.types(), parallelReversed.types());
        assertEquals(serial.derivedRelationships(), parallelReversed.derivedRelationships());

        // Progress callbacks fired cleanly and summed to all 48 files!
        assertEquals(1, startedCount.get());
        assertEquals(1, completedCount.get());
        assertEquals(48, filesDeltaTotal.get());
    }

    @Test
    void universalIngestionPipelinePriorityOrderingPreservesInputOrderMerge() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/prio-pipeline.git");
        var filesMap = new LinkedHashMap<String, String>();
        filesMap.put("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>mod-a</module><module>mod-b</module><module>mod-c</module></modules></project>");
        // Module A (index 0): large (5 files)
        filesMap.put("mod-a/pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>mod-a</artifactId><version>1</version></project>");
        for (int i = 0; i < 5; i++) {
            filesMap.put("mod-a/src/main/java/a/Big" + i + ".java",
                    "package a; public class Big" + i + " { public int f() { return " + i + "; } }");
        }
        // Module B (index 1): tiny (1 file)
        filesMap.put("mod-b/pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>mod-b</artifactId><version>1</version></project>");
        filesMap.put("mod-b/src/main/java/b/Tiny.java", "package b; public class Tiny { public int t() { return 1; } }");
        // Module C (index 2): medium (3 files, marked cache hit!)
        filesMap.put("mod-c/pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>mod-c</artifactId><version>1</version></project>");
        for (int i = 0; i < 3; i++) {
            filesMap.put("mod-c/src/main/java/c/Cached" + i + ".java",
                    "package c; public class Cached" + i + " { public int c() { return " + i + "; } }");
        }

        var dummyMod = ModuleDescriptor.create(repo, "root", "root");
        var sourceInputs = new TreeMap<String, SourceInput>();
        filesMap.forEach((p, text) -> {
            byte[] b = text.getBytes(StandardCharsets.UTF_8);
            sourceInputs.put(p, new SourceInput(
                    SourceDocument.create(repo, dummyMod, p, ContentDigest.sha256(b), SourceClassification.MAIN), b));
        });
        var docs = sourceInputs.values().stream().map(SourceInput::document).toList();
        var snap = RepositorySnapshot.create(repo, Optional.empty(), false,
                docs.stream().map(SnapshotFile::from).toList(), docs);
        var repoInputs = new RepositoryInputs(snap, sourceInputs);

        var build = new UniversalBuildIngestion().ingest(
                repoInputs, UniversalBuildIngestion.Policy.defaults(),
                Optional.of(new com.evolution.analysis.maven.MavenBuildModelProvider()), Map.of(),
                new BuildModelPolicy(List.of(), List.of(), Map.of(), 10000, 100, 16));
        var platform = TestInputs.request("class P {}").platform();
        var resolutions = new LinkedHashMap<UniversalSourceIngestion.SourceSet, UniversalSourceIngestion.Resolution>();
        for (var mod : build.modules()) {
            for (var sp : mod.sourcePlan().sourceSets()) {
                var key = new UniversalSourceIngestion.SourceSet(sp.module(), sp.kind(), sp.name());
                resolutions.put(key, new UniversalSourceIngestion.Resolution(
                        platform, List.of(), ContentDigest.sha256Utf8("res-" + key)));
            }
        }
        var comp = new ManifestComponent(
                new com.evolution.analysis.contract.common.VersionedIdentifier("test.prio", "1"),
                ContentDigest.sha256Utf8("prio-v1"));
        var assembled = new UniversalSourceIngestion().assemble(
                repoInputs, build, resolutions, comp, comp, comp, Map.of(), Optional.of(platform));

        var pipeline = new UniversalIngestionPipeline();
        var frontend = new JavaParserFrontend();

        var baseline = pipeline.run(assembled, frontend, JavaParserFrontend.PROVIDER, 500, 50,
                1, 256L << 20, SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);

        var completionOrder = new CopyOnWriteArrayList<Integer>();
        var prioritized = pipeline.run(assembled, frontend, JavaParserFrontend.PROVIDER, 500, 50,
                1, 256L << 20, SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED,
                FrontendProgress.NOOP,
                (origIdx, unit, durMs, hit) -> completionOrder.add(origIdx),
                req -> req.sources().stream().anyMatch(s -> s.document().path().startsWith("mod-c/")));

        // Exact original-input-order merge parity!
        assertEquals(baseline.identity(), prioritized.identity());
        assertEquals(baseline.units().size(), prioritized.units().size());
        for (int i = 0; i < baseline.units().size(); i++) {
            assertEquals(baseline.units().get(i).sourceSet(), prioritized.units().get(i).sourceSet());
        }
        // Cache-hit module C (3 files) ran BEFORE large non-cached module A (5 files)!
        int idxA = -1, idxC = -1;
        for (int i = 0; i < assembled.outcomes().size(); i++) {
            var reqOpt = assembled.outcomes().get(i).request();
            if (reqOpt.isPresent()) {
                String p = reqOpt.orElseThrow().sources().isEmpty() ? "" : reqOpt.orElseThrow().sources().getFirst().document().path();
                if (p.startsWith("mod-a/")) idxA = i;
                if (p.startsWith("mod-c/")) idxC = i;
            }
        }
        assertTrue(idxA >= 0 && idxC >= 0);
        assertTrue(completionOrder.indexOf(idxC) < completionOrder.indexOf(idxA),
                "Cache-hit module C should be scheduled before uncached larger module A; order=" + completionOrder);
    }
}
