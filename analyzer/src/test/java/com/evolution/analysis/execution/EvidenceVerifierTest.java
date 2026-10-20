package com.evolution.analysis.execution;

import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.analysis.execution.ExecutionGraphTest.*;
import static com.evolution.analysis.execution.EvidenceVerifier.*;

class EvidenceVerifierTest {
    static SignatureEnvelope sign(Object payload,String id,KeyPair key)throws Exception {
        var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(CanonicalJson.write(Map.of("keyId",id,"payload",payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8));return new SignatureEnvelope(id,Base64.getEncoder().encodeToString(signer.sign()));
    }
    @Test void controllerReceiptCannotPromoteTargetAssertionsWithoutIndependentAttestation()throws Exception{
        var controller=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var validator=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var attacker=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),List.of(new Method(VERSION,VERSION,digest("tool"),List.of("context","inputs","census"))));
        var receipt=new Receipt("execution-protected-receipt:m8.4-v1","attempt",1,digest("launch"),digest("profile"),digest("outputs"),digest("materials"),digest("termination"));
        var expected=new Expected(receipt.launch(),receipt.profile(),1,digest("scope"),digest("inputs"),new ContentHandle(digest("claim"),100),VERSION,VERSION,digest("tool"));
        var assertion=new Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),digest("claim"),expected.scope(),expected.inputClosure(),expected.tool(),VERSION,VERSION,List.of("context","inputs","census"),true);
        var cs=sign(receipt,"controller",controller);
        assertEquals(Outcome.REJECT,verifier.verify(expected,receipt,cs,assertion,sign(assertion,"validator",attacker)).outcome());
        assertEquals(Outcome.ACCEPT,verifier.verify(expected,receipt,cs,assertion,sign(assertion,"validator",validator)).outcome());
        assertEquals(Outcome.REJECT,verifier.verify(expected,receipt,sign(receipt,"controller",attacker),assertion,sign(assertion,"validator",validator)).outcome());
        var open=new Attestation(assertion.schema(),assertion.receipt(),assertion.claim(),assertion.expectedScope(),assertion.inputClosure(),assertion.tool(),VERSION,VERSION,assertion.passedChecks(),false);
        assertEquals(Outcome.INCOMPLETE,verifier.verify(expected,receipt,cs,open,sign(open,"validator",validator)).outcome());
        var wrongFence=new Expected(expected.launch(),expected.profile(),2,expected.scope(),expected.inputClosure(),expected.claim(),VERSION,VERSION,expected.tool());
        assertEquals(Outcome.REJECT,verifier.verify(wrongFence,receipt,cs,assertion,sign(assertion,"validator",validator)).outcome());
    }
    @Test void sameKeyCannotCertifyItsOwnCaptureAndMissingMethodsStayUnqualified()throws Exception{
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        assertThrows(IllegalArgumentException.class,()->new EvidenceVerifier(Map.of("a",key.getPublic()),Map.of("b",key.getPublic()),List.of()));
    }
}
