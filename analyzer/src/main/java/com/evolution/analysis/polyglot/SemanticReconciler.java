package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.*;

/** Merge a separately captured obligation census with emissions. Missing rows cannot improve coverage. */
public final class SemanticReconciler {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.polyglot-reconciler","m8.4-v7");
    public static final VersionedIdentifier CATALOG=new VersionedIdentifier("evidence.polyglot-gaps","m8.4-v1");
    public enum Status { RESOLVED, CANDIDATES, AMBIGUOUS, PARTIAL, CONDITIONAL, UNRESOLVED, UNSUPPORTED, ERROR, NOT_APPLICABLE }
    public record Coverage(long total,long resolved,long candidates,long ambiguous,long partial,long conditional,long unresolved,long unsupported,long errors,long notApplicable,long omitted,long sourceMappingGaps,
            boolean censusQualified,boolean observationsQualified,boolean complete,ContentDigest semanticIdentity){}
    public static Map<String,Object> proofPayload(LanguageSemanticRequest request,SemanticTables.Table observations){
        return Map.of("schema","polyglot-semantic-claim:m8.4-v1","request",request.identity(),"documents",request.documents(),"census",request.census(),"observations",observations);
    }
    static void validateInputs(LanguageSemanticRequest request,SemanticTables tables,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException {
        if(request.provider().equals(SourceMappedSemanticProvider.PROVIDER)){
            try(var projection=SemanticSourceProjection.open(request,tables,content,cancelled)){return;}
        }
        var empty=tables.write(SemanticTables.Kind.OBSERVATION,Collections.emptyIterator(),cancelled);
        new SemanticReconciler().reconcile(request,empty,tables,content,Map.of(),Set.of(),request::policy,g->{},cancelled);
    }
    public Coverage reconcile(LanguageSemanticRequest request,SemanticTables.Table observations,SemanticTables tables,RepositoryContentStore content,
            Map<String,EvidenceVerifier.VerifiedClaim> proofs,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,
            Consumer<CapabilityGapRecord> gaps,BooleanSupplier cancelled)throws IOException{
        return reconcile(request,observations,tables,content,proofs,revoked,livePolicy,gaps,cancelled,true);
    }
    /** Structural target-question validation after the original provider's rows
     * have been revalidated. Only an independent correspondence predicate can
     * subsequently authorize these alternative answers in the ledger. */
    void reconcileCorrespondence(LanguageSemanticRequest request,SemanticTables.Table observations,SemanticTables tables,RepositoryContentStore content,BooleanSupplier cancelled)throws IOException {
        reconcile(request,observations,tables,content,Map.of(),Set.of(),request::policy,g->{},cancelled,false);
    }
    private Coverage reconcile(LanguageSemanticRequest request,SemanticTables.Table observations,SemanticTables tables,RepositoryContentStore content,
            Map<String,EvidenceVerifier.VerifiedClaim> proofs,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,
            Consumer<CapabilityGapRecord> gaps,BooleanSupplier cancelled,boolean validateProjection)throws IOException{
        if(observations.kind()!=SemanticTables.Kind.OBSERVATION)throw new IOException("SEMANTIC_OBSERVATION_TABLE_KIND");
        if(!request.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_POLICY_REVOKED");
        if(!content.verify(request.resolution()))throw new IOException("SEMANTIC_RESOLUTION_CONTENT_UNAVAILABLE");
        // Validate actual document rows/hashes before treating a census as covering those documents.
        var documentKeys=new HashSet<String>();var coordinates=new SemanticCoordinates(content,cancelled);
        try(var reader=tables.open(request.documents(),cancelled)){SemanticTables.Row row;
            while((row=reader.next())!=null){scope(request,row);if(!documentKeys.add(row.key()))throw new IOException("SEMANTIC_DUPLICATE_DOCUMENT");
                var handle=handle(row.fields().get("content"));if(!content.verify(handle))throw new IOException("SEMANTIC_DOCUMENT_CONTENT_UNAVAILABLE");coordinates.document(row);}
        }
        boolean censusQualified=qualified("census",request,observations,content,proofs,revoked),observationsQualified=qualified("observations",request,observations,content,proofs,revoked);
        if(!censusQualified)gap(request,"CENSUS_METHOD_UNQUALIFIED",request.census().identity().value(),gaps);
        if(!observationsQualified)gap(request,"SEMANTIC_METHOD_UNQUALIFIED",observations.identity().value(),gaps);
        var seenDocuments=new HashSet<String>();var categories=new HashMap<String,Set<String>>();
        long total=0,resolved=0,candidates=0,ambiguous=0,partial=0,conditional=0,unresolved=0,unsupported=0,errors=0,notApplicable=0,omitted=0,sourceMappingGaps=0;
        try(var census=tables.open(request.census(),cancelled);var output=tables.open(observations,cancelled);var projection=validateProjection?SemanticSourceProjection.open(request,tables,content,cancelled):null){
            var emitted=output.next();SemanticTables.Row expected;
            while((expected=census.next())!=null){
                scope(request,expected);String document=text(expected,"document"),category=text(expected,"category");
                if(!documentKeys.contains(document)||!LanguageProfile.requiredCategories().contains(category))throw new IOException("SEMANTIC_CENSUS_SCOPE_OR_CATEGORY");
                seenDocuments.add(document);categories.computeIfAbsent(document,key->new HashSet<>()).add(category);total=Math.addExact(total,1);coordinates.validate(expected,null);
                if(emitted!=null&&emitted.key().compareTo(expected.key())<0)throw new IOException("SEMANTIC_EMISSION_OUTSIDE_CENSUS");
                if(emitted==null||!emitted.key().equals(expected.key())){omitted=Math.addExact(omitted,1);gap(request,"SEMANTIC_OCCURRENCE_OMITTED",expected.identity().value(),gaps);continue;}
                scope(request,emitted);
                coordinates.validate(expected,emitted);
                if(projection!=null&&projection.validate(emitted,expected)){sourceMappingGaps=Math.addExact(sourceMappingGaps,1);nativeGap(request,"SEMANTIC_SOURCE_MAPPING_INCOMPLETE",expected,emitted,observations,gaps);}
                if(!text(emitted,"obligation").equals(expected.identity().value()))throw new IOException("SEMANTIC_OBLIGATION_BINDING_MISMATCH");
                Status state;
                try{state=Status.valueOf(text(emitted,"status"));}catch(IllegalArgumentException invalid){throw new IOException("SEMANTIC_STATUS_INVALID",invalid);}
                if(state==Status.NOT_APPLICABLE&&Set.of("A01","A11","A12").contains(category))throw new IOException("SEMANTIC_MANDATORY_CATEGORY_NOT_APPLICABLE");
                if((state==Status.PARTIAL||state==Status.CONDITIONAL||state==Status.AMBIGUOUS)&&(!(emitted.fields().get("uncertainty") instanceof List<?> uncertainty)||uncertainty.isEmpty()))throw new IOException("SEMANTIC_UNCERTAINTY_REQUIRED");
                var targets=GraphJson.array(emitted.fields().get("targets"));
                if(state==Status.CANDIDATES&&targets.isEmpty())throw new IOException("SEMANTIC_CANDIDATE_SET_EMPTY");
                var targetKeys=new HashSet<String>();
                try{for(var target:targets){String key=GraphJson.string(target);new ContentDigest(key);if(!targetKeys.add(key))throw new IllegalArgumentException("duplicate target");}}
                catch(IllegalArgumentException invalid){throw new IOException("SEMANTIC_TARGET_IDENTITY_INVALID",invalid);}
                try{if(emitted.fields().get("owner")!=null)new ContentDigest(GraphJson.string(emitted.fields().get("owner")));if(emitted.fields().get("relation")!=null)ContractChecks.token(GraphJson.string(emitted.fields().get("relation")),"relation meaning");}
                catch(IllegalArgumentException invalid){throw new IOException("SEMANTIC_RELATION_MEANING_INVALID",invalid);}
                if((state==Status.RESOLVED&&targets.size()!=1&&Set.of("A03","A05","A06").contains(category))||
                    ((state==Status.UNRESOLVED||state==Status.UNSUPPORTED||state==Status.ERROR||state==Status.NOT_APPLICABLE)&&!targets.isEmpty()))throw new IOException("SEMANTIC_STATUS_TARGET_MISMATCH");
                switch(state){case RESOLVED->resolved++;case CANDIDATES->candidates++;case AMBIGUOUS->ambiguous++;case PARTIAL->partial++;case CONDITIONAL->conditional++;case UNRESOLVED->unresolved++;case UNSUPPORTED->unsupported++;case ERROR->errors++;case NOT_APPLICABLE->notApplicable++;}
                if(state!=Status.RESOLVED&&state!=Status.NOT_APPLICABLE)nativeGap(request,"SEMANTIC_"+state.name(),expected,emitted,observations,gaps);
                emitted=output.next();
            }
            if(emitted!=null)throw new IOException("SEMANTIC_EMISSION_OUTSIDE_CENSUS");
            if(projection!=null)projection.finish();
        }
        boolean closed=!documentKeys.isEmpty()&&seenDocuments.equals(documentKeys)&&categories.values().stream().allMatch(set->set.containsAll(LanguageProfile.requiredCategories()));
        if(!closed)gap(request,"SEMANTIC_DOCUMENT_OR_CATEGORY_CENSUS_OPEN",request.documents().identity().value(),gaps);
        if(!request.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_POLICY_REVOKED");
        boolean complete=closed&&censusQualified&&observationsQualified&&candidates==0&&ambiguous==0&&partial==0&&conditional==0&&unresolved==0&&unsupported==0&&errors==0&&omitted==0&&sourceMappingGaps==0;
        var identity=CanonicalJson.sha256(List.of("polyglot-coverage:m8.4-v4",PROVIDER,validateProjection,request.identity(),observations,closed,censusQualified,observationsQualified,total,resolved,candidates,ambiguous,partial,conditional,unresolved,unsupported,errors,notApplicable,omitted,sourceMappingGaps));
        return new Coverage(total,resolved,candidates,ambiguous,partial,conditional,unresolved,unsupported,errors,notApplicable,omitted,sourceMappingGaps,censusQualified,observationsQualified,complete,identity);
    }
    static boolean qualified(String role,LanguageSemanticRequest request,SemanticTables.Table observations,RepositoryContentStore content,
            Map<String,EvidenceVerifier.VerifiedClaim> proofs,Set<ContentDigest> revoked)throws IOException{
        var proof=proofs.get(role);if(proof==null||revoked.contains(proof.identity())||!proof.policy().equals(request.policy()))return false;
        var e=proof.expected();byte[] payload=CanonicalJson.write(proofPayload(request,observations)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var required=role.equals("census")?List.of("INDEPENDENT_CENSUS","DOCUMENT_MEMBERSHIP","CATEGORY_CLOSURE"):List.of("CALLER_TARGET_ORIGIN","SOURCE_COORDINATES","APPLICABILITY_VALIDATED");
        return e.scope().equals(request.scope())&&e.inputClosure().equals(request.inputClosure())&&e.tool().equals(request.tool())
            &&e.predicate().equals(new VersionedIdentifier("polyglot."+role,"m8.4-v1"))&&proof.checks().containsAll(required)
            &&e.claim().equals(new ContentHandle(ContentDigest.sha256(payload),payload.length))&&content.verify(e.claim());
    }
    private static void scope(LanguageSemanticRequest request,SemanticTables.Row row)throws IOException{if(!request.scope().equals(row.scope()))throw new IOException("SEMANTIC_ROW_CONTEXT_MISMATCH");}
    private static String text(SemanticTables.Row row,String key)throws IOException{try{return GraphJson.string(row.fields().get(key));}catch(IllegalArgumentException invalid){throw new IOException("SEMANTIC_ROW_FIELD_"+key,invalid);}}
    private static ContentHandle handle(Object value){var map=GraphJson.object(value);return new ContentHandle(new ContentDigest(GraphJson.string(map.get("digest"))),((java.math.BigDecimal)map.get("size")).longValueExact());}
    private static void gap(LanguageSemanticRequest request,String reason,String subject,Consumer<CapabilityGapRecord> consumer){
        var source=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,subject);var observation=ProviderObservationReference.create(PROVIDER,"polyglot.coverage",request.identity(),CanonicalJson.sha256(List.of(reason,subject)));
        var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.semantic-evidence",List.of(source),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
            List.of("Capture exact native inputs and independently validate the missing category, full source mapping and closed census."));
        consumer.accept(CapabilityGapRecord.create(CATALOG,request.context(),PROVIDER,"polyglot.semantic",reason,source,List.of(),List.of(observation),List.of(need),List.of(),
            List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.semantic")),List.of(),List.of(),List.of("Pack qualification and repository completeness are separate.")));
    }
    private static void nativeGap(LanguageSemanticRequest request,String reason,SemanticTables.Row expected,SemanticTables.Row observed,SemanticTables.Table output,Consumer<CapabilityGapRecord> consumer){
        var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,expected.identity().value());var observation=ProviderObservationReference.create(PROVIDER,"polyglot.native-status-projection",output.content().digest(),observed.identity());
        Object rawReason=observed.fields().get("reason");String nativeReason=rawReason instanceof String value&&value.matches("[A-Z][A-Z_0-9]{0,199}")?value:"NATIVE_REASON_UNAVAILABLE";
        var details=new TreeMap<String,String>();details.put("nativeReason",nativeReason);details.put("nativeProviderId",request.provider().id());details.put("nativeProviderVersion",request.provider().version());details.put("category",Objects.toString(expected.fields().get("category")));details.put("obligation",expected.identity().value());details.put("observation",observed.identity().value());if(rawReason!=null&&nativeReason.equals("NATIVE_REASON_UNAVAILABLE"))details.put("unclassifiedReasonDigest",CanonicalJson.sha256(rawReason).value());
        var diagnostic=new com.evolution.analysis.contract.semantic.Diagnostic(com.evolution.analysis.contract.semantic.DiagnosticSeverity.WARNING,"polyglot.native-status","Native semantic evidence retains a degraded or uncertain outcome.",Optional.empty(),details);
        var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.semantic-evidence",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Resolve the recorded native provider reason against these exact source, context and observation bytes."));
        consumer.accept(CapabilityGapRecord.create(CATALOG,request.context(),PROVIDER,"polyglot.semantic",reason,subject,List.of(),List.of(observation),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.semantic")),List.of(),List.of(diagnostic),List.of("Native observation bytes and their provider version are retained; this gap is not a semantic qualification.")));
    }
}
