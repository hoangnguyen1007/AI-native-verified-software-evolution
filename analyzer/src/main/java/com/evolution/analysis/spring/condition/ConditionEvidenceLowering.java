package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.JavaSymbolName;
import com.evolution.analysis.spring.*;
import com.evolution.analysis.spring.SpringMechanismInventory.*;
import java.util.*;
import static com.evolution.analysis.spring.condition.ConditionProcessing.Reason.*;

/** Passive evidence-to-IR boundary. Every M4A obligation is classified in the output denominator. */
public final class ConditionEvidenceLowering {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.condition-lowering", "m4b.2");
    public record Limits(int maxLoweredRows, int maxMetadataCharacters, int maxProfileDepth) {
        public Limits { if (maxLoweredRows < 1 || maxMetadataCharacters < 1 || maxProfileDepth < 1) throw new IllegalArgumentException("Positive lowering limits required"); }
        public static Limits conservative() { return new Limits(10000, 16384, 256); }
    }
    /** The supplying provider evaluates the entire exact annotation query, including linkage/absence closure.
     * A classpath filename or a version label alone is not such an observation. */
    public record BuildObservation(ContentDigest rawObservationIdentity, ConditionExpression.BuildPredicate predicate,
                                   SpringBuildContext.Identity buildContext, LogicalValue value, ConditionEvidence evidence) {
        public BuildObservation {
            Objects.requireNonNull(rawObservationIdentity); Objects.requireNonNull(predicate); Objects.requireNonNull(buildContext);
            Objects.requireNonNull(value); Objects.requireNonNull(evidence);
        }
        public ContentDigest identity() { return ConditionIdentitySupport.digest(this); }
    }
    public enum Outcome { LOWERED, OPAQUE, NOT_A_CONDITION }
    public record Row(ContentDigest obligation, ContentDigest rawObservation, Outcome outcome,
                      Optional<ConditionOccurrence.View> occurrence) {
        public Row {
            Objects.requireNonNull(obligation); Objects.requireNonNull(rawObservation); Objects.requireNonNull(outcome); Objects.requireNonNull(occurrence);
            if (occurrence.isPresent() == (outcome == Outcome.NOT_A_CONDITION)) throw new IllegalArgumentException("Lowering row/occurrence mismatch");
        }
    }
    public record Coverage(int inputObligations, int loweredRows, int opaqueRows, int otherObligations) {
        public Coverage {
            if (inputObligations < 0 || loweredRows < 0 || opaqueRows < 0 || otherObligations < 0
                    || (long) loweredRows + opaqueRows + otherObligations != inputObligations) throw new IllegalArgumentException("Lowering denominator must be closed");
        }
    }
    public static final class Result {
        private final ContentDigest input, inventory;
        private final ConditionExpression.Semantics semantics;
        private final List<Row> rows;
        private final List<ConditionOccurrence> occurrences;
        private final List<ConditionProcessing.Issue> issues;
        private final List<CapabilityGapRecord> gaps;
        Result(ContentDigest input, SpringMechanismInventory inventory, SpringBuildContext build,
               List<Row> rows, List<ConditionOccurrence> occurrences, Collection<ConditionProcessing.Issue> issues) {
            this.input = input; this.inventory = inventory.identity(); semantics = ConditionEvidenceLowering.semantics(inventory.frameworkEvidence());
            this.rows = List.copyOf(rows); this.occurrences = List.copyOf(occurrences);
            this.issues = issues.stream().distinct().sorted(Comparator.comparing(ConditionProcessing.Issue::identity)).toList();
            gaps = ConditionProcessing.gaps(PROVIDER, input, build, this.issues);
        }
        public ContentDigest inputIdentity() { return input; }
        public ContentDigest inventoryIdentity() { return inventory; }
        public ConditionExpression.Semantics semantics() { return semantics; }
        public List<Row> rows() { return rows; }
        public List<ConditionOccurrence> occurrences() { return occurrences; }
        public List<ConditionProcessing.Issue> issues() { return issues; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public ContentDigest identity() { return ConditionIdentitySupport.digest(canonicalForm()); }
        public Coverage coverage() {
            return new Coverage(rows.size(), count(Outcome.LOWERED), count(Outcome.OPAQUE), count(Outcome.NOT_A_CONDITION));
        }
        private int count(Outcome outcome) { return (int) rows.stream().filter(r -> r.outcome() == outcome).count(); }
        public Object canonicalForm() {
            // Immutable original inventory and DAG roots remain available to the caller; flat expression table below.
            var expressions = new TreeMap<ConditionExpression.Identity, ConditionExpression.View>();
            for (var row : occurrences) {
                Deque<Iterator<ConditionExpression>> pending = new ArrayDeque<>(); pending.push(List.of(row.expression()).iterator());
                while (!pending.isEmpty()) {
                    if (!pending.peek().hasNext()) { pending.pop(); continue; }
                    var node = pending.peek().next();
                    if (expressions.putIfAbsent(node.identity(), node.view()) == null && !node.children().isEmpty()) pending.push(node.children().iterator());
                }
            }
            return Map.of("schema", "spring-condition-lowering-v1", "provider", PROVIDER, "inputIdentity", input,
                    "inventoryIdentity", inventory, "semantics", semantics, "rows", rows, "expressions", List.copyOf(expressions.values()),
                    "issues", issues, "capabilityGaps", gaps, "coverage", coverage());
        }
    }
    private ConditionEvidenceLowering() {}
    public static ConditionExpression.Semantics semantics(SpringFrameworkEvidence evidence) {
        return new ConditionExpression.Semantics(ExogenousConditionEvaluator.SEMANTICS, evidence.identity());
    }
    public static Result lower(SpringBuildContext build, SpringMechanismInventory inventory,
                               List<BuildObservation> observations, Limits limits) {
        Objects.requireNonNull(build); Objects.requireNonNull(inventory); Objects.requireNonNull(limits);
        observations = ContractChecks.sortedDistinct(observations, Comparator.comparing(BuildObservation::identity), "build condition observations");
        var input = ConditionIdentitySupport.digest(Map.of("provider", PROVIDER, "build", build.identity(),
                "inventory", inventory.identity(), "observations", observations, "limits", limits));
        var semantics = semantics(inventory.frameworkEvidence());
        boolean matchingBuild = inventory.analysis().equals(build.analysisIdentity()) && build.containsFrameworkEvidence(inventory.frameworkEvidence());
        Map<ContentDigest, RawObservation> raw = new HashMap<>(); inventory.rawObservations().forEach(r -> raw.put(r.identity(), r));
        Map<ContentDigest, List<BuildObservation>> builds = new HashMap<>();
        for (var observation : observations) builds.computeIfAbsent(observation.rawObservationIdentity(), ignored -> new ArrayList<>()).add(observation);
        List<Row> rows = new ArrayList<>(); List<ConditionOccurrence> occurrences = new ArrayList<>();
        List<ConditionProcessing.Issue> issues = new ArrayList<>();
        int attempted = 0;
        for (var obligation : inventory.obligations()) {
            boolean condition = obligation.primaryMechanism().startsWith("spring.condition.");
            boolean unclassified = obligation.classification() == Classification.UNCLASSIFIED;
            if (!condition && !unclassified) {
                rows.add(new Row(obligation.identity(), obligation.rawObservationIdentity(), Outcome.NOT_A_CONDITION, Optional.empty())); continue;
            }
            var observation = raw.get(obligation.rawObservationIdentity());
            ConditionEvidence evidence = build.sourceDigest(observation.document())
                    .<ConditionEvidence>map(digest -> new ConditionEvidence.Source(observation.document(), digest, observation.span(), observation.ordinal()))
                    .orElseGet(() -> new ConditionEvidence.Derived(List.of(inventory.identity(), observation.identity()), PROVIDER, "unavailable-source"));
            ConditionProcessing.Reason reason = null;
            ConditionExpression expression = null;
            String type = exactAnnotationType(observation, inventory.frameworkEvidence());
            if (!matchingBuild) reason = BUILD_CONTEXT_MISMATCH;
            else if (build.sourceDigest(observation.document()).isEmpty()) reason = SOURCE_EVIDENCE_MISMATCH;
            else if (observation.span().isEmpty()) reason = MISSING_SOURCE_SPAN;
            else if (unclassified) reason = POTENTIAL_CONDITION_UNCLASSIFIED;
            else if (++attempted > limits.maxLoweredRows() || observation.spelling().length() > limits.maxMetadataCharacters()) reason = LOWERING_LIMIT;
            else if (obligation.secondaryTags().contains("spring.annotation.composed")) reason = ANNOTATION_COMPOSITION_UNSUPPORTED;
            else if (type == null || observation.kind() != RawKind.ANNOTATION_USE || observation.evidenceState() != EvidenceState.VERIFIED) reason = ANNOTATION_METADATA_UNSUPPORTED;
            else if (!inventory.frameworkEvidence().acceptedConditionFragment(type.startsWith("org.springframework.boot."))) reason = VERSION_FRAGMENT_NOT_VALIDATED;
            else {
                try {
                    if (type.equals("org.springframework.context.annotation.Profile")) {
                        var attributes = LiteralConditionAnnotation.parse(observation.spelling());
                        if (!Set.of("value").containsAll(attributes.keySet())) throw LiteralConditionAnnotation.unsupported();
                        var values = LiteralConditionAnnotation.strings(attributes, "value", List.of());
                        if (values.isEmpty()) throw LiteralConditionAnnotation.unsupported();
                        List<ConditionExpression> expressions = new ArrayList<>();
                        for (String value : values) expressions.add(ProfileExpressionLowering.parse(value, semantics, limits.maxProfileDepth()));
                        expression = ProfileExpressionLowering.combine(semantics, false, expressions);
                    } else if (type.endsWith(".ConditionalOnProperty")) {
                        var attributes = LiteralConditionAnnotation.parse(observation.spelling());
                        if (!Set.of("name", "value", "prefix", "havingValue", "matchIfMissing").containsAll(attributes.keySet())) throw LiteralConditionAnnotation.unsupported();
                        var names = LiteralConditionAnnotation.strings(attributes, "name", List.of());
                        var values = LiteralConditionAnnotation.strings(attributes, "value", List.of());
                        if (names.isEmpty() == values.isEmpty()) throw LiteralConditionAnnotation.unsupported();
                        var selected = names.isEmpty() ? values : names;
                        expression = ConditionExpression.atom(semantics, new ConditionExpression.Property(
                                LiteralConditionAnnotation.string(attributes, "prefix", "").trim(), selected.stream().distinct().toList(),
                                LiteralConditionAnnotation.string(attributes, "havingValue", ""), LiteralConditionAnnotation.bool(attributes, "matchIfMissing", false)));
                    } else if (buildPredicate(type).isPresent()) {
                        var supplied = builds.getOrDefault(observation.identity(), List.of());
                        if (supplied.isEmpty()) reason = BUILD_EVIDENCE_UNAVAILABLE;
                        else if (supplied.stream().map(BuildObservation::value).distinct().count() != 1
                                || supplied.stream().anyMatch(b -> b.predicate() != buildPredicate(type).orElseThrow()
                                || !b.buildContext().equals(build.identity()))) reason = BUILD_EVIDENCE_CONFLICT;
                        else {
                            List<ContentDigest> inputs = supplied.stream().map(BuildObservation::identity).toList();
                            var proof = new ConditionEvidence.Derived(inputs, PROVIDER, "whole-annotation-build-query");
                            boolean validEvidence = supplied.stream().allMatch(b -> !(b.evidence() instanceof ConditionEvidence.Source source)
                                    || source.span().isPresent() && build.containsSource(source));
                            if (!validEvidence) reason = SOURCE_EVIDENCE_MISMATCH;
                            else expression = ConditionExpression.atom(semantics, new ConditionExpression.Build(buildPredicate(type).orElseThrow(),
                                    observation.identity().value(), build.identity(), supplied.getFirst().value(), proof));
                        }
                    } else if (obligation.primaryMechanism().equals("spring.condition.bean-state")) reason = BEAN_STATE_REQUIRED;
                    else reason = OPAQUE_CONDITION;
                } catch (IllegalArgumentException exception) {
                    // Only the bounded decoder/IR construction runs here. Never expose target text in diagnostics.
                    reason = ANNOTATION_METADATA_UNSUPPORTED;
                }
            }
            if (reason != null) expression = ConditionExpression.atom(semantics, new ConditionExpression.Opaque(
                    ConditionIdentitySupport.digest(Map.of("build", build.identity(), "inventory", inventory.identity(), "raw", observation.identity())),
                    reason == VERSION_FRAGMENT_NOT_VALIDATED ? ConditionExpression.OpaqueReason.VERSION_NOT_VALIDATED
                            : reason == OPAQUE_CONDITION ? ConditionExpression.OpaqueReason.CUSTOM_CODE : ConditionExpression.OpaqueReason.UNSUPPORTED_PREDICATE));
            var occurrence = new ConditionOccurrence(Objects.requireNonNull(expression), evidence,
                    "m4a-obligation/" + obligation.identity().value(), obligation.role(), obligation.roleOrdinal());
            occurrences.add(occurrence);
            rows.add(new Row(obligation.identity(), observation.identity(), reason == null ? Outcome.LOWERED : Outcome.OPAQUE, Optional.of(occurrence.view())));
            if (reason != null) issues.add(new ConditionProcessing.Issue(reason, obligation.identity().value(), Optional.of(occurrence.identity()), List.of(evidence)));
        }
        // Extra supplied proofs stay explicit; they are neither silently applied nor allowed to alter another row.
        for (var observation : observations) if (!raw.containsKey(observation.rawObservationIdentity())) {
            issues.add(new ConditionProcessing.Issue(BUILD_EVIDENCE_CONFLICT, observation.identity().value(), Optional.empty(), List.of(observation.evidence())));
        }
        return new Result(input, inventory, build, rows, occurrences, issues);
    }
    private static Optional<ConditionExpression.BuildPredicate> buildPredicate(String type) {
        return switch (type) {
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass" -> Optional.of(ConditionExpression.BuildPredicate.CLASS_PRESENT);
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnResource" -> Optional.of(ConditionExpression.BuildPredicate.RESOURCE_PRESENT);
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnJava" -> Optional.of(ConditionExpression.BuildPredicate.JAVA_VERSION);
            default -> Optional.empty();
        };
    }
    static String exactAnnotationType(RawObservation observation, SpringFrameworkEvidence framework) {
        for (var entry : SpringMechanismCatalog.entries()) if (entry.id().startsWith("spring.condition.")) {
            for (String type : entry.annotationTypes()) {
                int dot = type.lastIndexOf('.'); String key = JavaSymbolName.topLevelType(type.substring(0, dot), type.substring(dot + 1)).canonicalName();
                if (!key.equals(observation.stableReference())) continue;
                String coordinate = type.startsWith("org.springframework.boot.") ? "org.springframework.boot:spring-boot-autoconfigure:" : "org.springframework:spring-context:";
                for (var artifact : framework.artifacts()) if (artifact.coordinate().startsWith(coordinate)
                        && observation.resolvedTarget().filter(EntityIdentity.from(EntityOrigin.DEPENDENCY, artifact.entityScope(), EntityKind.TYPE, key)::equals).isPresent()) return type;
            }
        }
        return null;
    }
}
