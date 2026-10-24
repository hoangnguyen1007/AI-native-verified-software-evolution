package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.semantic.*;
import com.github.javaparser.*;
import com.github.javaparser.ast.body.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeJavaSymbolMappingTest {
    @Test void parameterKindNameAndSiblingIndexMustAllMatchTheActualSourceDeclaration(){
        var request=TestInputs.request("class C<T> { void f(int a, int b) {} }");var input=request.sources().getFirst();var frontend=new JavaParserFrontend().analyze(request);
        var ast=new JavaParser().parse(input.text()).getResult().orElseThrow();var parameters=ast.findAll(Parameter.class);var source=new OriginalSource(input.document().identity(),input.text());
        var a=frontend.declarations().stream().filter(d->d.entity().kind()==EntityKind.PARAMETER&&d.entity().declaration().filter(source.span(parameters.getFirst())::equals).isPresent()).findFirst().orElseThrow().entity();
        assertTrue(NativeJavaSymbolMapping.matchesSourceDeclaration(Map.of("kind","PARAMETER","name","a"),a,parameters.getFirst(),input,source));
        assertFalse(NativeJavaSymbolMapping.matchesSourceDeclaration(Map.of("kind","FIELD","name","a"),a,parameters.getFirst(),input,source));
        assertFalse(NativeJavaSymbolMapping.matchesSourceDeclaration(Map.of("kind","PARAMETER","name","x"),a,parameters.getFirst(),input,source));
        assertFalse(NativeJavaSymbolMapping.matchesSourceDeclaration(Map.of("kind","PARAMETER","name","b"),a,parameters.getLast(),input,source));
        var type=frontend.declarations().stream().filter(d->d.entity().kind()==EntityKind.TYPE&&d.entity().declaration().isPresent()).findFirst().orElseThrow().entity();
        assertFalse(NativeJavaSymbolMapping.matchesSourceDeclaration(Map.of("kind","INTERFACE","name","C"),type,ast.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow(),input,source));
    }
}
