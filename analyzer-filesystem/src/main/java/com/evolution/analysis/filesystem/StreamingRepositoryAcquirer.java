package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;
import static com.evolution.analysis.acquisition.streaming.InventoryEntry.*;

/** Comprehensive local capture: iterative streaming traversal, persisted frontier, captured immutable bytes.
 * Local filesystems supply mutation detection, not an atomic filesystem snapshot. */
public final class StreamingRepositoryAcquirer {
    public record Receipt(String revision, JobJournal.State state, Optional<InventoryManifest> manifest, String reason) {}
    private final StreamingContentStore content;
    private final SqliteInventoryStore inventory;
    public StreamingRepositoryAcquirer(StreamingContentStore content,SqliteInventoryStore inventory) { this.content=content;this.inventory=inventory; }
    public Receipt acquire(Path selectedRoot,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(cancelled); var job=inventory.create(revision,request.identity(),Optional.empty());
        if(job.state()==JobJournal.State.COMPLETE)return inventory.verifySeal(revision,cancelled)?new Receipt(revision,job.state(),inventory.manifest(revision),""):new Receipt(revision,JobJournal.State.WAITING_FOR_INPUT,Optional.empty(),"SEALED_CONTENT_UNAVAILABLE");
        if(job.state()==JobJournal.State.CANCELLED||job.state()==JobJournal.State.FAILED)return new Receipt(revision,job.state(),Optional.empty(),job.reason());
        job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"",Optional.empty());
        try{
            var sealed=inventory.manifest(revision);
            if(sealed.isPresent()){
                var payload=content.put(new ByteArrayInputStream(CanonicalJson.write(sealed.orElseThrow()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),cancelled);
                inventory.transition(revision,job.revision(),JobJournal.State.COMPLETE,"",Optional.of(payload));
                return new Receipt(revision,JobJournal.State.COMPLETE,sealed,"");
            }
            Path root=selectedRoot.toAbsolutePath().normalize();StreamingContentStore.safe(root);
            if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||!root.equals(root.toRealPath()))throw new IOException("ROOT_UNAVAILABLE");
            var batch=new ArrayList<SqliteInventoryStore.Observation>(256);
            class Frame implements AutoCloseable{
                final Path path;final String logical;final BasicFileAttributes before;final DirectoryStream<Path> stream;final Iterator<Path> iterator;
                Frame(Path path,String logical)throws IOException{this.path=path;this.logical=logical;before=attributes(path);stream=Files.newDirectoryStream(path);iterator=stream.iterator();}
                public void close()throws IOException{stream.close();}
            }
            var stack=new ArrayDeque<Frame>();stack.push(new Frame(root,""));
            try{
                while(!stack.isEmpty()){
                    check(cancelled);var frame=stack.peek();
                    if(!frame.iterator.hasNext()){
                        if(!stamp(frame.before).equals(stamp(attributes(frame.path)))){
                            if(frame.logical.isEmpty())throw new IOException("SOURCE_DIRECTORY_CHANGED");
                            batch.add(new SqliteInventoryStore.Observation(gap(logicalPath(root,frame.path),Kind.DIRECTORY,"DIRECTORY_CHANGED"),""));
                        }
                        frame.close();stack.pop();flush(revision,batch);continue;
                    }
                    Path file=frame.iterator.next();LogicalPath path=logicalPath(root,file);String logical=path.utf8().orElse("raw-name-"+path.key());BasicFileAttributes attrs;
                    try{attrs=attributes(file);}catch(IOException unavailable){batch.add(new SqliteInventoryStore.Observation(gap(path,Kind.SPECIAL,"ENTRY_UNAVAILABLE"),""));flushIfFull(revision,batch);continue;}
                    Kind kind=attrs.isSymbolicLink()?Kind.SYMBOLIC_LINK:attrs.isDirectory()?Kind.DIRECTORY:attrs.isRegularFile()?Kind.FILE:Kind.SPECIAL;
                    InventoryEntry entry;
                    if(request.excludes(logical))entry=new InventoryEntry(path,kind,"",classify(logical),Disposition.EXCLUDED,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
                    else if(kind==Kind.DIRECTORY){
                        entry=new InventoryEntry(path,kind,"040000",classify(logical),Disposition.ACQUIRED,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
                        batch.add(new SqliteInventoryStore.Observation(entry,stamp(attrs)));flush(revision,batch);
                        try{stack.push(new Frame(file,logical));}catch(IOException unavailable){inventory.entries(revision,List.of(new SqliteInventoryStore.Observation(gap(path,kind,"DIRECTORY_UNAVAILABLE"),"")));}continue;
                    }else if(kind==Kind.FILE){
                        var previous=inventory.observation(revision,path);var handle=Optional.<ContentHandle>empty();
                        if(previous.isPresent()&&previous.orElseThrow().stamp().equals(stamp(attrs))&&previous.orElseThrow().entry().content().isPresent()){
                            var captured=previous.orElseThrow().entry().content().orElseThrow();
                            if(content.verify(captured)&&matches(file,captured,cancelled))handle=Optional.of(captured);
                            else if(previous.orElseThrow().entry().disposition()==Disposition.ACQUIRED)throw new IOException("SOURCE_CHANGED_SINCE_CHECKPOINT");
                        }
                        try{
                            if(handle.isEmpty())try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){handle=Optional.of(content.put(input,cancelled));}
                            if(!stamp(attrs).equals(stamp(attributes(file)))||handle.orElseThrow().size()!=attrs.size())throw new IOException("SOURCE_CHANGED_DURING_READ");
                            entry=new InventoryEntry(path,kind,"100644",classify(logical),Disposition.ACQUIRED,handle,Optional.empty(),Optional.empty(),List.of(),Optional.empty());
                            if(isLfsPointer(handle.orElseThrow()))entry=new InventoryEntry(path,kind,"100644",classify(logical),Disposition.GAP,handle,Optional.empty(),Optional.empty(),List.of(),Optional.of("LFS_OBJECT_REQUIRED"));
                        }catch(IOException failure){
                            if(isStorageFailure(failure))throw failure;
                            entry=gap(path,kind,"FILE_UNAVAILABLE_OR_CHANGED");
                        }
                    }else if(kind==Kind.SYMBOLIC_LINK){
                        String target=Files.readSymbolicLink(file).toString();
                        var handle=content.put(new ByteArrayInputStream(target.getBytes(java.nio.charset.StandardCharsets.UTF_8)),cancelled);
                        Path destination=file.getParent().resolve(target).normalize();
                        boolean contained=!Path.of(target).isAbsolute()&&destination.startsWith(root);
                        entry=new InventoryEntry(path,kind,"120000",classify(logical),contained?Disposition.ACQUIRED:Disposition.GAP,Optional.of(handle),Optional.of(target),Optional.empty(),List.of(),contained?Optional.empty():Optional.of("LINK_OUTSIDE_SELECTION"));
                    }else entry=gap(path,kind,"NON_REGULAR_ENTRY");
                    batch.add(new SqliteInventoryStore.Observation(entry,stamp(attrs)));flushIfFull(revision,batch);
                }
            }finally{for(var frame:stack)frame.close();flush(revision,batch);}
            // A second stat/hash pass detects changes to previously visited regions, including deleted entries.
            Optional<String> cursor=Optional.empty();do{
                var page=inventory.page(revision,256,cursor);
                for(var entry:page.entries()){
                    check(cancelled);if(entry.disposition()!=Disposition.ACQUIRED)continue;
                    Path file=physicalPath(root,entry.path());boolean valid;
                    try{
                        var observed=inventory.observation(revision,entry.path()).orElseThrow();valid=observed.stamp().equals(stamp(attributes(file)));
                        if(entry.kind()==Kind.FILE&&valid)valid=matches(file,entry.content().orElseThrow(),cancelled);
                        if(entry.kind()==Kind.SYMBOLIC_LINK&&valid)valid=validLink(root,file,entry.target().orElseThrow(),revision);
                    }catch(IOException failure){valid=false;}
                    if(!valid)batch.add(new SqliteInventoryStore.Observation(gap(entry.path(),entry.kind(),"SOURCE_CHANGED_OR_LINK_UNCLOSED"),""));flushIfFull(revision,batch);
                }cursor=page.continuation();
            }while(cursor.isPresent());flush(revision,batch);check(cancelled);
            job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"FRONTIER_CLOSED",Optional.empty());
            var manifest=inventory.seal(revision,request.repository(),request.identity());
            var payload=content.put(new ByteArrayInputStream(CanonicalJson.write(manifest).getBytes(java.nio.charset.StandardCharsets.UTF_8)),cancelled);
            job=inventory.transition(revision,job.revision(),JobJournal.State.COMPLETE,"",Optional.of(payload));
            return new Receipt(revision,job.state(),Optional.of(manifest),"");
        }catch(CancellationException cancelledWork){
            job=inventory.transition(revision,job.revision(),JobJournal.State.WAITING_FOR_INPUT,"CANCELLED_CHECKPOINT",Optional.empty());
            return new Receipt(revision,job.state(),Optional.empty(),job.reason());
        }catch(DirectoryIteratorException interruptedDirectory){
            job=inventory.transition(revision,job.revision(),JobJournal.State.WAITING_FOR_INPUT,"DIRECTORY_READ_FAILED",Optional.empty());
            return new Receipt(revision,job.state(),Optional.empty(),job.reason());
        }catch(IOException failure){
            String reason=stableReason(failure);JobJournal.State state=isStorageFailure(failure)?JobJournal.State.WAITING_FOR_RESOURCES:JobJournal.State.WAITING_FOR_INPUT;
            try{job=inventory.transition(revision,job.revision(),state,reason,Optional.empty());}catch(IOException cannotWrite){/* Prior durable RUNNING checkpoint is resumable after storage recovery. */}
            return new Receipt(revision,state,Optional.empty(),reason);
        }
    }
    private boolean validLink(Path root,Path file,String target,String revision)throws IOException{
        var visited=new HashSet<Path>();Path current=file;
        while(true){if(!visited.add(current))return false;Path destination=current.getParent().resolve(target).normalize();if(!destination.startsWith(root)||destination.equals(root))return false;
            String logical=root.relativize(destination).toString().replace('\\','/');var found=inventory.observation(revision,LogicalPath.utf8(logical));if(found.isEmpty())return false;var e=found.orElseThrow().entry();
            if(e.disposition()!=Disposition.ACQUIRED)return false;if(e.kind()!=Kind.SYMBOLIC_LINK)return true;current=destination;target=e.target().orElseThrow();}
    }
    private boolean isLfsPointer(ContentHandle handle)throws IOException{
        if(handle.size()>1024)return false;try(var input=content.open(handle)){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/v1\n");}
    }
    private static InventoryEntry gap(LogicalPath path,Kind kind,String reason){return new InventoryEntry(path,kind,"",classify(path.utf8().orElse("")),Disposition.GAP,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),Optional.of(reason));}
    private void flushIfFull(String revision,List<SqliteInventoryStore.Observation> batch)throws IOException{if(batch.size()>=256)flush(revision,batch);}
    private void flush(String revision,List<SqliteInventoryStore.Observation> batch)throws IOException{if(!batch.isEmpty()){inventory.entries(revision,batch);batch.clear();}}
    static BasicFileAttributes attributes(Path path)throws IOException{StreamingContentStore.safe(path.getParent());return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    private static LogicalPath logicalPath(Path root,Path file)throws IOException{
        if(Files.isSymbolicLink(file)){
            String relative=root.relativize(file).toString().replace('\\','/');if(relative.indexOf('\uFFFD')>=0)throw new IOException("RAW_LINK_NAME_ENCODING_REQUIRED");
            try{return LogicalPath.utf8(relative);}catch(IllegalArgumentException invalid){throw new IOException("INVALID_LOGICAL_PATH");}
        }
        String base=java.net.URI.create(root.toUri().toASCIIString()).getRawPath();if(!base.endsWith("/"))base+="/";String raw=java.net.URI.create(file.toUri().toASCIIString()).getRawPath();if(!raw.startsWith(base))throw new IOException("PATH_OUTSIDE_ROOT");
        String relative=raw.substring(base.length());if(relative.endsWith("/"))relative=relative.substring(0,relative.length()-1);var bytes=new ByteArrayOutputStream();
        for(int i=0;i<relative.length();i++){char c=relative.charAt(i);if(c=='%'){if(i+2>=relative.length())throw new IOException("INVALID_LOGICAL_PATH");try{bytes.write(Integer.parseInt(relative.substring(i+1,i+3),16));}catch(NumberFormatException invalid){throw new IOException("INVALID_LOGICAL_PATH");}i+=2;}else{if(c>127)throw new IOException("INVALID_LOGICAL_PATH");bytes.write(c);}}
        try{return LogicalPath.of(bytes.toByteArray());}catch(IllegalArgumentException invalid){throw new IOException("INVALID_LOGICAL_PATH");}
    }
    private static Path physicalPath(Path root,LogicalPath path)throws IOException{
        String base=root.toUri().toASCIIString();if(!base.endsWith("/"))base+="/";var encoded=new StringBuilder();for(byte b:path.bytes()){int value=b&255;if(value=='/')encoded.append('/');else encoded.append('%').append(HexFormat.of().toHexDigits(b));}
        try{Path physical=Path.of(java.net.URI.create(base+encoded));if(!physical.normalize().startsWith(root))throw new IOException("PATH_OUTSIDE_ROOT");return physical;}catch(IllegalArgumentException invalid){throw new IOException("PATH_MAPPING_UNAVAILABLE");}
    }
    static String stamp(BasicFileAttributes a){return a.size()+":"+a.lastModifiedTime()+":"+a.fileKey()+":"+a.isDirectory()+":"+a.isSymbolicLink();}
    private static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static boolean matches(Path file,ContentHandle handle,BooleanSupplier cancelled)throws IOException{
        var digest=StreamingContentStore.sha256();long size=0;try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){check(cancelled);digest.update(buffer,0,n);size=Math.addExact(size,n);}}
        return size==handle.size()&&handle.digest().value().equals("sha256:"+HexFormat.of().formatHex(digest.digest()));
    }
    private static boolean isStorageFailure(IOException failure){return failure instanceof FileSystemException||String.valueOf(failure.getMessage()).matches(".*(DISK|CAS_|JOURNAL_|STORAGE).*" );}
    private static String stableReason(IOException failure){String reason=failure.getMessage();return reason!=null&&reason.matches("[A-Z_]+")?reason:"ACQUISITION_IO_UNAVAILABLE";}

    /** Small-input adapter only. A failed budget never truncates the sealed inventory or invents a root snapshot. */
    public RepositoryAcquisitionResult compatibility(String revision,RepositoryAcquisitionRequest request,Predicate<InventoryEntry> selected)throws IOException{
        return compatibility(revision,request,selected,"caller-selected-files:m8.4-v2");
    }
    public RepositoryAcquisitionResult compatibility(String revision,RepositoryAcquisitionRequest request,Predicate<InventoryEntry> selected,String selectionPolicy)throws IOException{
        if(inventory.manifest(revision).isEmpty())throw new IOException("INVENTORY_NOT_SEALED");
        var files=new ArrayList<AcquiredFile>();var directories=new TreeSet<String>();directories.add(".");long bytes=0;Optional<String> cursor=Optional.empty();
        do{var page=inventory.page(revision,256,cursor);for(var e:page.entries()){
            if(e.disposition()!=Disposition.ACQUIRED||!selected.test(e))continue;
            String path=e.path().utf8().orElseThrow(()->new IOException("V1_PATH_UNREPRESENTABLE"));
            if(path.chars().filter(c->c=='/').count()>request.policy().maxDepth())throw new IOException("COMPATIBILITY_WORKER_REQUIRED");
            try{ContractChecks.repositoryRelativePath(path,"v1 path");}catch(IllegalArgumentException invalid){throw new IOException("V1_PATH_UNREPRESENTABLE");}
            if(e.kind()==Kind.DIRECTORY){if(directories.size()>=request.policy().maxDirectories())throw new IOException("COMPATIBILITY_WORKER_REQUIRED");directories.add(path);continue;}
            if(e.kind()!=Kind.FILE)throw new IOException("V1_KIND_UNREPRESENTABLE");var handle=e.content().orElseThrow();bytes=Math.addExact(bytes,handle.size());
            for(int slash=path.lastIndexOf('/');slash>0;slash=path.lastIndexOf('/',slash-1)){directories.add(path.substring(0,slash));if(directories.size()>request.policy().maxDirectories())throw new IOException("COMPATIBILITY_WORKER_REQUIRED");}
            if(files.size()>=request.policy().maxFiles()||handle.size()>request.policy().maxFileBytes()||bytes>request.policy().maxTotalBytes())throw new IOException("COMPATIBILITY_WORKER_REQUIRED");
            try(var input=content.open(handle)){byte[] value=input.readNBytes(Math.toIntExact(handle.size()));if(value.length!=handle.size()||!ContentDigest.sha256(value).equals(handle.digest()))throw new IOException("CAS_CORRUPT");files.add(new AcquiredFile(path,value));}
        }cursor=page.continuation();}while(cursor.isPresent());
        var snapshot=RepositorySnapshot.create(request.repository(),request.revision(),request.dirty(),files.stream().map(f->new SnapshotFile(f.path(),f.contentDigest())).toList(),List.of());
        var problems=files.stream().noneMatch(f->f.path().equals(request.rootPom()))?List.of(new RepositoryAcquisitionResult.Problem(RepositoryAcquisitionResult.Reason.MISSING_ROOT_POM,request.rootPom(),RepositoryAcquisitionResult.Requirement.BUILD_MODEL_INPUT)):List.<RepositoryAcquisitionResult.Problem>of();
        var selection=Map.of("schema","compatibility.selection:m8.4-v2","inventory",inventory.manifest(revision).orElseThrow().inventory(),"request",request.identity(),"snapshot",snapshot.identity(),"selectedFiles",files.size(),"selectedBytes",bytes,"selectionPolicy",Objects.requireNonNull(selectionPolicy));
        var handle=content.put(new ByteArrayInputStream(CanonicalJson.write(selection).getBytes(java.nio.charset.StandardCharsets.UTF_8)),()->false);
        var selectionIdentity=CanonicalJson.sha256(selection);inventory.create(revision+".compat."+selectionIdentity.value().substring(7),selectionIdentity,Optional.of(handle));
        return new RepositoryAcquisitionResult(RepositoryAcquisitionResult.SCHEMA,request,new VersionedIdentifier("repository.cas-compatibility","m8.4-v2"),RepositoryAcquisitionResult.Completion.COMPLETE,Optional.of(snapshot),files,List.copyOf(directories),problems,List.of(),RepositoryAcquisitionResult.LIMITATIONS);
    }
}
