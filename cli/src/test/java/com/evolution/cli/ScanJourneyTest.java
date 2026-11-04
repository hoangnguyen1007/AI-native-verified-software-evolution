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
}
