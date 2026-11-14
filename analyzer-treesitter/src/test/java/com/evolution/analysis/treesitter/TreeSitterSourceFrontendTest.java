package com.evolution.analysis.treesitter;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeSitterSourceFrontendTest {
    @Test void indirectServersCannotFollowReassignedDuplicateOrForwardBases()throws Exception {
        for(String declarations:List.of(
                "class Base(demo_pb2_grpc.CartServiceServicer):\n    pass\nclass Base:\n    pass\nclass Real(Base):\n    def GetCart(self,req,ctx): pass\n",
                "class Base(demo_pb2_grpc.CartServiceServicer):\n    pass\nBase=object\nclass Real(Base):\n    def GetCart(self,req,ctx): pass\n",
                "class Real(Base):\n    def GetCart(self,req,ctx): pass\nclass Base(demo_pb2_grpc.CartServiceServicer):\n    pass\n")) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();new TreeSitterSourceFrontend().parse("python",("import demo_pb2_grpc\n"+declarations).getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            assertTrue(facts.stream().noneMatch(f->f.category().equals("RPC_HANDLER_CANDIDATE")),declarations);
        }
    }
    @Test void goAllocationsCannotFollowShadowedNewOrReassignedAliases()throws Exception {
        for(String body:List.of("func new(x interface{}) *cart{return nil}\nfunc run(){svc:=new(cart);pb.RegisterCartServiceServer(server,svc)}",
                "func run(new func(interface{}) *cart){svc:=new(cart);pb.RegisterCartServiceServer(server,svc)}",
                "func run(){svc:=new(cart);svc=unknown;pb.RegisterCartServiceServer(server,svc)}")) {
            String source="package main\ntype cart struct{}\nfunc (s *cart) GetCart(ctx context.Context,r *Req)(*Reply,error){return nil,nil}\n"+body;
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();new TreeSitterSourceFrontend().parse("go",source.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            assertTrue(facts.stream().noneMatch(f->f.category().equals("RPC_HANDLER_CANDIDATE")),body);
        }
    }
    @Test void localPythonServicerInheritanceAndGoNewRegistrationsKeepRealHandlerSpans()throws Exception {
        assertEquals("source.tree-sitter:m8.4-rpc-local-inheritance-v5",TreeSitterSourceFrontend.PROVIDER);
        var sources=Map.of("python","import demo_pb2_grpc\nclass Base(demo_pb2_grpc.EmailServiceServicer):\n    pass\nclass Middle(Base):\n    pass\nclass Real(Middle):\n    def SendOrderConfirmation(self, req, ctx):\n        pass\nclass Dummy(Base):\n    def SendOrderConfirmation(self, req, ctx):\n        pass\n",
                "go","package main\ntype checkout struct{}\nfunc (s *checkout) PlaceOrder(ctx context.Context, r *Req) (*Reply,error){return nil,nil}\nfunc run(){svc := new(checkout); pb.RegisterCheckoutServiceServer(server,svc)}\n");
        for(var entry:sources.entrySet()) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();var result=new TreeSitterSourceFrontend().parse(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            assertEquals(0,result.errors());var handlers=facts.stream().filter(f->f.category().equals("RPC_HANDLER_CANDIDATE")).toList();
            assertEquals(entry.getKey().equals("python")?2:1,handlers.size());
            assertTrue(handlers.stream().allMatch(f->entry.getValue().substring(f.span().start(),f.span().end()).contains(entry.getKey().equals("python")?"def SendOrderConfirmation":"func (s *checkout) PlaceOrder")));
        }
    }
    @Test void registeredGoReceiversAndJavascriptHandlerMapsKeepServiceSyntax()throws Exception {
        var sources=Map.of("go","package main\ntype cart struct {}\nfunc (s *cart) GetCart(ctx context.Context, r *Req) (*Reply,error) { return nil,nil }\nfunc run(){ svc := &cart{}; pb.RegisterCartServiceServer(server, svc) }\n",
                "javascript","function getCart(call, callback) {}\nfunction run(){ server.addService(shop.CartService.service, {getCart}); }\n");
        for(var entry:sources.entrySet()) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();new TreeSitterSourceFrontend().parse(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            var handlers=facts.stream().filter(f->f.category().equals("RPC_HANDLER_CANDIDATE")).toList();assertEquals(1,handlers.size(),entry.getKey());assertEquals("CartService",handlers.getFirst().attributes().get("service"));
        }
    }
    @Test void shadowedParametersStringsAndOrdinaryMethodNamesCannotCreateRpcCalls()throws Exception {
        String source="import demo_pb2_grpc\nstub = demo_pb2_grpc.CartServiceStub(channel)\ndef f(stub):\n    stub.GetCart(req)\nordinary.GetCart(req)\ntext='stub.GetCart(req)'\n# stub.GetCart(req)\n";
        var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();new TreeSitterSourceFrontend().parse("python",source.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        assertTrue(facts.stream().noneMatch(f->f.category().equals("RPC_CALL_CANDIDATE")));
    }
    @Test void grpcConstructorsAndServicerMethodsProduceSourceCandidatesWithActualRanges()throws Exception {
        var sources=Map.of("go","package main\nfunc f(){ pb.NewCartServiceClient(conn).GetCart(ctx, req) }",
                "python","import demo_pb2_grpc\nstub = demo_pb2_grpc.CartServiceStub(channel)\nclass Server(demo_pb2_grpc.CartServiceServicer):\n    def GetCart(self, req, ctx):\n        return stub.GetCart(req)\n",
                "csharp","class Server : Shop.CartService.CartServiceBase { public override Task<Reply> GetCart(Req r, CallContext c) { return client.GetCartAsync(r); } CartServiceClient client = new CartServiceClient(channel); }");
        for(var entry:sources.entrySet()) {
            var facts=new ArrayList<TreeSitterSourceFrontend.Fact>();new TreeSitterSourceFrontend().parse(entry.getKey(),entry.getValue().getBytes(StandardCharsets.UTF_8),facts::add,()->false);
            var calls=facts.stream().filter(f->f.category().equals("RPC_CALL_CANDIDATE")).toList();assertEquals(1,calls.size(),entry.getKey()+facts);
            assertEquals("CartService",calls.getFirst().attributes().get("service"));assertTrue(entry.getValue().substring(calls.getFirst().span().start(),calls.getFirst().span().end()).contains("GetCart"));
            if(!entry.getKey().equals("go"))assertEquals(1,facts.stream().filter(f->f.category().equals("RPC_HANDLER_CANDIDATE")).count(),entry.getKey());
        }
    }
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
    @Test void transcodedGrammarBytesStillPointToTheOriginalBomAndUnicodeDocument()throws Exception {
        String source="\uFEFF# " + "😀".repeat(257) + "\r\ndef f():\r\n    print('é😀')\r\n";
        var frontend = new TreeSitterSourceFrontend();
        var expected = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var summary = frontend.parse("python",source.getBytes(StandardCharsets.UTF_8),expected::add,()->false);
        var call = expected.stream().filter(fact -> fact.category().equals("CALL")).findFirst().orElseThrow();
        assertEquals("print('é😀')",source.substring(call.span().start(),call.span().end()));
        assertEquals(3,call.span().startLine());
        assertEquals(5,call.span().startColumn());
        for (var encoding : List.of(StandardCharsets.UTF_16LE,StandardCharsets.UTF_16BE)) {
            var observed = new ArrayList<TreeSitterSourceFrontend.Fact>();
            assertEquals(summary,frontend.parse("python",source.getBytes(encoding),observed::add,()->false));
            assertEquals(expected,observed);
        }
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
