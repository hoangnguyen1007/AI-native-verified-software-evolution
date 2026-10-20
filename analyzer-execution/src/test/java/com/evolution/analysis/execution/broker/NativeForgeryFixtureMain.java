package com.evolution.analysis.execution.broker;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.output.OutputImporter;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.CapturedBuildEvidence;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Verifies authored real-VM malicious-plugin output after externally observed VM termination.
 * Fixture receipts attest captured bytes only; no native capture method is qualified here.
 */
public final class NativeForgeryFixtureMain {
    static ContentDigest d(String value){return ContentDigest.sha256Utf8(value);}
    static CapturedBuildEvidence.Manifest expected(){
        return new CapturedBuildEvidence.Manifest(d("authored-forgery-context"),d("authored-forgery-selection"),d("authored-forgery-inputs"),new VersionedIdentifier("capture.gradle.authored","8.14.6-fixture"),d("authored-jdk-pin"),List.of(d("legitimate-compiler-entry-a"),d("legitimate-compiler-entry-b")),List.of(),List.of("--release","21"),List.of());
    }
    static EvidenceVerifier.SignatureEnvelope sign(Object payload,String id,KeyPair key)throws Exception{
        var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(CanonicalJson.write(Map.of("keyId",id,"payload",payload)).getBytes(java.nio.charset.StandardCharsets.UTF_8));return new EvidenceVerifier.SignatureEnvelope(id,Base64.getEncoder().encodeToString(signer.sign()));
    }
    static Map<String,Object> read(Path path)throws IOException{
        if(Files.size(path)>1048576)throw new IOException("FIXTURE_RECORD_WORKER_REQUIRED");return GraphJson.object(GraphJson.read(Files.readString(path),new GraphJson.Limits(1048576,32,100000)));
    }
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]);var result=read(root.resolve("online-result.json"));var stopped=read(Path.of(args[1]));
        if(!"Off".equals(stopped.get("state"))||!"01a6f92c-d952-4bd6-abf4-63c5437e71b2".equals(stopped.get("vmId"))||!Boolean.FALSE.equals(stopped.get("isolationQualified")))throw new IOException("AUTHORITATIVE_CANDIDATE_TERMINATION_REQUIRED");
        if(!"forged-capture".equals(result.get("scenario"))||!java.math.BigDecimal.ZERO.equals(result.get("buildExit")))throw new IOException("FORGED_FIXTURE_DID_NOT_COMPLETE");
        boolean agentMatches=ContentDigest.sha256(Files.readAllBytes(Path.of("analyzer-execution/src/main/resources/guest/runner.py"))).value().substring(7).equals(result.get("protectedAgentSha256"));
        if(!agentMatches)throw new IOException("PROTECTED_AGENT_PIN_MISMATCH");
        var cas=new StreamingContentStore(root.resolve("verification-cas"));var imported=new TreeMap<String,ContentHandle>();
        var importer=new OutputImporter(cas);
        for(var entry:GraphJson.object(result.get("outputs")).entrySet()){
            var metadata=GraphJson.object(entry.getValue());String hex=GraphJson.string(metadata.get("sha256"));var handle=new ContentHandle(new ContentDigest("sha256:"+hex),((java.math.BigDecimal)metadata.get("size")).longValueExact());
            var declared=new OutputImporter.Entry(LogicalPath.utf8(entry.getKey()),OutputImporter.Kind.FILE,Optional.of(handle),"authored-output",expected().context(),CanonicalJson.sha256(result.get("launch")));
            var captured=importer.capture(declared,e->Files.newInputStream(root.resolve("outputs").resolve(handle.digest().value().substring(7))),expected().context(),1048576,()->false);
            if(captured.disposition()!=OutputImporter.Disposition.CAPTURED)throw new IOException("FIXTURE_OUTPUT_IMPORT_FAILED");imported.put(entry.getKey(),captured.captured().orElseThrow());
        }
        var nativeCapture=NativeCaptureParser.read(imported.get("capture/claimed.json"),cas,GraphJson.Limits.defaults());
        if(nativeCapture.trust()!=EvidenceVerifier.Trust.PRODUCER_ASSERTION||nativeCapture.declared().observation().trust()!=CapturedBuildEvidence.Trust.VALIDATED_OBSERVATION)throw new IOException("FORGED_TRUST_FLAG_WAS_PROMOTED");
        if(nativeCapture.declared().observation().manifest().orderedClasspath().equals(expected().orderedClasspath()))throw new IOException("MALICIOUS_PLUGIN_DID_NOT_REWRITE_CAPTURE");
        try(var input=cas.open(imported.get("capture/tamper.json"))){
            if(!Boolean.TRUE.equals(GraphJson.object(GraphJson.read(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),GraphJson.Limits.defaults())).get("protectedAgentWriteDenied")))throw new IOException("PROTECTED_AGENT_TAMPER_NOT_DENIED");
        }
        var claim=cas.put(new ByteArrayInputStream(CanonicalJson.write(nativeCapture.declared().observation()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
        var controller=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();var validator=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),List.of());
        var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1",GraphJson.string(result.get("attempt")),1,CanonicalJson.sha256(result.get("launch")),d("authored-unqualified-candidate-profile"),CanonicalJson.sha256(Map.of("outputs",imported,"census","OPEN")),d("authored-open-material-frontier"),CanonicalJson.sha256(stopped));
        var version=new VersionedIdentifier("capture.gradle.authored","8.14.6-fixture");
        var expected=new EvidenceVerifier.Expected(receipt.launch(),receipt.profile(),1,expected().context(),expected().inputClosure(),claim,version,version,d("authored-tool"));
        // Even granting the attacker the exact external receipt/scope cannot give it the private validator key.
        var forged=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),claim.digest(),expected.scope(),expected.inputClosure(),expected.tool(),version,version,List.of("context","inputs","census"),true);
        var fakeSignature=new EvidenceVerifier.SignatureEnvelope("validator",Base64.getEncoder().encodeToString(new byte[64]));
        var signature=sign(receipt,"controller",controller);var decision=verifier.verify(expected,receipt,signature,forged,fakeSignature);
        if(decision.outcome()!=EvidenceVerifier.Outcome.REJECT||decision.trust()!=EvidenceVerifier.Trust.CAPTURED_BYTES||verifier.verifyAccepted(expected,receipt,signature,forged,fakeSignature,cas).isPresent())throw new IOException("FORGED_NATIVE_OBSERVATION_ACCEPTED");
        System.out.println(CanonicalJson.write(Map.of("schema","authored-native-forgery-result:m8.4-v1","nativeExit",0,"importedObjects",imported.size(),"parserTrust",nativeCapture.trust(),"decision",decision.outcome(),"receiptTrust",decision.trust(),"reason",decision.reason(),"protectedAgentUnchanged",agentMatches,"qualified",false)));
        Files.writeString(root.resolve("controller-receipt.json"),CanonicalJson.write(Map.of("receipt",receipt,"signature",signature,"controllerPublicKey",Base64.getEncoder().encodeToString(controller.getPublic().getEncoded()))),StandardOpenOption.CREATE_NEW);
    }
}
