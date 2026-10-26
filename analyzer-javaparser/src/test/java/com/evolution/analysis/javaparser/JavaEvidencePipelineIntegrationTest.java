package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.execution.EvidenceVerifier;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.javaevidence.*;
import com.evolution.analysis.readiness.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Authored captured-data integration; this is not a native generator acceptance test. */
class JavaEvidencePipelineIntegrationTest {
    @TempDir Path root;
    private final JavaEvidenceCodec codec=new JavaEvidenceCodec();
    private StreamingContentStore content;private JavaEvidenceTables tables;
    private Map<String,Object> scope;private KeyPair controller,validator;private EvidenceVerifier verifier;
    private UniversalBuildModel nativeBuild;
    private final ContentDigest closure=ContentDigest.sha256Utf8("authored-input-closure");
    private final Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs=new HashMap<>();
    private JavaEvidenceRecord record(String kind,Object... pairs){var map=new LinkedHashMap<String,Object>();map.put("schema",kind+":m8.4-v1");for(int i=0;i<pairs.length;i+=2)map.put((String)pairs[i],pairs[i+1]);return codec.create(map);}
    private ContentHandle put(Object value)throws IOException{return content.put(new ByteArrayInputStream((value instanceof String s?s:CanonicalJson.write(value)).getBytes(StandardCharsets.UTF_8)),()->false);}
    private JavaEvidenceTables.Table table(String kind,List<JavaEvidenceRecord> rows)throws IOException{return tables.write(kind,rows.iterator(),()->false);}
    private JavaEvidenceAuthority.Verified proof(String question,String subject,String answer)throws Exception{
        var bytes=put(answer);var empty=put("");var checks=new ArrayList<Object>();for(String name:JavaEvidenceAuthority.requiredChecks(question)){var check=new LinkedHashMap<String,Object>();check.put("name",name);check.put("outcome","PASS");check.put("evidence",List.of(bytes));check.put("reason",null);checks.add(check);}
        var predicate=new VersionedIdentifier(question,JavaEvidenceAuthority.VERSION);var method=new VersionedIdentifier(question+".capture","1");
        var record=record("java-evidence-proof","scope",scope,"question",question,"subject",subject,"predicate",predicate,"validator",new VersionedIdentifier("fixture.validator","1"),"policy",verifier.policyIdentity(),"answer",bytes,"inputClosure",closure,"inputs",Map.of("schema","java-evidence-input-table:m8.4-v1","content",empty,"rows",0),"checks",checks,"outcome","ACCEPT","trust","VALIDATED_OBSERVATION","limitations",List.of());
        var claim=put(record.json());var context=new ContentDigest((String)scope.get("context"));var expected=new EvidenceVerifier.Expected(closure,closure,1,context,closure,claim,method,predicate,closure);
        var receipt=new EvidenceVerifier.Receipt("execution-protected-receipt:m8.4-v1","authored-contract",1,closure,closure,closure,closure,closure);
        var attestation=new EvidenceVerifier.Attestation("execution-protected-attestation:m8.4-v1",receipt.identity(),claim.digest(),context,closure,closure,method,predicate,List.copyOf(JavaEvidenceAuthority.requiredChecks(question)),true);
        var authenticated=verifier.verifyAccepted(expected,receipt,signature("controller",controller,receipt),attestation,signature("validator",validator,attestation),content).orElseThrow();
        var result=new JavaEvidenceAuthority(verifier.policyIdentity(),content).accept(record,authenticated).orElseThrow();proofs.put(result.identity(),result);return result;
    }
    private static EvidenceVerifier.SignatureEnvelope signature(String id,KeyPair key,Object data)throws Exception{var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(CanonicalJson.write(Map.of("keyId",id,"payload",data)).getBytes(StandardCharsets.UTF_8));return new EvidenceVerifier.SignatureEnvelope(id,Base64.getEncoder().encodeToString(signer.sign()));}
    private static FrontendRequest rebind(FrontendRequest request,List<BinaryInput> binaries,boolean bind){
        var previous=request.manifest();var entries=new ArrayList<com.evolution.analysis.contract.analysis.ClasspathEntry>();entries.add(request.platform().entry());binaries.forEach(b->entries.add(b.entry()));var options=new TreeMap<>(previous.configuration().values());options.putAll(FrontendRequest.binaryOriginOptions(binaries));if(!bind)options.remove("java.binary-native-origins");
        var manifest=com.evolution.analysis.contract.analysis.AnalysisManifest.create(previous.manifestVersion(),previous.snapshot(),previous.modules(),entries,com.evolution.analysis.contract.analysis.AnalysisConfiguration.create(previous.configuration().schema(),options),previous.analyzer(),previous.ruleSet(),previous.graphSchema());
        return new FrontendRequest(manifest,request.module(),request.sourceSet(),request.plan(),request.sources(),request.platform(),binaries);
    }
    private FrontendRequest nativeMemberRequest(String omitMember)throws Exception{
        content=new StreamingContentStore(root.resolve("member-cas"));tables=new JavaEvidenceTables(content,100000);
        scope=Map.of("inventory",closure.value(),"baseSnapshot","snapshot:"+closure.value(),"readiness",closure.value(),"selection",closure.value(),"context",closure.value(),"logicalScope",closure.value(),"module",TestInputs.MODULE.identity().value(),"sourceSet","main","variant","baseline");
        var generator=KeyPairGenerator.getInstance("Ed25519");controller=generator.generateKeyPair();validator=generator.generateKeyPair();
        var methods=List.of("java.resolution-context","java.binary-shape","java.source-binary-correspondence").stream().map(id->new EvidenceVerifier.Method(new VersionedIdentifier(id+".capture","1"),new VersionedIdentifier(id,JavaEvidenceAuthority.VERSION),closure,List.copyOf(JavaEvidenceAuthority.requiredChecks(id)))).toList();verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),methods);
        String client="class Client { Model make(){ return Model.builder().value(1).build(); } Model call(){ return new Model(2); } int keep(Model m){ return m.handwritten(); } }";
        if(omitMember.equals("INVALID_DEFAULT"))client=client.replace("new Model(2)","new Model()");
        if(omitMember.equals("SHADOWED_TYPE"))client=client.replace("return Model.builder()","String Model = \"\"; return Model.builder()");
        if(omitMember.equals("INVALID_INSTANCE_STATIC"))client=client.replace("m.handwritten()","Model.handwritten()");
        String model="class Model { int value; int handwritten(){ return 7; } }";
        if(omitMember.equals("RETURN_MISMATCH"))model=model.replace("int handwritten()","long handwritten()");
        if(omitMember.equals("FIELD_MISMATCH"))model=model.replace("int value;","long value;");
        if(omitMember.equals("STATIC_MISMATCH"))model=model.replace("int handwritten()","static int handwritten()");
        var sourceRequest=TestInputs.request(Map.of("fixture/Model.java",model,"fixture/Client.java",client),List.of());
        var actualScope=new LinkedHashMap<>(scope);actualScope.put("baseSnapshot",sourceRequest.manifest().snapshot().identity().value());scope=Map.copyOf(actualScope);
        var sourceInputs=new RepositoryInputs(sourceRequest.manifest().snapshot(),sourceRequest.sources().stream().collect(java.util.stream.Collectors.toMap(s->s.document().path(),s->s)));var plans=new ArrayList<SourcePlanModel.SourceSetPlan>();
        java.util.function.Function<String,SourcePlanModel.Setting> setting=value->new SourcePlanModel.Setting("captured.fixture",Optional.of(value),Optional.of(value),SourcePlanModel.Status.DECLARED,SourcePlanModel.Origin.EFFECTIVE_MODEL,List.of(new BuildModelResult.PomEvidence("authored-compiler-input",closure)));
        for(var kind:List.of(SourcePlanModel.Kind.MAIN,SourcePlanModel.Kind.TEST))plans.add(new SourcePlanModel.SourceSetPlan(sourceRequest.module(),kind,kind==SourcePlanModel.Kind.MAIN?List.of(setting.apply("fixture")):List.of(),List.of(),setting.apply("captured-classes"),Map.of(),setting.apply("21"),setting.apply("21"),setting.apply("21"),setting.apply("UTF-8"),List.of(),List.of()));
        nativeBuild=new UniversalBuildModel(closure,sourceInputs.identity(),List.of(new UniversalBuildModel.Module(TestInputs.MODULE,UniversalBuildModel.Tool.IMPORTED_SOURCE_PLAN,Optional.empty(),Optional.empty(),new SourcePlanModel(plans,List.of()),List.of(),List.of())),List.of(),List.of(),List.of());
        var baseline=new JavaParserFrontend().analyze(sourceRequest);var sourceEntities=baseline.declarations().stream().map(DeclarationRecord::entity).filter(e->e.declaration().isPresent()).toList();var owner=sourceEntities.stream().filter(e->e.kind()==EntityKind.TYPE&&e.canonicalName().equals(JavaSymbolName.topLevelType("","Model").canonicalName())).findFirst().orElseThrow();
        var fixed=new ResolutionInputsTest();fixed.temp=root.resolve("fixed-bytecode");var compiled=fixed.jar("model","class Model { int value; Model(int value){ this.value=value; } int handwritten(){ return 7; } static Builder builder(){ return new Builder(); } static class Builder { int value; Builder value(int v){ value=v; return this; } Model build(){ return new Model(value); } } }");
        ContentHandle nativeBytes;try(var input=Files.newInputStream(compiled.path())){nativeBytes=content.put(input,()->false);}var shape=proof("java.binary-shape",nativeBytes.digest().value(),"authored fixed-fixture binary shape");
        var slot=record("java-resolution-slot","scope",scope,"space","CLASS_PATH","ordinal",0,"logicalName","fixture:model:1@jar","kind","JAR","original",nativeBytes,"tree",null,"selectedView",null,"origin","PROJECT","module",TestInputs.MODULE.identity().value(),"proof",shape.identity(),"disposition","ACCEPTED","gaps",List.of());
        var manifest=record("java-resolution-manifest","scope",scope,"overlay",closure,"compiler",new VersionedIdentifier("fixture.compiler","21"),"sourceLanguage","21","bytecodeTarget","21","release","21","preview",false,"encoding","UTF-8","platform",sourceRequest.platform().entry().contentDigest(),"sourceMembership",table("java-evidence-input",List.of()).wire(),"slots",table("java-resolution-slot",List.of(slot)).wire(),"arguments",List.of(),"argfiles",List.of(),"contextProof",closure,"status","EXACT","gaps",List.of());
        var context=proof("java.resolution-context",closure.value(),CanonicalJson.write(JavaResolutionPlanner.answerFields(manifest)));var manifestFields=new LinkedHashMap<>(manifest.fields());manifestFields.put("contextProof",context.identity());manifest=codec.create(manifestFields);
        var binary=new com.evolution.analysis.filesystem.JavaBinaryViews(content,root.resolve("member-views"),new com.evolution.analysis.filesystem.JavaBinaryViews.Budget(100,100000,100000)).materialize(slot).withValidatedOrigin(slot,shape,BinaryInput.nativeCensus(manifest,context,content,100,()->false));
        var rowSpecs=List.of(new String[]{"Model","TYPE","Model","","SAME_DECLARATION"},new String[]{"Model","FIELD","value","I","SAME_DECLARATION"},new String[]{"Model","METHOD","handwritten","()I","SAME_DECLARATION"},new String[]{"Model","CONSTRUCTOR","<init>","(I)V","AUGMENTS"},new String[]{"Model","METHOD","builder","()LModel$Builder;","AUGMENTS"},new String[]{"Builder","TYPE","Builder","","AUGMENTS"},new String[]{"Builder","FIELD","value","I","AUGMENTS"},new String[]{"Builder","CONSTRUCTOR","<init>","()V","AUGMENTS"},new String[]{"Builder","METHOD","value","(I)LModel$Builder;","AUGMENTS"},new String[]{"Builder","METHOD","build","()LModel;","AUGMENTS"});
        var modelSource=sourceRequest.sources().stream().filter(s->s.document().path().endsWith("Model.java")).findFirst().orElseThrow();content.put(new ByteArrayInputStream(modelSource.bytes()),()->false);
        var rows=new ArrayList<JavaEvidenceRecord>();for(var spec:rowSpecs){if((spec[0]+"/"+spec[2]).equals(omitMember))continue;var sourceEntity=owner;if(spec[4].equals("SAME_DECLARATION")&&!spec[1].equals("TYPE")){var symbol=spec[1].equals("FIELD")?JavaSymbolName.field(JavaSymbolName.topLevelType("","Model"),"value"):JavaSymbolName.method(JavaSymbolName.topLevelType("","Model"),"handwritten",List.of());sourceEntity=sourceEntities.stream().filter(e->e.canonicalName().equals(symbol.canonicalName())).findFirst().orElseThrow();}
            var endpoint=Map.of("schema","java-binary-member:m8.4-v1","slot",slot.identity(),"package","","owners",spec[0].equals("Model")?List.of("Model"):List.of("Model","Builder"),"kind",spec[1],"name",spec[2],"descriptor",spec[3],"transformation",put("authored native transformation closure"));
            rows.add(record("java-correspondence","scope",scope,"sourceEvidence",put(sourceEntity),"binaryOrGeneratedEvidence",put(endpoint),"sourceContent",new ContentHandle(modelSource.document().contentDigest(),modelSource.bytes().length),"outputContent",nativeBytes,"outcome",spec[4],"predicate",new VersionedIdentifier("java.source-binary-correspondence",JavaEvidenceAuthority.VERSION),"proof",closure,"supportingSpans",List.of(),"reason",null));
        }
        var mappingProof=proof("java.source-binary-correspondence",slot.identity().value(),CanonicalJson.write(JavaSourceCorrespondence.answerFields(scope,slot,rows)));var mappingInputs=new ArrayList<JavaEvidenceRecord>();for(var row:rows){var data=new LinkedHashMap<>(row.fields());data.put("proof",mappingProof.identity());var mapped=codec.create(data);mappingInputs.add(record("java-evidence-input","role","CORRESPONDENCE","content",put(mapped.json()),"locator",null,"provider",new VersionedIdentifier("fixture.correspondence","1")));}mappingInputs.sort(JavaEvidenceTables::compare);
        var mapping=new JavaSourceCorrespondence(content).importTable(scope,table("java-evidence-input",mappingInputs),List.of(slot),sourceRequest.sources(),proofs,100,()->false);
        var plan=new FrontendPlan(Optional.of(21),Optional.of(21),false,manifest.identity(),closure);var original=sourceRequest.manifest();var options=new TreeMap<>(FrontendRequest.options(plan,sourceRequest.module(),sourceRequest.sourceSet(),sourceRequest.sources().stream().map(SourceInput::document).toList(),sourceRequest.platform()));options.putAll(FrontendRequest.binaryOriginOptions(List.of(binary)));options.putAll(FrontendRequest.correspondenceOptions(Optional.of(mapping)));options.put("java.native-context",closure.value());
        options.put("ingestion.build-model",nativeBuild.identity().value());
        var requestManifest=com.evolution.analysis.contract.analysis.AnalysisManifest.create(original.manifestVersion(),original.snapshot(),original.modules(),List.of(sourceRequest.platform().entry(),binary.entry()),com.evolution.analysis.contract.analysis.AnalysisConfiguration.create(original.configuration().schema(),options),original.analyzer(),original.ruleSet(),original.graphSchema());
        return new FrontendRequest(requestManifest,sourceRequest.module(),sourceRequest.sourceSet(),plan,sourceRequest.sources(),sourceRequest.platform(),List.of(binary),List.of(),Optional.of(mapping));
    }
    @Test void nativeMembersAugmentTheSourceOwnerWithoutReplacingHandwrittenDeclarations()throws Exception{
        var request=nativeMemberRequest("");var result=new JavaParserFrontend().analyze(request);
        var calls=result.observations().stream().filter(o->Set.of("java.calls","java.constructor-calls").contains(o.category().value())).toList();
        assertEquals(5,calls.size());assertTrue(calls.stream().allMatch(o->o.attribution()==SemanticStatus.RESOLVED),()->calls.toString());
        var type=JavaSymbolName.topLevelType("","Model");var constructor=JavaSymbolName.constructor(type,List.of(ErasedType.primitive("int"))).canonicalName();
        var nativeCtor=result.declarations().stream().filter(d->d.entity().canonicalName().equals(constructor)).findFirst().orElseThrow();assertEquals(EntityOrigin.PROJECT,nativeCtor.entity().origin());assertTrue(nativeCtor.entity().declaration().isEmpty());assertEquals(DerivationKind.DERIVED,nativeCtor.derivation().kind());
        var handwritten=result.declarations().stream().filter(d->d.entity().canonicalName().equals(JavaSymbolName.method(type,"handwritten",List.of()).canonicalName())).findFirst().orElseThrow();assertTrue(handwritten.entity().declaration().isPresent());assertEquals(DerivationKind.DIRECT,handwritten.derivation().kind());
        assertEquals(1,result.declarations().stream().filter(d->d.entity().canonicalName().equals(type.canonicalName())).count());assertTrue(result.declarations().stream().noneMatch(d->d.entity().canonicalName().equals(JavaSymbolName.constructor(type,List.of()).canonicalName())),"Native constructor census excludes a default constructor");
        assertEquals(CanonicalJson.sha256(result),CanonicalJson.sha256(new JavaParserFrontend().analyze(request)));
        assertThrows(FrontendInputException.class,()->new FrontendRequest(request.manifest(),request.module(),request.sourceSet(),request.plan(),request.sources(),request.platform(),request.dependencies(),List.of()),"The mapping capability cannot be removed from an otherwise bound request");
        var inputs=new RepositoryInputs(request.manifest().snapshot(),request.sources().stream().collect(java.util.stream.Collectors.toMap(s->s.document().path(),s->s)));var set=new UniversalSourceIngestion.SourceSet(request.module(),SourcePlanModel.Kind.MAIN);
        var configuration=new com.evolution.analysis.spring.condition.ConfigDataIngestion().ingest(inputs,com.evolution.analysis.spring.condition.ConfigDataIngestion.Policy.defaults());var sourceResult=new UniversalSourceIngestion.Result(closure,List.of(),List.of(new UniversalSourceIngestion.Outcome(set,Optional.of(request),configuration)),List.of(),List.of());
        var ingestion=new UniversalIngestionPipeline.Result(closure,sourceResult,List.of(new UniversalIngestionPipeline.Unit(set,UniversalIngestionPipeline.Status.ANALYZED,Optional.of(result),Optional.empty(),Optional.empty())),List.of(),List.of());
        var graph=new com.evolution.analysis.graph.CanonicalGraphBuilder().build(new com.evolution.analysis.graph.CanonicalGraphInput(inputs,Optional.of(nativeBuild),Optional.of(ingestion),List.of(),List.of(),List.of()));
        for(var mapping:request.correspondence().orElseThrow().mappings())assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.correspondence-endpoint")&&ContentDigest.sha256Utf8(e.canonicalJson()).equals(JavaEvidenceRecord.handle(mapping.record().fields().get("binaryOrGeneratedEvidence")).digest())));
        var graphCodec=new com.evolution.analysis.graph.GraphSnapshotCodec();assertEquals(graph.identity(),graphCodec.decode(graphCodec.encode(graph),GraphJson.Limits.defaults()).identity());
    }
    @Test void aSignedButIncompleteMemberCensusCannotHideMissingBytecodeMembers()throws Exception{
        var request=nativeMemberRequest("Builder/build");var failure=assertThrows(FrontendInputException.class,()->new JavaParserFrontend().analyze(request));assertEquals("frontend.native-correspondence-census-incomplete",failure.diagnostic().code());
    }
    @Test void aRevokedEndpointCannotBeReusedAfterRequestConstruction()throws Exception{
        var request=nativeMemberRequest("");var mapping=request.correspondence().orElseThrow().mappings().getFirst();var handle=JavaEvidenceRecord.handle(mapping.record().fields().get("binaryOrGeneratedEvidence"));String digest=handle.digest().value().substring(7);Files.writeString(root.resolve("member-cas/sha256").resolve(digest.substring(0,2)).resolve(digest.substring(2)),"corrupt endpoint");
        var failure=assertThrows(FrontendInputException.class,()->new JavaParserFrontend().analyze(request));assertEquals("frontend.native-correspondence-revoked",failure.diagnostic().code());
    }
    @Test void aRevokedNativeShapeCannotBeReusedAfterRequestConstruction()throws Exception{
        var request=nativeMemberRequest("");var proof=proofs.values().stream().filter(p->p.record().text("question").equals("java.binary-shape")).findFirst().orElseThrow();String digest=proof.answer().value().substring(7);Files.writeString(root.resolve("member-cas/sha256").resolve(digest.substring(0,2)).resolve(digest.substring(2)),"revoked binary shape");
        var failure=assertThrows(FrontendInputException.class,()->new JavaParserFrontend().analyze(request));assertEquals("frontend.native-origin-unverified",failure.diagnostic().code());
    }
    @Test void nativeEvidenceCannotInventADefaultConstructorOrIgnoreAValueShadowingAType()throws Exception{
        var request=nativeMemberRequest("INVALID_DEFAULT");var result=new JavaParserFrontend().analyze(request);assertTrue(result.observations().stream().anyMatch(o->o.category().value().equals("java.constructor-calls")&&o.attribution()!=SemanticStatus.RESOLVED));
        var shadow=nativeMemberRequest("SHADOWED_TYPE");var shadowResult=new JavaParserFrontend().analyze(shadow);assertTrue(shadowResult.observations().stream().anyMatch(o->o.category().value().equals("java.calls")&&o.attribution()!=SemanticStatus.RESOLVED));
    }
    @Test void aNativeInstanceMethodCannotBeCalledThroughATypeReceiver()throws Exception{
        var request=nativeMemberRequest("INVALID_INSTANCE_STATIC");var result=new JavaParserFrontend().analyze(request);
        assertTrue(result.observations().stream().anyMatch(o->o.category().value().equals("java.calls")&&o.reference().contains("handwritten")&&o.attribution()!=SemanticStatus.RESOLVED));
    }
    @Test void sameErasedEntityNameCannotHideReturnFieldOrStaticSignatureDisagreement()throws Exception{
        for(String mode:List.of("RETURN_MISMATCH","FIELD_MISMATCH","STATIC_MISMATCH")){
            var request=nativeMemberRequest(mode);var failure=assertThrows(FrontendInputException.class,()->new JavaParserFrontend().analyze(request),mode);assertEquals("frontend.native-correspondence-source-signature-mismatch",failure.diagnostic().code());
        }
    }
    @Test void capturedGeneratedDocumentReachesTheActualPipelineWithExactConstructorAndImmutableCaller()throws Exception{
        capturedGeneratedDocument(false);
    }
    @Test void aConsistentlyReboundCensusCannotSilentlyMoveAnOriginalSourceToAnotherModule()throws Exception{
        var failure=assertThrows(IOException.class,()->capturedGeneratedDocument(true));
        assertEquals("JAVA_ORIGINAL_SOURCE_MODULE_CHANGED",failure.getMessage());
    }
    @Test void malformedGeneratedEncodingRetainsItsCapturedDocumentAndSoundSiblingBytes()throws Exception{capturedGeneratedDocument(false,true);}
    private void capturedGeneratedDocument(boolean foreignOwner)throws Exception{capturedGeneratedDocument(foreignOwner,false);}
    private void capturedGeneratedDocument(boolean foreignOwner,boolean invalidEncoding)throws Exception{
        content=new StreamingContentStore(root.resolve("cas"));tables=new JavaEvidenceTables(content,100000);
        var module=ModuleDescriptor.create(TestInputs.REPO,".","fixture");var files=new TreeMap<String,SourceInput>();
        for(var entry:Map.of("build.gradle","plugins { java }\ndependencies { testRuntimeOnly 'fixture:provided:1' }\n","src/main/java/Client.java","class Client { Generated value = new Generated(1); int n(){ return dep.Library.number(); } }",".evo-generated/readme.txt","original namespace occupant").entrySet()){byte[] raw=entry.getValue().getBytes(StandardCharsets.UTF_8);files.put(entry.getKey(),new SourceInput(SourceDocument.create(TestInputs.REPO,module,entry.getKey(),ContentDigest.sha256(raw),SourceClassification.MAIN),raw));}
        if(foreignOwner){var caller=files.get("src/main/java/Client.java");var other=ModuleDescriptor.create(TestInputs.REPO,"other","other");
            files.put(caller.document().path(),new SourceInput(SourceDocument.create(TestInputs.REPO,other,caller.document().path(),caller.document().contentDigest(),caller.document().classification()),caller.bytes()));}
        var documents=files.values().stream().map(SourceInput::document).toList();var base=new RepositoryInputs(RepositorySnapshot.create(TestInputs.REPO,Optional.empty(),false,documents.stream().map(SnapshotFile::from).toList(),documents),files);var baseIdentity=base.identity();
        var original=new UniversalBuildIngestion().ingest(base,new UniversalBuildIngestion.Policy(Optional.of(21),Optional.of(21),Optional.of("UTF-8"),100,10000,1000,16),Optional.empty(),Map.of(),new BuildModelPolicy(List.of(),List.of(),Map.of(),10000,100,16));
        assertTrue(original.modules().getFirst().dependencies().stream().anyMatch(d->d.configuration().equals("testRuntimeOnly")));
        var inventory=new InventoryManifest(InventoryManifest.SCHEMA,TestInputs.REPO,closure,base.snapshot().contentDigest(),files.size(),files.size(),files.values().stream().mapToLong(f->f.bytes().length).sum(),0,Optional.of(base.snapshot().identity()),Optional.of(base.snapshot().contentDigest()));
        var nativeContext=ReadinessPlanner.context("module","root","main","baseline",List.of("src/main/java"),Map.of("compiler","21"),List.of(),inventory,true);
        var selection=new BuildContextSelection(BuildContextSelection.SCHEMA,Map.of(),List.of(nativeContext.id()),List.of(nativeContext.id()),List.of(),List.of());
        var readiness=new ReadinessPlan(ReadinessPlan.SCHEMA,inventory,selection,List.of(new ReadinessPlan.Root("root",".",ReadinessPlan.Family.GRADLE,List.of(),List.of())),List.of(new ReadinessPlan.Module("module","root",".","root",List.of("java"),List.of(),List.of())),List.of(nativeContext),List.of(),List.of(),new ReadinessPlan.OwnershipSummary(files.size(),Map.of("OWNED",(long)files.size()),put("authored ownership table")));
        scope=JavaEvidenceScopes.scope(readiness,nativeContext,module);
        var generator=KeyPairGenerator.getInstance("Ed25519");controller=generator.generateKeyPair();validator=generator.generateKeyPair();
        var methods=List.of("java.generated-lineage","java.resolution-context","java.binary-shape","java.semantic-occurrence").stream().map(id->new EvidenceVerifier.Method(new VersionedIdentifier(id+".capture","1"),new VersionedIdentifier(id,JavaEvidenceAuthority.VERSION),closure,List.copyOf(JavaEvidenceAuthority.requiredChecks(id)))).toList();verifier=new EvidenceVerifier(Map.of("controller",controller.getPublic()),Map.of("validator",validator.getPublic()),methods);
        var census=put("authored captured census");var tool=put("authored generator binary");var input=record("java-evidence-input","role","TOOL","content",tool,"locator",null,"provider",new VersionedIdentifier("fixture.generator","1"));
        var lineage=record("java-generation-lineage","scope",scope,"generator",new VersionedIdentifier("fixture.generator","1"),"compiler",new VersionedIdentifier("fixture.compiler","21"),"compilerApplicability","APPLICABLE","compilerApplicabilityProof",null,"toolchain",closure,"recipe",closure,"configuration",closure,"environment",closure,"nativeResolution",closure,"inputs",table("java-evidence-input",List.of(input)).wire(),"outputCensus",census,"inputClosure",closure,"closure","COMPLETE","gaps",List.of());
        var layout=Map.of("schema","java-generated-root-layout:m8.4-v1","scope",scope,"roots",List.of(Map.of("kind","RESOURCE","role","DECLARED_RESOURCE","sourceSet","main","root",LogicalPath.utf8("src/main/resources"),"encoding","UTF-8"),Map.of("kind","RESOURCE","role","generated-resource","sourceSet","main","root",LogicalPath.utf8("custom/generated-resources"),"encoding","UTF-8")));
        var rootProof=proof("java.generated-lineage",lineage.identity().value(),CanonicalJson.write(layout));var generated=invalidEncoding?content.put(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),()->false):put("class Generated { Generated(int value) {} }");
        var locator=Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8("custom/generated/Generated.java"));
        var row=record("java-output-row","key",closure,"scope",scope,"role","generated-java","expectation","REQUIRED","locator",locator,"kind","FILE","content",generated,"lineage",lineage.identity(),"rootProof",rootProof.identity(),"category","JAVA_SOURCE","disposition","ACCEPTED","reason",null);
        var outputFields=new LinkedHashMap<>(row.fields());outputFields.put("key",JavaGeneratedOverlay.outputKey(row));row=codec.create(outputFields);
        var resourceFields=new LinkedHashMap<>(row.fields());resourceFields.put("role","generated-resource");resourceFields.put("category","RESOURCE");resourceFields.put("content",put("feature.generated=true\n"));resourceFields.put("locator",Map.of("container",census,"members",List.of(),"path",LogicalPath.utf8("custom/generated-resources/application.properties")));var resource=codec.create(resourceFields);resourceFields.put("key",JavaGeneratedOverlay.outputKey(resource));resource=codec.create(resourceFields);var outputs=new ArrayList<>(List.of(row,resource));outputs.sort(JavaEvidenceTables::compare);
        var imported=new JavaGeneratedOverlay(content,100000).importOverlay(scope,census,table("java-generation-lineage",List.of(lineage)),table("java-output-row",outputs),proofs,true,100,()->false);assertEquals("COMPLETE",imported.overlay().text("closure"));
        var client=base.files().get("src/main/java/Client.java");var sourceMembership=new ArrayList<JavaEvidenceRecord>();
        for(var entry:List.of(Map.entry(put(client.document()),Map.entry(put(new String(client.bytes(),StandardCharsets.UTF_8)),"MAIN")),Map.entry(put(row.json()),Map.entry(generated,"GENERATED_MAIN")))){
            var payload=record("java-source-membership","scope",scope,"sourceEvidence",entry.getKey(),"content",entry.getValue().getKey(),"rootProof",rootProof.identity(),"sourceSet","main","role",entry.getValue().getValue(),"disposition","INCLUDED","reason",null);
            sourceMembership.add(record("java-evidence-input","role","SOURCE_MEMBERSHIP","content",put(payload.json()),"locator",null,"provider",new VersionedIdentifier("fixture.compiler","21")));
        }
        sourceMembership.sort(Comparator.comparing(r->JavaEvidenceRecord.handle(r.fields().get("content")).digest()));
        var platform=TestInputs.request("class Platform {}").platform();
        var dependencyFixture=new ResolutionInputsTest();dependencyFixture.temp=root.resolve("trusted-binary-fixture");var dependency=dependencyFixture.jar("library","package dep; public class Library { public static int number(){ return 1; } }");
        ContentHandle binaryBytes;try(var inputBytes=Files.newInputStream(dependency.path())){binaryBytes=content.put(inputBytes,()->false);}var binaryProof=proof("java.binary-shape",binaryBytes.digest().value(),"authored exact binary shape");
        var binaryFields=new LinkedHashMap<String,Object>();binaryFields.put("schema","java-resolution-slot:m8.4-v1");binaryFields.put("scope",scope);binaryFields.put("space","CLASS_PATH");binaryFields.put("ordinal",0);binaryFields.put("logicalName","org.springframework:spring-beans:6.2.0@jar");binaryFields.put("kind","JAR");binaryFields.put("original",binaryBytes);binaryFields.put("tree",null);binaryFields.put("selectedView",null);binaryFields.put("origin","DEPENDENCY");binaryFields.put("module",null);binaryFields.put("proof",binaryProof.identity());binaryFields.put("disposition","ACCEPTED");binaryFields.put("gaps",List.of());var binarySlot=codec.create(binaryFields);
        var repeatedFields=new LinkedHashMap<>(binarySlot.fields());repeatedFields.put("ordinal",1);var repeatedSlot=codec.create(repeatedFields);
        var manifest=record("java-resolution-manifest","scope",scope,"overlay",imported.overlay().identity(),"compiler",new VersionedIdentifier("fixture.compiler","21"),"sourceLanguage","21","bytecodeTarget","21","release","21","preview",false,"encoding","UTF-8","platform",platform.entry().contentDigest(),"sourceMembership",table("java-evidence-input",sourceMembership).wire(),"slots",table("java-resolution-slot",List.of(binarySlot,repeatedSlot)).wire(),"arguments",List.of(),"argfiles",List.of(),"contextProof",closure,"status","EXACT","gaps",List.of());
        var contextProof=proof("java.resolution-context",(String)scope.get("context"),CanonicalJson.write(JavaResolutionPlanner.answerFields(manifest)));var manifestFields=new LinkedHashMap<>(manifest.fields());manifestFields.put("contextProof",contextProof.identity());manifest=codec.create(manifestFields);
        var tables=new JavaEvidenceTables(content,100000);var requirements=JavaEvidencePreAnalysis.footprint(scope,imported.overlay(),manifest,tables,()->false);
        var pre=new JavaEvidenceLedger().publish(scope,put(CanonicalJson.write(requirements)),verifier.policyIdentity(),requirements,List.of(),List.of(),Optional.empty(),proofs,Set.of(),Set.of(),tables,()->false);
        var prepared=new JavaEvidenceAssembler(content,100000).prepare(base,readiness,nativeContext,original,module,imported.overlay(),manifest,platform,proofs,new com.evolution.analysis.filesystem.JavaBinaryViews(content,root.resolve("binary-views"),new com.evolution.analysis.filesystem.JavaBinaryViews.Budget(100,100000,100000)),pre,100,10000,()->false);
        assertEquals(baseIdentity,base.identity());assertEquals(client.document().identity(),prepared.inputs().files().get(client.document().path()).document().identity());
        if(invalidEncoding){
            assertTrue(content.verify(generated));assertTrue(prepared.inputs().snapshot().documents().stream().anyMatch(d->d.contentDigest().equals(generated.digest())));assertTrue(prepared.inputs().files().values().stream().noneMatch(s->s.document().contentDigest().equals(generated.digest())));
            var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();var result=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy);
            assertFalse(result.report().gaps().isEmpty());assertTrue(result.report().issues().stream().anyMatch(i->Set.of(IngestionEvidence.Reason.INPUT_UNAVAILABLE,IngestionEvidence.Reason.SOURCE_INCOMPLETE).contains(i.reason())));assertEquals(client.document().identity(),prepared.inputs().files().get(client.document().path()).document().identity());return;
        }
        var policy=UniversalArchitecturePipeline.Policy.builder(new JavaParserFrontend(),JavaParserFrontend.PROVIDER).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
        var result=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy);var graphInput=result.graphInput().orElseThrow();
        assertTrue(result.report().issues().stream().noneMatch(i->i.reason()==IngestionEvidence.Reason.CLASSPATH_RESOLUTION_REQUIRED),"The validated main context must not acquire an inactive testRuntimeOnly dependency");
        assertEquals("true",graphInput.ingestion().orElseThrow().sourceIngestion().outcomes().stream().filter(o->o.sourceSet().name().equals("main")).findFirst().orElseThrow().configuration().properties().get("feature.generated"));
        var frontend=graphInput.ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).findFirst().orElseThrow();
        var request=graphInput.ingestion().orElseThrow().sourceIngestion().outcomes().stream().flatMap(o->o.request().stream()).findFirst().orElseThrow();var framework=com.evolution.analysis.spring.SpringFrameworkEvidence.fromRequest(request);assertEquals(1,framework.artifacts().size());assertEquals("org.springframework:spring-beans:6.2.0@jar",framework.artifacts().getFirst().classpathLogicalName());assertFalse(framework.verifiedArtifact(framework.artifacts().getFirst()),"Authored bytes cannot obtain framework trust from their coordinates");
        var springBuild=com.evolution.analysis.spring.condition.SpringBuildContext.fromExactRequest(request,graphInput.ingestion().orElseThrow().sourceIngestion().identity());
        assertTrue(springBuild.containsFrameworkEvidence(framework),"The protected native origin must match without mistaking a per-slot solver view for its artifact");
        assertTrue(springBuild.containsArtifact(binaryBytes.digest()));
        var wrongFramework=new com.evolution.analysis.spring.SpringFrameworkEvidence(true,Optional.of(request.plan().classpathManifest()),List.of(new com.evolution.analysis.spring.SpringFrameworkEvidence.Artifact("org.springframework:spring-beans:6.2.0",ContentDigest.sha256Utf8("other native bytes"))));
        assertFalse(springBuild.containsFrameworkEvidence(wrongFramework));
        assertEquals(2,request.dependencies().size());assertEquals(request.dependencies().getFirst().originEntry(),request.dependencies().getLast().originEntry());assertNotEquals(request.dependencies().getFirst().path(),request.dependencies().getLast().path());assertThrows(FrontendInputException.class,()->rebind(request,List.of(request.dependencies().getFirst()),true));assertThrows(FrontendInputException.class,()->rebind(request,List.of(request.dependencies().getLast(),request.dependencies().getFirst()),true));assertThrows(FrontendInputException.class,()->rebind(request,request.dependencies(),false));
        var binary=request.dependencies().getFirst();assertThrows(FrontendInputException.class,()->new BinaryInput(new com.evolution.analysis.contract.analysis.ClasspathEntry(binary.entry().kind(),binary.entry().logicalName(),ContentDigest.sha256Utf8("foreign view")),binary.path(),binary.reactorModule(),binary.nativeOrigin()));
        var library=frontend.declarations().stream().map(DeclarationRecord::entity).filter(e->e.canonicalName().equals(JavaSymbolName.method(JavaSymbolName.topLevelType("dep","Library"),"number",List.of()).canonicalName())).findFirst().orElseThrow();assertEquals(com.evolution.analysis.contract.identity.EntityScope.external(EntityOrigin.DEPENDENCY,"org.springframework:spring-beans:6.2.0@jar",binaryBytes.digest()),library.stableScope());assertTrue(library.declaration().isEmpty());
        var call=frontend.occurrences().stream().filter(o->o.relationship().kind().value().equals("java.constructor-calls")&&o.status()==SemanticStatus.RESOLVED).findFirst().orElseThrow();
        var target=((RelationshipTarget.Resolved)call.relationship().target()).target();var declaration=frontend.declarations().stream().filter(d->d.entity().identity().equals(target)).findFirst().orElseThrow();
        assertEquals(JavaSymbolName.constructor(JavaSymbolName.topLevelType("","Generated"),List.of(ErasedType.primitive("int"))).canonicalName(),declaration.entity().canonicalName());assertEquals(EntityOrigin.PROJECT,declaration.entity().origin());assertEquals(client.document().identity(),call.span().document());
        assertEquals(new SourceSpan(client.document().identity(),1,34,1,50),call.span());
        var generatedDoc=prepared.inputs().files().values().stream().map(SourceInput::document).filter(d->d.classification()==SourceClassification.GENERATED_MAIN).findFirst().orElseThrow();assertTrue(generatedDoc.path().startsWith(".evo-generated-1/"));assertEquals(generatedDoc.identity(),declaration.entity().declaration().orElseThrow().document());assertNotEquals(base.snapshot().identity(),prepared.inputs().snapshot().identity());assertEquals(base.files().get(".evo-generated/readme.txt").document(),prepared.inputs().files().get(".evo-generated/readme.txt").document());
        assertEquals(prepared.view().identity(),result.report().inputIdentity());
        assertEquals(new SourceSpan(generatedDoc.identity(),1,19,1,42),declaration.entity().declaration().orElseThrow());
        assertEquals(UniversalArchitectureReport.Status.PARTIAL,result.report().status());assertNotEquals(UniversalArchitectureReport.ConfidenceLevel.FULL,result.report().metrics().confidence());
        assertTrue(result.report().gaps().stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_MAPPING_UNPROVEN")),"Readiness proofs cannot substitute for an absent post-analysis occurrence census");
        var graph=new com.evolution.analysis.graph.CanonicalGraphBuilder().build(graphInput);
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.view")&&e.canonicalJson().equals(prepared.view().json())));
        assertTrue(graph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.source-map")&&e.canonicalJson().contains(generatedDoc.identity().value())));
        var graphCodec=new com.evolution.analysis.graph.GraphSnapshotCodec();var restored=graphCodec.decode(graphCodec.encode(graph),GraphJson.Limits.defaults());assertEquals(graph.identity(),restored.identity());assertEquals(graph.evidence(),restored.evidence());
        // Deliberately incomplete independent labels: the source visibly contains calls,
        // while this captured census asserts zero. The real frontend must keep the gap open.
        var indexProvider=new VersionedIdentifier("compiler.authored-index","1");var empty=table("java-evidence-input",List.of());var indexedDocs=new ArrayList<JavaEvidenceRecord>();
        for(var source:request.sources()){
            try(var sourceBytes=new ByteArrayInputStream(source.bytes())){content.put(sourceBytes,()->false);}
            var categories=new TreeMap<String,Long>();FrontendRequest.CATEGORIES.forEach(c->categories.put("java."+c,0L));
            var doc=put(Map.of("schema","java-semantic-document:m8.4-v1","document",source.document().identity(),"content",new ContentHandle(source.document().contentDigest(),source.bytes().length),"disposition","CLOSED","categories",categories,"occurrences",empty.wire()));
            indexedDocs.add(record("java-evidence-input","role","SOURCE","content",doc,"locator",null,"provider",indexProvider));
        }
        indexedDocs.sort(JavaEvidenceTables::compare);var index=new TreeMap<String,Object>();index.put("schema","java-semantic-census:m8.4-v1");index.put("scope",scope);index.put("inputView",prepared.view().identity());index.put("analysis",frontend.analysis());index.put("provider",indexProvider);index.put("documents",table("java-evidence-input",indexedDocs).wire());
        var censusProof=proof("java.semantic-occurrence",JavaSemanticEvidence.censusKey(scope,prepared.view().identity(),frontend.analysis()).value(),CanonicalJson.write(index));index.put("proof",censusProof.identity());var captured=put(index);
        var postRun=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy,new JavaSemanticEvidence.Bundle(content,captured,proofs,100,()->false));
        assertEquals(frontend,newFrontend(postRun));assertTrue(postRun.report().gaps().stream().anyMatch(g->g.reasonCode().equals("SEMANTIC_MAPPING_UNPROVEN")));
        var postGraph=new com.evolution.analysis.graph.CanonicalGraphBuilder().build(postRun.graphInput().orElseThrow());assertTrue(postGraph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.post-projection")&&e.canonicalJson().contains("OPEN")));assertEquals(postGraph.identity(),graphCodec.decode(graphCodec.encode(postGraph),GraphJson.Limits.defaults()).identity());
        var postInput=postRun.graphInput().orElseThrow();var originalIngestion=postInput.ingestion().orElseThrow();
        var changedState=frontend.state()==FrontendResult.State.COMPLETED?FrontendResult.State.PARTIAL:FrontendResult.State.FAILED;
        var changedFrontend=new FrontendResult(frontend.analysis(),frontend.frontend(),changedState,frontend.declarations(),frontend.occurrences(),frontend.observations(),frontend.sources(),frontend.coverage(),frontend.diagnostics(),frontend.types(),frontend.annotations(),frontend.derivedRelationships(),frontend.typeDeclarations(),frontend.memberDeclarations());
        var changedUnits=originalIngestion.units().stream().map(u->new UniversalIngestionPipeline.Unit(u.sourceSet(),u.status(),u.frontend().map(f->changedFrontend),u.components(),u.constructors(),u.spring())).toList();
        var changedIngestion=new UniversalIngestionPipeline.Result(originalIngestion.inputIdentity(),originalIngestion.sourceIngestion(),changedUnits,originalIngestion.issues(),originalIngestion.gaps());
        assertThrows(IllegalArgumentException.class,()->new com.evolution.analysis.graph.CanonicalGraphInput(postInput.repository(),postInput.build(),Optional.of(changedIngestion),postInput.spring(),postInput.issues(),postInput.gaps(),postInput.acquisition(),postInput.javaEvidence()),"The same manifest cannot authorize a different frontend result");
        var budgeted=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy,new JavaSemanticEvidence.Bundle(content,captured,proofs,1,()->false));assertTrue(budgeted.report().gaps().stream().anyMatch(g->g.reasonCode().equals("WORKER_CAPACITY_REQUIRED")));assertEquals(frontend,newFrontend(budgeted));
        var cancelled=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy,new JavaSemanticEvidence.Bundle(content,captured,proofs,100,()->true));assertTrue(cancelled.report().gaps().stream().anyMatch(g->g.reasonCode().equals("CANCELLED_WITH_FRONTIER")));assertEquals(frontend,newFrontend(cancelled));
        var malformed=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy,new JavaSemanticEvidence.Bundle(content,put("not a canonical census"),proofs,100,()->false));assertTrue(malformed.report().gaps().stream().anyMatch(g->g.reasonCode().equals("VALIDATION_REJECTED")));assertEquals(frontend,newFrontend(malformed));
        // Inject one primary-provider gap, using independently fixed source/call labels.
        var site=new SourceSpan(client.document().identity(),1,68,1,88);assertEquals("dep.Library.number()",new String(client.bytes(),StandardCharsets.UTF_8).substring(67,87));
        var caller=frontend.declarations().stream().map(DeclarationRecord::entity).filter(e->e.canonicalName().equals(JavaSymbolName.method(JavaSymbolName.topLevelType("","Client"),"n",List.of()).canonicalName())).findFirst().orElseThrow();
        var answer=new TreeMap<String,Object>();answer.put("schema","java-compiler-attribution-row:m8.4-v1");answer.put("scope",scope);answer.put("inputView",prepared.view().identity());answer.put("primary",JavaParserFrontend.PROVIDER);answer.put("provider",indexProvider);answer.put("document",client.document().identity());answer.put("sourceContent",put(new String(client.bytes(),StandardCharsets.UTF_8)));answer.put("category","java.calls");answer.put("span",site);answer.put("caller",put(caller));answer.put("targets",List.of(put(library)));answer.put("status","RESOLVED");answer.put("recovery",false);
        proof("java.semantic-occurrence",JavaCompilerAttribution.subject(scope,prepared.view().identity(),client.document().identity(),"java.calls",site).value(),CanonicalJson.write(answer));
        var compilerRows=table("java-evidence-input",List.of(record("java-evidence-input","role","SOURCE","content",put(answer),"locator",null,"provider",indexProvider)));
        var compilerIndex=put(Map.of("schema","java-compiler-attribution:m8.4-v1","scope",scope,"inputView",prepared.view().identity(),"primary",JavaParserFrontend.PROVIDER,"provider",indexProvider,"rows",compilerRows.wire()));
        var compilerBundle=new JavaCompilerAttribution.Bundle(content,compilerIndex,proofs,100,()->false);
        SemanticFrontend primaryWithGap=req->withUnresolvedCall(new JavaParserFrontend().analyze(req),site);
        var gapPolicy=UniversalArchitecturePipeline.Policy.builder(primaryWithGap,JavaParserFrontend.PROVIDER).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
        var attributed=new UniversalArchitecturePipeline().runWithEvidence(prepared,gapPolicy,compilerBundle,Optional.empty());var attributedFrontend=newFrontend(attributed);
        assertEquals(JavaCompilerAttribution.PROVIDER,attributedFrontend.frontend());var restoredCall=attributedFrontend.occurrences().stream().filter(o->o.span().equals(site)&&o.relationship().kind().value().equals("java.calls")).findFirst().orElseThrow();assertEquals(new RelationshipTarget.Resolved(library.identity()),restoredCall.relationship().target());assertEquals(DerivationKind.DERIVED,restoredCall.derivation().kind());assertEquals(caller.identity(),restoredCall.relationship().source());assertNotEquals(frontend.analysis(),attributedFrontend.analysis());
        var attributedGraph=new com.evolution.analysis.graph.CanonicalGraphBuilder().build(attributed.graphInput().orElseThrow());assertTrue(attributedGraph.evidence().stream().anyMatch(e->e.family().equals("java.evidence.compiler-primary")&&e.canonicalJson().contains("UNRESOLVED")));assertEquals(attributedGraph.identity(),graphCodec.decode(graphCodec.encode(attributedGraph),GraphJson.Limits.defaults()).identity());
        var invalidCompiler=new JavaCompilerAttribution.Bundle(content,put("malformed compiler index"),proofs,100,()->false);var retained=new UniversalArchitecturePipeline().runWithEvidence(prepared,gapPolicy,invalidCompiler,Optional.empty());assertTrue(retained.report().gaps().stream().anyMatch(g->g.reasonCode().equals("VALIDATION_REJECTED")));assertTrue(newFrontend(retained).occurrences().stream().anyMatch(o->o.span().equals(site)&&o.status()==SemanticStatus.UNRESOLVED));assertEquals(attributedFrontend.declarations(),newFrontend(retained).declarations());
        var invalidSpan=new TreeMap<String,Object>();invalidSpan.put("document",site.document());invalidSpan.put("startLine","invalid");invalidSpan.put("startColumn",68);invalidSpan.put("endLine",1);invalidSpan.put("endColumn",88);
        var malformedAnswer=new TreeMap<>(answer);malformedAnswer.put("span",invalidSpan);
        var malformedRows=table("java-evidence-input",List.of(record("java-evidence-input","role","SOURCE","content",put(malformedAnswer),"locator",null,"provider",indexProvider)));
        var malformedIndex=put(Map.of("schema","java-compiler-attribution:m8.4-v1","scope",scope,"inputView",prepared.view().identity(),"primary",JavaParserFrontend.PROVIDER,"provider",indexProvider,"rows",malformedRows.wire()));
        var badSpanResult=new UniversalArchitecturePipeline().runWithEvidence(prepared,gapPolicy,new JavaCompilerAttribution.Bundle(content,malformedIndex,proofs,100,()->false),Optional.empty());
        assertTrue(badSpanResult.report().gaps().stream().anyMatch(g->g.reasonCode().equals("VALIDATION_REJECTED")),"Malformed captured coordinates must retain primary facts with a typed rejection");assertEquals(attributedFrontend.declarations(),newFrontend(badSpanResult).declarations());assertTrue(newFrontend(badSpanResult).occurrences().stream().anyMatch(o->o.span().equals(site)&&o.status()==SemanticStatus.UNRESOLVED));
        String compilerBytes=compilerIndex.digest().value().substring(7);Files.writeString(root.resolve("cas/sha256").resolve(compilerBytes.substring(0,2)).resolve(compilerBytes.substring(2)),"revoked compiler index");assertThrows(IllegalArgumentException.class,()->new com.evolution.analysis.graph.CanonicalGraphBuilder().build(attributed.graphInput().orElseThrow()));
        String capturedBytes=captured.digest().value().substring(7);Files.writeString(root.resolve("cas/sha256").resolve(capturedBytes.substring(0,2)).resolve(capturedBytes.substring(2)),"revoked captured census");assertThrows(IllegalArgumentException.class,()->new com.evolution.analysis.graph.CanonicalGraphBuilder().build(postInput));
        String proofBytes=rootProof.answer().value().substring(7);Files.writeString(root.resolve("cas/sha256").resolve(proofBytes.substring(0,2)).resolve(proofBytes.substring(2)),"corrupt captured proof answer");
        var invalidated=new UniversalArchitecturePipeline().runWithEvidence(prepared,policy);assertEquals(UniversalArchitectureReport.Status.TERMINAL_FAILURE,invalidated.report().status());assertTrue(invalidated.graphInput().orElseThrow().ingestion().isEmpty());assertTrue(invalidated.report().gaps().stream().anyMatch(g->g.reasonCode().equals("EVIDENCE_REVOKED")));
    }
    private static FrontendResult newFrontend(UniversalArchitecturePipeline.DetailedResult result){return result.graphInput().orElseThrow().ingestion().orElseThrow().units().stream().flatMap(u->u.frontend().stream()).findFirst().orElseThrow();}
    private static FrontendResult withUnresolvedCall(FrontendResult p,SourceSpan site){
        var old=p.occurrences().stream().filter(o->o.span().equals(site)&&o.relationship().kind().value().equals("java.calls")).findFirst().orElseThrow();
        var replacement=RelationshipOccurrence.create(SemanticRelationship.create(old.relationship().source(),old.relationship().kind(),new RelationshipTarget.Unresolved("dep.Library.number()")),site,0,SemanticStatus.UNRESOLVED,old.derivation(),List.of(new Uncertainty("java.unresolved","Authored primary gap",List.of("compiler.binding"))),List.of());
        var occurrences=p.occurrences().stream().map(o->o.identity().equals(old.identity())?replacement:o).toList();var observations=p.observations().stream().map(o->o.mappedOccurrence().equals(Optional.of(old.identity()))?new ObservationRecord(o.document(),o.category(),o.span(),SemanticStatus.UNRESOLVED,o.origin(),o.provenance(),Optional.of(replacement.identity()),o.reference(),List.of()):o).toList();
        return new FrontendResult(p.analysis(),p.frontend(),p.state(),p.declarations(),occurrences,observations,p.sources(),p.coverage(),p.diagnostics(),p.types(),p.annotations(),p.derivedRelationships(),p.typeDeclarations(),p.memberDeclarations());
    }
}
