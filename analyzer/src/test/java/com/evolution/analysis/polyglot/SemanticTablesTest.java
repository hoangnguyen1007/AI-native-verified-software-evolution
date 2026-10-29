package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SemanticTablesTest {
    @Test void pagedRowsRetainTheirExactPrefixAndRejectForgedCursors()throws Exception{
        var content=new Memory();var tables=new SemanticTables(content,4096);var scope=ContentDigest.sha256Utf8("context");
        var rows=java.util.stream.IntStream.range(0,513).mapToObj(i->new SemanticTables.Row(SemanticTables.Kind.CENSUS,String.format(Locale.ROOT,"%04d",i),scope,Map.of("label","é😀"))).toList();
        var table=tables.write(SemanticTables.Kind.CENSUS,rows.iterator(),()->false);var actual=new ArrayList<SemanticTables.Row>();Optional<SemanticTables.Cursor> cursor=Optional.empty();
        while(true){var page=tables.scan(table,cursor,100,actual::add,()->false);if(page.end())break;cursor=Optional.of(page.cursor());}
        assertEquals(rows,actual);assertEquals(513,table.rows());
        var forged=new SemanticTables.Cursor(table.identity(),10,Optional.of("unrelated"));
        assertThrows(IOException.class,()->tables.scan(table,Optional.of(forged),10,row->fail(),()->false));
    }
    @Test void emptyKindCountDuplicateOrderAndMalformedTailAreNotSuccess()throws Exception{
        var content=new Memory();var tables=new SemanticTables(content,1024);var row=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"x",ContentDigest.sha256Utf8("scope"),Map.of());
        assertThrows(IOException.class,()->tables.write(SemanticTables.Kind.CENSUS,List.of(row,row).iterator(),()->false));
        var table=tables.write(SemanticTables.Kind.CENSUS,List.of(row).iterator(),()->false);
        var wrong=new SemanticTables.Table(table.schema(),table.kind(),table.content(),0);
        assertThrows(IOException.class,()->{try(var reader=tables.open(wrong,()->false)){while(reader.next()!=null){}}});
        var bad=content.put(new ByteArrayInputStream("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
        assertThrows(IOException.class,()->{try(var reader=tables.open(new SemanticTables.Table(table.schema(),table.kind(),bad,1),()->false)){reader.next();}});
    }
    @Test void cancellationNeverReportsAnEndAndMutableRowsCannotChangeStoredIdentity()throws Exception{
        var values=new HashMap<String,Object>();values.put("nested",new ArrayList<>(List.of("a")));
        var row=new SemanticTables.Row(SemanticTables.Kind.CENSUS,"x",ContentDigest.sha256Utf8("scope"),values);var identity=row.identity();
        ((List<String>)values.get("nested")).add("b");assertEquals(identity,row.identity());
        var content=new Memory();var tables=new SemanticTables(content,1024);var table=tables.write(SemanticTables.Kind.CENSUS,List.of(row).iterator(),()->false);
        assertThrows(IOException.class,()->tables.scan(table,Optional.empty(),1,x->fail(),()->true));
        content.blobs.put(table.content().digest(),new byte[]{1});assertThrows(IOException.class,()->tables.open(table,()->false));
    }
    static final class Memory implements RepositoryContentStore {
        final Map<ContentDigest,byte[]> blobs=new HashMap<>();
        public ContentHandle put(InputStream input,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean())throw new IOException("cancelled");byte[] bytes=input.readAllBytes();var handle=new ContentHandle(ContentDigest.sha256(bytes),bytes.length);blobs.put(handle.digest(),bytes);return handle;}
        public InputStream open(ContentHandle handle)throws IOException{if(!verify(handle))throw new IOException("missing");return new ByteArrayInputStream(blobs.get(handle.digest()));}
        public InputStream range(ContentHandle handle,long offset,long length)throws IOException{byte[] bytes=blobs.get(handle.digest());return new ByteArrayInputStream(Arrays.copyOfRange(bytes,Math.toIntExact(offset),Math.toIntExact(offset+length)));}
        public boolean verify(ContentHandle handle){var bytes=blobs.get(handle.digest());return bytes!=null&&bytes.length==handle.size()&&ContentDigest.sha256(bytes).equals(handle.digest());}
        ContentHandle text(String value)throws IOException{return put(new ByteArrayInputStream(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);}
    }
}
