package com.evolution.analysis.javaparser;

import com.evolution.analysis.javaevidence.JavaSemanticProjection;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

@Tag("native-campaign")
class NativeSemanticProjectionTest {
    @Test void differentReplayAttemptAndTimeKeepTheSameJavaMeaningAndSeparateAuditBindings()throws Exception{
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));
        var first=fixture.execute("protobuf-client",Map.of("captureReplayAttempt","8d73975a-247a-4a9d-90c9-04e739b5ef04","captureReplayRecordedAt","2026-10-24T10:00:00Z"));
        var second=fixture.execute("protobuf-client",Map.of("captureReplayAttempt","80455c3f-6b31-423a-9e4b-2b396d1fb158","captureReplayRecordedAt","2026-10-25T10:00:00Z"));
        assertEquals(first.prepared().inputs().snapshot().identity(),second.prepared().inputs().snapshot().identity());
        assertNotEquals(first.prepared().view().identity(),second.prepared().view().identity());
        var projection=new JavaSemanticProjection();var a=projection.project(first.prepared(),first.pipeline());var b=projection.project(second.prepared(),second.pipeline());
        assertEquals(a.semanticIdentity(),b.semanticIdentity(),"Different operational UUID/time cannot rename identical captured Java facts");
        assertNotEquals(a.auditIdentity(),b.auditIdentity(),"Each operational evidence binding must remain separately inspectable");
        var decoded=a.projection();decoded.put("state","FORGED");
        assertEquals(a.semanticIdentity(),com.evolution.analysis.contract.serialization.CanonicalJson.sha256(a.projection()));
    }
    @Test void generatedSourcesAndPrivateLombokMembersRetainStableTypedMeaningAcrossReplayBindings()throws Exception{
        var fixture=new NativePipelineWiringFixture(Path.of(System.getProperty("native.fixture.root")));var projector=new JavaSemanticProjection();
        for(String name:List.of("mapstruct-mapper","lombok-model")){
            var first=fixture.execute(name,Map.of("captureReplayAttempt","first-registered-replay"));
            var second=fixture.execute(name,Map.of("captureReplayAttempt","second-registered-replay"));
            var a=projector.project(first.prepared(),first.pipeline());var b=projector.project(second.prepared(),second.pipeline());
            assertEquals(a.semanticIdentity(),b.semanticIdentity(),name);assertNotEquals(a.auditIdentity(),b.auditIdentity(),name);
            assertNotEquals(first.prepared().view().identity(),second.prepared().view().identity());
        }
    }
}
