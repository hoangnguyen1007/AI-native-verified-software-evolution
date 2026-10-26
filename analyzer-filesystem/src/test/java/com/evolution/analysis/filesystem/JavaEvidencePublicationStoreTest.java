package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class JavaEvidencePublicationStoreTest {
    @Test void anUnavailableOldArtifactDoesNotBlockAFencedIndependentlyValidatedNewRevision()throws Exception{
        var delegate=new StreamingContentStore(root.resolve("recovery-cas"));
        var first=delegate.put(new ByteArrayInputStream(new byte[]{1,2}),()->false);
        var next=delegate.put(new ByteArrayInputStream(new byte[]{3,4}),()->false);
        var missing=new java.util.concurrent.atomic.AtomicBoolean();
        var content=new com.evolution.analysis.acquisition.streaming.RepositoryContentStore(){
            public com.evolution.analysis.acquisition.streaming.ContentHandle put(InputStream in,java.util.function.BooleanSupplier stop)throws IOException{return delegate.put(in,stop);}
            public InputStream open(com.evolution.analysis.acquisition.streaming.ContentHandle h)throws IOException{return delegate.open(h);}
            public InputStream range(com.evolution.analysis.acquisition.streaming.ContentHandle h,long offset,long length)throws IOException{return delegate.range(h,offset,length);}
            public boolean verify(com.evolution.analysis.acquisition.streaming.ContentHandle h)throws IOException{return !(missing.get()&&h.equals(first))&&delegate.verify(h);}
        };
        var store=new JavaEvidencePublicationStore(root.resolve("recovery-publications"),content);
        var key=ContentDigest.sha256Utf8("recovery registration");var initial=store.commit(key,Optional.empty(),Map.of("graph",first));missing.set(true);
        assertEquals("JAVA_PUBLICATION_CONTENT_UNAVAILABLE",assertThrows(IOException.class,()->store.load(key)).getMessage());
        assertEquals("JAVA_PUBLICATION_FENCE_MISMATCH",assertThrows(IOException.class,()->store.commit(key,Optional.empty(),Map.of("graph",next))).getMessage());
        var recovered=store.commit(key,Optional.of(initial.document().digest()),Map.of("graph",next));
        assertEquals(Optional.of(initial.document().digest()),recovered.parent());assertEquals(recovered,store.load(key).orElseThrow());
        assertTrue(delegate.verify(initial.document()),"Keep the old immutable publication and its unavailable-artifact reference in history");
    }
    @Test void abruptPlatformWorkerExitKeepsACompleteRevisionAndColdReplayReadsCommittedBytesOnly()throws Exception{
        var content=new StreamingContentStore(root.resolve("cas"));var key=ContentDigest.sha256Utf8("crash control");
        var first=content.put(new ByteArrayInputStream(new byte[]{1,2}),()->false);var store=new JavaEvidencePublicationStore(root.resolve("publications"),content);
        var initial=store.commit(key,Optional.empty(),Map.of("graph",first));
        for(var boundary:Map.of("before-cas",90,"before-pointer",91).entrySet()){
            assertEquals(boundary.getValue().intValue(),faultWorker(boundary.getKey()).exit());
            assertEquals(initial,new JavaEvidencePublicationStore(root.resolve("publications"),new StreamingContentStore(root.resolve("cas"))).load(key).orElseThrow());
        }
        assertEquals(92,faultWorker("after-pointer").exit());var committed=store.load(key).orElseThrow();
        assertEquals(Optional.of(initial.document().digest()),committed.parent());assertNotEquals(initial.document(),committed.document());
        var replay=faultWorker("inspect");assertEquals(0,replay.exit());assertEquals(committed.document().digest().value(),replay.output());
        assertTrue(content.verify(initial.document()));assertTrue(content.verify(first));
    }
    private record WorkerResult(int exit,String output){}
    private WorkerResult faultWorker(String mode)throws Exception{
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx64m","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),JavaPublicationCrashFixture.class.getName(),root.toString(),mode)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{
            assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS),"The trusted crash control exceeded its process budget");
            byte[] output=process.getInputStream().readNBytes(1025);assertTrue(output.length<=1024);
            return new WorkerResult(process.exitValue(),new String(output,java.nio.charset.StandardCharsets.UTF_8));
        }finally{if(process.isAlive())process.destroyForcibly();}
    }
    @Test void fabricatedPointerCannotMakeAnInvalidOrNoncanonicalArtifactCensusLoadable()throws Exception{
        var content=new StreamingContentStore(root.resolve("strict-cas"));var directory=root.resolve("strict-publications");
        var store=new JavaEvidencePublicationStore(directory,content);var id=ContentDigest.sha256Utf8("strict publication");
        var value=content.put(new ByteArrayInputStream("bytes".getBytes()),()->false);
        for(String shape:List.of("empty","bad-role","noncanonical")){
            var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-evidence-publication:m8.4-v1");fields.put("registration",id);fields.put("parent",null);fields.put("trust","PRODUCER_ASSERTION");
            fields.put("artifacts",shape.equals("empty")?Map.of():shape.equals("bad-role")?Map.of("Invalid Role",value):Map.of("view",value));
            String raw=com.evolution.analysis.contract.serialization.CanonicalJson.write(fields);if(shape.equals("noncanonical"))raw=" "+raw;
            var document=content.put(new ByteArrayInputStream(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
            Files.writeString(directory.resolve(id.value().substring(7)+".json"),com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("document",document)));
            assertThrows(IOException.class,()->store.load(id),shape);
        }
    }
    @Test void cancellationAfterStagingCannotReplaceThePreviousCommittedRevision()throws Exception{
        var delegate=new StreamingContentStore(root.resolve("cancel-cas"));var cancelled=new java.util.concurrent.atomic.AtomicBoolean();var armed=new java.util.concurrent.atomic.AtomicBoolean();
        var content=new com.evolution.analysis.acquisition.streaming.RepositoryContentStore(){
            public com.evolution.analysis.acquisition.streaming.ContentHandle put(InputStream input,java.util.function.BooleanSupplier stop)throws IOException{
                var result=delegate.put(input,stop);if(armed.get())cancelled.set(true);return result;
            }
            public InputStream open(com.evolution.analysis.acquisition.streaming.ContentHandle h)throws IOException{return delegate.open(h);}
            public InputStream range(com.evolution.analysis.acquisition.streaming.ContentHandle h,long offset,long length)throws IOException{return delegate.range(h,offset,length);}
            public boolean verify(com.evolution.analysis.acquisition.streaming.ContentHandle h)throws IOException{return delegate.verify(h);}
        };
        var first=delegate.put(new ByteArrayInputStream("first".getBytes()),()->false);var next=delegate.put(new ByteArrayInputStream("next".getBytes()),()->false);
        var id=ContentDigest.sha256Utf8("cancelled publication");var store=new JavaEvidencePublicationStore(root.resolve("cancel-publications"),content);
        var initial=store.commit(id,Optional.empty(),Map.of("view",first));armed.set(true);
        var failure=assertThrows(IOException.class,()->store.commit(id,Optional.of(initial.document().digest()),Map.of("view",next),cancelled::get));
        assertEquals("JAVA_PUBLICATION_CANCELLED",failure.getMessage());
        assertEquals(initial,new JavaEvidencePublicationStore(root.resolve("cancel-publications"),delegate).load(id).orElseThrow());
        assertTrue(delegate.verify(first));assertTrue(delegate.verify(next));
    }
    @TempDir Path root;
    @Test void replayAndFencingKeepPreviousRevisionWhenNextEvidenceCannotBeVerified()throws Exception{
        var content=new StreamingContentStore(root.resolve("cas"));var id=ContentDigest.sha256Utf8("registered-evidence");var first=content.put(new ByteArrayInputStream(new byte[]{1,2}),()->false);var second=content.put(new ByteArrayInputStream(new byte[]{3,4}),()->false);
        var publications=new JavaEvidencePublicationStore(root.resolve("publications"),content);var initial=publications.commit(id,Optional.empty(),Map.of("view",first));
        var reopened=new JavaEvidencePublicationStore(root.resolve("publications"),new StreamingContentStore(root.resolve("cas")));assertEquals(initial,reopened.load(id).orElseThrow());
        assertThrows(IOException.class,()->reopened.commit(id,Optional.empty(),Map.of("view",second)));assertEquals(initial,reopened.load(id).orElseThrow());
        var changed=reopened.commit(id,Optional.of(initial.document().digest()),Map.of("view",second));assertEquals(Optional.of(initial.document().digest()),changed.parent());assertTrue(content.verify(initial.document()));
        var absent=new com.evolution.analysis.acquisition.streaming.ContentHandle(ContentDigest.sha256Utf8("absent"),6);
        assertThrows(IOException.class,()->reopened.commit(id,Optional.of(changed.document().digest()),Map.of("view",absent)));assertEquals(changed,reopened.load(id).orElseThrow());
        assertThrows(IOException.class,()->reopened.commit(id,Optional.of(changed.document().digest()),Map.of("a".repeat(2_000_001),first)));assertEquals(changed,reopened.load(id).orElseThrow());
    }
}
