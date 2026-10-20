package com.evolution.analysis.execution.qualification;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.filesystem.StreamingContentStore;
import java.io.*;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class QualifiedSandboxExecutorTest {
    @TempDir Path root;
    static final VersionedIdentifier VERSION=new VersionedIdentifier("authored.backend","1");
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static class Driver implements SandboxExecutor {
        int launches,stops;Probe readiness;
        Driver(Optional<ContentDigest> certificate){readiness=new Probe(Availability.AVAILABLE,"",VERSION,certificate);}
        public Probe probe(){return readiness;}
        public Handle launch(Launch request){launches++;return new Handle(request.attempt(),request.fence(),"owned-vm");}
        public Status inspect(Handle handle){return new Status(handle,true,Optional.empty(),Optional.empty(),"");}
        public Status terminate(Handle handle){stops++;return new Status(handle,false,Optional.of(d("off")),Optional.empty(),"");}
        public Status collect(Handle handle){return new Status(handle,false,Optional.of(d("off")),Optional.empty(),"CENSUS_PENDING");}
    }
    @Test void missingQualificationCannotReachLaunchEvenWhenDriverClaimsAvailable()throws Exception{
        var cas=new StreamingContentStore(root);var gate=new SandboxQualificationGate(SandboxQualificationGate.hyperVSuite(),VERSION,Map.of(),cas,Clock.systemUTC(),Duration.ofHours(1),Set::of,Set::of);
        var driver=new Driver(Optional.of(d("invented-certificate")));
        var executor=new QualifiedSandboxExecutor(driver,gate,Optional.empty());
        assertEquals(SandboxExecutor.Availability.UNQUALIFIED,executor.probe().availability());
        assertThrows(IOException.class,()->executor.launch(new SandboxExecutor.Launch(d("p"),d("t"),d("r"),d("s"),new ContentHandle(d("i"),1),d("policy"),"attempt",1)));
        assertEquals(0,driver.launches);
    }
    @Test void exactProfileAndDriverCertificateAreRequiredAndRevocationStillAllowsCleanup()throws Exception{
        var cas=new StreamingContentStore(root);var pin=cas.put(new ByteArrayInputStream(new byte[]{1}),()->false);
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var revoked=new AtomicReference<Set<ContentDigest>>(Set.of());
        var now=SandboxQualificationGateTest.NOW;
        var gate=new SandboxQualificationGate(new SandboxQualificationGate.Suite(VERSION,List.of("authored")),VERSION,Map.of("harness",key.getPublic()),cas,Clock.fixed(now,ZoneOffset.UTC),Duration.ofHours(1),revoked::get,Set::of);
        var profile=new SandboxQualificationGate.Profile(VERSION,pin,pin,Optional.empty(),"linux","x86_64",d("e"),d("t"),d("r"),d("n"),Optional.empty());
        var certificate=new SandboxQualificationGate.Certificate("execution-qualification-certificate:m8.4-v1",profile.underTest(),VERSION,VERSION,gate.policy(),List.of(new SandboxQualificationGate.Control("authored",SandboxQualificationGate.Outcome.PASS,SandboxQualificationGate.Origin.REAL_BACKEND,List.of(pin),"")),now.minusSeconds(1),now.plusSeconds(60),SandboxQualificationGate.Outcome.PASS);
        var token=gate.evaluate(profile,certificate,SandboxQualificationGateTest.sign(certificate,key)).profile().orElseThrow();
        var driver=new Driver(Optional.of(certificate.identity()));var executor=new QualifiedSandboxExecutor(driver,gate,Optional.of(token));
        var request=new SandboxExecutor.Launch(d("p"),d("t"),d("r"),token.descriptor().identity(),pin,d("policy"),"attempt",1);
        assertEquals(SandboxExecutor.Availability.AVAILABLE,executor.probe().availability());
        assertThrows(IOException.class,()->executor.launch(new SandboxExecutor.Launch(request.plan(),request.task(),request.recipe(),d("foreign-profile"),pin,request.policy(),request.attempt(),1)));
        assertEquals(0,driver.launches);
        driver.readiness=new SandboxExecutor.Probe(SandboxExecutor.Availability.AVAILABLE,"",VERSION,Optional.of(d("other-certificate")));
        assertThrows(IOException.class,()->executor.launch(request));assertEquals(0,driver.launches);
        driver.readiness=new SandboxExecutor.Probe(SandboxExecutor.Availability.AVAILABLE,"",VERSION,Optional.of(certificate.identity()));
        var handle=executor.launch(request);assertEquals(1,driver.launches);
        revoked.set(Set.of(certificate.identity()));
        assertEquals(SandboxExecutor.Availability.UNQUALIFIED,executor.probe().availability());
        assertThrows(IOException.class,()->executor.launch(request));assertEquals(1,driver.launches);
        assertFalse(executor.terminate(handle).running());assertEquals(1,driver.stops);
    }
}
