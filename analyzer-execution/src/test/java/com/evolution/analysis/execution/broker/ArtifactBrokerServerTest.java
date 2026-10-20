package com.evolution.analysis.execution.broker;

import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactBrokerServerTest {
    @TempDir Path root;
    @Test void exposesOnlyRegisteredRepositoryThroughLoopbackAndCapturedBytes()throws Exception{
        var calls=new AtomicInteger();var records=new ArrayList<ArtifactBroker.Material>();
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(URI.create("https://repo.example/repository/"))),false,new StreamingContentStore(root),
                (uri,cancelled)->{calls.incrementAndGet();assertEquals("https://repo.example/repository/a.jar",uri.toString());return new ByteArrayInputStream(new byte[]{1,2,3});},records::add,Clock.systemUTC());
        var grant=broker.grant("attempt",1,Duration.ofMinutes(1),100);
        try(var server=new ArtifactBrokerServer(broker,grant,1,Map.of("central",URI.create("https://repo.example/repository/")),new StreamingContentStore(root),Map.of())){
            URI uri=server.baseUri().resolve("central/a.jar");var client=HttpClient.newHttpClient();
            var response=client.send(HttpRequest.newBuilder(uri).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200,response.statusCode());assertArrayEquals(new byte[]{1,2,3},response.body());assertEquals(1,calls.get());assertEquals(ArtifactBroker.State.ACQUIRED,records.getFirst().state());
            assertTrue(server.baseUri().getHost().equals("127.0.0.1"));
            for(String suffix:List.of("foreign/a.jar","central/../a.jar","central/%2e%2e/a.jar","central/a.jar?secret=x")){
                assertEquals(403,client.send(HttpRequest.newBuilder(server.baseUri().resolve(suffix)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            }
            assertEquals(1,calls.get());
            assertEquals(405,client.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(5,records.stream().filter(r->r.state()==ArtifactBroker.State.DENIED).count());
            assertTrue(records.stream().allMatch(r->r.endpoint().isEmpty()||r.endpoint().orElseThrow().getRawQuery()==null));
        }
    }
    @Test void offlineServesOnlyPinnedCapturedClosureAndRevocationStopsDelivery()throws Exception{
        var store=new StreamingContentStore(root);var handle=store.put(new ByteArrayInputStream(new byte[]{7}),()->false);var endpoint=URI.create("https://repo.example/a.jar");
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(URI.create("https://repo.example/"))),true,store,(uri,c)->{fail("offline");return null;},r->{},Clock.systemUTC());
        var grant=broker.grant("attempt",2,Duration.ofMinutes(1),100);
        try(var server=new ArtifactBrokerServer(broker,grant,2,Map.of("central",URI.create("https://repo.example/")),store,Map.of(endpoint,handle))){
            var client=HttpClient.newHttpClient();var request=HttpRequest.newBuilder(server.baseUri().resolve("central/a.jar")).GET().build();
            assertEquals(200,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(503,client.send(HttpRequest.newBuilder(server.baseUri().resolve("central/missing.jar")).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            broker.revoke(grant);
            assertEquals(503,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
    @Test void unavailableAuditFailsClosedWithoutTransferOrRecursiveRetry()throws Exception{
        var audits=new AtomicInteger();var transfers=new AtomicInteger();
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(URI.create("https://repo.example/"))),false,new StreamingContentStore(root),
                (uri,c)->{transfers.incrementAndGet();return new ByteArrayInputStream(new byte[]{1});},
                r->{audits.incrementAndGet();throw new IOException("private audit failure");},Clock.systemUTC());
        var grant=broker.grant("attempt",1,Duration.ofMinutes(1),100);
        try(var server=new ArtifactBrokerServer(broker,grant,1,Map.of("central",URI.create("https://repo.example/")),new StreamingContentStore(root),Map.of())){
            var client=HttpClient.newHttpClient();
            var rejected=client.send(HttpRequest.newBuilder(server.baseUri().resolve("foreign/a.jar?secret=x")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(503,rejected.statusCode());assertEquals(0,rejected.body().length);assertEquals(1,audits.get());assertEquals(0,transfers.get());
            var allowed=client.send(HttpRequest.newBuilder(server.baseUri().resolve("central/a.jar")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(503,allowed.statusCode());assertEquals(0,allowed.body().length);assertEquals(2,audits.get());assertEquals(1,transfers.get());
        }
    }
}
