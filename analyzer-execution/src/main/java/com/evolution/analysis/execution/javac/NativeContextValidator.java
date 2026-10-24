package com.evolution.analysis.execution.javac;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.JavaEvidenceRecord;
import java.io.*;
import java.lang.module.ModuleDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipFile;
import java.util.zip.CRC32;
import java.nio.file.*;
import java.security.*;

/** Passive validation of captured native inputs; never invokes a compiler or loads a target class. */
public final class NativeContextValidator {
    public static final String VERSION="native-context-validator:m8.4-v1";
    public record Slot(String path,Kind kind,Map<String,ContentHandle> files){
        public enum Kind { JAR, DIRECTORY }
        public Slot {Objects.requireNonNull(path);Objects.requireNonNull(kind);files=Map.copyOf(files);}
    }
    /** A checked data view, not an authenticated semantic attestation. */
    public static final class Platform {
        private final String runtime;private final Set<String> selected;private final Map<String,ContentHandle> files;
        private final Map<String,Object> report;private final Set<ContentHandle> references;
        private Platform(String runtime,Set<String> selected,Map<String,ContentHandle> files,Map<String,Object> report,Set<ContentHandle> references){
            this.runtime=runtime;this.selected=Set.copyOf(selected);this.files=Map.copyOf(files);this.report=Collections.unmodifiableMap(new TreeMap<>(report));this.references=Set.copyOf(references);
        }
        public String runtime(){return runtime;}
        public Set<String> selectedModules(){return selected;}
        public Map<String,Object> report(){return report;}
    }
    public record Validation(Map<String,Object> report,Set<ContentHandle> references){
        public Validation {report=Collections.unmodifiableMap(new TreeMap<>(report));references=Set.copyOf(references);}
    }
    private final RepositoryContentStore content;private final BooleanSupplier cancelled;
    private final Map<String,byte[]> members=new HashMap<>();
    public NativeContextValidator(RepositoryContentStore content,BooleanSupplier cancelled){this.content=Objects.requireNonNull(content);this.cancelled=Objects.requireNonNull(cancelled);}

