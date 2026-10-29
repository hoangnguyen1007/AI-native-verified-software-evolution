package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Explicit same-snapshot question/answer correspondence. A map is a producer
 * assertion until its independent, input-bound predicate is verified. */
public final class SemanticCorrespondence {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.correspondence","m8.4-v2");
    public static final List<String> CHECKS=List.of("SAME_REPOSITORY_SNAPSHOT","CONTEXT_COMPATIBILITY","QUESTION_IDENTITY","CENSUS_CORRESPONDENCE","SYMBOL_IDENTITY","SOURCE_COORDINATES","CONSERVED_UNCERTAINTY");
    public record Projection(ContentHandle mapping,ContentDigest tool,SemanticTables.Table observations){}
    private final RepositoryContentStore content;private final SemanticTables tables;private final SemanticContextCoordinator.CensusIndex indexes;
    public SemanticCorrespondence(RepositoryContentStore content,SemanticTables tables,SemanticContextCoordinator.CensusIndex indexes){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.indexes=Objects.requireNonNull(indexes);}
    public Projection project(LanguageSemanticRequest target,LanguageSemanticRequest source,SemanticTables.Table output,ContentHandle mapping,BooleanSupplier cancelled)throws IOException {
        try {
            // Same repository and exact snapshot, compilation variant and language.
            // This boundary cannot authorize Track-B snapshot evolution.
            if(!target.context().snapshotIdentity().equals(source.context().snapshotIdentity())||!target.scope().equals(source.scope())||!target.policy().equals(source.policy()))throw new IOException("SEMANTIC_CORRESPONDENCE_CONTEXT");
            SemanticReconciler.validateInputs(target,tables,content,cancelled);new SemanticReconciler().reconcile(source,output,tables,content,Map.of(),Set.of(),source::policy,g->{},cancelled);
            var map=read(mapping);if(!map.keySet().equals(Set.of("schema","source","target","questions","tool"))||!"semantic-correspondence:m8.4-v1".equals(map.get("schema"))||!source.identity().value().equals(map.get("source"))||!target.identity().value().equals(map.get("target")))throw new IOException("SEMANTIC_CORRESPONDENCE_BINDING");
            var questions=LanguageSemanticRequest.table(map.get("questions"));if(questions.kind()!=SemanticTables.Kind.CENSUS)throw new IOException("SEMANTIC_CORRESPONDENCE_TABLE_KIND");var tool=new ContentDigest(GraphJson.string(map.get("tool")));
            try(var index=indexes.open()){
                for(String space:List.of("correspondence.questions","correspondence.observations","correspondence.used"))try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("SEMANTIC_CORRESPONDENCE_INDEX_NOT_EMPTY");}
                try(var census=tables.open(source.census(),cancelled)){SemanticTables.Row row;while((row=census.next())!=null)index.put("correspondence.questions",row.key(),row);}
                try(var emitted=tables.open(output,cancelled)){SemanticTables.Row row;while((row=emitted.next())!=null)index.put("correspondence.observations",row.key(),row);}
                try(var census=tables.open(target.census(),cancelled);var bindings=tables.open(questions,cancelled)){
                    var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending;boolean end;
                        public boolean hasNext(){try{while(pending==null&&!end){var expected=census.next();var binding=bindings.next();if(expected==null){if(binding!=null)throw new IOException("SEMANTIC_CORRESPONDENCE_OUTSIDE_CENSUS");end=true;break;}
                            if(binding==null||!binding.key().equals(expected.key())||!binding.scope().equals(target.inputClosure())||!binding.fields().keySet().equals(Set.of("source","sourceIdentity","target","observation","reason"))||!expected.identity().value().equals(binding.fields().get("target"))||!(binding.fields().get("reason") instanceof String reason)||reason.isBlank())throw new IOException("SEMANTIC_CORRESPONDENCE_TARGET_CENSUS");
                            if(binding.fields().get("source")==null){if(binding.fields().get("sourceIdentity")!=null||binding.fields().get("observation")!=null)throw new IOException("SEMANTIC_CORRESPONDENCE_NEW_QUESTION");continue;}
                            String key=GraphJson.string(binding.fields().get("source"));var original=index.get("correspondence.questions",key).orElseThrow(()->new IOException("SEMANTIC_CORRESPONDENCE_SOURCE_CENSUS"));if(!original.identity().value().equals(binding.fields().get("sourceIdentity"))||!Objects.equals(original.fields().get("category"),expected.fields().get("category"))||index.get("correspondence.used",key).isPresent())throw new IOException("SEMANTIC_CORRESPONDENCE_QUESTION_IDENTITY");index.put("correspondence.used",key,binding);
                            var nativeRow=index.get("correspondence.observations",key);if(nativeRow.isEmpty()){if(binding.fields().get("observation")!=null)throw new IOException("SEMANTIC_CORRESPONDENCE_INVENTED_OBSERVATION");continue;}
                            if(binding.fields().get("observation")==null)throw new IOException("SEMANTIC_CORRESPONDENCE_DROPPED_OBSERVATION");var projected=row(read(LanguageSemanticRequest.handle(binding.fields().get("observation"))));
                            if(projected.kind()!=SemanticTables.Kind.OBSERVATION||!projected.key().equals(expected.key())||!projected.scope().equals(target.scope())||!expected.identity().value().equals(projected.fields().get("obligation"))||!Objects.equals(nativeRow.orElseThrow().fields().get("status"),projected.fields().get("status")))throw new IOException("SEMANTIC_CORRESPONDENCE_STATUS_OR_OBLIGATION");
                            // Detailed symbol/type/span rewrites require the map predicate;
                            // even a signed map cannot turn an unknown native status exact.
                            pending=projected;
                        }return pending!=null;}catch(IOException failure){throw new UncheckedIOException(failure);}}
                        public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var result=pending;pending=null;return result;}
                    };
                    var projected=tables.write(SemanticTables.Kind.OBSERVATION,iterator,cancelled);
                    // A newer, smaller census cannot silently erase an older
                    // unresolved question. V1 maps express one-to-one carryover
                    // and new questions, not retirement or many-to-one merging.
                    try(var originals=tables.open(source.census(),cancelled)){SemanticTables.Row original;while((original=originals.next())!=null)if(index.get("correspondence.used",original.key()).isEmpty())throw new IOException("SEMANTIC_CORRESPONDENCE_SOURCE_DENOMINATOR_OPEN");}
                    new SemanticReconciler().reconcileCorrespondence(target,projected,tables,content,cancelled);return new Projection(mapping,tool,projected);
                }
            }
        }catch(UncheckedIOException failure){throw failure.getCause();}catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("SEMANTIC_CORRESPONDENCE_INVALID",invalid);}
    }
    public static Map<String,Object> proofPayload(LanguageSemanticRequest target,LanguageSemanticRequest source,SemanticTables.Table nativeOutput,Projection projection){return Map.of("schema","semantic-correspondence-claim:m8.4-v1","target",target.identity(),"source",source.identity(),"nativeOutput",nativeOutput,"projection",projection,"mapper",PROVIDER);}
    public static ContentDigest inputClosure(LanguageSemanticRequest target,LanguageSemanticRequest source,SemanticTables.Table nativeOutput,Projection projection){return CanonicalJson.sha256(List.of("semantic-correspondence-inputs:m8.4-v1",PROVIDER,target.inputClosure(),source.inputClosure(),nativeOutput,projection));}
    public boolean qualified(LanguageSemanticRequest target,LanguageSemanticRequest source,SemanticTables.Table nativeOutput,Projection projection,EvidenceVerifier.VerifiedClaim proof,Set<ContentDigest> revoked)throws IOException {
        if(proof==null||revoked.contains(proof.identity())||!proof.policy().equals(target.policy())||!proof.checks().containsAll(CHECKS))return false;var expected=proof.expected();byte[] payload=CanonicalJson.write(proofPayload(target,source,nativeOutput,projection)).getBytes(StandardCharsets.UTF_8);
        return expected.scope().equals(target.scope())&&expected.inputClosure().equals(inputClosure(target,source,nativeOutput,projection))&&expected.tool().equals(projection.tool())&&expected.predicate().equals(new VersionedIdentifier("polyglot.correspondence","m8.4-v1"))&&expected.claim().equals(new ContentHandle(ContentDigest.sha256(payload),payload.length))&&content.verify(expected.claim());
    }
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_CORRESPONDENCE_RECORD_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_CORRESPONDENCE_NONCANONICAL");return value;}}
    private static SemanticTables.Row row(Map<String,Object> raw){if(!raw.keySet().equals(Set.of("kind","key","scope","fields")))throw new IllegalArgumentException("SEMANTIC_CORRESPONDENCE_ROW_FIELDS");return new SemanticTables.Row(SemanticTables.Kind.valueOf(GraphJson.string(raw.get("kind"))),GraphJson.string(raw.get("key")),new ContentDigest(GraphJson.string(raw.get("scope"))),GraphJson.object(raw.get("fields")));}
}
