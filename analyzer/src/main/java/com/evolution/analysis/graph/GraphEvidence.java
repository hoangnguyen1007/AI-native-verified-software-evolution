package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.SourceInput;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.time.Instant;
import java.util.*;

/** Lossless canonical provider payload; its digest is distinct from provider semantic identity. */
public record GraphEvidence(ContentDigest identity, String family, String canonicalJson) {
    public GraphEvidence {
        Objects.requireNonNull(identity); family=ContractChecks.token(family,"graph evidence family");
        Objects.requireNonNull(canonicalJson);
        if (!canonicalJson.equals(CanonicalJson.write(GraphJson.read(canonicalJson, GraphJson.Limits.defaults()))))
            throw new IllegalArgumentException("Noncanonical graph evidence");
        if(!identity.equals(digest(family,canonicalJson))) throw new IllegalArgumentException("Graph evidence hash mismatch");
    }
    public static GraphEvidence capture(String family, Object payload) {
        String json=CanonicalJson.write(form(payload,0));
        return new GraphEvidence(digest(family,json),family,json);
    }
    /** Semantic replay excludes only known provenance timestamps/revision labels, never configuration values. */
    public ContentDigest semanticIdentity() {
        Object form=GraphJson.read(canonicalJson,GraphJson.Limits.defaults());
        if(Set.of("repository.snapshot","analysis.manifest","source.request","repository.acquisition","evidence.attempt","evidence.ledger").contains(family)) {
            var copy=new TreeMap<>(GraphJson.object(form));
            switch(family) {
                case "repository.snapshot" -> removeSnapshotLabels(copy);
                case "analysis.manifest","repository.acquisition" -> snapshotMember(copy);
                case "source.request" -> {
                    var manifest=new TreeMap<>(GraphJson.object(copy.get("manifest")));snapshotMember(manifest);copy.put("manifest",manifest);
                }
                case "evidence.attempt" -> removeTiming(copy);
                case "evidence.ledger" -> copy.put("attempts",GraphJson.array(copy.get("attempts")).stream().map(a->{var row=new TreeMap<>(GraphJson.object(a));removeTiming(row);return row;}).toList());
                default -> throw new IllegalStateException("Unknown semantic evidence family");
            }
            form=copy;
        }
        return digest(family,CanonicalJson.write(form));
    }
    private static void removeSnapshotLabels(Map<String,Object> snapshot) {snapshot.remove("revision");snapshot.remove("dirty");}
    private static void removeTiming(Map<String,Object> attempt) {attempt.remove("startedAt");attempt.remove("completedAt");}
    private static void snapshotMember(Map<String,Object> value) {
        if(value.get("snapshot")!=null) {var snapshot=new TreeMap<>(GraphJson.object(value.get("snapshot")));removeSnapshotLabels(snapshot);value.put("snapshot",snapshot);}
    }
    private static ContentDigest digest(String family,String json) {
        return ContentDigest.sha256Utf8(CanonicalJson.write(List.of("graph.evidence:m5.1-v1",family,json)));
    }
    /** Records/canonical forms only; no arbitrary fields or polymorphic deserialization. */
    static Object form(Object value,int depth) {
        if(depth>128) throw new IllegalArgumentException("Provider evidence depth limit");
        if(value==null || value instanceof String || value instanceof Number || value instanceof Boolean
                || value instanceof Enum<?> || value instanceof CanonicalIdentifier || value instanceof Instant || value instanceof URI)
            return value;
        if(value instanceof Optional<?> optional) return optional.isPresent()?form(optional.orElseThrow(),depth+1):null;
        if(value instanceof byte[] bytes) return Map.of("encoding","base64","contentDigest",ContentDigest.sha256(bytes),"bytes",Base64.getEncoder().encodeToString(bytes));
        if(value instanceof com.evolution.analysis.acquisition.AcquiredFile file) return Map.of("path",file.path(),"contentDigest",file.contentDigest(),"bytesBase64",Base64.getEncoder().encodeToString(file.bytes()));
        if(value instanceof SourceInput source) return Map.of("document",source.document(),"decoding",source.decoding(),
                "bytesBase64",Base64.getEncoder().encodeToString(source.bytes()));
        if(value instanceof com.evolution.analysis.spring.condition.ConditionExpression expression) {
            var nodes=new TreeMap<String,Object>();var pending=new ArrayDeque<com.evolution.analysis.spring.condition.ConditionExpression>();pending.add(expression);
            while(!pending.isEmpty()){var node=pending.removeFirst();if(nodes.putIfAbsent(node.identity().value(),node.view())==null)pending.addAll(node.children());}
            return form(Map.of("rootIdentity",expression.identity(),"expressions",List.copyOf(nodes.values())),depth+1);
        }
        if(value instanceof com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula formula) {
            var nodes=new TreeMap<String,Object>();var pending=new ArrayDeque<com.evolution.analysis.spring.truth.SymbolicConfiguration.Formula>();pending.add(formula);
            while(!pending.isEmpty()){var node=pending.removeFirst();if(nodes.putIfAbsent(node.identity().value(),node.view())==null)pending.addAll(node.children());}
            return form(Map.of("rootIdentity",formula.identity(),"formulas",List.copyOf(nodes.values())),depth+1);
        }
        if(value instanceof Map<?,?> map) {
            if(map.keySet().stream().allMatch(String.class::isInstance)) {
                var result=new TreeMap<String,Object>();map.forEach((k,v)->result.put((String)k,form(v,depth+1)));return result;
            }
            return map.entrySet().stream().map(e->Arrays.asList(form(e.getKey(),depth+1),form(e.getValue(),depth+1)))
                    .sorted(Comparator.comparing(CanonicalJson::write)).toList();
        }
        if(value instanceof Collection<?> collection) {
            var result=collection.stream().map(v->form(v,depth+1)).toList();
            return value instanceof Set<?> ? result.stream().sorted(Comparator.comparing(CanonicalJson::write)).toList():result;
        }
        if(value.getClass().isRecord()) {
            var result=new TreeMap<String,Object>();
            try {for(var component:value.getClass().getRecordComponents())
                result.put(component.getName(),form(component.getAccessor().invoke(value),depth+1));}
            catch(IllegalAccessException|InvocationTargetException failure) {throw new IllegalArgumentException("Cannot encode provider record",failure);}
            return result;
        }
        try {
            var method=value.getClass().getMethod("canonicalForm");
            if(method.getParameterCount()==0) return form(method.invoke(value),depth+1);
        } catch(NoSuchMethodException absent) {
            // Public records below are the other supported provider shape.
        } catch(ReflectiveOperationException failed) {
            throw new IllegalArgumentException("Cannot encode provider canonical form",failed);
        }
        throw new IllegalArgumentException("Provider has no supported neutral evidence form: "+value.getClass().getSimpleName());
    }
}
