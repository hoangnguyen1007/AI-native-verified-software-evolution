package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Full-inventory scan composition. Native outputs use the existing language coordinator's
 * authenticated publication lane, never target execution on the controller. */
public final class PolyglotScan {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("scan.polyglot","m8.4-protobuf-http-v5");
    @FunctionalInterface public interface Current {void check()throws IOException;}
    public record Published(SemanticContextCoordinator.Plan plan,SemanticPublicationStore.Publication publication,
            RepositoryContentStore content,SemanticTables tables,Map<String,Object> report,String trust,Current current) {}
    public record Result(PolyglotInventory.Report inventory,SemanticTables tables,RepositoryContentStore content,
            ReadinessPlan readiness,EvidenceContext context,Map<String,Long> languages,Map<String,Long> uncovered,
            long nativeContexts,long nativeDocuments,long nativeObservationRows,List<CapabilityGapRecord> gaps,Optional<Published> published,StaticPolyglotScan.Result syntax) {
        public Result {languages=Map.copyOf(languages);uncovered=Map.copyOf(uncovered);gaps=List.copyOf(gaps);}
        public boolean nonJavaSources(){return languages.keySet().stream().anyMatch(l->!l.equals("java"));}
        public Map<String,Object> summary() {
            return Map.ofEntries(Map.entry("inventory",inventory),Map.entry("filesByLanguage",languages),Map.entry("withoutNativeContextByLanguage",uncovered),
                    Map.entry("buildComponents",readiness.modules().size()),Map.entry("nativeContexts",nativeContexts),Map.entry("nativeDocuments",nativeDocuments),Map.entry("nativeObservationRows",nativeObservationRows),
                    Map.entry("semanticReuse","REVALIDATION_REQUIRED"),
                    Map.entry("status",published.isPresent()?"CAPTURED_NATIVE_WITH_STATIC_SYNTAX":"STATIC_SYNTAX_WITH_PROVIDER_OBLIGATIONS"),Map.entry("sourceSyntax",syntax.summary()),
                    Map.entry("trust",published.map(Published::trust).orElse("SOURCE_SYNTAX")),
                    Map.entry("interpretation","File language hints and build components are inventory; native facts retain their original context, gaps and trust. Component counts are not deployed-service counts."));
        }
    }
    public static Published load(Path directory,ContentDigest plan,ContentDigest policy,Optional<Path> authority,BooleanSupplier cancelled)throws IOException {
        directory=StreamingContentStore.existingDirectory(directory);
        StreamingContentStore.existingDirectory(directory.resolve("cas"));StreamingContentStore.existingDirectory(directory.resolve("publications"));
        var content=new StreamingContentStore(directory.resolve("cas"));var tables=new SemanticTables(content,65536);
        Optional<SemanticPublicationValidation> validation=Optional.empty();
        Path work=directory.resolve("scan-work");
        if(authority.isPresent())validation=Optional.of(new SemanticAuthorityStore(authority.orElseThrow(),content,tables,policy)
                .validation(()->SqliteSemanticCensusIndex.temporary(work,65536)));
        var store=new SemanticPublicationStore(directory.resolve("publications"),content,tables,validation);
        java.util.function.Supplier<ContentDigest> live=()->{
            try{return authority.isPresent()?SemanticAuthorityStore.policy(authority.orElseThrow()):policy;}
            catch(IOException unavailable){return ContentDigest.sha256Utf8("semantic scan authority unavailable");}
        };
        var publication=store.load(plan,live,cancelled)
                .orElseThrow(()->new IOException("SEMANTIC_PUBLICATION_UNAVAILABLE"));
        var report=read(content,publication.rootReport());var metadata=read(content,publication.document());
        return new Published(SemanticContextCoordinator.Plan.from(report.get("plan")),publication,content,tables,report,GraphJson.string(metadata.get("trust")),()->{
            if(!store.load(plan,live,cancelled).map(value->value.document().equals(publication.document())).orElse(false))throw new IOException("SEMANTIC_SCAN_PUBLICATION_CHANGED");
        });
    }

