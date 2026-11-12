package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** M9.2 source-authored controls; a compiler error is never used as a binding oracle. */
class M9RecoveryEvidenceTest {
    @TempDir Path temp;

    @Test void missingImportsCannotInventTypesMembersOrOrigins() throws Exception {
        String source = """
                import absent.library.Client;
                import static absent.library.Client.send;
                class C { Client field; void run() { field.send("x"); send("x");
                    Client.send("x"); new Client("x"); int n = Client.VALUE; } }
                """;
        assertCompilerRejects(source);
        var result = recover(source);
        for (String category : List.of("field-type", "calls", "constructor-calls", "reads-field")) {
            var rows = observations(result, category);
            if (category.equals("reads-field")) rows = rows.stream().filter(o -> o.reference().equals("Client.VALUE")).toList();
            assertFalse(rows.isEmpty(), category + " cannot disappear");
            assertTrue(rows.stream().noneMatch(o -> o.attribution() == SemanticStatus.RESOLVED), category);
            assertTrue(rows.stream().allMatch(o -> o.provenance() == ObservationRecord.EvidenceState.VERIFIED));
        }
        assertTrue(observations(result, "reads-field").stream().anyMatch(o -> o.reference().equals("field") && o.attribution() == SemanticStatus.RESOLVED),
                "The written source field exists even though its type is unknown");
        assertNoInventedDeclaration(result);
        assertEquals(FrontendResult.State.PARTIAL, result.state());
    }

    @Test void wildcardAndFrameworkLookalikesCannotProveADeclaration() throws Exception {
        for (String source : List.of(
                "import absent.library.*; class C { Imaginary field; }",
                "import org.springframework.stereotype.*; @Service class C {}",
                "import org.springframework.stereotype.*; @Imaginary class C {}",
                "class C { org.springframework.stereotype.Imaginary field; }")) {
            assertCompilerRejects(source);
            var result = recover(source);
            var rows = result.observations().stream().filter(o -> Set.of("java.field-type", "java.annotated-with").contains(o.category().value())).toList();
            assertFalse(rows.isEmpty());
            assertTrue(rows.stream().noneMatch(o -> o.attribution() == SemanticStatus.RESOLVED), source);
            assertNoInventedDeclaration(result);
        }
    }

    @Test void unknownArgumentCannotSelectAnExactOverload() throws Exception {
        String source = """
                import absent.library.Payload;
                class C { void choose(Object value) {} void choose(CharSequence value) {}
                    void run(Payload value) { choose(value); } }
                """;
        assertCompilerRejects(source);
        var result = recover(source);
        assertEquals(1, observations(result, "calls").size());
        assertNotEquals(SemanticStatus.RESOLVED, observations(result, "calls").getFirst().attribution());
    }

    @Test void androidNamesCannotSubstituteForSdkOrGeneratorEvidence() throws Exception {
        String source = """
                package app;
                import androidx.appcompat.app.AppCompatActivity;
                class C extends AppCompatActivity { void run() { setContentView(R.layout.main);
                    boolean debug = BuildConfig.DEBUG; } }
                """;
        assertCompilerRejects(source);
        var result = recover(source);
        for (String category : List.of("extends", "calls", "reads-field")) {
            var rows = observations(result, category);
            assertFalse(rows.isEmpty(), category);
            assertTrue(rows.stream().noneMatch(o -> o.attribution() == SemanticStatus.RESOLVED), category);
        }
        assertNoInventedDeclaration(result);
    }

    @Test void callableIdentityCannotUseAGuessedImportedParameterType() throws Exception {
        String source = "import absent.library.Payload; class C { void choose(Payload value) {} void run(){ choose(null); } }";
        assertCompilerRejects(source);
        var result = recover(source);
        assertEquals(1, observations(result,"calls").size());
        assertNotEquals(SemanticStatus.RESOLVED,observations(result,"calls").getFirst().attribution());
        assertTrue(result.declarations().stream().noneMatch(d -> d.entity().canonicalName().contains("\"choose\"")),
                "A guessed parameter erasure cannot become a canonical callable identity");
        assertTrue(observations(result,"declares").stream().anyMatch(o -> o.reference().contains("choose(Payload")
                && o.attribution()==SemanticStatus.UNRESOLVED && o.span().isPresent()),"Unmappable declaration stays in the ledger");
    }

