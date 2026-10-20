package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class OutputImporterTest {
    @TempDir Path root;
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    @Test void importsOpaqueBytesByDigestWithoutMaterializingGuestPathsOnHost()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var expected=new ContentHandle(d("class bytes"),11);
        var entry=new OutputImporter.Entry(LogicalPath.utf8("CON:x/Generated.class"),OutputImporter.Kind.FILE,Optional.of(expected),"generated-class",d("context"),d("origin"));
        var imported=new OutputImporter(cas).capture(entry,e->new ByteArrayInputStream("class bytes".getBytes()),d("context"),100,()->false);
        assertEquals(OutputImporter.Disposition.CAPTURED,imported.disposition());assertEquals(expected,imported.captured().orElseThrow());assertTrue(cas.verify(expected));try(var children=java.nio.file.Files.list(root)){assertEquals(List.of("cas"),children.map(p->p.getFileName().toString()).toList());}
    }
    @Test void rejectsSpecialEntriesAndForeignContextBeforeOpeningTheirBytes()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var importer=new OutputImporter(cas);
        for(var kind:List.of(OutputImporter.Kind.SYMLINK,OutputImporter.Kind.HARDLINK,OutputImporter.Kind.FIFO,OutputImporter.Kind.SOCKET,OutputImporter.Kind.DEVICE,OutputImporter.Kind.UNKNOWN)){
            var entry=new OutputImporter.Entry(LogicalPath.utf8("special"),kind,Optional.empty(),"output",d("context"),d("origin"));
            assertEquals(OutputImporter.Disposition.REJECTED,importer.capture(entry,e->{fail("must not follow/open special entry");return null;},d("context"),100,()->false).disposition());
        }
        var directory=new OutputImporter.Entry(LogicalPath.utf8("empty"),OutputImporter.Kind.DIRECTORY,Optional.empty(),"output",d("context"),d("origin"));
        assertEquals("DIRECTORY_CENSUS_REQUIRED",importer.capture(directory,e->{fail("directory is not a file");return null;},d("context"),100,()->false).reason());
        var entry=new OutputImporter.Entry(LogicalPath.utf8("file"),OutputImporter.Kind.FILE,Optional.of(new ContentHandle(d("x"),1)),"output",d("foreign"),d("origin"));
        assertEquals("OUTPUT_CONTEXT_MISMATCH",importer.capture(entry,e->{fail("foreign scope");return null;},d("context"),100,()->false).reason());
    }
    @Test void wrongHashRetainsCapturedBytesWithoutAcceptingDeclaredOutput()throws Exception{
        var cas=new StreamingContentStore(root.resolve("cas"));var entry=new OutputImporter.Entry(LogicalPath.utf8("file"),OutputImporter.Kind.FILE,Optional.of(new ContentHandle(d("good"),4)),"output",d("c"),d("o"));
        var imported=new OutputImporter(cas).capture(entry,e->new ByteArrayInputStream("evil".getBytes()),d("c"),10,()->false);
        assertEquals(OutputImporter.Disposition.REJECTED,imported.disposition());assertEquals("OUTPUT_CONTENT_MISMATCH",imported.reason());assertEquals(d("evil"),imported.captured().orElseThrow().digest());
    }
    @Test void workerBudgetAndMissingOutputStayExplicitAndCannotBecomeCaptured()throws Exception{
        var importer=new OutputImporter(new StreamingContentStore(root.resolve("cas")));var entry=new OutputImporter.Entry(LogicalPath.utf8("file"),OutputImporter.Kind.FILE,Optional.of(new ContentHandle(d("x"),1)),"output",d("c"),d("o"));
        assertEquals("OUTPUT_BYTES_WORKER_REQUIRED",importer.capture(entry,e->{fail("budget");return null;},d("c"),0,()->false).reason());
        assertEquals("OUTPUT_UNAVAILABLE",importer.capture(entry,e->{throw new FileNotFoundException("sensitive remote location");},d("c"),1,()->false).reason());
        var excessive=importer.capture(entry,e->new ByteArrayInputStream(new byte[20]),d("c"),5,()->false);
        assertEquals(OutputImporter.Disposition.PENDING,excessive.disposition());assertEquals("OUTPUT_BYTES_WORKER_REQUIRED",excessive.reason());
        assertThrows(IllegalArgumentException.class,()->LogicalPath.utf8("../escape"));
    }
}
