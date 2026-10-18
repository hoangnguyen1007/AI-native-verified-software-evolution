package com.evolution.analysis.filesystem;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static com.evolution.analysis.acquisition.streaming.InventoryEntry.*;

/** No checkout or target execution: hydrate the complete pinned tree through verified native Git objects. */
public final class GitSnapshotAcquirer {
    private final StreamingContentStore content;private final SqliteInventoryStore inventory;private final GitHostPolicy policy;private final String executable;private final long idleMillis;
    public GitSnapshotAcquirer(StreamingContentStore content,SqliteInventoryStore inventory,GitHostPolicy policy,String executable,long idleMillis){
        this.content=content;this.inventory=inventory;this.policy=policy;this.executable=executable;if(idleMillis<1000)throw new IllegalArgumentException("Invalid progress lease");this.idleMillis=idleMillis;
    }
    public StreamingRepositoryAcquirer.Receipt acquire(Path workspace,String revision,URI uri,String ref,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException{
        return acquire(workspace,revision,uri,ref,request,cancelled,new HashSet<>(),workspace.toAbsolutePath().normalize());
    }
    private StreamingRepositoryAcquirer.Receipt acquire(Path workspace,String revision,URI uri,String ref,StreamingAcquisitionRequest request,BooleanSupplier cancelled,Set<String> ancestry,Path rootWorkspace)throws IOException{
        GitHostPolicy.syntax(uri.toASCIIString());if(!ref.matches("[A-Za-z0-9][A-Za-z0-9_./-]{0,255}")||ref.contains(".."))throw new IllegalArgumentException("Invalid ref");
        var identity=CanonicalJson.sha256(List.of(request.identity(),uri.toASCIIString(),ref));var job=inventory.create(revision,identity,Optional.empty());
        if(job.state()==JobJournal.State.COMPLETE)return inventory.verifySeal(revision,cancelled)?new StreamingRepositoryAcquirer.Receipt(revision,job.state(),inventory.manifest(revision),""):new StreamingRepositoryAcquirer.Receipt(revision,JobJournal.State.WAITING_FOR_INPUT,Optional.empty(),"SEALED_CONTENT_UNAVAILABLE");
        if(job.state()==JobJournal.State.CANCELLED||job.state()==JobJournal.State.FAILED)return new StreamingRepositoryAcquirer.Receipt(revision,job.state(),Optional.empty(),job.reason());
        job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"",job.payload());
        try{
            workspace=StreamingContentStore.directory(workspace);Path git=workspace.resolve("objects.git");StreamingContentStore.safe(git);
            String version=small(workspace,List.of("--version"),cancelled).strip();var match=java.util.regex.Pattern.compile("git version ([0-9]+)\\.([0-9]+).*" ).matcher(version);
            if(!match.matches()||Integer.parseInt(match.group(1))<2||Integer.parseInt(match.group(1))==2&&Integer.parseInt(match.group(2))<53)throw new IOException("GIT_TOOLCHAIN_UPGRADE_REQUIRED");
            var provenance=Map.of("schema","git-capture-request:m8.4-v2","selection",request,"remote",uri.toASCIIString(),"ref",ref,"gitVersion",version,"credential",policy.credentialReference(uri));
            var descriptor=content.put(new ByteArrayInputStream(CanonicalJson.write(provenance).getBytes(StandardCharsets.UTF_8)),cancelled);
            inventory.create(revision+".request."+descriptor.digest().value().substring(7,23),identity,Optional.of(descriptor));
            if(!Files.exists(git))run(workspace,List.of("init","--bare","--template="+workspace.resolve("empty-config"),git.toString()),Optional.empty(),cancelled);
            String commit;
            if(job.payload().isPresent())try(var input=content.open(job.payload().orElseThrow())){commit=new String(input.readNBytes(129),StandardCharsets.US_ASCII);}
            else{
                IOException last=null;
                for(int attempt=0;attempt<3;attempt++){
                    if(cancelled.getAsBoolean())throw new CancellationException();
                    var started=java.time.Instant.now();
                    try{
                        quarantineIncompleteObjects(workspace,git);
                        var endpoint=policy.endpoint(uri);
                        // Fetch all blobs: no partial-checkout claim and no history/size admission limit.
                        var args=new ArrayList<>(List.of("--git-dir="+git,"fetch","--progress","--no-tags","--no-recurse-submodules"));if(attempt==0)args.add("--depth=1");args.addAll(List.of("--",uri.toASCIIString(),ref));
                        run(workspace,args,Optional.of(endpoint),cancelled);recordAttempt(revision,uri,ref,attempt,started,"SUCCEEDED","");last=null;break;
                    }catch(IOException failure){last=failure;String reason=failure.getMessage();if(reason==null||!reason.matches("[A-Z_]+"))reason="GIT_IO_UNAVAILABLE";recordAttempt(revision,uri,ref,attempt,started,"FAILED",reason);if(List.of("GIT_ACCESS_REQUIRED","GIT_NOT_FOUND","GIT_TLS_FAILED","ENDPOINT_DENIED").contains(failure.getMessage()))throw failure;
                        if(attempt<2){try{Thread.sleep(100L*(1L<<attempt)+new java.security.SecureRandom().nextInt(100));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException();}}}
                }
                if(last!=null)throw last;
                commit=small(workspace,List.of("--git-dir="+git,"rev-parse","--verify","FETCH_HEAD^{commit}"),cancelled).strip();
                if(!commit.matches("[0-9a-f]{40}|[0-9a-f]{64}"))throw new IOException("GIT_INVALID_COMMIT");
                var pin=content.put(new ByteArrayInputStream(commit.getBytes(StandardCharsets.US_ASCII)),cancelled);
                job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"PINNED_COMMIT",Optional.of(pin));
            }
            if(!commit.matches("[0-9a-f]{40}|[0-9a-f]{64}"))throw new IOException("GIT_INVALID_PIN");
            if(!ancestry.add(uri+"@"+commit))throw new IOException("SUBMODULE_CYCLE");
            if(inventory.get(revision+".git-tree").isEmpty()){
                ContentHandle commitBytes;
                try(var objects=new SafeGitProcess(executable,workspace,List.of("--git-dir="+git,"cat-file","--batch"),Optional.empty(),policy,cancelled,idleMillis)){
                    commitBytes=captureObject(objects,commit,"commit",cancelled);
                    String first;try(var input=content.range(commitBytes,0,Math.min(256,commitBytes.size()))){first=new String(input.readAllBytes(),StandardCharsets.US_ASCII).split("\n",2)[0];}
                    if(!first.matches("tree ([0-9a-f]{40}|[0-9a-f]{64})"))throw new IOException("GIT_INVALID_COMMIT");String tree=first.substring(5);var treeBytes=captureObject(objects,tree,"tree",cancelled);
                    var lineage=content.put(new ByteArrayInputStream(CanonicalJson.write(Map.of("commit",commit,"commitBytes",commitBytes,"tree",tree,"treeBytes",treeBytes)).getBytes(StandardCharsets.UTF_8)),cancelled);
                    inventory.create(revision+".git-tree",CanonicalJson.sha256(List.of(commit,tree)),Optional.of(lineage));
                }
            }
            if(inventory.manifest(revision).isEmpty()){
                hydrate(workspace,git,commit,revision,request,cancelled);
                closeLfs(revision,uri,cancelled);
                closeSubmodules(rootWorkspace,revision,uri,request,cancelled,ancestry);
                InventoryLinkResolver.resolve(inventory,revision);
            }
            if(cancelled.getAsBoolean())throw new CancellationException();job=inventory.transition(revision,job.revision(),JobJournal.State.RUNNING,"FRONTIER_CLOSED",job.payload());var manifest=inventory.seal(revision,request.repository(),request.identity());
            job=inventory.transition(revision,job.revision(),JobJournal.State.COMPLETE,"PINNED_COMMIT",job.payload());return new StreamingRepositoryAcquirer.Receipt(revision,job.state(),Optional.of(manifest),"");
        }catch(IOException|CancellationException failure){
            String reason=failure instanceof CancellationException?"CANCELLED_CHECKPOINT":failure.getMessage();if(reason==null||!reason.matches("[A-Z_]+"))reason="GIT_ACQUISITION_UNAVAILABLE";
            JobJournal.State state=reason.contains("DISK")?JobJournal.State.WAITING_FOR_RESOURCES:JobJournal.State.WAITING_FOR_INPUT;
            try{job=inventory.transition(revision,job.revision(),state,reason,job.payload());}catch(IOException cannotWrite){ }
            return new StreamingRepositoryAcquirer.Receipt(revision,state,Optional.empty(),reason);
        }
    }
    private void recordAttempt(String revision,URI uri,String ref,int attempt,java.time.Instant started,String outcome,String reason)throws IOException{
        var record=Map.of("schema","git-fetch-attempt:m8.4-v2","remote",uri.toASCIIString(),"ref",ref,"attempt",attempt,"outcome",outcome,"reason",reason,"started",started,"finished",java.time.Instant.now(),"idleMillis",idleMillis);
        var handle=content.put(new ByteArrayInputStream(CanonicalJson.write(record).getBytes(StandardCharsets.UTF_8)),()->false);
        inventory.create(revision+".fetch."+UUID.randomUUID(),CanonicalJson.sha256(List.of(uri.toASCIIString(),ref,attempt)),Optional.of(handle));
    }
    private static void quarantineIncompleteObjects(Path workspace,Path git)throws IOException{
        Path objects=git.resolve("objects");if(!Files.isDirectory(objects,LinkOption.NOFOLLOW_LINKS))return;
        Path quarantine=workspace.resolve("interrupted-objects").resolve(UUID.randomUUID().toString());
        try(var directories=Files.newDirectoryStream(objects)){
            for(var directory:directories){String name=directory.getFileName().toString();if(!(name.matches("[0-9a-f]{2}")||name.equals("pack"))||!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))continue;
                StreamingContentStore.safe(directory);
                try(var files=Files.newDirectoryStream(directory)){
                    for(var file:files){String leaf=file.getFileName().toString();if(!leaf.endsWith(".temp")&&!leaf.endsWith(".prev"))continue;
                        StreamingContentStore.safe(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("UNSAFE_GIT_TEMP_OBJECT");
                        StreamingContentStore.directory(quarantine);Path destination=quarantine.resolve(name+"-"+leaf).toAbsolutePath().normalize();Path source=file.toAbsolutePath().normalize();
                        if(!source.startsWith(workspace.toAbsolutePath().normalize())||!destination.startsWith(workspace.toAbsolutePath().normalize()))throw new IOException("UNSAFE_GIT_TEMP_OBJECT");
                        // Preserve interrupted bytes, but do not let native Git perform an unvalidated HTTP range append.
                        Files.move(source,destination);
                    }
                }
            }
        }
    }
    private void closeLfs(String revision,URI uri,BooleanSupplier cancelled)throws IOException{
        var lfs=new LfsObjectAcquirer(content,policy);Optional<String> cursor=Optional.empty();
        do{var page=inventory.page(revision,256,cursor);for(var entry:page.entries()){
            if(!entry.problem().filter(p->p.startsWith("LFS_")).isPresent()||entry.content().isEmpty())continue;
            try{
                var pointer=lfs.pointer(entry.content().orElseThrow()).orElseThrow(()->new IOException("LFS_POINTER_INVALID"));var actual=lfs.acquire(uri,pointer,cancelled);
                var resolved=new InventoryEntry(entry.path(),entry.kind(),entry.mode(),entry.population(),Disposition.ACQUIRED,Optional.of(actual),Optional.of("lfs-pointer:"+pointer.pointerBytes().digest().value()+";size="+pointer.pointerBytes().size()),entry.encoding(),entry.ownershipCandidates(),Optional.empty());
                inventory.entries(revision,List.of(new SqliteInventoryStore.Observation(resolved,inventory.observation(revision,entry.path()).orElseThrow().stamp())));
            }catch(IOException failure){if(cancelled.getAsBoolean())throw new CancellationException();/* Missing LFS objects keep their captured pointer and active obligation. */}
        }cursor=page.continuation();}while(cursor.isPresent());
    }
    private void closeSubmodules(Path workspace,String revision,URI parent,StreamingAcquisitionRequest request,BooleanSupplier cancelled,Set<String> ancestry)throws IOException{
        var config=inventory.observation(revision,LogicalPath.utf8(".gitmodules"));Optional<String> cursor=Optional.empty();
        do{var page=inventory.page(revision,256,cursor);for(var entry:page.entries()){
            if(entry.kind()!=Kind.GITLINK||entry.disposition()==Disposition.EXCLUDED||entry.disposition()==Disposition.ACQUIRED)continue;
            if(config.isEmpty()||config.orElseThrow().entry().content().isEmpty())continue;
            String path=entry.path().utf8().orElse(null);if(path==null)continue;
            var url=submoduleUrl(config.orElseThrow().entry().content().orElseThrow(),path);if(url.isEmpty())continue;
            URI child;
            try{String value=url.orElseThrow();child=value.startsWith("../")||value.startsWith("./")?parent.resolve(parent.getPath().endsWith("/")?value:parent.getPath()+"/"+value).normalize():GitHostPolicy.syntax(value);GitHostPolicy.syntax(child.toString());policy.endpoint(child);}catch(IllegalArgumentException|IOException denied){continue;}
            String commit=entry.target().orElseThrow(),key=child+"@"+commit;if(ancestry.contains(key))continue;
            String childRevision="sub-"+CanonicalJson.sha256(List.of(revision,path,child.toASCIIString(),commit)).value().substring(7);
            var childRequest=new StreamingAcquisitionRequest(request.repository(),Optional.of(commit),false,List.of());
            // Each child is a separately sealed snapshot; the parent retains its gitlink identity and qualified closure.
            var receipt=acquire(workspace.resolve("submodules").resolve(childRevision),childRevision,child,commit,childRequest,cancelled,new HashSet<>(ancestry),workspace);
            if(receipt.manifest().isPresent()){
                var manifest=receipt.manifest().orElseThrow();var handle=content.put(new ByteArrayInputStream(CanonicalJson.write(manifest).getBytes(StandardCharsets.UTF_8)),cancelled);
                var resolved=new InventoryEntry(entry.path(),Kind.GITLINK,entry.mode(),entry.population(),Disposition.ACQUIRED,Optional.of(handle),Optional.of("closure:"+manifest.inventory().value()+";gitlink="+commit+";selection="+manifest.selection().value()),Optional.empty(),List.of(),Optional.empty());
                copySubmodule(revision,entry.path(),childRevision);
                inventory.entries(revision,List.of(new SqliteInventoryStore.Observation(resolved,commit)));
            }
        }cursor=page.continuation();}while(cursor.isPresent());
    }
    private void copySubmodule(String revision,LogicalPath prefix,String childRevision)throws IOException{
        Optional<String> cursor=Optional.empty();do{var page=inventory.page(childRevision,256,cursor);var batch=new ArrayList<SqliteInventoryStore.Observation>();
            for(var e:page.entries()){byte[] left=prefix.bytes(),right=e.path().bytes(),path=new byte[left.length+1+right.length];System.arraycopy(left,0,path,0,left.length);path[left.length]='/';System.arraycopy(right,0,path,left.length+1,right.length);
                var full=new InventoryEntry(LogicalPath.of(path),e.kind(),e.mode(),e.population(),e.disposition(),e.content(),e.target(),e.encoding(),e.ownershipCandidates(),e.problem());batch.add(new SqliteInventoryStore.Observation(full,childRevision));}
            if(!batch.isEmpty())inventory.entries(revision,batch);cursor=page.continuation();}while(cursor.isPresent());
    }
    private Optional<String> submoduleUrl(ContentHandle config,String expectedPath)throws IOException{
        try(var reader=new BufferedReader(new InputStreamReader(content.open(config),StandardCharsets.UTF_8))){String path=null,url=null,line;boolean active=false;
            while((line=reader.readLine())!=null){if(line.length()>1_048_576)throw new IOException("SUBMODULE_METADATA_OVERSIZE");String value=line.strip();
                if(value.startsWith("[")){if(active&&expectedPath.equals(path)&&url!=null)return Optional.of(url);active=value.matches("\\[submodule \\\"[^\\\"]+\\\"\\]");path=null;url=null;}
                else if(active){int equals=value.indexOf('=');if(equals<1)continue;String name=value.substring(0,equals).strip(),operand=value.substring(equals+1).strip();
                    if(operand.startsWith("\"")&&operand.endsWith("\""))operand=operand.substring(1,operand.length()-1);
                    if(name.equals("path")){if(path!=null)throw new IOException("SUBMODULE_CONFIG_AMBIGUOUS");path=operand;}else if(name.equals("url")){if(url!=null)throw new IOException("SUBMODULE_CONFIG_AMBIGUOUS");url=operand;}}
            }return active&&expectedPath.equals(path)?Optional.ofNullable(url):Optional.empty();
        }
    }
    private void hydrate(Path workspace,Path git,String commit,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException{
        var batch=new ArrayList<SqliteInventoryStore.Observation>(256);
        try(var tree=new SafeGitProcess(executable,workspace,List.of("--git-dir="+git,"ls-tree","-rzt","--full-tree",commit),Optional.empty(),policy,cancelled,idleMillis);
            var blobs=new SafeGitProcess(executable,workspace,List.of("--git-dir="+git,"cat-file","--batch"),Optional.empty(),policy,cancelled,idleMillis)){
            byte[] record;
            while((record=terminated(tree.output,0,1_048_576))!=null){
                if(cancelled.getAsBoolean())throw new CancellationException();int tab=-1;for(int i=0;i<record.length;i++)if(record[i]=='\t'){tab=i;break;}if(tab<0)throw new IOException("GIT_INVALID_TREE");
                String[] header=new String(record,0,tab,StandardCharsets.US_ASCII).split(" ");if(header.length!=3||!header[2].matches("[0-9a-f]{40}|[0-9a-f]{64}"))throw new IOException("GIT_INVALID_TREE");
                String mode=header[0],object=header[2];var path=LogicalPath.of(Arrays.copyOfRange(record,tab+1,record.length));String logical=path.utf8().orElse("");
                Kind kind=switch(mode){case "040000"->Kind.DIRECTORY;case "120000"->Kind.SYMBOLIC_LINK;case "160000"->Kind.GITLINK;case "100644","100755"->Kind.FILE;default->Kind.SPECIAL;};
                InventoryEntry entry;var previous=inventory.observation(revision,path);
                if(request.excludes(logical))entry=new InventoryEntry(path,kind,mode,classify(logical),Disposition.EXCLUDED,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
                else if(previous.isPresent()&&previous.orElseThrow().stamp().equals(object)&&previous.orElseThrow().entry().disposition()==Disposition.ACQUIRED
                        &&(kind==Kind.DIRECTORY||previous.orElseThrow().entry().content().isPresent()&&content.verify(previous.orElseThrow().entry().content().orElseThrow())))continue;
                else if(kind==Kind.DIRECTORY)entry=new InventoryEntry(path,kind,mode,classify(logical),Disposition.ACQUIRED,Optional.of(captureObject(blobs,object,"tree",cancelled)),Optional.empty(),Optional.empty(),List.of(),Optional.empty());
                else if(kind==Kind.GITLINK)entry=new InventoryEntry(path,kind,mode,classify(logical),Disposition.GAP,Optional.empty(),Optional.of(object),Optional.empty(),List.of(),Optional.of("SUBMODULE_COMMIT_REQUIRED"));
                else if(kind==Kind.SPECIAL)entry=new InventoryEntry(path,kind,mode,classify(logical),Disposition.GAP,Optional.empty(),Optional.empty(),Optional.empty(),List.of(),Optional.of("GIT_MODE_UNKNOWN"));
                else{
                    blobs.input.write((object+"\n").getBytes(StandardCharsets.US_ASCII));blobs.input.flush();String[] info=new String(Objects.requireNonNull(terminated(blobs.output,10,1024)),StandardCharsets.US_ASCII).split(" ");
                    if(info.length!=3||!info[0].equals(object)||!info[1].equals("blob"))throw new IOException("GIT_OBJECT_UNAVAILABLE");long length;try{length=Long.parseLong(info[2]);if(length<0)throw new NumberFormatException();}catch(NumberFormatException bad){throw new IOException("GIT_INVALID_OBJECT_SIZE");}
                    MessageDigest objectDigest;try{objectDigest=MessageDigest.getInstance(object.length()==40?"SHA-1":"SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
                    objectDigest.update(("blob "+length+"\0").getBytes(StandardCharsets.US_ASCII));
                    var limited=new LimitedInput(blobs.output,length);ContentHandle captured=content.put(new DigestInputStream(limited,objectDigest),cancelled);
                    if(limited.remaining!=0||blobs.output.read()!=10||!object.equals(HexFormat.of().formatHex(objectDigest.digest())))throw new IOException("GIT_OBJECT_CHECKSUM_MISMATCH");
                    Optional<String> target=Optional.empty(),problem=Optional.empty();
                    if(kind==Kind.SYMBOLIC_LINK){if(length>1_048_576)problem=Optional.of("LINK_METADATA_OVERSIZE");else try(var input=content.open(captured)){
                        try{String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(input.readAllBytes())).toString();target=Optional.of(value);if(!safeLink(path,value))problem=Optional.of("LINK_OUTSIDE_SELECTION");}
                        catch(java.nio.charset.CharacterCodingException invalid){problem=Optional.of("LINK_TARGET_ENCODING_REQUIRED");}
                    }}
                    else if(length<1024)try(var input=content.open(captured)){if(new String(input.readAllBytes(),StandardCharsets.US_ASCII).startsWith("version https://git-lfs.github.com/spec/v1\n"))problem=Optional.of("LFS_OBJECT_REQUIRED");}
                    entry=new InventoryEntry(path,kind,mode,classify(logical),problem.isPresent()?Disposition.GAP:Disposition.ACQUIRED,Optional.of(captured),target,Optional.empty(),List.of(),problem);
                }
                batch.add(new SqliteInventoryStore.Observation(entry,object));if(batch.size()==256){inventory.entries(revision,batch);batch.clear();}
            }
            if(tree.finish()!=0)throw new IOException(tree.failure());
        }finally{if(!batch.isEmpty())inventory.entries(revision,batch);}
    }
    private ContentHandle captureObject(SafeGitProcess objects,String object,String kind,BooleanSupplier cancelled)throws IOException{
        objects.input.write((object+"\n").getBytes(StandardCharsets.US_ASCII));objects.input.flush();byte[] header=terminated(objects.output,10,1024);if(header==null)throw new IOException("GIT_OBJECT_UNAVAILABLE");
        String[] fields=new String(header,StandardCharsets.US_ASCII).split(" ");if(fields.length!=3||!fields[0].equals(object)||!fields[1].equals(kind))throw new IOException("GIT_OBJECT_UNAVAILABLE");
        long size;try{size=Long.parseLong(fields[2]);if(size<0)throw new NumberFormatException();}catch(NumberFormatException invalid){throw new IOException("GIT_INVALID_OBJECT_SIZE");}
        MessageDigest digest;try{digest=MessageDigest.getInstance(object.length()==40?"SHA-1":"SHA-256");}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        digest.update((kind+" "+size+"\0").getBytes(StandardCharsets.US_ASCII));var limited=new LimitedInput(objects.output,size);var handle=content.put(new DigestInputStream(limited,digest),cancelled);
        if(limited.remaining!=0||objects.output.read()!=10||!object.equals(HexFormat.of().formatHex(digest.digest())))throw new IOException("GIT_OBJECT_CHECKSUM_MISMATCH");return handle;
    }
    private static boolean safeLink(LogicalPath path,String target){
        if(target.isEmpty()||target.startsWith("/")||target.indexOf('\\')>=0||target.indexOf(':')>=0||target.indexOf('\0')>=0)return false;
        int depth=(int)path.utf8().orElse("").chars().filter(c->c=='/').count();for(String part:target.split("/")){if(part.equals("..")){if(--depth<0)return false;}else if(!part.equals(".")&&!part.isEmpty())depth++;}return true;
    }
    private void run(Path workspace,List<String> args,Optional<GitHostPolicy.Endpoint> endpoint,BooleanSupplier cancelled)throws IOException{
        try(var git=new SafeGitProcess(executable,workspace,args,endpoint,policy,cancelled,idleMillis)){git.input.close();git.output.transferTo(OutputStream.nullOutputStream());if(git.finish()!=0)throw new IOException(git.failure());}
    }
    private String small(Path workspace,List<String> args,BooleanSupplier cancelled)throws IOException{
        try(var git=new SafeGitProcess(executable,workspace,args,Optional.empty(),policy,cancelled,idleMillis)){git.input.close();byte[] bytes=git.output.readNBytes(4097);if(bytes.length>4096)throw new IOException("GIT_METADATA_OVERSIZE");if(git.finish()!=0)throw new IOException(git.failure());return new String(bytes,StandardCharsets.US_ASCII);}
    }
    static byte[] terminated(InputStream input,int terminator,int max)throws IOException{
        var bytes=new ByteArrayOutputStream();int b;while((b=input.read())!=-1){if(b==terminator)return bytes.toByteArray();if(bytes.size()>=max)throw new IOException("GIT_METADATA_OVERSIZE");bytes.write(b);}if(bytes.size()!=0)throw new EOFException("GIT_INTERRUPTED_OBJECT");return null;
    }
    static final class LimitedInput extends FilterInputStream{
        long remaining;LimitedInput(InputStream input,long length){super(input);remaining=length;}
        @Override public int read()throws IOException{if(remaining==0)return -1;int b=in.read();if(b<0)throw new EOFException();remaining--;return b;}
        @Override public int read(byte[] b,int off,int len)throws IOException{if(len==0)return 0;if(remaining==0)return -1;int n=in.read(b,off,(int)Math.min(len,remaining));if(n<0)throw new EOFException();remaining-=n;return n;}
        @Override public void close(){}
    }
}
