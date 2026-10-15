package com.evolution.analysis.query;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Pure Java architecture queries over one immutable graph and one exact context per request. */
public final class ArchitectureQueryService {
    private final KnowledgeGraph graph;
    private final Supplier<StopReason> guard;
    private final Optional<Metrics> metrics;
    /** Operational guards are outside query identity; never treat interrupted output as a complete cache entry. */
    public ArchitectureQueryService(KnowledgeGraph graph){this(graph,operationalGuard(()->false,0,8_000_000),Optional.empty());}
    public ArchitectureQueryService(KnowledgeGraph graph,Supplier<StopReason> guard,Optional<Metrics> metrics){
        this.graph=Objects.requireNonNull(graph);this.guard=Objects.requireNonNull(guard);this.metrics=Objects.requireNonNull(metrics);
    }
    public static Supplier<StopReason> operationalGuard(BooleanSupplier cancelled,long deadlineNanos,long minimumAvailableHeapBytes){
        if(minimumAvailableHeapBytes<0)throw new IllegalArgumentException("Negative heap reserve");
        return ()->{if(cancelled.getAsBoolean())return StopReason.CANCELLED;
            if(deadlineNanos!=0&&System.nanoTime()-deadlineNanos>=0)return StopReason.TIME;
            var runtime=Runtime.getRuntime();if(runtime.maxMemory()-runtime.totalMemory()+runtime.freeMemory()<minimumAvailableHeapBytes)return StopReason.MEMORY;
            return StopReason.NONE;};
    }
    public KnowledgeGraph graph(){return graph;}
    /** Additive M6 assessment/metric orchestration shares this query service's cancellation and operational guard. */
    public StopReason assessmentStopReason(){return Objects.requireNonNull(guard.get());}
    static final class LogicalEdge {
        final String source,target;final SortedSet<String> supports=new TreeSet<>(),kinds=new TreeSet<>(),regions=new TreeSet<>();
        final SortedSet<ContentDigest> evidence=new TreeSet<>();QueryRegion region;EdgeView view;
        LogicalEdge(String source,String target,QueryRegion region){this.source=source;this.target=target;this.region=region;}
    }
    /** Only population columns are retained; paths, hashes and other parsed document fields stay transient. */
    private record DocumentPopulation(String module,String sourceSet) {}
    final class Prepared {
        final Request request;final QueryBudget budget;
        final Map<String,KnowledgeGraph.Node> nodes=new TreeMap<>();final Map<String,EntityView> entities=new TreeMap<>(),descriptions=new HashMap<>();
        final List<LogicalEdge> edges=new ArrayList<>();final Map<String,List<LogicalEdge>> adjacency=new TreeMap<>();
        GraphRegionCatalog catalog;
        Prepared(Request request,boolean project){
            this(request,project,false);
        }
        Prepared(Request request,boolean project,boolean contextOnly){
            this.request=request;validate(request);budget=new QueryBudget(request.limits(),guard);
            if(contextOnly){
                var selected=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
                for(var node:graph.nodes()){
                    if(!budget.scan())break;
                    if((node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT"))
                            &&(node.context().equals(request.context())||node.context().equals(graph.snapshot().value())
                            ||selected.analysis().map(a->a.value().equals(node.context())).orElse(false))){
                        budget.qualify("UPSTREAM_CAPABILITY_GAP");break;
                    }
                }
                return;
            }
            for(var node:graph.nodes())if(node.context().equals(request.context())){
                if(!budget.scan())break;
                if(nodes.size()>=request.limits().maxVertices()){budget.halt(StopReason.INPUT);break;}
                if(!budget.reserve(3))break;nodes.put(node.identity(),node);
            }
            var documents=new HashMap<String,DocumentPopulation>();
            for(var node:nodes.values())if(node.kind().equals("SOURCE_DOCUMENT"))for(var ref:node.evidence()){
                if(!budget.scan())break;var evidence=graph.evidence(ref).orElseThrow();
                if(evidence.family().equals("source.document")&&!documents.containsKey(node.identity())){
                    // Reserve entry/record/scalar state before parsing or retaining this document's metadata.
                    if(!budget.reserve(4))break;
                    var payload=read(evidence,budget);
                    documents.put(node.identity(),new DocumentPopulation(optionalText(payload,"module"),optionalText(payload,"classification")));
                }
            }
            var context=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
            var contextPayload=read(graph.evidence(context.evidence()).orElseThrow(),budget);
            var contextPartition=Optional.ofNullable(contextPayload.get("build")).filter(Map.class::isInstance).map(GraphJson::object)
                    .map(b->b.get("moduleSourceSet")).filter(Map.class::isInstance).map(GraphJson::object).orElse(Map.of());
            for(var node:nodes.values()){
                if(!budget.scan())break;
                var entity=describe(node,documents,contextPartition,budget);
                descriptions.put(node.identity(),entity);
                if(request.population().nodeKinds().isEmpty()||request.population().nodeKinds().contains(entity.kind())){
                    boolean inducedOwner=project&&(request.scope()==Scope.PACKAGE||request.scope()==Scope.MODULE)&&entity.kind().equals(request.scope().name());
                    if(!inducedOwner&&(!request.population().modules().isEmpty()&&entity.module().isEmpty()||!request.population().origins().isEmpty()&&entity.origin().isEmpty()
                            ||!request.population().sourceSets().isEmpty()&&entity.sourceSet().isEmpty()))budget.qualify("POPULATION_METADATA_UNAVAILABLE");
                    if(included(entity,request.population()))entities.put(node.identity(),entity);
                }
                if(node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT"))budget.qualify("UPSTREAM_CAPABILITY_GAP");
            }
            // Analysis-wide/snapshot gaps remain relevant even when evidence is partitioned in a Spring context.
            var selected=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
            for(var node:graph.nodes())if((node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT"))
                    &&(node.context().equals(graph.snapshot().value())||selected.analysis().map(a->a.value().equals(node.context())).orElse(false)))
                budget.qualify("UPSTREAM_CAPABILITY_GAP");
            if(project&&budget.running()){catalog=new GraphRegionCatalog(graph,request,budget,nodes);project();}
        }
        private void project(){
            var owners=new HashMap<String,String>();var parent=new HashMap<String,String>();
            int count=0;
            for(var edge:graph.edges())if(edge.context().equals(request.context())){
                if(!budget.scan())return;
                if(++count>request.limits().maxEdges()){budget.halt(StopReason.INPUT);return;}
                if(edge.target().state()==KnowledgeGraph.TargetState.RESOLVED){String target=edge.target().identities().getFirst();
                    if(edge.kind().equals("spring.declares-injection"))owners.put(target,edge.source());
                    if(edge.kind().equals("graph.contains")||edge.kind().equals("graph.declares"))parent.put(target,edge.source());
                }
            }
            var grouped=new TreeMap<String,LogicalEdge>();
            // A package/module population is induced by eligible source types, not by whichever declaration's
            // metadata happens to be last on the aggregate owner (which may also contain generated/test types).
            if(request.scope()==Scope.PACKAGE||request.scope()==Scope.MODULE){
                var eligibleOwners=new TreeMap<String,EntityView>();
                for(var type:descriptions.values())if(type.kind().equals("TYPE")&&included(type,new Population(Set.of(),request.population().modules(),request.population().origins(),request.population().sourceSets()))){
                    String owner=scope(type.identity(),request.scope(),owners,parent);var view=descriptions.get(owner);
                    if(view!=null&&(request.population().nodeKinds().isEmpty()||request.population().nodeKinds().contains(view.kind())))eligibleOwners.put(owner,new EntityView(view.identity(),view.context(),view.kind(),view.canonicalName(),view.qualifiedName(),view.spelling(),type.module(),type.origin(),type.sourceSet(),view.evidence()));
                }
                entities.entrySet().removeIf(e->e.getValue().kind().equals(request.scope().name()));entities.putAll(eligibleOwners);
            }
            for(var edge:graph.edges())if(edge.context().equals(request.context())&&request.relationshipKinds().contains(edge.kind())){
                if(!budget.scan())break;
                if(!catalog.preferred(edge))continue;
                if(knownExcluded(descriptions.get(edge.source()),request.population()))continue;
                String source=scope(edge.source(),request.scope(),owners,parent);
                var region=catalog.region(edge).combine(catalog.activate(edge.source()),true,budget);
                if(edge.target().state()!=KnowledgeGraph.TargetState.RESOLVED){budget.qualify("UNRESOLVED_RELATIONSHIP");region=QueryRegion.unknown();}
                // Raw occurrences may have a target while semantic resolution/provenance remains qualified.
                for(var ref:edge.evidence()){
                    var payload=graph.evidence(ref).orElseThrow();
                    if(payload.family().equals("java.occurrence")){
                        var status=read(payload,budget).get("status");if(status!=null&&!"RESOLVED".equals(status)){region=QueryRegion.unknown();budget.qualify("QUALIFIED_SEMANTIC_RELATIONSHIP");}
                    }
                }
                if(edge.target().identities().isEmpty())continue;
                for(var targetIdentity:edge.target().identities()){
                    if(knownExcluded(descriptions.get(targetIdentity),request.population()))continue;
                    String target=scope(targetIdentity,request.scope(),owners,parent);
                    if(source==null||target==null||!entities.containsKey(source)||!entities.containsKey(target))continue;
                    if(source.equals(target)&&(request.scope()==Scope.PACKAGE||request.scope()==Scope.MODULE))continue;
                    var activated=region.combine(catalog.activate(targetIdentity),true,budget).combine(catalog.activate(source),true,budget).combine(catalog.activate(target),true,budget);
                    String a=request.direction()==Direction.OUTGOING?source:target,b=request.direction()==Direction.OUTGOING?target:source;
                    String key=a+"|"+b;var previous=grouped.get(key);
                    if(!budget.reserve(4L+edge.evidence().size()))break;
                    if(previous==null){previous=new LogicalEdge(a,b,activated);grouped.put(key,previous);}else previous.region=previous.region.combine(activated,false,budget);
                    if(previous.supports.size()+previous.evidence.size()+previous.regions.size()+edge.evidence().size()>request.limits().maxAuxiliaryStates()){
                        budget.halt(StopReason.INPUT);budget.frontier(a);break;}
                    previous.supports.add(edge.identity());previous.kinds.add(edge.kind());previous.regions.addAll(edge.region().stream().toList());previous.evidence.addAll(edge.evidence());
                }
            }
            for(var edge:grouped.values()){
                if(!budget.scan())break;
                var classification=edge.region.classify(budget);var check=edge.region.check(budget,request.realization());
                boolean include=switch(request.projection()){
                    case MUST->classification==Classification.MUST;
                    case MAY->classification==Classification.MAY||request.includeMust()&&classification==Classification.MUST;
                    case REALIZED->catalog.realizationValid&&check.truth()==Truth.SAT;
                    case BOUNDED_UNION->check.truth()!=Truth.UNSAT;
                    case UNKNOWN_OVERLAY->classification==Classification.UNKNOWN||check.truth()==Truth.UNKNOWN;
                };
                if(check.truth()==Truth.UNKNOWN)budget.qualify("UNKNOWN_FEASIBILITY");
                if(!include)continue;
                edge.view=new EdgeView(KnowledgeGraph.derivedIdentity("query-edge",List.of(VERSION,request.context(),request.scope(),edge.source,edge.target,edge.supports)),
                        edge.source,edge.target,List.copyOf(edge.kinds),bounded(edge.supports,budget),bounded(edge.evidence,budget),bounded(edge.regions,budget),classification,check.truth()==Truth.SAT,
                        edge.supports.size(),edge.supports.size()>request.limits().maxResults());
                edges.add(edge);adjacency.computeIfAbsent(edge.source,k->new ArrayList<>()).add(edge);
            }
        }
        private String scope(String identity,Scope scope,Map<String,String> owners,Map<String,String> parent){
            if(scope==Scope.ENTITY)return identity;
            if(scope==Scope.SPRING_COMPONENT)return owners.getOrDefault(identity,identity);
            String kind=scope.name();var seen=new HashSet<String>();String cursor=identity;
            while(cursor!=null&&seen.add(cursor)&&seen.size()<=request.limits().maxVertices()){
                if(!budget.scan())return null;
                var node=nodes.get(cursor);if(node!=null&&node.kind().equals(kind))return cursor;cursor=parent.get(cursor);
            }
            budget.qualify("SCOPE_OWNER_UNAVAILABLE");return null;
        }
    }
    private void validate(Request request){
        if(!graph.identity().equals(request.graph()))throw new IllegalArgumentException("Foreign/stale graph identity");
        if(graph.contexts().stream().noneMatch(c->c.identity().equals(request.context())))throw new IllegalArgumentException("Foreign context");
    }
    public Result<EntityView> entities(Request request,String search,Page page){
        Objects.requireNonNull(search);var p=new Prepared(request,false);
        var values=p.entities.values().stream().filter(e->scopeIncludes(e,request.scope())).filter(e->search.isEmpty()||e.identity().equals(search)||e.spelling().map(s->s.equals(search)).orElse(false)
                ||e.canonicalName().map(s->s.equals(search)||s.contains(search)).orElse(false)||e.qualifiedName().filter(s->s.equals(search)).isPresent()).toList();
        return page(p,"entities",search,values,EntityView::identity,page);
    }
    private static boolean scopeIncludes(EntityView entity,Scope scope){return scope==Scope.ENTITY||entity.kind().equals(scope==Scope.SPRING_COMPONENT?"BEAN_DEFINITION_CANDIDATE":scope.name());}
    public Result<EntityView> entity(Request request,String identity){
        var p=new Prepared(request,false);var value=p.entities.get(identity);if(value==null)p.budget.qualify("ENTITY_UNAVAILABLE");
        return result(p,"entity",identity,value==null?List.of():List.of(value),Optional.empty(),value==null?Optional.empty():Optional.of(1L));
    }
    public Result<EdgeView> edges(Request request,Page page){var p=new Prepared(request,true);return page(p,"edges","",p.edges.stream().map(e->e.view).sorted(Comparator.comparing(EdgeView::identity)).toList(),EdgeView::identity,page);}
    public Result<EdgeView> dependencies(Request request,String source,Page page){
        var p=new Prepared(request,true);requireEndpoint(p,source);
        return page(p,"dependencies",source,p.adjacency.getOrDefault(source,List.of()).stream().map(e->e.view).sorted(Comparator.comparing(EdgeView::identity)).toList(),EdgeView::identity,page);
    }
    /** Raw denominator including candidate/unresolved rows; never promoted to a definite projected edge. */
    public Result<RelationshipView> relationships(Request request,Page page){
        var p=new Prepared(request,false);var values=new ArrayList<RelationshipView>();int count=0;
        for(var e:graph.edges())if(e.context().equals(request.context())&&request.relationshipKinds().contains(e.kind())){
            if(!p.budget.scan())break;if(++count>request.limits().maxEdges()){p.budget.halt(StopReason.INPUT);break;}
            if(!p.entities.containsKey(e.source()))continue;
            values.add(new RelationshipView(e.identity(),e.source(),e.kind(),e.target().state().name(),bounded(e.target().identities(),p.budget),
                    e.target().unresolvedReference().map(ContentDigest::sha256Utf8),bounded(e.evidence(),p.budget),e.region()));
        }
        return page(p,"relationships","",values,RelationshipView::identity,page);
    }
    public Result<Neighborhood> expand(Request request,String source){
        var p=new Prepared(request,true);requireEndpoint(p,source);var selected=new ArrayList<EdgeView>();var ids=new TreeSet<String>();ids.add(source);
        for(var e:p.adjacency.getOrDefault(source,List.of())){
            if(selected.size()==request.limits().maxResults()||!ids.contains(e.target)&&ids.size()==request.limits().maxResults()){
                p.budget.frontier(e.target);p.budget.halt(StopReason.OUTPUT);break;}
            selected.add(e.view);ids.add(e.target);
        }
        return result(p,"expand",source,List.of(new Neighborhood(ids.stream().map(p.entities::get).toList(),selected)),Optional.empty(),Optional.empty());
    }
    public Result<Summary> summary(Request request){
        var p=new Prepared(request,true);var n=new TreeMap<String,Long>();var e=new TreeMap<String,Long>();
        p.entities.values().forEach(v->n.merge(v.kind(),1L,Long::sum));p.edges.forEach(v->v.kinds.forEach(k->e.merge(k,1L,Long::sum)));
        return result(p,"summary","",List.of(new Summary(n,e,graph.contexts().size(),graph.identity(),"Distinct context-qualified nodes; logical projected edges retain raw supports with explicit expansion limits. Counts are source dependencies, not runtime calls.")),Optional.empty(),Optional.of(1L));
    }
    public Result<MetricProjection> metricProjection(Request request){
        var p=new Prepared(request,true);var vertices=p.entities.values().stream().filter(v->scopeIncludes(v,request.scope())).toList();
        var active=new TreeSet<String>();var unknown=new TreeSet<String>();
        if(p.catalog!=null&&request.scope()==Scope.SPRING_COMPONENT)for(var node:vertices){
            if(!p.budget.scan())break;var region=p.catalog.activate(node.identity());
            // Candidate metadata alone is not proof of activation; a definition fact must have been supplied.
            if(!p.catalog.activation.containsKey(node.identity())){unknown.add(node.identity());p.budget.qualify("ACTIVATION_EVIDENCE_UNAVAILABLE");continue;}
            var check=region.check(p.budget,request.realization());var c=region.classify(p.budget);
            if(check.truth()==Truth.UNKNOWN||c==Classification.UNKNOWN){unknown.add(node.identity());p.budget.qualify("UNKNOWN_FEASIBILITY");}
            boolean selected=switch(request.projection()){
                case REALIZED->p.catalog.realizationValid&&check.truth()==Truth.SAT;case MUST->c==Classification.MUST;
                case MAY->c==Classification.MAY||request.includeMust()&&c==Classification.MUST;
                case BOUNDED_UNION->check.truth()==Truth.SAT;case UNKNOWN_OVERLAY->false;};
            if(selected)active.add(node.identity());
        }
        p.budget.reserve((long)vertices.size()+p.edges.size()+active.size()+unknown.size());
        return result(p,"metric-projection","full-declared-scope",List.of(new MetricProjection(vertices,p.edges.stream().map(e->e.view).toList(),List.copyOf(active),List.copyOf(unknown))),Optional.empty(),Optional.empty());
    }
    public Result<Path> findPaths(Request request,String source,String target){return traverse(request,source,Optional.of(target),false);}
    public Result<Path> shortestPath(Request request,String source,String target){return traverse(request,source,Optional.of(target),true);}
    /** Use INCOMING for structural impact; each returned dependent has a jointly feasible source path. */
    public Result<Path> reachability(Request request,String source){return traverse(request,source,Optional.empty(),false);}
    public Result<Path> impact(Request request,String changed){
        if(request.direction()!=Direction.INCOMING)throw new IllegalArgumentException("Impact requires INCOMING dependencies");return reachability(request,changed);
    }
    private Result<Path> traverse(Request request,String source,Optional<String> target,boolean shortest){
        var p=new Prepared(request,true);requireEndpoint(p,source);target.ifPresent(t->requireEndpoint(p,t));
        var paths=BoundedGraphTraversal.paths(p,source,target,shortest);
        return result(p,shortest?"shortest-path":target.isPresent()?"paths":"reachability",List.of(source,target),paths,Optional.empty(),Optional.empty());
    }
    public Result<Path> findCycles(Request request){
        var p=new Prepared(request,true);var cycles=BoundedGraphTraversal.cycles(p);
        return result(p,"cycles","",cycles,Optional.empty(),p.budget.stop==StopReason.NONE&&p.budget.unknown==0&&p.budget.reasons.isEmpty()?Optional.of((long)cycles.size()):Optional.empty());
    }
    /** Rule-selected induced edges; unknown cycles remain inspectable without a fabricated witness. */
    public Result<Path> policyCycles(Request request,Set<String> eligibleEdges){
        Objects.requireNonNull(eligibleEdges);
        if(eligibleEdges.size()>request.limits().maxEdges())throw new IllegalArgumentException("Induced edge budget exceeded");
        if(request.projection()!=Projection.BOUNDED_UNION||request.direction()!=Direction.OUTGOING)
            throw new IllegalArgumentException("Policy cycles require outgoing BOUNDED_UNION dependencies");
        var p=new Prepared(request,true);
        if(p.budget.reserve(eligibleEdges.size())){
            var missing=new HashSet<>(eligibleEdges);p.edges.forEach(e->missing.remove(e.view.identity()));
            if(!missing.isEmpty())p.budget.qualify("PREDICATE_EDGE_UNAVAILABLE");
        }
        p.edges.removeIf(e->!eligibleEdges.contains(e.view.identity()));
        p.adjacency.values().forEach(v->v.removeIf(e->!eligibleEdges.contains(e.view.identity())));
        var cycles=BoundedGraphTraversal.cycles(p,true);
        return result(p,"policy-cycles",List.of(ArchitectureCondition.RULE_VERSION,new TreeSet<>(eligibleEdges)),cycles,Optional.empty(),
                p.budget.stop==StopReason.NONE&&p.budget.unknown==0&&p.budget.reasons.isEmpty()?Optional.of((long)cycles.size()):Optional.empty());
    }
    public Result<ArchitectureCondition.Evaluation> condition(Request request,ArchitectureCondition.Predicate predicate){
        Objects.requireNonNull(predicate);
        if(request.projection()!=Projection.BOUNDED_UNION||request.direction()!=Direction.OUTGOING)
            throw new IllegalArgumentException("Condition composition requires outgoing BOUNDED_UNION dependencies");
        var p=new Prepared(request,true);var index=new HashMap<String,LogicalEdge>();
        if(p.budget.reserve(2L*p.edges.size()))p.edges.forEach(e->index.put(e.view.identity(),e));
        QueryRegion union=null;var evidence=new TreeSet<ContentDigest>();var regions=new TreeSet<String>();
        for(var term:predicate.alternatives()){
            QueryRegion joint=QueryRegion.universal();
            for(var id:term.edges()){
                if(!p.budget.step()){joint=QueryRegion.unknown();break;}
                var edge=index.get(id);
                if(edge==null){p.budget.qualify("PREDICATE_EDGE_UNAVAILABLE");joint=joint.combine(QueryRegion.unknown(),true,p.budget);}
                else {joint=joint.combine(edge.region,true,p.budget);
                    if(p.budget.reserve((long)edge.evidence.size()+edge.regions.size())){evidence.addAll(edge.evidence);regions.addAll(edge.regions);}}
            }
            union=union==null?joint:union.combine(joint,false,p.budget);
        }
        if(!p.budget.running())union=QueryRegion.unknown();
        var classification=union.classify(p.budget);var yes=union.check(p.budget,Optional.empty());var no=union.negate().check(p.budget,Optional.empty());
        if(!p.budget.running())classification=Classification.UNKNOWN;
        if(classification==Classification.UNKNOWN)p.budget.qualify("PREDICATE_TRUTH_UNKNOWN");
        var regionId=ContentDigest.sha256Utf8(CanonicalJson.write(List.of(ArchitectureCondition.VERSION,request.identity(),union.identity())));
        Function<Witness,Witness> bind=w->new Witness(w.world(),w.assignment(),regionId,w.formulaReplayed(),w.semanticReplayed());
        var value=new ArchitectureCondition.Evaluation(regionId,classification,yes.witness().map(bind),no.witness().map(bind),bounded(evidence,p.budget),bounded(regions,p.budget));
        return result(p,"condition",List.of(ArchitectureCondition.VERSION,predicate),List.of(value),Optional.empty(),Optional.of(1L));
    }
    public Result<ArchitectureCondition.Evaluation> ruleCondition(Request request,ArchitectureCondition.RulePredicate predicate){
        Objects.requireNonNull(predicate);
        if(request.projection()!=Projection.BOUNDED_UNION||request.direction()!=Direction.OUTGOING)
            throw new IllegalArgumentException("Rule composition requires outgoing BOUNDED_UNION dependencies");
        var p=new Prepared(request,true);var index=new HashMap<String,LogicalEdge>();
        if(p.budget.reserve(2L*p.edges.size()))p.edges.forEach(e->index.put(e.view.identity(),e));
        var universe=p.catalog==null?QueryRegion.unknown():p.catalog.universe();
        QueryRegion union=universe.negate();var evidence=new TreeSet<ContentDigest>();var regions=new TreeSet<String>();
        for(var alternative:predicate.alternatives()){
            QueryRegion joint=universe;
            for(String id:alternative.term().edges()){
                if(!p.budget.step()){joint=QueryRegion.unknown();break;}
                var edge=index.get(id);
                if(edge==null){p.budget.qualify("PREDICATE_EDGE_UNAVAILABLE");joint=QueryRegion.unknown();}
                else{
                    joint=joint.combine(edge.region,true,p.budget);
                    if(p.budget.reserve((long)edge.evidence.size()+edge.regions.size())){evidence.addAll(edge.evidence);regions.addAll(edge.regions);}
                }
            }
            if(!alternative.eligible()){joint=joint.uncertain();p.budget.qualify("PREDICATE_ELIGIBILITY_UNKNOWN");}
            union=union.combine(joint,false,p.budget);
        }
        if(!predicate.complete()){union=union.combine(universe.uncertain(),false,p.budget);p.budget.qualify("RULE_DENOMINATOR_INCOMPLETE");}
        if(!p.budget.running())union=QueryRegion.unknown();
        var classification=union.classify(p.budget);var yes=union.check(p.budget,Optional.empty());var no=union.negate().check(p.budget,Optional.empty());
        if(!p.budget.running())classification=Classification.UNKNOWN;
        if(classification==Classification.UNKNOWN)p.budget.qualify("PREDICATE_TRUTH_UNKNOWN");
        var regionId=ContentDigest.sha256Utf8(CanonicalJson.write(List.of(ArchitectureCondition.RULE_VERSION,request.identity(),union.identity())));
        Function<Witness,Witness> bind=w->new Witness(w.world(),w.assignment(),regionId,w.formulaReplayed(),w.semanticReplayed());
        var value=new ArchitectureCondition.Evaluation(regionId,classification,yes.witness().map(bind),no.witness().map(bind),bounded(evidence,p.budget),bounded(regions,p.budget));
        return result(p,"rule-condition",List.of(ArchitectureCondition.RULE_VERSION,predicate),List.of(value),Optional.empty(),Optional.of(1L));
    }
    public Result<EvidenceView> evidence(Request request,ContentDigest reference){
        var p=new Prepared(request,false);
        boolean allowed=p.nodes.values().stream().anyMatch(n->n.evidence().contains(reference))
                ||graph.edges().stream().anyMatch(e->e.context().equals(request.context())&&e.evidence().contains(reference))
                ||graph.contexts().stream().anyMatch(c->c.identity().equals(request.context())&&c.evidence().equals(reference));
        var evidence=allowed?graph.evidence(reference):Optional.<GraphEvidence>empty();if(evidence.isEmpty())p.budget.qualify("EVIDENCE_UNAVAILABLE");
        return result(p,"evidence",reference,evidence.stream().map(e->describeEvidence(e,p.budget)).toList(),Optional.empty(),Optional.empty());
    }
    public Result<EvidenceView> explain(Request request,String identity,Page page){
        var p=new Prepared(request,false);var node=p.nodes.get(identity);var refs=new TreeSet<ContentDigest>();
        if(node!=null&&p.budget.reserve(node.evidence().size()))refs.addAll(node.evidence());
        for(var edge:graph.edges())if(edge.context().equals(request.context())&&edge.identity().equals(identity)&&p.budget.reserve(edge.evidence().size()))refs.addAll(edge.evidence());
        if(refs.isEmpty())p.budget.qualify("EVIDENCE_UNAVAILABLE");
        return evidencePage(p,"explain",identity,refs.stream().map(r->graph.evidence(r).orElseThrow()).toList(),page);
    }
    public Result<EntityView> gaps(Request request,Page page){return inventory(request,Set.of("CAPABILITY_GAP","ACQUISITION_ATTEMPT","PROVIDER_CONFLICT"),page,"gaps");}
    public Result<EntityView> spring(Request request,Set<String> kinds,Page page){
        if(!Set.of("BEAN_PRODUCER","BEAN_DEFINITION_CANDIDATE","INJECTION_POINT","INJECTION_USE","REGISTRATION_EVENT","REGISTRATION_TRANSITION","ENDPOINT").containsAll(kinds)||kinds.isEmpty())throw new IllegalArgumentException("Explicit Spring inventory kinds required");
        var p=new Prepared(request,false);
        if(kinds.contains("ENDPOINT")&&p.entities.values().stream().noneMatch(n->n.kind().equals("ENDPOINT")))p.budget.qualify("ENDPOINT_COVERAGE_UNAVAILABLE");
        return page(p,"spring",new TreeSet<>(kinds),p.entities.values().stream().filter(n->kinds.contains(n.kind())).toList(),EntityView::identity,page);
    }
    public Result<EntityView> configurations(Request request,Page page){return inventory(request,Set.of("CONFIGURATION_WORLD","CONFIGURATION_WITNESS","WITNESS_REPLAY","CONDITIONAL_FACT","TRUTH_REGION","CONFIGURATION_CONDITION","CONDITION_EXPRESSION","REGION_FORMULA"),page,"configuration");}
    private Result<EntityView> inventory(Request request,Set<String> kinds,Page page,String operation){
        var p=new Prepared(request,false);return page(p,operation,new TreeSet<>(kinds),p.entities.values().stream().filter(n->kinds.contains(n.kind())).toList(),EntityView::identity,page);
    }
    public Result<EvidenceView> provenance(Request request){
        var p=new Prepared(request,false,true);var context=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
        return result(p,"provenance","",List.of(describeEvidence(graph.evidence(context.evidence()).orElseThrow(),p.budget)),Optional.empty(),Optional.of(1L));
    }
    /** Graph-level provenance catalog: no cross-context arithmetic. Useful for modeled-space/parameter descriptors. */
    public Result<EvidenceView> artifacts(Request request,Set<String> families,Page page){
        if(families.isEmpty())throw new IllegalArgumentException("Explicit evidence families required");var p=new Prepared(request,false);var values=new ArrayList<GraphEvidence>();
        for(var evidence:graph.evidence())if(families.contains(evidence.family())){
            if(!p.budget.scan())break;if(!p.budget.reserve(1))break;values.add(evidence);}
        return evidencePage(p,"artifacts",new TreeSet<>(families),values,page);
    }
    private Result<EvidenceView> evidencePage(Prepared p,String operation,Object argument,List<GraphEvidence> references,Page page){
        // Select at most a page of references before materializing/redacting any payload descriptors.
        var selected=page(p,operation,argument,references,e->e.identity().value(),page);
        var descriptors=selected.items().stream().map(e->describeEvidence(e,p.budget)).toList();
        return result(p,operation,List.of(argument,"identity-ascending",Math.min(page.size(),p.request.limits().maxResults())),descriptors,selected.continuation(),selected.exactTotal());
    }
    public Result<String> metricCatalog(Request request,Page page){
        validate(request);
        if(metrics.isPresent())return metrics.orElseThrow().catalog(request,page);
        var p=new Prepared(request,false);p.budget.qualify("METRICS_PROVIDER_UNAVAILABLE");return result(p,"metric-catalog","",List.of(),Optional.empty(),Optional.empty());
    }
    public Result<Map<String,Object>> metric(Request request,String metric,String subject){
        validate(request);
        if(metrics.isPresent())return metrics.orElseThrow().evaluate(request,metric,subject);
        var p=new Prepared(request,false);p.budget.qualify("METRICS_PROVIDER_UNAVAILABLE");return result(p,"metric",List.of(metric,subject),List.of(),Optional.empty(),Optional.empty());
    }
    /** Conservative preparation for M11, not a temporal diff or approval of an evolution gate. */
    public Result<Compatibility> comparisonPreparation(Request request,KnowledgeGraph other,String otherContext){
        Objects.requireNonNull(other);var p=new Prepared(request,false);var right=other.contexts().stream().filter(c->c.identity().equals(otherContext)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("Foreign comparison context"));
        var left=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();var reasons=new TreeSet<String>();
        var l=graph.evidence(left.evidence()).orElseThrow();var r=other.evidence(right.evidence()).orElseThrow();
        if(!l.family().equals(r.family()))reasons.add("CONTEXT_FAMILY_DRIFT");
        var leftPolicy=graphPolicy(graph,left,p.budget);var rightPolicy=graphPolicy(other,right,p.budget);
        if(!leftPolicy.equals(rightPolicy)){
            reasons.add("BUILD_OR_PROVIDER_POLICY_DRIFT");
            if(leftPolicy instanceof Map<?,?> lm&&rightPolicy instanceof Map<?,?> rm){var keys=new TreeSet<String>();lm.keySet().forEach(k->keys.add(k.toString()));rm.keySet().forEach(k->keys.add(k.toString()));
                keys.stream().filter(k->!Objects.equals(lm.get(k),rm.get(k))).forEach(k->reasons.add("POLICY_FIELD_"+k));}
        }
        if(!formulaPolicy(graph,left).equals(formulaPolicy(other,right)))reasons.add("FORMULA_ENCODING_DRIFT");
        if(!Set.of("analysis.manifest","spring.context").contains(l.family())||!Set.of("analysis.manifest","spring.context").contains(r.family()))reasons.add("COMPATIBILITY_EVIDENCE_UNAVAILABLE");
        if(p.budget.stop!=StopReason.NONE||p.budget.reasons.contains("EVIDENCE_DESCRIPTOR_LIMIT")||p.budget.reasons.contains("COMPATIBILITY_EVIDENCE_UNAVAILABLE"))reasons.add("INCOMPLETE_COMPATIBILITY_EVIDENCE");
        return result(p,"comparison-preparation",List.of(other.identity(),otherContext),List.of(new Compatibility(graph.identity().equals(other.identity()),reasons.isEmpty(),List.copyOf(reasons))),Optional.empty(),Optional.of(1L));
    }
    private Object graphPolicy(KnowledgeGraph value,KnowledgeGraph.Context context,QueryBudget budget){
        var evidence=value.evidence(context.evidence()).orElseThrow();var payload=read(evidence,budget);
        if(evidence.family().equals("analysis.manifest")){
            var policy=select(payload,Set.of("manifestVersion","modules","classpath","configuration","analyzer","ruleSet","graphSchema"));
            if(payload.get("configuration") instanceof Map<?,?>){var configuration=GraphJson.object(payload.get("configuration"));
                var values=new TreeMap<>(GraphJson.object(configuration.get("values")));
                // These are exact-input bindings, not analysis policy. Ordered binary classpath and module/source-set
                // policy remain compared independently. Snapshot/source edits are normal evolution inputs.
                Set.of("java.source-plan","java.source-decoding","java.reactor-sources","java.classpath-manifest","ingestion.source-input","ingestion.build-model").forEach(values::remove);
                policy.put("configuration",Map.of("schema",configuration.get("schema"),"values",values));
            }else budget.qualify("COMPATIBILITY_EVIDENCE_UNAVAILABLE");
            return policy;
        }
        if(evidence.family().equals("spring.context")){
            var build=Optional.ofNullable(payload.get("build")).filter(Map.class::isInstance).map(GraphJson::object).orElse(Map.of());
            var policy=new TreeMap<>(select(build,Set.of("moduleSourceSet","platformViewIdentity")));policy.put("parameters",payload.get("parameters"));
            if(build.get("orderedResolutionInputs") instanceof List<?> ordered)policy.put("orderedResolutionInputs",ordered.stream().map(item->{var m=GraphJson.object(item);
                return "REACTOR_SOURCE".equals(m.get("kind"))?Map.of("kind","REACTOR_SOURCE"):m;}).toList());
            var analysis=context.analysis().flatMap(id->value.contexts().stream().filter(c->c.identity().equals(id.value())).findFirst());
            if(analysis.isPresent()&&value.evidence(analysis.orElseThrow().evidence()).orElseThrow().family().equals("analysis.manifest"))policy.put("analysisPolicy",graphPolicy(value,analysis.orElseThrow(),budget));
            else budget.qualify("COMPATIBILITY_EVIDENCE_UNAVAILABLE");
            return policy;
        }
        return Map.of("family",evidence.family());
    }
    private static Map<String,Object> select(Map<String,Object> input,Set<String> keys){var value=new TreeMap<String,Object>();keys.forEach(k->{if(input.containsKey(k))value.put(k,input.get(k));});return value;}
    private Set<String> formulaPolicy(KnowledgeGraph value,KnowledgeGraph.Context context){
        var kinds=new TreeSet<String>();for(var node:value.nodes())if(node.context().equals(context.identity())&&node.kind().equals("TRUTH_REGION"))for(var ref:node.evidence()){
            String family=value.evidence(ref).orElseThrow().family();if(family.equals("spring.explicit-region"))kinds.add("explicit-worlds-m4d-v1");
            else if(family.equals("spring.symbolic-region"))kinds.add("symbolic-dag-m4u.2-v1");else kinds.add("unknown");}
        return kinds;
    }
    static void requireEndpoint(Prepared p,String identity){if(!p.entities.containsKey(identity)&&p.budget.stop==StopReason.NONE)throw new IllegalArgumentException("Endpoint outside query population/context");}
    private <T> Result<T> page(Prepared p,String operation,Object argument,List<T> values,Function<T,String> key,Page page){
        int size=Math.min(page.size(),p.request.limits().maxResults());var binding=queryId(p.request,operation,List.of(argument,"identity-ascending",size));
        int start=0;
        if(page.cursor().isPresent()){
            try {String decoded=new String(Base64.getUrlDecoder().decode(page.cursor().orElseThrow()),StandardCharsets.UTF_8);String prefix=binding.value()+"|";
                if(!decoded.startsWith(prefix))throw new IllegalArgumentException("Foreign cursor");String last=decoded.substring(prefix.length());
                int found=-1;for(int i=0;i<values.size();i++)if(key.apply(values.get(i)).equals(last)){found=i;break;}
                if(found<0)throw new IllegalArgumentException("Stale cursor");start=found+1;
            }catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid or stale query cursor");}
        }
        int end=Math.min(values.size(),start+size);var selected=values.subList(start,end);Optional<String> continuation=Optional.empty();
        if(end<values.size()&&p.budget.stop==StopReason.NONE){continuation=Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString((binding.value()+"|"+key.apply(selected.getLast())).getBytes(StandardCharsets.UTF_8)));p.budget.halt(StopReason.OUTPUT);}
        return result(p,operation,List.of(argument,"identity-ascending",size),selected,continuation,p.budget.reasons.stream().allMatch(r->r.equals("OUTPUT"))?Optional.of((long)values.size()):Optional.empty());
    }
    private <T> Result<T> result(Prepared p,String operation,Object argument,List<T> values,Optional<String> continuation,Optional<Long> total){
        var query=queryId(p.request,operation,argument);var gaps=p.budget.reasons.stream().map(reason->gap(p,query,reason)).toList();
        var evidence=new TreeSet<ContentDigest>();
        for(var node:p.nodes.values())if(node.kind().equals("CAPABILITY_GAP")||node.kind().equals("PROVIDER_CONFLICT"))evidence.addAll(node.evidence());
        for(var value:values){if(value instanceof EntityView v)evidence.addAll(v.evidence());else if(value instanceof EdgeView v)evidence.addAll(v.evidence());
            else if(value instanceof EvidenceView v)evidence.add(v.identity());else if(value instanceof Path v)v.edges().forEach(e->evidence.addAll(e.evidence()));
            else if(value instanceof RelationshipView v)evidence.addAll(v.evidence());
            else if(value instanceof ArchitectureCondition.Evaluation v)evidence.addAll(v.evidence());
            else if(value instanceof Neighborhood v){v.entities().forEach(e->evidence.addAll(e.evidence()));v.edges().forEach(e->evidence.addAll(e.evidence()));}}
        var boundedEvidence=bounded(evidence,p.budget);
        gaps=p.budget.reasons.stream().map(reason->gap(p,query,reason)).toList();
        boolean partial=!gaps.isEmpty()||p.budget.stop!=StopReason.NONE;
        return new Result<>(query,partial?AssessmentStatus.PARTIAL:AssessmentStatus.COMPLETE,
                p.budget.stop==StopReason.CANCELLED?Operation.CANCELLED:p.budget.stop==StopReason.MEMORY||p.budget.stop==StopReason.TIME?Operation.RESOURCE_LIMIT:Operation.COMPLETED,
                values,boundedEvidence,gaps,p.budget.bounds(values.size()),continuation,partial&&p.budget.stop!=StopReason.OUTPUT?Optional.empty():total);
    }
    static ContentDigest queryId(Request request,String operation,Object argument){return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,request.identity(),operation,argument)));}
    private CapabilityGapRecord gap(Prepared p,ContentDigest query,String reason){
        var context=graph.contexts().stream().filter(c->c.identity().equals(p.request.context())).findFirst().orElseThrow();
        var observation=ProviderObservationReference.create(VERSION,"query.execution",graph.identity(),query);var subject=EvidenceSubject.observation(observation.identity());
        var requirement=new EvidenceRequirement(EvidenceRequirement.Kind.REPOSITORY_CONTENT,"query.complete-evidence",List.of(subject),EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Supply compatible complete graph/region evidence or rerun with sufficient bounded query resources."));
        return CapabilityGapRecord.create(new VersionedIdentifier("evidence.query-gaps","m5.2-v1"),new EvidenceContext(graph.snapshot(),context.analysis()),VERSION,
                "query.architecture",reason,subject,List.of(),List.of(observation),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.GRAPH_PROJECTION,"query.architecture")),List.of(),List.of(),
                List.of("The query is qualified; absence of returned items is not proof of absent architecture facts."));
    }
    private static <T> List<T> bounded(Collection<T> values,QueryBudget budget){
        if(values.size()>budget.limits.maxResults())budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");return values.stream().limit(budget.limits.maxResults()).toList();
    }
    private static String optionalText(Map<String,Object> value,String key){return value.get(key) instanceof String text?text:null;}
    private EntityView describe(KnowledgeGraph.Node node,Map<String,DocumentPopulation> documents,Map<String,Object> partition,QueryBudget budget){
        var name=Optional.<String>empty();var spelling=Optional.<String>empty();var module=Optional.<String>empty();var origin=Optional.<String>empty();var sourceSet=Optional.<String>empty();
        for(var ref:node.evidence()){
            var evidence=graph.evidence(ref).orElseThrow();
            if(evidence.family().equals("java.declaration")){
                var payload=read(evidence,budget);if(!(payload.get("entity") instanceof Map<?,?>))continue;var entity=GraphJson.object(payload.get("entity"));
                name=Optional.ofNullable((String)entity.get("canonicalName"));spelling=Optional.ofNullable((String)payload.get("spelling")).filter(ArchitectureQueryService::safeSpelling);origin=Optional.ofNullable((String)entity.get("origin"));
                String scope=(String)entity.get("stableScope");if(scope!=null&&scope.startsWith("module:"))module=Optional.of(scope);
                if(entity.get("declaration") instanceof Map<?,?> span){var document=documents.get(span.get("document"));if(document!=null)sourceSet=Optional.ofNullable(document.sourceSet());}
                if(node.kind().equals("PACKAGE")&&name.isPresent()&&name.orElseThrow().startsWith("java:v1:")){
                    try {
                        name=Optional.of(packageName(name.orElseThrow()));spelling=name.map(ArchitectureQueryService::qualifiedName);
                    }catch(IllegalArgumentException malformed){name=Optional.empty();spelling=Optional.empty();budget.qualify("INVALID_CANONICAL_NAME");}
                }
            }
            if(evidence.family().equals("source.document")){
                var document=documents.get(node.identity());
                if(document!=null){module=Optional.ofNullable(document.module());sourceSet=Optional.ofNullable(document.sourceSet());}
                name=sourcePath(read(evidence,budget),budget);spelling=name;
            }
            if(evidence.family().equals("module.descriptor")||evidence.family().equals("build.module")){
                var payload=read(evidence,budget);var descriptor=evidence.family().equals("build.module")?GraphJson.object(payload.get("descriptor")):payload;
                module=Optional.of(node.identity());origin=Optional.of("PROJECT");
                if(descriptor.get("path") instanceof String path)try{name=Optional.of(ContractChecks.modulePath(path));}
                catch(IllegalArgumentException invalid){budget.qualify("INVALID_MODULE_PATH");}
                spelling=name;
            }
        }
        if(sourceSet.isEmpty())sourceSet=Optional.ofNullable((String)partition.get("sourceSet"));
        if(module.isEmpty())module=Optional.ofNullable((String)partition.get("module"));
        return new EntityView(node.identity(),node.context(),node.kind(),name,name.map(ArchitectureQueryService::qualifiedName),spelling,module,origin,sourceSet,bounded(node.evidence(),budget));
    }
    private static String packageName(String canonical){
        var tuple=GraphJson.array(GraphJson.read(canonical.substring(8),new GraphJson.Limits(100_000,64,10_000)));
        for(int depth=0;depth<64;depth++){
            if(tuple.isEmpty())throw new IllegalArgumentException("Empty symbol tuple");
            String kind=GraphJson.string(tuple.getFirst());
            if(kind.equals("type")||kind.equals("package")){
                // Validate arity and package parts before projecting the owner package.
                label(tuple,depth);return "java:v1:"+CanonicalJson.write(List.of("package",tuple.get(1)));
            }
            if(tuple.size()<2)throw new IllegalArgumentException("Missing type owner");tuple=GraphJson.array(tuple.get(1));
        }
        throw new IllegalArgumentException("Type owner depth limit");
    }
    /** Display only: structural M1 names remain canonical identity authority. */
    private static String qualifiedName(String canonical){
        if(!canonical.startsWith("java:v1:"))return canonical;
        try {return label(GraphJson.array(GraphJson.read(canonical.substring(8),new GraphJson.Limits(100_000,64,10_000))),0);}
        catch(IllegalArgumentException unknown){return canonical;}
    }
    private static String label(List<Object> tuple,int depth){
        if(depth>64||tuple.size()<2)throw new IllegalArgumentException("Unknown symbol grammar");
        String kind=GraphJson.string(tuple.getFirst());
        if(kind.equals("package")||kind.equals("type")){
            if(tuple.size()!=(kind.equals("package")?2:3))throw new IllegalArgumentException("Invalid package/type tuple arity");
            String pkg=String.join(".",GraphJson.array(tuple.get(1)).stream().map(GraphJson::string).map(ArchitectureQueryService::decodeName).toList());
            return kind.equals("package")?pkg:(pkg.isEmpty()?"":pkg+".")+decodeName(GraphJson.string(tuple.get(2)));
        }
        String owner=label(GraphJson.array(tuple.get(1)),depth+1);
        if(Set.of("member-type","method","field","record-component").contains(kind)){
            if(tuple.size()<3)throw new IllegalArgumentException("Missing member name");return owner+"."+decodeName(GraphJson.string(tuple.get(2)));}
        return owner;
    }
    private static String decodeName(String name){var value=new StringBuilder();for(int i=0;i<name.length();i++){
        if(name.charAt(i)=='%'&&i+6<name.length()){value.appendCodePoint(Integer.parseInt(name.substring(i+1,i+7),16));i+=6;}else value.append(name.charAt(i));}return value.toString();}
    private boolean included(EntityView e,Population p){return(p.nodeKinds().isEmpty()||p.nodeKinds().contains(e.kind()))&&(p.modules().isEmpty()||e.module().filter(p.modules()::contains).isPresent())
            &&(p.origins().isEmpty()||e.origin().filter(p.origins()::contains).isPresent())&&(p.sourceSets().isEmpty()||e.sourceSet().filter(p.sourceSets()::contains).isPresent());}
    private static boolean knownExcluded(EntityView e,Population p){return e!=null&&(
            !p.modules().isEmpty()&&e.module().isPresent()&&!p.modules().contains(e.module().orElseThrow())
            ||!p.origins().isEmpty()&&e.origin().isPresent()&&!p.origins().contains(e.origin().orElseThrow())
            ||!p.sourceSets().isEmpty()&&e.sourceSet().isPresent()&&!p.sourceSets().contains(e.sourceSet().orElseThrow()));}
    private static Optional<String> sourcePath(Map<String,Object> payload,QueryBudget budget){
        if(!(payload.get("path") instanceof String path))return Optional.empty();
        try{return Optional.of(com.evolution.analysis.contract.common.ContractChecks.repositoryRelativePath(path,"source path"));}
        catch(IllegalArgumentException invalid){budget.qualify("INVALID_SOURCE_PATH");return Optional.empty();}
    }
    private Map<String,Object> read(GraphEvidence evidence,QueryBudget budget){
        try{return GraphJson.object(GraphJson.read(evidence.canonicalJson(),new GraphJson.Limits(8_000_000,128,budget.limits.maxAuxiliaryStates())));}
        catch(IllegalArgumentException malformed){budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");return Map.of();}
    }
    private static final Set<String> SAFE_FIELDS=Set.of("identity","kind","entity","canonicalName","spelling","origin","stableScope","module","classification","document","span","sourceSpan","sourceSpans",
            "startOffset","endOffset","startLine","endLine","startColumn","endColumn","ordinal","status","derivation","derivationKind","provider","version","id","inputs","relationship","source","target","candidates",
            "fact","definition","injectionPoint","bindingOwner","factSemanticsVersion","trueWorlds","falseWorlds","unknownWorlds","trueRegion","falseRegion","unknownRegion","encoding","space","evaluationInput","semanticsContext",
            "reasonCode","mechanismCategory","schemaVersion","detectingProvider","observationReferences","acquisitionAttemptReferences","affectedOutputs","gapIdentity","outcome","permissionDecision","trustDecision",
            "factRegionIdentity","legalOrderIdentity","expectedTruth","witnessPolicyVersion","witness","replayed","operator","children","analysis","planIdentity","parameters","truthLimits","reasonerPolicy","evaluatorArtifact","maxSignatures","symbolicLimits",
            "schema","contentIdentity","semanticIdentity","contexts","nodes","edges","evidencePayloads","snapshotIdentity","analysisIdentity","requirement","attempt","conflict","rootIdentity",
            "producer","producerKind","buildContextIdentity","declaredNameKey","primary","aliases","exposedTypes","declarationEvidenceKey","method","owner","point","site","type","qualifiers",
            "actualTruth","assignmentFeasibility","valid","independentEvaluationDigest","branchEvidence","baselineConfigurationIdentity","feasibility","enumerationComplete","feasibleWorlds","unknownFeasibilityWorlds","declaredAssignments",
            "operationalStatus","exogenousEvaluationIdentity","bindingResultIdentity","facts","truth","abstractionVersion","feasibilityPolicy","precedencePolicy","profilePolicy",
            "discoveryEvent","operation","readerDecision","gateExpectation","gate","alias","eventIdentity","expressionIdentity","role","evidence",
            "declaration","manifest","manifestVersion","modules","configuration","analyzer","ruleSet","graphSchema","classpath","contentDigest","artifactDigest","entryDigest","rawDigest","build","moduleSourceSet","sourceSet",
            "maxDepth","maxSteps","maxNodes","maxClauses","maxVariables","maxFacts","maxRegionCells","maxWitnessReplays","maxCartesianAssignments","maxExpressionVisits","maxExpressionDepth");
    private static final Set<String> SAFE_FAMILIES=Set.of("analysis.manifest","source.request","source.document","java.declaration","java.occurrence","java.type-use","java.type-shape","java.member-shape","java.annotation-use","java.source-outcome",
            "evidence.gap","evidence.attempt","evidence.conflict","evidence.resolution","spring.context","spring.producer","spring.candidate","spring.injection-dependency","spring.registration-event","spring.registration-step",
            "spring.condition","spring.condition-expression","spring.space","spring.evaluation-parameters","spring.world","spring.explicit-region","spring.symbolic-region","spring.fact-key","spring.witness","spring.witness-replay","spring.formula");
    private EvidenceView describeEvidence(GraphEvidence evidence,QueryBudget budget){
        if(!SAFE_FAMILIES.contains(evidence.family()))return new EvidenceView(evidence.identity(),evidence.family(),true,Map.of());
        var value=new TreeMap<>(read(evidence,budget));if(!evidence.family().equals("java.declaration"))value.remove("spelling");
        var metadata=new TreeMap<>(GraphJson.object(sanitize(value,0,budget)));
        if(evidence.family().equals("source.document"))sourcePath(value,budget).ifPresent(path->metadata.put("path",path));
        if(evidence.family().equals("analysis.manifest")&&value.get("configuration") instanceof Map<?,?> configuration
                &&configuration.get("values") instanceof Map<?,?> options){
            Object module=options.get("java.module"),set=options.get("java.source-set");
            if(value.get("modules") instanceof List<?> modules)for(var descriptor:modules){
                if(!(descriptor instanceof Map<?,?> row)||!Objects.equals(row.get("identity"),module))continue;
                if(module instanceof String id&&budget.reserve(2))metadata.put("module",id);
                if(row.get("path") instanceof String path)try{
                    String relative=ContractChecks.modulePath(path);if(budget.reserve(2))metadata.put("modulePath",relative);
                }catch(IllegalArgumentException invalid){budget.qualify("INVALID_MODULE_PATH");}
                break;
            }
            if(set instanceof String s&&Set.of("MAIN","TEST","GENERATED_MAIN","GENERATED_TEST").contains(s)&&budget.reserve(2))metadata.put("sourceSet",s);
        }
        if(evidence.family().equals("spring.space")&&value.get("domains") instanceof List<?> domains){
            if(domains.size()>budget.limits.maxResults())budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");
            long permitted=Math.min(budget.limits.maxResults(),Math.max(0,(budget.limits.maxAuxiliaryStates()-budget.auxiliary-budget.descriptorUnits)/4));
            if(domains.size()>permitted)budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");budget.descriptorUnits+=4*Math.min(domains.size(),permitted);
            metadata.put("domainCount",domains.size());metadata.put("domains",domains.stream().limit(permitted).map(d->{var domain=GraphJson.object(d);var variable=GraphJson.object(domain.get("variable"));
                return Map.of("kind",variable.get("kind"),"variableDigest",ContentDigest.sha256Utf8(CanonicalJson.write(variable)),"valueCount",GraphJson.array(domain.get("values")).size());}).toList());
        }
        return new EvidenceView(evidence.identity(),evidence.family(),!CanonicalJson.write(metadata).equals(evidence.canonicalJson()),metadata);
    }
    private Object sanitize(Object value,int depth,QueryBudget budget){
        if(budget.auxiliary+budget.descriptorUnits>=budget.limits.maxAuxiliaryStates()){budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");return Map.of();}budget.descriptorUnits++;
        if(depth>20){budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");return Map.of();}
        if(value instanceof Map<?,?> map){var safe=new TreeMap<String,Object>();for(var entry:map.entrySet())if(SAFE_FIELDS.contains(entry.getKey())&&(!entry.getKey().equals("spelling")||safeSpelling(entry.getValue())))safe.put((String)entry.getKey(),sanitize(entry.getValue(),depth+1,budget));return safe;}
        if(value instanceof List<?> list){if(list.size()>budget.limits.maxResults())budget.qualify("EVIDENCE_DESCRIPTOR_LIMIT");return list.stream().limit(budget.limits.maxResults()).map(v->sanitize(v,depth+1,budget)).toList();}
        return value;
    }
    /** DeclarationRecord.spelling may contain an entire class/method body, not just a name. */
    private static boolean safeSpelling(Object value){return value instanceof String text&&javax.lang.model.SourceVersion.isName(text,javax.lang.model.SourceVersion.RELEASE_21);}
}
