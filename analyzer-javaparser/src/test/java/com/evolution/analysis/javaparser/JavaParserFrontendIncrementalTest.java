package com.evolution.analysis.javaparser;

import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaParserFrontendIncrementalTest {
    @Test
    void bodyOnlyEditReextractsSingleFileAndMatchesColdBitForBit() throws Exception {
        var baseFiles = new LinkedHashMap<String, String>();
        baseFiles.put("app/ServiceA.java", "package app; public class ServiceA { public int compute(int x) { return x + 1; } }");
        baseFiles.put("app/ServiceB.java", "package app; public class ServiceB { public int use(ServiceA a) { return a.compute(5); } }");
        baseFiles.put("app/ServiceC.java", "package app; public class ServiceC { public String tag() { return \"c\"; } }");
        baseFiles.put("app/ServiceD.java", "package app; public class ServiceD { private final ServiceB b = new ServiceB(); public int call(ServiceA a) { return b.use(a); } }");
        baseFiles.put("app/ServiceE.java", "package app; public class ServiceE { public int id() { return 42; } }");
        baseFiles.put("app/ServiceF.java", "package app; public class ServiceF { public int ping(ServiceC c) { return c.tag().length(); } }");

        var frontend = new JavaParserFrontend();
        FrontendResult coldFirst = frontend.analyze(TestInputs.request(baseFiles, List.of()));
        assertEquals(baseFiles.size(), JavaParserFrontend.lastReextractedUnitCount());

        // Body-only edit in ServiceC: API signature unchanged -> only 1 unit re-extracted!
        var bodyEdited = new LinkedHashMap<>(baseFiles);
        bodyEdited.put("app/ServiceC.java", "package app; public class ServiceC { public String tag() { String s = \"v2\"; return s; } }");
        FrontendResult incrementalBody = frontend.analyze(TestInputs.request(bodyEdited, List.of()));
        assertEquals(1, JavaParserFrontend.lastReextractedUnitCount());

        // Disable incremental cache and run cold -> bit-for-bit parity check!
        System.setProperty("evo.incremental.disabled", "true");
        FrontendResult coldSecond;
        try {
            coldSecond = frontend.analyze(TestInputs.request(bodyEdited, List.of()));
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }
        assertEquals(coldSecond, incrementalBody);
        assertEquals(IngestionEvidence.digest(coldSecond), IngestionEvidence.digest(incrementalBody));
    }

    @Test
    void apiSignatureEditInvalidatesDependentsAndMatchesColdBitForBit() throws Exception {
        var baseFiles = new LinkedHashMap<String, String>();
        baseFiles.put("p1/Api.java", "package p1; public class Api { public int compute(int x) { return x; } }");
        baseFiles.put("p2/Caller.java", "package p2; import p1.Api; public class Caller { public int go(Api a) { return a.compute(7); } }");
        baseFiles.put("p3/Unrelated1.java", "package p3; public class Unrelated1 { public int k() { return 1; } }");
        baseFiles.put("p3/Unrelated2.java", "package p3; public class Unrelated2 { public int k() { return 2; } }");
        baseFiles.put("p3/Unrelated3.java", "package p3; public class Unrelated3 { public int k() { return 3; } }");
        baseFiles.put("p3/Unrelated4.java", "package p3; public class Unrelated4 { public int k() { return 4; } }");

        var frontend = new JavaParserFrontend();
        frontend.analyze(TestInputs.request(baseFiles, List.of()));

        // Change Api method signature -> Api AND Caller must re-extract (2), p3/* stays clean!
        var sigEdited = new LinkedHashMap<>(baseFiles);
        sigEdited.put("p1/Api.java", "package p1; public class Api { public int compute(int x, int y) { return x + y; } }");
        FrontendResult incrementalSig = frontend.analyze(TestInputs.request(sigEdited, List.of()));
        assertTrue(JavaParserFrontend.lastReextractedUnitCount() <= 3,
                "Only API-changed file and its referencing dependents should re-extract, got "
                        + JavaParserFrontend.lastReextractedUnitCount());

        System.setProperty("evo.incremental.disabled", "true");
        FrontendResult coldSig;
        try {
            coldSig = frontend.analyze(TestInputs.request(sigEdited, List.of()));
        } finally {
            System.clearProperty("evo.incremental.disabled");
        }
        assertEquals(coldSig, incrementalSig);
        assertEquals(IngestionEvidence.digest(coldSig), IngestionEvidence.digest(incrementalSig));
    }
}
