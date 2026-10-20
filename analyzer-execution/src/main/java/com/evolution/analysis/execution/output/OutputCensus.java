package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Streaming verification of captured guest metadata and opaque objects. No host path materialization,
 * archive/class loading, link following, or promotion of producer metadata to semantic truth.
 */
public final class OutputCensus {
    public enum Decision { CAPTURED,OPEN,REJECTED }
    public enum RowState { CAPTURED,CLASSIFIED,PENDING,REJECTED }
    public record Budget(int maxRowBytes,long maxObjectBytes,long maxCaptureBytes){
        public Budget{if(maxRowBytes<1024||maxRowBytes>1048576||maxObjectBytes<0||maxCaptureBytes<0)throw new IllegalArgumentException("INVALID_CENSUS_WORKER");}
    }
    public record Scope(String attempt,String nonce,Budget budget){
        public Scope{if(attempt==null||!attempt.matches("[a-f0-9]{32}")||nonce==null||!nonce.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("INVALID_CENSUS_SCOPE");Objects.requireNonNull(budget);}
    }
    public record Counts(long entries,long logicalBytes,long capturedBytes,long pending,long rejected){
        public Counts{if(entries<0||logicalBytes<0||capturedBytes<0||pending<0||rejected<0)throw new IllegalArgumentException("NEGATIVE_CENSUS_COUNTER");}
    }
    public record Header(ContentHandle source,Scope scope,Counts declared,boolean inventoryClosed,boolean captureClosed,
            ContentHandle manifest,ContentHandle scopeRecord,String declaredTrust){
        public Header{Objects.requireNonNull(source);Objects.requireNonNull(scope);Objects.requireNonNull(declared);Objects.requireNonNull(manifest);Objects.requireNonNull(scopeRecord);ExecutionGraph.text(declaredTrust);}
        public EvidenceVerifier.Trust effectiveTrust(){return EvidenceVerifier.Trust.PRODUCER_ASSERTION;}
    }
    public record RowOutcome(long ordinal,ContentDigest rawRow,Optional<LogicalPath> path,RowState state,Optional<ContentHandle> captured,String reason){
        public RowOutcome{if(ordinal<1)throw new IllegalArgumentException("INVALID_CENSUS_ORDINAL");Objects.requireNonNull(rawRow);Objects.requireNonNull(path);Objects.requireNonNull(state);Objects.requireNonNull(captured);Objects.requireNonNull(reason);}
    }
    public record Result(Header header,Decision decision,Optional<Counts> observed,long byteObjects,long unavailableObjects,String reason){
        public Result{Objects.requireNonNull(header);Objects.requireNonNull(decision);Objects.requireNonNull(observed);Objects.requireNonNull(reason);}
        public EvidenceVerifier.Trust effectiveTrust(){return EvidenceVerifier.Trust.PRODUCER_ASSERTION;}
    }
    @FunctionalInterface public interface ObjectSource{InputStream open(ContentHandle expected)throws IOException;}
    @FunctionalInterface public interface Recorder{void append(RowOutcome row)throws IOException;}
    private final RepositoryContentStore content;
    public OutputCensus(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}

