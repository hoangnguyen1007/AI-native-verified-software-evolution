package com.evolution.cli;

import java.io.*;
import java.nio.file.Path;
import java.util.Map;
import java.util.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.query.*;
import com.evolution.analysis.query.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EvoAppTest {
    @Test void paletteNextUsesExactCursorAndStopsAtLastPage()throws Exception{
        var p=graph(new CliFixture().node("A").node("B").node("C"));var out=new StringWriter();var err=new StringWriter();
        String script="find -n 1\nnext\nevo next\nnext\nquit\n";
        assertEquals(0,new EvoApp(directory,new StringReader(script),new PrintWriter(out),new PrintWriter(err),true,Map.of("NO_COLOR","1")).execute("--graph",p.toString(),"--scope","entity"));
        assertEquals(3,out.toString().split("FIND",-1).length-1);assertTrue(err.toString().contains("NEXT_UNAVAILABLE"));assertFalse(err.toString().contains("INVALID_USAGE"));
        for(String name:List.of("A","B","C"))assertTrue(out.toString().contains(CliFixture.id(name).substring(CliFixture.id(name).length()-64,CliFixture.id(name).length()-52)));
        assertTrue(out.toString().contains("next"));assertFalse(out.toString().contains("--cursor c2hh"));
    }
    @Test void paletteContextChangeInvalidatesContinuation()throws Exception{
        var p=graph(new CliFixture().node("A").node("B"));var out=new StringWriter();var err=new StringWriter();
        new EvoApp(directory,new StringReader("find -n 1\nuse 1\nnext\nquit\n"),new PrintWriter(out),new PrintWriter(err),true,Map.of("NO_COLOR","1")).execute("--graph",p.toString(),"--scope","entity");
        assertTrue(err.toString().contains("NEXT_UNAVAILABLE"));assertEquals(1,out.toString().split("FIND",-1).length-1);
    }
    @Test void paletteNextAcceptsRequestFileAndRejectsQueryOverrides()throws Exception{
        var f=new CliFixture().node("A").node("B");var p=graph(f);var file=directory.resolve("page-request.json");
        Files.writeString(file,CanonicalJson.write(Map.of("request",f.request(),"arguments",Map.of("search",""),"page",new ArchitectureQuery.Page(1,Optional.empty()))));
        var out=new StringWriter();var err=new StringWriter();String query="query entities --request \""+file+"\"\n";
        new EvoApp(directory,new StringReader(query+"next --json\n"+query+"next --scope module\nquit\n"),new PrintWriter(out),new PrintWriter(err),true,Map.of("NO_COLOR","1")).execute("--graph",p.toString(),"--scope","entity");
        assertTrue(out.toString().contains("\"resultIdentity\""),err.toString());assertTrue(out.toString().contains("\"exactTotal\":2"),err.toString());assertTrue(err.toString().contains("CONFLICTING_REQUEST"));
    }
    @Test void paletteNextDoesNotRereadChangedRequestFiles()throws Exception{
        var f=new CliFixture().node("A").node("B");var p=graph(f);var file=directory.resolve("mutable-request.json");
        Files.writeString(file,CanonicalJson.write(Map.of("request",f.request(),"arguments",Map.of("search",""),"page",new ArchitectureQuery.Page(1,Optional.empty()))));
        String query="query entities --request \""+file+"\"\n";var out=new StringWriter();var err=new StringWriter();
        var reader=new StringReader(query+"next --json\nquit\n"){
            int count;
            @Override public int read(char[] buffer,int offset,int length)throws java.io.IOException{
                if(count==query.length())Files.writeString(file,"{}");int read=super.read(buffer,offset,Math.min(1,length));if(read>0)count+=read;return read;
            }
        };
        new EvoApp(directory,reader,new PrintWriter(out),new PrintWriter(err),true,Map.of("NO_COLOR","1")).execute("--graph",p.toString());
        assertEquals("{}",Files.readString(file));assertEquals("",err.toString());assertTrue(out.toString().contains("\"exactTotal\":2"));
    }
    @TempDir Path directory;
    record Run(int code,String out,String err){Map<String,Object> tree(){return GraphJson.object(GraphJson.read(out.strip(),GraphJson.Limits.defaults()));}Map<String,Object> data(){return GraphJson.object(tree().get("data"));}}
    Run run(String... args){var out=new StringWriter();var err=new StringWriter();int code=new EvoApp(directory,new StringReader(""),new PrintWriter(out),new PrintWriter(err),false,Map.of()).execute(args);return new Run(code,out.toString(),err.toString());}
    Path graph(CliFixture fixture)throws Exception{var path=directory.resolve("snapshot.json");Files.writeString(path,new GraphSnapshotCodec().encode(fixture.graph()));return path;}
    @Test void nonTerminalEntryShowsUsefulHelpWithoutReadingStdinOrCreatingWorkspace() {
        var output=new StringWriter(); var errors=new StringWriter();
        var input=new Reader(){public int read(char[] b,int o,int l){fail("Nonterminal entry must not read stdin");return -1;}public void close(){}};
        int code=new EvoApp(directory,input,new PrintWriter(output),new PrintWriter(errors),false,Map.of()).execute();
        assertEquals(0,code); assertTrue(output.toString().contains("scan")); assertTrue(output.toString().contains("impact"));
        assertFalse(output.toString().contains("\u001b")); assertEquals("",errors.toString());
        assertFalse(java.nio.file.Files.exists(directory.resolve(".evo")));
    }
    @Test void canonicalJsonMatchesApiByteForByte()throws Exception{
        var fixture=new CliFixture().node("A").node("B").edge("A","B");var path=graph(fixture);
        var store=new InMemoryGraphStore();store.save(fixture.graph());
        byte[] request=CanonicalJson.write(Map.of("request",fixture.request(),"arguments",Map.of())).getBytes(StandardCharsets.UTF_8);
        var expected=new ArchitectureQueryApi(store).handle("POST","/api/v1/graphs/"+fixture.graph().identity().value()+"/queries/summary","application/json",request,()->false);
        var actual=run("show","--graph",path.toString(),"--scope","entity","--json");
        assertEquals(0,actual.code);assertEquals(new String(expected.body(),StandardCharsets.UTF_8),actual.out.strip());assertEquals("",actual.err);
    }
    @Test void paginationPreservesExactTotalAndCursorBinding()throws Exception{
        var f=new CliFixture().node("A").node("B").node("C");var p=graph(f);
        var first=run("find","--graph",p.toString(),"--scope","entity","-n","1","--json");
        assertEquals("3",first.data().get("exactTotal").toString());assertEquals(1,GraphJson.array(first.data().get("items")).size());
        String cursor=GraphJson.string(first.data().get("continuation"));
        var next=run("find","--graph",p.toString(),"--scope","entity","-n","1","--cursor",cursor,"--json");
        assertNotEquals(first.data().get("items"),next.data().get("items"));
        assertEquals(2,run("find","changed-filter","--graph",p.toString(),"--scope","entity","-n","1","--cursor",cursor,"--json").code);
    }
    @Test void humanFindNumbersResolveExactRowsAcrossProcessesAndRejectStaleGraph()throws Exception{
        var f=new CliFixture().node("A").node("B").edge("A","B");var p=graph(f);
        assertEquals(0,run("find","--graph",p.toString(),"--scope","entity","--color","never").code);
        var expected=run("deps",f.graph().nodes().stream().map(KnowledgeGraph.Node::identity).sorted().toList().getFirst(),"--graph",p.toString(),"--scope","entity","--json");
        var numbered=run("deps","1","--graph",p.toString(),"--scope","entity","--json");
        assertEquals(expected.code,numbered.code,numbered.err);assertEquals(expected.out,numbered.out);
        p=graph(new CliFixture().node("C"));
        assertEquals(2,run("deps","1","--graph",p.toString(),"--scope","entity","--json").code);
    }
    @Test void impactUsesIncomingAndRejectsInfeasibleCrossWorldPaths()throws Exception{
        var f=new CliFixture().node("A").node("B").node("C").conditional("A","B","dev").conditional("B","C","prod");var p=graph(f);
        var result=run("impact",CliFixture.id("C"),"--graph",p.toString(),"--scope","entity","--json");
        assertEquals(0,result.code);String items=CanonicalJson.write(result.data().get("items"));assertTrue(items.contains(CliFixture.id("B")));assertFalse(items.contains(CliFixture.id("A")));
        assertEquals(List.of(),run("paths",CliFixture.id("A"),CliFixture.id("C"),"--graph",p.toString(),"--scope","entity","--json").data().get("items"));
        var realized=run("impact",CliFixture.id("C"),"--graph",p.toString(),"--scope","entity","--view","realized","--world",CliFixture.id("dev"),"--json");
        assertEquals(List.of(),realized.data().get("items"));
    }
    @Test void genericRequestReachesEveryPublishedQueryOperation()throws Exception{
        var f=new CliFixture().node("A").node("B").edge("A","B");var p=graph(f);
        for(String op:ArchitectureQueryApi.OPERATIONS){
            Map<String,Object> args=switch(op){case "entity","dependencies","expand","impact","reachability","explain","children"->Map.of("identity",CliFixture.id("A"));case "evidence"->Map.of("reference",f.base.identity());case "paths","shortest-path"->Map.of("source",CliFixture.id("A"),"target",CliFixture.id("B"));case "spring"->Map.of("kinds",List.of("ENDPOINT"));case "artifacts"->Map.of("families",List.of("fixture.context"));case "metric"->Map.of("metric","metric.inventory","subject",f.context);case "metric-definition"->Map.of("metric","metric.inventory");case "comparison-preparation"->Map.of("graph",f.graph().identity(),"context",f.context);default->Map.of();};
            var request=new TreeMap<String,Object>(GraphJson.object(GraphJson.read(CanonicalJson.write(f.request()),GraphJson.Limits.defaults())));
            if(op.equals("impact"))request.put("direction","INCOMING");
            var file=directory.resolve("request.json");Files.writeString(file,CanonicalJson.write(Map.of("request",request,"arguments",args)));
            var result=run("query",op,"--graph",p.toString(),"--request",file.toString(),"--json");
            assertTrue(result.code==0||result.code==3,op+": "+result.err+result.out);assertTrue(result.tree().containsKey("data"),op);
        }
    }
    @Test void selectedSnapshotSurvivesNewProcessWithoutNetworkOrServer()throws Exception{
        var f=new CliFixture().node("A");var p=graph(f);assertEquals(0,run("open",p.toString(),"--json").code);
        assertEquals(f.graph().identity().value(),GraphJson.string(new Workspace(directory).selection().get("graph")));
        assertEquals(0,run("show","--scope","entity","--json").code);
        assertEquals(0,run("use","1","--json").code);
    }
    @Test void invalidInputsAreSanitizedAndNeverProduceSuccessJson()throws Exception{
        var f=new CliFixture().node("A");var p=graph(f);
        for(String[] args:List.of(new String[]{"--lang","invalid","show"},new String[]{"show","--limit","51"},new String[]{"paths","only-one"},new String[]{"show","--view","realized"},new String[]{"query","unknown-operation"})){
            var all=new ArrayList<>(List.of(args));all.addAll(List.of("--graph",p.toString(),"--json"));var result=run(all.toArray(String[]::new));assertEquals(2,result.code,Arrays.toString(args));assertFalse(result.out.contains("\"data\""));
        }
        var result=run("--json","--password=PRIVATE_SENTINEL");assertEquals(2,result.code);assertFalse((result.out+result.err).contains("PRIVATE_SENTINEL"));
    }
    @Test void interactiveMenuAcceptsNumbersLanguageSwitchAndLiteralPaths()throws Exception{
        var f=new CliFixture().node("A");Path p=directory.resolve("snapshot with spaces.json");Files.writeString(p,new GraphSnapshotCodec().encode(f.graph()));
        String script="open \""+p+"\"\nlang vi\n2\nquit\n";var output=new StringWriter();var errors=new StringWriter();
        int code=new EvoApp(directory,new StringReader(script),new PrintWriter(output),new PrintWriter(errors),true,Map.of("NO_COLOR","1")).execute();
        assertEquals(0,code);assertTrue(output.toString().contains("Bắt đầu"));assertTrue(output.toString().contains("SHOW"));assertFalse(output.toString().contains("\u001b"));assertEquals("",errors.toString());
    }
    @Test void paletteAcceptsShellStyleEvoPrefixAndKeepsSessionAlive()throws Exception{
        var p=graph(new CliFixture().node("A"));var output=new StringWriter();var errors=new StringWriter();
        assertEquals(0,new EvoApp(directory,new StringReader("evo show\nevo find A\nevo quit\n"),new PrintWriter(output),new PrintWriter(errors),true,Map.of("NO_COLOR","1")).execute("--graph",p.toString(),"--scope","entity"));
        assertEquals("",errors.toString());assertTrue(output.toString().contains("SHOW"));assertTrue(output.toString().contains("FIND"));
    }
    @Test void exportJsonAndGraphmlRetainCanonicalDataAndRefuseOverwrite()throws Exception{
        var f=new CliFixture().node("A").node("B").edge("A","B");var p=graph(f);var dest=directory.resolve("export.json");
        var written=run("export","json","--graph",p.toString(),"--scope","entity","-o",dest.toString(),"--json");assertEquals(0,written.code);
        assertEquals(run("show","--graph",p.toString(),"--scope","entity","--json").out.strip(),Files.readString(dest));
        assertEquals(1,run("export","json","--graph",p.toString(),"--scope","entity","-o",dest.toString(),"--json").code);
        var xml=run("export","graphml","--graph",p.toString(),"--scope","entity");assertEquals(0,xml.code);
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var doc=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.out.getBytes(StandardCharsets.UTF_8)));
        assertEquals(2,doc.getElementsByTagNameNS("http://graphml.graphdrawing.org/xmlns","node").getLength());assertEquals(1,doc.getElementsByTagNameNS("http://graphml.graphdrawing.org/xmlns","edge").getLength());
        var metadata=doc.getElementsByTagNameNS("http://graphml.graphdrawing.org/xmlns","data").item(0).getTextContent();assertTrue(metadata.contains("resultIdentity"));assertTrue(metadata.contains("bounds"));
    }
    @Test void requestFileCannotSilentlyOverrideExplicitQueryFlags()throws Exception{
        var f=new CliFixture().node("A");var p=graph(f);var request=directory.resolve("request.json");Files.writeString(request,CanonicalJson.write(Map.of("request",f.request())));
        var result=run("show","--graph",p.toString(),"--request",request.toString(),"--depth","1","--json");assertEquals(2,result.code);assertTrue(result.err.contains("CONFLICTING_REQUEST"));
    }
    @Test void missingSnapshotAndPolicyHaveActionableBilingualErrors()throws Exception{
        var missing=run("show","--lang","vi");assertEquals(2,missing.code);assertTrue(missing.err.contains("evo scan"));assertTrue(missing.err.contains("Chưa"));
        var p=graph(new CliFixture().node("A"));var policy=run("score","--graph",p.toString(),"--json");assertEquals(2,policy.code);assertTrue(policy.err.contains("POLICY_REQUIRED"));
    }
    @Test void jsonDisablesColorEvenWhenExplicitlyForced()throws Exception{
        var p=graph(new CliFixture().node("A"));var result=run("show","--graph",p.toString(),"--scope","entity","--color","always","--json");assertEquals(0,result.code);assertFalse(result.out.contains("\u001b"));assertTrue(result.tree().containsKey("data"));
        assertTrue(run("--json").data().containsKey("commands"));
        assertEquals(EvoApp.VERSION,run("--json","--version").data().get("version"));
    }
    @Test void multiContextSnapshotRequiresAnExplicitSelectionAndHonorsRequestFile()throws Exception{
        var f=new CliFixture().node("A");String second=CliFixture.id("context2");
        var graph=new KnowledgeGraph(f.snapshot,List.of(new KnowledgeGraph.Context(f.context,f.snapshot,Optional.empty(),f.base.identity()),new KnowledgeGraph.Context(second,f.snapshot,Optional.empty(),f.base.identity())),f.nodes,f.edges,f.evidence);
        Path path=directory.resolve("multi.json");Files.writeString(path,new GraphSnapshotCodec().encode(graph));
        assertEquals(0,run("open",path.toString(),"--json").code);assertEquals(2,run("find","--json").code);assertTrue(run("show","--json").data().containsKey("contexts"));
        var request=directory.resolve("request.json");var r=new ArchitectureQuery.Request(graph.identity(),f.context,f.request().scope(),f.request().projection(),f.request().direction(),f.request().relationshipKinds(),f.request().population(),false,Optional.empty(),f.request().limits());Files.writeString(request,CanonicalJson.write(Map.of("request",r)));
        assertEquals(0,run("show","--request",request.toString(),"--json").code);
        assertEquals(0,run("use","2","--json").code);assertEquals(0,run("show","--scope","entity","--json").code);
    }
    @Test void shortIdentityResolvesOnlyWhenUniquelyObserved()throws Exception{
        var f=new CliFixture().node("A").node("B").edge("A","B");var p=graph(f);String id=CliFixture.id("B"),prefix=id.substring(id.lastIndexOf(':')+1,id.lastIndexOf(':')+13);
        var result=run("impact",prefix,"--graph",p.toString(),"--scope","entity","--json");assertEquals(0,result.code,prefix+" "+CliFixture.id("B")+" "+result.err+result.out);
        assertEquals(2,run("impact","sha256","--graph",p.toString(),"--scope","entity","--json").code);
    }
    @Test void completionsComeFromActualCommandAndOptionModel(){var ps=run("completion","powershell");assertEquals(0,ps.code);assertTrue(ps.out.contains("Register-ArgumentCompleter"));assertTrue(ps.out.contains("'impact'"));assertTrue(ps.out.contains("'--world'"));assertFalse(ps.out.contains("\u001b"));var bash=run("completion","bash");assertEquals(0,bash.code);assertTrue(bash.out.contains("complete"));assertEquals(2,run("completion","unsupported").code);assertEquals(2,run("completion","powershell","--json").code);}
    @Test void interactiveDefaultsAndExplicitOverridesSurviveErrors()throws Exception{
        var p=graph(new CliFixture().node("A"));var out=new StringWriter();var err=new StringWriter();
        String script="nonsense\nshow\n"+"x".repeat(9000)+"\nshow --lang vi\nquit\n";
        assertEquals(0,new EvoApp(directory,new StringReader(script),new PrintWriter(out),new PrintWriter(err),true,Map.of("NO_COLOR","1")).execute("--graph",p.toString(),"--scope","entity"));
        assertTrue(out.toString().contains("SHOW"));assertTrue(out.toString().contains("Quan sát danh mục"));assertTrue(err.toString().contains("INVALID_USAGE"));assertTrue(err.toString().contains("INPUT_LIMIT"));
    }
    @Test void visualGraphProjectionAndCytoscapeExport()throws Exception{
        var f=new CliFixture().node("A").node("B").edge("A","B");var p=graph(f);
        var visualJson=run("visual","--graph",p.toString(),"--scope","entity","--json");
        assertEquals(0,visualJson.code);
        assertTrue(visualJson.data().containsKey("nodes"));
        assertTrue(visualJson.data().containsKey("edges"));

        var visualText=run("visual","--graph",p.toString(),"--scope","entity");
        assertEquals(0,visualText.code);
        assertTrue(visualText.out.contains("Visual workbench") || visualText.out.contains("Phép chiếu"));

        var cyOut=run("export","cytoscape","--graph",p.toString(),"--scope","entity");
        assertEquals(0,cyOut.code);
        var cy=GraphJson.object(GraphJson.read(cyOut.out.strip(),GraphJson.Limits.defaults()));
        assertEquals("cytoscape.js",cy.get("format"));
        assertTrue(cy.containsKey("elements"));
        var elements=GraphJson.object(cy.get("elements"));
        assertTrue(elements.containsKey("nodes"));
        assertTrue(elements.containsKey("edges"));
    }
}
