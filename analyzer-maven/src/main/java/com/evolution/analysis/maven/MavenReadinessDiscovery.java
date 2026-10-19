package com.evolution.analysis.maven;

import com.evolution.analysis.acquisition.streaming.InventoryManifest;
import com.evolution.analysis.readiness.ReadinessDiscovery;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import com.evolution.analysis.readiness.ReadinessPlan.Module;
import com.evolution.analysis.buildmodel.PassiveReadinessDiscovery;
import com.evolution.analysis.readiness.ReadinessPlanner;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import java.util.*;
import static com.evolution.analysis.readiness.ReadinessPlanner.*;

public final class MavenReadinessDiscovery implements ReadinessDiscovery {
    @Override public Projection discover(Map<String,Descriptor> descriptors,InventoryManifest inventory){
        var models=new TreeMap<String,Model>();var roots=new ArrayList<Root>();var modules=new ArrayList<Module>();var contexts=new ArrayList<Context>();
        var requirements=new ArrayList<Requirement>();var unknown=new TreeSet<String>();
        var poms=new TreeMap<String,Descriptor>();
        for(var descriptor:descriptors.values())if(filename(descriptor.path()).equals("pom.xml")){
            String dir=directory(descriptor.path());poms.put(dir,descriptor);
            try{models.put(dir,SecurePomReader.read(descriptor.bytes()));}
            catch(SecurePomReader.Rejected invalid){requirements.add(requirement(Kind.MODEL,descriptor.path(),"POM_MODEL_UNAVAILABLE",List.of(),evidence(descriptor)));}
        }
        var children=new TreeMap<String,Set<String>>();var profileChildren=new TreeMap<String,Map<String,List<String>>>();var included=new TreeSet<String>();
        for(var entry:models.entrySet()){
            var variants=new TreeMap<String,List<String>>();variants.put("baseline",entry.getValue().getModules());
            for(var profile:entry.getValue().getProfiles()){
                if(profile.getId()==null||profile.getId().isBlank())continue;
                variants.put("profile:"+profile.getId(),profile.getModules());unknown.add("MAVEN:"+entry.getKey()+":profile-combinations-and-activation");
            }
            for(var variant:variants.entrySet())for(String declared:variant.getValue())try{
                String dir=resolveModule(entry.getKey(),declared);
                children.computeIfAbsent(entry.getKey(),key->new TreeSet<>()).add(dir);included.add(dir);
                profileChildren.computeIfAbsent(entry.getKey(),key->new TreeMap<>()).computeIfAbsent(variant.getKey(),key->new ArrayList<>()).add(dir);
                if(!poms.containsKey(dir))requirements.add(requirement(Kind.DISCOVERY,dir,"DECLARED_MODULE_UNAVAILABLE",List.of(),evidence(poms.get(entry.getKey()))));
            }catch(IllegalArgumentException unsafe){requirements.add(requirement(Kind.DISCOVERY,poms.get(entry.getKey()).path(),"BUILD_PATH_UNRESOLVED",List.of(),evidence(poms.get(entry.getKey()))));}
        }
        var rootDirs=new TreeSet<>(poms.keySet());rootDirs.removeAll(included);
        var reached=new HashSet<String>();
        for(String dir:rootDirs)reached.addAll(closure(dir,children));
        for(String dir:poms.keySet())if(!reached.contains(dir)){
            rootDirs.add(dir);reached.addAll(closure(dir,children));requirements.add(requirement(Kind.MODEL,"MAVEN:"+dir,"AGGREGATION_CYCLE_OR_SHARED_MODEL",List.of(),evidence(poms.get(dir))));
        }
        var coordinates=new TreeMap<String,List<String>>();
        for(var entry:models.entrySet()){
            String gav=gav(entry.getValue());if(!gav.contains("UNKNOWN"))coordinates.computeIfAbsent(gav,key->new ArrayList<>()).add(entry.getKey());
        }
        coordinates.forEach((gav,owners)->{if(owners.size()>1)requirements.add(requirement(Kind.MODEL,gav,"DUPLICATE_GAV",List.of(),owners.stream().flatMap(owner->evidence(poms.get(owner)).stream()).toList()));});
        for(String rootDir:rootDirs){
            String rootId="MAVEN:"+rootDir;var related=new TreeSet<String>();
            var rootVariants=new TreeSet<String>();rootVariants.add("baseline");
            for(String member:closure(rootDir,children))profileChildren.getOrDefault(member,Map.of()).keySet().stream().filter(variant->!variant.equals("baseline")).forEach(rootVariants::add);
            var memberships=new TreeMap<String,Set<String>>();for(String variant:rootVariants)memberships.put(variant,variantClosure(rootDir,variant,profileChildren));
            for(String dir:closure(rootDir,children)){
                var model=models.get(dir);if(model==null)continue;
                String moduleId=rootId+"->"+dir;var evidence=evidence(poms.get(dir));var dependencies=new TreeSet<String>();
                var parent=model.getParent();
                if(parent!=null){
                    String relative=parent.getRelativePath();
                    if(relative!=null&&!relative.isEmpty())try{
                        String parentPath=PassiveReadinessDiscovery.resolve(dir,relative),parentDir=filename(parentPath).equals("pom.xml")?directory(parentPath):parentPath;
                        if(poms.containsKey(parentDir))related.add("MAVEN:"+parentDir);else requirements.add(requirement(Kind.MODEL,moduleId,"PARENT_MODEL_REQUIRED",List.of(),evidence));
                    }catch(IllegalArgumentException external){requirements.add(requirement(Kind.MODEL,moduleId,"PARENT_MODEL_REQUIRED",List.of(),evidence));}
                    else requirements.add(requirement(Kind.MODEL,moduleId,"PARENT_MODEL_REQUIRED",List.of(),evidence));
                }
                for(var dependency:model.getDependencies()){
                    String gav=String.join(":",text(dependency.getGroupId()),text(dependency.getArtifactId()),text(dependency.getVersion()));
                    var matches=coordinates.getOrDefault(gav,List.of());
                    if(matches.size()==1&&closure(rootDir,children).contains(matches.getFirst()))dependencies.add(rootId+"->"+matches.getFirst());
                    if(dependency.getVersion()==null||dependency.getVersion().contains("$")||dependency.getVersion().contains("[")||dependency.getVersion().endsWith("SNAPSHOT"))
                        requirements.add(requirement(Kind.DEPENDENCIES,moduleId+":"+gav,"NATIVE_VERSION_RESOLUTION_REQUIRED",List.of(),evidence));
                    String artifact=text(dependency.getArtifactId()).toLowerCase(Locale.ROOT);
                    String reason=artifact.contains("javafx")?"JAVAFX_PLATFORM_REQUIRED":artifact.contains("servlet")||artifact.contains("jakartaee")||artifact.contains("javaee")?"ENTERPRISE_PLATFORM_REQUIRED":artifact.contains("osgi")?"OSGI_RUNTIME_ARTIFACTS_REQUIRED":null;
                    if(reason!=null)requirements.add(requirement(Kind.PLATFORM,moduleId,reason,List.of(),evidence));
                }
                var variants=new TreeMap<String,Profile>();
                for(String variant:rootVariants)if(memberships.get(variant).contains(dir))variants.put(variant,model.getProfiles().stream().filter(profile->variant.equals("profile:"+profile.getId())).findFirst().orElse(null));
                for(var variant:variants.entrySet()){
                    var environment=new TreeMap<String,String>();var properties=new Properties();properties.putAll(model.getProperties());
                    if(variant.getValue()!=null)properties.putAll(variant.getValue().getProperties());
                    for(String name:List.of("source","target","release","enablePreview"))environment.put(name,property(properties,"maven.compiler."+name));
                    environment.put("encoding",property(properties,"project.build.sourceEncoding"));environment.put("buildJdk","UNSPECIFIED");environment.put("platformVendor","UNSPECIFIED");environment.put("modulePath","UNOBSERVED");
                    var build=model.getBuild();
                    if(build!=null)for(var plugin:build.getPlugins()){
                        String name=text(plugin.getArtifactId());
                        if(name.equals("maven-compiler-plugin")&&plugin.getConfiguration() instanceof Xpp3Dom config)for(String key:List.of("source","target","release","encoding","enablePreview","executable","fork")){
                            var node=config.getChild(key);if(node!=null&&node.getValue()!=null)environment.put(key,node.getValue().contains("${")?"UNRESOLVED":node.getValue());
                        }
                        String reason=name.contains("protobuf")||name.contains("antlr")||name.contains("openapi")||name.contains("jooq")?"NATIVE_GENERATOR_REQUIRED":name.contains("aspectj")?"WOVEN_BYTECODE_REQUIRED":name.contains("jni")||name.contains("nar")?"JNI_NATIVE_TOOLCHAIN_REQUIRED":name.equals("maven-bundle-plugin")?"OSGI_RUNTIME_ARTIFACTS_REQUIRED":null;
                        if(reason!=null)requirements.add(requirement(reason.equals("NATIVE_GENERATOR_REQUIRED")?Kind.GENERATION:Kind.PLATFORM,moduleId,reason,List.of(),evidence));
                        unknown.add(moduleId+":plugin-effects:"+name);
                    }
                    for(String set:List.of("main","test")){
                        String declared=build==null?null:set.equals("main")?build.getSourceDirectory():build.getTestSourceDirectory();
                        List<String> sourceRoots;
                        try{sourceRoots=List.of(PassiveReadinessDiscovery.resolve(dir,declared==null?"src/"+set+"/java":declared));}
                        catch(IllegalArgumentException unresolved){sourceRoots=List.of();requirements.add(requirement(Kind.DISCOVERY,moduleId,"SOURCE_ROOT_MODEL_REQUIRED",List.of(),evidence));}
                        contexts.add(ReadinessPlanner.context(moduleId,rootId,set,variant.getKey(),sourceRoots,environment,evidence,inventory,true));
                    }
                }
                modules.add(new Module(moduleId,rootId,dir,text(model.getArtifactId()),List.of(),List.copyOf(dependencies),evidence));
                if("war".equals(model.getPackaging())||"ear".equals(model.getPackaging()))requirements.add(requirement(Kind.GENERATION,moduleId,"JSP_ENTERPRISE_GENERATION_REQUIRED",List.of(),evidence));
            }
            roots.add(new Root(rootId,rootDir,Family.MAVEN,evidence(poms.get(rootDir)),List.copyOf(related)));
        }
        return new Projection(roots,modules,contexts,requirements,List.copyOf(unknown));
    }
    private static String property(Properties properties,String key){String value=properties.getProperty(key);return value==null?"UNSPECIFIED":value.contains("${")?"UNRESOLVED":value;}
    private static String text(String value){return value==null||value.isBlank()?"UNKNOWN":value;}
    private static String gav(Model model){return String.join(":",text(model.getGroupId()!=null?model.getGroupId():model.getParent()==null?null:model.getParent().getGroupId()),text(model.getArtifactId()),text(model.getVersion()!=null?model.getVersion():model.getParent()==null?null:model.getParent().getVersion()));}
    private static List<Evidence> evidence(Descriptor descriptor){return descriptor==null?List.of():List.of(new Evidence(descriptor.path(),descriptor.content().digest()));}
    private static String resolveModule(String dir,String declared){String path=PassiveReadinessDiscovery.resolve(dir,declared);return filename(path).equals("pom.xml")?directory(path):path;}
    private static Set<String> closure(String root,Map<String,Set<String>> children){
        var result=new TreeSet<String>();var frontier=new ArrayDeque<String>();frontier.add(root);
        while(!frontier.isEmpty()){String next=frontier.removeFirst();if(result.add(next))frontier.addAll(children.getOrDefault(next,Set.of()));}
        return result;
    }
    private static Set<String> variantClosure(String root,String variant,Map<String,Map<String,List<String>>> children){
        var result=new TreeSet<String>();var frontier=new ArrayDeque<String>();frontier.add(root);
        while(!frontier.isEmpty()){
            String next=frontier.removeFirst();if(!result.add(next))continue;var declared=children.getOrDefault(next,Map.of());
            frontier.addAll(declared.getOrDefault("baseline",List.of()));if(!variant.equals("baseline"))frontier.addAll(declared.getOrDefault(variant,List.of()));
        }
        return result;
    }
}
