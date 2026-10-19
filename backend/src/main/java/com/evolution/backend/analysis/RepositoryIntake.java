package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.RepositoryIdentity;
import com.evolution.analysis.filesystem.*;
import com.evolution.analysis.buildmodel.PassiveReadinessDiscovery;
import com.evolution.analysis.maven.MavenReadinessDiscovery;
import com.evolution.analysis.readiness.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
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
    public ReadinessPlan readiness(String revision,ReadinessPlanner.Policy policy,BooleanSupplier cancelled)throws IOException{
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("READINESS_CANCELLED");
        var manifest=inventory.manifest(revision).orElseThrow(()->new IOException("SEALED_INVENTORY_REQUIRED"));
        var request=IngestionEvidence.digest(List.of(ReadinessPlan.PROVIDER,manifest,policy));
        try(var reports=new ReadinessReports(root.resolve("readiness"),content)){
            var saved=reports.get(request);if(saved.isPresent())return saved.orElseThrow();
            var plan=new ReadinessPlanner(List.of(new PassiveReadinessDiscovery(),new MavenReadinessDiscovery())).plan(revision,inventory,content,policy,cancelled);
            reports.save(request,plan,cancelled);return plan;
        }
    }
    public Object readinessPage(String revision,String section,int size,Optional<String> cursor)throws IOException{
        return readinessPage(revision,section,size,cursor,ReadinessPlanner.Policy.comprehensive());
    }
    public Object readinessPage(String revision,String section,int size,Optional<String> cursor,ReadinessPlanner.Policy policy)throws IOException{
        if(size<1||size>200)throw new IllegalArgumentException("Invalid readiness page");
        var plan=readiness(revision,policy,()->Thread.currentThread().isInterrupted());
        List<?> rows=switch(section){case "roots"->plan.roots();case "modules"->plan.modules();case "contexts"->plan.contexts();case "requirements"->plan.requirements();case "recipes"->plan.recipes();case "compilation-groups"->plan.compilationGroups();case "unknown"->plan.selection().unknown();case "selection"->plan.contexts().stream().map(context->Map.of("context",context.id(),"selected",plan.selection().selected().contains(context.id()))).toList();default->throw new IllegalArgumentException("Invalid readiness section");};
        int offset=0;
        if(cursor.isPresent()){
            String[] parts=cursor.orElseThrow().split(":",-1);if(parts.length!=3||!parts[0].equals(plan.identity().value().substring(7))||!parts[1].equals(section))throw new IllegalArgumentException("Foreign readiness cursor");
            try{offset=Integer.parseInt(parts[2]);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid readiness cursor");}
            if(offset<0||offset>rows.size())throw new IllegalArgumentException("Invalid readiness cursor");
        }
        int end=(int)Math.min(rows.size(),(long)offset+size);
        return Map.of("schema",plan.schema(),"identity",plan.identity(),"inventory",plan.inventory(),"selection",Map.of("identity",plan.selection().identity(),"discovered",plan.selection().discovered().size(),"selected",plan.selection().selected().size(),"unselected",plan.selection().unselected().size(),"unknownDimensions",plan.selection().unknown().size()),
                "ownership",plan.ownership(),"section",section,"rows",rows.subList(offset,end),"total",rows.size(),"continuation",end<rows.size()?Optional.of(plan.identity().value().substring(7)+":"+section+":"+end):Optional.empty(),"semanticCoverage","NOT_EVALUATED");
    }
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
