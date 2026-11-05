package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bundled grammar parsing of immutable bytes, one file at a time. No project
 * compiler, plugin, interpreter, model request or target library is loaded. */
public final class StaticPolyglotScan {
    public record Result(SemanticTables.Table records,Map<String,Long> categories,Map<String,Long> failures,long documents,long parsed,long workerFileBytes) {
        public Result{categories=Map.copyOf(categories);failures=Map.copyOf(failures);}
        public Map<String,Object> summary(){return Map.of("provider",TreeSitterSourceFrontend.PROVIDER,"records",records.identity(),"documents",documents,"parsedDocuments",parsed,"categories",categories,"failures",failures,"trust","SOURCE_SYNTAX","boundCallTargets",false,"workerFileBytes",workerFileBytes,"population","LANGUAGE_DOCUMENT_ATTEMPTS");}
    }
    public Result run(PolyglotInventory.Report inventory,SemanticTables tables,RepositoryContentStore content,Path scratch,BooleanSupplier cancelled)throws IOException {
        var categories=new TreeMap<String,Long>();var failures=new TreeMap<String,Long>();long documents=0,parsed=0;
        int fileBudget=(int)Math.min(64_000_000L,Math.max(1_000_000L,Runtime.getRuntime().maxMemory()/16));
        try(var index=SqliteSemanticCensusIndex.temporary(scratch,65536);var input=tables.open(inventory.entries(),cancelled)) {
            SemanticTables.Row row;while((row=input.next())!=null) {
                if(!"FILE".equals(row.fields().get("kind"))||!"ACQUIRED".equals(row.fields().get("disposition")))continue;
                var languages=GraphJson.array(row.fields().get("languageCandidates")).stream().map(GraphJson::string).filter(l->!l.equals("java")).toList();
                for(String hinted:languages) {
                    String path=new LogicalPath(GraphJson.string(row.fields().get("rawPath"))).utf8().orElse("");
                    String language=hinted.equals("typescript")&&path.endsWith(".tsx")?"tsx":hinted;
                    // Unknown/document languages retain the full inventory and native obligations.
                    if(!hinted.equals("protobuf")&&LanguageProfile.baseline().stream().noneMatch(p->p.language().equals(hinted)))continue;
                    documents++;String document=KnowledgeGraph.derivedIdentity("syntax-document",List.of(inventory.inventory(),row.key(),hinted));
                    var source=PolyglotScan.handle(row.fields().get("content"));
                    var metadata=new TreeMap<String,Object>();metadata.put("rowType","DOCUMENT");metadata.put("document",document);metadata.put("path",row.fields().get("rawPath"));metadata.put("language",hinted);metadata.put("content",source);metadata.put("workerFileBytes",fileBudget);metadata.put("provider",TreeSitterSourceFrontend.PROVIDER);
                    try {
                        if(!TreeSitterSourceFrontend.languages().contains(language))throw new IOException("SYNTAX_GRAMMAR_UNAVAILABLE");
                        if(source.size()>fileBudget)throw new IOException("SYNTAX_FILE_WORKER_REQUIRED");
                        if(!content.verify(source))throw new IOException("SYNTAX_SOURCE_UNAVAILABLE");byte[] bytes;
                        try(var captured=content.open(source)){bytes=captured.readNBytes(fileBudget+1);if(bytes.length!=source.size())throw new IOException("SYNTAX_SOURCE_SIZE_MISMATCH");}
                        var summary=new TreeSitterSourceFrontend().parse(language,bytes,fact->{
                            var fields=new TreeMap<String,Object>();fields.put("rowType","FACT");fields.put("document",document);fields.put("content",source);fields.put("language",hinted);fields.put("path",metadata.get("path"));fields.put("semanticBinding","NOT_ESTABLISHED");fields.put("provider",TreeSitterSourceFrontend.PROVIDER);
                            fields.put("fact",GraphJson.object(GraphJson.read(CanonicalJson.write(fact),GraphJson.Limits.defaults())));
                            String key=CanonicalJson.sha256(List.of(document,fact.category(),fact.nodeKind(),fact.span())).value();
                            index.put("syntax",key,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,key,inventory.inventory(),fields));categories.merge(hinted+":"+fact.category(),1L,Long::sum);
                        },cancelled);
                        parsed++;metadata.put("status",summary.errors()==0?(hinted.equals("protobuf")?"SCHEMA_DECLARATIONS_OBSERVED":"PARSED"):"RECOVERED_SYNTAX");metadata.put("summary",GraphJson.object(GraphJson.read(CanonicalJson.write(summary),GraphJson.Limits.defaults())));
                        if(summary.errors()>0)failures.merge(hinted+":SYNTAX_ERROR",1L,Long::sum);
                    }catch(IOException|IllegalArgumentException failure) {
                        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");
                        String reason=failure.getMessage();if(reason==null||!reason.matches("[A-Z][A-Z0-9_]{0,100}"))reason="SYNTAX_SOURCE_OR_PROVIDER_FAILED";
                        metadata.put("status",reason);failures.merge(hinted+":"+reason,1L,Long::sum);
                    }
                    String key="document:"+document;index.put("syntax",key,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,key,inventory.inventory(),metadata));
                }
            }
            try(var rows=index.rows("syntax",false)) {
                var iterator=new Iterator<SemanticTables.Row>(){SemanticTables.Row next;boolean end;
                    public boolean hasNext(){if(next!=null)return true;if(end)return false;try{next=rows.next();end=next==null;return !end;}catch(IOException failure){throw new UncheckedIOException(failure);}}
                    public SemanticTables.Row next(){if(!hasNext())throw new NoSuchElementException();var value=next;next=null;return value;}
                };
                return new Result(tables.write(SemanticTables.Kind.OBSERVATION,iterator,cancelled),categories,failures,documents,parsed,fileBudget);
            }catch(UncheckedIOException failure){throw failure.getCause();}
        }
    }
    public void project(Result result,SemanticTables tables,GraphProjectionStore store,String context,BooleanSupplier cancelled)throws IOException {
        var nodes=store.records("nodes",GraphSnapshotCodec::node);var edges=store.records("edges",GraphSnapshotCodec::edge);var evidence=store.records("evidence",GraphSnapshotCodec::evidence);
        var protoSymbols=store.records("proto-source-symbols",GraphJson::object);var protoMethods=store.records("proto-source-methods",GraphJson::object);
        try(var rows=tables.open(result.records(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
            var fields=row.fields();String document=GraphJson.string(fields.get("document"));var artifact=GraphEvidence.capture("polyglot.source-syntax",row);evidence.putIfAbsent(artifact.identity().value(),artifact);
            if(fields.get("rowType").equals("DOCUMENT")){node(nodes,context,document,"SOURCE_DOCUMENT",artifact.identity());continue;}
            var fact=GraphJson.object(fields.get("fact"));var span=GraphJson.object(fact.get("span"));String category=GraphJson.string(fact.get("category"));
            String slot=span.get("start")+":"+span.get("end");String id=category.equals("DECLARATION")?declaration(document,slot):KnowledgeGraph.derivedIdentity("syntax-site",List.of(document,category,fact.get("nodeKind"),span));
            node(nodes,context,id,switch(category){case "DECLARATION"->fields.get("language").equals("protobuf")?"PROTOBUF_"+GraphJson.string(fact.get("nodeKind")).toUpperCase(Locale.ROOT):"SOURCE_DECLARATION";case "RPC_METHOD"->"PROTOBUF_RPC_METHOD";case "SCHEMA_FIELD"->"PROTOBUF_FIELD";case "CALL"->"CALL_SITE";case "ROUTE_CANDIDATE"->"ROUTE_CANDIDATE";default->"SOURCE_"+category;},artifact.identity());
            if(fields.get("language").equals("protobuf")&&!Boolean.TRUE.equals(fact.get("recovered"))) {
                var attrs=GraphJson.object(fact.get("attributes"));
                if(category.equals("DECLARATION")&&fact.get("nodeKind").equals("message")) {
                    String lookup=document+"|"+GraphJson.string(attrs.get("qualifiedName"));
                    var ids=new TreeSet<String>();var old=protoSymbols.get(lookup);if(old!=null)GraphJson.array(old.get("identities")).forEach(v->ids.add(GraphJson.string(v)));ids.add(id);
                    protoSymbols.put(lookup,Map.of("identities",List.copyOf(ids)));
                }
                if(category.equals("RPC_METHOD"))protoMethods.put(id,Map.of("document",document,"attributes",attrs,"proof",artifact.identity().value()));
            }
            String owner=GraphJson.string(fact.get("owner"));String from=owner.isEmpty()?document:declaration(document,owner);
            String edge=KnowledgeGraph.derivedIdentity("syntax-relation",List.of(document,from,id,category));
            edges.putIfAbsent(context+"|"+edge,new KnowledgeGraph.Edge(edge,context,"syntax."+category.toLowerCase(Locale.ROOT).replace('_','-'),from,new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id),Optional.empty()),List.of(artifact.identity()),Optional.empty()));
        }}
        for(var rpc:protoMethods.entrySet())for(String role:List.of("request","response")) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");
            var attrs=GraphJson.object(rpc.getValue().get("attributes"));String type=GraphJson.string(attrs.get(role+"Type"));
            String qualified=GraphJson.string(attrs.get("qualifiedName"));String prefix=qualified.substring(0,qualified.lastIndexOf('.'));
            Map<String,Object> symbol=null;
            while(true) {
                String name=type.startsWith(".")?type.substring(1):prefix.isEmpty()?type:prefix+"."+type;
                symbol=protoSymbols.get(rpc.getValue().get("document")+"|"+name);if(symbol!=null||type.startsWith(".")||prefix.isEmpty())break;
                int dot=prefix.lastIndexOf('.');prefix=dot<0?"":prefix.substring(0,dot);
            }
            var targets=symbol==null?List.<String>of():GraphJson.array(symbol.get("identities")).stream().map(GraphJson::string).toList();
            var proof=GraphEvidence.capture("protobuf.source-type-candidate",Map.of("method",rpc.getKey(),"role",role,"writtenType",type,"targets",targets,
                    "sourceEvidence",rpc.getValue().get("proof"),"semanticBinding","NOT_ESTABLISHED","generatedStubBinding",false));
            evidence.putIfAbsent(proof.identity().value(),proof);
            String id=KnowledgeGraph.derivedIdentity("protobuf-source-type-candidate",List.of(rpc.getKey(),role,targets));
            var target=targets.isEmpty()?new KnowledgeGraph.Target(KnowledgeGraph.TargetState.UNRESOLVED,List.of(),Optional.of(type))
                    :new KnowledgeGraph.Target(targets.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES,targets,Optional.empty());
            edges.put(context+"|"+id,new KnowledgeGraph.Edge(id,context,"protobuf.rpc-"+role+".candidate",rpc.getKey(),target,List.of(proof.identity()),Optional.empty()));
            if(targets.isEmpty()) {
                var provider=new VersionedIdentifier("polyglot.protobuf-source-types","m8.4-v1");
                var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,proof.identity().value());
                var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,"protobuf.native-descriptor-closure",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                        List.of("Acquire hash-bound native descriptors/import context for this exact schema reference; generated-stub bindings remain a separate obligation."));
                var gap=CapabilityGapRecord.create(EvidenceContext.forSnapshot(new com.evolution.analysis.contract.identity.SnapshotIdentity(context)),provider,"polyglot.protobuf-source","PROTOBUF_SCHEMA_TYPE_UNRESOLVED",subject,List.of(),
                        List.of(ProviderObservationReference.create(provider,"protobuf.source-type-candidate",new ContentDigest(GraphJson.string(rpc.getValue().get("proof"))),proof.identity())),List.of(requirement),List.of(),
                        List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"polyglot.protobuf-source")),List.of(),List.of(),List.of("The source RPC/type spelling remains visible; no imported message or generated stub is invented."));
                var gapEvidence=GraphEvidence.capture("evidence.gap",gap);evidence.putIfAbsent(gapEvidence.identity().value(),gapEvidence);node(nodes,context,gap.gapIdentity().value(),"CAPABILITY_GAP",gapEvidence.identity());
            }
        }
    }
    private static String declaration(String document,String slot){return KnowledgeGraph.derivedIdentity("syntax-declaration",List.of(document,slot));}
    private static void node(Map<String,KnowledgeGraph.Node> nodes,String context,String id,String kind,ContentDigest support){
        var old=nodes.get(context+"|"+id);if(old!=null&&!old.kind().equals(kind))throw new IllegalArgumentException("SYNTAX_NODE_CONFLICT");
        var supports=new TreeSet<ContentDigest>();if(old!=null)supports.addAll(old.evidence());supports.add(support);nodes.put(context+"|"+id,new KnowledgeGraph.Node(id,context,kind,List.copyOf(supports)));
    }
}
