package com.evolution.analysis.policy;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import java.util.*;

/** Parser-neutral architecture rule AST. */
public record ArchitecturePolicy(String id, String version, List<Rule> rules) {
    public static final String SCHEMA = "architecture-policy:v1";
    public static final VersionedIdentifier VERSION = new VersionedIdentifier("architecture.policy", "m6.1-v1");
    public static final Set<String> DEPENDENCIES = Set.of("graph.depends-on", "spring.selected-binding", "spring.injection-candidate");
    public enum Severity { INFO, WARNING, ERROR }
    public enum Kind { FORBIDDEN_DEPENDENCY, BOUNDARY, LAYER_BOUNDARY, ACYCLIC }
    public ArchitecturePolicy {
        id = ContractChecks.namespacedId(id, "policy id"); version = ContractChecks.token(version, "policy version");
        rules = ContractChecks.sortedDistinct(rules, Comparator.comparing(r -> r.header().id()), "policy rules");
        if (rules.isEmpty() || rules.size() > 256) throw new IllegalArgumentException("Policy needs 1..256 rules");
    }
    public ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, this))); }
    public record Header(String id, String version, Severity severity, Scope scope, Set<String> relationships) {
        public Header {
            id = ContractChecks.namespacedId(id, "rule id"); version = ContractChecks.token(version, "rule version");
            Objects.requireNonNull(severity); Objects.requireNonNull(scope);
            if (scope == Scope.ENTITY) throw new IllegalArgumentException("Policy scope must be an architecture component");
            relationships = strings(relationships);
            if (relationships.isEmpty() || !DEPENDENCIES.containsAll(relationships)) throw new IllegalArgumentException("Unknown dependency kind");
            if (scope != Scope.SPRING_COMPONENT && relationships.stream().anyMatch(k -> k.startsWith("spring.")))
                throw new IllegalArgumentException("Spring dependency kinds require SPRING_COMPONENT scope");
            if (scope == Scope.SPRING_COMPONENT && relationships.contains("graph.depends-on"))
                throw new IllegalArgumentException("Java dependency kind requires a Java scope");
        }
    }
    /** Alternatives within a dimension are OR; nonempty dimensions compose with AND. No ambient defaults. */
    public record Selector(Set<String> packages, Set<String> names, Set<String> modules, Set<String> origins, Set<String> sourceSets, Set<String> identities) {
        public Selector {
            packages = patterns(packages,true); names = patterns(names,false); modules = strings(modules);
            modules.forEach(ModuleIdentity::new); origins = strings(origins); sourceSets = strings(sourceSets);
            identities = strings(identities);
            identities.forEach(v->{if(!v.matches("(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid canonical node identity");});
            if (!Set.of("PROJECT", "DEPENDENCY", "JDK", "SYNTHETIC").containsAll(origins)
                    || !Set.of(SourceClassification.values()).stream().map(Enum::name).toList().containsAll(sourceSets)) throw new IllegalArgumentException("Invalid population selector");
            if (packages.isEmpty() && names.isEmpty() && modules.isEmpty() && origins.isEmpty() && sourceSets.isEmpty() && identities.isEmpty())
                throw new IllegalArgumentException("Selector must declare at least one dimension; use names: ['*'] for all");
        }
    }
    public sealed interface Rule permits Forbidden, Boundary, Layers, Cycles {
        Header header();
        default Kind kind() { return switch (this) {
            case Forbidden ignored -> Kind.FORBIDDEN_DEPENDENCY; case Boundary ignored -> Kind.BOUNDARY;
            case Layers ignored -> Kind.LAYER_BOUNDARY; case Cycles ignored -> Kind.ACYCLIC;
        }; }
        default ContentDigest identity() { return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, kind(), this))); }
    }
    public record Forbidden(Header header, Selector from, Selector to) implements Rule {
        public Forbidden { Objects.requireNonNull(header); Objects.requireNonNull(from); Objects.requireNonNull(to); }
    }
    /** Outgoing dependencies from 'from' may target only 'allowed'. */
    public record Boundary(Header header, Selector from, Selector allowed) implements Rule {
        public Boundary { Objects.requireNonNull(header); Objects.requireNonNull(from); Objects.requireNonNull(allowed); }
    }
    public record Layer(String name, Selector select) {
        public Layer { name = ContractChecks.token(name, "layer name"); Objects.requireNonNull(select); }
    }
    public record Layers(Header header, List<Layer> layers, Map<String,Set<String>> allowed) implements Rule {
        public Layers {
            Objects.requireNonNull(header);
            layers = ContractChecks.sortedDistinct(layers, Comparator.comparing(Layer::name), "layers");
            if (layers.size() < 2 || layers.size() > 64) throw new IllegalArgumentException("Need 2..64 layers");
            var names = new TreeSet<String>(); layers.forEach(l -> names.add(l.name()));
            var copy = new TreeMap<String,Set<String>>(); allowed.forEach((k,v) -> copy.put(k, strings(v)));
            if (!names.containsAll(copy.keySet()) || copy.values().stream().anyMatch(v -> !names.containsAll(v)))
                throw new IllegalArgumentException("Unknown layer reference");
            allowed = Collections.unmodifiableMap(copy);
        }
    }
    public record Cycles(Header header, Selector within) implements Rule {
        public Cycles { Objects.requireNonNull(header); Objects.requireNonNull(within); }
    }
    private static Set<String> strings(Set<String> values) {
        if (values.size() > 128) throw new IllegalArgumentException("Selector alternatives limit");
        var copy = new TreeSet<String>(); values.forEach(v -> { Objects.requireNonNull(v);
            if (v.length() > 512 || v.indexOf('\0') >= 0 || !v.equals(v.strip())) throw new IllegalArgumentException("Invalid selector scalar");
            CanonicalJson.write(v); copy.add(v); });
        return Collections.unmodifiableSet(copy);
    }
    private static Set<String> patterns(Set<String> values, boolean allowDefaultPackage) {
        var copy = strings(values);
        for (var value : copy) {
            if(allowDefaultPackage && value.isEmpty())continue;
            String base = value.endsWith(".**") ? value.substring(0,value.length()-3) : value;
            if (!value.equals("*") && (base.isEmpty() || base.contains("*") || base.contains("..") || base.startsWith(".") || base.endsWith(".")))
                throw new IllegalArgumentException("Expected exact name, '*' or dotted prefix followed by '.**'");
        }
        return copy;
    }
}
