package com.evolution.analysis.execution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.util.*;

/** Current evidence view. Historical plan/gap records remain immutable. */
public final class RequirementReconciler {
    public enum State { PENDING, SATISFIED, NOT_APPLICABLE, CONFLICTED }
    public record Result(State state,String reason,List<ContentDigest> evidence,List<String> blockers) {
        public Result {Objects.requireNonNull(state);Objects.requireNonNull(reason);evidence=evidence.stream().distinct().sorted().toList();blockers=ExecutionGraph.strings(blockers);}
    }
    public record ProducerResult(Optional<EvidenceVerifier.VerifiedClaim> accepted,String disposition) {
        public ProducerResult {Objects.requireNonNull(accepted);ExecutionGraph.text(disposition);}
    }
    public record View(String schema,ContentDigest readiness,ContentDigest trustPolicy,Map<String,Result> requirements) {
        public View {requirements=Collections.unmodifiableMap(new TreeMap<>(requirements));}
        public ContentDigest identity(){return CanonicalJson.sha256(this);}
        public long active(){return requirements.values().stream().filter(r->r.state()==State.PENDING||r.state()==State.CONFLICTED).count();}
    }
    private final ContentDigest trustPolicy;
    public RequirementReconciler(ContentDigest trustPolicy){this.trustPolicy=Objects.requireNonNull(trustPolicy);}
    public static ContentDigest scope(ReadinessPlan plan,ReadinessPlan.Requirement requirement){
        return plan.contexts().stream().filter(context->context.id().equals(requirement.subject())).findFirst().map(ReadinessPlan.Context::identity)
                .orElseGet(()->CanonicalJson.sha256(Map.of("schema","execution-question-scope:m8.4-v1","plan",plan.identity(),"requirement",requirement.id())));
    }
    public View reconcile(ReadinessPlan plan,ExecutionGraph graph,List<ExecutionPlan.Binding> bindings,
            Map<ExecutionPlan.Candidate,ProducerResult> producers,Map<ContentDigest,EvidenceVerifier.VerifiedClaim> reusable) {
        ExecutionPlan.validateBindings(plan,graph,bindings);var byRequirement=new HashMap<String,ExecutionPlan.Binding>();bindings.forEach(binding->byRequirement.put(binding.requirement().localId(),binding));
        var candidates=new HashSet<ExecutionPlan.Candidate>();bindings.forEach(binding->candidates.addAll(binding.candidates()));
        if(!candidates.containsAll(producers.keySet()))throw new IllegalArgumentException("FOREIGN_PRODUCER_RESULT");
        var results=new TreeMap<String,Result>();
        for(var requirement:plan.schedule()){
            var binding=byRequirement.get(requirement.id());
            var blockers=requirement.prerequisites().stream().filter(id->!Set.of(State.SATISFIED,State.NOT_APPLICABLE).contains(results.get(id).state())).toList();
            if(!blockers.isEmpty()){results.put(requirement.id(),pending("PREREQUISITE_PENDING",blockers));continue;}
            if(binding.mode()==ExecutionPlan.Mode.PENDING){results.put(requirement.id(),pending(binding.reason().orElseThrow(),List.of()));continue;}
            var accepted=new ArrayList<EvidenceVerifier.VerifiedClaim>();boolean incomplete=false;boolean conflict=false;
            if(binding.mode()==ExecutionPlan.Mode.REUSE){
                var proof=reusable.get(binding.decision().orElseThrow());
                if(proof==null||!proof.identity().equals(binding.decision().orElseThrow())||!valid(plan,requirement,proof))incomplete=true;else accepted.add(proof);
            }else{
                var roles=new TreeMap<String,List<EvidenceVerifier.VerifiedClaim>>();
                for(var candidate:binding.candidates()){
                    roles.computeIfAbsent(candidate.evidenceRole(),id->new ArrayList<>());
                    var producer=producers.get(candidate);if(producer==null){incomplete=true;continue;}
                    if(producer.accepted().isEmpty())continue;var proof=producer.accepted().orElseThrow();
                    if(!valid(plan,requirement,proof)||!proof.expected().predicate().equals(candidate.predicate())){incomplete=true;continue;}
                    roles.get(candidate.evidenceRole()).add(proof);
                }
                for(var role:roles.values()){
                    if(role.isEmpty())incomplete=true;
                    if(role.stream().map(p->p.expected().claim().digest()).distinct().count()>1)conflict=true;
                    accepted.addAll(role);
                }
            }
            var evidence=accepted.stream().map(EvidenceVerifier.VerifiedClaim::identity).toList();
            if(conflict){results.put(requirement.id(),new Result(State.CONFLICTED,"PROVIDER_CONFLICT",evidence,List.of()));continue;}
            if(incomplete||accepted.isEmpty()){results.put(requirement.id(),new Result(State.PENDING,"EVIDENCE_INCOMPLETE",evidence,List.of()));continue;}
            boolean notApplicable=requirement.kind()==ReadinessPlan.Kind.BUILD_VALIDATION&&accepted.stream().anyMatch(proof->proof.checks().contains("compilation-not-applicable"));
            results.put(requirement.id(),new Result(notApplicable?State.NOT_APPLICABLE:State.SATISFIED,"",evidence,List.of()));
        }
        return new View("execution-readiness-view:m8.4-v1",plan.identity(),trustPolicy,results);
    }
    private boolean valid(ReadinessPlan plan,ReadinessPlan.Requirement requirement,EvidenceVerifier.VerifiedClaim claim){
        if(!claim.policy().equals(trustPolicy)||!claim.expected().scope().equals(scope(plan,requirement)))return false;
        return plan.contexts().stream().filter(c->c.id().equals(requirement.subject())).findFirst().map(c->c.inputClosure().equals(claim.expected().inputClosure())).orElse(true);
    }
    private static Result pending(String reason,List<String> blockers){return new Result(State.PENDING,reason,List.of(),blockers);}
}
