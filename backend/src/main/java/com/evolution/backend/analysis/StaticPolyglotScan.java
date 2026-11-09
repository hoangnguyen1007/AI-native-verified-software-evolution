package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.polyglot.*;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import com.evolution.analysis.javaparser.JavaGrpcSourceFrontend;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bundled grammar parsing of immutable bytes, one file at a time. No project
 * compiler, plugin, interpreter, model request or target library is loaded. */
public final class StaticPolyglotScan {
    public record Result(SemanticTables.Table records,Map<String,Long> categories,Map<String,Long> failures,long documents,long parsed,long workerFileBytes) {
        public Result{categories=Map.copyOf(categories);failures=Map.copyOf(failures);}
        public Map<String,Object> summary(){var values=new TreeMap<String,Object>(Map.of("provider",TreeSitterSourceFrontend.provider(),"records",records.identity(),"documents",documents,"parsedDocuments",parsed,"categories",categories,"failures",failures,"trust","SOURCE_SYNTAX","boundCallTargets",false,"workerFileBytes",workerFileBytes,"population","LANGUAGE_DOCUMENT_ATTEMPTS"));values.put("javaRpcProvider",JavaGrpcSourceFrontend.PROVIDER);values.put("javaRpcAcquisition","SELECTIVE_SOURCE_SYNTAX");return Map.copyOf(values);}
    }
    public Result run(PolyglotInventory.Report inventory,SemanticTables tables,RepositoryContentStore content,Path scratch,BooleanSupplier cancelled)throws IOException {
        var categories=new TreeMap<String,Long>();var failures=new TreeMap<String,Long>();long documents=0,parsed=0;
        int fileBudget=(int)Math.min(64_000_000L,Math.max(1_000_000L,Runtime.getRuntime().maxMemory()/16));
        try(var index=SqliteSemanticCensusIndex.temporary(scratch,65536);var input=tables.open(inventory.entries(),cancelled)) {
            SemanticTables.Row row;while((row=input.next())!=null) {
                if(!"FILE".equals(row.fields().get("kind"))||!"ACQUIRED".equals(row.fields().get("disposition")))continue;
                var languages=GraphJson.array(row.fields().get("languageCandidates")).stream().map(GraphJson::string).toList();
                for(String hinted:languages) {
                    String path=new LogicalPath(GraphJson.string(row.fields().get("rawPath"))).utf8().orElse("");
                    String language=hinted.equals("typescript")&&path.endsWith(".tsx")?"tsx":hinted;
                    // Unknown/document languages retain the full inventory and native obligations.
                    if(!hinted.equals("protobuf")&&LanguageProfile.baseline().stream().noneMatch(p->p.language().equals(hinted)))continue;
                    var source=PolyglotScan.handle(row.fields().get("content"));
                    byte[] javaBytes=null;IOException javaFailure=null;
                    if(hinted.equals("java"))try{javaBytes=source(content,source,fileBudget);if(!JavaGrpcSourceFrontend.candidate(javaBytes))continue;}catch(IOException failure){javaFailure=failure;}
                    documents++;String document=KnowledgeGraph.derivedIdentity("syntax-document",List.of(inventory.inventory(),row.key(),hinted));
                    String provider=hinted.equals("java")?JavaGrpcSourceFrontend.PROVIDER:TreeSitterSourceFrontend.provider();
                    var metadata=new TreeMap<String,Object>();metadata.put("rowType","DOCUMENT");metadata.put("document",document);metadata.put("path",row.fields().get("rawPath"));metadata.put("language",hinted);metadata.put("content",source);metadata.put("workerFileBytes",fileBudget);metadata.put("provider",provider);
                    try {
                        if(!hinted.equals("java")&&!TreeSitterSourceFrontend.languages().contains(language))throw new IOException("SYNTAX_GRAMMAR_UNAVAILABLE");
                        if(javaFailure!=null)throw javaFailure;byte[] bytes=javaBytes==null?source(content,source,fileBudget):javaBytes;
                        TreeSitterSourceFrontend.Sink receive=fact->{
                            var fields=new TreeMap<String,Object>();fields.put("rowType","FACT");fields.put("document",document);fields.put("content",source);fields.put("language",hinted);fields.put("path",metadata.get("path"));fields.put("semanticBinding","NOT_ESTABLISHED");fields.put("provider",provider);
                            fields.put("fact",GraphJson.object(GraphJson.read(CanonicalJson.write(fact),GraphJson.Limits.defaults())));
                            String key=CanonicalJson.sha256(fact.category().startsWith("RPC_")?List.of(document,fact.category(),fact.nodeKind(),fact.span(),fact.name(),fact.attributes()):List.of(document,fact.category(),fact.nodeKind(),fact.span())).value();
                            index.put("syntax",key,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,key,inventory.inventory(),fields));categories.merge(hinted+":"+fact.category(),1L,Long::sum);
                        };
                        TreeSitterSourceFrontend.Summary summary;
                        if(hinted.equals("java")) {
                            var parsedJava=new JavaGrpcSourceFrontend().parse(bytes,fact->receive.accept(new TreeSitterSourceFrontend.Fact(fact.category(),fact.nodeKind(),fact.name(),fact.written(),fact.span(),fact.owner(),fact.recovered(),fact.attributes())),cancelled);
                            summary=new TreeSitterSourceFrontend.Summary(parsedJava.nodes(),parsedJava.facts(),parsedJava.errors(),parsedJava.grammar(),parsedJava.toolIdentity());
                        } else summary=new TreeSitterSourceFrontend().parse(language,bytes,receive,cancelled);
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
    private static byte[] source(RepositoryContentStore content,ContentHandle source,int fileBudget)throws IOException {
        if(source.size()>fileBudget)throw new IOException("SYNTAX_FILE_WORKER_REQUIRED");if(!content.verify(source))throw new IOException("SYNTAX_SOURCE_UNAVAILABLE");
        try(var captured=content.open(source)){byte[] bytes=captured.readNBytes(fileBudget+1);if(bytes.length!=source.size())throw new IOException("SYNTAX_SOURCE_SIZE_MISMATCH");return bytes;}
    }
    private record DeclEntry(String id,String document,String langGroup,String normPath,String dirPath,String fileStem,String symbolName,String proof) {}
    private record SiteEntry(String siteId,String document,String langGroup,String language,String normPath,String dirPath,String category,String name,String written,String proof) {}
    private static final Set<String> KEYWORDS=Set.of("import","from","as","type","default","require","package","using","export","const","let","var","async","function","class","interface","struct","def","func","extends","implements","new","return","self","this","super");

    public void project(Result result,SemanticTables tables,GraphProjectionStore store,String context,BooleanSupplier cancelled)throws IOException {
        var nodes=store.records("nodes",GraphSnapshotCodec::node);var edges=store.records("edges",GraphSnapshotCodec::edge);var evidence=store.records("evidence",GraphSnapshotCodec::evidence);
        var protoSymbols=store.records("proto-source-symbols",GraphJson::object);var protoMethods=store.records("proto-source-methods",GraphJson::object);
        var byLangAndSymbol=new HashMap<String,List<DeclEntry>>();var byDocAndSymbol=new HashMap<String,List<DeclEntry>>();
        var byDirAndSymbol=new HashMap<String,List<DeclEntry>>();var byStemAndSymbol=new HashMap<String,List<DeclEntry>>();
        var docImports=new HashMap<String,Set<String>>();var sites=new ArrayList<SiteEntry>();
        try(var rows=tables.open(result.records(),cancelled)){SemanticTables.Row row;while((row=rows.next())!=null) {
            var fields=row.fields();String document=GraphJson.string(fields.get("document"));var artifact=GraphEvidence.capture("polyglot.source-syntax",row);evidence.putIfAbsent(artifact.identity().value(),artifact);
            if(fields.get("rowType").equals("DOCUMENT")){node(nodes,context,document,"SOURCE_DOCUMENT",artifact.identity());continue;}
            var fact=GraphJson.object(fields.get("fact"));var span=GraphJson.object(fact.get("span"));String category=GraphJson.string(fact.get("category"));
            String slot=span.get("start")+":"+span.get("end");String id=category.equals("DECLARATION")?declaration(document,slot):KnowledgeGraph.derivedIdentity("syntax-site",category.startsWith("RPC_")&&!category.equals("RPC_METHOD")?List.of(document,category,fact.get("nodeKind"),span,fact.get("name"),fact.get("attributes")):List.of(document,category,fact.get("nodeKind"),span));
            String language=GraphJson.string(fields.get("language"));
            node(nodes,context,id,switch(category){case "DECLARATION"->language.equals("protobuf")?"PROTOBUF_"+GraphJson.string(fact.get("nodeKind")).toUpperCase(Locale.ROOT):"SOURCE_DECLARATION";case "RPC_METHOD"->"PROTOBUF_RPC_METHOD";case "SCHEMA_FIELD"->"PROTOBUF_FIELD";case "CALL"->"CALL_SITE";case "ROUTE_CANDIDATE"->"ROUTE_CANDIDATE";default->"SOURCE_"+category;},artifact.identity());
            if(language.equals("protobuf")&&!Boolean.TRUE.equals(fact.get("recovered"))) {
                var attrs=GraphJson.object(fact.get("attributes"));
                if(category.equals("DECLARATION")&&fact.get("nodeKind").equals("message")) {
                    String lookup=document+"|"+GraphJson.string(attrs.get("qualifiedName"));
                    var ids=new TreeSet<String>();var old=protoSymbols.get(lookup);if(old!=null)GraphJson.array(old.get("identities")).forEach(v->ids.add(GraphJson.string(v)));ids.add(id);
                    protoSymbols.put(lookup,Map.of("identities",List.copyOf(ids)));
                }
                if(category.equals("RPC_METHOD"))protoMethods.put(id,Map.of("document",document,"attributes",attrs,"proof",artifact.identity().value()));
            } else if(!language.equals("protobuf")&&!Boolean.TRUE.equals(fact.get("recovered"))) {
                String rawPath=fields.get("path")==null?"":GraphJson.string(fields.get("path")).replace('\\','/');
                int slash=rawPath.lastIndexOf('/');String dir=slash<0?"":rawPath.substring(0,slash);
                String base=slash<0?rawPath:rawPath.substring(slash+1);int dot=base.lastIndexOf('.');String stem=dot<0?base:base.substring(0,dot);
                if((stem.equals("index")||stem.equals("__init__"))&&!dir.isEmpty()){int pSlash=dir.lastIndexOf('/');stem=pSlash<0?dir:dir.substring(pSlash+1);}
                String group=langGroup(language);String name= fact.get("name")==null?"":GraphJson.string(fact.get("name"));
                String written=fact.get("written")==null?"":GraphJson.string(fact.get("written"));
                if(category.equals("DECLARATION")) {
                    String sym=declSymbol(name,written);
                    if(!sym.isEmpty()&&!KEYWORDS.contains(sym)) {
                        var entry=new DeclEntry(id,document,group,rawPath,dir,stem,sym,artifact.identity().value());
                        addDecl(byLangAndSymbol,group+"|"+sym,entry);addDecl(byDocAndSymbol,document+"|"+sym,entry);
                        addDecl(byDirAndSymbol,group+"|"+dir+"|"+sym,entry);
                        if(!stem.isEmpty())addDecl(byStemAndSymbol,group+"|"+stem+"|"+sym,entry);
                    }
                } else if(Set.of("IMPORT","CALL","HERITAGE").contains(category)) {
                    if(category.equals("IMPORT")) {
                        var imported=docImports.computeIfAbsent(document,k->new TreeSet<>());
                        for(String tok:tokens(name+" "+written))if(!KEYWORDS.contains(tok))imported.add(tok);
                    }
                    sites.add(new SiteEntry(id,document,group,language,rawPath,dir,category,name,written,artifact.identity().value()));
                }
            }
            String owner=GraphJson.string(fact.get("owner"));String from=owner.isEmpty()?document:declaration(document,owner);
            String edge=KnowledgeGraph.derivedIdentity("syntax-relation",List.of(document,from,id,category));
            edges.putIfAbsent(context+"|"+edge,new KnowledgeGraph.Edge(edge,context,"syntax."+category.toLowerCase(Locale.ROOT).replace('_','-'),from,new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(id),Optional.empty()),List.of(artifact.identity()),Optional.empty()));
            if(category.equals("RPC_HANDLER_CANDIDATE")&&Set.of("function_definition","function_declaration","method_declaration","method_definition").contains(fact.get("nodeKind"))) {
                String declaration=declaration(document,slot);String binding=KnowledgeGraph.derivedIdentity("rpc-handler-source-declaration",List.of(id,declaration));
                edges.putIfAbsent(context+"|"+binding,new KnowledgeGraph.Edge(binding,context,"syntax.rpc-handler-declaration",id,new KnowledgeGraph.Target(KnowledgeGraph.TargetState.RESOLVED,List.of(declaration),Optional.empty()),List.of(artifact.identity()),Optional.empty()));
            }
        }}
        linkPolyglotSymbols(sites,byLangAndSymbol,byDocAndSymbol,byDirAndSymbol,byStemAndSymbol,docImports,edges,evidence,context,cancelled);
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
    private static void linkPolyglotSymbols(List<SiteEntry> sites,Map<String,List<DeclEntry>> byLangAndSymbol,Map<String,List<DeclEntry>> byDocAndSymbol,
            Map<String,List<DeclEntry>> byDirAndSymbol,Map<String,List<DeclEntry>> byStemAndSymbol,Map<String,Set<String>> docImports,
            Map<String,KnowledgeGraph.Edge> edges,Map<String,GraphEvidence> evidence,String context,BooleanSupplier cancelled)throws IOException {
        for(var site:sites) {
            if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("SYNTAX_CANCELLED");
            var matchedIds=new TreeSet<String>();
            if(site.category().equals("IMPORT")) {
                var siteTokens=tokens(site.name()+" "+site.written());
                for(String tok:siteTokens) {
                    if(KEYWORDS.contains(tok))continue;
                    var candidates=byLangAndSymbol.getOrDefault(site.langGroup()+"|"+tok,List.of());
                    var external=candidates.stream().filter(d->!d.document().equals(site.document())).toList();
                    if(external.size()==1)matchedIds.add(external.getFirst().id());
                    else if(external.size()>1) {
                        var pathFiltered=external.stream().filter(d->!d.fileStem().isEmpty()&&siteTokens.contains(d.fileStem())).toList();
                        for(var d:(pathFiltered.isEmpty()?external:pathFiltered))matchedIds.add(d.id());
                    }
                }
            } else if(site.category().equals("CALL")) {
                String expr=site.written().isEmpty()?site.name():site.written();
                int openParen=expr.indexOf('(');if(openParen>=0)expr=expr.substring(0,openParen).trim();
                if(expr.startsWith("new "))expr=expr.substring(4).trim();
                int lastDot=Math.max(expr.lastIndexOf('.'),expr.lastIndexOf(':'));
                String qualifier=lastDot>0?expr.substring(0,lastDot).trim():"";
                int prevDot=Math.max(qualifier.lastIndexOf('.'),qualifier.lastIndexOf(':'));
                if(prevDot>=0)qualifier=qualifier.substring(prevDot+1).trim();
                String callee=lastDot>=0?expr.substring(lastDot+1).trim():expr.trim();
                if(callee.matches("[A-Za-z_][A-Za-z0-9_]*")&&!KEYWORDS.contains(callee)) {
                    if(qualifier.isEmpty()||qualifier.equals("self")||qualifier.equals("this")) {
                        for(var d:byDocAndSymbol.getOrDefault(site.document()+"|"+callee,List.of()))matchedIds.add(d.id());
                    }
                    if(matchedIds.isEmpty()&&!qualifier.isEmpty()) {
                        for(var d:byStemAndSymbol.getOrDefault(site.langGroup()+"|"+qualifier+"|"+callee,List.of()))matchedIds.add(d.id());
                    }
                    if(matchedIds.isEmpty()) {
                        for(var d:byDirAndSymbol.getOrDefault(site.langGroup()+"|"+site.dirPath()+"|"+callee,List.of()))matchedIds.add(d.id());
                    }
                    if(matchedIds.isEmpty()) {
                        var imported=docImports.getOrDefault(site.document(),Set.of());
                        var repoCandidates=byLangAndSymbol.getOrDefault(site.langGroup()+"|"+callee,List.of());
                        if(imported.contains(callee)||(!qualifier.isEmpty()&&imported.contains(qualifier))) {
                            var stemFiltered=repoCandidates.stream().filter(d->!d.fileStem().isEmpty()&&imported.contains(d.fileStem())).toList();
                            for(var d:(stemFiltered.isEmpty()?repoCandidates:stemFiltered))matchedIds.add(d.id());
                        } else if(repoCandidates.size()==1) {
                            matchedIds.add(repoCandidates.getFirst().id());
                        }
                    }
                }
            } else if(site.category().equals("HERITAGE")) {
                for(String tok:tokens(site.name()+" "+site.written())) {
                    if(KEYWORDS.contains(tok))continue;
                    var local=byDocAndSymbol.getOrDefault(site.document()+"|"+tok,List.of());
                    if(!local.isEmpty()){for(var d:local)matchedIds.add(d.id());continue;}
                    var dirLocal=byDirAndSymbol.getOrDefault(site.langGroup()+"|"+site.dirPath()+"|"+tok,List.of());
                    if(!dirLocal.isEmpty()){for(var d:dirLocal)matchedIds.add(d.id());continue;}
                    for(var d:byLangAndSymbol.getOrDefault(site.langGroup()+"|"+tok,List.of()))matchedIds.add(d.id());
                }
            }
            if(!matchedIds.isEmpty()) {
                var targets=List.copyOf(matchedIds);
                String edgeKind=switch(site.category()){case "IMPORT"->"syntax.imports-declaration";case "CALL"->"syntax.calls-declaration";default->"syntax.inherits-declaration";};
                var proof=GraphEvidence.capture("polyglot.static-symbol-link",Map.of("site",site.siteId(),"category",site.category(),"language",site.language(),
                        "written",site.written(),"targets",targets,"sourceEvidence",site.proof(),"trust","STATIC_SYNTAX_DERIVED","runtimeObserved",false));
                evidence.putIfAbsent(proof.identity().value(),proof);
                String edgeId=KnowledgeGraph.derivedIdentity("polyglot-static-symbol-link",List.of(site.siteId(),edgeKind,targets));
                var targetState=targets.size()==1?KnowledgeGraph.TargetState.RESOLVED:KnowledgeGraph.TargetState.CANDIDATES;
                edges.putIfAbsent(context+"|"+edgeId,new KnowledgeGraph.Edge(edgeId,context,edgeKind,site.siteId(),new KnowledgeGraph.Target(targetState,targets,Optional.empty()),List.of(proof.identity()),Optional.empty()));
            }
        }
    }
    private static String langGroup(String language){return Set.of("javascript","typescript","tsx").contains(language)?"js-ts":language;}
    private static String declSymbol(String name,String written) {
        String candidate=name==null?"":name.trim();
        if(candidate.matches("[A-Za-z_][A-Za-z0-9_]*"))return candidate;
        for(String tok:tokens(written))if(!KEYWORDS.contains(tok))return tok;
        return "";
    }
    private static List<String> tokens(String text) {
        if(text==null||text.isEmpty())return List.of();
        var list=new ArrayList<String>();int start=-1;
        for(int i=0;i<=text.length();i++) {
            char c=i<text.length()?text.charAt(i):' ';
            if((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9'&&start>=0)||c=='_') {
                if(start<0)start=i;
            } else if(start>=0) {
                list.add(text.substring(start,i));start=-1;
            }
        }
        return list;
    }
    private static void addDecl(Map<String,List<DeclEntry>> index,String key,DeclEntry entry) {
        var list=index.computeIfAbsent(key,k->new ArrayList<>());
        if(list.size()<64)list.add(entry);
    }
    private static String declaration(String document,String slot){return KnowledgeGraph.derivedIdentity("syntax-declaration",List.of(document,slot));}
    private static void node(Map<String,KnowledgeGraph.Node> nodes,String context,String id,String kind,ContentDigest support){
        var old=nodes.get(context+"|"+id);if(old!=null&&!old.kind().equals(kind))throw new IllegalArgumentException("SYNTAX_NODE_CONFLICT");
        var supports=new TreeSet<ContentDigest>();if(old!=null)supports.addAll(old.evidence());supports.add(support);nodes.put(context+"|"+id,new KnowledgeGraph.Node(id,context,kind,List.copyOf(supports)));
    }
}
