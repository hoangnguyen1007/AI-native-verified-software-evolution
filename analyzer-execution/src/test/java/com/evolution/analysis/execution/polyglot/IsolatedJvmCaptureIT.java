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

/** Explicit native-data checkpoint. No application code executes in this host-side reader. */
class IsolatedJvmCaptureIT {
    @TempDir Path temporary;
    Map<String,Object> object(Path path)throws IOException{return GraphJson.object(GraphJson.read(Files.readString(path),new GraphJson.Limits(8388608,64,8388608)));}
    ContentHandle handle(Object value){var row=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),((BigDecimal)row.get("size")).longValueExact());}
    ContentHandle put(StreamingContentStore store,Path path)throws IOException{try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){return store.put(input,()->false);}}
    ContentHandle put(StreamingContentStore store,Object value)throws IOException{return store.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    @Test void everyAuthoredNativeCaseReachesExactCanonicalContextAndGoldenAssertions()throws Exception{
        String configured=System.getProperty("polyglot.jvmCaptureRoot");assertNotNull(configured,"Explicit completed JVM native capture is required");var root=Path.of(configured).toRealPath();
        var setup=object(root.resolve("setup.json"));assertEquals("AUTHORED_NATIVE_SDK_AND_FRONTEND",setup.get("acceptanceScope"));var profile=object(root.resolve("profile.json"));
        var export=GraphJson.object(object(root.resolve("export-request.json")).get("request"));String attempt=GraphJson.string(export.get("attempt"));var owner=object(root.resolve("owner-"+attempt+".json"));var off=object(root.resolve("termination-result.json"));
        assertEquals("Off",off.get("state"));for(String key:List.of("attempt","vmId","fence","launch"))assertEquals(owner.get(key),off.get(key));assertEquals(setup.get("attempt"),off.get("attempt"));
        var store=new StreamingContentStore(temporary.resolve("cas"));var tables=new SemanticTables(store,65536);
        for(var pin:GraphJson.object(profile.get("controls")).entrySet()){assertFalse(Arrays.asList(pin.getKey().split("/")).contains(".."));assertEquals(pin.getValue(),put(store,root.resolve("control-sources").resolve(pin.getKey())).digest().value());}
        var campaign=object(root.resolve("campaign.json"));assertEquals(handle(profile.get("campaign")),put(store,root.resolve("campaign.json")));var contexts=GraphJson.object(campaign.get("contexts"));
        for(String tool:List.of("platform","adapters")){var asset=GraphJson.object(campaign.get(tool));assertEquals(handle(asset),put(store,Path.of(GraphJson.string(asset.get("path")))));}
        var cases=GraphJson.object(GraphJson.read(Files.readString(Path.of(System.getProperty("polyglot.jvmCases","src/test/resources/polyglot/jvm-native-cases.json"))),GraphJson.Limits.defaults()));var labels=GraphJson.object(cases.get("cases"));var replays=GraphJson.object(cases.get("replays"));
        var allNames=new HashSet<>(labels.keySet());allNames.addAll(replays.keySet());assertEquals(allNames,contexts.keySet());
        var results=GraphJson.object(object(root.resolve("native-campaign-result.json")).get("results"));assertEquals(allNames,results.keySet());
        String selectedNames=System.getProperty("polyglot.jvmContextSelection","*");var excluded=new TreeSet<String>();if(!selectedNames.equals("*")){var selected=new TreeSet<>(Arrays.asList(selectedNames.split(",",-1)));assertFalse(selected.isEmpty());assertTrue(contexts.keySet().containsAll(selected));excluded.addAll(contexts.keySet());excluded.removeAll(selected);var subset=new TreeMap<String,Object>();contexts.forEach((key,value)->{if(selected.contains(key))subset.put(key,value);});contexts=subset;var selectedReplays=new TreeMap<String,Object>();replays.forEach((key,value)->{if(selected.contains(key)){assertTrue(selected.contains(GraphJson.string(value)));selectedReplays.put(key,value);}});replays=selectedReplays;}System.out.println("JVM context selection: selected="+contexts.keySet()+", excludedFromQualification="+excluded+", fullCapturedDenominator="+allNames.size());
        var expected=new TreeMap<String,SemanticCaptureImport.NativeExpected>();
        for(var entry:contexts.entrySet()){
            String name=entry.getKey();var input=GraphJson.object(GraphJson.object(entry.getValue()).get("request"));var provider=GraphJson.object(input.get("provider"));var result=GraphJson.object(results.get(name));assertEquals(BigDecimal.ZERO,result.get("exit"),name);
            expected.put(name,new SemanticCaptureImport.NativeExpected(LogicalPath.utf8("native/"+name+"/capture.ndjson"),handle(result.get("output")),new ContentDigest(GraphJson.string(input.get("scope"))),GraphJson.string(input.get("language")),GraphJson.string(input.get("dialect")),new VersionedIdentifier(GraphJson.string(provider.get("id")),GraphJson.string(provider.get("version"))),new ContentDigest(GraphJson.string(input.get("tool")))));
        }
        var directory=root.resolve("capture");put(store,directory.resolve("scope.json"));put(store,directory.resolve("entries.ndjson"));var scope=new OutputCensus.Scope(attempt,GraphJson.string(export.get("nonce")),new OutputCensus.Budget(((BigDecimal)export.get("maxRowBytes")).intValueExact(),((BigDecimal)export.get("maxObjectBytes")).longValueExact(),((BigDecimal)export.get("maxCaptureBytes")).longValueExact()));
        var header=new OutputCensus(store).header(put(store,directory.resolve("collector-result.json")),scope);var rows=new ArrayList<OutputCensus.RowOutcome>();var imported=new SemanticCaptureImport().readNative(header,h->Files.newInputStream(directory.resolve("objects").resolve(h.digest().value().substring(7))),expected,store,tables,65536,rows::add,()->false);
        assertTrue(imported.bytesClosed());assertEquals(header.declared().entries(),rows.size());var policy=ContentDigest.sha256Utf8("no-production-native-semantic-authority");var workPolicy=CanonicalJson.sha256(profile.get("workPolicy"));
        for(var entry:contexts.entrySet()){
            String name=entry.getKey(),original=replays.containsKey(name)?GraphJson.string(replays.get(name)):name;var label=GraphJson.object(labels.get(original));var golden=GraphJson.object(label.get("expected"));var input=GraphJson.object(GraphJson.object(entry.getValue()).get("request"));var sourceTexts=new TreeMap<String,String>();var identities=new ArrayList<List<String>>();
            for(var source:GraphJson.array(input.get("files"))){var file=GraphJson.object(source);String path=GraphJson.string(file.get("path")),text=GraphJson.string(file.get("text"));sourceTexts.put(path,text);var saved=store.put(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)),()->false);assertEquals(file.get("sha256"),saved.digest().value());identities.add(List.of(path,saved.digest().value()));}
            assertEquals(GraphJson.object(label.get("files")),sourceTexts);var inventory=CanonicalJson.sha256(identities);var actual=imported.outcomes().get(name).capture().orElseThrow();var repository=RepositoryIdentity.fromCanonicalCoordinate(GraphJson.string(input.get("repositoryScope")));
            var request=new LanguageSemanticRequest(EvidenceContext.forSnapshot(SnapshotIdentity.from(repository,inventory)),inventory,"authored","app","main","default",actual.language(),actual.dialect(),actual.documents(),actual.census(),put(store,Map.of("sdk",GraphJson.object(profile.get("sdk")).get("manifest"),"scope","AUTHORED_NATIVE_JVM_ONLY")),actual.provider(),actual.tool(),policy,workPolicy);
            assertEquals(request.scope(),actual.scope(),name);var output=actual.asProvider(request,tables,store).analyze(request,()->false);var gaps=new ArrayList<CapabilityGapRecord>();var coverage=new SemanticReconciler().reconcile(request,output,tables,store,Map.of(),Set.of(),()->policy,gaps::add,()->false);assertEquals(0,coverage.omitted(),name);assertFalse(coverage.complete());assertFalse(coverage.observationsQualified());
            boolean callFound=false,syntaxError=false;
            try(var reader=tables.open(output,()->false)){SemanticTables.Row row;while((row=reader.next())!=null){var fields=row.fields();if(fields.get("status").equals("ERROR"))syntaxError=true;
                if(!golden.containsKey("call")||!fields.get("role").equals("call")||fields.get("span")==null)continue;
                var span=GraphJson.object(fields.get("span"));String source=sourceTexts.get(GraphJson.string(span.get("path")));String written=source.substring(((BigDecimal)span.get("start")).intValueExact(),((BigDecimal)span.get("end")).intValueExact());if(!written.equals(golden.get("call")))continue;callFound=true;
                if(golden.containsKey("status")){assertEquals(golden.get("status"),fields.get("status"),name);assertTrue(GraphJson.array(fields.get("targets")).isEmpty());}
                else{assertEquals("RESOLVED",fields.get("status"),name);var symbol=GraphJson.object(GraphJson.array(fields.get("symbols")).getFirst());assertEquals(golden.get("targetName"),symbol.get("name"));assertEquals(golden.get("origin"),symbol.get("origin"));if(golden.containsKey("signatureContains"))assertTrue(GraphJson.string(symbol.get("signature")).contains(GraphJson.string(golden.get("signatureContains"))));}
            }}
            if(golden.containsKey("categoryControls"))NativeCategoryControls.verify(name,golden.get("categoryControls"),request.census(),output,tables);
            if(golden.containsKey("call"))assertTrue(callFound,name);if(Boolean.TRUE.equals(golden.get("syntaxError")))assertTrue(syntaxError,name);if(Boolean.TRUE.equals(golden.get("diagnosticError")))assertTrue(coverage.errors()>0,name);
        }
        for(var replay:replays.entrySet())assertEquals(expected.get(replay.getKey()).bytes(),expected.get(GraphJson.string(replay.getValue())).bytes());
        assertTrue(new StreamingContentStore(temporary.resolve("cas")).verify(imported.report()));System.out.println("Native JVM checkpoint: contexts="+contexts.size()+", censusRows="+rows.size()+", rawReplay=identical, qualified=false");
    }
}
