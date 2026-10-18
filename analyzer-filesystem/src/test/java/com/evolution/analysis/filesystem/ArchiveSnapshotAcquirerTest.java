package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveSnapshotAcquirerTest {
    @TempDir Path root;
    byte[] zip(String...names)throws IOException{var bytes=new ByteArrayOutputStream();try(var zip=new ZipOutputStream(bytes)){for(String name:names){zip.putNextEntry(new ZipEntry(name));zip.write(("content:"+name).getBytes());zip.closeEntry();}}return bytes.toByteArray();}
    StreamingAcquisitionRequest request(){return new StreamingAcquisitionRequest(RepositoryIdentity.fromCanonicalCoordinate("urn:test:zip"),Optional.empty(),false,List.of());}
    @Test void capturesUnknownLanguagesAndWindowsIncompatibleNamesWithoutExtraction()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var archive=cas.put(new ByteArrayInputStream(zip("main.py","api/schema.proto","CON:x","A","a")),()->false);
        try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            var receipt=new ArchiveSnapshotAcquirer(cas,store).acquire(archive,"zip",request(),()->false);
            assertEquals(JobJournal.State.COMPLETE,receipt.state(),receipt.reason());assertEquals(5,receipt.manifest().orElseThrow().files());assertTrue(receipt.manifest().orElseThrow().compatibleV1Snapshot().isEmpty());
            assertFalse(Files.exists(root.resolve("main.py")));
        }
    }
    @Test void traversalTruncationAndInvalidEnvelopeCannotBecomeEmptyCompleteSnapshots()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));byte[] good=zip("known");
        List<byte[]> cases=List.of(zip("../escape"),Arrays.copyOf(good,good.length-15),new byte[]{1,2,3});int index=0;
        try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            for(byte[] bytes:cases){String revision="invalid"+index++;var receipt=new ArchiveSnapshotAcquirer(cas,store).acquire(cas.put(new ByteArrayInputStream(bytes),()->false),revision,request(),()->false);
                assertEquals(JobJournal.State.WAITING_FOR_INPUT,receipt.state());assertTrue(receipt.manifest().isEmpty());assertTrue(store.manifest(revision).isEmpty());}
        }
    }
    @Test void localPayloadMustMatchCentralDirectory()throws Exception{
        byte[] bytes=zip("file");int central=-1;for(int i=0;i<bytes.length-4;i++)if(bytes[i]==0x50&&bytes[i+1]==0x4b&&bytes[i+2]==1&&bytes[i+3]==2){central=i;break;}assertTrue(central>0);bytes[central+16]^=1;
        var cas=new StreamingContentStore(root.resolve("cas"));try(var store=new SqliteInventoryStore(root.resolve("metadata"),cas)){
            var receipt=new ArchiveSnapshotAcquirer(cas,store).acquire(cas.put(new ByteArrayInputStream(bytes),()->false),"mismatch",request(),()->false);assertEquals("ARCHIVE_LOCAL_DIRECTORY_MISMATCH",receipt.reason());assertTrue(receipt.manifest().isEmpty());
        }
    }
}
