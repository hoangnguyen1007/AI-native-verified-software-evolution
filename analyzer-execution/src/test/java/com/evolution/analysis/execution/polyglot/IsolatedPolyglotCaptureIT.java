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

/** Explicit registered native checkpoint; never silently skip when its capture root is missing.
 * Run with -Dtest=IsolatedPolyglotCaptureIT -Dpolyglot.captureRoot=<completed-private-attempt>.
 * All operations here read data; target execution belongs exclusively to the owned VM recipe.
 */
class IsolatedPolyglotCaptureIT {
    @TempDir Path temporary;
    Map<String,Object> object(Path path,int maximum)throws IOException{
        if(Files.size(path)>maximum)throw new IOException("CHECKPOINT_INPUT_WORKER_REQUIRED");
        return GraphJson.object(GraphJson.read(Files.readString(path),new GraphJson.Limits(maximum,64,maximum)));
    }
    ContentHandle capture(StreamingContentStore store,Path file)throws IOException{try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){return store.put(input,()->false);}}
    ContentHandle handle(Object value){var row=GraphJson.object(value);return new ContentHandle(new ContentDigest((String)row.get("digest")),((BigDecimal)row.get("size")).longValueExact());}
    @Test void actualIsolatedOutputsReachCasRequestsAndExplicitSemanticCoverage()throws Exception{
        String configured=System.getProperty("polyglot.captureRoot");assertNotNull(configured,"Explicit completed native capture root is required");var root=Path.of(configured).toRealPath();var directory=root.resolve("capture");
        var setup=object(root.resolve("setup.json"),65536);assertEquals("POLYGLOT_AUTHORED_FIXTURE",setup.get("acceptanceScope"));
        var request=GraphJson.object(object(root.resolve("export-request.json"),65536).get("request"));
        var scope=new OutputCensus.Scope((String)request.get("attempt"),(String)request.get("nonce"),new OutputCensus.Budget(((BigDecimal)request.get("maxRowBytes")).intValueExact(),((BigDecimal)request.get("maxObjectBytes")).longValueExact(),((BigDecimal)request.get("maxCaptureBytes")).longValueExact()));
        var off=object(root.resolve("termination-result.json"),65536);var owner=object(root.resolve("owner-"+scope.attempt()+".json"),65536);
        assertEquals("Off",off.get("state"));assertEquals(setup.get("attempt"),off.get("attempt"));assertEquals(owner.get("vmId"),off.get("vmId"));assertEquals(owner.get("launch"),off.get("launch"));assertEquals(owner.get("fence"),off.get("fence"));
        var nativeResult=object(root.resolve("native-result.json"),1048576);assertEquals(scope.attempt(),nativeResult.get("attempt"));assertEquals(false,nativeResult.get("qualified"));
        var profile=object(root.resolve("profile.json"),1048576);var payload=GraphJson.object(profile.get("payload"));
        var toolset=CanonicalJson.sha256(List.of("polyglot-toolset:m8.4-v1",handle(payload.get("typescript.js")).digest(),handle(payload.get("typescript-worker.cjs")).digest()));
        var store=new StreamingContentStore(temporary.resolve("cas"));var tables=new SemanticTables(store,65536);
        Map<String,Object> architecture=Map.of();if(payload.containsKey("qualification-cases.json")){var qualifiedFile=root.resolve("payload/qualification-cases.json");assertEquals(handle(payload.get("qualification-cases.json")),capture(store,qualifiedFile));architecture=GraphJson.object(object(qualifiedFile,1048576).get("cases"));}
        for(var item:GraphJson.object(profile.get("controls")).entrySet()){
            assertTrue(item.getKey().matches("[A-Za-z0-9_./-]+"));assertFalse(Arrays.asList(item.getKey().split("/")).contains(".."));
            assertEquals(item.getValue(),capture(store,root.resolve("control-sources").resolve(item.getKey())).digest().value());
        }
        for(String file:List.of("scope.json","entries.ndjson"))capture(store,directory.resolve(file));
        var header=new OutputCensus(store).header(capture(store,directory.resolve("collector-result.json")),scope);
        var expectations=new TreeMap<String,SemanticCaptureImport.Expected>();var inputs=new TreeMap<String,Map<String,Object>>();
        for(var item:GraphJson.object(nativeResult.get("contexts")).entrySet()){
            String name=item.getKey();assertTrue(name.matches("[a-z][a-z0-9-]{0,63}"));var result=GraphJson.object(item.getValue());assertEquals(BigDecimal.ZERO,result.get("exit"));
            var file=root.resolve("payload").resolve(name+".input.json");assertEquals(handle(payload.get(name+".input.json")),capture(store,file));var input=object(file,8388608);inputs.put(name,input);
            expectations.put(name,new SemanticCaptureImport.Expected(LogicalPath.utf8("results/"+name+".ndjson"),handle(result.get("output")),new ContentDigest((String)input.get("scope")),(String)input.get("language"),"5.9.3",toolset));
        }
        var expectedContexts=new TreeSet<>(Set.of("typescript","typescript-replay","javascript","missing-import"));expectedContexts.addAll(architecture.keySet());assertEquals(expectedContexts,expectations.keySet());
        var rows=new ArrayList<OutputCensus.RowOutcome>();
        var imported=new SemanticCaptureImport().read(header,h->Files.newInputStream(directory.resolve("objects").resolve(h.digest().value().substring(7)),LinkOption.NOFOLLOW_LINKS),expectations,store,tables,65536,rows::add,()->false);
        assertTrue(imported.bytesClosed());assertEquals(header.declared().entries(),rows.size());
        var policy=ContentDigest.sha256Utf8("no-production-semantic-method");var workPolicy=capture(store,root.resolve("profile.json")).digest();
        for(var item:imported.outcomes().entrySet()){
            String name=item.getKey();boolean architectureCase=architecture.containsKey(name);var golden=architectureCase?GraphJson.object(GraphJson.object(architecture.get(name)).get("expected")):Map.<String,Object>of();var actual=item.getValue().capture().orElseThrow();var input=inputs.get(name);String project=(String)GraphJson.array(input.get("projectFiles")).getFirst();String source=null;
            for(var file:GraphJson.array(input.get("files"))){var row=GraphJson.object(file);byte[] raw=((String)row.get("text")).getBytes(StandardCharsets.UTF_8);var saved=store.put(new ByteArrayInputStream(raw),()->false);assertEquals(row.get("sha256"),saved.digest().value());if(project.equals(row.get("path")))source=(String)row.get("text");}
            assertNotNull(source);var inventory=CanonicalJson.sha256(List.of("authored-typescript-fixture",source));var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:test:typescript-native");
            var resolution=store.put(new ByteArrayInputStream(CanonicalJson.write(Map.of("schema","captured-typescript-resolution:m8.4-v1","input",input,"toolchain",toolset)).getBytes(StandardCharsets.UTF_8)),()->false);
            var semanticRequest=new LanguageSemanticRequest(EvidenceContext.forSnapshot(SnapshotIdentity.from(repository,inventory)),inventory,"root","app","main","baseline",actual.language(),"5.9.3",actual.documents(),actual.census(),resolution,new VersionedIdentifier("frontend.typescript-capture","m8.4-v1"),toolset,policy,workPolicy);
            assertEquals(semanticRequest.scope(),actual.scope(),"Canonical request context: "+name);
            var output=new CapturedSemanticProvider(actual,tables,store).analyze(semanticRequest,()->false);var gaps=new ArrayList<CapabilityGapRecord>();
            var coverage=new SemanticReconciler().reconcile(semanticRequest,output,tables,store,Map.of(),Set.of(),()->policy,gaps::add,()->false);
            assertFalse(coverage.complete());assertEquals(0,coverage.omitted());assertFalse(coverage.censusQualified());assertFalse(coverage.observationsQualified());assertTrue(gaps.size()>=2);
            if(name.equals("missing-import"))assertTrue(coverage.errors()+coverage.unresolved()>0);else if(!architectureCase){assertEquals(0,coverage.errors()+coverage.unresolved());boolean current=handle(payload.get("typescript-worker.cjs")).equals(capture(store,Path.of("src/main/resources/polyglot/typescript-worker.cjs")));assertEquals(name.equals("javascript")?(current?18:16):(current?20:17),coverage.total());}else NativeCategoryControls.verify(name,golden.get("categoryControls"),semanticRequest.census(),output,tables);
            boolean foundCall=false;
            try(var reader=tables.open(output,()->false)){SemanticTables.Row row;while((row=reader.next())!=null)if(row.key().endsWith(":A06:call")){
                foundCall=true;
                if(name.equals("missing-import")){assertNotEquals("RESOLVED",row.fields().get("status"));assertTrue(GraphJson.array(row.fields().get("targets")).isEmpty());}
                else{assertEquals(architectureCase&&actual.language().equals("typescript")?"CONDITIONAL":"RESOLVED",row.fields().get("status"));var symbol=GraphJson.object(GraphJson.array(row.fields().get("symbols")).getFirst());assertEquals("twice",symbol.get("name"));assertEquals("PROJECT",symbol.get("origin"));var span=GraphJson.object(row.fields().get("span"));assertEquals(architectureCase?golden.get("call"):"twice(4)",source.substring(((BigDecimal)span.get("start")).intValueExact(),((BigDecimal)span.get("end")).intValueExact()));}
            }}
            assertTrue(foundCall);
        }
        assertEquals(expectations.get("typescript").bytes(),expectations.get("typescript-replay").bytes());
        assertTrue(new StreamingContentStore(temporary.resolve("cas")).verify(imported.report()));
        System.out.println("Native polyglot checkpoint: contexts="+expectations.size()+", censusRows="+rows.size()+", replay=identical, trust=PRODUCER_ASSERTION, qualified=false");
    }
}
