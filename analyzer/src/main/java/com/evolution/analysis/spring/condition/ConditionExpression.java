package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import java.util.*;

/** Immutable, acyclic, typed IR. Content identity is distinct from each source occurrence.
 * No algebraic rewrites, state evaluation, opaque branching or solver implementation occur here. */
public final class ConditionExpression {
    public static final VersionedIdentifier IR = new VersionedIdentifier("spring.condition-ir", "m4b.1-v1");
    public enum Operator { CONSTANT, ALL, ANY, NOT, PROFILE, PROPERTY, WEB_MODE, BUILD, BEAN_STATE, OPAQUE }
    public enum Dependency { BUILD_CONTEXT, CONFIGURATION, BEAN_STATE, OPAQUE }
    public enum BuildPredicate { CLASS_PRESENT, RESOURCE_PRESENT, JAVA_VERSION, FRAMEWORK_VERSION }
    public enum BeanPredicate { PRESENT, MISSING, SINGLE_CANDIDATE }
    public enum Search { CURRENT, ANCESTORS, ALL }
    public enum OpaqueReason { CUSTOM_CODE, UNRESOLVED_EXPRESSION, UNSUPPORTED_PREDICATE, VERSION_NOT_VALIDATED }

    public record Semantics(VersionedIdentifier version, ContentDigest frameworkEvidence) {
        public Semantics { Objects.requireNonNull(version); Objects.requireNonNull(frameworkEvidence); }
    }
    public sealed interface Operand permits Constant, Profile, Property, Web, Build, Bean, Opaque, None {}
    public record Constant(LogicalValue value) implements Operand { public Constant { Objects.requireNonNull(value); } }
    public record Profile(String name) implements Operand { public Profile { name = ConditionIdentitySupport.name(name); } }
    /** Exact prefix/names and Spring condition options, not a relaxed binder or property evaluator. */
    public record Property(String prefix, List<String> names, String havingValue, boolean matchIfMissing)
            implements Operand {
        public Property {
            prefix = ConditionIdentitySupport.raw(prefix); havingValue = ConditionIdentitySupport.raw(havingValue);
            names = ContractChecks.sortedDistinct(names.stream().map(ConditionIdentitySupport::name).toList(),
                    Comparator.naturalOrder(), "property names");
            if (names.isEmpty()) throw new IllegalArgumentException("Property condition needs names");
        }
        public List<String> keys() {
            String normalizedPrefix = prefix.isEmpty() || prefix.endsWith(".") ? prefix : prefix + ".";
            return names.stream().map(name -> normalizedPrefix + name).toList();
        }
    }
    public record Web(FiniteDomain.WebMode mode) implements Operand { public Web { Objects.requireNonNull(mode); } }
    /** A supplied observation bound to an exact build; UNKNOWN also retains a typed gap. */
    public record Build(BuildPredicate predicate, String subject, SpringBuildContext.Identity buildContext,
                        LogicalValue observedValue, ConditionEvidence evidence) implements Operand {
        public Build {
            Objects.requireNonNull(predicate); subject = ConditionIdentitySupport.name(subject);
            Objects.requireNonNull(buildContext); Objects.requireNonNull(observedValue); Objects.requireNonNull(evidence);
        }
    }
    /** Query against state at a later registration event; deliberately contains no final-state identity. */
    public record Bean(BeanPredicate predicate, String containerKey, List<String> types,
                       List<String> names, List<String> annotations, Search search) implements Operand {
        public Bean {
            Objects.requireNonNull(predicate); containerKey = ConditionIdentitySupport.name(containerKey);
            types = strings(types); names = strings(names); annotations = strings(annotations);
            Objects.requireNonNull(search);
            if (types.isEmpty() && names.isEmpty() && annotations.isEmpty()) {
                throw new IllegalArgumentException("Bean-state query needs an explicit selector");
            }
        }
    }
    /** Correlation key identifies the opaque computation's exact inputs, not a fresh Boolean variable per use. */
    public record Opaque(ContentDigest correlationKey, OpaqueReason reason) implements Operand {
        public Opaque { Objects.requireNonNull(correlationKey); Objects.requireNonNull(reason); }
    }
    public record None() implements Operand {}
    public record Identity(String value) implements CanonicalIdentifier, Comparable<Identity> {
        public Identity { value = ConditionIdentitySupport.require(value, "spring-condition-expression"); }
        @Override public int compareTo(Identity other) { return value.compareTo(other.value); }
    }

    private final Identity identity;
    private final Semantics semantics;
    private final Operator operator;
    private final Operand operand;
    private final List<ConditionExpression> children;
    private final Set<Dependency> dependencies;
    private final int depth;

