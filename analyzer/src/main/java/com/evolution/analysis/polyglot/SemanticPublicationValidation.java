package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Operator composition of live verification capabilities. No signer, key or
 * method can be registered through target output or a saved publication. */
public final class SemanticPublicationValidation {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.publication-validation","m8.4-v1");
    public static final class Validated {
        private final ContentDigest plan,policy;private final ContentHandle report;private final boolean complete;private final BooleanSupplier current;
        private Validated(ContentDigest plan,ContentDigest policy,ContentHandle report,boolean complete,BooleanSupplier current){this.plan=plan;this.policy=policy;this.report=report;this.complete=complete;this.current=current;}
        public boolean matches(ContentDigest plan,ContentDigest policy,ContentHandle report){try{return this.plan.equals(plan)&&this.policy.equals(policy)&&this.report.equals(report)&&current.getAsBoolean();}catch(UncheckedIOException unavailable){return false;}}
        public boolean complete(){return complete;}
    }
    private final RepositoryContentStore content;private final SemanticTables tables;private final SemanticAnswerLedger.Proofs claims;private final SemanticContextCoordinator.RootClaims rootClaims;private final SemanticContextCoordinator.CensusIndex indexes;private final Supplier<Set<ContentDigest>> revoked;private final Supplier<ContentDigest> livePolicy;
    public SemanticPublicationValidation(RepositoryContentStore content,SemanticTables tables,SemanticAnswerLedger.Proofs claims,SemanticContextCoordinator.RootClaims rootClaims,SemanticContextCoordinator.CensusIndex indexes,Supplier<Set<ContentDigest>> revoked,Supplier<ContentDigest> livePolicy){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.claims=Objects.requireNonNull(claims);this.rootClaims=Objects.requireNonNull(rootClaims);this.indexes=Objects.requireNonNull(indexes);this.revoked=Objects.requireNonNull(revoked);this.livePolicy=Objects.requireNonNull(livePolicy);}
    /** Recompute every saved coverage/current/conflict projection. Cold loading
     * must invoke this again; the capability itself is never serialized. */
    public Validated validate(SemanticContextCoordinator.Plan plan,ContentHandle reportHandle,BooleanSupplier cancelled)throws IOException {
        try {
            check(plan,cancelled);var revocations=Set.copyOf(revoked.get());var report=read(reportHandle);
            if(!"semantic-root-report:m8.4-v2".equals(report.get("schema"))||!same(plan,report.get("plan"))||!report.keySet().equals(Set.of("schema","plan","contexts","selected","processed","failed","missingProviders","incomplete","rootGap","complete","rootCensus")))throw new IOException("SEMANTIC_AUTHENTICATED_REPORT_BINDING");
            var contexts=LanguageSemanticRequest.table(report.get("contexts"));long processed=0,failed=0,missing=0,incomplete=0;
            try(var selected=tables.open(plan.contexts(),cancelled);var children=tables.open(contexts,cancelled)){
                SemanticTables.Row selection;while((selection=selected.next())!=null){check(plan,cancelled);var row=children.next();if(row==null||!row.key().equals(selection.key())||!row.scope().equals(plan.identity()))throw new IOException("SEMANTIC_AUTHENTICATED_CONTEXT_DENOMINATOR");
                    var receipt=read(LanguageSemanticRequest.handle(row.fields().get("receipt")));var request=LanguageSemanticRequest.from(read(LanguageSemanticRequest.handle(receipt.get("request"))));
                    if(!same(receipt.get("request"),selection.fields().get("request"))||!request.identity().value().equals(row.fields().get("request"))||!request.identity().value().equals(selection.fields().get("identity"))||!request.scope().value().equals(selection.fields().get("scope"))||!request.policy().equals(plan.policy())||!request.inventory().equals(plan.inventory().inventory())||!request.context().snapshotIdentity().equals(plan.root().snapshotIdentity())||!plan.identity().value().equals(receipt.get("plan"))||!row.key().equals(receipt.get("key")))throw new IOException("SEMANTIC_AUTHENTICATED_REQUEST_BINDING");
                    String state=GraphJson.string(receipt.get("state"));if(!state.equals(row.fields().get("state")))throw new IOException("SEMANTIC_AUTHENTICATED_CHILD_STATE");
                    if(receipt.get("observations")!=null){
                        var observations=LanguageSemanticRequest.table(receipt.get("observations"));var proofs=Objects.requireNonNull(claims.forRevision(request,observations));var coverage=new SemanticReconciler().reconcile(request,observations,tables,content,proofs,revocations,livePolicy,g->{},cancelled);
                        if(!same(coverage,receipt.get("coverage"))||receipt.get("answers")==null)throw new IOException("SEMANTIC_AUTHENTICATED_COVERAGE_STALE");
                        if(!Set.of("COMPLETE","PARTIAL").contains(state))throw new IOException("SEMANTIC_AUTHENTICATED_CHILD_STATE");
                    }else if(!Set.of("FAILED","MISSING_PROVIDER").contains(state))throw new IOException("SEMANTIC_AUTHENTICATED_CHILD_STATE");
                    if(receipt.get("answers")!=null){
                        var ledger=new SemanticAnswerLedger(content,tables,Optional.of(indexes));var saved=ledger.verifyStoredState(request,LanguageSemanticRequest.handle(receipt.get("answers")),cancelled);var current=ledger.fold(request,saved.history(),claims,revocations,livePolicy,g->{},cancelled);
                        if(!same(current,saved)||receipt.containsKey("history")&&!same(saved.history(),receipt.get("history"))||receipt.get("observations")!=null&&state.equals("COMPLETE")!=(Boolean.TRUE.equals(GraphJson.object(receipt.get("coverage")).get("complete"))&&current.answersComplete()))throw new IOException("SEMANTIC_AUTHENTICATED_ANSWER_STALE");
                    }else if(receipt.get("history")!=null)throw new IOException("SEMANTIC_AUTHENTICATED_CURRENT_UNAVAILABLE");
                    processed=Math.addExact(processed,1);if(state.equals("FAILED"))failed++;if(state.equals("MISSING_PROVIDER"))missing++;if(!state.equals("COMPLETE"))incomplete++;
                }if(children.next()!=null)throw new IOException("SEMANTIC_AUTHENTICATED_CONTEXT_DENOMINATOR");
            }
            var savedCensus=GraphJson.object(report.get("rootCensus"));var input=SemanticRootCensus.input(savedCensus.get("input"));var rootProof=rootClaims.forInput(plan,input);SemanticRootCensus.Result census;
            try(var index=indexes.open()){census=new SemanticRootCensus(content,tables).reconcile(plan,input,index,rootProof,revocations,livePolicy,cancelled);}
            if(!census.qualified()||!same(census,savedCensus))throw new IOException("SEMANTIC_AUTHENTICATED_ROOT_CENSUS_STALE");
            boolean complete=incomplete==0;
            if(number(report.get("selected"))!=plan.contexts().rows()||number(report.get("processed"))!=processed||processed!=plan.contexts().rows()||number(report.get("failed"))!=failed||number(report.get("missingProviders"))!=missing||number(report.get("incomplete"))!=incomplete||!Boolean.valueOf(complete).equals(report.get("complete"))||complete&&report.get("rootGap")!=null)throw new IOException("SEMANTIC_AUTHENTICATED_ROOT_COUNTS");
            if(!revocations.equals(Set.copyOf(revoked.get())))throw new IOException("SEMANTIC_AUTHENTICATED_REVOCATIONS_CHANGED");check(plan,cancelled);return new Validated(plan.identity(),plan.policy(),reportHandle,complete,()->plan.policy().equals(livePolicy.get())&&revocations.equals(Set.copyOf(revoked.get())));
        }catch(UncheckedIOException unavailable){throw unavailable.getCause();}catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("SEMANTIC_AUTHENTICATED_REPORT_INVALID",invalid);}
    }
    private void check(SemanticContextCoordinator.Plan plan,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_AUTHENTICATED_CANCELLED");if(!plan.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_AUTHENTICATED_POLICY_REVOKED");}
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_AUTHENTICATED_RECORD_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_AUTHENTICATED_RECORD_NONCANONICAL");return value;}}
    private static boolean same(Object left,Object right){return CanonicalJson.write(left).equals(CanonicalJson.write(right));}
    private static long number(Object value){long number=((java.math.BigDecimal)value).longValueExact();if(number<0)throw new IllegalArgumentException("NEGATIVE_COUNT");return number;}
}
