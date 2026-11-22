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
import java.util.function.Predicate;

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
        return plan(revision,inventory,content,policy,cancelled,path->false);
    }
    public ReadinessPlan plan(String revision, InventoryReader inventory, RepositoryContentStore content, Policy policy,
                              BooleanSupplier cancelled, Predicate<String> installedDependency)throws IOException{
        var manifest=inventory.manifest(revision).orElseThrow(()->new IOException("SEALED_INVENTORY_REQUIRED"));
        var descriptors=new TreeMap<String,ReadinessDiscovery.Descriptor>();var issues=new ArrayList<Requirement>();
        var sourceAnchors=new TreeMap<String,Set<String>>();var languageHints=new TreeMap<String,Set<String>>();
        var cachedSourceHints=new HashMap<String,SourceHint>();
        Optional<String> cursor=Optional.empty();
        do{
            check(cancelled);var page=inventory.page(revision,512,cursor);cursor=page.continuation();
            for(var entry:page.entries()){
                if(entry.disposition()!=InventoryEntry.Disposition.ACQUIRED||entry.kind()!=InventoryEntry.Kind.FILE)continue;
                if(entry.path().utf8().isEmpty())continue;
                String path=entry.path().utf8().orElseThrow();
                if(installedDependency.test(path))continue;
                String readinessReason=path.endsWith("/module-info.java")||path.equals("module-info.java")?"JPMS_MANIFEST_REQUIRED":path.endsWith(".jsp")?"JSP_GENERATION_REQUIRED":path.endsWith(".fxml")?"JAVAFX_PLATFORM_REQUIRED":path.endsWith(".aj")?"WOVEN_BYTECODE_REQUIRED":filename(path).equals("AndroidManifest.xml")?"ANDROID_SDK_REQUIRED":null;
                if(readinessReason!=null)issues.add(requirement(Kind.PLATFORM,path,readinessReason,List.of(),List.of(new Evidence(path,entry.content().orElseThrow().digest()))));
                var sourceHint=sourceHint(entry,content);
                cachedSourceHints.put(path,sourceHint);
                if(sourceHint.unavailable())issues.add(requirement(Kind.DISCOVERY,path,"SOURCE_CLASSIFICATION_INPUT_UNAVAILABLE",List.of(),List.of(new Evidence(path,entry.content().orElseThrow().digest()))));
                if(sourceHint.language().isPresent())sourceAnchors.computeIfAbsent(anchor(path),key->new TreeSet<>()).add(sourceSet(path));
                sourceHint.language().ifPresent(language->languageHints.computeIfAbsent(anchor(path),key->new TreeSet<>()).add(language));
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
        var ownershipIndex=new OwnershipIndex(modules.values(),contexts.values());
        for(var entry:sourceAnchors.entrySet()){
            String directory=entry.getKey();
            String sourcePrefix=directory.equals(".")?"":directory+"/";
            if(entry.getValue().stream().allMatch(set->set.equals("unclassified")?ownershipIndex.hasRoot(directory):ownershipIndex.hasRootUnder(sourcePrefix+"src/"+set)))continue;
            var owners=ownershipIndex.nearest(directory);
            if(owners.isEmpty()){
                String id="PLAIN:"+directory;
                var sourceEvidence=List.of(new Evidence("inventory",manifest.inventory()));
                roots.put(id,new Root(id,directory,Family.PLAIN,sourceEvidence,List.of()));
                var module=new Module(id,id,directory,directory,List.of(),List.of(),sourceEvidence);
                modules.put(id,module);
                ownershipIndex.add(module);
                for(String set:entry.getValue()){
                    String prefix=directory.equals(".")?"":directory+"/";
                    String sourceRoot=set.equals("unclassified")?directory:prefix+"src/"+set;
                    var context=context(id,id,set,"baseline",List.of(sourceRoot),Map.of("compiler","UNSPECIFIED","platform","UNSPECIFIED","encoding","UNSPECIFIED"),sourceEvidence,manifest,true);
                    contexts.put(context.id(),context);
                    ownershipIndex.add(context);
                }
                unknown.add(id+":native-source-plan");
            }else{
                for(var module:owners)for(String set:entry.getValue()){
                    boolean covered=ownershipIndex.byModule.getOrDefault(module.id(),List.of()).stream().anyMatch(context->context.sourceSet().equals(set)||
                            !set.equals("unclassified")&&context.sourceRoots().stream().anyMatch(root->within(root,sourcePrefix+"src/"+set)));
                    if(!covered){
                        String prefix=directory.equals(".")?"":directory+"/";
                        var context=context(module.id(),module.root(),set,"discovered-layout",List.of(set.equals("unclassified")?directory:prefix+"src/"+set),Map.of("sourcePlan","UNVALIDATED"),module.evidence(),manifest,true);
                        contexts.put(context.id(),context);unknown.add(module.id()+":source-set:"+set);
                        ownershipIndex.add(context);
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
        var inferredLanguages=new HashMap<String,Set<String>>();
        languageHints.forEach((directory,values)->ownershipIndex.nearest(directory).forEach(module->
                inferredLanguages.computeIfAbsent(module.id(),unused->new TreeSet<>()).addAll(values)));
        for(var module:List.copyOf(modules.values())){
            var languages=new TreeSet<>(module.languages());
            languages.addAll(inferredLanguages.getOrDefault(module.id(),Set.of()));
            modules.put(module.id(),new Module(module.id(),module.root(),module.directory(),module.target(),List.copyOf(languages),module.dependencies(),module.evidence()));
        }
        var selectedByRoot=new HashMap<String,List<Context>>();
        for(var context:contexts.values())if(selected.contains(context.id()))
            selectedByRoot.computeIfAbsent(context.root(),unused->new ArrayList<>()).add(context);
        var descriptorsByRoot=new HashMap<String,List<ReadinessDiscovery.Descriptor>>();
        roots.values().forEach(root->descriptorsByRoot.computeIfAbsent(root.directory(),unused->new ArrayList<>()));
        for(var descriptor:descriptors.values())for(String path=directory(descriptor.path());path!=null;path=parent(path)){
            var visible=descriptorsByRoot.get(path);
            if(visible!=null)visible.add(descriptor);
        }
        var recipes=new ArrayList<Recipe>();
        for(var root:roots.values()){
            var chosen=selectedByRoot.getOrDefault(root.id(),List.of());
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
            var seenInputs=new HashSet<>(recipeInputs);
            for(var descriptor:descriptorsByRoot.get(root.directory())){
                var input=new Evidence(descriptor.path(),descriptor.content().digest());if(seenInputs.add(input))recipeInputs.add(input);
                String name=filename(descriptor.path());
                if(name.contains("wrapper")||name.equals("rust-toolchain.toml")||name.equals(".java-version")||name.equals("global.json")||name.equals(".tool-versions"))pins.put(descriptor.path(),descriptor.content().digest().value());
            }
            recipes.add(new Recipe("recipe:"+root.id(),root.family(),root.id(),chosen.stream().map(Context::id).toList(),recipeNeeds,
                    List.of("CAPTURE_NATIVE_MODEL","RESOLVE_EXACT_DEPENDENCIES","GENERATE","COMPILE_SELECTED_SOURCE_SETS","NATIVE_SEMANTIC_VALIDATE"),recipeInputs,
                    List.of("ordered-compiler-manifests","generated-output-lineage","per-target-results","native-model"),pins,"ISOLATED_ROOT","BROKER_ONLY","CONFIGURED_WORKER_REQUIRED",
                    List.of("guest-overlay","broker-cache-downloads"),List.of("exact-input-closure","independent-protected-observation","context-and-toolchain-match","output-digests-and-paths")));
        }
        OwnershipSummary summary;
        try(var stream=new OwnershipStream(revision,inventory,content,ownershipIndex,cachedSourceHints,cancelled,installedDependency)){
            var ledger=content.put(stream,cancelled);summary=new OwnershipSummary(stream.total,stream.counts,ledger);
        }
        if(summary.counts().getOrDefault("DEPENDENCY_INPUT",0L)>0)
            issues.add(requirement(Kind.DISCOVERY,manifest.inventory().value(),"INSTALLED_DEPENDENCY_SEMANTICS_DEFERRED",
                    List.of(),List.of(new Evidence("ownership-ledger",summary.ledger().digest()))));
        if(summary.counts().getOrDefault("UNOWNED",0L)>0||summary.counts().getOrDefault("AMBIGUOUS",0L)>0||summary.counts().getOrDefault("UNAVAILABLE",0L)>0)
            issues.add(requirement(Kind.OWNERSHIP,manifest.inventory().value(),"SOURCE_OWNERSHIP_UNRESOLVED",List.of(),List.of(new Evidence("ownership-ledger",summary.ledger().digest()))));
        var distinctIssues = new ArrayList<Requirement>();
        var seenReqIds = new HashSet<String>();
        for (var issue : issues) {
            if (seenReqIds.add(issue.id())) {
                distinctIssues.add(issue);
            }
        }
        distinctIssues.sort(Comparator.comparing(Requirement::id));
        return new ReadinessPlan(ReadinessPlan.SCHEMA,manifest,selection,List.copyOf(roots.values()),List.copyOf(modules.values()),List.copyOf(contexts.values()),
                distinctIssues,recipes,summary);
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
    public static boolean descriptor(String path){String name=filename(path);return com.evolution.analysis.polyglot.LanguageDiscovery.buildDescriptor(name)||Set.of("pom.xml","build.gradle","build.gradle.kts","settings.gradle","settings.gradle.kts","gradle-wrapper.properties","maven-wrapper.properties","gradle.properties","maven.config","jvm.config","extensions.xml","toolchains.xml",".classpath",".project","build.xml","WORKSPACE","WORKSPACE.bazel","MODULE.bazel","BUILD","BUILD.bazel","build.sbt","package.json","package-lock.json","pnpm-workspace.yaml","pnpm-lock.yaml","yarn.lock","pyproject.toml","setup.py","requirements.txt","Cargo.toml","Cargo.lock","go.mod","go.sum","composer.json","composer.lock","Gemfile","Gemfile.lock","pubspec.yaml","pubspec.lock","Package.swift","CMakeLists.txt","Makefile","rust-toolchain.toml","global.json",".java-version",".tool-versions","libs.versions.toml").contains(name)||name.endsWith(".csproj")||name.endsWith(".sln")||name.endsWith(".iml");}
    /** Legacy readiness hints only; semantic selection uses evidence-qualified LanguageDiscovery. */
    public static Optional<String> language(String path){
        var hints=com.evolution.analysis.polyglot.LanguageDiscovery.hints(path);
        // Preserve the old readiness-only .h hint for historical plans. It is never a semantic proof.
        if(filename(path).endsWith(".h"))return Optional.of("c");
        if(hints.stream().anyMatch(Set.of("json","yaml","toml","xml","hcl","html","css","scss","sass","less","notebook")::contains))return Optional.empty();
        return hints.size()==1?Optional.of(hints.getFirst()):Optional.empty();
    }
    private static final Set<String> NON_SOURCE_BINARY_EXTENSIONS = Set.of(
            "jar","war","ear","apk","aab","zip","tar","gz","tgz","xz","zst","7z","rar",
            "so","dll","dylib","exe","bin","o","a","class","jmod","pyc","wasm",
            "png","jpg","jpeg","gif","webp","ico","bmp","pdf",
            "mp3","mp4","mov","avi","mkv","wav","ogg",
            "woff","woff2","ttf","otf","eot","sqlite","db","pack");
    public static boolean isNonSourceBinaryExtension(String path) {
        String name = filename(path);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && NON_SOURCE_BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
    private record SourceHint(Optional<String> language,boolean unavailable){}
    /** Preserve legacy filename hints; add captured shebang evidence for names
     * that have no recognized suffix. This discovers inputs, never executes them. */
    private static SourceHint sourceHint(InventoryEntry entry,RepositoryContentStore content){
        var path=entry.path().utf8();if(path.isEmpty())return new SourceHint(Optional.empty(),true);
        String p=path.orElseThrow();
        var legacy=language(p);
        if(legacy.isPresent()||entry.population()==InventoryEntry.Population.DOCUMENTATION||!com.evolution.analysis.polyglot.LanguageDiscovery.hints(p).isEmpty())return new SourceHint(legacy,false);
        try{
            var bytes=entry.content().orElseThrow();
            boolean mustFullVerify = entry.population() == InventoryEntry.Population.SOURCE
                    || entry.population() == InventoryEntry.Population.UNKNOWN
                    || bytes.size() <= 65_536
                    || !isNonSourceBinaryExtension(p);
            if(mustFullVerify){
                if(!content.verify(bytes))return new SourceHint(Optional.empty(),true);
                try(var prefix=content.open(bytes)){return new SourceHint(com.evolution.analysis.polyglot.LanguageDiscovery.detect(p,prefix.readNBytes(8192),Optional.empty()).language(),false);}
            } else {
                int len=(int)Math.min(bytes.size(),8192L);
                try(var prefix=content.range(bytes,0,len)){
                    byte[] head=prefix.readNBytes(len);
                    if(head.length!=len)return new SourceHint(Optional.empty(),true);
                    return new SourceHint(com.evolution.analysis.polyglot.LanguageDiscovery.detect(p,head,Optional.empty()).language(),false);
                }
            }
        }
        catch(IOException unavailable){return new SourceHint(Optional.empty(),true);}
    }
    private static String anchor(String path){String rooted="/"+path;int src=rooted.indexOf("/src/");return src<0?directory(path):src==0?".":rooted.substring(1,src);}
    private static String sourceSet(String path){String rooted="/"+path;int src=rooted.indexOf("/src/");if(src<0)return "unclassified";String rest=rooted.substring(src+5);int slash=rest.indexOf('/');return slash<0?"unclassified":rest.substring(0,slash);}
    public static String directory(String path){int slash=path.lastIndexOf('/');return slash<0?".":path.substring(0,slash);}
    public static String filename(String path){return path.substring(path.lastIndexOf('/')+1);}
    public static boolean within(String path,String directory){return directory.equals(".")||path.equals(directory)||path.startsWith(directory+"/");}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("READINESS_CANCELLED");}
    private static String parent(String path){
        return path.equals(".") ? null : directory(path);
    }
    private static final class OwnershipIndex {
        final Map<String,List<Module>> byDirectory=new HashMap<>();
        final Map<String,Module> byId=new HashMap<>();
        final Map<String,List<Context>> byModule=new HashMap<>();
        final NavigableMap<String,List<Context>> bySourceRoot=new TreeMap<>();
        OwnershipIndex(Collection<Module> modules,Collection<Context> contexts){
            modules.forEach(this::add);contexts.forEach(this::add);
        }
        void add(Module module){
            byId.put(module.id(),module);
            byDirectory.computeIfAbsent(module.directory(),unused->new ArrayList<>()).add(module);
        }
        void add(Context context){
            byModule.computeIfAbsent(context.module(),unused->new ArrayList<>()).add(context);
            context.sourceRoots().forEach(root->bySourceRoot.computeIfAbsent(root,unused->new ArrayList<>()).add(context));
        }
        boolean hasRoot(String directory){return bySourceRoot.containsKey(directory);}
        boolean hasRootUnder(String directory){
            String child=bySourceRoot.ceilingKey(directory+"/");
            return hasRoot(directory)||child!=null&&child.startsWith(directory+"/");
        }
        List<Module> nearest(String path){
            for(String candidate=path;candidate!=null;candidate=parent(candidate)){
                var modules=byDirectory.get(candidate);
                if(modules!=null)return modules;
            }
            return List.of();
        }
        Collection<Context> matching(String path){
            var nearest=new HashSet<String>();
            nearest(path).forEach(module->nearest.add(module.id()));
            var contexts=new TreeMap<String,Context>();
            for(String root=path;root!=null;root=parent(root)){
                for(var context:bySourceRoot.getOrDefault(root,List.of())){
                    var owner=byId.get(context.module());
                    if(nearest.contains(context.module())||owner==null||!root.equals(owner.directory()))
                        contexts.put(context.id(),context);
                }
            }
            return contexts.values();
        }
    }
    private static final class OwnershipStream extends InputStream {
        final String revision;final InventoryReader inventory;final RepositoryContentStore content;final OwnershipIndex index;final Map<String,SourceHint> cachedHints;final BooleanSupplier cancelled;final Predicate<String> installedDependency;
        final Map<String,Long> counts=new TreeMap<>();long total;Optional<String> cursor=Optional.empty();boolean ended;
        Iterator<InventoryEntry> entries=Collections.emptyIterator();byte[] pending=new byte[0];int offset;
        OwnershipStream(String revision,InventoryReader inventory,RepositoryContentStore content,OwnershipIndex index,
                        Map<String,SourceHint> cachedHints,BooleanSupplier cancelled,Predicate<String> installedDependency){
            this.revision=revision;this.inventory=inventory;this.content=content;this.index=index;
            this.cachedHints=cachedHints;this.cancelled=cancelled;this.installedDependency=installedDependency;
        }
        private boolean fill()throws IOException{
            while(offset==pending.length){
                check(cancelled);
                if(!entries.hasNext()){
                    if(ended)return false;
                    var page=inventory.page(revision,512,cursor);entries=page.entries().iterator();cursor=page.continuation();ended=cursor.isEmpty();if(!entries.hasNext())continue;
                }
                var entry=entries.next();var ownership=ownership(entry,content,index,cachedHints,installedDependency);counts.merge(ownership.disposition().name(),1L,Math::addExact);total=Math.addExact(total,1);
                pending=(CanonicalJson.write(ownership)+"\n").getBytes(StandardCharsets.UTF_8);offset=0;
            }
            return true;
        }
        @Override public int read()throws IOException{return fill()?Byte.toUnsignedInt(pending[offset++]):-1;}
        @Override public int read(byte[] output,int start,int length)throws IOException{Objects.checkFromIndexSize(start,length,output.length);if(length==0)return 0;if(!fill())return -1;int count=Math.min(length,pending.length-offset);System.arraycopy(pending,offset,output,start,count);offset+=count;return count;}
    }
    private static Ownership ownership(InventoryEntry entry,RepositoryContentStore content,OwnershipIndex index,
                                       Map<String,SourceHint> cachedHints,Predicate<String> installedDependency){
        if(entry.disposition()==InventoryEntry.Disposition.EXCLUDED)return new Ownership(entry.path(),Disposition.EXCLUDED,List.of(),"EXPLICIT_SELECTION");
        if(entry.disposition()!=InventoryEntry.Disposition.ACQUIRED)return new Ownership(entry.path(),Disposition.UNAVAILABLE,List.of(),"ACQUISITION_GAP");
        if(entry.path().utf8().isEmpty())return new Ownership(entry.path(),Disposition.UNAVAILABLE,List.of(),"RAW_PATH_PROVIDER_REQUIRED");
        String path=entry.path().utf8().orElseThrow();
        if(entry.kind()!=InventoryEntry.Kind.FILE)return new Ownership(entry.path(),Disposition.NON_SOURCE,List.of(),"INVENTORIED");
        if(installedDependency.test(path))return new Ownership(entry.path(),Disposition.DEPENDENCY_INPUT,List.of(),"INSTALLED_DEPENDENCY_SEMANTICS_DEFERRED");
        var hint=cachedHints.get(path);
        if(hint==null)hint=sourceHint(entry,content);
        if(hint.unavailable())return new Ownership(entry.path(),Disposition.UNAVAILABLE,List.of(),"SOURCE_CLASSIFICATION_INPUT_UNAVAILABLE");
        if(hint.language().isEmpty()){
            boolean unknown=entry.population()==InventoryEntry.Population.UNKNOWN||entry.population()==InventoryEntry.Population.SOURCE;
            return new Ownership(entry.path(),unknown?Disposition.UNAVAILABLE:Disposition.NON_SOURCE,List.of(),unknown?"FILE_CLASSIFICATION_REQUIRED":"INVENTORIED");
        }
        var contexts=index.matching(path);
        var matching=contexts.stream().map(Context::id).toList();
        long moduleCount=contexts.stream().map(Context::module).distinct().count();
        boolean overlap=contexts.stream().map(context->context.module()+"/"+context.variant()).distinct().count()<contexts.size();
        return new Ownership(entry.path(),matching.isEmpty()?Disposition.UNOWNED:moduleCount>1||overlap?Disposition.AMBIGUOUS:Disposition.OWNED,matching,matching.isEmpty()?"SOURCE_PLAN_REQUIRED":moduleCount>1||overlap?"OVERLAPPING_SOURCE_OWNERS":"CONTEXT_QUALIFIED");
    }
}
