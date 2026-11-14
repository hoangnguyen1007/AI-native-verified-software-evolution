package com.evolution.backend.query;

import com.evolution.analysis.graph.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.SqliteInventoryStore;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.backend.analysis.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DurableAnalysisJobsTest {
    @TempDir Path root;
    @Test void legacyV2ReceiptIsReadWithoutRewritingTheOriginalPreimage() throws Exception {
        var id = UUID.randomUUID();
        var registration = AnalysisJobsTest.registration("local", control -> { throw new AssertionError("History must not run"); });
        var now = Instant.parse("2026-11-12T00:00:00Z");
        var expected = new AnalysisJobs.View(id.toString(), "local", AnalysisJobs.State.FAILED, AnalysisJobs.Stage.FINISHED,
                3, now, Optional.of(now), Optional.of(now), 0, Optional.empty(), Optional.of("ANALYSIS_FAILED"),
                List.of(new AnalysisJobs.Event(AnalysisJobs.Stage.FINISHED, now)), Optional.empty());
        var oldView = new TreeMap<>(GraphJson.object(GraphJson.read(CanonicalJson.write(expected), GraphJson.Limits.defaults())));
        oldView.keySet().removeAll(List.of("force", "reuse", "reuseDecision"));
        var content = new StreamingContentStore(root.resolve("content"));
        var gaps = content.put(new ByteArrayInputStream("{\"gaps\":[]}".getBytes(StandardCharsets.UTF_8)), () -> false);
        var inputs = content.put(new ByteArrayInputStream("{\"inputProblems\":[]}".getBytes(StandardCharsets.UTF_8)), () -> false);
        var original = CanonicalJson.write(Map.of("schema", "analysis-job-storage:m8.4-v2", "retry", "legacy",
                "setup", registration.setup(), "view", oldView, "gaps", gaps, "inputProblems", inputs));
        var handle = content.put(new ByteArrayInputStream(original.getBytes(StandardCharsets.UTF_8)), () -> false);
        try (var journal = new SqliteInventoryStore(root.resolve("metadata"), content)) {
            journal.create(id.toString(), ContentDigest.sha256Utf8("legacy"), Optional.of(handle));
        }
        try (var disk = new AnalysisJobDiskStore(root)) {
            assertEquals(expected, disk.get(id, true).orElseThrow().view());
            assertEquals(expected, disk.retry("legacy").orElseThrow().view());
            assertEquals(expected, disk.history(0, 10).items().getFirst().view());
        }
        try (var journal = new SqliteInventoryStore(root.resolve("metadata"), content, true)) {
            assertEquals(Optional.of(handle), journal.get(id.toString()).orElseThrow().payload());
        }
    }
    @Test void acceptedJobsExceedLegacyHistoryAndQueueWithoutEvictionAndSurviveRestart()throws Exception{
        var graph=new QueryFixture().graph();var graphs=new InMemoryGraphStore();var reports=new PublishedReports();var latch=new CountDownLatch(1);var entered=new CountDownLatch(1);var calls=new AtomicInteger();
        var registration=AnalysisJobsTest.registration("local",control->{calls.incrementAndGet();entered.countDown();latch.await();return AnalysisJobsTest.output(graph);});var limits=new AnalysisJobs.Limits(1,1,2,0,1000);var ids=new ArrayList<String>();
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),graphs,reports,limits,disk)){
            ids.add(jobs.submit("local","first").job().id());assertTrue(entered.await(3,TimeUnit.SECONDS));
            for(int i=0;i<8;i++)ids.add(jobs.submit("local","key-"+i).job().id());
            assertEquals(9,jobs.history(20,Optional.empty()).total());
            assertEquals(AnalysisJobs.State.CANCELLED,jobs.cancel(UUID.fromString(ids.getLast())).state());latch.countDown();
            for(String id:ids.subList(0,ids.size()-1))assertEquals(AnalysisJobs.State.SUCCEEDED,AnalysisJobsTest.finished(jobs,id).state());
            assertEquals(8,calls.get());assertEquals(9,jobs.history(20,Optional.empty()).items().size());
            var first=jobs.history(2,Optional.empty());String late=jobs.submit("local","late").job().id();var members=new ArrayList<String>();members.addAll(first.items().stream().map(AnalysisJobs.View::id).toList());var cursor=first.continuation();
            while(cursor.isPresent()){var page=jobs.history(2,cursor);assertEquals(9,page.total());members.addAll(page.items().stream().map(AnalysisJobs.View::id).toList());cursor=page.continuation();}
            assertEquals(9,members.size());assertFalse(members.contains(late));assertThrows(IllegalArgumentException.class,()->jobs.history(3,first.continuation()));AnalysisJobsTest.finished(jobs,late);
        }finally{latch.countDown();}
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),graphs,reports,limits,disk)){
            assertEquals(10,jobs.history(20,Optional.empty()).total());assertEquals(ids.getFirst(),jobs.submit("local","first").job().id());assertEquals(9,calls.get());
            assertEquals(graph.identity(),jobs.get(UUID.fromString(ids.getFirst())).result().orElseThrow().graph().orElseThrow());
        }
    }
    @Test void gapLedgerRemainsReadableAfterActiveJobEvictionAndRestart()throws Exception{
        var fixture=PublishedReportsTest.fixture();var gaps=fixture.report().assessment().orElseThrow().gaps();assertFalse(gaps.isEmpty());
        var registration=AnalysisJobsTest.registration("local",control->new AnalysisJobs.Outcome(AnalysisJobs.Completion.PARTIAL,Optional.of(fixture.graph()),List.of(fixture.report()),gaps,Optional.empty(),Optional.empty()));String id;
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),fixture.store(),new PublishedReports(),new AnalysisJobs.Limits(1,1,2,0,1000),disk)){
            id=jobs.submit("local","gaps").job().id();assertEquals(AnalysisJobs.State.PARTIAL,AnalysisJobsTest.finished(jobs,id).state());assertEquals(gaps.size(),jobs.gaps(UUID.fromString(id),50,Optional.empty()).items().size());
        }
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),fixture.store(),new PublishedReports(),new AnalysisJobs.Limits(1,1,2,0,1000),disk)){
            assertEquals(com.evolution.analysis.contract.serialization.CanonicalJson.sha256(gaps.stream().distinct().sorted().toList()),com.evolution.analysis.contract.serialization.CanonicalJson.sha256(jobs.gaps(UUID.fromString(id),50,Optional.empty()).items()));
        }
    }
    @Test void readsGapPagesAfterRestartAndRejectsACorruptedChunk() throws Exception {
        var fixture=PublishedReportsTest.fixture();
        var base=fixture.report().assessment().orElseThrow().gaps().getFirst();
        var gaps=java.util.stream.IntStream.range(0,125).mapToObj(i->
                com.evolution.analysis.evidence.CapabilityGapRecord.create(base.context(),base.detectingProvider(),
                        base.mechanismCategory(),base.reasonCode(),
                        new com.evolution.analysis.evidence.EvidenceSubject(
                                base.subject().kind(),"fixture-gap-"+i),base.sourceSpans(),base.observationReferences(),
                        base.evidenceRequirements(),base.candidateProviders(),base.affectedOutputs(),
                        base.acquisitionAttemptReferences(),base.diagnostics(),base.limitations())).sorted().toList();
        var registration=AnalysisJobsTest.registration("local",control->new AnalysisJobs.Outcome(
                AnalysisJobs.Completion.PARTIAL,Optional.of(fixture.graph()),List.of(),gaps,Optional.empty(),Optional.empty()));
        UUID id;
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),fixture.store(),
                new PublishedReports(),AnalysisJobs.Limits.defaults(),disk)){
            id=UUID.fromString(jobs.submit("local","segmented").job().id());
            assertEquals(AnalysisJobs.State.PARTIAL,AnalysisJobsTest.finished(jobs,id.toString()).state());
        }
        var content=new StreamingContentStore(root.resolve("content"));
        Map<String,Object> chunks;
        try(var journal=new SqliteInventoryStore(root.resolve("metadata"),content,true)){
            var receipt=blob(content,journal.get(id.toString()).orElseThrow().payload().orElseThrow());
            assertEquals("analysis-job-storage:m8.4-v4",receipt.get("schema"));
            chunks=blob(content,handle(receipt.get("gaps")));
        }
        var parts=GraphJson.array(chunks.get("chunks"));
        assertTrue(parts.size()>1);
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),fixture.store(),
                new PublishedReports(),AnalysisJobs.Limits.defaults(),disk)){
            var collected=new ArrayList<com.evolution.analysis.evidence.CapabilityGapRecord>();
            Optional<String> cursor=Optional.empty(), later=Optional.empty();
            do {var page=jobs.gaps(id,37,cursor);assertEquals(gaps.size(),page.total());
                collected.addAll(page.items());cursor=page.continuation();
                if(collected.size()==74)later=cursor;} while(cursor.isPresent());
            assertEquals(gaps,collected);
            assertEquals(CanonicalJson.sha256(Map.of("gaps",gaps)).value(),chunks.get("canonical"));
            String digest=handle(chunks.get("pack")).digest().value().substring(7);
            var path=root.resolve("content/sha256").resolve(digest.substring(0,2)).resolve(digest.substring(2));
            byte[] bytes=Files.readAllBytes(path);bytes[0]^=1;Files.write(path,bytes);
            assertEquals("JOB_STORAGE_UNAVAILABLE",assertThrows(AnalysisJobs.Rejected.class,
                    ()->jobs.gaps(id,37,Optional.empty())).code());
            assertEquals(gaps.subList(74,111),jobs.gaps(id,37,later).items());
        }
    }
    private static com.evolution.analysis.acquisition.streaming.ContentHandle handle(Object value){
        var row=GraphJson.object(value);
        return new com.evolution.analysis.acquisition.streaming.ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),
                ((Number)row.get("size")).longValue());
    }
    private static Map<String,Object> blob(StreamingContentStore content,
            com.evolution.analysis.acquisition.streaming.ContentHandle handle)throws Exception{
        try(var input=content.open(handle)){
            return GraphJson.object(GraphJson.read(new String(input.readAllBytes(),StandardCharsets.UTF_8),GraphJson.Limits.defaults()));
        }
    }
    @Test void changedSetupCannotSilentlyResumeAnAcceptedRequest()throws Exception{
        String id=UUID.randomUUID().toString();var original=AnalysisJobsTest.registration("local",control->{throw new AssertionError("Should not run");});
        try(var disk=new AnalysisJobDiskStore(root)){
            var view=new AnalysisJobs.View(id,"local",AnalysisJobs.State.RUNNING,AnalysisJobs.Stage.ACQUISITION,2,java.time.Instant.now(),Optional.empty(),Optional.empty(),0,Optional.empty(),Optional.empty(),List.of(),Optional.empty());disk.save("original",original.setup(),view,List.of(),List.of());
        }
        var setup=original.setup();var changed=new AnalysisJobs.Registration(new AnalysisJobs.Setup(setup.id(),setup.repository(),17,setup.includeTests(),setup.policy(),setup.configurationSpace()),original.runner());
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(changed),new InMemoryGraphStore(),new PublishedReports(),new AnalysisJobs.Limits(1,1,2,0,1000),disk)){
            assertEquals("SETUP_CONFIGURATION_CHANGED",jobs.get(UUID.fromString(id)).error().orElseThrow());assertEquals("SETUP_CONFIGURATION_CHANGED",assertThrows(AnalysisJobs.Rejected.class,()->jobs.resume(UUID.fromString(id))).code());
            assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(AnalysisJobs.Rejected.class,()->jobs.submit("local","original")).code());
        }
    }
    @Test void interruptedRunningReceiptResumesSameJobIdentity()throws Exception{
        var graph=new QueryFixture().graph();var registration=AnalysisJobsTest.registration("local",control->AnalysisJobsTest.output(graph));String id=UUID.randomUUID().toString();
        try(var disk=new AnalysisJobDiskStore(root)){
            var view=new AnalysisJobs.View(id,"local",AnalysisJobs.State.RUNNING,AnalysisJobs.Stage.ACQUISITION,3,java.time.Instant.now(),Optional.empty(),Optional.empty(),0,Optional.empty(),Optional.empty(),List.of(),Optional.empty());
            disk.save("retry",registration.setup(),view,List.of(),List.of());
        }
        try(var disk=new AnalysisJobDiskStore(root);var jobs=new AnalysisJobs(List.of(registration),new InMemoryGraphStore(),new PublishedReports(),new AnalysisJobs.Limits(1,1,2,0,1000),disk)){
            assertEquals(AnalysisJobs.State.SUCCEEDED,AnalysisJobsTest.finished(jobs,id).state());assertEquals(id,jobs.submit("local","retry").job().id());
        }
    }
}
