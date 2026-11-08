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
}
