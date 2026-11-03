package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Data-only direct consumer of the real authored mixed capture. No SDK, build or source execution. */
class BridgeMixedReplayIT {
    @Test void wholeSnapshotCoverageRecoveryWithdrawalAndTopologySurviveColdReplay()throws Exception{
        String configured=System.getProperty("bridge.fixtureRoot");assertNotNull(configured,"Explicit existing mixed capture required");Path root=Path.of(configured).toRealPath();var content=new StreamingContentStore(root.resolve("cas"));var tables=new SemanticTables(content,4_000_000);var policy=new BridgeReadPolicy(root.resolve("read-policy.json"));
        List<Path> heads;try(var files=Files.list(root.resolve("bridges"))){heads=files.filter(p->p.getFileName().toString().matches("[a-f0-9]{64}\\.json")).toList();}assertEquals(1,heads.size());var scope=new ContentDigest("sha256:"+heads.getFirst().getFileName().toString().substring(0,64));
        var store=new BridgePublicationStore(root.resolve("bridges"),content,tables,policy::policy,policy::revoked);var view=store.load(scope,()->false).orElseThrow();assertEquals(8,view.result().partitions());assertEquals(0,view.result().missingCensus());assertEquals(2L,view.result().counts().get("REOPENED"));assertFalse(view.result().qualified());
        var actual=new TreeMap<String,String>();Map<String,Object> snapshot=null;
        try(var rows=tables.open(view.input().partitions(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null){var part=ArchitectureBridges.partition(row.fields());try(var documents=tables.open(part.documents(),()->false)){SemanticTables.Row document;while((document=documents.next())!=null){String digest=handle(document.fields().get("content")).digest().value();String previous=actual.putIfAbsent(document.key(),digest);assertTrue(previous==null||previous.equals(digest),"Contradictory physical bytes across contexts");}}
            if(row.key().equals("backend")){var capture=object(content,part.capture().orElseThrow());snapshot=GraphJson.object(object(content,handle(capture.get("manifest"))).get("snapshot"));}
        }}assertNotNull(snapshot);assertEquals(view.input().root().snapshotIdentity().value(),snapshot.get("identity"));var expected=new TreeMap<String,String>();for(Object value:GraphJson.array(snapshot.get("documents"))){var doc=GraphJson.object(value);expected.put(GraphJson.string(doc.get("path")),GraphJson.string(doc.get("contentDigest")));}
        assertEquals(Integer.getInteger("bridge.expectedSources",32).intValue(),expected.size());for(var entry:expected.entrySet())assertEquals(entry.getValue(),actual.get(entry.getKey()),"Missing/changed inventoried source "+entry.getKey());
        assertEquals("SAVED_UNVERIFIED",store.queries().catalog(Optional.empty(),5,()->false).items().getFirst().availability());
        var named=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("library"),Optional.empty(),Optional.empty(),Optional.of("upper")),50,Optional.empty(),()->false);assertFalse(named.items().isEmpty(),"Displayed native symbol names must be searchable");
        var calls=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.of("A06"),Optional.of("worker"),Optional.empty(),Optional.of("worker/remote.py")),50,Optional.empty(),()->false);var resolvedCall=calls.items().stream().filter(row->"call".equals(row.fields().get("role"))&&"RESOLVED".equals(row.fields().get("status"))).findFirst().orElseThrow();
        var gaps=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.NATIVE_GAPS,Optional.of("A06"),Optional.of("worker"),Optional.empty(),Optional.of("worker/remote.py")),50,Optional.empty(),()->false);assertTrue(gaps.items().stream().noneMatch(row->resolvedCall.fields().get("obligation").equals(GraphJson.object(GraphJson.object(row.fields().get("gap")).get("subject")).get("identity"))),"Only the recovered call obligation closes; unrelated native uncertainties stay visible");
        var broken=store.queries().page(scope,new BridgeQueryService.Selection(BridgeQueryService.Section.FACTS,Optional.empty(),Optional.of("broken"),Optional.empty()),50,Optional.empty(),()->false);assertTrue(broken.items().stream().anyMatch(row->"ERROR".equals(row.fields().get("status"))));
        Map<String,Object> contract=null;try(var rows=tables.open(view.result().relations(),()->false)){SemanticTables.Row row;while((row=rows.next())!=null){var fields=row.fields();if("EXPOSES_CONTRACT".equals(fields.get("relation"))&&!GraphJson.array(fields.get("targets")).isEmpty())contract=GraphJson.object(GraphJson.array(fields.get("targets")).getFirst());}}assertNotNull(contract);
        var topology=store.queries().topology(scope,new ContentDigest(GraphJson.string(contract.get("identity"))),3,20,1000,()->false);assertTrue(topology.boundaries().stream().anyMatch(boundary->"backend".equals(boundary.from().get("partition"))));assertFalse(topology.exhaustive());assertFalse(topology.impact().runtimeBreakageProven());
        System.out.println("Mixed cold replay: "+expected.size()+"/"+expected.size()+" M1 sources, 8 contexts, native recovery closed, broken sibling retained, 2 reopened bindings, qualified topology PASS");
    }
    private static ContentHandle handle(Object value){var map=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),((java.math.BigDecimal)map.get("size")).longValueExact());}
    private static Map<String,Object> object(RepositoryContentStore content,ContentHandle handle)throws IOException{assertTrue(content.verify(handle));try(var input=content.open(handle)){return GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(64_000_000,64,64_000_000)));}}
}
