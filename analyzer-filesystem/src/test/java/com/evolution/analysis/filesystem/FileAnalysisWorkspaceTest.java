package com.evolution.analysis.filesystem;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.ingestion.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FileAnalysisWorkspaceTest {
    @TempDir Path directory;
    @Test void openingAnotherWorkspaceLeavesSiblingScratchAndCallerDirectoriesAlone() throws Exception {
        var callerFile = Files.writeString(Files.createDirectory(directory.resolve("analysis-caller")).resolve("owned.txt"), "caller");
        try (var first = new FileAnalysisWorkspace(directory)) {
            var rows = first.<String>list();
            rows.add("first analysis");
            try (var second = new FileAnalysisWorkspace(directory)) {
                var others = second.<String>list();
                others.add("second analysis");
                assertEquals("caller", Files.readString(callerFile));
                assertEquals("first analysis", rows.getFirst());
                assertEquals("second analysis", others.getFirst());
            }
            assertEquals("first analysis", rows.getFirst());
        }
        assertEquals("caller", Files.readString(callerFile));
    }

    @Test void repetitiveRowsUseLessScratchAndReplayExactJavaCodeUnits() throws Exception {
        try (var spool = new FileAnalysisWorkspace(directory)) {
            String payload = "captured symbol metadata \ue000\ud800".repeat(1024);
            var rows = spool.<String>list();
            for (int index = 0; index < 32; index++) rows.add(payload + index);
            var replay = spool.freeze(rows);
            Path bytes;
            try (var paths = Files.walk(directory)) {
                bytes = paths.filter(file -> file.getFileName().toString().equals("values.bin")).findFirst().orElseThrow();
            }
            assertTrue(Files.size(bytes) < 64 * 1024, "Repetitive spool rows must fit in the authored scratch budget");
            for (int index = 0; index < 32; index++) assertEquals(payload + index, replay.get(index));
        }
    }

    @Test void nestedReplayListsCannotMoveTheAppendWriterOrCorruptTheirSourceRows()throws Exception {
        try(var spool=new FileAnalysisWorkspace(directory)) {
            var original=spool.<String>list();original.add("original");var replay=spool.freeze(original);
            var separate=spool.<String>list();separate.add("must survive nested replay");
            var nested=spool.<List<String>>list();nested.add(replay);
            assertEquals(List.of("original"),nested.getFirst());assertEquals("original",replay.getFirst());
            assertEquals("must survive nested replay",separate.getFirst());
        }
    }
    @Test void distinctReusesVerifiedRowBytesAndKeepsFirstWinnerAndJavaOrdering()throws Exception {
        try(var spool=new FileAnalysisWorkspace(directory)) {
            var rows=spool.<String>list();rows.addAll(List.of("b-first","a-first","b-second"));var replay=spool.freeze(rows);
            Path bytes;try(var paths=Files.walk(directory)){bytes=paths.filter(p->p.toString().endsWith("values.bin")).findFirst().orElseThrow();}
            long original=Files.size(bytes);var distinct=spool.distinct(replay,s->s.substring(0,1));
            assertEquals(List.of("a-first","b-first"),distinct);
            assertEquals(original,Files.size(bytes),"Sorting/deduplication of stored rows must alias authenticated bytes instead of re-encoding them twice");
            Files.writeString(bytes,"corrupt");assertThrows(IllegalStateException.class,distinct::getFirst);
        }
    }
    @Test void diskRowsReplayExactlySealAndSortWithoutKeepingCallerObjects()throws Exception {
        List<IngestionEvidence.Issue> replay;
        try(var spool=new FileAnalysisWorkspace(directory)){
            var rows=spool.<IngestionEvidence.Issue>list();
            var original=new IngestionEvidence.Issue(IngestionEvidence.Reason.INPUT_UNAVAILABLE,"missing.java",List.of(ContentDigest.sha256Utf8("input")));
            rows.add(original);rows.add(original);replay=spool.freeze(rows);
            assertNotSame(original,replay.getFirst());assertEquals(original,replay.getFirst());
            assertEquals(IngestionEvidence.digest(List.of(original,original)),IngestionEvidence.digest(replay));
            assertThrows(UnsupportedOperationException.class,()->rows.add(original));
            assertThrows(UnsupportedOperationException.class,()->replay.set(0,original));
            assertEquals(List.of("a","b"),spool.distinct(List.of("b","a","b"),s->s));
            assertEquals(1,spool.distinct(replay,r->r.identity().value()).size());
        }
        assertThrows(IllegalStateException.class,replay::getFirst);
        try(var files=Files.list(directory)){assertEquals(0,files.count());}
    }
    @Test void corruptScratchBytesFailClosedAndUnrelatedFilesSurviveCleanup()throws Exception {
        Path caller=directory.resolve("keep.txt");Files.writeString(caller,"keep");
        try(var spool=new FileAnalysisWorkspace(directory)){
            var rows=spool.<String>list();rows.add("one");var replay=spool.freeze(rows);
            Path bytes;try(var paths=Files.walk(directory)){bytes=paths.filter(p->p.toString().endsWith("values.bin")).findFirst().orElseThrow();}
            Files.writeString(bytes,"corrupt");assertThrows(IllegalStateException.class,replay::getFirst);
        }
        assertEquals("keep",Files.readString(caller));
    }
    @Test void sortingUsesJavaUtf16OrderAndLargePopulationsUseConstantFileCount()throws Exception {
        try(var spool=new FileAnalysisWorkspace(directory)){
            var values=spool.<String>list();for(int i=0;i<2048;i++)values.add("row-"+i);
            values.add("\ue000");values.add("\ud83d\ude00");values.add("row-0");
            values.add("\ud800raw\udc00");
            var expected=new TreeSet<String>(values);
            assertEquals(List.copyOf(expected),spool.distinct(values,s->s));
            try(var paths=Files.walk(directory)){assertTrue(paths.filter(Files::isRegularFile).count()<=3,"Rows share one data file and an on-disk SQLite index");}
        }
    }
}
