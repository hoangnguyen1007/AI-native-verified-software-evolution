package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Sealed acquisition/readiness -> independent semantic census. Native inputs
 * come from captured invocation/source-list metadata, never emitted symbols.
 * Captures are producer data; this adapter neither executes tools nor qualifies them. */
public final class SemanticRepositoryCensus {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.repository-census","m8.4-v1");
    private final RepositoryContentStore content;private final SemanticTables tables;
    public SemanticRepositoryCensus(RepositoryContentStore content,SemanticTables tables){this.content=Objects.requireNonNull(content);this.tables=Objects.requireNonNull(tables);}
    public ContentHandle capture(ReadinessPlan readiness,ReadinessPlan.Context context,LanguageSemanticRequest request,SemanticTables.Table inputs,ContentHandle nativeEvidence,BooleanSupplier cancelled)throws IOException{
        if(inputs.kind()!=SemanticTables.Kind.DOCUMENT||!content.verify(nativeEvidence))throw new IOException("SEMANTIC_REPOSITORY_NATIVE_INPUTS_UNAVAILABLE");
        var fields=new TreeMap<String,Object>();fields.put("schema","captured-semantic-context:m8.4-v2");fields.put("producer",PROVIDER);fields.put("readiness",readiness.identity());fields.put("readinessBytes",put(readiness,cancelled));fields.put("context",context.identity());fields.put("scope",request.scope());fields.put("inventory",request.inventory());fields.put("resolution",request.resolution());fields.put("provider",request.provider());fields.put("tool",request.tool());fields.put("workPolicy",request.workPolicy());fields.put("inputs",inputs);fields.put("nativeEvidence",nativeEvidence);return put(fields,cancelled);
    }
    public SemanticRootCensus.Input produce(String revision,InventoryReader inventory,ReadinessPlan readiness,SemanticContextCoordinator.Plan plan,SemanticTables.Table bindings,SemanticRootCensus.Index index,ContentDigest validatorTool,BooleanSupplier cancelled)throws IOException{
        check(cancelled);var sealed=inventory.manifest(revision).orElseThrow(()->new IOException("SEMANTIC_REPOSITORY_SEALED_INVENTORY_REQUIRED"));
        var expectedSnapshot=SnapshotIdentity.from(sealed.repository(),sealed.inventory());
        if(!sealed.equals(readiness.inventory())||!plan.root().snapshotIdentity().equals(expectedSnapshot)&&!sealed.compatibleV1Snapshot().filter(plan.root().snapshotIdentity()::equals).isPresent())throw new IOException("SEMANTIC_REPOSITORY_SNAPSHOT_BINDING");
        if(!new PolyglotInventory().scan(revision,inventory,content,tables,cancelled).equals(plan.inventory()))throw new IOException("SEMANTIC_REPOSITORY_INVENTORY_PROJECTION");
        if(bindings.kind()!=SemanticTables.Kind.CONTEXT||!content.verify(readiness.ownership().ledger()))throw new IOException("SEMANTIC_REPOSITORY_READINESS_INPUTS");
        for(String space:List.of("repo.ready","repo.requests","repo.documents","repo.covered","repo.sources","repo.contexts","repo.memberships"))try(var rows=index.rows(space,false)){if(rows.next()!=null)throw new IOException("SEMANTIC_REPOSITORY_INDEX_NOT_EMPTY");}
        var readyIdentity=readiness.identity();var selectedIds=new HashSet<>(readiness.selection().selected());var excludedIds=new HashSet<>(readiness.selection().unselected());var sourceEvidence=put(Map.of("schema","repository-census-source-evidence:m8.4-v1","producer",PROVIDER,"inventory",plan.inventory().identity(),"readiness",put(readiness,cancelled),"ownership",readiness.ownership()),cancelled);
        try{
            for(var context:readiness.contexts()){
                check(cancelled);index.put("repo.ready",context.identity().value(),new SemanticTables.Row(SemanticTables.Kind.CONTEXT,context.id(),readyIdentity,fields(context)));
            }
            try(var selected=tables.open(plan.contexts(),cancelled)){SemanticTables.Row row;while((row=selected.next())!=null){
                check(cancelled);if(!row.scope().equals(plan.inventory().identity())||!row.fields().keySet().equals(Set.of("request","identity","scope")))throw new IOException("SEMANTIC_REPOSITORY_SELECTED_FIELDS");var request=LanguageSemanticRequest.from(read(handle(row.fields().get("request"))));validateRequest(plan,request,row);index.put("repo.requests",row.key(),row);
                try(var documents=tables.open(request.documents(),cancelled)){SemanticTables.Row doc;while((doc=documents.next())!=null){if(!doc.scope().equals(request.scope()))throw new IOException("SEMANTIC_REPOSITORY_DOCUMENT_SCOPE");index.put("repo.documents",SemanticRootCensus.documentKey(row.key(),doc.key()),doc);}}
            }}
            try(var captures=tables.open(bindings,cancelled)){SemanticTables.Row row;while((row=captures.next())!=null){
                check(cancelled);if(!row.scope().equals(readyIdentity)||!row.fields().keySet().equals(Set.of("request","capture")))throw new IOException("SEMANTIC_REPOSITORY_CAPTURE_BINDING");var selected=index.get("repo.requests",row.key()).orElseThrow(()->new IOException("SEMANTIC_REPOSITORY_UNSELECTED_CAPTURE"));var request=LanguageSemanticRequest.from(read(handle(selected.fields().get("request"))));if(!text(row.fields().get("request")).equals(request.identity().value()))throw new IOException("SEMANTIC_REPOSITORY_REQUEST_BINDING");
                var captured=handle(row.fields().get("capture"));var frame=read(captured);if(!frame.keySet().equals(Set.of("schema","producer","readiness","readinessBytes","context","scope","inventory","resolution","provider","tool","workPolicy","inputs","nativeEvidence"))||!"captured-semantic-context:m8.4-v2".equals(frame.get("schema"))||!CanonicalJson.write(SemanticRepositoryCensus.PROVIDER).equals(CanonicalJson.write(frame.get("producer")))||!handle(frame.get("readinessBytes")).digest().value().equals(frame.get("readiness"))||!content.verify(handle(frame.get("readinessBytes")))||!readyIdentity.value().equals(frame.get("readiness"))||!request.scope().value().equals(frame.get("scope"))||!request.inventory().value().equals(frame.get("inventory"))||!request.resolution().equals(handle(frame.get("resolution")))||!CanonicalJson.write(request.provider()).equals(CanonicalJson.write(frame.get("provider")))||!request.tool().value().equals(frame.get("tool"))||!request.workPolicy().value().equals(frame.get("workPolicy"))||!content.verify(handle(frame.get("nativeEvidence"))))throw new IOException("SEMANTIC_REPOSITORY_NATIVE_CONTEXT_MISMATCH");
                var build=index.get("repo.ready",text(frame.get("context"))).orElseThrow(()->new IOException("SEMANTIC_REPOSITORY_NATIVE_CONTEXT_UNDISCOVERED"));
                if(!selectedIds.contains(build.key())||!request.root().equals(text(build.fields().get("root")))||!request.module().equals(text(build.fields().get("module")))||!request.sourceSet().equals(text(build.fields().get("sourceSet")))||!request.variant().equals(text(build.fields().get("variant"))))throw new IOException("SEMANTIC_REPOSITORY_BUILD_CONTEXT_MISMATCH");
                index.mark("repo.ready",text(frame.get("context")));context(index,plan,row.key(),"SELECTED",request.identity(),"CAPTURED_NATIVE_CONTEXT",captured);
                var inputs=LanguageSemanticRequest.table(frame.get("inputs"));if(inputs.kind()!=SemanticTables.Kind.DOCUMENT)throw new IOException("SEMANTIC_REPOSITORY_NATIVE_INPUT_KIND");
                try(var nativeInputs=tables.open(inputs,cancelled)){SemanticTables.Row input;while((input=nativeInputs.next())!=null){
                    check(cancelled);if(!input.scope().equals(request.scope())||!input.fields().keySet().equals(Set.of("origin","source","content","lineage")))throw new IOException("SEMANTIC_REPOSITORY_NATIVE_INPUT_FIELDS");var bytes=handle(input.fields().get("content"));var lineage=handle(input.fields().get("lineage"));if(!content.verify(bytes)||!content.verify(lineage))throw new IOException("SEMANTIC_REPOSITORY_NATIVE_INPUT_UNAVAILABLE");String origin=text(input.fields().get("origin")),source="";
                    if(Set.of("INVENTORY","EMBEDDED").contains(origin)){source=new LogicalPath(text(input.fields().get("source"))).key();if(index.get("repo.sources",source).isEmpty())index.put("repo.sources",source,input);}
                    else if(!Set.of("GENERATED","EXTERNAL").contains(origin)||input.fields().get("source")!=null)throw new IOException("SEMANTIC_REPOSITORY_NATIVE_INPUT_ORIGIN");
                    String key=SemanticRootCensus.documentKey(row.key(),input.key());var document=index.get("repo.documents",key);var values=new TreeMap<String,Object>();values.put("context",row.key());values.put("document",input.key());values.put("documentIdentity",document.map(SemanticTables.Row::identity).orElse(null));values.put("origin",origin);values.put("source",source);values.put("content",bytes);values.put("lineage",lineage);index.put("repo.memberships",key,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,key,plan.identity(),values));
                }}
            }}
            for(var build:readiness.contexts()){
                check(cancelled);if(excludedIds.contains(build.id()))context(index,plan,auxiliary("excluded",build.identity()),"EXCLUDED",build.identity(),"EXPLICIT_READINESS_SELECTION",put(build,cancelled));
            }
            try(var uncovered=index.rows("repo.ready",true)){SemanticTables.Row row;while((row=uncovered.next())!=null){if(selectedIds.contains(row.key()))context(index,plan,auxiliary("missing",row.identity()),"UNKNOWN",row.identity(),"SELECTED_BUILD_CONTEXT_NOT_CAPTURED",put(row,cancelled));}}
            for(String unknown:readiness.selection().unknown()){var evidence=put(Map.of("readiness",readyIdentity,"unknown",unknown),cancelled);context(index,plan,auxiliary("unknown",evidence.digest()),"UNKNOWN",evidence.digest(),"READINESS_DISCOVERY_OPEN",evidence);}
            // Context enumeration owns discovery/model questions. Other readiness
            // dimensions remain in the captured readiness artifact and their own
            // build/semantic predicates; a pending child-semantic check must not
            // create a circular prerequisite for the root context census.
            for(var requirement:readiness.requirements())if(requirement.state()==ReadinessPlan.State.PENDING&&Set.of(ReadinessPlan.Kind.DISCOVERY,ReadinessPlan.Kind.MODEL).contains(requirement.kind())){check(cancelled);var evidence=put(requirement,cancelled);context(index,plan,auxiliary("requirement",evidence.digest()),"UNKNOWN",evidence.digest(),"READINESS_CONTEXT_REQUIREMENT_PENDING",evidence);}
            SemanticTables.Table sources;try(var entries=tables.open(plan.inventory().entries(),cancelled)){
                var programming=new HashSet<String>();LanguageProfile.baseline().forEach(profile->programming.add(profile.language()));
                var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending;public boolean hasNext(){try{if(pending==null)pending=entries.next();return pending!=null;}catch(IOException e){throw new UncheckedIOException(e);}}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var row=pending;pending=null;try{
                    check(cancelled);String disposition,reason;
                    if(!"ACQUIRED".equals(row.fields().get("disposition"))){disposition="UNAVAILABLE";reason="ACQUISITION_DISPOSITION_OPEN";}
                    else if("DIRECTORY".equals(row.fields().get("kind"))){disposition="NON_SEMANTIC";reason="INVENTORIED_DIRECTORY";}
                    else if(!"FILE".equals(row.fields().get("kind"))){disposition="UNKNOWN";reason="LINK_OR_COMPONENT_INPUT_REQUIRED";}
                    else if(index.get("repo.sources",row.key()).isPresent()||GraphJson.array(row.fields().get("languageCandidates")).stream().map(GraphJson::string).anyMatch(programming::contains)){disposition="REQUIRED";reason="NATIVE_OR_PROGRAMMING_SOURCE";}
                    else if(!GraphJson.array(row.fields().get("languageCandidates")).isEmpty()){disposition="DEFERRED";reason="ADDITIONAL_LANGUAGE_OR_STRUCTURAL_PROFILE_REQUIRED";}
                    else{disposition="UNKNOWN";reason="SOURCE_CLASSIFICATION_REQUIRED";}
                    return new SemanticTables.Row(SemanticTables.Kind.CENSUS,row.key(),plan.identity(),Map.of("entry",row.identity(),"disposition",disposition,"reason",reason,"evidence",sourceEvidence));
                }catch(IOException e){throw new UncheckedIOException(e);}}};sources=tables.write(SemanticTables.Kind.CENSUS,iterator,cancelled);
            }
            if(!inventory.manifest(revision).filter(sealed::equals).isPresent())throw new IOException("SEMANTIC_REPOSITORY_INVENTORY_CHANGED");
            return new SemanticRootCensus.Input(sources,write(index,"repo.contexts",SemanticTables.Kind.CONTEXT,cancelled),write(index,"repo.memberships",SemanticTables.Kind.DOCUMENT,cancelled),validatorTool);
        }catch(UncheckedIOException failure){throw failure.getCause();}catch(IllegalArgumentException|ClassCastException|NullPointerException|ArithmeticException invalid){throw new IOException("SEMANTIC_REPOSITORY_CENSUS_INVALID",invalid);}
    }
    private SemanticTables.Table write(SemanticRootCensus.Index index,String space,SemanticTables.Kind kind,BooleanSupplier cancelled)throws IOException{
        try(var rows=index.rows(space,false)){var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row pending;public boolean hasNext(){try{if(pending==null)pending=rows.next();return pending!=null;}catch(IOException e){throw new UncheckedIOException(e);}}public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var result=pending;pending=null;return result;}};return tables.write(kind,iterator,cancelled);}
    }
    private static void context(SemanticRootCensus.Index index,SemanticContextCoordinator.Plan plan,String key,String disposition,ContentDigest request,String reason,ContentHandle evidence)throws IOException{index.put("repo.contexts",key,new SemanticTables.Row(SemanticTables.Kind.CONTEXT,key,plan.identity(),Map.of("disposition",disposition,"request",request,"reason",reason,"evidence",evidence)));}
    private static String auxiliary(String role,ContentDigest identity){return "repository-"+role+":"+identity.value();}
    private static void validateRequest(SemanticContextCoordinator.Plan plan,LanguageSemanticRequest request,SemanticTables.Row row)throws IOException{if(!request.identity().value().equals(text(row.fields().get("identity")))||!request.scope().value().equals(text(row.fields().get("scope")))||!request.context().snapshotIdentity().equals(plan.root().snapshotIdentity())||!request.inventory().equals(plan.inventory().inventory())||!request.policy().equals(plan.policy()))throw new IOException("SEMANTIC_REPOSITORY_SELECTED_INPUTS");}
    private Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>65536||!content.verify(handle))throw new IOException("SEMANTIC_REPOSITORY_CAPTURE_UNAVAILABLE_OR_WORKER_REQUIRED");try(var input=content.open(handle)){var value=GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(65536,64,65536)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw new IOException("SEMANTIC_REPOSITORY_NONCANONICAL_CAPTURE");return value;}}
    private static ContentHandle handle(Object value){return value instanceof ContentHandle handle?handle:LanguageSemanticRequest.handle(value);}
    private static String text(Object value){return value instanceof ContentDigest digest?digest.value():GraphJson.string(value);}
    private static Map<String,Object> fields(Object value){return GraphJson.object(GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults()));}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SEMANTIC_REPOSITORY_CENSUS_CANCELLED");}
    private ContentHandle put(Object value,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),cancelled);}
}
