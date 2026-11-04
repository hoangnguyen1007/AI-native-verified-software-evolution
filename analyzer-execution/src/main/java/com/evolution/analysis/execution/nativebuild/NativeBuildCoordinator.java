package com.evolution.analysis.execution.nativebuild;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.execution.*;
import com.evolution.analysis.execution.qualification.*;
import com.evolution.analysis.execution.controller.ControllerTerminationLedger;
import com.evolution.analysis.execution.hyperv.HyperVWorkerControl;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Selected roots -> native recipes -> qualified fresh Hyper-V workers -> opaque captured bytes.
 * Build completion does not satisfy model, generated-member or semantic-validation obligations. */
public final class NativeBuildCoordinator {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("execution.hyperv-native-build","m8.4-v1");
    static final List<String> RESOURCES=List.of("nativebuild/controller.py","nativebuild/guest_runner.py","nativebuild/gradle-init.gradle",
            "guest/bootstrap.py","guest/runner.py","guest/export.py","guest/make_worker_seed.py",
            "hyperv/worker.ps1","hyperv/provision.ps1","hyperv/supervisor.py","hyperv/host_storage.py","mvp/capture.py");
    public record RootResult(String recipe,String root,List<String> contexts,String status,String reason,
            boolean isolatedBuildExecuted,String launchDisposition,Optional<ContentHandle> receipt){public RootResult{contexts=List.copyOf(contexts);Objects.requireNonNull(receipt);}}
    public record Result(String schema,String status,ContentDigest readiness,long roots,long requested,long executed,long succeeded,
            List<RootResult> results,Optional<ContentHandle> publication,String semanticCoverage){
        public Result{results=List.copyOf(results);Objects.requireNonNull(publication);if(!"NOT_EVALUATED".equals(semanticCoverage))throw new IllegalArgumentException();}
    }
    private final NativeBuildProfile profile;private final NativeBuildRegistrations.Selected registration;
    private final Path directory;private final StreamingContentStore content;private final Map<String,byte[]> bundle;
    private final SandboxQualificationGate gate;private final Optional<SandboxQualificationGate.QualifiedProfile> qualification;
    private final SandboxQualificationGate.Profile descriptor;

