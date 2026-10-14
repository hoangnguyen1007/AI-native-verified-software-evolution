package com.evolution.analysis.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.spring.truth.SymbolicConfiguration;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Reconstitutes the neutral, hash-verified M4 DAG, never parser objects or Cartesian worlds. */
final class GraphRegionCatalog {
    final KnowledgeGraph graph;final Request request;final QueryBudget budget;
    final Map<String,KnowledgeGraph.Node> nodes;
    final Map<String,QueryRegion> regions=new HashMap<>();
    final Map<String,Map<String,Object>> formulaNodes=new TreeMap<>();
    final Map<String,Formula> formulas=new HashMap<>();
    final Map<String,Boolean> completeEvaluations=new HashMap<>();
    final Map<String,List<QueryRegion>> activation=new HashMap<>();
    final Set<String> preferredFacts=new HashSet<>();
    final Set<String> evaluationInputs=new TreeSet<>();
    boolean symbolic;
    boolean realizationValid=true;
    Optional<String> expectedSpace=Optional.empty();
    GraphRegionCatalog(KnowledgeGraph graph,Request request,QueryBudget budget,Map<String,KnowledgeGraph.Node> nodes){
        this.graph=graph;this.request=request;this.budget=budget;this.nodes=nodes;
        try {
            for(var node:nodes.values())if(node.kind().equals("REGION_FORMULA")){
                if(!budget.scan())return;
                if(formulaNodes.size()>=request.limits().maxFormulaNodes()){budget.halt(StopReason.INPUT);return;}
                var payload=payload(node,"spring.formula");if(payload.isPresent()){
                    var form=payload.orElseThrow();if(!budget.reserve(2L+GraphJson.array(form.get("children")).size()))return;formulaNodes.put(node.identity(),form);}
            }
            symbolic=!formulaNodes.isEmpty()&&request.realization().flatMap(Realization::world).isEmpty();
            var context=graph.contexts().stream().filter(c->c.identity().equals(request.context())).findFirst().orElseThrow();
            var contextPayload=read(graph.evidence(context.evidence()).orElseThrow());
            expectedSpace=Optional.ofNullable((String)contextPayload.get("space"));
            for(var node:nodes.values())if(node.kind().equals("TRUTH_REGION")){
                if(!budget.scan())return;
                payload(node,symbolic?"spring.symbolic-region":"spring.explicit-region").ifPresent(p->{
                    preferredFacts.add(com.evolution.analysis.contract.serialization.CanonicalJson.write(object(object(p,"region"),"fact")));
                    if(p.get("evaluationInput") instanceof String input)evaluationInputs.add(input);
                    if(contextPayload.get("space") instanceof String space&&!space.equals(p.get("space")))budget.qualify("INCOMPATIBLE_REGION_SPACE");
                });
            }
            if(evaluationInputs.size()>1)budget.qualify("MIXED_EVALUATION_CONTEXT");
            for(var evidence:graph.evidence())if(evidence.family().equals("spring.symbolic-result")){
                if(!budget.scan())return;
                // Canonical token prefilter avoids parsing unrelated contexts' potentially large result payloads.
                if(evaluationInputs.stream().noneMatch(id->evidence.canonicalJson().contains("\"inputIdentity\":\""+id+"\"")))continue;
                var value=read(evidence);String id=string(value,"inputIdentity");
                if(evaluationInputs.contains(id))completeEvaluations.put(id,Boolean.TRUE.equals(value.get("signaturesComplete")));
            }
            for(var node:nodes.values())if(node.kind().equals("TRUTH_REGION")){
                if(!budget.scan())return;
                if(!budget.reserve(1))return;
                var region=load(node);regions.put(node.identity(),region);
                String family=family(node);
                payload(node,family).ifPresent(p->{var fact=object(object(p,"region"),"fact");
                    if("DEFINITION_PRESENT".equals(fact.get("kind"))&&fact.get("definition") instanceof String definition)
                        activation.computeIfAbsent(definition,k->new ArrayList<>()).add(region);});
            }
            request.realization().ifPresent(r->{
                if(r.world().isPresent())realizationValid=regions.values().stream().anyMatch(v->v.encoding==QueryRegion.Encoding.EXPLICIT&&v.universe.contains(r.world().orElseThrow()));
                else {
                    var variables=new TreeSet<String>();formulaNodes.values().forEach(f->{if("VARIABLE".equals(f.get("operator")))variables.add(string(f,"variable"));});
                    realizationValid=r.assignment().keySet().equals(variables)&&regions.values().stream().anyMatch(v->{
                        if(v.encoding!=QueryRegion.Encoding.SYMBOLIC)return false;
                        try{return v.universeFormula.evaluate(r.assignment());}catch(NullPointerException missing){return false;}});
                }
                if(!realizationValid)budget.qualify("REALIZATION_NOT_IN_MODELED_SPACE");
            });
        }catch(IllegalArgumentException malformed){budget.qualify("INVALID_REGION_EVIDENCE");}
    }
    QueryRegion region(KnowledgeGraph.Edge edge){return edge.region().map(id->regions.getOrDefault(id,QueryRegion.unknown())).orElseGet(QueryRegion::universal);}
    boolean preferred(KnowledgeGraph.Edge edge){
        if(edge.region().isEmpty())return true;
        var node=nodes.get(edge.region().orElseThrow());if(node==null)return true;
        var preferred=payload(node,symbolic?"spring.symbolic-region":"spring.explicit-region");if(preferred.isPresent())return true;
        var alternate=payload(node,symbolic?"spring.explicit-region":"spring.symbolic-region");
        return alternate.isEmpty()||!preferredFacts.contains(com.evolution.analysis.contract.serialization.CanonicalJson.write(object(object(alternate.orElseThrow(),"region"),"fact")));
    }
    QueryRegion activate(String node){
        var values=activation.get(node);if(values==null||values.isEmpty())return QueryRegion.universal();
        QueryRegion result=values.getFirst();for(int i=1;i<values.size();i++)result=result.combine(values.get(i),false,budget);return result;
    }
    private QueryRegion load(KnowledgeGraph.Node node){
        try {
            String family=family(node);var value=payload(node,family);if(value.isEmpty())return QueryRegion.unknown();
            var wrapper=value.orElseThrow();var r=object(wrapper,"region");var fact=object(r,"fact");
            if(expectedSpace.isPresent()&&!expectedSpace.orElseThrow().equals(wrapper.get("space")))throw new IllegalArgumentException("Foreign region space");
            if(!Map.of("id","spring.conditional-fact","version","m4d-v1").equals(object(fact,"factSemanticsVersion")))
                throw new IllegalArgumentException("Unsupported fact semantics");
            var c=Classification.valueOf(string(r,"classification"));
            if(family.equals("spring.symbolic-region")){
                if(!"symbolic-dag-m4u.2-v1".equals(wrapper.get("encoding")))throw new IllegalArgumentException("Unsupported symbolic encoding");
                String evaluation=string(wrapper,"evaluationInput");
                if(!completeEvaluations.containsKey(evaluation))budget.qualify("REGION_EVALUATION_UNAVAILABLE");
                var y=formula(string(r,"trueRegion"));var n=formula(string(r,"falseRegion"));var u=formula(string(r,"unknownRegion"));
                return QueryRegion.symbolic(request.context()+"|"+string(wrapper,"space")+"|"+evaluation,y,or(y,u),or(y,n,u),
                        completeEvaluations.getOrDefault(evaluation,false),c);
            }
            if(!"explicit-worlds-m4d-v1".equals(wrapper.get("encoding"))||!request.context().equals(wrapper.get("semanticsContext")))
                throw new IllegalArgumentException("Incompatible explicit context");
            var y=strings(r.get("trueWorlds"));var n=strings(r.get("falseWorlds"));var u=strings(r.get("unknownWorlds"));
            if(!budget.reserve(3L*(y.size()+n.size()+u.size())))return QueryRegion.unknown();
            var universe=new TreeSet<>(y);int size=y.size()+n.size()+u.size();universe.addAll(n);universe.addAll(u);
            if(universe.size()!=size||universe.stream().anyMatch(w->!nodes.containsKey(w)||!nodes.get(w).kind().equals("CONFIGURATION_WORLD")))
                throw new IllegalArgumentException("Invalid explicit worlds");
            var feasibility=object(r,"feasibility");boolean closed=Boolean.TRUE.equals(feasibility.get("enumerationComplete"))
                    &&new java.math.BigDecimal(feasibility.get("unknownFeasibilityWorlds").toString()).signum()==0;
            var possible=new TreeSet<>(y);possible.addAll(u);
            return QueryRegion.explicit(request.context()+"|"+string(wrapper,"space"),y,possible,universe,closed,c);
        }catch(IllegalArgumentException|NullPointerException malformed){budget.qualify("INVALID_REGION_EVIDENCE");return QueryRegion.unknown();}
    }
    private String family(KnowledgeGraph.Node node){String preferred=symbolic?"spring.symbolic-region":"spring.explicit-region";
        return payload(node,preferred).isPresent()?preferred:symbolic?"spring.explicit-region":"spring.symbolic-region";}
    private record Frame(String id,boolean exit,int depth) {}
    private Formula formula(String root){
        var pending=new ArrayDeque<Frame>();var visiting=new HashSet<String>();pending.push(new Frame(root,false,1));
        while(!pending.isEmpty()){
            if(!budget.scan())throw new IllegalArgumentException("Formula input budget");
            var frame=pending.pop();if(formulas.containsKey(frame.id()))continue;
            if(frame.depth()>128)throw new IllegalArgumentException("Formula depth limit");
            var node=formulaNodes.get(frame.id());if(node==null)throw new IllegalArgumentException("Missing formula");
            var children=GraphJson.array(node.get("children")).stream().map(GraphJson::string).toList();
            if(!frame.exit()){
                if(!visiting.add(frame.id()))throw new IllegalArgumentException("Cyclic formula");
                pending.push(new Frame(frame.id(),true,frame.depth()));
                for(int i=children.size()-1;i>=0;i--)if(!formulas.containsKey(children.get(i)))pending.push(new Frame(children.get(i),false,frame.depth()+1));
            }else{
                var operator=Operator.valueOf(string(node,"operator"));
                if((operator==Operator.TRUE||operator==Operator.FALSE||operator==Operator.VARIABLE)&&!children.isEmpty()
                        ||operator==Operator.NOT&&children.size()!=1)throw new IllegalArgumentException("Invalid formula arity");
                var values=children.stream().map(formulas::get).toList();
                Formula f=switch(operator){case TRUE->TRUE;case FALSE->FALSE;case VARIABLE->variable(string(node,"variable"));
                    case NOT->not(values.getFirst());case AND->and(values);case OR->or(values);};
                if(!f.identity().value().equals(frame.id())||!frame.id().equals(node.get("identity")))throw new IllegalArgumentException("Formula digest mismatch");
                formulas.put(frame.id(),f);visiting.remove(frame.id());
            }
        }
        return formulas.get(root);
    }
    Optional<Map<String,Object>> payload(KnowledgeGraph.Node node,String family){
        return node.evidence().stream().map(id->graph.evidence(id).orElseThrow()).filter(e->e.family().equals(family)).findFirst().map(this::read);
    }
    Map<String,Object> read(GraphEvidence evidence){return GraphJson.object(GraphJson.read(evidence.canonicalJson(),new GraphJson.Limits(8_000_000,128,request.limits().maxAuxiliaryStates())));}
    static Map<String,Object> object(Map<String,Object> value,String key){return GraphJson.object(value.get(key));}
    static String string(Map<String,Object> value,String key){return GraphJson.string(value.get(key));}
    static Set<String> strings(Object value){var list=GraphJson.array(value).stream().map(GraphJson::string).toList();var set=new TreeSet<>(list);if(set.size()!=list.size())throw new IllegalArgumentException("Duplicate world");return set;}
}
