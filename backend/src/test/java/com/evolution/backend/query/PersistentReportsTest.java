package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.common.ContentDigest;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PersistentReportsTest {
    @TempDir Path root;
    @Test void realFindingsMetricsAssessmentAndGapsRoundTripWithIdenticalIdentity()throws Exception{
        var fixture=PublishedReportsTest.fixture();var report=fixture.report();ContentDigest id;
        try(var disk=new PersistentReports(root)){
            var published=new PublishedReports(disk);id=published.publish(report);assertEquals(report.identity(),published.get(id).orElseThrow().identity());assertEquals(List.of(id),published.forGraph(fixture.graph().identity()));
        }
        try(var disk=new PersistentReports(root)){
            var restored=new PublishedReports(disk).get(id).orElseThrow();assertEquals(CanonicalJson.sha256(report),CanonicalJson.sha256(restored));assertEquals(id,restored.identity());
            assertEquals(report.evaluation().findings().size(),restored.evaluation().findings().size());assertEquals(report.assessment().orElseThrow().gaps().size(),restored.assessment().orElseThrow().gaps().size());
        }
    }
    @Test void publishingAnotherGraphDoesNotExhaustTheLegacyGlobalReportCapacity()throws Exception{
        var fixture=PublishedReportsTest.fixture();try(var disk=new PersistentReports(root)){
            var published=new PublishedReports(disk);
            // Distinct captures remain on disk; the old list-returning per-graph query keeps a bounded compatibility limit.
            for(int i=0;i<33;i++){
                var report=new PublishedReports.Report(fixture.report().evaluation(),fixture.report().metrics(),Optional.of(fixture.report().assessment().orElseThrow().withComputedAt(java.time.Instant.ofEpochSecond(i))));
                assertEquals(report.identity(),published.get(published.publish(report)).orElseThrow().identity());
            }
            assertThrows(IllegalStateException.class,()->published.forGraph(fixture.graph().identity()));
        }
    }
}
