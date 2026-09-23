package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.ingestion.IngestionEvidence;
import com.evolution.analysis.spring.condition.*;
import com.evolution.analysis.spring.binding.*;
import com.evolution.analysis.spring.universal.UniversalSpringEvidence;
import java.util.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.*;

/** Symbolic exogenous truth regions over the exact finite-domain contract, without world enumeration. */
public final class SymbolicTruthRegions {
    public record Truth(Formula yes, Formula no) {
        public Truth { Objects.requireNonNull(yes); Objects.requireNonNull(no); }
        public Formula unknown() { return not(SymbolicConfiguration.or(yes,no)); }
        public Truth and(Truth other) { return new Truth(SymbolicConfiguration.and(yes,other.yes),SymbolicConfiguration.or(no,other.no)); }
        public Truth or(Truth other) { return new Truth(SymbolicConfiguration.or(yes,other.yes),SymbolicConfiguration.and(no,other.no)); }
        public Truth negate() { return new Truth(no,yes); }
    }
    private static final Truth T=new Truth(TRUE,FALSE), F=new Truth(FALSE,TRUE), U=new Truth(FALSE,FALSE);
    public record Witness(LogicalValue expected, ConfigurationAssignment assignment, ContentDigest replay) {
        public ContentDigest identity() { return IngestionEvidence.digest(List.of(expected,assignment.identity(),replay)); }
    }
    public record Row(ConditionOccurrence.Identity occurrence, ContentDigest trueRegion, ContentDigest falseRegion,
                      ContentDigest unknownRegion, TruthRegionEvaluation.Classification classification,
                      List<Witness> witnesses) { public Row { witnesses=List.copyOf(witnesses); } }
    public record Result(ContentDigest inputIdentity, ConfigurationSpaceIdentity space,
                         ConfigurationReasoner.Satisfiability feasibility, List<Row> rows,
                         List<Answer> queries,List<Node> formulas, List<UniversalSpringEvidence.Issue> issues,
                         List<CapabilityGapRecord> gaps) {
        public Result { rows=List.copyOf(rows);queries=List.copyOf(queries);formulas=List.copyOf(formulas);issues=List.copyOf(issues);gaps=List.copyOf(gaps); }
        public ContentDigest identity() { return IngestionEvidence.digest(List.of(inputIdentity,space,feasibility,
                rows.stream().map(r->List.of(r.occurrence(),r.trueRegion(),r.falseRegion(),r.unknownRegion(),r.classification(),
                        r.witnesses().stream().map(Witness::identity).toList())).toList(),queries,formulas,issues,gaps)); }
    }
    public static Result evaluate(ConditionModel model, ConditionExpression.Semantics semantics,
                                  ConfigurationReasoner reasoner, Limits limits) {
        return new Compilation(model,semantics,reasoner,limits).run();
    }
    public record Trace(ContentDigest region,ConfigurationAssignment representative,ContentDigest bindingResult,
                        TruthRegionEvaluation.OperationalStatus operation,Map<ConditionalFactKey.Identity,LogicalValue> facts,boolean replayed) {
        public Trace {facts=Collections.unmodifiableMap(new TreeMap<>(facts));}
        public Object canonicalForm(){return List.of(region,representative.identity(),bindingResult,operation,facts.entrySet().stream().map(e->List.of(e.getKey(),e.getValue())).toList(),replayed);}
    }
    public record FactRegion(ConditionalFactKey fact,ContentDigest trueRegion,ContentDigest falseRegion,ContentDigest unknownRegion,
                             TruthRegionEvaluation.Classification classification) {}
    public record StagedResult(ContentDigest inputIdentity,boolean signaturesComplete,List<Trace> traces,List<FactRegion> regions,
                               List<Node> formulas,List<Answer> queries,List<UniversalSpringEvidence.Issue> issues,List<CapabilityGapRecord> gaps) {
        public StagedResult {traces=List.copyOf(traces);regions=List.copyOf(regions);formulas=List.copyOf(formulas);queries=List.copyOf(queries);issues=List.copyOf(issues);gaps=List.copyOf(gaps);}
        public ContentDigest identity(){return IngestionEvidence.digest(List.of(inputIdentity,signaturesComplete,traces.stream().map(Trace::canonicalForm).toList(),regions,formulas,queries,issues,gaps));}
    }
    /** SAT partitions exogenous atom truth signatures; each representative executes the unchanged staged M4C engine.
     * Stateful conditions are never independently guessed. Limits leave an explicit unknown residual region. */
    public static StagedResult evaluateStaged(TruthRegionEvaluation.Request request,int maxSignatures,Limits limits) {
        if(maxSignatures<1)throw new IllegalArgumentException("Positive signature limit required");
        var c=new Compilation(request.conditionModel(),request.conditionSemantics(),request.reasoner(),limits);
        var input=IngestionEvidence.digest(List.of("spring.symbolic-staged:m4u.2-v1",c.input,request.bindingPlan().identity(),request.baseline().identity(),
                request.limits(),request.evaluatorArtifactDigest(),maxSignatures));
        c.prepare();
        var dependencies=new HashMap<ContentDigest,InjectionBindingPlan.Dependency>();request.bindingPlan().dependencies().forEach(d->dependencies.put(d.identity(),d));
        var facts=TruthRegionEvaluation.deriveFacts(request.bindingPlan(),dependencies);
        var lowering=request.bindingPlan().registrationPlan().discoveryPlan().lowering();
        var modelOccurrences=request.conditionModel().sourceRows().stream().map(ConditionOccurrence::identity).collect(java.util.stream.Collectors.toSet());
        boolean compatible=request.bindingPlan().registrationPlan().discoveryPlan().buildContext().identity().equals(c.space.buildContext().identity())
                &&lowering.semantics().equals(request.conditionSemantics())&&modelOccurrences.containsAll(lowering.occurrences().stream().map(ConditionOccurrence::identity).toList());
        if(!compatible)c.issue(UniversalSpringEvidence.Reason.EVIDENCE_CONFLICT,"staged-context");
        Truth feasibility=T;for(var constraint:c.space.constraints())feasibility=feasibility.and(c.evidenced(constraint.declarationEvidenceKey())?c.expression(constraint.expression(),0):c.unknown("constraint-evidence"));
        if(c.incomplete||!compatible)feasibility=feasibility.and(U);
        Formula legal=and(c.constraints),feasible=and(legal,feasibility.yes()),possible=and(legal,not(feasibility.no()));
        Formula remaining=feasible;boolean complete=false;
        var atoms=new TreeMap<ConditionExpression.Identity,Truth>();var visited=new HashSet<ConditionExpression.Identity>();var pending=new ArrayDeque<ConditionExpression>();
        request.conditionModel().sourceRows().forEach(r->pending.add(r.expression()));
        while(!pending.isEmpty()) {
            var node=pending.removeFirst();if(!visited.add(node.identity()))continue;
            if(visited.size()>limits.maxNodes()){c.issue(UniversalSpringEvidence.Reason.SOLVER_LIMIT,"signature-basis");compatible=false;break;}
            if(node.children().isEmpty()&&node.dependencies().contains(ConditionExpression.Dependency.CONFIGURATION))atoms.put(node.identity(),c.expression(node,0));
            pending.addAll(node.children());
        }
        var traces=new ArrayList<Trace>();var gaps=new TreeSet<>(request.conditionModel().capabilityGaps());
        var yes=new TreeMap<ConditionalFactKey.Identity,List<Formula>>();var no=new TreeMap<ConditionalFactKey.Identity,List<Formula>>();
        facts.forEach(f->{yes.put(f.identity(),new ArrayList<>());no.put(f.identity(),new ArrayList<>());});
        if(facts.size()>request.limits().maxFacts()){compatible=false;c.issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"staged-facts");}
        long evaluatedCells=0;
        for(int signature=0;compatible&&signature<maxSignatures;signature++) {
            var answer=c.query(remaining);
            if(answer.status()==UNSATISFIABLE){complete=true;break;}
            if(answer.status()!=SATISFIABLE)break;
            if((evaluatedCells+=facts.size())>request.limits().maxRegionCells()){c.issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"staged-fact-cells");break;}
            var cube=new ArrayList<Formula>();
            for(var atom:atoms.values())cube.add(atom.yes().evaluate(answer.witness())?atom.yes():atom.no().evaluate(answer.witness())?atom.no():atom.unknown());
            Formula region=and(feasible,and(cube));
            var assignment=c.assignment(answer);
            var exogenous=ExogenousConditionEvaluator.evaluate(request.conditionModel(),request.conditionSemantics(),assignment,request.limits().enumeration().perAssignment());
            if(exogenous.assignmentFeasibility()!=LogicalValue.TRUE){c.issue(UniversalSpringEvidence.Reason.WITNESS_REPLAY_FAILED,"signature-assignment");break;}
            var binding=InjectionBindings.evaluate(request.bindingPlan(),request.conditionModel(),assignment,request.limits().enumeration().perAssignment());
            gaps.addAll(binding.capabilityGaps());var operation=TruthRegionEvaluation.operationalStatus(binding);
            boolean replayed=false;
            if(signature<request.limits().maxWitnessReplays()&&operation!=TruthRegionEvaluation.OperationalStatus.ERROR&&operation!=TruthRegionEvaluation.OperationalStatus.LIMIT_EXCEEDED) {
                var replay=InjectionBindings.evaluate(request.bindingPlan(),request.conditionModel(),assignment,request.limits().enumeration().perAssignment());
                replayed=binding.identity().equals(replay.identity());
                if(!replayed)c.issue(UniversalSpringEvidence.Reason.WITNESS_REPLAY_FAILED,"staged-trace");
            }else if(signature>=request.limits().maxWitnessReplays())c.issue(UniversalSpringEvidence.Reason.RESOURCE_LIMIT,"witness-replay-budget");
            var values=new TreeMap<ConditionalFactKey.Identity,LogicalValue>();
            for(var fact:facts) {
                LogicalValue value=TruthRegionEvaluation.factTruth(fact,binding,dependencies);values.put(fact.identity(),value);
                if(value==LogicalValue.TRUE)yes.get(fact.identity()).add(region);else if(value==LogicalValue.FALSE)no.get(fact.identity()).add(region);
            }
            traces.add(new Trace(region.identity(),assignment,binding.identity(),operation,values,replayed));
            remaining=and(remaining,not(and(cube)));
        }
        if(!complete&&compatible&&c.query(remaining).status()==UNSATISFIABLE)complete=true;
        if(!complete)c.issue(UniversalSpringEvidence.Reason.SOLVER_LIMIT,"signature-residual");
        if(c.query(and(legal,feasibility.unknown())).status()!=UNSATISFIABLE){complete=false;c.issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"feasibility-residual");}
        var regions=new ArrayList<FactRegion>();var exists=c.query(feasible);
        for(var fact:facts) {
            Formula y=or(yes.get(fact.identity())),n=or(no.get(fact.identity())),u=and(possible,not(or(y,n)));
            var ya=c.query(y);var na=c.query(n);var ua=c.query(u);var classification=TruthRegionEvaluation.Classification.UNKNOWN;
            if(compatible&&exists.status()==SATISFIABLE&&ua.status()==UNSATISFIABLE) {
                if(ya.status()==SATISFIABLE&&na.status()==UNSATISFIABLE)classification=TruthRegionEvaluation.Classification.MUST;
                else if(ya.status()==UNSATISFIABLE&&na.status()==SATISFIABLE)classification=TruthRegionEvaluation.Classification.NEVER;
                else if(ya.status()==SATISFIABLE&&na.status()==SATISFIABLE)classification=TruthRegionEvaluation.Classification.MAY;
            }
            regions.add(new FactRegion(fact,y.identity(),n.identity(),u.identity(),classification));
        }
        var issues=c.issues.stream().distinct().sorted(Comparator.comparing(UniversalSpringEvidence.Issue::identity)).toList();
        gaps.addAll(UniversalSpringEvidence.gaps(c.space.buildContext().snapshotIdentity(),input,issues));
        return new StagedResult(input,complete,traces,regions,List.copyOf(c.formulas.values()),c.queries,issues,List.copyOf(gaps));
    }
    private record Choice(FiniteDomain.Variable variable, FiniteDomain.Value value,
                          Optional<ConfigurationSpace.SourceReference> source) {}
    private static final class Compilation {
        final ConditionModel model; final ConfigurationSpace space; final ConditionExpression.Semantics semantics;
        final ConfigurationReasoner reasoner;final Limits limits;final ContentDigest input;
        final List<UniversalSpringEvidence.Issue> issues=new ArrayList<>();final List<Answer> queries=new ArrayList<>();
        final Map<ContentDigest,Node> formulas=new TreeMap<>();
        final List<Formula> constraints=new ArrayList<>();final Map<String,Choice> choices=new TreeMap<>();
        final Map<FiniteDomain.Variable,Map<FiniteDomain.Value,Formula>> environment=new TreeMap<>();
        final Map<String,Truth> profiles=new TreeMap<>();final Map<ConditionExpression.Identity,Truth> compiled=new HashMap<>();
        boolean incomplete;
        Compilation(ConditionModel model,ConditionExpression.Semantics semantics,ConfigurationReasoner reasoner,Limits limits) {
            this.model=Objects.requireNonNull(model);space=model.space();this.semantics=Objects.requireNonNull(semantics);
            this.reasoner=Objects.requireNonNull(reasoner);this.limits=Objects.requireNonNull(limits);
            input=IngestionEvidence.digest(List.of("spring.symbolic-regions:m4u.2-v1",model.identity(),semantics,reasoner.policy(),limits));
        }
        Result run() {
            prepare();
            var feasible=T;
            for(var occurrence:space.constraints())feasible=feasible.and(evidenced(occurrence.declarationEvidenceKey())?expression(occurrence.expression(),0):unknown("constraint-evidence"));
            if(incomplete)feasible=feasible.and(U);
            Formula legal=and(constraints), possible=and(legal,not(feasible.no()));
            Answer feasibility=query(and(legal,feasible.yes()));
            var possibleFeasibility=query(possible);
            var feasibilityStatus=feasibility.status()==SATISFIABLE?SATISFIABLE:possibleFeasibility.status()==UNSATISFIABLE?UNSATISFIABLE:UNKNOWN;
            var rows=new ArrayList<Row>();
            for(var occurrence:model.sourceRows()) {
                Truth truth=evidenced(occurrence.declarationEvidenceKey())?expression(occurrence.expression(),0):U;
                Formula yes=and(legal,feasible.yes(),truth.yes()), no=and(legal,feasible.yes(),truth.no());
                Formula unknown=and(possible,or(feasible.unknown(),truth.unknown()));
                Answer y=query(yes), n=query(no), u=query(unknown);
                var classification=TruthRegionEvaluation.Classification.UNKNOWN;
                if(feasibility.status()==SATISFIABLE&&u.status()==UNSATISFIABLE) {
                    if(y.status()==SATISFIABLE&&n.status()==UNSATISFIABLE)classification=TruthRegionEvaluation.Classification.MUST;
                    else if(y.status()==UNSATISFIABLE&&n.status()==SATISFIABLE)classification=TruthRegionEvaluation.Classification.NEVER;
                    else if(y.status()==SATISFIABLE&&n.status()==SATISFIABLE)classification=TruthRegionEvaluation.Classification.MAY;
                }
                var witnesses=new ArrayList<Witness>();
                replay(occurrence,y,LogicalValue.TRUE,witnesses);replay(occurrence,n,LogicalValue.FALSE,witnesses);
                if((y.status()==SATISFIABLE?1:0)+(n.status()==SATISFIABLE?1:0)!=witnesses.size())classification=TruthRegionEvaluation.Classification.UNKNOWN;
                rows.add(new Row(occurrence.identity(),yes.identity(),no.identity(),unknown.identity(),classification,witnesses));
            }
            var sorted=issues.stream().distinct().sorted(Comparator.comparing(UniversalSpringEvidence.Issue::identity)).toList();
            var gaps=new TreeSet<>(model.capabilityGaps());gaps.addAll(UniversalSpringEvidence.gaps(space.buildContext().snapshotIdentity(),input,sorted));
            return new Result(input,space.identity(),feasibilityStatus,rows,queries,List.copyOf(formulas.values()),sorted,List.copyOf(gaps));
        }
        void prepare() {
            if(space.domains().size()>limits.maxNodes()) {incomplete=true;issue(UniversalSpringEvidence.Reason.SOLVER_LIMIT,"domains");return;}
            for(var domain:space.domains()) {
                environment.put(domain.variable(),selection(domain.variable(),domain.values(),Optional.empty()));
                if(!evidenced(domain.evidence())) {environment.put(domain.variable(),Map.of());incomplete=true;}
            }
            if(!ExogenousConditionEvaluator.SEMANTICS.equals(semantics.version())
                    || !ExogenousConditionEvaluator.PROFILES.equals(space.profilePolicy().version())
                    || !ExogenousConditionEvaluator.PRECEDENCE.equals(space.precedencePolicy().version())
                    || !evidenced(space.profilePolicy().evidence()) || !evidenced(space.precedencePolicy().evidence())
                    || !evidenced(space.feasibilityPolicy().evidence())) {incomplete=true;issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"policy");}
            for(var entry:environment.entrySet())if(entry.getKey().kind()==FiniteDomain.Kind.PROFILE) {
                Formula yes=entry.getValue().getOrDefault(FiniteDomain.Value.bool(true),FALSE);
                Formula no=entry.getValue().getOrDefault(FiniteDomain.Value.bool(false),FALSE);
                profiles.put(entry.getKey().name(),new Truth(yes,no));
            }
            var policy=space.profilePolicy();var required=new TreeSet<>(policy.defaultProfiles());required.addAll(policy.includes());
            policy.groups().forEach((k,v)->{required.add(k);required.addAll(v);});
            if(!profiles.keySet().containsAll(required)){profiles.replaceAll((k,v)->U);incomplete=true;issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"profile-domains");}
            else {
                policy.includes().forEach(p->profiles.put(p,T));expandGroups();
                Truth any=F;for(var value:profiles.values())any=any.or(value);
                for(String name:policy.defaultProfiles())profiles.put(name,profiles.get(name).or(any.negate()));
                expandGroups();
            }
            var sources=new HashMap<ConfigurationSpace.SourceReference,ConfigurationEnvelope.DeclaredSource>();
            for(var envelope:space.envelopes()) {
                for(var source:envelope.declaredSources())sources.put(new ConfigurationSpace.SourceReference(envelope.layer(),source.identity()),source);
                for(var document:envelope.documents())if(envelope.declaredSources().stream().noneMatch(s->s.evidence().equals(document.evidence()))
                        && document.availability()!=ConfigurationEnvelope.Availability.AVAILABLE
                        && document.availability()!=ConfigurationEnvelope.Availability.MISSING_OPTIONAL) {incomplete=true;issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"unplaced-document");}
            }
            if(!sources.keySet().equals(new HashSet<>(space.precedencePolicy().lowToHigh()))) {incomplete=true;issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"source-precedence");}
            else for(var reference:space.precedencePolicy().lowToHigh())applySource(reference,sources.get(reference));
            compiled.clear();
        }
        Map<FiniteDomain.Value,Formula> selection(FiniteDomain.Variable variable,List<FiniteDomain.Value> values,Optional<ConfigurationSpace.SourceReference> source) {
            var selected=new LinkedHashMap<FiniteDomain.Value,Formula>();var formulas=new ArrayList<Formula>();
            if(values.size()>limits.maxNodes()||choices.size()+values.size()>limits.maxNodes()) {incomplete=true;issue(UniversalSpringEvidence.Reason.SOLVER_LIMIT,"domain-values");return selected;}
            for(var value:values) {
                String key=IngestionEvidence.digest(List.of(variable,value,source)).value();var formula=variable(key);
                choices.put(key,new Choice(variable,value,source));selected.put(value,formula);formulas.add(formula);
                if(value.kind()==FiniteDomain.ValueKind.OTHER){incomplete=true;issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"other-value");}
            }
            constraints.add(or(formulas));
            // Linear sequential at-most-one encoding, avoiding quadratic clauses for large finite domains.
            Formula previous=FALSE;
            for(var formula:formulas){constraints.add(not(and(previous,formula)));previous=or(previous,formula);}
            return selected;
        }
        void expandGroups() {
            var original=new TreeMap<>(profiles);
            for(String source:original.keySet()) {
                var reached=new TreeSet<String>();var queue=new ArrayDeque<String>();queue.add(source);
                while(!queue.isEmpty())for(String target:space.profilePolicy().groups().getOrDefault(queue.removeFirst(),List.of()))
                    if(reached.add(target))queue.addLast(target);
                for(String target:reached)profiles.put(target,profiles.get(target).or(original.get(source)));
            }
        }
        Truth guard(Optional<ConditionExpression.Identity> id) {
            if(id.isEmpty())return T;
            var occurrence=model.sourceRows().stream().filter(r->r.expression().identity().equals(id.orElseThrow())).findFirst();
            if(occurrence.isEmpty()||!evidenced(occurrence.orElseThrow().declarationEvidenceKey()))return U;
            var expression=occurrence.orElseThrow().expression();
            if(expression.dependencies().contains(ConditionExpression.Dependency.BEAN_STATE)||expression.dependencies().contains(ConditionExpression.Dependency.OPAQUE))return U;
            return expression(expression,0);
        }
        void applySource(ConfigurationSpace.SourceReference ref,ConfigurationEnvelope.DeclaredSource source) {
            if(source.availability()==ConfigurationEnvelope.Availability.MISSING_OPTIONAL)return;
            Truth active=guard(source.activation());
            for(var envelope:space.envelopes())if(envelope.layer()==ref.layer())for(var doc:envelope.documents())if(doc.evidence().equals(source.evidence())) {
                active=active.and(guard(doc.activation()));
                if(doc.availability()==ConfigurationEnvelope.Availability.MISSING_OPTIONAL)active=F;
                else if(doc.availability()!=ConfigurationEnvelope.Availability.AVAILABLE||!evidenced(doc.evidence()))active=active.and(U);
            }
            if(source.availability()!=ConfigurationEnvelope.Availability.AVAILABLE||!evidenced(source.evidence())
                    ||!ExogenousConditionEvaluator.CONVERSION.equals(source.conversionPolicy()))active=active.and(U);
            if(query(and(and(constraints),active.unknown())).status()!=UNSATISFIABLE) {
                incomplete=true;
                issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"source:"+source.identity().value());
            }
            for(var entry:source.values().entrySet()) {
                var old=environment.get(entry.getKey());
                if(old==null||entry.getKey().kind()!=FiniteDomain.Kind.PROPERTY) {incomplete=true;issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"source-domain");continue;}
                var domain=space.domains().stream().filter(d->d.variable().equals(entry.getKey())).findFirst().orElseThrow();
                if(!domain.values().containsAll(entry.getValue())) {incomplete=true;environment.put(entry.getKey(),Map.of());continue;}
                var incoming=selection(entry.getKey(),entry.getValue(),Optional.of(ref));
                Formula missing=incoming.getOrDefault(FiniteDomain.Value.missing(),FALSE);
                var effective=new LinkedHashMap<FiniteDomain.Value,Formula>();
                for(var value:domain.values()) {
                    var prior=old.getOrDefault(value,FALSE);var supplied=incoming.getOrDefault(value,FALSE);
                    Formula selected=value.kind()==FiniteDomain.ValueKind.MISSING?FALSE:and(active.yes(),supplied);
                    effective.put(value,or(selected,and(prior,or(active.no(),missing)),
                            value.kind()==FiniteDomain.ValueKind.MISSING?FALSE:and(prior,supplied,active.unknown())));
                }
                environment.put(entry.getKey(),effective);
            }
            compiled.clear();
        }
        Truth expression(ConditionExpression expression,int depth) {
            var cached=compiled.get(expression.identity());if(cached!=null)return cached;
            if(depth>=limits.maxDepth()||compiled.size()>=limits.maxNodes()) {issue(UniversalSpringEvidence.Reason.SOLVER_LIMIT,"expression");return U;}
            if(!semantics.equals(expression.semantics()))return unknown("semantics");
            Truth truth;
            if(expression.operator()==ConditionExpression.Operator.ALL||expression.operator()==ConditionExpression.Operator.ANY) {
                truth=expression.operator()==ConditionExpression.Operator.ALL?T:F;
                for(var child:expression.children())truth=expression.operator()==ConditionExpression.Operator.ALL?
                        truth.and(expression(child,depth+1)):truth.or(expression(child,depth+1));
            }else if(expression.operator()==ConditionExpression.Operator.NOT)truth=expression(expression.children().getFirst(),depth+1).negate();
            else truth=switch(expression.operand()) {
                case ConditionExpression.Constant c -> constant(c.value());
                case ConditionExpression.Profile p -> profiles.containsKey(p.name())?profiles.get(p.name()):unknown("profile:"+p.name());
                case ConditionExpression.Property p -> property(p);
                case ConditionExpression.BasicSpel s -> spel(s);
                case ConditionExpression.Web w -> matching(new FiniteDomain.Variable(FiniteDomain.Kind.WEB_MODE,"spring.web-mode"),v->v.webMode().map(m->m==w.mode()).orElse(false));
                case ConditionExpression.Build b -> b.buildContext().equals(space.buildContext().identity())&&evidenced(b.evidence())?constant(b.observedValue()):unknown("build");
                default -> unknown(expression.identity().value());
            };
            compiled.put(expression.identity(),truth);return truth;
        }
        Truth property(ConditionExpression.Property property) {
            if(!property.prefix().equals(property.prefix().trim()))return unknown("property-prefix");
            Truth truth=T;
            for(String key:property.keys()) {
                if(key.contains("${")||key.contains("[")||key.contains("]"))return unknown("property-selector");
                var variable=new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,key);
                var values=environment.get(variable);if(values==null){truth=truth.and(unknown("property-domain"));continue;}
                var yes=new ArrayList<Formula>();var no=new ArrayList<Formula>();
                for(var entry:values.entrySet()) {
                    var v=entry.getKey();Boolean match=null;
                    if(v.kind()==FiniteDomain.ValueKind.MISSING)match=property.matchIfMissing();
                    else if(v.kind()==FiniteDomain.ValueKind.EXACT) {
                        String text=v.exact().orElseThrow();
                        if(text.chars().allMatch(c->c<128)&&property.havingValue().chars().allMatch(c->c<128)&&!text.contains("${"))
                            match=property.havingValue().isEmpty()?!text.equalsIgnoreCase("false"):text.equalsIgnoreCase(property.havingValue());
                    }
                    if(match==null)issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,"property-value");
                    else (match?yes:no).add(entry.getValue());
                }
                truth=truth.and(new Truth(or(yes),or(no)));
            }
            return truth;
        }
        Truth spel(ConditionExpression.BasicSpel expression) {
            var keys=com.evolution.analysis.spring.universal.BasicSpelEvaluator.propertyKeys(expression.expression());
            if(keys.isEmpty()||!expression.policy().equals(new VersionedIdentifier("spring.basic-spel","m4u.2-v1")))return unknown("spel-policy");
            var variables=keys.orElseThrow().stream().map(k->new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY,k)).toList();
            long cells=1;
            for(var variable:variables){var values=environment.get(variable);if(values==null)return unknown("spel-domain");cells*=values.size();if(cells>limits.maxNodes())return unknown("spel-atom-budget");}
            var cases=new ArrayList<SpelCase>();cases.add(new SpelCase(TRUE,Map.of(),true));
            for(var variable:variables) {
                var next=new ArrayList<SpelCase>();
                for(var prefix:cases)for(var value:environment.get(variable).entrySet()) {
                    var properties=new TreeMap<>(prefix.values());value.getKey().exact().ifPresent(v->properties.put(variable.name(),v));
                    next.add(new SpelCase(and(prefix.region(),value.getValue()),properties,prefix.known()&&value.getKey().kind()!=FiniteDomain.ValueKind.OTHER));
                }
                cases=next;
            }
            var yes=new ArrayList<Formula>();var no=new ArrayList<Formula>();
            for(var item:cases)if(item.known()) {
                var evaluated=com.evolution.analysis.spring.universal.BasicSpelEvaluator.evaluate(expression.expression(),item.values(),UniversalSpringEvidence.derived(input,"spel-atom"),
                        space.buildContext().snapshotIdentity(),com.evolution.analysis.spring.universal.BasicSpelEvaluator.Limits.defaults());
                if(evaluated.value()==LogicalValue.TRUE)yes.add(item.region());else if(evaluated.value()==LogicalValue.FALSE)no.add(item.region());else unknown("spel-atom");
            }
            return new Truth(or(yes),or(no));
        }
        private record SpelCase(Formula region,Map<String,String> values,boolean known) {}
        Truth matching(FiniteDomain.Variable variable,java.util.function.Predicate<FiniteDomain.Value> predicate) {
            var values=environment.get(variable);if(values==null)return unknown("missing-domain");
            var yes=new ArrayList<Formula>();var no=new ArrayList<Formula>();values.forEach((v,f)->(predicate.test(v)?yes:no).add(f));
            return new Truth(or(yes),or(no));
        }
        Truth constant(LogicalValue value){return switch(value){case TRUE->T;case FALSE->F;case UNKNOWN->unknown("logical-unknown");};}
        Truth unknown(String subject){issue(UniversalSpringEvidence.Reason.SYMBOLIC_INPUT_UNKNOWN,subject);return U;}
        boolean evidenced(ConditionEvidence evidence) {return !(evidence instanceof ConditionEvidence.Source s)||s.span().isPresent()&&space.buildContext().containsSource(s);}
        Answer query(Formula formula) {
            var pending=new ArrayDeque<Formula>();pending.add(formula);
            while(!pending.isEmpty()){var next=pending.removeFirst();if(formulas.putIfAbsent(next.identity(),next.view())==null)pending.addAll(next.children());}
            var answer=reasoner.solve(new Query(formula,limits));queries.add(answer);
            if(answer.status()==UNKNOWN)issue(UniversalSpringEvidence.Reason.SOLVER_LIMIT,answer.query().value());return answer;
        }
        void replay(ConditionOccurrence occurrence,Answer answer,LogicalValue expected,List<Witness> output) {
            if(answer.status()!=SATISFIABLE)return;
            var assignment=assignment(answer);
            var replay=ExogenousConditionEvaluator.evaluate(model,semantics,assignment,ExogenousConditionEvaluator.Limits.conservative());
            boolean valid=replay.assignmentFeasibility()==LogicalValue.TRUE&&replay.rows().stream().anyMatch(r->r.occurrence().equals(occurrence.identity())&&r.truth()==expected);
            if(valid)output.add(new Witness(expected,assignment,replay.identity()));
            else issue(UniversalSpringEvidence.Reason.WITNESS_REPLAY_FAILED,occurrence.identity().value());
        }
        ConfigurationAssignment assignment(Answer answer) {
            var baseline=new TreeMap<FiniteDomain.Variable,FiniteDomain.Value>();var sourceChoices=new ArrayList<ConfigurationAssignment.SourceChoice>();
            choices.forEach((key,choice)->{if(answer.witness().getOrDefault(key,false)) {
                if(choice.source().isEmpty())baseline.put(choice.variable(),choice.value());
                else sourceChoices.add(new ConfigurationAssignment.SourceChoice(choice.source().orElseThrow(),choice.variable(),choice.value()));
            }});
            // Unconstrained single-domain variables may have been simplified out of the query.
            for(var domain:space.domains())baseline.putIfAbsent(domain.variable(),domain.values().getFirst());
            return new ConfigurationAssignment(baseline,sourceChoices,UniversalSpringEvidence.derived(input,"sat-witness"));
        }
        void issue(UniversalSpringEvidence.Reason reason,String subject) {issues.add(new UniversalSpringEvidence.Issue(reason,subject,List.of(UniversalSpringEvidence.derived(input,subject))));}
    }
    private SymbolicTruthRegions() {}
}
