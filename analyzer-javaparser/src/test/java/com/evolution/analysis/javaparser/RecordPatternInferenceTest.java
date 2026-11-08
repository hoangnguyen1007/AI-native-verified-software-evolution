package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.SemanticStatus;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecordPatternInferenceTest {
    @TempDir Path compiled;
    @Test void sourceRecordComponentsSupplyNestedAndGenericPatternVarTypes()throws Exception {
        String source="""
            record Pair(String name,int count) {}
            record Container(Pair pair) {}
            record Box<T>(T value) {}
            class C {
              int simple(Object value) { return switch(value) { case Pair(var name,var count) -> name.length()+count; default -> 0; }; }
              int nested(Object value) { return switch(value) { case Container(Pair(var name,var count)) -> name.length()+count; default -> 0; }; }
              int generic(Box<String> value) { return switch(value) { case Box<String>(var text) -> text.length(); }; }
            }
            """;
        Path file=compiled.resolve("C.java");Files.writeString(file,source);
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","21","-proc:none","-d",compiled.toString(),file.toString()));
        var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
        var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")).toList();
        assertEquals(3,calls.size());assertEquals(3,calls.stream().filter(o->o.attribution()==SemanticStatus.RESOLVED).count(),()->calls.stream().map(o->o.reference()+":"+o.attribution()).toList().toString());
        assertTrue(result.observations().stream().noneMatch(o->o.attribution()==SemanticStatus.ERROR));
        assertEquals(5,result.types().stream().filter(t->t.type().status()==SemanticStatus.RESOLVED&&t.type().spelling().equals("var")).count());
        assertTrue(result.observations().stream().filter(o->o.reference().equals("var")).allMatch(o->o.attribution()!=SemanticStatus.ERROR));
    }
    @Test void unknownRecordsAndWrongArityCannotInventComponentTypes() {
        for(String source:List.of(
                "class C { int run(Object o) { return switch(o) {case Missing(var value) -> value.length(); default -> 0;}; } }",
                "record Pair(String name,int count){} class C { int run(Object o) { return switch(o) {case Pair(var value) -> value.length(); default -> 0;}; } }")) {
            var result=new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(source)));
            var calls=result.observations().stream().filter(o->o.category().value().equals("java.calls")).toList();
            assertEquals(1,calls.size());assertNotEquals(SemanticStatus.RESOLVED,calls.getFirst().attribution());
        }
    }
}
