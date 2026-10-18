package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.source.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StreamingRepositoryAcquirerTest {
    @TempDir Path temp;
    final RepositoryIdentity repo=RepositoryIdentity.fromCanonicalCoordinate("urn:test:streaming");
    StreamingAcquisitionRequest request(){return new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of());}
    @Test void fullInventorySurvivesRestartAndKeepsV1GoldenIdentity()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));
        for(String path:List.of("schema.proto","main.py","nested/pom.xml","src/test/Test.java","unknown.weird")){
            Path file=source.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,path);
        }
        var cas=new StreamingContentStore(temp.resolve("cas"));InventoryManifest manifest;
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var receipt=new StreamingRepositoryAcquirer(cas,store).acquire(source,"full",request(),()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());manifest=receipt.manifest().orElseThrow();assertEquals(5,manifest.files());
            var old=new FilesystemRepositoryAcquirer().acquire(source,new RepositoryAcquisitionRequest(repo,Optional.empty(),true,"pom.xml",new RepositoryAcquisitionPolicy(100,100,10000,100000,64,List.of())));
            assertEquals(old.snapshot().orElseThrow().identity(),manifest.compatibleV1Snapshot().orElseThrow());
            var view=new StreamingRepositoryAcquirer(cas,store).compatibility("full",old.request(),e->true);
            assertEquals(old.snapshot().orElseThrow(),view.snapshot().orElseThrow());
            assertThrows(IOException.class,()->store.entries("full",List.of(new SqliteInventoryStore.Observation(store.page("full",1,Optional.empty()).entries().getFirst(),""))));
        }
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            assertEquals(manifest,store.manifest("full").orElseThrow());assertEquals(JobJournal.State.COMPLETE,store.get("full").orElseThrow().state());
            var first=store.page("full",2,Optional.empty());assertTrue(first.continuation().isPresent());
            assertThrows(IllegalArgumentException.class,()->store.page("other",2,first.continuation()));
            int count=0;Optional<String> cursor=Optional.empty();do{var page=store.page("full",2,cursor);count+=page.entries().size();cursor=page.continuation();}while(cursor.isPresent());assertEquals(manifest.entries(),count);
        }
    }
    @Test void cancellationRetainsFrontierAndResumeDoesNotDuplicatePaths()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));for(int i=0;i<30;i++)Files.writeString(source.resolve("file"+i),"bytes"+i);
        var cas=new StreamingContentStore(temp.resolve("cas"));AtomicInteger polls=new AtomicInteger();
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var receipt=new StreamingRepositoryAcquirer(cas,store).acquire(source,"resume",request(),()->polls.incrementAndGet()>20);
            assertEquals(JobJournal.State.WAITING_FOR_INPUT,receipt.state());assertTrue(store.manifest("resume").isEmpty());
        }
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var receipt=new StreamingRepositoryAcquirer(cas,store).acquire(source,"resume",request(),()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());assertEquals(30,receipt.manifest().orElseThrow().files());
        }
    }
    @Test void actualDirectoryAndSingleFileCrossLegacyAdmissionCeilings()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));
        for(int i=0;i<20_001;i++)Files.createFile(source.resolve("file"+i));
        try(var file=java.nio.channels.FileChannel.open(source.resolve("giant.bin"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            file.position(128_000_000L);file.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        var cas=new StreamingContentStore(temp.resolve("cas"));
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var acquirer=new StreamingRepositoryAcquirer(cas,store);var receipt=acquirer.acquire(source,"large-directory",request(),()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());var manifest=receipt.manifest().orElseThrow();
            assertEquals(20_002,manifest.files());assertEquals(128_000_001L,manifest.bytes());
            long paths=0;Optional<String> cursor=Optional.empty();do{var page=store.page("large-directory",1000,cursor);paths+=page.entries().size();cursor=page.continuation();}while(cursor.isPresent());assertEquals(20_002,paths);
            var legacy=new RepositoryAcquisitionRequest(repo,Optional.empty(),true,"pom.xml",new RepositoryAcquisitionPolicy(10_000,10_000,10_000_000,128_000_000,64,List.of()));
            assertEquals("COMPATIBILITY_WORKER_REQUIRED",assertThrows(IOException.class,()->acquirer.compatibility("large-directory",legacy,e->true)).getMessage());
            assertEquals(manifest,store.manifest("large-directory").orElseThrow());
        }
    }
    @Test void giantDepthAndExplicitExclusionsHaveAClosedLedger()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source")),deep=source;for(int i=0;i<300;i++)deep=Files.createDirectory(deep.resolve("d"));Files.writeString(deep.resolve("end.rs"),"fn main() {}");
        Files.writeString(source.resolve("secret.input"),"excluded");var cas=new StreamingContentStore(temp.resolve("cas"));
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var request=new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of("secret.input"));
            var receipt=new StreamingRepositoryAcquirer(cas,store).acquire(source,"deep",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());assertEquals(1,receipt.manifest().orElseThrow().files());assertEquals(1,receipt.manifest().orElseThrow().excluded());
        }
    }
    @Test void lfsPointerKeepsAnOpenObligationAndCannotSeal()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("image"),"version https://git-lfs.github.com/spec/v1\noid sha256:"+"a".repeat(64)+"\nsize 2\n");
        var cas=new StreamingContentStore(temp.resolve("cas"));try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var receipt=new StreamingRepositoryAcquirer(cas,store).acquire(source,"lfs",request(),()->false);
            assertEquals(JobJournal.State.WAITING_FOR_INPUT,receipt.state());assertEquals("INVENTORY_FRONTIER_OPEN",receipt.reason());
            assertEquals("LFS_OBJECT_REQUIRED",store.page("lfs",10,Optional.empty()).entries().getFirst().problem().orElseThrow());assertTrue(receipt.manifest().isEmpty());
        }
    }
    @Test void rawNamesPreserveCaseUnicodeAndNonUtf8WithoutHostMaterialization(){
        assertNotEquals(LogicalPath.utf8("A"),LogicalPath.utf8("a"));assertNotEquals(LogicalPath.utf8("é"),LogicalPath.utf8("e\u0301"));
        assertTrue(LogicalPath.of(new byte[]{(byte)255}).utf8().isEmpty());assertEquals("CON:x",LogicalPath.utf8("CON:x").utf8().orElseThrow());
        for(String invalid:List.of("../a","a/../b","/a","a//b","a/.","a\0b"))assertThrows(IllegalArgumentException.class,()->LogicalPath.utf8(invalid));
    }
    @Test void v1IdentityUsesUtf16CanonicalOrderingRatherThanRawUtf8Order()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("\uE000"),"bmp");Files.writeString(source.resolve("\uD800\uDC00"),"supplementary");
        var cas=new StreamingContentStore(temp.resolve("cas"));try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var result=new StreamingRepositoryAcquirer(cas,store).acquire(source,"unicode",request(),()->false);assertEquals(JobJournal.State.COMPLETE,result.state(),result.reason());
            var legacy=RepositorySnapshot.create(repo,Optional.empty(),true,List.of(new SnapshotFile("\uE000",ContentDigest.sha256Utf8("bmp")),new SnapshotFile("\uD800\uDC00",ContentDigest.sha256Utf8("supplementary"))),List.of());
            assertEquals(legacy.identity(),result.manifest().orElseThrow().compatibleV1Snapshot().orElseThrow());
        }
    }
    @Test void mutationAfterCaptureWithholdsTheRootInsteadOfMixingSourceRevisions()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));for(int i=0;i<3;i++)Files.writeString(source.resolve("f"+i),"old");var polls=new AtomicInteger();var cas=new StreamingContentStore(temp.resolve("cas"));
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            var result=new StreamingRepositoryAcquirer(cas,store).acquire(source,"mutation",request(),()->{
                if(polls.incrementAndGet()==10)try{for(int i=0;i<3;i++)Files.writeString(source.resolve("f"+i),"changed bytes");}catch(IOException failed){throw new java.io.UncheckedIOException(failed);}return false;
            });
            assertTrue(polls.get()>=10);assertEquals(JobJournal.State.WAITING_FOR_INPUT,result.state());assertTrue(result.manifest().isEmpty());assertTrue(store.manifest("mutation").isEmpty());
            assertTrue(store.page("mutation",10,Optional.empty()).entries().stream().anyMatch(e->e.disposition()==InventoryEntry.Disposition.GAP));
        }
    }
    @Test void threadInterruptionStillPublishesTheCancellationCheckpoint()throws Exception{
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("file"),"content");var cas=new StreamingContentStore(temp.resolve("cas"));
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            StreamingRepositoryAcquirer.Receipt receipt;boolean interrupted;
            try{receipt=new StreamingRepositoryAcquirer(cas,store).acquire(source,"interrupted",request(),()->{Thread.currentThread().interrupt();return true;});interrupted=Thread.currentThread().isInterrupted();}
            finally{Thread.interrupted();}
            assertTrue(interrupted);assertEquals(JobJournal.State.WAITING_FOR_INPUT,receipt.state());assertEquals("CANCELLED_CHECKPOINT",store.get("interrupted").orElseThrow().reason());assertTrue(store.manifest("interrupted").isEmpty());
            var pin=cas.put(new ByteArrayInputStream("pin".getBytes()),()->false);store.create("pinned",ContentDigest.sha256Utf8("request"),Optional.of(pin));
            try{Thread.currentThread().interrupt();store.transition("pinned",0,JobJournal.State.WAITING_FOR_INPUT,"CANCELLED_CHECKPOINT",Optional.of(pin));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
            assertEquals(JobJournal.State.WAITING_FOR_INPUT,store.get("pinned").orElseThrow().state());
        }
    }
}