    private ConditionExpression(Semantics semantics, Operator operator, Operand operand,
                                List<ConditionExpression> children) {
        this.semantics = Objects.requireNonNull(semantics);
        this.operator = Objects.requireNonNull(operator); this.operand = Objects.requireNonNull(operand);
        List<ConditionExpression> copy = List.copyOf(children);
        if (copy.stream().anyMatch(child -> !child.semantics.equals(semantics))) {
            throw new IllegalArgumentException("An expression cannot mix framework semantics/evidence");
        }
        EnumSet<Dependency> dependencies = EnumSet.noneOf(Dependency.class);
        switch (operator) {
            case BUILD -> dependencies.add(Dependency.BUILD_CONTEXT);
            case PROFILE, PROPERTY, WEB_MODE -> dependencies.add(Dependency.CONFIGURATION);
            case BEAN_STATE -> dependencies.add(Dependency.BEAN_STATE);
            case OPAQUE -> dependencies.add(Dependency.OPAQUE);
            default -> { }
        }
        copy.forEach(child -> dependencies.addAll(child.dependencies));
        this.dependencies = Set.copyOf(dependencies);
        // Even an opaque grandchild prevents reordering. Stateful invocation order is evidence.
        if ((operator == Operator.ALL || operator == Operator.ANY)
                && !dependencies.contains(Dependency.OPAQUE) && !dependencies.contains(Dependency.BEAN_STATE)) {
            copy = ContractChecks.sortedDistinct(copy, Comparator.comparing(ConditionExpression::identity), "pure operands");
        }
        this.children = copy;
        this.depth = Math.addExact(1, copy.stream().mapToInt(ConditionExpression::depth).max().orElse(0));
        this.identity = new Identity(ConditionIdentitySupport.derive("spring-condition-expression", Map.of(
                "irVersion", IR, "frameworkSemantics", semantics, "operator", operator,
                "typedOperands", operand, "childExpressionIdentities", copy.stream().map(ConditionExpression::identity).toList())));
    }

    public static ConditionExpression atom(Semantics semantics, Operand operand) {
        return new ConditionExpression(semantics, atomOperator(operand), operand, List.of());
    }
    private static Operator atomOperator(Operand operand) {
        return switch (operand) {
            case Constant ignored -> Operator.CONSTANT; case Profile ignored -> Operator.PROFILE;
            case Property ignored -> Operator.PROPERTY; case Web ignored -> Operator.WEB_MODE;
            case Build ignored -> Operator.BUILD; case Bean ignored -> Operator.BEAN_STATE;
            case Opaque ignored -> Operator.OPAQUE;
            case None ignored -> throw new IllegalArgumentException("None is not an atom");
        };
    }
    public static ConditionExpression all(Semantics semantics, List<ConditionExpression> children) {
        return new ConditionExpression(semantics, Operator.ALL, new None(), children);
    }
    public static ConditionExpression any(Semantics semantics, List<ConditionExpression> children) {
        return new ConditionExpression(semantics, Operator.ANY, new None(), children);
    }
    public static ConditionExpression not(ConditionExpression child) {
        return new ConditionExpression(child.semantics, Operator.NOT, new None(), List.of(child));
    }
    private static List<String> strings(List<String> values) {
        return ContractChecks.sortedDistinct(values.stream().map(ConditionIdentitySupport::name).toList(),
                Comparator.naturalOrder(), "selector names");
    }
    public Identity identity() { return identity; }
    public Semantics semantics() { return semantics; }
    public Operator operator() { return operator; }
    public Operand operand() { return operand; }
    public List<ConditionExpression> children() { return children; }
    public Set<Dependency> dependencies() { return dependencies; }
    public int depth() { return depth; }
    /** Flat node export uses child identities, so deep DAGs never recursively serialize. */
    public Object canonicalForm() {
        return Map.of("identity", identity, "irVersion", IR, "frameworkSemantics", semantics,
                "operator", operator, "typedOperands", operand,
                "childExpressionIdentities", children.stream().map(ConditionExpression::identity).toList());
    }
    public View view() {
        return new View(identity, IR, semantics, operator, operand, children.stream().map(ConditionExpression::identity).toList());
    }
    /** Typed immutable export. Child identities preserve a bounded partial graph without recursive serialization. */
    public record View(Identity identity, VersionedIdentifier irVersion, Semantics frameworkSemantics,
                       Operator operator, Operand typedOperands, List<Identity> childExpressionIdentities) {
        public View {
            Objects.requireNonNull(identity); Objects.requireNonNull(frameworkSemantics);
            Objects.requireNonNull(operator); Objects.requireNonNull(typedOperands);
            childExpressionIdentities = List.copyOf(childExpressionIdentities);
            if (!IR.equals(irVersion)) throw new IllegalArgumentException("Unsupported condition IR version");
            if (operator == Operator.ALL || operator == Operator.ANY || operator == Operator.NOT) {
                if (!(typedOperands instanceof None) || operator == Operator.NOT && childExpressionIdentities.size() != 1) {
                    throw new IllegalArgumentException("Boolean node has an invalid operand or arity");
                }
            } else if (operator != atomOperator(typedOperands) || !childExpressionIdentities.isEmpty()) {
                throw new IllegalArgumentException("Atom has an invalid operand or children");
            }
            Identity expected = new Identity(ConditionIdentitySupport.derive("spring-condition-expression", Map.of(
                    "irVersion", irVersion, "frameworkSemantics", frameworkSemantics, "operator", operator,
                    "typedOperands", typedOperands, "childExpressionIdentities", childExpressionIdentities)));
            if (!identity.equals(expected)) throw new IllegalArgumentException("Expression view identity does not match contents");
        }
    }
}
