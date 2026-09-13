package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** An explicitly supplied finite partition, not evidence of full deployment coverage or feasibility. */
public record FiniteDomain(Variable variable, List<Value> values, ConditionEvidence evidence) {
    public enum Kind { PROPERTY, PROFILE, WEB_MODE }
    public enum WebMode { NONE, SERVLET, REACTIVE }
    public enum ValueKind { EXACT, MISSING, BOOLEAN, WEB_MODE, OTHER }

    public record Variable(Kind kind, String name) implements Comparable<Variable> {
        public Variable { Objects.requireNonNull(kind); name = ConditionIdentitySupport.name(name); }
        @Override public int compareTo(Variable other) {
            int order = kind.compareTo(other.kind); return order == 0 ? name.compareTo(other.name) : order;
        }
    }
    /** OTHER is retained as a proposed abstraction and always qualified until a verifier is implemented. */
    public record Value(ValueKind kind, Optional<String> exact, Optional<Boolean> bool,
                        Optional<WebMode> webMode, Optional<ContentDigest> uniformityEvidence) {
        public Value {
            Objects.requireNonNull(kind); Objects.requireNonNull(exact); Objects.requireNonNull(bool);
            Objects.requireNonNull(webMode); Objects.requireNonNull(uniformityEvidence);
            exact.ifPresent(ConditionIdentitySupport::raw);
            if (exact.isPresent() != (kind == ValueKind.EXACT) || bool.isPresent() != (kind == ValueKind.BOOLEAN)
                    || webMode.isPresent() != (kind == ValueKind.WEB_MODE)
                    || uniformityEvidence.isPresent() && kind != ValueKind.OTHER) {
                throw new IllegalArgumentException("Domain value payload does not match its tag");
            }
        }
        public static Value exact(String value) { return new Value(ValueKind.EXACT, Optional.of(value), Optional.empty(), Optional.empty(), Optional.empty()); }
        public static Value missing() { return new Value(ValueKind.MISSING, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()); }
        public static Value bool(boolean value) { return new Value(ValueKind.BOOLEAN, Optional.empty(), Optional.of(value), Optional.empty(), Optional.empty()); }
        public static Value web(WebMode value) { return new Value(ValueKind.WEB_MODE, Optional.empty(), Optional.empty(), Optional.of(value), Optional.empty()); }
        public static Value other(Optional<ContentDigest> proof) { return new Value(ValueKind.OTHER, Optional.empty(), Optional.empty(), Optional.empty(), proof); }
    }
    public FiniteDomain {
        Objects.requireNonNull(variable); Objects.requireNonNull(evidence);
        values = ContractChecks.sortedDistinct(values, Comparator.comparing(CanonicalJson::write), "domain values");
        for (Value value : values) {
            boolean compatible = switch (variable.kind()) {
                case PROPERTY -> value.kind() == ValueKind.EXACT || value.kind() == ValueKind.MISSING || value.kind() == ValueKind.OTHER;
                case PROFILE -> value.kind() == ValueKind.BOOLEAN;
                case WEB_MODE -> value.kind() == ValueKind.WEB_MODE;
            };
            if (!compatible) throw new IllegalArgumentException("Domain value has the wrong variable type");
        }
        // An empty domain is a semantic INVALID_MODEL outcome, retained by ConfigurationSpace.
    }
}
