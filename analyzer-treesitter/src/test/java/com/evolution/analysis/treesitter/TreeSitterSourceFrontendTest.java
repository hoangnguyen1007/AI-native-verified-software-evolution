package com.evolution.analysis.treesitter;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeSitterSourceFrontendTest {
    @Test void httpClientsRetainLiteralPathsAndExplicitMethodsWithoutInventingDynamicOptions()throws Exception {
        String text="fetch('/pets/42'); fetch('/pets', {method: 'POST'}); axios.delete('/cart/7'); fetch('/unknown', opts); fetch(`/pets/${id}`); const fake=\"fetch('/fake')\";";
        var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("typescript",text.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        var clients=facts.stream().filter(f->f.category().equals("HTTP_CALL_CANDIDATE")).toList();
        assertEquals(4,clients.size());
        assertEquals(List.of("'/pets/42'","'/pets'","'/cart/7'","'/unknown'"),clients.stream().map(TreeSitterSourceFrontend.Fact::name).toList());
        assertEquals(List.of("GET","POST","DELETE","UNKNOWN"),clients.stream().map(f->f.attributes().get("method")).toList());
    }
    @Test void cancellationAfterIntakeDoesNotEmitRowsOrPoisonTheNextParser()throws Exception {
        var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();var polls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class,()->new TreeSitterSourceFrontend().parse("javascript","f();".repeat(20000).getBytes(StandardCharsets.UTF_8),facts::add,()->polls.incrementAndGet()>=2));
        assertTrue(facts.isEmpty());assertTrue(polls.get()>=2);
        assertEquals(0,new TreeSitterSourceFrontend().parse("go","package main\nfunc main(){}".getBytes(StandardCharsets.UTF_8),facts::add,()->false).errors());
    }
    @Test void routeCandidatesKeepLiteralSyntaxWithoutAssumingAFrameworkBinding()throws Exception {
        String text="app.get('/health', handler); app.get(path, handler); app.get(`/x${path}`, handler); app.get(path, () => '/not-a-route'); const text = \"app.get('/fake')\";";var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("javascript",text.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        var routes=facts.stream().filter(f->f.category().equals("ROUTE_CANDIDATE")).toList();assertEquals(1,routes.size());assertEquals("'/health'",routes.getFirst().name());
        assertEquals("app.get('/health', handler)",text.substring(routes.getFirst().span().start(),routes.getFirst().span().end()));
    }
    @Test void fourBoutiqueLanguagesAcquireRealAstCallsWithoutExecutingSource()throws Exception {
        var sources=Map.of("go","package main\nimport \"fmt\"\nfunc f() { g() }\nfunc g() {}", "python","def f():\n    g()\ndef g():\n    pass\n", "csharp","class C { void F() { G(); } void G() {} }", "javascript","function f() { g(); } function g() {}");
        for(var entry:sources.entrySet()) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();var result=new TreeSitterSourceFrontend().parse(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            assertEquals(0,result.errors(),entry.getKey());assertTrue(facts.stream().anyMatch(f->f.category().equals("CALL")),entry.getKey());
            assertTrue(facts.stream().anyMatch(f->f.category().equals("DECLARATION")),entry.getKey());assertTrue(result.toolIdentity().startsWith("sha256:"));
            if(entry.getKey().equals("go"))assertTrue(facts.stream().anyMatch(f->f.category().equals("IMPORT")),"Go import declarations must remain imports");
        }
    }
    @Test void commentsStringsAndUnicodeCannotInventCallsOrShiftSourceSpans()throws Exception {
        String source="# fake()\ntext = 'fake() 😀'\ndef f():\n    print('é')\n";var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("python",source.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        var calls=facts.stream().filter(f->f.category().equals("CALL")).toList();assertEquals(1,calls.size());var call=calls.getFirst();
        assertEquals("print('é')",source.substring(call.span().start(),call.span().end()));assertEquals(4,call.span().startLine());assertEquals(5,call.span().startColumn());
    }
    @Test void everyBundledGrammarLoadsAndKeepsActualDeclarations()throws Exception {
        var sources=Map.ofEntries(Map.entry("typescript","function f(): void { g(); } function g(): void {}"),Map.entry("tsx","function f() { g(); return <div/>; } function g() {}"),
                Map.entry("c","void f(){g();} void g(){}"),Map.entry("cpp","struct C { void f(){g();} void g(){} };"),Map.entry("kotlin","fun f(){g()}\nfun g(){}"),
                Map.entry("scala","object C { def f() = g(); def g() = 1 }"),Map.entry("rust","fn f(){g();} fn g(){}"),Map.entry("php","<?php function f(){g();} function g(){}"),
                Map.entry("ruby","def f\n g()\nend\ndef g\nend\n"),Map.entry("dart","void f(){g();} void g(){}"),Map.entry("swift","func f() { g() }\nfunc g() {}"),Map.entry("objectivec","void f(){g();} void g(){}"));
        for(var entry:sources.entrySet()) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();var result=new TreeSitterSourceFrontend().parse(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            assertEquals(0,result.errors(),entry.getKey());assertTrue(facts.stream().anyMatch(f->f.category().equals("DECLARATION")),entry.getKey());
            assertTrue(facts.stream().anyMatch(f->f.category().equals("CALL")),entry.getKey()+facts);
        }
    }
    @Test void malformedSourceAndCancellationAreNeverReportedAsClean()throws Exception {
        var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();var result=new TreeSitterSourceFrontend().parse("python","def broken(:\n".getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        assertTrue(result.errors()>0);assertThrows(java.io.IOException.class,()->new TreeSitterSourceFrontend().parse("go",new byte[0],facts::add,()->true));
    }
}
