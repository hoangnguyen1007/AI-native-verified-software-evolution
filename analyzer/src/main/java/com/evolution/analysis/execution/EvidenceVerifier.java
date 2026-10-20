package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.acquisition.streaming.RepositoryContentStore;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.readiness.CapturedBuildEvidence;
import java.security.*;
import java.util.*;

/** Receipt authenticity and independently issued capture attestation are separate checks.
 * Keys/method registrations are operator-owned; nothing in target output can register one. */
public final class EvidenceVerifier {
    public enum Outcome { ACCEPT, REJECT, INCOMPLETE }
    public enum Trust { CAPTURED_BYTES, PRODUCER_ASSERTION, VALIDATED_OBSERVATION }
    public record Receipt(String schema,String attempt,long fence,ContentDigest launch,ContentDigest profile,
            ContentDigest outputManifest,ContentDigest materials,ContentDigest termination) {
        public Receipt {
            if(!"execution-protected-receipt:m8.4-v1".equals(schema)||fence<1)throw new IllegalArgumentException("INVALID_RECEIPT");
            ExecutionGraph.text(attempt);Objects.requireNonNull(launch);Objects.requireNonNull(profile);Objects.requireNonNull(outputManifest);Objects.requireNonNull(materials);Objects.requireNonNull(termination);
        }
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
    }
    public record Attestation(String schema,ContentDigest receipt,ContentDigest claim,ContentDigest expectedScope,
            ContentDigest inputClosure,ContentDigest tool,VersionedIdentifier method,VersionedIdentifier predicate,
            List<String> passedChecks,boolean denominatorClosed) {
        public Attestation {
            if(!"execution-protected-attestation:m8.4-v1".equals(schema))throw new IllegalArgumentException("INVALID_ATTESTATION");
            Objects.requireNonNull(receipt);Objects.requireNonNull(claim);Objects.requireNonNull(expectedScope);Objects.requireNonNull(inputClosure);Objects.requireNonNull(tool);Objects.requireNonNull(method);Objects.requireNonNull(predicate);passedChecks=ExecutionGraph.strings(passedChecks);
        }
    }
    public record SignatureEnvelope(String keyId,String signatureBase64) {public SignatureEnvelope {ExecutionGraph.text(keyId);Objects.requireNonNull(signatureBase64);}}
    public record Method(VersionedIdentifier method,VersionedIdentifier predicate,ContentDigest tool,List<String> requiredChecks) {
        public Method {Objects.requireNonNull(method);Objects.requireNonNull(predicate);Objects.requireNonNull(tool);requiredChecks=ExecutionGraph.strings(requiredChecks);if(requiredChecks.isEmpty())throw new IllegalArgumentException("EMPTY_PREDICATE");}
    }
    public record Expected(ContentDigest launch,ContentDigest profile,long fence,ContentDigest scope,ContentDigest inputClosure,
            ContentHandle claim,VersionedIdentifier method,VersionedIdentifier predicate,ContentDigest tool) {
        public Expected {Objects.requireNonNull(launch);Objects.requireNonNull(profile);Objects.requireNonNull(scope);Objects.requireNonNull(inputClosure);Objects.requireNonNull(claim);Objects.requireNonNull(method);Objects.requireNonNull(predicate);Objects.requireNonNull(tool);if(fence<1)throw new IllegalArgumentException("INVALID_FENCE");}
    }
    public record Decision(Outcome outcome,Trust trust,String reason,ContentDigest receipt,ContentDigest claim) {}
    private final Map<String,PublicKey> controllers,validators;
    private final Map<VersionedIdentifier,Method> methods;
    private final ContentDigest policyIdentity;
    public static final class VerifiedClaim {
        private final Expected expected;private final Attestation attestation;private final ContentDigest policy;
        private VerifiedClaim(Expected expected,Attestation attestation,ContentDigest policy){this.expected=expected;this.attestation=attestation;this.policy=policy;}
        public Expected expected(){return expected;}
        public List<String> checks(){return attestation.passedChecks();}
        public ContentDigest policy(){return policy;}
        public ContentDigest identity(){return CanonicalJson.sha256(Map.of("schema","execution-verified-claim:m8.4-v1","scope",expected.scope(),"inputs",expected.inputClosure(),"claim",expected.claim(),"method",expected.method(),"predicate",expected.predicate(),"tool",expected.tool(),"checks",checks(),"policy",policy));}
    }
    public EvidenceVerifier(Map<String,PublicKey> controllers,Map<String,PublicKey> validators,Collection<Method> methods) {
        this.controllers=Map.copyOf(controllers);this.validators=Map.copyOf(validators);var registry=new TreeMap<VersionedIdentifier,Method>();
        for(var key:this.controllers.values())for(var validator:this.validators.values())if(Arrays.equals(key.getEncoded(),validator.getEncoded()))throw new IllegalArgumentException("CAPTURE_AUTHORITY_NOT_SEPARATED");
        for(var method:methods)if(registry.putIfAbsent(method.method(),method)!=null)throw new IllegalArgumentException("DUPLICATE_CAPTURE_METHOD");this.methods=Map.copyOf(registry);
        var controllerPins=new TreeMap<String,ContentDigest>();controllers.forEach((id,key)->controllerPins.put(id,ContentDigest.sha256(key.getEncoded())));
        var validatorPins=new TreeMap<String,ContentDigest>();validators.forEach((id,key)->validatorPins.put(id,ContentDigest.sha256(key.getEncoded())));
        policyIdentity=CanonicalJson.sha256(Map.of("schema","execution-trust-policy:m8.4-v1","controllers",controllerPins,"validators",validatorPins,"methods",registry.values()));
    }
    public ContentDigest policyIdentity(){return policyIdentity;}
    public Optional<VerifiedClaim> verifyAccepted(Expected expected,Receipt receipt,SignatureEnvelope controllerSignature,Attestation attestation,SignatureEnvelope validatorSignature,RepositoryContentStore content)throws java.io.IOException {
        var decision=verify(expected,receipt,controllerSignature,attestation,validatorSignature);
        if(decision.outcome()!=Outcome.ACCEPT||!content.verify(expected.claim()))return Optional.empty();
        return Optional.of(new VerifiedClaim(expected,attestation,policyIdentity));
    }
    public Decision verify(Expected expected,Receipt receipt,SignatureEnvelope controllerSignature,Attestation attestation,SignatureEnvelope validatorSignature) {
        if(!authentic(receipt,controllerSignature,controllers))return decision(expected,receipt,Outcome.REJECT,Trust.PRODUCER_ASSERTION,"RECEIPT_AUTHENTICATION_FAILED");
        if(!expected.launch().equals(receipt.launch())||!expected.profile().equals(receipt.profile())||expected.fence()!=receipt.fence())return decision(expected,receipt,Outcome.REJECT,Trust.CAPTURED_BYTES,"RECEIPT_SCOPE_MISMATCH");
        if(!authentic(attestation,validatorSignature,validators))return decision(expected,receipt,Outcome.REJECT,Trust.CAPTURED_BYTES,"ATTESTATION_AUTHENTICATION_FAILED");
        var method=methods.get(expected.method());
        if(method==null||!method.predicate().equals(expected.predicate())||!method.tool().equals(expected.tool()))return decision(expected,receipt,Outcome.INCOMPLETE,Trust.CAPTURED_BYTES,"CAPTURE_METHOD_UNQUALIFIED");
        if(!attestation.receipt().equals(receipt.identity())||!attestation.claim().equals(expected.claim().digest())||!attestation.expectedScope().equals(expected.scope())
                ||!attestation.inputClosure().equals(expected.inputClosure())||!attestation.tool().equals(expected.tool())||!attestation.method().equals(expected.method())||!attestation.predicate().equals(expected.predicate()))return decision(expected,receipt,Outcome.REJECT,Trust.CAPTURED_BYTES,"OBSERVATION_SCOPE_MISMATCH");
        if(!attestation.denominatorClosed()||!attestation.passedChecks().containsAll(method.requiredChecks()))return decision(expected,receipt,Outcome.INCOMPLETE,Trust.CAPTURED_BYTES,"PROMOTION_PREDICATE_INCOMPLETE");
        return decision(expected,receipt,Outcome.ACCEPT,Trust.VALIDATED_OBSERVATION,"");
    }
    private static Decision decision(Expected e,Receipt r,Outcome outcome,Trust trust,String reason){return new Decision(outcome,trust,reason,r.identity(),e.claim().digest());}
    private static boolean authentic(Object payload,SignatureEnvelope envelope,Map<String,PublicKey> keys){
        var key=keys.get(envelope.keyId());if(key==null)return false;
        try{var verifier=Signature.getInstance("Ed25519");verifier.initVerify(key);verifier.update(CanonicalJson.write(Map.of("keyId",envelope.keyId(),"payload",payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8));return verifier.verify(Base64.getDecoder().decode(envelope.signatureBase64()));}
        catch(GeneralSecurityException|IllegalArgumentException invalid){return false;}
    }
    public Optional<CapturedBuildEvidence.Validated> validateCaptured(CapturedBuildEvidence.Manifest expectedManifest,CapturedBuildEvidence.Observation observation,
            Expected expected,Receipt receipt,SignatureEnvelope controllerSignature,Attestation attestation,SignatureEnvelope validatorSignature) {
        var decision=verify(expected,receipt,controllerSignature,attestation,validatorSignature);
        if(decision.outcome()!=Outcome.ACCEPT||!expected.scope().equals(expectedManifest.context())||!expected.inputClosure().equals(expectedManifest.inputClosure())||!expected.claim().digest().equals(observation.identity())||!observation.protectedReceipt().equals(receipt.identity()))return Optional.empty();
        return CapturedBuildEvidence.validate(expectedManifest,observation,ignored->true);
    }
}
