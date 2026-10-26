package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.ModuleDescriptor;
import com.evolution.analysis.acquisition.streaming.ContentHandle;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.readiness.ReadinessPlan;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reconciles the repository census independently of a provider's returned rows. */
public final class JavaReadinessClosure {
    public static final String QUESTION="java.readiness-requirement";
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("source.java-readiness","m8.4-step4-v1");
    public enum State {PENDING,SATISFIED,NOT_APPLICABLE,CONFLICTED,REOPENED}
    public record Row(State state,String reason,List<String> blockers,List<ContentDigest> proofs){
        public Row {blockers=blockers.stream().distinct().sorted().toList();proofs=proofs.stream().distinct().sorted().toList();}
        public boolean active(){return state!=State.SATISFIED&&state!=State.NOT_APPLICABLE;}
    }
    public record ContextRow(String root,String sourceSet,boolean selected,List<String> requirements,boolean readinessClosed){
        public ContextRow {requirements=requirements.stream().distinct().sorted().toList();}
    }
    public record View(ReadinessPlan plan,ContentDigest policy,Map<String,Row> requirements,Map<String,ContextRow> contexts,List<String> frontier){
        public View {requirements=Collections.unmodifiableMap(new TreeMap<>(requirements));contexts=Collections.unmodifiableMap(new TreeMap<>(contexts));frontier=frontier.stream().distinct().sorted().toList();}
        public long active(){return Math.addExact(requirements.values().stream().filter(Row::active).count(),frontier.size());}
        public boolean readinessClosed(){return !requirements.isEmpty()&&!contexts.isEmpty()&&active()==0&&contexts.values().stream().allMatch(ContextRow::readinessClosed);}
        public ContentDigest identity(){return CanonicalJson.sha256(wire());}
        public Map<String,Object> wire(){
            var counts=new TreeMap<String,Long>();counts.put("total",(long)requirements.size());counts.put("active",requirements.values().stream().filter(Row::active).count());
            counts.put("rootCount",(long)plan.roots().size());counts.put("moduleCount",(long)plan.modules().size());counts.put("contextCount",(long)contexts.size());
            counts.put("selectedContextCount",(long)plan.selection().selected().size());counts.put("frontierCount",(long)frontier.size());
            for(var state:State.values())counts.put(state.name(),requirements.values().stream().filter(r->r.state()==state).count());
            return Map.of("schema","java-readiness-current:m8.4-v1","readiness",plan.identity(),"selection",plan.selection().identity(),
                    "policy",policy,"requirements",requirements,"contexts",contexts,"frontier",frontier,"counts",counts,"semanticCoverage","NOT_EVALUATED");
        }
        public List<CapabilityGapRecord> gaps(SnapshotIdentity snapshot){
            if(!plan.inventory().compatibleV1Snapshot().filter(snapshot::equals).isPresent())throw new IllegalArgumentException("JAVA_READINESS_SNAPSHOT_MISMATCH");
            var result=new ArrayList<CapabilityGapRecord>();
            requirements.forEach((id,row)->{if(row.active())result.add(gap(snapshot,"READINESS_"+row.state().name(),id,CanonicalJson.sha256(row)));});
            for(var item:frontier)result.add(gap(snapshot,"READINESS_FRONTIER_OPEN",item,CanonicalJson.sha256(item)));
            return List.copyOf(result);
        }
        /** An analyzed context does not imply that its selected siblings have been analyzed. */
        public List<CapabilityGapRecord> remainingContexts(SnapshotIdentity snapshot,ContentDigest analyzedContext){
            if(!plan.inventory().compatibleV1Snapshot().filter(snapshot::equals).isPresent()||plan.contexts().stream().noneMatch(c->c.identity().equals(analyzedContext)))
                throw new IllegalArgumentException("JAVA_READINESS_ANALYSIS_CONTEXT_SCOPE");
            var result=new ArrayList<CapabilityGapRecord>();
            for(var context:plan.contexts())if(plan.selection().selected().contains(context.id())&&!context.identity().equals(analyzedContext))
                result.add(gap(snapshot,"CONTEXT_SEMANTICS_NOT_EVALUATED",context.id(),context.identity()));
            return List.copyOf(result);
        }
        private CapabilityGapRecord gap(SnapshotIdentity snapshot,String reason,String id,ContentDigest payload){
            var observation=ProviderObservationReference.create(PROVIDER,"java.readiness",identity(),payload);
            var subject=new EvidenceSubject(EvidenceSubject.Kind.BUILD_INPUT,id);
            var need=new EvidenceRequirement(EvidenceRequirement.Kind.BUILD_MODEL,"java.readiness",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Supply the exact readiness/requirement/census proof; context completion cannot erase root obligations."));
            return CapabilityGapRecord.create(new VersionedIdentifier("evidence.java-readiness","m8.4-step4-v1"),EvidenceContext.forSnapshot(snapshot),PROVIDER,
                    "repository.readiness",reason,subject,List.of(),List.of(observation),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"repository.readiness")),
                    List.of(),List.of(),List.of("Readiness closure and semantic/Spring correctness are separate outcomes."));
        }
        public GraphEvidence evidence(){return GraphEvidence.capture("java.evidence.readiness-coverage",wire());}
    }
    public View reconcile(ReadinessPlan plan,ContentDigest policy,Collection<JavaEvidenceAuthority.Verified> proofs){
        Objects.requireNonNull(plan);Objects.requireNonNull(policy);proofs=List.copyOf(proofs);
        validateStructure(plan);
        var rows=new TreeMap<String,Row>();
        var capturedPlan=new ContentHandle(plan.identity(),CanonicalJson.write(plan).getBytes(StandardCharsets.UTF_8).length);
        for(var requirement:plan.schedule()){
            var matching=new ArrayList<JavaEvidenceAuthority.Verified>();boolean stale=false;
            for(var proof:proofs){
                if(!QUESTION.equals(proof.record().text("question"))||!subject(plan,requirement).value().equals(proof.record().text("subject"))||!scopeMatches(plan,requirement,proof))continue;
                try{
                    if(!policy.equals(proof.record().digest("policy"))||!proof.current()){stale=true;continue;}
                    if(proof.covers(capturedPlan)&&proof.covers(plan.ownership().ledger()))matching.add(proof);
                }catch(IOException unavailable){stale=true;}
            }
            var answers=matching.stream().map(JavaEvidenceAuthority.Verified::answer).collect(java.util.stream.Collectors.toSet());
            var blockers=requirement.prerequisites().stream().filter(id->rows.get(id).active()).toList();
            State state;String reason;
            if(answers.size()>1){state=State.CONFLICTED;reason="READINESS_PROVIDER_CONFLICT";}
            else if(stale&&answers.isEmpty()){state=State.REOPENED;reason="READINESS_EVIDENCE_REVOKED";}
            else if(!blockers.isEmpty()){state=State.PENDING;reason="READINESS_PREREQUISITE_PENDING";}
            else if(answers.equals(Set.of(CanonicalJson.sha256(answer(plan,requirement,State.SATISFIED))))){state=State.SATISFIED;reason="";}
            else if(Set.of(ReadinessPlan.Kind.BUILD_VALIDATION,ReadinessPlan.Kind.GENERATION).contains(requirement.kind())
                    &&answers.equals(Set.of(CanonicalJson.sha256(answer(plan,requirement,State.NOT_APPLICABLE))))){state=State.NOT_APPLICABLE;reason="";}
            else {state=State.PENDING;reason=answers.isEmpty()?"READINESS_PROOF_REQUIRED":"READINESS_ANSWER_MISMATCH";}
            rows.put(requirement.id(),new Row(state,reason,blockers,matching.stream().map(JavaEvidenceAuthority.Verified::identity).toList()));
        }
        var frontier=new ArrayList<String>();plan.selection().unknown().forEach(id->frontier.add("DISCOVERY:"+id));
        plan.selection().unselected().forEach(id->frontier.add("UNSELECTED:"+id));
        for(String kind:List.of("AMBIGUOUS","UNOWNED","UNAVAILABLE"))if(plan.ownership().counts().getOrDefault(kind,0L)>0)frontier.add("OWNERSHIP:"+kind);
        if(rows.isEmpty())frontier.add("READINESS_REQUIREMENT_CENSUS_EMPTY");
        if(plan.selection().selected().isEmpty())frontier.add("SELECTED_CONTEXT_CENSUS_EMPTY");
        for(var root:plan.roots()){
            if(plan.contexts().stream().noneMatch(c->c.root().equals(root.id())))frontier.add("ROOT_WITHOUT_CONTEXT:"+root.id());
            for(var kind:List.of(ReadinessPlan.Kind.MODEL,ReadinessPlan.Kind.TOOLCHAIN,ReadinessPlan.Kind.DEPENDENCIES))
                if(plan.requirements().stream().noneMatch(r->r.subject().equals(root.id())&&r.kind()==kind))frontier.add("ROOT_REQUIREMENT_MISSING:"+root.id()+":"+kind.name());
        }
        for(var context:plan.contexts())for(var kind:List.of(ReadinessPlan.Kind.PLATFORM,ReadinessPlan.Kind.GENERATION,
                context.buildable()?ReadinessPlan.Kind.BUILD_VALIDATION:ReadinessPlan.Kind.SEMANTIC_VALIDATION))
            if(plan.requirements().stream().noneMatch(r->r.subject().equals(context.id())&&r.kind()==kind))frontier.add("CONTEXT_REQUIREMENT_MISSING:"+context.id()+":"+kind.name());
        var contexts=new TreeMap<String,ContextRow>();
        var scoped=new HashSet<String>();plan.roots().forEach(r->scoped.add(r.id()));plan.contexts().forEach(c->scoped.add(c.id()));
        for(var context:plan.contexts()){
            var required=plan.requirements().stream().filter(r->r.subject().equals(context.id())||r.subject().equals(context.root())||!scoped.contains(r.subject())).map(ReadinessPlan.Requirement::id).sorted().toList();
            boolean selected=plan.selection().selected().contains(context.id());
            if(required.isEmpty())frontier.add("CONTEXT_REQUIREMENT_CENSUS_EMPTY:"+context.id());
            contexts.put(context.id(),new ContextRow(context.root(),context.sourceSet(),selected,required,selected&&!required.isEmpty()&&required.stream().noneMatch(id->rows.get(id).active())&&frontier.isEmpty()));
        }
        if(!frontier.isEmpty())contexts.replaceAll((id,row)->new ContextRow(row.root(),row.sourceSet(),row.selected(),row.requirements(),false));
        return new View(plan,policy,rows,contexts,frontier);
    }
    public static ContentDigest subject(ReadinessPlan plan,ReadinessPlan.Requirement requirement){
        return CanonicalJson.sha256(Map.of("schema","java-readiness-requirement-key:m8.4-v1","readiness",plan.identity(),"requirement",CanonicalJson.sha256(requirement)));
    }
    public static Map<String,Object> answer(ReadinessPlan plan,ReadinessPlan.Requirement requirement,State state){
        if(state!=State.SATISFIED&&state!=State.NOT_APPLICABLE)throw new IllegalArgumentException("JAVA_READINESS_TERMINAL_ANSWER_REQUIRED");
        return Map.of("schema","java-readiness-requirement-answer:m8.4-v1","readiness",plan.identity(),"inventory",plan.inventory().inventory(),"selection",plan.selection().identity(),
                "requirement",CanonicalJson.sha256(requirement),"kind",requirement.kind(),"subject",requirement.subject(),"state",state,"ownership",plan.ownership().ledger());
    }
    private static boolean scopeMatches(ReadinessPlan plan,ReadinessPlan.Requirement requirement,JavaEvidenceAuthority.Verified proof){
        for(var context:plan.contexts())if(context.identity().value().equals(proof.record().object("scope").get("context"))){
            if(plan.roots().stream().anyMatch(r->r.id().equals(requirement.subject()))&&!context.root().equals(requirement.subject()))return false;
            if(plan.contexts().stream().anyMatch(c->c.id().equals(requirement.subject()))&&!context.id().equals(requirement.subject()))return false;
            var module=plan.modules().stream().filter(m->m.id().equals(context.module())).findFirst().orElseThrow();
            try{return JavaEvidenceScopes.scope(plan,context,ModuleDescriptor.create(plan.inventory().repository(),module.directory(),module.id())).equals(proof.record().object("scope"));}
            catch(IOException|IllegalArgumentException invalid){return false;}
        }
        return false;
    }
    private static void validateStructure(ReadinessPlan plan){
        var roots=new HashSet<String>();for(var root:plan.roots())if(!roots.add(root.id()))throw new IllegalArgumentException("JAVA_READINESS_DUPLICATE_ROOT");
        var modules=new HashMap<String,ReadinessPlan.Module>();for(var module:plan.modules())if(modules.putIfAbsent(module.id(),module)!=null||!roots.contains(module.root()))throw new IllegalArgumentException("JAVA_READINESS_MODULE_ROOT");
        var contexts=new HashSet<String>();for(var context:plan.contexts())if(!contexts.add(context.id())||!modules.containsKey(context.module())||!modules.get(context.module()).root().equals(context.root()))throw new IllegalArgumentException("JAVA_READINESS_CONTEXT_SCOPE");
    }
}
