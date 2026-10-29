package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.*;

/** Atomic, fenced publication with live authenticated or producer-asserted views. Publication
 * and operational completion never supply a semantic validation capability.
 */
public final class SemanticPublicationStore {
    public record Publication(ContentHandle document,ContentDigest plan,ContentDigest policy,ContentHandle rootReport,SemanticTables.Table contexts,long revision,Optional<ContentDigest> parent){}
    private final Path root;private final RepositoryContentStore content;private final SemanticTables tables;private final Optional<SemanticPublicationValidation> validation;
    public SemanticPublicationStore(Path root,RepositoryContentStore content,SemanticTables tables)throws IOException{this(root,content,tables,Optional.empty());}
    public SemanticPublicationStore(Path root,RepositoryContentStore content,SemanticTables tables,Optional<SemanticPublicationValidation> validation)throws IOException{this.root=StreamingContentStore.directory(root);this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);this.validation=Objects.requireNonNull(validation);}
    public Publication commit(SemanticContextCoordinator.Plan plan,SemanticContextCoordinator.Root result,Optional<ContentDigest> expected,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{
        check(plan.policy(),livePolicy,cancelled);
        Path key=root.resolve(plan.identity().value().substring(7)+".lock");StreamingContentStore.safe(key);
        try(var channel=FileChannel.open(key,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("SEMANTIC_PUBLICATION_BUSY",busy);}if(lock==null)throw new IOException("SEMANTIC_PUBLICATION_BUSY");
            try(lock){
                var prior=head(plan.identity());var current=prior.map(p->p.document().digest());
                if(!expected.equals(current))throw new IOException("SEMANTIC_PUBLICATION_FENCE_MISMATCH");
                if(!result.plan().equals(plan.identity())||result.complete()&&!result.selectionQualified()||result.selected()!=plan.contexts().rows()||result.processed()!=result.selected()||result.contexts().rows()!=result.processed())throw new IOException("SEMANTIC_PUBLICATION_SCOPE_OR_AUTHORITY");
                var authority=result.selectionQualified()?Optional.of(validator().validate(plan,result.report(),cancelled)):Optional.<SemanticPublicationValidation.Validated>empty();
                var report=read(result.report());
                if(!Set.of("semantic-root-report:m8.4-v1","semantic-root-report:m8.4-v2").contains(report.get("schema"))||!CanonicalJson.write(plan).equals(CanonicalJson.write(report.get("plan")))||!CanonicalJson.write(result.contexts()).equals(CanonicalJson.write(report.get("contexts")))||!Boolean.valueOf(result.complete()).equals(report.get("complete")))throw new IOException("SEMANTIC_PUBLICATION_REPORT_BINDING");
                if(number(report.get("failed"))!=result.failed()||number(report.get("missingProviders"))!=result.missingProviders()||number(report.get("incomplete"))!=result.incomplete())throw new IOException("SEMANTIC_PUBLICATION_RESULT_COUNTS");
                verifyReport(plan.identity(),plan.policy(),result.contexts(),report,authority,cancelled);
                check(plan.policy(),livePolicy,cancelled);
                if(prior.isPresent()&&prior.orElseThrow().rootReport().equals(result.report())&&prior.orElseThrow().contexts().equals(result.contexts()))return prior.orElseThrow();
                long revision=prior.isEmpty()?1:Math.addExact(prior.orElseThrow().revision(),1);
                var fields=new TreeMap<String,Object>();fields.put("schema","semantic-publication:m8.4-v1");fields.put("plan",plan.identity());fields.put("policy",plan.policy());fields.put("rootReport",result.report());fields.put("contexts",result.contexts());fields.put("revision",revision);fields.put("parent",current.orElse(null));fields.put("trust",authority.isPresent()?"VALIDATED_OBSERVATION":"PRODUCER_ASSERTION");fields.put("qualification",authority.isPresent()?"CURRENT_POLICY_VALIDATED":"UNQUALIFIED");
                var document=put(fields,cancelled);var publication=new Publication(document,plan.identity(),plan.policy(),result.report(),result.contexts(),revision,current);
                Path temporary=Files.createTempFile(root,"semantic-publication-",".part");
                try{
                    byte[] pointer=CanonicalJson.write(Map.of("document",document)).getBytes(StandardCharsets.UTF_8);
                    try(var output=FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){var buffer=ByteBuffer.wrap(pointer);while(buffer.hasRemaining())output.write(buffer);output.force(true);}
                    check(plan.policy(),livePolicy,cancelled);
                    if(authority.isPresent()&&!authority.orElseThrow().matches(plan.identity(),plan.policy(),result.report()))throw new IOException("SEMANTIC_PUBLICATION_AUTHORITY_REVOKED");
                    Files.move(temporary,path(plan.identity()),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                }finally{Files.deleteIfExists(temporary);}
                return publication;
            }
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("SEMANTIC_PUBLICATION_RECORD_INVALID",invalid);}
    }
    /** Metadata for fencing only. load() checks current bytes and live policy. */
    public Optional<Publication> head(ContentDigest plan)throws IOException{
        Path pointer=path(plan);if(!Files.exists(pointer,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
        if(!Files.isRegularFile(pointer,LinkOption.NOFOLLOW_LINKS)||Files.size(pointer)>4096)throw new IOException("SEMANTIC_PUBLICATION_POINTER_INVALID");
        try{
            var value=GraphJson.object(GraphJson.read(Files.readString(pointer,StandardCharsets.UTF_8),new GraphJson.Limits(4096,8,128)));
            if(!value.keySet().equals(Set.of("document")))throw new IOException("SEMANTIC_PUBLICATION_POINTER_FIELDS");
            var document=handle(value.get("document"));var fields=read(document);
            if(!fields.keySet().equals(Set.of("schema","plan","policy","rootReport","contexts","revision","parent","trust","qualification"))||!"semantic-publication:m8.4-v1".equals(fields.get("schema"))||!plan.value().equals(fields.get("plan"))||!("PRODUCER_ASSERTION".equals(fields.get("trust"))&&"UNQUALIFIED".equals(fields.get("qualification"))||"VALIDATED_OBSERVATION".equals(fields.get("trust"))&&"CURRENT_POLICY_VALIDATED".equals(fields.get("qualification"))))throw new IOException("SEMANTIC_PUBLICATION_SCOPE");
            long revision=((java.math.BigDecimal)fields.get("revision")).longValueExact();if(revision<1)throw new IOException("SEMANTIC_PUBLICATION_REVISION");
            return Optional.of(new Publication(document,plan,new ContentDigest(GraphJson.string(fields.get("policy"))),handle(fields.get("rootReport")),table(fields.get("contexts")),revision,Optional.ofNullable(fields.get("parent")).map(v->new ContentDigest(GraphJson.string(v)))));
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("SEMANTIC_PUBLICATION_RECORD_INVALID",invalid);}
    }
    public Optional<Publication> load(ContentDigest plan,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{
        return loadCurrent(plan,livePolicy,cancelled).map(Loaded::publication);
    }
    private record Loaded(Publication publication,Optional<SemanticPublicationValidation.Validated> authority){}
    private Optional<Loaded> loadCurrent(ContentDigest plan,Supplier<ContentDigest> livePolicy,BooleanSupplier cancelled)throws IOException{
        try{
        var publication=head(plan);if(publication.isEmpty())return Optional.empty();var value=publication.orElseThrow();check(value.policy(),livePolicy,cancelled);
        var report=read(value.rootReport());var metadata=read(value.document());var authority="VALIDATED_OBSERVATION".equals(metadata.get("trust"))?Optional.of(validator().validate(SemanticContextCoordinator.Plan.from(report.get("plan")),value.rootReport(),cancelled)):Optional.<SemanticPublicationValidation.Validated>empty();
        verifyReport(plan,value.policy(),value.contexts(),report,authority,cancelled);
        check(value.policy(),livePolicy,cancelled);if(authority.isPresent()&&!authority.orElseThrow().matches(plan,value.policy(),value.rootReport()))throw new IOException("SEMANTIC_PUBLICATION_AUTHORITY_REVOKED");return Optional.of(new Loaded(value,authority));
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){throw new IOException("SEMANTIC_PUBLICATION_RECORD_INVALID",invalid);}
    }
    public SemanticQueryService queries(Supplier<ContentDigest> livePolicy){
        return new SemanticQueryService((plan,cancelled)->loadCurrent(plan,livePolicy,cancelled).map(loaded->{var value=loaded.publication();return new SemanticQueryService.Publication(value.document(),value.plan(),value.policy(),value.rootReport(),value.contexts(),value.revision(),loaded.authority());}),content,tables,livePolicy);
    }
    private SemanticPublicationValidation validator()throws IOException{return validation.orElseThrow(()->new IOException("SEMANTIC_PUBLICATION_LIVE_VALIDATOR_REQUIRED"));}
    private void verifyReport(ContentDigest plan,ContentDigest policy,SemanticTables.Table contexts,Map<String,Object> report,Optional<SemanticPublicationValidation.Validated> authority,BooleanSupplier cancelled)throws IOException{
        boolean withCensus="semantic-root-report:m8.4-v2".equals(report.get("schema"));var reportFields=new HashSet<>(Set.of("schema","plan","contexts","selected","processed","failed","missingProviders","incomplete","rootGap","complete"));if(withCensus)reportFields.add("rootCensus");
        if(!report.keySet().equals(reportFields)||!withCensus&&!"semantic-root-report:m8.4-v1".equals(report.get("schema"))||!Boolean.valueOf(authority.isPresent()&&authority.orElseThrow().complete()).equals(report.get("complete")))throw new IOException("SEMANTIC_PUBLICATION_REPORT_BINDING");
        var planned=GraphJson.object(report.get("plan"));
        boolean legacyPlan=planned.keySet().equals(Set.of("root","inventory","contexts","policy"));if(!legacyPlan&&!planned.keySet().equals(Set.of("root","inventory","contexts","policy","coordinator"))||!policy.value().equals(planned.get("policy"))||!plan.equals(CanonicalJson.sha256(List.of(legacyPlan?"semantic-context-plan:m8.4-v1":"semantic-context-plan:m8.4-v2",planned)))||!table(report.get("contexts")).equals(contexts))throw new IOException("SEMANTIC_PUBLICATION_PLAN_BINDING");
        if(!legacyPlan){var coordinator=GraphJson.object(planned.get("coordinator"));if(!coordinator.keySet().equals(Set.of("id","version"))||!SemanticContextCoordinator.PROVIDER.id().equals(coordinator.get("id")))throw new IOException("SEMANTIC_PUBLICATION_COORDINATOR_BINDING");new com.evolution.analysis.contract.common.VersionedIdentifier(GraphJson.string(coordinator.get("id")),GraphJson.string(coordinator.get("version")));}
        var selection=table(planned.get("contexts"));var inventory=GraphJson.object(planned.get("inventory"));
        verify(selection,cancelled);verify(table(inventory.get("entries")),cancelled);
        long selected=((java.math.BigDecimal)report.get("selected")).longValueExact(),processed=((java.math.BigDecimal)report.get("processed")).longValueExact();
        if(selection.rows()!=selected||selected!=processed||processed!=contexts.rows())throw new IOException("SEMANTIC_PUBLICATION_DENOMINATOR");
        var classifier=GraphJson.object(inventory.get("classifier"));var capturedInventory=new PolyglotInventory.Report(new ContentDigest(GraphJson.string(inventory.get("inventory"))),table(inventory.get("entries")),number(inventory.get("files")),number(inventory.get("unresolvedClassification")),new com.evolution.analysis.contract.common.VersionedIdentifier(GraphJson.string(classifier.get("id")),GraphJson.string(classifier.get("version"))));var inventoryScope=capturedInventory.identity();
        var counts=verifyContexts(plan,contexts,selection,inventoryScope,capturedInventory.inventory(),policy,GraphJson.string(GraphJson.object(planned.get("root")).get("snapshotIdentity")),cancelled);
        if(number(report.get("failed"))!=counts[0]||number(report.get("missingProviders"))!=counts[1]||number(report.get("incomplete"))!=counts[2])throw new IOException("SEMANTIC_PUBLICATION_CHILD_COUNTS");
        if(withCensus&&authority.isEmpty()){
            // Recompute the complete source/context join; persisted closure bits
            // and a saved proof identity cannot restore opaque method authority.
            var census=GraphJson.object(report.get("rootCensus"));if(!CanonicalJson.write(SemanticRootCensus.PROVIDER).equals(CanonicalJson.write(census.get("evaluator"))))throw new IOException("SEMANTIC_PUBLICATION_ROOT_EVALUATOR_UNAVAILABLE");var censusInput=SemanticRootCensus.input(census.get("input"));Path scratch=root.resolve("census-"+UUID.randomUUID()+".sqlite");boolean owned=false;
            try{
                try(var index=new SqliteSemanticCensusIndex(scratch,65536)){
                    owned=true;
                    var replay=new SemanticRootCensus(content,tables).reconcile(SemanticContextCoordinator.Plan.from(planned),censusInput,index,Optional.empty(),Set.of(),()->policy,cancelled);
                    if(!CanonicalJson.write(replay).equals(CanonicalJson.write(census)))throw new IOException("SEMANTIC_PUBLICATION_ROOT_CENSUS_REPLAY");
                }
            }finally{if(owned)Files.deleteIfExists(scratch);}
        }
    }
    private long[] verifyContexts(ContentDigest plan,SemanticTables.Table contexts,SemanticTables.Table selection,ContentDigest inventoryScope,ContentDigest inventory,ContentDigest policy,String rootSnapshot,BooleanSupplier cancelled)throws IOException{
        if(contexts.kind()!=SemanticTables.Kind.CONTEXT||selection.kind()!=SemanticTables.Kind.CONTEXT)throw new IOException("SEMANTIC_PUBLICATION_CONTEXT_KIND");
        var counts=new long[3];
        try(var reader=tables.open(contexts,cancelled);var planned=tables.open(selection,cancelled)){SemanticTables.Row row;
            while((row=reader.next())!=null){
                var selected=planned.next();if(selected==null||!selected.key().equals(row.key())||!selected.scope().equals(inventoryScope)||!selected.fields().keySet().equals(Set.of("request","identity","scope")))throw new IOException("SEMANTIC_PUBLICATION_SELECTED_CONTEXT_BINDING");
                if(!row.scope().equals(plan)||!row.fields().keySet().equals(Set.of("receipt","state","request")))throw new IOException("SEMANTIC_PUBLICATION_CHILD_SCOPE");
                var receipt=read(handle(row.fields().get("receipt")));
                boolean legacy="semantic-context-receipt:m8.4-v1".equals(receipt.get("schema")),retained="semantic-context-receipt:m8.4-v3".equals(receipt.get("schema"));var receiptFields=new HashSet<>(Set.of("schema","plan","key","request","observations","coverage","gaps","state","reason","failure","parent"));if(!legacy)receiptFields.add("answers");if(retained)receiptFields.add("history");
                if(!receipt.keySet().equals(receiptFields)||!legacy&&!retained&&!"semantic-context-receipt:m8.4-v2".equals(receipt.get("schema"))||!plan.value().equals(receipt.get("plan"))||!row.key().equals(receipt.get("key"))||!row.fields().get("state").equals(receipt.get("state"))||!handle(selected.fields().get("request")).equals(handle(receipt.get("request"))))throw new IOException("SEMANTIC_PUBLICATION_RECEIPT_BINDING");
                String state=GraphJson.string(receipt.get("state"));if(!Set.of("COMPLETE","PARTIAL","FAILED","MISSING_PROVIDER").contains(state))throw new IOException("SEMANTIC_PUBLICATION_CHILD_STATE");
                if(state.equals("FAILED"))counts[0]++;if(state.equals("MISSING_PROVIDER"))counts[1]++;if(!state.equals("COMPLETE"))counts[2]++;
                boolean observed=receipt.get("observations")!=null;if(observed!=(receipt.get("coverage")!=null)||observed!=Set.of("COMPLETE","PARTIAL").contains(state)||state.equals("FAILED")!=(receipt.get("failure")!=null))throw new IOException("SEMANTIC_PUBLICATION_CHILD_OUTCOME");
                var request=LanguageSemanticRequest.from(read(handle(receipt.get("request"))));if(!rootSnapshot.equals(request.context().snapshotIdentity().value())||!request.inventory().equals(inventory)||!request.policy().equals(policy)||!request.identity().value().equals(row.fields().get("request"))||!request.identity().value().equals(selected.fields().get("identity"))||!request.scope().value().equals(selected.fields().get("scope")))throw new IOException("SEMANTIC_PUBLICATION_REQUEST_BINDING");
                if(!legacy&&(!retained&&observed!=(receipt.get("answers")!=null)||retained&&observed&&(receipt.get("answers")==null||receipt.get("history")==null)))throw new IOException("SEMANTIC_PUBLICATION_ANSWER_OUTCOME");boolean answersComplete=legacy;
                Optional<SemanticAnswerLedger.History> history=retained&&receipt.get("history")!=null?Optional.of(SemanticAnswerLedger.History.from(receipt.get("history"))):Optional.empty();
                if(history.isPresent()){if(!history.orElseThrow().scope().equals(request.inputClosure()))throw new IOException("SEMANTIC_PUBLICATION_HISTORY_SCOPE");verify(history.orElseThrow().revisions(),cancelled);}
                if(receipt.get("answers")!=null){var answers=new SemanticAnswerLedger(content,tables,Optional.of(()->SqliteSemanticCensusIndex.temporary(root.resolve("semantic-work"),65536))).verifyStoredState(request,handle(receipt.get("answers")),cancelled);if(retained&&!history.equals(Optional.of(answers.history())))throw new IOException("SEMANTIC_PUBLICATION_HISTORY_BINDING");answersComplete=answers.answersComplete();}
                if(observed&&state.equals("COMPLETE")!=(Boolean.TRUE.equals(GraphJson.object(receipt.get("coverage")).get("complete"))&&answersComplete))throw new IOException("SEMANTIC_PUBLICATION_CHILD_COVERAGE");
                verify(request.documents(),cancelled);verify(request.census(),cancelled);if(!content.verify(request.resolution()))throw new IOException("SEMANTIC_PUBLICATION_RESOLUTION_UNAVAILABLE");
                try(var documents=tables.open(request.documents(),cancelled)){SemanticTables.Row document;while((document=documents.next())!=null)if(!content.verify(handle(document.fields().get("content"))))throw new IOException("SEMANTIC_PUBLICATION_SOURCE_UNAVAILABLE");}
                if(observed){var observations=table(receipt.get("observations"));if(observations.kind()!=SemanticTables.Kind.OBSERVATION)throw new IOException("SEMANTIC_PUBLICATION_OBSERVATIONS_KIND");verify(observations,cancelled);new SemanticReconciler().reconcile(request,observations,tables,content,Map.of(),Set.of(),()->policy,g->{},cancelled);}var gaps=table(receipt.get("gaps"));if(gaps.kind()!=SemanticTables.Kind.CAPABILITY_GAP)throw new IOException("SEMANTIC_PUBLICATION_GAPS_KIND");verify(gaps,cancelled);
                if(receipt.get("failure")!=null)read(handle(receipt.get("failure")));
            }
            if(planned.next()!=null)throw new IOException("SEMANTIC_PUBLICATION_SELECTED_CONTEXT_BINDING");
        }
        return counts;
    }
    private void verify(SemanticTables.Table table,BooleanSupplier cancelled)throws IOException{try(var reader=tables.open(table,cancelled)){while(reader.next()!=null){}}}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
    private Map<String,Object> read(ContentHandle handle)throws IOException{
        if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_PUBLICATION_CONTENT_UNAVAILABLE_OR_WORKER_REQUIRED");
        try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_PUBLICATION_NONCANONICAL_RECORD");return value;}
    }
    private Path path(ContentDigest plan)throws IOException{Path pointer=root.resolve(plan.value().substring(7)+".json");StreamingContentStore.safe(pointer);return pointer;}
    private static ContentHandle handle(Object value){var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("SEMANTIC_HANDLE_FIELDS");return new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),((java.math.BigDecimal)row.get("size")).longValueExact());}
    private static SemanticTables.Table table(Object value){var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("schema","kind","content","rows")))throw new IllegalArgumentException("SEMANTIC_TABLE_FIELDS");return new SemanticTables.Table(GraphJson.string(row.get("schema")),SemanticTables.Kind.valueOf(GraphJson.string(row.get("kind"))),handle(row.get("content")),((java.math.BigDecimal)row.get("rows")).longValueExact());}
    private static long number(Object value)throws IOException{try{long number=((java.math.BigDecimal)value).longValueExact();if(number<0)throw new IOException("SEMANTIC_PUBLICATION_NEGATIVE_COUNT");return number;}catch(ClassCastException|ArithmeticException|NullPointerException invalid){throw new IOException("SEMANTIC_PUBLICATION_COUNT_INVALID",invalid);}}
    private static void check(ContentDigest policy,Supplier<ContentDigest> live,BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_PUBLICATION_CANCELLED");if(!policy.equals(live.get()))throw new IOException("SEMANTIC_PUBLICATION_POLICY_REVOKED");}
}
