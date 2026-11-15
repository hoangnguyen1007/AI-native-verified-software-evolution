package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.io.StringReader;
import java.util.*;

import static com.evolution.analysis.evolution.EvolutionRows.*;

/** Indexes existing M4 evidence by the producer's exact reference, never by a context name. */
final class SpringOperands {
    private SpringOperands() {}

    static void index(EvolutionInput side, GraphEvidence evidence) throws IOException {
        String family=evidence.family();
        switch(family) {
            case "java.declaration", "source.document" -> {
                var raw=EvolutionInput.payload(evidence);
                String id=family.equals("java.declaration")?text(obj(raw.get("entity")),"identity"):text(raw,"identity");
                unique(side.run.index,"spring-source-support",side.name+"|"+id+"|"+evidence.identity().value(),
                        row("evidence",evidence.identity(),"family",family));
            }
            case "spring.space", "spring.configuration-envelope", "spring.plan" -> {
                var raw=EvolutionInput.payload(evidence);
                String identity=switch(family) {
                    case "spring.space" -> identity("spring-configuration-space",space(raw));
                    case "spring.configuration-envelope" -> identity("spring-envelope",envelope(raw));
                    default -> planIdentity(raw);
                };
                remember(side,family,identity,evidence.identity());
            }
            case "spring.explicit-result", "spring.symbolic-result" -> indexResult(side,evidence);
            default -> { }
        }
    }

    static List<GraphEvidence> sourceSupport(EvolutionInput side,String id,String family,String analysis)throws IOException {
        var result=new ArrayList<GraphEvidence>();
        var rows=new Rows(side.run.index,"spring-source-support",side.name+"|"+id+"|");
        for(var item=rows.next();item!=null;item=rows.next()) {
            var value=item.value();
            if(!family.equals(value.get("family")))continue;
            var ref=new ContentDigest(text(value,"evidence"));
            if(analysis==null||side.frontendMember(analysis,"declarations",ref)){
                result.add(side.evidence(ref));
                if(result.size()==2)break; // ambiguity is explicit; no need to materialize the rest
            }
        }
        return result;
    }

    private static void indexResult(EvolutionInput side,GraphEvidence evidence)throws IOException {
        var meta=new TreeMap<String,Object>();
        GraphJson.streamObject(new StringReader(evidence.canonicalJson()),
                new GraphJson.StreamLimits(Math.max(1,evidence.canonicalJson().length()),128,Long.MAX_VALUE),
                (field,value)->{
                    switch(field) {
                        case "schema","provider","inputIdentity","semanticsContextIdentity","legalOrderIdentity",
                                "coverage","feasibility","identity","signaturesComplete" -> meta.put(field,value.read());
                        case "worlds","regions","witnesses","replays","traces" -> {
                            var ordinal=new long[1];
                            value.array(member->{
                                EvolutionSnapshot.check(side.run.cancelled);
                                String prefix=side.name+"|"+evidence.identity().value()+"|"+field+"|";
                                var digest=CanonicalJson.sha256(member);
                                unique(side.run.index,"spring-member",prefix+digest.value(),row("ordinal",ordinal[0]));
                                // Only symbolic traces have no separately keyed context node.
                                if(field.equals("traces"))unique(side.run.index,"spring-trace",
                                        prefix+digest.value(),obj(member));
                                ordinal[0]++;
                            });
                            meta.put(field+"Count",ordinal[0]);
                        }
                        default -> value.skip();
                    }
                });
        meta.put("evidence",evidence.identity());
        String key=evidence.family().equals("spring.explicit-result")?text(meta,"semanticsContextIdentity"):text(meta,"inputIdentity");
        var lookup=side.name+"|"+evidence.family()+"|"+key;
        var old=side.run.index.get("spring-result",lookup);
        side.run.index.put("spring-result",lookup,old.isEmpty()?meta:row("ambiguous",true));
    }

    static boolean member(EvolutionInput side,String result,String family,Object payload)throws IOException {
        return ordinal(side,result,family,payload)>=0;
    }

    static long ordinal(EvolutionInput side,String result,String family,Object payload)throws IOException {
        var value=side.run.index.get("spring-member",side.name+"|"+result+"|"+family+"|"+CanonicalJson.sha256(payload).value());
        return value.isEmpty()?-1:number(value.orElseThrow().get("ordinal"));
    }

    static Optional<Map<String,Object>> result(EvolutionInput side,String family,String identity)throws IOException {
        return side.run.index.get("spring-result",side.name+"|"+family+"|"+identity)
                .filter(r->!Boolean.TRUE.equals(r.get("ambiguous")));
    }

    private static void remember(EvolutionInput side,String family,String identity,ContentDigest ref)throws IOException {
        String key=side.name+"|"+family+"|"+identity;
        var old=side.run.index.get("spring-operands",key);
        side.run.index.put("spring-operands",key,old.isEmpty()?row("evidence",ref):row("ambiguous",true));
    }

    static GraphEvidence lookup(EvolutionInput side,String family,String identity)throws IOException {
        if(identity==null)return null;
        var match=side.run.index.get("spring-operands",side.name+"|"+family+"|"+identity).orElse(Map.of());
        String ref=maybe(match,"evidence");
        return ref==null?null:side.evidence(new ContentDigest(ref));
    }

    static String container(EvolutionInput side,Map<String,Object> context)throws IOException {
        var descriptor=EvolutionInput.payload(side.evidence(new ContentDigest(text(context,"evidence"))));
        var ev=lookup(side,"spring.plan",text(descriptor,"planIdentity"));
        if(ev==null)return null;
        var plan=EvolutionInput.payload(ev);
        var discovery=obj(plan.get("discovery"));
        if(!Objects.equals(discovery.get("build"),descriptor.get("build"))
                ||!Objects.equals(obj(plan.get("inventory")).get("analysis"),context.get("parentAnalysis")))return null;
        return text(discoveryInputs(plan).getFirst(),"key");
    }

