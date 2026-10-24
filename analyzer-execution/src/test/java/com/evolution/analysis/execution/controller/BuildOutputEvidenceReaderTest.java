package com.evolution.analysis.execution.controller;

import com.evolution.analysis.execution.output.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class BuildOutputEvidenceReaderTest {
    @TempDir Path root;
    @Test void registeredPublicationCanBeReadAgainWithoutLaunchingOrPromotingTrust()throws Exception{
        var fixture=new ExecutionControllerTest();fixture.root=root;var harness=fixture.new Harness();
        try(var open=harness.open();var publication=new DurableOutputPublication(root.resolve("outputs"),harness.cas,open.attempts,open.ledger,16,65536)){
            var attempt=open.prepare(DurableOutputPublicationTest.ATTEMPT,"task");open.enqueue(attempt);open.controller.start(DurableOutputPublicationTest.ATTEMPT);
            publication.prepare(attempt.launch(),DurableOutputPublicationTest.scope(),List.of(new DurableOutputPublication.Expectation("bytecode",com.evolution.analysis.acquisition.streaming.LogicalPath.utf8("classes/Caller.class"),true)));
            var header=DurableOutputPublicationTest.header(harness.cas,true);var reader=new BuildOutputEvidenceReader(harness.cas);var rows=new ArrayList<OutputCensus.RowOutcome>();
            assertThrows(java.io.IOException.class,()->reader.read(publication,attempt.launch(),header,rows::add,()->false));assertTrue(rows.isEmpty());
            open.controller.finish(DurableOutputPublicationTest.ATTEMPT);
            var capture=reader.read(publication,attempt.launch(),header,rows::add,()->false);
            assertEquals(OutputCensus.Decision.CAPTURED,capture.decision());assertEquals("PRODUCER_ASSERTION",capture.trust());assertEquals(1,rows.size());assertEquals(1,harness.backend.launches);
            rows.clear();assertEquals(capture,reader.read(publication,attempt.launch(),header,rows::add,()->false));assertEquals(1,rows.size());assertEquals(1,harness.backend.launches);
        }
    }
}
