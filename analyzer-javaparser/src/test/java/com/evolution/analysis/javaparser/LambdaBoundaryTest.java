package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.SemanticStatus;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LambdaBoundaryTest {
    @TempDir Path compiled;
    private static final class Sdk {
        static final PlatformInput PLATFORM=new com.evolution.analysis.filesystem.FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),new com.evolution.analysis.input.PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform().orElseThrow();
        static final List<BinaryInput> JARS=jars();
        private static List<BinaryInput> jars(){try{
            Path jar=Path.of(org.springframework.data.domain.Sort.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return List.of(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,"org.springframework.data:spring-data-commons:3.4.0@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar));
        }catch(Exception failure){throw new ExceptionInInitializerError(failure);}}
    }
    private FrontendRequest request(String source,boolean valid)throws Exception {
        Path file=compiled.resolve("C.java");Files.writeString(file,source);
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            assertEquals(valid,compiler.getTask(null,manager,diagnostics,List.of("--release","21","-proc:none","-classpath",System.getProperty("java.class.path"),"-d",compiled.toString()),null,manager.getJavaFileObjects(file)).call(),()->diagnostics.getDiagnostics().toString());
        }
        var request=TestInputs.request(Map.of("fixture/C.java",source),Sdk.JARS);
        return TestInputs.request(request.sources(),Sdk.PLATFORM,Sdk.JARS);
    }
    private FrontendResult analyze(String source)throws Exception {
        var request=request(source,true);
        resolvedCalls(new JavaParserFrontend().analyze(request));
        var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(request));
        var original=new OriginalSource(request.sources().getFirst().document().identity(),source);
        for(var observation:result.observations())assertEquals(observation.reference(),original.slice(observation.span().orElseThrow()),"Original coordinates and spelling");
        for(var type:result.types())assertEquals(type.type().spelling(),original.slice(type.span()),"No synthetic source types");
        return result;
    }
    private void resolvedCalls(FrontendResult result) {
        var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")).toList();assertFalse(calls.isEmpty());
        assertTrue(calls.stream().allMatch(o->o.attribution()==SemanticStatus.RESOLVED),()->calls.stream().filter(o->o.attribution()!=SemanticStatus.RESOLVED).map(o->o.reference()+":"+o.attribution()+":"+o.diagnostics().stream().map(d->d.code()).toList()).toList().toString());
    }
    @Test void blockLambdaReturnsUseTheirGroundParameterType()throws Exception {
        resolvedCalls(analyze("""
            import org.springframework.data.domain.Sort;
            class C { Sort rewrite(Sort sort) {
              var orders=sort.stream().map(order -> { if("created".equals(order.getProperty())) return order.withProperty("metadata.created"); return order; }).toList();
              return Sort.by(orders);
            } }
            """));
    }
    @Test void comparatorChainsKeepCapturedSourceParameterIdentity()throws Exception {
        resolvedCalls(analyze("""
            import java.util.*; import java.time.*;
            record Metadata(Instant created,String name) {}
            interface Menu extends Comparable<Menu> { Metadata getMetadata(); }
            class C { Comparator<Menu> comparator() {
              return Comparator.comparing((Menu menu) -> menu.getMetadata().created(),Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(menu -> menu.getMetadata().name(),Comparator.nullsLast(Comparator.naturalOrder()));
            } }
            """));
    }
    @Test void atomicUpdateWithNullableCapturedPairKeepsItsReturnType()throws Exception {
        resolvedCalls(analyze("""
            import java.util.concurrent.atomic.AtomicReference; import org.springframework.data.util.Pair;
            record Config() {} record Sender() {}
            class C { AtomicReference<Pair<Config,Sender>> ref=new AtomicReference<>();
              Sender sender(Config config) { return ref.updateAndGet(pair -> {
                if(pair!=null&&pair.getFirst().equals(config)) return pair;
                return Pair.of(config,new Sender());
              }).getSecond(); }
            }
            """));
    }
    @Test void multipleImplicitAndVarInputsPreserveReplayAndWrittenTypeDenominator()throws Exception {
        String source="""
            import java.util.*;
            record Pet(String name) {}
            class C {
              List<Pet> a(List<Pet> pets) {return pets.stream().map(p -> {if(p.name().isBlank())return new Pet("a");return p;}).toList();}
              List<Pet> b(List<Pet> pets) {return pets.stream().map((var p) -> {if(p.name().isBlank())return new Pet("b");return p;}).toList();}
            }
            """;
        var request=request(source,true);var frontend=new JavaParserFrontend();
        var first=frontend.analyze(LombokIngestionTest.recovery(request));resolvedCalls(first);
        assertEquals(first,frontend.analyze(LombokIngestionTest.recovery(request)));
        assertEquals(1,first.types().stream().filter(t->t.type().spelling().equals("var")).count());
        assertTrue(first.types().stream().noneMatch(t->t.type().spelling().equals("p")),"Implicit input has no invented written type");
        assertEquals(2,first.observations().stream().filter(o->o.reference().equals("p.name()")).count());
    }
    @Test void unknownParentsRawAndLowerWildcardInputsCannotGainMembers()throws Exception {
        for(String source:List.of(
                "import java.util.*; record Pet(int id){} class C{void run(List<? super Pet> pets){pets.forEach(p -> p.id());}}",
                "import java.util.*; record Pet(int id){} class C{void run(List pets){pets.forEach(p -> p.id());}}",
                "import java.util.*; class Pet extends Missing{void pick(Object o){}} class C{void run(List<Pet> pets){pets.forEach(p -> p.pick(\"x\"));}}")) {
            var request=request(source,false);
            for(boolean recovery:List.of(false,true)) {
                var result=new JavaParserFrontend().analyze(recovery?LombokIngestionTest.recovery(request):request);
                var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")&&(o.reference().equals("p.id()")||o.reference().equals("p.pick(\"x\")"))).toList();
                assertEquals(1,calls.size());assertNotEquals(SemanticStatus.RESOLVED,calls.getFirst().attribution());
            }
        }
    }
    @Test void lambdaTypingCannotChooseAnAmbiguousOverload()throws Exception {
        var request=request("""
            interface TextAction { String run(String value); }
            interface NumberAction { Integer run(Integer value); }
            class Box { void use(TextAction action){} void use(NumberAction action){} }
            class C { void run(Box box) {box.use(value -> value.substring(0));} }
            """,false);
        for(boolean recovery:List.of(false,true)) {
            var result=new JavaParserFrontend().analyze(recovery?LombokIngestionTest.recovery(request):request);
            var call=result.observations().stream().filter(o->o.category().value().equals("java.calls")&&o.reference().startsWith("box.use(")).toList();
            assertEquals(1,call.size());assertNotEquals(SemanticStatus.RESOLVED,call.getFirst().attribution());
        }
    }
}