    static Map<String,Object> discovery(Map<String,Object> plan) {return obj(obj(plan.get("discovery")).get("canonical"));}
    static Map<String,Object> registration(Map<String,Object> plan) {return obj(obj(plan.get("binding")).get("registrationPlan"));}
    static List<Map<String,Object>> discoveryInputs(Map<String,Object> plan) {
        return list(obj(discovery(plan).get("inputs")).get("containerHierarchy")).stream().map(EvolutionRows::obj).toList();
    }

    static String planIdentity(Map<String,Object> plan) {
        return CanonicalJson.sha256(List.of(plan.get("inputIdentity"),obj(plan.get("inventory")).get("identity"),
                CanonicalJson.sha256(plan.get("conditions")),bindingIdentity(plan),plan.get("gaps"),plan.get("methodSelections"))).value();
    }

    static ContentDigest bindingIdentity(Map<String,Object> plan) {
        var binding=new TreeMap<>(obj(plan.get("binding")));
        binding.put("schema","spring-injection-binding-plan-v2");
        binding.put("registrationPlan",registrationIdentity(plan));
        return CanonicalJson.sha256(binding);
    }

    static String registrationIdentity(Map<String,Object> plan) {
        var registration=new TreeMap<>(registration(plan));
        var discovery=discovery(plan);
        var inputs=obj(discovery.get("inputs"));
        registration.put("discoveryPlan",text(discovery,"identity"));
        registration.put("schema","spring-bean-registration-plan-v1");
        return identity("spring-registration-plan",row("buildContextIdentity",inputs.get("buildContextIdentity"),
                "frameworkSemantics",inputs.get("frameworkSemantics"),"eventDescriptors",registration,
                "precedenceConstraints",registration.get("establishedOrderPrefix"),"initialDefinitions",inputs.get("initialDefinitions"),
                "containerHierarchy",row("container",discoveryInputs(plan).getFirst(),"noParent",registration.get("noParentContainer"),
                        "evidence",registration.get("closureEvidence")),"orderCompleteness",registration.get("order"),
                "overridePolicy",inputs.get("overridePolicy")));
    }

    static Map<String,Object> space(Map<String,Object> raw) {
        var result=fields(raw,"domains","precedencePolicy","profilePolicy","abstractionVersion","feasibilityPolicy");
        result.put("buildContextIdentity",identity("spring-build-context",obj(raw.get("buildContext"))));
        result.put("repositoryEnvelopeIdentity",identity("spring-envelope",envelope(obj(raw.get("repositoryEnvelope")))));
        result.put("deploymentEnvelopeIdentity",raw.get("deploymentEnvelope")==null?null:
                identity("spring-envelope",envelope(obj(raw.get("deploymentEnvelope")))));
        result.put("constraints",list(raw.get("constraints")).stream().map(EvolutionRows::obj).map(constraint->{
            var resultRow=fields(constraint,"declarationEvidenceKey","metadataPath","role","ordinal");
            resultRow.put("expressionIdentity",text(obj(constraint.get("expression")),"rootIdentity"));
            return resultRow;
        }).toList());
        return result;
    }

    static Map<String,Object> assignment(Map<String,Object> raw) {
        if(raw.containsKey("schema"))return raw;
        Object baseline=raw.get("baseline");
        var values=baseline instanceof Map<?,?> empty&&empty.isEmpty()?List.of():list(baseline).stream().map(v->{
            var pair=list(v);return row("variable",pair.getFirst(),"value",pair.getLast());
        }).sorted(Comparator.comparing(v->variableKey(obj(v.get("variable"))))).toList();
        return row("schema","spring-configuration-assignment-v1","baseline",values,
                "sourceChoices",raw.get("sourceChoices"),"evidence",raw.get("evidence"));
    }

    static Map<String,Object> envelope(Map<String,Object> raw) {
        var sources=list(raw.get("declaredSources")).stream().map(EvolutionRows::obj).map(source->{
            var copy=new TreeMap<>(source);
            Object values=source.get("values");
            copy.put("values",values instanceof Map<?,?> empty && empty.isEmpty()?List.of():
                    list(values).stream().map(v->{var pair=list(v);return row("variable",pair.getFirst(),"values",pair.getLast());})
                            .sorted(Comparator.comparing(v->variableKey(obj(v.get("variable"))))).toList());
            return copy;
        }).toList();
        return row("layer",raw.get("layer"),"documents",raw.get("documents"),"declaredSources",sources,
                "acquisitionPolicyIdentity",raw.get("acquisitionPolicyIdentity"));
    }

    private static String variableKey(Map<String,Object> variable) {
        String kind=switch(text(variable,"kind")){case "PROPERTY"->"0";case "PROFILE"->"1";case "WEB_MODE"->"2";default->throw new IllegalArgumentException("Invalid variable kind");};
        return kind+text(variable,"name");
    }

    static String identity(String kind,Map<String,Object> fields) {
        var payload=new TreeMap<>(fields);payload.put("schema","m4-r0-identity-v1");
        return kind+":"+CanonicalJson.sha256(row("components",List.of(CanonicalJson.write(payload)),"kind",kind,"version",1)).value();
    }

    static Map<String,Object> fields(Map<String,Object> source,String... fields) {
        var result=new TreeMap<String,Object>();
        for(String key:fields) {
            if(!source.containsKey(key))throw new IllegalArgumentException("Missing Spring field: "+key);
            result.put(key,source.get(key));
        }
        return result;
    }
}
