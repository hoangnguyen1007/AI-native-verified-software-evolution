package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityScope;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ResolutionInputsTest {
    @TempDir Path temp;
    @Test void capturedEmptyJarIsAnExactEmptySearchSlot()throws Exception{
        Path path=temp.resolve("empty.jar");try(var output=new JarOutputStream(Files.newOutputStream(path))){}
        var binary=new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"empty:fixture:1",ContentDigest.sha256(Files.readAllBytes(path))),path);
        var result=new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java","class C { int count(){ return 1; } }"),List.of(binary)));
        assertTrue(result.declarations().stream().anyMatch(d->d.entity().kind()==EntityKind.TYPE));assertTrue(result.diagnostics().stream().noneMatch(d->d.severity()==DiagnosticSeverity.ERROR));
    }
    @Test void unprovedNativeOriginCannotBorrowFrameworkIdentity()throws Exception{
        var original=jar("native-original","package dep; public class Library { public static int number(){return 1;} }");
        Path view=temp.resolve("derived.jar");try(var source=new JarFile(original.path().toFile());var output=new JarOutputStream(Files.newOutputStream(view))){
            for(var entry:source.stream().toList()){output.putNextEntry(new JarEntry(entry.getName()));try(var input=source.getInputStream(entry)){input.transferTo(output);}output.closeEntry();}
            output.putNextEntry(new JarEntry("META-INF/view-correspondence.txt"));output.write("authored contract fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();
        }
        var nativeEntry=new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.springframework:spring-beans:6.2.0@jar",original.entry().contentDigest());
        var solverEntry=new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"captured-slot@CLASS_PATH:0",ContentDigest.sha256(Files.readAllBytes(view)));
        var binary=new BinaryInput(solverEntry,view,Optional.empty(),Optional.of(new BinaryInput.NativeOrigin(nativeEntry,ContentDigest.sha256Utf8("authored-slot"))));
        var rejected=assertThrows(FrontendInputException.class,()->TestInputs.request(Map.of("fixture/C.java","class C {}"),List.of(binary)));assertEquals("frontend.native-origin-unverified",rejected.diagnostic().code());
    }
    private Path compileClass(String directory, String code) throws Exception {
        Path root = temp.resolve(directory); Files.createDirectories(root.resolve("dep"));
        Path source = root.resolve("dep/Library.java"); Files.writeString(source, code);
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
                "-encoding", "UTF-8", "-d", root.toString(), source.toString());
        assertEquals(0, exit);
        return root.resolve("dep/Library.class");
    }
    BinaryInput jar(String name, String code) throws Exception {
        Path root = temp.resolve(name); Files.createDirectories(root);
        Path source = root.resolve("Library.java"); Files.writeString(source,code);
        // Only test-owned fixed source, no annotation processors or target build lifecycle.
        int exit = ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-proc:none","-encoding","UTF-8","-classpath",root.toString(),"-sourcepath",root.toString(),"-d",root.toString(),source.toString());
        assertEquals(0,exit);
        Path jar = temp.resolve(name + ".jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(root)) {
            for (var file : files.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                var entry = new JarEntry(root.relativize(file).toString().replace('\\','/')); entry.setTime(0);
                output.putNextEntry(entry); Files.copy(file,output); output.closeEntry();
            }
        }
        return new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"fixture:" + name + ":1",ContentDigest.sha256(Files.readAllBytes(jar))),jar);
    }
    private static List<RelationshipOccurrence> calls(FrontendResult result) { return result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.calls")).toList(); }
    @Test void exactJarOriginsAndRemovedDependencyAreEvidenceBacked() throws Exception {
        var dependency = jar("library","package javax.fixture; public class Library { public static String text(){return \"x\";} }");
        var source = Map.of("fixture/C.java","class C { String run(){return javax.fixture.Library.text().trim();} }");
        var request = TestInputs.request(source,List.of(dependency));
        var result = new JavaParserFrontend().analyze(request);
        assertEquals(2,calls(result).size()); assertTrue(calls(result).stream().allMatch(o -> o.status() == SemanticStatus.RESOLVED));
        var target = result.declarations().stream().map(DeclarationRecord::entity).filter(e -> e.canonicalName().contains("\"text\"")).findFirst().orElseThrow();
        assertEquals(EntityOrigin.DEPENDENCY,target.origin());
        assertEquals(EntityScope.external(EntityOrigin.DEPENDENCY,dependency.entry().logicalName(),dependency.entry().contentDigest()),target.stableScope());
        assertTrue(target.declaration().isEmpty());
        var removed = new JavaParserFrontend().analyze(TestInputs.request(source,List.of()));
        assertEquals(2,calls(removed).size()); assertTrue(calls(removed).stream().allMatch(o -> o.status() == SemanticStatus.UNRESOLVED));
        assertNotEquals(request.manifest().identity(),TestInputs.request(source,List.of()).manifest().identity());
    }
    @Test void classpathOrderControlsSelectionAndReportsDuplicateDefinitions() throws Exception {
        String code = "package dep; public class Library { public static int number; public static String text(){return \"x\";} }";
        var first = jar("first",code); var second = jar("second",code);
        var source = Map.of("fixture/C.java","class C { String run(){return dep.Library.text() + dep.Library.number;} }");
        var forward = TestInputs.request(source,List.of(first,second)); var reversed = TestInputs.request(source,List.of(second,first));
        var a = new JavaParserFrontend().analyze(forward); var b = new JavaParserFrontend().analyze(reversed);
        assertNotEquals(forward.manifest().identity(),reversed.manifest().identity());
        assertTrue(a.diagnostics().stream().anyMatch(d -> d.code().equals("java.duplicate-binary-type")));
        assertNotEquals(calls(a).getFirst().relationship().target(),calls(b).getFirst().relationship().target());
        assertEquals(1,FieldAccessTest.accesses(a).size());
        assertNotEquals(FieldAccessTest.accesses(a).getFirst().relationship().target(),FieldAccessTest.accesses(b).getFirst().relationship().target());
    }
    @Test void inheritedFieldsKeepTheirActualJarOriginAndMissingDependencyEvidence() throws Exception {
        var dependency = jar("fields","package javax.fixture; public class Library { public static int NUMBER; public int count; }");
        var source = Map.of("fixture/C.java","class C extends javax.fixture.Library { int run(){return NUMBER + this.count + javax.fixture.Library.NUMBER;} }");
        var request = TestInputs.request(source,List.of(dependency));
        var result = new JavaParserFrontend().analyze(request);
        var entities = TypeRelationshipsTest.entities(result);
        assertEquals(3,FieldAccessTest.accesses(result).size());
        for (var occurrence : FieldAccessTest.accesses(result)) {
            assertEquals(SemanticStatus.RESOLVED,occurrence.status());
            var target = entities.get(((RelationshipTarget.Resolved)occurrence.relationship().target()).target());
            assertEquals(EntityOrigin.DEPENDENCY,target.origin());
            assertEquals(EntityScope.external(EntityOrigin.DEPENDENCY,dependency.entry().logicalName(),dependency.entry().contentDigest()),target.stableScope());
            assertTrue(Set.of(FieldAccessTest.fieldName("javax.fixture.Library.NUMBER"),FieldAccessTest.fieldName("javax.fixture.Library.count")).contains(target.canonicalName()));
            assertTrue(target.declaration().isEmpty());
        }
        var removed = new JavaParserFrontend().analyze(TestInputs.request(source,List.of()));
        var ledger = removed.observations().stream().filter(o -> o.category().value().equals("java.reads-field")).toList();
        assertEquals(3,ledger.size());
        assertTrue(ledger.stream().allMatch(o -> o.attribution() == SemanticStatus.UNRESOLVED));
        assertEquals(1,ledger.stream().filter(o -> o.mappedOccurrence().isEmpty()).count());
        assertTrue(removed.declarations().stream().noneMatch(d -> d.entity().kind() == EntityKind.FIELD));
    }
    @Test void mutableArtifactHandlesAreRecheckedBeforeUse() throws Exception {
        var dependency = jar("changed","package dep; public class Library { public static void hit(){} }");
        var request = TestInputs.request(Map.of("fixture/C.java","class C {}"),List.of(dependency));
        Files.writeString(dependency.path(),"changed");
        var failure = assertThrows(FrontendInputException.class,() -> new JavaParserFrontend().analyze(request));
        assertEquals("frontend.artifact-digest",failure.diagnostic().code());
    }
    @Test void multiReleaseJarSelectsTheHighestVersionNotNewerThanTheTargetPlatform() throws Exception {
        Path base = compileClass("mr-base", "package dep; public class Library { public static void baseOnly(){} }");
        Path java17 = compileClass("mr-17", "package dep; public class Library { public static void selected(){} }");
        Path java22 = compileClass("mr-22", "package dep; public class Library { public static void tooNew(){} }");
        Path archive = temp.resolve("multi-release.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            for (var item : List.of(
                    Map.entry("dep/Library.class", base),
                    Map.entry("META-INF/versions/17/dep/Library.class", java17),
                    Map.entry("META-INF/versions/22/dep/Library.class", java22))) {
                var entry = new JarEntry(item.getKey()); entry.setTime(0);
                output.putNextEntry(entry); Files.copy(item.getValue(), output); output.closeEntry();
            }
        }
        var dependency = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "fixture:multi-release:1", ContentDigest.sha256(Files.readAllBytes(archive))), archive);

        var result = new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java",
                "class C { void run(){ dep.Library.selected(); dep.Library.tooNew(); } }"), List.of(dependency)));

        assertEquals(2, calls(result).size());
        assertEquals(1, calls(result).stream().filter(call -> call.status() == SemanticStatus.RESOLVED).count());
        assertEquals(1, calls(result).stream().filter(call -> call.status() == SemanticStatus.UNRESOLVED).count());
    }
    @Test void manifestClasspathIsExplicitlyDiagnosedWhileTheExactSuppliedClasspathRemainsAuthoritative() throws Exception {
        Path compiled = compileClass("manifest-classpath", "package dep; public class Library { public static void hit(){} }");
        Path archive = temp.resolve("manifest-classpath.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "unbound-neighbor.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            var entry = new JarEntry("dep/Library.class"); entry.setTime(0);
            output.putNextEntry(entry); Files.copy(compiled, output); output.closeEntry();
        }
        var dependency = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "fixture:manifest-classpath:1", ContentDigest.sha256(Files.readAllBytes(archive))), archive);

        var result = new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java",
                "class C { void run(){ dep.Library.hit(); } }"), List.of(dependency)));

        assertEquals(SemanticStatus.RESOLVED, calls(result).getFirst().status());
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic ->
                diagnostic.code().equals("frontend.jar-classpath-unmodeled")
                        && diagnostic.details().get("artifact").equals("fixture:manifest-classpath:1")));
    }
    @Test void missingSourceSpansRetainDistinctDeterministicAstProvenance() {
        var unit = new com.github.javaparser.ast.CompilationUnit();
        var body = unit.addClass("C").addMethod("run").createBody();
        var first = new MethodCallExpr("hit");
        var second = new MethodCallExpr("hit");
        body.addStatement(first); body.addStatement(second);

        var a = Extraction.missingSpanDiagnostic(first, new IllegalArgumentException());
        var b = Extraction.missingSpanDiagnostic(second, new IllegalArgumentException());

        assertNotEquals(a, b);
        assertNotEquals(a.details().get("astPath"), b.details().get("astPath"));
        assertEquals(a.details().get("astPath"),
                Extraction.missingSpanDiagnostic(first, new IllegalArgumentException()).details().get("astPath"));
    }
    @Test void hostApplicationDependenciesNeverLeakIntoAnalysis() {
        var result = new JavaParserFrontend().analyze(TestInputs.request("class C { void run(){org.junit.jupiter.api.Assertions.assertTrue(true);} }"));
        assertEquals(1,calls(result).size()); assertEquals(SemanticStatus.UNRESOLVED,calls(result).getFirst().status());
    }
    @Test void sourceInputPermutationIsDeterministicAndCrossFileTargetsRemainProject() {
        var sources = new LinkedHashMap<String,String>(); sources.put("fixture/B.java","class B { static String hit(){return \"x\";} }"); sources.put("fixture/A.java","class A { String run(){return B.hit();} }");
        var a = TestInputs.request(sources,List.of());
        var b = new FrontendRequest(a.manifest(),a.module(),a.sourceSet(),a.sources().reversed(),a.platform(),a.dependencies());
        var result = new JavaParserFrontend().analyze(a);
        assertEquals(CanonicalJson.write(result),CanonicalJson.write(new JavaParserFrontend().analyze(b)));
        assertEquals(1,calls(result).size());
        var targetId = ((RelationshipTarget.Resolved)calls(result).getFirst().relationship().target()).target();
        assertEquals(EntityOrigin.PROJECT,result.declarations().stream().map(DeclarationRecord::entity).filter(e -> e.identity().equals(targetId)).findFirst().orElseThrow().origin());
    }
    @Test void wrongPlatformHashIsRejectedBeforeParsing() {
        var valid = TestInputs.request("class C {}");
        var original = valid.platform().artifacts().getFirst();
        var invalidPlatform = PlatformInput.create(valid.platform().release(), valid.platform().version(),
                valid.platform().vendor(), List.of(new PlatformInput.Artifact(original.logicalName(),
                        ContentDigest.sha256Utf8("wrong"), original.path(), original.format())));
        var request = TestInputs.request(valid.sources(),invalidPlatform,List.of());
        assertEquals("frontend.artifact-digest",assertThrows(FrontendInputException.class,() -> new JavaParserFrontend().analyze(request)).diagnostic().code());
    }

    @Test void explicitPlatformJarDecouplesSymbolsFromTheRunningJdk() throws Exception {
        var archive = jar("platform-view", "package platform.fixture; public class Library { public static void hit(){} }");
        var platform = PlatformInput.create(8, "1.8.0-fixture", "Fixture Vendor", List.of(
                new PlatformInput.Artifact("lib/rt.jar", archive.entry().contentDigest(), archive.path(),
                        PlatformInput.Format.JAR)));
        var base = TestInputs.request("class C { void run(){ platform.fixture.Library.hit(); } }");
        var plan = new FrontendPlan(Optional.of(8), Optional.of(8), false,
                ContentDigest.sha256Utf8("classpath"), ContentDigest.sha256Utf8("decoding"));
        var result = new JavaParserFrontend().analyze(
                TestInputs.request(base.sources(), platform, List.of(), plan));

        assertEquals(1, calls(result).size());
        assertEquals(SemanticStatus.RESOLVED, calls(result).getFirst().status());
        var targetId = ((RelationshipTarget.Resolved) calls(result).getFirst().relationship().target()).target();
        assertEquals(EntityOrigin.JDK, result.declarations().stream().map(DeclarationRecord::entity)
                .filter(entity -> entity.identity().equals(targetId)).findFirst().orElseThrow().origin());

        var unsupportedSyntax = TestInputs.request(Map.of("fixture/C.java", "record TooNew(int value) {}"), List.of());
        var java8Request = TestInputs.request(unsupportedSyntax.sources(), platform, List.of(), plan);
        assertEquals(SourceOutcome.State.ERROR,
                new JavaParserFrontend().analyze(java8Request).sources().getFirst().state());
    }

    @Test void explicitJmodViewUsesClassesPrefixAndRejectsMutation() throws Exception {
        var archive = jar("jmod-source", "package platform.fixture; public class Library { public static void hit(){} }");
        Path jmod = temp.resolve("java.fixture.jmod");
        try (var input = new JarFile(archive.path().toFile());
                var output = new JarOutputStream(Files.newOutputStream(jmod))) {
            for (var item : input.stream().filter(value -> value.getName().endsWith(".class")).toList()) {
                JarEntry entry = new JarEntry("classes/" + item.getName()); entry.setTime(0);
                output.putNextEntry(entry); input.getInputStream(item).transferTo(output); output.closeEntry();
            }
        }
        var artifact = new PlatformInput.Artifact("jmods/java.fixture.jmod",
                ContentDigest.sha256(Files.readAllBytes(jmod)), jmod, PlatformInput.Format.JMOD);
        var platform = PlatformInput.create(17, "17-fixture", "Fixture Vendor", List.of(artifact));
        var base = TestInputs.request("class C { void run(){ platform.fixture.Library.hit(); } }");
        var plan = new FrontendPlan(Optional.of(17), Optional.of(17), false,
                ContentDigest.sha256Utf8("classpath"), ContentDigest.sha256Utf8("decoding"));
        assertEquals(SemanticStatus.RESOLVED, calls(new JavaParserFrontend().analyze(
                TestInputs.request(base.sources(), platform, List.of(), plan))).getFirst().status());

        Files.writeString(jmod, "changed");
        assertEquals("frontend.artifact-digest", assertThrows(FrontendInputException.class,
                () -> new JavaParserFrontend().analyze(
                        TestInputs.request(base.sources(), platform, List.of(), plan))).diagnostic().code());
    }

    @Test void ctSymViewSelectsOnlyTheRequestedReleaseSignatures() throws Exception {
        var archive = jar("ct-sym-source", "package platform.fixture; public class Library { public static void hit(){} }");
        Path ctSym = temp.resolve("ct.sym");
        try (var input = new JarFile(archive.path().toFile());
                var output = new JarOutputStream(Files.newOutputStream(ctSym))) {
            for (var item : input.stream().filter(value -> value.getName().endsWith(".class")).toList()) {
                JarEntry selected = new JarEntry("H/java.base/" + item.getName().replace(".class", ".sig"));
                selected.setTime(0); output.putNextEntry(selected); input.getInputStream(item).transferTo(output);
                output.closeEntry();
                JarEntry wrongRelease = new JarEntry("K/java.base/newer/" + item.getName().replace(".class", ".sig"));
                wrongRelease.setTime(0); output.putNextEntry(wrongRelease); input.getInputStream(item).transferTo(output);
                output.closeEntry();
            }
        }
        var artifact = new PlatformInput.Artifact("lib/ct.sym#release-17",
                ContentDigest.sha256(Files.readAllBytes(ctSym)), ctSym, PlatformInput.Format.CT_SYM);
        var platform = PlatformInput.create(17, "release-17-from-21-fixture", "Fixture Vendor", List.of(artifact));
        var base = TestInputs.request("class C { void run(){ platform.fixture.Library.hit(); } }");
        var plan = new FrontendPlan(Optional.of(17), Optional.of(17), false,
                ContentDigest.sha256Utf8("classpath"), ContentDigest.sha256Utf8("decoding"));

        var result = new JavaParserFrontend().analyze(TestInputs.request(base.sources(), platform, List.of(), plan));

        assertEquals(SemanticStatus.RESOLVED, calls(result).getFirst().status());
    }

    @Test void cachedArchivesAvoidRepeatedDecompressionTempFilesAndCrossContextLeakage() throws Exception {
        var bJar = jar("dep-b", "package dep2; public class Library { public static String base(){ return \"ok\"; } }");
        Path aRoot = temp.resolve("dep-a"); Files.createDirectories(aRoot);
        Path aSource = aRoot.resolve("A.java");
        Files.writeString(aSource, "package dep1; public class A extends dep2.Library { public static String call(){ return base(); } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "21", "-proc:none",
                "-encoding", "UTF-8", "-classpath", bJar.path().toString(), "-d", aRoot.toString(), aSource.toString()));
        Path aArchive = temp.resolve("dep-a.jar");
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, "neighbor.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(aArchive), manifest)) {
            var entry = new JarEntry("dep1/A.class"); entry.setTime(0);
            output.putNextEntry(entry); Files.copy(aRoot.resolve("dep1/A.class"), output); output.closeEntry();
        }
        var aDigest = ContentDigest.sha256(Files.readAllBytes(aArchive));
        var aJar1 = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "fixture:dep-a:1", aDigest), aArchive);
        var aJar2 = new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY, "fixture:dep-a-alias:1", aDigest), aArchive);

        Path jmod = temp.resolve("java.cached.jmod");
        var pSource = jar("jmod-cached-src", "package platform.cached; public class Library { public static int v(){ return 7; } }");
        try (var input = new JarFile(pSource.path().toFile());
                var output = new JarOutputStream(Files.newOutputStream(jmod))) {
            for (var item : input.stream().filter(v -> v.getName().endsWith(".class")).toList()) {
                var entry = new JarEntry("classes/" + item.getName()); entry.setTime(0);
                output.putNextEntry(entry); input.getInputStream(item).transferTo(output); output.closeEntry();
            }
        }
        var basePlatform = TestInputs.request("class S {}").platform();
        var jmodList = new ArrayList<>(basePlatform.artifacts());
        jmodList.add(new PlatformInput.Artifact("jmods/java.cached.jmod",
                ContentDigest.sha256(Files.readAllBytes(jmod)), jmod, PlatformInput.Format.JMOD));
        var platform = PlatformInput.create(21, basePlatform.version(), basePlatform.vendor(), jmodList);

        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        long tempJarsBefore;
        try (var s = Files.list(tmpDir)) {
            tempJarsBefore = s.filter(p -> p.getFileName().toString().startsWith("jar_file_from_input_stream")).count();
        }

        // Warm platform + aJar + bJar
        var srcWithBoth = TestInputs.request("class C1 { String f(){ return dep1.A.base() + platform.cached.Library.v(); } }").sources();
        var req1 = TestInputs.request(srcWithBoth, platform, List.of(aJar1, bJar));
        var res1 = new JavaParserFrontend().analyze(req1);
        assertEquals(2, calls(res1).stream().filter(c -> c.status() == SemanticStatus.RESOLVED).count());
        assertTrue(res1.diagnostics().stream().anyMatch(d -> d.code().equals("frontend.jar-classpath-unmodeled")
                && "fixture:dep-a:1".equals(d.details().get("artifact"))));

        long buildsAfterWarm = ResolutionEnvironment.cachedArchiveBuilds();

        // Second & concurrent runs with same digest (different logical name aJar2) must hit cache (0 new archive builds)
        // AND isolate parent solver when bJar is omitted!
        var srcWithoutB = TestInputs.request("class C2 { String f(){ return dep1.A.base(); } }").sources();
        var reqWithoutB = TestInputs.request(srcWithoutB, platform, List.of(aJar2));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var f1 = pool.submit(() -> new JavaParserFrontend().analyze(req1));
            var f2 = pool.submit(() -> new JavaParserFrontend().analyze(reqWithoutB));
            assertEquals(res1, f1.get());
            var res2 = f2.get();
            // Without bJar in Context 2, dep1.A.base() inherited from dep2.B cannot borrow Context 1's bJar solver!
            assertEquals(SemanticStatus.UNRESOLVED, calls(res2).getFirst().status());
            assertTrue(res2.diagnostics().stream().anyMatch(d -> d.code().equals("frontend.jar-classpath-unmodeled")
                    && "fixture:dep-a-alias:1".equals(d.details().get("artifact"))));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(buildsAfterWarm, ResolutionEnvironment.cachedArchiveBuilds(),
                "Repeated and concurrent analyses of identical platform/JAR digests must reuse cached archives");

        long tempJarsAfter;
        try (var s = Files.list(tmpDir)) {
            tempJarsAfter = s.filter(p -> p.getFileName().toString().startsWith("jar_file_from_input_stream")).count();
        }
        assertEquals(tempJarsBefore, tempJarsAfter, "CachedArchive must never materialize JarTypeSolver temp JARs on disk");

        // Mutating a cached JAR on disk must STILL fail per-request SHA-256 verification!
        Files.writeString(aArchive, "tampered");
        assertEquals("frontend.artifact-digest", assertThrows(FrontendInputException.class,
                () -> new JavaParserFrontend().analyze(reqWithoutB)).diagnostic().code());
    }

    @Test void sourceRecoveryRetainsUnprovedWildcardAndQualifiedTypesAndWithholdsDuplicates() {
        var sources = Map.of(
                "src/main/java/demo/Owner.java", """
                        package demo;
                        import jakarta.persistence.*;
                        import org.springframework.web.bind.annotation.*;
                        import io.grpc.services.*;
                        import javax.cache.configuration.Configuration;
                        @Entity
                        @Table(name = "owners")
                        @RequestMapping("/owners")
                        @org.springframework.context.annotation.Configuration
                        public class Owner extends hipstershop.AdServiceGrpc.AdServiceImplBase {
                            private HealthStatusManager healthMgr;
                            private hipstershop.AdServiceGrpc.AdServiceBlockingStub blockingStub;
                            @GetMapping("/list")
                            public String list() { return "ok"; }
                        }
                        """,
                "src/debug/java/demo/Owner.java", """
                        package demo;
                        public class Owner {}
                        """);
        var base = TestInputs.request(sources, List.of());
        // Strict mode rejects duplicate source type definitions
        assertEquals("frontend.duplicate-type", assertThrows(FrontendInputException.class,
                () -> new JavaParserFrontend().analyze(base)).diagnostic().code());

        // Source-recovery mode withholds the duplicate source document with a warning diagnostic
        // and retains unproved wildcard/qualified types without inventing declarations.
        var options = new TreeMap<>(base.manifest().configuration().values());
        options.put(FrontendRequest.RESOLUTION_MODE, FrontendRequest.SOURCE_RECOVERY);
        var component = new ManifestComponent(
                new com.evolution.analysis.contract.common.VersionedIdentifier("test.fixture", "1"),
                ContentDigest.sha256Utf8("test-fixture-components-v1"));
        var recoveryManifest = AnalysisManifest.create(
                new com.evolution.analysis.contract.common.VersionedIdentifier("analysis.manifest", "1"),
                base.manifest().snapshot(),
                base.manifest().modules(),
                base.manifest().classpath(),
                AnalysisConfiguration.create(
                        new com.evolution.analysis.contract.common.VersionedIdentifier("analysis.configuration", "1"),
                        options),
                component,
                component,
                component);
        var recoveryReq = new FrontendRequest(
                recoveryManifest,
                base.module(),
                base.sourceSet(),
                base.plan(),
                base.sources(),
                base.platform(),
                base.dependencies());
        var result = new JavaParserFrontend().analyze(recoveryReq);
        assertEquals(FrontendResult.State.PARTIAL, result.state());
        assertEquals(2, result.sources().size());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("java.duplicate-source-type")));

        var structuralObs = result.observations().stream()
                .filter(o -> Set.of("java.extends", "java.implements", "java.annotated-with", "java.field-type")
                        .contains(o.category().value()))
                .toList();
        assertFalse(structuralObs.isEmpty());
        assertTrue(structuralObs.stream().allMatch(o -> o.attribution() == SemanticStatus.UNRESOLVED));
        assertTrue(structuralObs.stream().allMatch(o -> o.span().isPresent() && !o.diagnostics().isEmpty()));
    }
}
