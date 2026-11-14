package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.FrontendRequest;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.polyglot.LanguageSemanticRequest;
import java.io.*;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;

/** Interprets existing producer envelopes. An authenticated graph is not a coverage certificate. */
final class EvolutionInput {
    private static final Set<String> SOURCE_BINDINGS=Set.of("java.source-plan","java.source-decoding",
            "ingestion.source-input","ingestion.build-model","readiness.plan");
    private static final ContentDigest EMPTY_REACTOR=CanonicalJson.sha256(List.of());
    private static final ContentDigest PLACEHOLDER_TOOL=ContentDigest.sha256Utf8("analyzer-component");
    private static final ContentDigest PLACEHOLDER_RULES=ContentDigest.sha256Utf8("rules-component");
    private static final ContentDigest GRAPH_SCHEMA=KnowledgeGraph.schemaComponent().contentDigest();
    private static final Set<String> JAVA_DEPENDENCIES=Set.copyOf(FrontendRequest.CATEGORIES.stream()
            .filter(s->!Set.of("declares","has-parameter").contains(s)).map(s->"java."+s).toList());

    final EvolutionRun run;
    final String name;
    final ContentDigest graph;
    EvolutionSnapshot snapshot;
    EvolutionSnapshot.Header header;
    boolean readable,foreign,lineage,expectedCensus;
    private boolean foundBuildModel,openBuildModel;
    String disposition="MISSING";

    EvolutionInput(EvolutionRun run,String name,ContentDigest graph){this.run=run;this.name=name;this.graph=graph;}
    void failed(String reason){disposition=reason;readable=false;}

