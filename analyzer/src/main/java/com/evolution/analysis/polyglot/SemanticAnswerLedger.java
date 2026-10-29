package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Same-input answer history, independent of arrival order. Every fold starts
 * from the question census and live opaque proofs, never saved acceptance bits.
 * This is evidence acquisition inside one context, not snapshot evolution.
 */
public final class SemanticAnswerLedger {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.answer-ledger","m8.4-v3");
    private static final List<String> LEGACY_CORE=List.of("status","targets","caller","callers","symbols","type","types","mode","access","truth");
    private static final List<String> CORE=java.util.stream.Stream.concat(LEGACY_CORE.stream(),java.util.stream.Stream.of("owner","relation")).toList();
    private static String coreSchema(Map<String,Object> core)throws IOException{
        if(core.keySet().equals(new HashSet<>(LEGACY_CORE)))return "semantic-answer-core:m8.4-v1";
        if(core.keySet().equals(new HashSet<>(CORE)))return "semantic-answer-core:m8.4-v2";
        throw new IOException("SEMANTIC_ANSWER_CORE_FIELDS");
    }
    public record History(ContentDigest scope,SemanticTables.Table revisions){public History{Objects.requireNonNull(scope);if(revisions.kind()!=SemanticTables.Kind.REVISION)throw new IllegalArgumentException("SEMANTIC_HISTORY_KIND");}public ContentDigest identity(){return CanonicalJson.sha256(List.of("semantic-answer-history:m8.4-v1",this));}
        public static History from(Object raw){var value=GraphJson.object(raw);if(!value.keySet().equals(Set.of("scope","revisions")))throw new IllegalArgumentException("SEMANTIC_HISTORY_FIELDS");return new History(new ContentDigest(GraphJson.string(value.get("scope"))),LanguageSemanticRequest.table(value.get("revisions")));}
    }
    public record Result(History history,SemanticTables.Table current,SemanticTables.Table conflicts,long questions,long unavailableRevisions,Map<String,Long> counts,boolean answersComplete){public Result{counts=Collections.unmodifiableMap(new TreeMap<>(counts));}}
    @FunctionalInterface public interface Proofs{Map<String,EvidenceVerifier.VerifiedClaim> forRevision(LanguageSemanticRequest request,SemanticTables.Table observations)throws IOException;}
    private final RepositoryContentStore content;private final SemanticTables tables;private final Optional<SemanticContextCoordinator.CensusIndex> indexes;
    public SemanticAnswerLedger(RepositoryContentStore content,SemanticTables tables){this(content,tables,Optional.empty());}
    public SemanticAnswerLedger(RepositoryContentStore content,SemanticTables tables,Optional<SemanticContextCoordinator.CensusIndex> indexes){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.indexes=Objects.requireNonNull(indexes);}
    public History append(LanguageSemanticRequest request,SemanticTables.Table observations,Optional<History> previous,BooleanSupplier cancelled)throws IOException{
        new SemanticReconciler().reconcile(request,observations,tables,content,Map.of(),Set.of(),request::policy,g->{},cancelled);
        return appendRevision(request,Map.of("request",put(request,cancelled),"observations",fields(observations)),previous,cancelled);
    }
    public History appendMapped(LanguageSemanticRequest target,LanguageSemanticRequest source,SemanticTables.Table observations,ContentHandle correspondence,Optional<History> previous,BooleanSupplier cancelled)throws IOException {
        correspondence().project(target,source,observations,correspondence,cancelled);
        return appendRevision(target,Map.of("request",put(source,cancelled),"observations",fields(observations),"correspondence",correspondence),previous,cancelled);
    }
    private SemanticCorrespondence correspondence()throws IOException{return new SemanticCorrespondence(content,tables,indexes.orElseThrow(()->new IOException("SEMANTIC_CORRESPONDENCE_INDEX_REQUIRED")));}
    private History appendRevision(LanguageSemanticRequest request,Map<String,Object> fields,Optional<History> previous,BooleanSupplier cancelled)throws IOException {
        var scope=request.inputClosure();var history=previous.orElseGet(()->null);if(history!=null&&!history.scope().equals(scope))throw new IOException("SEMANTIC_HISTORY_INPUT_CLOSURE");
        var before=history==null?tables.write(SemanticTables.Kind.REVISION,Collections.emptyIterator(),cancelled):history.revisions();long ordinal=before.rows();var next=new SemanticTables.Row(SemanticTables.Kind.REVISION,key(ordinal),scope,fields);
        try(var reader=tables.open(before,cancelled)){
            var rows=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending;boolean ended,added;
                public boolean hasNext(){try{if(!ended&&pending==null){pending=reader.next();ended=pending==null;}return pending!=null||!added;}catch(IOException failed){throw new UncheckedIOException(failed);}}
                public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();if(pending!=null){var row=pending;pending=null;if(!row.scope().equals(scope))throw new UncheckedIOException(new IOException("SEMANTIC_HISTORY_SCOPE"));return row;}added=true;return next;}
            };
            try{return new History(scope,tables.write(SemanticTables.Kind.REVISION,rows,cancelled));}catch(UncheckedIOException failure){throw failure.getCause();}
        }
    }
    public Result fold(LanguageSemanticRequest request,History history,Proofs proofs,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,Consumer<CapabilityGapRecord> gaps,BooleanSupplier cancelled)throws IOException{
        if(!history.scope().equals(request.inputClosure()))throw new IOException("SEMANTIC_HISTORY_INPUT_CLOSURE");check(request,livePolicy,cancelled);
        SemanticReconciler.validateInputs(request,tables,content,cancelled);
        SemanticTables.Table current;
        try(var census=tables.open(request.census(),cancelled)){
            var rows=iterator(census,row->new SemanticTables.Row(SemanticTables.Kind.CURRENT,row.key(),history.scope(),Map.of("obligation",row.identity().value(),"seen",false,"historical",false,"answers",List.of(),"state","PENDING")));
            current=write(SemanticTables.Kind.CURRENT,rows,cancelled);
        }
        long ordinal=0,unavailable=0;
        try(var revisions=tables.open(history.revisions(),cancelled)){SemanticTables.Row revision;
            while((revision=revisions.next())!=null){
                check(request,livePolicy,cancelled);boolean mapped=revision.fields().containsKey("correspondence");if(!revision.scope().equals(history.scope())||!revision.key().equals(key(ordinal++))||!revision.fields().keySet().equals(mapped?Set.of("request","observations","correspondence"):Set.of("request","observations")))throw new IOException("SEMANTIC_HISTORY_REVISION_BINDING");
                var stored=LanguageSemanticRequest.from(read(LanguageSemanticRequest.handle(revision.fields().get("request"))));if(!mapped&&!stored.inputClosure().equals(history.scope()))throw new IOException("SEMANTIC_HISTORY_INPUT_CLOSURE");var output=LanguageSemanticRequest.table(revision.fields().get("observations"));var nativeOutput=output;var claims=Objects.requireNonNull(proofs.forRevision(stored,output));Optional<ContentHandle> mapping=mapped?Optional.of(LanguageSemanticRequest.handle(revision.fields().get("correspondence"))):Optional.empty();var mapProof=mapping.map(h->claims.get("correspondence:"+h.digest().value())).orElse(null);
                boolean nativeHistorical=SemanticReconciler.qualified("census",stored,output,content,claims,Set.of())&&SemanticReconciler.qualified("observations",stored,output,content,claims,Set.of());boolean historical=nativeHistorical&&!mapped;boolean active=false,available=true;
                try{var coverage=new SemanticReconciler().reconcile(stored,output,tables,content,claims,revoked,stored::policy,g->{},cancelled);active=stored.policy().equals(request.policy())&&coverage.censusQualified()&&coverage.observationsQualified();if(mapped){var bridge=correspondence();var projection=bridge.project(request,stored,output,mapping.orElseThrow(),cancelled);output=projection.observations();historical=nativeHistorical&&bridge.qualified(request,stored,nativeOutput,projection,mapProof,Set.of());active&=bridge.qualified(request,stored,nativeOutput,projection,mapProof,revoked);if(!active)emitGap(request,"SEMANTIC_CORRESPONDENCE_UNQUALIFIED",revision.identity(),nativeOutput.content().digest(),mapping.orElseThrow().digest(),gaps);}}
                catch(IOException failed){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw failed;available=false;unavailable++;historyGap(request,revision,failed,gaps);}
                boolean unavailableWasClosed=historical&&claims.get("observations").checks().contains("ALL_OBSERVATIONS_CLOSED")&&(!mapped||mapProof!=null&&mapProof.checks().contains("ALL_TARGET_QUESTIONS_CLOSED"));
                current=advance(history.scope(),current,available?Optional.of(output):Optional.empty(),stored,claims,active,historical,unavailableWasClosed,mapping,Optional.ofNullable(mapProof),nativeOutput,cancelled);
            }
        }
        var counts=new TreeMap<String,Long>();for(String state:List.of("PENDING","NARROWED","SATISFIED","NOT_APPLICABLE","CONFLICTED","REOPENED"))counts.put(state,0L);
        SemanticTables.Table conflicts;
        // Conflict witnesses are one pair per question. All answer/support sets
        // remain in the streamed current table, including any further answers.
        var finalCurrent=current;
        try(var rows=tables.open(current,cancelled)){
            var witnesses=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending;boolean end;
                public boolean hasNext(){try{while(pending==null&&!end){var row=rows.next();if(row==null){end=true;break;}String state=GraphJson.string(row.fields().get("state"));counts.compute(state,(k,v)->Math.addExact(v,1));if(!Set.of("SATISFIED","NOT_APPLICABLE").contains(state))currentGap(request,finalCurrent,row,state,gaps);if(state.equals("CONFLICTED"))pending=conflict(request,history,row);}return pending!=null;}catch(IOException failed){throw new UncheckedIOException(failed);}}
                public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var row=pending;pending=null;return row;}
            };conflicts=write(SemanticTables.Kind.CONFLICT,witnesses,cancelled);
        }
        check(request,livePolicy,cancelled);long questions=current.rows();return new Result(history,current,conflicts,questions,unavailable,counts,questions>0&&unavailable==0&&counts.get("SATISFIED")+counts.get("NOT_APPLICABLE")==questions);
    }
    private static SemanticTables.Row conflict(LanguageSemanticRequest request,History history,SemanticTables.Row row){
        var answers=GraphJson.array(row.fields().get("answers"));var left=GraphJson.object(GraphJson.array(GraphJson.object(answers.get(0)).get("supports")).getFirst());var right=GraphJson.object(GraphJson.array(GraphJson.object(answers.get(1)).get("supports")).getFirst());var conflict=ProviderConflictRecord.create(request.context(),reference(left),reference(right),List.of("polyglot.same-input-answer"),ProviderConflictRecord.AdjudicationStatus.UNRESOLVED,Optional.empty(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.semantic")),List.of(),List.of("A deterministic witness pair is shown; every active answer remains in the current question row. Arrival order is not adjudication."));return new SemanticTables.Row(SemanticTables.Kind.CONFLICT,row.key(),history.scope(),fields(conflict));
    }
    private SemanticTables.Table advance(ContentDigest scope,SemanticTables.Table before,Optional<SemanticTables.Table> output,LanguageSemanticRequest request,Map<String,EvidenceVerifier.VerifiedClaim> claims,boolean active,boolean historical,boolean unavailableWasClosed,Optional<ContentHandle> mapping,Optional<EvidenceVerifier.VerifiedClaim> mapProof,SemanticTables.Table nativeOutput,BooleanSupplier cancelled)throws IOException{
        try(var states=tables.open(before,cancelled);var observed=output.isPresent()?tables.open(output.orElseThrow(),cancelled):null){
            final SemanticTables.Row[] pending={observed==null?null:observed.next()};
            var rows=iterator(states,row->{
                var value=pending[0];if(value!=null&&value.key().compareTo(row.key())<0)throw new IOException("SEMANTIC_HISTORY_OBSERVATION_OUTSIDE_CENSUS");if(value!=null&&!value.key().equals(row.key()))value=null;
                boolean seen=Boolean.TRUE.equals(row.fields().get("seen"))||value!=null;boolean wasClosed=value!=null&&Set.of("RESOLVED","NOT_APPLICABLE").contains(GraphJson.string(value.fields().get("status")));boolean had=Boolean.TRUE.equals(row.fields().get("historical"))||historical&&wasClosed||output.isEmpty()&&unavailableWasClosed;var answers=new TreeMap<String,Map<String,Object>>();for(var answer:GraphJson.array(row.fields().get("answers"))){var map=GraphJson.object(answer);answers.put(GraphJson.string(map.get("answer")),map);}
                if(value!=null){
                    if(active&&wasClosed&&sourceMapped(value)){
                        var core=new TreeMap<String,Object>();for(String field:CORE)core.put(field,value.fields().get(field));String answer=CanonicalJson.sha256(List.of("semantic-answer-core:m8.4-v2",core)).value();
                        var supports=new TreeMap<String,Object>();if(answers.containsKey(answer))for(var support:GraphJson.array(answers.get(answer).get("supports")))supports.put(CanonicalJson.sha256(support).value(),support);
                        var evidenceProofs=new ArrayList<>(List.of(claims.get("census").identity().value(),claims.get("observations").identity().value()));if(mapping.isPresent())evidenceProofs.add(mapProof.orElseThrow().identity().value());var support=new TreeMap<String,Object>();support.put("result",output.orElseThrow().content().digest().value());support.put("observation",value.identity().value());support.put("provider",fields(request.provider()));support.put("proofs",evidenceProofs.stream().distinct().sorted().toList());if(mapping.isPresent()){support.put("correspondence",fields(mapping.orElseThrow()));support.put("nativeResult",nativeOutput.content().digest().value());}supports.put(CanonicalJson.sha256(support).value(),support);answers.put(answer,Map.of("answer",answer,"core",core,"supports",List.copyOf(supports.values())));
                    }
                    pending[0]=observed.next();
                }
                String state=answers.size()>1?"CONFLICTED":answers.size()==1?GraphJson.object(answers.firstEntry().getValue().get("core")).get("status").equals("NOT_APPLICABLE")?"NOT_APPLICABLE":"SATISFIED":had?"REOPENED":seen?"NARROWED":"PENDING";
                return new SemanticTables.Row(SemanticTables.Kind.CURRENT,row.key(),scope,Map.of("obligation",row.fields().get("obligation"),"seen",seen,"historical",had,"answers",List.copyOf(answers.values()),"state",state));
            });var result=write(SemanticTables.Kind.CURRENT,rows,cancelled);if(pending[0]!=null)throw new IOException("SEMANTIC_HISTORY_OBSERVATION_OUTSIDE_CENSUS");return result;
        }
    }
    private static boolean sourceMapped(SemanticTables.Row row){return row.fields().get("sourceProjection")==null||!Set.of("PIECEWISE","UNMAPPED","POINT_ONLY").contains(GraphJson.object(row.fields().get("sourceProjection")).get("precision"));}
    /** Structural replay of a saved producer projection. This never restores
     * opaque proof capabilities or certifies saved qualification/currentness. */
    public Result verifyStoredState(LanguageSemanticRequest request,ContentHandle state,BooleanSupplier cancelled)throws IOException{
        var value=read(state);var history=SemanticContextCoordinator.history(value);if(!history.scope().equals(request.inputClosure()))throw new IOException("SEMANTIC_ANSWER_HISTORY_SCOPE");var current=LanguageSemanticRequest.table(value.get("current"));var conflicts=LanguageSemanticRequest.table(value.get("conflicts"));long questions=number(value.get("questions")),unavailable=number(value.get("unavailableRevisions"));
        if(current.kind()!=SemanticTables.Kind.CURRENT||conflicts.kind()!=SemanticTables.Kind.CONFLICT||questions!=request.census().rows()||questions!=current.rows())throw new IOException("SEMANTIC_ANSWER_STATE_DENOMINATOR");
        var counts=new TreeMap<String,Long>();for(String key:List.of("PENDING","NARROWED","SATISFIED","NOT_APPLICABLE","CONFLICTED","REOPENED"))counts.put(key,0L);
        try(var rows=tables.open(current,cancelled);var census=tables.open(request.census(),cancelled);var witnesses=tables.open(conflicts,cancelled)){SemanticTables.Row row;
            while((row=rows.next())!=null){var question=census.next();if(question==null||!row.key().equals(question.key())||!row.scope().equals(history.scope())||!row.fields().keySet().equals(Set.of("obligation","seen","historical","answers","state"))||!(row.fields().get("seen") instanceof Boolean)||!(row.fields().get("historical") instanceof Boolean)||!question.identity().value().equals(row.fields().get("obligation")))throw new IOException("SEMANTIC_ANSWER_QUESTION_BINDING");var answers=GraphJson.array(row.fields().get("answers"));String previous=null;
                for(var raw:answers){var answer=GraphJson.object(raw);if(!answer.keySet().equals(Set.of("answer","core","supports")))throw new IOException("SEMANTIC_ANSWER_CORE_FIELDS");var core=GraphJson.object(answer.get("core"));String schema=coreSchema(core);if(!Set.of("RESOLVED","NOT_APPLICABLE").contains(core.get("status")))throw new IOException("SEMANTIC_ANSWER_CORE_STATUS");String identity=GraphJson.string(answer.get("answer"));if(!identity.equals(CanonicalJson.sha256(List.of(schema,core)).value())||previous!=null&&previous.compareTo(identity)>=0||GraphJson.array(answer.get("supports")).isEmpty())throw new IOException("SEMANTIC_ANSWER_CORE_BINDING");previous=identity;String priorSupport=null;for(var support:GraphJson.array(answer.get("supports"))){var evidence=GraphJson.object(support);String supportIdentity=CanonicalJson.sha256(support).value();if(!evidence.keySet().equals(evidence.containsKey("correspondence")?Set.of("result","observation","provider","proofs","correspondence","nativeResult"):Set.of("result","observation","provider","proofs"))||priorSupport!=null&&priorSupport.compareTo(supportIdentity)>=0||GraphJson.array(evidence.get("proofs")).size()!=(evidence.containsKey("correspondence")?3:2))throw new IOException("SEMANTIC_ANSWER_SUPPORT_FIELDS");if(evidence.containsKey("correspondence")){LanguageSemanticRequest.handle(evidence.get("correspondence"));new ContentDigest(GraphJson.string(evidence.get("nativeResult")));}new ContentDigest(GraphJson.string(evidence.get("result")));new ContentDigest(GraphJson.string(evidence.get("observation")));var provider=GraphJson.object(evidence.get("provider"));new VersionedIdentifier(GraphJson.string(provider.get("id")),GraphJson.string(provider.get("version")));for(var proof:GraphJson.array(evidence.get("proofs")))new ContentDigest(GraphJson.string(proof));priorSupport=supportIdentity;}}
                String expected=answers.size()>1?"CONFLICTED":answers.size()==1?GraphJson.object(GraphJson.object(answers.getFirst()).get("core")).get("status").equals("NOT_APPLICABLE")?"NOT_APPLICABLE":"SATISFIED":Boolean.TRUE.equals(row.fields().get("historical"))?"REOPENED":Boolean.TRUE.equals(row.fields().get("seen"))?"NARROWED":"PENDING";
                if(!expected.equals(row.fields().get("state")))throw new IOException("SEMANTIC_ANSWER_STATE_BINDING");counts.compute(expected,(k,v)->Math.addExact(v,1));
                if(expected.equals("CONFLICTED")){var witness=witnesses.next();if(witness==null||!CanonicalJson.write(conflict(request,history,row)).equals(CanonicalJson.write(witness)))throw new IOException("SEMANTIC_ANSWER_CONFLICT_BINDING");}
            }if(census.next()!=null||witnesses.next()!=null)throw new IOException("SEMANTIC_ANSWER_STATE_DENOMINATOR");
        }
        long ordinal=0,observedUnavailable=0;
        try(var revisions=tables.open(history.revisions(),cancelled)){SemanticTables.Row row;while((row=revisions.next())!=null){boolean mapped=row.fields().containsKey("correspondence");if(!row.scope().equals(history.scope())||!row.key().equals(key(ordinal++))||!row.fields().keySet().equals(mapped?Set.of("request","observations","correspondence"):Set.of("request","observations")))throw new IOException("SEMANTIC_HISTORY_REVISION_BINDING");var stored=LanguageSemanticRequest.from(read(LanguageSemanticRequest.handle(row.fields().get("request"))));if(!mapped&&!stored.inputClosure().equals(history.scope()))throw new IOException("SEMANTIC_HISTORY_INPUT_CLOSURE");var output=LanguageSemanticRequest.table(row.fields().get("observations"));try{new SemanticReconciler().reconcile(stored,output,tables,content,Map.of(),Set.of(),stored::policy,g->{},cancelled);if(mapped)correspondence().project(request,stored,output,LanguageSemanticRequest.handle(row.fields().get("correspondence")),cancelled);}catch(IOException failed){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw failed;observedUnavailable++;}}}
        var savedCounts=GraphJson.object(value.get("counts"));if(!savedCounts.keySet().equals(counts.keySet()))throw new IOException("SEMANTIC_ANSWER_COUNT_FIELDS");for(var count:counts.entrySet())if(number(savedCounts.get(count.getKey()))!=count.getValue())throw new IOException("SEMANTIC_ANSWER_COUNT_BINDING");boolean complete=questions>0&&unavailable==0&&counts.get("SATISFIED")+counts.get("NOT_APPLICABLE")==questions;
        if(unavailable!=observedUnavailable||!Boolean.valueOf(complete).equals(value.get("answersComplete")))throw new IOException("SEMANTIC_ANSWER_COMPLETE_BINDING");return new Result(history,current,conflicts,questions,unavailable,counts,complete);
    }
    @FunctionalInterface private interface Transform{SemanticTables.Row apply(SemanticTables.Row row)throws IOException;}
    private static Iterator<SemanticTables.Row> iterator(SemanticTables.Reader reader,Transform transform){return new Iterator<>(){SemanticTables.Row pending;boolean end;public boolean hasNext(){try{if(pending==null&&!end){pending=reader.next();end=pending==null;}return pending!=null;}catch(IOException failure){throw new UncheckedIOException(failure);}}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var row=pending;pending=null;try{return transform.apply(row);}catch(IOException failure){throw new UncheckedIOException(failure);}}};}
    private SemanticTables.Table write(SemanticTables.Kind kind,Iterator<SemanticTables.Row> rows,BooleanSupplier cancelled)throws IOException{try{return tables.write(kind,rows,cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_HISTORY_RECORD_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_HISTORY_RECORD_NONCANONICAL");return value;}}
    private static Map<String,Object> fields(Object value){return GraphJson.object(GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults()));}
    private static String key(long ordinal){return String.format(Locale.ROOT,"%020d",ordinal);}
    private static long number(Object value)throws IOException{try{long number=((java.math.BigDecimal)value).longValueExact();if(number<0)throw new IOException("SEMANTIC_ANSWER_NEGATIVE_COUNT");return number;}catch(ClassCastException|ArithmeticException|NullPointerException invalid){throw new IOException("SEMANTIC_ANSWER_COUNT_INVALID",invalid);}}
    private static ProviderObservationReference reference(Map<String,Object> support){return ProviderObservationReference.create(PROVIDER,"polyglot.validated-answer",new ContentDigest(GraphJson.string(support.get("result"))),new ContentDigest(GraphJson.string(support.get("observation"))));}
    private static void check(LanguageSemanticRequest request,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_HISTORY_CANCELLED");if(!request.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_HISTORY_POLICY_REVOKED");}
    private static void currentGap(LanguageSemanticRequest request,SemanticTables.Table current,SemanticTables.Row row,String state,Consumer<CapabilityGapRecord> gaps){emitGap(request,"SEMANTIC_ANSWER_"+state,new ContentDigest(GraphJson.string(row.fields().get("obligation"))),current.content().digest(),row.identity(),gaps);}
    private static void historyGap(LanguageSemanticRequest request,SemanticTables.Row revision,IOException failure,Consumer<CapabilityGapRecord> gaps){emitGap(request,"SEMANTIC_HISTORY_REVISION_UNAVAILABLE",revision.identity(),revision.scope(),CanonicalJson.sha256(List.of(revision.identity(),failure.getClass().getName(),ContentDigest.sha256Utf8(Objects.toString(failure.getMessage(),"")))),gaps);}
    private static void emitGap(LanguageSemanticRequest request,String reason,ContentDigest subject,ContentDigest result,ContentDigest payload,Consumer<CapabilityGapRecord> gaps){
        var source=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,subject.value());var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.semantic-answer",List.of(source),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Acquire or revalidate exact native evidence; qualify proofs independently and retain every contradictory answer until adjudicated."));gaps.accept(CapabilityGapRecord.create(SemanticReconciler.CATALOG,request.context(),PROVIDER,"polyglot.semantic",reason,source,List.of(),List.of(ProviderObservationReference.create(PROVIDER,"polyglot.answer-current",result,payload)),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.semantic")),List.of(),List.of(),List.of("Same-input question state is distinct from root source/context closure. Saved state and arrival order are not authority.")));
    }
}
