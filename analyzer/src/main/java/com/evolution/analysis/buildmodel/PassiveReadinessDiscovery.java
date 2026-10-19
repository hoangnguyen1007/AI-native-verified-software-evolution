package com.evolution.analysis.buildmodel;

import com.evolution.analysis.acquisition.streaming.InventoryManifest;
import com.evolution.analysis.readiness.ReadinessDiscovery;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import com.evolution.analysis.readiness.ReadinessPlan.Module;
import com.evolution.analysis.readiness.ReadinessPlanner;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import static com.evolution.analysis.readiness.ReadinessPlanner.*;

public final class PassiveReadinessDiscovery implements ReadinessDiscovery {
    @Override public Projection discover(Map<String,Descriptor> descriptors,InventoryManifest inventory){
        var roots=new TreeMap<String,Root>();var modules=new TreeMap<String,Module>();var contexts=new TreeMap<String,Context>();
        var requirements=new ArrayList<Requirement>();var unknown=new TreeSet<String>();
        var scripts=new TreeMap<String,List<DeclarativeScript.Statement>>();
        for(var descriptor:descriptors.values()){
            String path=descriptor.path(),name=filename(path);Family family=family(name);
            if(family==null)continue;
            String dir=directory(path),id=family+":"+dir;
            var evidence=List.of(new Evidence(path,descriptor.content().digest()));
            var prior=roots.get(id);var combined=new ArrayList<Evidence>(prior==null?List.of():prior.evidence());combined.addAll(evidence);
            roots.put(id,new Root(id,dir,family,combined,List.of()));
            if(family==Family.GRADLE)try{scripts.put(path,DeclarativeScript.parse(decode(descriptor),200_000,64));}
            catch(IllegalArgumentException failure){requirements.add(requirement(Kind.DISCOVERY,path,"DYNAMIC_BUILD_DISCOVERY_REQUIRED",List.of(),evidence));}
        }
        var membership=new TreeMap<String,String>();var related=new TreeMap<String,Set<String>>();
        var bazelMembers=new TreeMap<String,Set<String>>();
        var bazelWorkspaces=descriptors.keySet().stream().filter(path->Set.of("WORKSPACE","WORKSPACE.bazel","MODULE.bazel").contains(filename(path))).map(ReadinessPlanner::directory).distinct().toList();
        for(var root:List.copyOf(roots.values()))if(root.family()==Family.BAZEL&&!bazelWorkspaces.contains(root.directory())){
            var owner=bazelWorkspaces.stream().filter(directory->within(root.directory(),directory)).max(Comparator.comparingInt(String::length));
            if(owner.isPresent()){roots.remove(root.id());bazelMembers.computeIfAbsent("BAZEL:"+owner.orElseThrow(),key->new TreeSet<>()).add(root.directory());}
        }
        for(var entry:scripts.entrySet())if(filename(entry.getKey()).startsWith("settings.")){
            String dir=directory(entry.getKey()),root="GRADLE:"+dir;
            for(var statement:entry.getValue()){
                if((statement.key().equals("include")||statement.key().equals("includeBuild"))&&statement.body().isEmpty()
                        &&statement.syntax().matches("(?:include|includeBuild)(?:\\(#(?:,#)*\\)|#(?:,#)*)")){
                    for(String literal:statement.strings())try{
                        String relative=statement.key().equals("include")?literal.replaceFirst("^:","").replace(':','/'):literal;
                        String moduleDir=resolve(dir,relative);
                        if(statement.key().equals("include"))membership.put(moduleDir,root);
                        else related.computeIfAbsent(root,key->new TreeSet<>()).add("GRADLE:"+moduleDir);
                    }catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,entry.getKey(),"BUILD_PATH_UNRESOLVED",List.of(),roots.get(root).evidence()));}
                }else unknown.add(root+":settings:"+statement.offset());
            }
        }
        for(var root:List.copyOf(roots.values())){
            if(root.family()==Family.GRADLE){
                String owner=membership.get(root.directory());
                if(owner!=null&&!owner.equals(root.id()))roots.remove(root.id());
            }
        }
        for(var entry:related.entrySet())for(String id:entry.getValue())if(!roots.containsKey(id)){
            String dir=id.substring("GRADLE:".length());roots.put(id,new Root(id,dir,Family.GRADLE,List.of(),List.of()));
            requirements.add(requirement(Kind.DISCOVERY,id,"INCLUDED_BUILD_MODEL_REQUIRED",List.of(),List.of()));
        }
        for(var root:List.copyOf(roots.values())){
            String dir=root.directory();
            roots.put(root.id(),new Root(root.id(),dir,root.family(),root.evidence(),List.copyOf(related.getOrDefault(root.id(),Set.of()))));
            var directories=new TreeSet<String>();directories.add(dir);
            directories.addAll(bazelMembers.getOrDefault(root.id(),Set.of()));
            membership.forEach((module,owner)->{if(owner.equals(root.id()))directories.add(module);});
            for(String moduleDir:directories){
                String id=root.family()+":"+moduleDir;
                var evidence=descriptors.values().stream().filter(descriptor->directory(descriptor.path()).equals(moduleDir)&&family(filename(descriptor.path()))==root.family())
                        .map(descriptor->new Evidence(descriptor.path(),descriptor.content().digest())).toList();
                var dependencies=new TreeSet<String>();
                var sets=new TreeMap<String,List<String>>();
                if(Set.of(Family.GRADLE,Family.SBT).contains(root.family())){
                    sets.put("main",List.of(resolve(moduleDir,"src/main")));sets.put("test",List.of(resolve(moduleDir,"src/test")));
                }else sets.put("unclassified",List.of(moduleDir));
                if(root.family()==Family.GRADLE){
                    unknown.add(root.id()+":variants-and-substitution");
                    for(var script:scripts.entrySet())if(directory(script.getKey()).equals(moduleDir)&&filename(script.getKey()).startsWith("build.")){
                        scanGradle(script.getValue(),moduleDir,root,sets,dependencies,requirements,unknown,evidence);
                    }
                }
                modules.put(id,new Module(id,root.id(),moduleDir,moduleDir,List.of(),List.copyOf(dependencies),evidence));
                for(var set:sets.entrySet()){
                    var context=ReadinessPlanner.context(id,root.id(),set.getKey(),"baseline",set.getValue(),Map.of("nativeModel","REQUIRED","toolchain","UNSPECIFIED"),evidence,inventory,true);
                    contexts.put(context.id(),context);
                }
                if(root.family()!=Family.GRADLE)unknown.add(id+":native-targets-source-sets-and-platform");
            }
        }
        return new Projection(List.copyOf(roots.values()),List.copyOf(modules.values()),List.copyOf(contexts.values()),requirements,List.copyOf(unknown));
    }
    private static void scanGradle(List<DeclarativeScript.Statement> statements,String dir,Root root,Map<String,List<String>> sets,Set<String> dependencies,
            List<Requirement> requirements,Set<String> unknown,List<Evidence> evidence){
        for(var statement:statements){
            if(statement.key().equals("sourceSets"))for(var set:statement.body()){
                if(!set.key().matches("[A-Za-z][A-Za-z0-9_]*")){unknown.add(root.id()+":dynamic-source-sets");continue;}
                var paths=new ArrayList<String>();boolean replaces=false;
                for(var language:set.body())for(var source:language.body())if(Set.of("srcDir","srcDirs","setSrcDirs").contains(source.key())){
                    replaces|=source.key().equals("setSrcDirs")||source.syntax().startsWith("srcDirs=");
                    for(String path:source.strings())try{paths.add(resolve(dir,path));}
                    catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,dir,"BUILD_PATH_UNRESOLVED",List.of(),evidence));}
                }
                if(!replaces)paths.addFirst(resolve(dir,"src/"+set.key()));
                sets.put(set.key(),List.copyOf(paths));
            }
            if(statement.key().equals("dependencies"))for(var dependency:statement.body())if(dependency.syntax().contains("project(#)"))for(String project:dependency.strings())try{
                dependencies.add("GRADLE:"+resolve(root.directory(),project.replaceFirst("^:","").replace(':','/')));
            }catch(IllegalArgumentException unsafe){unknown.add(root.id()+":dynamic-project-dependency");}
            if(statement.key().equals("plugins"))for(var plugin:statement.body())for(String name:plugin.strings()){
                String reason=name.startsWith("com.android.")?"ANDROID_SDK_REQUIRED":name.contains("javafx")?"JAVAFX_PLATFORM_REQUIRED":name.contains("protobuf")?"PROTOBUF_GENERATOR_REQUIRED":name.contains("aspectj")?"WOVEN_BYTECODE_REQUIRED":null;
                if(reason!=null)requirements.add(requirement(reason.endsWith("GENERATOR_REQUIRED")?Kind.GENERATION:Kind.PLATFORM,root.id(),reason,List.of(),evidence));
            }
            if(statement.key().equals("android")){
                unknown.add(root.id()+":android-variant-feasibility");
                requirements.add(requirement(Kind.DISCOVERY,root.id(),"ANDROID_VARIANT_MODEL_REQUIRED",List.of(),evidence));
            }
        }
    }
    private static String decode(Descriptor descriptor){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(descriptor.bytes())).toString();}catch(CharacterCodingException malformed){throw new IllegalArgumentException("Descriptor decoding required");}}
    public static String resolve(String directory,String relative){
        if(relative.isBlank()||relative.startsWith("/")||relative.contains("\\")||relative.contains(":")||relative.contains("$")||relative.indexOf('\0')>=0)throw new IllegalArgumentException("Unsafe build path");
        var parts=new ArrayList<String>();if(!directory.equals("."))parts.addAll(List.of(directory.split("/")));
        for(String part:relative.split("/",-1)){if(part.equals(".")||part.isEmpty())continue;if(part.equals("..")){if(parts.isEmpty())throw new IllegalArgumentException("Outside snapshot");parts.removeLast();}else parts.add(part);}
        return parts.isEmpty()?".":String.join("/",parts);
    }
    private static Family family(String name){
        if(Set.of("build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts").contains(name))return Family.GRADLE;
        if(Set.of(".classpath",".project").contains(name)||name.endsWith(".iml"))return Family.IDE;
        return switch(name){case "build.xml"->Family.ANT;case "WORKSPACE","WORKSPACE.bazel","MODULE.bazel","BUILD","BUILD.bazel"->Family.BAZEL;case "build.sbt"->Family.SBT;
            case "package.json"->Family.NODE;case "pyproject.toml","setup.py"->Family.PYTHON;case "Cargo.toml"->Family.CARGO;case "go.mod"->Family.GO;
            case "composer.json"->Family.COMPOSER;case "Gemfile"->Family.RUBY;case "pubspec.yaml"->Family.DART;case "Package.swift"->Family.SWIFT;
            case "CMakeLists.txt"->Family.CMAKE;case "Makefile"->Family.MAKE;default->name.endsWith(".csproj")||name.endsWith(".sln")?Family.DOTNET:null;};
    }
}