    public NativeBuildCoordinator(NativeBuildProfile profile,NativeBuildRegistrations.Selected registration)throws IOException{
        this.profile=Objects.requireNonNull(profile);this.registration=Objects.requireNonNull(registration);NativeBuildRegistrations.unchanged(registration);
        if(!CanonicalJson.write(profile).equals(CanonicalJson.write(registration.document())))throw new IOException("NATIVE_REGISTRATION_PROFILE_MISMATCH");
        directory=NativeBuildFiles.safe(profile.stateDirectory());if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("REGISTERED_PRIVATE_STATE_REQUIRED");
        content=new StreamingContentStore(directory.resolve("content"));bundle=new TreeMap<>();for(String name:RESOURCES)bundle.put(name,NativeBuildFiles.resource(name));
        var hashes=new TreeMap<String,ContentDigest>();bundle.forEach((name,bytes)->hashes.put(name,ContentDigest.sha256(bytes)));
        for(Class<?> type:List.of(NativeBuildCoordinator.class,NativeBuildProfile.class,NativeBuildFiles.class,NativeBuildRegistrations.class,NativeBuildRecipes.class,HyperVNativeBuildExecutor.class,SandboxQualificationGate.class,QualifiedSandboxExecutor.class,HyperVWorkerControl.class))hashes.put(type.getName(),ContentDigest.sha256(NativeBuildFiles.resource(type.getName().replace('.','/')+".class")));
        try{Path code=Path.of(NativeBuildCoordinator.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if(Files.isRegularFile(code,LinkOption.NOFOLLOW_LINKS))hashes.put("java.archive",NativeBuildFiles.hash(code).digest());
            else try(var classes=Files.walk(code.resolve("com/evolution/analysis/execution"))){for(var path:classes.filter(p->p.toString().endsWith(".class")).sorted().toList())hashes.put("java.class:"+code.relativize(path).toString().replace('\\','/'),NativeBuildFiles.hash(path).digest());}
        }catch(java.net.URISyntaxException invalid){throw new IOException("NATIVE_CONTROLLER_IDENTITY_UNAVAILABLE",invalid);}
        var driver=content.put(new ByteArrayInputStream(CanonicalJson.write(Map.of("provider",PROVIDER,"files",hashes)).getBytes(StandardCharsets.UTF_8)),()->false);
        descriptor=new SandboxQualificationGate.Profile(PROVIDER,driver,profile.disk().content(),Optional.empty(),"linux",toolArch(),
                CanonicalJson.sha256(Map.of("controls",hashes,"images",profile.images(),"tools",profile.tools(),"materials",profile.materials())),CanonicalJson.sha256(List.of(profile.python(),profile.powershell(),profile.pythonModules(),profile.pythonModuleManifest(),profile.storageReceipt(),profile.workspace(),profile.stateDirectory(),profile.storageOwner(),profile.validation().revocations())),
                CanonicalJson.sha256(profile.resources()),CanonicalJson.sha256(Map.of("network",profile.network(),"offline",true)),Optional.empty());
        var keys=new TreeMap<String,PublicKey>();
        try{for(var entry:profile.validation().publicKeys().entrySet())keys.put(entry.getKey(),KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(entry.getValue()))));}
        catch(GeneralSecurityException|IllegalArgumentException invalid){throw new IOException("NATIVE_QUALIFICATION_KEY_INVALID",invalid);}
        var pins=new RepositoryContentStore(){
            public ContentHandle put(InputStream input,BooleanSupplier cancelled)throws IOException{return content.put(input,cancelled);}
            public InputStream open(ContentHandle handle)throws IOException{if(handle.equals(profile.disk().content()))return Files.newInputStream(NativeBuildFiles.safe(profile.disk().path()),LinkOption.NOFOLLOW_LINKS);return content.open(handle);}
            public InputStream range(ContentHandle handle,long offset,long length)throws IOException{return content.range(handle,offset,length);}
            public boolean verify(ContentHandle handle)throws IOException{if(handle.equals(profile.disk().content()))return NativeBuildFiles.hash(NativeBuildFiles.safe(profile.disk().path())).equals(handle);return content.verify(handle);}
        };
        gate=new SandboxQualificationGate(SandboxQualificationGate.hyperVSuite(),profile.validation().controller(),keys,pins,Clock.systemUTC(),Duration.ofSeconds(profile.validation().maxAgeSeconds()),
                ()->revocations("certificates"),()->revokedKeys());
        qualification=profile.validation().certificate().isEmpty()?Optional.empty():gate.evaluate(descriptor,profile.validation().certificate().orElseThrow(),profile.validation().signature().orElseThrow()).profile();
    }
    private String toolArch(){var values=profile.tools().values().stream().map(t->t.tool().arch()).distinct().toList();if(values.size()!=1)throw new IllegalArgumentException("NATIVE_PLATFORM_SCOPE_REQUIRED");return values.getFirst();}
    private Set<ContentDigest> revocations(String field){return revokeRows(field).stream().map(ContentDigest::new).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    private Set<String> revokedKeys(){return Set.copyOf(revokeRows("keys"));}
    private List<String> revokeRows(String field){try{var row=NativeBuildFiles.read(NativeBuildFiles.safe(profile.validation().revocations()),1048576);if(!row.keySet().equals(Set.of("schema","certificates","keys"))||!"native-build-revocations:m8.4-v1".equals(row.get("schema")))throw new IOException();return GraphJson.array(row.get(field)).stream().map(GraphJson::string).toList();}catch(IOException|RuntimeException unavailable){throw new IllegalStateException("NATIVE_REVOCATIONS_UNAVAILABLE");}}
    public Object describe(){boolean ready=qualified();return Map.of("schema","native-build-qualification-input:m8.4-v1","profile",descriptor,"profileUnderTest",descriptor.underTest(),"validityPolicy",gate.policy(),"suite",SandboxQualificationGate.hyperVSuite(),"availability",ready?"AVAILABLE":"UNQUALIFIED","status",ready?"READY":"UNQUALIFIED");}
    private boolean qualified(){return qualification.isPresent()&&gate.admissible(qualification.orElseThrow());}
    public static Result unavailable(ReadinessPlan plan,String reason){return result(plan,plan.recipes().stream().filter(r->Set.of(ReadinessPlan.Family.MAVEN,ReadinessPlan.Family.GRADLE).contains(r.family())).map(r->new RootResult(r.id(),r.root(),r.contexts(),"WAITING_FOR_EXECUTOR",reason,false,"NOT_REQUESTED",Optional.empty())).toList(),Optional.empty());}
    private static Result result(ReadinessPlan plan,List<RootResult> rows,Optional<ContentHandle> publication){long requested=rows.stream().filter(r->!r.launchDisposition().equals("NOT_REQUESTED")).count(),executed=rows.stream().filter(RootResult::isolatedBuildExecuted).count(),success=rows.stream().filter(r->r.status().equals("BUILT")).count();String status=rows.isEmpty()?"NOT_APPLICABLE":success==rows.size()?"BUILT":success>0||rows.stream().anyMatch(r->r.status().equals("WAITING_FOR_CAPTURE"))?"PARTIAL":requested>0?"FAILED":"WAITING_FOR_EXECUTOR";return new Result("repository-native-build:m8.4-v1",status,plan.identity(),rows.size(),requested,executed,success,rows,publication,"NOT_EVALUATED");}
    public Result run(ReadinessPlan plan,String revision,InventoryReader inventory,RepositoryContentStore sources,Path sourceDirectory,BooleanSupplier cancelled)throws IOException{
        NativeBuildRegistrations.unchanged(registration);if(!qualified())return unavailable(plan,"CURRENT_SANDBOX_QUALIFICATION_REQUIRED");
        NativeBuildFiles.require(profile.python());NativeBuildFiles.require(profile.powershell());NativeBuildFiles.require(profile.pythonModuleManifest());NativeBuildFiles.require(profile.storageReceipt());
        var control=directory.resolve("controller-tools");extract(control);prepareState(control,cancelled);
        Path lock=directory.resolve("native-build.lock");
        try(var channel=java.nio.channels.FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var held=channel.tryLock()){
            if(held==null)return unavailable(plan,"NATIVE_WORKER_BUSY");
            // A lost process releases its file lock. Reconcile durable old launch intents
            // before another worker can claim the same registered address/resources.
            try(var paths=Files.list(directory)){var iterator=paths.filter(p->p.getFileName().toString().matches("worker-[a-f0-9]{32}")).iterator();while(iterator.hasNext()){
                var old=iterator.next();if(Files.exists(old.resolve("intent.json"),LinkOption.NOFOLLOW_LINKS)){var attempt=UUID.fromString(old.getFileName().toString().substring(7).replaceFirst("^(.{8})(.{4})(.{4})(.{4})(.{12})$","$1-$2-$3-$4-$5"));if(!hasQuiescence(attempt))recover(attempt);}
            }}
            if(!inventory.manifest(revision).filter(plan.inventory()::equals).isPresent())throw new IOException("NATIVE_INPUT_INVENTORY_MISMATCH");
            var sourceTable=captureInputs(plan,revision,inventory,sources,sourceDirectory,cancelled);var results=new ArrayList<RootResult>();
            for(var recipe:plan.recipes()){
                check(cancelled);if(!Set.of(ReadinessPlan.Family.MAVEN,ReadinessPlan.Family.GRADLE).contains(recipe.family()))continue;
                String role=recipe.family().name().toLowerCase(Locale.ROOT);var tool=profile.tools().get(role);
                if(tool==null){results.add(new RootResult(recipe.id(),recipe.root(),recipe.contexts(),"WAITING_FOR_TOOLCHAIN","REGISTERED_NATIVE_TOOL_REQUIRED",false,"NOT_REQUESTED",Optional.empty()));continue;}
                var lowered=NativeBuildRecipes.automatic(plan,recipe,tool.tool(),sourceTable.digest(),descriptor.network(),descriptor.resources(),profile.taskMappings());
                if(lowered.recipe().isEmpty()){results.add(new RootResult(recipe.id(),recipe.root(),recipe.contexts(),"WAITING_FOR_MAPPING",String.join("_",lowered.pendingReasons()),false,"NOT_REQUESTED",Optional.empty()));continue;}
                var nativeRecipe=lowered.recipe().orElseThrow();var attempt=UUID.randomUUID();Path root=directory.resolve("worker-"+attempt.toString().replace("-",""));Files.createDirectory(root);extract(root);
                var launch=new SandboxExecutor.Launch(plan.identity(),CanonicalJson.sha256(List.of(plan.identity(),recipe.id())),nativeRecipe.identity(),qualification.orElseThrow().descriptor().identity(),sourceTable,gate.policy(),attempt.toString(),1);
                var contexts=plan.contexts().stream().filter(c->recipe.contexts().contains(c.id())).map(c->{var module=plan.modules().stream().filter(m->m.id().equals(c.module())).findFirst().orElseThrow();return Map.of("id",c.id(),"moduleDirectory",module.directory(),"sourceSet",c.sourceSet(),"variant",c.variant());}).toList();
                NativeBuildFiles.write(root.resolve("request.json"),Map.of("schema","native-build-job:m8.4-v1","launch",launch,"recipe",nativeRecipe,"contexts",contexts,"profile",profile,"sourceTable",sourceTable,"contentDirectory",directory.resolve("content").toString()));
                NativeBuildFiles.write(root.resolve("intent.json"),Map.of("launch",CanonicalJson.sha256(launch),"request",NativeBuildFiles.hash(root.resolve("request.json"))));
                var driver=new HyperVNativeBuildExecutor(profile,registration,root,launch,gate,qualification.orElseThrow(),content,cancelled);
                var executor=new QualifiedSandboxExecutor(driver,gate,qualification);
                SandboxExecutor.Handle handle;
                try{handle=executor.launch(launch);var status=executor.collect(handle);if(status.export().isEmpty())throw new IOException("NATIVE_EXPORT_UNAVAILABLE");var receipt=status.export().orElseThrow();var nativeResult=NativeBuildFiles.read(root.resolve("native-result.json"),1048576);boolean success=((java.math.BigDecimal)nativeResult.get("exitCode")).longValueExact()==0;
                    results.add(new RootResult(recipe.id(),recipe.root(),recipe.contexts(),success?"BUILT":"FAILED",success?"":"NATIVE_BUILD_EXIT_NONZERO",true,"PROTECTED_GUEST_LAUNCHER_RETURNED",Optional.of(receipt)));
                }catch(IOException failure){String reason=failure.getMessage();results.add(new RootResult(recipe.id(),recipe.root(),recipe.contexts(),"NATIVE_OUTPUT_CAPTURE_PENDING".equals(reason)?"WAITING_FOR_CAPTURE":"FAILED",reason!=null&&reason.matches("[A-Z][A-Z0-9_]{0,95}")?reason:"NATIVE_BUILD_UNAVAILABLE",driver.executed(),driver.executed()?"PROTECTED_GUEST_LAUNCHER_RETURNED":driver.requested()?"UNKNOWN":"NOT_REQUESTED",Optional.empty()));if(!driver.quiescent())throw new IOException("NATIVE_TERMINATION_UNCONFIRMED",failure);}
            }
            var report=result(plan,results,Optional.empty());var publication=content.write(output->{var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));CanonicalJson.writeTo(report,writer);writer.flush();},cancelled);
            return result(plan,results,Optional.of(publication));
        }catch(java.nio.channels.OverlappingFileLockException busy){return unavailable(plan,"NATIVE_WORKER_BUSY");}
    }
    private ContentHandle captureInputs(ReadinessPlan plan,String revision,InventoryReader inventory,RepositoryContentStore sources,Path sourceDirectory,BooleanSupplier cancelled)throws IOException{
        return content.write(output->{var writer=new BufferedWriter(new OutputStreamWriter(output,StandardCharsets.UTF_8));Optional<String> cursor=Optional.empty();long entries=0,bytes=0;
            do{var page=inventory.page(revision,500,cursor);for(var entry:page.entries()){
                check(cancelled);entries++;String path=entry.path().utf8().orElseThrow(()->new IOException("NATIVE_LOGICAL_PATH_ADAPTER_REQUIRED"));
                if(entry.disposition()==InventoryEntry.Disposition.EXCLUDED)continue;
                if(entry.disposition()!=InventoryEntry.Disposition.ACQUIRED||!Set.of(InventoryEntry.Kind.FILE,InventoryEntry.Kind.DIRECTORY,InventoryEntry.Kind.SYMBOLIC_LINK).contains(entry.kind()))throw new IOException("NATIVE_INPUT_CLOSURE_PENDING");
                if(entry.content().isPresent()){var handle=entry.content().orElseThrow();bytes=Math.addExact(bytes,handle.size());if(bytes>profile.resources().inputBytes())throw new IOException("NATIVE_INPUT_RESOURCE_CLASS_REQUIRED");try(var input=sources.open(handle)){if(!content.put(input,cancelled).equals(handle))throw new IOException("NATIVE_INPUT_BYTES_CHANGED");}}
                var target=entry.target();
                if(entry.kind()==InventoryEntry.Kind.SYMBOLIC_LINK){
                    Path base=sourceDirectory.toAbsolutePath().normalize(),parent=base.resolve(path).getParent();Path resolved=parent.resolve(entry.target().orElseThrow()).normalize();
                    if(!resolved.startsWith(base))throw new IOException("NATIVE_SOURCE_LINK_ESCAPE");target=Optional.of(parent.relativize(resolved).toString().replace('\\','/'));
                }
                CanonicalJson.writeTo(Map.of("path",path,"kind",entry.kind(),"mode",entry.mode(),"content",entry.content(),"target",target,"capturedTarget",entry.target()),writer);writer.write('\n');
            }cursor=page.continuation();}while(cursor.isPresent());if(entries!=plan.inventory().entries())throw new IOException("NATIVE_INPUT_DENOMINATOR_MISMATCH");writer.flush();},cancelled);
    }
    private void extract(Path root)throws IOException{for(var entry:bundle.entrySet()){Path path=root.resolve("tools").resolve(entry.getKey());Files.createDirectories(path.getParent());if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){if(!NativeBuildFiles.hash(path).digest().equals(ContentDigest.sha256(entry.getValue())))throw new IOException("NATIVE_CONTROLLER_BYTES_CHANGED");}else Files.write(path,entry.getValue(),StandardOpenOption.CREATE_NEW);}}
    private void prepareState(Path control,BooleanSupplier cancelled)throws IOException{
        Path request=control.resolve("profile-"+CanonicalJson.sha256(profile).value().substring(7)+".json");if(!Files.exists(request))NativeBuildFiles.write(request,profile);
        var builder=new ProcessBuilder(profile.python().path(),"-I",control.resolve("tools/nativebuild/controller.py").toString(),"--prepare-state",request.toString()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);NativeBuildFiles.controllerEnvironment(builder);var process=builder.start();
        long end=System.nanoTime()+Math.multiplyExact(profile.resources().controlMillis(),1_000_000L);
        try{while(!process.waitFor(100,java.util.concurrent.TimeUnit.MILLISECONDS)){check(cancelled);if(System.nanoTime()>end)throw new IOException("NATIVE_PRIVATE_STATE_TIMEOUT");}if(process.exitValue()!=0)throw new IOException("NATIVE_PRIVATE_STATE_UNAVAILABLE");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("NATIVE_BUILD_CANCELLED",interrupted);}finally{if(process.isAlive())process.destroyForcibly();}
    }
    public Object recover(UUID attempt)throws IOException{
        NativeBuildRegistrations.unchanged(registration);NativeBuildFiles.require(profile.powershell());
        Path root=directory.resolve("worker-"+attempt.toString().replace("-",""));var launch=readLaunch(attempt);
        Path script=root.resolve("tools/hyperv/worker.ps1");ContentDigest driverPin=ContentDigest.sha256(NativeBuildFiles.resource("hyperv/worker.ps1"));
        try(var ledger=new ControllerTerminationLedger(root.resolve("terminations"),content,PROVIDER)){
            var driver=new HyperVWorkerControl(Path.of(profile.powershell().path()),script,driverPin,root,launch,content,ledger);var observed=driver.recover(launch);Optional<ContentDigest> proof=observed.termination();
            if(observed.handle().isPresent()){var stopped=driver.terminate(observed.handle().orElseThrow());if(stopped.running())throw new IOException("NATIVE_TERMINATION_UNCONFIRMED");proof=stopped.termination();}
            if(proof.isEmpty())throw new IOException("NATIVE_TERMINATION_UNCONFIRMED");
            var data=Map.of("schema","native-build-quiescence:m8.4-v1","launch",CanonicalJson.sha256(launch),"termination",proof.orElseThrow());
            if(!Files.exists(root.resolve("quiescent.json")))NativeBuildFiles.write(root.resolve("quiescent.json"),data);
            if(!Files.exists(root.resolve("controller-protocol-complete.json")))NativeBuildFiles.write(root.resolve("controller-protocol-complete.json"),Map.of("attempt",launch.attempt(),"fence",launch.fence(),"launch",CanonicalJson.sha256(launch)));
            return Map.of("attempt",attempt.toString(),"state","OFF_OR_ABSENT","termination",proof.orElseThrow(),"targetRetried",false);
        }
    }
    private SandboxExecutor.Launch readLaunch(UUID attempt)throws IOException{
        Path root=directory.resolve("worker-"+attempt.toString().replace("-",""));var request=NativeBuildFiles.read(root.resolve("request.json"),1048576);var value=GraphJson.object(request.get("launch"));
        if(!value.keySet().equals(Set.of("plan","task","recipe","profile","immutableInputs","policy","attempt","fence"))||!attempt.toString().equals(value.get("attempt")))throw new IOException("NATIVE_RECOVERY_SCOPE_MISMATCH");
        return new SandboxExecutor.Launch(new ContentDigest(GraphJson.string(value.get("plan"))),new ContentDigest(GraphJson.string(value.get("task"))),new ContentDigest(GraphJson.string(value.get("recipe"))),
                new ContentDigest(GraphJson.string(value.get("profile"))),NativeBuildFiles.handle(value.get("immutableInputs")),new ContentDigest(GraphJson.string(value.get("policy"))),attempt.toString(),((java.math.BigDecimal)value.get("fence")).longValueExact());
    }
    private boolean hasQuiescence(UUID attempt)throws IOException{
        Path root=directory.resolve("worker-"+attempt.toString().replace("-",""));if(!Files.exists(root.resolve("quiescent.json"),LinkOption.NOFOLLOW_LINKS))return false;var launch=readLaunch(attempt);var row=NativeBuildFiles.read(root.resolve("quiescent.json"),16384);
        if(!row.keySet().equals(Set.of("schema","launch","termination"))||!"native-build-quiescence:m8.4-v1".equals(row.get("schema"))||!CanonicalJson.sha256(launch).value().equals(row.get("launch")))throw new IOException("NATIVE_QUIESCENCE_SCOPE_MISMATCH");
        try(var ledger=new ControllerTerminationLedger(root.resolve("terminations"),content,PROVIDER)){
            ledger.require(launch,new ContentDigest(GraphJson.string(row.get("termination"))));return true;
        }
    }
    static void check(BooleanSupplier cancelled){if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
}
