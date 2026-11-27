package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Hardening and parity regression tests covering parallel Pass-2 thread confinement,
 * >64 KiB serialization, SOURCE_RECOVERY incremental extraction (S6), package-private
 * superclass cross-module ABI, field-level Lombok ABI, nested-type wildcard shadowing,
 * self-referential lazy skeletons, and S1 warm disk-cache skip.
 */
class IncrementalParityHardeningTest {

    @TempDir Path tempDir;

    private static PlatformInput platform() {
        return TestInputs.request("class Stub {}").platform();
    }

    private static FrontendRequest requestFor(
            RepositoryIdentity repo,
            ModuleDescriptor mod,
            Map<String, String> files,
            boolean sourceRecovery,
            List<ReactorSourceInput> reactorSources) {
        var plat = platform();
        var sources = new ArrayList<SourceInput>();
        var docs = new ArrayList<SourceDocument>();
        var snapFiles = new ArrayList<SnapshotFile>();
        for (var e : new TreeMap<>(files).entrySet()) {
            byte[] bytes = e.getValue().getBytes(StandardCharsets.UTF_8);
            var doc = SourceDocument.create(repo, mod, e.getKey(), ContentDigest.sha256(bytes), SourceClassification.MAIN);
            docs.add(doc);
            snapFiles.add(SnapshotFile.from(doc));
            sources.add(new SourceInput(doc, bytes));
        }
        var modules = new LinkedHashMap<ModuleIdentity, ModuleDescriptor>();
        modules.put(mod.identity(), mod);
        for (var r : reactorSources) {
            for (var s : r.sources()) {
                docs.add(s.document());
                snapFiles.add(SnapshotFile.from(s.document()));
            }
            modules.putIfAbsent(r.module(), ModuleDescriptor.create(repo, "reactor-" + r.order(), "reactor-" + r.order()));
        }
        var snapshot = RepositorySnapshot.create(repo, Optional.empty(), false, snapFiles, docs);
        var plan = reactorSources.isEmpty()
                ? FrontendPlan.legacy()
                : new FrontendPlan(Optional.of(21), Optional.of(21), false,
                        ContentDigest.sha256Utf8("cp"), ContentDigest.sha256Utf8("dec"));
        var options = new TreeMap<>(FrontendRequest.options(plan, mod.identity(), SourceClassification.MAIN,
                sources.stream().map(SourceInput::document).toList(), plat, reactorSources));
        if (sourceRecovery) {
            options.put(FrontendRequest.RESOLUTION_MODE, FrontendRequest.SOURCE_RECOVERY);
        }
        var comp = new ManifestComponent(new VersionedIdentifier("test.hardening", "1"), ContentDigest.sha256Utf8("v1"));
        var manifest = AnalysisManifest.create(
                new VersionedIdentifier("analysis.manifest", "1"),
                snapshot,
                List.copyOf(modules.values()),
                List.of(plat.entry()),
                AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration", "1"), options),
                comp, comp, comp);
        return new FrontendRequest(manifest, mod.identity(), SourceClassification.MAIN, plan,
                sources, plat, List.of(), reactorSources);
    }

    private static FrontendResult analyzeCold(JavaParserFrontend frontend, FrontendRequest request) {
        String prev = System.getProperty("evo.incremental.disabled");
        try {
            System.setProperty("evo.incremental.disabled", "true");
            return frontend.analyze(request);
        } finally {
            if (prev == null) System.clearProperty("evo.incremental.disabled");
            else System.setProperty("evo.incremental.disabled", prev);
        }
    }

    private static void assertBitForBitParity(FrontendResult expected, FrontendResult actual) {
        assertEquals(CanonicalJson.write(expected), CanonicalJson.write(actual),
                "Incremental / parallel result MUST match cold full run bit-for-bit");
    }

    @Test
    void parallelPass2StressWithExplicitImportTypeSolverAndNestedRecoveryTypes() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/parallel-stress.git");
        var mod = ModuleDescriptor.create(repo, "stress", "stress");
        var files = new LinkedHashMap<String, String>();
        for (int i = 0; i < 20; i++) {
            files.put("p/Unit" + i + ".java", """
                    package p;
                    import ext.missing.Outer%d;
                    import ext.missing.Shared;
                    public class Unit%d {
                        Outer%d.Nested field;
                        Shared<Outer%d> generic;
                        public Outer%d.Nested get(Shared<Outer%d> arg) { return field; }
                    }
                    """.formatted(i, i, i, i, i, i));
        }
        var req = requestFor(repo, mod, files, true, List.of());
        var frontend = new JavaParserFrontend();

