package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.readiness.ReadinessPlan.*;
import com.evolution.analysis.readiness.ReadinessPlan.Module;
import com.evolution.analysis.ingestion.IngestionEvidence;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class ReadinessPlanner {
    public record Policy(Map<String,List<String>> requested, boolean expandDiscovered, int descriptorWorkerBytes) {
        public Policy {
            var copy=new TreeMap<String,List<String>>();requested.forEach((key,value)->copy.put(key,BuildContextSelection.sorted(value)));requested=Collections.unmodifiableMap(copy);
            if(descriptorWorkerBytes<1||descriptorWorkerBytes>64_000_000)throw new IllegalArgumentException("Invalid descriptor worker");
        }
        public static Policy comprehensive(){return new Policy(Map.of(),true,1_000_000);}
    }
    private final List<ReadinessDiscovery> providers;
    public ReadinessPlanner(List<ReadinessDiscovery> providers){this.providers=List.copyOf(providers);}
    public ReadinessPlan plan(String revision, InventoryReader inventory, RepositoryContentStore content, Policy policy, BooleanSupplier cancelled)throws IOException{
        var manifest=inventory.manifest(revision).orElseThrow(()->new IOException("SEALED_INVENTORY_REQUIRED"));
        var descriptors=new TreeMap<String,ReadinessDiscovery.Descriptor>();var issues=new ArrayList<Requirement>();
        var sourceAnchors=new TreeMap<String,Set<String>>();var languageHints=new TreeMap<String,Set<String>>();
        Optional<String> cursor=Optional.empty();
        do{
            check(cancelled);var page=inventory.page(revision,512,cursor);cursor=page.continuation();
            for(var entry:page.entries()){
                if(entry.disposition()!=InventoryEntry.Disposition.ACQUIRED||entry.kind()!=InventoryEntry.Kind.FILE)continue;
                if(entry.path().utf8().isEmpty())continue;
                String path=entry.path().utf8().orElseThrow();
                String readinessReason=path.endsWith("/module-info.java")||path.equals("module-info.java")?"JPMS_MANIFEST_REQUIRED":path.endsWith(".jsp")?"JSP_GENERATION_REQUIRED":path.endsWith(".fxml")?"JAVAFX_PLATFORM_REQUIRED":path.endsWith(".aj")?"WOVEN_BYTECODE_REQUIRED":filename(path).equals("AndroidManifest.xml")?"ANDROID_SDK_REQUIRED":null;
                if(readinessReason!=null)issues.add(requirement(Kind.PLATFORM,path,readinessReason,List.of(),List.of(new Evidence(path,entry.content().orElseThrow().digest()))));
                if(language(path).isPresent())sourceAnchors.computeIfAbsent(anchor(path),key->new TreeSet<>()).add(sourceSet(path));
                language(path).ifPresent(language->languageHints.computeIfAbsent(anchor(path),key->new TreeSet<>()).add(language));
                if(!descriptor(path))continue;
                var handle=entry.content().orElseThrow();
                if(handle.size()>policy.descriptorWorkerBytes()){
                    issues.add(requirement(Kind.DISCOVERY,path,"DESCRIPTOR_WORKER_REQUIRED",List.of(),List.of(new Evidence(path,handle.digest()))));continue;
                }
                if(!content.verify(handle))throw new IOException("DESCRIPTOR_DIGEST_MISMATCH");
                try(var stream=content.open(handle)){
                    byte[] bytes=stream.readNBytes(policy.descriptorWorkerBytes()+1);
                    if(bytes.length!=handle.size()||!ContentDigest.sha256(bytes).equals(handle.digest()))throw new IOException("DESCRIPTOR_DIGEST_MISMATCH");
                    descriptors.put(path,new ReadinessDiscovery.Descriptor(path,handle,bytes));
                }
            }
        }while(cursor.isPresent());
        var roots=new TreeMap<String,Root>();var modules=new TreeMap<String,Module>();var contexts=new TreeMap<String,Context>();var unknown=new TreeSet<String>();
        for(var provider:providers){
            check(cancelled);var projection=provider.discover(Collections.unmodifiableMap(descriptors),manifest);
            for(var root:projection.roots())put(roots,root.id(),root);
            for(var module:projection.modules())put(modules,module.id(),module);
            for(var context:projection.contexts())put(contexts,context.id(),context);
            issues.addAll(projection.requirements());unknown.addAll(projection.unknown());
        }
        for(var entry:sourceAnchors.entrySet()){
            String directory=entry.getKey();
            String sourcePrefix=directory.equals(".")?"":directory+"/";
            if(entry.getValue().stream().allMatch(set->contexts.values().stream().anyMatch(context->context.sourceRoots().stream().anyMatch(root->set.equals("unclassified")?root.equals(directory):within(root,sourcePrefix+"src/"+set)))))continue;
            var owners=modules.values().stream().filter(module->within(directory,module.directory())).toList();
            if(owners.isEmpty()){
                String id="PLAIN:"+directory;
                var sourceEvidence=List.of(new Evidence("inventory",manifest.inventory()));
                roots.put(id,new Root(id,directory,Family.PLAIN,sourceEvidence,List.of()));
                modules.put(id,new Module(id,id,directory,directory,List.of(),List.of(),sourceEvidence));
                for(String set:entry.getValue()){
                    String prefix=directory.equals(".")?"":directory+"/";
                    String sourceRoot=set.equals("unclassified")?directory:prefix+"src/"+set;
                    var context=context(id,id,set,"baseline",List.of(sourceRoot),Map.of("compiler","UNSPECIFIED","platform","UNSPECIFIED","encoding","UNSPECIFIED"),sourceEvidence,manifest,true);
                    contexts.put(context.id(),context);
                }
                unknown.add(id+":native-source-plan");
            }else{
                int depth=owners.stream().mapToInt(module->module.directory().length()).max().orElseThrow();
                for(var module:owners)if(module.directory().length()==depth)for(String set:entry.getValue()){
                    boolean covered=contexts.values().stream().anyMatch(context->context.module().equals(module.id())&&(context.sourceSet().equals(set)||context.sourceRoots().stream().anyMatch(root->!set.equals("unclassified")&&within(root,sourcePrefix+"src/"+set))));
                    if(!covered){
                        String prefix=directory.equals(".")?"":directory+"/";
                        var context=context(module.id(),module.root(),set,"discovered-layout",List.of(set.equals("unclassified")?directory:prefix+"src/"+set),Map.of("sourcePlan","UNVALIDATED"),module.evidence(),manifest,true);
                        contexts.put(context.id(),context);unknown.add(module.id()+":source-set:"+set);
                    }
                }
            }
        }
        var selected=new TreeSet<String>();var unselected=new TreeSet<String>();
        for(var context:contexts.values()){
            boolean matches=policy.requested().entrySet().stream().allMatch(entry->entry.getValue().contains(dimension(context,entry.getKey())));
            if(matches&&(policy.expandDiscovered()||context.variant().equals("baseline")))selected.add(context.id());else unselected.add(context.id());
        }
        for(var entry:policy.requested().entrySet())for(String requested:entry.getValue())if(contexts.values().stream().noneMatch(context->dimension(context,entry.getKey()).equals(requested))){
            String subject="requested:"+entry.getKey()+":"+requested;unknown.add(subject);issues.add(requirement(Kind.DISCOVERY,subject,"REQUESTED_CONTEXT_UNDISCOVERED",List.of(),List.of()));
        }
        var selection=new BuildContextSelection(BuildContextSelection.SCHEMA,policy.requested(),List.copyOf(contexts.keySet()),List.copyOf(selected),List.copyOf(unselected),List.copyOf(unknown));
        if(!unknown.isEmpty())issues.add(requirement(Kind.DISCOVERY,manifest.inventory().value(),"CONTEXT_DISCOVERY_OPEN",List.of(),List.of(new Evidence("unknown-context-dimensions",IngestionEvidence.digest(unknown)))));
        for(var module:List.copyOf(modules.values())){
            var languages=new TreeSet<>(module.languages());languageHints.forEach((directory,values)->{
                int nearest=modules.values().stream().filter(candidate->within(directory,candidate.directory())).mapToInt(candidate->candidate.directory().length()).max().orElse(-1);
                if(within(directory,module.directory())&&module.directory().length()==nearest)languages.addAll(values);
            });
            modules.put(module.id(),new Module(module.id(),module.root(),module.directory(),module.target(),List.copyOf(languages),module.dependencies(),module.evidence()));
        }
        var recipes=new ArrayList<Recipe>();
        for(var root:roots.values()){
            var chosen=contexts.values().stream().filter(context->context.root().equals(root.id())&&selected.contains(context.id())).toList();
            if(chosen.isEmpty())continue;
            var model=requirement(Kind.MODEL,root.id(),"NATIVE_MODEL_REQUIRED",List.of(),root.evidence());issues.add(model);
            var tools=requirement(Kind.TOOLCHAIN,root.id(),"BUILD_TOOLCHAIN_REQUIRED",List.of(),root.evidence());issues.add(tools);
            var dependencies=requirement(Kind.DEPENDENCIES,root.id(),"EXACT_DEPENDENCY_CLOSURE_REQUIRED",List.of(model.id()),root.evidence());issues.add(dependencies);
            var recipeNeeds=new ArrayList<String>();
            for(var context:chosen){
                var platform=requirement(Kind.PLATFORM,context.id(),"ANALYZED_PLATFORM_REQUIRED",List.of(model.id(),tools.id()),context.evidence());issues.add(platform);
                var generation=requirement(Kind.GENERATION,context.id(),"GENERATOR_CLOSURE_REQUIRED",List.of(model.id(),dependencies.id(),tools.id()),context.evidence());issues.add(generation);
                var validation=requirement(context.buildable()?Kind.BUILD_VALIDATION:Kind.SEMANTIC_VALIDATION,context.id(),context.buildable()?"NATIVE_BUILD_VALIDATION_REQUIRED":"NATIVE_SEMANTIC_VALIDATION_REQUIRED",List.of(platform.id(),generation.id()),context.evidence());issues.add(validation);recipeNeeds.add(validation.id());
            }
            var pins=new TreeMap<String,String>();
            var recipeInputs=new ArrayList<Evidence>(root.evidence());
            for(var descriptor:descriptors.values())if(within(directory(descriptor.path()),root.directory())){
                var input=new Evidence(descriptor.path(),descriptor.content().digest());if(!recipeInputs.contains(input))recipeInputs.add(input);
                String name=filename(descriptor.path());
                if(name.contains("wrapper")||name.equals("rust-toolchain.toml")||name.equals(".java-version")||name.equals("global.json")||name.equals(".tool-versions"))pins.put(descriptor.path(),descriptor.content().digest().value());
            }
            recipes.add(new Recipe("recipe:"+root.id(),root.family(),root.id(),chosen.stream().map(Context::id).toList(),recipeNeeds,
                    List.of("CAPTURE_NATIVE_MODEL","RESOLVE_EXACT_DEPENDENCIES","GENERATE","COMPILE_SELECTED_SOURCE_SETS","NATIVE_SEMANTIC_VALIDATE"),recipeInputs,
                    List.of("ordered-compiler-manifests","generated-output-lineage","per-target-results","native-model"),pins,"ISOLATED_ROOT","BROKER_ONLY","CONFIGURED_WORKER_REQUIRED",
                    List.of("guest-overlay","broker-cache-downloads"),List.of("exact-input-closure","independent-protected-observation","context-and-toolchain-match","output-digests-and-paths")));
        }
        OwnershipSummary summary;
        try(var stream=new OwnershipStream(revision,inventory,List.copyOf(modules.values()),List.copyOf(contexts.values()),cancelled)){
            var ledger=content.put(stream,cancelled);summary=new OwnershipSummary(stream.total,stream.counts,ledger);
        }
        if(summary.counts().getOrDefault("UNOWNED",0L)>0||summary.counts().getOrDefault("AMBIGUOUS",0L)>0||summary.counts().getOrDefault("UNAVAILABLE",0L)>0)
            issues.add(requirement(Kind.OWNERSHIP,manifest.inventory().value(),"SOURCE_OWNERSHIP_UNRESOLVED",List.of(),List.of(new Evidence("ownership-ledger",summary.ledger().digest()))));
        return new ReadinessPlan(ReadinessPlan.SCHEMA,manifest,selection,List.copyOf(roots.values()),List.copyOf(modules.values()),List.copyOf(contexts.values()),
                issues.stream().distinct().sorted(Comparator.comparing(Requirement::id)).toList(),recipes,summary);
    }
    public static Context context(String module,String root,String set,String variant,List<String> sourceRoots,Map<String,String> environment,List<Evidence> evidence,InventoryManifest inventory,boolean buildable){
        String id="context:"+IngestionEvidence.digest(List.of(module,set,variant,environment)).value();
        return new Context(id,module,root,set,variant,sourceRoots,environment,evidence,IngestionEvidence.digest(List.of(inventory.inventory(),inventory.selection(),module,set,variant,environment)),buildable);
    }
    public static Requirement requirement(Kind kind,String subject,String reason,List<String> prerequisites,List<Evidence> evidence){
        return new Requirement("requirement:"+IngestionEvidence.digest(List.of(kind,subject,reason)).value(),kind,subject,reason,prerequisites,evidence,State.PENDING,Optional.empty());
    }
    private static <T> void put(Map<String,T> values,String key,T value){if(values.putIfAbsent(key,value)!=null&&!values.get(key).equals(value))throw new IllegalArgumentException("Conflicting discovery provider");}
    private static String dimension(Context context,String dimension){return switch(dimension){case "root"->context.root();case "module","target"->context.module();case "sourceSet"->context.sourceSet();case "variant","profile"->context.variant();default->context.environment().getOrDefault(dimension,"UNKNOWN");};}
    public static boolean descriptor(String path){String name=filename(path);return Set.of("pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts","gradle-wrapper.properties","maven-wrapper.properties","gradle.properties","maven.config","jvm.config","extensions.xml","toolchains.xml",".classpath",".project","build.xml","WORKSPACE","WORKSPACE.bazel","MODULE.bazel","BUILD","BUILD.bazel","build.sbt","package.json","package-lock.json","pnpm-workspace.yaml","pnpm-lock.yaml","yarn.lock","pyproject.toml","setup.py","requirements.txt","Cargo.toml","Cargo.lock","go.mod","go.sum","composer.json","composer.lock","Gemfile","Gemfile.lock","pubspec.yaml","pubspec.lock","Package.swift","CMakeLists.txt","Makefile","rust-toolchain.toml","global.json",".java-version",".tool-versions","libs.versions.toml").contains(name)||name.endsWith(".csproj")||name.endsWith(".sln")||name.endsWith(".iml");}
    public static Optional<String> language(String path){String name=filename(path);int dot=name.lastIndexOf('.');if(dot<0)return Optional.empty();return Optional.ofNullable(Map.ofEntries(Map.entry("java","java"),Map.entry("kt","kotlin"),Map.entry("scala","scala"),Map.entry("groovy","groovy"),Map.entry("py","python"),Map.entry("js","javascript"),Map.entry("jsx","javascript"),Map.entry("ts","typescript"),Map.entry("tsx","typescript"),Map.entry("rs","rust"),Map.entry("go","go"),Map.entry("cs","csharp"),Map.entry("c","c"),Map.entry("h","c"),Map.entry("cpp","cpp"),Map.entry("hpp","cpp"),Map.entry("rb","ruby"),Map.entry("php","php"),Map.entry("swift","swift"),Map.entry("dart","dart"),Map.entry("sql","sql"),Map.entry("proto","protobuf"),Map.entry("jsp","jsp"),Map.entry("aj","aspectj")).get(name.substring(dot+1)));}
    private static String anchor(String path){String rooted="/"+path;int src=rooted.indexOf("/src/");return src<0?directory(path):src==0?".":rooted.substring(1,src);}
    private static String sourceSet(String path){String rooted="/"+path;int src=rooted.indexOf("/src/");if(src<0)return "unclassified";String rest=rooted.substring(src+5);int slash=rest.indexOf('/');return slash<0?"unclassified":rest.substring(0,slash);}
    public static String directory(String path){int slash=path.lastIndexOf('/');return slash<0?".":path.substring(0,slash);}
    public static String filename(String path){return path.substring(path.lastIndexOf('/')+1);}
    public static boolean within(String path,String directory){return directory.equals(".")||path.equals(directory)||path.startsWith(directory+"/");}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("READINESS_CANCELLED");}
    private static final class OwnershipStream extends InputStream {
        final String revision;final InventoryReader inventory;final List<Module> modules;final List<Context> contexts;final BooleanSupplier cancelled;
        final Map<String,Long> counts=new TreeMap<>();long total;Optional<String> cursor=Optional.empty();boolean ended;
        Iterator<InventoryEntry> entries=Collections.emptyIterator();byte[] pending=new byte[0];int offset;
        OwnershipStream(String revision,InventoryReader inventory,List<Module> modules,List<Context> contexts,BooleanSupplier cancelled){this.revision=revision;this.inventory=inventory;this.modules=modules;this.contexts=contexts;this.cancelled=cancelled;}
        private boolean fill()throws IOException{
            while(offset==pending.length){
                check(cancelled);
                if(!entries.hasNext()){
                    if(ended)return false;
                    var page=inventory.page(revision,512,cursor);entries=page.entries().iterator();cursor=page.continuation();ended=cursor.isEmpty();if(!entries.hasNext())continue;
                }
                var entry=entries.next();var ownership=ownership(entry,modules,contexts);counts.merge(ownership.disposition().name(),1L,Math::addExact);total=Math.addExact(total,1);
                pending=(CanonicalJson.write(ownership)+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
            }
            return true;
        }
        @Override public int read()throws IOException{return fill()?Byte.toUnsignedInt(pending[offset++]):-1;}
        @Override public int read(byte[] output,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,output.length);if(length==0)return 0;if(!fill())return -1;int count=Math.min(length,pending.length-offset);System.arraycopy(pending,offset,output,start,count);offset+=count;return count;}
    }
    private static Ownership ownership(InventoryEntry entry,List<Module> modules,List<Context> contexts){
        if(entry.disposition()==InventoryEntry.Disposition.EXCLUDED)return new Ownership(entry.path(),Disposition.EXCLUDED,List.of(),"EXPLICIT_SELECTION");
        if(entry.disposition()!=InventoryEntry.Disposition.ACQUIRED)return new Ownership(entry.path(),Disposition.UNAVAILABLE,List.of(),"ACQUISITION_GAP");
        if(entry.path().utf8().isEmpty())return new Ownership(entry.path(),Disposition.UNAVAILABLE,List.of(),"RAW_PATH_PROVIDER_REQUIRED");
        String path=entry.path().utf8().orElseThrow();
        if(entry.kind()!=InventoryEntry.Kind.FILE)return new Ownership(entry.path(),Disposition.NON_SOURCE,List.of(),"INVENTORIED");
        if(language(path).isEmpty()){
            boolean unknown=entry.population()==InventoryEntry.Population.UNKNOWN||entry.population()==InventoryEntry.Population.SOURCE;
            return new Ownership(entry.path(),unknown?Disposition.UNAVAILABLE:Disposition.NON_SOURCE,List.of(),unknown?"FILE_CLASSIFICATION_REQUIRED":"INVENTORIED");
        }
        var owners=modules.stream().filter(module->within(path,module.directory())).toList();
        int depth=owners.stream().mapToInt(module->module.directory().length()).max().orElse(-1);
        var nearest=owners.stream().filter(module->module.directory().length()==depth).map(Module::id).toList();
        var matching=contexts.stream().filter(context->context.sourceRoots().stream().anyMatch(root->within(path,root)&&
                (nearest.contains(context.module())||modules.stream().filter(module->module.id().equals(context.module())).noneMatch(module->root.equals(module.directory()))))).map(Context::id).toList();
        long moduleCount=contexts.stream().filter(context->matching.contains(context.id())).map(Context::module).distinct().count();
        boolean overlap=contexts.stream().filter(context->matching.contains(context.id())).collect(java.util.stream.Collectors.groupingBy(context->context.module()+"/"+context.variant(),java.util.stream.Collectors.counting())).values().stream().anyMatch(count->count>1);
        return new Ownership(entry.path(),matching.isEmpty()?Disposition.UNOWNED:moduleCount>1||overlap?Disposition.AMBIGUOUS:Disposition.OWNED,matching,matching.isEmpty()?"SOURCE_PLAN_REQUIRED":moduleCount>1||overlap?"OVERLAPPING_SOURCE_OWNERS":"CONTEXT_QUALIFIED");
    }
}
