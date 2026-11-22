package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.contract.source.SourceSpan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CapturedRepairTest {
    private static final JavaRepairSyntax.Profile JAVA_21=new JavaRepairSyntax.Profile(21,false,"TEST",Optional.empty());
    @TempDir Path temp;

    @Test void modelContextUsesLargeCapturedFilesAndFitsCitedExcerptsWhenNeeded() {
        String prefix="class Example {\n"+"// padding outside the finding\n".repeat(2400);
        String text=prefix+"    int citedField;\n}\n";
        var digest=ContentDigest.sha256Utf8(text);
        var path="Example.java";
        var document=SourceDocumentIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:java-repair"),path);
        var citation=new FindingDiagnosis.Citation(digest,document.value(),digest,"test.span",path,
                new SourceSpan(document,2402,5,2402,20),digest);
        var file=new RepairSource.File(path,digest,digest,text,true,"test",SourceClassification.MAIN,List.of(citation));
        var endpoint=java.net.URI.create("http://127.0.0.1:8081/v1");
        for (int budget : List.of(1024*1024,16*1024)) {
            var settings=new ChatCompletions.Settings(budget,8192,"max_tokens",false,java.time.Duration.ofSeconds(120));
            var model=new RepairChatModel(endpoint,"example",null,false,settings);
            var packet=ModelSources.fit(List.of(file), selections -> model.request(Map.of("files",
                    selections.stream().map(ModelSources.Selection::payload).toList()),List.of(digest)));
            assertTrue(packet.bytes().length <= budget);
            var scope=packet.files().getFirst();
            assertTrue(scope.contains(prefix.length(),prefix.length()+"    int citedField;".length()));
            if (budget > 65_536) {
                assertTrue(scope.full());
                assertTrue(packet.bytes().length > 65_536);
            } else {
                assertFalse(scope.full());
                assertFalse(scope.contains(0,1));
                assertTrue(scope.slices().getFirst().startLine() > 1);
            }
        }
    }

    @Test void approximateAnchorsRequireUniqueOriginalLinesAndPreserveLiterals() {
        String source="class C {\r\n\tint first;\r\n\tint second;\r\n}";
        var digest=ContentDigest.sha256Utf8(source);
        var change=new RepairChatModel.Change(1,digest,"  int first;\n  int second;","  int merged;\n  int another;",null);
        var edit=RepairAnchors.locate(source,change);
        assertEquals("\tint first;\r\n\tint second;",source.substring(edit.start(),edit.end()));
        assertEquals("\tint merged;\r\n\tint another;",edit.replacement());

        String repeated="class C {\n    int value;\n    int value;\n}";
        var ambiguous=new RepairChatModel.Change(1,digest,"  int value;","  int other;",null);
        assertThrows(IllegalArgumentException.class,() -> RepairAnchors.locate(repeated,ambiguous));
        var anchored=new RepairChatModel.Change(1,digest,"  int value;","  int other;",3);
        assertEquals(repeated.lastIndexOf("    int value;"),RepairAnchors.locate(repeated,anchored).start());

        String literal="class C {\n    String value = \"two  spaces\";\n}";
        var wrong=new RepairChatModel.Change(1,digest,"  String value = \"two spaces\";","  String value = \"changed\";",null);
        assertThrows(IllegalArgumentException.class,() -> RepairAnchors.locate(literal,wrong));
    }

    @Test void recipesRespectUseAndCitationBoundaries() {
        assertEquals("class Client { Client() {} }",transform(JavaRepairRecipes.Recipe.CONSTRUCTOR_PARAMETER,
                "class Client { Client(Store store) {} }","Store"));
        assertEquals("\nclass C {}",transform(JavaRepairRecipes.Recipe.IMPORT,"import java.util.Date;\nclass C {}","java.util.Date"));
        assertNull(transform(JavaRepairRecipes.Recipe.IMPORT,"import java.util.Date;\nclass C { Date date; }","java.util.Date"));
        assertNull(transform(JavaRepairRecipes.Recipe.PRIVATE_FIELD,
                "class C { private Store store; Store get() { return this.store; } }","Store"));
        assertNull(transform(JavaRepairRecipes.Recipe.PRIVATE_FIELD,
                "class C { private Store x, y; }","Store"));
        assertNull(transform(JavaRepairRecipes.Recipe.CONSTRUCTOR_PARAMETER,
                "class Client { Client(Store s) { s.start(); } }","Store"));
        assertNull(transform(JavaRepairRecipes.Recipe.CONSTRUCTOR_PARAMETER,
                "class Client { Client(Store s) {} }","class Client"));
    }

    @Test void compilerChecksThePinnedLanguageLevelWithoutResolvingOrExecutingReferences() {
        String source="record Example(MissingDependency field) {}";
        assertEquals(JavaRepairSyntax.Status.PASSED,JavaRepairSyntax.parse(source,JAVA_21).check().status());
        var old=new JavaRepairSyntax.Profile(8,false,"TEST",Optional.empty());
        assertEquals(JavaRepairSyntax.Status.REJECTED,JavaRepairSyntax.parse(source,old).check().status());
        var future=new JavaRepairSyntax.Profile(9999,false,"TEST",Optional.empty());
        assertEquals(JavaRepairSyntax.Status.UNAVAILABLE,JavaRepairSyntax.parse(source,future).check().status());
    }

    @Test void gitAppliesQuotedPathsWithMixedLineEndingsAndNoFinalNewline() throws Exception {
        String path="nested space/mód/Example.java";
        String before="class Example {\r\n  private Dependency field;\r\n  int count;\n}";
        String after="class Example {\r\n  int count;\n}";
        Path file=temp.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file,before);
        Path diff=Files.writeString(temp.resolve("candidate.patch"),RepairPatch.file(path,before,after));
        assertEquals(0,apply(diff,"--check"));
        assertEquals(0,apply(diff));
        assertArrayEquals(after.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(file));
        Files.writeString(file,before.replace("Dependency field","Dependency other"));
        assertNotEquals(0,apply(diff,"--check"));
    }

    private int apply(Path diff,String... options) throws Exception {
        var args=new ArrayList<>(List.of("git","-c","core.autocrlf=false","apply","--whitespace=nowarn"));
        args.addAll(List.of(options));
        args.add(diff.toString());
        var process=new ProcessBuilder(args).directory(temp.toFile()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(10,TimeUnit.SECONDS));
        return process.exitValue();
    }

    private static String transform(JavaRepairRecipes.Recipe recipe,String text,String cited) {
        var digest=ContentDigest.sha256Utf8(text);
        var path="Example.java";
        var id=SourceDocumentIdentity.from(RepositoryIdentity.fromCanonicalCoordinate("urn:test:java-repair"),path);
        int offset=text.indexOf(cited);
        var citation=new FindingDiagnosis.Citation(digest,id.value(),digest,"test.span",path,
                new SourceSpan(id,1,offset+1,1,offset+cited.length()+1),digest);
        var source=new RepairSource.File(path,digest,digest,text,false,"test",SourceClassification.MAIN,List.of(citation));
        var matches=JavaRepairRecipes.edits(recipe,source,JavaRepairSyntax.parse(text,JAVA_21));
        return matches.isEmpty() ? null : JavaRepairRecipes.outside(text,matches.getFirst());
    }

    @Test void removesUnusedInjectedFieldAndConstructorDependencyWithImportPruning() {
        String injectedSource = """
                package demo;
                import example.UnusedDep;
                import org.springframework.beans.factory.annotation.Autowired;
                class Controller {
                    @Autowired
                    private UnusedDep unusedDep;
                    int active() { return 1; }
                }
                """;
        var injectedDoc = new com.evolution.analysis.contract.identity.SourceDocumentIdentity("source:sha256:" + "a".repeat(64));
        var injectedDecl = new FindingDiagnosis.Citation(new ContentDigest("sha256:" + "1".repeat(64)),
                "analysis:1", new ContentDigest("sha256:" + "3".repeat(64)), "java.occurrence",
                "src/main/java/demo/Controller.java",
                new com.evolution.analysis.contract.source.SourceSpan(injectedDoc, 6, 1, 6, 30),
                ContentDigest.sha256Utf8(injectedSource));
        var injectedFile = new RepairSource.File("src/main/java/demo/Controller.java",
                ContentDigest.sha256Utf8(injectedSource), new ContentDigest("sha256:" + "4".repeat(64)),
                injectedSource, true, "demo", com.evolution.analysis.contract.source.SourceClassification.MAIN,
                List.of(injectedDecl));
        var parsedInjected = JavaRepairSyntax.parse(injectedSource, JAVA_21);
        var injectedCandidates = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.INJECTED_FIELD, injectedFile, parsedInjected);
        assertEquals(1, injectedCandidates.size());
        String afterInjected = injectedCandidates.getFirst();
        assertFalse(afterInjected.contains("unusedDep"));
        assertFalse(afterInjected.contains("import example.UnusedDep;"));
        assertFalse(afterInjected.contains("import org.springframework.beans.factory.annotation.Autowired;"));
        assertEquals(JavaRepairSyntax.Status.PASSED, JavaRepairSyntax.parse(afterInjected, JAVA_21).check().status());

        String ctorSource = """
                package demo;
                import example.ActiveDep;
                import example.UnusedDep;
                class Service {
                    private final ActiveDep active;
                    private final UnusedDep unused;
                    Service(ActiveDep active, UnusedDep unused) {
                        this.active = active;
                        this.unused = java.util.Objects.requireNonNull(unused);
                    }
                    String go() { return active.toString(); }
                }
                """;
        var ctorDoc = new com.evolution.analysis.contract.identity.SourceDocumentIdentity("source:sha256:" + "b".repeat(64));
        var ctorCite = new FindingDiagnosis.Citation(new ContentDigest("sha256:" + "2".repeat(64)),
                "analysis:1", new ContentDigest("sha256:" + "5".repeat(64)), "java.occurrence",
                "src/main/java/demo/Service.java",
                new com.evolution.analysis.contract.source.SourceSpan(ctorDoc, 6, 1, 6, 30),
                ContentDigest.sha256Utf8(ctorSource));
        var ctorFile = new RepairSource.File("src/main/java/demo/Service.java",
                ContentDigest.sha256Utf8(ctorSource), new ContentDigest("sha256:" + "6".repeat(64)),
                ctorSource, true, "demo", com.evolution.analysis.contract.source.SourceClassification.MAIN,
                List.of(ctorCite));
        var parsedCtor = JavaRepairSyntax.parse(ctorSource, JAVA_21);
        var ctorCandidates = JavaRepairRecipes.candidates(JavaRepairRecipes.Recipe.CONSTRUCTOR_DEPENDENCY, ctorFile, parsedCtor);
        assertEquals(1, ctorCandidates.size());
        String afterCtor = ctorCandidates.getFirst();
        assertFalse(afterCtor.contains("UnusedDep"));
        assertFalse(afterCtor.contains("unused"));
        assertTrue(afterCtor.contains("ActiveDep active"));
        assertEquals(JavaRepairSyntax.Status.PASSED, JavaRepairSyntax.parse(afterCtor, JAVA_21).check().status());
    }

    @Test void appliesMultiFilePatchesPreservingLineEndings() {
        String aBefore = "class A {\r\n  int x = 1;\r\n}\r\n";
        String aAfter = "class A {\r\n  int x = 2;\r\n}\r\n";
        String bBefore = "class B {\n  int y = 3;\n}";
        String bAfter = "class B {\n  int y = 4;\n}";
        String patch = RepairPatch.file("m1/A.java", aBefore, aAfter) + RepairPatch.file("m2/B.java", bBefore, bAfter);
        var updated = RepairPatch.applyAll(Map.of("m1/A.java", aBefore, "m2/B.java", bBefore), patch);
        assertEquals(aAfter, updated.get("m1/A.java"));
        assertEquals(bAfter, updated.get("m2/B.java"));
    }
}
