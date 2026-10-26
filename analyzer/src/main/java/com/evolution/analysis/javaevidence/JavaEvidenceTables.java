package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

/** Canonical streamed NDJSON. The row bound is a worker limit, never a repository ceiling. */
public final class JavaEvidenceTables {
    public record Table(String schema,ContentHandle content,long rows){
        public Table{Objects.requireNonNull(schema);Objects.requireNonNull(content);if(rows<0||!schema.endsWith("-table:m8.4-v1"))throw new IllegalArgumentException("JAVA_TABLE_INVALID");}
        public Map<String,Object> wire(){return Map.of("schema",schema,"content",content,"rows",rows);}
        public static Table from(Object value){var map=GraphJson.object(value);return new Table(GraphJson.string(map.get("schema")),JavaEvidenceRecord.handle(map.get("content")),((java.math.BigDecimal)map.get("rows")).longValueExact());}
        public String kind(){return schema.substring(0,schema.length()-"-table:m8.4-v1".length());}
    }
    @FunctionalInterface public interface Consumer {void accept(JavaEvidenceRecord row)throws IOException;}
    public enum ScanState { MORE, END, CANCELLED }
    /** Only a validated prefix can construct a cursor. Deserialized checkpoints are replayed. */
    public static final class Cursor {
        private final Table table;
        private final long rows,offset;
        private final JavaEvidenceRecord previous;
        private Cursor(Table table,long rows,long offset,JavaEvidenceRecord previous){this.table=table;this.rows=rows;this.offset=offset;this.previous=previous;}
        public Table table(){return table;}public long rows(){return rows;}public long offset(){return offset;}
    }
    public record Scan(Cursor cursor,ScanState state){}
    public static final class ScanFailure extends IOException {
        private final Cursor cursor;
        private ScanFailure(String reason,Cursor cursor,Throwable cause){super(reason,cause);this.cursor=cursor;}
        public Cursor cursor(){return cursor;}
    }
    private final RepositoryContentStore content;private final int rowCharacters;private final JavaEvidenceCodec codec;
    public JavaEvidenceTables(RepositoryContentStore content,int rowCharacters){this.content=Objects.requireNonNull(content);if(rowCharacters<1)throw new IllegalArgumentException("JAVA_ROW_BUDGET");this.rowCharacters=rowCharacters;codec=new JavaEvidenceCodec(new GraphJson.Limits(rowCharacters,64,Math.max(rowCharacters,1)));}
    public ContentHandle put(JavaEvidenceRecord record,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(record.json().getBytes(StandardCharsets.UTF_8)),cancelled);}
    public ContentHandle putCanonical(Object record,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(record).getBytes(StandardCharsets.UTF_8)),cancelled);}
    public void requireContent(ContentHandle handle)throws IOException{if(!content.verify(handle))throw new IOException("JAVA_EVIDENCE_CONTENT_UNAVAILABLE");}
    public JavaEvidenceRecord read(ContentHandle handle)throws IOException{
        if(!content.verify(handle))throw new IOException("JAVA_EVIDENCE_CONTENT_UNAVAILABLE");
        try(var in=content.open(handle);var reader=new InputStreamReader(in,decoder())){return codec.read(reader);}
    }
    public Table write(String kind,Iterator<JavaEvidenceRecord> rows,BooleanSupplier cancelled)throws IOException{
        requireKind(kind);
        var input=new RowsInput(kind,rows,cancelled);var handle=content.put(input,cancelled);return new Table(kind+"-table:m8.4-v1",handle,input.count);
    }
    public void read(Table table,Consumer consumer,BooleanSupplier cancelled)throws IOException{
        requireKind(table.kind());
        if(!content.verify(table.content()))throw new IOException("JAVA_TABLE_CONTENT_UNAVAILABLE");
        long count=0;JavaEvidenceRecord previous=null;
        try(var in=content.open(table.content());var reader=new BufferedReader(new InputStreamReader(in,decoder()))){
            while(true){check(cancelled);var line=new StringBuilder();int c;
                while((c=reader.read())!=-1&&c!='\n'){if(line.length()>=rowCharacters)throw new IOException("JAVA_TABLE_WORKER_REQUIRED");line.append((char)c);}
                if(c==-1){if(!line.isEmpty())throw new IOException("JAVA_TABLE_UNTERMINATED_ROW");break;}
                var record=codec.read(line.toString());validate(table.kind(),previous,record);
                if(!record.json().contentEquals(line))throw new IOException("JAVA_TABLE_NONCANONICAL_ROW");
                consumer.accept(record);previous=record;count=Math.addExact(count,1);
            }
        }
        if(count!=table.rows())throw new IOException("JAVA_TABLE_COUNT_MISMATCH");
    }
    /** Stream at most maxRows through the consumer; memory is bounded by one encoded row.
     * MORE/CANCELLED are open frontiers, never certificates for the whole table. */
    public Scan scan(Table table,Optional<Cursor> resume,int maxRows,Consumer consumer,BooleanSupplier cancelled)throws IOException{
        requireKind(table.kind());
        if(maxRows<1)throw new IllegalArgumentException("JAVA_TABLE_SCAN_BUDGET");
        Objects.requireNonNull(consumer);Objects.requireNonNull(cancelled);
        var position=resume.orElseGet(()->new Cursor(table,0,0,null));
        if(!position.table.equals(table))throw new IOException("JAVA_TABLE_CURSOR_SCOPE_MISMATCH");
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return new Scan(position,ScanState.CANCELLED);
        if(!content.verify(table.content()))throw new ScanFailure("JAVA_TABLE_CONTENT_UNAVAILABLE",position,null);
        if(position.offset==table.content().size())return end(table,position);
        try(var input=new BufferedInputStream(content.range(table.content(),position.offset,table.content().size()-position.offset))){
            int consumed=0;
            while(consumed<maxRows){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return new Scan(position,ScanState.CANCELLED);
                var line=new ByteArrayOutputStream();int next;
                while((next=input.read())!=-1&&next!='\n'){
                    if(line.size()>=Math.min(Integer.MAX_VALUE,4L*rowCharacters))throw new ScanFailure("JAVA_TABLE_WORKER_REQUIRED",position,null);
                    line.write(next);
                }
                if(next==-1){if(line.size()!=0)throw new ScanFailure("JAVA_TABLE_UNTERMINATED_ROW",position,null);return end(table,position);}
                String json;JavaEvidenceRecord row;
                try{json=decoder().decode(java.nio.ByteBuffer.wrap(line.toByteArray())).toString();
                    if(json.length()>rowCharacters)throw new ScanFailure("JAVA_TABLE_WORKER_REQUIRED",position,null);
                    row=codec.read(json);
                }catch(IllegalArgumentException|CharacterCodingException invalid){throw new ScanFailure("JAVA_TABLE_ROW_INVALID",position,invalid);}
                try{validate(table.kind(),position.previous,row);}catch(IOException invalid){throw new ScanFailure(invalid.getMessage(),position,invalid);}
                if(!row.json().equals(json))throw new ScanFailure("JAVA_TABLE_NONCANONICAL_ROW",position,null);
                long count=Math.addExact(position.rows,1),offset=Math.addExact(position.offset,(long)line.size()+1);
                if(count>table.rows()||offset>table.content().size())throw new ScanFailure("JAVA_TABLE_COUNT_MISMATCH",position,null);
                try{consumer.accept(row);}catch(IOException|RuntimeException rejected){throw new ScanFailure("JAVA_TABLE_CONSUMER_REJECTED",position,rejected);}
                position=new Cursor(table,count,offset,row);consumed++;
                if(offset==table.content().size())return end(table,position);
            }
            return new Scan(position,cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()?ScanState.CANCELLED:ScanState.MORE);
        }catch(ScanFailure invalid){throw invalid;}
        catch(IOException unavailable){throw new ScanFailure("JAVA_TABLE_READ_UNAVAILABLE",position,unavailable);}
    }
    private static Scan end(Table table,Cursor cursor)throws ScanFailure{
        if(cursor.rows!=table.rows())throw new ScanFailure("JAVA_TABLE_COUNT_MISMATCH",cursor,null);
        return new Scan(cursor,ScanState.END);
    }
    public ContentHandle checkpoint(Cursor cursor)throws IOException{
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-evidence-table-cursor:m8.4-v1");fields.put("table",cursor.table.wire());
        fields.put("rows",cursor.rows);fields.put("offset",cursor.offset);fields.put("previous",cursor.previous==null?null:cursor.previous.identity());
        return putCanonical(fields,()->false);
    }
    /** Checkpoint JSON is untrusted. Revalidate the exact sorted prefix before issuing a cursor.
     * A replay budget is a worker limit; the immutable checkpoint remains reusable by a larger worker. */
    public Cursor restore(Table table,ContentHandle checkpoint,long replayRows,BooleanSupplier cancelled)throws IOException{
        requireKind(table.kind());
        if(replayRows<0)throw new IllegalArgumentException("JAVA_TABLE_REPLAY_BUDGET");
        if(checkpoint.size()>16_384||!content.verify(checkpoint)||!content.verify(table.content()))throw new IOException("JAVA_TABLE_CURSOR_UNAVAILABLE");
        Map<String,Object> fields;
        try(var in=content.open(checkpoint);var reader=new InputStreamReader(in,decoder())){
            fields=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(16_384,16,1000)));
        }catch(IllegalArgumentException invalid){throw new IOException("JAVA_TABLE_CURSOR_INVALID",invalid);}
        try{
            if(!fields.keySet().equals(Set.of("schema","table","rows","offset","previous"))||!"java-evidence-table-cursor:m8.4-v1".equals(fields.get("schema"))
                    ||!Table.from(fields.get("table")).equals(table))throw new IOException("JAVA_TABLE_CURSOR_SCOPE_MISMATCH");
        }catch(RuntimeException invalid){throw new IOException("JAVA_TABLE_CURSOR_INVALID",invalid);}
        long rows,offset;
        if(!(fields.get("rows") instanceof java.math.BigDecimal)||!(fields.get("offset") instanceof java.math.BigDecimal))throw new IOException("JAVA_TABLE_CURSOR_INVALID");
        try{rows=((java.math.BigDecimal)fields.get("rows")).longValueExact();offset=((java.math.BigDecimal)fields.get("offset")).longValueExact();}
        catch(ArithmeticException|ClassCastException invalid){throw new IOException("JAVA_TABLE_CURSOR_INVALID",invalid);}
        if(rows<0||offset<0||rows>table.rows()||offset>table.content().size())throw new IOException("JAVA_TABLE_CURSOR_INVALID");
        if(rows>replayRows)throw new IOException("JAVA_TABLE_REPLAY_WORKER_REQUIRED");
        var cursor=new Cursor(table,0,0,null);
        while(cursor.rows<rows){
            var page=scan(table,Optional.of(cursor),(int)Math.min(256,rows-cursor.rows),row->{},cancelled);cursor=page.cursor();
            if(page.state()==ScanState.CANCELLED)throw new ScanFailure("JAVA_EVIDENCE_CANCELLED_WITH_FRONTIER",cursor,null);
        }
        Object previous=cursor.previous==null?null:cursor.previous.identity().value();
        if(cursor.offset!=offset||!Objects.equals(previous,fields.get("previous")))throw new IOException("JAVA_TABLE_CURSOR_PREFIX_MISMATCH");
        return cursor;
    }
    public static int compare(JavaEvidenceRecord a,JavaEvidenceRecord b){return switch(a.kind()){
        case "java-evidence-input"->{int c=a.text("role").compareTo(b.text("role"));if(c==0)c=CanonicalJson.write(a.fields().get("locator")).compareTo(CanonicalJson.write(b.fields().get("locator")));yield c!=0?c:JavaEvidenceRecord.handle(a.fields().get("content")).digest().compareTo(JavaEvidenceRecord.handle(b.fields().get("content")).digest());}
        case "java-resolution-slot"->{int c=a.text("space").compareTo(b.text("space"));yield c!=0?c:Long.compare(a.number("ordinal"),b.number("ordinal"));}
        case "java-output-row"->a.digest("key").compareTo(b.digest("key"));
        case "java-closure-requirement"->JavaEvidenceLedger.requirementKey(a).compareTo(JavaEvidenceLedger.requirementKey(b));
        case "java-closure-current-row"->a.digest("requirement").compareTo(b.digest("requirement"));
        case "java-source-map-row"->a.text("projectedPath").compareTo(b.text("projectedPath"));
        default->a.identity().compareTo(b.identity());};}
    private static void requireKind(String kind)throws IOException{if(!JavaEvidenceCodec.recordKind(kind))throw new IOException("JAVA_TABLE_ROW_KIND");}
    private static void validate(String kind,JavaEvidenceRecord previous,JavaEvidenceRecord row)throws IOException{
        if(!row.kind().equals(kind))throw new IOException("JAVA_TABLE_ROW_KIND");
        if(previous!=null&&compare(previous,row)>=0)throw new IOException("JAVA_TABLE_DUPLICATE_OR_UNORDERED_KEY");
    }
    private static CharsetDecoder decoder(){return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("JAVA_EVIDENCE_CANCELLED_WITH_FRONTIER");}
    private final class RowsInput extends InputStream {
        private final String kind;private final Iterator<JavaEvidenceRecord> rows;private final BooleanSupplier cancelled;
        private byte[] buffer=new byte[0];private int offset;private long count;private JavaEvidenceRecord previous;
        RowsInput(String kind,Iterator<JavaEvidenceRecord> rows,BooleanSupplier cancelled){this.kind=kind;this.rows=rows;this.cancelled=cancelled;}
        private boolean fill()throws IOException{
            while(offset==buffer.length){check(cancelled);if(!rows.hasNext())return false;var row=rows.next();validate(kind,previous,row);String json=row.json();if(json.length()>rowCharacters)throw new IOException("JAVA_TABLE_WORKER_REQUIRED");buffer=(json+"\n").getBytes(StandardCharsets.UTF_8);offset=0;previous=row;count=Math.addExact(count,1);}
            return true;
        }
        @Override public int read()throws IOException{return fill()?Byte.toUnsignedInt(buffer[offset++]):-1;}
        @Override public int read(byte[] target,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,target.length);if(length==0)return 0;if(!fill())return -1;int size=Math.min(length,buffer.length-offset);System.arraycopy(buffer,offset,target,start,size);offset+=size;return size;}
    }
}
