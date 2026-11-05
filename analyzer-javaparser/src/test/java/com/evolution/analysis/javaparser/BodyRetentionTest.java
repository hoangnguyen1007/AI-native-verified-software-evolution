package com.evolution.analysis.javaparser;

import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BodyRetentionTest {
    @Test void sourceBodiesAreReleasedAndCrossFileLocalLambdaAndAnonymousFactsReplayExactly()throws Exception {
        var request=TestInputs.request(Map.of(
                "fixture/Api.java","package fixture; interface Api { int work(); } class Service implements Api { public int work(){ return 1; } }",
                "fixture/Client.java","""
                    package fixture;
                    class Client {
                      Runnable initialization=()->{ Service service=new Service(); service.work(); service.work(); };
                      int run(Service service) {
                        class Local { int get(){ return service.work(); } }
                        java.util.function.IntSupplier f=()->service.work();
                        Api anonymous=new Api(){ public int work(){return service.work();} };
                        return new Local().get()+f.getAsInt()+anonymous.work();
                      }
                    }
                    """),List.of());
        var baseline=new Extraction(request,false);var expected=baseline.run();
        var compact=new Extraction(request,true);var actual=compact.run();
        assertEquals(expected,actual);
        assertEquals(IngestionEvidence.digest(expected),IngestionEvidence.digest(actual));
        assertTrue(baseline.retainedBodyStatements()>10);
        // Empty declaration body blocks remain; executable statements/local bodies do not.
        assertTrue(compact.retainedBodyStatements()<baseline.retainedBodyStatements()/2);
        assertEquals(0,compact.retainedCalls(),"Field lambda bodies must not leak through declaration skeletons");
    }
}