    @Test void exactRemovalAndRestorationKeepTargetsSpansAndReplayIdentity() throws Exception {
        var helper = new ResolutionInputsTest(); helper.temp = temp;
        var binary = helper.jar("m9-library", "package dep; public class Library { public static String text(){ return \"x\"; } }");
        String source = "import dep.Library; class C { String run(){ return Library.text().trim(); } }";
        var full = TestInputs.request(Map.of("fixture/C.java", source), List.of(binary));
        var first = new JavaParserFrontend().analyze(full);
        var removedRequest = LombokIngestionTest.recovery(TestInputs.request(source));
        var removed = new JavaParserFrontend().analyze(removedRequest);
        assertEquals(2, observations(first, "calls").size());
        assertTrue(observations(first, "calls").stream().allMatch(o -> o.attribution() == SemanticStatus.RESOLVED));
        assertEquals(2, observations(removed, "calls").size());
        assertTrue(observations(removed, "calls").stream().noneMatch(o -> o.attribution() == SemanticStatus.RESOLVED));
        assertNoInventedDeclaration(removed);
        assertNotEquals(full.manifest().identity(), removedRequest.manifest().identity());
        assertEquals(CanonicalJson.write(first), CanonicalJson.write(new JavaParserFrontend().analyze(full)));
        assertEquals(observations(first, "calls").stream().map(ObservationRecord::span).toList(),
                observations(removed, "calls").stream().map(ObservationRecord::span).toList());
    }

    @Test void sameSizeAndMtimeCannotBypassDependencyByteVerification() throws Exception {
        var helper = new ResolutionInputsTest(); helper.temp = temp;
        var small = helper.jar("m9-integrity", "package dep; public class Library { public static int number(){ return 1; } }");
        Files.write(small.path(), new byte[70_000], StandardOpenOption.APPEND);
        var stamp = FileTime.fromMillis(1_600_000_000_000L);
        Files.setLastModifiedTime(small.path(), stamp);
        byte[] original = Files.readAllBytes(small.path());
        var binary = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                small.entry().logicalName(), ContentDigest.sha256(original)), small.path());
        var request = TestInputs.request(Map.of("fixture/C.java", "class C { int run(){return dep.Library.number();} }"), List.of(binary));
        new JavaParserFrontend().analyze(request);
        new JavaParserFrontend().analyze(request);
        byte[] changed = original.clone(); changed[changed.length - 1] = 1;
        Files.write(binary.path(), changed); Files.setLastModifiedTime(binary.path(), stamp);
        assertEquals(original.length, Files.size(binary.path()));
        assertEquals(stamp, Files.getLastModifiedTime(binary.path()));
        assertEquals("frontend.artifact-digest", assertThrows(FrontendInputException.class,
                () -> new JavaParserFrontend().analyze(request)).diagnostic().code());
    }

    private static FrontendResult recover(String source) {
        return new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
    }
    private static List<ObservationRecord> observations(FrontendResult result, String category) {
        return result.observations().stream().filter(o -> o.category().value().equals("java." + category)).toList();
    }
    private static void assertNoInventedDeclaration(FrontendResult result) {
        assertTrue(result.declarations().stream().noneMatch(d -> Set.of("java.explicit-import-recovery", "java.android-synthetic-recovery").contains(d.derivation().method().id())),
                "Syntax guesses must not create canonical declarations");
    }
    private void assertCompilerRejects(String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path file = temp.resolve("C.java"); Files.writeString(file, source);
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, List.of());
            manager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(temp));
            assertFalse(compiler.getTask(null, manager, diagnostics, List.of("--release", "21", "-proc:none", "-implicit:none"),
                    null, manager.getJavaFileObjects(file)).call());
            assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == javax.tools.Diagnostic.Kind.ERROR));
        }
    }
}
