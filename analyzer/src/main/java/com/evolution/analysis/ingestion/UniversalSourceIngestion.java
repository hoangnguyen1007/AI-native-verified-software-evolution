package com.evolution.analysis.ingestion;

import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.spring.condition.ConfigDataIngestion;
import com.evolution.analysis.spring.condition.ConditionEvidence;
import com.evolution.analysis.spring.universal.AutoConfigurationMetadata;
import java.util.*;
import static com.evolution.analysis.ingestion.IngestionEvidence.Reason.*;

/** Connects declarative plans to the existing neutral frontend and configuration ports.
 * Resolution is an explicit exact input; a Gradle dependency declaration is never treated as a JAR. */
public final class UniversalSourceIngestion {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("source.universal-ingestion","m4uv2.1-v2");
    public record SourceSet(ModuleIdentity module,SourcePlanModel.Kind kind,String name) {
        public SourceSet { Objects.requireNonNull(module); Objects.requireNonNull(kind);
            name=ContractChecks.token(name,"source-set name"); }
        public SourceSet(ModuleIdentity module,SourcePlanModel.Kind kind) {
            this(module,kind,kind==SourcePlanModel.Kind.MAIN?"main":
                    kind==SourcePlanModel.Kind.TEST?"test":"");
        }
    }
    public record Resolution(PlatformInput platform,List<BinaryInput> binaries,ContentDigest exactClasspathEvidence) {
        public Resolution { Objects.requireNonNull(platform);binaries=List.copyOf(binaries);Objects.requireNonNull(exactClasspathEvidence); }
    }
    public enum Status { OWNED, UNOWNED, OVERLAPPING, INPUT_UNAVAILABLE, CLASSIFICATION_CONFLICT }
    public record SourceRow(String path,Status status,List<SourceSet> claims) {public SourceRow{claims=List.copyOf(claims);}}
    public record Outcome(SourceSet sourceSet,Optional<FrontendRequest> request,ConfigDataIngestion.Result configuration,
                          List<AutoConfigurationMetadata.Resource> metadata) {
        public Outcome {metadata=List.copyOf(metadata);}
        public Outcome(SourceSet set,Optional<FrontendRequest> request,ConfigDataIngestion.Result configuration){this(set,request,configuration,List.of());}
    }
    public record Result(ContentDigest inputIdentity,List<SourceRow> sources,List<Outcome> outcomes,
                         List<IngestionEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {sources=List.copyOf(sources);outcomes=List.copyOf(outcomes);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(List.of(inputIdentity,sources,
                outcomes.stream().map(o->List.of(o.sourceSet(),o.request().map(r->r.manifest().identity()),o.configuration().identity(),o.metadata().stream().map(AutoConfigurationMetadata.Resource::identity).toList())).toList(),issues,gaps));}
    }
    public Result assemble(RepositoryInputs inputs,UniversalBuildModel build,Map<SourceSet,Resolution> resolutions,
                           ManifestComponent analyzer,ManifestComponent rules,ManifestComponent schema) {
        var descriptors=build.modules().stream().map(UniversalBuildModel.Module::descriptor).toList();
        if(!build.repositoryInputIdentity().equals(inputs.identity()))throw new IllegalArgumentException("Build and source inputs differ");
        if(descriptors.stream().anyMatch(m->!m.repository().equals(inputs.snapshot().repository())))throw new IllegalArgumentException("Build belongs to another repository");
        var resolutionKeys=resolutions.entrySet().stream().map(e->Map.of("set",e.getKey(),"platform",e.getValue().platform().entry(),
                "classpath",e.getValue().binaries().stream().map(BinaryInput::entry).toList(),"evidence",e.getValue().exactClasspathEvidence())).sorted(Comparator.comparing(IngestionEvidence::digest)).toList();
        var identity=IngestionEvidence.digest(List.of(PROVIDER,inputs.identity(),build.identity(),resolutionKeys,analyzer,rules,schema));
        var plans=build.modules().stream().flatMap(m->m.sourcePlan().sourceSets().stream()).toList();
        var issues=new ArrayList<IngestionEvidence.Issue>();var rows=new ArrayList<SourceRow>();
        Map<SourceSet,List<SourceInput>> owned=new HashMap<>();Set<SourceSet> incomplete=new HashSet<>();
        for(var file:inputs.snapshot().files())if(file.path().endsWith(".java")) {
            var claims=plans.stream().filter(p->p.sourceRoots().stream().flatMap(r->r.value().stream()).anyMatch(root->root.equals(".")||file.path().startsWith(root+"/")))
                    .map(p->new SourceSet(p.module(),p.kind(),p.name())).distinct().toList();
            var original=inputs.files().get(file.path());
            var claimedPlan=claims.size()==1?plans.stream().filter(p->p.module().equals(claims.getFirst().module())
                    &&p.kind()==claims.getFirst().kind()&&p.name().equals(claims.getFirst().name()))
                    .findFirst():Optional.<SourcePlanModel.SourceSetPlan>empty();
            boolean classificationConflict=claims.size()==1&&original!=null
                    && (original.document().classification()==SourceClassification.GENERATED_MAIN&&claimedPlan.orElseThrow().semanticRole()!=SourceClassification.MAIN
                    || original.document().classification()==SourceClassification.GENERATED_TEST&&claimedPlan.orElseThrow().semanticRole()!=SourceClassification.TEST);
            Status status=claims.isEmpty()?Status.UNOWNED:claims.size()>1?Status.OVERLAPPING
                    :original==null?Status.INPUT_UNAVAILABLE:classificationConflict?Status.CLASSIFICATION_CONFLICT:Status.OWNED;
            rows.add(new SourceRow(file.path(),status,claims));
            if(status!=Status.OWNED) {
                incomplete.addAll(claims);issues.add(new IngestionEvidence.Issue(status==Status.INPUT_UNAVAILABLE?INPUT_UNAVAILABLE
                        :status==Status.OVERLAPPING||status==Status.CLASSIFICATION_CONFLICT?AMBIGUOUS_INPUT:CUSTOM_SOURCE_LAYOUT,
                        file.path(),List.of(file.contentDigest())));continue;
            }
            var claim=claims.getFirst();var module=descriptors.stream().filter(m->m.identity().equals(claim.module())).findFirst().orElseThrow();
            var classification=original.document().classification();
            if(classification!=SourceClassification.GENERATED_MAIN&&classification!=SourceClassification.GENERATED_TEST)
                classification=claimedPlan.orElseThrow().semanticRole();
            var document=SourceDocument.create(inputs.snapshot().repository(),module,file.path(),file.contentDigest(),classification);
            owned.computeIfAbsent(claim,k->new ArrayList<>()).add(new SourceInput(document,original.bytes(),original.decoding()));
        }
        var documents=owned.values().stream().flatMap(Collection::stream).map(SourceInput::document).toList();
        var snapshot=RepositorySnapshot.create(inputs.snapshot().repository(),inputs.snapshot().revision(),inputs.snapshot().dirty(),inputs.snapshot().files(),documents);
        var outcomes=new ArrayList<Outcome>();
        for(var plan:plans) {
            var key=new SourceSet(plan.module(),plan.kind(),plan.name());var resolution=resolutions.get(key);Optional<FrontendRequest> request=Optional.empty();
            var resources=new ArrayList<SourcePlanModel.Setting>();
            if(plan.semanticRole()==SourceClassification.TEST)plans.stream().filter(p->p.module().equals(plan.module())&&p.kind()==SourcePlanModel.Kind.MAIN).forEach(p->resources.addAll(p.resourceRoots()));
            resources.addAll(plan.resourceRoots());
            var locations=resources.stream().flatMap(r->r.value().stream()).flatMap(p->java.util.stream.Stream.of(p,p.equals(".")?"config":p+"/config")).distinct().toList();
            var defaults=ConfigDataIngestion.Policy.defaults();var config=new ConfigDataIngestion().ingest(inputs,new ConfigDataIngestion.Policy(locations,List.of(),Map.of(),defaults.maxDocuments(),defaults.maxCharacters(),defaults.maxDepth(),defaults.maxProperties()));
            if(!owned.getOrDefault(key,List.of()).isEmpty()) {
                if(resolution==null)issues.add(new IngestionEvidence.Issue(CLASSPATH_RESOLUTION_REQUIRED,key.toString(),List.of(identity)));
                else if(buildClosureUnknown(build,inputs,key)||incomplete.contains(key)||plan.syntaxLevel().value().isEmpty()||plan.platformRelease().value().isEmpty()||plan.encoding().value().isEmpty()
                        ||plan.gaps().stream().anyMatch(g->g!=SourcePlanModel.Gap.GENERATED_SOURCES_NOT_ACQUIRED))
                    issues.add(new IngestionEvidence.Issue(SOURCE_INCOMPLETE,key.toString(),List.of(identity)));
                else try {
                    int level=Integer.parseInt(plan.syntaxLevel().value().orElseThrow()),release=Integer.parseInt(plan.platformRelease().value().orElseThrow());
                    if(resolution.platform().release()!=release)throw new IllegalArgumentException();
                    var sourceInputs=owned.get(key).stream().sorted(Comparator.comparing(s->s.document().path())).toList();
                    if(sourceInputs.stream().anyMatch(s->!s.decoding().charset().equalsIgnoreCase(plan.encoding().value().orElseThrow())))throw new IllegalArgumentException();
                    var frontendPlan=new FrontendPlan(Optional.of(level),plan.bytecodeTarget().value().map(Integer::valueOf),false,resolution.exactClasspathEvidence(),inputs.identity());
                    var set=plan.semanticRole();
                    var options=new TreeMap<>(FrontendRequest.options(frontendPlan,plan.module(),set,sourceInputs.stream().map(SourceInput::document).toList(),resolution.platform()));
                    options.put("ingestion.build-model",build.identity().value());options.put("ingestion.source-input",identity.value());
                    options.put("ingestion.source-set-name",plan.name());
                    var classpath=new ArrayList<ClasspathEntry>();classpath.add(resolution.platform().entry());resolution.binaries().forEach(b->classpath.add(b.entry()));
                    var manifest=AnalysisManifest.create(new VersionedIdentifier("analysis.manifest","1"),snapshot,descriptors,classpath,
                            AnalysisConfiguration.create(new VersionedIdentifier("analysis.configuration","1"),options),analyzer,rules,schema);
                    request=Optional.of(new FrontendRequest(manifest,plan.module(),set,frontendPlan,sourceInputs,resolution.platform(),resolution.binaries()));
                }catch(IllegalArgumentException failure){issues.add(new IngestionEvidence.Issue(SOURCE_INCOMPLETE,key.toString(),List.of(identity)));}
            }
            var metadata=new ArrayList<AutoConfigurationMetadata.Resource>();
            for(String root:resources.stream().flatMap(r->r.value().stream()).distinct().toList())for(String name:List.of("META-INF/spring.factories","META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                String path=root.equals(".")?name:root+"/"+name;var file=inputs.files().get(path);
                if(file!=null)metadata.add(new AutoConfigurationMetadata.Resource(path,file.bytes(),new ConditionEvidence.Source(file.document().identity(),file.document().contentDigest(),Optional.empty(),0)));
                else if(inputs.snapshot().files().stream().anyMatch(f->f.path().equals(path)))issues.add(new IngestionEvidence.Issue(INPUT_UNAVAILABLE,path,List.of(identity)));
            }
            outcomes.add(new Outcome(key,request,config,metadata));
        }
        var sorted=issues.stream().distinct().sorted(Comparator.comparing(IngestionEvidence.Issue::identity)).toList();
        var gaps=new TreeSet<>(build.gaps());outcomes.forEach(o->gaps.addAll(o.configuration().gaps()));gaps.addAll(IngestionEvidence.gaps(snapshot.identity(),PROVIDER,identity,sorted));
        return new Result(identity,rows,outcomes,sorted,List.copyOf(gaps));
    }

    private static boolean buildClosureUnknown(UniversalBuildModel build,RepositoryInputs inputs,SourceSet sourceSet) {
        return build.issues().stream().filter(i->Set.of(INPUT_UNAVAILABLE,INPUT_LIMIT,MALFORMED_INPUT,
                UNSAFE_PATH,AMBIGUOUS_INPUT,DYNAMIC_BUILD_LOGIC,CUSTOM_SOURCE_LAYOUT).contains(i.reason()))
                .anyMatch(i->affects(i,build,inputs,sourceSet.module()));
    }

    /** A failed standalone Gradle build root has a local footprint; unknown or shared roots remain broad. */
    private static boolean affects(IngestionEvidence.Issue issue,UniversalBuildModel build,
                                   RepositoryInputs inputs,ModuleIdentity target) {
        String subject=issue.subject().split("#",2)[0];
        var owner=build.modules().stream().filter(m->m.buildFile().filter(subject::equals).isPresent()).findFirst();
        if(owner.isEmpty()||!Set.of(UniversalBuildModel.Tool.GRADLE_GROOVY,
                UniversalBuildModel.Tool.GRADLE_KOTLIN).contains(owner.orElseThrow().tool()))return true;
        var failed=owner.orElseThrow();
        if(failed.descriptor().identity().equals(target))return true;
        String failedPath=failed.descriptor().path();
        String targetPath=build.modules().stream().filter(m->m.descriptor().identity().equals(target))
                .map(m->m.descriptor().path()).findFirst().orElse(".");
        if(under(failedPath,targetPath)||under(targetPath,failedPath))return true;
        // A settings script may configure every included project. Without a proven partition, do not narrow.
        boolean sharedSettings=inputs.snapshot().files().stream().map(f->f.path())
                .filter(p->p.equals("settings.gradle")||p.equals("settings.gradle.kts")
                        ||p.endsWith("/settings.gradle")||p.endsWith("/settings.gradle.kts"))
                .anyMatch(p->{String root=p.contains("/")?p.substring(0,p.lastIndexOf('/')):".";
                    return under(root,failedPath)&&build.modules().stream().anyMatch(m->m.descriptor().identity().equals(target)&&under(root,m.descriptor().path()));});
        if(sharedSettings)return true;
        String project=":"+failedPath.replace('/',':');
        // Follow explicit project dependencies, including transitive consumers of the failed root.
        var tainted=new HashSet<ModuleIdentity>();tainted.add(failed.descriptor().identity());
        boolean changed;
        do {
            changed=false;
            for(var module:build.modules())if(!tainted.contains(module.descriptor().identity())) {
                boolean depends=module.dependencies().stream().flatMap(d->d.projectPath().stream())
                        .anyMatch(p->p.equals(project)||build.modules().stream()
                                .filter(m->tainted.contains(m.descriptor().identity()))
                                .anyMatch(m->p.equals(":"+m.descriptor().path().replace('/',':'))));
                if(depends)changed=tainted.add(module.descriptor().identity());
            }
        }while(changed);
        return tainted.contains(target);
    }

    private static boolean under(String root,String path) {
        return root.equals(".")||path.equals(root)||path.startsWith(root+"/");
    }
}
