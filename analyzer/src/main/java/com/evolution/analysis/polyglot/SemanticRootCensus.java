package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;

/** Independent repository membership accounting. Native document paths are never
 * guessed to be repository paths. All joins use explicit captured bindings. */
public final class SemanticRootCensus {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.root-census","m8.4-v3");
    public record Input(SemanticTables.Table sources,SemanticTables.Table contexts,SemanticTables.Table memberships,ContentDigest validatorTool){
        public Input{Objects.requireNonNull(validatorTool);if(sources.kind()!=SemanticTables.Kind.CENSUS||contexts.kind()!=SemanticTables.Kind.CONTEXT||memberships.kind()!=SemanticTables.Kind.DOCUMENT)throw new IllegalArgumentException("SEMANTIC_ROOT_CENSUS_KIND");}
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("semantic-root-census-input:m8.4-v1",this));}
    }
    /** Scratch index belongs to one evaluation. Implementations must reject
     * duplicate keys, offer bounded cursors and never interpret target paths. */
    public interface Index extends AutoCloseable {
        void put(String space,String key,SemanticTables.Row row)throws IOException;
        Optional<SemanticTables.Row> get(String space,String key)throws IOException;
        void mark(String space,String key)throws IOException;
        Rows rows(String space,boolean unmarkedOnly)throws IOException;
        @Override void close()throws IOException;
    }
    public interface Rows extends AutoCloseable {SemanticTables.Row next()throws IOException;@Override void close()throws IOException;}
    public record Accounting(long entries,long requiredSources,long selectedContexts,long excludedContexts,long documents,long expectedDocuments,long memberships,long generated,long external,long embedded,long omittedNativeDocuments,long omittedNativeMemberships,long open){
        public Accounting{for(long count:new long[]{entries,requiredSources,selectedContexts,excludedContexts,documents,expectedDocuments,memberships,generated,external,embedded,omittedNativeDocuments,omittedNativeMemberships,open})if(count<0)throw new IllegalArgumentException("SEMANTIC_ROOT_CENSUS_COUNT");if(requiredSources>entries||memberships>expectedDocuments||omittedNativeDocuments>expectedDocuments||omittedNativeMemberships>expectedDocuments||expectedDocuments-omittedNativeDocuments>documents||Math.addExact(Math.addExact(generated,external),embedded)>expectedDocuments)throw new IllegalArgumentException("SEMANTIC_ROOT_CENSUS_COUNT");}
    }
    public record Result(VersionedIdentifier evaluator,Input input,Accounting accounting,SemanticTables.Table gaps,boolean structurallyClosed,boolean qualified,Optional<ContentDigest> proof){
        public Result{Objects.requireNonNull(evaluator);Objects.requireNonNull(input);Objects.requireNonNull(accounting);Objects.requireNonNull(gaps);Objects.requireNonNull(proof);if(!evaluator.id().equals(PROVIDER.id())||gaps.kind()!=SemanticTables.Kind.CAPABILITY_GAP||qualified!=proof.isPresent()||structurallyClosed!=(accounting.open()==0)||qualified&&(!structurallyClosed||gaps.rows()!=0))throw new IllegalArgumentException("SEMANTIC_ROOT_CENSUS_RESULT");}
        public ContentDigest identity(){return CanonicalJson.sha256(List.of("semantic-root-census-result:m8.4-v1",this));}
    }
    private final RepositoryContentStore content;private final SemanticTables tables;
    public SemanticRootCensus(RepositoryContentStore content,SemanticTables tables){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);}
    public Result reconcile(SemanticContextCoordinator.Plan plan,Input input,Index index,Optional<EvidenceVerifier.VerifiedClaim> proof,Set<ContentDigest> revoked,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{
        check(plan,livePolicy,cancelled);var counts=new long[13];
        // The caller supplies a fresh scratch index. Rows are not authoritative
        // unless independently joined to the exact immutable input tables here.
        for(String space:List.of("sources","required","contexts","scopes","enumerated","documents","native-inputs","captured-contexts","gaps"))try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("SEMANTIC_ROOT_INDEX_NOT_EMPTY");}
        try{
            try(var inventory=tables.open(plan.inventory().entries(),cancelled);var dispositions=tables.open(input.sources(),cancelled)){
                var expected=dispositions.next();SemanticTables.Row entry;long files=0;
                while((entry=inventory.next())!=null){
                    check(plan,livePolicy,cancelled);if(!entry.scope().equals(plan.inventory().inventory()))throw new IOException("SEMANTIC_ROOT_INVENTORY_SCOPE");counts[0]=Math.addExact(counts[0],1);
                    if(text(entry,"kind").equals("FILE"))files=Math.addExact(files,1);
                    if(expected!=null&&expected.key().compareTo(entry.key())<0)throw new IOException("SEMANTIC_ROOT_SOURCE_OUTSIDE_INVENTORY");
                    String disposition="UNKNOWN";
                    if(expected==null||!expected.key().equals(entry.key()))issue(plan,index,counts,"ROOT_SOURCE_DISPOSITION_OMITTED",entry.identity());
                    else{
                        fields(expected,Set.of("entry","disposition","reason","evidence"));scope(plan,expected);evidence(expected,"evidence");
                        if(!entry.identity().value().equals(text(expected,"entry")))throw new IOException("SEMANTIC_ROOT_SOURCE_IDENTITY");disposition=text(expected,"disposition");
                        if(!Set.of("REQUIRED","NON_SEMANTIC","DEFERRED","UNAVAILABLE","UNKNOWN").contains(disposition))throw new IOException("SEMANTIC_ROOT_SOURCE_DISPOSITION");
                        if(Set.of("DEFERRED","UNAVAILABLE","UNKNOWN").contains(disposition))issue(plan,index,counts,"ROOT_SOURCE_DISPOSITION_OPEN",expected.identity());
                        if(text(entry,"kind").equals("FILE")&&!text(entry,"disposition").equals("ACQUIRED")&&!Set.of("DEFERRED","UNAVAILABLE","UNKNOWN").contains(disposition))issue(plan,index,counts,"ROOT_SOURCE_BYTES_UNAVAILABLE",entry.identity());
                        expected=dispositions.next();
                    }
                    var values=new TreeMap<String,Object>(entry.fields());values.put("censusDisposition",disposition);var source=new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,entry.key(),plan.identity(),values);index.put("sources",entry.key(),source);
                    if(disposition.equals("REQUIRED")){counts[1]=Math.addExact(counts[1],1);index.put("required",entry.key(),source);}
                }
                if(expected!=null)throw new IOException("SEMANTIC_ROOT_SOURCE_OUTSIDE_INVENTORY");if(files!=plan.inventory().files())throw new IOException("SEMANTIC_ROOT_INVENTORY_FILE_COUNT");
            }
            // Every selected context is independently indexed, including ones
            // omitted from the supplied effective-context census.
            try(var selected=tables.open(plan.contexts(),cancelled)){SemanticTables.Row row;
                while((row=selected.next())!=null){
                    check(plan,livePolicy,cancelled);fields(row,Set.of("request","identity","scope"));if(!row.scope().equals(plan.inventory().identity()))throw new IOException("SEMANTIC_ROOT_CONTEXT_SCOPE");
                    var request=LanguageSemanticRequest.from(read(handle(row.fields().get("request"))));
                    if(!request.identity().value().equals(text(row,"identity"))||!request.scope().value().equals(text(row,"scope"))||!request.context().snapshotIdentity().equals(plan.root().snapshotIdentity())||!request.inventory().equals(plan.inventory().inventory())||!request.policy().equals(plan.policy()))throw new IOException("SEMANTIC_ROOT_CONTEXT_INPUTS");
                    index.put("contexts",row.key(),row);index.put("scopes",request.scope().value(),row);counts[2]=Math.addExact(counts[2],1);
                    try(var documents=tables.open(request.documents(),cancelled)){SemanticTables.Row doc;while((doc=documents.next())!=null){
                        if(!doc.scope().equals(request.scope()))throw new IOException("SEMANTIC_ROOT_DOCUMENT_SCOPE");var bytes=handle(doc.fields().get("content"));if(!content.verify(bytes))throw new IOException("SEMANTIC_ROOT_DOCUMENT_BYTES");
                        var key=documentKey(row.key(),doc.key());index.put("documents",key,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,key,plan.identity(),Map.of("context",row.key(),"document",doc.key(),"identity",doc.identity(),"content",bytes)));counts[4]=Math.addExact(counts[4],1);
                    }}
                }
            }
            try(var contexts=tables.open(input.contexts(),cancelled)){SemanticTables.Row row;while((row=contexts.next())!=null){
                check(plan,livePolicy,cancelled);scope(plan,row);fields(row,Set.of("disposition","request","reason","evidence"));evidence(row,"evidence");new ContentDigest(text(row,"request"));index.put("enumerated",text(row,"request"),row);var selected=index.get("contexts",row.key());String state=text(row,"disposition");
                if(state.equals("SELECTED")){
                    if(selected.isEmpty()||!text(selected.orElseThrow(),"identity").equals(text(row,"request")))throw new IOException("SEMANTIC_ROOT_CONTEXT_SELECTION_MISMATCH");index.mark("contexts",row.key());
                    if(text(row,"reason").equals("CAPTURED_NATIVE_CONTEXT")){
                        var request=LanguageSemanticRequest.from(read(handle(selected.orElseThrow().fields().get("request"))));var frame=read(handle(row.fields().get("evidence")));
                        if(!frame.keySet().equals(Set.of("schema","producer","readiness","readinessBytes","context","scope","inventory","resolution","provider","tool","workPolicy","inputs","nativeEvidence"))||!"captured-semantic-context:m8.4-v2".equals(frame.get("schema"))||!CanonicalJson.write(SemanticRepositoryCensus.PROVIDER).equals(CanonicalJson.write(frame.get("producer")))||!handle(frame.get("readinessBytes")).digest().value().equals(frame.get("readiness"))||!content.verify(handle(frame.get("readinessBytes")))||!request.scope().value().equals(frame.get("scope"))||!request.inventory().value().equals(frame.get("inventory"))||!request.resolution().equals(handle(frame.get("resolution")))||!CanonicalJson.write(request.provider()).equals(CanonicalJson.write(frame.get("provider")))||!request.tool().value().equals(frame.get("tool"))||!request.workPolicy().value().equals(frame.get("workPolicy"))||!content.verify(handle(frame.get("nativeEvidence"))))throw new IOException("SEMANTIC_ROOT_CAPTURED_CONTEXT_INVALID");
                        new ContentDigest(GraphJson.string(frame.get("readiness")));new ContentDigest(GraphJson.string(frame.get("context")));var nativeInputs=LanguageSemanticRequest.table(frame.get("inputs"));if(nativeInputs.kind()!=SemanticTables.Kind.DOCUMENT)throw new IOException("SEMANTIC_ROOT_NATIVE_INPUT_KIND");index.put("captured-contexts",row.key(),row);
                        try(var captured=tables.open(nativeInputs,cancelled)){SemanticTables.Row nativeInput;while((nativeInput=captured.next())!=null){
                            if(!nativeInput.scope().equals(request.scope())||!nativeInput.fields().keySet().equals(Set.of("origin","source","content","lineage"))||!content.verify(handle(nativeInput.fields().get("content")))||!content.verify(handle(nativeInput.fields().get("lineage"))))throw new IOException("SEMANTIC_ROOT_NATIVE_INPUT_INVALID");
                            var bytes=handle(nativeInput.fields().get("content"));String origin=text(nativeInput,"origin"),sourceKey=Set.of("INVENTORY","EMBEDDED").contains(origin)?new LogicalPath(text(nativeInput,"source")).key():"";if(!Set.of("INVENTORY","EMBEDDED").contains(origin)&&nativeInput.fields().get("source")!=null)throw new IOException("SEMANTIC_ROOT_NATIVE_INPUT_ORIGIN");
                            assignSource(index,origin,sourceKey,bytes,counts,true);String key=documentKey(row.key(),nativeInput.key());var doc=index.get("documents",key);
                            if(doc.isEmpty()){issue(plan,index,counts,"ROOT_NATIVE_DOCUMENT_OMITTED",nativeInput.identity());counts[10]=Math.addExact(counts[10],1);}else if(!bytes.equals(handle(doc.orElseThrow().fields().get("content"))))throw new IOException("SEMANTIC_ROOT_NATIVE_INPUT_DOCUMENT_MISMATCH");
                            counts[11]=Math.addExact(counts[11],1);index.put("native-inputs",key,nativeInput);
                        }}
                    }
                }else if(state.equals("EXCLUDED")||state.equals("UNKNOWN")){
                    if(selected.isPresent())throw new IOException("SEMANTIC_ROOT_CONTEXT_SELECTION_MISMATCH");
                    if(state.equals("UNKNOWN"))issue(plan,index,counts,"ROOT_CONTEXT_ENUMERATION_OPEN",row.identity());else counts[3]=Math.addExact(counts[3],1);
                }else throw new IOException("SEMANTIC_ROOT_CONTEXT_DISPOSITION");
            }}
            try(var memberships=tables.open(input.memberships(),cancelled)){SemanticTables.Row row;while((row=memberships.next())!=null){
                check(plan,livePolicy,cancelled);scope(plan,row);fields(row,Set.of("context","document","documentIdentity","origin","source","content","lineage"));
                String key=documentKey(text(row,"context"),text(row,"document"));if(!row.key().equals(key))throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_KEY");if(index.get("contexts",text(row,"context")).isEmpty())throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_OUTSIDE_CONTEXT");var document=index.get("documents",key);
                var bytes=handle(row.fields().get("content"));if(!content.verify(bytes))throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_DOCUMENT_MISMATCH");
                if(document.isPresent()){var doc=document.orElseThrow();if(!text(doc,"identity").equals(text(row,"documentIdentity"))||!handle(doc.fields().get("content")).equals(bytes))throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_DOCUMENT_MISMATCH");}
                else{if(row.fields().get("documentIdentity")!=null)new ContentDigest(text(row,"documentIdentity"));if(index.get("captured-contexts",text(row,"context")).isEmpty()){issue(plan,index,counts,"ROOT_NATIVE_DOCUMENT_OMITTED",row.identity());counts[10]=Math.addExact(counts[10],1);}}
                evidence(row,"lineage");String origin=text(row,"origin"),sourceKey=string(row,"source");
                if(index.get("captured-contexts",text(row,"context")).isPresent()){
                    var captured=index.get("native-inputs",key).orElseThrow(()->new IOException("SEMANTIC_ROOT_MEMBERSHIP_NOT_IN_NATIVE_INPUTS"));String expectedSource=Set.of("INVENTORY","EMBEDDED").contains(origin)?new LogicalPath(text(captured,"source")).key():"";
                    if(!origin.equals(text(captured,"origin"))||!sourceKey.equals(expectedSource)||!bytes.equals(handle(captured.fields().get("content")))||!handle(row.fields().get("lineage")).equals(handle(captured.fields().get("lineage")))||!Set.of("INVENTORY","EMBEDDED").contains(origin)&&captured.fields().get("source")!=null)throw new IOException("SEMANTIC_ROOT_NATIVE_MEMBERSHIP_BINDING");index.mark("native-inputs",key);
                }
                boolean legacy=index.get("captured-contexts",text(row,"context")).isEmpty();assignSource(index,origin,sourceKey,bytes,counts,legacy);if(legacy)counts[11]=Math.addExact(counts[11],1);
                if(document.isPresent())index.mark("documents",key);counts[5]=Math.addExact(counts[5],1);
            }}
            for(var pair:Map.of("contexts","ROOT_SELECTED_CONTEXT_OMITTED","documents","ROOT_DOCUMENT_MEMBERSHIP_OMITTED","required","ROOT_SOURCE_NOT_ASSIGNED","native-inputs","ROOT_NATIVE_MEMBERSHIP_OMITTED").entrySet())try(var rows=index.rows(pair.getKey(),true)){SemanticTables.Row row;while((row=rows.next())!=null){check(plan,livePolicy,cancelled);issue(plan,index,counts,pair.getValue(),row.identity());if(pair.getKey().equals("native-inputs"))counts[12]=Math.addExact(counts[12],1);}}
            var accounting=new Accounting(counts[0],counts[1],counts[2],counts[3],counts[4],counts[11],counts[5],counts[6],counts[7],counts[8],counts[10],counts[12],counts[9]);boolean closed=accounting.open()==0;
            boolean qualified=closed&&qualified(plan,input,accounting,proof,revoked);
            if(!qualified)issue(plan,index,null,"ROOT_SOURCE_CONTEXT_CENSUS_METHOD_UNQUALIFIED",input.identity());
            SemanticTables.Table gaps;try(var rows=index.rows("gaps",false)){
                var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending;public boolean hasNext(){try{if(pending==null)pending=rows.next();return pending!=null;}catch(IOException e){throw new UncheckedIOException(e);}}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var result=pending;pending=null;return result;}};
                gaps=tables.write(SemanticTables.Kind.CAPABILITY_GAP,iterator,cancelled);
            }
            check(plan,livePolicy,cancelled);return new Result(PROVIDER,input,accounting,gaps,closed,qualified,qualified?Optional.of(proof.orElseThrow().identity()):Optional.empty());
        }catch(UncheckedIOException failure){throw failure.getCause();}catch(IllegalArgumentException|ClassCastException|NullPointerException|ArithmeticException invalid){throw new IOException("SEMANTIC_ROOT_CENSUS_INVALID",invalid);}
    }
    public static String documentKey(String context,String document){return CanonicalJson.sha256(List.of("semantic-root-document:m8.4-v1",context,document)).value();}
    private void assignSource(Index index,String origin,String sourceKey,ContentHandle bytes,long[] counts,boolean countOrigin)throws IOException{
        if(origin.equals("INVENTORY")||origin.equals("EMBEDDED")){
            var source=index.get("sources",sourceKey).orElseThrow(()->new IOException("SEMANTIC_ROOT_MEMBERSHIP_SOURCE_MISSING"));
            if(!text(source,"kind").equals("FILE")||!text(source,"disposition").equals("ACQUIRED")||!content.verify(handle(source.fields().get("content"))))throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_SOURCE_BYTES");
            if(origin.equals("INVENTORY")&&!handle(source.fields().get("content")).equals(bytes))throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_SOURCE_MISMATCH");if(text(source,"censusDisposition").equals("NON_SEMANTIC"))throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_DISPOSITION_CONFLICT");
            if(index.get("required",sourceKey).isPresent())index.mark("required",sourceKey);if(countOrigin&&origin.equals("EMBEDDED"))counts[8]=Math.addExact(counts[8],1);
        }else if(origin.equals("GENERATED")||origin.equals("EXTERNAL")){
            if(!sourceKey.isEmpty())throw new IOException("SEMANTIC_ROOT_NONINVENTORY_SOURCE_KEY");if(countOrigin){int counter=origin.equals("GENERATED")?6:7;counts[counter]=Math.addExact(counts[counter],1);}
        }else throw new IOException("SEMANTIC_ROOT_MEMBERSHIP_ORIGIN");
    }
    public static Input input(Object value){var fields=GraphJson.object(value);if(!fields.keySet().equals(Set.of("sources","contexts","memberships","validatorTool")))throw new IllegalArgumentException("SEMANTIC_ROOT_CENSUS_INPUT_FIELDS");return new Input(LanguageSemanticRequest.table(fields.get("sources")),LanguageSemanticRequest.table(fields.get("contexts")),LanguageSemanticRequest.table(fields.get("memberships")),new ContentDigest(GraphJson.string(fields.get("validatorTool"))));}
    public static ContentDigest inputClosure(SemanticContextCoordinator.Plan plan,Input input){return CanonicalJson.sha256(List.of("semantic-root-input-closure:m8.4-v1",PROVIDER,plan,input));}
    public static Map<String,Object> proofPayload(SemanticContextCoordinator.Plan plan,Input input,Accounting accounting){return Map.of("schema","semantic-root-census-claim:m8.4-v1","evaluator",PROVIDER,"plan",plan.identity(),"input",input,"accounting",accounting);}
    private boolean qualified(SemanticContextCoordinator.Plan plan,Input input,Accounting accounting,Optional<EvidenceVerifier.VerifiedClaim> proof,Set<ContentDigest> revoked)throws IOException{
        if(proof.isEmpty())return false;var claim=proof.orElseThrow();var expected=claim.expected();byte[] payload=CanonicalJson.write(proofPayload(plan,input,accounting)).getBytes(StandardCharsets.UTF_8);
        return !revoked.contains(claim.identity())&&claim.policy().equals(plan.policy())&&expected.scope().equals(plan.identity())&&expected.inputClosure().equals(inputClosure(plan,input))&&expected.tool().equals(input.validatorTool())&&expected.predicate().equals(new VersionedIdentifier("polyglot.root-census","m8.4-v1"))&&claim.checks().containsAll(List.of("SEALED_SOURCE_MEMBERSHIP","EFFECTIVE_CONTEXT_CLOSURE","GENERATED_EXTERNAL_LINEAGE","EMBEDDED_SOURCE_MAPPING"))&&expected.claim().equals(new ContentHandle(ContentDigest.sha256(payload),payload.length))&&content.verify(expected.claim());
    }
    private void issue(SemanticContextCoordinator.Plan plan,Index index,long[] counts,String reason,ContentDigest subject)throws IOException{
        if(counts!=null)counts[9]=Math.addExact(counts[9],1);var source=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,subject.value());
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.root-census",List.of(source),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Close exact source, selected context and generated/embedded membership against independently captured build evidence."));
        var gap=CapabilityGapRecord.create(SemanticReconciler.CATALOG,plan.root(),PROVIDER,"polyglot.root-census",reason,source,List.of(),List.of(ProviderObservationReference.create(PROVIDER,"polyglot.root-census",inputSubject(plan),CanonicalJson.sha256(List.of(reason,subject)))),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.root-census")),List.of(),List.of(),List.of("Source/context accounting and semantic correctness require separate predicates."));
        var key=gap.gapIdentity().value();if(index.get("gaps",key).isEmpty())index.put("gaps",key,new SemanticTables.Row(SemanticTables.Kind.CAPABILITY_GAP,key,plan.identity(),GraphJson.object(GraphJson.read(CanonicalJson.write(gap),GraphJson.Limits.defaults()))));
    }
    private static ContentDigest inputSubject(SemanticContextCoordinator.Plan plan){return plan.identity();}
    private void evidence(SemanticTables.Row row,String field)throws IOException{if(!content.verify(handle(row.fields().get(field))))throw new IOException("SEMANTIC_ROOT_LINEAGE_OR_EVIDENCE_UNAVAILABLE");if(field.equals("evidence"))text(row,"reason");}
    private static void fields(SemanticTables.Row row,Set<String> expected)throws IOException{if(!row.fields().keySet().equals(expected))throw new IOException("SEMANTIC_ROOT_ROW_FIELDS");}
    private static void scope(SemanticContextCoordinator.Plan plan,SemanticTables.Row row)throws IOException{if(!row.scope().equals(plan.identity()))throw new IOException("SEMANTIC_ROOT_ROW_SCOPE");}
    private static ContentHandle handle(Object value){if(value instanceof ContentHandle handle)return handle;return LanguageSemanticRequest.handle(value);}
    private static String string(SemanticTables.Row row,String key){var value=row.fields().get(key);return value instanceof ContentDigest digest?digest.value():GraphJson.string(value);}
    private static String text(SemanticTables.Row row,String key)throws IOException{String value=string(row,key);if(value.isBlank())throw new IOException("SEMANTIC_ROOT_EMPTY_FIELD");return value;}
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_ROOT_REQUEST_UNAVAILABLE_OR_WORKER_REQUIRED");try(var stream=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(stream,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_ROOT_REQUEST_NONCANONICAL");return value;}}
    private static void check(SemanticContextCoordinator.Plan plan,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_ROOT_CANCELLED_WITH_FRONTIER");if(!plan.policy().equals(livePolicy.get()))throw new IOException("SEMANTIC_ROOT_POLICY_REVOKED");}
}
