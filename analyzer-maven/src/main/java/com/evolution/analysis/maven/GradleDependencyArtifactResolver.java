package com.evolution.analysis.maven;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.dependency.*;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.ingestion.UniversalSourceIngestion;
import java.nio.file.Path;
import java.util.*;

/** Passive recovery from exact Gradle declarations and verified Maven metadata.
 * Neither POM mediation nor this evidence order claims Gradle variant resolution. */
public final class GradleDependencyArtifactResolver {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("dependency.gradle-recovery","m8.4-compile-variants-v2");
    public record Problem(String reason,String subject) implements Comparable<Problem> {
        public int compareTo(Problem other){int c=reason.compareTo(other.reason);return c==0?subject.compareTo(other.subject):c;}
    }
    public record Context(UniversalSourceIngestion.SourceSet sourceSet,List<BinaryInput> binaries,
                          List<ModuleIdentity> projects,List<Problem> problems,List<BuildModelResult.PomEvidence> metadata,ContentDigest identity) {
        public Context {binaries=List.copyOf(binaries);projects=List.copyOf(projects);problems=List.copyOf(problems);metadata=List.copyOf(metadata);}
    }
    public record Result(List<Context> contexts,List<DependencyAcquisitionResult> acquisitions,List<Problem> problems) {
        public Result {contexts=List.copyOf(contexts);acquisitions=List.copyOf(acquisitions);problems=List.copyOf(problems);}
    }
    private final Path directory;
    private final DependencyArtifactProvider provider;
    public GradleDependencyArtifactResolver(Path directory){this(directory,new MavenDependencyArtifactProvider(directory));}
    GradleDependencyArtifactResolver(Path directory,DependencyArtifactProvider provider){this.directory=directory.toAbsolutePath().normalize();this.provider=provider;}