    /** Validate the image-bound full JDK inventory, including modules not selected by javac.
     * --release 21 uses ct.sym's L/system-modules for default root candidates.
     * Required internal runtime modules remain in the closure, even outside that list.
     * Default roots and their requires closure follow OpenJDK 21 Modules.setupAllModules.
     */
    public Platform platform(ContentHandle manifest,List<ContentHandle> expectedImage,Map<String,ContentHandle> files)throws IOException {
        var references=new LinkedHashSet<ContentHandle>();require(manifest,references);
        var raw=json(manifest);if(!raw.keySet().equals(Set.of("schema","image","nativeHome","vendor","version","platformSelection","qualified","files"))
                ||!"native-jdk-image-materials:m8.4-v1".equals(raw.get("schema"))||!Boolean.FALSE.equals(raw.get("qualified"))
                ||!"UNESTABLISHED".equals(raw.get("platformSelection")))throw invalid("INVENTORY_SCHEMA");
        if(expectedImage.isEmpty()||!GraphJson.array(raw.get("image")).stream().map(JavaEvidenceRecord::handle).toList().equals(expectedImage))throw invalid("IMAGE_BINDING");
        var declared=new TreeMap<String,ContentHandle>();
        for(Object value:GraphJson.array(raw.get("files"))){var row=GraphJson.object(value);String path=GraphJson.string(row.get("path"));safeName(path);
            if(declared.putIfAbsent(path,JavaEvidenceRecord.handle(row))!=null)throw invalid("DUPLICATE_MATERIAL");}
        if(!declared.equals(files))throw invalid("MATERIAL_DENOMINATOR");for(var ref:files.values())require(ref,references);
        var properties=new Properties();try(var stream=content.open(required(files,"release"))){properties.load(new InputStreamReader(stream,StandardCharsets.UTF_8));}
        String runtime=unquote(properties.getProperty("JAVA_RUNTIME_VERSION")),version=unquote(properties.getProperty("JAVA_VERSION"));
        if(Runtime.Version.parse(runtime).feature()!=21||!version.equals(raw.get("version"))||!unquote(properties.getProperty("IMPLEMENTOR")).equals(raw.get("vendor")))throw invalid("RELEASE_BINDING");
        var names=List.of(unquote(properties.getProperty("MODULES")).split(" ",-1));var inventory=new TreeSet<>(names);
        if(inventory.size()!=names.size()||!inventory.contains("java.base"))throw invalid("MODULE_DENOMINATOR");
        var expectedFiles=new TreeSet<>(Set.of("release","bin/java","bin/javac","lib/modules","lib/ct.sym"));
        inventory.forEach(name->{if(!name.matches("[A-Za-z0-9_.]+"))throw new IllegalArgumentException("INVALID_MODULE_NAME");expectedFiles.add("jmods/"+name+".jmod");});
        if(!files.keySet().equals(expectedFiles))throw invalid("MODULE_DENOMINATOR");
        var descriptors=new TreeMap<String,ModuleDescriptor>();var excluded=new TreeSet<String>();var descriptorDigests=new TreeMap<String,Object>();
        for(String name:inventory){byte[] bytes=member(required(files,"jmods/"+name+".jmod"),"classes/module-info.class",true);
            ModuleDescriptor descriptor;try{descriptor=ModuleDescriptor.read(new ByteArrayInputStream(bytes));}catch(RuntimeException malformed){throw invalid("MODULE_DESCRIPTOR");}
            if(!descriptor.name().equals(name)||descriptor.isAutomatic())throw invalid("MODULE_NAME");
            if((resolutionFlags(bytes)&1)!=0)excluded.add(name);descriptors.put(name,descriptor);
            descriptorDigests.put(name,com.evolution.analysis.contract.common.ContentDigest.sha256(bytes));
        }
        String moduleText=new String(member(required(files,"lib/ct.sym"),"L/system-modules",false),StandardCharsets.UTF_8);
        var observableRows=moduleText.lines().toList();var observable=new TreeSet<>(observableRows);
        if(observable.size()!=observableRows.size()||!inventory.containsAll(observable))throw invalid("RELEASE_MODULE_DENOMINATOR");
        var selected=selectedModules(descriptors,observable,excluded);var exports=new TreeMap<String,Object>();
        for(String name:selected)exports.put(name,descriptors.get(name).exports().stream().filter(e->!e.isQualified()).map(ModuleDescriptor.Exports::source).sorted().toList());
        var report=new TreeMap<String,Object>();report.put("schema",VERSION);report.put("manifest",manifest);report.put("image",List.copyOf(expectedImage));report.put("runtime",runtime);
        report.put("inventoryModules",List.copyOf(inventory));report.put("releaseRootCandidates",List.copyOf(observable));report.put("selectedModules",selected.stream().sorted().toList());
        report.put("excludedDefaultRoots",List.copyOf(excluded));report.put("moduleDescriptors",Collections.unmodifiableMap(descriptorDigests));report.put("unqualifiedExports",Collections.unmodifiableMap(exports));
        report.put("platformSelection","VALIDATED_UNNAMED_RELEASE_21");report.put("semanticAttestation",false);
        return new Platform(runtime,selected,files,report,references);
    }

    public Validation validate(CapturedJavacReader.Capture capture,List<Slot> slots,Platform platform)throws IOException {
        if(!capture.current())throw invalid("CAPTURE_CHANGED");var context=capture.context();var references=new LinkedHashSet<>(platform.references);
        if(!context.runtime().equals(platform.runtime))throw invalid("RUNTIME_MISMATCH");
        var known=new TreeSet<>(platform.selected);known.add("");if(!known.equals(capture.knownModules()))throw invalid("NATIVE_MODULE_GRAPH_MISMATCH");
        String cp=context.options().get(6);if(!List.of(cp.split(":",-1)).equals(slots.stream().map(Slot::path).toList()))throw invalid("CLASSPATH_ORDER");
        var ordered=new ArrayList<Object>();for(var slot:slots){if(!slot.path().startsWith("/")||slot.path().contains("..")||slot.path().contains("\\"))throw invalid("SLOT_PATH");
            if(slot.kind()==Slot.Kind.JAR&&!slot.files().keySet().equals(Set.of("")))throw invalid("JAR_DENOMINATOR");
            if(slot.kind()==Slot.Kind.DIRECTORY&&slot.files().containsKey(""))throw invalid("DIRECTORY_DENOMINATOR");
            for(var file:slot.files().entrySet()){if(!file.getKey().isEmpty())safeName(file.getKey());require(file.getValue(),references);}
            if(slot.kind()==Slot.Kind.JAR)member(slot.files().get(""),null,false);
            ordered.add(Map.of("path",slot.path(),"kind",slot.kind(),"files",Collections.unmodifiableMap(new TreeMap<>(slot.files()))));
        }
        for(var source:context.sources().values())require(source,references);
        for(var entry:capture.artifacts().entrySet()){
            String uri=entry.getKey();ContentHandle expected=context.sources().get(uri);
            if(expected==null&&uri.startsWith("jrt:/")){
                String path=uri.substring(5);int split=path.indexOf('/');if(split<1||!platform.selected.contains(path.substring(0,split)))throw invalid("UNSELECTED_PLATFORM_ARTIFACT");
                byte[] bytes=member(required(platform.files,"jmods/"+path.substring(0,split)+".jmod"),"classes/"+path.substring(split+1),true);
                expected=new ContentHandle(com.evolution.analysis.contract.common.ContentDigest.sha256(bytes),bytes.length);
            }
            if(expected==null)for(var slot:slots){String prefix="file://"+slot.path();
                if(slot.kind()==Slot.Kind.DIRECTORY&&uri.startsWith(prefix+"/"))expected=slot.files().get(uri.substring(prefix.length()+1));
                else if(slot.kind()==Slot.Kind.JAR&&uri.startsWith("jar:"+prefix+"!/")){
                    byte[] bytes=member(slot.files().get(""),uri.substring(("jar:"+prefix+"!/").length()),false);
                    expected=new ContentHandle(com.evolution.analysis.contract.common.ContentDigest.sha256(bytes),bytes.length);
                }
                if(expected!=null)break;
            }
            if(expected==null||!expected.equals(entry.getValue().content())||!entry.getValue().bytesBound())throw invalid("ARTIFACT_ORIGIN");require(expected,references);
        }
        references.add(capture.document());for(var ref:references)require(ref,new HashSet<>());
        var report=new TreeMap<String,Object>();report.put("schema",VERSION);report.put("capture",capture.document());report.put("platform",CanonicalJson.sha256(platform.report));
        report.put("sources",Collections.unmodifiableMap(new TreeMap<>(context.sources())));report.put("orderedClasspath",List.copyOf(ordered));report.put("nativeModules",List.copyOf(known));
        report.put("checks",List.of("COMPILER_INPUTS_VALIDATED","ORDER_PRESERVED","PLATFORM_VALIDATED","SOURCE_CENSUS_CLOSED","SEARCH_SPACES_CLOSED"));
        report.put("semanticAttestation",false);return new Validation(report,references);
    }

