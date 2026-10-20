package com.evolution.analysis.execution.qualification;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
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

class SandboxQualificationGateTest {
    @TempDir Path root;
    static ContentDigest d(String s){return ContentDigest.sha256Utf8(s);}
    static final VersionedIdentifier VERSION=new VersionedIdentifier("qualification.authored","1");
    static final Instant NOW=Instant.parse("2026-10-19T10:00:00Z");
    ContentHandle put(StreamingContentStore cas,String value)throws Exception{return cas.put(new ByteArrayInputStream(value.getBytes()),()->false);}
    static EvidenceVerifier.SignatureEnvelope sign(Object payload,KeyPair key)throws Exception{
        var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(CanonicalJson.write(Map.of("keyId","harness","payload",payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8));return new EvidenceVerifier.SignatureEnvelope("harness",Base64.getEncoder().encodeToString(signer.sign()));
    }
    @Test void forgedOrIncompleteCertificateCannotMintQualifiedProfile()throws Exception{
        var cas=new StreamingContentStore(root);var pin=put(cas,"pinned bytes");var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var attacker=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var suite=new SandboxQualificationGate.Suite(VERSION,List.of("one","two"));var gate=new SandboxQualificationGate(suite,VERSION,Map.of("harness",key.getPublic()),cas,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofDays(1),Set::of,Set::of);
        var profile=new SandboxQualificationGate.Profile(VERSION,pin,pin,Optional.of(pin),"linux","x86_64",d("enforcement"),d("transport"),d("resources"),d("network"),Optional.empty());
        var controls=List.of(new SandboxQualificationGate.Control("one",SandboxQualificationGate.Outcome.PASS,SandboxQualificationGate.Origin.REAL_BACKEND,List.of(pin),""));
        var partial=new SandboxQualificationGate.Certificate("execution-qualification-certificate:m8.4-v1",profile.underTest(),VERSION,VERSION,gate.policy(),controls,NOW.minusSeconds(1),NOW.plusSeconds(60),SandboxQualificationGate.Outcome.PASS);
        assertEquals(SandboxQualificationGate.Decision.REJECTED,gate.evaluate(profile,partial,sign(partial,attacker)).decision());
        assertEquals(SandboxQualificationGate.Decision.INCOMPLETE,gate.evaluate(profile,partial,sign(partial,key)).decision());
        assertTrue(gate.evaluate(profile,partial,sign(partial,key)).profile().isEmpty());
    }
    @Test void exactPinsActualEvidenceFreshnessAndLiveRevocationAllGateAdmission()throws Exception{
        var cas=new StreamingContentStore(root);var pin=put(cas,"pinned bytes");var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var revoked=new AtomicReference<Set<ContentDigest>>(Set.of());
        var gate=new SandboxQualificationGate(new SandboxQualificationGate.Suite(VERSION,List.of("one")),VERSION,Map.of("harness",key.getPublic()),cas,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofDays(1),revoked::get,Set::of);
        var profile=new SandboxQualificationGate.Profile(VERSION,pin,pin,Optional.empty(),"linux","x86_64",d("e"),d("t"),d("r"),d("n"),Optional.empty());
        var control=new SandboxQualificationGate.Control("one",SandboxQualificationGate.Outcome.PASS,SandboxQualificationGate.Origin.REAL_BACKEND,List.of(pin),"");
        var certificate=new SandboxQualificationGate.Certificate("execution-qualification-certificate:m8.4-v1",profile.underTest(),VERSION,VERSION,gate.policy(),List.of(control),NOW.minusSeconds(1),NOW.plusSeconds(60),SandboxQualificationGate.Outcome.PASS);
        var result=gate.evaluate(profile,certificate,sign(certificate,key));assertEquals(SandboxQualificationGate.Decision.PASS,result.decision());
        var token=result.profile().orElseThrow();assertTrue(gate.admissible(token));assertEquals(certificate.identity(),token.descriptor().qualification().orElseThrow());
        revoked.set(null);
        assertFalse(gate.admissible(token),"unavailable revocation state must close admission");
        assertEquals("QUALIFICATION_REVOCATION_UNAVAILABLE",gate.evaluate(profile,certificate,sign(certificate,key)).reason());
        revoked.set(Set.of());
        var changed=new SandboxQualificationGate.Profile(VERSION,pin,pin,Optional.empty(),"linux","x86_64",d("changed"),d("t"),d("r"),d("n"),Optional.empty());
        assertEquals(SandboxQualificationGate.Decision.REJECTED,gate.evaluate(changed,certificate,sign(certificate,key)).decision());
        revoked.set(Set.of(certificate.identity()));assertFalse(gate.admissible(token));
    }
    @Test void mockEvidenceAndExpiredOrOpenControlsNeverPassEvenWhenSigned()throws Exception{
        var cas=new StreamingContentStore(root);var pin=put(cas,"data");var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var gate=new SandboxQualificationGate(new SandboxQualificationGate.Suite(VERSION,List.of("one")),VERSION,Map.of("harness",key.getPublic()),cas,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofDays(1),Set::of,Set::of);
        var profile=new SandboxQualificationGate.Profile(VERSION,pin,pin,Optional.empty(),"linux","x86_64",d("e"),d("t"),d("r"),d("n"),Optional.empty());
        for(var origin:SandboxQualificationGate.Origin.values()){
            var control=new SandboxQualificationGate.Control("one",SandboxQualificationGate.Outcome.PASS,origin,List.of(pin),"");
            var expired=new SandboxQualificationGate.Certificate("execution-qualification-certificate:m8.4-v1",profile.underTest(),VERSION,VERSION,gate.policy(),List.of(control),NOW.minusSeconds(60),NOW.minusSeconds(1),SandboxQualificationGate.Outcome.PASS);
            assertNotEquals(SandboxQualificationGate.Decision.PASS,gate.evaluate(profile,expired,sign(expired,key)).decision());
            if(origin==SandboxQualificationGate.Origin.MOCK){
                var fresh=new SandboxQualificationGate.Certificate(expired.schema(),expired.profileUnderTest(),VERSION,VERSION,gate.policy(),List.of(control),NOW.minusSeconds(1),NOW.plusSeconds(60),SandboxQualificationGate.Outcome.PASS);
                assertEquals(SandboxQualificationGate.Decision.INCOMPLETE,gate.evaluate(profile,fresh,sign(fresh,key)).decision());
            }
        }
        assertEquals(16,SandboxQualificationGate.hyperVSuite().controls().size());
    }
}
