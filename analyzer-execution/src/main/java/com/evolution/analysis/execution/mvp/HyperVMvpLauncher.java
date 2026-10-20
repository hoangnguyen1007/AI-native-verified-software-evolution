package com.evolution.analysis.execution.mvp;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.filesystem.StreamingContentStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Fixed platform launcher for the human-approved offline Maven Core MVP recipe. */
public final class HyperVMvpLauncher implements CoreMvpBuildPipeline.Launcher {
    private static final List<String> TOOLS=List.of("guest/bootstrap.py","guest/runner.py","guest/export.py",
        "hyperv/worker.ps1","hyperv/provision.ps1","hyperv/supervisor.py","hyperv/host_storage.py",
        "mvp/launch.py","mvp/native_build.py","mvp/capture.py","mvp/fixture-files.txt");
    private final Path python,powershell,modules,workspace;private final ContentDigest pythonPin,powershellPin;
    public HyperVMvpLauncher(Map<String,Object> setup)throws IOException{
        python=Path.of(CoreMvpBuildPipeline.text(setup,"python")).toRealPath();powershell=Path.of(CoreMvpBuildPipeline.text(setup,"powershell")).toRealPath();
        modules=Path.of(CoreMvpBuildPipeline.text(setup,"pythonModules")).toRealPath();workspace=Path.of(CoreMvpBuildPipeline.text(setup,"workspace")).toRealPath();
        pythonPin=new ContentDigest(CoreMvpBuildPipeline.text(setup,"pythonPin"));powershellPin=new ContentDigest(CoreMvpBuildPipeline.text(setup,"powershellPin"));verifyExecutables();
    }
    private static ContentDigest hash(Path file)throws IOException{try(var in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){var digest=java.security.MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)if(n>0)digest.update(buffer,0,n);return new ContentDigest("sha256:"+HexFormat.of().formatHex(digest.digest()));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private void verifyExecutables()throws IOException{if(!hash(python).equals(pythonPin)||!hash(powershell).equals(powershellPin))throw new IOException("CORE_MVP_RUNTIME_PIN_MISMATCH");}
    private static byte[] resource(String name)throws IOException{
        try(var in=HyperVMvpLauncher.class.getResourceAsStream("/"+name)){if(in==null)throw new IOException("CORE_MVP_RESOURCE_MISSING");return in.readAllBytes();}
    }
    private static Map<String,byte[]> bundle()throws IOException{
        var files=new TreeMap<String,byte[]>();for(var name:TOOLS)files.put(name,resource(name));
        for(String name:new String(files.get("mvp/fixture-files.txt"),StandardCharsets.UTF_8).lines().toList()){
            if(!name.startsWith("mvp/native/maven-generated/")||name.contains("..")||name.contains("\\"))throw new IOException("CORE_MVP_FIXTURE_MANIFEST_INVALID");files.put(name,resource(name));
        }
        return files;
    }
    @Override public ContentDigest fingerprint()throws IOException{
        verifyExecutables();var pins=new TreeMap<String,ContentDigest>();bundle().forEach((name,bytes)->pins.put(name,ContentDigest.sha256(bytes)));
        pins.put("controller.class",ContentDigest.sha256(resource("com/evolution/analysis/execution/mvp/CoreMvpBuildPipeline.class")));
        pins.put("launcher.class",ContentDigest.sha256(resource("com/evolution/analysis/execution/mvp/HyperVMvpLauncher.class")));
        return CanonicalJson.sha256(Map.of("scope","CORE_MVP","tools",pins,"python",pythonPin,"powershell",powershellPin));
    }
    private void extract(Path root)throws IOException{
        for(var entry:bundle().entrySet()){
            Path path=root.resolve("tools").resolve(entry.getKey());Files.createDirectories(path.getParent());
            if(Files.exists(path)){if(!Arrays.equals(Files.readAllBytes(path),entry.getValue()))throw new IOException("CORE_MVP_TOOL_BYTES_CHANGED");}
            else Files.write(path,entry.getValue(),StandardOpenOption.CREATE_NEW);
        }
    }
    @Override public void launch(Path root)throws IOException{
        verifyExecutables();extract(root);
        var command=List.of(python.toString(),"-s",root.resolve("tools/mvp/launch.py").toString(),"--root",root.toString(),"--powershell",powershell.toString(),"--control-pid",Long.toString(ProcessHandle.current().pid()));
        var builder=new ProcessBuilder(command).directory(workspace.toFile()).redirectOutput(root.resolve("launcher.private.log").toFile()).redirectError(root.resolve("launcher-error.private.log").toFile());
        builder.environment().put("PYTHONPATH",modules.toString());builder.environment().put("PYTHONNOUSERSITE","1");builder.environment().remove("PYTHONINSPECT");builder.environment().remove("PYTHONSTARTUP");
        var process=builder.start();
        try{
            if(!process.waitFor(1200,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("CORE_MVP_LAUNCH_DEADLINE");}
            if(process.exitValue()!=0)throw new IOException("CORE_MVP_LAUNCH_FAILED");
        }catch(InterruptedException interrupted){process.destroyForcibly();try{observeOff(root);}catch(IOException cleanup){interrupted.addSuppressed(cleanup);}finally{Thread.currentThread().interrupt();}throw new IOException("CORE_MVP_INTERRUPTED",interrupted);}
        catch(IOException failed){try{observeOff(root);}catch(IOException cleanup){failed.addSuppressed(cleanup);}throw failed;}
    }
    @Override public Map<String,Object> observeOff(Path root)throws IOException{
        verifyExecutables();var request=CoreMvpBuildPipeline.read(root.resolve("provision-request.json"),65536);
        var result=root.resolve("off-observation-"+UUID.randomUUID()+".json");
        var process=new ProcessBuilder(powershell.toString(),"-NoProfile","-NonInteractive","-File",root.resolve("tools/hyperv/worker.ps1").toString(),
                "-Action","Stop","-Workspace",root.toString(),"-Attempt",CoreMvpBuildPipeline.text(request,"attempt"),"-LaunchDigest",CoreMvpBuildPipeline.text(request,"launch"),"-Fence",Long.toString(CoreMvpBuildPipeline.number(request.get("fence"))))
                .redirectOutput(result.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{if(!process.waitFor(45,TimeUnit.SECONDS)||process.exitValue()!=0)throw new IOException("CORE_MVP_OFF_OBSERVATION_FAILED");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("CORE_MVP_OFF_OBSERVATION_INTERRUPTED",interrupted);}
        finally{if(process.isAlive())process.destroyForcibly();}
        return CoreMvpBuildPipeline.read(result,8192);
    }
    public static Map<String,Object> run(Path root)throws IOException{
        var setup=CoreMvpBuildPipeline.read(root.resolve("setup.json"),65536);
        if(!"CORE_MVP".equals(setup.get("acceptanceScope"))||!"HUMAN".equals(setup.get("approvedBy")))throw new IOException("CORE_MVP_APPROVAL_REQUIRED");
        var launcher=new HyperVMvpLauncher(setup);var cas=new StreamingContentStore(root.resolve("publication-cas"));
        var document=new CoreMvpBuildPipeline(cas,launcher).run(root);
        return Map.of("schema","core-mvp-pipeline-receipt:m8.4-v1","scope","CORE_MVP","document",document,"status","CAPTURED_NATIVE_OUTPUTS","qualified",false);
    }
    public static void main(String[] args)throws IOException{
        if(args.length!=1)throw new IllegalArgumentException("REGISTERED_CORE_MVP_ROOT_REQUIRED");
        System.out.println(CanonicalJson.write(run(Path.of(args[0]))));
    }
}
