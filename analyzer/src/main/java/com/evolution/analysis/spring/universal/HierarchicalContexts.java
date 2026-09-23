package com.evolution.analysis.spring.universal;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.registration.*;
import java.util.*;

/** Parent visibility over completed registration states, preserving child name shadowing and closure. */
public final class HierarchicalContexts {
    public record Context(String key,Optional<String> parent,BeanDefinitionState registry,ConditionEvidence evidence) {
        public Context {ContractChecks.text(key,"context key");Objects.requireNonNull(parent);Objects.requireNonNull(registry);Objects.requireNonNull(evidence);
            if(!key.equals(registry.containerKey()))throw new IllegalArgumentException("Registry container differs");}
    }
    public record Visible(String context,String name,BeanDefinitionCandidate.Identity candidate,List<String> aliases,ConditionEvidence evidence) {public Visible{aliases=List.copyOf(aliases);}}
    public record Result(ContentDigest inputIdentity,String requestedContext,List<Visible> definitions,boolean complete,
                         List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Result {definitions=List.copyOf(definitions);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public enum SelectionStatus { SELECTED, ABSENT, AMBIGUOUS, UNKNOWN }
    /** Whole-descriptor compatibility must be supplied by the M4C type/qualifier provider. */
    public record Match(BeanDefinitionCandidate.Identity candidate,LogicalValue eligible,LogicalValue primary,ConditionEvidence evidence) {}
    public record Selection(ContentDigest inputIdentity,SelectionStatus status,Optional<BeanDefinitionCandidate.Identity> selected,
                            List<BeanDefinitionCandidate.Identity> candidates,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public Selection {candidates=List.copyOf(candidates);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(this);}
    }
    public static Selection select(Result visibility,List<Match> matches,Optional<String> name,SnapshotIdentity snapshot) {
        var input=IngestionEvidence.digest(List.of("spring.hierarchical-selection:m4u.2-v1",visibility.identity(),matches.stream().sorted(Comparator.comparing(Match::candidate)).toList(),name));
        var issues=new ArrayList<UniversalSpringEvidence.Issue>();var byId=new HashMap<BeanDefinitionCandidate.Identity,Match>();boolean unknown=!visibility.complete();
        for(var match:matches)if(byId.putIfAbsent(match.candidate(),match)!=null){unknown=true;issue(issues,input,UniversalSpringEvidence.Reason.EVIDENCE_CONFLICT,match.candidate().value());}
        var eligible=new ArrayList<Visible>();
        for(var definition:visibility.definitions()) {
            if(name.isPresent()&&!definition.name().equals(name.orElseThrow())&&!definition.aliases().contains(name.orElseThrow()))continue;
            var match=byId.get(definition.candidate());
            if(match==null||match.eligible()==LogicalValue.UNKNOWN){unknown=true;continue;}
            if(match.eligible()==LogicalValue.TRUE){eligible.add(definition);if(match.primary()==LogicalValue.UNKNOWN)unknown=true;}
        }
        Optional<BeanDefinitionCandidate.Identity> selected=Optional.empty();SelectionStatus status;
        if(unknown){status=SelectionStatus.UNKNOWN;issue(issues,input,UniversalSpringEvidence.Reason.BINDING_UNKNOWN,"hierarchy-selection");}
        else if(eligible.isEmpty())status=SelectionStatus.ABSENT;
        else if(eligible.size()==1){status=SelectionStatus.SELECTED;selected=Optional.of(eligible.getFirst().candidate());}
        else {
            var primaries=eligible.stream().filter(d->byId.get(d.candidate()).primary()==LogicalValue.TRUE).toList();
            // Spring distinguishes local from inherited primaries, not distance between ancestors.
            var local=primaries.stream().filter(d->d.context().equals(visibility.requestedContext())).toList();
            var preferred=local.isEmpty()?primaries:local;
            if(preferred.size()==1){status=SelectionStatus.SELECTED;selected=Optional.of(preferred.getFirst().candidate());}
            else {status=SelectionStatus.AMBIGUOUS;issue(issues,input,UniversalSpringEvidence.Reason.BINDING_AMBIGUOUS,"hierarchy-selection");}
        }
        var gaps=new TreeSet<>(visibility.gaps());gaps.addAll(UniversalSpringEvidence.gaps(snapshot,input,issues));
        return new Selection(input,status,selected,eligible.stream().map(Visible::candidate).distinct().toList(),issues,List.copyOf(gaps));
    }
    public static Result visible(List<Context> contexts,String requested,ConditionExpression.Search search,SnapshotIdentity snapshot,int maxDepth) {
        if(maxDepth<1)throw new IllegalArgumentException("Positive hierarchy bound required");
        var ordered=contexts.stream().sorted(Comparator.comparing(Context::key)).toList();
        var input=IngestionEvidence.digest(List.of("spring.context-hierarchy:m4u.2-v1",ordered,requested,search,maxDepth));
        var issues=new ArrayList<UniversalSpringEvidence.Issue>();var byKey=new TreeMap<String,Context>();
        for(var context:ordered)if(byKey.putIfAbsent(context.key(),context)!=null)issue(issues,input,UniversalSpringEvidence.Reason.CONTEXT_HIERARCHY_INVALID,context.key());
        var definitions=new ArrayList<Visible>();var hidden=new HashSet<String>();var visited=new HashSet<String>();boolean complete=issues.isEmpty();String key=requested;int depth=0;
        while(key!=null) {
            if(++depth>maxDepth){issue(issues,input,UniversalSpringEvidence.Reason.RESOURCE_LIMIT,key);complete=false;break;}
            if(!visited.add(key)||!byKey.containsKey(key)){issue(issues,input,UniversalSpringEvidence.Reason.CONTEXT_HIERARCHY_INVALID,key);complete=false;break;}
            var context=byKey.get(key);boolean include=search!=ConditionExpression.Search.ANCESTORS||depth>1;
            if(include) {
                if(!context.registry().registryClosed()||!context.registry().executionEstablished()){complete=false;issue(issues,input,UniversalSpringEvidence.Reason.CONTEXT_INCOMPLETE,key);}
                var aliases=context.registry().aliases();var canonicalAliases=new TreeMap<String,String>();
                for(String alias:aliases.keySet()) {
                    String target=alias;var seen=new HashSet<String>();
                    while(aliases.containsKey(target)&&seen.add(target))target=aliases.get(target);
                    if(aliases.containsKey(target)||!context.registry().definitions().containsKey(target)){complete=false;issue(issues,input,UniversalSpringEvidence.Reason.CONTEXT_HIERARCHY_INVALID,alias);}
                    else canonicalAliases.put(alias,target);
                }
                for(var entry:context.registry().definitions().entrySet())if(!hidden.contains(entry.getKey())&&!aliases.containsKey(entry.getKey())) {
                    String name=entry.getKey();var names=canonicalAliases.entrySet().stream().filter(e->e.getValue().equals(name)&&!hidden.contains(e.getKey())).map(Map.Entry::getKey).toList();
                    definitions.add(new Visible(key,name,entry.getValue(),names,context.evidence()));
                }
                hidden.addAll(context.registry().definitions().keySet());hidden.addAll(aliases.keySet());
            }
            if(search==ConditionExpression.Search.CURRENT)break;key=context.parent().orElse(null);
        }
        if(!complete)issue(issues,input,UniversalSpringEvidence.Reason.CONTEXT_INCOMPLETE,requested);
        return new Result(input,requested,definitions,complete,issues,UniversalSpringEvidence.gaps(snapshot,input,issues));
    }
    private static void issue(List<UniversalSpringEvidence.Issue> issues,ContentDigest input,UniversalSpringEvidence.Reason reason,String subject){issues.add(new UniversalSpringEvidence.Issue(reason,subject,List.of(UniversalSpringEvidence.derived(input,subject))));}
    private HierarchicalContexts() {}
}