    void read(EvolutionSnapshot.Reader reader)throws IOException {
        snapshot=reader.open(graph,run.cancelled);var candidate=snapshot.header();
        if(!candidate.graph().equals(graph))throw corrupt();
        header=candidate;
        if(!KnowledgeGraph.SCHEMA.equals(header.schema()))throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNSUPPORTED);
        scan(cursor->snapshot.evidence(run.size,cursor),header.evidence(),run.size,run.cancelled,this::evidenceRow);
        expectedCensus=foundBuildModel&&!openBuildModel;
        verifySourceBytes();
        scan(cursor->snapshot.contexts(run.size,cursor),header.contexts(),run.size,run.cancelled,this::context);
        addExpected();
        readable=true;disposition="AVAILABLE";
    }

    private void verifySourceBytes()throws IOException {
        var rows=new Rows(run.index,"source-bytes",name+"|");
        for(var item=rows.next();item!=null;item=rows.next()){
            EvolutionSnapshot.check(run.cancelled);var value=item.value();
            var content=run.index.get("source-content",name+"|"+text(value,"content")).orElseThrow(EvolutionInput::corrupt);
            if(!Objects.equals(value.get("digest"),content.get("digest"))||!Objects.equals(value.get("digest"),value.get("documentDigest")))throw corrupt();
        }
    }

    private void evidenceRow(GraphEvidence evidence)throws IOException {
        unique(run.index,"seen-evidence",name+"|"+evidence.identity().value(),row("seen",true));
        switch(evidence.family()) {
            case "frontend.result-index" -> frontendIndex(evidence);
            case "build.module" -> {
                var module=payload(evidence);var descriptor=obj(module.get("descriptor"));
                var id=text(descriptor,"identity");
                if(!run.request.repository().value().equals(text(descriptor,"repository")))return;
                for(var raw:list(obj(module.get("sourcePlan")).get("sourceSets"))) {
                    var set=obj(raw);String role=text(set,"semanticRole"),setName=text(set,"name");
                    var value=row("module",id,"path",text(descriptor,"path"),"role",role,"name",setName,"evidence",evidence.identity());
                    String key=name+"|"+hash("evolution.expected",List.of(id,role,setName,evidence.identity())).value();
                    unique(run.index,"expected",key,value);
                }
            }
            case "polyglot.build-component" -> {
                var raw=payload(evidence);
                unique(run.index,"components",name+"|"+evidence.identity().value(),row("module",text(raw,"id"),
                        "root",text(raw,"root"),"path",text(raw,"directory"),"languages",list(raw.get("languages")),"evidence",evidence.identity()));
            }
            case "build.model" -> {
                var counts=new int[2];
                GraphJson.streamObject(new StringReader(evidence.canonicalJson()),
                        new GraphJson.StreamLimits(Math.max(1,evidence.canonicalJson().length()),128,Long.MAX_VALUE),
                        (field,member)->{
                            if(field.equals("issues")||field.equals("gaps")){
                                counts[0]++;member.array(ignored->{EvolutionSnapshot.check(run.cancelled);counts[1]=1;});
                            } else member.skip();
                        });
                foundBuildModel=true;openBuildModel|=counts[0]!=2||counts[1]!=0;
            }
            case "ingestion.unit" -> {
                var raw=payload(evidence);var set=obj(raw.get("sourceSet"));
                var id=hash("evolution.unit",List.of(text(set,"module"),text(set,"name"))).value();
                var previous=run.index.get("units",name+"|"+id);
                run.index.put("units",name+"|"+id,previous.isPresent()?row("status","CONFLICT"):
                        row("status",raw.get("status"),"evidence",evidence.identity()));
            }
            case "evidence.gap" -> {
                var raw=payload(evidence);var binding=obj(raw.get("context"));
                String owner=maybe(binding,"analysisIdentity");
                unique(run.index,"original-gaps",name+"|"+evidence.identity().value(),
                        row("side",name,"graph",graph,"context",owner,"evidence",evidence.identity(),"gap",raw));
            }
            case "source.content" -> {
                var raw=payload(evidence);
                byte[] bytes;
                try{bytes=Base64.getDecoder().decode(text(raw,"bytesBase64"));}
                catch(IllegalArgumentException invalid){throw corrupt();}
                if(bytes.length!=number(raw.get("size"))||!ContentDigest.sha256(bytes).value().equals(raw.get("contentDigest")))throw corrupt();
                unique(run.index,"source-content",name+"|"+evidence.identity().value(),row("digest",raw.get("contentDigest")));
            }
            case "source.bytes" -> {
                var raw=payload(evidence);var doc=obj(raw.get("document"));
                unique(run.index,"source-bytes",name+"|"+evidence.identity().value(),row("document",text(doc,"identity"),
                        "digest",text(raw,"contentDigest"),"documentDigest",text(doc,"contentDigest"),"content",text(raw,"contentEvidence")));
            }
            case "java.type-shape","java.member-shape" -> {
                var shape=payload(evidence);String id=text(shape,evidence.family().equals("java.type-shape")?"type":"member");
                unique(run.index,"shapes",name+"|"+evidence.identity().value(),row("id",id,"shape",shape));
            }
            default -> { }
        }
    }

    private void frontendIndex(GraphEvidence evidence)throws IOException {
        // Index arrays may be much larger than a page; stream their members to the request's scratch space.
        var meta=new TreeMap<String,Object>();
        String prefix=name+"|"+evidence.identity().value()+"|";
        long[] documents={0};boolean[] processed={true};
        GraphJson.streamObject(new StringReader(evidence.canonicalJson()),
                new GraphJson.StreamLimits(Math.max(1,evidence.canonicalJson().length()),128,Long.MAX_VALUE),
                (field,member)->{
                    switch(field) {
                        case "analysis","frontend","state","coverage","indexSchema" -> meta.put(field,member.read());
                        case "sources" -> member.array(raw->{
                            var source=obj(raw);String id=text(source,"document");
                            unique(run.index,"processed",prefix+id,source);
                            documents[0]++;processed[0]&="PROCESSED".equals(text(source,"state"));
                        });
                        case "typeDeclarations","memberDeclarations" -> member.array(raw->{
                            String id=GraphJson.string(raw);
                            unique(run.index,"context-shapes",prefix+id,row("evidence",id));
                        });
                        case "declarations","occurrences","observations","derivedRelationships" -> member.array(raw->{
                            EvolutionSnapshot.check(run.cancelled);var id=GraphJson.string(raw);
                            run.index.put("frontend-members",prefix+field+"|"+id,row("present",true));
                        });
                        case "types","annotations","diagnostics","constructorCandidates","httpClientCalls" -> member.skip();
                        default -> member.skip();
                    }
                });
        var analysis=new AnalysisIdentity(text(meta,"analysis"));
        meta.put("evidence",evidence.identity());meta.put("sourceCount",documents[0]);meta.put("processed",processed[0]);
        var key=name+"|"+analysis.value();
        if(run.index.get("frontends",key).isPresent())run.index.put("frontends",key,row("conflict",true));
        else run.index.put("frontends",key,meta);
    }

    private void context(EvolutionSnapshot.Partition partition)throws IOException {
        var context=partition.context();
        if(!header.snapshot().equals(context.snapshot()))throw corrupt();
        var evidence=evidence(context.evidence());String family=evidence.family();
        var details=switch(family) {
            case "repository.snapshot" -> repository(context,evidence);
            case "analysis.manifest" -> javaContext(context,evidence);
            case "polyglot.context" -> polyglot(context,evidence);
            case "repository.inventory-snapshot" -> row("scope",scope(family,run.request.repository()),"selected",true,
                    "available",true,"repository",run.request.repository());
            case "spring.context" -> spring(context,evidence);
            default -> row("scope",scope(family,context.identity()),"selected",false,"available",true);
        };
        var data=new TreeMap<>(details);data.put("side",name);data.put("graph",graph);
        data.put("context",context.identity());data.put("family",family);data.put("evidence",evidence.identity());
        data.put("analysis",context.analysis());data.put("nodes",partition.nodes());data.put("edges",partition.edges());
        unique(run.index,"context-id",name+"|"+context.identity(),row("analysis",context.analysis()));
        unique(run.index,"scopes",name+"|"+text(data,"scope")+"|"+context.identity(),data);
        if(family.equals("analysis.manifest"))run.index.put("realized",name+"|"+text(data,"scope"),row("present",true));
    }

    private Map<String,Object> repository(KnowledgeGraph.Context context,GraphEvidence evidence)throws IOException {
        var raw=new TreeMap<String,Object>();
        try(var digest=new ArrayDigest()) {
            GraphJson.streamObject(new StringReader(evidence.canonicalJson()),
                    new GraphJson.StreamLimits(Math.max(1,evidence.canonicalJson().length()),128,Long.MAX_VALUE),
                    (field,member)->{
                        switch(field) {
                            case "identity","repository","contentDigest" -> raw.put(field,member.read());
                            case "files" -> member.array(file->{
                                EvolutionSnapshot.check(run.cancelled);digest.add(file);var value=obj(file);
                                unique(run.index,"inventory",name+"|"+hash("evolution.source-path",text(value,"path")).value(),value);
                            });
                            default -> member.skip();
                        }
                    });
            if(!digest.finish().value().equals(raw.get("contentDigest")))throw corrupt();
        }
        var repository=new RepositoryIdentity(text(raw,"repository"));
        if(!context.identity().equals(header.snapshot().value())||!boundSnapshot(raw,repository))throw corrupt();
        bindRepository(repository);
        return row("scope",scope(evidence.family(),repository),"selected",true,"available",true,
                "repository",repository);
    }

    private Map<String,Object> javaContext(KnowledgeGraph.Context context,GraphEvidence evidence)throws IOException {
        var raw=payload(evidence);var s=obj(raw.get("snapshot"));
        var repo=new RepositoryIdentity(text(s,"repository"));
        if(!boundSnapshot(s,repo)||!context.identity().equals(text(raw,"identity"))
                ||context.analysis().filter(id->id.value().equals(context.identity())).isEmpty())throw corrupt();
        bindRepository(repo);
        var configuration=obj(raw.get("configuration"));var values=obj(configuration.get("values"));
        var config=ConfigurationIdentity.from(version(configuration.get("schema")),stringMap(values));
        if(!config.value().equals(text(configuration,"identity")))throw corrupt();
        var identity=AnalysisIdentity.fromCanonicalManifestInputs(CanonicalJson.write(row("analyzer",raw.get("analyzer"),
                "classpath",raw.get("classpath"),"configurationIdentity",config,"graphSchema",raw.get("graphSchema"),
                "manifestVersion",raw.get("manifestVersion"),"modules",raw.get("modules"),"ruleSet",raw.get("ruleSet"),
                "snapshotContentDigest",text(s,"contentDigest"),"snapshotIdentity",header.snapshot())));
        if(!identity.value().equals(context.identity()))throw corrupt();
        var module=maybe(values,"java.module");var role=maybe(values,"java.source-set");
        var setName=maybe(values,"ingestion.source-set-name");
        if(setName==null&&role!=null)setName=role.toLowerCase(Locale.ROOT);
        String path=null;
        for(var item:list(raw.get("modules"))) {
            var m=obj(item);String id=text(m,"identity"),p=text(m,"path");
            if(!repo.value().equals(text(m,"repository"))||!ModuleIdentity.from(repo,p).value().equals(id))throw corrupt();
            if(id.equals(module))path=p;
        }
        boolean scoped=module!=null&&path!=null&&role!=null;
        var profile=new TreeMap<>(values);SOURCE_BINDINGS.forEach(profile::remove);
        // An exact binary list proves its ordering. Reactor source slots are not in that list; retain the
        // captured classpath/slot fingerprints unless the producer explicitly reports an empty reactor.
        if(EMPTY_REACTOR.value().equals(profile.get("java.reactor-sources")))profile.remove("java.classpath-manifest");
        var frontend=run.index.get("frontends",name+"|"+context.identity()).orElse(Map.of());
        var tool=new ContentDigest(text(obj(raw.get("analyzer")),"contentDigest"));
        var rules=new ContentDigest(text(obj(raw.get("ruleSet")),"contentDigest"));
        var graphSchema=new ContentDigest(text(obj(raw.get("graphSchema")),"contentDigest"));
        var platform=maybe(values,"java.platform.symbol-digest");var classpath=list(raw.get("classpath"));
        boolean knownPlatform=platform!=null&&classpath.stream().map(EvolutionRows::obj)
                .anyMatch(cp->"JDK_MODULE".equals(cp.get("kind"))&&platform.equals(cp.get("contentDigest")));
        String variant=maybe(values,"readiness.context-variant");
        String root=maybe(values,"readiness.build-root");
        var contextProfile=row("tool",raw.get("analyzer"),"rules",raw.get("ruleSet"),"classpath",classpath,
                "schema",List.of(raw.get("graphSchema"),raw.get("manifestVersion"),configuration.get("schema")),
                "platform",platform,"platformKnown",knownPlatform,"provider",frontend.get("frontend"),
                "toolKnown",!tool.equals(PLACEHOLDER_TOOL)&&frontend.get("frontend")!=null,
                "rulesKnown",!rules.equals(PLACEHOLDER_RULES),"schemaKnown",graphSchema.equals(GRAPH_SCHEMA),"configuration",profile);
        return row("scope",scoped?javaScope(repo,module,role,setName,root,variant):scope("unscoped-java",context.identity()),
                "selected",role==null||selected(role,setName),"available",scoped,"module",module,"path",path,
                "sourceSet",role,"name",setName,"root",root,"variant",variant,"language","java","repository",repo,
                "sourcePlan",values.get("java.source-plan"),"resolutionMode",values.get(FrontendRequest.RESOLUTION_MODE),"profile",contextProfile);
    }

    private Map<String,Object> polyglot(KnowledgeGraph.Context context,GraphEvidence evidence)throws IOException {
        var raw=payload(evidence);var q=LanguageSemanticRequest.from(raw.get("request"));
        var fields=List.of(q.language(),q.dialect(),q.root(),q.module(),q.sourceSet(),q.variant());
        if(!q.scope().value().equals(context.identity()))throw corrupt();
        return row("scope",scope("polyglot.context",List.of(run.request.repository(),fields)),"selected",selected(q.sourceSet(),q.sourceSet()),
                "available",true,"language",q.language(),"dialect",q.dialect(),"root",q.root(),"module",q.module(),
                "sourceSet",q.sourceSet(),"variant",q.variant(),"provider",q.provider(),"tool",q.tool(),"trust",raw.get("trust"));
    }

    private Map<String,Object> spring(KnowledgeGraph.Context context,GraphEvidence evidence)throws IOException {
        var raw=payload(evidence);var analysis=new AnalysisIdentity(text(raw,"analysis"));
        if(context.analysis().filter(analysis::equals).isEmpty())throw corrupt();
        return row("scope",scope("spring.context",context.identity()),"selected",false,"available",true,
                "parentAnalysis",analysis,"regionInput",raw.get("space"));
    }

    private void addExpected()throws IOException {
        var rows=new Rows(run.index,"expected",name+"|");
        for(var e=rows.next();e!=null;e=rows.next()) {
            EvolutionSnapshot.check(run.cancelled);var value=e.value();
            String role=text(value,"role"),setName=text(value,"name"),module=text(value,"module");
            String scope=javaScope(run.request.repository(),module,role,setName,null,null);
            if(run.index.get("realized",name+"|"+scope).isPresent())continue;
            String id=hash("evolution.expected-context",List.of(graph,scope,value.get("evidence"))).value();
            var unit=run.index.get("units",name+"|"+hash("evolution.unit",List.of(module,setName)).value()).orElse(Map.of());
            unique(run.index,"scopes",name+"|"+scope+"|"+id,row("side",name,"graph",graph,"context",id,
                    "scope",scope,"selected",selected(role,setName),"available",false,"expectedOnly",true,
                    "family","analysis.manifest","evidence",value.get("evidence"),"module",module,"path",value.get("path"),
                    "sourceSet",role,"name",setName,"language","java","ingestionStatus",unit.get("status"),"ingestionEvidence",unit.get("evidence")));
        }
        var components=new Rows(run.index,"components",name+"|");
        for(var e=components.next();e!=null;e=components.next()){
            EvolutionSnapshot.check(run.cancelled);var value=e.value();
            String scope=scope("build-component",List.of(run.request.repository(),value.get("root"),value.get("module"),value.get("path")));
            var id=hash("evolution.build-component",List.of(graph,scope,value.get("evidence"))).value();
            unique(run.index,"scopes",name+"|"+scope+"|"+id,row("side",name,"graph",graph,"context",id,
                    "scope",scope,"selected",false,"available",true,"family","polyglot.build-component",
                    "root",value.get("root"),"module",value.get("module"),"path",value.get("path"),"languages",value.get("languages"),"evidence",value.get("evidence")));
        }
    }

    record Coverage(boolean closed,List<ContentDigest> references,boolean readable) {}
    Coverage coverage(Map<String,Object> context)throws IOException {
        String id=text(context,"context"),scope=text(context,"scope");
        var found=run.index.get("frontends",name+"|"+id).orElse(Map.of());
        var manifest=new ContentDigest(text(context,"evidence"));
        var evidenceId=maybe(found,"evidence");
        boolean closed="frontend.result-index:m8.4-v2".equals(found.get("indexSchema"))&&"COMPLETED".equals(found.get("state"))
                &&Boolean.TRUE.equals(found.get("processed"))&&context.get("sourcePlan")!=null
                &&!FrontendRequest.SOURCE_RECOVERY.equals(context.get("resolutionMode"));
        if(closed&&!run.request.selection().relationshipKinds().isEmpty())closed=categoryCoverage(list(found.get("coverage")));
        var refs=new ArrayList<ContentDigest>();refs.add(manifest);
        if(evidenceId!=null)refs.add(new ContentDigest(evidenceId));
        final boolean[] complete={closed};
        long[] sourceCount={0};
        String prefix=name+"|"+evidenceId+"|";
        scan(cursor->snapshot.nodes(id,run.size,cursor),number(context.get("nodes")),run.size,run.cancelled,node->{
            if(!id.equals(node.context()))throw corrupt();
            unique(run.index,"seen-nodes",name+"|"+id+"|"+node.identity(),row("seen",true));
            if(node.kind().equals("SOURCE_DOCUMENT")) {
                for(var ref:node.evidence()){
                    var proof=evidence(ref);if(!proof.family().equals("source.document"))continue;
                    var document=payload(proof);
                    if(!node.identity().equals(text(document,"identity")))throw corrupt();
                    var repository=new RepositoryIdentity(text(document,"repository"));
                    if(!run.request.repository().equals(repository)&&!foreign)throw corrupt();
                    String path=text(document,"path");
                    if(!SourceDocumentIdentity.from(repository,path).value().equals(node.identity()))throw corrupt();
                    var entry=run.index.get("inventory",name+"|"+hash("evolution.source-path",path).value());
                    if(entry.isPresent()&&!Objects.equals(entry.orElseThrow().get("contentDigest"),document.get("contentDigest")))throw corrupt();
                    unique(run.index,"documents",name+"|"+id+"|"+node.identity(),document);
                    if(context.get("module").equals(document.get("module"))&&roleMatches(text(context,"sourceSet"),text(document,"classification"))){
                        sourceCount[0]++;
                        var outcome=run.index.get("processed",prefix+node.identity());
                        if(outcome.isEmpty())complete[0]=false;
                        else {
                            var o=evidence(new ContentDigest(text(outcome.orElseThrow(),"evidence")));
                            if(!o.family().equals("java.source-outcome")||!node.identity().equals(text(payload(o),"document"))
                                    ||!outcome.orElseThrow().get("state").equals(payload(o).get("state")))throw corrupt();
                        }
                        if(text(document,"classification").startsWith("GENERATED"))complete[0]=false;
                    }
                }
            }
            if(node.kind().equals("CAPABILITY_GAP")||node.kind().equals("OBSERVATION")) {
                if(node.kind().equals("CAPABILITY_GAP"))complete[0]=false;
                for(var ref:node.evidence()){
                    var proof=evidence(ref);
                    if(proof.family().equals("evidence.gap")) {
                        complete[0]=false;
                    } else if(proof.family().equals("java.observation")&&!run.request.selection().relationshipKinds().isEmpty()) {
                        var raw=payload(proof);
                        if(!frontendMember(id,"observations",ref))complete[0]=false;
                        if(relevantCategory(category(raw.get("category")))&&(!"RESOLVED".equals(raw.get("attribution"))
                                ||!"VERIFIED".equals(raw.get("provenance"))||!"VERIFIED".equals(raw.get("origin"))))complete[0]=false;
                    }
                }
            }
        });
        if(!found.containsKey("sourceCount")||sourceCount[0]!=number(found.get("sourceCount")))complete[0]=false;
        // Rebuild the registered source-plan digest from the same ordered document records, not a count.
        if(complete[0])try(var digest=new ArrayDigest()){
            var docs=new Rows(run.index,"documents",name+"|"+id+"|");
            for(var entry=docs.next();entry!=null;entry=docs.next()){
                EvolutionSnapshot.check(run.cancelled);
                var doc=entry.value();if(context.get("module").equals(doc.get("module"))
                        &&roleMatches(text(context,"sourceSet"),text(doc,"classification")))digest.add(doc);
            }
            complete[0]=digest.finish().value().equals(context.get("sourcePlan"));
        }
        run.index.put("coverage",name+"|"+scope,row("closed",complete[0],"sources",sourceCount[0],"references",refs));
        return new Coverage(complete[0],List.copyOf(refs),true);
    }

    private boolean categoryCoverage(List<Object> values) {
        var required=new HashSet<String>();
        for(var kind:run.request.selection().relationshipKinds()) {
            if(kind.equals("graph.depends-on"))required.addAll(JAVA_DEPENDENCIES);
            else if(kind.startsWith("java."))required.add(kind);
            else return false;
        }
        for(var value:values) {
            var row=obj(value);String kind=category(row.get("category"));
            if(required.remove(kind)&&(!"IMPLEMENTED".equals(row.get("support"))||number(row.get("unmappable"))!=0))return false;
        }
        return required.isEmpty();
    }

    private boolean relevantCategory(String category) {
        return run.request.selection().relationshipKinds().contains(category)
                ||run.request.selection().relationshipKinds().contains("graph.depends-on")&&JAVA_DEPENDENCIES.contains(category);
    }
    static String category(Object value){return value instanceof String s?s:text(obj(value),"value");}
    Optional<AnalysisIdentity> analysis(String context)throws IOException {
        if(context==null)return Optional.empty();
        return run.index.get("context-id",name+"|"+context).map(row->maybe(row,"analysis")).map(AnalysisIdentity::new);
    }
    void publishOriginalGaps()throws IOException {
        var rows=new Rows(run.index,"original-gaps",name+"|");
        for(var row=rows.next();row!=null;row=rows.next()){
            EvolutionSnapshot.check(run.cancelled);run.put(SnapshotComparison.Section.GAPS,row.key(),row.value());
        }
    }
    boolean frontendMember(String context,String family,ContentDigest ref)throws IOException {
        var frontend=run.index.get("frontends",name+"|"+context).orElse(Map.of());
        var id=maybe(frontend,"evidence");
        return id!=null&&run.index.get("frontend-members",name+"|"+id+"|"+family+"|"+ref.value()).isPresent();
    }
    GraphEvidence evidence(ContentDigest ref)throws IOException {
        var value=snapshot.evidence(ref).orElseThrow(EvolutionInput::corrupt);
        if(!value.identity().equals(ref))throw corrupt();return value;
    }
    static Map<String,Object> payload(GraphEvidence evidence){return obj(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));}
    private boolean boundSnapshot(Map<String,Object> value,RepositoryIdentity repository) {
        return header.snapshot().value().equals(text(value,"identity"))&&header.snapshot().equals(
                SnapshotIdentity.from(repository,new ContentDigest(text(value,"contentDigest"))));
    }
    private void bindRepository(RepositoryIdentity repository){lineage=true;foreign|=!run.request.repository().equals(repository);}
    private boolean selected(String role,String set){var wanted=run.request.selection().sourceSets();return wanted.isEmpty()||wanted.contains(role)||wanted.contains(set);}
    private static boolean roleMatches(String role,String source){return role.equals(source)||("GENERATED_"+role).equals(source);}
    private static String scope(String family,Object value){return hash("architecture.evolution.context-key:v1",List.of(family,value)).value();}
    private static String javaScope(RepositoryIdentity repository,String module,String role,String set,String root,String variant){
        return scope("analysis.manifest",Arrays.asList(repository,module,role,set,root,variant));
    }
    private static com.evolution.analysis.contract.common.VersionedIdentifier version(Object value){
        var v=obj(value);return new com.evolution.analysis.contract.common.VersionedIdentifier(text(v,"id"),text(v,"version"));
    }
    private static Map<String,String> stringMap(Map<String,Object> values){
        var result=new TreeMap<String,String>();values.forEach((k,v)->result.put(k,GraphJson.string(v)));return result;
    }
    static EvolutionSnapshot.ReadFailure corrupt(){return new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.CORRUPT);}
}
