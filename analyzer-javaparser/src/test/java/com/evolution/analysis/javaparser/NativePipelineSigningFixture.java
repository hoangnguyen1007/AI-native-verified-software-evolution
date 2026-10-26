package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.execution.javac.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.*;
import java.util.*;

/** Explicit in-process test authority. Keys below are public test material, never operator
 * credentials. This class is excluded from production artifacts and accepts only fixture scopes.
 * Real predicate checks belong to the caller; signatures cannot replace those checks.
 */
final class NativePipelineSigningFixture {
    private static final String CONTROLLER_PRIVATE="MC4CAQAwBQYDK2VwBCIEICbM3nuWFWbt9r/sFEej7R2T0K8MUpKCuhAjzExIVSh1",CONTROLLER_PUBLIC="MCowBQYDK2VwAyEAw8eO+AJdUxvn+6U2uTRlt0hxLuXB6F2Wqq8cUcAoJxs=";
    private static final String VALIDATOR_PRIVATE="MC4CAQAwBQYDK2VwBCIEIOkjbwO3RHX2j7eV2Q22XKAZI6oErJHiKgHjZEmiM/H7",VALIDATOR_PUBLIC="MCowBQYDK2VwAyEAq58LVuStl6kF+NO0q3rauqzI9PoIdxtRmeOIdm+EthY=";
    private static final Set<String> QUESTIONS=Set.of("java.output-bytes","java.generated-lineage","java.resolution-context","java.binary-shape","java.source-binary-correspondence","java.semantic-occurrence","java.generator-not-applicable","java.build-validation",JavaEvidenceGapClosure.QUESTION);
    record Issued(EvidenceVerifier.VerifiedClaim authenticated,JavaEvidenceAuthority.Verified verified,ContentHandle envelope){}
    private final RepositoryContentStore content;private final KeyPair controller,validator;
    private final EvidenceVerifier verifier;private final JavaEvidenceAuthority authority;private final ContentHandle tool;
    private final Set<ContentHandle> written=new HashSet<>();
    NativePipelineSigningFixture(RepositoryContentStore content)throws IOException {
        this.content=content;
        try{
            controller=keys(CONTROLLER_PRIVATE,CONTROLLER_PUBLIC);validator=keys(VALIDATOR_PRIVATE,VALIDATOR_PUBLIC);
            var pins=new TreeMap<String,ContentDigest>();
            var pending=new ArrayDeque<Class<?>>(List.of(javassist.bytecode.ClassFile.class,JavaEvidenceCodec.class,JavaEvidenceTables.class,JavaEvidenceLedger.class,JavaEvidenceGapClosure.class,com.evolution.analysis.graph.CanonicalGraphBuilder.class,NativeSourceBinaryFixtureMapping.class,JavaEvidenceAssembler.class,JavaResolutionPlanner.class,JavaSemanticEvidence.class,JavaGeneratedOverlay.class,JavaSourceCorrespondence.class,NativePipelineWiringFixture.class,NativePipelineSigningFixture.class,NativeFixtureClaims.class,NativeJavaSymbolMapping.class,NativeFixtureOwnership.class,NativeMemberExtraction.class,Extraction.class,OriginalSource.class,UnicodeSource.class,JavaParserFrontend.class,NativeContextValidator.class,OccurrenceCensusVerifier.class,CapturedJavacReader.class,EvidenceVerifier.class,JavaEvidenceAuthority.class));
            while(!pending.isEmpty()){
                var type=pending.removeFirst();if(pins.containsKey(type.getName()))continue;
                try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){if(in==null)throw new IOException("FIXTURE_VALIDATOR_CLASS_MISSING");pins.put(type.getName(),ContentDigest.sha256(in.readAllBytes()));}
                pending.addAll(Arrays.asList(type.getDeclaredClasses()));
            }
            try(var in=OccurrenceCensusVerifier.class.getResourceAsStream("NativeEndpointComparison.class")){if(in==null)throw new IOException("FIXTURE_VALIDATOR_CLASS_MISSING");pins.put("com.evolution.analysis.execution.javac.NativeEndpointComparison",ContentDigest.sha256(in.readAllBytes()));}
            try(var in=JavaEvidenceCodec.class.getResourceAsStream(JavaEvidenceCodec.RESOURCE)){if(in==null)throw new IOException("FIXTURE_SCHEMA_MISSING");pins.put("java-evidence.schema",ContentDigest.sha256(in.readAllBytes()));}
            tool=put(Map.of("schema","java-native-fixture-validator:m8.4-v1","classes",pins,"provider",JavaParserFrontend.PROVIDER,"validatorRuntime",Runtime.version().toString(),"publicTestAuthority",true));
            var methods=QUESTIONS.stream().sorted().map(q->new EvidenceVerifier.Method(method(q),predicate(q),tool.digest(),List.copyOf(JavaEvidenceAuthority.requiredChecks(q)))).toList();
            verifier=new EvidenceVerifier(Map.of("pipeline-fixture.controller",controller.getPublic()),Map.of("pipeline-fixture.validator",validator.getPublic()),methods);
            authority=new JavaEvidenceAuthority(verifier.policyIdentity(),content);
        }catch(GeneralSecurityException invalid){throw new IOException("FIXTURE_SIGNING_KEYS",invalid);}
    }
    ContentDigest policy(){return verifier.policyIdentity();}
    ContentHandle tool(){return tool;}
    Issued issue(Map<String,Object> scope,String question,String subject,ContentHandle inputClosure,ContentHandle answer,
            JavaEvidenceTables.Table inputs,Map<String,List<ContentHandle>> checked)throws IOException {
        if(!"native-pipeline-fixture-v1".equals(scope.get("variant"))||!QUESTIONS.contains(question))throw new IOException("FIXTURE_SIGNING_SCOPE_REQUIRED");
        if(!checked.keySet().equals(JavaEvidenceAuthority.requiredChecks(question))||inputs.rows()<1||!content.verify(inputClosure)||!content.verify(answer))throw new IOException("FIXTURE_SIGNING_CHECKS_INCOMPLETE");
        var rows=new ArrayList<Object>();var references=new HashSet<ContentHandle>();for(String name:new TreeSet<>(checked.keySet())){
            var refs=checked.get(name);if(refs==null||refs.isEmpty())throw new IOException("FIXTURE_SIGNING_CHECK_EVIDENCE_REQUIRED");
            for(var ref:refs)if(references.add(ref)&&!content.verify(ref))throw new IOException("FIXTURE_SIGNING_EVIDENCE_CHANGED");
            var check=new TreeMap<String,Object>();check.put("name",name);check.put("outcome","PASS");check.put("evidence",List.copyOf(refs));check.put("reason",null);rows.add(check);
        }
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-evidence-proof:m8.4-v1");fields.put("scope",scope);fields.put("question",question);fields.put("subject",subject);fields.put("predicate",predicate(question));fields.put("validator",new VersionedIdentifier("fixture.native-validator","1"));fields.put("policy",policy());fields.put("answer",answer);fields.put("inputClosure",inputClosure.digest());fields.put("inputs",inputs.wire());fields.put("checks",rows);fields.put("outcome","ACCEPT");fields.put("trust","VALIDATED_OBSERVATION");fields.put("limitations",List.of("PUBLIC_TEST_AUTHORITY_ONLY"));
        var proof=new JavaEvidenceCodec().create(fields);var claim=putBytes(proof.json().getBytes(StandardCharsets.UTF_8));var scopeDigest=new ContentDigest(GraphJson.string(scope.get("context")));
        var profile=CanonicalJson.sha256(Map.of("schema","fixture-native-profile:m8.4-v1","policy",policy()));
        var termination=put(Map.of("schema","fixture-validator-completion:m8.4-v1","scope",scopeDigest,"inputs",inputClosure,"nativeExecutionCertificate",false));
        var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1","fixture:"+scopeDigest.value(),1,scopeDigest,profile,answer.digest(),inputClosure.digest(),termination.digest());
        var attestation=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),claim.digest(),scopeDigest,inputClosure.digest(),tool.digest(),method(question),predicate(question),List.copyOf(new TreeSet<>(checked.keySet())),true);
        try{
            var controllerSignature=sign("pipeline-fixture.controller",controller,receipt);var validatorSignature=sign("pipeline-fixture.validator",validator,attestation);
            var expected=new EvidenceVerifier.Expected(scopeDigest,profile,1,scopeDigest,inputClosure.digest(),claim,method(question),predicate(question),tool.digest());
            var authenticated=verifier.verifyAccepted(expected,receipt,controllerSignature,attestation,validatorSignature,content).orElseThrow(()->new IOException("FIXTURE_CLAIM_REJECTED"));
            var verified=authority.accept(proof,authenticated).orElseThrow(()->new IOException("FIXTURE_JAVA_PROOF_REJECTED"));
            var envelope=put(Map.of("schema","java-native-fixture-signed-claim:m8.4-v1","claim",claim,"inputClosure",inputClosure,"receipt",receipt,"controllerSignature",controllerSignature,"attestation",attestation,"validatorSignature",validatorSignature,"publicTestAuthority",true));
            return new Issued(authenticated,verified,envelope);
        }catch(GeneralSecurityException invalid){throw new IOException("FIXTURE_SIGNATURE_FAILED",invalid);}
    }
    Issued replay(ContentHandle envelope)throws IOException {
        var raw=read(envelope);if(!raw.keySet().equals(Set.of("schema","claim","inputClosure","receipt","controllerSignature","attestation","validatorSignature","publicTestAuthority"))||!"java-native-fixture-signed-claim:m8.4-v1".equals(raw.get("schema"))||!Boolean.TRUE.equals(raw.get("publicTestAuthority")))throw new IOException("FIXTURE_ENVELOPE_SCOPE");
        var claim=JavaEvidenceRecord.handle(raw.get("claim"));var closure=JavaEvidenceRecord.handle(raw.get("inputClosure"));if(!content.verify(closure))throw new IOException("FIXTURE_INPUT_CLOSURE_CHANGED");
        var proof=new JavaEvidenceCodec().create(read(claim));if(!"native-pipeline-fixture-v1".equals(proof.object("scope").get("variant")))throw new IOException("FIXTURE_SIGNING_SCOPE_REQUIRED");
        var r=GraphJson.object(raw.get("receipt"));var receipt=new EvidenceVerifier.Receipt(string(r,"schema"),string(r,"attempt"),integer(r,"fence"),digest(r,"launch"),digest(r,"profile"),digest(r,"outputManifest"),digest(r,"materials"),digest(r,"termination"));
        var a=GraphJson.object(raw.get("attestation"));var attestation=new EvidenceVerifier.Attestation(string(a,"schema"),digest(a,"receipt"),digest(a,"claim"),digest(a,"expectedScope"),digest(a,"inputClosure"),digest(a,"tool"),version(a.get("method")),version(a.get("predicate")),GraphJson.array(a.get("passedChecks")).stream().map(GraphJson::string).toList(),Boolean.TRUE.equals(a.get("denominatorClosed")));
        String question=proof.text("question");if(!QUESTIONS.contains(question)||!closure.digest().equals(proof.digest("inputClosure")))throw new IOException("FIXTURE_REPLAY_QUESTION");
        var scope=new ContentDigest(GraphJson.string(proof.object("scope").get("context")));var expected=new EvidenceVerifier.Expected(scope,CanonicalJson.sha256(Map.of("schema","fixture-native-profile:m8.4-v1","policy",policy())),1,scope,closure.digest(),claim,method(question),predicate(question),tool.digest());
        var authenticated=verifier.verifyAccepted(expected,receipt,envelope(raw.get("controllerSignature")),attestation,envelope(raw.get("validatorSignature")),content).orElseThrow(()->new IOException("FIXTURE_CLAIM_REJECTED"));
        return new Issued(authenticated,authority.accept(proof,authenticated).orElseThrow(()->new IOException("FIXTURE_JAVA_PROOF_REJECTED")),envelope);
    }
    private ContentHandle put(Object value)throws IOException{return putBytes(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8));}
    private ContentHandle putBytes(byte[] bytes)throws IOException{
        var expected=new ContentHandle(ContentDigest.sha256(bytes),bytes.length);
        if(written.contains(expected)){if(!content.verify(expected))throw new IOException("FIXTURE_WRITTEN_CONTENT_CHANGED");return expected;}
        var actual=content.put(new ByteArrayInputStream(bytes),()->false);if(!actual.equals(expected))throw new IOException("FIXTURE_CONTENT_BINDING");written.add(actual);return actual;
    }
    private Map<String,Object> read(ContentHandle ref)throws IOException{if(!content.verify(ref)||ref.size()>2_000_000)throw new IOException("FIXTURE_REFERENCE_REQUIRED");try(var in=content.open(ref)){return GraphJson.object(GraphJson.read(new String(in.readNBytes(2_000_001),StandardCharsets.UTF_8),new GraphJson.Limits(2_000_000,64,2_000_000)));}}
    private static KeyPair keys(String privateKey,String publicKey)throws GeneralSecurityException{var factory=KeyFactory.getInstance("Ed25519");return new KeyPair(factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))),factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))));}
    private static EvidenceVerifier.SignatureEnvelope sign(String id,KeyPair keys,Object payload)throws GeneralSecurityException{var signature=Signature.getInstance("Ed25519");signature.initSign(keys.getPrivate());signature.update(CanonicalJson.write(Map.of("keyId",id,"payload",payload)).getBytes(StandardCharsets.UTF_8));return new EvidenceVerifier.SignatureEnvelope(id,Base64.getEncoder().encodeToString(signature.sign()));}
    private static EvidenceVerifier.SignatureEnvelope envelope(Object value){var map=GraphJson.object(value);return new EvidenceVerifier.SignatureEnvelope(string(map,"keyId"),string(map,"signatureBase64"));}
    private static VersionedIdentifier method(String q){return new VersionedIdentifier("fixture.pipeline-native."+q,"1");}
    private static VersionedIdentifier predicate(String q){return new VersionedIdentifier(q,JavaEvidenceAuthority.VERSION);}
    private static VersionedIdentifier version(Object v){var map=GraphJson.object(v);return new VersionedIdentifier(string(map,"id"),string(map,"version"));}
    private static String string(Map<String,Object> m,String key){return GraphJson.string(m.get(key));}
    private static long integer(Map<String,Object> m,String key){return ((java.math.BigDecimal)m.get(key)).longValueExact();}
    private static ContentDigest digest(Map<String,Object> m,String key){return new ContentDigest(string(m,key));}
}
