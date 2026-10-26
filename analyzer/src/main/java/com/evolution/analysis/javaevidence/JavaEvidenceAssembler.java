package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.ingestion.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Produces a derived, per-named-source-set view only from a validated compiler census. */
public final class JavaEvidenceAssembler {
    public static final class Prepared {
        private final RepositoryInputs base,inputs;private final UniversalBuildModel model;private final UniversalSourceIngestion.SourceSet sourceSet;
        private final UniversalSourceIngestion.Resolution resolution;private final JavaEvidenceRecord view,overlay;private final JavaEvidenceLedger.Published closure;private final JavaEvidenceGraph graph;
        private final List<JavaEvidenceAuthority.Verified> proofInputs;
        private final com.evolution.analysis.readiness.ReadinessPlan readiness;
        private Prepared(RepositoryInputs base,RepositoryInputs inputs,UniversalBuildModel model,UniversalSourceIngestion.SourceSet sourceSet,UniversalSourceIngestion.Resolution resolution,JavaEvidenceRecord view,JavaEvidenceRecord overlay,JavaEvidenceLedger.Published closure,JavaEvidenceGraph graph,List<JavaEvidenceAuthority.Verified> proofInputs,com.evolution.analysis.readiness.ReadinessPlan readiness){this.base=base;this.inputs=inputs;this.model=model;this.sourceSet=sourceSet;this.resolution=resolution;this.view=view;this.overlay=overlay;this.closure=closure;this.graph=graph;this.proofInputs=List.copyOf(proofInputs);this.readiness=Objects.requireNonNull(readiness);}
        public RepositoryInputs base(){return base;}public RepositoryInputs inputs(){return inputs;}public UniversalBuildModel model(){return model;}
        public JavaEvidenceRecord view(){return view;}public JavaEvidenceRecord overlay(){return overlay;}
        public List<com.evolution.analysis.evidence.CapabilityGapRecord> activeGaps(){return JavaEvidencePreAnalysis.activeGaps(closure);}
        public JavaEvidenceGraph graph(){return graph;}
        public com.evolution.analysis.readiness.ReadinessPlan readiness(){return readiness;}
        /** Attach a separately verified historical census without changing the semantic input view. */
        public Prepared withGapClosure(JavaEvidenceGapClosure closure)throws IOException{
            return new Prepared(base,inputs,model,sourceSet,resolution,view,overlay,this.closure,graph.withGapClosure(closure),proofInputs,readiness);
        }
        public ContentDigest policy(){return closure.projection().digest("policy");}
        public List<com.evolution.analysis.evidence.CapabilityGapRecord> invalidationGaps(){
            var result=new ArrayList<com.evolution.analysis.evidence.CapabilityGapRecord>();
            try{graph.requireCurrent();}catch(IOException missing){result.add(JavaEvidenceGaps.create(view.object("scope"),JavaEvidenceGaps.Reason.EVIDENCE_REVOKED,view.identity(),view.identity()));}
            for(var proof:proofInputs){boolean current;try{current=proof.current();}catch(IOException missing){current=false;}if(!current)result.add(JavaEvidenceGaps.create(view.object("scope"),JavaEvidenceGaps.Reason.EVIDENCE_REVOKED,view.identity(),proof.identity()));}
            resolution.correspondence().ifPresent(binding->{boolean current;try{current=binding.current();}catch(IOException missing){current=false;}if(!current)result.add(JavaEvidenceGaps.create(view.object("scope"),JavaEvidenceGaps.Reason.EVIDENCE_REVOKED,view.identity(),binding.table().content().digest()));});
            return List.copyOf(result);
        }
        public Map<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution> resolutions(){return Map.of(sourceSet,resolution);}
        public Map<String,String> options(){return Map.of("java.evidence-view",view.identity().value(),"java.generated-overlay",overlay.identity().value(),"java.base-inventory",view.text("baseInventory"),"java.base-snapshot",base.snapshot().identity().value(),"java.pre-analysis-closure",view.text("preAnalysisClosure"),"java.native-context",GraphJson.string(view.object("scope").get("context")));}
    }
    private final RepositoryContentStore content;private final JavaEvidenceTables tables;private final JavaEvidenceCodec codec=new JavaEvidenceCodec();
    public JavaEvidenceAssembler(RepositoryContentStore content,int rowCharacters){this.content=content;tables=new JavaEvidenceTables(content,rowCharacters);}
    public Prepared prepare(RepositoryInputs base,com.evolution.analysis.readiness.ReadinessPlan readiness,com.evolution.analysis.readiness.ReadinessPlan.Context context,UniversalBuildModel original,ModuleDescriptor module,JavaEvidenceRecord overlay,JavaEvidenceRecord manifest,
            PlatformInput platform,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,JavaResolutionPlanner.BinaryMaterializer materializer,JavaEvidenceLedger.Published preAnalysisClosure,
            int workerRows,int sourceBytes,BooleanSupplier cancelled)throws IOException{
        return prepare(base,readiness,context,original,module,overlay,manifest,platform,proofs,materializer,preAnalysisClosure,tables.write("java-evidence-input",Collections.emptyIterator(),cancelled),workerRows,sourceBytes,cancelled);
    }
    public Prepared prepare(RepositoryInputs base,com.evolution.analysis.readiness.ReadinessPlan readiness,com.evolution.analysis.readiness.ReadinessPlan.Context context,UniversalBuildModel original,ModuleDescriptor module,JavaEvidenceRecord overlay,JavaEvidenceRecord manifest,
            PlatformInput platform,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,JavaResolutionPlanner.BinaryMaterializer materializer,JavaEvidenceLedger.Published preAnalysisClosure,JavaEvidenceTables.Table correspondence,
            int workerRows,int sourceBytes,BooleanSupplier cancelled)throws IOException{
        if(workerRows<1||sourceBytes<1||sourceBytes==Integer.MAX_VALUE)throw new IllegalArgumentException("JAVA_INPUT_WORKER_BUDGET");
        JavaEvidenceScopes.require(readiness,context,module,overlay.object("scope"));
        var scope=overlay.object("scope");if(!base.snapshot().identity().value().equals(scope.get("baseSnapshot"))||!module.identity().value().equals(scope.get("module"))||!module.repository().equals(base.snapshot().repository())||!original.repositoryInputIdentity().equals(base.identity()))throw new IOException("JAVA_INPUT_BASE_SCOPE_MISMATCH");
        if(Boolean.TRUE.equals(manifest.fields().get("preview")))throw new IOException("JAVA_PREVIEW_ATTRIBUTION_PROVIDER_REQUIRED");
        var checkedOverlay=new JavaGeneratedOverlay(content,2_000_000).importOverlay(scope,JavaEvidenceRecord.handle(overlay.fields().get("outputCensus")),JavaEvidenceTables.Table.from(overlay.fields().get("lineages")),JavaEvidenceTables.Table.from(overlay.fields().get("outputs")),proofs,overlay.text("census").equals("SEALED"),workerRows,cancelled);
        if(!checkedOverlay.overlay().identity().equals(overlay.identity()))throw new IOException("JAVA_OVERLAY_REVALIDATION_FAILED");
        var preAnalysis=preAnalysisClosure.projection();JavaEvidenceLedger.kind(preAnalysis,"java-closure-projection");JavaGeneratedOverlay.sameScope(scope,preAnalysis);JavaEvidencePreAnalysis.requireFootprint(preAnalysisClosure,overlay,manifest,tables,cancelled);
        var resolution=new JavaResolutionPlanner(content,2_000_000).plan(manifest,overlay,platform,proofs,materializer,workerRows,cancelled);
        if(resolution.binaries().isEmpty())throw new IOException("JAVA_INPUT_EXACT_RESOLUTION_WITHHELD");
        var currentPolicy=preAnalysis.digest("policy");
        for(var proof:proofs.values())if(proof.record().fields().get("scope").equals(scope)&&!proof.record().digest("policy").equals(currentPolicy))throw new IOException("JAVA_INPUT_TRUST_POLICY_MISMATCH");
        var memberships=JavaEvidenceTables.Table.from(manifest.fields().get("sourceMembership"));var outputTable=JavaEvidenceTables.Table.from(overlay.fields().get("outputs"));
        if(memberships.rows()>workerRows||outputTable.rows()>workerRows)throw new IOException("JAVA_INPUT_WORKER_CAPACITY_REQUIRED");
        var generated=new HashMap<ContentDigest,JavaEvidenceRecord>();tables.read(outputTable,row->{if(row.text("category").equals("JAVA_SOURCE")&&row.text("disposition").equals("ACCEPTED"))generated.put(row.identity(),row);},cancelled);
        var resources=new ArrayList<JavaEvidenceRecord>();tables.read(outputTable,row->{if(row.text("category").equals("RESOURCE")&&row.text("disposition").equals("ACCEPTED"))resources.add(row);},cancelled);
        var handwritten=new HashMap<ContentDigest,SourceInput>();for(var input:base.files().values())if(input.document().path().endsWith(".java"))handwritten.put(CanonicalJson.sha256(input.document()),input);
        var seen=new HashSet<ContentDigest>();var sources=new TreeMap<String,SourceInput>();var sourceMaps=new ArrayList<JavaEvidenceRecord>();var membershipPayloads=new ArrayList<JavaEvidenceRecord>();
        var unavailableDocuments=new ArrayList<SourceDocument>();var decodingEvidence=new ArrayList<com.evolution.analysis.graph.GraphEvidence>();
        String namespace=namespace(base);String logical=GraphJson.string(scope.get("logicalScope")).substring(7);String namedSet=GraphJson.string(scope.get("sourceSet"));
        tables.read(memberships,row->{
            if(!row.text("role").equals("SOURCE_MEMBERSHIP"))throw new IOException("JAVA_MEMBERSHIP_ROW_ROLE");
            var payload=tables.read(JavaEvidenceRecord.handle(row.fields().get("content")));JavaEvidenceLedger.kind(payload,"java-source-membership");JavaGeneratedOverlay.sameScope(scope,payload);
            if(!payload.text("sourceSet").equals(namedSet)||payload.text("disposition").equals("UNKNOWN"))throw new IOException("JAVA_SOURCE_CENSUS_OPEN");
            var sourceRef=JavaEvidenceRecord.handle(payload.fields().get("sourceEvidence"));if(!content.verify(sourceRef)||!seen.add(sourceRef.digest()))throw new IOException("JAVA_MEMBERSHIP_DUPLICATE_OR_MISSING_REFERENCE");
            membershipPayloads.add(payload);if(payload.text("disposition").equals("EXCLUDED"))return;
            var bytesHandle=JavaEvidenceRecord.handle(payload.fields().get("content"));if(!content.verify(bytesHandle)||bytesHandle.size()>sourceBytes)throw new IOException("JAVA_SOURCE_WORKER_OR_CONTENT_REQUIRED");
            var origin=handwritten.get(sourceRef.digest());var output=generated.get(sourceRef.digest());if(origin==null&&output==null)throw new IOException("JAVA_SOURCE_NOT_IN_CAPTURED_CENSUS");
            if(origin!=null&&!origin.document().contentDigest().equals(bytesHandle.digest())||output!=null&&!JavaEvidenceRecord.handle(output.fields().get("content")).equals(bytesHandle))throw new IOException("JAVA_SOURCE_MEMBERSHIP_DIGEST_MISMATCH");
            String path=origin!=null?origin.document().path():namespace+"/"+logical+"/"+HexFormat.of().formatHex(JavaGeneratedOverlay.path(output.object("locator").get("path")).bytes())+".java";
            SourceClassification role;try{role=SourceClassification.valueOf(payload.text("role"));}catch(IllegalArgumentException unknown){throw new IOException("JAVA_SOURCE_ROLE_UNPROVEN");}
            if(origin!=null&&origin.document().classification()!=role)throw new IOException("JAVA_ORIGINAL_DOCUMENT_CLASSIFICATION_CHANGED");
            if(origin!=null&&!origin.document().module().equals(module.identity()))throw new IOException("JAVA_ORIGINAL_SOURCE_MODULE_CHANGED");
            if(output!=null&&!Set.of(SourceClassification.GENERATED_MAIN,SourceClassification.GENERATED_TEST).contains(role))throw new IOException("JAVA_GENERATED_CLASSIFICATION_REQUIRED");
            byte[] raw;try(var in=content.open(bytesHandle)){raw=in.readNBytes(sourceBytes+1);}if(raw.length!=bytesHandle.size())throw new IOException("JAVA_SOURCE_CHANGED");
            var doc=origin!=null?origin.document():SourceDocument.create(base.snapshot().repository(),module,path,bytesHandle.digest(),role);
            var decoding=new SourceInput.Decoding("source-decoding-v1",manifest.text("encoding"),SourceInput.EncodingOrigin.BUILD_DECLARATION,List.of(new SourceInput.Evidence("captured-compiler",manifest.identity())),SourceInput.Bom.detect(raw),SourceInput.LineEndings.from(""));
            if(sources.containsKey(path)||unavailableDocuments.stream().anyMatch(d->d.path().equals(path)))throw new IOException("JAVA_SOURCE_VIEW_COLLISION");
            decode(sources,unavailableDocuments,decodingEvidence,doc,raw,decoding);
            if(output!=null){var map=new LinkedHashMap<String,Object>();map.put("schema","java-source-map-row:m8.4-v1");map.put("scope",scope);map.put("locator",output.fields().get("locator"));map.put("content",bytesHandle);map.put("projectedPath",path);map.put("projectedDocument",doc.identity());map.put("role",role.name());map.put("lineage",output.fields().get("lineage"));map.put("supportingSources",List.of());sourceMaps.add(codec.create(map));}
        },cancelled);
        var expected=new HashSet<>(handwritten.keySet());expected.addAll(generated.keySet());if(!seen.equals(expected))throw new IOException("JAVA_SOURCE_CENSUS_INCOMPLETE");
        var roles=membershipPayloads.stream().filter(m->m.text("disposition").equals("INCLUDED")).map(m->m.text("role")).map(r->r.startsWith("GENERATED_")?r.substring(10):r).distinct().toList();
        if(roles.size()!=1||!Set.of("MAIN","TEST").contains(roles.getFirst()))throw new IOException("JAVA_NAMED_SOURCE_SET_ROLE_UNPROVEN");
        var role=SourceClassification.valueOf(roles.getFirst());var kind=namedSet.equals("main")?SourcePlanModel.Kind.MAIN:namedSet.equals("test")?SourcePlanModel.Kind.TEST:SourcePlanModel.Kind.CUSTOM;
        // Keep captured original non-Java/config documents; Java membership is exact for this context.
        for(var input:base.files().values())if(!input.document().path().endsWith(".java")&&input.document().module().equals(module.identity()))sources.put(input.document().path(),input);
        var resourceRoots=new LinkedHashSet<String>();var nativeResourceRoots=new LinkedHashMap<String,String>();var resourceOrders=new ArrayList<List<String>>();var binaryFiles=new ArrayList<SnapshotFile>();var binaryDocuments=new ArrayList<SourceDocument>();
        for(var resource:resources){
            var proof=proofs.get(resource.digest("rootProof"));if(proof==null)throw new IOException("JAVA_RESOURCE_OWNER_UNPROVEN");var mapping=JavaGeneratedResources.mapping(resource,proof,content,sourceBytes);
            String resourceRoot=namespace+"/"+logical+"/resources/"+mapping.rootKey();nativeResourceRoots.put(mapping.nativeRoot(),resourceRoot);resourceOrders.add(mapping.nativeOrder());String path=resourceRoot+"/"+mapping.relativePath();
            var bytesHandle=JavaEvidenceRecord.handle(resource.fields().get("content"));var document=SourceDocument.create(base.snapshot().repository(),module,path,bytesHandle.digest(),SourceClassification.OTHER);
            if(sources.containsKey(path)||binaryFiles.stream().anyMatch(f->f.path().equals(path)))throw new IOException("JAVA_RESOURCE_VIEW_COLLISION");
            if(JavaGeneratedResources.textConsumer(mapping.relativePath())){
                String encoding=mapping.encoding().orElseThrow(()->new IOException("JAVA_GENERATED_RESOURCE_ENCODING_REQUIRED"));if(bytesHandle.size()>sourceBytes)throw new IOException("JAVA_RESOURCE_WORKER_CAPACITY_REQUIRED");byte[] raw;try(var in=content.open(bytesHandle)){raw=in.readNBytes(sourceBytes+1);}if(raw.length!=bytesHandle.size())throw new IOException("JAVA_RESOURCE_CHANGED");
                var decoding=new SourceInput.Decoding("source-decoding-v1",encoding,SourceInput.EncodingOrigin.BUILD_DECLARATION,List.of(new SourceInput.Evidence("captured-generator-resource",proof.identity())),SourceInput.Bom.detect(raw),SourceInput.LineEndings.from(""));decode(sources,unavailableDocuments,decodingEvidence,document,raw,decoding);
            }else{binaryFiles.add(SnapshotFile.from(document));binaryDocuments.add(document);}
            var map=new LinkedHashMap<String,Object>();map.put("schema","java-source-map-row:m8.4-v1");map.put("scope",scope);map.put("locator",resource.fields().get("locator"));map.put("content",bytesHandle);map.put("projectedPath",path);map.put("projectedDocument",document.identity());map.put("role","UNCLASSIFIED");map.put("lineage",resource.fields().get("lineage"));map.put("supportingSources",List.of());sourceMaps.add(codec.create(map));
        }
        var files=new ArrayList<>(sources.values().stream().map(s->SnapshotFile.from(s.document())).toList());files.addAll(binaryFiles);var docs=new ArrayList<>(sources.values().stream().map(SourceInput::document).toList());docs.addAll(binaryDocuments);
        docs.addAll(unavailableDocuments);files.addAll(unavailableDocuments.stream().map(SnapshotFile::from).toList());
        var snapshot=RepositorySnapshot.create(base.snapshot().repository(),base.snapshot().revision(),base.snapshot().dirty(),files,docs);var inputs=new RepositoryInputs(snapshot,sources);
        var owner=original.modules().stream().filter(m->m.descriptor().identity().equals(module.identity())).findFirst().orElseThrow(()->new IOException("JAVA_BUILD_MODULE_UNAVAILABLE"));
        if(!resourceOrders.isEmpty()){
            var order=resourceOrders.getFirst();if(resourceOrders.stream().anyMatch(o->!o.equals(order)))throw new IOException("JAVA_RESOURCE_ORDER_CONFLICT");
            var declared=owner.sourcePlan().sourceSets().stream().flatMap(p->p.resourceRoots().stream()).flatMap(r->r.value().stream()).collect(java.util.stream.Collectors.toSet());
            for(String nativeRoot:order){String mapped=nativeResourceRoots.get(nativeRoot);if(mapped!=null)resourceRoots.add(mapped);else if(declared.contains(nativeRoot))resourceRoots.add(nativeRoot);else throw new IOException("JAVA_RESOURCE_ROOT_NOT_ACQUIRED");}
        }
        var plans=new ArrayList<SourcePlanModel.SourceSetPlan>();for(var plan:owner.sourcePlan().sourceSets()){
            boolean selected=plan.name().equals(namedSet);var roots=new ArrayList<SourcePlanModel.Setting>();if(selected){if(resources.isEmpty())roots.addAll(plan.resourceRoots());else for(String resourceRoot:resourceRoots)roots.add(setting("resource-root",resourceRoot,manifest));}plans.add(new SourcePlanModel.SourceSetPlan(module.identity(),plan.kind(),plan.name(),selected?role:plan.semanticRole(),selected?List.of(setting("source-root",".",manifest)):List.of(),roots,plan.outputDirectory(),plan.compilerSettings(),setting("syntax",manifest.text("sourceLanguage"),manifest),setting("target",manifest.text("bytecodeTarget"),manifest),setting("platform",Integer.toString(platform.release()),manifest),setting("encoding",manifest.text("encoding"),manifest),List.of(),plan.kind()==SourcePlanModel.Kind.CUSTOM&&plan.semanticRole()==SourceClassification.OTHER&&!selected?List.of(SourcePlanModel.Gap.UNRESOLVED_SOURCE_ROLE):List.of()));
        }
        if(plans.stream().noneMatch(p->p.name().equals(namedSet)))throw new IOException("JAVA_NAMED_SOURCE_SET_NOT_IN_MODEL");
        var modules=new ArrayList<UniversalBuildModel.Module>();modules.add(new UniversalBuildModel.Module(module,UniversalBuildModel.Tool.IMPORTED_SOURCE_PLAN,owner.buildFile(),owner.coordinate(),new SourcePlanModel(plans,owner.sourcePlan().plugins()),owner.dependencies(),owner.evidence()));
        // Keep binary-project owners addressable; their source sets are explicitly outside this view.
        for(var other:original.modules())if(!other.descriptor().identity().equals(module.identity())){
            var outside=other.sourcePlan().sourceSets().stream().map(p->new SourcePlanModel.SourceSetPlan(p.module(),p.kind(),p.name(),p.semanticRole(),List.of(),List.of(),p.outputDirectory(),p.compilerSettings(),p.syntaxLevel(),p.bytecodeTarget(),p.platformRelease(),p.encoding(),List.of(),p.kind()==SourcePlanModel.Kind.CUSTOM&&p.semanticRole()==SourceClassification.OTHER?List.of(SourcePlanModel.Gap.UNRESOLVED_SOURCE_ROLE):List.of())).toList();
            modules.add(new UniversalBuildModel.Module(other.descriptor(),other.tool(),other.buildFile(),other.coordinate(),new SourcePlanModel(outside,other.sourcePlan().plugins()),other.dependencies(),other.evidence()));
        }
        var build=new UniversalBuildModel(manifest.identity(),inputs.identity(),modules,List.of(),List.of(),List.of());
        var slots=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(manifest.fields().get("slots")),slots::add,cancelled);
        var nativeMappings=new JavaSourceCorrespondence(content).importTable(scope,correspondence,slots,List.copyOf(sources.values()),proofs,workerRows,cancelled);
        var correspondenceBinding=nativeMappings.size()==0?Optional.<JavaSourceCorrespondence.Verified>empty():Optional.of(nativeMappings);
        for(var binary:resolution.binaries().orElseThrow())if(binary.reactorModule().filter(module.identity()::equals).isPresent()&&nativeMappings.mappings().stream().noneMatch(m->m.endpoint().slot().equals(binary.nativeOrigin().orElseThrow().slot())))throw new IOException("JAVA_SOURCE_BINARY_CORRESPONDENCE_REQUIRED");
        if(nativeMappings.policy().isPresent()&&!nativeMappings.policy().orElseThrow().equals(currentPolicy))throw new IOException("JAVA_INPUT_TRUST_POLICY_MISMATCH");
        sourceMaps.sort(JavaEvidenceTables::compare);var sourceMap=tables.write("java-source-map-row",sourceMaps.iterator(),cancelled);
        var view=new LinkedHashMap<String,Object>();view.put("schema","java-semantic-input-view:m8.4-v1");view.put("scope",scope);view.put("baseInventory",scope.get("inventory"));view.put("overlay",overlay.identity());view.put("model",build.identity());view.put("sourcePlan",CanonicalJson.sha256(plans));view.put("resolution",manifest.identity());view.put("sourceMap",sourceMap.wire());view.put("correspondence",correspondence.wire());view.put("preAnalysisClosure",preAnalysis.identity());view.put("providers",List.of(JavaEvidenceGaps.PROVIDER));view.put("policy",proofs.get(manifest.digest("contextProof")).record().fields().get("policy"));view.put("derivedSnapshot",snapshot.identity());
        var viewRecord=codec.create(view);tables.put(viewRecord,cancelled);tables.put(preAnalysis,cancelled);tables.put(manifest,cancelled);tables.put(overlay,cancelled);
        put(CanonicalJson.write(build),cancelled);put(CanonicalJson.write(plans),cancelled);
        var graphEvidence=new ArrayList<com.evolution.analysis.graph.GraphEvidence>();
        graphEvidence.addAll(decodingEvidence);
        graphEvidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.base-snapshot",base.snapshot()));
        for(var item:Map.of("view",viewRecord,"overlay",overlay,"resolution",manifest,"closure-ledger",preAnalysisClosure.ledger(),"closure-projection",preAnalysis).entrySet())graphEvidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence."+item.getKey(),item.getValue().fields()));
        for(var item:Map.of("source-map",sourceMap,"lineage",JavaEvidenceTables.Table.from(overlay.fields().get("lineages")),"output",outputTable,"requirement",JavaEvidenceTables.Table.from(preAnalysisClosure.ledger().fields().get("requirements")),"decision",JavaEvidenceTables.Table.from(preAnalysisClosure.ledger().fields().get("decisions")),"current",JavaEvidenceTables.Table.from(preAnalysis.fields().get("current")),"resolution-slot",JavaEvidenceTables.Table.from(manifest.fields().get("slots")),"membership-input",memberships,"binding",JavaEvidenceTables.Table.from(preAnalysisClosure.ledger().fields().get("bindings"))).entrySet()){
            if(item.getValue().rows()>workerRows)throw new IOException("JAVA_GRAPH_EVIDENCE_WORKER_CAPACITY_REQUIRED");
            tables.read(item.getValue(),row->graphEvidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence."+item.getKey(),row.fields())),cancelled);
        }
        for(var membership:membershipPayloads)graphEvidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.source-membership",membership.fields()));
        if(nativeMappings.size()>0)graphEvidence.addAll(nativeMappings.evidence());
        for(var binary:resolution.binaries().orElseThrow())binary.nativeOrigin().ifPresent(origin->graphEvidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.binary-origin",origin.canonicalForm())));
        var proofInputs=proofs.values().stream().filter(p->p.record().fields().get("scope").equals(scope)).sorted(Comparator.comparing(JavaEvidenceAuthority.Verified::identity)).toList();
        for(var proof:proofInputs)graphEvidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.proof",proof.record().fields()));
        var graph=new JavaEvidenceGraph(base.snapshot().identity(),snapshot.identity(),graphEvidence);
        tables.putCanonical(readiness,cancelled);
        return new Prepared(base,inputs,build,new UniversalSourceIngestion.SourceSet(module.identity(),kind,namedSet),new UniversalSourceIngestion.Resolution(platform,resolution.binaries().orElseThrow(),manifest.identity(),correspondenceBinding),viewRecord,overlay,preAnalysisClosure,graph,proofInputs,readiness);
    }
    private ContentHandle put(String bytes,BooleanSupplier cancelled)throws IOException{return content.put(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8)),cancelled);}
    private static void decode(Map<String,SourceInput> sources,List<SourceDocument> unavailable,List<com.evolution.analysis.graph.GraphEvidence> evidence,SourceDocument document,byte[] raw,SourceInput.Decoding decoding){
        try{sources.put(document.path(),new SourceInput(document,raw,decoding));}
        catch(FrontendInputException invalid){
            if(!invalid.diagnostic().code().startsWith("frontend.encoding"))throw invalid;
            unavailable.add(document);evidence.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.source-decoding",Map.of("document",document,"content",new ContentHandle(document.contentDigest(),raw.length),"decoding",decoding,"diagnostic",invalid.diagnostic(),"outcome","UNAVAILABLE")));
        }
    }
    private static SourcePlanModel.Setting setting(String selector,String value,JavaEvidenceRecord evidence){return new SourcePlanModel.Setting("java.native."+selector,Optional.of(value),Optional.of(value),SourcePlanModel.Status.DECLARED,SourcePlanModel.Origin.EFFECTIVE_MODEL,List.of(new BuildModelResult.PomEvidence("captured:"+evidence.identity().value(),evidence.identity())));}
    static String namespace(RepositoryInputs base){for(long suffix=0;;suffix=Math.addExact(suffix,1)){String candidate=".evo-generated"+(suffix==0?"":"-"+suffix);if(base.snapshot().files().stream().noneMatch(f->f.path().equals(candidate)||f.path().startsWith(candidate+"/")))return candidate;}}
}
