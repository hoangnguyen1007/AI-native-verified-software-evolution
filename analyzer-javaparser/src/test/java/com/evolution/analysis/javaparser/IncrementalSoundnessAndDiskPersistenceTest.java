package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end clean-vs-incremental equivalence tests proving all 5 soundness bug fixes,
 * {@link LazySkeletonTypeSolver} zero-unchanged-file parsing, O(k) Declaration-Span Rebinding
 * (both intra-module and cross-module upstream reactor), and disk-backed persistence across JVM restarts.
 */
class IncrementalSoundnessAndDiskPersistenceTest {
    @TempDir Path cacheDir;

    private static FrontendResult analyzeCold(JavaParserFrontend frontend, FrontendRequest req) {
        System.setProperty("evo.incremental.disabled", "true");
        try {
            return frontend.analyze(req);
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }
    }

    private static void assertBitForBitParity(FrontendResult expectedCold, FrontendResult actualIncremental) {
        assertEquals(expectedCold, actualIncremental);
        assertEquals(IngestionEvidence.digest(expectedCold), IngestionEvidence.digest(actualIncremental));
    }

    private static FrontendRequest requestForRepo(
            RepositoryIdentity repo,
            ModuleDescriptor mod,
            Map<String, String> javaSources,
            Map<String, String> extraSnapshotFiles,
            String analysisProfile,
            List<ModuleDescriptor> extraModules,
            List<ReactorSourceInput> reactors) {
        var platform = TestInputs.request("class Stub {}").platform();
        var sources = javaSources.entrySet().stream().map(e -> {
            byte[] b = e.getValue().getBytes(StandardCharsets.UTF_8);
            return new SourceInput(SourceDocument.create(repo, mod, e.getKey(), ContentDigest.sha256(b), SourceClassification.MAIN), b);
        }).toList();
        var ownDocs = sources.stream().map(SourceInput::document).toList();
        var allDocs = new ArrayList<SourceDocument>(ownDocs);
        for (var r : reactors) {
            for (var rs : r.sources()) {
                allDocs.add(rs.document());
            }
        }
        var snapFiles = new ArrayList<SnapshotFile>(allDocs.stream().map(SnapshotFile::from).toList());
        for (var extra : extraSnapshotFiles.entrySet()) {
            byte[] b = extra.getValue().getBytes(StandardCharsets.UTF_8);
            snapFiles.add(new SnapshotFile(extra.getKey(), ContentDigest.sha256(b)));
        }
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, snapFiles, allDocs);
        var entries = List.of(platform.entry());
        var plan = reactors.isEmpty()
                ? FrontendPlan.legacy()
                : new FrontendPlan(Optional.of(21), Optional.of(21), false,
                        ContentDigest.sha256Utf8("soundness-cp"), ContentDigest.sha256Utf8("soundness-dec"));
        var options = new TreeMap<>(FrontendRequest.options(
                plan, mod.identity(), SourceClassification.MAIN, ownDocs, platform, reactors));
        if (analysisProfile != null) {
            options.put("analysis.profile", analysisProfile);
        }
        var modules = new ArrayList<ModuleDescriptor>();
        modules.add(mod);
        modules.addAll(extraModules);
        var comp = new ManifestComponent(new VersionedIdentifier("test.soundness", "1"), ContentDigest.sha256Utf8("soundness-v1"));
        var manifest = AnalysisManifest.create(
                new VersionedIdentifier("analysis.manifest", "2"), snapshot, modules, entries,
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "2"), options),
                comp, comp, comp);
        return new FrontendRequest(manifest, mod.identity(), SourceClassification.MAIN, plan,
                sources, platform, List.of(), reactors);
    }

    private static FrontendRequest requestForRepo(
            RepositoryIdentity repo,
            ModuleDescriptor mod,
            Map<String, String> javaSources,
            Map<String, String> extraSnapshotFiles,
            String analysisProfile,
            List<ReactorSourceInput> reactors) {
        return requestForRepo(repo, mod, javaSources, extraSnapshotFiles, analysisProfile, List.of(), reactors);
    }

    @Test
    void bug1_sixHopInheritanceChainPropagatesUnboundedAndMatchesCold() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/bug1-6hop.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("h0/H0.java", "package h0; public class H0 { public int val() { return 1; } }");
        v1.put("h1/H1.java", "package h1; import h0.H0; public class H1 extends H0 {}");
        v1.put("h2/H2.java", "package h2; import h1.H1; public class H2 extends H1 {}");
        v1.put("h3/H3.java", "package h3; import h2.H2; public class H3 extends H2 {}");
        v1.put("h4/H4.java", "package h4; import h3.H3; public class H4 extends H3 {}");
        v1.put("h5/H5.java", "package h5; import h4.H4; public class H5 extends H4 {}");
        v1.put("h6/Leaf.java", "package h6; import h5.H5; public class Leaf { public Object read(H5 h) { return h.val(); } }");
        for (int i = 0; i < 12; i++) {
            v1.put("clean/U" + i + ".java", "package clean; public class U" + i + " { public int f() { return " + i + "; } }");
        }

        var frontend = new JavaParserFrontend();
        frontend.analyze(requestForRepo(repo, mod, v1, Map.of(), null, List.of()));

        // Edit H0.val() return type from int -> String (6 hops away from Leaf!)
        var v2 = new LinkedHashMap<>(v1);
        v2.put("h0/H0.java", "package h0; public class H0 { public String val() { return \"ok\"; } }");
        var req2 = requestForRepo(repo, mod, v2, Map.of(), null, List.of());

        FrontendResult inc = frontend.analyze(req2);
        var stats = JavaParserFrontend.lastIncrementalStats();
        assertNotNull(stats);
        assertTrue(stats.incrementalHit(), "Should hit incremental cache");
        assertEquals(7, stats.reextractedUnits(), "All 6 hops + Leaf must re-extract (no hops < 4 cap!)");
        assertEquals(12, stats.reusedUnits(), "12 unrelated files must remain reused");

        FrontendResult cold = analyzeCold(frontend, req2);
        assertBitForBitParity(cold, inc);
    }

    @Test
    void bug2_lombokConfigChangeInvalidatesModuleContextKey() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/bug2-lombok-cfg.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var files = new LinkedHashMap<String, String>();
        files.put("p/A.java", "package p; public class A { public int x() { return 1; } }");
        files.put("p/B.java", "package p; public class B { public int y(A a) { return a.x(); } }");

        var rNoCfg = requestForRepo(repo, mod, files, Map.of(), null, List.of());
        var rWithCfg = requestForRepo(repo, mod, files, Map.of("lombok.config", "lombok.accessors.fluent = true\n"), null, List.of());
        var rEditedCfg = requestForRepo(repo, mod, files, Map.of("lombok.config", "lombok.accessors.fluent = false\n"), null, List.of());

        var k1 = ModuleIncrementalCache.moduleContextKey(rNoCfg, JavaParserFrontend.PROVIDER, false);
        var k2 = ModuleIncrementalCache.moduleContextKey(rWithCfg, JavaParserFrontend.PROVIDER, false);
        var k3 = ModuleIncrementalCache.moduleContextKey(rEditedCfg, JavaParserFrontend.PROVIDER, false);

        assertNotEquals(k1, k2, "Adding lombok.config MUST invalidate moduleContextKey");
        assertNotEquals(k2, k3, "Editing lombok.config content MUST invalidate moduleContextKey");
    }

    @Test
    void bug3_fastArchitectureNewSimpleTypeNameInvalidatesCleanReferencingUnit() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/bug3-fast-arch.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        // Clean file calls Helper.run() on an external/local var named Helper (not yet a project type!)
        v1.put("app/Caller.java", "package app; public class Caller { public int go() { String Helper = \"abc\"; return Helper.length(); } }");
        for (int i = 0; i < 6; i++) {
            v1.put("other/O" + i + ".java", "package other; public class O" + i + " { public int id() { return " + i + "; } }");
        }

        var frontend = new JavaParserFrontend();
        frontend.analyze(requestForRepo(repo, mod, v1, Map.of(), "FAST_ARCHITECTURE", List.of()));

        // Now add a brand new class `util.Helper` -> its simple name `Helper` enters architecturalSimpleTypeNames!
        var v2 = new LinkedHashMap<>(v1);
        v2.put("util/Helper.java", "package util; public class Helper { public static int ping() { return 1; } }");
        var req2 = requestForRepo(repo, mod, v2, Map.of(), "FAST_ARCHITECTURE", List.of());

        FrontendResult inc = frontend.analyze(req2);
        var stats = JavaParserFrontend.lastIncrementalStats();
        assertNotNull(stats);
        assertTrue(stats.incrementalHit());
        assertEquals(2, stats.reextractedUnits(), "Added Helper + referencing Caller must both re-extract in FAST_ARCHITECTURE");

        FrontendResult cold = analyzeCold(frontend, req2);
        assertBitForBitParity(cold, inc);
    }

    @Test
    void bug4_intraModuleAndCrossModuleLineShiftsRebindSourceSpansWithoutParsingCleanFiles() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/bug4-spans.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var reactorMod = ModuleDescriptor.create(repo, "lib", "lib");

        var appFiles = new LinkedHashMap<String, String>();
        appFiles.put("app/Target.java", "package app;\npublic class Target {\n  public int ping() { return 1; }\n}\n");
        appFiles.put("app/Caller.java", "package app;\nimport lib.ReactorApi;\npublic class Caller {\n  public int run(Target t, ReactorApi r) { return t.ping() + r.value(); }\n}\n");
        for (int i = 0; i < 6; i++) {
            appFiles.put("app/Idle" + i + ".java", "package app; public class Idle" + i + " { public int f() { return " + i + "; } }");
        }

        String reactorV1 = "package lib;\npublic class ReactorApi {\n  public int value() { return 10; }\n}\n";
        var rDocV1 = SourceDocument.create(repo, reactorMod, "lib/ReactorApi.java",
                ContentDigest.sha256(reactorV1.getBytes(StandardCharsets.UTF_8)), SourceClassification.MAIN);
        var rSlotV1 = ReactorSourceInput.create(reactorMod.identity(), SourceClassification.MAIN, 0,
                List.of(new SourceInput(rDocV1, reactorV1.getBytes(StandardCharsets.UTF_8))));

        var frontend = new JavaParserFrontend();
        frontend.analyze(requestForRepo(repo, mod, appFiles, Map.of(), null, List.of(reactorMod), List.of(rSlotV1)));

        // Part A: Intra-module body-only + 15 blank lines shift in Target.java!
        var appShifted = new LinkedHashMap<>(appFiles);
        appShifted.put("app/Target.java",
                "package app;\n" + "\n".repeat(15) + "public class Target {\n  public int ping() { int k = 1; return k; }\n}\n");
        var reqIntra = requestForRepo(repo, mod, appShifted, Map.of(), null, List.of(reactorMod), List.of(rSlotV1));
        FrontendResult incIntra = frontend.analyze(reqIntra);
        var statsIntra = JavaParserFrontend.lastIncrementalStats();
        assertTrue(statsIntra.incrementalHit());
        assertEquals(1, statsIntra.reextractedUnits(), "Only Target.java should re-extract on line-shift body edit");
        assertEquals(0, statsIntra.lazySkeletonsParsed(), "Target is self-contained -> ZERO clean files parsed!");

        // Verify Target.ping() has updated line 18 (not stale line 3!) AND bit-for-bit parity with cold!
        int pingLine = incIntra.declarations().stream()
                .filter(d -> d.entity().canonicalName().contains("\"ping\""))
                .findFirst().orElseThrow()
                .entity().declaration().orElseThrow().startLine();
        assertEquals(18, pingLine, "Peer DeclarationRecord SourceSpan MUST reflect new line number!");
        assertBitForBitParity(analyzeCold(frontend, reqIntra), incIntra);

        // Part B: Cross-module upstream reactor body-only + 12 blank lines shift in ReactorApi.java!
        String reactorV2 = "package lib;\n" + "\n".repeat(12) + "public class ReactorApi {\n  public int value() { int x = 10; return x; }\n}\n";
        var rDocV2 = SourceDocument.create(repo, reactorMod, "lib/ReactorApi.java",
                ContentDigest.sha256(reactorV2.getBytes(StandardCharsets.UTF_8)), SourceClassification.MAIN);
        var rSlotV2 = ReactorSourceInput.create(reactorMod.identity(), SourceClassification.MAIN, 0,
                List.of(new SourceInput(rDocV2, reactorV2.getBytes(StandardCharsets.UTF_8))));
        var reqCross = requestForRepo(repo, mod, appShifted, Map.of(), null, List.of(reactorMod), List.of(rSlotV2));

        FrontendResult incCross = frontend.analyze(reqCross);
        var statsCross = JavaParserFrontend.lastIncrementalStats();
        assertTrue(statsCross.incrementalHit());
        assertEquals(0, statsCross.reextractedUnits(), "Upstream reactor body-only edit -> Case 0 (0 downstream units re-extracted!)");
        assertTrue(statsCross.reboundReactorDeclarations() >= 1,
                "Expected O(k) Declaration-Span Rebinding on shifted ReactorApi declarations, got " + statsCross.reboundReactorDeclarations());
        int reactorValLine = incCross.declarations().stream()
                .filter(d -> d.entity().canonicalName().contains("\"value\""))
                .findFirst().orElseThrow()
                .entity().declaration().orElseThrow().startLine();
        assertEquals(15, reactorValLine, "Rebound reactor DeclarationRecord MUST have new line 15!");
        assertBitForBitParity(analyzeCold(frontend, reqCross), incCross);
    }

    @Test
    void bug5_scopedWildcardsDoNotBlastJavaUtilWildcardImporters() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/bug5-wildcards.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("api/Consts.java", "package api; public class Consts { public static final int N = 1; public int m() { return 1; } }");
        v1.put("use/PkgStar.java", "package use; import api.*; public class PkgStar { public int g(Consts c) { return c.m(); } }");
        v1.put("use/StaticStar.java", "package use; import static api.Consts.*; public class StaticStar { public int g() { return N; } }");
        for (int i = 0; i < 8; i++) {
            v1.put("other/UtilUser" + i + ".java",
                    "package other; import java.util.*; public class UtilUser" + i + " { public List<String> xs() { return List.of(\"a\"); } }");
        }

        var frontend = new JavaParserFrontend();
        frontend.analyze(requestForRepo(repo, mod, v1, Map.of(), null, List.of()));

        var v2 = new LinkedHashMap<>(v1);
        v2.put("api/Consts.java", "package api; public class Consts { public static final int N = 2; public long m() { return 2L; } }");
        var req2 = requestForRepo(repo, mod, v2, Map.of(), null, List.of());

        FrontendResult inc = frontend.analyze(req2);
        var stats = JavaParserFrontend.lastIncrementalStats();
        assertTrue(stats.incrementalHit());
        assertEquals(3, stats.reextractedUnits(), "Only Consts + PkgStar + StaticStar should re-extract; 8 java.util.* files must stay clean!");
        assertEquals(8, stats.reusedUnits());
        assertBitForBitParity(analyzeCold(frontend, req2), inc);
    }

    @Test
    void diskPersistenceSurvivesCacheClearAndFallsBackCleanlyOnCorruption() throws Exception {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/disk-persist.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("app/Solo.java", "package app; public class Solo { public int calc() { return 10; } }");
        for (int i = 0; i < 6; i++) {
            v1.put("app/Peer" + i + ".java", "package app; public class Peer" + i + " { public int f() { return " + i + "; } }");
        }

        var frontend = new JavaParserFrontend();
        JavaParserFrontend.configureModuleCacheDirectory(cacheDir);
        try {
            frontend.analyze(requestForRepo(repo, mod, v1, Map.of(), null, List.of()));

            // Simulate fresh JVM restart by clearing ALL in-memory entries!
            ModuleIncrementalCache.clearInMemoryCache();

            // Verify disk files were written
            try (var walk = Files.walk(cacheDir)) {
                long m3Count = walk.filter(p -> p.toString().endsWith(".m3.gz")).count();
                assertTrue(m3Count >= 1, "Expected EVOM3 manifest file persisted on disk");
            }

            // Edit Solo.java body -> reloads module from disk, parses 0 clean files, matches cold bit-for-bit!
            var v2 = new LinkedHashMap<>(v1);
            v2.put("app/Solo.java", "package app; public class Solo { public int calc() { return 20 + 22; } }");
            var req2 = requestForRepo(repo, mod, v2, Map.of(), null, List.of());

            FrontendResult incFromDisk = frontend.analyze(req2);
            var stats = JavaParserFrontend.lastIncrementalStats();
            assertNotNull(stats);
            assertTrue(stats.incrementalHit(), "Must hit disk-persisted module cache after JVM restart!");
            assertEquals(1, stats.reextractedUnits());
            assertEquals(6, stats.reusedUnits());
            assertEquals(0, stats.lazySkeletonsParsed(), "0 clean files should be parsed!");
            assertBitForBitParity(analyzeCold(frontend, req2), incFromDisk);

            // Corrupt every .u3.gz file on disk, clear in-memory cache -> must detect SHA-256 tamper & fall back cleanly!
            try (var walk = Files.walk(cacheDir)) {
                for (Path p : walk.filter(f -> f.toString().endsWith(".u3.gz")).toList()) {
                    byte[] raw = Files.readAllBytes(p);
                    raw[raw.length - 1] ^= 0xFF;
                    Files.write(p, raw);
                }
            }
            ModuleIncrementalCache.clearInMemoryCache();
            FrontendResult fallbackRes = frontend.analyze(req2);
            var fallbackStats = JavaParserFrontend.lastIncrementalStats();
            assertFalse(fallbackStats.incrementalHit(), "Corrupted disk unit must NOT be trusted");
            assertBitForBitParity(analyzeCold(frontend, req2), fallbackRes);
        } finally {
            JavaParserFrontend.configureModuleCacheDirectory(null);
        }
    }

    @Test
    void p0_diskUnitCacheContentAddressesOverloadTargetChangeAcrossRestart() throws Exception {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/p0-disk-overload.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("app/Bar.java", "package app; public class Bar { public int f(Object o) { return 1; } }");
        v1.put("app/Foo.java", "package app; public class Foo { public int call(Bar b) { return b.f(\"hi\"); } }");
        v1.put("app/Unrelated.java", "package app; public class Unrelated { public int v() { return 1; } }");
        for (int i = 0; i < 6; i++) {
            v1.put("app/Peer" + i + ".java", "package app; public class Peer" + i + " { public int p() { return " + i + "; } }");
        }

        var frontend = new JavaParserFrontend();
        JavaParserFrontend.configureModuleCacheDirectory(cacheDir);
        String fObject = JavaSymbolName.method(
                JavaSymbolName.topLevelType("app", "Bar"), "f",
                List.of(ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "Object")))).canonicalName();
        String fString = JavaSymbolName.method(
                JavaSymbolName.topLevelType("app", "Bar"), "f",
                List.of(ErasedType.declared(JavaSymbolName.topLevelType("java.lang", "String")))).canonicalName();
        try {
            // Run 1: Foo resolves b.f("hi") -> Bar.f(Object) and persists to disk.
            FrontendResult run1 = frontend.analyze(requestForRepo(repo, mod, v1, Map.of(), null, List.of()));
            assertTrue(hasResolvedCallTarget(run1, fObject),
                    "Run 1 must resolve b.f(\"hi\") to Bar.f(Object)");

            // Run 2: Bar adds overload f(String) while keeping f(Object). Foo is untouched on disk (same contentDigest,
            // same externalAbiDigest, same list sizes) yet re-extracts with a different call target Bar.f(String).
            var v2 = new LinkedHashMap<>(v1);
            v2.put("app/Bar.java",
                    "package app; public class Bar { public int f(Object o) { return 1; } public int f(String s) { return 2; } }");
            var req2 = requestForRepo(repo, mod, v2, Map.of(), null, List.of());
            FrontendResult run2 = frontend.analyze(req2);
            assertBitForBitParity(analyzeCold(frontend, req2), run2);

            // Simulate JVM restart: drop in-memory cache so Run 3 reloads Foo from disk.
            ModuleIncrementalCache.clearInMemoryCache();

            // Run 3: body-only edit to Unrelated forces disk reload of Foo without re-extracting Foo.
            var v3 = new LinkedHashMap<>(v2);
            v3.put("app/Unrelated.java", "package app; public class Unrelated { public int v() { return 2; } }");
            var req3 = requestForRepo(repo, mod, v3, Map.of(), null, List.of());
            FrontendResult run3 = frontend.analyze(req3);
            var stats3 = JavaParserFrontend.lastIncrementalStats();
            assertNotNull(stats3);
            assertTrue(stats3.incrementalHit(), "Run 3 must reload from disk cache after JVM restart");
            assertEquals(1, stats3.reextractedUnits(), "Only Unrelated should re-extract; Foo must be reused from disk");

            assertTrue(hasResolvedCallTarget(run3, fString),
                    "Disk-reloaded Foo MUST target Bar.f(String)");
            assertFalse(hasResolvedCallTarget(run3, fObject),
                    "Disk-reloaded Foo MUST NOT carry stale Run-1 target Bar.f(Object)");
            assertBitForBitParity(analyzeCold(frontend, req3), run3);
        } finally {
            JavaParserFrontend.configureModuleCacheDirectory(null);
        }
    }

    private static boolean hasResolvedCallTarget(FrontendResult res, String expectedCanonicalName) {
        var names = new HashMap<EntityIdentity, String>();
        for (var d : res.declarations()) {
            names.put(d.entity().identity(), d.entity().canonicalName());
        }
        for (var o : res.occurrences()) {
            if (!"java.calls".equals(o.relationship().kind().value())) continue;
            if (o.relationship().target() instanceof RelationshipTarget.Resolved r) {
                if (expectedCanonicalName.equals(names.get(r.target()))) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    void p1_nestedTypeWildcardImportInvalidatedWhenNestedTypeAddedToOuter() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/p1-nested-wildcard.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("com/other2/Inner.java", "package com.other2; public class Inner {}");
        v1.put("com/acme/Outer.java", "package com.acme; public class Outer {}");
        v1.put("use/Client.java",
                "package use; import com.other2.*; import com.acme.Outer.*; public class Client { public Inner ref() { return null; } }");
        for (int i = 0; i < 8; i++) {
            v1.put("clean/Other" + i + ".java",
                    "package clean; import java.util.*; public class Other" + i + " { public List<String> xs() { return List.of(); } }");
        }

        var frontend = new JavaParserFrontend();
        FrontendResult run1 = frontend.analyze(requestForRepo(repo, mod, v1, Map.of(), null, List.of()));
        String other2Inner = JavaSymbolName.topLevelType("com.other2", "Inner").canonicalName();
        var run1Names = new HashMap<EntityIdentity, String>();
        for (var d : run1.declarations()) {
            run1Names.put(d.entity().identity(), d.entity().canonicalName());
        }
        boolean run1ResolvesOther2 = run1.types().stream()
                .flatMap(t -> t.type().target().stream())
                .map(id -> run1Names.getOrDefault(id, ""))
                .anyMatch(other2Inner::equals);
        assertTrue(run1ResolvesOther2, "Run 1 must resolve Inner to com.other2.Inner");

        // Run 2: Add nested class Inner to com.acme.Outer (Client has import com.acme.Outer.* without referencing Outer directly).
        var v2 = new LinkedHashMap<>(v1);
        v2.put("com/acme/Outer.java", "package com.acme; public class Outer { public static class Inner {} }");
        var req2 = requestForRepo(repo, mod, v2, Map.of(), null, List.of());

        FrontendResult inc = frontend.analyze(req2);
        var stats = JavaParserFrontend.lastIncrementalStats();
        assertNotNull(stats);
        assertTrue(stats.incrementalHit(), "Expected incremental hit with targeted nested-type wildcard invalidation");
        assertEquals(2, stats.reextractedUnits(), "Outer + Client (import com.acme.Outer.*) must re-extract");
        assertEquals(9, stats.reusedUnits(), "com.other2.Inner + 8 java.util.* files must remain reused");
        assertBitForBitParity(analyzeCold(frontend, req2), inc);
    }
}
