package com.evolution.analysis.readiness;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

public record ReadinessPlan(String schema, InventoryManifest inventory, BuildContextSelection selection,
        List<Root> roots, List<Module> modules, List<Context> contexts, List<Requirement> requirements,
        List<Recipe> recipes, OwnershipSummary ownership) {
    public static final String SCHEMA="repository-readiness:m8.4-v1";
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("build.readiness-planner","m8.4-v1");
    public enum Family { MAVEN, GRADLE, IDE, ANT, BAZEL, SBT, NODE, PYTHON, CARGO, GO, DOTNET, COMPOSER, RUBY, DART, SWIFT, CMAKE, MAKE, PLAIN, CUSTOM }
    public enum Kind { DISCOVERY, MODEL, DEPENDENCIES, TOOLCHAIN, PLATFORM, GENERATION, BUILD_VALIDATION, SEMANTIC_VALIDATION, OWNERSHIP }
    public enum State { PENDING, SATISFIED, NOT_APPLICABLE }
    public enum Disposition { OWNED, AMBIGUOUS, UNOWNED, EXCLUDED, NON_SOURCE, UNAVAILABLE }
    public record Evidence(String path, ContentDigest digest) {
        public Evidence {Objects.requireNonNull(path);Objects.requireNonNull(digest);}
    }
    public record Root(String id, String directory, Family family, List<Evidence> evidence, List<String> relatedRoots) {
        public Root {evidence=List.copyOf(evidence);relatedRoots=BuildContextSelection.sorted(relatedRoots);}
    }
    public record Module(String id, String root, String directory, String target, List<String> languages,
            List<String> dependencies, List<Evidence> evidence) {
        public Module {languages=BuildContextSelection.sorted(languages);dependencies=BuildContextSelection.sorted(dependencies);evidence=List.copyOf(evidence);}
    }
    public record Context(String id, String module, String root, String sourceSet, String variant,
            List<String> sourceRoots, Map<String,String> environment, List<Evidence> evidence,
            ContentDigest inputClosure, boolean buildable) {
        public Context {sourceRoots=BuildContextSelection.sorted(sourceRoots);environment=Collections.unmodifiableMap(new TreeMap<>(environment));evidence=List.copyOf(evidence);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public record Requirement(String id, Kind kind, String subject, String reason, List<String> prerequisites,
            List<Evidence> evidence, State state, Optional<ContentDigest> satisfaction) {
        public Requirement {
            prerequisites=BuildContextSelection.sorted(prerequisites);evidence=List.copyOf(evidence);Objects.requireNonNull(state);Objects.requireNonNull(satisfaction);
            if((state!=State.PENDING)!=satisfaction.isPresent())throw new IllegalArgumentException("Completion needs validation evidence");
        }
    }
    public record Recipe(String id, Family family, String root, List<String> contexts, List<String> requirements,
            List<String> actions, List<Evidence> declaredInputs, List<String> expectedOutputs,
            Map<String,String> toolPins, String executionScope, String networkPolicy, String resourcePolicy,
            List<String> sideEffects, List<String> satisfactionChecks) {
        public Recipe {
            contexts=BuildContextSelection.sorted(contexts);requirements=BuildContextSelection.sorted(requirements);actions=List.copyOf(actions);
            declaredInputs=List.copyOf(declaredInputs);expectedOutputs=List.copyOf(expectedOutputs);toolPins=Collections.unmodifiableMap(new TreeMap<>(toolPins));
            sideEffects=List.copyOf(sideEffects);satisfactionChecks=List.copyOf(satisfactionChecks);
            if(!executionScope.equals("ISOLATED_ROOT")||!networkPolicy.equals("BROKER_ONLY")||!resourcePolicy.equals("CONFIGURED_WORKER_REQUIRED"))throw new IllegalArgumentException("Unsafe recipe");
        }
    }
    public record Ownership(LogicalPath path, Disposition disposition, List<String> contexts, String reason) {
        public Ownership {contexts=BuildContextSelection.sorted(contexts);}
    }
    public record OwnershipSummary(long entries, Map<String,Long> counts, ContentHandle ledger) {
        public OwnershipSummary {counts=Map.copyOf(counts);if(counts.values().stream().mapToLong(Long::longValue).sum()!=entries)throw new IllegalArgumentException("Open ownership denominator");}
    }
    public ReadinessPlan {
        if(!SCHEMA.equals(schema))throw new IllegalArgumentException("Unsupported readiness plan");
        roots=List.copyOf(roots);modules=List.copyOf(modules);contexts=List.copyOf(contexts);requirements=List.copyOf(requirements);recipes=List.copyOf(recipes);
        if(ownership.entries()!=inventory.entries())throw new IllegalArgumentException("Inventory denominator mismatch");
        if(!new TreeSet<>(contexts.stream().map(Context::id).toList()).equals(new TreeSet<>(selection.discovered())))throw new IllegalArgumentException("Context denominator mismatch");
        var ids=new HashSet<String>();requirements.forEach(requirement->{if(!ids.add(requirement.id()))throw new IllegalArgumentException("Duplicate requirement");});
        for(var requirement:requirements)if(!ids.containsAll(requirement.prerequisites())||requirement.prerequisites().contains(requirement.id()))throw new IllegalArgumentException("Invalid prerequisite");
        topological(requirements);
    }
    public ContentDigest identity(){return CanonicalJson.sha256(this);}
    public List<List<String>> compilationGroups(){
        var adjacency=new TreeMap<String,List<String>>();var reverse=new TreeMap<String,List<String>>();
        var ids=new HashSet<>(modules.stream().map(Module::id).toList());
        for(var module:modules){var dependencies=module.dependencies().stream().filter(ids::contains).sorted().toList();adjacency.put(module.id(),dependencies);for(String dependency:dependencies)reverse.computeIfAbsent(dependency,key->new ArrayList<>()).add(module.id());}
        var visited=new HashSet<String>();var order=new ArrayList<String>();
        record Frame(String id,Iterator<String> children){}
        for(String id:adjacency.keySet())if(visited.add(id)){
            var stack=new ArrayDeque<Frame>();stack.push(new Frame(id,adjacency.get(id).iterator()));
            while(!stack.isEmpty()){var frame=stack.peek();if(frame.children().hasNext()){String next=frame.children().next();if(visited.add(next))stack.push(new Frame(next,adjacency.get(next).iterator()));}else{order.add(stack.pop().id());}}
        }
        visited.clear();var groups=new ArrayList<List<String>>();Collections.reverse(order);
        for(String id:order)if(visited.add(id)){
            var group=new TreeSet<String>();var stack=new ArrayDeque<String>();stack.push(id);
            while(!stack.isEmpty()){String next=stack.pop();group.add(next);for(String predecessor:reverse.getOrDefault(next,List.of()))if(visited.add(predecessor))stack.push(predecessor);}
            groups.add(List.copyOf(group));
        }
        return groups.stream().sorted(Comparator.comparing(group->group.getFirst())).toList();
    }
    public List<Requirement> schedule(){return topological(requirements);}
    public List<Requirement> runnable(Set<String> completed){
        var satisfied=new HashSet<>(completed);requirements.stream().filter(requirement->requirement.state()!=State.PENDING).map(Requirement::id).forEach(satisfied::add);
        return schedule().stream().filter(requirement->requirement.state()==State.PENDING&&!satisfied.contains(requirement.id())&&satisfied.containsAll(requirement.prerequisites())).toList();
    }
    public List<CapabilityGapRecord> gaps(SnapshotIdentity snapshot){
        var catalog=new VersionedIdentifier("evidence.readiness-gaps","m8.4-v1");
        var planIdentity=identity();
        return requirements.stream().filter(requirement->requirement.state()==State.PENDING).map(requirement->{
            var observation=ProviderObservationReference.create(PROVIDER,"readiness.requirement",planIdentity,IngestionEvidence.digest(requirement));
            var subject=new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT,requirement.subject());
            boolean isolated=requirement.kind()!=Kind.OWNERSHIP&&!requirement.reason().equals("DESCRIPTOR_WORKER_REQUIRED");
            var need=new EvidenceRequirement(isolated?EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT:EvidenceRequirement.Kind.BUILD_MODEL,
                    "readiness."+requirement.kind().name().toLowerCase(Locale.ROOT).replace('_','-'),List.of(subject),
                    isolated?EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION:EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Validate exact captured inputs, context, toolchain, ordered manifests, outputs and independent observation."));
            return CapabilityGapRecord.create(catalog,EvidenceContext.forSnapshot(snapshot),PROVIDER,"repository.readiness",requirement.reason(),subject,
                    List.of(),List.of(observation),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"repository.readiness")),List.of(),List.of(),
                    List.of("Readiness is separate from semantic correctness and all-context completion."));
        }).sorted().toList();
    }
    private static List<Requirement> topological(List<Requirement> requirements){
        var index=new HashMap<String,Requirement>();var consumers=new HashMap<String,List<String>>();var indegree=new HashMap<String,Integer>();
        for(var requirement:requirements){index.put(requirement.id(),requirement);indegree.put(requirement.id(),requirement.prerequisites().size());for(String prerequisite:requirement.prerequisites())consumers.computeIfAbsent(prerequisite,key->new ArrayList<>()).add(requirement.id());}
        var ready=new PriorityQueue<String>(Comparator.<String>comparingInt(id->consumers.getOrDefault(id,List.of()).size()).reversed().thenComparing(Comparator.naturalOrder()));
        indegree.forEach((id,count)->{if(count==0)ready.add(id);});var ordered=new ArrayList<Requirement>();
        while(!ready.isEmpty()){String id=ready.remove();ordered.add(index.get(id));for(String consumer:consumers.getOrDefault(id,List.of()))if(indegree.compute(consumer,(key,count)->count-1)==0)ready.add(consumer);}
        if(ordered.size()!=requirements.size())throw new IllegalArgumentException("Cyclic requirement DAG");
        return List.copyOf(ordered);
    }
}
