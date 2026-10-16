package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.UniversalSourceIngestion;
import com.evolution.analysis.maven.*;
import java.nio.file.Path;
import java.util.*;

/** Passive composition of the existing Maven providers; no target lifecycle or network. */
public final class MavenScan {
    public static final BuildModelPolicy POLICY=new BuildModelPolicy(List.of(),List.of(),Map.of(),10_000_000,1000,128);
    public static Optional<MavenBuildModelResolution> model(RepositoryAcquisitionResult acquisition,Path cache){
        return acquisition.buildModelRequest(POLICY).map(r->new MavenBuildModelResolver(cache).resolve(r,new MavenPomResolutionPolicy(256,4_000_000,32_000_000,16)));
    }
    public static List<String> outsideEntryBuild(RepositoryAcquisitionResult acquisition,MavenBuildModelResolution resolution,boolean tests){
        var model=resolution.model();
        if(!model.problems().isEmpty()||model.modules().stream().anyMatch(m->m.effectivePom().isEmpty()))return List.of();
        var poms=new HashSet<String>();var roots=new ArrayList<String>();
        for(var m:model.modules()){
            poms.add(m.pomPath());
            for(var set:m.effectivePom().orElseThrow().sourcePlan().sourceSets()){
                if(!tests&&set.kind()==SourcePlanModel.Kind.TEST)continue;
                if(set.sourceRoots().stream().anyMatch(s->s.value().isEmpty()))return List.of();
                set.sourceRoots().forEach(s->roots.add(s.value().orElseThrow()));
            }
        }
        return acquisition.files().stream().map(AcquiredFile::path).filter(p->
                p.endsWith(".java")&&roots.stream().noneMatch(r->r.equals(".")||p.startsWith(r+"/"))
                ||(p.equals("pom.xml")||p.endsWith("/pom.xml"))&&!poms.contains(p)
                ||p.endsWith(".gradle")||p.endsWith(".gradle.kts")).sorted().toList();
    }
    public record Inputs(Map<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution> resolutions,
                  Map<MavenCoordinate,PomInput> externalPoms,List<CapabilityGapRecord> gaps,Map<String,Object> summary){}
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,Optional<MavenBuildModelResolution> build,Path cache,Optional<PlatformInput> platform){
        if(build.isEmpty()||platform.isEmpty())return new Inputs(Map.of(),Map.of(),List.of(),Map.of("mode","NOT_APPLICABLE"));
        var resolved=build.orElseThrow();
        var classpath=new MavenLocalClasspathProvider(cache).resolve(new ClasspathResolutionRequest(resolved.request(),resolved.model(),new ClasspathResolutionPolicy(1024,2048,4_000_000,64_000_000,256_000_000,64)));
        var resolutions=new HashMap<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution>();
        for(var manifest:classpath.manifests())if(manifest.sourceSet()!=SourcePlanModel.Kind.CUSTOM){
            var binaries=manifest.entries().stream().map(e->new BinaryInput(e.classpathEntry(),cache.resolve(e.repositoryPath()))).toList();
            resolutions.put(new UniversalSourceIngestion.SourceSet(manifest.module(),manifest.sourceSet()),new UniversalSourceIngestion.Resolution(platform.orElseThrow(),binaries,manifest.identity()));
        }
        var ledger=CapabilityGapNormalizer.normalize(EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),EvidenceNormalizationInput.builder().buildModels(List.of(resolved.model())).classpaths(List.of(classpath)).build());
        return new Inputs(Map.copyOf(resolutions),resolved.request().artifactPoms(),ledger.gaps(),Map.of("mode","LOCAL_CACHE_ONLY","build",resolved.identity(),"classpath",classpath.identity(),"artifacts",classpath.artifacts().size(),"problems",classpath.manifests().stream().flatMap(m->m.problems().stream()).distinct().toList()));
    }
    private MavenScan(){}
}
