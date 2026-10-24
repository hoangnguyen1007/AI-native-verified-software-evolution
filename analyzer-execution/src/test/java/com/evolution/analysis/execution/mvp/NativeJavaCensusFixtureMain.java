package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Non-executing native fixture import/replay through the production byte census.
 * Neither native exit nor this fixture can issue a semantic attestation.
 */
public final class NativeJavaCensusFixtureMain {
    private static final Set<String> REQUIRED = Set.of(
            "protobuf/generated/fixture/proto/Request.java", "protobuf/generated/fixture/proto/EchoGrpc.java",
            "protobuf/schema/classes/fixture/proto/Request.class", "protobuf/schema/classes/fixture/proto/EchoGrpc.class",
            "protobuf/client/classes/fixture/ProtoCaller.class", "lombok/model/classes/fixture/Person.class",
            "lombok/model/classes/fixture/Person$PersonBuilder.class", "lombok/client/classes/fixture/LombokCaller.class",
            "mapstruct/generated/fixture/PersonMapperImpl.java", "mapstruct/mapper/classes/fixture/PersonMapperImpl.class",
            "mapstruct/client/classes/fixture/MapStructCaller.class", "custom/generated/java/fixture/Generated.java",
            "custom/generated/resources/META-INF/evo/generated.properties", "custom/producer/classes/fixture/Generated.class",
            "custom/client/classes/fixture/CustomCaller.class", "native-cohort.json");
    static final Set<String> STAGES = Set.of(
            "java-version", "javac-version", "maven-version", "protoc-version", "protobuf-generate", "protobuf-schema",
            "protobuf-client", "protobuf-consumer", "lombok-model", "lombok-client", "lombok-consumer", "mapstruct-model",
            "mapstruct-generate", "mapstruct-client", "mapstruct-consumer", "custom-generator-compile", "custom-generate",
            "custom-producer", "custom-client", "custom-broken", "custom-dependent", "custom-consumer-after-broken");
    static final Set<String> PROCESSOR_FAILURE_STAGES=Set.of("partial-processor-compile","partial-processor-failure","custom-consumer-after-processor-failure");

