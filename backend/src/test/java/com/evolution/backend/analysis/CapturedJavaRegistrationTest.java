package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CapturedJavaRegistrationTest {
    @TempDir Path directory;
    private CapturedJavaReader.Registration registration(){
        var digest=ContentDigest.sha256Utf8("missing capture");
        return new CapturedJavaReader.Registration("captured-java-registration:m8.4-v1",directory.resolve("cas").toString(),new ContentHandle(digest,15),
                new EvidenceVerifier(Map.of(),Map.of(),List.of()).policyIdentity(),Map.of(),Map.of(),List.of(),digest,digest,1,"REGISTERED_VALIDATION");
    }
    @Test void onlyTheExactOperatorSelectedRecordSchemaIsAccepted()throws Exception{
        String canonical=CanonicalJson.write(registration());Path file=directory.resolve("registration.json");Files.writeString(file,canonical);
        assertEquals(registration(),CapturedJavaReader.registration(directory));
        for(String invalid:List.of(" "+canonical,canonical.replace("\"schema\":","\"inputSelectedClass\":\"java.lang.Runtime\",\"schema\":"),canonical.replace("\"fence\":1","\"fence\":1.5"))){
            Files.writeString(file,invalid);assertThrows(java.io.IOException.class,()->CapturedJavaReader.registration(directory));
        }
    }
    @Test void missingCapturedBytesCannotFallBackToAnyBuildOrMutableSource()throws Exception{
        var reader=new CapturedJavaReader(new StreamingContentStore(directory.resolve("cas")),()->false);
        var failure=assertThrows(java.io.IOException.class,()->reader.load(registration(),directory.resolve("views")));
        assertEquals("CAPTURED_JAVA_CONTENT_UNAVAILABLE",failure.getMessage());assertFalse(Files.exists(directory.resolve("views")));
    }
    @Test void cancellationBeforeCaptureReadsLeavesNoMaterializedView()throws Exception{
        var reader=new CapturedJavaReader(new StreamingContentStore(directory.resolve("cas")),()->true);
        assertThrows(java.util.concurrent.CancellationException.class,()->reader.load(registration(),directory.resolve("views")));
        assertFalse(Files.exists(directory.resolve("views")));
    }
}
