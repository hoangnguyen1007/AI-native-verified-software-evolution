package com.evolution.backend.analysis;

import com.evolution.analysis.acquisition.*;
import com.evolution.analysis.buildmodel.*;
import com.evolution.analysis.classpath.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.ingestion.UniversalSourceIngestion;
import com.evolution.analysis.maven.*;
import com.evolution.analysis.dependency.*;
import java.nio.file.Path;
import java.util.*;

/** Passive metadata/artifact acquisition; target lifecycles and target-declared repositories stay inert. */
public final class MavenScan {
    public static final long DEFAULT_DEPENDENCY_TOTAL_BYTES = 2_147_483_648L;
    public static final List<java.net.URI> DEFAULT_REPOSITORIES=List.of(
            java.net.URI.create("https://repo.maven.apache.org/maven2/"),
            java.net.URI.create("https://dl.google.com/dl/android/maven2/"),
            java.net.URI.create("https://plugins.gradle.org/m2/"),
            java.net.URI.create("https://jitpack.io/"));
    public static final BuildModelPolicy POLICY=new BuildModelPolicy(List.of(),List.of(),Map.of(),10_000_000,1000,128);
    public static MavenPomResolutionPolicy pomPolicy(List<java.net.URI> repositories){
        return new MavenPomResolutionPolicy(4096,4_000_000,256_000_000L,64,repositories,5000,15000);
    }
    public static Optional<MavenBuildModelResolution> model(RepositoryAcquisitionResult acquisition,Path cache){
        prepareCache(cache,DEFAULT_REPOSITORIES);
        return acquisition.buildModelRequest(POLICY).map(r->new MavenBuildModelResolver(cache).resolve(r,pomPolicy(DEFAULT_REPOSITORIES)));
    }
    public static List<MavenBuildModelResolution> models(RepositoryAcquisitionResult acquisition,Path cache){
        return models(acquisition,cache,DEFAULT_REPOSITORIES);
    }
    public static List<MavenBuildModelResolution> models(RepositoryAcquisitionResult acquisition,Path cache,List<java.net.URI> repositories){
        return models(acquisition, cache, repositories, null);
    }
    public static List<MavenBuildModelResolution> models(RepositoryAcquisitionResult acquisition,Path cache,List<java.net.URI> repositories,BuildDeltaCache deltaCache){
        if(acquisition.snapshot().isEmpty())return List.of();
        if(deltaCache!=null){
            var cached=deltaCache.getMavenModels(acquisition,cache,repositories);
            if(cached.isPresent())return cached.orElseThrow();
        }
        var policy=pomPolicy(repositories);
        if(!acquisition.workspacePoms().isEmpty())prepareCache(cache,repositories);
        var poms=acquisition.workspacePoms();var modeled=new HashSet<String>();var results=new ArrayList<MavenBuildModelResolution>();
        // Model aggregators before descendants. Otherwise app/pom.xml is first modeled
        // alone, then again in pom.xml's reactor, creating a false classpath conflict.
        var paths=poms.keySet().stream().sorted(Comparator.comparingLong((String path)->path.chars().filter(c->c=='/').count()).thenComparing(Comparator.naturalOrder())).toList();
        for(String path:paths){
            if(modeled.contains(path))continue;
            var request=new BuildModelRequest(acquisition.snapshot().orElseThrow(),path,poms,Map.of(),POLICY);
            var result=new MavenBuildModelResolver(cache).resolve(request,policy);
            results.add(result);result.model().modules().stream().map(BuildModelResult.ModuleModel::pomPath).forEach(modeled::add);
        }
        var immutable=List.copyOf(results);
        if(deltaCache!=null)deltaCache.putMavenModels(acquisition,cache,repositories,immutable);
        return immutable;
    }
    public record Inputs(Map<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution> resolutions,
                  Map<MavenCoordinate,PomInput> externalPoms,List<CapabilityGapRecord> gaps,Map<String,Object> summary,List<EvidenceAcquisitionLedger> ledgers){
        public Inputs(Map<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution> resolutions,Map<MavenCoordinate,PomInput> externalPoms,List<CapabilityGapRecord> gaps,Map<String,Object> summary){this(resolutions,externalPoms,gaps,summary,List.of());}
        public Inputs{ledgers=List.copyOf(ledgers);}
    }
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,Optional<MavenBuildModelResolution> build,Path cache,Optional<PlatformInput> platform){
        return inputs(acquisition,models(acquisition,cache),cache,platform,new DependencyAcquisitionPolicy(4096,64_000_000,DEFAULT_DEPENDENCY_TOTAL_BYTES,64,DEFAULT_REPOSITORIES,5000,15000,1,16));
    }
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,List<MavenBuildModelResolution> builds,Path cache,Optional<PlatformInput> platform,DependencyAcquisitionPolicy acquisitionPolicy){
        return inputs(acquisition,builds,cache,platform,acquisitionPolicy,Map.of());
    }
    /** Native platform views are acquired by the composition root, once per declared release. */
    public static Set<Integer> platformReleases(List<MavenBuildModelResolution> builds) {
        var releases=new TreeSet<Integer>();
        for(var build:builds)for(var module:build.model().modules())module.effectivePom().ifPresent(pom->{
            for(var set:pom.sourcePlan().sourceSets())set.platformRelease().value().flatMap(MavenScan::release).ifPresent(releases::add);
        });
        return Collections.unmodifiableSet(releases);
    }
    private static Optional<Integer> release(String value) {
        try{int release=Integer.parseInt(value);return release>0?Optional.of(release):Optional.empty();}
        catch(NumberFormatException unknown){return Optional.empty();}
    }
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,List<MavenBuildModelResolution> builds,Path cache,Optional<PlatformInput> platform,DependencyAcquisitionPolicy acquisitionPolicy,Map<Integer,PlatformInput> platforms){
        return inputs(acquisition, builds, cache, platform, acquisitionPolicy, platforms, null);
    }
    public static Inputs inputs(RepositoryAcquisitionResult acquisition,List<MavenBuildModelResolution> builds,Path cache,Optional<PlatformInput> platform,DependencyAcquisitionPolicy acquisitionPolicy,Map<Integer,PlatformInput> platforms,BuildDeltaCache deltaCache){
        return inputs(acquisition, builds, cache, platform, acquisitionPolicy, platforms, deltaCache,
                (request, policy) -> new MavenDependencyArtifactResolver(cache).resolve(request, policy));
    }
    static Inputs inputs(RepositoryAcquisitionResult acquisition,List<MavenBuildModelResolution> builds,Path cache,Optional<PlatformInput> platform,DependencyAcquisitionPolicy acquisitionPolicy,Map<Integer,PlatformInput> platforms,BuildDeltaCache deltaCache,java.util.function.BiFunction<ClasspathResolutionRequest,DependencyAcquisitionPolicy,MavenDependencyArtifactResolution> dependencyResolver){
        var views=Map.copyOf(platforms);views.forEach((release,view)->{if(release!=view.release())throw new IllegalArgumentException("PLATFORM_VIEW_RELEASE_MISMATCH");});
        if(builds.isEmpty())return new Inputs(Map.of(),Map.of(),List.of(),Map.of("mode","NOT_APPLICABLE"));
        if(deltaCache!=null){
            var cached=deltaCache.getMavenInputs(acquisition,builds,cache,platform,acquisitionPolicy,views);
            if(cached.isPresent())return cached.orElseThrow();
        }
        var resolutions=new HashMap<UniversalSourceIngestion.SourceSet,UniversalSourceIngestion.Resolution>();
        var ledgers=new ArrayList<EvidenceAcquisitionLedger>();
        var external=new HashMap<MavenCoordinate,PomInput>();var gaps=new TreeSet<CapabilityGapRecord>();var summaries=new ArrayList<Object>();
        var conflicts=new ArrayList<com.evolution.analysis.ingestion.IngestionEvidence.Issue>();
        var conflictedSets=new HashSet<UniversalSourceIngestion.SourceSet>();var conflictedPoms=new HashSet<MavenCoordinate>();
        for(var resolved:builds){
        var request=new ClasspathResolutionRequest(resolved.request(),resolved.model(),new ClasspathResolutionPolicy(4096,8192,4_000_000,64_000_000,Math.max(DEFAULT_DEPENDENCY_TOTAL_BYTES,acquisitionPolicy.maxTotalBytes()),64));
        var acquired=dependencyResolver.apply(request,acquisitionPolicy);var classpath=acquired.classpath();
        for(var manifest:classpath.manifests())if(manifest.sourceSet()!=SourcePlanModel.Kind.CUSTOM){
            var requested=resolved.model().modules().stream().filter(m->m.module().identity().equals(manifest.module())).flatMap(m->m.effectivePom().stream())
                    .flatMap(p->p.sourcePlan().sourceSets().stream()).filter(s->s.kind()==manifest.sourceSet()).flatMap(s->s.platformRelease().value().stream()).findFirst().flatMap(MavenScan::release);
            var selected=requested.map(views::get).or(()->platform);if(selected.isEmpty())continue;
            var binaries=manifest.entries().stream().map(e->new BinaryInput(e.classpathEntry(),cache.resolve(e.repositoryPath()))).toList();
            var key=new UniversalSourceIngestion.SourceSet(manifest.module(),manifest.sourceSet());
            var value=new UniversalSourceIngestion.Resolution(selected.orElseThrow(),binaries,manifest.identity(),Optional.empty(),
                    manifest.problems().stream().allMatch(p->p.reason()==ExactClasspathResult.Reason.REACTOR_OUTPUT_NOT_ACQUIRED),
                    manifest.reactorEntries().stream().map(e->new UniversalSourceIngestion.ReactorSlot(new UniversalSourceIngestion.SourceSet(e.module(),e.targetSourceSet()),e.order())).toList());
            if(conflictedSets.contains(key))continue;
            var prior=resolutions.putIfAbsent(key,value);if(prior!=null&&!prior.equals(value)){
                resolutions.remove(key);conflictedSets.add(key);conflicts.add(new com.evolution.analysis.ingestion.IngestionEvidence.Issue(com.evolution.analysis.ingestion.IngestionEvidence.Reason.AMBIGUOUS_INPUT,key.toString(),List.of(prior.exactClasspathEvidence(),value.exactClasspathEvidence())));
            }
        }
        var canonicalFailed=canonicalFailedAcquisitions(acquired.acquisitions(),acquisitionPolicy,unresolvedCoordinates(classpath));
        var ledger=CapabilityGapNormalizer.normalize(EvidenceContext.forSnapshot(acquisition.snapshot().orElseThrow().identity()),EvidenceNormalizationInput.builder().buildModels(List.of(resolved.model())).classpaths(List.of(classpath)).dependencyAcquisitions(canonicalFailed).build());
        ledgers.add(ledger);
        gaps.addAll(ledger.gaps());
        resolved.request().artifactPoms().forEach((coordinate,pom)->{
            if(conflictedPoms.contains(coordinate))return;
            var prior=external.putIfAbsent(coordinate,pom);if(prior!=null&&!prior.digest().equals(pom.digest())){
                external.remove(coordinate);conflictedPoms.add(coordinate);conflicts.add(new com.evolution.analysis.ingestion.IngestionEvidence.Issue(com.evolution.analysis.ingestion.IngestionEvidence.Reason.AMBIGUOUS_INPUT,coordinate.notation(),List.of(prior.digest(),pom.digest())));
            }
        });
        int acquisitionAttempts=acquired.acquisitions().stream().mapToInt(a->a.attempts().size()).sum();
        summaries.add(Map.ofEntries(
                Map.entry("root",resolved.request().rootPom()),
                Map.entry("build",resolved.identity()),
                Map.entry("pomPolicy",resolved.policyIdentity()),
                Map.entry("poms",resolved.acquiredPoms().size()),
                Map.entry("pomAttempts",resolved.attempts().size()),
                Map.entry("pomProblems",resolved.problems()),
                Map.entry("classpath",classpath.identity()),
                Map.entry("acquisition",acquired.identity()),
                Map.entry("acquisitionAttempts",acquisitionAttempts),
                Map.entry("artifacts",classpath.artifacts().size()),
                Map.entry("problems",classpath.manifests().stream().flatMap(m->m.problems().stream()).distinct().toList())));
        }
        gaps.addAll(com.evolution.analysis.ingestion.IngestionEvidence.gaps(acquisition.snapshot().orElseThrow().identity(),new VersionedIdentifier("build.product-readiness","m8.4-v1"),acquisition.identity(),conflicts));
        var out=new Inputs(Map.copyOf(resolutions),Map.copyOf(external),List.copyOf(gaps),Map.of("mode",acquisitionPolicy.remoteRepositories().isEmpty()?"PASSIVE_CACHE_THEN_ISOLATED_REQUIREMENT":"EXPLICIT_ARTIFACT_ACQUISITION","policy",acquisitionPolicy.identity(),"roots",summaries),ledgers);
        if(deltaCache!=null)deltaCache.putMavenInputs(acquisition,builds,cache,platform,acquisitionPolicy,views,out);
        return out;
    }

    static Set<ArtifactCoordinate> unresolvedCoordinates(ExactClasspathResult classpath) {
        var unresolved = new TreeSet<ArtifactCoordinate>();
        for (var attempt : classpath.attempts()) {
            if (attempt.outcome() != ExactClasspathResult.AttemptOutcome.SUCCEEDED) {
                unresolved.add(attempt.coordinate());
            }
        }
        return unresolved;
    }

    /**
     * Extracts a cache-state-independent, batch-independent canonical view of genuinely failed
     * dependency acquisitions for ledger/graph projection. Operational cache-miss, mirror-fallback,
     * and retry attempts stay in operational summaries rather than entering canonical graph identity,
     * while every still-unresolved coordinate retains its typed failure gap.
     */
    static List<DependencyAcquisitionResult> canonicalFailedAcquisitions(
            List<DependencyAcquisitionResult> acquisitions,
            DependencyAcquisitionPolicy policy,
            Set<ArtifactCoordinate> unresolvedFilter) {
        if (acquisitions.isEmpty()) return List.of();
        var succeeded = new HashSet<ArtifactCoordinate>();
        for (var result : acquisitions) {
            for (var outcome : result.outcomes()) {
                if (outcome.status() != DependencyAcquisitionResult.Status.FAILED) {
                    succeeded.add(outcome.requirement().coordinate());
                }
            }
        }
        Comparator<VersionedIdentifier> providerOrder = Comparator.comparing(VersionedIdentifier::id)
                .thenComparing(VersionedIdentifier::version);
        Comparator<DependencyAcquisitionRequest.Requirement> reqOrder = Comparator
                .comparing(DependencyAcquisitionRequest.Requirement::coordinate)
                .thenComparing(r -> r.expectedDigest().map(ContentDigest::value).orElse(""));
        var failedByProvider = new TreeMap<VersionedIdentifier, TreeMap<DependencyAcquisitionRequest.Requirement, DependencyAcquisitionResult.FailureReason>>(providerOrder);
        for (var result : acquisitions) {
            for (var outcome : result.outcomes()) {
                if (outcome.status() != DependencyAcquisitionResult.Status.FAILED || outcome.failureReason().isEmpty()) {
                    continue;
                }
                var coord = outcome.requirement().coordinate();
                if (succeeded.contains(coord)) continue;
                if (unresolvedFilter != null && !unresolvedFilter.contains(coord)) continue;
                failedByProvider.computeIfAbsent(result.provider(), ignored -> new TreeMap<>(reqOrder))
                        .put(outcome.requirement(), outcome.failureReason().orElseThrow());
            }
        }
        if (failedByProvider.isEmpty()) return List.of();
        int maxChunk = policy.maxCoordinates();
        var canonical = new ArrayList<DependencyAcquisitionResult>();
        for (var entry : failedByProvider.entrySet()) {
            var reqList = new ArrayList<>(entry.getValue().keySet());
            for (int start = 0; start < reqList.size(); start += maxChunk) {
                var chunk = List.copyOf(reqList.subList(start, Math.min(start + maxChunk, reqList.size())));
                var req = new DependencyAcquisitionRequest(chunk, policy);
                var outcomes = new ArrayList<DependencyAcquisitionResult.Outcome>(chunk.size());
                for (var r : chunk) {
                    outcomes.add(new DependencyAcquisitionResult.Outcome(
                            r, DependencyAcquisitionResult.Status.FAILED, Optional.empty(),
                            Optional.of(entry.getValue().get(r))));
                }
                canonical.add(DependencyAcquisitionResult.create(req, entry.getKey(), outcomes, List.of(), 0L));
            }
        }
        return List.copyOf(canonical);
    }
    /** Create only the caller-selected cache, without traversing existing aliases/links.
     * Unavailable storage remains a typed acquisition result rather than aborting source analysis. */
    static void prepareCache(Path cache,List<java.net.URI> repositories){
        if(repositories.isEmpty())return;
        var absolute=cache.toAbsolutePath().normalize();
        try {
            var parents=new ArrayDeque<Path>();
            for(Path p=absolute;p!=null;p=p.getParent())parents.push(p);
            while(!parents.isEmpty()){
                Path p=parents.pop();
                if(!java.nio.file.Files.exists(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                    try{java.nio.file.Files.createDirectory(p);}catch(java.nio.file.FileAlreadyExistsException concurrent){/* Recheck below. */}
                }
                if(java.nio.file.Files.isSymbolicLink(p)||!java.nio.file.Files.isDirectory(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)||!p.toRealPath().equals(p))return;
            }
        }catch(java.io.IOException|SecurityException unavailable){/* Provider reports the exact typed cache failure. */}
    }
    private MavenScan(){}
}
