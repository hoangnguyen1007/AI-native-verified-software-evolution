package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class CapturedJavaScanTest {
    @TempDir Path directory;
    private static ContentDigest hash(String value){return ContentDigest.sha256Utf8(value);}
    @Test void publicFixtureSignersCannotAuthorizeNormalScanOrCreateAnEmptyEvidenceCache()throws Exception{
        var root=Files.createDirectory(directory.resolve("operator"));var cache=directory.resolve("nonexistent-cas");
        var registration=new CapturedJavaReader.Registration("captured-java-registration:m8.4-v1",cache.toString(),new ContentHandle(hash("capture"),1),hash("policy"),Map.of(),Map.of(),List.of(),hash("launch"),hash("profile"),1,"PUBLIC_TEST_POLICY_ONLY");
        Files.writeString(root.resolve("registration.json"),CanonicalJson.write(registration));
        var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:test:captured-scan");
        var expected=new CapturedJavaScan.Expected(repository,SnapshotIdentity.from(repository,hash("source")),hash("readiness"));
        var error=assertThrows(java.io.IOException.class,()->new CapturedJavaScan().run(root,expected,directory.resolve("scratch"),()->false));
        assertEquals("SCAN_CAPTURE_REGISTERED_VALIDATION_REQUIRED",error.getMessage());
        assertFalse(Files.exists(cache));assertFalse(Files.exists(directory.resolve("scratch")));
    }
}