    public Header header(ContentHandle source,Scope expected)throws IOException{
        try{
            var object=object(source,8192);
            fields(object,Set.of("schema","attempt","nonce","entries","logicalBytes","capturedBytes","pending","rejected","inventoryClosed","captureClosed","manifest","scope","trust"));
            if(!"guest-output-census:m8.4-v2".equals(object.get("schema"))||!expected.attempt().equals(object.get("attempt"))||!expected.nonce().equals(object.get("nonce")))throw new IllegalArgumentException("CENSUS_SCOPE_MISMATCH");
            var scopeHandle=handle(object.get("scope"));
            var scopeObject=object(scopeHandle,65536);
            fields(scopeObject,Set.of("schema","nonce","work","budget"));
            var budget=GraphJson.object(scopeObject.get("budget"));fields(budget,Set.of("maxRowBytes","maxObjectBytes","maxCaptureBytes"));
            var actualBudget=new Budget(Math.toIntExact(number(budget.get("maxRowBytes"))),number(budget.get("maxObjectBytes")),number(budget.get("maxCaptureBytes")));
            String expectedWork=Base64.getEncoder().encodeToString(("/work/"+expected.attempt()).getBytes(StandardCharsets.UTF_8));
            if(!"guest-output-scope:m8.4-v1".equals(GraphJson.string(scopeObject.get("schema")))||!expected.nonce().equals(GraphJson.string(scopeObject.get("nonce")))||!expectedWork.equals(GraphJson.string(scopeObject.get("work")))||!expected.budget().equals(actualBudget))throw new IllegalArgumentException("CENSUS_SCOPE_MISMATCH");
            var counts=new Counts(number(object.get("entries")),number(object.get("logicalBytes")),number(object.get("capturedBytes")),number(object.get("pending")),number(object.get("rejected")));
            boolean inventory=bool(object.get("inventoryClosed")),capture=bool(object.get("captureClosed"));
            if(Math.addExact(counts.pending(),counts.rejected())>counts.entries()||counts.capturedBytes()>counts.logicalBytes()||capture&&(!inventory||counts.pending()!=0||counts.rejected()!=0))throw new IllegalArgumentException("CENSUS_DECLARATION_CONFLICT");
            return new Header(source,expected,counts,inventory,capture,handle(object.get("manifest")),scopeHandle,GraphJson.string(object.get("trust")));
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("OUTPUT_CENSUS_HEADER_INVALID",invalid);}
    }
    private Map<String,Object> object(ContentHandle handle,int maxBytes)throws IOException{
        if(handle.size()>maxBytes)throw new IOException("OUTPUT_CENSUS_WORKER_REQUIRED");
        if(!content.verify(handle))throw new IOException("OUTPUT_CENSUS_CONTENT_UNAVAILABLE");
        try(var input=content.open(handle)){
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            return GraphJson.object(GraphJson.read(new InputStreamReader(input,decoder),new GraphJson.Limits(maxBytes,24,maxBytes)));
        }
    }
    private static void fields(Map<String,Object> object,Set<String> fields){if(!object.keySet().equals(fields))throw new IllegalArgumentException("CENSUS_FIELDS_INVALID");}
    private static long number(Object value){long result=((BigDecimal)value).longValueExact();if(result<0)throw new IllegalArgumentException("NEGATIVE_CENSUS_COUNTER");return result;}
    private static boolean bool(Object value){if(!(value instanceof Boolean b))throw new IllegalArgumentException("BOOLEAN_REQUIRED");return b;}
    private static ContentHandle handle(Object value){var object=GraphJson.object(value);fields(object,Set.of("digest","size"));return new ContentHandle(new ContentDigest(GraphJson.string(object.get("digest"))),number(object.get("size")));}
    private static byte[] rawBase64(Object value){String text=GraphJson.string(value);byte[] raw=Base64.getDecoder().decode(text);if(!Base64.getEncoder().encodeToString(raw).equals(text))throw new IllegalArgumentException("NONCANONICAL_RAW_NAME");return raw;}
    private record Row(LogicalPath path,OutputImporter.Kind kind,long size,long mode,long blocks,long links,Optional<ContentHandle> content,Optional<byte[]> link,Optional<LogicalPath> group,String disposition,String reason){}
    private static Row row(byte[] bytes){
        String text;
        try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException malformed){throw new IllegalArgumentException("CENSUS_UTF8_INVALID",malformed);}
        var object=GraphJson.object(GraphJson.read(text,new GraphJson.Limits(bytes.length,16,Math.max(bytes.length,1))));
        fields(object,Set.of("path","kind","size","mode","blocks","links","content","linkTarget","linkGroup","disposition","reason"));
        var path=LogicalPath.of(rawBase64(object.get("path")));var kind=OutputImporter.Kind.valueOf(GraphJson.string(object.get("kind")));
        long size=number(object.get("size")),mode=number(object.get("mode")),blocks=number(object.get("blocks")),links=number(object.get("links"));
        if(mode>4095)throw new IllegalArgumentException("CENSUS_MODE_INVALID");
        var content=object.get("content")==null?Optional.<ContentHandle>empty():Optional.of(handle(object.get("content")));
        var target=object.get("linkTarget")==null?Optional.<byte[]>empty():Optional.of(rawBase64(object.get("linkTarget")));
        var group=object.get("linkGroup")==null?Optional.<LogicalPath>empty():Optional.of(LogicalPath.of(rawBase64(object.get("linkGroup"))));
        String disposition=GraphJson.string(object.get("disposition")),reason=GraphJson.string(object.get("reason"));
        if(!Set.of("PRESENT","PENDING","REJECTED").contains(disposition)||!disposition.equals("PRESENT")&&reason.isEmpty())throw new IllegalArgumentException("CENSUS_DISPOSITION_INVALID");
        if(kind==OutputImporter.Kind.SYMLINK){if(target.isEmpty()||target.orElseThrow().length==0||content.isPresent()||group.isPresent())throw new IllegalArgumentException("CENSUS_LINK_INVALID");}
        else if(target.isPresent())throw new IllegalArgumentException("CENSUS_LINK_INVALID");
        if(kind==OutputImporter.Kind.FILE||kind==OutputImporter.Kind.HARDLINK){
            if(disposition.equals("PRESENT")&&content.isEmpty()||!disposition.equals("PRESENT")&&content.isPresent()||content.isPresent()&&content.orElseThrow().size()!=size)throw new IllegalArgumentException("CENSUS_FILE_CONTENT_INVALID");
            if(kind==OutputImporter.Kind.FILE&&(links>1||disposition.equals("PRESENT")&&links!=1||group.isPresent())||kind==OutputImporter.Kind.HARDLINK&&links<2)throw new IllegalArgumentException("CENSUS_LINK_COUNT_INVALID");
            if(kind==OutputImporter.Kind.HARDLINK&&disposition.equals("PRESENT")&&(group.isEmpty()||group.orElseThrow().compareTo(path)>0))throw new IllegalArgumentException("CENSUS_LINK_GROUP_INVALID");
        }else if(content.isPresent()||group.isPresent())throw new IllegalArgumentException("CENSUS_UNEXPECTED_CONTENT");
        if(!Set.of(OutputImporter.Kind.FILE,OutputImporter.Kind.HARDLINK,OutputImporter.Kind.DIRECTORY,OutputImporter.Kind.SYMLINK).contains(kind)&&disposition.equals("PRESENT"))throw new IllegalArgumentException("CENSUS_SPECIAL_CONTENT_INVALID");
        // Canonicalize only after every value has its bounded registered type. A small encoded array
        // of decimal exponents in a string slot must never expand into a large temporary JSON string.
        if(!Arrays.equals(bytes,CanonicalJson.write(object).getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException("CENSUS_ROW_NONCANONICAL");
        return new Row(path,kind,size,mode,blocks,links,content,target,group,disposition,reason);
    }
    private static byte[] line(InputStream input,int maximum)throws IOException{
        var bytes=new ByteArrayOutputStream();int b;
        while((b=input.read())!=-1&&b!='\n'){if(bytes.size()>=maximum)throw new IOException("OUTPUT_CENSUS_ROW_WORKER_REQUIRED");bytes.write(b);}
        if(b==-1){if(bytes.size()!=0)throw new IOException("OUTPUT_CENSUS_UNSEALED_ROW");return null;}
        return bytes.toByteArray();
    }
    private boolean verifyManifest(ContentHandle handle,BooleanSupplier cancelled)throws IOException{
        java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException(impossible);}
        long size=0;
        if(cancelled.getAsBoolean())throw new IOException("OUTPUT_CENSUS_CANCELLED");
        try(var input=content.open(handle)){
            byte[] buffer=new byte[65536];
            while(true){
                if(cancelled.getAsBoolean())throw new IOException("OUTPUT_CENSUS_CANCELLED");
                int n=input.read(buffer);if(n==-1)break;if(n==0)continue;
                try{size=Math.addExact(size,n);}catch(ArithmeticException exceeded){throw new IOException("OUTPUT_CENSUS_COUNTER_OVERFLOW");}
                if(size>handle.size())return false;digest.update(buffer,0,n);
            }
        }
        return size==handle.size()&&new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest())).equals(handle.digest());
    }

    /** Header and manifest must already be captured in CAS. Imported objects are addressed only by digest.
     * One recorder outcome per row preserves the denominator even when that row is malformed/unavailable.
     */
    public Result verify(Header header,ObjectSource source,Recorder recorder,BooleanSupplier cancelled)throws IOException{
        Objects.requireNonNull(header);Objects.requireNonNull(source);Objects.requireNonNull(recorder);Objects.requireNonNull(cancelled);
        if(!header(header.source(),header.scope()).equals(header))throw new IOException("OUTPUT_CENSUS_HEADER_CONFLICT");
        if(!verifyManifest(header.manifest(),cancelled))throw new IOException("OUTPUT_CENSUS_CONTENT_UNAVAILABLE");
        long entries=0,logical=0,declaredCaptured=0,pending=0,rejected=0,byteObjects=0,unavailable=0;
        boolean invalid=false,overflow=false;LogicalPath previous=null;long[] readBytes={0};
        var importer=new OutputImporter(content);var context=header.scopeRecord().digest();
        try(var input=new BufferedInputStream(content.open(header.manifest()),65536)){
            byte[] bytes;
            while((bytes=line(input,header.scope().budget().maxRowBytes()))!=null){
                if(cancelled.getAsBoolean())throw new IOException("OUTPUT_CENSUS_CANCELLED");
                entries=Math.addExact(entries,1);var raw=ContentDigest.sha256(bytes);Row row;
                try{row=row(bytes);}catch(IllegalArgumentException|ArithmeticException|ClassCastException bad){
                    invalid=true;recorder.append(new RowOutcome(entries,raw,Optional.empty(),RowState.REJECTED,Optional.empty(),"OUTPUT_CENSUS_ROW_INVALID"));continue;
                }
                boolean badOrder=previous!=null&&previous.compareTo(row.path())>=0;
                if(badOrder)invalid=true;previous=row.path();
                try{
                    if(row.kind()==OutputImporter.Kind.FILE||row.kind()==OutputImporter.Kind.HARDLINK)logical=Math.addExact(logical,row.size());
                    if(row.content().isPresent())declaredCaptured=Math.addExact(declaredCaptured,row.content().orElseThrow().size());
                    if(row.disposition().equals("PENDING"))pending=Math.addExact(pending,1);
                    if(row.disposition().equals("REJECTED"))rejected=Math.addExact(rejected,1);
                }catch(ArithmeticException exceeded){overflow=true;invalid=true;}
                RowState state;Optional<ContentHandle> captured=Optional.empty();String reason=row.reason();
                if(row.content().isPresent()){
                    long remaining=Math.max(0,header.scope().budget().maxCaptureBytes()-readBytes[0]);
                    var expected=row.content().orElseThrow();
                    if(readBytes[0]>header.scope().budget().maxCaptureBytes()){
                        state=RowState.PENDING;reason="OUTPUT_CAPTURE_BUDGET_EXHAUSTED";unavailable=Math.addExact(unavailable,1);
                    }else{
                        var entry=new OutputImporter.Entry(row.path(),OutputImporter.Kind.FILE,Optional.of(expected),"opaque-output-object",context,raw);
                        var imported=importer.capture(entry,e->{
                            var original=source.open(expected);
                            return new FilterInputStream(original){
                                @Override public int read()throws IOException{int b=in.read();if(b>=0)readBytes[0]=Math.addExact(readBytes[0],1);return b;}
                                @Override public int read(byte[] buffer,int offset,int length)throws IOException{int n=in.read(buffer,offset,length);if(n>0)readBytes[0]=Math.addExact(readBytes[0],n);return n;}
                            };
                        },context,Math.min(remaining,header.scope().budget().maxObjectBytes()),cancelled);
                        captured=imported.captured();reason=imported.reason();
                        state=switch(imported.disposition()){case CAPTURED->RowState.CAPTURED;case REJECTED->RowState.REJECTED;case PENDING->RowState.PENDING;};
                        if(state==RowState.CAPTURED){byteObjects=Math.addExact(byteObjects,1);if(row.kind()==OutputImporter.Kind.HARDLINK)reason="PRODUCER_LINK_SCOPE_UNVERIFIED";}
                        else{unavailable=Math.addExact(unavailable,1);if(state==RowState.REJECTED)invalid=true;}
                    }
                }else state=switch(row.disposition()){case "PENDING"->RowState.PENDING;case "REJECTED"->RowState.REJECTED;default->RowState.CLASSIFIED;};
                recorder.append(new RowOutcome(entries,raw,Optional.of(row.path()),badOrder?RowState.REJECTED:state,captured,badOrder?"OUTPUT_PATH_ORDER_OR_DUPLICATE":reason));
            }
        }catch(ArithmeticException exceeded){throw new IOException("OUTPUT_CENSUS_COUNTER_OVERFLOW",exceeded);}
        Optional<Counts> observed=overflow?Optional.empty():Optional.of(new Counts(entries,logical,declaredCaptured,pending,rejected));
        if(overflow)return new Result(header,Decision.REJECTED,observed,byteObjects,unavailable,"OUTPUT_CENSUS_COUNTER_OVERFLOW");
        if(!observed.orElseThrow().equals(header.declared()))invalid=true;
        if(readBytes[0]>header.scope().budget().maxCaptureBytes()||declaredCaptured>header.scope().budget().maxCaptureBytes())invalid=true;
        boolean open=!header.inventoryClosed()||!header.captureClosed()||pending!=0||rejected!=0||unavailable!=0;
        return new Result(header,invalid?Decision.REJECTED:open?Decision.OPEN:Decision.CAPTURED,observed,byteObjects,unavailable,invalid?"OUTPUT_CENSUS_VALIDATION_FAILED":open?"OUTPUT_CENSUS_INCOMPLETE":"");
    }
}