    static Set<String> selectedModules(Map<String,ModuleDescriptor> modules,Set<String> observable,Set<String> excludedRoots)throws IOException {
        if(!observable.contains("java.base")||!modules.keySet().containsAll(observable)||!modules.keySet().containsAll(excludedRoots))throw invalid("OBSERVABLE_MODULES");
        var selected=new TreeSet<String>();var pending=new ArrayDeque<String>();pending.add("java.base");
        for(String name:new TreeSet<>(observable))if(!excludedRoots.contains(name)&&modules.get(name).exports().stream().anyMatch(e->!e.isQualified()))pending.add(name);
        while(!pending.isEmpty()){String name=pending.removeFirst();if(!modules.containsKey(name))throw invalid("MODULE_REQUIRES_UNAVAILABLE");
            if(selected.add(name))for(var dependency:modules.get(name).requires())pending.add(dependency.name());}
        return Collections.unmodifiableSet(selected);
    }
    private void require(ContentHandle ref,Set<ContentHandle> references)throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid("CANCELLED");if(!content.verify(ref))throw invalid("CONTENT_CHANGED");references.add(ref);}
    private static ContentHandle required(Map<String,ContentHandle> files,String key)throws IOException {var ref=files.get(key);if(ref==null)throw invalid("MISSING_MATERIAL");return ref;}
    private Map<String,Object> json(ContentHandle ref)throws IOException {if(ref.size()>2_000_000)throw invalid("WORKER_CAPACITY_REQUIRED");try(var in=content.open(ref);var reader=new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))){return GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(2_000_000,64,2_000_000)));}}
    private static String unquote(String value)throws IOException {if(value==null||value.length()<2||value.charAt(0)!='"'||value.charAt(value.length()-1)!='"')throw invalid("RELEASE_PROPERTY");return value.substring(1,value.length()-1);}
    private static void safeName(String name)throws IOException {try{LogicalPath.utf8(name);}catch(IllegalArgumentException bad){throw invalid("ARCHIVE_PATH");}if(name.contains("\\"))throw invalid("ARCHIVE_PATH");}
    private byte[] member(ContentHandle archive,String wanted,boolean jmod)throws IOException {
        if(wanted!=null)safeName(wanted);require(archive,new HashSet<>());String key=jmod+":"+archive.digest().value()+"/"+wanted;
        byte[] cached=members.get(key);if(cached!=null)return cached.clone();byte[] selected=null;
        if(archive.size()>2_000_000_000L)throw invalid("WORKER_CAPACITY_REQUIRED");
        Path temporary=Files.createTempFile("evo-native-context-",".archive");
        try{
            MessageDigest digest;try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            long copied=0;try(var raw=content.open(archive);var in=new DigestInputStream(raw,digest);var out=Files.newOutputStream(temporary)){
                byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid("CANCELLED");copied=Math.addExact(copied,n);if(copied>archive.size())throw invalid("CONTENT_CHANGED");out.write(buffer,0,n);}}
            if(copied!=archive.size()||!archive.digest().value().equals("sha256:"+HexFormat.of().formatHex(digest.digest())))throw invalid("CONTENT_CHANGED");
            try(var in=Files.newInputStream(temporary)){byte[] header=in.readNBytes(4);if(jmod?!Arrays.equals(header,new byte[]{'J','M',1,0}):!Arrays.equals(header,new byte[]{'P','K',3,4})&&!Arrays.equals(header,new byte[]{'P','K',5,6}))throw invalid("ARCHIVE_HEADER");}
            // Use the central directory, as javac does. Local-header iteration alone
            // cannot establish the set of names visible to the native compiler.
            try(var zip=new ZipFile(temporary.toFile())){var names=new HashSet<String>();long total=0;
                var entries=zip.entries();while(entries.hasMoreElements()){var entry=entries.nextElement();
                    if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid("CANCELLED");
                    String name=entry.getName();safeName(entry.isDirectory()?name.substring(0,name.length()-1):name);
                    if(names.size()>=100_000||!names.add(name))throw invalid("ARCHIVE_DENOMINATOR");
                    long size=0;byte[] buffer=new byte[65536];var copy=name.equals(wanted)?new ByteArrayOutputStream():null;var crc=new CRC32();
                    try(var in=zip.getInputStream(entry)){for(int n;(n=in.read(buffer))!=-1;){size=Math.addExact(size,n);total=Math.addExact(total,n);if(size>32_000_000||total>2_000_000_000L)throw invalid("WORKER_CAPACITY_REQUIRED");crc.update(buffer,0,n);if(copy!=null){if(size>2_000_000)throw invalid("WORKER_CAPACITY_REQUIRED");copy.write(buffer,0,n);}}}
                    if(size!=entry.getSize()||crc.getValue()!=entry.getCrc())throw invalid("ARCHIVE_CRC_OR_SIZE");
                    if(copy!=null){if(entry.isDirectory())throw invalid("ARCHIVE_MEMBER_KIND");selected=copy.toByteArray();}
                }
            }
        }finally{Files.deleteIfExists(temporary);}
        if(wanted==null)selected=new byte[0];if(selected==null)throw invalid("ARCHIVE_MEMBER_MISSING");members.put(key,selected.clone());return selected;
    }
    /** ModuleDescriptor deliberately ignores this JDK attribute; retain DO_NOT_RESOLVE_BY_DEFAULT. */
    static int resolutionFlags(byte[] bytes)throws IOException {
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            if(in.readInt()!=0xcafebabe)throw invalid("MODULE_CLASS_FORMAT");in.readUnsignedShort();int major=in.readUnsignedShort();if(major>65)throw invalid("MODULE_CLASS_VERSION");
            var utf=new HashMap<Integer,String>();int count=in.readUnsignedShort();
            for(int i=1;i<count;i++)switch(in.readUnsignedByte()){
                case 1->utf.put(i,in.readUTF());case 3,4->in.skipNBytes(4);case 5,6->{in.skipNBytes(8);i++;}
                case 7,8,16,19,20->in.skipNBytes(2);case 9,10,11,12,17,18->in.skipNBytes(4);case 15->in.skipNBytes(3);default->throw invalid("MODULE_CONSTANT_POOL");
            }
            in.skipNBytes(6);in.skipNBytes(2L*in.readUnsignedShort());
            for(int kind=0;kind<2;kind++)for(int n=in.readUnsignedShort();n>0;n--){in.skipNBytes(6);for(int a=in.readUnsignedShort();a>0;a--){in.readUnsignedShort();in.skipNBytes(Integer.toUnsignedLong(in.readInt()));}}
            int result=0;boolean found=false;
            for(int n=in.readUnsignedShort();n>0;n--){String name=utf.get(in.readUnsignedShort());long size=Integer.toUnsignedLong(in.readInt());if(name==null)throw invalid("MODULE_ATTRIBUTE");
                if(name.equals("ModuleResolution")){if(found||size!=2)throw invalid("MODULE_RESOLUTION");result=in.readUnsignedShort();found=true;if((result&~15)!=0)throw invalid("MODULE_RESOLUTION");}else in.skipNBytes(size);}
            if(in.read()!=-1)throw invalid("MODULE_TRAILING_BYTES");return result;
        }
    }
    private static IOException invalid(String reason){return new IOException("NATIVE_CONTEXT_"+reason);}
}
