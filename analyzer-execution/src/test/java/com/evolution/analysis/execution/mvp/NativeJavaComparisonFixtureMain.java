package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.execution.output.*;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fixed cohort comparison over two retained real captures. No VM start or semantic promotion. */
public final class NativeJavaComparisonFixtureMain {
    private static ContentHandle handle(Object value){return JavaEvidenceRecord.handle(value);}
    private static Map<String,Object> read(Path file,int limit)throws IOException{return CoreMvpBuildPipeline.read(file,limit);}
    private static Map<String,Object> object(StreamingContentStore store,ContentHandle ref)throws IOException{
        if(!store.verify(ref)||ref.size()>4_000_000)throw new IOException("NATIVE_COMPARISON_OBJECT_UNAVAILABLE");
        try(var stream=store.open(ref)){
            return GraphJson.object(GraphJson.read(new String(stream.readNBytes(4_000_001),StandardCharsets.UTF_8),new GraphJson.Limits(4_000_000,64,2_000_000)));
        }
    }
    private static ContentHandle put(StreamingContentStore store,Object value)throws IOException{
        return store.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);
    }
    private record Captured(Path root,StreamingContentStore store,Map<String,Object> profile,Map<String,Object> request,
            ContentHandle byteDocument,Map<String,Object> bytes,ContentHandle inputDocument,Map<String,Object> inputBinding,
            ContentHandle inputClosure,Map<String,Object> inputs){}
    private static Captured captured(Path directory)throws IOException{
        Path root=directory.toRealPath();CoreMvpRegistrations.require(root);
        var store=new StreamingContentStore(root.resolve("native-import-cas"));
        var byteDocument=handle(read(root.resolve("native-byte-import.json"),8192));var bytes=object(store,byteDocument);
        var inputDocument=handle(read(root.resolve("native-input-import.json"),8192));var binding=object(store,inputDocument);
        var profile=read(root.resolve("profile.json"),65_536);var request=read(root.resolve("provision-request.json"),65_536);
        if(!Objects.equals(bytes.get("attempt"),binding.get("attempt"))||!Objects.equals(bytes.get("attempt"),request.get("attempt"))
                ||!Boolean.TRUE.equals(bytes.get("nativeSuccess"))||!Boolean.FALSE.equals(bytes.get("qualified")))
            throw new IOException("NATIVE_COMPARISON_SUCCESSFUL_CAPTURE_REQUIRED");
        var profileRef=handle(binding.get("profile"));
        try(var input=Files.newInputStream(root.resolve("profile.json"),LinkOption.NOFOLLOW_LINKS)){
            if(!store.put(input,()->false).equals(profileRef))throw new IOException("NATIVE_COMPARISON_INPUT_BINDING_CHANGED");
        }
        var closure=handle(binding.get("inputClosure"));var inputs=object(store,closure);
        if(!"m844-native-logical-inputs:v1".equals(inputs.get("schema")))throw new IOException("NATIVE_COMPARISON_INPUT_SCHEMA");
        for(Object value:GraphJson.object(inputs.get("files")).values())if(!store.verify(handle(value)))throw new IOException("NATIVE_COMPARISON_INPUT_MISSING");
        var stopped=read(root.resolve("supervisor-result.json"),8192);
        var state=GraphJson.object(stopped.get("state"));
        if(!"CONTROLLER_PROTOCOL_COMPLETE".equals(stopped.get("reason"))||!"Off".equals(state.get("state"))
                ||!Objects.equals(request.get("attempt"),state.get("attempt"))||!Objects.equals(request.get("launch"),state.get("launch")))
            throw new IOException("NATIVE_COMPARISON_TERMINATION_UNCONFIRMED");
        return new Captured(root,store,profile,request,byteDocument,bytes,inputDocument,binding,closure,inputs);
    }
    private static ContentHandle environment(Captured captured)throws IOException{
        var image=GraphJson.object(captured.profile().get("image"));var nodes=new ArrayList<Object>();
        for(Object value:GraphJson.array(image.get("images"))){var node=GraphJson.object(value);nodes.add(Map.of("digest",node.get("digest"),"size",node.get("size")));}
        // Physical paths/VM/seed identities remain in the registered profile audit binding.
        return put(captured.store(),Map.of("schema","m844-registered-generation-context:v1","images",nodes,
                "enforcement",captured.profile().get("enforcement"),"controller",GraphJson.object(captured.profile().get("nativeFixture")).get("controller"),
                "resources",captured.request().get("resources")));
    }
    private static NativeOutputComparison.Revision revision(NativeOutputComparison comparison,Captured captured)throws IOException{
        var export=GraphJson.object(read(captured.root().resolve("export-request.json"),8192).get("request"));
        var expected=new OutputCensus.Scope(GraphJson.string(export.get("attempt")),GraphJson.string(export.get("nonce")),
                new OutputCensus.Budget(Math.toIntExact(CoreMvpBuildPipeline.number(export.get("maxRowBytes"))),CoreMvpBuildPipeline.number(export.get("maxObjectBytes")),CoreMvpBuildPipeline.number(export.get("maxCaptureBytes"))));
        return comparison.read(captured.store(),new NativeOutputComparison.Inputs(captured.inputClosure(),environment(captured)),
                handle(captured.bytes().get("header")),expected,10_000,()->false);
    }
    private static Optional<NativeOutputComparison.Role> role(String path){
        if(path.equals("native-cohort.json"))return Optional.of(NativeOutputComparison.Role.AUDIT);
        for(String stage:NativeJavaCensusFixtureMain.STAGES)if(!stage.equals("custom-dependent")
                &&(path.equals("stage-"+stage+".json")||path.equals("logs/"+stage+".log")))return Optional.of(NativeOutputComparison.Role.AUDIT);
        if(path.endsWith(".java")&&(path.startsWith("protobuf/generated/")||path.startsWith("mapstruct/generated/")||path.startsWith("custom/generated/java/")))
            return Optional.of(NativeOutputComparison.Role.OUTPUT);
        for(String prefix:List.of("protobuf/schema/","protobuf/client/","lombok/model/","lombok/client/","mapstruct/model/","mapstruct/mapper/","mapstruct/client/","custom/generator/","custom/producer/","custom/client/"))
            if(path.startsWith(prefix+"classes/")&&path.endsWith(".class"))return Optional.of(NativeOutputComparison.Role.OUTPUT);
        if(path.equals("custom/generated/resources/META-INF/evo/generated.properties"))return Optional.of(NativeOutputComparison.Role.OUTPUT);
        return Optional.empty();
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3||!Set.of("compare","replay").contains(args[0]))throw new IllegalArgumentException("compare|replay before-root after-root required");
        var before=captured(Path.of(args[1]));var after=captured(Path.of(args[2]));var comparison=new NativeOutputComparison();
        var files=new ArrayList<SnapshotFile>();
        for(var row:GraphJson.object(before.inputs().get("files")).entrySet())files.add(new SnapshotFile(row.getKey(),handle(row.getValue()).digest()));
        var repository=RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:m844-native-java-evidence:v1");
        var snapshot=RepositorySnapshot.create(repository,Optional.empty(),false,files,List.of());
        var roles=new TreeMap<LogicalPath,NativeOutputComparison.Role>();var left=revision(comparison,before);var right=revision(comparison,after);
        var paths=new TreeSet<>(left.paths());paths.addAll(right.paths());
        var filePaths=new TreeSet<LogicalPath>();for(String path:GraphJson.object(before.bytes().get("files")).keySet())filePaths.add(LogicalPath.utf8(path));for(String path:GraphJson.object(after.bytes().get("files")).keySet())filePaths.add(LogicalPath.utf8(path));
        for(var path:paths){
            if(!filePaths.contains(path))roles.put(path,NativeOutputComparison.Role.OUTPUT);
            else path.utf8().flatMap(NativeJavaComparisonFixtureMain::role).ifPresent(value->roles.put(path,value));
        }
        var result=comparison.compare(Map.of("baseSnapshot",snapshot.identity().value()),left,right,roles);
        // Retain original input/publication audit records alongside the comparison's captured byte refs.
        for(var ref:List.of(after.byteDocument(),after.inputDocument(),handle(after.inputBinding().get("profile")))){
            try(var input=after.store().open(ref)){if(!before.store().put(input,()->false).equals(ref))throw new IOException("NATIVE_COMPARISON_AUDIT_CHANGED");}
        }
        var document=put(before.store(),Map.of("schema","m844-native-comparison-binding:v1","beforeInputs",before.inputDocument(),
                "afterInputs",after.inputDocument(),"beforeNative",before.byteDocument(),"afterNative",after.byteDocument(),"comparison",result.canonicalForm()));
        String afterAttempt=GraphJson.string(after.bytes().get("attempt")).replace("-","");Path pointer=before.root().resolve("native-comparison-"+afterAttempt+".json");
        String encoded=CanonicalJson.write(document);
        if(args[0].equals("compare"))Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);
        else if(!Files.readString(pointer).equals(encoded))throw new IOException("NATIVE_COMPARISON_REPLAY_CHANGED");
        System.out.println(CanonicalJson.write(Map.of("mode",args[0],"document",document,"state",result.state(),"outputChanges",result.outputs().size(),
                "auditChanges",result.audit().size(),"unclassified",result.unclassified().size(),"gapReasons",result.gaps().stream().map(g->g.reasonCode()).toList(),
                "qualified",false,"semanticTrust","PRODUCER_ASSERTION")));
    }
}
