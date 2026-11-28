package com.evolution.analysis.treesitter;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TreeSitterSourceFrontendTest {
    @Test
    void indirectServersCannotFollowReassignedDuplicateOrForwardBases() throws Exception {
        for (String declarations : List.of(
                "class Base(demo_pb2_grpc.CartServiceServicer):\n    pass\nclass Base:\n    pass\nclass Real(Base):\n    def GetCart(self,req,ctx): pass\n",
                "class Base(demo_pb2_grpc.CartServiceServicer):\n    pass\nBase=object\nclass Real(Base):\n    def GetCart(self,req,ctx): pass\n",
                "class Real(Base):\n    def GetCart(self,req,ctx): pass\nclass Base(demo_pb2_grpc.CartServiceServicer):\n    pass\n")) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            new TreeSitterSourceFrontend().parse("python",
                    ("import demo_pb2_grpc\n" + declarations).getBytes(StandardCharsets.UTF_8), facts::add,
                    () -> false);
            assertTrue(facts.stream().noneMatch(f -> f.category().equals("RPC_HANDLER_CANDIDATE")), declarations);
        }
    }

    @Test
    void goAllocationsCannotFollowShadowedNewOrReassignedAliases() throws Exception {
        for (String body : List.of(
                "func new(x interface{}) *cart{return nil}\nfunc run(){svc:=new(cart);pb.RegisterCartServiceServer(server,svc)}",
                "func run(new func(interface{}) *cart){svc:=new(cart);pb.RegisterCartServiceServer(server,svc)}",
                "func run(){svc:=new(cart);svc=unknown;pb.RegisterCartServiceServer(server,svc)}")) {
            String source = "package main\ntype cart struct{}\nfunc (s *cart) GetCart(ctx context.Context,r *Req)(*Reply,error){return nil,nil}\n"
                    + body;
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            new TreeSitterSourceFrontend().parse("go", source.getBytes(StandardCharsets.UTF_8), facts::add,
                    () -> false);
            assertTrue(facts.stream().noneMatch(f -> f.category().equals("RPC_HANDLER_CANDIDATE")), body);
        }
    }

    @Test
    void localPythonServicerInheritanceAndGoNewRegistrationsKeepRealHandlerSpans() throws Exception {
        assertEquals("source.tree-sitter:m8.4-semantic-wrapper-v6", TreeSitterSourceFrontend.PROVIDER);
        var sources = Map.of("python",
                "import demo_pb2_grpc\nclass Base(demo_pb2_grpc.EmailServiceServicer):\n    pass\nclass Middle(Base):\n    pass\nclass Real(Middle):\n    def SendOrderConfirmation(self, req, ctx):\n        pass\nclass Dummy(Base):\n    def SendOrderConfirmation(self, req, ctx):\n        pass\n",
                "go",
                "package main\ntype checkout struct{}\nfunc (s *checkout) PlaceOrder(ctx context.Context, r *Req) (*Reply,error){return nil,nil}\nfunc run(){svc := new(checkout); pb.RegisterCheckoutServiceServer(server,svc)}\n");
        for (var entry : sources.entrySet()) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            var result = new TreeSitterSourceFrontend().parse(entry.getKey(),
                    entry.getValue().getBytes(StandardCharsets.UTF_8), facts::add, () -> false);
            assertEquals(0, result.errors());
            var handlers = facts.stream().filter(f -> f.category().equals("RPC_HANDLER_CANDIDATE")).toList();
            assertEquals(entry.getKey().equals("python") ? 2 : 1, handlers.size());
            assertTrue(handlers.stream()
                    .allMatch(f -> entry.getValue().substring(f.span().start(), f.span().end())
                            .contains(entry.getKey().equals("python") ? "def SendOrderConfirmation"
                                    : "func (s *checkout) PlaceOrder")));
        }
    }

    @Test
    void registeredGoReceiversAndJavascriptHandlerMapsKeepServiceSyntax() throws Exception {
        var sources = Map.of("go",
                "package main\ntype cart struct {}\nfunc (s *cart) GetCart(ctx context.Context, r *Req) (*Reply,error) { return nil,nil }\nfunc run(){ svc := &cart{}; pb.RegisterCartServiceServer(server, svc) }\n",
                "javascript",
                "function getCart(call, callback) {}\nfunction run(){ server.addService(shop.CartService.service, {getCart}); }\n");
        for (var entry : sources.entrySet()) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            new TreeSitterSourceFrontend().parse(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8),
                    facts::add, () -> false);
            var handlers = facts.stream().filter(f -> f.category().equals("RPC_HANDLER_CANDIDATE")).toList();
            assertEquals(1, handlers.size(), entry.getKey());
            assertEquals("CartService", handlers.getFirst().attributes().get("service"));
        }
    }

    @Test
    void shadowedParametersStringsAndOrdinaryMethodNamesCannotCreateRpcCalls() throws Exception {
        String source = "import demo_pb2_grpc\nstub = demo_pb2_grpc.CartServiceStub(channel)\ndef f(stub):\n    stub.GetCart(req)\nordinary.GetCart(req)\ntext='stub.GetCart(req)'\n# stub.GetCart(req)\n";
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("python", source.getBytes(StandardCharsets.UTF_8), facts::add,
                () -> false);
        assertTrue(facts.stream().noneMatch(f -> f.category().equals("RPC_CALL_CANDIDATE")));
    }

    @Test
    void grpcConstructorsAndServicerMethodsProduceSourceCandidatesWithActualRanges() throws Exception {
        var sources = Map.of("go", "package main\nfunc f(){ pb.NewCartServiceClient(conn).GetCart(ctx, req) }",
                "python",
                "import demo_pb2_grpc\nstub = demo_pb2_grpc.CartServiceStub(channel)\nclass Server(demo_pb2_grpc.CartServiceServicer):\n    def GetCart(self, req, ctx):\n        return stub.GetCart(req)\n",
                "csharp",
                "class Server : Shop.CartService.CartServiceBase { public override Task<Reply> GetCart(Req r, CallContext c) { return client.GetCartAsync(r); } CartServiceClient client = new CartServiceClient(channel); }");
        for (var entry : sources.entrySet()) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            new TreeSitterSourceFrontend().parse(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8),
                    facts::add, () -> false);
            var calls = facts.stream().filter(f -> f.category().equals("RPC_CALL_CANDIDATE")).toList();
            assertEquals(1, calls.size(), entry.getKey() + facts);
            assertEquals("CartService", calls.getFirst().attributes().get("service"));
            assertTrue(entry.getValue().substring(calls.getFirst().span().start(), calls.getFirst().span().end())
                    .contains("GetCart"));
            if (!entry.getKey().equals("go"))
                assertEquals(1, facts.stream().filter(f -> f.category().equals("RPC_HANDLER_CANDIDATE")).count(),
                        entry.getKey());
        }
    }

    @Test
    void pythonGoAndCsharpHttpClientsAreCandidatesWithLiteralMethodAndUri() throws Exception {
        var sources = Map.of("python",
                "import requests\nrequests.get('/pets/42')\nrequests.post('/pets')\nrequests.get(f'/pets/{ident}')\n",
                "go", "package main\nfunc f(){ http.Get(\"/pets/42\"); http.Post(\"/pets\",\"text/plain\",nil) }",
                "csharp", "class C { void F(){ client.GetAsync(\"/pets/42\"); client.PostAsync(\"/pets\",body); } }");
        for (var entry : sources.entrySet()) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            new TreeSitterSourceFrontend().parse(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8),
                    facts::add, () -> false);
            var clients = facts.stream().filter(f -> f.category().equals("HTTP_CALL_CANDIDATE")).toList();
            assertEquals(2, clients.size(), entry.getKey());
            assertEquals(Set.of("GET", "POST"), clients.stream().map(f -> f.attributes().get("method"))
                    .collect(java.util.stream.Collectors.toSet()));
        }
    }

    @Test
    void protobufMessagesAndStreamingRpcKeepQualifiedNamesAndOriginalSpans() throws Exception {
        String source = "\ufeff// service Fake { rpc Fake(No) returns (No); }\r\nsyntax = \"proto3\"; package shop.v1;\r\n"
                + "import \"google/protobuf/empty.proto\"; message Request { string id = 1; message Nested { int32 n = 1; } }\r\n"
                + "message Reply {} service Catalog { rpc Find (stream Request) returns (stream Reply) {} }";
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var result = new TreeSitterSourceFrontend().parse("protobuf", source.getBytes(StandardCharsets.UTF_8),
                facts::add, () -> false);
        assertEquals(0, result.errors());
        assertEquals(Set.of("shop.v1.Request", "shop.v1.Request.Nested", "shop.v1.Reply", "shop.v1.Catalog"),
                facts.stream().filter(f -> f.category().equals("DECLARATION"))
                        .map(f -> f.attributes().get("qualifiedName")).collect(java.util.stream.Collectors.toSet()));
        var rpc = facts.stream().filter(f -> f.category().equals("RPC_METHOD")).findFirst().orElseThrow();
        assertEquals("/shop.v1.Catalog/Find", rpc.attributes().get("grpcPath"));
        assertEquals("Request", rpc.attributes().get("requestType"));
        assertEquals("true", rpc.attributes().get("clientStreaming"));
        assertEquals("true", rpc.attributes().get("serverStreaming"));
        assertEquals("rpc Find (stream Request) returns (stream Reply) {}",
                source.substring(rpc.span().start(), rpc.span().end()));
        assertEquals(1, facts.stream().filter(f -> f.category().equals("IMPORT")).count());
        assertTrue(facts.stream().noneMatch(f -> f.name().equals("Fake")));
        assertTrue(facts.stream().filter(f -> !f.owner().isEmpty())
                .allMatch(f -> facts.stream().anyMatch(d -> d.category().equals("DECLARATION")
                        && (d.span().start() + ":" + d.span().end()).equals(f.owner()))));
    }

    @Test
    void incompleteAndUnknownProtobufSyntaxRetainsRecoveredFactsAndCancellation() throws Exception {
        for (String source : List.of("message Open { string id=1;",
                "message Healthy {} extend Unknown { optional string field=1; }", "message Healthy {} /* unclosed",
                "message Invalid {string field=;}")) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            var summary = new TreeSitterSourceFrontend().parse("protobuf", source.getBytes(StandardCharsets.UTF_8),
                    facts::add, () -> false);
            assertTrue(summary.errors() > 0);
            assertTrue(facts.stream().allMatch(TreeSitterSourceFrontend.Fact::recovered));
        }
        assertThrows(java.io.IOException.class,
                () -> new TreeSitterSourceFrontend().parse("protobuf", new byte[0], fact -> {
                }, () -> true));
    }

    @Test
    void httpClientsRetainLiteralPathsAndExplicitMethodsWithoutInventingDynamicOptions() throws Exception {
        String text = "fetch('/pets/42'); fetch('/pets', {method: 'POST'}); axios.delete('/cart/7'); fetch('/unknown', opts); fetch(`/pets/${id}`); const fake=\"fetch('/fake')\";";
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("typescript", text.getBytes(StandardCharsets.UTF_8), facts::add,
                () -> false);
        var clients = facts.stream().filter(f -> f.category().equals("HTTP_CALL_CANDIDATE")).toList();
        assertEquals(4, clients.size());
        assertEquals(List.of("'/pets/42'", "'/pets'", "'/cart/7'", "'/unknown'"),
                clients.stream().map(TreeSitterSourceFrontend.Fact::name).toList());
        assertEquals(List.of("GET", "POST", "DELETE", "UNKNOWN"),
                clients.stream().map(f -> f.attributes().get("method")).toList());
    }

    @Test
    void cancellationAfterIntakeDoesNotEmitRowsOrPoisonTheNextParser() throws Exception {
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var polls = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(java.io.IOException.class, () -> new TreeSitterSourceFrontend().parse("javascript",
                "f();".repeat(20000).getBytes(StandardCharsets.UTF_8), facts::add, () -> polls.incrementAndGet() >= 2));
        assertTrue(facts.isEmpty());
        assertTrue(polls.get() >= 2);
        assertEquals(0,
                new TreeSitterSourceFrontend().parse("go",
                        "package main\nfunc main(){}".getBytes(StandardCharsets.UTF_8), facts::add, () -> false)
                        .errors());
    }

    @Test
    void routeCandidatesKeepLiteralSyntaxWithoutAssumingAFrameworkBinding() throws Exception {
        String text = "app.get('/health', handler); app.get(path, handler); app.get(`/x${path}`, handler); app.get(path, () => '/not-a-route'); const text = \"app.get('/fake')\";";
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("javascript", text.getBytes(StandardCharsets.UTF_8), facts::add,
                () -> false);
        var routes = facts.stream().filter(f -> f.category().equals("ROUTE_CANDIDATE")).toList();
        assertEquals(1, routes.size());
        assertEquals("'/health'", routes.getFirst().name());
        assertEquals("app.get('/health', handler)",
                text.substring(routes.getFirst().span().start(), routes.getFirst().span().end()));
    }

    @Test
    void fourBoutiqueLanguagesAcquireRealAstCallsWithoutExecutingSource() throws Exception {
        var sources = Map.of("go", "package main\nimport \"fmt\"\nfunc f() { g() }\nfunc g() {}", "python",
                "def f():\n    g()\ndef g():\n    pass\n", "csharp", "class C { void F() { G(); } void G() {} }",
                "javascript", "function f() { g(); } function g() {}");
        for (var entry : sources.entrySet()) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            var result = new TreeSitterSourceFrontend().parse(entry.getKey(),
                    entry.getValue().getBytes(StandardCharsets.UTF_8), facts::add, () -> false);
            assertEquals(0, result.errors(), entry.getKey());
            assertTrue(facts.stream().anyMatch(f -> f.category().equals("CALL")), entry.getKey());
            assertTrue(facts.stream().anyMatch(f -> f.category().equals("DECLARATION")), entry.getKey());
            assertTrue(result.toolIdentity().startsWith("sha256:"));
            if (entry.getKey().equals("go"))
                assertTrue(facts.stream().anyMatch(f -> f.category().equals("IMPORT")),
                        "Go import declarations must remain imports");
        }
    }

    @Test
    void commentsStringsAndUnicodeCannotInventCallsOrShiftSourceSpans() throws Exception {
        String source = "# fake()\ntext = 'fake() 😀'\ndef f():\n    print('é')\n";
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("python", source.getBytes(StandardCharsets.UTF_8), facts::add,
                () -> false);
        var calls = facts.stream().filter(f -> f.category().equals("CALL")).toList();
        assertEquals(1, calls.size());
        var call = calls.getFirst();
        assertEquals("print('é')", source.substring(call.span().start(), call.span().end()));
        assertEquals(4, call.span().startLine());
        assertEquals(5, call.span().startColumn());
    }

    @Test
    void transcodedGrammarBytesStillPointToTheOriginalBomAndUnicodeDocument() throws Exception {
        String source = "\uFEFF# " + "😀".repeat(257) + "\r\ndef f():\r\n    print('é😀')\r\n";
        var frontend = new TreeSitterSourceFrontend();
        var expected = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var summary = frontend.parse("python", source.getBytes(StandardCharsets.UTF_8), expected::add, () -> false);
        var call = expected.stream().filter(fact -> fact.category().equals("CALL")).findFirst().orElseThrow();
        assertEquals("print('é😀')", source.substring(call.span().start(), call.span().end()));
        assertEquals(3, call.span().startLine());
        assertEquals(5, call.span().startColumn());
        for (var encoding : List.of(StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE)) {
            var observed = new ArrayList<TreeSitterSourceFrontend.Fact>();
            assertEquals(summary, frontend.parse("python", source.getBytes(encoding), observed::add, () -> false));
            assertEquals(expected, observed);
        }
    }

    @Test
    void everyBundledGrammarLoadsAndKeepsActualDeclarations() throws Exception {
        var sources = Map.ofEntries(Map.entry("typescript", "function f(): void { g(); } function g(): void {}"),
                Map.entry("tsx", "function f() { g(); return <div/>; } function g() {}"),
                Map.entry("c", "void f(){g();} void g(){}"),
                Map.entry("cpp", "struct C { void f(){g();} void g(){} };"),
                Map.entry("kotlin", "fun f(){g()}\nfun g(){}"),
                Map.entry("scala", "object C { def f() = g(); def g() = 1 }"),
                Map.entry("rust", "fn f(){g();} fn g(){}"), Map.entry("php", "<?php function f(){g();} function g(){}"),
                Map.entry("ruby", "def f\n g()\nend\ndef g\nend\n"), Map.entry("dart", "void f(){g();} void g(){}"),
                Map.entry("swift", "func f() { g() }\nfunc g() {}"),
                Map.entry("objectivec", "void f(){g();} void g(){}"));
        for (var entry : sources.entrySet()) {
            var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
            var result = new TreeSitterSourceFrontend().parse(entry.getKey(),
                    entry.getValue().getBytes(StandardCharsets.UTF_8), facts::add, () -> false);
            assertEquals(0, result.errors(), entry.getKey());
            assertTrue(facts.stream().anyMatch(f -> f.category().equals("DECLARATION")), entry.getKey());
            assertTrue(facts.stream().anyMatch(f -> f.category().equals("CALL")), entry.getKey() + facts);
        }
    }

    @Test
    void malformedSourceAndCancellationAreNeverReportedAsClean() throws Exception {
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var result = new TreeSitterSourceFrontend().parse("python", "def broken(:\n".getBytes(StandardCharsets.UTF_8),
                facts::add, () -> false);
        assertTrue(result.errors() > 0);
        assertThrows(java.io.IOException.class,
                () -> new TreeSitterSourceFrontend().parse("go", new byte[0], facts::add, () -> true));
    }

    @Test
    void factToMapAndFastArchitectureModePreserveCanonicalParityAndArchitecturalFacts() throws Exception {
        String tsSource = "import { Base } from './base';\nexport class CartController extends Base {\n  load(id: string): Promise<number> {\n    app.get('/cart', handler);\n    return fetch('/api/items');\n  }\n}\n";
        byte[] bytes = tsSource.getBytes(StandardCharsets.UTF_8);
        var frontend = new TreeSitterSourceFrontend();
        var fullFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var fullSummary = frontend.parse("typescript", bytes, fullFacts::add, () -> false, false);
        assertEquals(
                com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(
                        com.evolution.analysis.contract.serialization.CanonicalJson.write(fullSummary),
                        com.evolution.analysis.graph.GraphJson.Limits.defaults())),
                fullSummary.toMap());
        for (var fact : fullFacts) {
            var viaJson = com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(
                    com.evolution.analysis.contract.serialization.CanonicalJson.write(fact),
                    com.evolution.analysis.graph.GraphJson.Limits.defaults()));
            assertEquals(viaJson, fact.toMap());
            assertEquals(com.evolution.analysis.contract.serialization.CanonicalJson.write(fact),
                    com.evolution.analysis.contract.serialization.CanonicalJson.write(fact.toMap()));
        }
        assertTrue(fullFacts.stream().anyMatch(f -> f.category().equals("TYPE_SYNTAX")));

        var fastFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var fastSummary = frontend.analyze("typescript", bytes, fastFacts::add, () -> false, true);
        assertEquals(0, fastSummary.errors());
        assertTrue(fastFacts.stream().noneMatch(f -> f.category().equals("TYPE_SYNTAX")));
        assertEquals(
                fullFacts.stream().filter(f -> !f.category().equals("TYPE_SYNTAX")).toList(),
                fastFacts);
    }

    @Test
    void capturesExpandedHttpClientsTemplateLiteralsAnnotationsAndAsyncEvents() throws Exception {
        String tsSource = "import { Get } from '@nestjs/common';\n@Get('/users/:id')\nexport class UserController {\n  async load() { await apiClient.patch(`/users/1`); await kafkaProducer.publish('order.created', {}); }\n}\n";
        var tsFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("typescript", tsSource.getBytes(StandardCharsets.UTF_8), tsFacts::add,
                () -> false);
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("'/users/:id'") && "GET".equals(f.attributes().get("method"))));
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("`/users/1`") && "PATCH".equals(f.attributes().get("method"))));
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("'order.created'") && "KAFKA".equals(f.attributes().get("protocol"))));

        String pySource = "@kafka.subscribe('order.created')\ndef handle_order(msg):\n    pass\n";
        var pyFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("python", pySource.getBytes(StandardCharsets.UTF_8), pyFacts::add,
                () -> false);
        assertTrue(pyFacts.stream()
                .anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE") && f.name().equals("'order.created'")));

        String ktSource = "fun fetchUser() {\n  httpClient.get(\"/api/users\")\n  applicationEventPublisher.publishEvent(\"user.created\")\n}\n";
        var ktFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("kotlin", ktSource.getBytes(StandardCharsets.UTF_8), ktFacts::add,
                () -> false);
        assertTrue(ktFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"/api/users\"") && "GET".equals(f.attributes().get("method"))));
        assertTrue(ktFacts.stream()
                .anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE") && f.name().equals("\"user.created\"")));
    }

    @Test
    void resolvesImportAliasesConstantConcatFactoryClientsAndWrapperParametersWithoutInventingDynamicUrls()
            throws Exception {
        String pySource = """
                import requests as req
                BASE = '/api/v1'
                def ping():
                    req.get(BASE + '/orders')
                    req.get(f'/orders/{ident}')
                def fetch_item(path):
                    return req.get(BASE + '/static')
                def call_http(endpoint):
                    return req.post(endpoint)
                """;
        var pyFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("python", pySource.getBytes(StandardCharsets.UTF_8), pyFacts::add,
                () -> false);
        assertTrue(pyFacts.stream()
                .anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE") && f.name().equals("\"/api/v1/orders\"")));
        assertTrue(pyFacts.stream()
                .anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE") && f.name().equals("\"/api/v1/static\"")));
        assertEquals(2, pyFacts.stream().filter(f -> f.category().equals("HTTP_CALL_CANDIDATE")).count());
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("HTTP_WRAPPER")
                && f.name().equals("call_http") && "0".equals(f.attributes().get("paramIndex"))
                && "POST".equals(f.attributes().get("method"))));

        String tsSource = """
                const ordersSdk = axios.create({ baseURL: '/api/v1' });
                const bus = new KafkaProducer();
                export function loadOrders() {
                    ordersSdk.get('/orders');
                    bus.send('orders.created', {});
                    fetch(`/orders/${id}`);
                }
                export function publishTopic(topic: string) {
                    bus.send(topic, {});
                }
                """;
        var tsFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        new TreeSitterSourceFrontend().parse("typescript", tsSource.getBytes(StandardCharsets.UTF_8), tsFacts::add,
                () -> false);
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"/api/v1/orders\"") && "GET".equals(f.attributes().get("method"))));
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("'orders.created'") && "KAFKA".equals(f.attributes().get("protocol"))));
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("EVENT_WRAPPER")
                && f.name().equals("publishTopic") && "0".equals(f.attributes().get("paramIndex"))
                && "KAFKA".equals(f.attributes().get("protocol"))));
        assertEquals(1, tsFacts.stream().filter(f -> f.category().equals("HTTP_CALL_CANDIDATE")).count());
    }

    @Test
    void wave3PolyglotFrameworksCaptureFastApiCeleryNestJsNextJsRefitMassTransitLaravelGuzzleRailsFaraday()
            throws Exception {
        var frontend = new TreeSitterSourceFrontend();

        // Python FastAPI router prefixes + Celery tasks/producers
        String pySource = """
                from fastapi import APIRouter
                from celery import shared_task
                router = APIRouter(prefix="/api/v1/users")
                @router.get("/{user_id}")
                def get_user(user_id):
                    return {}
                @router.post("")
                def create_user():
                    send_email.delay(1)
                    process_order.apply_async(args=[2])
                    app.send_task("jobs.cleanup")
                @app.task(name="emails.send")
                def send_email(uid):
                    pass
                @shared_task
                def process_order(oid):
                    pass
                """;
        var pyFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("python", pySource.getBytes(StandardCharsets.UTF_8), pyFacts::add, () -> false);
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/api/v1/users/{user_id}\"") && "GET".equals(f.attributes().get("method"))),
                () -> "FastAPI prefixed GET missing: " + pyFacts);
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/api/v1/users\"") && "POST".equals(f.attributes().get("method"))),
                () -> "FastAPI prefixed empty-path POST missing: " + pyFacts);
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"emails.send\"") && "CELERY".equals(f.attributes().get("protocol"))));
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"process_order\"") && "CELERY".equals(f.attributes().get("protocol"))));
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"emails.send\"") && "CELERY".equals(f.attributes().get("protocol"))));
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"process_order\"") && "CELERY".equals(f.attributes().get("protocol"))));
        assertTrue(pyFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"jobs.cleanup\"") && "CELERY".equals(f.attributes().get("protocol"))));

        // TypeScript NestJS @Controller + method decorators
        String nestSource = """
                import { Controller, Get, Post, Put, Delete, Patch } from '@nestjs/common';
                @Controller('orders')
                export class OrdersController {
                  @Get()
                  list() {}
                  @Get(':id')
                  findOne() {}
                  @Post('/create')
                  create() {}
                  @Put(':id')
                  replace() {}
                  @Patch(':id/status')
                  patchStatus() {}
                  @Delete(':id')
                  remove() {}
                }
                """;
        var nestFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("typescript", nestSource.getBytes(StandardCharsets.UTF_8), nestFacts::add, () -> false);
        assertTrue(nestFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/orders\"") && "GET".equals(f.attributes().get("method"))));
        assertTrue(nestFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/orders/:id\"") && "GET".equals(f.attributes().get("method"))));
        assertTrue(nestFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/orders/create\"") && "POST".equals(f.attributes().get("method"))));
        assertTrue(nestFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/orders/:id\"") && "PUT".equals(f.attributes().get("method"))));
        assertTrue(nestFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/orders/:id/status\"") && "PATCH".equals(f.attributes().get("method"))));
        assertTrue(nestFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/orders/:id\"") && "DELETE".equals(f.attributes().get("method"))));

        // TypeScript Next.js App Router route exports
        String nextSource = """
                export async function GET(request: Request) { return new Response('ok'); }
                export const POST = async (request: Request) => new Response('created');
                export async function DELETE(request: Request) { return new Response(null); }
                """;
        var nextFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("typescript", nextSource.getBytes(StandardCharsets.UTF_8), nextFacts::add, () -> false);
        var nextRoutes = nextFacts.stream().filter(f -> f.category().equals("ROUTE_CANDIDATE")).toList();
        assertEquals(Set.of("GET", "POST", "DELETE"),
                nextRoutes.stream().map(f -> f.attributes().get("method")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(nextRoutes.stream().allMatch(f -> "nextjs-app-router".equals(f.attributes().get("framework"))));

        // C# Refit + MassTransit
        String csSource = """
                using Refit;
                using MassTransit;
                public interface IUsersApi {
                    [Get("/users/{id}")]
                    Task<string> GetUser(string id);
                    [Post("/users")]
                    Task CreateUser(object body);
                }
                public class OrderConsumer : IConsumer<OrderSubmitted> {
                    public Task Consume(ConsumeContext<OrderSubmitted> ctx) { return Task.CompletedTask; }
                }
                public class OrderService {
                    public void Submit(IPublishEndpoint publisher, IBus bus) {
                        publisher.Publish<OrderSubmitted>(new OrderSubmitted());
                        bus.Send(new ChargeCard());
                    }
                }
                """;
        var csFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("csharp", csSource.getBytes(StandardCharsets.UTF_8), csFacts::add, () -> false);
        assertTrue(csFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"/users/{id}\"") && "GET".equals(f.attributes().get("method"))));
        assertTrue(csFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"/users\"") && "POST".equals(f.attributes().get("method"))));
        assertTrue(csFacts.stream().noneMatch(f -> f.category().equals("ROUTE_CANDIDATE")),
                "Refit interface attributes must be HTTP_CALL_CANDIDATE, not ROUTE_CANDIDATE");
        assertTrue(csFacts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"OrderSubmitted\"") && "MASSTRANSIT".equals(f.attributes().get("protocol"))));
        assertTrue(csFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"OrderSubmitted\"") && "MASSTRANSIT".equals(f.attributes().get("protocol"))));
        assertTrue(csFacts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"ChargeCard\"") && "MASSTRANSIT".equals(f.attributes().get("protocol"))));

        // PHP Laravel + Guzzle
        String phpSource = """
                <?php
                Route::get('/users', 'UserController@index');
                Route::post('/orders', 'OrderController@store');
                $client = new \\GuzzleHttp\\Client(['base_uri' => 'https://api.example.com']);
                $client->get('/items');
                $client->request('PUT', '/items/1');
                Http::delete('https://api.example.com/items/2');
                """;
        var phpFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("php", phpSource.getBytes(StandardCharsets.UTF_8), phpFacts::add, () -> false);
        assertTrue(phpFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("'/users'") && "GET".equals(f.attributes().get("method"))));
        assertTrue(phpFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("'/orders'") && "POST".equals(f.attributes().get("method"))));
        assertTrue(phpFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"https://api.example.com/items\"") && "GET".equals(f.attributes().get("method"))));
        assertTrue(phpFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"https://api.example.com/items/1\"") && "PUT".equals(f.attributes().get("method"))));
        assertTrue(phpFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("'https://api.example.com/items/2'") && "DELETE".equals(f.attributes().get("method"))));

        // Ruby Rails routes + Faraday
        String rbSource = """
                get '/users', to: 'users#index'
                post '/orders', to: 'orders#create'
                resources :products
                Faraday.get('https://api.example.com/health')
                conn = Faraday.new(url: 'https://api.example.com')
                conn.patch('/orders/9')
                """;
        var rbFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("ruby", rbSource.getBytes(StandardCharsets.UTF_8), rbFacts::add, () -> false);
        assertTrue(rbFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("'/users'") && "GET".equals(f.attributes().get("method"))));
        assertTrue(rbFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("'/orders'") && "POST".equals(f.attributes().get("method"))));
        assertTrue(rbFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/products\"")));
        assertTrue(rbFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("'https://api.example.com/health'") && "GET".equals(f.attributes().get("method"))));
        assertTrue(rbFacts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"https://api.example.com/orders/9\"") && "PATCH".equals(f.attributes().get("method"))));
    }

    @Test
    void nonAdditiveBinaryOpsNullFetchArgsLongDeclarationsAndEventTieBreakingRemainSafe() throws Exception {
        var frontend = new TreeSitterSourceFrontend();
        String tsSource = """
                const huge = "x".repeat(2000) + "${'y'.repeat(1500)}";
                app.get('/a+b' === '/c', handler);
                fetch('/ok' === '/x+y');
                const kafkaClient = new KafkaConsumer();
                kafkaClient.subscribe('orders.topic');
                """;
        var tsFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var summary = frontend.parse("typescript", tsSource.getBytes(StandardCharsets.UTF_8), tsFacts::add, () -> false);
        assertEquals(0, summary.errors());
        assertTrue(tsFacts.stream().noneMatch(f -> f.category().equals("ROUTE_CANDIDATE")),
                "Equality comparison containing '+' inside string literal must not concatenate as route");
        assertTrue(tsFacts.stream().noneMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")),
                "Equality comparison containing '+' inside string literal must not concatenate as URL");
        assertTrue(tsFacts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("'orders.topic'") && "CONSUMER".equals(f.attributes().get("role"))));

        // Malformed fetch() with missing/broken arguments must never throw NPE in clientMethod
        var brokenFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        assertDoesNotThrow(() -> frontend.parse("javascript", "fetch(`/broken".getBytes(StandardCharsets.UTF_8),
                brokenFacts::add, () -> false));
    }

    @Test
    void composesGoGroupExpressUseAndKotlinRequestMappingPrefixesAndGuardsConsumerRegex() throws Exception {
        var frontend = new TreeSitterSourceFrontend();

        // Go nested router.Group prefixes
        String goSrc = """
                package api
                func register(r *gin.Engine) {
                    v1 := r.Group("/api/v1")
                    users := v1.Group("/users")
                    users.GET("/:id", getUser)
                    users.POST("", createUser)
                }
                """;
        var goFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("go", goSrc.getBytes(StandardCharsets.UTF_8), goFacts::add, () -> false);
        assertTrue(goFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/api/v1/users/:id\"") && "GET".equals(f.attributes().get("method"))),
                () -> "Go nested Group GET missing: " + goFacts);
        assertTrue(goFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/api/v1/users\"") && "POST".equals(f.attributes().get("method"))),
                () -> "Go nested Group empty-path POST missing: " + goFacts);

        // Express app.use('/v1', router) prefix composition + non-Kafka Consumer guard
        String expressSrc = """
                const router = express.Router();
                app.use('/v1', router);
                router.get('/orders', listOrders);
                const c: Consumer<string> = createConsumer();
                c.on('click', handler);
                """;
        var expressFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("typescript", expressSrc.getBytes(StandardCharsets.UTF_8), expressFacts::add, () -> false);
        assertTrue(expressFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")
                && f.name().equals("\"/v1/orders\"") && "GET".equals(f.attributes().get("method"))),
                () -> "Express app.use prefix missing: " + expressFacts);
        assertTrue(expressFacts.stream().noneMatch(f -> "KAFKA".equals(f.attributes().get("protocol"))),
                () -> "Generic Consumer<string> must not be misclassified as KAFKA: " + expressFacts);

        // Bounded parser pool close + clean reuse + cancellation check inside file scope
        TreeSitterSourceFrontend.closePooledParsers();
        assertThrows(java.io.IOException.class, () ->
                frontend.parse("typescript", expressSrc.getBytes(StandardCharsets.UTF_8), f -> {}, () -> true));
        var afterCancelFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var summary = frontend.parse("typescript", "app.get('/ok', h);".getBytes(StandardCharsets.UTF_8),
                afterCancelFacts::add, () -> false);
        assertEquals(0, summary.errors());
        assertTrue(afterCancelFacts.stream().anyMatch(f -> f.category().equals("ROUTE_CANDIDATE")));
    }

    @Test
    void splitFileGoUnimplementedEmbedDottedPythonHttpClientAndGoVarSpecResolve() throws Exception {
        var frontend = new TreeSitterSourceFrontend();

        // F6a: Go struct embedding Unimplemented*Server without Register* in same file
        String goGrpc = """
                package main
                type productCatalog struct {
                    pb.UnimplementedProductCatalogServiceServer
                }
                func (p *productCatalog) ListProducts(ctx context.Context, req *pb.Empty) (*pb.ListProductsResponse, error) {
                    return nil, nil
                }
                """;
        var grpcFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("go", goGrpc.getBytes(StandardCharsets.UTF_8), grpcFacts::add, () -> false);
        var grpcHandlers = grpcFacts.stream().filter(f -> f.category().equals("RPC_HANDLER_CANDIDATE")).toList();
        assertEquals(1, grpcHandlers.size(), () -> "Split-file Go Unimplemented embed missing: " + grpcFacts);
        assertEquals("ProductCatalogService", grpcHandlers.getFirst().attributes().get("service"));
        assertTrue(goGrpc.substring(grpcHandlers.getFirst().span().start(), grpcHandlers.getFirst().span().end())
                .contains("func (p *productCatalog) ListProducts"));

        // F6b: Python dotted client receiver (e.g. Locust self.client / l.client)
        String pyLocust = """
                def browse(l):
                    l.client.get("/")
                class User:
                    def checkout(self):
                        self.client.post("/cart")
                """;
        var pyFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("python", pyLocust.getBytes(StandardCharsets.UTF_8), pyFacts::add, () -> false);
        var pyCalls = pyFacts.stream().filter(f -> f.category().equals("HTTP_CALL_CANDIDATE")).toList();
        assertEquals(2, pyCalls.size(), () -> "Dotted Python HTTP client calls missing: " + pyFacts);
        assertEquals(Set.of("GET", "POST"),
                pyCalls.stream().map(f -> f.attributes().get("method")).collect(java.util.stream.Collectors.toSet()));
        assertTrue(pyFacts.stream().noneMatch(f -> f.category().equals("ROUTE_CANDIDATE")));

        // F6b: Go var_spec + Gorilla Mux string concatenation
        String goVar = """
                package main
                var baseUrl = ""
                func routes(r *mux.Router) {
                    basePrefix := "/v1"
                    r.HandleFunc(baseUrl + "/cart", h).Methods("POST")
                    r.HandleFunc(basePrefix + "/items", h)
                }
                """;
        var goVarFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        frontend.parse("go", goVar.getBytes(StandardCharsets.UTF_8), goVarFacts::add, () -> false);
        var routes = goVarFacts.stream().filter(f -> f.category().equals("ROUTE_CANDIDATE")).map(TreeSitterSourceFrontend.Fact::name).toList();
        assertTrue(routes.contains("\"/cart\""), () -> "Missing Go var_spec concat route /cart in " + routes);
        assertTrue(routes.contains("\"/v1/items\""), () -> "Missing Go short_var concat route /v1/items in " + routes);
    }
}
