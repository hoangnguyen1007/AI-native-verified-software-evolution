package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.RepositoryContentStore;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.util.*;

/** Operator-composed authority. Deserializing an ACCEPT record cannot construct Verified.
 * The upstream attestation must be issued by a real registered validator, never by the producer. */
public final class JavaEvidenceAuthority {
    public static final String VERSION="m8.4-step4-v1";
    private static final Set<String> COMMON=Set.of("SCOPE_MATCH","INPUT_CLOSURE","REFERENCE_INTEGRITY","POLICY_CURRENT","NO_CONTRADICTION");
    private static final Map<String,Set<String>> EXTRA=Map.ofEntries(
        Map.entry("java.output-bytes",Set.of("PUBLICATION_BINDING","LOCATOR_VALID","CONTENT_HASH_SIZE")),
        Map.entry("java.generated-lineage",Set.of("TOOL_RECIPE_BINDING","OUTPUT_ASSOCIATION","GENERATOR_CENSUS_CLOSED","OWNERSHIP_PROVEN")),
        Map.entry("java.resolution-context",Set.of("COMPILER_INPUTS_VALIDATED","ORDER_PRESERVED","PLATFORM_VALIDATED","SOURCE_CENSUS_CLOSED","SEARCH_SPACES_CLOSED")),
        Map.entry("java.binary-shape",Set.of("NON_LOADING_EXTRACTOR","CLASS_FORMAT_VALID","MEMBER_CENSUS_CLOSED","ORIGIN_PROVEN")),
        Map.entry("java.source-binary-correspondence",Set.of("OWNER_SIGNATURE_MATCH","TRANSFORM_LINEAGE","MAPPING_CENSUS_CLOSED")),
        Map.entry("java.semantic-occurrence",Set.of("INPUT_VIEW_MATCH","OCCURRENCE_CENSUS","TARGET_MAPPING","ORIGIN_PROVEN","PROVENANCE_VALID")),
        Map.entry("java.generator-not-applicable",Set.of("GENERATOR_CENSUS_CLOSED","APPLICABILITY_PROVEN")),
        Map.entry("java.build-validation",Set.of("NATIVE_STAGE_VALIDATED","TARGET_CENSUS_CLOSED","COMPILATION_APPLICABILITY")));
    public static Set<String> requiredChecks(String predicate){
        var extra=EXTRA.get(predicate);if(extra==null)throw new IllegalArgumentException("UNKNOWN_JAVA_PREDICATE");
        var result=new TreeSet<>(COMMON);result.addAll(extra);return Collections.unmodifiableSet(result);
    }
    public static final class Verified {
        private final JavaEvidenceRecord record;
        private final RepositoryContentStore content;
        private final ContentDigest identity;
        private Verified(JavaEvidenceRecord record,RepositoryContentStore content){this.record=record;this.content=content;this.identity=record.identity();}
        public JavaEvidenceRecord record(){return record;}
        public ContentDigest identity(){return identity;}
        public ContentDigest answer(){return JavaEvidenceRecord.handle(record.fields().get("answer")).digest();}
        public boolean current()throws IOException{
            byte[] bytes=record.json().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var references=new LinkedHashSet<com.evolution.analysis.acquisition.streaming.ContentHandle>();references.add(new com.evolution.analysis.acquisition.streaming.ContentHandle(identity,bytes.length));references.add(JavaEvidenceRecord.handle(record.fields().get("answer")));references.add(JavaEvidenceRecord.handle(record.object("inputs").get("content")));
            for(Object value:GraphJson.array(record.fields().get("checks")))for(Object item:GraphJson.array(GraphJson.object(value).get("evidence")))references.add(JavaEvidenceRecord.handle(item));
            for(var reference:references)if(!content.verify(reference))return false;
            return inputsCurrent(record,content);
        }
        public Set<ContentDigest> adjudicationInputs(){
            var result=new TreeSet<ContentDigest>();
            for(Object value:GraphJson.array(record.fields().get("checks"))){var check=GraphJson.object(value);if("NO_CONTRADICTION".equals(check.get("name")))for(Object item:GraphJson.array(check.get("evidence")))result.add(JavaEvidenceRecord.handle(item).digest());}
            return Collections.unmodifiableSet(result);
        }
    }
    private final ContentDigest policy;
    private final RepositoryContentStore content;
    public JavaEvidenceAuthority(ContentDigest policy,RepositoryContentStore content){this.policy=Objects.requireNonNull(policy);this.content=Objects.requireNonNull(content);}
    public Optional<Verified> accept(JavaEvidenceRecord proof,EvidenceVerifier.VerifiedClaim authenticated)throws IOException{
        if(!proof.kind().equals("java-evidence-proof")||!proof.text("outcome").equals("ACCEPT")||!proof.text("trust").equals("VALIDATED_OBSERVATION"))return Optional.empty();
        var predicate=proof.object("predicate");String id=GraphJson.string(predicate.get("id"));
        if(!EXTRA.containsKey(id)||!VERSION.equals(predicate.get("version"))||!id.equals(proof.text("question")))return Optional.empty();
        if(!authenticated.policy().equals(policy)||!policy.equals(proof.digest("policy"))
                ||!authenticated.expected().claim().digest().equals(proof.identity())
                ||!authenticated.expected().scope().value().equals(proof.object("scope").get("context"))
                ||!authenticated.expected().inputClosure().equals(proof.digest("inputClosure"))
                ||!authenticated.expected().predicate().equals(new VersionedIdentifier(id,VERSION)))return Optional.empty();
        var checks=new TreeSet<String>();
        var references=new LinkedHashSet<com.evolution.analysis.acquisition.streaming.ContentHandle>();
        for(Object value:GraphJson.array(proof.fields().get("checks"))){
            var check=GraphJson.object(value);
            if(!checks.add(GraphJson.string(check.get("name")))||!"PASS".equals(check.get("outcome")))return Optional.empty();
            for(Object evidence:GraphJson.array(check.get("evidence")))references.add(JavaEvidenceRecord.handle(evidence));
        }
        for(var reference:references)if(!content.verify(reference))return Optional.empty();
        if(!checks.equals(requiredChecks(id))||!authenticated.checks().containsAll(checks)
                ||!content.verify(authenticated.expected().claim())||!content.verify(JavaEvidenceRecord.handle(proof.fields().get("answer")))
                ||!content.verify(JavaEvidenceRecord.handle(proof.object("inputs").get("content"))))return Optional.empty();
        if(!inputsCurrent(proof,content))return Optional.empty();
        return Optional.of(new Verified(proof,content));
    }
    private static boolean inputsCurrent(JavaEvidenceRecord proof,RepositoryContentStore content)throws IOException{
        boolean[] valid={true};
        try{
            new JavaEvidenceTables(content,2_000_000).read(JavaEvidenceTables.Table.from(proof.fields().get("inputs")),row->{
                if(!content.verify(JavaEvidenceRecord.handle(row.fields().get("content"))))valid[0]=false;
                if(row.fields().get("locator")!=null){var locator=row.object("locator");JavaGeneratedOverlay.path(locator.get("path"));if(!content.verify(JavaEvidenceRecord.handle(locator.get("container"))))valid[0]=false;for(Object item:GraphJson.array(locator.get("members"))){var member=GraphJson.object(item);JavaGeneratedOverlay.path(member.get("path"));if(!content.verify(JavaEvidenceRecord.handle(member.get("content"))))valid[0]=false;}}
            },()->false);
        }catch(IllegalArgumentException invalid){return false;}
        return valid[0];
    }
}
