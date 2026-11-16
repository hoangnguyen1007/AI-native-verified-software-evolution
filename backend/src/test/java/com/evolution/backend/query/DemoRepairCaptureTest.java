package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class DemoRepairCaptureTest {
    @TempDir Path directory;

    @Test void refusesChangedCaptureBytesEvenAtTheRegisteredLocation() throws IOException {
        var path=Files.writeString(directory.resolve("capture.json"),"{\"checks\":[]}");
        var original=ContentDigest.sha256(Files.readAllBytes(path));
        Files.writeString(path,"{\"checks\":[\"candidate.dev-on\"]}");
        var error=assertThrows(IOException.class,()->new DemoRepairCapture(path,original));
        assertEquals("DEMO_CAPTURE_PIN_MISMATCH",error.getMessage());
    }
}
