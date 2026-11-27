package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SourceAbiFingerprinter} and {@link FileDependencyIndex} covering:
 * <ul>
 *   <li>Body-only / comment / line-shift / private-helper Early Cutoff</li>
 *   <li>Cross-module package-private isolation ({@code publicCrossModuleAbiDigest})</li>
 *   <li>ABI-breaking edits (signatures, constants, Lombok private fields, records, sealed permits, sole private ctor, Spring annotations)</li>
 *   <li>Unbounded 6-hop inheritance propagation (Bug #1 fix — no {@code hops < 4} cap)</li>
 *   <li>Scoped wildcard package &amp; static wildcard invalidation (Bug #5 fix — never blast {@code java.util.*})</li>
 *   <li>{@code FAST_ARCHITECTURE} {@code declaredSimpleNames} symmetric diff invalidation (Bug #3 fix)</li>
 * </ul>
 */
class SourceAbiFingerprinterAndDependencyIndexTest {
    private static final JavaParser PARSER = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

    private static SourceAbiFingerprinter.AbiSummary fp(String code, String path) {
        CompilationUnit cu = PARSER.parse(code).getResult().orElseThrow();
        return SourceAbiFingerprinter.fingerprint(cu, path);
    }

    @Test
    void bodyCommentsLineShiftsAndPrivateHelpersPreserveAllAbiDigests() {
        var v1 = fp("""
                package com.acme;
                public class Service {
                    private int internalCounter = 1;
                    private int helper(int x) { return x + 1; }
                    public String greet(String who) {
                        return "hi " + who + helper(internalCounter);
                    }
                }
                """, "com/acme/Service.java");

        var v2 = fp("""
                package com.acme;
                // Leading comment and 10 shifted lines



                public class Service {
                    private String renamedPrivateField = "abc";
                    private long anotherPrivateHelper(String s) { return s.length() * 2L; }
                    public String greet(String who) {
                        /* Multi-line body rewrite */
                        long n = anotherPrivateHelper(who + renamedPrivateField);
                        return "hello " + who + ":" + n;
                    }
                }
                """, "com/acme/Service.java");

        assertEquals(v1.externalAbiDigest(), v2.externalAbiDigest());
        assertEquals(v1.publicCrossModuleAbiDigest(), v2.publicCrossModuleAbiDigest());
        assertEquals(v1.springRelevantDigest(), v2.springRelevantDigest());
        assertEquals(v1.staticMembersDigest(), v2.staticMembersDigest());
    }

    @Test
    void packagePrivateEditChangesExternalAbiButPreservesPublicCrossModuleAbi() {
        var v1 = fp("""
                package com.acme;
                public class Api {
                    String internalHelper() { return "a"; }
                    public int compute(int x) { return x; }
                }
                """, "com/acme/Api.java");
        var v2 = fp("""
                package com.acme;
                public class Api {
                    int internalHelper(String s) { return s.length(); }
                    public int compute(int x) { return x * 2; }
                }
                """, "com/acme/Api.java");

        assertNotEquals(v1.externalAbiDigest(), v2.externalAbiDigest());
        assertEquals(v1.publicCrossModuleAbiDigest(), v2.publicCrossModuleAbiDigest());
    }

    @Test
    void abiBreakingEditsChangeRelevantDigests() {
        // 1. Static final compile-time constant change
        var c1 = fp("package p; public class Consts { public static final String URL = \"/v1\"; }", "p/Consts.java");
        var c2 = fp("package p; public class Consts { public static final String URL = \"/v2\"; }", "p/Consts.java");
        assertNotEquals(c1.externalAbiDigest(), c2.externalAbiDigest());
        assertNotEquals(c1.publicCrossModuleAbiDigest(), c2.publicCrossModuleAbiDigest());
        assertNotEquals(c1.staticMembersDigest(), c2.staticMembersDigest());

        // 2. Lombok @Data private field change affects external & cross-module ABI
        var l1 = fp("package p; import lombok.Data; @Data public class Dto { private String name; }", "p/Dto.java");
        var l2 = fp("package p; import lombok.Data; @Data public class Dto { private String fullName; }", "p/Dto.java");
        assertNotEquals(l1.externalAbiDigest(), l2.externalAbiDigest());
        assertNotEquals(l1.publicCrossModuleAbiDigest(), l2.publicCrossModuleAbiDigest());

        // 3. Record component reorder changes ABI
        var r1 = fp("package p; public record Pair(String a, int b) {}", "p/Pair.java");
        var r2 = fp("package p; public record Pair(int b, String a) {}", "p/Pair.java");
        assertNotEquals(r1.externalAbiDigest(), r2.externalAbiDigest());

        // 4. Sealed permits list change
        var s1 = fp("package p; public sealed interface Shape permits Circle {}", "p/Shape.java");
        var s2 = fp("package p; public sealed interface Shape permits Circle, Square {}", "p/Shape.java");
        assertNotEquals(s1.externalAbiDigest(), s2.externalAbiDigest());

        // 5. Sole private constructor removes implicit public default constructor!
        var d1 = fp("package p; public class Util { public static int f() { return 1; } }", "p/Util.java");
        var d2 = fp("package p; public class Util { private Util() {} public static int f() { return 1; } }", "p/Util.java");
        assertNotEquals(d1.externalAbiDigest(), d2.externalAbiDigest());
        assertNotEquals(d1.publicCrossModuleAbiDigest(), d2.publicCrossModuleAbiDigest());

        // 6. Spring annotation argument change updates springRelevantDigest
        var sp1 = fp("package p; import org.springframework.web.bind.annotation.*; public class C { @GetMapping(\"/a\") public void x() {} }", "p/C.java");
        var sp2 = fp("package p; import org.springframework.web.bind.annotation.*; public class C { @GetMapping(\"/b\") public void x() {} }", "p/C.java");
        assertNotEquals(sp1.springRelevantDigest(), sp2.springRelevantDigest());
        assertNotEquals(sp1.externalAbiDigest(), sp2.externalAbiDigest());
    }

    private static SourceDocumentIdentity doc(String name) {
        return new SourceDocumentIdentity("source:" + ContentDigest.sha256Utf8(name).value());
    }

    private static FileDependencyIndex.UnitNode node(
            String id, String pkg, Set<String> fqns, Set<String> simpleNames,
            Set<String> supertypes, Set<String> explicitImports, Set<String> wildcardPkgs,
            Set<String> staticWildcards, Set<String> refFqns, Set<String> refSimpleNames, String abiSeed) {
        return new FileDependencyIndex.UnitNode(
                doc(id), id, ContentDigest.sha256Utf8(id + ":" + abiSeed),
                ContentDigest.sha256Utf8("ext:" + abiSeed),
                ContentDigest.sha256Utf8("pub:" + abiSeed),
                ContentDigest.sha256Utf8("spr:" + abiSeed),
                ContentDigest.sha256Utf8("sta:" + abiSeed),
                pkg, fqns, simpleNames, supertypes, explicitImports, wildcardPkgs, staticWildcards,
                refFqns, refSimpleNames, false, false);
    }

    @Test
    void unboundedFixpointPropagatesAcrossSixHopInheritanceChainAndScopesWildcards() {
        var prev = new LinkedHashMap<SourceDocumentIdentity, FileDependencyIndex.UnitNode>();
        // 6-hop chain: Hop0 <- Hop1 <- Hop2 <- Hop3 <- Hop4 <- Hop5 <- LeafCaller
        prev.put(doc("Hop0"), node("Hop0", "p0", Set.of("p0.Hop0"), Set.of("Hop0"),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "v1"));
        for (int h = 1; h <= 5; h++) {
            String prevFqn = "p" + (h - 1) + ".Hop" + (h - 1);
            String curFqn = "p" + h + ".Hop" + h;
            prev.put(doc("Hop" + h), node("Hop" + h, "p" + h, Set.of(curFqn), Set.of("Hop" + h),
                    Set.of(prevFqn), Set.of(prevFqn), Set.of(), Set.of(), Set.of(prevFqn), Set.of("Hop" + (h - 1)), "v1"));
        }
        prev.put(doc("LeafCaller"), node("LeafCaller", "p6", Set.of("p6.LeafCaller"), Set.of("LeafCaller"),
                Set.of(), Set.of("p5.Hop5"), Set.of(), Set.of(), Set.of("p5.Hop5"), Set.of("Hop5"), "v1"));

        // Bug #5 test nodes: 20 unrelated clean files with `import java.util.*;` (must NOT be invalidated!)
        for (int u = 0; u < 20; u++) {
            prev.put(doc("Unrelated" + u), node("Unrelated" + u, "other", Set.of("other.Unrelated" + u),
                    Set.of("Unrelated" + u), Set.of(), Set.of(), Set.of("java.util"), Set.of(),
                    Set.of("java.util.List"), Set.of("List"), "v1"));
        }
        // Targeted wildcard package importer `import p0.*;` (MUST be invalidated!)
        prev.put(doc("PkgWildcard"), node("PkgWildcard", "consumer", Set.of("consumer.PkgWildcard"),
                Set.of("PkgWildcard"), Set.of(), Set.of(), Set.of("p0"), Set.of(), Set.of(), Set.of(), "v1"));
        // Targeted static wildcard importer `import static p0.Hop0.*;` (MUST be invalidated!)
        prev.put(doc("StaticWildcard"), node("StaticWildcard", "consumer2", Set.of("consumer2.StaticWildcard"),
                Set.of("StaticWildcard"), Set.of(), Set.of(), Set.of(), Set.of("p0.Hop0"), Set.of(), Set.of(), "v1"));

        var changed = Map.of(doc("Hop0"), node("Hop0", "p0", Set.of("p0.Hop0"), Set.of("Hop0"),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "v2-breaking"));

        var res = FileDependencyIndex.computeDirtyClosure(
                prev, changed, Set.of(), Set.of(), Set.of(), false, prev.size(), 0.55);

        assertFalse(res.exceededSafetyCeiling());
        // ALL 6 hops + LeafCaller + PkgWildcard + StaticWildcard = 9 dirty files (proving no hops < 4 cap!)
        for (int h = 0; h <= 5; h++) {
            assertTrue(res.dirtyDocuments().contains(doc("Hop" + h)), "Missing Hop" + h);
        }
        assertTrue(res.dirtyDocuments().contains(doc("LeafCaller")));
        assertTrue(res.dirtyDocuments().contains(doc("PkgWildcard")));
        assertTrue(res.dirtyDocuments().contains(doc("StaticWildcard")));
        // None of the 20 `import java.util.*;` files were invalidated!
        for (int u = 0; u < 20; u++) {
            assertFalse(res.dirtyDocuments().contains(doc("Unrelated" + u)), "java.util.* must NOT be blasted!");
        }
        assertEquals(9, res.dirtyDocuments().size());
    }

    @Test
    void nestedTypeWildcardImportInvalidatedDirectlyAndThroughSubtypes() {
        var prev = new LinkedHashMap<SourceDocumentIdentity, FileDependencyIndex.UnitNode>();
        prev.put(doc("Outer"), node("Outer", "com.acme", Set.of("com.acme.Outer"), Set.of("Outer"),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "v1"));
        prev.put(doc("SubOuter"), node("SubOuter", "com.acme.sub", Set.of("com.acme.sub.SubOuter"), Set.of("SubOuter"),
                Set.of("com.acme.Outer"), Set.of("com.acme.Outer"), Set.of(), Set.of(), Set.of("com.acme.Outer"), Set.of("Outer"), "v1"));
        // Direct nested wildcard importer: `import com.acme.Outer.*;` (no reference to com.acme.Outer yet!)
        prev.put(doc("DirectClient"), node("DirectClient", "use", Set.of("use.DirectClient"), Set.of("DirectClient"),
                Set.of(), Set.of(), Set.of("com.other2", "com.acme.Outer"), Set.of(),
                Set.of("com.other2.Inner"), Set.of("Inner"), "v1"));
        // Inherited nested wildcard importer: `import com.acme.sub.SubOuter.*;`
        prev.put(doc("InheritedClient"), node("InheritedClient", "use2", Set.of("use2.InheritedClient"), Set.of("InheritedClient"),
                Set.of(), Set.of(), Set.of("com.other2", "com.acme.sub.SubOuter"), Set.of(),
                Set.of("com.other2.Inner"), Set.of("Inner"), "v1"));
        // Consumer of DirectClient whose signature may change if DirectClient's Inner changes
        prev.put(doc("DownstreamOfClient"), node("DownstreamOfClient", "use3", Set.of("use3.DownstreamOfClient"), Set.of("DownstreamOfClient"),
                Set.of(), Set.of("use.DirectClient"), Set.of(), Set.of(),
                Set.of("use.DirectClient"), Set.of("DirectClient"), "v1"));
        // 15 unrelated files importing java.util.* must remain clean
        for (int i = 0; i < 15; i++) {
            prev.put(doc("Clean" + i), node("Clean" + i, "clean", Set.of("clean.Clean" + i), Set.of("Clean" + i),
                    Set.of(), Set.of(), Set.of("java.util"), Set.of(), Set.of("java.util.List"), Set.of("List"), "v1"));
        }

        // Outer adds nested class Inner
        var changed = Map.of(doc("Outer"), node("Outer", "com.acme",
                Set.of("com.acme.Outer", "com.acme.Outer.Inner"), Set.of("Outer", "Inner"),
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), "v2-added-inner"));

        var res = FileDependencyIndex.computeDirtyClosure(
                prev, changed, Set.of(), Set.of(), Set.of(), false, prev.size(), 0.55);

        assertFalse(res.exceededSafetyCeiling());
        assertTrue(res.dirtyDocuments().contains(doc("Outer")));
        assertTrue(res.dirtyDocuments().contains(doc("SubOuter")));
        assertTrue(res.dirtyDocuments().contains(doc("DirectClient")),
                "import com.acme.Outer.* must be invalidated when com.acme.Outer adds nested Inner");
        assertTrue(res.dirtyDocuments().contains(doc("InheritedClient")),
                "import com.acme.sub.SubOuter.* must be invalidated when supertype Outer adds nested Inner");
        assertTrue(res.dirtyDocuments().contains(doc("DownstreamOfClient")),
                "Consumers of invalidated nested-wildcard importer must also be invalidated");
        for (int i = 0; i < 15; i++) {
            assertFalse(res.dirtyDocuments().contains(doc("Clean" + i)));
        }
        assertEquals(5, res.dirtyDocuments().size());
    }
}
