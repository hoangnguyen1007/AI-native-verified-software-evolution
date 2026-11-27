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
import java.util.function.Predicate;

/** Product composition for the durable acquisition journey; no compiler, SQL or target execution in this boundary. */
public final class RepositoryIntake implements AutoCloseable {
    private final Path root;
    private final StreamingContentStore content;
    private final SqliteInventoryStore inventory;
    private final boolean readOnly;
    public RepositoryIntake(Path root)throws IOException{
        this(root,false);
    }
    public RepositoryIntake(Path root,boolean readOnly)throws IOException{this.root=root.toAbsolutePath().normalize();this.readOnly=readOnly;content=new StreamingContentStore(this.root.resolve("content"));inventory=new SqliteInventoryStore(this.root.resolve("metadata"),content,readOnly);}
    public InventoryReader inventory(){return inventory;}
    public Optional<SqliteInventoryStore.SealedHead> latestSealed(RepositoryIdentity repository, ContentDigest selection) throws IOException {
        return inventory.latestSealed(repository, selection);
    }
    boolean hasUncapturedEntries(String revision) throws IOException { return inventory.hasUncapturedEntries(revision); }
    /** Product capture drains traversal in one invocation and retries only an unsettled
     * filesystem checkpoint. Missing LFS/content/resources and cancellation never spin. */
    public StreamingRepositoryAcquirer.Receipt localComplete(Path source,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException {
        StreamingRepositoryAcquirer.Receipt result=null;
        for(int pass=0;pass<3;pass++) {
            result=local(source,revision,request,cancelled);
            if(result.manifest().isPresent()||cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())return result;
            if(!result.reason().equals("INVENTORY_FRONTIER_OPEN")&&!result.reason().equals("SOURCE_DIRECTORY_CHANGED"))return result;
            boolean transientOnly=true,found=result.reason().equals("SOURCE_DIRECTORY_CHANGED");Optional<String> cursor=Optional.empty();
            do{var page=inventory.page(revision,256,cursor);for(var entry:page.entries())if(entry.disposition()==InventoryEntry.Disposition.GAP||entry.disposition()==InventoryEntry.Disposition.PENDING) {
                found=true;transientOnly&=entry.problem().filter(Set.of("DIRECTORY_CHANGED","SOURCE_CHANGED_OR_LINK_UNCLOSED","FILE_UNAVAILABLE_OR_CHANGED")::contains).isPresent();
            }cursor=page.continuation();}while(transientOnly&&cursor.isPresent());
            if(!found||!transientOnly)return result;
        }
        return result;
    }
    public StreamingRepositoryAcquirer.IncrementalReceipt localCompleteIncremental(
            Path source,
            String revision,
            Optional<String> baseRevision,
            StreamingAcquisitionRequest request,
            boolean verifyAll,
            BooleanSupplier cancelled) throws IOException {
        StreamingRepositoryAcquirer.IncrementalReceipt inc = localIncremental(source, revision, baseRevision, request, verifyAll, cancelled);
        var result = inc.receipt();
        if (result.manifest().isPresent() || cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            return inc;
        }
        if (!result.reason().equals("INVENTORY_FRONTIER_OPEN") && !result.reason().equals("SOURCE_DIRECTORY_CHANGED")) {
            return inc;
        }
        // Retry unsettled filesystem checkpoint using full localComplete path for safety.
        var retried = localComplete(source, revision, request, cancelled);
        return new StreamingRepositoryAcquirer.IncrementalReceipt(retried, false, "RETRY_AFTER_TRANSIENT_MUTATION", Optional.empty());
    }
    /** Immutable captured bytes for isolated build transport; never a mutable host source view. */
    public RepositoryContentStore content(){return content;}
    public JobJournal journal(){return inventory;}
    public ReadinessPlan readiness(String revision,ReadinessPlanner.Policy policy,BooleanSupplier cancelled)throws IOException{
        return readiness(revision,policy,cancelled,false);
    }
    ReadinessPlan readiness(String revision,ReadinessPlanner.Policy policy,BooleanSupplier cancelled,boolean fast)throws IOException{
        return readinessIncremental(revision, Optional.empty(), policy, cancelled, fast);
    }
    /**
     * Delta-aware readiness plan computation: when {@code delta} confirms zero added/deleted files
     * and only readiness-neutral modifications (e.g., D1 Java/Kotlin or D0 polyglot body edits),
     * rebinds the base revision's persisted {@link ReadinessPlan} deterministically with exact
     * {@link ReadinessPlan#identity()} parity instead of rescanning inventory and ownership.
     */
    public ReadinessPlan readinessIncremental(
            String revision,
            Optional<StreamingRepositoryAcquirer.FileDelta> delta,
            ReadinessPlanner.Policy policy,
            BooleanSupplier cancelled,
            boolean fast) throws IOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("READINESS_CANCELLED");
        var manifest=inventory.manifest(revision).orElseThrow(()->new IOException("SEALED_INVENTORY_REQUIRED"));
        var request=readinessRequestKey(manifest, policy, fast);
        if(Files.isRegularFile(root.resolve("readiness/index.sqlite"),LinkOption.NOFOLLOW_LINKS)){
            try(var reports=new ReadinessReports(root.resolve("readiness"),content,true)){
                var saved=reports.get(request,cancelled);if(saved.isPresent())return saved.orElseThrow();
            }catch(IOException unavailable){if(readOnly)throw unavailable;}
        }
        if(readOnly)throw new IOException("READINESS_PLAN_NOT_PUBLISHED");
        try(var reports=new ReadinessReports(root.resolve("readiness"),content)){
            var saved=reports.get(request,cancelled);if(saved.isPresent())return saved.orElseThrow();
            if(delta.isPresent() && !Boolean.getBoolean("evo.incremental.disabled")) {
                var d = delta.orElseThrow();
                var baseManifest = inventory.manifest(d.baseRevision());
                if(baseManifest.isPresent()) {
                    var baseRequest = readinessRequestKey(baseManifest.orElseThrow(), policy, fast);
                    var basePlan = reports.get(baseRequest, cancelled);
                    if(basePlan.isPresent() && ReadinessDelta.canFastRebind(
                            basePlan.orElseThrow(), manifest, d.addedPaths(), d.modifiedPaths(), d.deletedPaths())) {
                        var rebound = ReadinessDelta.rebindPlan(basePlan.orElseThrow(), manifest);
                        reports.save(request, rebound, cancelled);
                        return rebound;
                    }
                }
            }
            ReadinessPlan plan;
            try {
                plan=new ReadinessPlanner(List.of(new PassiveReadinessDiscovery(),new MavenReadinessDiscovery()))
                        .plan(revision,inventory,content,policy,cancelled,fast?installedNodeDependencies(revision):path->false);
            }catch(UncheckedIOException unavailable){throw unavailable.getCause();}
            reports.save(request,plan,cancelled);return plan;
        }
    }
    private static ContentDigest readinessRequestKey(InventoryManifest manifest, ReadinessPlanner.Policy policy, boolean fast) {
        return IngestionEvidence.digest(fast?List.of(ReadinessPlan.PROVIDER,manifest,policy,"installed-node-dependency-scope:v1"):
                List.of(ReadinessPlan.PROVIDER,manifest,policy));
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
    public Map<String,Object> publishedOverview(String revision)throws IOException{
        return publishedOverview(revision,Optional.empty());
    }
    public Map<String,Object> publishedOverview(String revision,Optional<AnalysisMode> mode)throws IOException{
        if(!readOnly)throw new IllegalStateException("Overview must use published readiness");
        boolean fast=mode.map(AnalysisMode::isFastArchitecture).orElse(false);
        ReadinessPlan plan;
        try {plan=readiness(revision,ReadinessPlanner.Policy.comprehensive(),()->Thread.currentThread().isInterrupted(),fast);}
        catch(IOException unavailable){
            if(!"READINESS_PLAN_NOT_PUBLISHED".equals(unavailable.getMessage()))throw unavailable;
            fast=!fast;
            plan=readiness(revision,ReadinessPlanner.Policy.comprehensive(),()->Thread.currentThread().isInterrupted(),fast);
        }
        int size=24;
        var installed=installedNodeDependencies(revision);
        var installedRoots=new HashSet<String>();
        var installedModules=new HashSet<String>();
        try {
            for(var root:plan.roots())if(installed.test(root.directory()+"/"))installedRoots.add(root.id());
            for(var module:plan.modules())if(installed.test(module.directory()+"/"))installedModules.add(module.id());
        } catch(UncheckedIOException unavailable) { throw unavailable.getCause(); }
        var roots=plan.roots().stream().filter(root->!installedRoots.contains(root.id()))
                .sorted(Comparator.comparingInt((ReadinessPlan.Root root)->pathDepth(root.directory()))
                .thenComparing(ReadinessPlan.Root::directory).thenComparing(ReadinessPlan.Root::id)).limit(size).map(root->Map.of("id",root.id(),"directory",root.directory(),
                "family",root.family(),"relatedRootCount",root.relatedRoots().size())).toList();
        var families=new HashMap<String,ReadinessPlan.Family>();
        plan.roots().forEach(root->families.put(root.id(),root.family()));
        var modules=plan.modules().stream().filter(module->!installedModules.contains(module.id()))
                .sorted(Comparator.comparingInt((ReadinessPlan.Module module)->pathDepth(module.directory()))
                .thenComparing(ReadinessPlan.Module::directory).thenComparing(ReadinessPlan.Module::id)).limit(size).map(module->Map.of("id",module.id(),"root",module.root(),
                "directory",module.directory(),"target",module.target(),"languages",module.languages(),
                "family",families.get(module.root()),"declaredDependencyCount",module.dependencies().size())).toList();
        var selection=plan.selection();
        var pendingReasons=new TreeMap<String,Long>();
        plan.requirements().stream().filter(requirement->requirement.state()==ReadinessPlan.State.PENDING)
                .forEach(requirement->pendingReasons.merge(requirement.reason(),1L,Long::sum));
        return Map.ofEntries(Map.entry("schema","repository.readiness-overview:v1"),Map.entry("plan",plan.identity()),
                Map.entry("inventory",plan.inventory().inventory()),Map.entry("profile",fast?AnalysisMode.FAST_ARCHITECTURE:AnalysisMode.FULL),
                Map.entry("roots",roots),Map.entry("rootCount",plan.roots().size()),Map.entry("modules",modules),Map.entry("moduleCount",plan.modules().size()),
                Map.entry("installedDependencyRoots",installedRoots.size()),Map.entry("installedDependencyModules",installedModules.size()),
                Map.entry("coverage",Map.of("discovered",selection.discovered().size(),"selected",selection.selected().size(),
                        "unknownDimensions",selection.unknown().size(),"dependencyInputs",plan.ownership().counts().getOrDefault("DEPENDENCY_INPUT",0L),
                        "pending",pendingReasons.values().stream().mapToLong(Long::longValue).sum())),
                Map.entry("pendingReasons",pendingReasons.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey())).limit(12)
                        .map(entry->Map.of("reason",entry.getKey(),"count",entry.getValue())).toList()),
                Map.entry("semanticCoverage","NOT_EVALUATED"));
    }
    private static int pathDepth(String path) {
        return path.equals(".") ? 0 : (int)path.chars().filter(character -> character=='/').count()+1;
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
    public StreamingRepositoryAcquirer.IncrementalReceipt localIncremental(
            Path source,
            String revision,
            Optional<String> baseRevision,
            StreamingAcquisitionRequest request,
            boolean verifyAll,
            BooleanSupplier cancelled) throws IOException {
        if(root.startsWith(source.toAbsolutePath().normalize())){
            String state=source.toAbsolutePath().normalize().relativize(root).toString().replace('\\','/');
            if(state.isEmpty()||!request.excludes(state))throw new IOException("STATE_DIRECTORY_MUST_BE_EXCLUDED");
        }
        return new StreamingRepositoryAcquirer(content,inventory)
                .acquireIncremental(source, revision, baseRevision, request, verifyAll, cancelled);
    }
    public StreamingRepositoryAcquirer.Receipt git(URI uri,String ref,String revision,StreamingAcquisitionRequest request,GitHostPolicy policy,String executable,BooleanSupplier cancelled)throws IOException{
        return new GitSnapshotAcquirer(content,inventory,policy,executable,60_000).acquire(root.resolve("git").resolve(ContentDigest.sha256Utf8(revision).value().substring(7)),revision,uri,ref,request,cancelled);
    }
    public StreamingRepositoryAcquirer.IncrementalReceipt gitIncremental(
            URI uri,
            String ref,
            String revision,
            Optional<String> baseRevision,
            StreamingAcquisitionRequest request,
            GitHostPolicy policy,
            String executable,
            BooleanSupplier cancelled) throws IOException {
        Path mirror = root.resolve("git-mirrors").resolve(ContentDigest.sha256Utf8(uri.toASCIIString()).value().substring(7));
        return new GitSnapshotAcquirer(content, inventory, policy, executable, 60_000)
                .acquireIncremental(mirror, revision, baseRevision, uri, ref, request, cancelled);
    }
    public StreamingRepositoryAcquirer.Receipt archive(Path archive,String revision,StreamingAcquisitionRequest request,BooleanSupplier cancelled)throws IOException{
        ContentHandle captured;try(var input=Files.newInputStream(LocalAnalysisRunner.safe(archive),LinkOption.NOFOLLOW_LINKS)){captured=content.put(input,cancelled);}
        return new ArchiveSnapshotAcquirer(content,inventory).acquire(captured,revision,request,cancelled);
    }
    public RepositoryAcquisitionResult compatibility(String revision,RepositoryIdentity repository,Optional<String> sourceRevision,boolean dirty,boolean includeTests,boolean allFiles,List<String> exclusions)throws IOException{
        RepositoryAcquisitionRequest request;try{request=new RepositoryAcquisitionRequest(repository,sourceRevision,dirty,"pom.xml",new RepositoryAcquisitionPolicy(500_000,500_000,10_000_000L,8_000_000_000L,256,exclusions));}catch(IllegalArgumentException unsupported){throw new IOException("V1_SELECTION_UNREPRESENTABLE");}
        var existsCache=new HashMap<String,Boolean>();
        var installed=installedNodeDependencies(revision,existsCache);
        try{return new StreamingRepositoryAcquirer(content,inventory).compatibility(revision,request,e->{
            if(e.kind()==InventoryEntry.Kind.DIRECTORY||!ScanSelection.semanticInput(e,includeTests,allFiles))return false;
            String path=e.path().utf8().orElse("");if(exclusions.stream().anyMatch(p->path.equals(p)||path.startsWith(p+"/")))return false;
            return allFiles||!installed.test(path)&&!declaredOutput(revision,path,existsCache);
        },com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("selector","java.compatibility:m8.4-v2","includeTests",includeTests,"allFiles",allFiles,"exclusions",exclusions)));}catch(UncheckedIOException unavailable){throw unavailable.getCause();}
    }
    private boolean declaredOutput(String revision,String path,Map<String,Boolean> existsCache){
        if(!path.contains("target/")&&!path.contains("build/")&&!path.contains(".gradle/"))return false;
        String[] parts=path.split("/");String parent="";
        for(int i=0;i<parts.length-1;i++){
            String segment=parts[i];String prefix=parent.isEmpty()?"":parent+"/";
            if(segment.equals("target")&&cachedExists(revision,prefix+"pom.xml",existsCache)
                    ||(segment.equals("build")||segment.equals(".gradle"))&&(cachedExists(revision,prefix+"build.gradle",existsCache)||cachedExists(revision,prefix+"build.gradle.kts",existsCache)))return true;
            parent=prefix+segment;
        }
        return false;
    }
    public Predicate<String> installedNodeDependencies(String revision){
        return installedNodeDependencies(revision,new HashMap<>());
    }
    private Predicate<String> installedNodeDependencies(String revision,Map<String,Boolean> existsCache){
        return path->{
            for(int start=0;(start=path.indexOf("node_modules/",start))>=0;start+="node_modules/".length()){
                if(start!=0&&path.charAt(start-1)!='/')continue;
                String owner=path.substring(0,start);
                if(cachedExists(revision,owner+"package.json",existsCache)
                        ||cachedExists(revision,owner+"pnpm-workspace.yaml",existsCache))return true;
            }
            return false;
        };
    }
    private boolean cachedExists(String revision,String path,Map<String,Boolean> existsCache){
        var cached=existsCache.get(path);
        if(cached!=null)return cached;
        try{boolean present=exists(revision,path);existsCache.put(path,present);return present;}
        catch(IOException unavailable){throw new UncheckedIOException(unavailable);}
    }
    private boolean exists(String revision,String path)throws IOException{return inventory.observation(revision,LogicalPath.utf8(path)).filter(o->o.entry().kind()==InventoryEntry.Kind.FILE&&o.entry().disposition()==InventoryEntry.Disposition.ACQUIRED).isPresent();}
    @Override public void close()throws IOException{inventory.close();}
}
