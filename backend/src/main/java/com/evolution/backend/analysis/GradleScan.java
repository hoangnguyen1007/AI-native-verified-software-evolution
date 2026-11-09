package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.RepositoryAcquisitionResult;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.PlatformInput;
import com.evolution.analysis.ingestion.*;
import com.evolution.analysis.maven.*;
import java.nio.file.Path;
import java.util.*;

/** Product composition for passive Gradle recovery. Target settings/plugins remain data. */
public final class GradleScan {
    private GradleScan() {}
    public static Optional<UniversalBuildModel> model(RepositoryAcquisitionResult acquisition,UniversalBuildIngestion.Policy policy,
                                                    RepositoryInputDecoder.Policy decoding,Map<MavenCoordinate,PomInput> poms) {
        if(acquisition.files().stream().noneMatch(f->f.path().endsWith("build.gradle")||f.path().endsWith("build.gradle.kts")||f.path().endsWith("settings.gradle")||f.path().endsWith("settings.gradle.kts")))return Optional.empty();
        return new RepositoryInputDecoder().decode(acquisition,decoding).inputs().map(inputs->new UniversalBuildIngestion().ingest(inputs,policy,Optional.of(new MavenBuildModelProvider()),poms,MavenScan.POLICY));
    }
    public static Set<Integer> platformReleases(Optional<UniversalBuildModel> model) {
        var releases=new TreeSet<Integer>();model.ifPresent(value->value.modules().stream().filter(m->m.tool()!=UniversalBuildModel.Tool.MAVEN).flatMap(m->m.sourcePlan().sourceSets().stream())
                .flatMap(s->s.platformRelease().value().stream()).forEach(v->{try{releases.add(Integer.parseInt(v));}catch(NumberFormatException unavailable){/* Assembly retains the compiler gap. */}}));return Set.copyOf(releases);
    }
    public static MavenScan.Inputs augment(MavenScan.Inputs prior,RepositoryAcquisitionResult acquisition,Optional<UniversalBuildModel> model,Path cache,
                                          Optional<PlatformInput> fallback,DependencyAcquisitionPolicy policy,Map<Integer,PlatformInput> platforms) {
        if(model.isEmpty())return prior;
        MavenScan.prepareCache(cache,policy.remoteRepositories());
        var build=model.orElseThrow();var result=new GradleDependencyArtifactResolver(cache).resolve(build,policy);
        var resolutions=new HashMap<>(prior.resolutions());var gaps=new TreeSet<>(prior.gaps());var ledgers=new ArrayList<>(prior.ledgers());
        var context=EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity());
        var ledger=CapabilityGapNormalizer.normalize(context,EvidenceNormalizationInput.builder().dependencyAcquisitions(result.acquisitions()).build());ledgers.add(ledger);gaps.addAll(ledger.gaps());
        var reasons=new TreeMap<String,Long>();long recovered=0;
        for(var row:result.contexts()) {
            recovered+=row.binaries().size();
            var set=build.modules().stream().flatMap(m->m.sourcePlan().sourceSets().stream()).filter(s->s.module().equals(row.sourceSet().module())&&s.kind()==row.sourceSet().kind()&&s.name().equals(row.sourceSet().name())).findFirst().orElseThrow();
            var platform=set.platformRelease().value().flatMap(v->{try{return Optional.ofNullable(platforms.get(Integer.parseInt(v)));}catch(NumberFormatException invalid){return Optional.empty();}}).or(()->fallback);
            if(platform.isPresent()) {
                var peers=new LinkedHashSet<UniversalSourceIngestion.SourceSet>();
                if(row.sourceSet().kind()==SourcePlanModel.Kind.TEST)peers.add(new UniversalSourceIngestion.SourceSet(row.sourceSet().module(),SourcePlanModel.Kind.MAIN));
                for(var project:row.projects())peers.add(new UniversalSourceIngestion.SourceSet(project,SourcePlanModel.Kind.MAIN));
                var slots=new ArrayList<UniversalSourceIngestion.ReactorSlot>();
                for(var peer:peers)slots.add(new UniversalSourceIngestion.ReactorSlot(peer,slots.size()));
                var resolution=new UniversalSourceIngestion.Resolution(platform.orElseThrow(),row.binaries(),row.identity(),Optional.empty(),false,slots);
                boolean gradleSelected=build.modules().stream().anyMatch(m->m.descriptor().identity().equals(row.sourceSet().module())
                        &&(m.tool()==UniversalBuildModel.Tool.GRADLE_GROOVY||m.tool()==UniversalBuildModel.Tool.GRADLE_KOTLIN));
                if(gradleSelected)resolutions.put(row.sourceSet(),resolution);
                else {
                    var existing=resolutions.putIfAbsent(row.sourceSet(),resolution);
                    if(existing!=null&&!existing.equals(resolution)){resolutions.remove(row.sourceSet());gaps.add(gap(context,build,row.identity(),new GradleDependencyArtifactResolver.Problem("GRADLE_SOURCE_SET_CONFLICT",row.sourceSet().toString())));}
                }
            }
            for(var problem:row.problems()){reasons.merge(problem.reason(),1L,Long::sum);gaps.add(gap(context,build,row.identity(),problem));}
        }
        for(var problem:result.problems()){reasons.merge(problem.reason(),1L,Long::sum);gaps.add(gap(context,build,build.identity(),problem));}
        long unique=result.contexts().stream().flatMap(c->c.binaries().stream()).map(com.evolution.analysis.frontend.BinaryInput::entry).distinct().count();
        var summary=new TreeMap<>(prior.summary());summary.put("gradle",Map.of("provider",GradleDependencyArtifactResolver.PROVIDER,"model",build.identity(),"contexts",result.contexts().size(),"binaryInputs",recovered,"uniqueBinaryArtifacts",unique,"problemsByReason",reasons,
                "trust","PASSIVE_RECOVERY","effectiveGradleContext",false,"acquisitionReceipts",result.acquisitions().stream().map(a->a.identity()).toList()));
        if(prior.resolutions().isEmpty())summary.put("mode",policy.remoteRepositories().isEmpty()?"PASSIVE_CACHE_THEN_ISOLATED_REQUIREMENT":"EXPLICIT_ARTIFACT_ACQUISITION");
        return new MavenScan.Inputs(Map.copyOf(resolutions),prior.externalPoms(),List.copyOf(gaps),Map.copyOf(summary),List.copyOf(ledgers));
    }
    private static CapabilityGapRecord gap(EvidenceContext context,UniversalBuildModel model,ContentDigest proof,GradleDependencyArtifactResolver.Problem problem) {
        var subject=new EvidenceSubject(EvidenceSubject.Kind.SOURCE_SET,problem.subject());
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT,"gradle.effective-context",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                List.of("Capture the selected Gradle variant, exact ordered classpath, constraints, plugin effects and generated outputs in the registered isolated executor; passive JAR recovery does not establish this context."));
        return CapabilityGapRecord.create(context,GradleDependencyArtifactResolver.PROVIDER,"build.gradle-recovery",problem.reason(),subject,List.of(),
                List.of(ProviderObservationReference.create(GradleDependencyArtifactResolver.PROVIDER,"gradle.passive-context",model.identity(),proof)),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"build.gradle-recovery")),List.of(),List.of(),List.of("Verified available binaries can support recovery while this obligation remains open."));
    }
}
