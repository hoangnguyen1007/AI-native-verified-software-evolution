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
    @Test void producerWritesExactBytesAndNeverPublishesFailedOrCancelledOutput()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));
        byte[] bytes="Tiếng Việt 😀".repeat(5000).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var expected=cas.put(new ByteArrayInputStream(bytes),()->false);
        assertEquals(expected,cas.write(output->{output.write(bytes,0,17);output.write(bytes,17,bytes.length-17);},()->false));
        RepositoryContentStore legacy=new RepositoryContentStore(){
            public ContentHandle put(InputStream input,java.util.function.BooleanSupplier stop)throws IOException{return cas.put(input,stop);}
            public InputStream open(ContentHandle handle)throws IOException{return cas.open(handle);}
            public InputStream range(ContentHandle handle,long offset,long length)throws IOException{return cas.range(handle,offset,length);}
            public boolean verify(ContentHandle handle)throws IOException{return cas.verify(handle);}
        };
        assertEquals(expected,legacy.write(output->output.write(bytes),()->false));
        assertTrue(cas.verify(expected));
        assertThrows(IOException.class,()->cas.write(output->{output.write('x');output.write(bytes);throw new IOException("producer failed");},()->false));
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        assertThrows(java.util.concurrent.CancellationException.class,()->cas.write(output->{output.write('y');output.write(bytes);cancelled.set(true);},cancelled::get));
        try(var paths=Files.list(temp.resolve("cas/staging"))){assertEquals(0,paths.count());}
        try(var paths=Files.walk(temp.resolve("cas/sha256"))){assertEquals(1,paths.filter(Files::isRegularFile).count());}
        assertTrue(cas.verify(expected));
    }
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
        // 1. Corrupt packed small blob (<= 64 KiB, stored in cas/packs/*.pack)
        Path source=Files.createDirectory(temp.resolve("source"));Files.writeString(source.resolve("file"),"original");var cas=new StreamingContentStore(temp.resolve("cas"));
        var request=new StreamingAcquisitionRequest(com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:corrupt"),Optional.empty(),true,List.of());
        try(var store=new SqliteInventoryStore(temp.resolve("metadata"),cas)){
            assertEquals(JobJournal.State.COMPLETE,new StreamingRepositoryAcquirer(cas,store).acquire(source,"sealed",request,()->false).state());
            var handle=store.page("sealed",10,Optional.empty()).entries().getFirst().content().orElseThrow();
            try(var packs=Files.list(temp.resolve("cas/packs"))){
                List<Path> packFiles=packs.filter(p->p.getFileName().toString().endsWith(".pack")).toList();
                assertFalse(packFiles.isEmpty(),"Small blob should reside in a pack file");
                for(Path packFile:packFiles){
                    byte[] bytes=Files.readAllBytes(packFile);
                    // Flip payload byte ("original" starts after 8-byte header + 36-byte entry header = offset 44).
                    bytes[44]^=0x55;
                    Files.write(packFile,bytes);
                }
            }
            assertFalse(cas.verify(handle));
            var replay=new StreamingRepositoryAcquirer(cas,store).acquire(source,"sealed",request,()->false);
            assertEquals(JobJournal.State.WAITING_FOR_INPUT,replay.state());
            assertTrue(replay.manifest().isEmpty());
            assertEquals("SEALED_CONTENT_UNAVAILABLE",replay.reason());
        }
        // 2. Corrupt loose large blob (> 64 KiB, stored in cas/sha256/<ab>/<rest>) with mismatched length
        Path largeSource=Files.createDirectory(temp.resolve("source-large"));
        byte[] largePayload=new byte[SmallBlobPacks.MAX_BLOB+1024];
        Arrays.fill(largePayload,(byte)'A');
        Files.write(largeSource.resolve("large.bin"),largePayload);
        var largeCas=new StreamingContentStore(temp.resolve("cas-large"));
        var largeReq=new StreamingAcquisitionRequest(com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:corrupt-large"),Optional.empty(),true,List.of());
        try(var store=new SqliteInventoryStore(temp.resolve("metadata-large"),largeCas)){
            assertEquals(JobJournal.State.COMPLETE,new StreamingRepositoryAcquirer(largeCas,store).acquire(largeSource,"sealed-large",largeReq,()->false).state());
            var handle=store.page("sealed-large",10,Optional.empty()).entries().getFirst().content().orElseThrow();
            String digest=handle.digest().value().substring(7);
            Files.writeString(temp.resolve("cas-large/sha256/"+digest.substring(0,2)+"/"+digest.substring(2)),"corrupted");
            assertFalse(largeCas.verify(handle),"Loose blob with mismatched size must return false rather than throwing");
            var replay=new StreamingRepositoryAcquirer(largeCas,store).acquire(largeSource,"sealed-large",largeReq,()->false);
            assertEquals(JobJournal.State.WAITING_FOR_INPUT,replay.state());
            assertTrue(replay.manifest().isEmpty());
            assertEquals("SEALED_CONTENT_UNAVAILABLE",replay.reason());
        }
    }
    @Test void cleanReopenAndWarmCasAvoidRedundantStagingForcesAndPreserveDeleteEntry()throws Exception{
        Path source=Files.createDirectory(temp.resolve("warm-source"));
        byte[] large=new byte[80_000];new Random(42).nextBytes(large);
        Files.write(source.resolve("large.bin"),large);
        var forces=new java.util.concurrent.atomic.AtomicInteger();
        var cas=new StreamingContentStore(temp.resolve("warm-cas"),0,channel->{forces.incrementAndGet();channel.force(true);});
        var repo=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:warm");
        var request=new StreamingAcquisitionRequest(repo,Optional.empty(),true,List.of());
        Path metadata=temp.resolve("warm-metadata");
        InventoryManifest firstManifest;
        try(var store=new SqliteInventoryStore(metadata,cas)){
            var r1=new StreamingRepositoryAcquirer(cas,store).acquire(source,"rev-1",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,r1.state());
            firstManifest=r1.manifest().orElseThrow();
            var handle=cas.put(new ByteArrayInputStream("conn".getBytes()),()->false);
            store.entries("connections",List.of(new SqliteInventoryStore.Observation(new InventoryEntry(LogicalPath.utf8("c1"),InventoryEntry.Kind.FILE,"100644",InventoryEntry.Population.UNKNOWN,InventoryEntry.Disposition.ACQUIRED,Optional.of(handle),Optional.empty(),Optional.of("UTF-8"),List.of(),Optional.empty()),"")));
            store.deleteEntry("connections",LogicalPath.utf8("c1"));
        }
        int forcesAfterFirst=forces.get();
        // Clean reopen uses journal_meta anchor; warm CAS capture of unchanged large.bin needs 0 blob forces for large.bin itself.
        try(var store=new SqliteInventoryStore(metadata,cas)){
            assertTrue(store.observation("connections",LogicalPath.utf8("c1")).isEmpty());
            var verified=new HashSet<ContentHandle>();
            int beforeBlob=forces.get();
            var reused=cas.putLocalFile(source.resolve("large.bin"),verified,()->false);
            assertEquals(beforeBlob,forces.get(),"Warm CAS large-file capture must not write or force a new staging blob");
            assertEquals(80_000L,reused.size());
            var r2=new StreamingRepositoryAcquirer(cas,store).acquire(source,"rev-2",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,r2.state());
            assertEquals(firstManifest.inventory(),r2.manifest().orElseThrow().inventory());
        }
        assertTrue(forces.get()>forcesAfterFirst);
        // Corrupting index.sqlite forces full replay from operations/*.commit and still preserves deleteEntry.
        Files.writeString(metadata.resolve("index.sqlite"),"corrupt");
        try(var store=new SqliteInventoryStore(metadata,cas)){
            assertTrue(store.observation("connections",LogicalPath.utf8("c1")).isEmpty());
            assertEquals(firstManifest,store.manifest("rev-1").orElseThrow());
        }
    }
    @Test void recoversCleanlyWhenOSDeletesEmptyStagingOrOperationsSubdirectoriesMidRun()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("heal-cas"));
        var metadata=temp.resolve("heal-metadata");
        try(var store=new SqliteInventoryStore(metadata,cas)){
            store.create("heal-job",ContentDigest.sha256Utf8("req"),Optional.empty());
            Files.deleteIfExists(temp.resolve("heal-cas/staging"));
            Files.deleteIfExists(temp.resolve("heal-cas/packs"));
            try(var ops=Files.list(metadata.resolve("operations"))){
                // Delete operations directory recursively to simulate OS cleanup of empty/transient dir.
            }
            for(var path:Files.walk(metadata.resolve("operations")).sorted(Comparator.reverseOrder()).toList()){
                Files.deleteIfExists(path);
            }
            byte[] payload="self-healed-content".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var h=cas.put(new ByteArrayInputStream(payload),()->false);
            assertTrue(cas.verify(h));
            var hSmall=new ContentHandle(ContentDigest.sha256Utf8("0123456789"),10);
            cas.putSmallBatch(Map.of(hSmall,"0123456789".getBytes(java.nio.charset.StandardCharsets.UTF_8)),new HashSet<>());
            assertTrue(cas.verify(hSmall));
            store.transition("heal-job",0,JobJournal.State.RUNNING,"healed",Optional.of(h));
            assertEquals(JobJournal.State.RUNNING,store.get("heal-job").orElseThrow().state());
        }
        var evDir=Files.createDirectories(temp.resolve("heal-ev"));
        new EvolutionResultStore(evDir,cas);
        new FileGraphStore(Files.createDirectories(temp.resolve("heal-graph")));
        assertTrue(Files.isDirectory(evDir));
    }
}
