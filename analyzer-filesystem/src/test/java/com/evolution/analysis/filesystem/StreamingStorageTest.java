package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StreamingStorageTest {
    @TempDir Path temp;
    @Test void casIsAtomicVerifiedAndRangeAddressable()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var handle=cas.put(new ByteArrayInputStream("0123456789".getBytes()),()->false);
        assertTrue(cas.verify(handle));assertEquals(handle,cas.put(new ByteArrayInputStream("0123456789".getBytes()),()->false));
        try(var input=cas.range(handle,3,4)){assertEquals("3456",new String(input.readAllBytes()));}
        assertThrows(IllegalArgumentException.class,()->cas.range(handle,Long.MAX_VALUE,1));
        assertThrows(java.util.concurrent.CancellationException.class,()->cas.put(new ByteArrayInputStream(new byte[]{1}),()->true));
        try(var paths=Files.list(temp.resolve("cas/staging"))){assertEquals(0,paths.count());}
        var broken=new InputStream(){int calls;public int read()throws IOException{if(calls++>3)throw new IOException();return 7;}};
        assertThrows(IOException.class,()->cas.put(broken,()->false));
    }
    @Test void journalRebuildsCorruptDerivedIndexAndChecksCompareAndSet()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));Path metadata=temp.resolve("metadata");
        try(var store=new SqliteInventoryStore(metadata,cas)){
            var job=store.create("job",ContentDigest.sha256Utf8("request"),Optional.empty());assertEquals(0,job.revision());
            store.transition("job",0,JobJournal.State.RUNNING,"",Optional.empty());
            assertThrows(IOException.class,()->store.transition("job",0,JobJournal.State.COMPLETE,"",Optional.empty()));
            assertThrows(IOException.class,()->store.create("job",ContentDigest.sha256Utf8("foreign"),Optional.empty()));
        }
        Files.writeString(metadata.resolve("index.sqlite"),"corruption");
        try(var store=new SqliteInventoryStore(metadata,cas)){
            assertEquals(JobJournal.State.RUNNING,store.get("job").orElseThrow().state());assertEquals(1,store.history(0,10).size());
        }
    }
    @Test void diskPressureRetainsRecoverableStateAndNoCompleteMarker()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("file"),"data");
        var normal=new StreamingContentStore(temp.resolve("cas"));
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),normal)){
            var noSpace=new StreamingContentStore(temp.resolve("cas"),Long.MAX_VALUE);
            var request=new StreamingAcquisitionRequest(com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:disk"),Optional.empty(),true,List.of());
            var receipt=new StreamingRepositoryAcquirer(noSpace,store).acquire(source,"disk",request,()->false);
            assertEquals(JobJournal.State.WAITING_FOR_RESOURCES,receipt.state());assertTrue(store.manifest("disk").isEmpty());
            assertEquals(JobJournal.State.COMPLETE,new StreamingRepositoryAcquirer(normal,store).acquire(source,"disk",request,()->false).state());
        }
    }
    @Test void abruptProcessDeathBetweenLogAndIndexCommitReplaysExactlyOnce()throws Exception{
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        var process=new ProcessBuilder(executable,"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),JournalCrashProbe.class.getName(),temp.toString()).redirectErrorStream(true).start();
        assertTrue(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS));String output=new String(process.getInputStream().readNBytes(4096));assertEquals(71,process.exitValue(),output);
        var cas=new StreamingContentStore(temp.resolve("cas"));try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            assertTrue(store.manifest("crash").isEmpty());var entries=store.page("crash",10,Optional.empty()).entries();assertEquals(1,entries.size());assertTrue(cas.verify(entries.getFirst().content().orElseThrow()));
            assertThrows(IOException.class,()->store.seal("crash",com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:crash"),ContentDigest.sha256Utf8("request")));
        }
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){assertEquals(1,store.page("crash",10,Optional.empty()).entries().size());}
    }
    @Test void concurrentReadOnlyViewSeesCommittedFrontierAndCannotWrite()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));try(var writer=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            writer.create("job",ContentDigest.sha256Utf8("request"),Optional.empty());
            try(var reader=new SqliteInventoryStore(temp.resolve("metadata"),cas,true)){
                assertEquals(JobJournal.State.PENDING,reader.get("job").orElseThrow().state());assertThrows(IOException.class,()->reader.create("other",ContentDigest.sha256Utf8("request"),Optional.empty()));
                writer.transition("job",0,JobJournal.State.RUNNING,"",Optional.empty());assertEquals(JobJournal.State.RUNNING,reader.get("job").orElseThrow().state());
            }
        }
    }
    @Test void aCorruptSealedBlobCannotBeReportedAsCurrentlyAcquired()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("file"),"original");var cas=new StreamingContentStore(temp.resolve("cas"));
        var request=new StreamingAcquisitionRequest(com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:corrupt"),Optional.empty(),true,List.of());
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            assertEquals(JobJournal.State.COMPLETE,new StreamingRepositoryAcquirer(cas,store).acquire(source,"sealed",request,()->false).state());
            String digest=store.page("sealed",10,Optional.empty()).entries().getFirst().content().orElseThrow().digest().value().substring(7);Files.writeString(temp.resolve("cas/sha256/"+digest.substring(0,2)+"/"+digest.substring(2)),"corrupted");
            var replay=new StreamingRepositoryAcquirer(cas,store).acquire(source,"sealed",request,()->false);assertEquals(JobJournal.State.WAITING_FOR_INPUT,replay.state());assertTrue(replay.manifest().isEmpty());assertEquals("SEALED_CONTENT_UNAVAILABLE",replay.reason());
        }
    }
}
