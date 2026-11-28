package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.spring.condition.LogicalValue;
import com.evolution.analysis.spring.condition.ComponentScanIngestion;
import com.evolution.analysis.spring.condition.ConstructorInjectionIngestion;
import com.evolution.analysis.spring.condition.ConditionExpression;
import com.evolution.analysis.spring.binding.BindingProcessing;
import com.evolution.analysis.spring.registration.BeanRegistrationProcessing;
import com.evolution.analysis.spring.truth.TruthProcessing;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import com.evolution.analysis.spring.truth.ExhaustiveConfigurationReasoner;
import com.evolution.analysis.spring.truth.SatConfigurationReasoner;
import com.evolution.analysis.spring.truth.ConditionalFactKey;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SpringOperands.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** One authenticated Spring partition and its producer result. */
final class SpringRegionInput {
    record Operand(String id,ContentDigest evidence,Map<String,Object> raw) {}
    record World(Operand operand,SpringDomain.Assignment assignment,LogicalValue feasibility,Map<String,LogicalValue> facts) {}
    record Fact(String kind,String subject,String site,String target,String local,Operand region,String classification) {}
    private record Endpoint(Operand source,String key) {}
    final EvolutionInput side;
    final String context;
    final String analysis;
    final String encoding;
    final SpringDomain domain;
    final Map<String,Object> result;
    final List<ContentDigest> references;
    final Object semantics, order, workPolicy;
    final boolean complete, orderComplete;
    private final boolean emptyDemoBindingPlan;
    final Map<String,World> worlds = new TreeMap<>();
    final Map<String,Fact> facts=new TreeMap<>();
    final Map<String,Operand> unaligned=new TreeMap<>();
    final Map<String,Operand> witnesses=new TreeMap<>();
    final Map<String,Operand> replays=new TreeMap<>();
    final Map<String,String> policyEndpoints;
    private final Map<String,Operand> pending=new TreeMap<>();
    private final Set<String> authenticatedPolicyRegions=new HashSet<>();
    private final Map<String,Operand> formulaNodes=new HashMap<>();
    private final Map<String,Operand> candidates=new HashMap<>();
    private final Map<String,Operand> sites=new HashMap<>();
    private final Map<String,Formula> formulas=new HashMap<>();
    private final Map<String,List<Operand>> witnessesByRegion=new HashMap<>();
    private final NavigableMap<Long,World> orderedWorlds=new TreeMap<>();
    private final Map<String,Map<String,Map<String,Object>>> traceWitnesses=new HashMap<>();
    private boolean tracesRead;
    private Formula provedSymbolicUniverse;
    private final Map<String,Object> descriptor;

