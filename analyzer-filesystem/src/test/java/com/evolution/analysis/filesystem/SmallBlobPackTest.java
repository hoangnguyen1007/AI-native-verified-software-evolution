package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SmallBlobPackTest {
    @TempDir Path temp;

    @Test void acquisitionGroupsSmallFilesAndColdReadsRecoverFromDurablePack() throws Exception {
        Path source=Files.createDirectory(temp.resolve("source")), root=temp.resolve("cas");
        for(int i=0;i<40;i++) Files.writeString(source.resolve("source-"+i+".java"),"class C"+i+" {}\n");
        var content=new StreamingContentStore(root);
        var request=new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:small-pack"),Optional.empty(),false,List.of());
        List<ContentHandle> handles;
        try(var inventory=new SqliteInventoryStore(temp.resolve("metadata"),content)) {
            var receipt=new StreamingRepositoryAcquirer(content,inventory).acquire(source,"capture",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state());
            handles=inventory.page("capture",100,Optional.empty()).entries().stream().flatMap(e->e.content().stream()).toList();
            assertEquals(40,handles.size());
        }
        assertTrue(Files.isDirectory(root.resolve("packs")),"Small source files must share a forced durable pack");
        try(var paths=Files.list(root.resolve("packs"))) { assertEquals(1,paths.filter(p->p.toString().endsWith(".pack")).count()); }
        // Loose files are a derived read cache. Losing them must not lose acknowledged bytes.
        for(var handle:handles) {
            String hex=handle.digest().value().substring(7);
            Files.delete(root.resolve("sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2)));
        }
        var cold=new StreamingContentStore(root);
        for(var handle:handles) assertTrue(cold.verify(handle));
        try(var inventory=new SqliteInventoryStore(temp.resolve("metadata"),cold)) { assertTrue(inventory.verifySeal("capture",()->false)); }
    }

    @Test void repeatedBytesReusePackAndCorruptRecoveryNeverReplacesAcknowledgedContent()throws Exception {
        Path root=temp.resolve("cas");var content=new StreamingContentStore(root);
        byte[] bytes="known original bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var handle=new ContentHandle(ContentDigest.sha256(bytes),bytes.length);
        content.putSmallBatch(List.of(bytes,bytes));content.putSmallBatch(List.of(bytes));
        Path pack;try(var paths=Files.list(root.resolve("packs"))){var files=paths.toList();assertEquals(1,files.size());pack=files.getFirst();}
        assertEquals(handle,content.put(new ByteArrayInputStream(bytes),()->false));
        String hex=handle.digest().value().substring(7);Path loose=root.resolve("sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));
        byte[] corrupt=Files.readAllBytes(pack);corrupt[corrupt.length-1]^=1;Files.write(pack,corrupt);
        assertTrue(content.verify(handle),"A damaged unrelated recovery copy does not invalidate intact loose evidence");
        Files.delete(loose);
        assertEquals("CAS_PACK_CORRUPT",assertThrows(IOException.class,()->new StreamingContentStore(root).open(handle)).getMessage());
        assertFalse(Files.exists(loose),"Never cache unauthenticated pack bytes");
    }

    @Test void diskPressureAndInvalidBatchCannotPublishHandles()throws Exception {
        Path root=temp.resolve("cas");var content=new StreamingContentStore(root,Long.MAX_VALUE);
        assertEquals("WAITING_FOR_DISK",assertThrows(IOException.class,()->content.putSmallBatch(List.of(new byte[]{1}))).getMessage());
        assertThrows(IllegalArgumentException.class,()->content.putSmallBatch(List.of(new byte[65537])));
        assertThrows(IllegalArgumentException.class,()->content.putSmallBatch(Collections.nCopies(257,new byte[0])));
        try(var paths=Files.walk(root.resolve("sha256"))){assertEquals(0,paths.filter(Files::isRegularFile).count());}
        try(var paths=Files.list(root.resolve("staging"))){assertEquals(0,paths.count());}
    }

    @Test void fortyUniqueSmallObjectsShareOneFsyncAndDuplicatesNeedNoAdditionalFlush()throws Exception {
        var forces=new java.util.concurrent.atomic.AtomicInteger();
        var content=new StreamingContentStore(temp.resolve("cas"),0,channel->{forces.incrementAndGet();channel.force(true);});
        var values=java.util.stream.IntStream.range(0,40).mapToObj(i->("blob-"+i).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toList();
        content.putSmallBatch(values);assertEquals(1,forces.get());
        content.putSmallBatch(values);assertEquals(1,forces.get());
        for(byte[] bytes:values)content.put(new ByteArrayInputStream(bytes),()->false);
        assertEquals(1,forces.get(),"Already verified CAS objects need no redundant force");
    }

    @Test void failedPackFlushLeavesNoPublishedObjectOrInventoryReference()throws Exception {
        Path root=temp.resolve("cas");
        var content=new StreamingContentStore(root,0,channel->{throw new IOException("CAS_FLUSH_FAILED");});
        assertEquals("CAS_FLUSH_FAILED",assertThrows(IOException.class,()->content.putSmallBatch(List.of(new byte[]{1},new byte[]{2}))).getMessage());
        for(String directory:List.of("sha256","staging","packs"))try(var paths=Files.walk(root.resolve(directory))){assertEquals(0,paths.filter(Files::isRegularFile).count());}
    }

    @Test void mixedCaptureCrossesBatchBoundaryWithoutTruncatingLargeFiles()throws Exception {
        Path source=Files.createDirectory(temp.resolve("mixed")),root=temp.resolve("cas");
        for(int i=0;i<270;i++)Files.writeString(source.resolve("file-"+i),"small-"+i);
        byte[] large=new byte[131073];new Random(173).nextBytes(large);Files.write(source.resolve("large.bin"),large);
        var content=new StreamingContentStore(root);
        var request=new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:mixed-pack"),Optional.empty(),false,List.of());
        try(var inventory=new SqliteInventoryStore(temp.resolve("metadata"),content)) {
            var receipt=new StreamingRepositoryAcquirer(content,inventory).acquire(source,"mixed",request,()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state());assertEquals(271,receipt.manifest().orElseThrow().files());
            var handle=new ContentHandle(ContentDigest.sha256(large),large.length);
            try(var input=content.open(handle)){assertArrayEquals(large,input.readAllBytes());}
            assertTrue(inventory.verifySeal("mixed",()->false));
        }
        try(var paths=Files.list(root.resolve("packs"))){assertEquals(2,paths.filter(p->p.toString().endsWith(".pack")).count());}
    }
}