    public Result resolve(UniversalBuildModel model,DependencyAcquisitionPolicy policy) {
        var session=new Session(model,policy);var contexts=new ArrayList<Context>();
        for(var module:model.modules())if(Set.of(UniversalBuildModel.Tool.GRADLE_GROOVY,UniversalBuildModel.Tool.GRADLE_KOTLIN).contains(module.tool()))
            for(var set:module.sourcePlan().sourceSets())if(set.kind()!=SourcePlanModel.Kind.CUSTOM)contexts.add(session.context(module,set));
        return new Result(contexts,session.acquisitions,List.copyOf(session.acquisitionProblems));
    }
    private final class Session {
        final UniversalBuildModel model;final DependencyAcquisitionPolicy policy;
        final List<DependencyAcquisitionResult> acquisitions=new ArrayList<>();
        final Set<ArtifactCoordinate> attempted=new HashSet<>();
        final Map<ArtifactCoordinate,DependencyAcquisitionResult.Artifact> artifacts=new HashMap<>();
        final Map<MavenCoordinate,MavenArtifactModelReader.DescriptorOutcome> descriptors=new HashMap<>();
        final Set<Problem> acquisitionProblems=new TreeSet<>();
        long bytes;int requested;
        Session(UniversalBuildModel model,DependencyAcquisitionPolicy policy){this.model=model;this.policy=policy;}
        void acquire(ArtifactCoordinate coordinate) {
            check();if(!attempted.add(coordinate))return;
            if(requested>=policy.maxCoordinates()||bytes>=policy.maxTotalBytes()){acquisitionProblems.add(new Problem("GRADLE_ACQUISITION_WORKER_REQUIRED",coordinate.notation()));return;}
            var result=provider.acquire(DependencyAcquisitionRequest.of(List.of(coordinate),policy.withLimits(1,policy.maxTotalBytes()-bytes)));
            acquisitions.add(result);requested++;bytes+=result.bytesConsumed();
            result.outcomes().forEach(row->row.artifact().ifPresent(value->artifacts.put(coordinate,value)));
            for(var row:result.outcomes())row.failureReason().ifPresent(reason->acquisitionProblems.add(new Problem(reason.name(),coordinate.notation())));
        }
        LocalArtifactCache cache()throws LocalArtifactCache.RootFailure {
            return new LocalArtifactCache(directory,Map.of(),new ClasspathResolutionPolicy(policy.maxCoordinates(),Math.max(policy.maxCoordinates(),1),4_000_000,policy.maxArtifactBytes(),policy.maxTotalBytes(),64),this::acquire);
        }
        MavenArtifactModelReader.DescriptorOutcome descriptor(MavenCoordinate coordinate) {
            return descriptors.computeIfAbsent(coordinate,key->{
                try{return new MavenArtifactModelReader(cache(),new BuildModelPolicy(List.of(),List.of(),Map.of(),4_000_000,Math.max(1,policy.maxCoordinates()),64)).descriptor(key);}
                catch(LocalArtifactCache.RootFailure failure){return new MavenArtifactModelReader.DescriptorOutcome(Optional.empty(),List.of(new MavenArtifactModelReader.Issue(failure.reason,key.notation(),ExactClasspathResult.Requirement.ARTIFACT_POM,List.of())));}
            });
        }
        Context context(UniversalBuildModel.Module module,SourcePlanModel.SourceSetPlan set) {
            var problems=new TreeSet<Problem>();var metadata=new TreeMap<String,BuildModelResult.PomEvidence>();
            var management=new TreeMap<String,Set<MavenCoordinate>>();
            constraints(module,set.kind(),false,management,metadata,problems,new HashSet<>());
            var declarations=new ArrayList<UniversalBuildModel.Dependency>();var projects=new LinkedHashSet<ModuleIdentity>();
            declarations(module,set.kind(),false,declarations,projects,problems,new HashSet<>());
            var queue=new ArrayDeque<Pending>();
            for(var dependency:declarations) {
                dependency.evidence().forEach(e->metadata.put(e.logicalId(),e));
                var selected=coordinate(dependency,management,problems);selected.ifPresent(c->queue.add(new Pending(new ArtifactCoordinate(c,"jar",""),Set.copyOf(dependency.exclusions()),0)));
            }
            var versions=new TreeMap<String,Set<MavenCoordinate>>();var binaries=new LinkedHashMap<ArtifactCoordinate,BinaryInput>();
            var visited=new HashSet<Pending>();int expanded=0;
            while(!queue.isEmpty()) {
                check();var pending=queue.removeFirst();if(!visited.add(new Pending(pending.coordinate(),pending.exclusions(),0)))continue;var artifact=pending.coordinate();
                if(pending.depth()>Math.min(64,policy.maxPasses())||++expanded>policy.maxCoordinates()){problems.add(new Problem("GRADLE_DEPENDENCY_WORKER_REQUIRED",artifact.notation()));continue;}
                String key=artifact.gav().groupId()+":"+artifact.gav().artifactId();if(excluded(pending.exclusions(),key))continue;
                MavenArtifactModelReader.DescriptorOutcome preOutcome=null;
                if(artifact.extension().equals("jar")&&!java.nio.file.Files.exists(directory.resolve(artifact.repositoryPath()))) {
                    preOutcome=descriptor(artifact.gav());
                    if(preOutcome.descriptor().isPresent()&&preOutcome.descriptor().orElseThrow().packaging().equalsIgnoreCase("aar")) {
                        artifact=new ArtifactCoordinate(artifact.gav(),"aar",artifact.classifier());
                    }
                }
                versions.computeIfAbsent(artifact.conflictKey(),ignored->new HashSet<>()).add(artifact.gav());
                acquire(artifact);var verified=artifacts.get(artifact);
                if(verified!=null) {
                    if(artifact.extension().equals("aar")) {
                        var extracted=extractAarClassesJar(artifact);
                        if(extracted.isPresent())binaries.putIfAbsent(artifact,extracted.orElseThrow());
                        else problems.add(new Problem("GRADLE_BINARY_UNAVAILABLE",artifact.notation()));
                    } else {
                        binaries.putIfAbsent(artifact,new BinaryInput(artifact.classpathEntry(verified.contentDigest()),directory.resolve(artifact.repositoryPath())));
                    }
                } else problems.add(new Problem("GRADLE_BINARY_UNAVAILABLE",artifact.notation()));
                var outcome=preOutcome!=null?preOutcome:descriptor(artifact.gav());
                outcome.issues().forEach(i->problems.add(new Problem(i.reason().name(),i.subject())));
                if(outcome.descriptor().isEmpty())continue;
                var descriptor=outcome.descriptor().orElseThrow();descriptor.evidence().forEach(e->metadata.put(e.logicalId(),new BuildModelResult.PomEvidence(e.logicalId(),e.digest())));
                for(var child:descriptor.dependencies()) {
                    // POM-backed API variants expose compile-scoped dependencies.
                    // Runtime implementation details cannot poison a compile context.
                    if(child.optional()||!child.scope().equals("compile"))continue;
                    String childType=child.type().equals("bundle")?"jar":child.type();
                    if(!Set.of("jar","aar").contains(childType)){problems.add(new Problem("GRADLE_TRANSITIVE_VARIANT_REQUIRED",child.groupId()+":"+child.artifactId()));continue;}
                    String childKey=child.groupId()+":"+child.artifactId();
                    if(!fixed(child.version())){problems.add(new Problem("GRADLE_VERSION_SELECTION_REQUIRED",childKey));continue;}
                    var exclusions=new TreeSet<>(pending.exclusions());exclusions.addAll(child.exclusions());
                    MavenCoordinate c;ArtifactCoordinate childArtifact;
                    try{c=new MavenCoordinate(child.groupId(),child.artifactId(),child.version());childArtifact=new ArtifactCoordinate(c,childType,child.classifier());}
                    catch(IllegalArgumentException invalid){problems.add(new Problem("GRADLE_TRANSITIVE_VARIANT_REQUIRED",childKey));continue;}
                    var constraint=management.get(childKey);
                    if(constraint!=null&&(constraint.size()!=1||!constraint.contains(c))){problems.add(new Problem("GRADLE_VERSION_CONFLICT",childKey));versions.computeIfAbsent(childKey+":"+child.classifier()+"@"+childType,ignored->new HashSet<>()).addAll(constraint);}
                    queue.addLast(new Pending(childArtifact,Set.copyOf(exclusions),pending.depth()+1));
                }
            }
            binaries.entrySet().removeIf(row->{if(versions.getOrDefault(row.getKey().conflictKey(),Set.of()).size()<=1)return false;problems.add(new Problem("GRADLE_VERSION_CONFLICT",row.getKey().conflictKey()));return true;});
            // Acquisition failures remain in their exact receipt/ledger; recovery itself
            // always has an effective-context obligation, even with a warm complete cache.
            problems.add(new Problem("GRADLE_EFFECTIVE_CONTEXT_REQUIRED",module.descriptor().path()+"#"+set.name()));
            var sourceSet=new UniversalSourceIngestion.SourceSet(set.module(),set.kind(),set.name());
            var values=List.copyOf(binaries.values());var proof=CanonicalJson.sha256(List.of(PROVIDER,model.identity(),sourceSet,values.stream().map(BinaryInput::entry).toList(),projects,List.copyOf(problems),metadata));
            return new Context(sourceSet,values,List.copyOf(projects),List.copyOf(problems),List.copyOf(metadata.values()),proof);
        }
        void constraints(UniversalBuildModel.Module module,SourcePlanModel.Kind kind,boolean exported,Map<String,Set<MavenCoordinate>> result,
                         Map<String,BuildModelResult.PomEvidence> metadata,Set<Problem> problems,Set<ModuleIdentity> visiting) {
            check();if(!visiting.add(module.descriptor().identity()))return;
            for(var dependency:module.dependencies())if(visible(dependency.configuration(),kind,exported)) {
                if(dependency.projectPath().isPresent()&&!platform(dependency.configuration())) {
                    project(module,dependency.projectPath().orElseThrow(),problems).ifPresent(p->constraints(p,SourcePlanModel.Kind.MAIN,true,result,metadata,problems,visiting));
                    continue;
                }
                if(dependency.configuration().endsWith(".constraint"))dependency.coordinate().ifPresent(c->manage(result,c));
                else if(platform(dependency.configuration())) {
                    dependency.evidence().forEach(e->metadata.put(e.logicalId(),e));
                    if(dependency.notation().equals("SpringBootPlugin.BOM_COORDINATES")&&!bootBom(module,dependency,metadata,problems))continue;
                    if(dependency.projectPath().isPresent())project(module,dependency.projectPath().orElseThrow(),problems).ifPresent(p->constraints(p,SourcePlanModel.Kind.MAIN,false,result,metadata,problems,visiting));
                    else if(dependency.coordinate().isPresent()) {
                        var outcome=descriptor(dependency.coordinate().orElseThrow());outcome.issues().forEach(i->problems.add(new Problem(i.reason().name(),i.subject())));
                        outcome.descriptor().ifPresent(d->{d.evidence().forEach(e->metadata.put(e.logicalId(),new BuildModelResult.PomEvidence(e.logicalId(),e.digest())));for(var entry:d.managedDependencies())if(Set.of("jar","aar","bundle").contains(entry.type())&&entry.classifier().isEmpty()&&fixed(entry.version()))manage(result,new MavenCoordinate(entry.groupId(),entry.artifactId(),entry.version()));});
                    } else problems.add(new Problem("GRADLE_PLATFORM_VALUE_REQUIRED",dependency.notation()));
                }
            }
        }
        Optional<BinaryInput> extractAarClassesJar(ArtifactCoordinate artifact) {
            Path aarPath=directory.resolve(artifact.repositoryPath());
            Path extractedJar=directory.resolve(artifact.repositoryPath()+"-classes.jar");
            try {
                byte[] classesBytes=null;
                try(var zis=new java.util.zip.ZipInputStream(java.nio.file.Files.newInputStream(aarPath))) {
                    for(var entry=zis.getNextEntry();entry!=null;entry=zis.getNextEntry()) {
                        if(!entry.isDirectory()&&entry.getName().equals("classes.jar")) {
                            classesBytes=zis.readNBytes(Math.toIntExact(Math.min(policy.maxArtifactBytes(),100_000_000L)));
                            break;
                        }
                    }
                }
                if(classesBytes==null) {
                    var empty=new java.io.ByteArrayOutputStream();
                    try(var jar=new java.util.jar.JarOutputStream(empty)) {}
                    classesBytes=empty.toByteArray();
                }
                var digest=ContentDigest.sha256(classesBytes);
                if(!java.nio.file.Files.exists(extractedJar)||!ContentDigest.sha256(java.nio.file.Files.readAllBytes(extractedJar)).equals(digest)) {
                    java.nio.file.Files.write(extractedJar,classesBytes);
                }
                return Optional.of(new BinaryInput(artifact.classpathEntry(digest),extractedJar));
            } catch(java.io.IOException|RuntimeException failure) {
                return Optional.empty();
            }
        }
        boolean bootBom(UniversalBuildModel.Module module,UniversalBuildModel.Dependency dependency,Map<String,BuildModelResult.PomEvidence> metadata,Set<Problem> problems) {
            var markers=module.dependencies().stream().filter(d->d.configuration().equals("gradlePlugin")&&d.notation().equals("org.springframework.boot")).flatMap(d->d.coordinate().stream()).distinct().toList();
            boolean valid=false;
            if(markers.size()==1&&dependency.coordinate().isPresent()) {
                var marker=markers.getFirst();var outcome=descriptor(marker);outcome.issues().forEach(i->problems.add(new Problem(i.reason().name(),i.subject())));
                if(outcome.descriptor().isPresent()) {
                    var descriptor=outcome.descriptor().orElseThrow();descriptor.evidence().forEach(e->metadata.put(e.logicalId(),new BuildModelResult.PomEvidence(e.logicalId(),e.digest())));
                    valid=descriptor.dependencies().stream().anyMatch(d->d.groupId().equals("org.springframework.boot")&&d.artifactId().equals("spring-boot-gradle-plugin")&&d.version().equals(marker.version())&&d.type().equals("jar"))
                            &&dependency.coordinate().orElseThrow().equals(new MavenCoordinate("org.springframework.boot","spring-boot-dependencies",marker.version()));
                }
            }
            if(!valid)problems.add(new Problem("GRADLE_PLUGIN_BOM_EVIDENCE_REQUIRED",module.descriptor().path()));return valid;
        }
        void declarations(UniversalBuildModel.Module module,SourcePlanModel.Kind kind,boolean exported,List<UniversalBuildModel.Dependency> result,Set<ModuleIdentity> projects,Set<Problem> problems,Set<ModuleIdentity> visited) {
            if(!visited.add(module.descriptor().identity()))return;
            for(var dependency:module.dependencies())if(visible(dependency.configuration(),kind,exported)&&!platform(dependency.configuration())&&!dependency.configuration().endsWith(".constraint")) {
                if(dependency.projectPath().isPresent())project(module,dependency.projectPath().orElseThrow(),problems).ifPresent(p->{projects.add(p.descriptor().identity());declarations(p,SourcePlanModel.Kind.MAIN,true,result,projects,problems,visited);});
                else result.add(dependency);
            }
        }
        Optional<UniversalBuildModel.Module> project(UniversalBuildModel.Module owner,String path,Set<Problem> problems) {
            Optional<String> settingsId=owner.evidence().stream().map(BuildModelResult.PomEvidence::logicalId).filter(p->p.endsWith("settings.gradle")||p.endsWith("settings.gradle.kts")).findFirst();
            String root=settingsId.map(p->p.contains("/")?p.substring(0,p.lastIndexOf('/')):".").orElse(".");
            String relative=path.replaceFirst("^:","").replace(':','/');String target=root.equals(".")?relative:root+"/"+relative;
            var candidates=model.modules().stream().filter(m->m.descriptor().path().equals(target)).toList();
            if(candidates.size()==1)return Optional.of(candidates.getFirst());
            String leaf=relative.substring(relative.lastIndexOf('/')+1),normalizedLeaf=normalizeProjectName(leaf);
            var remapped=model.modules().stream().filter(m->{
                boolean sameRoot=settingsId.isPresent()
                        ?m.evidence().stream().anyMatch(e->e.logicalId().equals(settingsId.orElseThrow()))
                        :(root.equals(".")||m.descriptor().path().startsWith(root+"/"));
                return sameRoot&&(m.descriptor().displayName().equals(leaf)||normalizeProjectName(m.descriptor().displayName()).equals(normalizedLeaf));
            }).toList();
            if(remapped.size()==1)return Optional.of(remapped.getFirst());
            problems.add(new Problem("GRADLE_PROJECT_CONTEXT_REQUIRED",path));return Optional.empty();
        }
    }
    private static String normalizeProjectName(String name){return name.replace("-","").replace("_","").toLowerCase(Locale.ROOT);}
    private record Pending(ArtifactCoordinate coordinate,Set<String> exclusions,int depth) {}
    private static Optional<MavenCoordinate> coordinate(UniversalBuildModel.Dependency dependency,Map<String,Set<MavenCoordinate>> management,Set<Problem> problems) {
        if(dependency.coordinate().isPresent()) {
            var value=dependency.coordinate().orElseThrow();var constraint=management.get(value.groupId()+":"+value.artifactId());
            if(constraint!=null&&(constraint.size()!=1||!constraint.contains(value))){problems.add(new Problem("GRADLE_VERSION_CONFLICT",dependency.notation()));return Optional.empty();}
            return dependency.coordinate();
        }
        var selected=management.get(dependency.notation());if(selected!=null&&selected.size()==1)return Optional.of(selected.iterator().next());
        problems.add(new Problem(selected==null?"GRADLE_VERSION_SELECTION_REQUIRED":"GRADLE_VERSION_CONFLICT",dependency.notation()));return Optional.empty();
    }
    private static void manage(Map<String,Set<MavenCoordinate>> values,MavenCoordinate coordinate){values.computeIfAbsent(coordinate.groupId()+":"+coordinate.artifactId(),ignored->new HashSet<>()).add(coordinate);}
    private static boolean platform(String configuration){return configuration.endsWith(".platform")||configuration.endsWith(".enforced-platform");}
    private static boolean visible(String configuration,SourcePlanModel.Kind kind,boolean exported) {
        String base=configuration.split("\\.",2)[0];
        boolean isTest=base.startsWith("test")||base.startsWith("androidTest")||base.matches("[a-z][A-Za-z0-9_]*Test(?:Implementation|Api|CompileOnly|CompileOnlyApi|Compile)");
        if(exported)return !isTest&&(Set.of("api","compile","compileOnlyApi").contains(base)||base.matches("[a-z][A-Za-z0-9_]*(?:Api|CompileOnlyApi)"));
        if(!isTest&&(Set.of("implementation","api","compileOnly","compileOnlyApi","compile").contains(base)||base.matches("[a-z][A-Za-z0-9_]*(?:Implementation|Api|CompileOnly|CompileOnlyApi)")))return true;
        return kind==SourcePlanModel.Kind.TEST&&isTest&&(Set.of("testImplementation","testCompileOnly","testCompile","androidTestImplementation","androidTestCompileOnly").contains(base)
                ||base.matches("[a-z][A-Za-z0-9_]*Test(?:Implementation|Api|CompileOnly|CompileOnlyApi|Compile)"));
    }
    private static boolean excluded(Set<String> exclusions,String key){String[] parts=key.split(":",2);return exclusions.contains(key)||exclusions.contains("*:*")||exclusions.contains(parts[0]+":*")||exclusions.contains("*:"+parts[1]);}
    private static boolean fixed(String version){String upper=version.toUpperCase(Locale.ROOT);return version.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")&&!upper.endsWith("-SNAPSHOT")&&!Set.of("LATEST","RELEASE").contains(upper)&&!version.startsWith("latest.");}
    private static void check(){if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException();}
}
