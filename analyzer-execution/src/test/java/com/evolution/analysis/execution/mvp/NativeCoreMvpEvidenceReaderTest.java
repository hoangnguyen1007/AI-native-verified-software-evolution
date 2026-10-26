package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.execution.output.OutputCensus;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.*;

/** Passive replay of a real operator-registered build; read() cannot launch a build. */
@Tag("native-campaign")
class NativeCoreMvpEvidenceReaderTest {
    @Test void registeredNativePublicationRetainsEveryRowRoleAndProducerTrust()throws Exception{
        var rows=new ArrayList<OutputCensus.RowOutcome>();var capture=new CoreMvpEvidenceReader().read(Path.of(System.getProperty("native.coremvp.root")),rows::add,()->false);
        assertEquals("PRODUCER_ASSERTION",capture.trust());assertEquals(EvidenceVerifier.Trust.PRODUCER_ASSERTION,capture.result().effectiveTrust());
        assertEquals(capture.result().header().declared().entries(),rows.size());assertEquals(capture.result().header().declared(),capture.result().observed().orElseThrow());assertFalse(rows.isEmpty());
        assertEquals(CoreMvpBuildPipeline.OUTPUTS.keySet(),capture.roles().keySet());capture.roles().forEach((role,handle)->assertEquals(CoreMvpBuildPipeline.OUTPUTS.get(role),handle.digest()));
        assertEquals(0,capture.result().unavailableObjects());assertTrue(capture.result().byteObjects()>0);
    }
}
