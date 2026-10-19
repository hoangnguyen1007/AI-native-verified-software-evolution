package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.UniversalSourceIngestion;
import com.evolution.analysis.maven.*;
import com.evolution.analysis.dependency.DependencyAcquisitionPolicy;
import java.nio.file.Path;
import java.util.*;

/** Passive composition of the existing Maven providers; no target lifecycle or network. */
public final class MavenScan {
    public static final BuildModelPolicy POLICY=new BuildModelPolicy(List.of(),List.of(),Map.of(),10_000_000,1000,128);
    public static Optional<MavenBuildModelResolution> model(RepositoryAcquisitionResult acquisition,Path cache){
        return acquisition.buildModelRequest(POLICY).map(r->new MavenBuildModelResolver(cache).resolve(r,new MavenPomResolutionPolicy(256,4_000_000,32_000_000,16)));
    }
    public static List<MavenBuildModelResolution> models(RepositoryAcquisitionResult acquisition,Path cache){
        if(acquisition.snapshot().isEmpty())return List.of();
        var poms=acquisition.workspacePoms();var modeled=new HashSet<String>();var results=new ArrayList<MavenBuildModelResolution>();
        for(String path:new TreeSet<>(poms.keySet())){
            if(modeled.contains(path))continue;
            var request=new BuildModelRequest(acquisition.snapshot().orElseThrow(),path,poms,Map.of(),POLICY);
            var result=new MavenBuildModelResolver(cache).resolve(request,new MavenPomResolutionPolicy(256,4_000_000,32_000_000,16));
            results.add(result);result.model().modules().stream().map(BuildModelResult.ModuleModel::pomPath).forEach(modeled::add);
        }
        return List.copyOf(results);
    }
    public record Inputs(Map<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution> resolutions,
                  Map<MavenCoordinate,PomInput> externalPoms,List<CapabilityGapRecord> gaps,Map<String,Object> summary){}
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,Optional<MavenBuildModelResolution> build,Path cache,Optional<PlatformInput> platform){
        return inputs(acquisition,models(acquisition,cache),cache,platform,new DependencyAcquisitionPolicy(1024,64_000_000,256_000_000,16,List.of(),5000,15000,0));
    }
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,List<MavenBuildModelResolution> builds,Path cache,Optional<PlatformInput> platform,DependencyAcquisitionPolicy acquisitionPolicy){
        if(builds.isEmpty()||platform.isEmpty())return new Inputs(Map.of(),Map.of(),List.of(),Map.of("mode","NOT_APPLICABLE"));
        var resolutions=new HashMap<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution>();
        var external=new HashMap<MavenCoordinate,PomInput>();var gaps=new TreeSet<CapabilityGapRecord>();var summaries=new ArrayList<Object>();
        var conflicts=new ArrayList<com.evolution.analysis.ingestion.IngestionEvidence.Issue>();
        var conflictedSets=new HashSet<UniversalSourceIngestion.SourceSet>();var conflictedPoms=new HashSet<MavenCoordinate>();
        for(var resolved:builds){
        var request=new ClasspathResolutionRequest(resolved.request(),resolved.model(),new ClasspathResolutionPolicy(1024,2048,4_000_000,64_000_000,256_000_000,64));
        var acquired=new MavenDependencyArtifactResolver(cache).resolve(request,acquisitionPolicy);var classpath=acquired.classpath();
        for(var manifest:classpath.manifests())if(manifest.sourceSet()!=SourcePlanModel.Kind.CUSTOM){
            var binaries=manifest.entries().stream().map(e->new BinaryInput(e.classpathEntry(),cache.resolve(e.repositoryPath()))).toList();
            var key=new UniversalSourceIngestion.SourceSet(manifest.module(),manifest.sourceSet());
            var value=new UniversalSourceIngestion.Resolution(platform.orElseThrow(),binaries,manifest.identity());
            if(conflictedSets.contains(key))continue;
            var prior=resolutions.putIfAbsent(key,value);if(prior!=null&&!prior.equals(value)){
                resolutions.remove(key);conflictedSets.add(key);conflicts.add(new com.evolution.analysis.ingestion.IngestionEvidence.Issue(com.evolution.analysis.ingestion.IngestionEvidence.Reason.AMBIGUOUS_INPUT,key.toString(),List.of(prior.exactClasspathEvidence(),value.exactClasspathEvidence())));
            }
        }
        var ledger=CapabilityGapNormalizer.normalize(EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),EvidenceNormalizationInput.builder().buildModels(List.of(resolved.model())).classpaths(List.of(classpath)).dependencyAcquisitions(acquired.acquisitions()).build());
        gaps.addAll(ledger.gaps());
        resolved.request().artifactPoms().forEach((coordinate,pom)->{
            if(conflictedPoms.contains(coordinate))return;
            var prior=external.putIfAbsent(coordinate,pom);if(prior!=null&&!prior.digest().equals(pom.digest())){
                external.remove(coordinate);conflictedPoms.add(coordinate);conflicts.add(new com.evolution.analysis.ingestion.IngestionEvidence.Issue(com.evolution.analysis.ingestion.IngestionEvidence.Reason.AMBIGUOUS_INPUT,coordinate.notation(),List.of(prior.digest(),pom.digest())));
            }
        });
        summaries.add(Map.of("root",resolved.request().rootPom(),"build",resolved.identity(),"classpath",classpath.identity(),"acquisition",acquired.identity(),"artifacts",classpath.artifacts().size(),"problems",classpath.manifests().stream().flatMap(m->m.problems().stream()).distinct().toList()));
        }
        gaps.addAll(com.evolution.analysis.ingestion.IngestionEvidence.gaps(acquisition.snapshot().orElseThrow().identity(),new VersionedIdentifier("build.product-readiness","m8.4-v1"),acquisition.identity(),conflicts));
        return new Inputs(Map.copyOf(resolutions),Map.copyOf(external),List.copyOf(gaps),Map.of("mode",acquisitionPolicy.remoteRepositories().isEmpty()?"PASSIVE_CACHE_THEN_ISOLATED_REQUIREMENT":"EXPLICIT_ARTIFACT_ACQUISITION","policy",acquisitionPolicy.identity(),"roots",summaries));
    }
    private MavenScan(){}
}
