package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Native-neutral immutable NDJSON. Bounds apply to one worker row, never to the repository. */
public final class SemanticTables {
    public enum Kind { DOCUMENT, CENSUS, OBSERVATION, CONTEXT, CAPABILITY_GAP, REVISION, CURRENT, CONFLICT }
    public record Row(Kind kind,String key,ContentDigest scope,Map<String,Object> fields) {
        public Row {
            Objects.requireNonNull(kind);Objects.requireNonNull(scope);if(key==null||key.isEmpty())throw new IllegalArgumentException("SEMANTIC_KEY_REQUIRED");
            fields=freeze(fields);
        }
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
        @SuppressWarnings("unchecked") private static <T> T freeze(T value){
            if(value instanceof Map<?,?> map){var copy=new TreeMap<String,Object>();map.forEach((key,member)->copy.put((String)key,freeze(member)));return (T)Collections.unmodifiableMap(copy);}
            if(value instanceof List<?> list)return (T)list.stream().map(Row::freeze).toList();
            if(value==null||value instanceof String||value instanceof Boolean||value instanceof java.math.BigDecimal||value instanceof Long||value instanceof Integer||value instanceof ContentDigest||value instanceof ContentHandle)return value;
            throw new IllegalArgumentException("NON_NEUTRAL_SEMANTIC_VALUE");
        }
    }
    public record Table(String schema,Kind kind,ContentHandle content,long rows) {
        public Table {if(!"semantic-table:m8.4-v1".equals(schema)||rows<0)throw new IllegalArgumentException("SEMANTIC_TABLE_INVALID");Objects.requireNonNull(kind);Objects.requireNonNull(content);}
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
    }
    /** An untrusted checkpoint must have its exact prefix revalidated before any rows are skipped. */
    public record Cursor(ContentDigest table,long rows,Optional<String> previousKey){
        public Cursor {Objects.requireNonNull(table);Objects.requireNonNull(previousKey);if(rows<0||(rows==0)!=previousKey.isEmpty())throw new IllegalArgumentException("SEMANTIC_CURSOR_INVALID");}
    }
    public record Scan(Cursor cursor,boolean end,boolean cancelled){}
    @FunctionalInterface public interface Consumer {void accept(Row row)throws IOException;}
    private final RepositoryContentStore content;private final int rowBytes;
    public SemanticTables(RepositoryContentStore content,int rowBytes){this.content=Objects.requireNonNull(content);if(rowBytes<1||rowBytes>64_000_000)throw new IllegalArgumentException("SEMANTIC_WORKER_BUDGET");this.rowBytes=rowBytes;}
    public Table write(Kind kind,Iterator<Row> rows,BooleanSupplier cancelled)throws IOException{
        try(var stream=new Rows(kind,rows,cancelled)){var handle=content.put(stream,cancelled);return new Table("semantic-table:m8.4-v1",kind,handle,stream.count);}
    }
    public Reader open(Table table,BooleanSupplier cancelled)throws IOException{return new Reader(table,cancelled);}
    public Scan scan(Table table,Optional<Cursor> saved,int pageSize,Consumer consumer,BooleanSupplier cancelled)throws IOException{
        if(pageSize<1)throw new IllegalArgumentException("SEMANTIC_PAGE_SIZE");
        var cursor=saved.orElse(new Cursor(table.identity(),0,Optional.empty()));
        if(!cursor.table().equals(table.identity()))throw new IOException("SEMANTIC_CURSOR_SCOPE");
        try(var reader=open(table,cancelled)){
            String previous=null;
            for(long i=0;i<cursor.rows();i++){var row=reader.next();if(row==null)throw new IOException("SEMANTIC_CURSOR_PREFIX");previous=row.key();}
            if(!Objects.equals(Optional.ofNullable(previous),cursor.previousKey()))throw new IOException("SEMANTIC_CURSOR_PREFIX");
            for(int i=0;i<pageSize;i++){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return new Scan(cursor,false,true);
                var row=reader.next();if(row==null)return new Scan(cursor,true,false);
                consumer.accept(row);cursor=new Cursor(table.identity(),Math.addExact(cursor.rows(),1),Optional.of(row.key()));
            }
            // No look-ahead consumption: the next scan validates END and the full row count.
            return new Scan(cursor,false,false);
        }
    }
    public final class Reader implements AutoCloseable {
        private final Table table;private final InputStream input;private final BooleanSupplier cancelled;private long count;private String previous;private boolean end;
        private Reader(Table table,BooleanSupplier cancelled)throws IOException{
            this.table=Objects.requireNonNull(table);this.cancelled=Objects.requireNonNull(cancelled);check(cancelled);
            if(!content.verify(table.content()))throw new IOException("SEMANTIC_TABLE_CONTENT_UNAVAILABLE");input=content.open(table.content());
        }
        public Row next()throws IOException{
            check(cancelled);if(end)return null;
            var bytes=new ByteArrayOutputStream();int b;
            while((b=input.read())!=-1&&b!='\n') {if(bytes.size()==rowBytes)throw new IOException("SEMANTIC_ROW_WORKER_REQUIRED");bytes.write(b);}
            if(b==-1){if(bytes.size()!=0)throw new IOException("SEMANTIC_UNTERMINATED_ROW");end=true;if(count!=table.rows())throw new IOException("SEMANTIC_TABLE_COUNT_MISMATCH");return null;}
            String line;
            try{line=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();}
            catch(CharacterCodingException invalid){throw new IOException("SEMANTIC_ROW_ENCODING",invalid);}
            try{
                var value=GraphJson.object(GraphJson.read(line,new GraphJson.Limits(rowBytes,64,rowBytes)));
                if(!value.keySet().equals(Set.of("kind","key","scope","fields")))throw new IllegalArgumentException("SEMANTIC_ROW_FIELDS");
                var row=new Row(Kind.valueOf(GraphJson.string(value.get("kind"))),GraphJson.string(value.get("key")),new ContentDigest(GraphJson.string(value.get("scope"))),GraphJson.object(value.get("fields")));
                if(!CanonicalJson.write(row).equals(line))throw new IllegalArgumentException("SEMANTIC_NONCANONICAL_ROW");
                validate(table.kind(),previous,row);previous=row.key();count=Math.addExact(count,1);return row;
            }catch(IllegalArgumentException invalid){throw new IOException("SEMANTIC_ROW_INVALID",invalid);}
        }
        @Override public void close()throws IOException{input.close();}
    }
    private final class Rows extends InputStream {
        final Kind kind;final Iterator<Row> rows;final BooleanSupplier cancelled;long count;String previous;byte[] pending=new byte[0];int offset;
        Rows(Kind kind,Iterator<Row> rows,BooleanSupplier cancelled){this.kind=Objects.requireNonNull(kind);this.rows=Objects.requireNonNull(rows);this.cancelled=Objects.requireNonNull(cancelled);}
        private boolean fill()throws IOException{
            check(cancelled);if(offset<pending.length)return true;if(!rows.hasNext())return false;
            var row=rows.next();validate(kind,previous,row);pending=(CanonicalJson.write(row)+"\n").getBytes(StandardCharsets.UTF_8);
            if(pending.length-1>rowBytes)throw new IOException("SEMANTIC_ROW_WORKER_REQUIRED");previous=row.key();count=Math.addExact(count,1);offset=0;return true;
        }
        @Override public int read()throws IOException{return fill()?Byte.toUnsignedInt(pending[offset++]):-1;}
        @Override public int read(byte[] bytes,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,bytes.length);if(length==0)return 0;if(!fill())return -1;int n=Math.min(length,pending.length-offset);System.arraycopy(pending,offset,bytes,start,n);offset+=n;return n;}
    }
    private static void validate(Kind kind,String previous,Row row)throws IOException{
        if(row.kind()!=kind||previous!=null&&previous.compareTo(row.key())>=0)throw new IOException("SEMANTIC_TABLE_KIND_ORDER_OR_DUPLICATE");
    }
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_CANCELLED_WITH_FRONTIER");}
}
