package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.spring.condition.FiniteDomain.*;
import com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;

/** Proves exact coordinate semantics; domain value sets and constraint outcomes may differ. */
final class SpringDomain {
    record Coordinate(Variable variable, String layer, String key) {}
    record Choice(Coordinate coordinate, Value value) {}
    record Assignment(String key, Map<Coordinate,Value> values) {}
    private static final String BASELINE="BASELINE";

    final String identity;
    final List<ContentDigest> evidence;
    final Object interpretation;
    final Map<Coordinate,Set<Value>> domains;
    final Map<String,Choice> atoms;
    final Map<String,Map<String,Object>> sources;
    final boolean supported;
    private final boolean onlyProfileInputs;

    SpringDomain(EvolutionInput input, GraphEvidence space) throws IOException {
        var raw=EvolutionInput.payload(space);
        var canonical=SpringOperands.space(raw);
        identity=SpringOperands.identity("spring-configuration-space",canonical);
        var refs=new ArrayList<ContentDigest>();refs.add(space.identity());
        var envelopes=new ArrayList<Map<String,Object>>();
        var bySource=new HashMap<String,Map<String,Object>>();
        boolean known=true;
        for(String key:List.of("repositoryEnvelopeIdentity","deploymentEnvelopeIdentity")) {
            String id=maybe(canonical,key);
            if(id==null)continue;
            var ev=SpringOperands.lookup(input,"spring.configuration-envelope",id);
            if(ev==null)throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.UNAVAILABLE);
            refs.add(ev.identity());
            var envelope=SpringOperands.envelope(EvolutionInput.payload(ev));
            String layer=text(envelope,"layer");
            var declarations=new ArrayList<Map<String,Object>>();
            for(Object member:list(envelope.get("declaredSources"))) {
                var source=obj(member);
                String digest=CanonicalJson.sha256(source).value();
                if(bySource.put(layer+"|"+digest,source)!=null)throw new IllegalArgumentException("Duplicate configuration source");
                known&=available(source);
                // Availability, guarded activation, ordered imports and source value semantics are material.
                declarations.add(SpringOperands.fields(source,"key","kind","activation","importAncestry","availability","values","conversionPolicy"));
            }
            var documents=list(envelope.get("documents")).stream().map(EvolutionRows::obj).toList();
            known&=documents.stream().allMatch(SpringDomain::available);
            // Document evidence carries the original content and origin. Never equate unrelated documents.
            envelopes.add(row("layer",layer,"policy",envelope.get("acquisitionPolicyIdentity"),
                    "documents",documents,"sources",declarations));
        }
        var precedence=obj(raw.get("precedencePolicy"));
        var ordered=new ArrayList<Object>();
        var choices=new HashMap<Coordinate,Set<Value>>();
        var symbols=new TreeMap<String,Choice>();
        for(var item:list(raw.get("domains"))) {
            var domain=obj(item);
            var coordinate=new Coordinate(typedVariable(obj(domain.get("variable"))),BASELINE,"");
            add(choices,symbols,coordinate,list(domain.get("values")),null);
        }
        var visited=new HashSet<String>();
        for(var item:list(precedence.get("lowToHigh"))) {
            var ref=obj(item);String layer=text(ref,"layer"), digest=text(ref,"sourceIdentity");
            String lookup=layer+"|"+digest;
            var source=bySource.get(lookup);
            if(source==null||!visited.add(lookup))throw new IllegalArgumentException("Invalid source precedence");
            String key=text(source,"key");
            ordered.add(List.of(layer,key));
            // An optional missing source contributes no symbolic selections (but explicit output can
            // still represent a source choice); leave that cross-encoding case qualified.
            if(!"AVAILABLE".equals(source.get("availability")))known=false;
            for(var field:list(source.get("values"))) {
                var domain=obj(field);
                add(choices,symbols,new Coordinate(typedVariable(obj(domain.get("variable"))),layer,key),list(domain.get("values")),ref);
            }
        }
        var profile=obj(raw.get("profilePolicy"));var feasibility=obj(raw.get("feasibilityPolicy"));
        interpretation=row("envelopes",envelopes,"precedence",ordered,"precedenceVersion",precedence.get("version"),
                "profile",SpringOperands.fields(profile,"version","defaultProfiles","groups","includes"),
                "abstraction",raw.get("abstractionVersion"),"feasibility",SpringOperands.fields(feasibility,"version","limits"));
        supported=known&&visited.size()==bySource.size()&&choices.values().stream().flatMap(Set::stream).noneMatch(v->v.kind()==ValueKind.OTHER);
        onlyProfileInputs=bySource.isEmpty() && envelopes.size()==1 && "REPOSITORY".equals(envelopes.getFirst().get("layer"))
                && list(envelopes.getFirst().get("documents")).isEmpty() && list(raw.get("constraints")).isEmpty()
                && list(profile.get("defaultProfiles")).isEmpty() && list(profile.get("includes")).isEmpty()
                && obj(profile.get("groups")).isEmpty();
        evidence=List.copyOf(refs);domains=Map.copyOf(choices);atoms=Map.copyOf(symbols);sources=Map.copyOf(bySource);
    }

    boolean isClosedBooleanProfile(String name) {
        var profile=new Coordinate(new Variable(Kind.PROFILE,name),BASELINE,"");
        return supported && onlyProfileInputs && domains.equals(Map.of(profile,Set.of(Value.bool(false),Value.bool(true))));
    }

    boolean alignable(SpringDomain other) {
        return supported&&other.supported&&interpretation.equals(other.interpretation)&&domains.keySet().equals(other.domains.keySet());
    }

    Assignment assignment(Map<String,Object> raw) {
        if(!"spring-configuration-assignment-v1".equals(raw.get("schema")))throw new IllegalArgumentException("Unsupported assignment");
        var values=new HashMap<Coordinate,Value>();
        for(var item:list(raw.get("baseline"))) {
            var entry=obj(item);
            put(values,new Coordinate(typedVariable(obj(entry.get("variable"))),BASELINE,""),value(obj(entry.get("value"))));
        }
        for(var item:list(raw.get("sourceChoices"))) {
            var entry=obj(item);var ref=obj(entry.get("source"));String layer=text(ref,"layer");
            var source=sources.get(layer+"|"+text(ref,"sourceIdentity"));
            if(source==null)throw new IllegalArgumentException("Foreign source assignment");
            put(values,new Coordinate(typedVariable(obj(entry.get("variable"))),layer,text(source,"key")),value(obj(entry.get("value"))));
        }
        domains.forEach((coordinate,allowed)->{
            if(!BASELINE.equals(coordinate.layer())&&allowed.size()==1)values.putIfAbsent(coordinate,allowed.iterator().next());
        });
        return typed(values);
    }

    private void put(Map<Coordinate,Value> values,Coordinate coordinate,Value value) {
        if(!domains.getOrDefault(coordinate,Set.of()).contains(value)||values.put(coordinate,value)!=null)
            throw new IllegalArgumentException("Assignment is outside the declared domain");
    }

    // Every producer atom is mapped through its typed preimage, including uncommon values.
    Formula normalize(String name) {
        var choice=atoms.get(name);
        if(choice==null)throw new IllegalArgumentException("Unmapped Boolean atom");
        return atom(choice);
    }
    static Formula atom(Choice choice){return variable(hash("evolution.spring.choice:v1",choice).value());}

    Map<String,Boolean> localChoices(Map<String,Boolean> normalized) {
        var values=new TreeMap<String,Boolean>();
        atoms.forEach((name,choice)->values.put(name,Boolean.TRUE.equals(normalized.get(atom(choice).variable()))));
        return values;
    }

    Formula legal(SpringDomain opposite,com.evolution.analysis.spring.truth.SymbolicConfiguration.Limits limits) throws IOException {
        var groups=new ArrayList<Formula>();
        long cells=0;
        for(var entry:domains.entrySet()) {
            var coordinate=entry.getKey();var local=entry.getValue();
            var available=local.stream().map(value->atom(new Choice(coordinate,value))).sorted(Comparator.comparing(Formula::identity)).toList();
            cells+=(long)available.size()*(available.size()+1)/2;
            if(cells>limits.maxNodes())throw new EvolutionSnapshot.ReadFailure(EvolutionSnapshot.Failure.RESOURCE_LIMIT);
            groups.add(or(available));
            for(int i=0;i<available.size();i++)for(int j=i+1;j<available.size();j++)
                groups.add(not(and(available.get(i),available.get(j))));
            for(Value value:opposite.domains.get(coordinate))if(!local.contains(value))
                groups.add(not(atom(new Choice(coordinate,value))));
        }
        return and(groups);
    }

    Formula cube(Assignment assignment) {
        return and(assignment.values().entrySet().stream().map(e->atom(new Choice(e.getKey(),e.getValue()))).toList());
    }

    Assignment replay(Map<String,Boolean> local) {
        var values=new HashMap<Coordinate,Value>();
        for(var choice:local.entrySet()) {
            var atom=atoms.get(choice.getKey());
            if(atom==null)throw new IllegalArgumentException("Foreign Boolean choice");
            if(choice.getValue())put(values,atom.coordinate(),atom.value());
        }
        return typed(values);
    }

    private Assignment typed(Map<Coordinate,Value> values) {
        if(!values.keySet().equals(domains.keySet()))throw new IllegalArgumentException("Incomplete typed assignment");
        return new Assignment(hash("evolution.spring.assignment:v1",values.entrySet().stream()
                .map(e->List.of(e.getKey(),e.getValue())).sorted(Comparator.comparing(CanonicalJson::write)).toList()).value(),Map.copyOf(values));
    }

    private static void add(Map<Coordinate,Set<Value>> domains,Map<String,Choice> atoms,Coordinate coordinate,
                            List<Object> raw,Map<String,Object> source) {
        var values=new HashSet<Value>();
        for(Object item:raw) {
            var value=value(obj(item));
            boolean compatible=switch(coordinate.variable().kind()) {
                case PROPERTY -> Set.of(ValueKind.EXACT,ValueKind.MISSING,ValueKind.OTHER).contains(value.kind());
                case PROFILE -> value.kind()==ValueKind.BOOLEAN;
                case WEB_MODE -> value.kind()==ValueKind.WEB_MODE;
            };
            if(!compatible||!values.add(value))throw new IllegalArgumentException("Invalid typed domain value");
            String id=CanonicalJson.sha256(Arrays.asList(coordinate.variable(),value,source)).value();
            if(atoms.put(id,new Choice(coordinate,value))!=null)throw new IllegalArgumentException("Duplicate Boolean atom");
        }
        if(domains.put(coordinate,Set.copyOf(values))!=null)throw new IllegalArgumentException("Duplicate typed coordinate");
    }

    private static boolean available(Map<String,Object> record) {
        return "AVAILABLE".equals(record.get("availability"))||"MISSING_OPTIONAL".equals(record.get("availability"));
    }
    private static Variable typedVariable(Map<String,Object> raw) {return new Variable(Kind.valueOf(text(raw,"kind")),text(raw,"name"));}
    private static Value value(Map<String,Object> raw) {
        return new Value(ValueKind.valueOf(text(raw,"kind")),Optional.ofNullable(maybe(raw,"exact")),
                Optional.ofNullable((Boolean)raw.get("bool")),Optional.ofNullable(maybe(raw,"webMode")).map(WebMode::valueOf),
                Optional.ofNullable(maybe(raw,"uniformityEvidence")).map(ContentDigest::new));
    }
}
