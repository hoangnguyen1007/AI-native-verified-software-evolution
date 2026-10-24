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
        var input=new RowsInput(kind,rows,cancelled);var handle=content.put(input,cancelled);return new Table(kind+"-table:m8.4-v1",handle,input.count);
    }
    public void read(Table table,Consumer consumer,BooleanSupplier cancelled)throws IOException{
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
    public static int compare(JavaEvidenceRecord a,JavaEvidenceRecord b){return switch(a.kind()){
        case "java-evidence-input"->{int c=a.text("role").compareTo(b.text("role"));if(c==0)c=CanonicalJson.write(a.fields().get("locator")).compareTo(CanonicalJson.write(b.fields().get("locator")));yield c!=0?c:JavaEvidenceRecord.handle(a.fields().get("content")).digest().compareTo(JavaEvidenceRecord.handle(b.fields().get("content")).digest());}
        case "java-resolution-slot"->{int c=a.text("space").compareTo(b.text("space"));yield c!=0?c:Long.compare(a.number("ordinal"),b.number("ordinal"));}
        case "java-output-row"->a.digest("key").compareTo(b.digest("key"));
        case "java-closure-requirement"->JavaEvidenceLedger.requirementKey(a).compareTo(JavaEvidenceLedger.requirementKey(b));
        case "java-closure-current-row"->a.digest("requirement").compareTo(b.digest("requirement"));
        case "java-source-map-row"->a.text("projectedPath").compareTo(b.text("projectedPath"));
        default->a.identity().compareTo(b.identity());};}
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
