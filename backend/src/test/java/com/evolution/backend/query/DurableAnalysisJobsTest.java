package com.evolution.backend.query;

import com.evolution.analysis.graph.*;
import com.evolution.backend.analysis.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DurableAnalysisJobsTest {
    @TempDir Path root;
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
