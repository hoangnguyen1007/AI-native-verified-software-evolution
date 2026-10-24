package com.evolution.analysis.javaparser;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
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
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Native-data continuation through the real architecture pipeline.
 * Signed claims use an explicit public TEST authority, never production qualification.
 * Saved graphs retain the raw obligations; this is not N01-N06 acceptance.
 */
public final class NativeJavaArchitectureFixtureMain {
    private final Path root;private final StreamingContentStore content;
    private NativeJavaArchitectureFixtureMain(Path root)throws IOException {this.root=root.toRealPath();content=new StreamingContentStore(this.root.resolve("native-import-cas"));}
    private static ContentHandle handle(Object value){return com.evolution.analysis.javaevidence.JavaEvidenceRecord.handle(value);}
    private static Map<String,ContentHandle> handles(Object value){var result=new TreeMap<String,ContentHandle>();GraphJson.object(value).forEach((key,item)->result.put(key,handle(item)));return result;}
    private Map<String,Object> read(ContentHandle ref)throws IOException {
        if(!content.verify(ref)||ref.size()>4_000_000)throw new IOException("NATIVE_ARCHITECTURE_CONTENT_REQUIRED");
        try(var in=content.open(ref)){return GraphJson.object(GraphJson.read(new String(in.readNBytes(4_000_001),StandardCharsets.UTF_8),new GraphJson.Limits(4_000_000,64,4_000_000)));}
    }
    private byte[] bytes(ContentHandle ref)throws IOException {if(!content.verify(ref)||ref.size()>32_000_000)throw new IOException("NATIVE_ARCHITECTURE_BYTES_REQUIRED");try(var in=content.open(ref)){return in.readNBytes(Math.toIntExact(ref.size()+1));}}
    private ContentHandle put(Object value)throws IOException {return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    private Path path(ContentHandle ref){String hex=ref.digest().value().substring(7);return root.resolve("native-import-cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));}
    private ContentHandle directory(Map<String,ContentHandle> files)throws IOException {
        var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes)){
            for(var entry:new TreeMap<>(files).entrySet()){LogicalPath.utf8(entry.getKey());var member=new JarEntry(entry.getKey());member.setTime(0);jar.putNextEntry(member);jar.write(bytes(entry.getValue()));jar.closeEntry();}
        }return content.put(new ByteArrayInputStream(bytes.toByteArray()),()->false);
    }
    private void run()throws IOException {
        var binding=handle(GraphJson.read(Files.readString(root.resolve("native-context-check-v2.json")),new GraphJson.Limits(8192,16,8192)));
        var checked=read(binding);if(!"m844-native-context-check:v2".equals(checked.get("schema"))||!Boolean.FALSE.equals(checked.get("semanticAttestation")))throw new IOException("NATIVE_ARCHITECTURE_VALIDATION_BINDING");
        var post=read(handle(checked.get("nativePostIndex")));var blocked=GraphJson.array(post.get("blocked"));
        if(blocked.size()!=1||!GraphJson.object(blocked.getFirst()).equals(Map.of("name","custom-dependent","prerequisite","custom-broken","state","BLOCKED")))throw new IOException("NATIVE_ARCHITECTURE_BLOCKED_DEPENDENT");
        var platformReport=GraphJson.object(checked.get("platform"));var manifest=handle(platformReport.get("manifest"));var materialRows=GraphJson.array(read(manifest).get("files"));
        var materials=new TreeMap<String,ContentHandle>();for(Object value:materialRows){var row=GraphJson.object(value);materials.put(GraphJson.string(row.get("path")),handle(row));}
        var validator=new NativeContextValidator(content,()->false);var nativePlatform=validator.platform(manifest,GraphJson.array(platformReport.get("image")).stream().map(NativeJavaArchitectureFixtureMain::handle).toList(),materials);
        if(!CanonicalJson.sha256(nativePlatform.report()).equals(CanonicalJson.sha256(platformReport)))throw new IOException("NATIVE_ARCHITECTURE_PLATFORM_CHANGED");
        var platformArtifacts=nativePlatform.selectedModules().stream().sorted().map(name->{var ref=materials.get("jmods/"+name+".jmod");return new PlatformInput.Artifact(name,ref.digest(),path(ref),PlatformInput.Format.JMOD);}).toList();
        var platform=PlatformInput.create(21,nativePlatform.runtime(),GraphJson.string(read(manifest).get("vendor")),platformArtifacts);
        var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:m844-native-java-evidence:v1");
        var ownership=new NativeFixtureOwnership(repository,post,handle(post.get("cohort")),read(handle(post.get("cohort"))),content);
        var ownershipDocument=put(ownership.report());var claims=new NativeFixtureClaims(content);
        var reports=new ArrayList<Object>();long totalNodes=0,totalEdges=0,totalObservations=0;int unavailable=0;
        var graphStore=new FileGraphStore(Files.createDirectories(root.resolve("architecture-graphs")).toRealPath());
        for(Object value:GraphJson.array(checked.get("contexts"))){
            var row=GraphJson.object(value);String name=GraphJson.string(row.get("name"));var validation=GraphJson.object(row.get("validation"));var index=handle(validation.get("capture"));var raw=read(index);
            var module=ownership.module(name);
            var sources=handles(validation.get("sources"));var artifacts=new TreeMap<String,ContentHandle>();
            for(var artifact:GraphJson.object(raw.get("artifacts")).entrySet())artifacts.put(artifact.getKey(),handle(artifact.getValue()));
            var slots=new ArrayList<NativeContextValidator.Slot>();for(Object slotValue:GraphJson.array(validation.get("orderedClasspath"))){var slot=GraphJson.object(slotValue);slots.add(new NativeContextValidator.Slot(GraphJson.string(slot.get("path")),NativeContextValidator.Slot.Kind.valueOf(GraphJson.string(slot.get("kind"))),handles(slot.get("files"))));}
            var options=GraphJson.array(raw.get("options")).stream().map(GraphJson::string).toList();
            var captured=new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(nativePlatform.runtime(),options,sources,artifacts),4_000_000,()->false);
            var revalidated=validator.validate(captured,slots,nativePlatform);if(!CanonicalJson.sha256(revalidated.report()).equals(CanonicalJson.sha256(validation)))throw new IOException("NATIVE_ARCHITECTURE_CONTEXT_CHANGED");
            var inputs=new TreeMap<String,SourceInput>();var sourceUris=new TreeMap<SourceDocumentIdentity,String>();
            for(var source:sources.entrySet()){
                String uri=source.getKey();String logical;
                if(uri.startsWith("file:///opt/evo-inputs/")){int at=uri.indexOf("/source/");if(at<0)throw new IOException("NATIVE_ARCHITECTURE_SOURCE_PATH");logical=uri.substring(at+8);}
                else if(uri.startsWith("file:///work/")){String relative=uri.substring("file:///work/".length());int at=relative.indexOf('/');if(at<0)throw new IOException("NATIVE_ARCHITECTURE_SOURCE_PATH");logical=".evo-native/"+relative.substring(at+1);}
                else throw new IOException("NATIVE_ARCHITECTURE_SOURCE_PATH");
                LogicalPath.utf8(logical);var document=SourceDocument.create(repository,module,logical,source.getValue().digest(),uri.startsWith("file:///work/")?SourceClassification.GENERATED_MAIN:SourceClassification.MAIN);
                if(inputs.putIfAbsent(logical,new SourceInput(document,bytes(source.getValue())))!=null)throw new IOException("NATIVE_ARCHITECTURE_SOURCE_COLLISION");sourceUris.put(document.identity(),uri);
            }
            var documents=inputs.values().stream().map(SourceInput::document).toList();var snapshot=RepositorySnapshot.create(repository,Optional.empty(),false,documents.stream().map(SnapshotFile::from).toList(),documents);var repositoryInputs=new RepositoryInputs(snapshot,inputs);
            var binaries=new ArrayList<BinaryInput>();int ordinal=0;for(var slot:slots){var binary=slot.kind()==NativeContextValidator.Slot.Kind.JAR?slot.files().get(""):directory(slot.files());
                var owner=ownership.owner(slot);
                binaries.add(new BinaryInput(new ClasspathEntry(owner.isPresent()?ClasspathEntryKind.MODULE_OUTPUT:ClasspathEntryKind.DEPENDENCY,"captured-slot:"+ordinal+++":"+slot.path(),binary.digest()),path(binary),owner));}
            var set=new UniversalSourceIngestion.SourceSet(module.identity(),SourcePlanModel.Kind.MAIN);
            var resolution=new UniversalSourceIngestion.Resolution(platform,binaries,CanonicalJson.sha256(Map.of("validation",validation,"ownership",ownership.report(),"mapping",new VersionedIdentifier("fixture.native-symbol-mapping","1"))));
            SemanticFrontend fixtureFrontend=request->ownership.derived(new JavaParserFrontend().analyze(request),request,sourceUris);
            var policy=UniversalArchitecturePipeline.Policy.builder(fixtureFrontend,JavaParserFrontend.PROVIDER).defaultPlatform(platform).resolutions(Map.of(set,resolution)).truthEvaluationMode(UniversalArchitecturePipeline.TruthEvaluationMode.NONE).build();
            var result=new UniversalArchitecturePipeline().runWithEvidence(repositoryInputs,policy);var graphInput=result.graphInput().orElseThrow(()->new IOException("NATIVE_ARCHITECTURE_GRAPH_ABSENT"));
            var frontend=graphInput.ingestion().orElseThrow().units().stream().filter(unit->unit.sourceSet().equals(set)).flatMap(unit->unit.frontend().stream()).findFirst().orElseThrow(()->new IOException("NATIVE_ARCHITECTURE_FRONTEND_ABSENT"));
            var request=graphInput.ingestion().orElseThrow().sourceIngestion().outcomes().stream().filter(outcome->outcome.sourceSet().equals(set)).flatMap(outcome->outcome.request().stream()).findFirst().orElseThrow();
            var mappings=new NativeJavaSymbolMapping(captured,request,frontend,sourceUris,slots).compare();var mappingDocument=put(mappings.report());
            var census=new OccurrenceCensusVerifier().verify(captured,frontend,sourceUris,List.copyOf(inputs.values()));var censusDocument=put(census.report());unavailable+=((Number)census.report().get("unavailableNativeCategories")).intValue();
            var eligibility=canonicalEligibility(captured,frontend,sourceUris,List.copyOf(inputs.values()),census);var eligibilityDocument=put(eligibility);
            var issued=claims.issue(binding,ownershipDocument,validation,captured,request,frontend,sourceUris,slots,materials.values());var claimsDocument=put(issued);
            var rawGaps=captured.gaps(com.evolution.analysis.evidence.EvidenceContext.forSnapshot(snapshot.identity()));var gaps=new TreeSet<>(graphInput.gaps());gaps.addAll(rawGaps);
            // Unqualified raw obligations stay on the saved graph; a successful parser run cannot close them.
            var candidateInput=new CanonicalGraphInput(graphInput.repository(),graphInput.build(),graphInput.ingestion(),graphInput.spring(),graphInput.issues(),List.copyOf(gaps),graphInput.acquisition());
            var graph=new CanonicalGraphBuilder().build(candidateInput);graphStore.save(graph,()->false);var loaded=graphStore.load(graph.identity()).orElseThrow();
            if(!loaded.identity().equals(graph.identity()))throw new IOException("NATIVE_ARCHITECTURE_GRAPH_REPLAY");
            totalNodes+=graph.nodes().size();totalEdges+=graph.edges().size();totalObservations+=frontend.observations().size();
            var counts=new TreeMap<String,Long>();for(var observation:frontend.observations())counts.merge(observation.attribution().name(),1L,Math::addExact);
            var summary=new TreeMap<String,Object>();summary.put("name",name);summary.put("capture",index);summary.put("graph",graph.identity());summary.put("census",censusDocument);summary.put("censusClosed",census.censusClosed());summary.put("canonicalEligibility",eligibilityDocument);summary.put("eligibleLocationsClosed",eligibility.get("eligibleLocationsClosed"));summary.put("excludedImportSites",GraphJson.array(eligibility.get("excludedImports")).size());
            summary.put("mapping",mappingDocument);summary.put("pendingTargets",mappings.report().get("pendingTargets"));summary.put("pendingCallers",mappings.report().get("pendingCallers"));summary.put("pendingOrigins",mappings.report().get("pendingOrigins"));
            summary.put("fixtureClaims",claimsDocument);summary.put("issuedSemanticClaims",issued.get("issued"));summary.put("withheldSemanticClaims",issued.get("withheld"));
            summary.put("endpointComparison",GraphJson.object(census.report().get("endpointComparison")).entrySet().stream().filter(e->!e.getKey().equals("sites")&&!e.getKey().equals("limitations")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,Map.Entry::getValue)));summary.put("nodes",graph.nodes().size());summary.put("edges",graph.edges().size());summary.put("observationStates",counts);summary.put("rawGapRecords",rawGaps.size());summary.put("primaryPipelineStatus",result.report().status());summary.put("status","PARTIAL");summary.put("nativeAcceptance","NOT_ESTABLISHED");summary.put("semanticAttestation",false);reports.add(Collections.unmodifiableMap(summary));
            System.out.println(CanonicalJson.write(Map.of("context",name,"nodes",graph.nodes().size(),"edges",graph.edges().size(),"censusClosed",census.censusClosed(),"targetSignatureDisagreements",GraphJson.object(census.report().get("endpointComparison")).get("targetSignatureDisagreements"),"callerSignatureDisagreements",GraphJson.object(census.report().get("endpointComparison")).get("callerSignatureDisagreements"),"nativeAcceptance","NOT_ESTABLISHED")));
        }
        if(reports.size()!=11)throw new IOException("NATIVE_ARCHITECTURE_CONTEXT_DENOMINATOR");
        assertSlice(reports);
        var report=put(Map.of("schema","m844-native-architecture-fixture:v8","contextValidation",binding,"ownership",ownershipDocument,"contexts",reports,"blockedContexts",blocked,"status","PARTIAL","semanticAttestation",false,"publicTestAuthority",true));
        String encoded=CanonicalJson.write(report);Path pointer=root.resolve("native-architecture-check-v8.json");if(Files.exists(pointer)){if(!Files.readString(pointer).equals(encoded))throw new IOException("NATIVE_ARCHITECTURE_REPLAY_CHANGED");}else Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);
        System.out.println(CanonicalJson.write(Map.of("document",report,"contexts",reports.size(),"nodes",totalNodes,"edges",totalEdges,"observations",totalObservations,"unavailableCategoryContexts",unavailable,"semanticAttestation",false)));
    }
    private void replay()throws IOException {
        var pointer=handle(GraphJson.read(Files.readString(root.resolve("native-architecture-check-v8.json")),new GraphJson.Limits(8192,16,8192)));var report=read(pointer);
        if(!"m844-native-architecture-fixture:v8".equals(report.get("schema"))||!Boolean.FALSE.equals(report.get("semanticAttestation"))||!Boolean.TRUE.equals(report.get("publicTestAuthority"))||!"PARTIAL".equals(report.get("status")))throw new IOException("NATIVE_ARCHITECTURE_REPLAY_SCOPE");
        var validation=read(handle(report.get("contextValidation")));var platform=GraphJson.object(validation.get("platform"));var material=read(handle(platform.get("manifest")));
        for(Object value:GraphJson.array(material.get("files")))if(!content.verify(handle(GraphJson.object(value))))throw new IOException("NATIVE_ARCHITECTURE_REPLAY_MATERIAL");
        var claims=new NativeFixtureClaims(content);var graphs=new FileGraphStore(root.resolve("architecture-graphs"));int contexts=0;long nodes=0,edges=0,semanticClaims=0;
        for(Object value:GraphJson.array(report.get("contexts"))){var context=GraphJson.object(value);var graph=graphs.load(new ContentDigest(GraphJson.string(context.get("graph")))).orElseThrow();
            var census=read(handle(context.get("census")));if(GraphJson.object(census.get("categories")).size()!=18||!Boolean.FALSE.equals(census.get("semanticAttestation"))||!Objects.equals(context.get("censusClosed"),census.get("censusClosed")))throw new IOException("NATIVE_ARCHITECTURE_REPLAY_CENSUS");
            var eligibility=read(handle(context.get("canonicalEligibility")));if(!Objects.equals(context.get("eligibleLocationsClosed"),eligibility.get("eligibleLocationsClosed"))||!Boolean.FALSE.equals(eligibility.get("semanticAttestation")))throw new IOException("NATIVE_ARCHITECTURE_REPLAY_ELIGIBILITY");
            var mapping=read(handle(context.get("mapping")));for(String field:List.of("pendingTargets","pendingCallers","pendingOrigins"))if(!Objects.equals(mapping.get(field),context.get(field)))throw new IOException("NATIVE_ARCHITECTURE_REPLAY_MAPPING");
            semanticClaims+=claims.replay(read(handle(context.get("fixtureClaims"))));
            if(graph.nodes().size()!=((java.math.BigDecimal)context.get("nodes")).longValueExact()||graph.edges().size()!=((java.math.BigDecimal)context.get("edges")).longValueExact())throw new IOException("NATIVE_ARCHITECTURE_REPLAY_COUNTS");nodes+=graph.nodes().size();edges+=graph.edges().size();contexts++;
        }
        if(contexts!=11||GraphJson.array(report.get("blockedContexts")).size()!=1)throw new IOException("NATIVE_ARCHITECTURE_REPLAY_DENOMINATOR");
        assertSlice(GraphJson.array(report.get("contexts")));if(semanticClaims!=2276)throw new IOException("NATIVE_ARCHITECTURE_REPLAY_CLAIMS");
        System.out.println(CanonicalJson.write(Map.of("mode","replay","document",pointer,"contexts",contexts,"nodes",nodes,"edges",edges,"verifiedFixtureSemanticClaims",semanticClaims,"verifiedFixtureContextClaims",contexts,"semanticAttestation",false)));
    }
    private static void assertSlice(List<Object> reports)throws IOException {
        long issued=0,withheld=0;for(Object value:reports){var row=GraphJson.object(value);for(String field:List.of("pendingTargets","pendingCallers","pendingOrigins"))if(((Number)row.get(field)).longValue()!=0)throw new IOException("NATIVE_ARCHITECTURE_PENDING_MAPPING");issued+=((Number)row.get("issuedSemanticClaims")).longValue();withheld+=((Number)row.get("withheldSemanticClaims")).longValue();}
        if(issued!=2276||withheld!=4)throw new IOException("NATIVE_ARCHITECTURE_SLICE_DENOMINATOR");
        var protobuf=reports.stream().map(GraphJson::object).filter(r->r.get("name").equals("protobuf-schema")).findFirst().orElseThrow();if(!Boolean.TRUE.equals(protobuf.get("eligibleLocationsClosed")))throw new IOException("NATIVE_ARCHITECTURE_PROTOBUF_CONSTRUCTORS");
    }
    /** Independent source parsing accounts for imports excluded by the existing M2 catalog.
     * Raw native observations remain intact and separately replayable. No semantic predicate is issued.
     */
    static Map<String,Object> canonicalEligibility(CapturedJavacReader.Capture capture,FrontendResult independent,
            Map<SourceDocumentIdentity,String> uris,List<SourceInput> inputs,OccurrenceCensusVerifier.Result census)throws IOException {
        var regions=new TreeMap<String,List<SourceSpan>>();var sourceIndex=new TreeMap<String,SourceInput>();
        for(var input:inputs){String uri=uris.get(input.document().identity());sourceIndex.put(uri,input);regions.put(uri,importSpans(input));}
        var excluded=new ArrayList<Object>();var excludedLocations=new HashSet<String>();
        for(var row:capture.observations()){
            if(!"java.type-uses".equals(row.get("category"))||row.get("span")==null)continue;
            String uri=GraphJson.string(row.get("source"));var span=GraphJson.object(row.get("span"));
            int sl=((Number)span.get("startLine")).intValue(),sc=((Number)span.get("startColumn")).intValue(),el=((Number)span.get("endLine")).intValue(),ec=((Number)span.get("endColumn")).intValue();
            var imported=regions.get(uri).stream().filter(s->(s.startLine()<sl||s.startLine()==sl&&s.startColumn()<=sc)&&(s.endLine()>el||s.endLine()==el&&s.endColumn()>=ec)).findFirst();
            if(imported.isEmpty())continue;
            String location=CanonicalJson.write(Map.of("source",uri,"startLine",sl,"startColumn",sc,"endLine",el,"endColumn",ec));excludedLocations.add(location);
            var input=sourceIndex.get(uri);excluded.add(Map.of("nativeObservation",CanonicalJson.sha256(row),"source",uri,"sourceContent",new ContentHandle(input.document().contentDigest(),input.bytes().length),"span",row.get("span"),"importSpan",imported.orElseThrow(),"disposition","OUTSIDE_M2_IMPORT_SCOPE"));
        }
        boolean closed=capture.traversalComplete()&&capture.state().equals("PARTIAL")&&independent.state()==FrontendResult.State.COMPLETED&&independent.sources().stream().allMatch(s->s.state()==SourceOutcome.State.PROCESSED);
        closed&=((Number)census.report().get("unavailableNativeCategories")).intValue()==0&&capture.observations().stream().allMatch(row->row.get("span")!=null)&&independent.observations().stream().allMatch(row->row.span().isPresent());
        var remaining=new TreeMap<String,Object>();int excludedRawOnly=0;
        for(var entry:GraphJson.object(census.report().get("categories")).entrySet()){
            var category=GraphJson.object(entry.getValue());var unmatched=GraphJson.array(category.get("nativeOnly")).stream().map(GraphJson::string).toList();
            var pending=unmatched.stream().filter(site->!entry.getKey().equals("java.type-uses")||!excludedLocations.contains(site)).toList();
            excludedRawOnly+=unmatched.size()-pending.size();remaining.put(entry.getKey(),pending);
            closed&=pending.isEmpty()&&GraphJson.array(category.get("independentOnly")).isEmpty()&&((Number)category.get("nativeDuplicates")).longValue()==0&&((Number)category.get("independentDuplicates")).longValue()==0&&!"UNAVAILABLE".equals(category.get("nativeState"));
            closed&=independent.coverage().stream().filter(c->c.category().value().equals(entry.getKey())).findFirst().orElseThrow().support()!=CategoryCoverage.Support.UNSUPPORTED;
        }
        // An import emitted by the canonical provider is a catalog disagreement, never an exclusion.
        closed&=excludedRawOnly==excluded.size();
        return Map.of("schema","java-native-canonical-eligibility:m8.4-v1","catalog",new VersionedIdentifier("java.m2.outside-imports","1"),"provider",JavaParserFrontend.PROVIDER,"capture",capture.document(),"independentResult",CanonicalJson.sha256(independent),"excludedImports",List.copyOf(excluded),"remainingNativeOnly",Collections.unmodifiableMap(remaining),"eligibleLocationsClosed",closed,"semanticAttestation",false);
    }
    static List<SourceSpan> importSpans(SourceInput input)throws IOException {
        try{
            var translated=UnicodeSource.translate(input.text());var parser=new com.github.javaparser.JavaParser(new com.github.javaparser.ParserConfiguration().setLanguageLevel(com.github.javaparser.ParserConfiguration.LanguageLevel.JAVA_21).setTabSize(1).setPreprocessUnicodeEscapes(false));
            var result=parser.parse(translated.text);if(!result.isSuccessful()||result.getResult().isEmpty())throw new IOException("NATIVE_ELIGIBILITY_SOURCE_PARSE_REQUIRED");
            var source=new OriginalSource(input.document().identity(),input.text(),translated);return result.getResult().orElseThrow().getImports().stream().map(source::span).sorted().toList();
        }catch(IllegalArgumentException invalid){throw new IOException("NATIVE_ELIGIBILITY_SOURCE_POSITION_REQUIRED",invalid);}
    }
    /** Fixed source labels registered after inspecting the pinned generated source, before repairing the native walker. */
    private void writtenSites()throws IOException {
        var binding=handle(GraphJson.read(Files.readString(root.resolve("native-context-check-v2.json")),new GraphJson.Limits(8192,16,8192)));
        var checked=read(binding);var context=GraphJson.array(checked.get("contexts")).stream().map(GraphJson::object).filter(row->"protobuf-schema".equals(row.get("name"))).findFirst().orElseThrow();
        var validation=GraphJson.object(context.get("validation"));var raw=read(handle(validation.get("capture")));var sources=handles(validation.get("sources"));int count=0;
        for(var label:List.of(List.of("EchoGrpc.java","54","EchoStub","java.type-argument","CLASS","fixture.proto.EchoGrpc$EchoStub"),
                List.of("Reply.java","399","com.google.protobuf.InvalidProtocolBufferException e","java.declares","EXCEPTION_PARAMETER","fixture.proto.Reply$Builder"))){
            String uri=sources.keySet().stream().filter(path->path.endsWith("/"+label.get(0))).findFirst().orElseThrow();
            var lines=new String(bytes(sources.get(uri)),StandardCharsets.UTF_8).lines().toList();int line=Integer.parseInt(label.get(1));String text=lines.get(line-1);int start=text.indexOf(label.get(2));
            if(start<0||text.indexOf(label.get(2),start+1)>=0)throw new IOException("NATIVE_WRITTEN_LABEL_SOURCE");
            var matches=GraphJson.array(raw.get("observations")).stream().map(GraphJson::object).filter(row->{
                if(!uri.equals(row.get("source"))||!label.get(3).equals(row.get("category"))||row.get("span")==null||row.get("target")==null)return false;
                var span=GraphJson.object(row.get("span"));var target=GraphJson.object(row.get("target"));
                return ((Number)span.get("startLine")).intValue()==line&&((Number)span.get("endLine")).intValue()==line
                        &&((Number)span.get("startColumn")).intValue()==start+1&&((Number)span.get("endColumn")).intValue()==start+label.get(2).length()+1
                        &&label.get(4).equals(target.get("kind"))&&label.get(5).equals(target.get("owner"));
            }).toList();
            if(matches.size()!=1)throw new IOException("NATIVE_WRITTEN_LABEL_MISSING:"+label.get(0)+":"+line+":"+label.get(3));count++;
        }
        System.out.println(CanonicalJson.write(Map.of("mode","written-sites","contextValidation",binding,"labels",count,"semanticAttestation",false)));
    }
    public static void main(String[] args)throws IOException {
        if(args.length==1)new NativeJavaArchitectureFixtureMain(Path.of(args[0])).run();
        else if(args.length==2&&args[0].equals("replay"))new NativeJavaArchitectureFixtureMain(Path.of(args[1])).replay();
        else if(args.length==2&&args[0].equals("written-sites"))new NativeJavaArchitectureFixtureMain(Path.of(args[1])).writtenSites();
        else throw new IllegalArgumentException("[replay|written-sites] validated-native-data-root");
    }
}
