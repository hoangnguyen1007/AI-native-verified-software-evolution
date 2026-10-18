package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.filesystem.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Product composition for the durable acquisition journey; no compiler, SQL or target execution in this boundary. */
public final class RepositoryIntake implements AutoCloseable {
    private final Path root;
    private final StreamingContentStore content;
    private final SqliteInventoryStore inventory;
    public RepositoryIntake(Path root)throws IOException{
        this(root,false);
    }
    public RepositoryIntake(Path root,boolean readOnly)throws IOException{this.root=root.toAbsolutePath().normalize();content=new StreamingContentStore(this.root.resolve("content"));inventory=new SqliteInventoryStore(this.root.resolve("metadata"),content,readOnly);}
    public InventoryReader inventory(){return inventory;}
    public JobJournal journal(){return inventory;}
    public Optional<String> pinnedCommit(String revision)throws IOException{
        var job=inventory.get(revision);if(job.isEmpty()||job.orElseThrow().payload().isEmpty())return Optional.empty();
        try(var input=content.open(job.orElseThrow().payload().orElseThrow())){String pin=new String(input.readNBytes(129),java.nio.charset.StandardCharsets.US_ASCII);return pin.matches("[0-9a-f]{40}|[0-9a-f]{64}")?Optional.of(pin):Optional.empty();}
    }
    public StreamingRepositoryAcquirer.Receipt local(Path source,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException{
        if(root.startsWith(source.toAbsolutePath().normalize())){
            String state=source.toAbsolutePath().normalize().relativize(root).toString().replace('\\','/');
            if(state.isEmpty()||!request.excludes(state))throw new IOException("STATE_DIRECTORY_MUST_BE_EXCLUDED");
        }
        return new StreamingRepositoryAcquirer(content,inventory).acquire(source,revision,request,cancelled);
    }
    public StreamingRepositoryAcquirer.Receipt git(URI uri,String ref,String revision,StreamingAcquisitionRequest request,GitHostPolicy policy,String executable,BooleanSupplier cancelled)throws IOException{
        return new GitSnapshotAcquirer(content,inventory,policy,executable,60_000).acquire(root.resolve("git").resolve(ContentDigest.sha256Utf8(revision).value().substring(7)),revision,uri,ref,request,cancelled);
    }
    public StreamingRepositoryAcquirer.Receipt archive(Path archive,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException{
        ContentHandle captured;try(var input=Files.newInputStream(LocalAnalysisRunner.safe(archive),LinkOption.NOFOLLOW_LINKS)){captured=content.put(input,cancelled);}
        return new ArchiveSnapshotAcquirer(content,inventory).acquire(captured,revision,request,cancelled);
    }
    public RepositoryAcquisitionResult compatibility(String revision,RepositoryIdentity repository,Optional<String> sourceRevision,boolean dirty,boolean includeTests,boolean allFiles,List<String> exclusions)throws IOException{
        RepositoryAcquisitionRequest request;try{request=new RepositoryAcquisitionRequest(repository,sourceRevision,dirty,"pom.xml",new RepositoryAcquisitionPolicy(10_000,10_000,10_000_000,128_000_000,64,exclusions));}catch(IllegalArgumentException unsupported){throw new IOException("V1_SELECTION_UNREPRESENTABLE");}
        try{return new StreamingRepositoryAcquirer(content,inventory).compatibility(revision,request,e->{
            if(e.kind()==InventoryEntry.Kind.DIRECTORY||!ScanSelection.semanticInput(e,includeTests,allFiles))return false;
            String path=e.path().utf8().orElse("");if(exclusions.stream().anyMatch(p->path.equals(p)||path.startsWith(p+"/")))return false;
            return allFiles||!declaredOutput(revision,path);
        },com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("selector","java.compatibility:m8.4-v2","includeTests",includeTests,"allFiles",allFiles,"exclusions",exclusions)));}catch(UncheckedIOException unavailable){throw unavailable.getCause();}
    }
    private boolean declaredOutput(String revision,String path){
        String[] parts=path.split("/");String parent="";
        try{for(int i=0;i<parts.length-1;i++){
            String segment=parts[i];String prefix=parent.isEmpty()?"":parent+"/";
            if(segment.equals("target")&&exists(revision,prefix+"pom.xml")||(segment.equals("build")||segment.equals(".gradle"))&&(exists(revision,prefix+"build.gradle")||exists(revision,prefix+"build.gradle.kts"))||segment.equals("node_modules")&&exists(revision,prefix+"package.json"))return true;
            parent=prefix+segment;
        }return false;}catch(IOException unavailable){throw new UncheckedIOException(unavailable);}
    }
    private boolean exists(String revision,String path)throws IOException{return inventory.observation(revision,LogicalPath.utf8(path)).filter(o->o.entry().kind()==InventoryEntry.Kind.FILE&&o.entry().disposition()==InventoryEntry.Disposition.ACQUIRED).isPresent();}
    @Override public void close()throws IOException{inventory.close();}
}
