package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OutputCensusTest {
    @TempDir Path root;
    static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static String b64(String s){return Base64.getEncoder().encodeToString(bytes(s));}
    static ContentHandle claimed(String s){return new ContentHandle(ContentDigest.sha256Utf8(s),bytes(s).length);}
    static Map<String,Object> row(String path,String kind,long size,String disposition){
        var row=new TreeMap<String,Object>();row.put("path",b64(path));row.put("kind",kind);row.put("size",size);row.put("mode",384);row.put("blocks",0);row.put("links",1);row.put("content",null);row.put("linkTarget",null);row.put("linkGroup",null);row.put("disposition",disposition);row.put("reason",disposition.equals("PRESENT")?"":"EXPLICIT_FIXTURE_GAP");return row;
    }
    static Map<String,Object> file(String path,String body){var row=row(path,"FILE",bytes(body).length,"PRESENT");row.put("content",claimed(body));return row;}
    class Fixture {
        final StreamingContentStore cas;final OutputCensus census;final OutputCensus.Scope scope;
        Fixture()throws Exception{this(new OutputCensus.Budget(65536,1024,4096));}
        Fixture(OutputCensus.Budget budget)throws Exception{cas=new StreamingContentStore(root.resolve("cas"));census=new OutputCensus(cas);scope=new OutputCensus.Scope("a".repeat(32),"b".repeat(32),budget);}
        ContentHandle put(byte[] raw)throws Exception{return cas.put(new ByteArrayInputStream(raw),()->false);}
        OutputCensus.Header header(List<Map<String,Object>> rows,OutputCensus.Counts counts,boolean closed)throws Exception{
            var body=new ByteArrayOutputStream();for(var row:rows){body.write(bytes(CanonicalJson.write(row)));body.write('\n');}return header(body.toByteArray(),counts,closed);
        }
        OutputCensus.Header header(byte[] body,OutputCensus.Counts counts,boolean closed)throws Exception{
            var manifest=put(body);var scopeHandle=put(bytes(CanonicalJson.write(Map.of("schema","guest-output-scope:m8.4-v1","nonce",scope.nonce(),"work",b64("/work/"+scope.attempt()),"budget",scope.budget()))));
            var header=new TreeMap<String,Object>();header.put("schema","guest-output-census:m8.4-v2");header.put("attempt",scope.attempt());header.put("nonce",scope.nonce());header.put("entries",counts.entries());header.put("logicalBytes",counts.logicalBytes());header.put("capturedBytes",counts.capturedBytes());header.put("pending",counts.pending());header.put("rejected",counts.rejected());header.put("inventoryClosed",true);header.put("captureClosed",closed);header.put("manifest",manifest);header.put("scope",scopeHandle);header.put("trust","VALIDATED_OBSERVATION");
            return census.header(put(bytes(CanonicalJson.write(header))),scope);
        }
    }
    @Test void completeByteCensusPreservesDirectoriesAndLinksButCannotPromoteProducerTrust()throws Exception{
        var f=new Fixture();var link=row("link","SYMLINK",11,"PRESENT");link.put("linkTarget",b64("/etc/shadow"));
        var header=f.header(List.of(file("a","abc"),row("empty","DIRECTORY",0,"PRESENT"),link),new OutputCensus.Counts(3,3,3,0,0),true);
        var calls=new AtomicInteger();var outcomes=new ArrayList<OutputCensus.RowOutcome>();
        var result=f.census.verify(header,h->{calls.incrementAndGet();return new ByteArrayInputStream(bytes("abc"));},outcomes::add,()->false);
        assertEquals(OutputCensus.Decision.CAPTURED,result.decision());assertEquals(EvidenceVerifier.Trust.PRODUCER_ASSERTION,result.effectiveTrust());
        assertEquals("VALIDATED_OBSERVATION",header.declaredTrust());assertEquals(3,outcomes.size());assertEquals(1,calls.get());assertEquals(1,result.byteObjects());assertTrue(f.cas.verify(claimed("abc")));
        assertEquals(List.of(OutputCensus.RowState.CAPTURED,OutputCensus.RowState.CLASSIFIED,OutputCensus.RowState.CLASSIFIED),outcomes.stream().map(OutputCensus.RowOutcome::state).toList());
    }
    @Test void unsafeAndDuplicatePathsRetainEveryRawRowWithoutHostMaterialization()throws Exception{
        var f=new Fixture();var outcomes=new ArrayList<OutputCensus.RowOutcome>();var calls=new AtomicInteger();
        var unsafe=f.header(List.of(file("../escape","abc")),new OutputCensus.Counts(1,3,3,0,0),true);
        assertEquals(OutputCensus.Decision.REJECTED,f.census.verify(unsafe,h->{calls.incrementAndGet();return InputStream.nullInputStream();},outcomes::add,()->false).decision());
        assertEquals(1,outcomes.size());assertTrue(outcomes.getFirst().path().isEmpty());assertEquals(0,calls.get());
        outcomes.clear();var duplicate=f.header(List.of(file("a","abc"),file("a","abc")),new OutputCensus.Counts(2,6,6,0,0),true);
        assertEquals(OutputCensus.Decision.REJECTED,f.census.verify(duplicate,h->new ByteArrayInputStream(bytes("abc")),outcomes::add,()->false).decision());
        assertEquals(2,outcomes.size());assertEquals("OUTPUT_PATH_ORDER_OR_DUPLICATE",outcomes.getLast().reason());assertTrue(outcomes.getLast().captured().isPresent());
    }
    @Test void rawCaseUnicodeAndWindowsNamesAreDataAndNotRenamed()throws Exception{
        var f=new Fixture();var rows=new ArrayList<Map<String,Object>>();
        for(String name:List.of("CASE","CON","case","e\u0301","x:y","\u00e9"))rows.add(file(name,"x"));
        rows.sort((a,b)->Arrays.compareUnsigned(Base64.getDecoder().decode((String)a.get("path")),Base64.getDecoder().decode((String)b.get("path"))));
        var header=f.header(rows,new OutputCensus.Counts(6,6,6,0,0),true);var outcomes=new ArrayList<OutputCensus.RowOutcome>();
        assertEquals(OutputCensus.Decision.CAPTURED,f.census.verify(header,h->new ByteArrayInputStream(bytes("x")),outcomes::add,()->false).decision());
        assertEquals(6,outcomes.stream().map(r->r.path().orElseThrow()).distinct().count());
        try(var files=Files.list(root)){assertEquals(List.of("cas"),files.map(p->p.getFileName().toString()).toList());}
    }
    @Test void unavailableObjectsStayOpenAndWrongBytesAreRetainedAsRejectedForensics()throws Exception{
        var f=new Fixture();var header=f.header(List.of(file("a","abc")),new OutputCensus.Counts(1,3,3,0,0),true);var outcomes=new ArrayList<OutputCensus.RowOutcome>();
        var missing=f.census.verify(header,h->{throw new IOException("missing fixture");},outcomes::add,()->false);
        assertEquals(OutputCensus.Decision.OPEN,missing.decision());assertEquals(1,missing.unavailableObjects());assertEquals(OutputCensus.RowState.PENDING,outcomes.getFirst().state());
        outcomes.clear();var bad=f.census.verify(header,h->new ByteArrayInputStream(bytes("xyz")),outcomes::add,()->false);
        assertEquals(OutputCensus.Decision.REJECTED,bad.decision());assertEquals(claimed("xyz"),outcomes.getFirst().captured().orElseThrow());assertTrue(f.cas.verify(claimed("xyz")));
    }
    @Test void sourceCountsEmptyExportAndSpecialEntriesHaveClosedDenominators()throws Exception{
        var f=new Fixture();var empty=f.header(List.of(),new OutputCensus.Counts(0,0,0,0,0),true);
        assertEquals(OutputCensus.Decision.CAPTURED,f.census.verify(empty,h->{throw new AssertionError("no file");},r->{throw new AssertionError("no row");},()->false).decision());
        var special=f.header(List.of(row("fifo","FIFO",0,"REJECTED"),row("socket","SOCKET",0,"REJECTED")),new OutputCensus.Counts(2,0,0,0,2),false);
        assertEquals(OutputCensus.Decision.OPEN,f.census.verify(special,h->{throw new AssertionError("special opened");},r->{},()->false).decision());
        var wrong=f.header(List.of(file("a","x")),new OutputCensus.Counts(2,1,1,0,0),true);
        assertEquals(OutputCensus.Decision.REJECTED,f.census.verify(wrong,h->new ByteArrayInputStream(bytes("x")),r->{},()->false).decision());
    }
    @Test void scopeUnknownFieldsAndNoncanonicalOrUnsealedRowsCannotBeAccepted()throws Exception{
        var f=new Fixture();var header=f.header(List.of(file("a","x")),new OutputCensus.Counts(1,1,1,0,0),true);
        assertThrows(IOException.class,()->f.census.header(header.source(),new OutputCensus.Scope("a".repeat(32),"c".repeat(32),f.scope.budget())));
        var unknown=file("a","x");unknown.put("injected",true);var bad=f.header(List.of(unknown),new OutputCensus.Counts(1,1,1,0,0),true);
        assertEquals(OutputCensus.Decision.REJECTED,f.census.verify(bad,h->{throw new AssertionError("invalid opened");},r->{},()->false).decision());
        var unsealed=f.header(bytes(CanonicalJson.write(file("a","x"))),new OutputCensus.Counts(1,1,1,0,0),true);
        assertEquals("OUTPUT_CENSUS_UNSEALED_ROW",assertThrows(IOException.class,()->f.census.verify(unsealed,h->{throw new AssertionError();},r->{},()->false)).getMessage());
    }
    @Test void overflowAndWorkerBudgetsNeverWrapOrReadUnboundedObjects()throws Exception{
        var f=new Fixture(new OutputCensus.Budget(1024,1024,1));var outcomes=new ArrayList<OutputCensus.RowOutcome>();var calls=new AtomicInteger();
        var over=f.header(List.of(file("a","x"),file("b","y")),new OutputCensus.Counts(2,2,2,0,0),true);
        assertEquals(OutputCensus.Decision.REJECTED,f.census.verify(over,h->{calls.incrementAndGet();return new ByteArrayInputStream(bytes("x"));},outcomes::add,()->false).decision());assertEquals(1,calls.get());assertEquals(2,outcomes.size());
        var huge=f.header(List.of(row("a","FILE",Long.MAX_VALUE,"PENDING"),row("b","FILE",Long.MAX_VALUE,"PENDING")),new OutputCensus.Counts(2,Long.MAX_VALUE,0,2,0),false);
        var result=f.census.verify(huge,h->{throw new AssertionError("pending opened");},r->{},()->false);assertEquals(OutputCensus.Decision.REJECTED,result.decision());assertTrue(result.observed().isEmpty());assertEquals("OUTPUT_CENSUS_COUNTER_OVERFLOW",result.reason());
        var longRow=f.header(List.of(file("a".repeat(2000),"x")),new OutputCensus.Counts(1,1,1,0,0),true);
        assertEquals("OUTPUT_CENSUS_ROW_WORKER_REQUIRED",assertThrows(IOException.class,()->f.census.verify(longRow,h->{throw new AssertionError();},r->{},()->false)).getMessage());
    }
    @Test void capturedHardlinkBytesDoNotValidateTheProducersClaimedLinkScope()throws Exception{
        var f=new Fixture();var link=file("a","x");link.put("kind","HARDLINK");link.put("links",2);link.put("linkGroup",b64("a"));
        var header=f.header(List.of(link),new OutputCensus.Counts(1,1,1,0,0),true);var outcomes=new ArrayList<OutputCensus.RowOutcome>();
        var result=f.census.verify(header,h->new ByteArrayInputStream(bytes("x")),outcomes::add,()->false);
        assertEquals(EvidenceVerifier.Trust.PRODUCER_ASSERTION,result.effectiveTrust());assertEquals("PRODUCER_LINK_SCOPE_UNVERIFIED",outcomes.getFirst().reason());
    }
    @Test void cancellationInterruptsManifestHashVerificationBeforeReadingTheWholeTable()throws Exception{
        var f=new Fixture();var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<1000;i++)rows.add(file(String.format(Locale.ROOT,"%05d",i),"x"));
        var header=f.header(rows,new OutputCensus.Counts(1000,1000,1000,0,0),true);long[] manifestReads={0};
        RepositoryContentStore observing=new RepositoryContentStore(){
            public ContentHandle put(InputStream input,java.util.function.BooleanSupplier c)throws IOException{return f.cas.put(input,c);}
            public InputStream range(ContentHandle h,long o,long n)throws IOException{return f.cas.range(h,o,n);}
            public InputStream open(ContentHandle h)throws IOException{
                var input=f.cas.open(h);if(!h.equals(header.manifest()))return input;
                return new FilterInputStream(input){
                    public int read()throws IOException{int b=in.read();if(b>=0)manifestReads[0]++;return b;}
                    public int read(byte[] b,int o,int n)throws IOException{int count=in.read(b,o,n);if(count>0)manifestReads[0]+=count;return count;}
                };
            }
            public boolean verify(ContentHandle h)throws IOException{
                java.security.MessageDigest digest;try{digest=java.security.MessageDigest.getInstance("SHA-256");}catch(java.security.GeneralSecurityException e){throw new IOException(e);}
                try(var input=open(h)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}
                return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest())).equals(h.digest());
            }
        };
        var parser=new OutputCensus(observing);
        assertEquals("OUTPUT_CENSUS_CANCELLED",assertThrows(IOException.class,()->parser.verify(header,h->{throw new AssertionError("cancelled object opened");},r->{throw new AssertionError("cancelled row published");},()->manifestReads[0]>=65536)).getMessage());
        assertTrue(manifestReads[0]<100000,"cancellation must stop hashing, not wait until the first decoded row");
    }
    @Test void hugeNumericExponentCannotExpandBeforeTypedValidationInASmallHeap()throws Exception{
        for(String mode:List.of("row","scope","row-aggregate","scope-aggregate")){
            String javaExecutable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            var process=new ProcessBuilder(javaExecutable,"-Xmx32m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),OutputCensusTest.class.getName(),root.resolve(mode).toString(),mode).redirectErrorStream(true).start();
            try{
                assertTrue(process.waitFor(15,java.util.concurrent.TimeUnit.SECONDS));
                String diagnostic=new String(process.getInputStream().readNBytes(4096),StandardCharsets.UTF_8);
                assertEquals(0,process.exitValue(),diagnostic);assertTrue(diagnostic.contains("PASS_NUMERIC_BUDGET"),diagnostic);
            }finally{if(process.isAlive())process.destroyForcibly();}
        }
    }
    public static void main(String[] args)throws Exception{
        var test=new OutputCensusTest();test.root=Path.of(args[0]);var f=test.new Fixture();
        String expansion="["+String.join(",",Collections.nCopies(6000,"1e4096"))+"]";
        if(args[1].startsWith("row")){
            var encoded=CanonicalJson.write(row("a","FILE",0,"PENDING"));
            encoded=args[1].equals("row")?encoded.replace("\"size\":0","\"size\":1e1000000000"):encoded.replace("\"reason\":\"EXPLICIT_FIXTURE_GAP\"","\"reason\":"+expansion);
            var malicious=bytes(encoded+"\n");
            var h=f.header(malicious,new OutputCensus.Counts(1,0,0,1,0),false);
            assertEquals(OutputCensus.Decision.REJECTED,f.census.verify(h,x->{throw new AssertionError();},r->{},()->false).decision());
        }else{
            var h=f.header(List.of(),new OutputCensus.Counts(0,0,0,0,0),true);byte[] raw;
            try(var input=f.cas.open(h.scopeRecord())){raw=input.readAllBytes();}
            var badScope=f.put(bytes(new String(raw,StandardCharsets.UTF_8).replace("\"maxCaptureBytes\":4096","\"maxCaptureBytes\":"+(args[1].equals("scope")?"1e1000000000":expansion))));
            Map<String,Object> object;
            try(var input=f.cas.open(h.source())){object=new TreeMap<>(com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),com.evolution.analysis.graph.GraphJson.Limits.defaults())));}
            object.put("scope",badScope);var source=f.put(bytes(CanonicalJson.write(object)));
            assertThrows(IOException.class,()->f.census.header(source,f.scope));
        }
        System.out.println("PASS_NUMERIC_BUDGET");
    }
}
