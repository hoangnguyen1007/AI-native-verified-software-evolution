package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.ContentDigest;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StreamingDiskBudgetTest {
    @TempDir Path temp;

    @Test void tinyProducerWritesDoNotProbeDiskPerFlushAndPreserveExactBytes() throws Exception {
        var probes=new AtomicInteger();byte[] bytes=new byte[3*1024*1024];
        for(int i=0;i<bytes.length;i++)bytes[i]=(byte)i;
        var cas=new StreamingContentStore(temp,17,c->c.force(true),()->{probes.incrementAndGet();return Long.MAX_VALUE;});
        var handle=cas.write(out->{for(byte b:bytes)out.write(b);},()->false);
        assertEquals(ContentDigest.sha256(bytes),handle.digest());assertEquals(bytes.length,handle.size());
        assertTrue(cas.verify(handle));assertTrue(probes.get()<=3,"disk probes="+probes);
    }

    @Test void aDroppingDiskBudgetStopsLargeWritesBeforePublication() throws Exception {
        var probes=new AtomicInteger();
        var cas=new StreamingContentStore(temp,100,c->c.force(true),()->probes.incrementAndGet()==1?Long.MAX_VALUE:100);
        var failure=assertThrows(IOException.class,()->cas.write(out->out.write(new byte[20*1024*1024]),()->false));
        assertEquals("WAITING_FOR_DISK",failure.getMessage());
        try(var files=Files.walk(temp)){assertEquals(0,files.filter(Files::isRegularFile).count());}
    }

    @Test void cachedAllowanceCannotSpendTheReserveEvenBeforeTheNextSample() throws Exception {
        var cas=new StreamingContentStore(temp,100,c->c.force(true),()->108);
        assertEquals(8,cas.put(new ByteArrayInputStream(new byte[8]),()->false).size());
        assertEquals("WAITING_FOR_DISK",assertThrows(IOException.class,()->cas.put(new ByteArrayInputStream(new byte[9]),()->false)).getMessage());
    }
}
