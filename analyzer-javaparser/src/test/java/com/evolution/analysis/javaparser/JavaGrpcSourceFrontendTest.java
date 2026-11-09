package com.evolution.analysis.javaparser;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaGrpcSourceFrontendTest {
    @Test void generatedJavaClientsAndOverridesKeepOriginalBomCrLfAndUnicodeRanges()throws Exception {
        String text="\ufeff// 😀\r\nclass Ads extends shop.AdServiceGrpc.AdServiceImplBase {\r\n @Override public void getAds(Req r, StreamObserver<Reply> out) {}\r\n void call() { var client = shop.AdServiceGrpc.newBlockingStub(channel); client.getAds(req); }\r\n}\r\n";
        var facts=new ArrayList<JavaGrpcSourceFrontend.Fact>();var summary=new JavaGrpcSourceFrontend().parse(text.getBytes(StandardCharsets.UTF_8),facts::add,()->false);
        assertEquals(0,summary.errors());var calls=facts.stream().filter(f->f.category().equals("RPC_CALL_CANDIDATE")).toList();assertEquals(1,calls.size());
        assertEquals("AdService",calls.getFirst().attributes().get("service"));assertEquals("client.getAds(req)",text.substring(calls.getFirst().span().start(),calls.getFirst().span().end()));
        assertEquals(1,facts.stream().filter(f->f.category().equals("RPC_HANDLER_CANDIDATE")).count());assertTrue(summary.toolIdentity().startsWith("sha256:"));
    }
    @Test void parameterShadowingCommentsAndUnmappedEscapesCannotInventGrpcCalls()throws Exception {
        String text="class C { AdServiceGrpc.AdServiceBlockingStub client; void f(Object client) { client.getAds(req); } String fake=\"AdServiceGrpc.newBlockingStub(channel).getAds(req)\"; }";
        var facts=new ArrayList<JavaGrpcSourceFrontend.Fact>();new JavaGrpcSourceFrontend().parse(text.getBytes(StandardCharsets.UTF_8),facts::add,()->false);assertTrue(facts.isEmpty());
        assertThrows(java.io.IOException.class,()->new JavaGrpcSourceFrontend().parse("class Grpc { String x=\"\\u0020\"; }".getBytes(StandardCharsets.UTF_8),facts::add,()->false));
        assertThrows(java.io.IOException.class,()->new JavaGrpcSourceFrontend().parse(new byte[0],facts::add,()->true));
        assertFalse(JavaGrpcSourceFrontend.candidate("class Localization { String bullet = \"\\u2022\"; }".getBytes(StandardCharsets.UTF_8)));
        assertTrue(JavaGrpcSourceFrontend.candidate("class Ads extends shop.AdServiceGrpc.AdServiceImplBase {}".getBytes(StandardCharsets.UTF_8)));
    }
}
