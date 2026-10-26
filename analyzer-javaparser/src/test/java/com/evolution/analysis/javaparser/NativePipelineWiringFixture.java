package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.execution.javac.*;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javassist.bytecode.ClassFile;

/** Native captured-byte integration under an explicitly public TEST authority. */
final class NativePipelineWiringFixture {
    static final VersionedIdentifier INDEX=new VersionedIdentifier("compiler.native-captured-javac","21-census-v2");
    private final Path root;private final StreamingContentStore content;private final JavaEvidenceTables tables;
    private final JavaEvidenceCodec codec=new JavaEvidenceCodec();private final NativePipelineSigningFixture signer;
    private final Map<String,Object> checked,post,cohort;private final ContentHandle cohortHandle;
    private final NativeFixtureOwnership ownership;private final NativeContextValidator validator;private final NativeContextValidator.Platform nativePlatform;private final PlatformInput platform;
    private final Map<String,ModuleDescriptor> modules=new TreeMap<>();private final Map<String,String> directories=new TreeMap<>();
    private final Map<String,ContentHandle> outputs=new TreeMap<>(),platformFiles=new TreeMap<>(),nativeInputs=new TreeMap<>();
    private final List<ContentHandle> envelopes=new ArrayList<>();
    private final Set<ContentHandle> written=new HashSet<>();
    private final Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs=new HashMap<>();
    private Map<String,Object> scope;private ContentHandle closure;private JavaEvidenceTables.Table inputs;
    private final RepositoryIdentity repository=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:m844-native-prepared:v1");
    record Result(KnowledgeGraph graph,JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.DetailedResult pipeline,
                  FrontendRequest request,FrontendResult frontend,Map<String,Object> mappings,ContentHandle receipt,JavaSemanticEvidence.Bundle semantic){}
    NativePipelineWiringFixture(Path root)throws IOException {
        this.root=root.toRealPath();content=new StreamingContentStore(this.root.resolve("native-import-cas"));tables=new JavaEvidenceTables(content,2_000_000);signer=new NativePipelineSigningFixture(content);
        checked=read(pointer("native-context-check-v2.json"));post=read(handle(checked.get("nativePostIndex")));cohortHandle=handle(post.get("cohort"));cohort=read(cohortHandle);
        var inputBinding=read(pointer("native-wiring-inputs-v1.json"));if(!"m844-native-input-binding:v1".equals(inputBinding.get("schema"))||!GraphJson.string(inputBinding.get("attempt")).replace("-", "").equals(cohort.get("attempt")))throw new IOException("NATIVE_WIRING_INPUT_ATTEMPT");nativeInputs.putAll(handles(read(handle(inputBinding.get("inputClosure"))).get("files")));for(var ref:nativeInputs.values())if(!content.verify(ref))throw new IOException("NATIVE_WIRING_SEALED_INPUT_CHANGED");
        var platformReport=GraphJson.object(checked.get("platform"));var manifest=handle(platformReport.get("manifest"));for(Object value:GraphJson.array(read(manifest).get("files"))){var row=GraphJson.object(value);platformFiles.put(GraphJson.string(row.get("path")),handle(row));}
        validator=new NativeContextValidator(content,()->false);nativePlatform=validator.platform(manifest,GraphJson.array(platformReport.get("image")).stream().map(NativePipelineWiringFixture::handle).toList(),platformFiles);
        if(!CanonicalJson.sha256(nativePlatform.report()).equals(CanonicalJson.sha256(platformReport)))throw new IOException("NATIVE_WIRING_PLATFORM_CHANGED");
        platform=PlatformInput.create(21,nativePlatform.runtime(),GraphJson.string(read(manifest).get("vendor")),nativePlatform.selectedModules().stream().sorted().map(n->new PlatformInput.Artifact(n,platformFiles.get("jmods/"+n+".jmod").digest(),path(platformFiles.get("jmods/"+n+".jmod")),PlatformInput.Format.JMOD)).toList());
        ownership=new NativeFixtureOwnership(repository,post,cohortHandle,cohort,content);
        for(Object value:GraphJson.array(ownership.report().get("contexts"))){var row=GraphJson.object(value);String name=GraphJson.string(row.get("name")),directory=GraphJson.string(row.get("directory"));String relative=directory.substring(("/work/"+cohort.get("attempt")+"/").length());if(!relative.endsWith("/classes"))throw new IOException("NATIVE_WIRING_MODULE_OUTPUT");
            modules.put(name,ModuleDescriptor.create(repository,relative.substring(0,relative.length()-8),name));directories.put(name,directory);
        }
        for(Object value:GraphJson.array(cohort.get("outputs"))){var row=GraphJson.object(value);outputs.put(GraphJson.string(row.get("path")),handle(row));}
    }
    KnowledgeGraph run(String name)throws IOException{return execute(name).graph();}
    record Broken(KnowledgeGraph graph,Map<String,Object> nativeContext,Map<String,Object> blocked,Map<String,Object> healthyAfterFailure){}
    Broken broken()throws IOException {
        var row=GraphJson.array(checked.get("contexts")).stream().map(GraphJson::object).filter(c->"custom-broken".equals(c.get("name"))).findFirst().orElseThrow();var validation=GraphJson.object(row.get("validation"));var index=handle(validation.get("capture"));var raw=read(index);
        if(!"ERROR".equals(raw.get("state"))||GraphJson.array(raw.get("diagnostics")).isEmpty())throw new IOException("NATIVE_WIRING_EXPECTED_BROKEN_DIAGNOSTIC");
        var blocked=GraphJson.object(GraphJson.array(post.get("blocked")).getFirst());if(!blocked.equals(Map.of("name","custom-dependent","prerequisite","custom-broken","state","BLOCKED")))throw new IOException("NATIVE_WIRING_BLOCKED_DEPENDENT");
        var healthy=GraphJson.array(cohort.get("stages")).stream().map(GraphJson::object).filter(s->"custom-consumer-after-broken".equals(s.get("name"))).findFirst().orElseThrow();if(((Number)healthy.get("exit")).longValue()!=0)throw new IOException("NATIVE_WIRING_HEALTHY_SIBLING_LOST");
        var diagnosticLog=outputs.get("logs/custom-broken.log");if(diagnosticLog==null)throw new IOException("NATIVE_WIRING_BROKEN_LOG_MISSING");
        String diagnosticText=new String(bytes(diagnosticLog),StandardCharsets.UTF_8);if(!diagnosticText.contains("Broken.java:3:5:")||!diagnosticText.contains("MissingType")||!diagnosticText.contains("compiler.err.cant.resolve.location"))throw new IOException("NATIVE_WIRING_BROKEN_LOG_LABEL");
        var files=new TreeMap<String,SourceInput>();for(var e:handles(validation.get("sources")).entrySet()){String logical=e.getKey().substring(e.getKey().indexOf("/source/")+8);files.put(logical,source(modules.get("custom-broken"),logical,e.getValue(),SourceClassification.MAIN));}
        var docs=files.values().stream().map(SourceInput::document).toList();var base=new RepositoryInputs(RepositorySnapshot.create(repository,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).defaultPlatform(platform).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
        var result=new UniversalArchitecturePipeline().runWithEvidence(base,policy);var input=result.graphInput().orElseThrow();var gaps=new TreeSet<>(input.gaps());
        var capture=new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(nativePlatform.runtime(),GraphJson.array(raw.get("options")).stream().map(GraphJson::string).toList(),handles(validation.get("sources")),handles(raw.get("artifacts"))),4_000_000,()->false);
        gaps.addAll(capture.gaps(com.evolution.analysis.evidence.EvidenceContext.forSnapshot(base.snapshot().identity())));var graph=new CanonicalGraphBuilder().build(new CanonicalGraphInput(input.repository(),input.build(),input.ingestion(),input.spring(),input.issues(),List.copyOf(gaps),input.acquisition()));
        var evidence=new ArrayList<>(graph.evidence());evidence.add(GraphEvidence.capture("java.native.failure-context",Map.of("capture",index,"diagnostics",raw.get("diagnostics"),"diagnosticLog",diagnosticLog,"diagnosticText",diagnosticText,"blocked",blocked,"healthyAfterFailure",healthy,"trust","PRODUCER_ASSERTION")));
        graph=new KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),evidence);return new Broken(graph,raw,blocked,healthy);
    }
    void readWithChangedSource(String name)throws IOException {
        var selected=GraphJson.array(checked.get("contexts")).stream().map(GraphJson::object).filter(c->name.equals(c.get("name"))).findFirst().orElseThrow();var validation=GraphJson.object(selected.get("validation"));var index=handle(validation.get("capture"));var raw=read(index);var sources=handles(validation.get("sources"));var first=sources.entrySet().iterator().next();byte[] original=bytes(first.getValue());byte[] changed=Arrays.copyOf(original,original.length+1);changed[changed.length-1]='\n';sources.put(first.getKey(),content.put(new ByteArrayInputStream(changed),()->false));
        new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(nativePlatform.runtime(),GraphJson.array(raw.get("options")).stream().map(GraphJson::string).toList(),sources,handles(raw.get("artifacts"))),4_000_000,()->false);
    }
    void requireLombokVersion(String expected)throws IOException {
        var artifact=nativeInputs.get("materials/lombok.jar");try(var jar=new java.util.jar.JarFile(path(artifact).toFile())){
            if(!expected.equals(jar.getManifest().getMainAttributes().getValue("Lombok-Version")))throw new IOException("NATIVE_LOMBOK_VERSION_MISMATCH");
        }
    }
    void readWithChangedLombokConfiguration()throws IOException {
        var selected=GraphJson.array(checked.get("contexts")).stream().map(GraphJson::object).filter(c->"lombok-model".equals(c.get("name"))).findFirst().orElseThrow();var validation=GraphJson.object(selected.get("validation"));var index=handle(validation.get("capture"));var raw=read(index);var sources=handles(validation.get("sources"));var first=sources.entrySet().iterator().next();
        String original=new String(bytes(first.getValue()),StandardCharsets.UTF_8);String changed=original.replace("@lombok.Builder","@lombok.Builder(access = lombok.AccessLevel.PRIVATE)");if(original.equals(changed))throw new IOException("NATIVE_LOMBOK_LABEL_NOT_FOUND");sources.put(first.getKey(),content.put(new ByteArrayInputStream(changed.getBytes(StandardCharsets.UTF_8)),()->false));
        new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(nativePlatform.runtime(),GraphJson.array(raw.get("options")).stream().map(GraphJson::string).toList(),sources,handles(raw.get("artifacts"))),4_000_000,()->false);
    }
    Result execute(String name)throws IOException {
        return execute(name,Map.of());
    }
    Result execute(String name,Map<String,String> replayAudit)throws IOException {
        if(!Set.of("captureReplayAttempt","captureReplayRecordedAt").containsAll(replayAudit.keySet()))throw new IOException("NATIVE_WIRING_REPLAY_AUDIT_KEYS");
        if(name.startsWith("lombok-"))requireLombokVersion("1.18.36");
        proofs.clear();envelopes.clear();var selected=GraphJson.array(checked.get("contexts")).stream().map(GraphJson::object).filter(c->name.equals(c.get("name"))).findFirst().orElseThrow();var validation=GraphJson.object(selected.get("validation"));var index=handle(validation.get("capture"));var raw=read(index);
        var sourceBytes=handles(validation.get("sources"));var artifacts=handles(raw.get("artifacts"));var slots=new ArrayList<NativeContextValidator.Slot>();
        for(Object value:GraphJson.array(validation.get("orderedClasspath"))){var slot=GraphJson.object(value);slots.add(new NativeContextValidator.Slot(GraphJson.string(slot.get("path")),NativeContextValidator.Slot.Kind.valueOf(GraphJson.string(slot.get("kind"))),handles(slot.get("files"))));}
        var captured=new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(nativePlatform.runtime(),GraphJson.array(raw.get("options")).stream().map(GraphJson::string).toList(),sourceBytes,artifacts),4_000_000,()->false);
        if(!CanonicalJson.sha256(validator.validate(captured,slots,nativePlatform).report()).equals(CanonicalJson.sha256(validation)))throw new IOException("NATIVE_WIRING_CONTEXT_CHANGED");
        var module=modules.get(name);var files=new TreeMap<String,SourceInput>();var generated=new TreeMap<String,ContentHandle>();
        for(var entry:sourceBytes.entrySet())if(entry.getKey().startsWith("file:///opt/evo-inputs/")){String logical=entry.getKey().substring(entry.getKey().indexOf("/source/")+8);files.put(logical,source(module,logical,entry.getValue(),SourceClassification.MAIN));}else{String logical=workRelative(entry.getKey());if(!entry.getValue().equals(outputs.get(logical)))throw new IOException("NATIVE_WIRING_GENERATED_SOURCE");generated.put(logical,entry.getValue());}
        for(var input:nativeInputs.entrySet())if(input.getKey().startsWith("source/"+module.path()+"/")&&!input.getKey().endsWith(".java")){String logical=input.getKey().substring(7);files.put(logical,source(module,logical,input.getValue(),SourceClassification.OTHER));}
        var docs=files.values().stream().map(SourceInput::document).toList();var base=new RepositoryInputs(RepositorySnapshot.create(repository,Optional.empty(),false,docs.stream().map(SnapshotFile::from).toList(),docs),files);
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,repository,base.snapshot().contentDigest(),base.snapshot().contentDigest(),files.size(),files.size(),files.values().stream().mapToLong(f->f.bytes().length).sum(),0,Optional.of(base.snapshot().identity()),Optional.of(base.snapshot().contentDigest()));
        var environment=new TreeMap<String,String>();environment.put("compiler",nativePlatform.runtime());environment.put("capturedContext",CanonicalJson.sha256(validation).value());environment.putAll(replayAudit);
        var context=ReadinessPlanner.context(name,name+"-root","main","native-pipeline-fixture-v1",List.of(module.path()),environment,List.of(),inventory,true);
        var selection=new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of(context.id()),List.of(context.id()),List.of(),List.of());
        var readiness=new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,selection,List.of(new ReadinessPlan.Root(name+"-root",module.path(),ReadinessPlan.Family.PLAIN,List.of(),List.of())),List.of(new ReadinessPlan.Module(name,name+"-root",module.path(),name+"-root",List.of("java"),List.of(),List.of())),List.of(context),List.of(),List.of(),new ReadinessPlan.OwnershipSummary(files.size(),Map.of("OWNED",(long)files.size()),put(Map.of("scope",module,"capture",index))));
        scope=JavaEvidenceScopes.scope(readiness,context,module);
        closure=put(Map.of("schema","native-prepared-fixture-inputs:m8.4-v1","scope",scope,"cohort",cohortHandle,"capture",index,"context",validation,"platform",checked.get("platform"),"tool",signer.tool()));
        var inputRows=new ArrayList<JavaEvidenceRecord>();inputRows.add(input("CONFIG",closure));inputRows.add(input("TOOL",signer.tool()));inputRows.add(input("CORRESPONDENCE",cohortHandle));sourceBytes.values().forEach(h->inputRows.add(input("SOURCE",h)));inputs=table("java-evidence-input",inputRows);
        if(captured.state().equals("ERROR"))throw new IOException("NATIVE_WIRING_ERROR_CONTEXT_REQUIRES_PARTIAL_LANE");
        var stage=GraphJson.array(cohort.get("stages")).stream().map(GraphJson::object).filter(s->name.equals(s.get("name"))||name.equals("mapstruct-mapper")&&"mapstruct-generate".equals(s.get("name"))||name.equals("custom-generator")&&"custom-generator-compile".equals(s.get("name"))).findFirst().orElseThrow();
        if(((java.math.BigDecimal)stage.get("exit")).intValueExact()!=0||!"COMPLETED".equals(stage.get("state")))throw new IOException("NATIVE_WIRING_UNSUCCESSFUL_STAGE");
        var lineageInputs=new ArrayList<>(inputRows);for(var nativeInput:nativeInputs.entrySet())lineageInputs.add(input(nativeInput.getKey().startsWith("materials/")?"MATERIAL":"SOURCE",nativeInput.getValue()));
        var lineage=record("java-generation-lineage","scope",scope,"generator",generator(name),"compiler",new VersionedIdentifier("compiler.javac",nativePlatform.runtime()),"compilerApplicability","APPLICABLE","compilerApplicabilityProof",null,"toolchain",CanonicalJson.sha256(checked.get("platform")),"recipe",nativeInputs.get("source/recipe.py").digest(),"configuration",CanonicalJson.sha256(validation),"environment",CanonicalJson.sha256(nativePlatform.report()),"nativeResolution",CanonicalJson.sha256(validation),"inputs",table("java-evidence-input",lineageInputs).wire(),"outputCensus",cohortHandle,"inputClosure",closure.digest(),"closure","COMPLETE","gaps",List.of());
        var layout=Map.of("schema","java-generated-root-layout:m8.4-v1","scope",scope,"roots",name.equals("custom-producer")?List.of(Map.of("kind","RESOURCE","role","generated-resource","sourceSet","main","root",LogicalPath.utf8("custom/generated/resources"),"encoding","UTF-8")):List.of());
        var lineageProof=proof("java.generated-lineage",lineage.identity().value(),layout,List.of(cohortHandle,closure));
        var outputRows=new ArrayList<JavaEvidenceRecord>();generated.forEach((p,h)->outputRows.add(output(p,h,"JAVA_SOURCE",lineage.identity(),lineageProof.identity())));
        String outputPrefix=workRelative("file://"+directories.get(name))+"/";
        for(var entry:outputs.entrySet())if(entry.getKey().startsWith(outputPrefix)||name.equals("custom-producer")&&entry.getKey().startsWith("custom/generated/resources/"))outputRows.add(output(entry.getKey(),entry.getValue(),entry.getKey().endsWith(".class")?"CLASS":"RESOURCE",lineage.identity(),lineageProof.identity()));
        if(outputRows.isEmpty())throw new IOException("NATIVE_WIRING_EMPTY_SUCCESS_OUTPUTS");
        var overlay=new JavaGeneratedOverlay(content,2_000_000).importOverlay(scope,cohortHandle,table("java-generation-lineage",List.of(lineage)),table("java-output-row",outputRows),proofs,true,20000,()->false).overlay();
        var members=new ArrayList<JavaEvidenceRecord>();
        for(var file:files.values())if(file.document().path().endsWith(".java"))members.add(membership(put(file.document()),new ContentHandle(file.document().contentDigest(),file.bytes().length),lineageProof.identity(),"MAIN"));
        tables.read(JavaEvidenceTables.Table.from(overlay.fields().get("outputs")),row->{if(row.text("category").equals("JAVA_SOURCE"))members.add(membership(put(row.fields()),handle(row.fields().get("content")),lineageProof.identity(),"GENERATED_MAIN"));},()->false);
        var resolutionSlots=new ArrayList<JavaEvidenceRecord>();for(int ordinal=0;ordinal<slots.size();ordinal++)resolutionSlots.add(slot(slots.get(ordinal),ordinal,lineage.identity(),lineageProof.identity()));
        var correspondence=table("java-evidence-input",List.of());
        if(name.equals("lombok-model")){
            var classFiles=new TreeMap<String,ContentHandle>();for(var output:outputs.entrySet())if(output.getKey().startsWith(outputPrefix))classFiles.put(output.getKey().substring(outputPrefix.length()),output.getValue());
            var witness=slot(new NativeContextValidator.Slot(directories.get(name),NativeContextValidator.Slot.Kind.DIRECTORY,classFiles),0,lineage.identity(),lineageProof.identity());var witnessFields=new TreeMap<>(witness.fields());witnessFields.put("space","BINARY_EVIDENCE");witness=codec.create(witnessFields);resolutionSlots.add(witness);
            var ordinary=new ArrayList<NativeSourceBinaryFixtureMapping.NativeContextValidatorSlot>();for(var input:slots){if(input.kind()!=NativeContextValidator.Slot.Kind.JAR)throw new IOException("NATIVE_LOMBOK_FIXTURE_CLASSPATH_CHANGED");var ref=input.files().get("");ordinary.add(new NativeSourceBinaryFixtureMapping.NativeContextValidatorSlot(coordinate(input),ref,path(ref)));}
            var rows=new NativeSourceBinaryFixtureMapping(content).rows(base,module,platform,ordinary,witness,classFiles,put(lineage.fields()));
            var mappingProof=proof("java.source-binary-correspondence",witness.identity().value(),JavaSourceCorrespondence.answerFields(scope,witness,rows),List.of(closure,cohortHandle));var mappedInputs=new ArrayList<JavaEvidenceRecord>();
            for(var mapping:rows){var fields=new TreeMap<>(mapping.fields());fields.put("proof",mappingProof.identity());mappedInputs.add(input("CORRESPONDENCE",put(codec.create(fields).fields())));}correspondence=table("java-evidence-input",mappedInputs);
        }
        var resolution=record("java-resolution-manifest","scope",scope,"overlay",overlay.identity(),"compiler",new VersionedIdentifier("compiler.javac",nativePlatform.runtime()),"sourceLanguage","21","bytecodeTarget","21","release","21","preview",false,"encoding","UTF-8","platform",platform.entry().contentDigest(),"sourceMembership",table("java-evidence-input",members).wire(),"slots",table("java-resolution-slot",resolutionSlots).wire(),"arguments",GraphJson.array(raw.get("options")),"argfiles",List.of(),"contextProof",closure.digest(),"status","EXACT","gaps",List.of());
        var resolutionProof=proof("java.resolution-context",context.identity().value(),JavaResolutionPlanner.answerFields(resolution),List.of(closure,cohortHandle));var resolutionFields=new TreeMap<>(resolution.fields());resolutionFields.put("contextProof",resolutionProof.identity());resolution=codec.create(resolutionFields);
        var requirements=JavaEvidencePreAnalysis.footprint(scope,overlay,resolution,tables,()->false);var decisions=new ArrayList<JavaEvidenceRecord>();
        for(var requirement:requirements){String q=requirement.text("question"),subject=requirement.text("subject");var existing=proofs.values().stream().filter(p->q.equals(p.record().text("question"))&&subject.equals(p.record().text("subject"))).findFirst();
            if(existing.isEmpty()&&q.equals("java.output-bytes"))existing=Optional.of(proof(q,subject,Map.of("capture",cohortHandle,"output",subject),List.of(cohortHandle,closure)));
            if(existing.isEmpty()&&q.equals("java.build-validation"))existing=Optional.of(proof(q,subject,Map.of("stage",stage,"nativeCapture",index),List.of(cohortHandle,closure)));
            if(existing.isPresent())decisions.add(decision(JavaEvidenceLedger.requirementKey(requirement),existing.orElseThrow()));
        }
        var pre=new JavaEvidenceLedger().publish(scope,put(requirements.stream().map(JavaEvidenceRecord::fields).toList()),signer.policy(),requirements,decisions,List.of(),Optional.empty(),proofs,Set.of(),requirements.stream().map(JavaEvidenceLedger::requirementKey).collect(java.util.stream.Collectors.toSet()),tables,()->false);
        var model=buildModel(base,name,resolution);var prepared=new JavaEvidenceAssembler(content,2_000_000).prepare(base,readiness,context,model,module,overlay,resolution,platform,proofs,new JavaBinaryViews(content,Files.createDirectories(root.resolve("prepared-binary-views").resolve(name)),new JavaBinaryViews.Budget(20000,128000000,32000000)),pre,correspondence,20000,4000000,()->false);
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
        var first=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy);var graphInput=first.graphInput().orElseThrow(()->new IOException("NATIVE_WIRING_NO_GRAPH:"+first.report().status()));
        var ingestion=graphInput.ingestion().orElseThrow(()->new IOException("NATIVE_WIRING_NO_INGESTION:"+first.report().gaps().stream().map(g->g.reasonCode()).toList()));
        var request=ingestion.sourceIngestion().outcomes().stream().flatMap(o->o.request().stream()).findFirst().orElseThrow(()->new IOException("NATIVE_WIRING_NO_REQUEST:"+first.report().issues().stream().map(IngestionEvidence.Issue::subject).toList()));var availableFrontend=graphInput.ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).findFirst();if(availableFrontend.isEmpty())new JavaParserFrontend().analyze(request);var frontend=availableFrontend.orElseThrow(()->new IOException("NATIVE_WIRING_FRONTEND_UNAVAILABLE:"+first.report().issues().stream().map(IngestionEvidence.Issue::subject).toList()));
        var uris=new TreeMap<SourceDocumentIdentity,String>();for(var source:request.sources()){var matching=sourceBytes.entrySet().stream().filter(e->e.getValue().digest().equals(source.document().contentDigest())).toList();if(matching.size()!=1)throw new IOException("NATIVE_WIRING_SOURCE_URI_AMBIGUOUS");uris.put(source.document().identity(),matching.getFirst().getKey());}
        var mapping=new NativeJavaSymbolMapping(captured,request,frontend,uris,slots).compare();var eligibility=NativeJavaArchitectureFixtureMain.canonicalEligibility(captured,frontend,uris,request.sources(),new OccurrenceCensusVerifier().verify(captured,frontend,uris,request.sources()));var semantic=semantic(prepared,request,frontend,mapping,captured,Boolean.TRUE.equals(eligibility.get("eligibleLocationsClosed")));
        var result=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy,semantic);var graph=new CanonicalGraphBuilder().build(result.graphInput().orElseThrow());var nativeClosure=closeNativeHistory(captured,base,prepared,frontend,pre,graph);
        prepared=prepared.withGapClosure(nativeClosure.closure());
        result=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy,semantic);
        graph=new CanonicalGraphBuilder().build(result.graphInput().orElseThrow());var enriched=new ArrayList<>(graph.evidence());enriched.addAll(nativeClosure.evidence());graph=new KnowledgeGraph(graph.snapshot(),graph.contexts(),graph.nodes(),graph.edges(),enriched);
        var graphStore=new FileGraphStore(Files.createDirectories(root.resolve("prepared-graphs")));graphStore.save(graph,()->false);if(!graphStore.load(graph.identity()).orElseThrow().identity().equals(graph.identity()))throw new IOException("NATIVE_WIRING_GRAPH_REPLAY");
        var receiptFields=new TreeMap<String,Object>();receiptFields.put("schema","native-prepared-wiring-result:m8.4-v1");receiptFields.put("name",name);receiptFields.put("capture",index);receiptFields.put("view",prepared.view().identity());receiptFields.put("graph",graph.identity());receiptFields.put("mapping",put(mapping.report()));receiptFields.put("preActive",pre.current().active());receiptFields.put("claims",table("java-evidence-input",envelopes.stream().map(e->input("CORRESPONDENCE",e)).toList()).wire());receiptFields.put("status",result.report().status());receiptFields.put("publicTestAuthority",true);receiptFields.put("nativeClosure",put(nativeClosure.published().projection().fields()));receiptFields.put("rawGapRecords",nativeClosure.history());receiptFields.put("activeRawGaps",nativeClosure.active());var receipt=put(receiptFields);
        return new Result(graph,prepared,result,request,frontend,mapping.report(),receipt,semantic);
    }
    private record NativeClosure(JavaEvidenceLedger.Published published,long history,long active,List<GraphEvidence> evidence,JavaEvidenceGapClosure closure){}
    private NativeClosure closeNativeHistory(CapturedJavacReader.Capture captured,RepositoryInputs base,JavaEvidenceAssembler.Prepared prepared,FrontendResult frontend,JavaEvidenceLedger.Published pre,KnowledgeGraph graph)throws IOException {
        List<JavaEvidenceRecord> requirements=new ArrayList<>();var decisions=new ArrayList<JavaEvidenceRecord>();tables.read(JavaEvidenceTables.Table.from(pre.ledger().fields().get("requirements")),requirements::add,()->false);tables.read(JavaEvidenceTables.Table.from(pre.ledger().fields().get("decisions")),decisions::add,()->false);
        for(var item:graph.evidence())if(item.family().equals("java.evidence.post-requirement"))requirements.add(codec.create(GraphJson.object(GraphJson.read(item.canonicalJson(),GraphJson.Limits.defaults()))));else if(item.family().equals("java.evidence.post-decision"))decisions.add(codec.create(GraphJson.object(GraphJson.read(item.canonicalJson(),GraphJson.Limits.defaults()))));
        var history=captured.gaps(com.evolution.analysis.evidence.EvidenceContext.forSnapshot(base.snapshot().identity()));var bindings=new TreeMap<ContentDigest,List<String>>();
        for(var gap:history){
            String question=gap.reasonCode().equals("PLATFORM_SELECTION_UNESTABLISHED")?"java.resolution-context":"java.semantic-occurrence";
            boolean mapped=Set.of("PLATFORM_SELECTION_UNESTABLISHED","OCCURRENCE_CENSUS_UNQUALIFIED","ELEMENT_MAPPING_UNAVAILABLE","ENCLOSING_SCOPE_MAPPING_REQUIRED","NATIVE_BINDING_SYNTAX_ONLY","SOURCE_POSITION_UNAVAILABLE").contains(gap.reasonCode());
            var requirement=mapped?requirements.stream().filter(r->r.text("question").equals(question)&&(question.equals("java.resolution-context")||r.text("category").equals("java.semantic-census"))).findFirst():Optional.<JavaEvidenceRecord>empty();
            if(requirement.isEmpty()){var missing=JavaEvidencePreAnalysis.requirement(scope,question,gap.gapIdentity().value());requirements.add(missing);requirement=Optional.of(missing);}
            bindings.computeIfAbsent(JavaEvidenceLedger.requirementKey(requirement.orElseThrow()),k->new ArrayList<>()).add(gap.gapIdentity().value());
        }
        requirements=requirements.stream().map(r->{var f=new TreeMap<>(r.fields());var refs=new TreeSet<String>();GraphJson.array(r.fields().get("gapRefs")).stream().map(GraphJson::string).forEach(refs::add);refs.addAll(bindings.getOrDefault(JavaEvidenceLedger.requirementKey(r),List.of()));f.put("gapRefs",List.copyOf(refs));return codec.create(f);}).toList();
        var census=put(Map.of("schema","native-java-obligation-census:m8.4-v1","scope",scope,"capture",captured.document(),"view",prepared.view().identity(),"frontend",CanonicalJson.sha256(frontend),"history",history.stream().map(g->g.gapIdentity()).toList(),"requirements",requirements.stream().map(JavaEvidenceLedger::requirementKey).toList()));
        var published=new JavaEvidenceLedger().publish(scope,census,signer.policy(),requirements,decisions,List.of(),Optional.empty(),proofs,Set.of(),requirements.stream().map(JavaEvidenceLedger::requirementKey).collect(java.util.stream.Collectors.toSet()),tables,()->false);
        var mappingProofs=new ArrayList<JavaEvidenceAuthority.Verified>();
        for(var gap:history){
            mappingProofs.add(proof(JavaEvidenceGapClosure.QUESTION,gap.gapIdentity().value(),JavaEvidenceGapClosure.bindingAnswer(scope,gap,requirements),List.of(put(gap),census)));
        }
        var closure=new JavaEvidenceGapClosure(published,history,mappingProofs);
        var dispositions=closure.current(signer.policy()).dispositions();var evidence=new ArrayList<GraphEvidence>();for(var disposition:dispositions){evidence.add(GraphEvidence.capture("java.native.gap-history",disposition.gap()));evidence.add(GraphEvidence.capture("java.native.gap-disposition",disposition));}
        evidence.add(GraphEvidence.capture("java.native.closure-ledger",published.ledger().fields()));evidence.add(GraphEvidence.capture("java.native.closure-projection",published.projection().fields()));return new NativeClosure(published,history.size(),dispositions.stream().filter(JavaEvidenceLedger.GapDisposition::active).count(),List.copyOf(evidence),closure);
    }
    private JavaSemanticEvidence.Bundle semantic(JavaEvidenceAssembler.Prepared prepared,FrontendRequest request,FrontendResult frontend,NativeJavaSymbolMapping.Result mapped,CapturedJavacReader.Capture captured,boolean eligible)throws IOException {
        var entities=new HashMap<EntityIdentity,Entity>();frontend.declarations().forEach(d->entities.put(d.entity().identity(),d.entity()));var byDocument=new TreeMap<SourceDocumentIdentity,List<JavaEvidenceRecord>>();var counts=new TreeMap<SourceDocumentIdentity,Map<String,Long>>();
        for(var source:request.sources()){byDocument.put(source.document().identity(),new ArrayList<>());var categories=new TreeMap<String,Long>();FrontendRequest.CATEGORIES.forEach(c->categories.put("java."+c,0L));counts.put(source.document().identity(),categories);}
        var nativeRows=new HashMap<ContentDigest,Map<String,Object>>();captured.observations().forEach(row->nativeRows.put(CanonicalJson.sha256(row),row));
        for(var row:mapped.rows()){
            var span=(SourceSpan)row.get("span");String category=GraphJson.string(row.get("category"));boolean matched="MAPPED".equals(row.get("disposition"))&&Boolean.TRUE.equals(row.get("provenanceValid"));
            var fields=new TreeMap<String,Object>();fields.put("schema","java-semantic-occurrence:m8.4-v1");fields.put("inputView",prepared.view().identity());fields.put("analysis",frontend.analysis());fields.put("document",span.document());fields.put("category",category);fields.put("span",span);fields.put("caller",matched?put(entities.get(row.get("caller"))):null);fields.put("targets",matched?List.of(put(entities.get(row.get("target")))):List.of());fields.put("status",matched?"RESOLVED":"UNRESOLVED");fields.put("proof",null);
            if(matched){var subject=JavaSemanticEvidence.locationKey(scope,prepared.view().identity(),frontend.analysis(),span.document(),category,Optional.of(span));fields.put("proof",proof("java.semantic-occurrence",subject.value(),JavaSemanticEvidence.answerFields(fields),List.of(put(row),put(nativeRows.get(row.get("nativeObservation"))),closure)).identity());}
            byDocument.get(span.document()).add(input("SOURCE",put(fields)));counts.get(span.document()).compute(category,(k,v)->v+1);
        }
        var documents=new ArrayList<JavaEvidenceRecord>();for(var source:request.sources()){var doc=source.document();var rows=byDocument.get(doc.identity());var value=Map.of("schema","java-semantic-document:m8.4-v1","document",doc.identity(),"content",new ContentHandle(doc.contentDigest(),source.bytes().length),"disposition","CLOSED","categories",counts.get(doc.identity()),"occurrences",table("java-evidence-input",rows).wire());documents.add(input("SOURCE",put(value)));}
        var census=new TreeMap<String,Object>();census.put("schema","java-semantic-census:m8.4-v1");census.put("scope",scope);census.put("inputView",prepared.view().identity());census.put("analysis",frontend.analysis());census.put("provider",INDEX);census.put("documents",table("java-evidence-input",documents).wire());census.put("proof",null);
        boolean closed=eligible&&((Number)mapped.report().get("pendingTargets")).longValue()==0&&((Number)mapped.report().get("pendingCallers")).longValue()==0&&((Number)mapped.report().get("pendingOrigins")).longValue()==0&&((Number)mapped.report().get("errorOrNonresolved")).longValue()==0;
        if(closed)census.put("proof",proof("java.semantic-occurrence",JavaSemanticEvidence.censusKey(scope,prepared.view().identity(),frontend.analysis()).value(),JavaSemanticEvidence.answerFields(census),List.of(put(mapped.report()),closure)).identity());
        return new JavaSemanticEvidence.Bundle(content,put(census),proofs,20000,()->false);
    }
    private UniversalBuildModel buildModel(RepositoryInputs base,String selected,JavaEvidenceRecord manifest){
        var rows=new ArrayList<UniversalBuildModel.Module>();for(var item:modules.entrySet()){var module=item.getValue();var plans=new ArrayList<SourcePlanModel.SourceSetPlan>();for(var kind:List.of(SourcePlanModel.Kind.MAIN,SourcePlanModel.Kind.TEST))plans.add(new SourcePlanModel.SourceSetPlan(module.identity(),kind,item.getKey().equals(selected)&&kind==SourcePlanModel.Kind.MAIN?List.of(setting(".")):List.of(),List.of(),setting(directories.get(item.getKey())),Map.of(),setting("21"),setting("21"),setting("21"),setting("UTF-8"),List.of(),List.of()));rows.add(new UniversalBuildModel.Module(module,UniversalBuildModel.Tool.IMPORTED_SOURCE_PLAN,Optional.empty(),Optional.empty(),new SourcePlanModel(plans,List.of()),List.of(),List.of()));}return new UniversalBuildModel(manifest.identity(),base.identity(),rows,List.of(),List.of(),List.of());
    }
    private SourcePlanModel.Setting setting(String value){return new SourcePlanModel.Setting("native.capture",Optional.of(value),Optional.of(value),SourcePlanModel.Status.DECLARED,SourcePlanModel.Origin.EFFECTIVE_MODEL,List.of(new BuildModelResult.PomEvidence("native.cohort",cohortHandle.digest())));}
    private JavaEvidenceRecord slot(NativeContextValidator.Slot slot,int ordinal,ContentDigest lineage,ContentDigest rootProof)throws IOException {
        ownership.owner(slot);JavaEvidenceTables.Table tree=null;ContentHandle original,selected=null;String kind;
        if(slot.kind()==NativeContextValidator.Slot.Kind.JAR){original=slot.files().get("");kind="JAR";validateClasses(original);}else{
            var rows=new ArrayList<JavaEvidenceRecord>();for(var e:new TreeMap<>(slot.files()).entrySet()){validateClass(e.getKey(),e.getValue());rows.add(output(e.getKey(),e.getValue(),e.getKey().endsWith(".class")?"CLASS":"RESOURCE",lineage,rootProof));}tree=table("java-output-row",rows);
            // Use a null native root: tree paths are already relative to this exact slot.
            var descriptor=new TreeMap<String,Object>();descriptor.put("schema","java-class-directory:m8.4-v1");descriptor.put("scope",scope);descriptor.put("root",null);descriptor.put("tree",tree.wire());original=put(descriptor);selected=directory(slot.files());kind="CLASS_DIRECTORY";
        }
        var shape=proof("java.binary-shape",original.digest().value(),Map.of("slot",slot.path(),"files",slot.files(),"original",original),List.of(original,closure));
        var fields=new TreeMap<String,Object>();fields.put("schema","java-resolution-slot:m8.4-v1");fields.put("scope",scope);fields.put("space","CLASS_PATH");fields.put("ordinal",ordinal);fields.put("logicalName",coordinate(slot));fields.put("kind",kind);fields.put("original",original);fields.put("tree",tree==null?null:tree.wire());fields.put("selectedView",selected);
        var owner=directories.entrySet().stream().filter(e->e.getValue().equals(slot.path())).map(e->modules.get(e.getKey())).toList();if(owner.size()>1)throw new IOException("NATIVE_WIRING_DUPLICATE_OWNER");fields.put("origin",owner.isEmpty()?"DEPENDENCY":"PROJECT");fields.put("module",owner.isEmpty()?null:owner.getFirst().identity());fields.put("proof",shape.identity());fields.put("disposition","ACCEPTED");fields.put("gaps",List.of());return codec.create(fields);
    }
    private String coordinate(NativeContextValidator.Slot slot){for(Object value:GraphJson.array(GraphJson.object(cohort.get("materials")).get("files"))){var row=GraphJson.object(value);if(slot.path().endsWith("/"+row.get("path"))){String origin=GraphJson.string(row.get("origin"));int start=origin.indexOf("/maven2/");if(start>=0){String[] path=origin.substring(start+8).split("/");if(path.length>=4)return String.join(".",Arrays.copyOf(path,path.length-3))+":"+path[path.length-3]+":"+path[path.length-2]+"@jar";}}}return "captured:"+slot.path();}
    private void validateClasses(ContentHandle jar)throws IOException {try(var zip=new java.util.zip.ZipInputStream(content.open(jar))){var names=new HashSet<String>();int classes=0;for(var e=zip.getNextEntry();e!=null;e=zip.getNextEntry()){if(!names.add(e.getName()))throw new IOException("NATIVE_WIRING_DUPLICATE_CLASS_ENTRY");if(e.getName().endsWith(".class")){var clazz=new ClassFile(new DataInputStream(zip));if(clazz.getMajorVersion()>65||clazz.getMajorVersion()<45)throw new IOException("NATIVE_WIRING_CLASS_VERSION");classes++;}}if(classes==0)throw new IOException("NATIVE_WIRING_EMPTY_CLASS_CENSUS");}}
    private void validateClass(String name,ContentHandle ref)throws IOException {if(!name.endsWith(".class"))return;try(var in=content.open(ref)){var clazz=new ClassFile(new DataInputStream(in));if(!name.equals(clazz.getName().replace('.','/')+".class"))throw new IOException("NATIVE_WIRING_CLASS_NAME");}}
    private ContentHandle directory(Map<String,ContentHandle> files)throws IOException {var buffer=new ByteArrayOutputStream();try(var jar=new JarOutputStream(buffer)){for(var e:new TreeMap<>(files).entrySet()){var member=new JarEntry(e.getKey());member.setTime(0);jar.putNextEntry(member);jar.write(bytes(e.getValue()));jar.closeEntry();}}return content.put(new ByteArrayInputStream(buffer.toByteArray()),()->false);}
    private JavaEvidenceRecord membership(ContentHandle source,ContentHandle bytes,ContentDigest owner,String role)throws IOException{return input("SOURCE_MEMBERSHIP",put(record("java-source-membership","scope",scope,"sourceEvidence",source,"content",bytes,"rootProof",owner,"sourceSet","main","role",role,"disposition","INCLUDED","reason",null).fields()));}
    private JavaEvidenceRecord output(String path,ContentHandle bytes,String category,ContentDigest lineage,ContentDigest owner){var row=record("java-output-row","key",closure.digest(),"scope",scope,"role",category.equals("RESOURCE")?"generated-resource":"native-output","expectation","REQUIRED","locator",Map.of("container",cohortHandle,"members",List.of(),"path",LogicalPath.utf8(path)),"kind","FILE","content",bytes,"lineage",lineage,"rootProof",owner,"category",category,"disposition","ACCEPTED","reason",null);var f=new TreeMap<>(row.fields());f.put("key",JavaGeneratedOverlay.outputKey(row));return codec.create(f);}
    private JavaEvidenceRecord decision(ContentDigest key,JavaEvidenceAuthority.Verified proof){return record("java-closure-decision","requirement",key,"state","SATISFIED","proofs",List.of(proof.identity()),"prerequisites",List.of(),"conflicts",List.of(),"supersedes",List.of(),"reason",null);}
    private JavaEvidenceAuthority.Verified proof(String question,String subject,Object answer,List<ContentHandle> evidence)throws IOException {var checks=new TreeMap<String,List<ContentHandle>>();JavaEvidenceAuthority.requiredChecks(question).forEach(n->checks.put(n,evidence));var issued=signer.issue(scope,question,subject,closure,put(answer),inputs,checks);envelopes.add(issued.envelope());proofs.put(issued.verified().identity(),issued.verified());return issued.verified();}
    private long replay(String name,ContentHandle ref)throws IOException {
        var receipt=read(ref);if(!"native-prepared-wiring-result:m8.4-v1".equals(receipt.get("schema"))||!Boolean.TRUE.equals(receipt.get("publicTestAuthority"))||!name.equals(receipt.get("name")))throw new IOException("NATIVE_WIRING_REPLAY_SCOPE");
        requireHealthy(receipt);
        var graph=new FileGraphStore(root.resolve("prepared-graphs")).load(new ContentDigest(GraphJson.string(receipt.get("graph")))).orElseThrow();
        if(graph.evidence().stream().noneMatch(e->e.family().equals("java.evidence.view")&&CanonicalJson.sha256(GraphJson.read(e.canonicalJson(),GraphJson.Limits.defaults())).value().equals(receipt.get("view"))))throw new IOException("NATIVE_WIRING_REPLAY_VIEW");
        var restored=new HashMap<ContentDigest,JavaEvidenceAuthority.Verified>();var questions=new HashSet<String>();long[] count={0};
        tables.read(JavaEvidenceTables.Table.from(receipt.get("claims")),row->{
            var issued=signer.replay(handle(row.fields().get("content")));var proof=issued.verified();
            if(!modules.get(name).identity().value().equals(proof.record().object("scope").get("module")))throw new IOException("NATIVE_WIRING_REPLAY_CLAIM_CONTEXT");
            if(restored.putIfAbsent(proof.identity(),proof)!=null)throw new IOException("NATIVE_WIRING_REPLAY_DUPLICATE_CLAIM");questions.add(proof.record().text("question"));count[0]++;
        },()->false);
        if(!questions.containsAll(Set.of("java.resolution-context","java.semantic-occurrence")))throw new IOException("NATIVE_WIRING_REPLAY_MISSING_CLAIMS");
        var closure=codec.create(read(handle(receipt.get("nativeClosure"))));
        var ledgerEvidence=graph.evidence().stream().filter(e->e.family().equals("java.native.closure-ledger")).findFirst().orElseThrow();
        var ledger=codec.create(GraphJson.object(GraphJson.read(ledgerEvidence.canonicalJson(),GraphJson.Limits.defaults())));
        if(!ledger.fields().get("scope").equals(closure.fields().get("scope"))||!signer.policy().equals(closure.digest("policy"))||!ledger.digest("census").equals(closure.digest("census"))||!closure.text("denominator").equals("CLOSED"))throw new IOException("NATIVE_WIRING_REPLAY_CLOSURE_SCOPE");
        var requirements=new ArrayList<JavaEvidenceRecord>();var decisions=new ArrayList<JavaEvidenceRecord>();
        tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("requirements")),requirements::add,()->false);tables.read(JavaEvidenceTables.Table.from(ledger.fields().get("decisions")),decisions::add,()->false);
        var current=new JavaEvidenceLedger().fold(requirements,decisions,restored,Set.of(),requirements.stream().map(JavaEvidenceLedger::requirementKey).collect(java.util.stream.Collectors.toSet()));
        if(current.active()!=0||current.rows().size()!=((Number)closure.object("counts").get("total")).longValue())throw new IOException("NATIVE_WIRING_REPLAY_CLOSURE");
        tables.read(JavaEvidenceTables.Table.from(closure.fields().get("current")),row->{var actual=current.rows().get(row.digest("requirement"));
            if(actual==null||!CanonicalJson.sha256(Map.of("state",actual.state(),"answers",actual.answers(),"proofContents",actual.proofs(),"blockers",actual.blockers(),"conflicts",actual.conflicts())).equals(CanonicalJson.sha256(Map.of("state",row.fields().get("state"),"answers",row.fields().get("answers"),"proofContents",row.fields().get("proofContents"),"blockers",row.fields().get("blockers"),"conflicts",row.fields().get("conflicts")))))throw new IOException("NATIVE_WIRING_REPLAY_CURRENT_ROWS");
        },()->false);
        return count[0];
    }
    private void requireHealthy(Map<String,Object> receipt)throws IOException {
        if(((Number)receipt.get("preActive")).longValue()!=0||((Number)receipt.get("activeRawGaps")).longValue()!=0)throw new IOException("NATIVE_WIRING_ACTIVE_GAPS");
        var mapping=read(handle(receipt.get("mapping")));for(String field:List.of("pendingCallers","pendingTargets","pendingOrigins","errorOrNonresolved"))if(((Number)mapping.get(field)).longValue()!=0)throw new IOException("NATIVE_WIRING_UNCLOSED_MAPPING:"+field);
    }
    private void campaign(boolean replay,String revision)throws IOException {
        if(!revision.matches("v[1-9][0-9]*"))throw new IllegalArgumentException("Invalid campaign revision");
        Path pointer=root.resolve("native-prepared-check-"+revision+".json");
        if(replay){var ref=handle(GraphJson.read(Files.readString(pointer),GraphJson.Limits.defaults()));var report=read(ref);
            if(!"native-prepared-pipeline-campaign:m8.4-v1".equals(report.get("schema"))||!"PUBLIC_TEST_POLICY_ONLY".equals(report.get("qualification"))||!Boolean.TRUE.equals(report.get("publicTestAuthority")))throw new IOException("NATIVE_WIRING_CAMPAIGN_SCOPE");
            var expected=new TreeSet<String>();for(Object value:GraphJson.array(checked.get("contexts"))){String name=GraphJson.string(GraphJson.object(value).get("name"));if(!name.equals("custom-broken"))expected.add(name);}
            var seen=new TreeSet<String>();long claims=0;for(Object value:GraphJson.array(report.get("contexts"))){var row=GraphJson.object(value);String name=GraphJson.string(row.get("name"));if(!expected.contains(name)||!seen.add(name))throw new IOException("NATIVE_WIRING_CAMPAIGN_CONTEXT");var receipt=read(handle(row.get("receipt")));if(!Objects.equals(row.get("graph"),receipt.get("graph")))throw new IOException("NATIVE_WIRING_CAMPAIGN_GRAPH");claims+=replay(name,handle(row.get("receipt")));}
            if(!seen.equals(expected))throw new IOException("NATIVE_WIRING_CAMPAIGN_INCOMPLETE");
            var broken=new FileGraphStore(root.resolve("prepared-graphs")).load(new ContentDigest(GraphJson.string(report.get("brokenGraph")))).orElseThrow();
            if(broken.evidence().stream().noneMatch(e->e.family().equals("java.native.failure-context")&&e.canonicalJson().contains("MissingType"))||!GraphJson.object(report.get("blocked")).equals(Map.of("name","custom-dependent","prerequisite","custom-broken","state","BLOCKED")))throw new IOException("NATIVE_WIRING_CAMPAIGN_BROKEN");
            System.out.println(CanonicalJson.write(Map.of("mode","replay","document",ref,"contexts",seen.size(),"verifiedClaims",claims,"publicTestAuthority",true)));return;}
        var contexts=new ArrayList<Object>();long history=0,active=0;for(Object value:GraphJson.array(checked.get("contexts"))){String name=GraphJson.string(GraphJson.object(value).get("name"));if(name.equals("custom-broken"))continue;var result=execute(name);var receipt=read(result.receipt());requireHealthy(receipt);history+=((Number)receipt.get("rawGapRecords")).longValue();active+=((Number)receipt.get("activeRawGaps")).longValue();contexts.add(Map.of("name",name,"receipt",result.receipt(),"graph",result.graph().identity(),"activeRawGaps",receipt.get("activeRawGaps")));System.out.println(CanonicalJson.write(Map.of("context",name,"graph",result.graph().identity(),"rawGaps",receipt.get("rawGapRecords"),"activeRawGaps",receipt.get("activeRawGaps"),"publicTestAuthority",true)));}
        var broken=broken();var store=new FileGraphStore(Files.createDirectories(root.resolve("prepared-graphs")));store.save(broken.graph(),()->false);var report=put(Map.of("schema","native-prepared-pipeline-campaign:m8.4-v1","contexts",contexts,"brokenGraph",broken.graph().identity(),"blocked",broken.blocked(),"healthyRawHistory",history,"healthyActiveRawGaps",active,"publicTestAuthority",true,"qualification","PUBLIC_TEST_POLICY_ONLY"));String encoded=CanonicalJson.write(report);if(Files.exists(pointer)){if(!Files.readString(pointer).equals(encoded))throw new IOException("NATIVE_WIRING_CAMPAIGN_CHANGED");}else Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);System.out.println(CanonicalJson.write(Map.of("mode","run","document",report,"contexts",contexts.size(),"healthyHistory",history,"healthyActiveGaps",active,"publicTestAuthority",true)));
    }
    public static void main(String[] args)throws IOException {if((args.length!=2&&args.length!=3)||!Set.of("run","replay").contains(args[0]))throw new IllegalArgumentException("run|replay native-root [vN]");new NativePipelineWiringFixture(Path.of(args[1])).campaign(args[0].equals("replay"),args.length==3?args[2]:"v2");}
    private VersionedIdentifier generator(String name){return switch(name){case "protobuf-schema"->new VersionedIdentifier("generator.protoc-grpc","3.25.5+1.68.1");case "mapstruct-mapper"->new VersionedIdentifier("generator.mapstruct","1.6.3");case "lombok-model"->new VersionedIdentifier("generator.lombok","1.18.36");case "custom-producer"->new VersionedIdentifier("generator.custom.generate",nativeInputs.get("source/custom/generator/Generate.java").digest().value());default->new VersionedIdentifier("compiler.javac",nativePlatform.runtime());};}
    private JavaEvidenceRecord input(String role,ContentHandle ref){return record("java-evidence-input","role",role,"content",ref,"locator",null,"provider",INDEX);}
    private JavaEvidenceRecord record(String kind,Object... pairs){var f=new LinkedHashMap<String,Object>();f.put("schema",kind+":m8.4-v1");for(int i=0;i<pairs.length;i+=2)f.put((String)pairs[i],pairs[i+1]);return codec.create(f);}
    private JavaEvidenceTables.Table table(String kind,Collection<JavaEvidenceRecord> rows)throws IOException{return tables.write(kind,rows.stream().distinct().sorted(JavaEvidenceTables::compare).iterator(),()->false);}
    private SourceInput source(ModuleDescriptor module,String path,ContentHandle bytes,SourceClassification role)throws IOException{return new SourceInput(SourceDocument.create(repository,module,path,bytes.digest(),role),bytes(bytes));}
    private ContentHandle put(Object value)throws IOException{
        byte[] bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);var expected=new ContentHandle(ContentDigest.sha256(bytes),bytes.length);
        if(written.contains(expected)){if(!content.verify(expected))throw new IOException("NATIVE_WIRING_WRITTEN_CONTENT_CHANGED");return expected;}
        var actual=content.put(new ByteArrayInputStream(bytes),()->false);if(!actual.equals(expected))throw new IOException("NATIVE_WIRING_CONTENT_BINDING");written.add(actual);return actual;
    }
    private ContentHandle pointer(String name)throws IOException{return handle(GraphJson.read(Files.readString(root.resolve(name)),GraphJson.Limits.defaults()));}
    private Path path(ContentHandle ref){String d=ref.digest().value().substring(7);return root.resolve("native-import-cas/sha256").resolve(d.substring(0,2)).resolve(d.substring(2));}
    private byte[] bytes(ContentHandle ref)throws IOException{if(!content.verify(ref)||ref.size()>32000000)throw new IOException("NATIVE_WIRING_CONTENT");try(var in=content.open(ref)){return in.readNBytes(Math.toIntExact(ref.size()+1));}}
    private Map<String,Object> read(ContentHandle ref)throws IOException {if(!content.verify(ref)||ref.size()>4000000)throw new IOException("NATIVE_WIRING_REFERENCE");try(var in=content.open(ref)){return GraphJson.object(GraphJson.read(new String(in.readNBytes(4000001),StandardCharsets.UTF_8),new GraphJson.Limits(4000000,64,4000000)));}}
    private static ContentHandle handle(Object v){return JavaEvidenceRecord.handle(v);}
    private static Map<String,ContentHandle> handles(Object v){var out=new TreeMap<String,ContentHandle>();GraphJson.object(v).forEach((k,r)->out.put(k,handle(r)));return out;}
    private String workRelative(String uri)throws IOException{String prefix="file:///work/"+cohort.get("attempt")+"/";if(!uri.startsWith(prefix))throw new IOException("NATIVE_WIRING_OUTPUT_URI");String relative=uri.substring(prefix.length());LogicalPath.utf8(relative);return relative;}
}
