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
    @Test void actualSourceToStoredGraphPolicyScoreEvidenceAndSarif()throws Exception{
        Path source=Path.of("src/test/resources/fixtures/tiny").toAbsolutePath();
        var scan=run("scan",source.toString(),"--repo","https://example.test/tiny.git","--json");assertTrue(scan.code==0||scan.code==3,scan.err);assertTrue(scan.data().containsKey("graph"),scan.out);
        var graph=new Workspace(workspace).selectedGraph();assertTrue(graph.nodes().stream().anyMatch(n->n.kind().equals("TYPE")),scan.out);
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
        var score=run("score","--json");assertEquals(3,score.code,score.out);assertEquals("WITHHELD",score.data().get("status"));assertNull(score.data().get("score"));
        var sarif=run("export","sarif");assertTrue(sarif.code==0||sarif.code==3,sarif.err);var tree=GraphJson.object(GraphJson.read(sarif.out.strip(),GraphJson.Limits.defaults()));assertEquals("2.1.0",tree.get("version"));
        var report=GraphJson.object(GraphJson.array(tree.get("runs")).getFirst());assertFalse(GraphJson.array(report.get("results")).isEmpty());assertTrue(GraphJson.object(report.get("properties")).containsKey("canonicalEvaluation"));
    }
    @Test void cliFallbackCannotOverrideDeclaredMavenEncoding()throws Exception{
        Path source=Files.createDirectory(workspace.resolve("maven-input"));Files.createDirectories(source.resolve("src/main/java/p"));Files.writeString(source.resolve("src/main/java/p/C.java"),"package p; class C {}");
        Files.writeString(source.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>ISO-8859-1</project.build.sourceEncoding></properties></project>");
        var prior=new CliFixture().node("Prior").graph();new Workspace(workspace).select(prior,Optional.of(prior.contexts().getFirst().identity()));
        var scan=run("scan",source.toString(),"--json");assertEquals(1,scan.code,scan.err);
        assertEquals(prior.identity(),new Workspace(workspace).selectedGraph().identity(),"A failed scan must not silently replace a usable selection");
        String id=GraphJson.string(scan.data().get("graph"));var graph=Workspace.graph(new Workspace(workspace).root.resolve(id.substring(7)+".graph.json"));
        assertTrue(graph.evidence().stream().anyMatch(e->e.canonicalJson().contains("ISO-8859-1")));assertTrue(graph.nodes().stream().noneMatch(n->n.kind().equals("TYPE")),"Mismatched decoding must withhold source semantics, not replace the build-declared charset");
    }
}
