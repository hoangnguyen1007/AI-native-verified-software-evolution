package com.evolution.analysis.filesystem;

import com.evolution.analysis.graph.GraphJson;
import java.nio.file.Path;
import java.sql.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SqliteGraphProjectionTest {
    @TempDir Path directory;
    @Test void gapCountersIncludeContributionsAndDeduplicateContextReferences()throws Exception {
        try(var database=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("gaps.sqlite"))) {
            try(var sql=database.createStatement()){sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");}
            try(var projection=new SqliteGraphProjection(database,4096,()->false)) {
                var evidence=projection.records("evidence",com.evolution.analysis.graph.GraphSnapshotCodec::evidence);var nodes=projection.records("nodes",com.evolution.analysis.graph.GraphSnapshotCodec::node);
                var proof=com.evolution.analysis.graph.GraphEvidence.capture("evidence.gap",java.util.Map.of("reasonCode","AUTHORED_UNRESOLVED"));evidence.put(proof.identity().value(),proof);
                String id=com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("gap").value();
                String one=com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("one").value(),two=com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("two").value();
                for(String context:java.util.List.of(one,two))nodes.put(context+"|"+id,new com.evolution.analysis.graph.KnowledgeGraph.Node(id,context,"CAPABILITY_GAP",java.util.List.of(proof.identity())));
                var counts=projection.gapCounts();assertEquals(2,counts.nodes());assertEquals(java.util.Map.of("AUTHORED_UNRESOLVED",1L),counts.reasons());
                nodes.put(one+"|"+id,new com.evolution.analysis.graph.KnowledgeGraph.Node(id,one,"TYPE",java.util.List.of(proof.identity())));
                assertEquals(1,projection.gapCounts().nodes());assertEquals(counts.reasons(),projection.gapCounts().reasons());
            }
        }
    }
    @Test void repeatedLargePayloadsDoNotOccupyTheKeyBtree()throws Exception {
        Path file=directory.resolve("compact.sqlite");
        try(var database=DriverManager.getConnection("jdbc:sqlite:"+file)) {
            try(var sql=database.createStatement()){sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");}
            database.setAutoCommit(false);
            try(var projection=new SqliteGraphProjection(database,64_000,()->false)) {
            var map=projection.records("values",GraphJson::string);String value="evidence-".repeat(3000);
            for(int i=0;i<256;i++)map.put(String.format(java.util.Locale.ROOT,"%05d",i),value);
            database.commit();
            assertEquals(value,map.get("00123"));assertEquals(256,map.size());
            assertTrue(java.nio.file.Files.size(file)<400_000,"large payloads must leave the SQLite key index");
            }
        }
    }
    @Test void hotRowOperationsReusePreparedStatementsAndRetainMapSemantics()throws Exception {
        try(var database=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("projection.sqlite"))) {
            try(var sql=database.createStatement()){sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");}database.setAutoCommit(false);
            var prepares=new AtomicInteger();var counted=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement"))prepares.incrementAndGet();
                try{return method.invoke(database,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            try(var projection=new SqliteGraphProjection(counted,4096,()->false)) {var map=projection.records("values",GraphJson::string);
            for(int i=0;i<100;i++){String key=String.format(java.util.Locale.ROOT,"%05d",i);assertNull(map.put(key,"value"+i));assertEquals("value"+i,map.get(key));assertTrue(map.containsKey(key));}
            assertEquals("value0",map.putIfAbsent("00000","ignored"));assertEquals(100,map.size());assertEquals(100,map.entrySet().stream().count());assertEquals("00000",map.keySet().iterator().next());
            assertTrue(prepares.get()<16,"SQL preparations: "+prepares.get());
            }
        }
    }
    @Test void valuesByKindUsesPartialKindIndexReusesStatementsAndPreservesCanonicalOrder()throws Exception {
        try(var database=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("kinds.sqlite"))) {
            try(var sql=database.createStatement()){sql.execute("CREATE TABLE rows(space TEXT NOT NULL,k TEXT NOT NULL,payload TEXT NOT NULL,PRIMARY KEY(space,k)) WITHOUT ROWID");}
            database.setAutoCommit(false);
            var prepares=new AtomicInteger();
            var counted=(Connection)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("prepareStatement"))prepares.incrementAndGet();
                try{return method.invoke(database,args);}catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
            });
            try(var projection=new SqliteGraphProjection(counted,64_000,()->false)) {
                String ctx=com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("ctx").value();
                var proof=com.evolution.analysis.graph.GraphEvidence.capture("fixture.kind",java.util.Map.of("ok",true));
                projection.records("evidence",com.evolution.analysis.graph.GraphSnapshotCodec::evidence).put(proof.identity().value(),proof);
                var nodes=projection.records("nodes",com.evolution.analysis.graph.GraphSnapshotCodec::node);
                var expectedKeys=new java.util.ArrayList<String>();
                for(int i=0;i<320;i++) {
                    String id=com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("node-"+i).value();
                    String key=ctx+"|"+id;
                    String kind=i==10?"ENDPOINT":i==90?"ROUTE_CANDIDATE":i==170?"ENDPOINT":i==250?"ROUTE_CANDIDATE":i==300?"ENDPOINT":"TYPE";
                    nodes.put(key,new com.evolution.analysis.graph.KnowledgeGraph.Node(id,ctx,kind,java.util.List.of(proof.identity())));
                    if(i==300)nodes.put(key,new com.evolution.analysis.graph.KnowledgeGraph.Node(id,ctx,"TYPE",java.util.List.of(proof.identity())));
                    else if(!kind.equals("TYPE"))expectedKeys.add(key);
                }
                java.util.Collections.sort(expectedKeys);
                database.commit();
                assertEquals(320,nodes.entrySet().stream().count());
                var decoded=new AtomicInteger();
                var matched=new java.util.ArrayList<String>();
                for(var node:projection.valuesByKind("nodes",java.util.Set.of("ENDPOINT","ROUTE_CANDIDATE"),raw->{
                    decoded.incrementAndGet();return com.evolution.analysis.graph.GraphSnapshotCodec.node(raw);
                },com.evolution.analysis.graph.KnowledgeGraph.Node::kind))matched.add(node.context()+"|"+node.identity());
                assertEquals(expectedKeys,matched);
                assertEquals(4,decoded.get(),"valuesByKind must decode only matching indexed kinds, never unrelated TYPE rows");
                assertTrue(prepares.get()<16,"Multi-page scans and valuesByKind must reuse prepared statements: "+prepares.get());
                try(var planStmt=database.prepareStatement("EXPLAIN QUERY PLAN SELECT k,payload FROM rows INDEXED BY rows_space_kind_k WHERE space=? AND kind=? AND kind IS NOT NULL ORDER BY k LIMIT 128")) {
                    planStmt.setString(1,"nodes");planStmt.setString(2,"ENDPOINT");
                    try(var plan=planStmt.executeQuery()) {
                        var details=new StringBuilder();while(plan.next())details.append(plan.getString("detail")).append(' ');
                        assertTrue(details.toString().contains("COVERING INDEX rows_space_kind_k"),"SQLite query plan must use covering index rows_space_kind_k: "+details);
                    }
                }
            }
        }
    }
}
