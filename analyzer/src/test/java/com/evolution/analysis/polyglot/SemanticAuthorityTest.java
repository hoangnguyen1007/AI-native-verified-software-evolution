package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Authored protocol controls. These keys/methods never qualify a production language engine. */
class SemanticAuthorityTest {
    @Test void separateAuthenticatedPredicatesAreRequiredAndRevocationWithholdsPriorClosure()throws Exception{
        var fixture=new SemanticReconcilerTest.Fixture();var output=fixture.emissions(fixture.requirements);var keys=KeyPairGenerator.getInstance("Ed25519");
        var controller=keys.generateKeyPair();var validator=keys.generateKeyPair();
        var censusChecks=List.of("INDEPENDENT_CENSUS","DOCUMENT_MEMBERSHIP","CATEGORY_CLOSURE");
        var occurrenceChecks=List.of("CALLER_TARGET_ORIGIN","SOURCE_COORDINATES","APPLICABILITY_VALIDATED");
        var methods=List.of(method("census",fixture.request.tool(),censusChecks),method("observations",fixture.request.tool(),occurrenceChecks));
        var verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),methods);
        var old=fixture.request;fixture.request=new LanguageSemanticRequest(old.context(),old.inventory(),old.root(),old.module(),old.sourceSet(),old.variant(),old.language(),old.dialect(),old.documents(),old.census(),old.resolution(),old.provider(),old.tool(),verifier.policyIdentity(),old.workPolicy());
        var request=fixture.request;var payload=fixture.content.text(CanonicalJson.write(SemanticReconciler.proofPayload(request,output)));
        var proofs=new HashMap<String,EvidenceVerifier.VerifiedClaim>();
        for(var method:methods){
            var expected=new EvidenceVerifier.Expected(ContentDigest.sha256Utf8("launch"),ContentDigest.sha256Utf8("profile"),1,request.scope(),request.inputClosure(),payload,method.method(),method.predicate(),method.tool());
            var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1","authored-control",1,expected.launch(),expected.profile(),ContentDigest.sha256Utf8("manifest"),ContentDigest.sha256Utf8("materials"),ContentDigest.sha256Utf8("termination"));
            var attestation=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),payload.digest(),request.scope(),request.inputClosure(),method.tool(),method.method(),method.predicate(),method.requiredChecks(),true);
            var proof=verifier.verifyAccepted(expected,receipt,sign("controller",receipt,controller.getPrivate()),attestation,sign("validator",attestation,validator.getPrivate()),fixture.content).orElseThrow();
            proofs.put(method.predicate().id().substring("polyglot.".length()),proof);
        }
        var reconciler=new SemanticReconciler();
        var complete=reconciler.reconcile(request,output,fixture.tables,fixture.content,proofs,Set.of(),request::policy,gap->{},()->false);
        assertTrue(complete.complete());assertEquals(12,complete.total());
        var revoked=reconciler.reconcile(request,output,fixture.tables,fixture.content,proofs,Set.of(proofs.get("observations").identity()),request::policy,gap->{},()->false);
        assertFalse(revoked.complete());assertNotEquals(complete.semanticIdentity(),revoked.semanticIdentity());
        var missing=reconciler.reconcile(request,output,fixture.tables,fixture.content,Map.of("census",proofs.get("census")),Set.of(),request::policy,gap->{},()->false);
        assertFalse(missing.complete());
        var source=fixture.content.text("export const a=1;");fixture.content.blobs.put(source.digest(),new byte[]{0});
        assertThrows(java.io.IOException.class,()->reconciler.reconcile(request,output,fixture.tables,fixture.content,proofs,Set.of(),request::policy,gap->{},()->false));
    }
    private static EvidenceVerifier.Method method(String role,ContentDigest tool,List<String> checks){return new EvidenceVerifier.Method(new VersionedIdentifier("authored.polyglot-"+role,"v1"),new VersionedIdentifier("polyglot."+role,"m8.4-v1"),tool,checks);}
    private static EvidenceVerifier.SignatureEnvelope sign(String id,Object payload,PrivateKey key)throws Exception{
        var signer=Signature.getInstance("Ed25519");signer.initSign(key);signer.update(CanonicalJson.write(Map.of("keyId",id,"payload",payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new EvidenceVerifier.SignatureEnvelope(id,Base64.getEncoder().encodeToString(signer.sign()));
    }
}
