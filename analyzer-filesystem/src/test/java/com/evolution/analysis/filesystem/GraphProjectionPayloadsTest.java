package com.evolution.analysis.filesystem;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GraphProjectionPayloadsTest {
    @TempDir Path directory;
    @Test void segmentsRoundTripDeduplicateAndReleaseAllOwnedFiles()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite"))) {
            var values=new LinkedHashMap<String,String>();
            try(var payloads=new GraphProjectionPayloads(db,4096,()->false,128)) {
                for(int i=0;i<90;i++) {
                    String value=i+":"+UUID.nameUUIDFromBytes(new byte[]{(byte)i})+"\n\uD83D\uDE00";
                    String id=payloads.put(value);values.put(id,value);assertEquals(id,payloads.put(value));
                }
                for(var row:values.entrySet())assertEquals(row.getValue(),payloads.get(row.getKey()));
                try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM payloads")){assertTrue(rows.next());assertEquals(90,rows.getInt(1));}
                try(var files=Files.list(directory)){assertTrue(files.filter(p->p.toString().endsWith(".pack")).count()>4);}
            }
            try(var files=Files.list(directory)){assertTrue(files.noneMatch(p->p.toString().endsWith(".pack")));}
        }
    }
    @Test void uncachedCorruptPayloadCannotBecomeCanonicalBytes()throws Exception {
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite"));var payloads=new GraphProjectionPayloads(db,4096,()->false,8)) {
            String first=payloads.put("original");for(int i=0;i<80;i++)payloads.put("next-"+i);
            Path file;
            // Locate the first segment by its exact original bytes, independent of filenames/timestamps.
            try(var files=Files.list(directory)){file=files.filter(p->p.toString().endsWith(".pack")).filter(p->{try{return Arrays.equals(Files.readAllBytes(p),"original".getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception e){throw new RuntimeException(e);}}).findFirst().orElseThrow();}
            Files.writeString(file,"corrupt!");
            assertEquals("GRAPH_PROJECTION_PAYLOAD_HASH_MISMATCH",assertThrows(java.io.IOException.class,()->payloads.get(first)).getMessage());
        }
    }
    @Test void cancelledOrOversizedPayloadNeverGetsAnIndexEntry()throws Exception {
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("index.sqlite"));var payloads=new GraphProjectionPayloads(db,16,cancelled::get)) {
            assertThrows(java.io.IOException.class,()->payloads.put("x".repeat(17)));cancelled.set(true);
            assertThrows(java.util.concurrent.CancellationException.class,()->payloads.put("valid"));
            try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT count(*) FROM payloads")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
        }
    }
}
