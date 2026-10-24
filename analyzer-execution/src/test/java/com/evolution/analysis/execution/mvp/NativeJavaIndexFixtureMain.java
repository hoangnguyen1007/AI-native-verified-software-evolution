package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.javac.CapturedJavacReader;
import com.evolution.analysis.execution.javac.NativeContextValidator;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;

/** Real captured compiler observations versus fixed, explicitly partial labels.
 * This fixture cannot register a validator, issue an attestation or close N01-N06.
 */
public final class NativeJavaIndexFixtureMain {
    private final Path root,platform,output;private final RepositoryContentStore content;
    private final List<ContentHandle> imageBinding;
    private final Map<String,ContentHandle> published,inputs,platformInputs=new TreeMap<>();
    private final Map<String,Object> platformManifest;private final String attempt,runtime;private final ContentHandle platformManifestContent,releaseContent;
    private final Map<String,ContentHandle> frozenModules;
    private final Map<String,ContentHandle> extracted=new TreeMap<>();
    private NativeJavaIndexFixtureMain(Path root,Path platform)throws IOException {
        this(root,platform,null);
    }
    private NativeJavaIndexFixtureMain(Path root,Path platform,Path output)throws IOException {
        this.root=root.toRealPath();this.platform=platform==null?null:platform.toRealPath();CoreMvpRegistrations.require(this.root);
        this.output=output==null?this.root:Files.createDirectories(output).toRealPath();
        var captured=new StreamingContentStore(this.root.resolve("native-import-cas"));
        if(output==null)content=captured;
        else {
            var derived=new StreamingContentStore(this.output.resolve("native-import-cas"));
            content=new RepositoryContentStore(){
                public ContentHandle put(InputStream in,java.util.function.BooleanSupplier cancel)throws IOException{return derived.put(in,cancel);}
                private RepositoryContentStore store(ContentHandle ref){String hex=ref.digest().value().substring(7);return Files.exists(NativeJavaIndexFixtureMain.this.output.resolve("native-import-cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2)),LinkOption.NOFOLLOW_LINKS)?derived:captured;}
                public InputStream open(ContentHandle ref)throws IOException{return store(ref).open(ref);}
                public InputStream range(ContentHandle ref,long start,long size)throws IOException{return store(ref).range(ref,start,size);}
                public boolean verify(ContentHandle ref)throws IOException{return store(ref).verify(ref);}
            };
        }
        var document=read(handle(CoreMvpBuildPipeline.read(this.root.resolve("native-byte-import.json"),8192)));
        if(!Boolean.TRUE.equals(document.get("nativeSuccess"))||!Boolean.FALSE.equals(document.get("qualified")))throw invalid("NATIVE_BUILD_REQUIRED");
        attempt=GraphJson.string(document.get("attempt")).replace("-","");published=handles(document.get("files"));
        var binding=read(handle(CoreMvpBuildPipeline.read(this.root.resolve("native-input-import.json"),8192)));
        if(!GraphJson.string(document.get("attempt")).equals(binding.get("attempt")))throw invalid("INPUT_ATTEMPT");
        inputs=handles(read(handle(binding.get("inputClosure"))).get("files"));
        if(this.platform==null){
            var frozen=read(handle(CoreMvpBuildPipeline.read(this.root.resolve("native-index-platform.json"),8192)));
            if(!"m844-native-index-platform-binding:v1".equals(frozen.get("schema"))||!Boolean.FALSE.equals(frozen.get("qualified")))throw invalid("FROZEN_PLATFORM_SCOPE");
            platformManifestContent=handle(frozen.get("manifest"));releaseContent=handle(frozen.get("release"));frozenModules=handles(frozen.get("observedModuleMaterials"));
        }else{
            for(Path ancestor=this.platform;ancestor!=null;ancestor=ancestor.getParent())if(Files.isSymbolicLink(ancestor))throw invalid("JDK_INPUT_ALIAS");
            try(var in=Files.newInputStream(this.platform.resolve("materials.json"),LinkOption.NOFOLLOW_LINKS)){platformManifestContent=content.put(in,()->false);}
            try(var in=Files.newInputStream(this.platform.resolve("release"),LinkOption.NOFOLLOW_LINKS)){releaseContent=content.put(in,()->false);}
            frozenModules=Map.of();
        }
        platformManifest=read(platformManifestContent);
        if(!"native-jdk-image-materials:m8.4-v1".equals(platformManifest.get("schema"))
                ||!Boolean.FALSE.equals(platformManifest.get("qualified"))||!"UNESTABLISHED".equals(platformManifest.get("platformSelection")))throw invalid("PLATFORM_INVENTORY");
        var profile=read(handle(binding.get("profile")));var images=GraphJson.array(GraphJson.object(profile.get("image")).get("images"));
        imageBinding=images.stream().map(JavaEvidenceRecord::handle).toList();
        var expected=images.stream().map(GraphJson::object).map(v->Map.of("digest",v.get("digest"),"size",v.get("size"))).toList();
        if(!expected.equals(platformManifest.get("image")))throw invalid("PLATFORM_IMAGE_BINDING");
        for(Object value:GraphJson.array(platformManifest.get("files"))){var row=GraphJson.object(value);String name=GraphJson.string(row.get("path"));
            LogicalPath.utf8(name);if(platformInputs.putIfAbsent(name,handle(row))!=null)throw invalid("PLATFORM_DENOMINATOR");}
        if(!releaseContent.equals(platformInputs.get("release"))||releaseContent.size()>65536||!content.verify(releaseContent))throw invalid("PLATFORM_RELEASE");
        var properties=new Properties();try(var in=content.open(releaseContent)){properties.load(new InputStreamReader(in,StandardCharsets.UTF_8));}
        String version=properties.getProperty("JAVA_RUNTIME_VERSION","");
        if(!version.matches("\"[A-Za-z0-9+._-]+\"")||!("\""+platformManifest.get("version")+"\"").equals(properties.getProperty("JAVA_VERSION")))throw invalid("PLATFORM_RUNTIME");
        runtime=version.substring(1,version.length()-1);if(Runtime.Version.parse(runtime).feature()!=21)throw invalid("PLATFORM_RELEASE");
    }
    private static ContentHandle handle(Object value){return com.evolution.analysis.javaevidence.JavaEvidenceRecord.handle(value);}
    private static Map<String,ContentHandle> handles(Object value){var result=new TreeMap<String,ContentHandle>();GraphJson.object(value).forEach((key,item)->result.put(key,handle(item)));return result;}
    private Map<String,Object> read(ContentHandle ref)throws IOException {
        if(!content.verify(ref)||ref.size()>4_000_000)throw invalid("CONTENT_REQUIRED");
        try(var in=content.open(ref)){return GraphJson.object(GraphJson.read(new String(in.readNBytes(4_000_001),StandardCharsets.UTF_8),new GraphJson.Limits(4_000_000,64,4_000_000)));}
    }
    private ContentHandle put(Object value)throws IOException {return content.put(new ByteArrayInputStream(CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8)),()->false);}
    private ContentHandle file(String uri)throws IOException {
        String source="file:///opt/evo-inputs/"+attempt+"/",output="file:///work/"+attempt+"/";ContentHandle ref;
        if(uri.startsWith(source))ref=inputs.get(uri.substring(source.length()));
        else if(uri.startsWith(output))ref=published.get(uri.substring(output.length()));else throw invalid("FOREIGN_FILE_URI");
        if(ref==null||!content.verify(ref))throw invalid("FILE_BINDING");return ref;
    }
    private Path casPath(ContentHandle ref){String hex=ref.digest().value().substring(7);Path local=output.resolve("native-import-cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));return Files.exists(local)?local:root.resolve("native-import-cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));}
    private ContentHandle member(ContentHandle container,String name)throws IOException {
        LogicalPath.utf8(name);String key=container.digest().value()+"!/"+name;if(extracted.containsKey(key))return extracted.get(key);
        if(!content.verify(container))throw invalid("CONTAINER_REQUIRED");
        try(var zip=new ZipFile(casPath(container).toFile())){
            var entries=zip.stream().filter(entry->entry.getName().equals(name)).toList();
            if(entries.size()!=1||entries.getFirst().isDirectory()||entries.getFirst().getSize()>2_000_000)throw invalid("MEMBER_BINDING");
            try(var input=zip.getInputStream(entries.getFirst())){
                byte[] bytes=input.readNBytes(2_000_001);if(bytes.length>2_000_000)throw invalid("MEMBER_WORKER_CAPACITY_REQUIRED");
                var ref=content.put(new ByteArrayInputStream(bytes),()->false);extracted.put(key,ref);return ref;
            }
        }
    }
    private ContentHandle artifact(String uri)throws IOException {
        if(uri.startsWith("file:"))return file(uri);
        if(uri.startsWith("jar:file:")){
            int separator=uri.indexOf("!/");if(separator<0)throw invalid("JAR_URI");
            return member(file(uri.substring(4,separator)),uri.substring(separator+2));
        }
        if(uri.startsWith("jrt:/")){
            String path=uri.substring(5);int separator=path.indexOf('/');if(separator<0)throw invalid("JRT_URI");
            String module=path.substring(0,separator);if(!module.matches("[a-zA-Z0-9_.]+"))throw invalid("JRT_MODULE");
            String name="jmods/"+module+".jmod";var expected=platformInputs.get(name);if(expected==null)throw invalid("JDK_MODULE_MISSING");
            ContentHandle actual;
            if(platform==null){actual=frozenModules.get(name);if(actual==null||!content.verify(actual))throw invalid("FROZEN_PLATFORM_INPUT");}
            else {Path selected=platform.resolve(name);for(Path p=selected;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw invalid("JDK_INPUT_ALIAS");
                try(var in=Files.newInputStream(selected,LinkOption.NOFOLLOW_LINKS)){actual=content.put(in,()->false);}}
            if(!actual.equals(expected))throw invalid("JDK_INPUT_CHANGED");
            return member(actual,"classes/"+path.substring(separator+1));
        }
        throw invalid("ARTIFACT_URI");
    }
    private void capturePlatform()throws IOException {
        if(platform==null)throw invalid("PLATFORM_MATERIAL_ROOT_REQUIRED");
        var needed=new TreeSet<String>();var post=read(published.get("native-post-index.json"));
        for(Object value:GraphJson.array(post.get("contexts")))for(String uri:GraphJson.object(read(handle(GraphJson.object(value).get("captured"))).get("artifacts")).keySet())
            if(uri.startsWith("jrt:/")){String path=uri.substring(5);int end=path.indexOf('/');if(end<0||!path.substring(0,end).matches("[A-Za-z0-9_.]+"))throw invalid("JRT_MODULE");needed.add("jmods/"+path.substring(0,end)+".jmod");}
        var modules=new TreeMap<String,ContentHandle>();
        for(String name:needed){
            Path selected=platform.resolve(name);for(Path p=selected;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw invalid("JDK_INPUT_ALIAS");
            ContentHandle actual;try(var in=Files.newInputStream(selected,LinkOption.NOFOLLOW_LINKS)){actual=content.put(in,()->false);}
            if(!actual.equals(platformInputs.get(name)))throw invalid("JDK_INPUT_CHANGED");modules.put(name,actual);
        }
        var binding=put(Map.of("schema","m844-native-index-platform-binding:v1","manifest",platformManifestContent,"release",releaseContent,
                "observedModuleMaterials",modules,"platformSelection","UNESTABLISHED","qualified",false));
        Path pointer=root.resolve("native-index-platform.json");String encoded=CanonicalJson.write(binding);
        if(Files.exists(pointer)){if(!encoded.equals(Files.readString(pointer)))throw invalid("FROZEN_PLATFORM_CHANGED");}
        else Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);
        System.out.println(CanonicalJson.write(Map.of("mode","capture-platform","document",binding,"observedModuleMaterials",modules.size(),"qualified",false)));
    }
    private void classpath(Map<String,Object> context)throws IOException {
        var paths=GraphJson.array(context.get("classpath")).stream().map(GraphJson::string).toList();var rows=GraphJson.array(context.get("classpathInputs"));
        if(rows.size()!=paths.size())throw invalid("CLASSPATH_DENOMINATOR");
        for(int i=0;i<paths.size();i++){
            String path=paths.get(i);var row=GraphJson.object(rows.get(i));if(!path.equals(row.get("path")))throw invalid("CLASSPATH_ORDER");
            var expected=new TreeMap<String,ContentHandle>();String prefix="/work/"+attempt+"/";
            if(path.startsWith(prefix)){
                String relative=path.substring(prefix.length())+"/";published.forEach((name,ref)->{if(name.startsWith(relative))expected.put(name.substring(relative.length()),ref);});
                if(expected.isEmpty())throw invalid("CLASSPATH_DIRECTORY_MISSING");
            }else if(!path.equals("/opt/evo-inputs/"+attempt+"/tooling/empty-classpath"))expected.put("",file("file://"+path));
            if(!expected.equals(handles(row.get("files"))))throw invalid("CLASSPATH_CONTENT");
            for(var ref:expected.values())if(!content.verify(ref))throw invalid("CLASSPATH_CONTENT");
        }
    }
    private record Label(String context,String expression,String owner,String name,String descriptor,String caller,String targetFile){}
    private static final List<Label> LABELS=List.of(
        new Label("protobuf-client","fixture.proto.Request.newBuilder()","fixture.proto.Request","newBuilder","()Lfixture/proto/Request$Builder;","request","protobuf/schema/classes/fixture/proto/Request.class"),
        new Label("protobuf-client","fixture.proto.Request.newBuilder().setName(\"native\")","fixture.proto.Request$Builder","setName","(Ljava/lang/String;)Lfixture/proto/Request$Builder;","request","protobuf/schema/classes/fixture/proto/Request$Builder.class"),
        new Label("protobuf-client","fixture.proto.Request.newBuilder().setName(\"native\").build()","fixture.proto.Request$Builder","build","()Lfixture/proto/Request;","request","protobuf/schema/classes/fixture/proto/Request$Builder.class"),
        new Label("protobuf-client","fixture.proto.EchoGrpc.newBlockingStub(channel)","fixture.proto.EchoGrpc","newBlockingStub","(Lio/grpc/Channel;)Lfixture/proto/EchoGrpc$EchoBlockingStub;","stub","protobuf/schema/classes/fixture/proto/EchoGrpc.class"),
        new Label("lombok-client","Person.builder()","fixture.Person","builder","()Lfixture/Person$PersonBuilder;","person","lombok/model/classes/fixture/Person.class"),
        new Label("lombok-client","Person.builder().name(\"native\")","fixture.Person$PersonBuilder","name","(Ljava/lang/String;)Lfixture/Person$PersonBuilder;","person","lombok/model/classes/fixture/Person$PersonBuilder.class"),
        new Label("lombok-client","Person.builder().name(\"native\").build()","fixture.Person$PersonBuilder","build","()Lfixture/Person;","person","lombok/model/classes/fixture/Person$PersonBuilder.class"),
        new Label("lombok-client","new Person(\"native\")","fixture.Person","<init>","(Ljava/lang/String;)V","construct","lombok/model/classes/fixture/Person.class"),
        new Label("lombok-client","person().getName()","fixture.Person","getName","()Ljava/lang/String;","main","lombok/model/classes/fixture/Person.class"),
        new Label("mapstruct-client","new PersonMapperImpl()","fixture.PersonMapperImpl","<init>","()V","map","mapstruct/mapper/classes/fixture/PersonMapperImpl.class"),
        new Label("mapstruct-client","new PersonMapperImpl().map(input)","fixture.PersonMapperImpl","map","(Lfixture/Input;)Lfixture/Output;","map","mapstruct/mapper/classes/fixture/PersonMapperImpl.class"),
        new Label("custom-client","Generated.value()","fixture.Generated","value","()Ljava/lang/String;","value","custom/producer/classes/fixture/Generated.class"));
    private List<Object> labels(String name,CapturedJavacReader.Capture captured,Map<String,ContentHandle> sources)throws IOException {
        var results=new ArrayList<Object>();
        for(var label:LABELS)if(label.context().equals(name)){
            if(sources.size()!=1)throw invalid("LABEL_SOURCE");String uri=sources.keySet().iterator().next();String text;
            try(var in=content.open(sources.get(uri))){text=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
            int start=text.indexOf(label.expression());if(start<0||text.indexOf(label.expression(),start+1)>=0)throw invalid("LABEL_EXPRESSION");
            var matches=captured.observations().stream().filter(row->{
                if(row.get("span")==null||!uri.equals(row.get("source")))return false;var span=GraphJson.object(row.get("span"));
                return CoreMvpBuildPipeline.number(span.get("startOffset"))==start&&CoreMvpBuildPipeline.number(span.get("endOffset"))==start+label.expression().length()
                        &&(label.name().equals("<init>")?"java.constructor-calls":"java.calls").equals(row.get("category"));
            }).toList();
            if(matches.size()!=1)throw invalid("LABEL_DENOMINATOR");var row=matches.getFirst();var target=GraphJson.object(row.get("target"));var caller=GraphJson.object(row.get("caller"));
            String artifact="file:///work/"+attempt+"/"+label.targetFile();
            if(!"RESOLVED".equals(row.get("binding"))||!label.owner().equals(target.get("owner"))||!label.name().equals(target.get("name"))
                    ||!label.descriptor().equals(target.get("descriptor"))||!label.caller().equals(caller.get("name"))||!uri.equals(caller.get("artifact"))
                    ||!artifact.equals(target.get("artifact"))||!captured.artifacts().get(artifact).bytesBound()
                    ||!captured.artifacts().get(artifact).content().equals(published.get(label.targetFile()))||!GraphJson.array(row.get("enclosingScopes")).isEmpty())throw invalid("LABEL_TARGET_OR_ORIGIN");
            results.add(Map.of("label",label,"source",sources.get(uri),"span",row.get("span"),"targetClass",published.get(label.targetFile()),"producerRole",label.targetFile().substring(0,label.targetFile().indexOf("/classes/"))));
        }
        return results;
    }
    private void run(String mode)throws IOException {
        var post=read(published.get("native-post-index.json"));NativeJavaCensusFixtureMain.validatePostIndex(content,published,attempt);
        if(!inputs.get("tooling/CapturedJavacIndex.java").equals(handle(post.get("toolSource")))||!inputs.get("tooling/native_index_control.py").equals(handle(post.get("controlSource"))))throw invalid("INDEX_TOOL_INPUTS");
        var repository=com.evolution.analysis.contract.identity.RepositoryIdentity.fromCanonicalCoordinate("urn:evo:fixture:m844-native-java-evidence:v1");
        var snapshot=com.evolution.analysis.contract.source.RepositorySnapshot.create(repository,Optional.empty(),false,
                inputs.entrySet().stream().map(e->new com.evolution.analysis.contract.source.SnapshotFile(e.getKey(),e.getValue().digest())).toList(),List.of());
        var evidenceContext=com.evolution.analysis.evidence.EvidenceContext.forSnapshot(snapshot.identity());
        var results=new ArrayList<Object>();int declarations=0,observations=0,labelCount=0,gapCount=0;
        for(Object value:GraphJson.array(post.get("contexts"))){
            var row=GraphJson.object(value);String name=GraphJson.string(row.get("name"));var index=handle(row.get("captured"));var raw=read(index);
            classpath(row);
            var sources=new TreeMap<String,ContentHandle>();
            for(String path:GraphJson.object(row.get("sourceInputs")).keySet()){
                if(!path.matches("/(opt/evo-inputs|work)/[a-f0-9]{32}/[A-Za-z0-9_./-]+"))throw invalid("INDEX_SOURCE_PATH");
                String uri="file://"+path;var ref=file(uri);if(!ref.equals(handle(GraphJson.object(row.get("sourceInputs")).get(path))))throw invalid("INDEX_SOURCE_INPUT");sources.put(uri,ref);
            }
            var artifacts=new TreeMap<String,ContentHandle>();for(String uri:GraphJson.object(raw.get("artifacts")).keySet())artifacts.put(uri,artifact(uri));
            var cp=GraphJson.array(row.get("classpath")).stream().map(GraphJson::string).toList();
            var options=List.of("--release","21","-encoding","UTF-8","-proc:none","-classpath",String.join(":",cp),"-Xlint:none");
            var captured=new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(runtime,options,sources,artifacts),4_000_000,()->false);
            if(!captured.state().equals(row.get("expectedState"))||!captured.frontier().isEmpty()||!captured.current())throw invalid("INDEX_EXPECTED_STATE");
            if(mode.equals("source-elements"))for(var declaration:captured.declarations()){
                if(declaration.get("element")==null)continue;var element=GraphJson.object(declaration.get("element"));
                if(!"TYPE_PARAMETER".equals(element.get("kind"))||element.get("declarationSource")==null)continue;
                String uri=GraphJson.string(element.get("declarationSource"));var artifact=captured.artifacts().get(uri);
                if(!uri.equals(element.get("artifact"))||artifact==null||!artifact.bytesBound()||!artifact.content().equals(sources.get(uri)))throw invalid("SOURCE_ELEMENT_ARTIFACT");
            }
            var labels=labels(name,captured,sources);labelCount+=labels.size();declarations+=captured.declarations().size();observations+=captured.observations().size();
            if(name.equals("custom-broken")){
                var errors=captured.diagnostics().stream().filter(d->"ERROR".equals(d.get("kind"))&&"compiler.err.cant.resolve.location".equals(d.get("code"))).toList();
                if(errors.size()!=1||CoreMvpBuildPipeline.number(GraphJson.object(errors.getFirst().get("span")).get("startLine"))!=3
                        ||CoreMvpBuildPipeline.number(GraphJson.object(errors.getFirst().get("span")).get("startColumn"))!=5)throw invalid("BROKEN_DIAGNOSTIC");
            }else if(!captured.diagnostics().isEmpty())throw invalid("HEALTHY_DIAGNOSTIC");
            var gaps=captured.gaps(evidenceContext);gapCount+=gaps.size();
            results.add(Map.of("name",name,"index",index,"state",captured.state(),"declarations",captured.declarations().size(),
                    "observations",captured.observations().size(),"artifacts",artifacts,"limitations",captured.limitations(),"labels",labels,"gaps",gaps));
        }
        if(results.size()!=11||labelCount!=12)throw invalid("FIXTURE_DENOMINATOR");
        var result=put(Map.of("schema","m844-native-selective-index-check:v1","capture",handle(CoreMvpBuildPipeline.read(root.resolve("native-byte-import.json"),8192)),
                "inputs",handle(CoreMvpBuildPipeline.read(root.resolve("native-input-import.json"),8192)),"contexts",results,"labels",labelCount,"qualified",false,"semanticTrust","PRODUCER_ASSERTION"));
        Path pointer=root.resolve("native-index-check.json");String encoded=CanonicalJson.write(result);
        if(Set.of("verify","source-elements").contains(mode))Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);else if(!Files.readString(pointer).equals(encoded))throw invalid("REPLAY_CHANGED");
        System.out.println(CanonicalJson.write(Map.of("mode",mode,"document",result,"contexts",results.size(),"declarations",declarations,"observations",observations,"labels",labelCount,"gaps",gapCount,"qualified",false)));
    }
    private void validateContexts()throws IOException {
        var materials=new TreeMap<String,ContentHandle>();
        for(var entry:platformInputs.entrySet()){
            if(platform==null){if(!content.verify(entry.getValue()))throw invalid("FROZEN_JDK_INPUT_REQUIRED");materials.put(entry.getKey(),entry.getValue());continue;}
            Path path=platform.resolve(entry.getKey());for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw invalid("JDK_INPUT_ALIAS");
            ContentHandle ref;try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){ref=content.put(in,()->false);}
            if(!ref.equals(entry.getValue()))throw invalid("JDK_INPUT_CHANGED");materials.put(entry.getKey(),ref);
        }
        var validator=new NativeContextValidator(content,()->false);var checkedPlatform=validator.platform(platformManifestContent,imageBinding,materials);
        if(GraphJson.array(checkedPlatform.report().get("inventoryModules")).size()!=69)throw invalid("FIXTURE_JDK_MODULE_DENOMINATOR");
        var post=read(published.get("native-post-index.json"));NativeJavaCensusFixtureMain.validatePostIndex(content,published,attempt);
        var reports=new ArrayList<Object>();int artifactsBound=0;var retained=new HashSet<ContentHandle>();retained.addAll(inputs.values());retained.addAll(published.values());
        var nativePublication=handle(CoreMvpBuildPipeline.read(root.resolve("native-byte-import.json"),8192));
        var nativeInputs=handle(CoreMvpBuildPipeline.read(root.resolve("native-input-import.json"),8192));var inputRecord=read(nativeInputs);
        retained.addAll(List.of(nativePublication,nativeInputs,handle(inputRecord.get("inputClosure")),handle(inputRecord.get("profile"))));
        for(Object value:GraphJson.array(post.get("contexts"))){
            var row=GraphJson.object(value);String name=GraphJson.string(row.get("name"));var index=handle(row.get("captured"));var raw=read(index);classpath(row);
            var sources=new TreeMap<String,ContentHandle>();for(var entry:GraphJson.object(row.get("sourceInputs")).entrySet()){
                String uri="file://"+entry.getKey();var ref=file(uri);if(!ref.equals(handle(entry.getValue())))throw invalid("INDEX_SOURCE_INPUT");sources.put(uri,ref);
            }
            var artifacts=new TreeMap<String,ContentHandle>();for(String uri:GraphJson.object(raw.get("artifacts")).keySet())artifacts.put(uri,artifact(uri));
            var cp=GraphJson.array(row.get("classpath")).stream().map(GraphJson::string).toList();
            var options=List.of("--release","21","-encoding","UTF-8","-proc:none","-classpath",String.join(":",cp),"-Xlint:none");
            var captured=new CapturedJavacReader(content).read(index,new CapturedJavacReader.Context(runtime,options,sources,artifacts),4_000_000,()->false);
            var slots=new ArrayList<NativeContextValidator.Slot>();for(Object item:GraphJson.array(row.get("classpathInputs"))){
                var slot=GraphJson.object(item);var files=handles(slot.get("files"));slots.add(new NativeContextValidator.Slot(GraphJson.string(slot.get("path")),
                        files.keySet().equals(Set.of(""))?NativeContextValidator.Slot.Kind.JAR:NativeContextValidator.Slot.Kind.DIRECTORY,files));
            }
            var checked=validator.validate(captured,slots,checkedPlatform);artifactsBound+=artifacts.size();retained.addAll(checked.references());
            var remaining=new TreeSet<>(captured.limitations());remaining.remove("PLATFORM_SELECTION_UNESTABLISHED");
            reports.add(Map.of("name",name,"validation",checked.report(),"rawLimitations",captured.limitations(),"remainingLimitations",List.copyOf(remaining)));
        }
        if(reports.size()!=11)throw invalid("FIXTURE_CONTEXT_DENOMINATOR");
        for(var ref:retained){String hex=ref.digest().value().substring(7);Path local=output.resolve("native-import-cas/sha256").resolve(hex.substring(0,2)).resolve(hex.substring(2));
            if(!Files.exists(local,LinkOption.NOFOLLOW_LINKS))try(var in=content.open(ref)){if(!content.put(in,()->false).equals(ref))throw invalid("RETAINED_CONTENT_CHANGED");}}
        var result=put(Map.of("schema","m844-native-context-check:v2","platform",checkedPlatform.report(),"contexts",reports,"nativePublication",nativePublication,
                "nativeInputs",nativeInputs,"nativePostIndex",published.get("native-post-index.json"),"semanticAttestation",false));
        Path pointer=output.resolve("native-context-check-v2.json");String encoded=CanonicalJson.write(result);
        if(Files.exists(pointer)){if(!Files.readString(pointer).equals(encoded))throw invalid("CONTEXT_REPLAY_CHANGED");}else Files.writeString(pointer,encoded,StandardOpenOption.CREATE_NEW);
        System.out.println(CanonicalJson.write(Map.of("mode","validate-context","document",result,"inventoryModules",69,"selectedModules",checkedPlatform.selectedModules().size(),
                "contexts",reports.size(),"artifacts",artifactsBound,"validatedPlatformLimitations",reports.size(),"semanticAttestation",false)));
    }
    private static IOException invalid(String code){return new IOException("NATIVE_INDEX_"+code);}
    public static void main(String[] args)throws Exception {
        if(args.length==3&&args[0].equals("replay-context")){
            new NativeJavaIndexFixtureMain(Path.of(args[1]),null,Path.of(args[2])).validateContexts();return;
        }
        if(args.length==4&&args[0].equals("validate-context")){
            new NativeJavaIndexFixtureMain(Path.of(args[1]),Path.of(args[2]),Path.of(args[3])).validateContexts();return;
        }
        if(args.length<2||args.length>3||!Set.of("verify","replay","capture-platform","source-elements").contains(args[0])
                ||Set.of("replay","source-elements").contains(args[0])&&args.length!=2||Set.of("verify","capture-platform").contains(args[0])&&args.length!=3)
            throw new IllegalArgumentException("verify|capture-platform registered-root platform-materials; replay|source-elements registered-root");
        var fixture=new NativeJavaIndexFixtureMain(Path.of(args[1]),args.length==3?Path.of(args[2]):null);
        if(args[0].equals("capture-platform"))fixture.capturePlatform();else fixture.run(args[0]);
    }
}
