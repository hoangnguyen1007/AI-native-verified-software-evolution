package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.javaevidence.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/** Non-loading CAS-to-solver adapter. All destinations are platform-selected slot names. */
public final class JavaBinaryViews implements JavaResolutionPlanner.BinaryMaterializer {
    public record Budget(int entries,long expandedBytes,int entryBytes){public Budget{if(entries<1||expandedBytes<1||entryBytes<1||entryBytes==Integer.MAX_VALUE)throw new IllegalArgumentException("JAVA_BINARY_WORKER_BUDGET");}}
    private final RepositoryContentStore content;private final Path root;private final Budget budget;private final JavaEvidenceTables tables;
    public JavaBinaryViews(RepositoryContentStore content,Path directory,Budget budget)throws IOException{this.content=Objects.requireNonNull(content);this.root=StreamingContentStore.directory(directory);this.budget=budget;tables=new JavaEvidenceTables(content,2_000_000);}
    @Override public BinaryInput materialize(JavaEvidenceRecord slot)throws IOException{return materialize(slot,0);}
    @Override public BinaryInput materialize(JavaEvidenceRecord slot,int release)throws IOException{
        if(release<0)throw new IllegalArgumentException("JAVA_BINARY_RELEASE_INVALID");
        ContentHandle nativeBytes=JavaEvidenceRecord.handle(slot.fields().get("original"));if(!content.verify(nativeBytes))throw new IOException("JAVA_BINARY_CONTENT_UNAVAILABLE");
        ContentHandle view;
        if(slot.text("kind").equals("CLASS_DIRECTORY"))view=directory(slot);
        else if(slot.text("kind").equals("JAR"))view=nativeBytes;
        else if(slot.text("kind").equals("NESTED_ARCHIVE"))view=nested(slot,nativeBytes);
        else throw new IOException("JAVA_BINARY_KIND_UNSUPPORTED");
        validateJar(view);
        // A raw JAR remains the exact protected slot; ResolutionEnvironment selects its
        // entries using FrontendRequest.platform(). A separately declared projection
        // must match its captured digest before it can become a different solver view.
        if(slot.fields().get("selectedView")!=null)view=releaseView(view,release);
        else if(slot.text("kind").equals("JAR"))requireRelease(view,release);
        if(slot.fields().get("selectedView")!=null&&!view.equals(JavaEvidenceRecord.handle(slot.fields().get("selectedView"))))throw new IOException("JAVA_BINARY_DERIVED_VIEW_MISMATCH");
        String name="slot-"+slot.text("space")+"-"+slot.number("ordinal")+"-"+view.digest().value().substring(7)+".jar";
        Path target=root.resolve(name);StreamingContentStore.safe(target);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("JAVA_BINARY_VIEW_UNSAFE");
            try(var in=Files.newInputStream(target,LinkOption.NOFOLLOW_LINKS)){if(!content.put(in,()->false).equals(view))throw new IOException("JAVA_BINARY_VIEW_CHANGED");}
        }else{
            Path temporary=Files.createTempFile(root,"slot-",".part");
            try{try(var in=content.open(view);var out=java.nio.channels.FileChannel.open(temporary,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1){var bytes=java.nio.ByteBuffer.wrap(buffer,0,n);while(bytes.hasRemaining())out.write(bytes);}out.force(true);
            }try{Files.createLink(target,temporary);}catch(FileAlreadyExistsException race){throw new IOException("JAVA_BINARY_VIEW_CONCURRENT_WRITER",race);}}
            finally{Files.deleteIfExists(temporary);}
        }
        boolean project=slot.text("origin").equals("PROJECT");
        var entry=new ClasspathEntry(project?ClasspathEntryKind.MODULE_OUTPUT:ClasspathEntryKind.DEPENDENCY,slot.text("logicalName")+"@"+slot.text("space")+":"+slot.number("ordinal"),view.digest());
        var nativeEntry=new ClasspathEntry(entry.kind(),slot.text("logicalName"),nativeBytes.digest());
        return new BinaryInput(entry,target,project?Optional.of(new ModuleIdentity(GraphJson.string(slot.fields().get("module")))):Optional.empty(),Optional.of(new BinaryInput.NativeOrigin(nativeEntry,slot.identity())));
    }
    public ContentHandle selectedReleaseView(ContentHandle original,int release)throws IOException{
        if(release<1)throw new IllegalArgumentException("JAVA_BINARY_RELEASE_INVALID");validateJar(original);return releaseView(original,release);
    }
    private void requireRelease(ContentHandle original,int release)throws IOException{
        if(release==0)releaseView(original,0);
    }
    /** Passive deterministic MR projection. The original archive remains the native origin. */
    private ContentHandle releaseView(ContentHandle original,int release)throws IOException{
        var entries=new TreeMap<String,byte[]>();Manifest manifest=null;boolean versioned=false;
        try(var in=new ZipInputStream(content.open(original))){for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry()){
            if(entry.isDirectory())continue;byte[] bytes=in.readNBytes(budget.entryBytes()+1);entries.put(entry.getName(),bytes);
            if(entry.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME)){
                if(manifest!=null)throw new IOException("JAVA_ARCHIVE_MANIFEST_AMBIGUOUS");manifest=new Manifest(new ByteArrayInputStream(bytes));
            }
            versioned|=entry.getName().startsWith("META-INF/versions/");
        }}
        boolean multi=manifest!=null&&"true".equalsIgnoreCase(manifest.getMainAttributes().getValue("Multi-Release"));
        if(multi&&release==0)throw new IOException("JAVA_BINARY_RELEASE_REQUIRED");
        if(!multi||!versioned)return original;
        var selected=new TreeMap<String,byte[]>();var versions=new HashMap<String,Integer>();
        for(var entry:entries.entrySet())if(!entry.getKey().startsWith("META-INF/versions/"))selected.put(entry.getKey(),entry.getValue());
        for(var entry:entries.entrySet()){
            String name=entry.getKey();if(!name.startsWith("META-INF/versions/"))continue;
            String tail=name.substring("META-INF/versions/".length());int slash=tail.indexOf('/');if(slash<1)continue;
            String number=tail.substring(0,slash),logical=tail.substring(slash+1);if(!number.matches("[1-9][0-9]*")||logical.startsWith("META-INF/"))continue;
            int version;try{version=Integer.parseInt(number);}catch(NumberFormatException beyondAnySupportedRelease){continue;}
            if(version<9||version>release||version<=versions.getOrDefault(logical,0))continue;
            versions.put(logical,version);selected.put(logical,entry.getValue());
        }
        // Disable a second runtime-dependent selection by downstream byte readers.
        manifest.getMainAttributes().remove(Attributes.Name.MULTI_RELEASE);
        var manifestBytes=new ByteArrayOutputStream();manifest.write(manifestBytes);selected.entrySet().removeIf(e->e.getKey().equalsIgnoreCase(JarFile.MANIFEST_NAME));selected.put(JarFile.MANIFEST_NAME,manifestBytes.toByteArray());
        Path temporary=Files.createTempFile(root,"release-view-",".jar");
        try{try(var out=new JarOutputStream(Files.newOutputStream(temporary,LinkOption.NOFOLLOW_LINKS))){for(var entry:selected.entrySet()){
            var item=new JarEntry(entry.getKey());item.setTime(0);out.putNextEntry(item);out.write(entry.getValue());out.closeEntry();
        }}try(var in=Files.newInputStream(temporary,LinkOption.NOFOLLOW_LINKS)){return content.put(in,()->false);}}
        finally{Files.deleteIfExists(temporary);}
    }
    private ContentHandle directory(JavaEvidenceRecord slot)throws IOException{
        var entries=new TreeMap<String,ContentHandle>();var tree=JavaEvidenceTables.Table.from(slot.fields().get("tree"));
        if(tree.rows()>budget.entries())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
        var original=JavaEvidenceRecord.handle(slot.fields().get("original"));if(original.size()>2_000_000)throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
        Map<String,Object> descriptor;
        try(var in=content.open(original);var reader=new InputStreamReader(in,java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT))){descriptor=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(2_000_000,32,100000)));}
        if(!descriptor.keySet().equals(Set.of("schema","scope","root","tree"))||!"java-class-directory:m8.4-v1".equals(descriptor.get("schema"))||!descriptor.get("scope").equals(slot.fields().get("scope"))||!descriptor.get("tree").equals(slot.fields().get("tree")))throw new IOException("JAVA_BINARY_DIRECTORY_LAYOUT_UNPROVEN");
        byte[] prefix=descriptor.get("root")==null?new byte[0]:JavaGeneratedOverlay.path(descriptor.get("root")).bytes();
        long[] bytes={0};tables.read(tree,row->{
            if(!row.fields().get("scope").equals(slot.fields().get("scope")))throw new IOException("JAVA_BINARY_TREE_SCOPE_MISMATCH");
            if(!row.text("disposition").equals("ACCEPTED"))throw new IOException("JAVA_BINARY_TREE_OPEN");
            byte[] raw=JavaGeneratedOverlay.path(row.object("locator").get("path")).bytes();
            if(row.text("kind").equals("DIRECTORY")&&Arrays.equals(prefix,raw))return;
            int start=0;if(prefix.length>0){if(raw.length<=prefix.length||raw[prefix.length]!='/'||!Arrays.equals(prefix,Arrays.copyOf(raw,prefix.length)))throw new IOException("JAVA_BINARY_ENTRY_OUTSIDE_ROOT");start=prefix.length+1;}
            String name=LogicalPath.of(Arrays.copyOfRange(raw,start,raw.length)).utf8().orElseThrow(()->new IOException("JAVA_BINARY_RAW_PATH_WORKER_REQUIRED"));
            if(row.text("kind").equals("DIRECTORY"))return;
            if(!row.text("kind").equals("FILE")||!row.text("disposition").equals("ACCEPTED"))throw new IOException("JAVA_BINARY_TREE_OPEN");
            var handle=JavaEvidenceRecord.handle(row.fields().get("content"));bytes[0]=Math.addExact(bytes[0],handle.size());
            if(handle.size()>budget.entryBytes()||bytes[0]>budget.expandedBytes())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
            if(!content.verify(handle)||entries.putIfAbsent(name,handle)!=null)throw new IOException("JAVA_BINARY_TREE_CONFLICT");
        },()->false);
        Path temporary=Files.createTempFile(root,"directory-view-",".jar");
        try{
            try(var out=new JarOutputStream(Files.newOutputStream(temporary,LinkOption.NOFOLLOW_LINKS))){for(var item:entries.entrySet()){
                var entry=new JarEntry(item.getKey());entry.setTime(0);out.putNextEntry(entry);try(var in=content.open(item.getValue())){in.transferTo(out);}out.closeEntry();
            }}
            ContentHandle result;try(var in=Files.newInputStream(temporary,LinkOption.NOFOLLOW_LINKS)){result=content.put(in,()->false);}
            if(slot.fields().get("selectedView")!=null&&!result.equals(JavaEvidenceRecord.handle(slot.fields().get("selectedView"))))throw new IOException("JAVA_BINARY_DERIVED_VIEW_MISMATCH");return result;
        }finally{Files.deleteIfExists(temporary);}
    }
    private ContentHandle nested(JavaEvidenceRecord slot,ContentHandle original)throws IOException{
        if(slot.fields().get("tree")==null)throw new IOException("JAVA_NESTED_LAYOUT_UNPROVEN");
        var tree=JavaEvidenceTables.Table.from(slot.fields().get("tree"));if(tree.rows()!=1)throw new IOException("JAVA_NESTED_SELECTION_AMBIGUOUS");
        ContentHandle[] selected={null};tables.read(tree,row->{
            if(!row.fields().get("scope").equals(slot.fields().get("scope"))||!row.text("category").equals("ARCHIVE")||!row.text("disposition").equals("ACCEPTED")||!JavaEvidenceRecord.handle(row.object("locator").get("container")).equals(original))throw new IOException("JAVA_NESTED_SCOPE_MISMATCH");
            ContentHandle current=original;
            for(Object value:GraphJson.array(row.object("locator").get("members"))){var member=GraphJson.object(value);var expected=JavaEvidenceRecord.handle(member.get("content"));String name=JavaGeneratedOverlay.path(member.get("path")).utf8().orElseThrow(()->new IOException("JAVA_NESTED_RAW_PATH"));long ordinal=((java.math.BigDecimal)member.get("ordinal")).longValueExact();current=member(current,name,ordinal);if(!current.equals(expected))throw new IOException("JAVA_NESTED_CONTENT_MISMATCH");}
            if(!current.equals(JavaEvidenceRecord.handle(row.fields().get("content"))))throw new IOException("JAVA_NESTED_LEAF_MISMATCH");selected[0]=current;
        },()->false);
        // Locator steps and the row content authenticate the original leaf above.
        // selectedView may instead name its release projection, checked by materialize().
        if(selected[0]==null)throw new IOException("JAVA_NESTED_VIEW_MISMATCH");return selected[0];
    }
    private ContentHandle member(ContentHandle container,String wanted,long wantedOrdinal)throws IOException{
        if(!content.verify(container))throw new IOException("JAVA_NESTED_CONTENT_UNAVAILABLE");
        try(var in=new ZipInputStream(content.open(container))){var seen=new HashSet<String>();long ordinal=0,total=0;ContentHandle selected=null;
            for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry(),ordinal++){
                if(ordinal>=budget.entries())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
                if(!seen.add(entry.getName()))throw new IOException("JAVA_ARCHIVE_MEMBER_AMBIGUOUS_OR_OVERSIZED");
                safeName(entry.getName());byte[] bytes=in.readNBytes(Math.addExact(budget.entryBytes(),1));total=Math.addExact(total,bytes.length);
                if(bytes.length>budget.entryBytes()||total>budget.expandedBytes())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
                if(entry.getName().equals(wanted)&&ordinal==wantedOrdinal)selected=content.put(new ByteArrayInputStream(bytes),()->false);
            }if(selected==null)throw new IOException("JAVA_NESTED_MEMBER_MISSING");return selected;
        }
    }
    private void validateJar(ContentHandle handle)throws IOException{
        if(!content.verify(handle))throw new IOException("JAVA_BINARY_VIEW_UNAVAILABLE");
        if(handle.size()>budget.expandedBytes())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
        var central=new LinkedHashSet<String>();Path check=Files.createTempFile(root,"archive-check-",".zip");
        try{
            try(var in=content.open(handle);var out=Files.newOutputStream(check,LinkOption.NOFOLLOW_LINKS)){in.transferTo(out);}
            // ZipInputStream alone accepts a truncated or absent central directory.
            try(var zip=new ZipFile(check.toFile())){var entries=zip.entries();while(entries.hasMoreElements()){
                var entry=entries.nextElement();safeName(entry.getName());if(central.size()>=budget.entries())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");if(!central.add(entry.getName()))throw new IOException("JAVA_ARCHIVE_MEMBER_AMBIGUOUS_OR_OVERSIZED");
            }}
        }finally{Files.deleteIfExists(check);}
        try(var raw=new BufferedInputStream(content.open(handle))){raw.mark(4);byte[] magic=raw.readNBytes(4);raw.reset();if(magic.length!=4||magic[0]!='P'||magic[1]!='K'||!((magic[2]==3&&magic[3]==4)||(magic[2]==5&&magic[3]==6)))throw new IOException("JAVA_BINARY_FORMAT_INVALID");
            try(var in=new ZipInputStream(raw)){var names=new HashSet<String>();long bytes=0;int count=0;for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry()){
                if(++count>budget.entries())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");if(!names.add(entry.getName()))throw new IOException("JAVA_ARCHIVE_MEMBER_AMBIGUOUS_OR_OVERSIZED");safeName(entry.getName());byte[] value=in.readNBytes(Math.addExact(budget.entryBytes(),1));bytes=Math.addExact(bytes,value.length);if(value.length>budget.entryBytes()||bytes>budget.expandedBytes())throw new IOException("JAVA_BINARY_WORKER_CAPACITY_REQUIRED");
            }if(!new ArrayList<>(names).containsAll(central)||names.size()!=central.size())throw new IOException("JAVA_ARCHIVE_DIRECTORY_MISMATCH");}
        }
    }
    private static void safeName(String name)throws IOException{
        if(name.indexOf('\\')>=0||name.indexOf(':')>=0)throw new IOException("JAVA_ARCHIVE_UNSAFE_MEMBER");
        try{LogicalPath.utf8(name.endsWith("/")?name.substring(0,name.length()-1):name);}catch(IllegalArgumentException invalid){throw new IOException("JAVA_ARCHIVE_UNSAFE_MEMBER");}
    }
}
