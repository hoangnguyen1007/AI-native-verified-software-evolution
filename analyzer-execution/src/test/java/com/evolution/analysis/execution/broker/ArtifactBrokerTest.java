package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ArtifactBrokerTest {
    @TempDir Path temp;
    static final URI ROOT=URI.create("https://artifacts.example.test/repository/");
    @Test void artifactBytesAreCapturedAndOfflineReplayNeverUsesTransport()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var events=new ArrayList<ArtifactBroker.Material>();
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),false,cas,(uri,cancelled)->new ByteArrayInputStream("artifact".getBytes()),events::add,Clock.systemUTC());
        var grant=broker.grant("attempt",1,Duration.ofMinutes(1),100);
        var material=broker.acquire(grant,1,ROOT.resolve("a.jar"),Optional.empty(),()->false);assertEquals(ArtifactBroker.State.ACQUIRED,material.state());assertEquals(1,events.size());
        var offline=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),true,cas,(uri,cancelled)->{throw new AssertionError("network during replay");},events::add,Clock.systemUTC());
        var replay=offline.grant("replay",2,Duration.ofMinutes(1),0);
        assertEquals(ArtifactBroker.State.REUSED,offline.acquire(replay,2,ROOT.resolve("a.jar"),material.content(),()->false).state());
        assertEquals(ArtifactBroker.State.UNAVAILABLE,offline.acquire(replay,2,ROOT.resolve("missing.jar"),Optional.empty(),()->false).state());
    }
    @Test void escapedPathsOtherOriginsRevokedAndForeignGrantsAreRejected()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var events=new ArrayList<ArtifactBroker.Material>();
        ArtifactBroker.Transfer transfer=(uri,cancelled)->{throw new AssertionError("unauthorized transfer");};
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),false,cas,transfer,events::add,Clock.systemUTC());var grant=broker.grant("a",1,Duration.ofMinutes(1),10);
        for(String uri:List.of("https://evil.test/repository/a","https://artifacts.example.test/repository-evil/a","https://artifacts.example.test/repository/%2e%2e/private","https://artifacts.example.test/repository/%252e%252e/a","https://artifacts.example.test/repository/%2fprivate"))assertEquals(ArtifactBroker.State.DENIED,broker.acquire(grant,1,URI.create(uri),Optional.empty(),()->false).state());
        assertThrows(IOException.class,()->broker.acquire(grant,2,ROOT.resolve("a"),Optional.empty(),()->false));broker.revoke(grant);assertThrows(IOException.class,()->broker.acquire(grant,1,ROOT.resolve("a"),Optional.empty(),()->false));
        var other=new ArtifactBroker(List.of(),true,cas,transfer,events::add,Clock.systemUTC());assertThrows(IOException.class,()->other.acquire(grant,1,ROOT,Optional.empty(),()->false));
    }
    @Test void budgetAndPoisonedExpectedContentNeverBecomeCacheSuccess()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var events=new ArrayList<ArtifactBroker.Material>();
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),false,cas,(uri,cancelled)->new ByteArrayInputStream("not-expected".getBytes()),events::add,Clock.systemUTC());
        var tiny=broker.grant("tiny",1,Duration.ofMinutes(1),1);assertEquals("BROKER_RESOURCE_EXHAUSTED",broker.acquire(tiny,1,ROOT.resolve("a"),Optional.empty(),()->false).reason());
        var grant=broker.grant("full",1,Duration.ofMinutes(1),100);var expected=new ContentHandle(ContentDigest.sha256Utf8("expected"),8);
        assertEquals(ArtifactBroker.State.REJECTED,broker.acquire(grant,1,ROOT.resolve("a"),Optional.of(expected),()->false).state());
    }
    @Test void cancellationIsRecordedAndCannotReturnCachedOrDownloadedSuccess()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var events=new ArrayList<ArtifactBroker.Material>();
        var captured=cas.put(new ByteArrayInputStream(new byte[]{1}),()->false);
        var cancelled=new java.util.concurrent.atomic.AtomicBoolean(true);
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),false,cas,(uri,c)->{cancelled.set(true);return new ByteArrayInputStream(new byte[]{1});},events::add,Clock.systemUTC());
        var grant=broker.grant("cancel",1,Duration.ofMinutes(1),100);
        assertEquals("BROKER_CANCELLED",broker.acquire(grant,1,ROOT.resolve("cached"),Optional.of(captured),cancelled::get).reason());
        cancelled.set(false);
        assertEquals("BROKER_CANCELLED",broker.acquire(grant,1,ROOT.resolve("download"),Optional.empty(),cancelled::get).reason());
        assertEquals(2,events.size());assertTrue(events.stream().allMatch(e->e.state()==ArtifactBroker.State.UNAVAILABLE));
    }
    @Test void transferFailureWithoutAMessageStillProducesAnExplicitMaterialRecord()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var events=new ArrayList<ArtifactBroker.Material>();
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),false,cas,(uri,c)->{throw new IOException();},events::add,Clock.systemUTC());
        var grant=broker.grant("empty-error",1,Duration.ofMinutes(1),100);
        var result=broker.acquire(grant,1,ROOT.resolve("a.jar"),Optional.empty(),()->false);
        assertEquals(ArtifactBroker.State.UNAVAILABLE,result.state());assertEquals("BROKER_TRANSFER_UNAVAILABLE",result.reason());assertEquals(1,events.size());
    }
    @Test void rejectedCredentialBearingEndpointCannotLeakSecretsToTheMaterialLedger()throws Exception{
        var cas=new StreamingContentStore(temp.resolve("cas"));var events=new ArrayList<ArtifactBroker.Material>();
        var broker=new ArtifactBroker(List.of(new ArtifactBroker.Rule(ROOT)),false,cas,(uri,c)->{throw new AssertionError("denied transport");},events::add,Clock.systemUTC());
        var grant=broker.grant("denied",1,Duration.ofMinutes(1),100);
        var uri=URI.create("https://user:fixture-private@artifacts.example.test/repository/a?token=fixture-query#fixture-fragment");
        var result=broker.acquire(grant,1,uri,Optional.empty(),()->false);
        assertEquals(ArtifactBroker.State.DENIED,result.state());assertNull(result.endpoint().orElseThrow().getRawUserInfo());assertNull(result.endpoint().orElseThrow().getRawQuery());assertNull(result.endpoint().orElseThrow().getRawFragment());
        assertEquals(ContentDigest.sha256Utf8(uri.toASCIIString()),result.requestIdentity());
        assertFalse(events.toString().contains("fixture-private"));assertFalse(events.toString().contains("fixture-query"));
        var opaque=broker.acquire(grant,1,URI.create("https:fixture-private?token=fixture-query"),Optional.empty(),()->false);
        assertEquals(ArtifactBroker.State.DENIED,opaque.state());assertTrue(opaque.endpoint().isEmpty());assertFalse(events.toString().contains("fixture-private"));
    }

}
