package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaSemanticClosureTest {
    @Test void annotationArgumentsAndDefaultsReadTheirExactConstantDeclaration() {
        var request=TestInputs.request("class Constants { static final String NAME=\"x\"; } @interface Label { String value() default Constants.NAME; } @Label(Constants.NAME) class C {}");
        var result=new JavaParserFrontend().analyze(request);
        var reads=result.occurrences().stream().filter(o -> o.relationship().kind().value().equals("java.reads-field")).toList();
        assertEquals(2,reads.size());
        assertTrue(reads.stream().allMatch(o -> o.status()==SemanticStatus.RESOLVED));
        assertEquals(1,reads.stream().map(o -> o.relationship().target()).distinct().count());
        assertEquals(18,result.coverage().size());
    }
    @Test void inferredVarPreservesActualTypeAndGenericArguments() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("class C { void run() { var text=\"x\"; var values=new java.util.ArrayList<String>(); } }"));
        var types=result.types().stream().filter(t -> t.type().spelling().equals("var")).toList();
        assertEquals(2,types.size());
        assertTrue(types.stream().allMatch(t -> t.type().status()==SemanticStatus.RESOLVED));
        assertTrue(types.stream().anyMatch(t -> t.type().components().size()==1));
    }
    @Test void lambdaAndCatchParametersHaveDistinctAnchoredDeclarations() {
        var result=new JavaParserFrontend().analyze(TestInputs.request("class C { void run() { java.util.function.Function<String,String> a=x->x; java.util.function.Function<String,String> b=x->x; try { throw new IllegalStateException(); } catch (RuntimeException x) {} try { throw new IllegalStateException(); } catch (RuntimeException x) {} } }"));
        var parameters=result.declarations().stream().filter(d -> d.entity().kind()==EntityKind.PARAMETER).toList();
        assertEquals(4,parameters.size());
        assertEquals(4,parameters.stream().map(d -> d.entity().identity()).distinct().count());
        assertTrue(parameters.stream().allMatch(d -> d.entity().declaration().isPresent()));
        assertFalse(result.diagnostics().stream().anyMatch(d -> d.code().equals("java.parameter-owner")));
    }
}
