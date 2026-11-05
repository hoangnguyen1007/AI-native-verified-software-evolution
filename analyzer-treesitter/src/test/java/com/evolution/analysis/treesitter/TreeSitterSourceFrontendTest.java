package com.evolution.analysis.treesitter;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeSitterSourceFrontendTest {
    @Test void pythonGoAndCsharpHttpClientsAreCandidatesWithLiteralMethodAndUri()throws Exception {
        var sources=Map.of("python","import requests\nrequests.get('/pets/42')\nrequests.post('/pets')\nrequests.get(f'/pets/{ident}')\n",
                "go","package main\nfunc f(){ http.Get(\"/pets/42\"); http.Post(\"/pets\",\"text/plain\",nil) }",
                "csharp","class C { void F(){ client.GetAsync(\"/pets/42\"); client.PostAsync(\"/pets\",body); } }");
        for(var entry:sources.entrySet()) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();
            new TreeSitterSourceFrontend().parse(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            var clients=facts.stream().filter(f->f.category().equals("HTTP_CALL_CANDIDATE")).toList();
            assertEquals(2,clients.size(),entry.getKey());
            assertEquals(Set.of("GET","POST"),clients.stream().map(f->f.attributes().get("method")).collect(java.util.stream.Collectors.toSet()));
        }
    }
    @Test void protobufMessagesAndStreamingRpcKeepQualifiedNamesAndOriginalSpans()throws Exception {
        String source="\ufeff// service Fake { rpc Fake(No) returns (No); }\r\nsyntax = \"proto3\"; package shop.v1;\r\n"
                +"import \"google/protobuf/empty.proto\"; message Request { string id = 1; message Nested { int32 n = 1; } }\r\n"
                +"message Reply {} service Catalog { rpc Find (stream Request) returns (stream Reply) {} }";
        var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();
        var result=new TreeSitterSourceFrontend().parse("protobuf",source.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        assertEquals(0,result.errors());
        assertEquals(Set.of("shop.v1.Request","shop.v1.Request.Nested","shop.v1.Reply","shop.v1.Catalog"),facts.stream().filter(f->f.category().equals("DECLARATION")).map(f->f.attributes().get("qualifiedName")).collect(java.util.stream.Collectors.toSet()));
        var rpc=facts.stream().filter(f->f.category().equals("RPC_METHOD")).findFirst().orElseThrow();
        assertEquals("/shop.v1.Catalog/Find",rpc.attributes().get("grpcPath"));
        assertEquals("Request",rpc.attributes().get("requestType"));assertEquals("true",rpc.attributes().get("clientStreaming"));assertEquals("true",rpc.attributes().get("serverStreaming"));
        assertEquals("rpc Find (stream Request) returns (stream Reply) {}",source.substring(rpc.span().start(),rpc.span().end()));
        assertEquals(1,facts.stream().filter(f->f.category().equals("IMPORT")).count());
        assertTrue(facts.stream().noneMatch(f->f.name().equals("Fake")));
        assertTrue(facts.stream().filter(f->!f.owner().isEmpty()).allMatch(f->facts.stream().anyMatch(d->d.category().equals("DECLARATION")&&(d.span().start()+":"+d.span().end()).equals(f.owner()))));
    }
    @Test void incompleteAndUnknownProtobufSyntaxRetainsRecoveredFactsAndCancellation()throws Exception {
        for(String source:List.of("message Open { string id=1;", "message Healthy {} extend Unknown { optional string field=1; }", "message Healthy {} /* unclosed", "message Invalid {string field=;}")) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();
            var summary=new TreeSitterSourceFrontend().parse("protobuf",source.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            assertTrue(summary.errors()>0);assertTrue(facts.stream().allMatch(TreeSitterSourceFrontend.Fact::recovered));
        }
        assertThrows(java.io.IOException.class,()->new TreeSitterSourceFrontend().parse("protobuf",new byte[0],fact->{},()->true));
    }
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
