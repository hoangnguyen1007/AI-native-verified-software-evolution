package com.evolution.analysis.execution.qualification;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import java.time.*;
import java.util.*;
import java.security.*;
import java.io.*;
import java.util.function.Supplier;

/** Authenticates an independently trusted conformance harness and its closed control denominator.
 * A signature authenticates that harness's report; it does not replace running the real controls.
 * Production Hyper-V admission uses hyperVSuite(), never a guest-selected or shortened suite.
 */
public final class SandboxQualificationGate {
    public enum Outcome {PASS,FAIL,NOT_RUN}
    public enum Origin {REAL_BACKEND,MOCK}
    public enum Decision {PASS,FAIL,INCOMPLETE,REJECTED}
    public record Profile(VersionedIdentifier backend,ContentHandle driver,ContentHandle guestImage,Optional<ContentHandle> toolImage,
            String os,String arch,ContentDigest enforcement,ContentDigest transport,ContentDigest resources,ContentDigest network,Optional<ContentDigest> qualification){
        public Profile {
            Objects.requireNonNull(backend);Objects.requireNonNull(driver);Objects.requireNonNull(guestImage);Objects.requireNonNull(toolImage);ExecutionGraph.text(os);ExecutionGraph.text(arch);
            Objects.requireNonNull(enforcement);Objects.requireNonNull(transport);Objects.requireNonNull(resources);Objects.requireNonNull(network);Objects.requireNonNull(qualification);
            if(driver.size()==0||guestImage.size()==0||toolImage.isPresent()&&toolImage.orElseThrow().size()==0)throw new IllegalArgumentException("EMPTY_PROFILE_PIN");
        }
        public ContentDigest identity(){return CanonicalJson.sha256(Map.of("schema","execution-sandbox-view:m8.4-v1","descriptor",this));}
        public ContentDigest underTest(){return withQualification(Optional.empty()).identity();}
        private Profile withQualification(Optional<ContentDigest> value){return new Profile(backend,driver,guestImage,toolImage,os,arch,enforcement,transport,resources,network,value);}
    }
    public record Suite(VersionedIdentifier version,List<String> controls){
        public Suite {Objects.requireNonNull(version);controls=ExecutionGraph.strings(controls);if(controls.isEmpty())throw new IllegalArgumentException("EMPTY_CONFORMANCE_SUITE");}
    }
    public record Control(String control,Outcome outcome,Origin origin,List<ContentHandle> evidence,String reason){
        public Control {
            ExecutionGraph.text(control);Objects.requireNonNull(outcome);Objects.requireNonNull(origin);Objects.requireNonNull(reason);
            evidence=evidence.stream().sorted(Comparator.comparing(ContentHandle::digest).thenComparingLong(ContentHandle::size)).toList();
            if(evidence.stream().distinct().count()!=evidence.size())throw new IllegalArgumentException("DUPLICATE_CONTROL_EVIDENCE");
            if(outcome!=Outcome.PASS&&reason.isEmpty())throw new IllegalArgumentException("CONTROL_REASON_REQUIRED");
        }
    }
    public record Certificate(String schema,ContentDigest profileUnderTest,VersionedIdentifier controller,VersionedIdentifier suite,
            ContentDigest validityPolicy,List<Control> controls,Instant issuedAt,Instant expiresAt,Outcome declared){
        public Certificate {
            if(!"execution-qualification-certificate:m8.4-v1".equals(schema))throw new IllegalArgumentException("UNSUPPORTED_QUALIFICATION_CERTIFICATE");
            Objects.requireNonNull(profileUnderTest);Objects.requireNonNull(controller);Objects.requireNonNull(suite);Objects.requireNonNull(validityPolicy);Objects.requireNonNull(issuedAt);Objects.requireNonNull(expiresAt);Objects.requireNonNull(declared);
            controls=controls.stream().sorted(Comparator.comparing(Control::control)).toList();
            if(controls.stream().map(Control::control).distinct().count()!=controls.size())throw new IllegalArgumentException("DUPLICATE_QUALIFICATION_CONTROL");
        }
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
    }
    /** Cannot be deserialized/minted by producer JSON. Native admission rechecks admissible() at use. */
    public static final class QualifiedProfile {
        private final SandboxQualificationGate owner;private final Profile descriptor;private final Certificate certificate;private final String key;
        private QualifiedProfile(SandboxQualificationGate owner,Profile descriptor,Certificate certificate,String key){this.owner=owner;this.descriptor=descriptor;this.certificate=certificate;this.key=key;}
        public Profile descriptor(){return descriptor;}
    }
    public record Result(Decision decision,String reason,Optional<QualifiedProfile> profile){
        public Result {Objects.requireNonNull(decision);Objects.requireNonNull(reason);Objects.requireNonNull(profile);if((decision==Decision.PASS)!=profile.isPresent())throw new IllegalArgumentException("QUALIFICATION_TOKEN_MISMATCH");}
    }
    private final Suite suite;private final VersionedIdentifier controller;private final Map<String,PublicKey> keys;private final RepositoryContentStore content;
    private final Clock clock;private final Duration maxAge;private final Supplier<Set<ContentDigest>> revoked;private final Supplier<Set<String>> revokedKeys;private final ContentDigest policy;
    public SandboxQualificationGate(Suite suite,VersionedIdentifier controller,Map<String,PublicKey> keys,RepositoryContentStore content,Clock clock,Duration maxAge,Supplier<Set<ContentDigest>> revoked,Supplier<Set<String>> revokedKeys){
        this.suite=Objects.requireNonNull(suite);this.controller=Objects.requireNonNull(controller);this.keys=Map.copyOf(keys);this.content=Objects.requireNonNull(content);this.clock=Objects.requireNonNull(clock);this.maxAge=Objects.requireNonNull(maxAge);this.revoked=Objects.requireNonNull(revoked);this.revokedKeys=Objects.requireNonNull(revokedKeys);
        if(maxAge.isZero()||maxAge.isNegative())throw new IllegalArgumentException("INVALID_QUALIFICATION_LIFETIME");
        var pins=new TreeMap<String,ContentDigest>();keys.forEach((id,key)->pins.put(ExecutionGraph.text(id),ContentDigest.sha256(key.getEncoded())));
        policy=CanonicalJson.sha256(Map.of("schema","execution-qualification-policy:m8.4-v1","suite",suite,"controller",controller,"keys",pins,"maxAge",maxAge.toString()));
    }
    public ContentDigest policy(){return policy;}
    private record Revocations(Set<ContentDigest> certificates,Set<String> keys){}
    private Optional<Revocations> revocations(){
        try{return Optional.of(new Revocations(Set.copyOf(revoked.get()),Set.copyOf(revokedKeys.get())));}
        catch(RuntimeException unavailable){return Optional.empty();}
    }
    public Result evaluate(Profile profile,Certificate certificate,EvidenceVerifier.SignatureEnvelope signature)throws IOException{
        var current=revocations();
        if(current.isEmpty())return reject(Decision.REJECTED,"QUALIFICATION_REVOCATION_UNAVAILABLE");
        if(!authentic(certificate,signature)||current.orElseThrow().keys().contains(signature.keyId()))return reject(Decision.REJECTED,"QUALIFICATION_AUTHENTICATION_FAILED");
        if(!certificate.profileUnderTest().equals(profile.underTest())||!certificate.controller().equals(controller)||!certificate.suite().equals(suite.version())||!certificate.validityPolicy().equals(policy)
                ||profile.qualification().isPresent()&&!profile.qualification().orElseThrow().equals(certificate.identity()))return reject(Decision.REJECTED,"QUALIFICATION_SCOPE_MISMATCH");
        if(!validTime(certificate)||current.orElseThrow().certificates().contains(certificate.identity()))return reject(Decision.REJECTED,"QUALIFICATION_EXPIRED_OR_REVOKED");
        var controls=new TreeMap<String,Control>();certificate.controls().forEach(c->controls.put(c.control(),c));
        if(!new HashSet<>(suite.controls()).containsAll(controls.keySet()))return reject(Decision.REJECTED,"FOREIGN_QUALIFICATION_CONTROL");
        if(controls.values().stream().anyMatch(c->c.outcome()==Outcome.FAIL))return reject(Decision.FAIL,"CONFORMANCE_CONTROL_FAILED");
        if(controls.size()!=suite.controls().size()||certificate.declared()!=Outcome.PASS)return reject(Decision.INCOMPLETE,"CONFORMANCE_DENOMINATOR_OPEN");
        for(var control:controls.values()){
            if(control.outcome()!=Outcome.PASS||control.origin()!=Origin.REAL_BACKEND||control.evidence().isEmpty())return reject(Decision.INCOMPLETE,"REAL_CONFORMANCE_EVIDENCE_REQUIRED");
            for(var handle:control.evidence())if(!verified(handle))return reject(Decision.INCOMPLETE,"CONFORMANCE_EVIDENCE_UNAVAILABLE");
        }
        if(!verified(profile.driver())||!verified(profile.guestImage())||profile.toolImage().isPresent()&&!verified(profile.toolImage().orElseThrow()))return reject(Decision.INCOMPLETE,"PROFILE_PIN_UNAVAILABLE");
        return new Result(Decision.PASS,"",Optional.of(new QualifiedProfile(this,profile.withQualification(Optional.of(certificate.identity())),certificate,signature.keyId())));
    }
    private boolean verified(ContentHandle handle){try{return content.verify(handle);}catch(IOException unavailable){return false;}}
    private boolean validTime(Certificate certificate){
        Instant now=clock.instant();return !now.isBefore(certificate.issuedAt())&&now.isBefore(certificate.expiresAt())&&certificate.expiresAt().isAfter(certificate.issuedAt())&&Duration.between(certificate.issuedAt(),certificate.expiresAt()).compareTo(maxAge)<=0;
    }
    public boolean admissible(QualifiedProfile profile){
        if(profile==null||profile.owner!=this||!validTime(profile.certificate))return false;
        return revocations().map(current->!current.certificates().contains(profile.certificate.identity())&&!current.keys().contains(profile.key)).orElse(false);
    }
    private boolean authentic(Certificate certificate,EvidenceVerifier.SignatureEnvelope envelope){
        var key=keys.get(envelope.keyId());if(key==null||envelope.signatureBase64().length()!=88)return false;
        try{var signature=Signature.getInstance("Ed25519");signature.initVerify(key);signature.update(CanonicalJson.write(Map.of("keyId",envelope.keyId(),"payload",certificate)).getBytes(java.nio.charset.StandardCharsets.UTF_8));return signature.verify(Base64.getDecoder().decode(envelope.signatureBase64()));}
        catch(GeneralSecurityException|IllegalArgumentException invalid){return false;}
    }
    private static Result reject(Decision decision,String reason){return new Result(decision,reason,Optional.empty());}
    public static Suite hyperVSuite(){return new Suite(new VersionedIdentifier("execution.hyperv-conformance","m8.4-v1"),java.util.stream.IntStream.rangeClosed(1,16).mapToObj(i->String.format(java.util.Locale.ROOT,"S%02d",i)).toList());}
}
