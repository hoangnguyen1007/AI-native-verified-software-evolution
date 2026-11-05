package com.evolution.analysis.javaparser;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class LombokIngestionTest {
    @Test void inheritedSettersBoxPrimitiveValuesAndAcceptNullWithoutAcceptingWrongTypes()throws Exception {
        var r=analyze("@lombok.Setter class Base { Integer id; } class C extends Base { void update(C c){c.setId(1); c.setId(null);} }");
        assertEquals(2,r.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")&&o.status()==SemanticStatus.RESOLVED).count());
        var bad=analyze("@lombok.Setter class C { Integer id; void update(){setId(\"wrong\");} }");
        assertTrue(bad.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).allMatch(o->o.status()!=SemanticStatus.RESOLVED));
    }
    @org.junit.jupiter.api.io.TempDir Path compiled;
    @Test void pinnedNativeLombokConfirmsAccessorAndBuilderSignaturesOnAuthoredSource()throws Exception {
        String source="@lombok.Data @lombok.Builder class C { String name; int id; static String run(){return C.builder().name(\"n\").id(1).build().getName();} } "
                +"@lombok.Getter @lombok.Setter class Other { boolean isReady; @lombok.Getter(lombok.AccessLevel.NONE) String hidden; String uShaped; }";
        Path input=compiled.resolve("C.java");Files.writeString(input,source);
        Path lombok=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try(var files=compiler.getStandardFileManager(diagnostics,null,java.nio.charset.StandardCharsets.UTF_8)) {
            assertTrue(compiler.getTask(null,files,diagnostics,List.of("-classpath",lombok.toString(),"-processor","lombok.launch.AnnotationProcessorHider$AnnotationProcessor","-d",compiled.toString()),null,files.getJavaFileObjects(input.toFile())).call(),
                    ()->diagnostics.getDiagnostics().stream().map(d->d.getKind()+":"+d.getCode()).toList().toString());
        }
        var expected=Map.of("C",Set.of("getName()Ljava/lang/String;","setName(Ljava/lang/String;)V","builder()LC$CBuilder;"),
                "C$CBuilder",Set.of("name(Ljava/lang/String;)LC$CBuilder;","id(I)LC$CBuilder;","build()LC;"),
                "Other",Set.of("isReady()Z","setReady(Z)V","getUShaped()Ljava/lang/String;"));
        for(var entry:expected.entrySet())try(var bytes=new java.io.DataInputStream(Files.newInputStream(compiled.resolve(entry.getKey()+".class")))) {
            var type=new javassist.bytecode.ClassFile(bytes);var signatures=type.getMethods().stream().map(m->m.getName()+m.getDescriptor()).collect(java.util.stream.Collectors.toSet());
            assertTrue(signatures.containsAll(entry.getValue()),entry.getKey());if(entry.getKey().equals("Other"))assertFalse(signatures.contains("getHidden()Ljava/lang/String;"));
        }
    }
    @Test void generatedAccessorReferencesRespectFunctionalArgumentsAndReturnTypes()throws Exception {
        var r=analyze("@lombok.Data class C { String name; java.util.function.Function<C,String> getter=C::getName; java.util.function.Supplier<String> bound=this::getName; java.util.function.BiConsumer<C,String> setter=C::setName; }");
        var refs=r.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.method-references")).toList();
        assertEquals(3,refs.size());assertEquals(3,refs.stream().filter(o->o.status()==SemanticStatus.RESOLVED).count());
        var bad=analyze("@lombok.Data class C { String name; java.util.function.Function<C,Integer> bad=C::getName; }");
        assertTrue(bad.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.method-references")).allMatch(o->o.status()!=SemanticStatus.RESOLVED));
    }
    @Test void builderFactorySettersBuildAndReturnedOwnerCallsBindAsOneChain()throws Exception {
        var r=analyze("@lombok.Data @lombok.Builder class C { String name; int id; static String run(){ return C.builder().name(\"n\").id(1).build().getName(); } }");
        var calls=r.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(5,calls.size());
        assertEquals(5,calls.stream().filter(o->o.status()==SemanticStatus.RESOLVED).count());
        var invalid=analyze("@lombok.Builder class C { String name; static C run(){ return C.builder().name(1).build(); } }");
        assertTrue(invalid.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).anyMatch(o->o.status()!=SemanticStatus.RESOLVED));
    }
    @Test void standaloneAccessorsAndInheritedDataMembersBindWithoutInventedBodies()throws Exception {
        var r=analyze("class Base { int base; } @lombok.Getter @lombok.Setter class C extends Base { String name; boolean isReady; java.lang.Boolean boxed; final int fixed=1; String run(C c){ c.setName(\"x\"); c.setReady(true); return c.getName(); } }");
        var calls=r.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(3,calls.size());assertTrue(calls.stream().allMatch(o->o.status()==SemanticStatus.RESOLVED),calls.toString());
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"getBoxed\"")));
        assertFalse(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"setFixed\"")));
        assertTrue(r.declarations().stream().filter(d->d.derivation().method().id().startsWith("lombok.")).allMatch(d->d.entity().declaration().isEmpty()));
        var data=analyze("class Base {} @lombok.Data class C extends Base { String name; String run(){return getName();} }");
        assertTrue(data.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).allMatch(o->o.status()==SemanticStatus.RESOLVED));
        var inherited=analyze("@lombok.Getter class Base { int id; } class C extends Base { int read(C c){return c.getId();} }");
        assertEquals(1,inherited.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")&&o.status()==SemanticStatus.RESOLVED).count());
    }
    @Test void fieldOverridesSuppressAccessorsAndDoNotSuppressTheJavaDefaultConstructor()throws Exception {
        var r=analyze("@lombok.Getter @lombok.Setter class C { @lombok.Getter(lombok.AccessLevel.NONE) String hidden; @lombok.Setter(lombok.AccessLevel.NONE) String immutable; @lombok.Getter(lombok.AccessLevel.PRIVATE) String secret; @lombok.Getter String uShaped; }");
        var names=r.declarations().stream().map(d->d.entity().canonicalName()).toList();
        assertFalse(names.stream().anyMatch(n->n.contains("\"getHidden\"")));
        assertFalse(names.stream().anyMatch(n->n.contains("\"setImmutable\"")));
        assertTrue(names.stream().anyMatch(n->n.contains("\"getUShaped\"")));
        assertEquals(1,r.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
        var access=analyze("class C { @lombok.Getter(lombok.AccessLevel.PRIVATE) String secret; } class D { String leak(C c){return c.getSecret();} }");
        assertTrue(access.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).allMatch(o->o.status()!=SemanticStatus.RESOLVED));
        var customized=analyze("@lombok.Getter @lombok.experimental.Accessors(fluent=true) class C { String value; }");
        assertTrue(customized.diagnostics().stream().anyMatch(d->d.code().startsWith("lombok.")));
        assertEquals(1,customized.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
    }
    @Test void generatedCallsKeepTheirOwnerAcrossDeclarationSkeletonAndActiveBody()throws Exception {
        Path jar=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var binary=new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.projectlombok:lombok:1.18.46@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar);
        var result=new JavaParserFrontend().analyze(TestInputs.request(Map.of(
                "fixture/C.java","package fixture; @lombok.Data class C { String name; String own(){return getName();} }",
                "fixture/D.java","package fixture; class D { String other(C c){return c.getName();} }"),List.of(binary)));
        var calls=result.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.calls")).toList();
        assertEquals(2,calls.size());assertTrue(calls.stream().allMatch(o->o.status()==SemanticStatus.RESOLVED));
        var target=calls.getFirst().relationship().target();assertEquals(target,calls.getLast().relationship().target());
    }
    @Test void recoveryRetainsOrderedConstructorCandidatesWithoutInventingConstructors() {
        var base=TestInputs.request("import lombok.RequiredArgsConstructor; @RequiredArgsConstructor class C { final Missing first; @lombok.NonNull Other second; static Missing ignored; final int initialized=1; }");
        var request=recovery(base);
        var result=new JavaParserFrontend().analyze(request);
        assertEquals(2,result.constructorCandidates().size());
        assertEquals(List.of("Missing","Other"),result.constructorCandidates().stream().sorted(Comparator.comparingInt(LombokConstructorCandidate::fieldOrder)).map(c->c.type().spelling()).toList());
        assertTrue(result.constructorCandidates().stream().allMatch(c->c.status()==LombokConstructorCandidate.Status.CANDIDATE&&c.supportingSpans().size()==2));
        assertFalse(result.declarations().stream().anyMatch(d->d.entity().kind()==EntityKind.CONSTRUCTOR));
        assertEquals(result,new JavaParserFrontend().analyze(request));
        var graph=new com.evolution.analysis.graph.CanonicalGraphBuilder().build(request.manifest(),result);
        assertEquals(2,graph.nodes().stream().filter(n->n.kind().equals("CONSTRUCTOR_INJECTION_CANDIDATE")).count());
        var codec=new com.evolution.analysis.graph.GraphSnapshotCodec();
        var restored=codec.decode(codec.encode(graph),com.evolution.analysis.graph.GraphJson.Limits.defaults());
        assertEquals(graph.identity(),restored.identity());assertEquals(codec.encode(graph),codec.encode(restored));
    }
    static FrontendRequest recovery(FrontendRequest base) {
        var old=base.manifest();var options=new TreeMap<>(old.configuration().values());
        options.put(FrontendRequest.RESOLUTION_MODE,FrontendRequest.SOURCE_RECOVERY);
        var config=AnalysisConfiguration.create(old.configuration().schema(),options);
        var manifest=AnalysisManifest.create(old.manifestVersion(),old.snapshot(),old.modules(),old.classpath(),config,old.analyzer(),old.ruleSet(),old.graphSchema());
        return new FrontendRequest(manifest,base.module(),base.sourceSet(),base.plan(),base.sources(),base.platform(),base.dependencies(),base.reactorSources(),base.correspondence());
    }
    @Test void recoveryRejectsImpostorsAndSuppressedDataConstructors() {
        for(String source:List.of("package lombok; @interface RequiredArgsConstructor {} @RequiredArgsConstructor class C { final Missing field; }",
                "@lombok.Data class C { final Missing field; C(Missing v){field=v;} }",
                "class C { String text=\"@lombok.RequiredArgsConstructor\"; final Missing field; }",
                "import other.RequiredArgsConstructor; @RequiredArgsConstructor class C { final Missing field; }")) {
            assertTrue(new JavaParserFrontend().analyze(recovery(TestInputs.request(source))).constructorCandidates().isEmpty());
        }
        var missingGenerator=new JavaParserFrontend().analyze(recovery(TestInputs.request("@lombok.Data class C { final Missing field; }")));
        assertFalse(missingGenerator.declarations().stream().anyMatch(d->d.entity().kind()==EntityKind.CONSTRUCTOR),"Missing processor evidence cannot manufacture a Java default constructor");
    }
    @Test void unsupportedGeneratorTargetsAndGeneratorsRemainExplicit()throws Exception {
        for(String source:List.of("class C { @lombok.Builder C(String value) {} }",
                "class C { @lombok.Builder static C create(String value) { return new C(); } }",
                "@lombok.experimental.SuperBuilder class C { String value; }")) {
            var result=analyze(source);
            assertEquals(FrontendResult.State.PARTIAL,result.state());
            assertTrue(result.diagnostics().stream().anyMatch(d->d.code().equals("lombok.generator-unsupported")));
        }
    }
    @Test void explicitDataConstructorSuppressesGenerationAndExplicitConstructorAnnotationsDoNot()throws Exception {
        var data=analyze("@lombok.Data class C { final String name; C(String n){name=n;} }");
        assertEquals(1,data.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
        var required=analyze("@lombok.RequiredArgsConstructor class C { final String name; C(){name=\"x\";} }");
        assertEquals(2,required.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
    }
    @Test void allNoArgsValueAndBuilderHaveDistinctConstructorRules()throws Exception {
        for(String annotation:List.of("AllArgsConstructor","Value","Builder")) {
            var r=analyze("@lombok."+annotation+" class C { String name; final int fixed=1; }");
            var constructors=r.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT&&!d.entity().canonicalName().contains("member-type")).toList();
            assertEquals(1,constructors.size());assertTrue(constructors.getFirst().entity().canonicalName().contains("String"));
        }
        var forced=analyze("@lombok.NoArgsConstructor(force=true) class C { final String name; }");
        assertEquals(1,forced.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).count());
        var invalid=analyze("@lombok.NoArgsConstructor class C { final String name; }");
        assertTrue(invalid.diagnostics().stream().anyMatch(d->d.code().equals("lombok.invalid-no-args")));
    }
    @Test void gettersSettersAndGenericParameterTypesRetainFieldEvidence()throws Exception {
        var r=analyze("@lombok.Data class C { final java.util.List<String> names; boolean ready; }");
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"getNames\"")));
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"isReady\"")));
        assertTrue(r.declarations().stream().anyMatch(d->d.entity().canonicalName().contains("\"setReady\"")));
        assertTrue(r.types().stream().anyMatch(t->t.role().value().equals("java.parameter-type")&&t.type().spelling().equals("java.util.List<String>")));
    }
    @Test void impostorsAndUnresolvedTypesNeverCreateLombokConstructors()throws Exception {
        var impostor=new JavaParserFrontend().analyze(TestInputs.request("package lombok; @interface RequiredArgsConstructor {} @RequiredArgsConstructor class C { final String name=null; }"));
        assertFalse(impostor.declarations().stream().anyMatch(d->d.derivation().method().id().startsWith("lombok.")));
        var missing=analyze("@lombok.RequiredArgsConstructor class C { final Missing dependency; }");
        assertTrue(missing.diagnostics().stream().anyMatch(d->d.code().equals("lombok.member-type-unresolved")));
    }
    private FrontendResult analyze(String source)throws Exception {
        Path jar=Path.of(lombok.Data.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var binary=new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.projectlombok:lombok:1.18.46@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar);
        return new JavaParserFrontend().analyze(TestInputs.request(Map.of("fixture/C.java",source),List.of(binary)));
    }
    @Test void requiredConstructorKeepsFieldOrderAndHasParameterTypes()throws Exception {
        var r=analyze("@lombok.RequiredArgsConstructor class C { final String name; @lombok.NonNull Integer count; static int ignored; final int initialized=1; }");
        var constructors=r.declarations().stream().filter(d->d.entity().kind()==EntityKind.CONSTRUCTOR&&d.entity().origin()==EntityOrigin.PROJECT).toList();
        assertEquals(1,constructors.size());assertTrue(constructors.getFirst().entity().canonicalName().contains("String"));
        assertTrue(constructors.getFirst().entity().canonicalName().contains("Integer"));
        assertEquals(2,r.declarations().stream().filter(d->d.entity().kind()==EntityKind.PARAMETER).count());
        assertEquals(2,r.types().stream().filter(t->t.role().value().equals("java.parameter-type")).count());
        assertTrue(constructors.getFirst().entity().declaration().isEmpty());
    }
}
