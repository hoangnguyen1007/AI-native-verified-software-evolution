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

/** One precommitted context plan, streamed child receipts and truthful
 * root accounting. Data providers may replay captured native outputs; executable
 * providers must still use their registered isolated execution lane.
 */
public final class SemanticContextCoordinator {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.context-coordinator","m8.4-v4");
    public record Selection(String key,LanguageSemanticRequest request) {public Selection{if(key==null||key.isBlank())throw new IllegalArgumentException("SEMANTIC_CONTEXT_KEY");Objects.requireNonNull(request);}}
    public record Plan(EvidenceContext root,PolyglotInventory.Report inventory,SemanticTables.Table contexts,ContentDigest policy,VersionedIdentifier coordinator){
        public Plan{Objects.requireNonNull(root);Objects.requireNonNull(inventory);Objects.requireNonNull(policy);Objects.requireNonNull(coordinator);if(!coordinator.id().equals(PROVIDER.id())||contexts.kind()!=SemanticTables.Kind.CONTEXT)throw new IllegalArgumentException("SEMANTIC_CONTEXT_PLAN_KIND");}
        public Plan(EvidenceContext root,PolyglotInventory.Report inventory,SemanticTables.Table contexts,ContentDigest policy){this(root,inventory,contexts,policy,PROVIDER);}
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("semantic-context-plan:m8.4-v2",this));}
        public static Plan from(Object value){
            var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("root","inventory","contexts","policy","coordinator")))throw new IllegalArgumentException("SEMANTIC_CONTEXT_PLAN_FIELDS");
            var context=GraphJson.object(row.get("root"));var inventory=GraphJson.object(row.get("inventory"));var coordinator=GraphJson.object(row.get("coordinator"));
            if(!context.keySet().equals(Set.of("snapshotIdentity","analysisIdentity"))||!inventory.keySet().equals(Set.of("inventory","entries","files","unresolvedClassification","classifier"))||!coordinator.keySet().equals(Set.of("id","version")))throw new IllegalArgumentException("SEMANTIC_CONTEXT_PLAN_SCOPE_FIELDS");
            var classifier=GraphJson.object(inventory.get("classifier"));if(!classifier.keySet().equals(Set.of("id","version")))throw new IllegalArgumentException("SEMANTIC_CONTEXT_PLAN_CLASSIFIER_FIELDS");
            var root=new EvidenceContext(new com.evolution.analysis.contract.identity.SnapshotIdentity(GraphJson.string(context.get("snapshotIdentity"))),Optional.ofNullable(context.get("analysisIdentity")).map(id->new com.evolution.analysis.contract.identity.AnalysisIdentity(GraphJson.string(id))));
            var report=new PolyglotInventory.Report(new ContentDigest(GraphJson.string(inventory.get("inventory"))),LanguageSemanticRequest.table(inventory.get("entries")),((java.math.BigDecimal)inventory.get("files")).longValueExact(),((java.math.BigDecimal)inventory.get("unresolvedClassification")).longValueExact(),new VersionedIdentifier(GraphJson.string(classifier.get("id")),GraphJson.string(classifier.get("version"))));
            return new Plan(root,report,LanguageSemanticRequest.table(row.get("contexts")),new ContentDigest(GraphJson.string(row.get("policy"))),new VersionedIdentifier(GraphJson.string(coordinator.get("id")),GraphJson.string(coordinator.get("version"))));
        }
    }
    public record Checkpoint(ContentDigest plan,long ordinal,String key,ContentHandle receipt){public Checkpoint{if(ordinal<0)throw new IllegalArgumentException("SEMANTIC_CONTEXT_ORDINAL");Objects.requireNonNull(plan);Objects.requireNonNull(key);Objects.requireNonNull(receipt);}}
    public record Root(ContentDigest plan,SemanticTables.Table contexts,ContentHandle report,long selected,long processed,long failed,long missingProviders,long incomplete,boolean selectionQualified,boolean complete){}
    @FunctionalInterface public interface Providers{Optional<LanguageSemanticProvider> find(LanguageSemanticRequest request)throws IOException;}
    @FunctionalInterface public interface Claims{Map<String,EvidenceVerifier.VerifiedClaim> forRequest(LanguageSemanticRequest request)throws IOException;}
    @FunctionalInterface public interface Histories{Optional<SemanticAnswerLedger.History> forRequest(LanguageSemanticRequest request)throws IOException;}
    @FunctionalInterface public interface CensusIndex{SemanticRootCensus.Index open()throws IOException;}
    @FunctionalInterface public interface RootClaims{Optional<EvidenceVerifier.VerifiedClaim> forInput(Plan plan,SemanticRootCensus.Input input)throws IOException;}
    public record RootCensus(SemanticRootCensus.Input input,CensusIndex index,Optional<EvidenceVerifier.VerifiedClaim> proof){public RootCensus{Objects.requireNonNull(input);Objects.requireNonNull(index);Objects.requireNonNull(proof);}}
    public record RepositoryInputs(String revision,InventoryReader inventory,com.evolution.analysis.readiness.ReadinessPlan readiness,SemanticTables.Table capturedContexts,ContentDigest validatorTool,CensusIndex index){public RepositoryInputs{if(revision==null||revision.isBlank())throw new IllegalArgumentException("SEMANTIC_REPOSITORY_REVISION");Objects.requireNonNull(inventory);Objects.requireNonNull(readiness);Objects.requireNonNull(capturedContexts);Objects.requireNonNull(validatorTool);Objects.requireNonNull(index);}}
    private final RepositoryContentStore content;private final SemanticTables tables;
    public SemanticContextCoordinator(RepositoryContentStore content,SemanticTables tables){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);}
    public Plan plan(EvidenceContext root,PolyglotInventory.Report inventory,Iterator<Selection> selected,ContentDigest policy,BooleanSupplier cancelled)throws IOException{
        verify(inventory.entries(),cancelled);
        var rows=new Iterator<SemanticTables.Row>(){
            public boolean hasNext(){return selected.hasNext();}
            public SemanticTables.Row next(){var item=selected.next();try{return new SemanticTables.Row(SemanticTables.Kind.CONTEXT,item.key(),inventory.identity(),Map.of("request",put(item.request(),cancelled),"identity",item.request().identity(),"scope",item.request().scope()));}catch(IOException failure){throw new UncheckedIOException(failure);}}
        };
        try{return new Plan(root,inventory,tables.write(SemanticTables.Kind.CONTEXT,rows,cancelled),policy);}catch(UncheckedIOException failed){throw failed.getCause();}
    }
    /** Resume receipts are untrusted references. Recheck their plan/key/request and
     * output bytes, then recompute coverage under current policy and revocations.
     * A receipt never restores an opaque authority capability or a saved pass bit.
     */
    public Root run(Plan plan,Providers providers,Claims claims,Map<String,ContentHandle> resume,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,
            Consumer<Checkpoint> recorder,BooleanSupplier cancelled)throws IOException{
        return runWithHistory(plan,providers,(request,observations)->claims.forRequest(request),request->Optional.empty(),resume,revoked,livePolicy,recorder,cancelled);
    }
    public Root runWithHistory(Plan plan,Providers providers,SemanticAnswerLedger.Proofs claims,Histories histories,Map<String,ContentHandle> resume,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,
            Consumer<Checkpoint> recorder,BooleanSupplier cancelled)throws IOException{
        return runWithRootCensus(plan,providers,claims,histories,Optional.empty(),resume,revoked,livePolicy,recorder,cancelled);
    }
    /** Actual sealed repository entry point. Captured native input lists remain
     * independent of semantic emissions and cannot supply their own authority. */
    public Root runRepository(Plan plan,RepositoryInputs inputs,Providers providers,SemanticAnswerLedger.Proofs claims,Histories histories,RootClaims rootClaims,Map<String,ContentHandle> resume,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,Consumer<Checkpoint> recorder,BooleanSupplier cancelled)throws IOException{
        if(!plan.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_ROOT_POLICY_REVOKED");SemanticRootCensus.Input census;
        try(var index=inputs.index().open()){census=new SemanticRepositoryCensus(content,tables).produce(inputs.revision(),inputs.inventory(),inputs.readiness(),plan,inputs.capturedContexts(),index,inputs.validatorTool(),cancelled);}
        var proof=rootClaims.forInput(plan,census);return runWithRootCensus(plan,providers,claims,histories,Optional.of(new RootCensus(census,inputs.index(),proof)),resume,revoked,livePolicy,recorder,cancelled);
    }
    public Root runWithRootCensus(Plan plan,Providers providers,SemanticAnswerLedger.Proofs claims,Histories histories,Optional<RootCensus> census,Map<String,ContentHandle> resume,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,
            Consumer<Checkpoint> recorder,BooleanSupplier cancelled)throws IOException{
        if(!plan.coordinator().equals(PROVIDER))throw new IOException("SEMANTIC_COORDINATOR_VERSION_UNAVAILABLE");
        if(!plan.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_ROOT_POLICY_REVOKED");
        verify(plan.inventory().entries(),cancelled);var counts=new long[5];var seenScopes=new HashSet<ContentDigest>();var seenResume=new HashSet<String>();
        try(var planned=tables.open(plan.contexts(),cancelled)){
            var rows=new Iterator<SemanticTables.Row>(){
                SemanticTables.Row pending;
                public boolean hasNext(){try{if(pending==null)pending=planned.next();return pending!=null;}catch(IOException failure){throw new UncheckedIOException(failure);}}
                public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var row=pending;pending=null;
                    try{
                        if(!row.scope().equals(plan.inventory().identity())||!row.fields().keySet().equals(Set.of("request","identity","scope")))throw new IOException("SEMANTIC_SELECTED_CONTEXT_SCOPE");
                        var requestHandle=LanguageSemanticRequest.handle(normalize(row.fields().get("request")));var request=LanguageSemanticRequest.from(read(requestHandle));
                        if(!request.context().snapshotIdentity().equals(plan.root().snapshotIdentity())||!request.inventory().equals(plan.inventory().inventory())||!request.policy().equals(plan.policy())||!request.identity().value().equals(text(row.fields().get("identity")))||!request.scope().value().equals(text(row.fields().get("scope")))||!seenScopes.add(request.scope()))throw new IOException("SEMANTIC_SELECTED_CONTEXT_IDENTITY_OR_DUPLICATE");
                        Optional<ContentHandle> previous=Optional.ofNullable(resume.get(row.key()));Optional<SemanticTables.Table> observations=Optional.empty();Optional<SemanticAnswerLedger.History> savedHistory=Optional.empty();
                        if(previous.isPresent()){
                            var saved=read(previous.orElseThrow());
                            boolean legacy="semantic-context-receipt:m8.4-v1".equals(saved.get("schema")),retained="semantic-context-receipt:m8.4-v3".equals(saved.get("schema"));var expectedFields=new HashSet<>(Set.of("schema","plan","key","request","observations","coverage","gaps","state","reason","failure","parent"));if(!legacy)expectedFields.add("answers");if(retained)expectedFields.add("history");
                            if(!saved.keySet().equals(expectedFields)||!legacy&&!retained&&!"semantic-context-receipt:m8.4-v2".equals(saved.get("schema"))||!plan.identity().value().equals(saved.get("plan"))||!row.key().equals(saved.get("key"))||!requestHandle.equals(LanguageSemanticRequest.handle(saved.get("request"))))throw new IOException("SEMANTIC_RESUME_RECEIPT_SCOPE");
                            if(saved.get("observations")!=null)observations=Optional.of(LanguageSemanticRequest.table(saved.get("observations")));seenResume.add(row.key());
                            if(saved.get("answers")!=null)savedHistory=Optional.of(history(read(LanguageSemanticRequest.handle(saved.get("answers")))));
                            if(retained&&saved.get("history")!=null){var raw=SemanticAnswerLedger.History.from(saved.get("history"));if(savedHistory.isPresent()&&!savedHistory.orElseThrow().equals(raw))throw new IOException("SEMANTIC_RESUME_HISTORY_BINDING");savedHistory=Optional.of(raw);}
                        }
                        String state="PARTIAL",reason="";SemanticReconciler.Coverage coverage=null;var gaps=new ArrayList<CapabilityGapRecord>();Optional<ContentHandle> failureRecord=Optional.empty();Optional<ContentHandle> answerRecord=Optional.empty();
                        var ledger=new SemanticAnswerLedger(content,tables,census.map(RootCensus::index));var retainedHistory=savedHistory;
                        try{
                            var external=histories.forRequest(request);if(retainedHistory.isPresent()&&external.isPresent()&&!retainedHistory.orElseThrow().identity().equals(external.orElseThrow().identity()))throw new IOException("SEMANTIC_ANSWER_HISTORY_SOURCE_CONFLICT");if(retainedHistory.isEmpty())retainedHistory=external;
                            if(observations.isEmpty()){
                                var provider=providers.find(request);
                                if(provider.isEmpty()){state="MISSING_PROVIDER";reason="SEMANTIC_PROVIDER_NOT_REGISTERED";counts[2]++;}
                                else observations=Optional.of(provider.orElseThrow().analyze(request,cancelled));
                            }
                            if(observations.isPresent()){
                                coverage=new SemanticReconciler().reconcile(request,observations.orElseThrow(),tables,content,claims.forRevision(request,observations.orElseThrow()),revoked,livePolicy,gaps::add,cancelled);
                                var appended=ledger.append(request,observations.orElseThrow(),retainedHistory,cancelled);retainedHistory=Optional.of(appended);var answers=ledger.fold(request,appended,claims,revoked,livePolicy,g->{if(!Set.of("SEMANTIC_ANSWER_NARROWED","SEMANTIC_ANSWER_PENDING").contains(g.reasonCode()))gaps.add(g);},cancelled);
                                // Method/omission gaps above already cover narrowed or pending
                                // rows. Their complete denominator remains in answers.current.
                                var answerFields=new TreeMap<String,Object>(fields(answers));answerFields.put("schema","semantic-answer-state:m8.4-v1");answerRecord=Optional.of(put(answerFields,cancelled));state=coverage.complete()&&answers.answersComplete()?"COMPLETE":"PARTIAL";
                            }
                        }catch(IOException|IllegalArgumentException failure){
                            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_ROOT_CANCELLED_WITH_CHECKPOINT",failure);
                            state="FAILED";reason="SEMANTIC_PROVIDER_OR_INPUT_FAILED";observations=Optional.empty();counts[1]++;
                            coverage=null;answerRecord=Optional.empty();
                            String code=Objects.toString(failure.getMessage(),"");
                            if(!code.matches("(?:SEMANTIC|NATIVE|CAPTURE|SOURCE|JAVA|POLYGLOT)_[A-Z_0-9]{1,100}"))code="UNCLASSIFIED_PROVIDER_FAILURE";
                            failureRecord=Optional.of(put(Map.of("schema","semantic-provider-failure:m8.4-v1","selectedContext",row.identity(),"type",failure.getClass().getName(),"code",code,"messageDigest",ContentDigest.sha256Utf8(Objects.toString(failure.getMessage(),""))),cancelled));
                        }
                        if(answerRecord.isEmpty()&&retainedHistory.isPresent()){
                            try{var answers=ledger.fold(request,retainedHistory.orElseThrow(),claims,revoked,livePolicy,gaps::add,cancelled);var answerFields=new TreeMap<String,Object>(fields(answers));answerFields.put("schema","semantic-answer-state:m8.4-v1");answerRecord=Optional.of(put(answerFields,cancelled));}
                            catch(IOException|IllegalArgumentException unavailable){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_ROOT_CANCELLED_WITH_CHECKPOINT",unavailable);gaps.add(gap(plan,row,"SEMANTIC_ANSWER_HISTORY_REPLAY_FAILED",Optional.of(put(retainedHistory.orElseThrow(),cancelled))));}
                        }
                        if(!reason.isEmpty())gaps.add(gap(plan,row,reason,failureRecord));
                        if(!state.equals("COMPLETE"))counts[3]++;
                        var gapRows=gaps.stream().distinct().sorted().map(g->new SemanticTables.Row(SemanticTables.Kind.CAPABILITY_GAP,g.gapIdentity().value(),request.scope(),fields(g))).iterator();
                        var gapTable=tables.write(SemanticTables.Kind.CAPABILITY_GAP,gapRows,cancelled);var fields=new TreeMap<String,Object>();
                        fields.put("schema","semantic-context-receipt:m8.4-v3");fields.put("plan",plan.identity());fields.put("key",row.key());fields.put("request",requestHandle);fields.put("observations",observations.orElse(null));fields.put("coverage",coverage);fields.put("gaps",gapTable);fields.put("answers",answerRecord.orElse(null));fields.put("history",retainedHistory.orElse(null));fields.put("state",state);fields.put("reason",reason);fields.put("failure",failureRecord.orElse(null));fields.put("parent",previous.orElse(null));
                        var receipt=put(fields,cancelled);recorder.accept(new Checkpoint(plan.identity(),counts[0],row.key(),receipt));counts[0]++;
                        return new SemanticTables.Row(SemanticTables.Kind.CONTEXT,row.key(),plan.identity(),Map.of("receipt",receipt,"state",state,"request",request.identity()));
                    }catch(IOException failure){throw new UncheckedIOException(failure);}
                }
            };
            SemanticTables.Table output;
            try{output=tables.write(SemanticTables.Kind.CONTEXT,rows,cancelled);}catch(UncheckedIOException failure){throw failure.getCause();}
            if(counts[0]!=plan.contexts().rows()||!seenResume.equals(resume.keySet()))throw new IOException("SEMANTIC_ROOT_CONTEXT_DENOMINATOR");
            if(!plan.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_ROOT_POLICY_REVOKED");
            // Root source/native-context enumeration requires a separate opaque
            // method capability. Child semantic proofs cannot qualify that census.
            SemanticRootCensus.Result rootCensus=null;
            if(census.isPresent()){var supplied=census.orElseThrow();try(var index=supplied.index().open()){rootCensus=new SemanticRootCensus(content,tables).reconcile(plan,supplied.input(),index,supplied.proof(),revoked,livePolicy,cancelled);}}
            boolean qualified=rootCensus!=null&&rootCensus.qualified(),complete=qualified&&counts[3]==0;
            var reportFields=new TreeMap<String,Object>();reportFields.put("schema",rootCensus==null?"semantic-root-report:m8.4-v1":"semantic-root-report:m8.4-v2");reportFields.put("plan",plan);reportFields.put("contexts",output);reportFields.put("selected",plan.contexts().rows());reportFields.put("processed",counts[0]);reportFields.put("failed",counts[1]);reportFields.put("missingProviders",counts[2]);reportFields.put("incomplete",counts[3]);reportFields.put("rootGap",complete?null:gap(plan,null,!qualified?"ROOT_SOURCE_CONTEXT_CENSUS_METHOD_UNQUALIFIED":"ROOT_CHILD_SEMANTICS_INCOMPLETE"));reportFields.put("complete",complete);if(rootCensus!=null)reportFields.put("rootCensus",rootCensus);
            if(!plan.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_ROOT_POLICY_REVOKED");var report=put(reportFields,cancelled);
            return new Root(plan.identity(),output,report,plan.contexts().rows(),counts[0],counts[1],counts[2],counts[3],qualified,complete);
        }
    }
    static SemanticAnswerLedger.History history(Map<String,Object> answerState)throws IOException{
        if(!answerState.keySet().equals(Set.of("schema","history","current","conflicts","questions","unavailableRevisions","counts","answersComplete"))||!"semantic-answer-state:m8.4-v1".equals(answerState.get("schema")))throw new IOException("SEMANTIC_ANSWER_STATE_FIELDS");var history=GraphJson.object(answerState.get("history"));if(!history.keySet().equals(Set.of("scope","revisions")))throw new IOException("SEMANTIC_ANSWER_HISTORY_FIELDS");return new SemanticAnswerLedger.History(new ContentDigest(GraphJson.string(history.get("scope"))),LanguageSemanticRequest.table(history.get("revisions")));
    }
    private void verify(SemanticTables.Table table,BooleanSupplier cancelled)throws IOException{try(var reader=tables.open(table,cancelled)){while(reader.next()!=null){}}}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
    private Map<String,Object> read(ContentHandle handle)throws IOException{
        if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_COORDINATOR_DOCUMENT_UNAVAILABLE_OR_WORKER_REQUIRED");
        try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_COORDINATOR_NONCANONICAL_DOCUMENT");return value;}
    }
    private static String text(Object value){return value instanceof ContentDigest digest?digest.value():GraphJson.string(value);}
    private static Object normalize(Object value){return GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults());}
    private static Map<String,Object> fields(Object value){return GraphJson.object(normalize(value));}
    private static CapabilityGapRecord gap(Plan plan,SemanticTables.Row row,String reason){
        return gap(plan,row,reason,Optional.empty());
    }
    private static CapabilityGapRecord gap(Plan plan,SemanticTables.Row row,String reason,Optional<ContentHandle> failure){
        var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,row==null?plan.contexts().identity().value():row.identity().value());
        var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.context-evidence",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Retain source membership and every selected context; obtain the missing provider or independent root-census validation."));
        return CapabilityGapRecord.create(SemanticReconciler.CATALOG,plan.root(),PROVIDER,"polyglot.contexts",reason,subject,List.of(),List.of(ProviderObservationReference.create(PROVIDER,"polyglot.contexts",plan.identity(),failure.map(ContentHandle::digest).orElseGet(()->CanonicalJson.sha256(List.of(reason,subject))))),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.contexts")),List.of(),List.of(),List.of("Operational completion does not establish root source/context closure."));
    }
}