    private static Map<String,Object> read(Path source, int limit)throws IOException {
        return CoreMvpBuildPipeline.read(source, limit);
    }
    private static ContentHandle capture(StreamingContentStore content, Path source)throws IOException {
        try(var input=Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)){return content.put(input,()->false);}
    }
    private static Map<String,Object> object(RepositoryContentStore content, ContentHandle source)throws IOException {
        if(!content.verify(source)||source.size()>4_000_000)throw new IOException("NATIVE_FIXTURE_OBJECT_INVALID");
        try(var stream=content.open(source)){
            return GraphJson.object(GraphJson.read(new String(stream.readNBytes(4_000_001),StandardCharsets.UTF_8),
                    new GraphJson.Limits(4_000_000,64,2_000_000)));
        }
    }
    private static void scope(Map<String,Object> owner, Map<String,Object> state)throws IOException {
        for(String key:List.of("attempt","vmId","launch","fence"))if(!Objects.equals(owner.get(key),state.get(key)))
            throw new IOException("NATIVE_FIXTURE_TERMINATION_SCOPE");
        if(!"Off".equals(state.get("state")))throw new IOException("NATIVE_FIXTURE_TERMINATION_UNCONFIRMED");
    }
    static final Set<String> INDEX_CONTEXTS=Set.of("protobuf-schema","protobuf-client","lombok-model","lombok-client",
            "mapstruct-model","mapstruct-mapper","mapstruct-client","custom-generator","custom-producer","custom-client","custom-broken");
    static Map<String,ContentHandle> validatePostIndex(RepositoryContentStore content, Map<String,ContentHandle> files,
                                                      String attempt)throws IOException {
        var post=object(content,files.get("native-post-index.json"));
        if(!"m844-native-post-index:v1".equals(post.get("schema"))||!attempt.equals(post.get("attempt"))
                ||!handle(post.get("cohort")).equals(files.get("native-cohort.json"))
                ||!Boolean.FALSE.equals(post.get("qualified"))||!"PRODUCER_ASSERTION".equals(post.get("semanticTrust")))
            throw new IOException("NATIVE_INDEX_SCOPE");
        var outputs=new TreeMap<String,ContentHandle>();
        for(var entry:GraphJson.object(post.get("outputs")).entrySet()){
            if(!entry.getKey().matches("index/[a-z-]+\\.json"))throw new IOException("NATIVE_INDEX_OUTPUT_PATH");
            var actual=handle(entry.getValue());if(!actual.equals(files.get(entry.getKey())))throw new IOException("NATIVE_INDEX_OUTPUT_CHANGED");
            outputs.put(entry.getKey(),actual);
        }
        var seen=new HashSet<String>();var expected=new TreeMap<String,ContentHandle>();
        for(Object value:GraphJson.array(post.get("contexts"))){
            var row=GraphJson.object(value);String name=GraphJson.string(row.get("name"));
            if(!INDEX_CONTEXTS.contains(name)||!seen.add(name)||!("index/"+name+".json").equals(row.get("output")))
                throw new IOException("NATIVE_INDEX_CONTEXT_DENOMINATOR");
            String state=GraphJson.string(row.get("captureState"));long exit=CoreMvpBuildPipeline.number(row.get("exit"));
            if(!Set.of("CAPTURED","PROCESS_FAILED","OUTPUT_UNAVAILABLE").contains(state)
                    ||state.equals("CAPTURED")&&(exit!=0||row.get("captured")==null)
                    ||state.equals("PROCESS_FAILED")&&exit==0||state.equals("OUTPUT_UNAVAILABLE")&&(exit!=0||row.get("captured")!=null))
                throw new IOException("NATIVE_INDEX_PROCESS_DISPOSITION");
            if(row.get("captured")!=null)expected.put(GraphJson.string(row.get("output")),handle(row.get("captured")));
        }
        if(!seen.equals(INDEX_CONTEXTS)||!outputs.equals(expected))throw new IOException("NATIVE_INDEX_CONTEXT_DENOMINATOR");
        var classes=GraphJson.object(post.get("toolClasses"));
        var toolFiles=new TreeMap<String,ContentHandle>();files.forEach((name,ref)->{if(name.startsWith("index-tool-classes/"))toolFiles.put(name.substring("index-tool-classes/".length()),ref);});
        if(classes.isEmpty()||!classes.keySet().equals(toolFiles.keySet()))throw new IOException("NATIVE_INDEX_TOOL_DENOMINATOR");
        for(var entry:classes.entrySet())
            if(!handle(entry.getValue()).equals(files.get("index-tool-classes/"+entry.getKey())))throw new IOException("NATIVE_INDEX_TOOL_CHANGED");
        return Collections.unmodifiableMap(outputs);
    }
    private static void validateNative(StreamingContentStore content, Map<String,ContentHandle> files,
                                       String attempt)throws IOException {
        var cohort=object(content,files.get("native-cohort.json"));
        if(!"m844-authored-native-cohort:v1".equals(cohort.get("schema"))||!attempt.equals(cohort.get("attempt"))
                ||!Boolean.FALSE.equals(cohort.get("qualified"))||!"PRODUCER_ASSERTION".equals(cohort.get("semanticTrust")))
            throw new IOException("NATIVE_FIXTURE_COHORT_SCOPE");
        var seen=new HashSet<String>();
        boolean processorFailure=GraphJson.array(cohort.get("stages")).stream().map(GraphJson::object).anyMatch(s->PROCESSOR_FAILURE_STAGES.contains(s.get("name")));
        var expectedStages=new HashSet<>(STAGES);if(processorFailure)expectedStages.addAll(PROCESSOR_FAILURE_STAGES);
        for(Object value:GraphJson.array(cohort.get("stages"))){
            var stage=GraphJson.object(value);String name=GraphJson.string(stage.get("name"));
            if(!expectedStages.contains(name)||!seen.add(name))throw new IOException("NATIVE_FIXTURE_STAGE_DENOMINATOR");
            if(name.equals("custom-dependent")){
                if(!"BLOCKED".equals(stage.get("state"))||!"custom-broken".equals(stage.get("prerequisite")))
                    throw new IOException("NATIVE_FIXTURE_DEPENDENT_DISPOSITION");
            }else{
                long expected=name.equals("custom-broken")||name.equals("partial-processor-failure")?1:0;
                if(!"COMPLETED".equals(stage.get("state"))||CoreMvpBuildPipeline.number(stage.get("exit"))!=expected
                        ||CoreMvpBuildPipeline.number(stage.get("expected"))!=expected)
                    throw new IOException("NATIVE_FIXTURE_STAGE_EXIT");
            }
        }
        if(!seen.equals(expectedStages))throw new IOException("NATIVE_FIXTURE_STAGE_DENOMINATOR");
        if(processorFailure){
            var partial=files.get("partialprocessor/generated/fixture/GeneratedBeforeFailure.java");var log=files.get("logs/partial-processor-failure.log");
            if(partial==null||log==null||log.size()>1_048_576||files.containsKey("partialprocessor/client/classes/fixture/GeneratedBeforeFailure.class"))throw new IOException("NATIVE_FIXTURE_PARTIAL_PROCESSOR_DISPOSITION");
            try(var stream=content.open(log)){if(!new String(stream.readNBytes(1_048_577),StandardCharsets.UTF_8).contains("EVO_EXPECTED_PARTIAL_PROCESSOR_FAILURE"))throw new IOException("NATIVE_FIXTURE_PARTIAL_PROCESSOR_DIAGNOSTIC");}
        }
        var declared=new TreeMap<String,ContentHandle>();
        for(Object value:GraphJson.array(cohort.get("outputs"))){
            var row=GraphJson.object(value);String path=GraphJson.string(row.get("path"));
            var handle=new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),CoreMvpBuildPipeline.number(row.get("size")));
            if(declared.putIfAbsent(path,handle)!=null||!handle.equals(files.get(path)))throw new IOException("NATIVE_FIXTURE_OUTPUT_CHANGED");
        }
        var expected=new TreeMap<>(files);expected.remove("native-cohort.json");
        if(files.containsKey("native-post-index.json")){
            var extra=validatePostIndex(content,files,attempt);expected.remove("native-post-index.json");extra.keySet().forEach(expected::remove);
        }
        if(!declared.equals(expected))throw new IOException("NATIVE_FIXTURE_OUTPUT_DENOMINATOR");
        var diagnostic=files.get("logs/custom-broken.log");
        if(diagnostic==null||diagnostic.size()>1_048_576)throw new IOException("NATIVE_FIXTURE_DIAGNOSTIC_REQUIRED");
        try(var stream=content.open(diagnostic)){
            String text=new String(stream.readNBytes(1_048_577),StandardCharsets.UTF_8);
            if(!text.contains("Broken.java:3:5:")||!text.contains("compiler.err.cant.resolve.location")||!text.contains("MissingType"))
                throw new IOException("NATIVE_FIXTURE_DIAGNOSTIC_MISMATCH");
        }
    }
    private static void inputs(Path root,Map<String,Object> setup,String attempt,boolean replay)throws IOException {
        var content=new StreamingContentStore(root.resolve("native-import-cas"));Path pointer=root.resolve("native-input-import.json");
        if(replay){
            var handle=handle(read(pointer,8192));var binding=object(content,handle);
            if(!attempt.equals(binding.get("attempt"))||!"m844-native-input-binding:v1".equals(binding.get("schema")))
                throw new IOException("NATIVE_FIXTURE_INPUT_REPLAY_SCOPE");
            var closure=handle(binding.get("inputClosure"));var stable=object(content,closure);
            for(Object value:GraphJson.object(stable.get("files")).values())if(!content.verify(handle(value)))
                throw new IOException("NATIVE_FIXTURE_INPUT_REPLAY_MISSING");
            System.out.println(CanonicalJson.write(Map.of("mode","inputs-replay","document",handle,"inputClosure",closure,
                    "files",GraphJson.object(stable.get("files")).size(),"qualified",false)));
            return;
        }
        var profile=read(root.resolve("profile.json"),65_536);var registered=GraphJson.object(profile.get("nativeFixture"));
        if(!"m844-authored-native-fixture:v1".equals(registered.get("schema")))throw new IOException("NATIVE_FIXTURE_INPUT_SCOPE");
        Path workspace=Path.of(GraphJson.string(setup.get("workspace"))).toRealPath();
        Path sourceRoot=workspace.resolve("analyzer-execution/src/test/resources/native/java-evidence").toRealPath();
        Path materialRoot=Path.of(GraphJson.string(registered.get("materials"))).toRealPath();
        if(!materialRoot.getParent().equals(workspace.resolve(".evo/executor").toRealPath())
                ||!materialRoot.getFileName().toString().matches("m844-native-materials-[a-f0-9]{8,32}"))
            throw new IOException("NATIVE_FIXTURE_MATERIAL_ROOT");
        var files=new TreeMap<String,ContentHandle>();
        for(Object value:GraphJson.array(registered.get("inputs"))){
            var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("path","size","digest")))throw new IOException("NATIVE_FIXTURE_INPUT_FIELDS");
            String path=GraphJson.string(row.get("path"));
            if(!path.matches("(source|materials)/[A-Za-z0-9_./-]+")
                    &&!Set.of("tooling/CapturedJavacIndex.java","tooling/native_index_control.py").contains(path))throw new IOException("NATIVE_FIXTURE_INPUT_PATH");
            LogicalPath.utf8(path);String relative=path.substring(path.indexOf('/')+1);
            Path base=path.startsWith("source/")?sourceRoot:materialRoot;
            if(path.startsWith("tooling/"))base=workspace.resolve(path.endsWith(".java")
                    ?"analyzer-execution/src/main/java/com/evolution/analysis/execution/javac":"analyzer-execution/src/test/python").toRealPath();
            Path selected=base.resolve(relative).normalize();
            if(!selected.startsWith(base)||!selected.toString().substring(base.toString().length()+1).replace('\\','/').equals(relative))
                throw new IOException("NATIVE_FIXTURE_INPUT_PATH");
            for(Path ancestor=selected;ancestor!=null;ancestor=ancestor.getParent())if(Files.isSymbolicLink(ancestor))
                throw new IOException("NATIVE_FIXTURE_INPUT_ALIAS");
            var actual=capture(content,selected);
            var expected=new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),CoreMvpBuildPipeline.number(row.get("size")));
            if(!actual.equals(expected)||files.putIfAbsent(path,actual)!=null)throw new IOException("NATIVE_FIXTURE_INPUT_CHANGED");
        }
        byte[] stable=CanonicalJson.write(Map.of("schema","m844-native-logical-inputs:v1","files",files)).getBytes(StandardCharsets.UTF_8);
        var closure=content.put(new ByteArrayInputStream(stable),()->false);
        byte[] encoded=CanonicalJson.write(Map.of("schema","m844-native-input-binding:v1","attempt",attempt,
                "inputClosure",closure,"profile",capture(content,root.resolve("profile.json")),"semanticTrust","PRODUCER_ASSERTION",
                "qualified",false)).getBytes(StandardCharsets.UTF_8);
        var binding=content.put(new ByteArrayInputStream(encoded),()->false);
        Files.writeString(pointer,CanonicalJson.write(binding),StandardOpenOption.CREATE_NEW);
        System.out.println(CanonicalJson.write(Map.of("mode","inputs","document",binding,"inputClosure",closure,"files",files.size(),"qualified",false)));
    }
    private static ContentHandle handle(Object value){return com.evolution.analysis.javaevidence.JavaEvidenceRecord.handle(value);}
    public static void main(String[] args)throws Exception {
        if(args.length!=2||!Set.of("import","replay","inputs","inputs-replay").contains(args[0]))throw new IllegalArgumentException("import|replay|inputs|inputs-replay registered-root required");
        Path root=Path.of(args[1]).toRealPath();CoreMvpRegistrations.require(root);var setup=read(root.resolve("setup.json"),65_536);
        String attempt=UUID.fromString(GraphJson.string(setup.get("attempt"))).toString(),workAttempt=attempt.replace("-","");
        var owner=read(root.resolve("owner-"+workAttempt+".json"),8192);Path captured=root.resolve("capture");
        scope(owner,read(captured.resolve("worker-off.json"),8192));
        var supervisor=read(root.resolve("supervisor-result.json"),8192);
        if(!"CONTROLLER_PROTOCOL_COMPLETE".equals(supervisor.get("reason")))throw new IOException("NATIVE_FIXTURE_PROTOCOL_INCOMPLETE");
        scope(owner,GraphJson.object(supervisor.get("state")));
        if(args[0].startsWith("inputs")){inputs(root,setup,attempt,args[0].equals("inputs-replay"));return;}
        var request=GraphJson.object(read(root.resolve("export-request.json"),8192).get("request"));
        if(!workAttempt.equals(request.get("attempt")))throw new IOException("NATIVE_FIXTURE_EXPORT_SCOPE");
        var budget=new OutputCensus.Budget(Math.toIntExact(CoreMvpBuildPipeline.number(request.get("maxRowBytes"))),
                CoreMvpBuildPipeline.number(request.get("maxObjectBytes")),CoreMvpBuildPipeline.number(request.get("maxCaptureBytes")));
        var expectedScope=new OutputCensus.Scope(workAttempt,GraphJson.string(request.get("nonce")),budget);
        var content=new StreamingContentStore(root.resolve("native-import-cas"));
        capture(content,captured.resolve("scope.json"));capture(content,captured.resolve("entries.ndjson"));
        var header=capture(content,captured.resolve("collector-result.json"));var parser=new OutputCensus(content);
        var files=new TreeMap<String,ContentHandle>();
        var verified=parser.verify(parser.header(header,expectedScope),
                handle->Files.newInputStream(captured.resolve("objects").resolve(handle.digest().value().substring(7)),LinkOption.NOFOLLOW_LINKS),
                row->{if(row.captured().isPresent()){
                    String path=new String(row.path().orElseThrow().bytes(),StandardCharsets.UTF_8);
                    if(files.putIfAbsent(path,row.captured().orElseThrow())!=null)throw new IOException("NATIVE_FIXTURE_DUPLICATE_PATH");
                }},()->false);
        if(verified.decision()!=OutputCensus.Decision.CAPTURED||verified.unavailableObjects()!=0)throw new IOException("NATIVE_FIXTURE_CAPTURE_OPEN");
        var missing=new TreeSet<>(REQUIRED);missing.removeAll(files.keySet());
        var nativeExit=read(root.resolve("native-exit.json"),8192);
        if(!workAttempt.equals(nativeExit.get("attempt")))throw new IOException("NATIVE_FIXTURE_EXIT_SCOPE");
        boolean nativeSuccess=CoreMvpBuildPipeline.number(nativeExit.get("exit"))==0;
        if(nativeSuccess&&!missing.isEmpty())throw new IOException("NATIVE_FIXTURE_REQUIRED_OUTPUT_MISSING");
        if(nativeSuccess)validateNative(content,files,workAttempt);
        var fields=new TreeMap<String,Object>();fields.put("schema","m844-native-byte-import:v1");fields.put("attempt",attempt);
        fields.put("header",header);fields.put("census",verified);fields.put("files",files);fields.put("missingRoles",missing);
        fields.put("nativeExit",capture(content,root.resolve("native-exit.json")));fields.put("nativeSuccess",nativeSuccess);
        fields.put("semanticTrust","PRODUCER_ASSERTION");fields.put("qualified",false);
        byte[] bytes=CanonicalJson.write(fields).getBytes(StandardCharsets.UTF_8);
        var document=content.put(new ByteArrayInputStream(bytes),()->false);Path pointer=root.resolve("native-byte-import.json");
        String encoded=CanonicalJson.write(document);
        if(args[0].equals("import"))Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);
        else if(!Files.readString(pointer).equals(encoded))throw new IOException("NATIVE_FIXTURE_REPLAY_CHANGED");
        System.out.println(CanonicalJson.write(Map.of("mode",args[0],"document",document,"byteDecision",verified.decision(),
                "entries",verified.observed().orElseThrow().entries(),"files",files.size(),"nativeSuccess",nativeSuccess,
                "missingRoles",missing.size(),"semanticTrust","PRODUCER_ASSERTION","qualified",false)));
    }
}
