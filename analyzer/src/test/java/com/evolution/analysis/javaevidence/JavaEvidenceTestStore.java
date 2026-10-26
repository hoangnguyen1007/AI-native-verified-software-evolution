package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Actual byte hashing and signed authored contract observations; no native-fixture claim. */
final class JavaEvidenceTestStore implements RepositoryContentStore {
    final Map<ContentDigest,byte[]> blobs=new HashMap<>();
    final Map<ContentHandle,Integer> verificationCounts=new HashMap<>();
    public ContentHandle put(InputStream input,BooleanSupplier cancel)throws IOException{if(cancel.getAsBoolean())throw new IOException("CANCELLED");byte[] bytes=input.readAllBytes();var digest=ContentDigest.sha256(bytes);blobs.put(digest,bytes);return new ContentHandle(digest,bytes.length);}
    ContentHandle put(String value)throws IOException{return put(new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)),()->false);}
    public InputStream open(ContentHandle h)throws IOException{if(!verify(h))throw new IOException("MISSING");return new ByteArrayInputStream(blobs.get(h.digest()));}
    public InputStream range(ContentHandle h,long offset,long length)throws IOException{var bytes=open(h).readAllBytes();return new ByteArrayInputStream(Arrays.copyOfRange(bytes,Math.toIntExact(offset),Math.toIntExact(offset+length)));}
    public boolean verify(ContentHandle h){verificationCounts.merge(h,1,Integer::sum);byte[] b=blobs.get(h.digest());return b!=null&&b.length==h.size()&&ContentDigest.sha256(b).equals(h.digest());}

    static final class AuthorityFixture {
        final JavaEvidenceTestStore store=new JavaEvidenceTestStore();final KeyPair controller,validator;
        final EvidenceVerifier verifier;final JavaEvidenceAuthority authority;
        AuthorityFixture()throws Exception{
            var generator=KeyPairGenerator.getInstance("Ed25519");controller=generator.generateKeyPair();validator=generator.generateKeyPair();
            var methods=List.of("java.output-bytes","java.generated-lineage","java.resolution-context","java.binary-shape","java.generator-not-applicable","java.compiler-not-applicable","java.source-binary-correspondence","java.semantic-occurrence",JavaEvidenceGapClosure.QUESTION,JavaReadinessClosure.QUESTION).stream().map(id->new EvidenceVerifier.Method(new VersionedIdentifier(id+".capture","1"),new VersionedIdentifier(id,JavaEvidenceAuthority.VERSION),new ContentDigest(JavaEvidenceCodecTest.D),List.copyOf(JavaEvidenceAuthority.requiredChecks(id)))).toList();
            verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),methods);authority=new JavaEvidenceAuthority(verifier.policyIdentity(),store);
        }
        JavaEvidenceAuthority.Verified proof(String question,String subject,String answer,List<ContentHandle> extra)throws Exception{
            return proof(JavaEvidenceLedgerTest.scope(),question,subject,answer,extra);
        }
        JavaEvidenceAuthority.Verified proof(Map<String,Object> scope,String question,String subject,String answer,List<ContentHandle> extra)throws Exception{
            var payload=store.put(answer);var empty=store.put("");var checks=JavaEvidenceAuthority.requiredChecks(question).stream().map(name->{var evidence=new ArrayList<Object>();evidence.add(payload);if(name.equals("NO_CONTRADICTION"))evidence.addAll(extra);var check=new LinkedHashMap<String,Object>();check.put("name",name);check.put("outcome","PASS");check.put("evidence",evidence);check.put("reason",null);return check;}).toList();
            var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-evidence-proof:m8.4-v1");fields.put("scope",scope);fields.put("question",question);fields.put("subject",subject);fields.put("predicate",new VersionedIdentifier(question,JavaEvidenceAuthority.VERSION));fields.put("validator",new VersionedIdentifier("fixture.validator","1"));fields.put("policy",verifier.policyIdentity());fields.put("answer",payload);fields.put("inputClosure",JavaEvidenceCodecTest.D);fields.put("inputs",Map.of("schema","java-evidence-input-table:m8.4-v1","content",empty,"rows",0));fields.put("checks",checks);fields.put("outcome","ACCEPT");fields.put("trust","VALIDATED_OBSERVATION");fields.put("limitations",List.of());
            var proof=new JavaEvidenceCodec().create(fields);var claim=store.put(proof.json());var d=new ContentDigest(JavaEvidenceCodecTest.D);var predicate=new VersionedIdentifier(question,JavaEvidenceAuthority.VERSION);var method=new VersionedIdentifier(question+".capture","1");
            var expected=new EvidenceVerifier.Expected(d,d,1,new ContentDigest(com.evolution.analysis.graph.GraphJson.string(scope.get("context"))),d,claim,method,predicate,d);
            var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1","authored",1,d,d,d,d,d);
            var attestation=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),claim.digest(),expected.scope(),d,d,method,predicate,List.copyOf(JavaEvidenceAuthority.requiredChecks(question)),true);
            var accepted=verifier.verifyAccepted(expected,receipt,sign("controller",controller,receipt),attestation,sign("validator",validator,attestation),store).orElseThrow();
            return authority.accept(proof,accepted).orElseThrow();
        }
        static EvidenceVerifier.SignatureEnvelope sign(String id,KeyPair key,Object value)throws Exception{
            var signature=Signature.getInstance("Ed25519");signature.initSign(key.getPrivate());signature.update(CanonicalJson.write(Map.of("keyId",id,"payload",value)).getBytes(StandardCharsets.UTF_8));return new EvidenceVerifier.SignatureEnvelope(id,Base64.getEncoder().encodeToString(signature.sign()));
        }
    }
}
