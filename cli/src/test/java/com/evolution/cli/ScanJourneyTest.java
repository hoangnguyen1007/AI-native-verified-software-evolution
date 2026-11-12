package com.evolution.cli;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import com.evolution.analysis.graph.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class ScanJourneyTest {
    @TempDir Path workspace;
    @Test void dependencyPreparationVerifiesRealCachedBinariesWithoutCreatingAGraphOrLoadingAJdk()throws Exception {
        Path source=Files.createDirectory(workspace.resolve("prepare-input")),cache=Files.createDirectory(workspace.resolve("prepare-cache"));
        Files.writeString(source.resolve("build.gradle"),"plugins { id 'java' }\ndependencies { implementation 'fixture:library:1' }\n");
        Path artifact=Files.createDirectories(cache.resolve("fixture/library/1"));
        Files.writeString(artifact.resolve("library-1.pom"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>library</artifactId><version>1</version></project>");
        try(var jar=new java.util.jar.JarOutputStream(Files.newOutputStream(artifact.resolve("library-1.jar")))){jar.putNextEntry(new java.util.jar.JarEntry("META-INF/fixture"));jar.write(new byte[]{1});jar.closeEntry();}
        var ready=run("scan",source.toString(),"--prepare-dependencies","--offline","--maven-cache",cache.toString(),"--jdk",workspace.resolve("absent-jdk").toString(),"--json");
        assertEquals(0,ready.code,ready.err);assertEquals("PREPARED",ready.data().get("status"));assertEquals(false,ready.data().get("graphCreated"));assertEquals("NOT_EVALUATED",ready.data().get("semanticCoverage"));
        assertTrue(((Number)GraphJson.object(GraphJson.object(ready.data().get("dependencies")).get("gradle")).get("binaryInputs")).intValue()>0);
        assertEquals(1,((Number)GraphJson.object(GraphJson.object(ready.data().get("dependencies")).get("gradle")).get("uniqueBinaryArtifacts")).intValue());
        assertEquals(false,GraphJson.object(ready.data().get("execution")).get("isolatedBuildExecuted"));assertFalse(Files.exists(workspace.resolve(".evo/selection.json")));
        var missing=run("scan",source.toString(),"--prepare-dependencies","--offline","--maven-cache",Files.createDirectory(workspace.resolve("empty-prepare-cache")).toString(),"--json");
        assertEquals(3,missing.code,missing.err+missing.out);assertEquals("PARTIAL",missing.data().get("status"));
        assertTrue(GraphJson.object(missing.data().get("problemsByReason")).containsKey("ARTIFACT_NOT_FOUND"));
        assertEquals(2,run("scan",source.toString(),"--prepare-dependencies","--build","required","--json").code);
    }
    @Test void receiptCountsUniquePublishedGapsIncludingRpcProjection()throws Exception {
        Path source=Files.createDirectory(workspace.resolve("rpc-input"));
        Files.writeString(source.resolve("cart.proto"),"syntax=\"proto3\"; package shop; message Req {} message Reply {} service Cart { rpc Get(Req) returns (Reply); }");
        Files.writeString(source.resolve("client.go"),"package main\nfunc f(){ pb.NewCartClient(conn).Get(ctx, req) }");
        Files.writeString(source.resolve("server.py"),"import demo_pb2_grpc\nclass C(demo_pb2_grpc.CartServicer):\n    def Get(self,r,c):\n        return Reply()\n");
        var result=run("scan",source.toString(),"--offline","--build","off","--json");assertEquals(3,result.code,result.err+result.out);
        assertEquals("PARTIAL",result.data().get("status"));
        assertEquals("PARTIAL_RECOVERY",result.data().get("evidenceTier"));
        var graph=new Workspace(workspace).selectedGraph();var summary=GraphJson.object(result.data().get("gapSummary"));
        assertEquals(graph.nodes().stream().filter(n->n.kind().equals("CAPABILITY_GAP")).map(KnowledgeGraph.Node::identity).distinct().count(),((Number)summary.get("total")).longValue());
        assertTrue(((Number)summary.get("inputTotal")).longValue()<((Number)summary.get("total")).longValue());
        assertEquals(2,((Number)GraphJson.object(summary.get("byReason")).get("GRPC_ROUTING_EVIDENCE_MISSING")).longValue());
    }
    @Test void gradleScanUsesThePassiveRecoveryCompositionWithoutLaunchingAWorker()throws Exception {
        Path source=Files.createDirectory(workspace.resolve("gradle-input"));Files.createDirectories(source.resolve("src/main/java"));
        Files.writeString(source.resolve("build.gradle"),"plugins { id 'java' }\njava { toolchain { languageVersion = JavaLanguageVersion.of(21) } }\n");
        Files.writeString(source.resolve("src/main/java/GradleClient.java"),"class GradleClient { int size(String s) { return s.length(); } }");
        var result=run("scan",source.toString(),"--offline","--build","off","--json");assertEquals(3,result.code,result.err+result.out);
        var dependencies=GraphJson.object(result.data().get("dependencies"));var gradle=GraphJson.object(dependencies.get("gradle"));
        assertEquals("PASSIVE_RECOVERY",gradle.get("trust"));assertEquals(false,gradle.get("effectiveGradleContext"));
        assertEquals(false,GraphJson.object(result.data().get("execution")).get("isolatedBuildExecuted"));
        assertTrue(new Workspace(workspace).selectedGraph().nodes().stream().anyMatch(n->n.kind().equals("TYPE")));
    }
    @Test void aJavaWorkerBudgetStillPublishesEveryInventoryEntryAndOtherLanguageCalls()throws Exception {
        Path source=Files.createDirectory(workspace.resolve("large-source"));byte[] block=new byte[8192];Arrays.fill(block,(byte)'a');
        try(var output=Files.newOutputStream(source.resolve("Huge.java"))){output.write("//".getBytes(java.nio.charset.StandardCharsets.UTF_8));for(int i=0;i<1222;i++)output.write(block);}
        Files.writeString(source.resolve("small.py"),"print('fallback')\n");
        var result=run("scan",source.toString(),"--offline","--build","off","--json");assertEquals(3,result.code,result.err+result.out);
        assertEquals("PARTIAL",result.data().get("status"));assertEquals("PARTIAL_RECOVERY",result.data().get("evidenceTier"));
        assertEquals("COMPATIBILITY_WORKER_REQUIRED",result.data().get("javaAnalysis"));assertEquals(Boolean.TRUE,result.data().get("graphCreated"));
        var graph=new Workspace(workspace).selectedGraph();assertEquals(2,graph.nodes().stream().filter(n->n.kind().equals("INVENTORY_ENTRY")).count());
        assertEquals(1,graph.nodes().stream().filter(n->n.kind().equals("CALL_SITE")).count());
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("JAVA_COMPATIBILITY_WORKER_REQUIRED")));
        assertTrue(graph.contexts().stream().allMatch(c->c.analysis().isEmpty()));
    }
    @Test void purePolyglotCompletionKeepsSyntaxInventoryAndDoesNotPublishAJavaHealthScore() throws Exception {
        Path source = Files.createDirectory(workspace.resolve("python-source"));
        Files.writeString(source.resolve("logic.py"), "def answer():\n    return 42\n\nprint(answer())\n");
        var scan = run("scan", source.toString(), "--offline", "--build", "off", "--json");
        assertEquals(0, scan.code, scan.err);
        assertEquals("COMPLETE", scan.data().get("status"));
        assertEquals("PASSIVE_ARCHITECTURE_RECOVERY", scan.data().get("evidenceTier"));
        var graph = new Workspace(workspace).selectedGraph();
        assertEquals(1, graph.nodes().stream().filter(node -> node.kind().equals("INVENTORY_ENTRY")).count());
        assertTrue(graph.nodes().stream().anyMatch(node -> node.kind().equals("SOURCE_DECLARATION")));
        assertTrue(graph.contexts().stream().allMatch(context -> context.analysis().isEmpty()));
        var metric = run("metrics", "metric.fan-out", "--json");
        var row = GraphJson.object(GraphJson.array(metric.data().get("items")).getFirst());
        assertEquals("NOT_APPLICABLE", row.get("status")); assertNull(row.get("value"));
        Path policy = Path.of("src/test/resources/fixtures/tiny/policy.yaml").toAbsolutePath();
        var score = run("score", "--policy", policy.toString(), "--json");
        assertEquals(3, score.code, score.err);
        assertEquals("WITHHELD", score.data().get("status")); assertNull(score.data().get("score"));
    }
    @Test void declaredMavenReleaseUsesMatchingSymbolsInsteadOfTheCliFallback()throws Exception {
        Path source=Files.createDirectory(workspace.resolve("release17"));Files.createDirectories(source.resolve("src/main/java/p"));
        Files.writeString(source.resolve("src/main/java/p/App.java"),"package p; class App { String text() { return \" x \".strip(); } }");
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>release17</artifactId><version>1</version><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties></project>");
        var scan=run("scan",source.toString(),"--offline","--build","off","--maven-cache",Files.createDirectory(workspace.resolve("cache17")).toString(),"--json");
        assertTrue(scan.code==0||scan.code==3,scan.err+scan.out);
        var graph=new Workspace(workspace).selectedGraph();var manifests=graph.evidence().stream().filter(e->e.family().equals("analysis.manifest")).map(e->GraphJson.object(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults()))).toList();
        assertFalse(manifests.isEmpty());assertTrue(manifests.stream().allMatch(m->"17".equals(GraphJson.object(GraphJson.object(m.get("configuration")).get("values")).get("java.release"))));
        assertTrue(((Number)GraphJson.object(GraphJson.object(scan.data().get("semanticDepth")).get("relationships")).get("resolved")).longValue()>0);
        assertFalse(graph.evidence().stream().filter(e->e.family().equals("evidence.gap")).anyMatch(e->e.canonicalJson().contains("REFERENCED_EVIDENCE_UNAVAILABLE")),"Platform acquisition attempts must accompany their ledger");
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("evidence.attempt")&&e.canonicalJson().contains(com.evolution.analysis.maven.MavenBuildModelProvider.VERSION.id())),"Available Maven attempt provenance must reach the graph too");
    }
    @Test void scanAccountsForEveryLanguageAndKeepsLocalCallsWhenAnUnrelatedDependencyIsMissing()throws Exception {
        Path source=Files.createDirectory(workspace.resolve("polyglot"));Files.createDirectories(source.resolve("src/main/java/p"));
        Files.writeString(source.resolve("src/main/java/p/App.java"),"package p; class App { void local() {} void run() { local(); } }");
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>polyglot</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties><dependencies><dependency><groupId>unavailable.fixture</groupId><artifactId>missing</artifactId><version>1</version></dependency></dependencies></project>");
        for(var file:Map.of("go/main.go","package main\nfunc main() { run() }\nfunc run() {}", "python/app.py","def run(): print('hello')", "dotnet/App.cs","class App { void Run() { Work(); } void Work() {} }", "node/app.js","function run() { work(); } function work() {} app.get('/health',run);").entrySet()) {Path path=source.resolve(file.getKey());Files.createDirectories(path.getParent());Files.writeString(path,file.getValue());}
        var result=run("scan",source.toString(),"--offline","--build","off","--maven-cache",Files.createDirectory(workspace.resolve("cache")).toString(),"--json");
        assertEquals(3,result.code,result.err+result.out);
        var nativeSummary=GraphJson.object(result.data().get("polyglot"));assertEquals(Set.of("java","go","python","csharp","javascript"),GraphJson.object(nativeSummary.get("filesByLanguage")).keySet());
        assertTrue(((Number)GraphJson.object(GraphJson.object(result.data().get("semanticDepth")).get("relationships")).get("resolved")).longValue()>0);
        var graph=new Workspace(workspace).selectedGraph();assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("polyglot.inventory-entry")&&e.canonicalJson().contains("python")));
        assertEquals(4,graph.evidence().stream().filter(e->e.family().equals("evidence.gap")&&e.canonicalJson().contains("NATIVE_CONTEXT_REQUIRED")).count());
        assertTrue(graph.nodes().stream().filter(n->n.kind().equals("CALL_SITE")).count()>=5);
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("ROUTE_CANDIDATE")));
        assertTrue(graph.evidence().stream().filter(e->e.family().equals("polyglot.source-syntax")).anyMatch(e->e.canonicalJson().contains("NOT_ESTABLISHED")));
        assertFalse(graph.evidence().stream().filter(e->e.family().equals("evidence.gap")).anyMatch(e->e.canonicalJson().contains("REFERENCED_EVIDENCE_UNAVAILABLE")),"Failed Maven acquisition attempts must remain available too");
        var pages=run("graph-pages","partitions","--json");assertEquals(0,pages.code,pages.err);
    }
    record Run(int code,String out,String err){Map<String,Object> data(){return GraphJson.object(GraphJson.object(GraphJson.read(out.strip(),GraphJson.Limits.defaults())).get("data"));}}
    Run run(String... args){var out=new StringWriter();var err=new StringWriter();int code=new EvoApp(workspace,new StringReader(""),new PrintWriter(out),new PrintWriter(err),false,Map.of()).execute(args);return new Run(code,out.toString(),err.toString());}
    @Test void graphPageBuildMigratesALegacySelectionWithoutChangingIt()throws Exception{
        var fixture=new CliFixture().node("a").node("b").edge("a","b");var graph=fixture.graph();var root=Files.createDirectories(workspace.resolve(".evo"));
        Files.writeString(root.resolve(graph.identity().value().substring(7)+".graph.json"),new GraphSnapshotCodec().encode(graph));
        String selection=com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("graph",graph.identity(),"context",fixture.context));Files.writeString(root.resolve("selection.json"),selection);
        var indexed=run("graph-pages","build","--json");assertEquals(0,indexed.code,indexed.err);assertEquals("INDEXED",GraphJson.object(indexed.data().get("page")).get("status"));
        assertEquals(selection,Files.readString(root.resolve("selection.json")));assertEquals(0,run("graph-pages","nodes","--partition",fixture.context,"--json").code);
    }
    @Test void graphPagesSurviveFreshCliInstancesAndReturnBothAdjacencyDirections()throws Exception{
        var fixture=new CliFixture().node("a").node("b").node("c").edge("a","b").edge("c","b");
        var graph=fixture.graph();new Workspace(workspace).select(graph,Optional.of(fixture.context));
        var partitions=run("graph-pages","partitions","--limit","1","--json");assertEquals(0,partitions.code,partitions.err);
        assertEquals("RAW_CONTEXT_FACTS",partitions.data().get("scope"));var ref=GraphJson.object(GraphJson.array(GraphJson.object(partitions.data().get("page")).get("items")).getFirst());
        String context=GraphJson.string(ref.get("context"));var nodes=run("graph-pages","nodes","--partition",context,"--limit","1","--json");assertEquals(0,nodes.code,nodes.err);
        var page=GraphJson.object(nodes.data().get("page"));assertEquals(1,GraphJson.array(page.get("items")).size());
        var next=run("graph-pages","nodes","--partition",context,"--limit","1","--cursor",GraphJson.string(page.get("continuation")),"--json");assertEquals(0,next.code,next.err);
        assertNotEquals(page.get("items"),GraphJson.object(next.data().get("page")).get("items"));
        var adjacency=run("graph-pages","adjacency",CliFixture.id("b"),"--partition",context,"--incoming","--json");assertEquals(0,adjacency.code,adjacency.err);
        assertEquals(2,GraphJson.array(GraphJson.object(adjacency.data().get("page")).get("items")).size());
        assertEquals(2,run("graph-pages","adjacency",CliFixture.id("b"),"--partition",context,"--limit","1","--cursor",GraphJson.string(page.get("continuation")),"--json").code);
        assertEquals(2,run("graph-pages","partitions","--incoming","--json").code);
    }
    @Test void capturedScanRejectsContextOverridesAndTargetOwnedAuthority()throws Exception{
        var source=Files.createDirectory(workspace.resolve("capture-input"));Files.createDirectories(source.resolve("src/main/java"));Files.writeString(source.resolve("src/main/java/App.java"),"class App {}");
        assertEquals(2,run("scan",source.toString(),"--java-capture",source.toString(),"--release","21","--offline","--json").code);
        var owned=run("scan",source.toString(),"--java-capture",source.toString(),"--offline","--json");assertEquals(2,owned.code,owned.err);assertTrue(owned.err.contains("CAPTURE_REGISTRATION_INSIDE_TARGET")||owned.out.contains("CAPTURE_REGISTRATION_INSIDE_TARGET"));
    }
    @Test void missingClasspathReportsSurfaceOnlyAndUnevaluatedSpringRatherThanZeroArchitecture()throws Exception{
        var source=Files.createDirectory(workspace.resolve("partial"));
        Files.createDirectories(source.resolve("src/main/java/p"));
        Files.writeString(source.resolve("src/main/java/p/C.java"),"package p; class C { void run() { missing(); } }");
        Files.writeString(source.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>partial</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>unavailable.fixture</groupId><artifactId>missing</artifactId><version>1</version></dependency></dependencies></project>
                """);
        var scan=run("scan",source.toString(),"--offline","--maven-cache",Files.createDirectory(workspace.resolve("cache")).toString(),"--json");
        assertEquals(3,scan.code,scan.err+scan.out);
        assertNotNull(scan.data().get("semanticDepth"),"Scan must distinguish inventory from semantic relationships");
        var depth=GraphJson.object(scan.data().get("semanticDepth"));
        assertEquals("SURFACE_ONLY",depth.get("level"));
        assertEquals(1L,((Number)depth.get("sourceRecoveryContexts")).longValue());
        var relations=GraphJson.object(depth.get("relationships"));
        assertTrue(((Number)relations.get("attempted")).longValue()>0);
        assertEquals(0L,((Number)relations.get("resolved")).longValue());
        assertEquals("NOT_EVALUATED",GraphJson.object(depth.get("spring")).get("status"));
        assertEquals("PASSIVE",GraphJson.object(scan.data().get("execution")).get("mode"));
    }
    @Test void actualSourceToStoredGraphPolicyScoreEvidenceAndSarif()throws Exception{
        Path source=Path.of("src/test/resources/fixtures/tiny").toAbsolutePath();
        var scan=run("scan",source.toString(),"--repo","https://example.test/tiny.git","--json");assertTrue(scan.code==0||scan.code==3,scan.err);assertTrue(scan.data().containsKey("graph"),scan.out);
        var depth=GraphJson.object(scan.data().get("semanticDepth"));
        assertTrue(((Number)GraphJson.object(depth.get("relationships")).get("resolved")).longValue()>0,"Independently authored calls must contribute attributed relationships");
        assertNotEquals("SURFACE_ONLY",depth.get("level"));
        var graph=new Workspace(workspace).selectedGraph();assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("TYPE")),scan.out);
        assertEquals(com.evolution.analysis.contract.serialization.CanonicalJson.write(depth),com.evolution.analysis.contract.serialization.CanonicalJson.write(com.evolution.backend.analysis.SemanticDepth.summarize(graph)),"Stored evidence and live-input semantic denominators must agree");
        var gapSummary=GraphJson.object(scan.data().get("gapSummary"));long gapTotal=((Number)gapSummary.get("total")).longValue();assertEquals(gapTotal,GraphJson.object(gapSummary.get("byReason")).values().stream().mapToLong(v->((Number)v).longValue()).sum());assertFalse(scan.data().containsKey("gaps"));
        assertTrue(graph.evidence().stream().filter(e->e.family().equals("evidence.gap")).count()>=gapTotal,"Every receipt gap must remain a typed canonical graph record");
        var again=run("scan",source.toString(),"--repo","https://example.test/tiny.git","--json");assertEquals(scan.out,again.out);assertEquals(scan.code,again.code);
        var find=run("find","Controller","--json");assertTrue(find.code==0||find.code==3,find.err);assertFalse(GraphJson.array(find.data().get("items")).isEmpty());
        var repository=run("find","data.Repository","--json");String repositoryId=GraphJson.string(GraphJson.object(GraphJson.array(repository.data().get("items")).getFirst()).get("identity"));
        assertEquals(2,run("impact","data.Repository","--json").code,"An open search denominator cannot prove unique-name resolution");
        var impact=run("impact",repositoryId,"--json");assertTrue(impact.code==0||impact.code==3,impact.err);assertFalse(GraphJson.array(impact.data().get("items")).isEmpty());
        var check=run("check",source.resolve("policy.yaml").toString(),"--json");assertTrue(check.code==0||check.code==3,check.err);assertFalse(GraphJson.array(check.data().get("items")).isEmpty());
        assertEquals(3,run("check","--fail-on","error","--json").code,"Incomplete policy evidence cannot establish a complete CI verdict");
        var findings=run("findings","--json");assertFalse(GraphJson.array(findings.data().get("items")).isEmpty(),findings.out);
        var finding=GraphJson.object(GraphJson.array(findings.data().get("items")).getFirst());String bundle=GraphJson.string(finding.get("bundleIdentity"));
        var evidence=run("witness",bundle,"--json");assertTrue(evidence.data().containsKey("sources"));assertTrue(evidence.data().containsKey("request"));assertTrue(evidence.data().containsKey("region"));
        var replay=run("replay",bundle,"--json");assertTrue(replay.code==0||replay.code==3,replay.err);
        var replayRequest=workspace.resolve("model-replay.json");Files.writeString(replayRequest,com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("arguments",Map.of("identity",finding.get("identity"),"counterWitness",false))));
        var modelReplay=run("query","replay","--request",replayRequest.toString(),"--json");assertTrue(modelReplay.code==0||modelReplay.code==3,modelReplay.err+modelReplay.out);
        var replayResult=GraphJson.object(GraphJson.array(modelReplay.data().get("items")).getFirst());assertEquals(true,replayResult.get("matched"));assertEquals(false,replayResult.get("semanticReplayed"));
        var score=run("score","--json");assertEquals(3,score.code,score.out);assertEquals("WITHHELD",score.data().get("status"));assertNull(score.data().get("score"));
        var sarif=run("export","sarif");assertTrue(sarif.code==0||sarif.code==3,sarif.err);var tree=GraphJson.object(GraphJson.read(sarif.out.strip(),GraphJson.Limits.defaults()));assertEquals("2.1.0",tree.get("version"));
        var report=GraphJson.object(GraphJson.array(tree.get("runs")).getFirst());assertFalse(GraphJson.array(report.get("results")).isEmpty());assertTrue(GraphJson.object(report.get("properties")).containsKey("canonicalEvaluation"));
    }
    @Test void cliFallbackCannotOverrideDeclaredMavenEncoding()throws Exception{
        Path source=Files.createDirectory(workspace.resolve("maven-input"));Files.createDirectories(source.resolve("src/main/java/p"));Files.writeString(source.resolve("src/main/java/p/C.java"),"package p; class C {}");
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding></properties></project>");
        var prior=new CliFixture().node("Prior").graph();new Workspace(workspace).select(prior,Optional.of(prior.contexts().getFirst().identity()));
        var scan=run("scan",source.toString(),"--offline","--json");assertEquals(3,scan.code,scan.err);
        String id=GraphJson.string(scan.data().get("graph"));var graph=Workspace.graph(new Workspace(workspace).root.resolve(id.substring(7)+".graph.json"));
        assertEquals(graph.identity(),new Workspace(workspace).selectedGraph().identity(),"Usable recovered declarations remain publishable as partial evidence");
        assertNotEquals(prior.identity(),graph.identity());
        assertTrue(graph.evidence().stream().filter(e->e.family().equals("build.module")).anyMatch(e->e.canonicalJson().contains("ISO-8859-1")),"The declared charset must retain its authority");
        assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("TYPE")),"Written declarations remain in the recovery denominator");
        var depth=GraphJson.object(scan.data().get("semanticDepth"));
        assertEquals(1L,((Number)depth.get("sourceRecoveryContexts")).longValue());
        assertEquals(0L,((Number)GraphJson.object(depth.get("relationships")).get("resolved")).longValue(),"Mismatched decoding cannot become exact attribution");
    }
    @Test void passiveScanCompletesWithPassiveArchitectureRecoveryTierUnderApprovedNinetyFivePercentPolicy()throws Exception{
        Path source=Files.createDirectory(workspace.resolve("arch-evidenced"));
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("src/main/java/demo/OwnerController.java"),"""
                package demo;
                @interface Marker {}
                class Base {}
                @Marker public class OwnerController extends Base {
                    public String list() { return "ok"; }
                }
                """);
        Files.writeString(source.resolve("build.gradle"),"plugins { id 'java' }\n");
        var scan=run("scan",source.toString(),"--offline","--build","off","--json");
        assertEquals(0,scan.code,scan.err+scan.out);
        assertEquals("COMPLETE",scan.data().get("status"));
        assertEquals("PASSIVE_ARCHITECTURE_RECOVERY",scan.data().get("evidenceTier"));
        var gapSummary=GraphJson.object(scan.data().get("gapSummary"));
        assertTrue(((Number)gapSummary.get("total")).longValue()>0,"Passive capability gaps must remain preserved");
        assertTrue(GraphJson.object(gapSummary.get("byObligation")).containsKey("ISOLATED_EXECUTION_REQUIRED"));
    }
    @Test void unprovedImportedStructureCannotEarnPassiveCompletion()throws Exception{
        Path source=Files.createDirectory(workspace.resolve("arch-complete"));
        Files.createDirectories(source.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("src/main/java/demo/OwnerController.java"),"""
                package demo;
                import jakarta.persistence.*;
                import org.springframework.web.bind.annotation.*;
                @Entity
                @RestController
                @RequestMapping("/owners")
                public class OwnerController extends hipstershop.AdServiceGrpc.AdServiceImplBase {
                    @GetMapping("/list")
                    public String list() { return "ok"; }
                }
                """);
        Files.writeString(source.resolve("build.gradle"),"plugins { id 'java' }\n");
        var scan=run("scan",source.toString(),"--offline","--build","off","--json");
        assertEquals(3,scan.code,scan.err+scan.out);
        assertEquals("PARTIAL",scan.data().get("status"));
        assertEquals("PARTIAL_RECOVERY",scan.data().get("evidenceTier"));
        var gapSummary=GraphJson.object(scan.data().get("gapSummary"));
        assertTrue(((Number)gapSummary.get("total")).longValue()>0,"Passive capability gaps must remain preserved");
        assertTrue(GraphJson.object(gapSummary.get("byObligation")).containsKey("ISOLATED_EXECUTION_REQUIRED"));
    }
}