    SpringRegionInput(EvolutionInput side,Map<String,Object> partition,Limits limits)throws IOException {
        this.side=side;context=text(partition,"context");analysis=text(partition,"parentAnalysis");
        var contextEvidence=side.evidence(new ContentDigest(text(partition,"evidence")));
        descriptor=EvolutionInput.payload(contextEvidence);
        var planEvidence=required(side,"spring.plan",text(descriptor,"planIdentity"));
        var spaceEvidence=required(side,"spring.space",maybe(descriptor,"space"));
        var plan=EvolutionInput.payload(planEvidence);
        var registration=registration(plan);
        var discoveryInputs=discoveryInputFields(plan);
        var binding=obj(plan.get("binding"));
        if(descriptor.get("parameters")==null)throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNAVAILABLE);
        var parameters=obj(descriptor.get("parameters"));
        var build=text(discoveryInputs,"buildContextIdentity");
        var buildForm=obj(descriptor.get("build"));
        if(!build.equals(identity("spring-build-context",buildForm))||!side.header.snapshot().value().equals(buildForm.get("snapshotIdentity")))
            throw EvolutionInput.corrupt();
        domain=new SpringDomain(side,spaceEvidence);
        if(!build.equals(space(EvolutionInput.payload(spaceEvidence)).get("buildContextIdentity")))throw EvolutionInput.corrupt();
        encoding=parameters.get("symbolicLimits")==null?"explicit":"symbolic";
        if(!numBetween(parameters.get("maxSignatures"),0,Integer.MAX_VALUE))throw EvolutionInput.corrupt();
        var reasoner=encoding.equals("explicit")?ExhaustiveConfigurationReasoner.INSTANCE.policy():SatConfigurationReasoner.INSTANCE.policy();
        if(!CanonicalJson.write(reasoner).equals(CanonicalJson.write(parameters.get("reasonerPolicy"))))
            throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNSUPPORTED);
        semantics=row("condition",obj(plan.get("conditions")).get("semantics"),
                "catalog",fields(obj(plan.get("inventory")),"provider","mechanismCatalog","catalogRevision"),
                "truthLimits",parameters.get("limits"),
                "registrationLimits",registration.get("limits"),"bindingLimits",binding.get("limits"),
                "discoveryLimits",discoveryLimits(plan),
                "overrides",discoveryInputs.get("overridePolicy"),
                "bindingPolicy",fields(obj(binding.get("environment")),"comparator","noResolvableDependencies","noPostRegistrationMutation"));
        order=establishedOrderPrefix(side,plan);
        workPolicy=fields(parameters,"reasonerPolicy","symbolicLimits","maxSignatures","evaluatorArtifact");
        orderComplete="COMPLETE".equals(discoveryInputs.get("orderCompleteness"))&&"COMPLETE".equals(registration.get("order"))
                &&"COMPLETE".equals(registration.get("initialRegistry"))&&"COMPLETE".equals(registration.get("noParentContainer"));
        emptyDemoBindingPlan=side.run.bindingCensus.map(DemoBindingCensus::receipt)
                .filter(census -> side.graph.equals(census.graph()) && context.equals(census.context()))
                .filter(census -> registeredDemoClasspath(buildForm,census.classpathCensus()))
                .isPresent() && emptyDemoBindingPlan(plan,binding);

        scan(cursor->side.snapshot.nodes(context,side.run.size,cursor),number(partition.get("nodes")),side.run.size,side.run.cancelled,
                node->read(node,limits));
        String resultKey=encoding.equals("explicit")?context:symbolicInput(partition);
        result=SpringOperands.result(side,"spring."+encoding+"-result",resultKey)
                .orElseThrow(()->new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNAVAILABLE));
        if(encoding.equals("symbolic")) {
            if(!KnowledgeGraph.derivedIdentity("spring-context",List.of(analysis,descriptor.get("planIdentity"),domain.identity,resultKey,parameters)).equals(context))
                throw EvolutionInput.corrupt();
            complete=Boolean.TRUE.equals(result.get("signaturesComplete"));
        } else {
            var coverage=obj(result.get("coverage"));
            if(!"spring-truth-region-result-v1".equals(result.get("schema"))||
                    !CanonicalJson.write(TruthProcessing.PROVIDER).equals(CanonicalJson.write(result.get("provider")))||
                    !context.equals(explicitContext(plan,build,parameters))||!legalOrder(side,plan).equals(result.get("legalOrderIdentity"))||
                    number(result.get("worldsCount"))!=worlds.size()||number(coverage.get("enumeratedAssignments"))!=worlds.size()
                    ||number(result.get("witnessesCount"))!=witnesses.size()||number(result.get("replaysCount"))!=replays.size()
                    ||number(coverage.get("feasibleAssignments"))!=worlds.values().stream().filter(w->w.feasibility==LogicalValue.TRUE).count()
                    ||number(coverage.get("infeasibleAssignments"))!=worlds.values().stream().filter(w->w.feasibility==LogicalValue.FALSE).count()
                    ||number(coverage.get("unknownFeasibilityAssignments"))!=worlds.values().stream().filter(w->w.feasibility==LogicalValue.UNKNOWN).count())
                throw EvolutionInput.corrupt();
            var census=obj(coverage.get("declaredAssignments"));
            complete=Boolean.TRUE.equals(census.get("exact")) && number(census.get("value"))==worlds.size()
                    && !"INVALID_MODEL".equals(result.get("feasibility"));
            for(var world:worlds.values()) {
                long ordinal=ordinal(side,text(result,"evidence"),"worlds",exportWorld(world));
                if(ordinal<0||orderedWorlds.putIfAbsent(ordinal,world)!=null||
                        !identity("spring-world",row("semanticsContextIdentity",context,"fullAssignment",assignment(obj(world.operand.raw.get("assignment"))),
                                "legalOrderIdentity",result.get("legalOrderIdentity"),"branchEvidence",List.of())).equals(world.operand.id))
                    throw EvolutionInput.corrupt();
            }
        }
        if(number(result.get("regionsCount"))!=pending.size())throw EvolutionInput.corrupt();
        var refs=new ArrayList<ContentDigest>(domain.evidence);
        refs.add(contextEvidence.identity()); refs.add(planEvidence.identity());refs.add(new ContentDigest(text(result,"evidence")));
        references=List.copyOf(refs);
        var candidateEndpoints=endpoints(candidates,true,build);var siteEndpoints=endpoints(sites,false,build);
        var policyKeys=new HashMap<String,String>();
        candidateEndpoints.forEach((id,endpoint)->policyKeys.put(id,endpoint.key()));
        siteEndpoints.forEach((id,endpoint)->policyKeys.put(id,endpoint.key()));
        policyEndpoints=Map.copyOf(policyKeys);
        long regionCells=0;
        for(var region:pending.values()) {
            EvolutionSnapshot.check(side.run.cancelled);
            var raw=obj(region.raw.get("region"));
            if(!domain.identity.equals(region.raw.get("space"))||!member("regions",raw)
                    ||encoding.equals("symbolic")&&!resultKey.equals(region.raw.get("evaluationInput"))
                    ||encoding.equals("explicit")&&!context.equals(region.raw.get("semanticsContext")))throw EvolutionInput.corrupt();
            if((regionCells+=worlds.size())>SpringEvolution.MAX_CELLS) {
                unaligned.put(region.id,region);continue;
            }
            var fact=fact(obj(raw.get("fact")),region,candidateEndpoints,siteEndpoints);
            if(fact==null){unaligned.put(region.id,region);continue;}
            if(facts.putIfAbsent(fact.subject,fact)!=null)throw EvolutionInput.corrupt();
            authenticatedPolicyRegions.add(region.id);
        }
    }

    boolean authenticatedPolicyRegion(String identity) {return authenticatedPolicyRegions.contains(identity);}

    boolean supports(DemoBindingCensus.Receipt census) throws IOException {
        if (!complete || !orderComplete || !emptyDemoBindingPlan || !sites.isEmpty()
                || candidates.size()!=2 || policyEndpoints.size()!=2 || !domain.isClosedBooleanProfile(census.profile())
                || !side.header.snapshot().equals(census.snapshot()) || !side.graph.equals(census.graph())
                || !context.equals(census.context())) return false;
        var names=new TreeSet<String>();
        for (var candidate:candidates.values()) {
            var producer=obj(candidate.raw.get("producer"));
            var name=obj(candidate.raw.get("declaredNameKey"));
            String bean=text(name,"primary");
            if (!"COMPONENT_CLASS".equals(producer.get("producerKind")) || !"EXACT".equals(name.get("status"))
                    || !list(name.get("aliases")).isEmpty() || !census.beans().contains(bean) || !names.add(bean)) return false;
            String type=bean.equals("client")?"Client":"Store";
            String path="src/main/java/app/"+type+".java";
            var expected=census.files().stream().filter(file -> file.path().equals(path)).findFirst().orElseThrow();
            var declarations=sourceSupport(side,text(producer,"declarationSlot"),"java.declaration",analysis);
            if (declarations.size()!=1) return false;
            var declaration=obj(EvolutionInput.payload(declarations.getFirst()).get("entity"));
            if (!("java:v1:"+CanonicalJson.write(List.of("type",List.of("app"),type))).equals(declaration.get("canonicalName"))) return false;
            var documents=sourceSupport(side,text(obj(declaration.get("declaration")),"document"),"source.document",null);
            if (documents.size()!=1) return false;
            var doc=EvolutionInput.payload(documents.getFirst());
            if (!path.equals(doc.get("path")) || !expected.contentDigest().value().equals(doc.get("contentDigest"))) return false;
        }
        return names.equals(new TreeSet<>(census.beans()));
    }

    private static boolean registeredDemoClasspath(Map<String,Object> build,ContentDigest expected) {
        var entries=list(build.get("orderedResolutionInputs"));
        if (entries.size()!=9 || !"MAIN".equals(obj(build.get("moduleSourceSet")).get("sourceSet"))) return false;
        // The ordinary compatibility check separately requires the same ordering on both sides.
        var byName=entries.stream().map(EvolutionRows::obj)
                .sorted(Comparator.comparing(entry -> text(obj(entry.get("entry")),"logicalName"))).toList();
        return expected.equals(CanonicalJson.sha256(byName));
    }

    private static boolean emptyDemoBindingPlan(Map<String,Object> plan,Map<String,Object> binding) {
        if (isPagedPlan(plan)) return false;
        var environment=obj(binding.get("environment"));
        var framework=obj(obj(plan.get("inventory")).get("frameworkEvidence"));
        var discovery=obj(plan.get("discovery"));
        var events=list(discovery.get("events"));
        return Boolean.TRUE.equals(framework.get("completeClasspath"))
                && list(binding.get("dependencies")).isEmpty() && list(binding.get("definitions")).size()==2
                && "COMPLETE".equals(environment.get("noResolvableDependencies"))
                && "COMPLETE".equals(environment.get("noPostRegistrationMutation"))
                && "NONE".equals(environment.get("comparator")) && list(discovery.get("initialDefinitions")).isEmpty()
                && events.size()==2 && events.stream().map(EvolutionRows::obj).allMatch(event ->
                    "COMPONENT".equals(event.get("kind")) && "COMPLETE".equals(event.get("membership")) && event.get("parent")==null);
    }

    Formula provedSymbolicUniverse()throws IOException {
        if(provedSymbolicUniverse!=null)return provedSymbolicUniverse;
        var roots=new ArrayList<Formula>();
        var rows=new Rows(side.run.index,"spring-trace",side.name+"|"+text(result,"evidence")+"|traces|");
        for(var trace=rows.next();trace!=null;trace=rows.next()) {
            EvolutionSnapshot.check(side.run.cancelled);
            var raw=trace.value();
            if(Set.of("COMPLETE","PARTIAL").contains(raw.get("operation")))
                roots.add(formula(text(raw,"region"),true,SpringEvolution.LIMITS));
        }
        return provedSymbolicUniverse=or(roots);
    }

    private String explicitContext(Map<String,Object> plan,String build,Map<String,Object> parameters) {
        var registration=registration(plan);
        var binding=obj(plan.get("binding"));
        return identity("spring-semantics-context",row("buildContextIdentity",build,"configurationSpaceIdentity",domain.identity,
                "registrationPlanIdentity",registrationIdentity(plan),"mechanismCatalog",obj(plan.get("inventory")).get("mechanismCatalog"),
                "conditionIrVersion",ConditionExpression.IR,"frameworkSemantics",obj(plan.get("conditions")).get("semantics"),
                "registrationSemantics",BeanRegistrationProcessing.SEMANTICS,"bindingSemantics",BindingProcessing.SEMANTICS,
                "bindingPlanIdentity",bindingIdentity(plan),"reasonerPolicy",parameters.get("reasonerPolicy"),
                "deterministicLimits",row("discovery",discoveryLimits(plan),
                        "registration",registration.get("limits"),"binding",binding.get("limits"),"truth",parameters.get("limits"))));
    }

    private static String legalOrder(EvolutionInput side, Map<String,Object> plan) throws IOException {
        var registration=registration(plan);
        var steps=registrationStepEvents(side, plan);
        var events=establishedOrderPrefix(side, plan).stream().map(steps::get).toList();
        return identity("spring-legal-order",row("registrationPlanIdentity",registrationIdentity(plan),"orderedEventIdentities",events,
                "realizabilityEvidence",row("evidence",CanonicalJson.sha256(registration.get("orderEvidence")),"completeness",registration.get("order")),
                "orderInterpretation","evidenced-registration-prefix-v1"));
    }

    Map<String,Map<String,Object>> witnesses(Fact fact)throws IOException {
        var output=new TreeMap<String,Map<String,Object>>();
        if(!encoding.equals("explicit")){
            if(!tracesRead)readTraceWitnesses();
            return traceWitnesses.getOrDefault(fact.local,Map.of());
        }
        for(var witness:witnessesByRegion.getOrDefault(fact.region.id,List.of())) {
            var raw=witness.raw;
            if(!fact.region.id.equals(raw.get("factRegionIdentity")))continue;
            var exact=assignment(obj(raw.get("fullAssignment")));
            var typed=domain.assignment(exact);
            var world=worlds.get(typed.key());
            var replay=replays.get(witness.id);
            var fields=new TreeMap<>(raw);
            fields.remove("identity");
            fields.put("fullAssignment",exact);
            if(replay==null||world==null||!Boolean.TRUE.equals(replay.raw.get("valid"))||
                    !witness.id.equals(identity("spring-witness",fields))||!member("witnesses",withIdentity(fields,witness.id))||
                    !member("replays",replay.raw)||!Objects.equals(raw.get("legalOrderIdentity"),result.get("legalOrderIdentity")))continue;
            String truth=text(raw,"expectedTruth");
            if(world.feasibility!=LogicalValue.TRUE||!truth.equals(replay.raw.get("actualTruth"))
                    ||!"TRUE".equals(replay.raw.get("assignmentFeasibility"))
                    ||!truth.equals(world.facts.getOrDefault(fact.local,LogicalValue.UNKNOWN).name()))continue;
            if(output.containsKey(truth))throw EvolutionInput.corrupt();
            output.put(truth,row("graph",side.graph,"side",side.name,"context",context,"region",fact.region.id,
                    "legalOrder",result.get("legalOrderIdentity"),"assignment",typed.key(),"witness",witness.evidence,
                    "replay",replay.evidence,"tier","CAPTURED_EVALUATOR_REPLAY","minimality","PRODUCER_POLICY_ONLY"));
        }
        return output;
    }

    private void readTraceWitnesses()throws IOException {
        var byFact=new HashMap<String,Fact>();facts.values().forEach(f->byFact.put(f.local,f));
        var rows=new Rows(side.run.index,"spring-trace",side.name+"|"+text(result,"evidence")+"|traces|");
        long cells=0;
        for(var next=rows.next();next!=null;next=rows.next()) {
            EvolutionSnapshot.check(side.run.cancelled);
            var raw=next.value();
            if(!Boolean.TRUE.equals(raw.get("replayed"))||!Set.of("COMPLETE","PARTIAL").contains(raw.get("operation")))continue;
            var exact=assignment(obj(raw.get("representative")));
            var typed=domain.assignment(exact);
            var local=new TreeMap<String,Boolean>();
            domain.atoms.forEach((key,choice)->local.put(key,choice.value().equals(typed.values().get(choice.coordinate()))));
            var traceRegion=formula(text(raw,"region"),false,SpringEvolution.LIMITS);
            if(!traceRegion.evaluate(local))continue;
            var traceDigest = "spring.symbolic-trace:m8.4-paged-v1".equals(raw.get("schema"))
                    ? new ContentDigest(text(raw, "traceDigest"))
                    : CanonicalJson.sha256(raw);
            long[] cellsBox = { cells };
            SpringOperands.ObjectConsumer factHandler = item -> {
                if (++cellsBox[0] > SpringEvolution.MAX_CELLS)
                    throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
                var pair = list(item);
                var fact = byFact.get((String) pair.getFirst());
                if (fact == null) return;
                String expected = (String) pair.getLast();
                if (!Set.of("TRUE", "FALSE").contains(expected)) return;
                var region = obj(fact.region.raw.get("region"));
                boolean yes = formula(text(region, "trueRegion"), false, SpringEvolution.LIMITS).evaluate(local);
                boolean no = formula(text(region, "falseRegion"), false, SpringEvolution.LIMITS).evaluate(local);
                if (yes == no || !expected.equals(yes ? "TRUE" : "FALSE")) return;
                var choices = traceWitnesses.computeIfAbsent(fact.local, key -> new TreeMap<>());
                var previous = choices.get(expected);
                if (previous != null && text(previous, "assignment").compareTo(typed.key()) <= 0) return;
                choices.put(expected, row("graph", side.graph, "side", side.name, "context", context,
                        "region", fact.region.id, "assignment", typed.key(), "trace", traceDigest,
                        "result", result.get("evidence"), "bindingResult", raw.get("bindingResult"),
                        "tier", "CAPTURED_SYMBOLIC_TRACE_REPLAY", "minimality", "NONE"));
            };
            if ("spring.symbolic-trace:m8.4-paged-v1".equals(raw.get("schema"))) {
                visitObjectPages(side, raw.get("factPages"), "spring.symbolic-trace-fact-page", "facts", factHandler);
            } else {
                Object payload = raw.get("facts");
                if (!(payload instanceof Map<?, ?> empty && empty.isEmpty())) {
                    for (var item : list(payload)) factHandler.accept(item);
                }
            }
            cells = cellsBox[0];
        }
        tracesRead=true;
    }

    private static Map<String,Object> withIdentity(Map<String,Object> fields,String identity) {
        var result=new TreeMap<>(fields);result.put("identity",identity);return result;
    }

    private static Map<String,Object> exportWorld(World world) {
        var row=new TreeMap<>(world.operand.raw);
        row.put("assignment",assignment(obj(row.get("assignment"))));
        row.put("facts",new TreeMap<>(world.facts).entrySet().stream()
                .map(e->row("fact",e.getKey(),"truth",e.getValue())).toList());
        return row;
    }

    private String symbolicInput(Map<String,Object> partition)throws IOException {
        var inputs=pending.values().stream().map(region->text(region.raw,"evaluationInput")).distinct().toList();
        if(inputs.isEmpty()) {
            var meta=new Rows(side.run.index,"spring-result",side.name+"|spring.symbolic-result|");
            String found=null;
            for(var e=meta.next();e!=null;e=meta.next()) {
                EvolutionSnapshot.check(side.run.cancelled);
                String id=maybe(e.value(),"inputIdentity");
                if(id==null||!KnowledgeGraph.derivedIdentity("spring-context",List.of(analysis,descriptor.get("planIdentity"),
                        domain.identity,id,descriptor.get("parameters"))).equals(text(partition,"context")))continue;
                if(found!=null)throw EvolutionInput.corrupt();
                found=id;
            }
            if(found!=null)return found;
        }
        if(inputs.size()!=1)throw new EvolutionSnapshot.ReadFailure(inputs.isEmpty()?EvolutionSnapshot.Failure.UNAVAILABLE:EvolutionSnapshot.Failure.CORRUPT);
        return inputs.getFirst();
    }

    private static boolean numBetween(Object value,int low,int high) {
        if(!(value instanceof Number))return false;
        long n=number(value);return n>=low&&n<=high;
    }

    private static GraphEvidence required(EvolutionInput side,String family,String id)throws IOException {
        var evidence=lookup(side,family,id);
        if(evidence==null)throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNAVAILABLE);
        return evidence;
    }
    boolean member(String family,Object payload)throws IOException{return SpringOperands.member(side,text(result,"evidence"),family,payload);}

    private void read(KnowledgeGraph.Node node,Limits limits)throws IOException {
        if(!context.equals(node.context()))throw EvolutionInput.corrupt();
        String family=switch(node.kind()){
            case "TRUTH_REGION"->"spring."+encoding+"-region";
            case "CONFIGURATION_WORLD"->"spring.world";
            case "CONFIGURATION_WITNESS"->"spring.witness";
            case "WITNESS_REPLAY"->"spring.witness-replay";
            case "REGION_FORMULA"->"spring.formula";
            case "BEAN_DEFINITION_CANDIDATE"->"spring.candidate";
            case "INJECTION_POINT"->"spring.injection-dependency";
            default->null;
        };
        if(family==null)return;
        Operand operand=null;
        for(var ref:node.evidence()){
            var ev=side.evidence(ref);
            if(!ev.family().equals(family))continue;
            var raw=EvolutionInput.payload(ev);
            if(family.equals("spring.injection-dependency"))raw=obj(raw.get("point"));
            if(operand!=null&&!operand.raw.equals(raw))throw EvolutionInput.corrupt();
            if(operand==null)operand=new Operand(node.identity(),ref,raw);
        }
        if(operand==null)throw EvolutionInput.corrupt();
        var raw=operand.raw;
        switch(node.kind()) {
            case "TRUTH_REGION" -> insert(pending,node.identity(),operand);
            case "REGION_FORMULA" -> insert(formulaNodes,node.identity(),operand);
            case "BEAN_DEFINITION_CANDIDATE" -> insert(candidates,node.identity(),operand);
            case "INJECTION_POINT" -> insert(sites,node.identity(),operand);
            case "CONFIGURATION_WITNESS" -> {
                insert(witnesses,node.identity(),operand);
                witnessesByRegion.computeIfAbsent(text(raw,"factRegionIdentity"),key->new ArrayList<>()).add(operand);
            }
            case "WITNESS_REPLAY" -> insert(replays,text(raw,"witness"),operand);
            case "CONFIGURATION_WORLD" -> {
                var assignment=domain.assignment(assignment(obj(raw.get("assignment"))));
                var truths=new HashMap<String,LogicalValue>();
                Object encoded=raw.get("facts");
                if(!(encoded instanceof Map<?,?> empty && empty.isEmpty()))for(var item:list(encoded)) {
                    var pair=list(item);
                    if(pair.size()!=2)throw EvolutionInput.corrupt();
                    insert(truths,(String)pair.getFirst(),LogicalValue.valueOf((String)pair.getLast()));
                }
                insert(worlds,assignment.key(),new World(operand,assignment,LogicalValue.valueOf(text(raw,"feasibility")),Map.copyOf(truths)));
            }
            default -> throw new IllegalStateException("Unexpected Spring operand kind");
        }
        if((long)pending.size()+formulaNodes.size()+candidates.size()+sites.size()+witnesses.size()+replays.size()+worlds.size()>limits.maxNodes())
            throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
    }

    private static <T> void insert(Map<String,T> table,String id,T value)throws IOException {
        if(table.putIfAbsent(id,value)!=null)throw EvolutionInput.corrupt();
    }

    private Map<String,Endpoint> endpoints(Map<String,Operand> records,boolean candidate,String build)throws IOException {
        var output=new HashMap<String,Endpoint>();var names=new HashMap<String,Long>();
        for(Operand operand:records.values()) {
            var raw=operand.raw;
            String stable;
            if(candidate) {
                var producer=obj(raw.get("producer"));var namesRow=obj(raw.get("declaredNameKey"));
                String producerId=identity("spring-producer",producer);
                String key=identity("spring-bean-candidate",row("producerIdentity",producerId,"containerKey",producer.get("containerKey"),
                        "definitionSlot",raw.get("definitionSlot"),"declaredNameKey",namesRow));
                if(!key.equals(operand.id)||!build.equals(producer.get("buildContextIdentity")))throw EvolutionInput.corrupt();
                if(!"EXACT".equals(namesRow.get("status"))||!declaration(producer,"declarationSlot"))continue;
                stable=hash("evolution.spring.candidate:v1",List.of(producer.get("containerKey"),producer.get("producerKind"),
                        producer.get("declarationSlot"),producer.get("discoveryPathKey"),raw.get("definitionSlot"),namesRow)).value();
            } else {
                if(!operand.id.equals(identity("spring-injection-point",raw))||!build.equals(raw.get("buildContextIdentity")))throw EvolutionInput.corrupt();
                if(!declaration(raw,"ownerDeclarationKey")||
                        Set.of("GENERATED","OTHER","XML_REFERENCE").contains(text(raw,"siteKind")))continue;
                stable=hash("evolution.spring.site:v1",fields(raw,"ownerDeclarationKey","siteKind","siteSlot")).value();
            }
            output.put(operand.id,new Endpoint(operand,stable));
            names.merge(stable,1L,Long::sum);
        }
        output.values().removeIf(e->names.get(e.key)!=1);
        return output;
    }

    private boolean declaration(Map<String,Object> raw,String field)throws IOException {
        var declaration=obj(raw.get("declarationEvidenceKey"));String id=text(raw,field);
        var supports=sourceSupport(side,id,"java.declaration",analysis);
        if(supports.size()!=1)return false;
        var data=EvolutionInput.payload(supports.getFirst());
        var entity=obj(data.get("entity"));
        if(!"PROJECT".equals(entity.get("origin"))||!"RESOLVED".equals(data.get("status")))return false;
        var span=entity.get("declaration");
        if(span==null)return false;
        var doc=text(obj(span),"document");
        boolean evidenced=switch(text(declaration,"kind")){
            case "SOURCE"->declaration.get("span")!=null&&doc.equals(declaration.get("document"));
            case "DERIVED"->"CONSTRUCTOR_PARAMETER".equals(raw.get("siteKind"))
                    ?constructorParameter(raw,entity,doc,declaration)
                    :"COMPONENT_CLASS".equals(raw.get("producerKind"))&&
                        CanonicalJson.write(ComponentScanIngestion.PROVIDER).equals(CanonicalJson.write(declaration.get("method")))&&
                        ("component-type:"+id).equals(declaration.get("slot"))&&
                        list(declaration.get("inputs")).contains(CanonicalJson.sha256(data).value());
            default->false;
        };
        if(!evidenced)return false;
        var documents=sourceSupport(side,doc,"source.document",null);
        if(documents.size()!=1)return false;
        var source=EvolutionInput.payload(documents.getFirst());
        return (!"SOURCE".equals(declaration.get("kind"))||Objects.equals(declaration.get("rawDigest"),source.get("contentDigest")))
                &&Set.of("MAIN","TEST").contains(text(source,"classification"));
    }

    private boolean constructorParameter(Map<String,Object> point,Map<String,Object> owner,String doc,
                                         Map<String,Object> evidence)throws IOException {
        var method=obj(evidence.get("method"));
        if (!ConstructorInjectionIngestion.PROVIDER.id().equals(method.get("id"))
                || !Set.of(ConstructorInjectionIngestion.PROVIDER.version(),"m8.4-local-fragment-v4").contains(method.get("version"))
                || !"TYPE".equals(owner.get("kind"))) return false;
        String parameterId=text(point,"siteSlot");
        var supports=sourceSupport(side,parameterId,"java.declaration",analysis);
        if (supports.size()!=1) return false;
        var parameter=EvolutionInput.payload(supports.getFirst());
        var entity=obj(parameter.get("entity"));
        if (!"RESOLVED".equals(parameter.get("status")) || !"PROJECT".equals(entity.get("origin"))
                || !"PARAMETER".equals(entity.get("kind")) || !Objects.equals(entity.get("stableScope"),owner.get("stableScope"))
                || entity.get("declaration")==null || !doc.equals(obj(entity.get("declaration")).get("document"))) return false;
        String name=text(entity,"canonicalName");
        if (!name.startsWith("java:v1:")) return false;
        var parts=list(GraphJson.read(name.substring("java:v1:".length()),new GraphJson.Limits(8192,32,512)));
        if (parts.size()!=3 || !"parameter".equals(parts.getFirst()) || !numBetween(parts.get(2),0,Integer.MAX_VALUE)
                || !("parameter:"+number(parts.get(2))).equals(evidence.get("slot"))) return false;
        var constructor=list(parts.get(1));
        if (constructor.size()!=3 || !"constructor".equals(constructor.getFirst())
                || !("java:v1:"+CanonicalJson.write(constructor.get(1))).equals(owner.get("canonicalName"))) return false;
        var inputs=list(evidence.get("inputs"));
        if (inputs.size()!=3) return false;
        String declaration=CanonicalJson.sha256(parameter).value();
        if (!inputs.contains(declaration)) return false;
        for (var input:inputs) {
            if (input.equals(declaration)) continue;
            var types=sourceSupport(side,GraphJson.string(input),"java.type-use",analysis);
            if (types.size()!=1) continue;
            var type=EvolutionInput.payload(types.getFirst());
            if (parameterId.equals(type.get("owner")) && "java.parameter-type".equals(EvolutionInput.category(type.get("role")))
                    && "RESOLVED".equals(obj(type.get("type")).get("status")) && type.get("span")!=null
                    && doc.equals(obj(type.get("span")).get("document"))) return true;
        }
        return false;
    }

    private Fact fact(Map<String,Object> raw,Operand region,Map<String,Endpoint> candidates,Map<String,Endpoint> sites)throws IOException {
        if(!CanonicalJson.write(ConditionalFactKey.SEMANTICS).equals(CanonicalJson.write(raw.get("factSemanticsVersion"))))return null;
        String kind=text(raw,"kind");
        String def=maybe(raw,"definition"), point=maybe(raw,"injectionPoint"), owner=maybe(raw,"bindingOwner"), target=maybe(raw,"target");
        boolean definition=kind.equals("DEFINITION_PRESENT");
        if(!definition&&!Set.of("INJECTION_CANDIDATE","SELECTED_BINDING").contains(kind))return null;
        var source=definition?candidates.get(def):sites.get(point);
        var bindingOwner=owner==null?null:candidates.get(owner);
        var endpoint=target==null?null:candidates.get(target);
        if(source==null||owner!=null&&bindingOwner==null||!definition&&endpoint==null)return null;
        String local=identity("spring-fact-key",row("kind",kind,"factSemanticsVersion",raw.get("factSemanticsVersion"),
                "siteIdentity",point,"typedSource",row("type",definition||owner!=null?"bean-definition-candidate":"injection-point",
                        "identity",definition?def:owner!=null?owner:point),
                "typedTargetOrCandidates",definition?List.of():List.of(row("type","bean-definition-candidate","identity",target))));
        String site=definition?null:hash("evolution.spring.injection-use:v1",Arrays.asList(source.key,bindingOwner==null?null:bindingOwner.key)).value();
        String subject=hash("architecture.evolution.subject:v1",Arrays.asList(kind,definition?source.key:site,endpoint==null?null:endpoint.key)).value();
        Fact fact=new Fact(kind,subject,site,endpoint==null?null:endpoint.key,local,region,text(obj(region.raw.get("region")),"classification"));
        if(encoding.equals("explicit"))validateFinite(fact);
        return fact;
    }

    private void validateFinite(Fact fact)throws IOException {
        var region=obj(fact.region.raw.get("region"));
        var actual=new EnumMap<LogicalValue,List<String>>(LogicalValue.class);
        for(var value:LogicalValue.values())actual.put(value,new ArrayList<>());
        for(var world:orderedWorlds.values())if(world.feasibility==LogicalValue.TRUE)
            actual.get(world.facts.getOrDefault(fact.local,LogicalValue.UNKNOWN)).add(world.operand.id);
        String regionId=identity("spring-fact-region",row("factKey",fact.local,"semanticsContextIdentity",context,
                "regionEncodingVersion",TruthRegionEvaluation.REGION_ENCODING,"trueRegion",actual.get(LogicalValue.TRUE),
                "falseRegion",actual.get(LogicalValue.FALSE),"unknownRegion",actual.get(LogicalValue.UNKNOWN),"feasibilityStatus",region.get("feasibility")));
        if(!text(region,"identity").equals(fact.region.id)||!regionId.equals(fact.region.id))throw EvolutionInput.corrupt();
        for(var e:Map.of("trueWorlds",LogicalValue.TRUE,"falseWorlds",LogicalValue.FALSE,"unknownWorlds",LogicalValue.UNKNOWN).entrySet()) {
            var observations=list(region.get(e.getKey()));
            var ids=observations.stream().map(String.class::cast).toList();
            if(!actual.get(e.getValue()).stream().sorted().toList().equals(ids))throw EvolutionInput.corrupt();
        }
    }

    Formula formula(String id,boolean normalized,Limits limits)throws IOException {
        return formula(id,normalized,limits,0,new HashSet<>());
    }

    private Formula formula(String id,boolean normalized,Limits limits,int depth,Set<String> active)throws IOException {
        EvolutionSnapshot.check(side.run.cancelled);
        String cacheKey=(normalized?"normalized|":"local|")+id;
        var cached=formulas.get(cacheKey);if(cached!=null)return cached;
        if(depth>=limits.maxDepth())throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
        var node=formulaNodes.get(id);
        if(node==null||!active.add(id)||!id.equals(node.raw.get("identity")))throw EvolutionInput.corrupt();
        var op=Operator.valueOf(text(node.raw,"operator"));var name=text(node.raw,"variable");
        var children=new ArrayList<Formula>();var original=new ArrayList<ContentDigest>();
        for(var child:list(node.raw.get("children"))) {
            String key=(String)child;original.add(new ContentDigest(key));children.add(formula(key,normalized,limits,depth+1,active));
        }
        new Node(new ContentDigest(id),op,name,original); // verify the exact source DAG before renaming atoms
        if((op==Operator.TRUE||op==Operator.FALSE||op==Operator.VARIABLE)&&!children.isEmpty()
                ||op==Operator.NOT&&children.size()!=1||op!=Operator.VARIABLE&&!name.isEmpty())throw EvolutionInput.corrupt();
        var out=switch(op){case TRUE->TRUE;case FALSE->FALSE;case VARIABLE->normalized?domain.normalize(name):variable(name);
            case NOT->not(children.getFirst());case AND->and(children);case OR->or(children);};
        if(!normalized&&!id.equals(out.identity().value()))throw EvolutionInput.corrupt();
        formulas.put(cacheKey,out); active.remove(id);
        return out;
    }
}
