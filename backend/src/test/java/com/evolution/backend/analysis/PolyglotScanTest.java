package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.RepositorySnapshot;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.RepositoryInputs;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PolyglotScanTest {
    @TempDir Path directory;
    @Test void grpcClientsJoinProtobufContractsAndServerHandlersThroughDiskPublication()throws Exception {
        Path source=Files.createDirectory(directory.resolve("rpc-source"));
        Files.writeString(source.resolve("cart.proto"),"syntax=\"proto3\"; package shop; message Req {} message Reply {} service CartService { rpc GetCart(Req) returns (Reply); } service AdService { rpc GetAds(Req) returns (Reply); }");
        Files.writeString(source.resolve("client.go"),"package main\nfunc f(){ pb.NewCartServiceClient(conn).GetCart(ctx, req); pb.NewAdServiceClient(conn).GetAds(ctx, req); ordinary.GetCart(req) }");
        Files.writeString(source.resolve("JavaServer.java"),"class Ads extends shop.AdServiceGrpc.AdServiceImplBase { @Override public void getAds(Req r, StreamObserver<Reply> out) {} }");
        Files.writeString(source.resolve("server.py"),"import demo_pb2_grpc\nclass Cart(demo_pb2_grpc.CartServiceServicer):\n    def GetCart(self, request, context):\n        return Reply()\n");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:grpc-scan");
        try(var intake=new RepositoryIntake(directory.resolve("rpc-intake"))) {
            intake.localComplete(source,"rpc",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("rpc",ReadinessPlanner.Policy.comprehensive(),()->false);
            var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());var scan=new PolyglotScan();
            var result=scan.run("rpc",intake,readiness,EvidenceContext.forSnapshot(snapshot.identity()),Optional.empty(),directory.resolve("rpc-work"),()->false);
            var input=new CanonicalGraphInput(new RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),result.gaps());
            var pages=new PartitionedGraphStore(directory.resolve("rpc-pages"));var snapshots=directory.resolve("rpc-snapshots");
            var published=pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false));
            var graph=FileGraphStore.readSnapshot(snapshots.resolve(published.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            assertEquals(2,graph.edges().stream().filter(e->e.kind().equals("polyglot.grpc-service-call.candidate")).count());
            assertEquals(2,graph.edges().stream().filter(e->e.kind().equals("INVOKES_RPC")).count());
            assertEquals(2,graph.edges().stream().filter(e->e.kind().equals("polyglot.grpc-contract.candidate")).count());
            assertEquals(2,graph.edges().stream().filter(e->e.kind().equals("polyglot.grpc-implements.candidate")).count());
            assertEquals(4,published.gapReasons().get("GRPC_ROUTING_EVIDENCE_MISSING"));
            assertEquals(graph.nodes().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).map(KnowledgeGraph.Node::identity).distinct().count(),published.gaps());
            assertEquals(graph.nodes().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).count(),published.gapNodes());
            assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("GRPC_ROUTING_EVIDENCE_MISSING")));
            assertTrue(graph.evidence().stream().filter(e->e.family().equals("polyglot.grpc-source-match")).allMatch(e->e.canonicalJson().contains("\"runtimeObserved\":false")));
            assertTrue(graph.evidence().stream().filter(e->e.family().equals("polyglot.source-syntax")&&e.canonicalJson().contains("\"rowType\":\"FACT\"")).allMatch(e->e.canonicalJson().contains("source.tree-sitter:m8.4-rpc-local-inheritance-v5")||e.canonicalJson().contains("source.javaparser-grpc:m8.4-v1")),"Capture uses the actual frontend version, including incremental SDK changes");
            assertTrue(new PartitionedGraphStore(directory.resolve("rpc-pages")).partition(published.graph(),snapshot.identity().value()).isPresent());
            assertEquals(published.graph(),pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false)).graph());
        }
    }
    @Test void ordinaryScanPublishesUriCandidateLinksToGoAndFrontendRoutesWithTypedUnknowns()throws Exception {
        Path source=Files.createDirectory(directory.resolve("http-source"));
        Files.writeString(source.resolve("client.ts"),"fetch('/pets/42'); axios.get('/cart'); fetch('/pets/42', {method:'POST'}); fetch('/api/pets/42');");
        Files.writeString(source.resolve("server.js"),"app.get('/pets/:id', handler);");
        Files.writeString(source.resolve("main.go"),"package main\nfunc register() { http.HandleFunc(\"/cart\", handler) }");
        Files.writeString(source.resolve("client.py"),"import requests\nrequests.get('/pets/42')\n");
        Files.writeString(source.resolve("client.go"),"package main\nfunc request() { http.Get(\"/pets/42\") }");
        Files.writeString(source.resolve("Client.cs"),"class Client { void F(){ client.GetAsync(\"/pets/42\"); } }");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:http-scan");
        try(var intake=new RepositoryIntake(directory.resolve("http-intake"))) {
            intake.local(source,"http",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("http",ReadinessPlanner.Policy.comprehensive(),()->false);
            var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());var scan=new PolyglotScan();
            var result=scan.run("http",intake,readiness,EvidenceContext.forSnapshot(snapshot.identity()),Optional.empty(),directory.resolve("http-work"),()->false);
            var input=new CanonicalGraphInput(new RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),result.gaps());
            var pages=new PartitionedGraphStore(directory.resolve("http-pages"));var snapshots=directory.resolve("http-snapshots");
            var published=pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false));
            var graph=FileGraphStore.readSnapshot(snapshots.resolve(published.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            assertEquals(5,graph.edges().stream().filter(e->e.kind().equals("polyglot.service-call.candidate")).count());
            assertEquals(5,graph.edges().stream().filter(e->e.kind().equals("CALLS_SERVICE")).count());
            assertTrue(graph.edges().stream().noneMatch(e->e.kind().equals("polyglot.service-call")));
            assertEquals(7,graph.evidence().stream().filter(e->e.family().equals("polyglot.http-uri-match")).count());
            assertEquals(2,graph.evidence().stream().filter(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("HTTP_URI_NO_CANDIDATE")).count());
            assertEquals(published.graph(),pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false)).graph());
        }
    }
    @Test void protobufDeclarationsAndRpcReachTheCanonicalGraphWithoutSyntheticStubs()throws Exception {
        Path source=Files.createDirectory(directory.resolve("proto-source"));
        Files.writeString(source.resolve("shop.proto"),"syntax=\"proto3\";package shop; message Request {string id=1;} message Reply {} service Store {rpc Lookup(Request) returns(Reply);rpc Remote(.external.Request) returns(Reply);}");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:proto-scan");
        try(var intake=new RepositoryIntake(directory.resolve("proto-intake"))) {
            intake.localComplete(source,"proto",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("proto",ReadinessPlanner.Policy.comprehensive(),()->false);
            var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());var scan=new PolyglotScan();
            var result=scan.run("proto",intake,readiness,EvidenceContext.forSnapshot(snapshot.identity()),Optional.empty(),directory.resolve("proto-work"),()->false);
            assertEquals(1,result.syntax().parsed());assertEquals(1,result.languages().get("protobuf"));
            assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("PROTOBUF_GENERATOR_REQUIRED")));
            var input=new CanonicalGraphInput(new RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),result.gaps());
            var snapshots=directory.resolve("proto-snapshots");var pages=new PartitionedGraphStore(directory.resolve("proto-pages"));
            var publication=pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false));
            var graph=FileGraphStore.readSnapshot(snapshots.resolve(publication.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            assertEquals(2,graph.nodes().stream().filter(n->n.kind().equals("PROTOBUF_MESSAGE")).count());
            assertEquals(1,graph.nodes().stream().filter(n->n.kind().equals("PROTOBUF_SERVICE")).count());
            assertEquals(2,graph.nodes().stream().filter(n->n.kind().equals("PROTOBUF_RPC_METHOD")).count());
            assertTrue(graph.edges().stream().anyMatch(e->e.kind().equals("syntax.rpc-method")));
            var references=graph.edges().stream().filter(e->e.kind().startsWith("protobuf.rpc-")&&e.kind().endsWith(".candidate")).toList();
            assertEquals(4,references.size());assertEquals(1,references.stream().filter(e->e.target().state()==KnowledgeGraph.TargetState.UNRESOLVED).count());
            assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("PROTOBUF_SCHEMA_TYPE_UNRESOLVED")));
            assertTrue(graph.edges().stream().noneMatch(e->e.kind().equals("java.calls")));
            assertEquals(publication.graph(),pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false)).graph());
        }
    }
    @Test void anUnavailableOptionalGrammarKeepsItsOutcomeWithoutDuplicatingTheNativeObligation()throws Exception {
        Path source=Files.createDirectory(directory.resolve("groovy-source"));Files.writeString(source.resolve("App.groovy"),"class App { def run() { work() } }");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:groovy-obligation");
        try(var intake=new RepositoryIntake(directory.resolve("groovy-intake"))) {
            var captured=intake.localComplete(source,"groovy",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("groovy",ReadinessPlanner.Policy.comprehensive(),()->false);
            var result=new PolyglotScan().run("groovy",intake,readiness,EvidenceContext.forSnapshot(SnapshotIdentity.from(repo,captured.manifest().orElseThrow().inventory())),Optional.empty(),directory.resolve("work"),()->false);
            assertEquals(Map.of("groovy",1L),result.uncovered());assertEquals(1,result.gaps().size());assertEquals("NATIVE_CONTEXT_REQUIRED",result.gaps().getFirst().reasonCode());
            assertEquals(1,result.syntax().failures().get("groovy:SYNTAX_GRAMMAR_UNAVAILABLE"));
            try(var rows=result.tables().open(result.syntax().records(),()->false)){assertEquals("SYNTAX_GRAMMAR_UNAVAILABLE",rows.next().fields().get("status"));assertNull(rows.next());}
        }
    }
    @Test void allNativeLanguagesRemainInInventoryAndGraphWithoutInventingSemanticCoverage()throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));
        for(var file:Map.of("go/main.go","package main\nfunc main() {}", "python/app.py","print('fixture')", "dotnet/App.cs","class App {}", "node/app.js","function run() {}", "java/App.java","class App {}").entrySet()) {
            Path path=source.resolve(file.getKey());Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());
        }
        Files.writeString(source.resolve("go/go.mod"),"module fixture\ngo 1.23\n");Files.writeString(source.resolve("python/pyproject.toml"),"[project]\nname='fixture'\nversion='1'\n");
        Files.writeString(source.resolve("dotnet/App.csproj"),"<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");
        Files.writeString(source.resolve("node/package.json"),"{\"name\":\"fixture\",\"version\":\"1.0.0\"}");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:scan-polyglot");
        try(var intake=new RepositoryIntake(directory.resolve("intake"))) {
            var captured=intake.local(source,"revision",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("revision",ReadinessPlanner.Policy.comprehensive(),()->false);
            var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());var context=EvidenceContext.forSnapshot(snapshot.identity());
            var scan=new PolyglotScan();var result=scan.run("revision",intake,readiness,context,Optional.empty(),directory.resolve("work"),()->false);
            assertEquals(Map.of("go",1L,"python",1L,"csharp",1L,"javascript",1L,"java",1L),result.languages());
            assertEquals(4,result.gaps().size());assertEquals(0,result.nativeObservationRows());
            assertEquals(4,result.syntax().parsed());assertEquals(1,result.syntax().categories().get("python:CALL"));assertTrue(result.syntax().failures().isEmpty());
            assertEquals(captured.manifest().orElseThrow().entries(),result.inventory().entries().rows());
            var input=new CanonicalGraphInput(new RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),result.gaps());
            var published=new PartitionedGraphStore(directory.resolve("pages")).build(input,directory.resolve("snapshots"),()->false,store->scan.project(result,store,()->false));
            var graph=FileGraphStore.readSnapshot(directory.resolve("snapshots/"+published.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            assertEquals(result.inventory().entries().rows(),graph.nodes().stream().filter(n->n.kind().equals("INVENTORY_ENTRY")).count());
            assertEquals(readiness.modules().size(),graph.nodes().stream().filter(n->n.kind().equals("BUILD_COMPONENT")).count());
            assertEquals(1,graph.nodes().stream().filter(n->n.kind().equals("CALL_SITE")).count());
            assertTrue(graph.edges().stream().anyMatch(e->e.kind().equals("syntax.call")));
            assertTrue(graph.edges().stream().noneMatch(e->e.kind().equals("java.calls")));
            assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("polyglot.inventory-entry")&&e.canonicalJson().contains("python")));
            assertEquals(published.graph(),new PartitionedGraphStore(directory.resolve("pages")).build(input,directory.resolve("snapshots"),()->false,store->scan.project(result,store,()->false)).graph());
            var fallbackContext=EvidenceContext.forSnapshot(SnapshotIdentity.from(repo,result.inventory().inventory()));
            var fallback=scan.run("revision",intake,readiness,fallbackContext,Optional.empty(),directory.resolve("work"),()->false);
            var raw=new PartitionedGraphStore(directory.resolve("raw-pages")).build(fallbackContext.snapshotIdentity(),directory.resolve("raw-snapshots"),()->false,store->scan.projectInventoryRoot(fallback,store,()->false));
            var partial=FileGraphStore.readSnapshot(directory.resolve("raw-snapshots/"+raw.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            assertEquals(result.inventory().entries().rows(),partial.nodes().stream().filter(n->n.kind().equals("INVENTORY_ENTRY")).count());
            assertTrue(partial.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("JAVA_COMPATIBILITY_WORKER_REQUIRED")));
            assertTrue(partial.edges().stream().anyMatch(e->e.kind().equals("syntax.call")));assertTrue(partial.contexts().stream().allMatch(c->c.analysis().isEmpty()));
        }
    }
    @Test void capturedProviderRowsAreAttachedWithSourceBindingAndProducerTrust()throws Exception {
        Path source=Files.createDirectory(directory.resolve("source"));Files.writeString(source.resolve("main.go"),"package main\nfunc main() {}\n");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:native-scan-protocol");var policy=ContentDigest.sha256Utf8("operator policy");
        try(var intake=new RepositoryIntake(directory.resolve("intake"))) {
            intake.local(source,"revision",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("revision",ReadinessPlanner.Policy.comprehensive(),()->false);
            var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());var context=EvidenceContext.forSnapshot(snapshot.identity());
            var content=new StreamingContentStore(directory.resolve("native/cas"));var tables=new SemanticTables(content,65536);var inventory=new PolyglotInventory().scan("revision",intake.inventory(),intake.content(),tables,()->false);
            var empty=tables.write(SemanticTables.Kind.DOCUMENT,Collections.emptyIterator(),()->false);var census=tables.write(SemanticTables.Kind.CENSUS,Collections.emptyIterator(),()->false);
            var resolution=content.put(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)),()->false);
            var prototype=new LanguageSemanticRequest(context,inventory.inventory(),"root","main","main","default","go","1.23",empty,census,resolution,new VersionedIdentifier("authored.protocol","1"),ContentDigest.sha256Utf8("tool"),policy,ContentDigest.sha256Utf8("work"));
            var bytes=Files.readAllBytes(source.resolve("main.go"));var captured=content.put(new ByteArrayInputStream(bytes),()->false);
            var documents=tables.write(SemanticTables.Kind.DOCUMENT,List.of(new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,"main.go",prototype.scope(),Map.of("content",captured))).iterator(),()->false);
            var questions=new ArrayList<SemanticTables.Row>();var facts=new ArrayList<SemanticTables.Row>();
            for(String category:LanguageProfile.requiredCategories()) {
                var question=new SemanticTables.Row(SemanticTables.Kind.CENSUS,category,prototype.scope(),Map.of("category",category,"document","main.go"));questions.add(question);
                var fields=new TreeMap<String,Object>();fields.put("obligation",question.identity().value());fields.put("status",Set.of("A01","A04","A06","A11","A12").contains(category)?"RESOLVED":"NOT_APPLICABLE");fields.put("targets",List.of());
                if(category.equals("A06")){
                    String target=ContentDigest.sha256Utf8("captured native callee signature").value();fields.put("targets",List.of(target));fields.put("caller",ContentDigest.sha256Utf8("captured native caller signature").value());
                    fields.put("symbols",List.of(Map.of("id",target,"signature","func work()","name","work","kind","FUNCTION","origin","PROJECT")));
                }
                if(category.equals("A04")){fields.put("targets",List.of(ContentDigest.sha256Utf8("native type").value()));fields.put("symbols",List.of(Map.of("name","incomplete optional symbol")));fields.put("caller","invalid optional caller");}
                facts.add(new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,category,prototype.scope(),fields));
            }
            var request=new LanguageSemanticRequest(context,inventory.inventory(),prototype.root(),prototype.module(),prototype.sourceSet(),prototype.variant(),prototype.language(),prototype.dialect(),documents,tables.write(SemanticTables.Kind.CENSUS,questions.iterator(),()->false),resolution,prototype.provider(),prototype.tool(),policy,prototype.workPolicy());
            var observations=tables.write(SemanticTables.Kind.OBSERVATION,facts.iterator(),()->false);var coordinator=new SemanticContextCoordinator(content,tables);
            var plan=coordinator.plan(context,inventory,List.of(new SemanticContextCoordinator.Selection("native",request)).iterator(),policy,()->false);
            var root=coordinator.run(plan,r->Optional.of((input,cancel)->observations),r->Map.of(),Map.of(),Set.of(),()->policy,c->{},()->false);
            new SemanticPublicationStore(directory.resolve("native/publications"),content,tables).commit(plan,root,Optional.empty(),()->policy,()->false);
            var loaded=PolyglotScan.load(directory.resolve("native"),plan.identity(),policy,Optional.empty(),()->false);
            var result=new PolyglotScan().run("revision",intake,readiness,context,Optional.of(loaded),directory.resolve("work"),()->false);
            assertEquals(1,result.nativeContexts());assertEquals(1,result.nativeDocuments());assertEquals(12,result.nativeObservationRows());assertEquals(Map.of(),result.uncovered());
            assertEquals("PRODUCER_ASSERTION",result.summary().get("trust"));assertTrue(result.gaps().stream().anyMatch(g->g.reasonCode().equals("NATIVE_PUBLICATION_PARTIAL")));
            var input=new CanonicalGraphInput(new RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),result.gaps());
            var published=new PartitionedGraphStore(directory.resolve("pages")).build(input,directory.resolve("snapshots"),()->false,store->new PolyglotScan().project(result,store,()->false));
            assertEquals(2,published.contexts().size());assertTrue(published.nodes()>=12);
            var graph=FileGraphStore.readSnapshot(directory.resolve("snapshots/"+published.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            var binding=graph.edges().stream().filter(e->e.kind().equals("polyglot.asserted.calls")).findFirst().orElseThrow();
            assertEquals(KnowledgeGraph.TargetState.RESOLVED,binding.target().state());
            assertEquals(List.of(ContentDigest.sha256Utf8("captured native callee signature").value()),binding.target().identities());
            assertTrue(graph.edges().stream().noneMatch(e->e.kind().equals("polyglot.native.calls")));
            assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("polyglot.native-binding")&&e.canonicalJson().contains("\"runtimeObserved\":false")&&e.canonicalJson().contains("PRODUCER_ASSERTION")));
            for(String reason:List.of("NATIVE_SYMBOL_METADATA_UNAVAILABLE","NATIVE_CALLER_METADATA_UNAVAILABLE"))assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains(reason)),reason);
            assertThrows(IOException.class,()->PolyglotScan.load(directory.resolve("native"),plan.identity(),ContentDigest.sha256Utf8("revoked"),Optional.empty(),()->false));
            Files.writeString(source.resolve("main.go"),"package different\n");
            intake.local(source,"changed",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var changed=intake.readiness("changed",ReadinessPlanner.Policy.comprehensive(),()->false);
            assertThrows(IOException.class,()->new PolyglotScan().run("changed",intake,changed,context,Optional.of(loaded),directory.resolve("work"),()->false));
        }
    }
    @Test void passiveIntraRepositoryLinkerConnectsPythonTypeScriptAndGoDeclarations()throws Exception {
        Path source=Files.createDirectory(directory.resolve("polyglot-links"));
        Files.createDirectories(source.resolve("python/app"));
        Files.writeString(source.resolve("python/app/models.py"),"class BaseOrder:\n    pass\n\ndef calculate_tax(amount):\n    return amount\n");
        Files.writeString(source.resolve("python/app/service.py"),"from app.models import BaseOrder, calculate_tax\nclass OrderService(BaseOrder):\n    def process(self, total):\n        return calculate_tax(total)\n");
        Files.createDirectories(source.resolve("web/src"));
        Files.writeString(source.resolve("web/src/pricing.ts"),"export class BaseWidget {}\nexport function formatCurrency(val: number) { return String(val); }\n");
        Files.writeString(source.resolve("web/src/CartView.tsx"),"import { BaseWidget, formatCurrency } from './pricing';\nexport class CartView extends BaseWidget {\n    render() { return formatCurrency(100); }\n}\n");
        Files.createDirectories(source.resolve("go/cart"));
        Files.writeString(source.resolve("go/cart/pricing.go"),"package cart\nfunc ComputeDiscount(v int) int { return v }\n");
        Files.writeString(source.resolve("go/cart/handler.go"),"package cart\nfunc HandleCheckout() int { return ComputeDiscount(42) }\n");
        var repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:polyglot-static-links");
        try(var intake=new RepositoryIntake(directory.resolve("links-intake"))) {
            intake.localComplete(source,"links",new StreamingAcquisitionRequest(repo,Optional.empty(),false,List.of()),()->false);
            var readiness=intake.readiness("links",ReadinessPlanner.Policy.comprehensive(),()->false);
            var snapshot=RepositorySnapshot.create(repo,Optional.empty(),false,List.of(),List.of());var scan=new PolyglotScan();
            var result=scan.run("links",intake,readiness,EvidenceContext.forSnapshot(snapshot.identity()),Optional.empty(),directory.resolve("links-work"),()->false);
            var input=new CanonicalGraphInput(new RepositoryInputs(snapshot,Map.of()),Optional.empty(),Optional.empty(),List.of(),List.of(),result.gaps());
            var pages=new PartitionedGraphStore(directory.resolve("links-pages"));var snapshots=directory.resolve("links-snapshots");
            var published=pages.build(input,snapshots,()->false,store->scan.project(result,store,()->false));
            var graph=FileGraphStore.readSnapshot(snapshots.resolve(published.graph().value().substring(7)+".graph.json"),64_000_000,new GraphJson.StreamLimits(64_000_000,128,64_000_000));
            assertTrue(graph.edges().stream().filter(e->e.kind().equals("syntax.imports-declaration")).count()>=2);
            assertTrue(graph.edges().stream().filter(e->e.kind().equals("syntax.calls-declaration")).count()>=3);
            assertTrue(graph.edges().stream().filter(e->e.kind().equals("syntax.inherits-declaration")).count()>=2);
            assertTrue(graph.evidence().stream().filter(e->e.family().equals("polyglot.static-symbol-link")).allMatch(e->e.canonicalJson().contains("\"trust\":\"STATIC_SYNTAX_DERIVED\"")&&e.canonicalJson().contains("\"runtimeObserved\":false")));
        }
    }
}
