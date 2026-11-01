package com.evolution.analysis.execution.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit immutable guest capture replay. This class never starts a VM or native tool. */
class ArchitectureNativeCaptureIT {
    @TempDir Path temporary;
    Map<String,Object> object(Path path)throws IOException {try(var input=Files.newBufferedReader(path,StandardCharsets.UTF_8)){return GraphJson.object(GraphJson.read(input,new GraphJson.Limits(64*1024*1024,64,64*1024*1024)));}}
    ContentHandle handle(Object value){var row=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),((BigDecimal)row.get("size")).longValueExact());}
    ContentHandle put(StreamingContentStore store,Path path)throws IOException {try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){return store.put(input,()->false);}}
    static byte[] sourceBytes(Map<String,Object> source){
        if(source.get("text") instanceof String text&&!source.containsKey("data"))return text.getBytes(StandardCharsets.UTF_8);
        if(source.get("data") instanceof String encoded&&!source.containsKey("text")){byte[] bytes=Base64.getDecoder().decode(encoded);if(!Base64.getEncoder().encodeToString(bytes).equals(encoded))throw new IllegalArgumentException("NON_CANONICAL_BINARY_SOURCE");return bytes;}
        throw new IllegalArgumentException("SOURCE_ENCODING_REQUIRED");
    }
    @Test void binarySourceReplayPreservesExactWireBytesInsteadOfTextDecoding(){
        byte[] bytes={(byte)255,0,10};assertArrayEquals(bytes,sourceBytes(Map.of("data",Base64.getEncoder().encodeToString(bytes))));
        assertThrows(IllegalArgumentException.class,()->sourceBytes(Map.of("data","Cg")));
        assertThrows(IllegalArgumentException.class,()->sourceBytes(Map.of("data","Cg==","text","x")));
    }
    static boolean forbiddenLabelMatches(Map<String,Object> fact,Map<String,Object> label){
        return label.get("forbiddenKind").equals(fact.get("kind"))&&(!label.containsKey("path")||label.get("path").equals(fact.get("path")))&&(!label.containsKey("target")||label.get("target").equals(fact.get("target")))&&(!label.containsKey("status")||label.get("status").equals(fact.get("status")));
    }
    @Test void forbiddenLabelsApplyOnlyToTheirDeclaredSourcePath(){
        var label=Map.<String,Object>of("forbiddenKind","NATIVE_ROUTE_DECLARATION","path","app/_private/page.tsx");
        assertFalse(forbiddenLabelMatches(Map.of("kind","NATIVE_ROUTE_DECLARATION","path","app/public/page.tsx"),label));
        assertTrue(forbiddenLabelMatches(Map.of("kind","NATIVE_ROUTE_DECLARATION","path","app/_private/page.tsx"),label));
    }
    @Test void allArchitectureContextsReachCasReplayIndependentLabelsAndBoundedEvidenceNavigation()throws Exception {
        String configured=System.getProperty("architecture.captureRoot"),labelPath=System.getProperty("architecture.labels");assertNotNull(configured,"Explicit completed architecture capture root required");assertNotNull(labelPath,"Precommitted independent labels required");
        verify(configured,labelPath,Optional.empty());
    }
    /** Recovery proves exact native output bytes; it never makes an incomplete export census pass. */
    void verify(String configured,String labelPath,Optional<Path> reproducedNativeBytes)throws Exception {
        var root=Path.of(configured).toRealPath();var setup=object(root.resolve("setup.json"));assertEquals("AUTHORED_NATIVE_SDK_AND_FRONTEND",setup.get("acceptanceScope"));
        var profile=object(root.resolve("profile.json"));var campaign=object(root.resolve("campaign.json"));var labels=object(Path.of(labelPath));var contexts=GraphJson.object(campaign.get("contexts"));var expected=GraphJson.object(labels.get("labels"));assertEquals(expected.keySet(),contexts.keySet());assertEquals(labels.containsKey("expectedContexts")?((BigDecimal)labels.get("expectedContexts")).intValueExact():12,contexts.size());
        var store=new StreamingContentStore(temporary.resolve("cas"));var tables=new SemanticTables(store,1_000_000);
        assertEquals(handle(profile.get("campaign")),put(store,root.resolve("campaign.json")));
        for(var pin:GraphJson.object(profile.get("controls")).entrySet()){assertFalse(Arrays.asList(pin.getKey().split("/")).contains(".."));assertEquals(pin.getValue(),put(store,root.resolve("control-sources").resolve(pin.getKey())).digest().value());}
        var exported=GraphJson.object(object(root.resolve("export-request.json")).get("request"));String attempt=GraphJson.string(exported.get("attempt"));var owner=object(root.resolve("owner-"+attempt+".json"));var off=object(root.resolve("termination-result.json"));assertEquals("Off",off.get("state"));for(String field:List.of("attempt","vmId","fence","launch"))assertEquals(owner.get(field),off.get(field));
        var results=GraphJson.object(object(root.resolve("native-campaign-result.json")).get("results"));assertEquals(contexts.keySet(),results.keySet());var paths=new TreeMap<String,ContentHandle>();String censusSummary;
        if(reproducedNativeBytes.isPresent()){
            assertEquals("HUMAN_TASK_M845_STEP2",setup.get("authorization"));
            var probe=object(root.resolve("sdk-probe-result.json"));assertEquals(results,GraphJson.object(GraphJson.object(probe.get("native")).get("results")));
            assertEquals("OVERLAY_BUDGET_EXHAUSTED",object(root.resolve("supervisor-result.json")).get("reason"));
            var raw=put(store,reproducedNativeBytes.orElseThrow());for(var result:results.entrySet()){var row=GraphJson.object(result.getValue());assertEquals(BigDecimal.ZERO,row.get("exit"));assertEquals(handle(row.get("output")),raw,"Recovered bytes must equal the independent VM-reported SHA-256 AND length");assertEquals(BigDecimal.ZERO,GraphJson.object(row.get("stderr")).get("size"));paths.put("native/"+result.getKey()+"/capture.ndjson",raw);}
            censusSummary="INCOMPLETE_NATIVE_DIGEST_MATCHED_REPLAY";
        }else{
        var directory=setup.get("captureRoot") instanceof String configuredCapture?Path.of(configuredCapture):root.resolve("capture");put(store,directory.resolve("scope.json"));put(store,directory.resolve("entries.ndjson"));
        var scope=new OutputCensus.Scope(attempt,GraphJson.string(exported.get("nonce")),new OutputCensus.Budget(((BigDecimal)exported.get("maxRowBytes")).intValueExact(),((BigDecimal)exported.get("maxObjectBytes")).longValueExact(),((BigDecimal)exported.get("maxCaptureBytes")).longValueExact()));
        var verifier=new OutputCensus(store);var header=verifier.header(put(store,directory.resolve("collector-result.json")),scope);
        var census=verifier.verify(header,h->Files.newInputStream(directory.resolve("objects").resolve(h.digest().value().substring(7))),row->{if(row.path().isPresent()&&row.captured().isPresent())assertNull(paths.put(row.path().orElseThrow().utf8().orElseThrow(),row.captured().orElseThrow()));},()->false);
        assertEquals(OutputCensus.Decision.CAPTURED,census.decision());assertEquals(0,census.observed().orElseThrow().pending());assertEquals(0,census.observed().orElseThrow().rejected());
        censusSummary=Long.toString(census.observed().orElseThrow().entries());
        }
        var imported=new TreeMap<String,ArchitectureCapture.Result>();var audits=new TreeMap<String,ArchitectureCaptureAudit.Result>();int checked=0,occurrences=0;
        for(var entry:contexts.entrySet()){
            String name=entry.getKey();var input=GraphJson.object(GraphJson.object(entry.getValue()).get("request"));var result=GraphJson.object(results.get(name));assertEquals(BigDecimal.ZERO,result.get("exit"),name);
            var raw=paths.get("native/"+name+"/capture.ndjson");assertEquals(handle(result.get("output")),raw,name);if(reproducedNativeBytes.isEmpty())assertNotNull(paths.get("native/"+name+"/prepared-request.json"));
            Map<String,Object> capture;try(var stream=store.open(raw)){capture=GraphJson.object(GraphJson.read(new InputStreamReader(stream,StandardCharsets.UTF_8),new GraphJson.Limits(64*1024*1024,64,64*1024*1024)));}
            var nativeScope=new ContentDigest(GraphJson.string(input.get("scope")));var docs=new TreeMap<String,SemanticTables.Row>();
            for(Object value:GraphJson.array(input.get("files"))){var source=GraphJson.object(value);byte[] bytes=sourceBytes(source);var content=store.put(new ByteArrayInputStream(bytes),()->false);assertEquals(source.get("sha256"),content.digest().value());String path=GraphJson.string(source.get("path"));var fields=new TreeMap<String,Object>();fields.put("content",content);fields.put("role",source.getOrDefault("role","unknown"));if(source.containsKey("text"))fields.put("encoding","UTF-8");docs.put(path,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,path,nativeScope,fields));}
            var documents=tables.write(SemanticTables.Kind.DOCUMENT,docs.values().iterator(),()->false);var evidence=EvidenceContext.forSnapshot(SnapshotIdentity.from(RepositoryIdentity.fromCanonicalCoordinate(GraphJson.string(input.get("repository"))),nativeScope));
            var profiles=GraphJson.array(input.get("profiles")).stream().map(GraphJson::object).toList();var request=new ArchitectureCapture.Request(evidence,nativeScope,GraphJson.string(input.get("repository")),GraphJson.string(input.get("app")),GraphJson.object(input.get("context")),profiles,documents,new ContentDigest(GraphJson.string(capture.get("tool"))));
            var gaps=new ArrayList<CapabilityGapRecord>();var first=ArchitectureCapture.read(request,raw,tables,store,64*1024*1024,gaps::add,()->false);assertEquals(docs.size(),first.documents());assertEquals(12L*docs.size(),first.census());assertTrue(gaps.stream().anyMatch(g->g.reasonCode().equals("ARCHITECTURE_METHOD_UNQUALIFIED")));
            var query=new ArchitectureCaptureQuery(tables);var filter=new ArchitectureCaptureQuery.Filter(ArchitectureCaptureQuery.Section.FACTS,Optional.empty(),Optional.empty());Optional<ArchitectureCaptureQuery.Cursor> cursor=Optional.empty();var facts=new ArrayList<Map<String,Object>>();do{var page=query.page(first,filter,cursor,25,()->false);facts.addAll(page.rows().stream().map(SemanticTables.Row::fields).toList());cursor=page.next();}while(cursor.isPresent());assertEquals(first.factCount(),facts.size());
            for(Object value:GraphJson.array(expected.get(name))){var label=GraphJson.object(value);
                if(label.containsKey("gap"))assertTrue(gaps.stream().anyMatch(g->label.get("gap").equals(g.reasonCode())),name+":"+label);
                else if(label.containsKey("forbiddenGap"))assertFalse(gaps.stream().anyMatch(g->label.get("forbiddenGap").equals(g.reasonCode())),name+":"+label);
                else if(label.containsKey("ownerName")){
                    var callable=facts.stream().filter(f->"CALLABLE_OWNER".equals(f.get("kind"))&&label.get("ownerName").equals(GraphJson.object(f.get("attributes")).get("name"))).findFirst().orElseThrow();
                    for(Object kind:GraphJson.array(label.get("boundaryKinds")))assertTrue(facts.stream().anyMatch(f->kind.equals(f.get("kind"))&&callable.get("target").equals(GraphJson.object(f.get("attributes")).get("owner"))),name+":"+kind);
                }
                else if(label.containsKey("forbiddenKind"))assertFalse(facts.stream().anyMatch(f->forbiddenLabelMatches(f,label)),name+":"+label);
                else assertTrue(facts.stream().anyMatch(f->label.get("kind").equals(f.get("kind"))&&(!label.containsKey("path")||label.get("path").equals(f.get("path")))&&(!label.containsKey("status")||label.get("status").equals(f.get("status")))&&(!label.containsKey("target")||label.get("target").equals(f.get("target")))&&(!label.containsKey("route")||label.get("route").equals(GraphJson.object(f.get("attributes")).get("route")))&&(!label.containsKey("binding")||label.get("binding").equals(GraphJson.object(f.get("attributes")).get("binding")))),name+":"+label);checked++;}
            if(labels.get("occurrences") instanceof Map<?,?> all&&all.containsKey(name)){
                var independent=new ArrayList<ArchitectureCaptureAudit.Expected>();
                for(Object value:GraphJson.array(all.get(name))){var label=GraphJson.object(value);var range=label.get("range")==null?Optional.<ArchitectureCaptureAudit.Range>empty():Optional.of(new ArchitectureCaptureAudit.Range(((BigDecimal)GraphJson.object(label.get("range")).get("start")).longValueExact(),((BigDecimal)GraphJson.object(label.get("range")).get("end")).longValueExact()));
                    independent.add(new ArchitectureCaptureAudit.Expected(GraphJson.string(label.get("id")),GraphJson.string(label.get("document")),handle(label.get("source")),GraphJson.string(label.get("category")),GraphJson.string(label.get("kind")),range,Optional.ofNullable(label.get("target")).map(GraphJson::string),GraphJson.object(label.get("attributes")),GraphJson.array(label.get("statuses")).stream().map(GraphJson::string).toList(),((BigDecimal)label.get("minimum")).longValueExact(),((BigDecimal)label.get("maximum")).longValueExact()));
                }
                var audit=ArchitectureCaptureAudit.read(request,raw,independent,tables,store,64*1024*1024,gaps::add,()->false);
                assertEquals(new ArchitectureCaptureAudit.Counts(independent.size(),independent.size(),0,0,0,0,0),audit.counts(),name);assertFalse(audit.qualified());
                var page=query.occurrences(audit,Optional.empty(),Optional.empty(),Optional.empty(),100,()->false);assertEquals(independent.size(),page.rows().size());assertTrue(page.rows().stream().allMatch(r->"SATISFIED".equals(r.fields().get("outcome"))));
                var coldStore=new StreamingContentStore(temporary.resolve("cas"));assertEquals(audit,ArchitectureCaptureAudit.read(request,raw,independent,new SemanticTables(coldStore,1_000_000),coldStore,64*1024*1024,g->{},()->false));audits.put(name,audit);occurrences+=independent.size();
            }
            var reopened=new StreamingContentStore(temporary.resolve("cas"));var cold=ArchitectureCapture.read(request,raw,new SemanticTables(reopened,1_000_000),reopened,64*1024*1024,g->{},()->false);assertEquals(first,cold);imported.put(name,first);
        }
        assertEquals(labels.containsKey("expectedLabels")?((BigDecimal)labels.get("expectedLabels")).intValueExact():31,checked);for(Object value:GraphJson.array(labels.get("replays"))){var pair=GraphJson.array(value);var a=imported.get(GraphJson.string(pair.get(0)));var b=imported.get(GraphJson.string(pair.get(1)));assertEquals(a.raw(),b.raw());assertEquals(a.identity(),b.identity());assertEquals(a.facts(),b.facts());}
        for(Object value:GraphJson.array(labels.get("replays"))){var pair=GraphJson.array(value);if(audits.containsKey(GraphJson.string(pair.getFirst())))assertEquals(audits.get(GraphJson.string(pair.getFirst())),audits.get(GraphJson.string(pair.get(1))));}
        System.out.println("Architecture native capture: contexts="+contexts.size()+", independentLabels="+checked+", independentOccurrences="+occurrences+", byteCensus="+censusSummary+", trust=PRODUCER_ASSERTION, qualified=false");
    }
}
