package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.output.OutputCensus;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Human-approved Core MVP. Native artifacts are verified bytes, never promoted semantic facts. */
public final class CoreMvpBuildPipeline {
    public interface Launcher {
        ContentDigest fingerprint()throws IOException;
        void launch(Path root)throws IOException;
        Map<String,Object> observeOff(Path root)throws IOException;
    }
    public static final Map<String,ContentDigest> OUTPUTS=Map.of(
        "model/target/generated-sources/evo/fixture/Generated.java",new ContentDigest("sha256:88196b39f5d19646ce44935fb0bdb49228dea4cf049c8eaf9cd20c41857fbfe2"),
        "model/target/classes/fixture/Generated.class",new ContentDigest("sha256:545cd424d601dab99ee0df94739b361bad5c85bd7cdb7dd1e2dc975c74834cd5"),
        "app/target/classes/fixture/Caller.class",new ContentDigest("sha256:0af3ef25593c7473ad03c0aca468137bfcc95f2f9d5bbdd317a09bf5059752c5"));
    private final StreamingContentStore content;private final Launcher launcher;private final Map<String,ContentDigest> expected;
    public CoreMvpBuildPipeline(StreamingContentStore content,Launcher launcher){this(content,launcher,OUTPUTS);}
    CoreMvpBuildPipeline(StreamingContentStore content,Launcher launcher,Map<String,ContentDigest> expected){
        this.content=Objects.requireNonNull(content);this.launcher=Objects.requireNonNull(launcher);this.expected=Map.copyOf(expected);
        if(expected.isEmpty())throw new IllegalArgumentException("CORE_MVP_OUTPUTS_REQUIRED");
    }
    public ContentHandle run(Path directory)throws IOException{
        var root=directory.toRealPath();var lockPath=root.resolve("core-mvp.lock");
        try(var channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            FileLock lock;try{lock=channel.tryLock();}catch(OverlappingFileLockException busy){throw new IOException("CORE_MVP_ALREADY_RUNNING",busy);}
            if(lock==null)throw new IOException("CORE_MVP_ALREADY_RUNNING");
            try(lock){return execute(root);}
        }
    }
    private ContentHandle execute(Path root)throws IOException{
        var setup=read(root.resolve("setup.json"),65536);
        if(!"CORE_MVP".equals(setup.get("acceptanceScope"))||!"HUMAN".equals(setup.get("approvedBy")))throw new IOException("CORE_MVP_APPROVAL_REQUIRED");
        String attempt=UUID.fromString(text(setup,"attempt")).toString(),workAttempt=attempt.replace("-","");
        var provision=read(root.resolve("provision-request.json"),65536);
        if(!attempt.equals(provision.get("attempt"))||!root.equals(Path.of(text(provision,"workspace")).toRealPath()))throw new IOException("CORE_MVP_SCOPE_MISMATCH");
        var identity=CanonicalJson.sha256(Map.of("schema","core-mvp-inputs:m8.4-v1","attempt",attempt,"provision",capture(root.resolve("provision-request.json")),"launcher",launcher.fingerprint(),"expected",expected));
        var pointer=root.resolve("core-mvp-publication.json");
        if(Files.exists(pointer)){
            var saved=read(pointer,65536);if(!identity.value().equals(saved.get("inputs")))throw new IOException("CORE_MVP_INPUTS_CHANGED");
            var document=handle(GraphJson.object(saved.get("document")));
            verifyPublication(root,document,identity);return document;
        }
        var intent=root.resolve("core-mvp-intent.json");
        if(!Files.exists(intent)){
            var request=Map.of("attempt",workAttempt,"nonce",UUID.randomUUID().toString().replace("-",""),"work","/work/"+workAttempt,"maxRowBytes",65536,"maxObjectBytes",134217728,"maxCaptureBytes",536870912);
            write(root.resolve("native-request.json"),Map.of("request",request));
            byte[] collector;try(var in=CoreMvpBuildPipeline.class.getResourceAsStream("/guest/export.py")){if(in==null)throw new IOException("CORE_MVP_TOOL_MISSING");collector=in.readAllBytes();}
            write(root.resolve("export-request.json"),Map.of("request",request,"collectorPin",ContentDigest.sha256(collector)));
            write(intent,Map.of("schema","core-mvp-intent:m8.4-v1","inputs",identity,"attempt",attempt));
            launcher.launch(root);
        }else{
            if(!identity.value().equals(read(intent,65536).get("inputs")))throw new IOException("CORE_MVP_INPUTS_CHANGED");
            if(!Files.exists(root.resolve("capture/collector-result.json")))throw new IOException("CORE_MVP_RECOVERY_REQUIRED");
        }
        var off=launcher.observeOff(root);
        var owner=read(root.resolve("owner-"+workAttempt+".json"),65536);
        if(!"Off".equals(off.get("state"))||!attempt.equals(off.get("attempt"))||!Objects.equals(provision.get("launch"),off.get("launch"))
                ||!Objects.equals(provision.get("fence"),off.get("fence"))||!Objects.equals(owner.get("vmId"),off.get("vmId")))throw new IOException("CORE_MVP_TERMINATION_UNCONFIRMED");
        var nativeResult=read(root.resolve("native-result.json"),1048576);
        if(!workAttempt.equals(nativeResult.get("attempt"))||number(nativeResult.get("buildExit"))!=0||number(nativeResult.get("consumerExit"))!=0)throw new IOException("CORE_MVP_NATIVE_BUILD_FAILED");
        var exportRequest=GraphJson.object(read(root.resolve("export-request.json"),65536).get("request"));
        var scope=new OutputCensus.Scope(workAttempt,text(exportRequest,"nonce"),new OutputCensus.Budget(65536,134217728,536870912));
        var captured=root.resolve("capture");capture(captured.resolve("scope.json"));capture(captured.resolve("entries.ndjson"));
        var census=new OutputCensus(content);var header=census.header(capture(captured.resolve("collector-result.json")),scope);
        var outputs=new TreeMap<String,ContentHandle>();var names=new HashMap<LogicalPath,String>();expected.keySet().forEach(name->names.put(LogicalPath.utf8(name),name));
        var verified=census.verify(header,h->Files.newInputStream(captured.resolve("objects").resolve(h.digest().value().substring(7)),LinkOption.NOFOLLOW_LINKS),row->{
            var name=row.path().map(names::get).orElse(null);
            if(name!=null&&row.captured().isPresent())outputs.put(name,row.captured().orElseThrow());
        },()->false);
        if(verified.decision()!=OutputCensus.Decision.CAPTURED||!outputs.keySet().equals(expected.keySet()))throw new IOException("CORE_MVP_OUTPUT_CLOSURE_OPEN");
        var declared=GraphJson.object(nativeResult.get("outputs"));
        for(var item:outputs.entrySet())if(!item.getValue().digest().equals(expected.get(item.getKey()))||!item.getValue().equals(handle(GraphJson.object(declared.get(item.getKey())))))throw new IOException("CORE_MVP_OUTPUT_DIGEST_MISMATCH");
        materializeSource(root,outputs);
        var report=Map.ofEntries(Map.entry("schema","core-mvp-build-result:m8.4-v1"),Map.entry("scope","CORE_MVP"),Map.entry("status","CAPTURED_NATIVE_OUTPUTS"),
                Map.entry("inputs",identity),Map.entry("attempt",attempt),Map.entry("buildExit",0),Map.entry("consumerExit",0),Map.entry("outputs",outputs),
                Map.entry("census",capture(captured.resolve("collector-result.json"))),Map.entry("termination",put(CanonicalJson.write(off).getBytes(StandardCharsets.UTF_8))),
                Map.entry("nativeObservation",capture(root.resolve("native-result.json"))),Map.entry("semanticTrust","PRODUCER_ASSERTION"),Map.entry("qualified",false));
        var document=put(CanonicalJson.write(report).getBytes(StandardCharsets.UTF_8));
        write(pointer,Map.of("schema","core-mvp-publication:m8.4-v1","inputs",identity,"document",document));
        return document;
    }
    private void verifyPublication(Path root,ContentHandle document,ContentDigest identity)throws IOException{
        if(!content.verify(document)||document.size()>1048576)throw new IOException("CORE_MVP_PUBLICATION_CORRUPT");
        Map<String,Object> value;try(var in=content.open(document)){value=GraphJson.object(GraphJson.read(new InputStreamReader(in,StandardCharsets.UTF_8),new GraphJson.Limits(1048576,32,32768)));}
        if(!identity.value().equals(value.get("inputs"))||!"CORE_MVP".equals(value.get("scope"))||!"PRODUCER_ASSERTION".equals(value.get("semanticTrust"))||!Boolean.FALSE.equals(value.get("qualified")))throw new IOException("CORE_MVP_PUBLICATION_SCOPE_MISMATCH");
        var outputs=GraphJson.object(value.get("outputs"));
        if(!outputs.keySet().equals(expected.keySet()))throw new IOException("CORE_MVP_OUTPUT_CLOSURE_OPEN");
        var handles=new TreeMap<String,ContentHandle>();for(var entry:outputs.entrySet()){
            var h=handle(GraphJson.object(entry.getValue()));if(!h.digest().equals(expected.get(entry.getKey()))||!content.verify(h))throw new IOException("CORE_MVP_PUBLICATION_CORRUPT");
            handles.put(entry.getKey(),h);
        }
        for(String key:List.of("census","termination","nativeObservation"))if(!content.verify(handle(GraphJson.object(value.get(key)))))throw new IOException("CORE_MVP_PUBLICATION_CORRUPT");
        var request=GraphJson.object(read(root.resolve("export-request.json"),65536).get("request"));
        var scope=new OutputCensus.Scope(text(value,"attempt").replace("-",""),text(request,"nonce"),new OutputCensus.Budget(65536,134217728,536870912));
        var census=new OutputCensus(content);var header=census.header(handle(GraphJson.object(value.get("census"))),scope);
        if(census.verify(header,content::open,row->{},()->false).decision()!=OutputCensus.Decision.CAPTURED)throw new IOException("CORE_MVP_PUBLICATION_CORRUPT");
        materializeSource(root,handles);
    }
    private void materializeSource(Path root,Map<String,ContentHandle> outputs)throws IOException{
        var source=outputs.entrySet().stream().filter(e->e.getKey().equals("Generated.java")||e.getKey().endsWith("/Generated.java")).toList();
        if(source.size()!=1)throw new IOException("CORE_MVP_GENERATED_SOURCE_REQUIRED");
        var folder=root.resolve("generated");if(!Files.exists(folder))Files.createDirectory(folder);
        if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(folder))throw new IOException("CORE_MVP_GENERATED_FOLDER_INVALID");
        var path=folder.resolve("Generated.java");var expected=source.getFirst().getValue();
        if(Files.exists(path)){if(!capture(path).equals(expected))throw new IOException("CORE_MVP_GENERATED_SOURCE_CHANGED");}
        else try(var input=content.open(expected)){Files.copy(input,path);}
    }
    private ContentHandle put(byte[] value)throws IOException{return content.put(new ByteArrayInputStream(value),()->false);}
    private ContentHandle capture(Path path)throws IOException{try(var in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){return content.put(in,()->false);}}
    static Map<String,Object> read(Path path,int maximum)throws IOException{
        if(Files.size(path)>maximum||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("CORE_MVP_RECORD_INVALID");
        return GraphJson.object(GraphJson.read(Files.readString(path,StandardCharsets.UTF_8),new GraphJson.Limits(maximum,32,maximum)));
    }
    static String text(Map<String,Object> value,String key){return GraphJson.string(value.get(key));}
    static long number(Object value){return ((java.math.BigDecimal)value).longValueExact();}
    static ContentHandle handle(Map<String,Object> value){return new ContentHandle(new ContentDigest(text(value,"digest")),number(value.get("size")));}
    static void write(Path path,Object value)throws IOException{
        var bytes=CanonicalJson.write(value).getBytes(StandardCharsets.UTF_8);
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
    }
}
