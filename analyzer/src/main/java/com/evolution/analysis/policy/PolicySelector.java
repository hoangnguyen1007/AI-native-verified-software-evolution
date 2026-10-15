package com.evolution.analysis.policy;

import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery.EntityView;
import java.util.*;
import static com.evolution.analysis.policy.ArchitecturePolicy.*;

/** Literal selector semantics; missing metadata stays UNKNOWN, never a guessed FQN/package. */
public final class PolicySelector {
    public enum Match {
        YES, NO, UNKNOWN;
        public Match and(Match other) { return this==NO||other==NO?NO:this==UNKNOWN||other==UNKNOWN?UNKNOWN:YES; }
        public Match not() { return this==YES?NO:this==NO?YES:UNKNOWN; }
    }
    public static Match match(Selector selector, EntityView entity) {
        var pkg=selector.packages().isEmpty()||selector.packages().contains("*")?Optional.<String>empty():packageName(entity);
        // M5 aggregate display metadata is not a proof of every constituent's source classification.
        var sourceSet=Set.of("PACKAGE","MODULE").contains(entity.kind())?Optional.<String>empty():entity.sourceSet();
        return dimension(selector.packages(), pkg).and(dimension(selector.names(),
                entity.qualifiedName().filter(v->!v.startsWith("java:v1:"))))
                .and(dimension(selector.modules(),entity.module())).and(dimension(selector.origins(),entity.origin()))
                .and(dimension(selector.sourceSets(),sourceSet)).and(dimension(selector.identities(),Optional.of(entity.identity())));
    }
    private static Match dimension(Set<String> patterns, Optional<String> value) {
        if (patterns.isEmpty() || patterns.contains("*")) return Match.YES;
        if (value.isEmpty()) return Match.UNKNOWN;
        return patterns.stream().anyMatch(p->literal(p,value.orElseThrow()))?Match.YES:Match.NO;
    }
    private static boolean literal(String pattern,String value) {
        if (!pattern.endsWith(".**")) return pattern.equals(value);
        String prefix=pattern.substring(0,pattern.length()-3);return value.equals(prefix)||value.startsWith(prefix+".");
    }
    static Optional<String> packageName(EntityView entity) {
        if (entity.canonicalName().isEmpty() || !entity.canonicalName().orElseThrow().startsWith("java:v1:")) return Optional.empty();
        try {
            var tuple=GraphJson.array(GraphJson.read(entity.canonicalName().orElseThrow().substring(8),new GraphJson.Limits(100_000,64,10_000)));
            for(int depth=0;depth<64;depth++) {
                if(tuple.isEmpty())return Optional.empty();String kind=GraphJson.string(tuple.getFirst());
                if(kind.equals("type")||kind.equals("package")) {
                    if(tuple.size()!=(kind.equals("type")?3:2))return Optional.empty();
                    var parts=GraphJson.array(tuple.get(1));var names=new ArrayList<String>();
                    for(Object part:parts)names.add(decode(GraphJson.string(part)));
                    String name=String.join(".",names);
                    // Round-trip the neutral key: malformed encodings cannot become a selector match.
                    String canonical=JavaSymbolName.packageName(name).canonicalName();
                    var checked=GraphJson.array(GraphJson.read(canonical.substring(8),new GraphJson.Limits(100_000,64,10_000)));
                    if(kind.equals("type")) {
                        var type=JavaSymbolName.topLevelType(name,decode(GraphJson.string(tuple.get(2))));
                        var expected=GraphJson.array(GraphJson.read(type.canonicalName().substring(8),new GraphJson.Limits(100_000,64,10_000)));
                        if(!expected.equals(tuple))return Optional.empty();
                    }
                    return checked.get(1).equals(parts)?Optional.of(name):Optional.empty();
                }
                if(!Set.of("member-type","local-type","method","constructor","field","record-component").contains(kind)||tuple.size()<2)return Optional.empty();
                tuple=GraphJson.array(tuple.get(1));
            }
        } catch (IllegalArgumentException invalid) { return Optional.empty(); }
        return Optional.empty();
    }
    private static String decode(String encoded) {
        var result=new StringBuilder();for(int i=0;i<encoded.length();i++) {
            if(encoded.charAt(i)=='%') {if(i+6>=encoded.length())throw new IllegalArgumentException("Invalid Java name escape");
                result.appendCodePoint(Integer.parseInt(encoded.substring(i+1,i+7),16));i+=6;}
            else result.append(encoded.charAt(i));
        }return result.toString();
    }
    private PolicySelector() {}
}
