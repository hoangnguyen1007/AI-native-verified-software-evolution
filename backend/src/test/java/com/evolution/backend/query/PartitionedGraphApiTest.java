package com.evolution.backend.query;

import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.graph.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.backend.query.ArchitectureQueryApiTest.*;

class PartitionedGraphApiTest {
    @TempDir Path directory;
    @Test void pagesUseDiskRowsWithoutInvokingTheLegacyWholeGraphLoader()throws Exception{
        var fixture=new QueryFixture().node("a").node("b").node("c").edge("a","b").edge("c","b");
        var graph=fixture.graph();new PartitionedGraphStore(directory).save(graph,()->false);
        var cold=new PartitionedGraphStore(directory);
        var paged=ArchitectureQueryApi.withPartitionedGraphs(new ArchitectureQueryApi(new GraphStore(){
            public void save(KnowledgeGraph ignored,java.util.function.BooleanSupplier cancelled){fail("Whole graph save");}
            public Optional<KnowledgeGraph> load(com.evolution.analysis.contract.common.ContentDigest ignored){throw new AssertionError("Whole graph load");}
        }),cold::reader);
        var api=ArchitectureQueryApi.withExecutions(paged,(operation,id)->{throw new AssertionError("Raw graph reads cannot launch a worker");});
        String path="/api/v1/graph-pages/"+graph.identity().value()+"/";
        var partitions=api.handle("POST",path+"partitions","application/json",bytes(Map.of("size",1)),()->false);
        assertEquals(200,partitions.status(),new String(partitions.body()));
        var data=GraphJson.object(body(partitions).get("data"));assertEquals("RAW_CONTEXT_FACTS",data.get("scope"));
        var partition=GraphJson.array(GraphJson.object(data.get("page")).get("items")).getFirst();
        var first=api.handle("POST",path+"nodes","application/json",bytes(Map.of("partition",partition,"size",1)),()->false);
        assertEquals(200,first.status());var firstPage=GraphJson.object(GraphJson.object(body(first).get("data")).get("page"));
        assertEquals(1,GraphJson.array(firstPage.get("items")).size());assertNotNull(firstPage.get("continuation"));
        var incoming=api.handle("POST",path+"adjacency","application/json",bytes(Map.of("partition",partition,"node",QueryFixture.id("b"),"incoming",true)),()->false);
        assertEquals(200,incoming.status());assertEquals(2,GraphJson.array(GraphJson.object(GraphJson.object(body(incoming).get("data")).get("page")).get("items")).size());
        assertEquals(400,api.handle("POST",path+"adjacency","application/json",bytes(Map.of("partition",partition,"node",QueryFixture.id("b"),"cursor",firstPage.get("continuation"),"size",1)),()->false).status());
        assertEquals(400,api.handle("POST",path+"nodes","application/json",bytes(Map.of("partition",partition,"size",new java.math.BigDecimal("1.5"))),()->false).status());
        assertEquals(400,api.handle("POST",path+"nodes","application/json",bytes(Map.of("partition",partition,"incoming",true)),()->false).status());
        assertEquals(405,api.handle("GET",path+"partitions","application/json",bytes(Map.of()),()->false).status());
        assertEquals(409,api.handle("POST",path+"partitions","application/json",bytes(Map.of()),()->true).status());
        assertEquals(503,new ArchitectureQueryApi(fixture.store()).handle("POST",path+"partitions","application/json",bytes(Map.of()),()->false).status());
    }
}