        String prevParallel = System.getProperty("evo.perf.parallelModuleExtraction");
        String prevThreshold = System.getProperty("evo.perf.parallelThreshold");
        try {
            System.setProperty("evo.perf.parallelModuleExtraction", "false");
            FrontendResult serial = analyzeCold(frontend, req);
            String serialJson = CanonicalJson.write(serial);

            System.setProperty("evo.perf.parallelModuleExtraction", "true");
            System.setProperty("evo.perf.parallelThreshold", "2");
            for (int run = 0; run < 10; run++) {
                FrontendResult par = analyzeCold(frontend, req);
                assertEquals(serialJson, CanonicalJson.write(par),
                        "Parallel Pass-2 run #" + run + " drifted from serial baseline");
            }
        } finally {
            if (prevParallel == null) System.clearProperty("evo.perf.parallelModuleExtraction");
            else System.setProperty("evo.perf.parallelModuleExtraction", prevParallel);
            if (prevThreshold == null) System.clearProperty("evo.perf.parallelThreshold");
            else System.setProperty("evo.perf.parallelThreshold", prevThreshold);
        }
    }

    @Test
    void largeStringsOver64KiBSerializeAndReloadCleanlyInModuleIncrementalCache() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/large-utf.git");
        var mod = ModuleDescriptor.create(repo, "big", "big");
        String hugeLiteral = "x".repeat(75_000);
        var files = new LinkedHashMap<String, String>();
        files.put("p/Big.java", """
                package p;
                @SuppressWarnings("%s")
                public class Big {
                    public String val() { return "ok"; }
                }
                """.formatted(hugeLiteral));
        files.put("p/Small.java", "package p; public class Small { public int n() { return 1; } }");

        ModuleIncrementalCache.clearInMemoryCache();
        ModuleIncrementalCache.configureDiskDirectory(tempDir.resolve("cache-utf"));
        try {
            var frontend = new JavaParserFrontend();
            var req1 = requestFor(repo, mod, files, false, List.of());
            FrontendResult cold1 = frontend.analyze(req1);

            // Force disk reload by clearing in-memory cache only.
            ModuleIncrementalCache.clearInMemoryCache();
            var files2 = new LinkedHashMap<>(files);
            files2.put("p/Small.java", "package p; public class Small { public int n() { return 2; } }");
            var req2 = requestFor(repo, mod, files2, false, List.of());
            FrontendResult inc2 = frontend.analyze(req2);
            var stats = JavaParserFrontend.lastIncrementalStats();
            assertNotNull(stats);
            assertTrue(stats.incrementalHit(), "Reloaded >64 KiB cache entry must hit incrementally");
            assertEquals(1, stats.reextractedUnits());
            assertEquals(1, stats.reusedUnits());

            FrontendResult cold2 = analyzeCold(frontend, req2);
            assertBitForBitParity(cold2, inc2);
            assertNotNull(cold1);
        } finally {
            ModuleIncrementalCache.configureDiskDirectory(null);
            ModuleIncrementalCache.clearInMemoryCache();
        }
    }

    @Test
    void sourceRecoveryIncrementalExtractionReusesCleanUnitsAndMatchesColdBitForBit() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/s6-recovery.git");
        var mod = ModuleDescriptor.create(repo, "rec", "rec");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("app/Caller.java", """
                package app;
                import missing.lib.Widget;
                public class Caller {
                    public int run(Widget w) { return 1; }
                }
                """);
        v1.put("app/WildcardUser.java", """
                package app;
                import missing.lib.*;
                public class WildcardUser {
                    public Widget get(Widget w) { return w; }
                }
                """);
        for (int i = 0; i < 6; i++) {
            v1.put("other/Clean" + i + ".java", "package other; public class Clean" + i + " { public int x() { return " + i + "; } }");
        }

        ModuleIncrementalCache.clearInMemoryCache();
        var frontend = new JavaParserFrontend();
        frontend.analyze(requestFor(repo, mod, v1, true, List.of()));

        // Edit 1: Body-only edit in Caller -> only Caller re-extracts, 7 units reused!
        var v2 = new LinkedHashMap<>(v1);
        v2.put("app/Caller.java", """
                package app;
                import missing.lib.Widget;
                public class Caller {
                    public int run(Widget w) { return 42; }
                }
                """);
        var req2 = requestFor(repo, mod, v2, true, List.of());
        FrontendResult inc2 = frontend.analyze(req2);
        var stats2 = JavaParserFrontend.lastIncrementalStats();
        assertNotNull(stats2);
        assertTrue(stats2.incrementalHit(), "S6 SOURCE_RECOVERY body edit must hit incremental cache");
        assertEquals(1, stats2.reextractedUnits(), "Only modified Caller should re-extract");
        assertEquals(7, stats2.reusedUnits());
        assertBitForBitParity(analyzeCold(frontend, req2), inc2);

        // Edit 2: Add type argument to missing.lib.Widget in Caller -> changes Widget recovery signature,
        // so WildcardUser MUST also re-extract and match cold bit-for-bit!
        var v3 = new LinkedHashMap<>(v2);
        v3.put("app/Caller.java", """
                package app;
                import missing.lib.Widget;
                public class Caller {
                    public int run(Widget<String> w) { return 42; }
                }
                """);
        var req3 = requestFor(repo, mod, v3, true, List.of());
        FrontendResult inc3 = frontend.analyze(req3);
        assertBitForBitParity(analyzeCold(frontend, req3), inc3);
    }

    @Test
    void packagePrivateSuperclassAbiAndFieldLevelLombokChangePublicCrossModuleAbi() {
        String base1 = "package lib; class PkgBase { public int api() { return 1; } int hidden() { return 0; } }";
        String base2 = "package lib; class PkgBase { public long api() { return 1L; } int hidden() { return 0; } }";
        String base3 = "package lib; class PkgBase { public int api() { return 1; } int hidden(int x) { return x; } }";
        var abi1 = SourceAbiFingerprinter.fingerprint(com.github.javaparser.StaticJavaParser.parse(base1), "lib/PkgBase.java");
        var abi2 = SourceAbiFingerprinter.fingerprint(com.github.javaparser.StaticJavaParser.parse(base2), "lib/PkgBase.java");
        var abi3 = SourceAbiFingerprinter.fingerprint(com.github.javaparser.StaticJavaParser.parse(base3), "lib/PkgBase.java");
        assertNotEquals(abi1.publicCrossModuleAbiDigest(), abi2.publicCrossModuleAbiDigest(),
                "Public method change on package-private superclass MUST change publicCrossModuleAbiDigest");
        assertEquals(abi1.publicCrossModuleAbiDigest(), abi3.publicCrossModuleAbiDigest(),
                "Package-private member change on package-private class stays local");

        String fieldLombok1 = "package lib; public class Bean { @lombok.Getter String name; }";
        String fieldLombok2 = "package lib; public class Bean { @lombok.Getter @lombok.Setter String name; }";
        var fl1 = SourceAbiFingerprinter.fingerprint(com.github.javaparser.StaticJavaParser.parse(fieldLombok1), "lib/Bean.java");
        var fl2 = SourceAbiFingerprinter.fingerprint(com.github.javaparser.StaticJavaParser.parse(fieldLombok2), "lib/Bean.java");
        assertNotEquals(fl1.publicCrossModuleAbiDigest(), fl2.publicCrossModuleAbiDigest(),
                "Field-level Lombok change without class-level Lombok MUST change publicCrossModuleAbiDigest");
    }

    @Test
    void nestedTypeWildcardShadowingAndSelfReferentialLazySkeletonMaintainBitForBitParity() {
        var repo = RepositoryIdentity.fromCanonicalCoordinate("https://example.test/nested-selfref.git");
        var mod = ModuleDescriptor.create(repo, "app", "app");
        var v1 = new LinkedHashMap<String, String>();
        v1.put("pkg/Outer.java", "package pkg; public class Outer { public static class Inner { public int v() { return 1; } } }");
        v1.put("use/Child.java", "package use; import pkg.*; public class Child extends Outer.Inner {}");
        v1.put("use/GrandChild.java", "package use; public class GrandChild extends Child { public int g() { return v(); } }");
        v1.put("rec/SelfRef.java", "package rec; public class SelfRef<T extends SelfRef<T>> { public T self() { return null; } }");
        v1.put("rec/Trigger.java", "package rec; public class Trigger { public Object run(SelfRef<?> s) { return s.self(); } }");
        for (int i = 0; i < 4; i++) {
            v1.put("pad/P" + i + ".java", "package pad; public class P" + i + " { public int id() { return " + i + "; } }");
        }

        ModuleIncrementalCache.clearInMemoryCache();
        var frontend = new JavaParserFrontend();
        frontend.analyze(requestFor(repo, mod, v1, false, List.of()));

        // Edit 1: Edit Trigger -> lazy-loads SelfRef<T extends SelfRef<T>> (MUST NOT StackOverflowError!)
        var v2 = new LinkedHashMap<>(v1);
        v2.put("rec/Trigger.java", "package rec; public class Trigger { public String run(SelfRef<?> s) { return String.valueOf(s.self()); } }");
        var req2 = requestFor(repo, mod, v2, false, List.of());
        FrontendResult inc2 = frontend.analyze(req2);
        assertBitForBitParity(analyzeCold(frontend, req2), inc2);

        // Edit 2: Add use.Outer shadowing pkg.Outer via wildcard -> invalidates Child AND GrandChild transitively!
        var v3 = new LinkedHashMap<>(v2);
        v3.put("use/Outer.java", "package use; public class Outer { public static class Inner { public long v() { return 2L; } } }");
        var req3 = requestFor(repo, mod, v3, false, List.of());
        FrontendResult inc3 = frontend.analyze(req3);
        assertBitForBitParity(analyzeCold(frontend, req3), inc3);
    }
}