    public Result run(String revision,RepositoryIntake intake,ReadinessPlan readiness,EvidenceContext context,
            Optional<Published> published,Path scratch,BooleanSupplier cancelled)throws IOException {
        var tables=new SemanticTables(intake.content(),65536);
        var inventory=new PolyglotInventory().scan(revision,intake.inventory(),intake.content(),tables,cancelled);
        var languages=new TreeMap<String,Long>();var uncovered=new TreeMap<String,Long>();long[] counts={0,0,0};
        var profiles=LanguageProfile.baseline().stream().map(LanguageProfile::language).collect(java.util.stream.Collectors.toSet());
        profiles.add("protobuf");
        try(var index=SqliteSemanticCensusIndex.temporary(scratch,65536)) {
            try(var rows=tables.open(inventory.entries(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
                index.put("inventory",row.key(),row);if(!"FILE".equals(row.fields().get("kind")))continue;
                for(String language:languages(row))if(profiles.contains(language))languages.merge(language,1L,Long::sum);
            }}
            if(published.isPresent()) {
                var nativeInput=published.orElseThrow();nativeInput.current().check();var expected=SnapshotIdentity.from(readiness.inventory().repository(),inventory.inventory());
                if(!nativeInput.plan().inventory().equals(inventory)||!context.snapshotIdentity().equals(nativeInput.plan().root().snapshotIdentity())&&!expected.equals(nativeInput.plan().root().snapshotIdentity()))
                    throw new IOException("SEMANTIC_SCAN_SNAPSHOT_OR_INVENTORY_MISMATCH");
                try(var rows=nativeInput.tables().open(nativeInput.plan().contexts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
                    var request=LanguageSemanticRequest.from(read(nativeInput.content(),handle(row.fields().get("request"))));counts[0]++;
                    try(var docs=nativeInput.tables().open(request.documents(),cancelled)){SemanticTables.Row doc;while((doc=docs.next())!=null) {
                        String key=LogicalPath.utf8(doc.key()).key();var member=index.get("inventory",key).orElseThrow(()->new IOException("SEMANTIC_SCAN_DOCUMENT_OUTSIDE_INVENTORY"));
                        if(!"ACQUIRED".equals(member.fields().get("disposition"))||!CanonicalJson.write(doc.fields().get("content")).equals(CanonicalJson.write(member.fields().get("content"))))throw new IOException("SEMANTIC_SCAN_SOURCE_BYTES_MISMATCH");
                        // Native membership can disambiguate a language hint; it cannot contradict it.
                        if(!languages(member).isEmpty()&&!languages(member).contains(request.language()))throw new IOException("SEMANTIC_SCAN_LANGUAGE_CONFLICT");
                        if(index.get("covered",key).isEmpty()){index.put("covered",key,doc);counts[1]++;}
                    }}
                }}
                try(var rows=nativeInput.tables().open(nativeInput.publication().contexts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
                    var receipt=read(nativeInput.content(),handle(row.fields().get("receipt")));
                    if(receipt.get("observations")!=null)counts[2]=Math.addExact(counts[2],table(receipt.get("observations")).rows());
                }}
            }
            try(var rows=tables.open(inventory.entries(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
                if(!"FILE".equals(row.fields().get("kind"))||index.get("covered",row.key()).isPresent())continue;
                for(String language:languages(row))if(profiles.contains(language)&&!language.equals("java"))uncovered.merge(language,1L,Long::sum);
            }}
        }
        var gaps=new ArrayList<CapabilityGapRecord>();
        for(var language:uncovered.entrySet())gaps.add(gap(context,inventory,language.getKey(),language.getValue(),language.getKey().equals("protobuf")?"PROTOBUF_GENERATOR_REQUIRED":"NATIVE_CONTEXT_REQUIRED"));
        if(published.isPresent()&&!Boolean.TRUE.equals(published.orElseThrow().report().get("complete")))gaps.add(gap(context,inventory,"polyglot",counts[0],"NATIVE_PUBLICATION_PARTIAL"));
        var syntax=new StaticPolyglotScan().run(inventory,tables,intake.content(),scratch,cancelled);
        boolean strongerNativeComplete=published.filter(p->p.trust().equals("VALIDATED_OBSERVATION")&&Boolean.TRUE.equals(p.report().get("complete"))).isPresent();
        syntax.failures().forEach((failure,count)->{int separator=failure.indexOf(':');String reason=failure.substring(separator+1);
            // A missing optional grammar is already covered by native-context/root
            // obligations. Stronger qualified evidence supersedes weaker-parser gaps.
            if(!strongerNativeComplete&&!reason.equals("SYNTAX_GRAMMAR_UNAVAILABLE"))gaps.add(gap(context,inventory,failure.substring(0,separator),count,reason));
        });
        return new Result(inventory,tables,intake.content(),readiness,context,languages,uncovered,counts[0],counts[1],counts[2],gaps,published,syntax);
    }
    private static List<String> languages(SemanticTables.Row row){return GraphJson.array(row.fields().get("languageCandidates")).stream().map(GraphJson::string).toList();}
    private static CapabilityGapRecord gap(EvidenceContext context,PolyglotInventory.Report inventory,String language,long count,String reason) {
        var subject=new EvidenceSubject(EvidenceSubject.Kind.SOURCE_SET,inventory.inventory().value()+"#"+language);
        var need=language.equals("protobuf")
                ?new EvidenceRequirement(EvidenceRequirement.Kind.GENERATED_SOURCE,"polyglot.protobuf-generation",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Acquire exact protoc/plugin identities, import context, native descriptors and generated-source lineage; source schema declarations do not establish stub or client/server bindings."))
                :new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.native-context",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Acquire exact native context/census/observations with the registered language adapter; keep missing and unqualified results explicit."));
        return CapabilityGapRecord.create(context,PROVIDER,"polyglot."+language,reason,subject,List.of(),List.of(ProviderObservationReference.create(PROVIDER,"polyglot.scan",inventory.identity(),CanonicalJson.sha256(List.of(language,count,reason)))),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot."+language)),List.of(),List.of(),List.of("documents="+count,"Full classified document denominator: "+inventory.entries().identity().value()));
    }
    public CapabilityGapRecord javaWorkerGap(Result result) {
        return gap(result.context(),result.inventory(),"java",result.languages().getOrDefault("java",0L),"JAVA_COMPATIBILITY_WORKER_REQUIRED");
    }
    /** Preserve the full inventory and static/native facts even when the legacy Java
     * view needs a larger/sharded worker. This root claims no Java attribution. */
    public void projectInventoryRoot(Result result,GraphProjectionStore store,BooleanSupplier cancelled)throws IOException {
        if(!result.context().snapshotIdentity().equals(SnapshotIdentity.from(result.readiness().inventory().repository(),result.inventory().inventory())))throw new IOException("INVENTORY_GRAPH_SNAPSHOT_MISMATCH");
        var writer=new Projection(store);String context=result.context().snapshotIdentity().value();
        var evidence=writer.evidence("repository.inventory-snapshot",Map.of("snapshot",result.context().snapshotIdentity(),"inventory",result.inventory().inventory(),"readiness",result.readiness().identity(),"javaAttribution","WORKER_REQUIRED"));
        writer.contexts.put(context,new KnowledgeGraph.Context(context,result.context().snapshotIdentity(),Optional.empty(),evidence));
        writer.node(KnowledgeGraph.derivedIdentity("inventory-root",List.of(result.inventory().inventory(),result.readiness().inventory().repository())),context,"REPOSITORY",evidence);
        var gap=javaWorkerGap(result);writer.node(gap.gapIdentity().value(),context,"CAPABILITY_GAP",writer.evidence("evidence.gap",gap));
        project(result,store,cancelled);
    }

    /** Project inventory and captured native rows without collecting an aggregate graph or export. */
    public void project(Result result,GraphProjectionStore store,BooleanSupplier cancelled)throws IOException {
        project(result,store,result.context().snapshotIdentity(),cancelled);
    }
    public void project(Result result,GraphProjectionStore store,SnapshotIdentity graphSnapshot,BooleanSupplier cancelled)throws IOException {
        var writer=new Projection(store);String context=graphSnapshot.value();
        if(result.published().isPresent())result.published().orElseThrow().current().check();
        var modules=new TreeMap<String,String>();
        for(var module:result.readiness().modules()) {
            String identity=KnowledgeGraph.derivedIdentity("inventory-component",List.of(result.inventory().inventory(),module.id()));modules.put(module.id(),identity);
            writer.node(identity,context,"BUILD_COMPONENT",writer.evidence("polyglot.build-component",module));
        }
        writer.evidence("polyglot.scan",result.summary());
        new StaticPolyglotScan().project(result.syntax(),result.tables(),store,context,cancelled);
        writer.evidence("polyglot.snapshot-binding",Map.of("graphSnapshot",graphSnapshot,"sourceSnapshot",result.context().snapshotIdentity(),"inventory",result.inventory().inventory(),"scope","CAPTURED_BASE_INVENTORY"));
        for(var gap:result.gaps())writer.node(gap.gapIdentity().value(),context,"CAPABILITY_GAP",writer.evidence("evidence.gap",gap));
        try(var rows=result.tables().open(result.inventory().entries(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
            var support=writer.evidence("polyglot.inventory-entry",row);String identity=KnowledgeGraph.derivedIdentity("inventory-document",List.of(result.inventory().inventory(),row.key()));
            writer.node(identity,context,"INVENTORY_ENTRY",support);
            var path=new LogicalPath(GraphJson.string(row.fields().get("rawPath"))).utf8();
            if(path.isPresent())for(var module:result.readiness().modules())if(module.directory().equals(".")||path.orElseThrow().equals(module.directory())||path.orElseThrow().startsWith(module.directory()+"/"))
                writer.edge(context,"graph.inventory-under-component",modules.get(module.id()),identity,support);
        }}
        if(result.published().isPresent()) {
            var nativeInput=result.published().orElseThrow();writer.evidence("polyglot.publication",read(nativeInput.content(),nativeInput.publication().document()));
            writer.evidence("polyglot.root-report",nativeInput.report());
            try(var rows=nativeInput.tables().open(nativeInput.publication().contexts(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
                var receipt=read(nativeInput.content(),handle(row.fields().get("receipt")));var request=LanguageSemanticRequest.from(read(nativeInput.content(),handle(receipt.get("request"))));
                var support=writer.evidence("polyglot.context",Map.of("request",request,"receipt",receipt,"trust",nativeInput.trust()));
                var prior=writer.contexts.putIfAbsent(request.scope().value(),new KnowledgeGraph.Context(request.scope().value(),graphSnapshot,Optional.empty(),support));
                if(prior!=null&&!prior.evidence().equals(support))throw new IOException("SEMANTIC_SCAN_CONTEXT_CONFLICT");
                for(String section:List.of("documents","census","observations","gaps")) {
                    Object raw=switch(section){case "documents"->request.documents();case "census"->request.census();default->receipt.get(section);};if(raw==null)continue;
                    var table=raw instanceof SemanticTables.Table t?t:table(raw);
                    try(var records=nativeInput.tables().open(table,cancelled)){SemanticTables.Row record;while((record=records.next())!=null) {
                        var evidence=writer.evidence("polyglot."+section,record);String id=KnowledgeGraph.derivedIdentity("polyglot-record",List.of(request.scope(),section,record.key()));
                        writer.node(id,request.scope().value(),"POLYGLOT_"+section.toUpperCase(Locale.ROOT),evidence);
                    }}
                }
                projectBindings(writer,nativeInput,request,receipt,cancelled);
            }}
            nativeInput.current().check();
        }
        HttpServiceCallProjection.project(writer.nodes,writer.edges,writer.evidence,store.records("http-projection-additions",GraphSnapshotCodec::node),context,cancelled);
    }
    /** Join the native census to native targets. Syntax names never supply a callee.
     * Producer assertions use a separate edge vocabulary from validated observations. */
    private static void projectBindings(Projection writer,Published input,LanguageSemanticRequest request,Map<String,Object> receipt,BooleanSupplier cancelled)throws IOException {
        if(receipt.get("observations")==null)return;
        String context=request.scope().value();
        try(var questions=input.tables().open(request.census(),cancelled);var answers=input.tables().open(table(receipt.get("observations")),cancelled)){
            SemanticTables.Row question=questions.next(),answer;
            while((answer=answers.next())!=null){
                while(question!=null&&question.key().compareTo(answer.key())<0)question=questions.next();
                if(question==null||!question.key().equals(answer.key())||!question.identity().value().equals(answer.fields().get("obligation")))throw new IOException("SEMANTIC_SCAN_BINDING_CENSUS");
                String category=GraphJson.string(question.fields().get("category"));
                if(!Set.of("A03","A04","A05","A06","A07","A09").contains(category))continue;
                String status=GraphJson.string(answer.fields().get("status"));
                var targets=GraphJson.array(answer.fields().get("targets")).stream().map(GraphJson::string).toList();
                if(targets.isEmpty())continue;
                String relation=switch(category){case "A03"->"imports";case "A04"->"uses-type";case "A05"->"inherits";case "A06"->"calls";case "A07"->"accesses-state";default->"annotated-with";};
                var proof=new TreeMap<String,Object>();proof.put("provider",request.provider());proof.put("tool",request.tool());proof.put("context",request.scope());proof.put("census",question);proof.put("observation",answer);proof.put("trust",input.trust());proof.put("bindingPhase","COMPILE_TIME");proof.put("runtimeObserved",false);
                var support=writer.evidence("polyglot.native-binding",proof);
                String occurrence=KnowledgeGraph.derivedIdentity("polyglot-record",List.of(request.scope(),"observations",answer.key()));
                if(answer.fields().get("symbols") instanceof List<?> symbols)for(var raw:symbols){
                    try{
                        var symbol=GraphJson.object(raw);String id=GraphJson.string(symbol.get("id"));new ContentDigest(id);
                        writer.node(id,context,"POLYGLOT_SYMBOL",writer.evidence("polyglot.native-symbol",Map.of("symbol",symbol,"context",request.scope(),"provider",request.provider(),"tool",request.tool(),"trust",input.trust())));
                    }catch(IllegalArgumentException unavailable){bindingGap(writer,request,answer,"NATIVE_SYMBOL_METADATA_UNAVAILABLE");}
                }
                for(var id:targets){new ContentDigest(id);writer.symbolReference(request,id,input.trust());}
                String owner=answer.fields().get("caller") instanceof String caller?caller:answer.fields().get("owner") instanceof String declaredOwner?declaredOwner:occurrence;
                if(!owner.equals(occurrence))try{new ContentDigest(owner);}catch(IllegalArgumentException unavailable){bindingGap(writer,request,answer,"NATIVE_CALLER_METADATA_UNAVAILABLE");owner=occurrence;}
                if(!owner.equals(occurrence)){writer.symbolReference(request,owner,input.trust());writer.edge(context,"polyglot.binding-owner",occurrence,owner,support);}
                var targetState=status.equals("RESOLVED")&&targets.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES;
                String kind=(input.trust().equals("VALIDATED_OBSERVATION")?"polyglot.native.":"polyglot.asserted.")+relation;
                String id=KnowledgeGraph.derivedIdentity("polyglot-binding",List.of(context,answer.key(),kind));
                var edge=new KnowledgeGraph.Edge(id,context,kind,owner,new KnowledgeGraph.Target(targetState,targets,Optional.empty()),List.of(support),Optional.empty());
                var previous=writer.edges.putIfAbsent(context+"|"+id,edge);if(previous!=null&&!previous.equals(edge))throw new IOException("SEMANTIC_SCAN_BINDING_CONFLICT");
            }
        }
    }
    private static void bindingGap(Projection writer,LanguageSemanticRequest request,SemanticTables.Row answer,String reason){
        var reference=ProviderObservationReference.create(PROVIDER,"polyglot.native-binding",request.identity(),answer.identity());
        var subject=EvidenceSubject.observation(reference.identity());
        var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"polyglot.binding-metadata",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Supply native symbol/caller metadata tied to this exact captured occurrence."));
        var gap=CapabilityGapRecord.create(request.context(),PROVIDER,"polyglot.binding-projection",reason,subject,List.of(),List.of(reference),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION,"polyglot.native-binding")),List.of(),List.of(),List.of("The native observation and targets are retained; missing declaration metadata is not invented."));
        writer.node(gap.gapIdentity().value(),request.scope().value(),"CAPABILITY_GAP",writer.evidence("evidence.gap",gap));
    }
    private static final class Projection {
        final Map<String,KnowledgeGraph.Context> contexts;final Map<String,KnowledgeGraph.Node> nodes;final Map<String,KnowledgeGraph.Edge> edges;final Map<String,GraphEvidence> evidence;
        Projection(GraphProjectionStore store){contexts=store.records("contexts",GraphSnapshotCodec::context);nodes=store.records("nodes",GraphSnapshotCodec::node);edges=store.records("edges",GraphSnapshotCodec::edge);evidence=store.records("evidence",GraphSnapshotCodec::evidence);}
        ContentDigest evidence(String family,Object value){var row=GraphEvidence.capture(family,value);var old=evidence.putIfAbsent(row.identity().value(),row);if(old!=null&&!old.equals(row))throw new IllegalArgumentException("SEMANTIC_SCAN_EVIDENCE_CONFLICT");return row.identity();}
        void symbolReference(LanguageSemanticRequest request,String id,String trust){node(id,request.scope().value(),"POLYGLOT_SYMBOL",evidence("polyglot.native-symbol-reference",Map.of("symbol",id,"context",request.scope(),"provider",request.provider(),"tool",request.tool(),"trust",trust,"declarationMetadata","NOT_IMPLIED")));}
        void node(String id,String context,String kind,ContentDigest support){var row=new KnowledgeGraph.Node(id,context,kind,List.of(support));var old=nodes.putIfAbsent(context+"|"+id,row);if(old!=null&&!old.equals(row)){if(!old.kind().equals(kind))throw new IllegalArgumentException("SEMANTIC_SCAN_NODE_CONFLICT");if(old.evidence().contains(support))return;var combined=new TreeSet<>(old.evidence());combined.add(support);nodes.put(context+"|"+id,new KnowledgeGraph.Node(id,context,kind,List.copyOf(combined)));}}
        void edge(String context,String kind,String from,String to,ContentDigest support){String id=KnowledgeGraph.derivedIdentity("inventory-relation",List.of(kind,from,to));var row=new KnowledgeGraph.Edge(id,context,kind,from,new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(to),Optional.empty()),List.of(support),Optional.empty());var old=edges.putIfAbsent(context+"|"+id,row);if(old!=null&&!old.equals(row))throw new IllegalArgumentException("SEMANTIC_SCAN_EDGE_CONFLICT");}
    }
    static Map<String,Object> read(RepositoryContentStore content,ContentHandle handle)throws IOException {
        if(handle.size()>4_000_000||!content.verify(handle))throw new IOException("SEMANTIC_SCAN_METADATA_UNAVAILABLE");
        try(var input=content.open(handle)){var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT));return GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(4_000_000,128,4_000_000)));}
    }
    static ContentHandle handle(Object raw){var row=GraphJson.object(raw);if(!row.keySet().equals(Set.of("digest","size")))throw new IllegalArgumentException("SEMANTIC_SCAN_HANDLE_FIELDS");return new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),count(row.get("size")));}
    static SemanticTables.Table table(Object raw){var row=GraphJson.object(raw);if(!row.keySet().equals(Set.of("schema","kind","content","rows")))throw new IllegalArgumentException("SEMANTIC_SCAN_TABLE_FIELDS");return new SemanticTables.Table(GraphJson.string(row.get("schema")),SemanticTables.Kind.valueOf(GraphJson.string(row.get("kind"))),handle(row.get("content")),count(row.get("rows")));}
    private static long count(Object raw){if(raw instanceof java.math.BigDecimal value)return value.longValueExact();if(raw instanceof Long||raw instanceof Integer)return ((Number)raw).longValue();throw new IllegalArgumentException("SEMANTIC_SCAN_INTEGER_REQUIRED");}
}
