package com.evolution.analysis.execution.polyglot;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit actual-VM output reconstruction by exact content identity; not an output-census certificate. */
class ArchitectureNativeReplayIT {
    @TempDir Path temporary;
    @Test void exactVmDigestMatchedAuthoredReplayReachesIndependentAuditAndColdNavigation()throws Exception {
        var root=System.getProperty("architecture.replayRoot");var labels=System.getProperty("architecture.replayLabels");var bytes=System.getProperty("architecture.replayedOutput");
        assertNotNull(root,"Explicit stopped native worker root required");assertNotNull(labels,"Precommitted independent labels required");assertNotNull(bytes,"Actual VM digest-matched reproduced bytes required");
        var importer=new ArchitectureNativeCaptureIT();importer.temporary=temporary;importer.verify(root,labels,Optional.of(Path.of(bytes)));
        var changed=temporary.resolve("wrong-replay.json");Files.writeString(changed,"{}");assertThrows(AssertionError.class,()->importer.verify(root,labels,Optional.of(changed)),"A host replay that differs from the VM output must never be admitted");
    }
}
