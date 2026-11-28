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

    @Test void batchedSpillMatchesInMemoryParityAcrossDuplicatesGapsAndZeroCopyDistinct() throws Exception {
        var memory = AnalysisWorkspace.MEMORY;
        var memRows = memory.<IngestionEvidence.Issue>list();
        var memDistinctInput = memory.<String>list();
        var memGaps = memory.gaps(List.of());

        Path spoolDir = Files.createDirectory(directory.resolve("perf-spool"));
        try (var disk = new FileAnalysisWorkspace(spoolDir)) {
            var diskRows = disk.<IngestionEvidence.Issue>list();
            var diskDistinctInput = disk.<String>list();
            var diskGaps = disk.gaps(List.of());

            for (int i = 0; i < 6_000; i++) {
                var issue = new IngestionEvidence.Issue(
                        IngestionEvidence.Reason.INPUT_UNAVAILABLE,
                        "src/main/java/mod" + (i % 50) + "/Item" + i + ".java",
                        List.of(ContentDigest.sha256Utf8("d-" + i)));
                memRows.add(issue);
                diskRows.add(issue);
                String keyVal = "k-" + (i % 1500) + "-row-" + i;
                memDistinctInput.add(keyVal);
                diskDistinctInput.add(keyVal);
                if (i == 100) {
                    // Interleaved read + set forces pending SQLite batch auto-flush cleanly.
                    assertEquals(issue, diskRows.get(100));
                    var updated = new IngestionEvidence.Issue(
                            IngestionEvidence.Reason.INPUT_UNAVAILABLE,
                            "src/main/java/mod/Updated100.java",
                            List.of(ContentDigest.sha256Utf8("u-100")));
                    assertEquals(issue, memRows.set(100, updated));
                    assertEquals(issue, diskRows.set(100, updated));
                    assertEquals(updated, diskRows.get(100));
                }
            }

            var repo = com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:test:p25-parity");
            var ctx = com.evolution.analysis.evidence.EvidenceContext.forSnapshot(
                    com.evolution.analysis.contract.identity.SnapshotIdentity.from(repo, ContentDigest.sha256Utf8("snap")));
            var prov = new VersionedIdentifier("test.p25", "1");
            for (int g = 0; g < 300; g++) {
                var subj = new com.evolution.analysis.evidence.EvidenceSubject(
                        com.evolution.analysis.evidence.EvidenceSubject.Kind.DOCUMENT, "doc-" + g);
                var obs = com.evolution.analysis.evidence.ProviderObservationReference.create(
                        prov, "p25.scope", ContentDigest.sha256Utf8("in-" + g), ContentDigest.sha256Utf8("out-" + g));
                var req = new com.evolution.analysis.evidence.EvidenceRequirement(
                        com.evolution.analysis.evidence.EvidenceRequirement.Kind.REPOSITORY_CONTENT,
                        "p25.resolve", List.of(subj),
                        com.evolution.analysis.evidence.EvidenceRequirement.AuthorizationClass.PASSIVE,
                        List.of("Supply missing content"));
                var out = new com.evolution.analysis.evidence.AffectedOutput(
                        com.evolution.analysis.evidence.AffectedOutput.Kind.GRAPH_PROJECTION, "graph.canonical");
                var gap = com.evolution.analysis.evidence.CapabilityGapRecord.create(
                        ctx, prov, "p25.scope", "MISSING_" + (g % 100), subj,
                        List.of(), List.of(obs), List.of(req), List.of(), List.of(out),
                        List.of(), List.of(), List.of("note"));
                memGaps.add(gap);
                diskGaps.add(gap);
            }

            var memFrozen = memory.freeze(memRows);
            var diskFrozen = disk.freeze(diskRows);
            assertEquals(IngestionEvidence.digest(memFrozen), IngestionEvidence.digest(diskFrozen));

            var memInputFrozen = memory.freeze(memDistinctInput);
            var diskInputFrozen = disk.freeze(diskDistinctInput);
            Path valuesBin;
            try (var paths = Files.walk(spoolDir)) {
                valuesBin = paths.filter(p -> p.getFileName().toString().equals("values.bin")).findFirst().orElseThrow();
            }
            long bytesBeforeDistinct = Files.size(valuesBin);
            var memDistinct = memory.distinct(memInputFrozen, s -> s.substring(0, s.indexOf("-row-")));
            var diskDistinct = disk.distinct(diskInputFrozen, s -> s.substring(0, s.indexOf("-row-")));
            assertEquals(bytesBeforeDistinct, Files.size(valuesBin),
                    "Zero-copy distinct aliasing over stored rows must not append duplicate bytes to values.bin");
            assertEquals(IngestionEvidence.digest(memDistinct), IngestionEvidence.digest(diskDistinct));

            var extList = new ArrayList<String>(4096);
            for (int i = 0; i < 4096; i++) extList.add("ext-" + (i % 900) + "#" + i);
            assertEquals(IngestionEvidence.digest(memory.distinct(extList, s -> s.substring(0, s.indexOf('#')))),
                    IngestionEvidence.digest(disk.distinct(extList, s -> s.substring(0, s.indexOf('#')))));

            assertEquals(List.copyOf(memGaps), List.copyOf(diskGaps));
        }
    }
}
