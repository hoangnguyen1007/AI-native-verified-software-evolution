package com.evolution.analysis.buildmodel;

import com.evolution.analysis.acquisition.streaming.InventoryManifest;
import com.evolution.analysis.readiness.ReadinessDiscovery;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import com.evolution.analysis.readiness.ReadinessPlan.Module;
import com.evolution.analysis.readiness.ReadinessPlanner;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.regex.*;
import static com.evolution.analysis.readiness.ReadinessPlanner.*;

public final class PassiveReadinessDiscovery implements ReadinessDiscovery {
    private static final Pattern SLN_PROJECT = Pattern.compile(
            "Project\\(\"\\{[^}]+\\}\"\\)\\s*=\\s*\"([^\"]+)\"\\s*,\\s*\"([^\"]+\\.(?:csproj|vbproj|fsproj))\"\\s*,\\s*\"\\{[^}]+\\}\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SLNX_PROJECT = Pattern.compile(
            "<Project\\b[^>]*\\bPath\\s*=\\s*\"([^\"]+\\.(?:csproj|vbproj|fsproj))\"",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> DOTNET_TEST_DIR_NAMES = Set.of(
            "test", "tests", "spec", "specs", "unit-tests", "unit-test",
            "integration-tests", "integration-test", "e2e-tests", "e2e-test");
    private static final Set<String> SINGLE_SERVICE_LAYOUT_SUBDIRS = Set.of(
            "src", "app", "main", "source", "lib", "core");
    private static final Set<String> PRIMARY_PYTHON_DESCRIPTORS = Set.of(
            "pyproject.toml", "setup.py", "setup.cfg", "Pipfile");

    @Override public Projection discover(Map<String,Descriptor> descriptors,InventoryManifest inventory){
        var roots=new TreeMap<String,Root>();var modules=new TreeMap<String,Module>();var contexts=new TreeMap<String,Context>();
        var requirements=new ArrayList<Requirement>();var unknown=new TreeSet<String>();
        var scripts=new TreeMap<String,List<DeclarativeScript.Statement>>();
        var pythonPrimaryDirs=new TreeSet<String>();
        var dotnetSolutions=new TreeMap<String,List<Descriptor>>();
        for(var descriptor:descriptors.values()){
            String path=descriptor.path(),name=filename(path);Family family=family(name);
            if(family==null)continue;
            String dir=directory(path),id=family+":"+dir;
            var evidence=List.of(new Evidence(path,descriptor.content().digest()));
            var prior=roots.get(id);var combined=new ArrayList<Evidence>(prior==null?List.of():prior.evidence());combined.addAll(evidence);
            roots.put(id,new Root(id,dir,family,combined,List.of()));
            if(family==Family.PYTHON&&PRIMARY_PYTHON_DESCRIPTORS.contains(name))pythonPrimaryDirs.add(dir);
            if(family==Family.DOTNET&&(name.endsWith(".sln")||name.endsWith(".slnx")))dotnetSolutions.computeIfAbsent(dir,k->new ArrayList<>()).add(descriptor);
            if(family==Family.GRADLE)try{scripts.put(path,DeclarativeScript.parse(decode(descriptor),200_000,64));}
            catch(IllegalArgumentException failure){requirements.add(requirement(Kind.DISCOVERY,path,"DYNAMIC_BUILD_DISCOVERY_REQUIRED",List.of(),evidence));}
        }
        // Fold secondary Python requirements.txt files into an enclosing Python root when one already owns them.
        for(var root:List.copyOf(roots.values()))if(root.family()==Family.PYTHON&&!pythonPrimaryDirs.contains(root.directory())){
            var owner=roots.values().stream()
                    .filter(candidate->candidate.family()==Family.PYTHON&&!candidate.id().equals(root.id())&&within(root.directory(),candidate.directory()))
                    .max(Comparator.comparingInt(candidate->candidate.directory().length()));
            if(owner.isPresent()){
                var enclosing=owner.orElseThrow();
                var merged=new ArrayList<Evidence>(enclosing.evidence());merged.addAll(root.evidence());
                roots.put(enclosing.id(),new Root(enclosing.id(),enclosing.directory(),Family.PYTHON,merged,enclosing.relatedRoots()));
                roots.remove(root.id());
            }
        }
        // Parse standard .NET solution files (.sln / .slnx) to attach child project roots to their enclosing solution.
        var dotnetSolutionMembers=new TreeMap<String,Set<String>>();
        var dotnetFoldedSourceRoots=new TreeMap<String,Map<String,List<String>>>();
        var dotnetFoldedEvidence=new TreeMap<String,List<Evidence>>();
        var dotnetMultiProjectSolutionDirs=new HashSet<String>();
        for(var entry:dotnetSolutions.entrySet()){
            String slnDir=entry.getKey(),slnRoot="DOTNET:"+slnDir;
            var referencedProjects=new TreeMap<String,String>(); // projDir -> resolvedProjFile
            for(var slnDesc:entry.getValue()){
                try{
                    String text=decode(slnDesc);
                    Pattern pat=filename(slnDesc.path()).endsWith(".slnx")?SLNX_PROJECT:SLN_PROJECT;
                    Matcher m=pat.matcher(text);
                    while(m.find()){
                        String rawRel=(pat==SLNX_PROJECT?m.group(1):m.group(2)).trim().replace('\\','/');
                        try{
                            String resolvedFile=resolve(slnDir,rawRel);
                            referencedProjects.putIfAbsent(directory(resolvedFile),resolvedFile);
                        }catch(IllegalArgumentException unsafe){
                            requirements.add(requirement(Kind.DISCOVERY,slnDesc.path(),"BUILD_PATH_UNRESOLVED",List.of(),List.of(new Evidence(slnDesc.path(),slnDesc.content().digest()))));
                        }
                    }
                }catch(IllegalArgumentException failure){
                    requirements.add(requirement(Kind.DISCOVERY,slnDesc.path(),"DYNAMIC_BUILD_DISCOVERY_REQUIRED",List.of(),List.of(new Evidence(slnDesc.path(),slnDesc.content().digest()))));
                }
            }
            if(referencedProjects.isEmpty())continue;
            var nonTestDirs=new TreeSet<String>();
            var testDirs=new TreeSet<String>();
            var extraEvidence=new ArrayList<Evidence>();
            boolean slnDirHasDirectProject=descriptors.keySet().stream().anyMatch(p->directory(p).equals(slnDir)&&isDotNetProjectFile(filename(p)));
            if(slnDirHasDirectProject)nonTestDirs.add(slnDir);
            for(var proj:referencedProjects.entrySet()){
                String projDir=proj.getKey(),projFile=proj.getValue();
                if(!projDir.equals(slnDir))roots.remove("DOTNET:"+projDir);
                if(isDotNetTestProject(projDir,projFile,descriptors))testDirs.add(projDir);
                else nonTestDirs.add(projDir);
                var projDesc=descriptors.get(projFile);
                if(projDesc!=null)extraEvidence.add(new Evidence(projDesc.path(),projDesc.content().digest()));
            }
            // Single-service solution layout (e.g. svc.sln -> src/svc.csproj + tests/svc.tests.csproj):
            // fold into one DOTNET:<slnDir> module with distinct main/test contexts rather than triple-counting.
            boolean singleLayout=nonTestDirs.size()<=1&&nonTestDirs.stream().allMatch(d->d.equals(slnDir)||isSingleServiceChildDir(slnDir,d));
            if(singleLayout){
                var sets=new TreeMap<String,List<String>>();
                if(!nonTestDirs.isEmpty())sets.put("main",List.copyOf(nonTestDirs));
                else sets.put("unclassified",List.of(slnDir));
                if(!testDirs.isEmpty())sets.put("test",List.copyOf(testDirs));
                dotnetFoldedSourceRoots.put(slnRoot,sets);
                dotnetFoldedEvidence.put(slnRoot,extraEvidence);
                var slnRootRec=roots.get(slnRoot);
                if(slnRootRec!=null&&!extraEvidence.isEmpty()){
                    var merged=new ArrayList<Evidence>(slnRootRec.evidence());merged.addAll(extraEvidence);
                    roots.put(slnRoot,new Root(slnRootRec.id(),slnRootRec.directory(),Family.DOTNET,merged,slnRootRec.relatedRoots()));
                }
            }else{
                var members=dotnetSolutionMembers.computeIfAbsent(slnRoot,k->new TreeSet<>());
                members.addAll(nonTestDirs);
                members.addAll(testDirs);
                if(!slnDirHasDirectProject)dotnetMultiProjectSolutionDirs.add(slnRoot);
            }
        }
        var membership=new TreeMap<String,String>();var related=new TreeMap<String,Set<String>>();
        var bazelMembers=new TreeMap<String,Set<String>>();
        var bazelWorkspaces=descriptors.keySet().stream().filter(path->Set.of("WORKSPACE","WORKSPACE.bazel","MODULE.bazel").contains(filename(path))).map(ReadinessPlanner::directory).distinct().toList();
        for(var root:List.copyOf(roots.values()))if(root.family()==Family.BAZEL&&!bazelWorkspaces.contains(root.directory())){
            var owner=bazelWorkspaces.stream().filter(directory->within(root.directory(),directory)).max(Comparator.comparingInt(String::length));
            if(owner.isPresent()){roots.remove(root.id());bazelMembers.computeIfAbsent("BAZEL:"+owner.orElseThrow(),key->new TreeSet<>()).add(root.directory());}
        }
        var projectRemapping=new TreeMap<String,String>();
        for(var entry:scripts.entrySet())if(filename(entry.getKey()).startsWith("settings.")){
            String dir=directory(entry.getKey()),root="GRADLE:"+dir;
            for(var statement:entry.getValue()){
                if((statement.key().equals("include")||statement.key().equals("includeBuild"))&&statement.body().isEmpty()
                        &&statement.syntax().matches("(?:include|includeBuild)(?:\\(#(?:,#)*\\)|#(?:,#)*)")){
                    for(String literal:statement.strings())try{
                        String relative=statement.key().equals("include")?literal.replaceFirst("^:","").replace(':','/'):literal;
                        String moduleDir=resolve(dir,relative);
                        if(statement.key().equals("include")){
                            membership.put(moduleDir,root);
                            projectRemapping.put(root+":"+relative,moduleDir);
                        }else related.computeIfAbsent(root,key->new TreeSet<>()).add("GRADLE:"+moduleDir);
                    }catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,entry.getKey(),"BUILD_PATH_UNRESOLVED",List.of(),roots.get(root).evidence()));}
                }else if(projectDirectoryAssignment(statement.syntax())) {
                    // Applied in second pass after includes.
                }else if(eachDirInclude(statement)){
                    try{
                        String baseDir=resolve(dir,statement.strings().getFirst()),prefix=baseDir.equals(".")?"":baseDir+"/";
                        for(String p:descriptors.keySet()){
                            if(!p.startsWith(prefix))continue;
                            String rest=p.substring(prefix.length());int slash=rest.indexOf('/');if(slash<=0)continue;
                            String childName=rest.substring(0,slash),childFile=rest.substring(slash+1);
                            if(!Set.of("build.gradle","build.gradle.kts").contains(childFile))continue;
                            String moduleDir=resolve(baseDir,childName);
                            membership.put(moduleDir,root);
                            projectRemapping.put(root+":"+childName,moduleDir);
                        }
                    }catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,entry.getKey(),"BUILD_PATH_UNRESOLVED",List.of(),roots.get(root).evidence()));}
                }else if(declarativeSettingsStatement(statement)){
                    // Declarative settings configuration.
                }else unknown.add(root+":settings:"+statement.offset());
            }
            for(var statement:entry.getValue())if(projectDirectoryAssignment(statement.syntax()))try{
                String project=statement.strings().getFirst();
                if(!project.startsWith(":"))throw new IllegalArgumentException();
                String relative=project.substring(1).replace(':','/');
                String oldDir=resolve(dir,relative),newDir=resolve(dir,statement.strings().get(1));
                if(membership.containsKey(oldDir)){
                    if(!descriptors.containsKey(resolve(oldDir,"build.gradle"))&&!descriptors.containsKey(resolve(oldDir,"build.gradle.kts")))
                        membership.remove(oldDir);
                    membership.put(newDir,root);
                    projectRemapping.put(root+":"+relative,newDir);
                }
            }catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,entry.getKey(),"BUILD_PATH_UNRESOLVED",List.of(),roots.get(root).evidence()));}
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
            var directories=new TreeSet<String>();
            if(!dotnetMultiProjectSolutionDirs.contains(root.id()))directories.add(dir);
            directories.addAll(bazelMembers.getOrDefault(root.id(),Set.of()));
            directories.addAll(dotnetSolutionMembers.getOrDefault(root.id(),Set.of()));
            membership.forEach((module,owner)->{if(owner.equals(root.id()))directories.add(module);});
            for(String moduleDir:directories){
                String id=root.family()+":"+moduleDir;
                var evidenceList=new ArrayList<>(descriptors.values().stream().filter(descriptor->directory(descriptor.path()).equals(moduleDir)&&family(filename(descriptor.path()))==root.family())
                        .map(descriptor->new Evidence(descriptor.path(),descriptor.content().digest())).toList());
                if(root.family()==Family.DOTNET&&moduleDir.equals(dir)&&dotnetFoldedEvidence.containsKey(root.id())){
                    var seenEv=new HashSet<>(evidenceList);
                    for(var extra:dotnetFoldedEvidence.get(root.id()))if(seenEv.add(extra))evidenceList.add(extra);
                }
                var evidence=List.copyOf(evidenceList);
                var dependencies=new TreeSet<String>();
                var sets=new TreeMap<String,List<String>>();
                if(root.family()==Family.DOTNET&&moduleDir.equals(dir)&&dotnetFoldedSourceRoots.containsKey(root.id())){
                    sets.putAll(dotnetFoldedSourceRoots.get(root.id()));
                }else if(Set.of(Family.GRADLE,Family.SBT).contains(root.family())){
                    sets.put("main",List.of(resolve(moduleDir,"src/main")));sets.put("test",List.of(resolve(moduleDir,"src/test")));
                }else sets.put("unclassified",List.of(moduleDir));
                if(root.family()==Family.GRADLE){
                    unknown.add(root.id()+":variants-and-substitution");
                    for(var script:scripts.entrySet())if(directory(script.getKey()).equals(moduleDir)&&filename(script.getKey()).startsWith("build.")){
                        scanGradle(script.getValue(),moduleDir,root,sets,dependencies,requirements,unknown,evidence,projectRemapping);
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
            List<Requirement> requirements,Set<String> unknown,List<Evidence> evidence,Map<String,String> projectRemapping){
        for(var statement:statements){
            if(statement.key().equals("sourceSets"))for(var set:statement.body()){
                if(!set.key().matches("[A-Za-z][A-Za-z0-9_]*")){unknown.add(root.id()+":dynamic-source-sets");continue;}
                var paths=new ArrayList<String>();boolean replaces=false;
                for(var language:set.body())for(var source:language.body())if(Set.of("srcDir","srcDirs","setSrcDirs","resourceDirs").contains(source.key())){
                    replaces|=source.key().equals("setSrcDirs")||source.syntax().startsWith("srcDirs=")||source.syntax().startsWith("resourceDirs=");
                    for(String path:source.strings())try{paths.add(resolve(dir,UniversalBuildIngestion.expandStandardBuildDirs(path)));}
                    catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,dir,"BUILD_PATH_UNRESOLVED",List.of(),evidence));}
                }
                if(!replaces)paths.addFirst(resolve(dir,"src/"+set.key()));
                sets.put(set.key(),List.copyOf(paths));
            }
            if(statement.key().equals("dependencies"))for(var dependency:statement.body()){
                if(dependency.syntax().contains("project(#)"))for(String project:dependency.strings())try{
                    String rel=project.replaceFirst("^:","").replace(':','/');
                    String mapped=projectRemapping.getOrDefault(root.id()+":"+rel,resolve(root.directory(),rel));
                    dependencies.add("GRADLE:"+mapped);
                }catch(IllegalArgumentException unsafe){unknown.add(root.id()+":dynamic-project-dependency");}
                else if(dependency.syntax().matches("[A-Za-z0-9_]+(?:\\(projects(?:\\.[A-Za-z0-9_]+)+\\)|projects(?:\\.[A-Za-z0-9_]+)+)"))try{
                    String acc=dependency.syntax().substring(dependency.key().length());
                    if(acc.startsWith("(")&&acc.endsWith(")"))acc=acc.substring(1,acc.length()-1);
                    if(acc.endsWith(".dependencyProject"))acc=acc.substring(0,acc.length()-".dependencyProject".length());
                    String rel=acc.substring("projects.".length()).replace('.','/');
                    String mapped=projectRemapping.getOrDefault(root.id()+":"+rel,resolve(root.directory(),rel));
                    dependencies.add("GRADLE:"+mapped);
                }catch(IllegalArgumentException unsafe){unknown.add(root.id()+":dynamic-project-dependency");}
            }
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
    private static boolean projectDirectoryAssignment(String syntax){
        return syntax.equals("project(#).projectDir=file(#)")||syntax.equals("project(#).projectDir=newFile(settingsDir,#)")
                ||syntax.equals("project(#).projectDir=File(settingsDir,#)")||syntax.equals("project(#).projectDir=newFile(rootDir,#)")
                ||syntax.equals("project(#).projectDir=File(rootDir,#)");
    }
    private static boolean eachDirInclude(DeclarativeScript.Statement statement){
        String syntax=statement.syntax();
        return (syntax.equals("file(#).eachDir")||syntax.equals("newFile(settingsDir,#).eachDir")
                ||syntax.equals("File(settingsDir,#).eachDir")||syntax.equals("newFile(rootDir,#).eachDir")
                ||syntax.equals("File(rootDir,#).eachDir"))
                &&!statement.body().isEmpty()&&statement.strings().size()==1
                &&statement.body().stream().anyMatch(child->child.key().equals("include"));
    }
    private static boolean declarativeSettingsStatement(DeclarativeScript.Statement statement){
        String syntax=statement.syntax(),key=statement.key();
        if(!statement.body().isEmpty())return Set.of("pluginManagement","dependencyResolutionManagement","plugins","buildCache","gradle").contains(key)&&syntax.equals(key);
        return syntax.equals("rootProject.name=#")||syntax.matches("enableFeaturePreview(?:\\(#\\)|#)")||key.equals("import")
                ||syntax.matches("(?:val|var|def)[A-Za-z_][A-Za-z0-9_]*=(?:#|true|false|[0-9]+)")
                ||syntax.matches("gradle\\.ext\\.[A-Za-z_][A-Za-z0-9_]*=(?:#|true|false|[0-9]+)");
    }
    private static String decode(Descriptor descriptor){try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(descriptor.bytes())).toString();}catch(CharacterCodingException malformed){throw new IllegalArgumentException("Descriptor decoding required");}}
    public static String resolve(String directory,String relative){
        if(relative.isBlank()||relative.startsWith("/")||relative.contains("\\")||relative.contains(":")||relative.contains("$")||relative.indexOf('\0')>=0)throw new IllegalArgumentException("Unsafe build path");
        var parts=new ArrayList<String>();if(!directory.equals("."))parts.addAll(List.of(directory.split("/")));
        for(String part:relative.split("/",-1)){if(part.equals(".")||part.isEmpty())continue;if(part.equals("..")){if(parts.isEmpty())throw new IllegalArgumentException("Outside snapshot");parts.removeLast();}else parts.add(part);}
        return parts.isEmpty()?".":String.join("/",parts);
    }
    private static boolean isDotNetProjectFile(String name){
        String lower=name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csproj")||lower.endsWith(".vbproj")||lower.endsWith(".fsproj");
    }
    private static boolean isSingleServiceChildDir(String slnDir,String projDir){
        String prefix=slnDir.equals(".")?"":slnDir+"/";
        if(!projDir.startsWith(prefix))return false;
        String rel=projDir.substring(prefix.length()).toLowerCase(Locale.ROOT);
        if(rel.contains("/"))return false;
        if(SINGLE_SERVICE_LAYOUT_SUBDIRS.contains(rel))return true;
        String slnBase=slnDir.equals(".")?"":filename(slnDir).toLowerCase(Locale.ROOT);
        return !slnBase.isEmpty()&&rel.equals(slnBase);
    }
    private static boolean isDotNetTestProject(String projDir,String projFile,Map<String,Descriptor> descriptors){
        String dirBase=filename(projDir).toLowerCase(Locale.ROOT);
        if(DOTNET_TEST_DIR_NAMES.contains(dirBase)||dirBase.endsWith(".tests")||dirBase.endsWith(".test")
                ||dirBase.endsWith("-tests")||dirBase.endsWith("-test")||dirBase.endsWith("_tests")||dirBase.endsWith("_test")){
            return true;
        }
        String fileBase=filename(projFile).toLowerCase(Locale.ROOT);
        int dot=fileBase.lastIndexOf('.');
        String stem=dot>0?fileBase.substring(0,dot):fileBase;
        if(stem.endsWith(".tests")||stem.endsWith(".test")||stem.endsWith("-tests")||stem.endsWith("-test")
                ||stem.endsWith("_tests")||stem.endsWith("_test")||stem.endsWith("tests")){
            return true;
        }
        var desc=descriptors.get(projFile);
        if(desc!=null){
            try{
                String content=decode(desc).toLowerCase(Locale.ROOT);
                if(content.contains("<istestproject>true</istestproject>")
                        ||content.contains("microsoft.net.test.sdk")
                        ||content.contains("mstest.testframework")){
                    return true;
                }
            }catch(IllegalArgumentException ignored){}
        }
        return false;
    }
    private static Family family(String name){
        if(Set.of("build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts").contains(name))return Family.GRADLE;
        if(Set.of(".classpath",".project").contains(name)||name.endsWith(".iml"))return Family.IDE;
        return switch(name){case "build.xml"->Family.ANT;case "WORKSPACE","WORKSPACE.bazel","MODULE.bazel","BUILD","BUILD.bazel"->Family.BAZEL;case "build.sbt"->Family.SBT;
            case "package.json","deno.json","deno.jsonc"->Family.NODE;case "pyproject.toml","setup.py","setup.cfg","requirements.txt","Pipfile"->Family.PYTHON;case "Cargo.toml"->Family.CARGO;case "go.mod","go.work"->Family.GO;
            case "composer.json"->Family.COMPOSER;case "Gemfile"->Family.RUBY;case "pubspec.yaml"->Family.DART;case "Package.swift"->Family.SWIFT;
            case "CMakeLists.txt"->Family.CMAKE;case "Makefile"->Family.MAKE;default->name.endsWith(".csproj")||name.endsWith(".vbproj")||name.endsWith(".fsproj")||name.endsWith(".sln")||name.endsWith(".slnx")?Family.DOTNET:null;};
    }
}
