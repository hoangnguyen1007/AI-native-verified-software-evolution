package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import java.util.*;
import java.util.stream.Stream;

/** Bounded IR validation and closed row inventory. No predicate/activation/feasibility solver or I/O.
 * Canonical inputs remain identifiable when traversal is withheld; partial graphs are explicitly marked. */
public final class ConditionModel {
    public static final String SCHEMA = "spring-condition-model-v1";
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.condition-model", "m4b.1");
    public static final VersionedIdentifier GAP_CATALOG = new VersionedIdentifier("evidence.spring-condition-gaps", "m4b.1-v1");
    public enum Status { REPRESENTED, PARTIAL, INVALID_MODEL }
    public enum RowStatus { REPRESENTED, QUALIFIED, NOT_EXPANDED }
    public enum Feasibility { NOT_EVALUATED, INVALID_MODEL }
    public enum Reason {
        OPAQUE_CONDITION, UNSUPPORTED_PREDICATE, VERSION_FRAGMENT_NOT_VALIDATED, BUILD_EVIDENCE_UNKNOWN, UNRESOLVED_LOGICAL_CONSTANT,
        BUILD_CONTEXT_MISMATCH, MISSING_SOURCE_SPAN, SOURCE_EVIDENCE_MISMATCH, DOMAIN_NOT_DECLARED, EMPTY_DOMAIN,
        OTHER_ABSTRACTION_NOT_VALIDATED, VARIABLE_LIMIT, DOMAIN_VALUE_LIMIT, WORLD_LIMIT,
        CONDITION_ROW_LIMIT, EXPRESSION_VISIT_LIMIT, EXPRESSION_DEPTH_LIMIT,
        UNRESOLVED_ACTIVATION, PRECEDENCE_INCOMPLETE, CONFIG_SOURCE_UNAVAILABLE,
        STATE_DEPENDENT_CONSTRAINT, OPAQUE_CONSTRAINT, SOURCE_VALUE_OUTSIDE_DOMAIN
    }
    public record Problem(Reason reason, String subject, Optional<ConditionOccurrence.Identity> occurrence,
                          List<ConditionEvidence> evidence) {
        public Problem {
            Objects.requireNonNull(reason); subject = ConditionIdentitySupport.name(subject); Objects.requireNonNull(occurrence);
            evidence = ContractChecks.sortedDistinct(evidence, Comparator.comparing(ConditionEvidence::identity), "problem evidence");
        }
    }
    public record Row(ConditionOccurrence.Identity identity, ConditionExpression.Identity expressionIdentity,
                      ConditionOccurrence.View occurrence, RowStatus status, List<ContentDigest> problemIdentities) {
        public Row {
            Objects.requireNonNull(identity); Objects.requireNonNull(expressionIdentity); Objects.requireNonNull(occurrence);
            Objects.requireNonNull(status);
            problemIdentities = ContractChecks.sortedDistinct(problemIdentities, Comparator.naturalOrder(), "row problems");
            if (!identity.equals(occurrence.identity()) || !expressionIdentity.equals(occurrence.expressionIdentity())
                    || (status == RowStatus.REPRESENTED) != problemIdentities.isEmpty()) {
                throw new IllegalArgumentException("Condition row does not match its expression/outcome");
            }
        }
    }
    public record Coverage(int inputRows, int representedRows, int qualifiedRows, int unexpandedRows) {
        public Coverage {
            if (inputRows < 0 || representedRows < 0 || qualifiedRows < 0 || unexpandedRows < 0
                    || (long) representedRows + qualifiedRows + unexpandedRows != inputRows) {
                throw new IllegalArgumentException("Condition-row denominator is not closed");
            }
        }
    }
    private final ConfigurationSpace space;
    private final List<ConditionOccurrence> sourceRows;
    private final ContentDigest inputIdentity;
    private final ContentDigest identity;
    private final List<Row> rows;
    private final List<ConditionExpression.View> inspectedExpressions;
    private final List<Problem> problems;
    private final List<CapabilityGapRecord> gaps;
    private final Coverage coverage;
    private final Status status;

    private ConditionModel(ConfigurationSpace space, List<ConditionOccurrence> inputRows) {
        this.space = Objects.requireNonNull(space);
        inputRows = ContractChecks.sortedDistinct(inputRows, Comparator.comparing(ConditionOccurrence::identity), "condition rows");
        Map<ConditionOccurrence.Identity, ConditionOccurrence> all = new TreeMap<>();
        inputRows.forEach(row -> all.put(row.identity(), row));
        space.constraints().forEach(row -> all.putIfAbsent(row.identity(), row));
        List<ConditionOccurrence> ordered = List.copyOf(all.values());
        sourceRows = ordered;
        inputIdentity = ConditionIdentitySupport.digest(Map.of("schema", SCHEMA, "provider", PROVIDER,
                "space", space.identity(), "rows", ordered.stream().map(ConditionOccurrence::canonicalForm).toList()));
        Validator validator = new Validator(space, ordered);
        validator.validate();
        problems = validator.problems.values().stream().toList();
        inspectedExpressions = validator.nodes.values().stream().map(ConditionExpression::view).toList();
        rows = List.copyOf(validator.rows);
        coverage = new Coverage(rows.size(), count(RowStatus.REPRESENTED), count(RowStatus.QUALIFIED), count(RowStatus.NOT_EXPANDED));
        status = problems.stream().anyMatch(problem -> problem.reason() == Reason.EMPTY_DOMAIN) ? Status.INVALID_MODEL
                : problems.isEmpty() ? Status.REPRESENTED : Status.PARTIAL;
        gaps = problems.stream().map(this::gap).sorted().toList();
        identity = ConditionIdentitySupport.digest(canonicalForm());
    }
    public static ConditionModel create(ConfigurationSpace space, List<ConditionOccurrence> rows) {
        return new ConditionModel(space, rows);
    }
    private int count(RowStatus status) { return (int) rows.stream().filter(row -> row.status() == status).count(); }
    public ContentDigest identity() { return identity; }
    public ContentDigest inputIdentity() { return inputIdentity; }
    public ConfigurationSpace space() { return space; }
    /** Complete immutable supplied roots, including formulas whose bounded expansion was withheld. */
    public List<ConditionOccurrence> sourceRows() { return sourceRows; }
    public List<Row> rows() { return rows; }
    public List<ConditionExpression.View> inspectedExpressions() { return inspectedExpressions; }
    public List<Problem> problems() { return problems; }
    public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
    public Coverage coverage() { return coverage; }
    public Status status() { return status; }
    public Feasibility feasibility() { return status == Status.INVALID_MODEL ? Feasibility.INVALID_MODEL : Feasibility.NOT_EVALUATED; }
    public Map<String, Object> canonicalForm() {
        return Map.ofEntries(Map.entry("schema", SCHEMA), Map.entry("provider", PROVIDER),
                Map.entry("inputIdentity", inputIdentity), Map.entry("spaceIdentity", space.identity()),
                Map.entry("rows", rows), Map.entry("inspectedExpressions", inspectedExpressions),
                Map.entry("problems", problems), Map.entry("capabilityGaps", gaps), Map.entry("coverage", coverage),
                Map.entry("status", status), Map.entry("feasibility", feasibility()), Map.entry("cartesianSize", space.cartesianSize()));
    }
    private CapabilityGapRecord gap(Problem problem) {
        var observation = ProviderObservationReference.create(PROVIDER, "spring.condition-model.problem",
                inputIdentity, ConditionIdentitySupport.digest(problem));
        EvidenceSubject subject = EvidenceSubject.observation(observation.identity());
        EvidenceRequirement.Kind kind = switch (problem.reason()) {
            case OPAQUE_CONDITION -> EvidenceRequirement.Kind.RUNTIME_OBSERVATION;
            case VERSION_FRAGMENT_NOT_VALIDATED, UNSUPPORTED_PREDICATE -> EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT;
            case BUILD_CONTEXT_MISMATCH, BUILD_EVIDENCE_UNKNOWN -> EvidenceRequirement.Kind.EXACT_CLASSPATH;
            case MISSING_SOURCE_SPAN, SOURCE_EVIDENCE_MISMATCH -> EvidenceRequirement.Kind.REPOSITORY_CONTENT;
            default -> EvidenceRequirement.Kind.CONFIGURATION;
        };
        EvidenceRequirement.AuthorizationClass authorization = kind == EvidenceRequirement.Kind.RUNTIME_OBSERVATION
                ? EvidenceRequirement.AuthorizationClass.RUNTIME_ACCESS : EvidenceRequirement.AuthorizationClass.PASSIVE;
        String question = "spring.condition." + problem.reason().name().toLowerCase(Locale.ROOT).replace('_', '-');
        EvidenceRequirement requirement = new EvidenceRequirement(kind, question, List.of(subject), authorization,
                List.of("Exact evidence or an explicitly revised finite model resolves the recorded cause within the pinned semantics."));
        return CapabilityGapRecord.create(GAP_CATALOG, EvidenceContext.forSnapshot(space.buildContext().snapshotIdentity()),
                PROVIDER, "spring.condition-model", problem.reason().name(), subject,
                problem.evidence().stream().flatMap(value -> value.spans().stream()).distinct().sorted().toList(),
                List.of(observation), List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.condition-model")),
                List.of(), List.of(), List.of("M4B.1 represents supplied IR; activation, registration, feasibility and truth regions are not evaluated."));
    }

    private static final class Validator {
        private final ConfigurationSpace space;
        private final List<ConditionOccurrence> inputs;
        private final ConfigurationSpace.Limits limits;
        private final Map<FiniteDomain.Variable, FiniteDomain> domains = new TreeMap<>();
        private final Map<ContentDigest, Problem> problems = new TreeMap<>();
        private final Map<ConditionOccurrence.Identity, Set<ContentDigest>> rowProblems = new TreeMap<>();
        private final Map<ConditionExpression.Identity, ConditionExpression> nodes = new TreeMap<>();
        private final List<Row> rows = new ArrayList<>();
        private int visits;

        Validator(ConfigurationSpace space, List<ConditionOccurrence> inputs) {
            this.space = space; this.inputs = inputs; limits = space.feasibilityPolicy().limits();
            space.domains().forEach(domain -> domains.put(domain.variable(), domain));
        }
        void validate() {
            if (domains.size() > limits.maxVariables()) global(Reason.VARIABLE_LIMIT, space.identity().value());
            if (space.cartesianSize().value().compareTo(java.math.BigInteger.valueOf(limits.maxCartesianAssignments())) > 0) {
                global(Reason.WORLD_LIMIT, space.identity().value());
            }
            for (FiniteDomain domain : space.domains()) {
                String key = ConditionIdentitySupport.digest(domain).value();
                evidence(domain.evidence(), Optional.empty());
                if (domain.values().isEmpty()) add(Reason.EMPTY_DOMAIN, key, Optional.empty(), List.of(domain.evidence()));
                if (domain.values().size() > limits.maxValuesPerDomain()) add(Reason.DOMAIN_VALUE_LIMIT, key, Optional.empty(), List.of(domain.evidence()));
                if (domain.values().stream().anyMatch(value -> value.kind() == FiniteDomain.ValueKind.OTHER)) {
                    add(Reason.OTHER_ABSTRACTION_NOT_VALIDATED, key, Optional.empty(), List.of(domain.evidence()));
                }
            }
            evidence(space.precedencePolicy().evidence(), Optional.empty());
            evidence(space.profilePolicy().evidence(), Optional.empty());
            evidence(space.feasibilityPolicy().evidence(), Optional.empty());
            Set<ConfigurationSpace.SourceReference> expectedSources = new HashSet<>();
            for (ConfigurationEnvelope envelope : space.envelopes()) {
                for (var document : envelope.documents()) {
                    evidence(document.evidence(), Optional.empty());
                    availability(document.availability(), ConditionIdentitySupport.digest(document).value(), document.evidence());
                }
                for (var source : envelope.declaredSources()) {
                    expectedSources.add(new ConfigurationSpace.SourceReference(envelope.layer(), source.identity()));
                    evidence(source.evidence(), Optional.empty());
                    availability(source.availability(), source.identity().value(), source.evidence());
                    source.values().forEach((variable, values) -> {
                        if (values.isEmpty()) add(Reason.EMPTY_DOMAIN, source.identity().value(), Optional.empty(), List.of(source.evidence()));
                        FiniteDomain domain = domains.get(variable);
                        if (domain == null || !domain.values().containsAll(values)) {
                            add(Reason.SOURCE_VALUE_OUTSIDE_DOMAIN, source.identity().value(), Optional.empty(), List.of(source.evidence()));
                        }
                    });
                }
            }
            if (!expectedSources.equals(new HashSet<>(space.precedencePolicy().lowToHigh()))) {
                global(Reason.PRECEDENCE_INCOMPLETE, space.identity().value());
            }
            for (ConditionOccurrence constraint : space.constraints()) {
                Set<ConditionExpression.Dependency> dependencies = constraint.expression().dependencies();
                if (dependencies.contains(ConditionExpression.Dependency.BEAN_STATE)) {
                    add(Reason.STATE_DEPENDENT_CONSTRAINT, constraint.identity().value(), Optional.of(constraint.identity()), List.of(constraint.declarationEvidenceKey()));
                }
                if (dependencies.contains(ConditionExpression.Dependency.OPAQUE)) {
                    add(Reason.OPAQUE_CONSTRAINT, constraint.identity().value(), Optional.of(constraint.identity()), List.of(constraint.declarationEvidenceKey()));
                }
            }
            for (int index = 0; index < inputs.size(); index++) row(inputs.get(index), index);
            Set<ConditionExpression.Identity> available = nodes.keySet();
            for (ConfigurationEnvelope envelope : space.envelopes()) {
                Stream.concat(envelope.documents().stream().flatMap(document -> document.activation().stream()),
                        envelope.declaredSources().stream().flatMap(source -> source.activation().stream()))
                        .filter(identity -> !available.contains(identity)).distinct()
                        .forEach(identity -> global(Reason.UNRESOLVED_ACTIVATION, identity.value()));
            }
        }
        private void row(ConditionOccurrence occurrence, int index) {
            Optional<ConditionOccurrence.Identity> rowId = Optional.of(occurrence.identity());
            evidence(occurrence.declarationEvidenceKey(), rowId);
            boolean expanded = true;
            if (index >= limits.maxConditionRows()) {
                add(Reason.CONDITION_ROW_LIMIT, occurrence.identity().value(), rowId, List.of(occurrence.declarationEvidenceKey()));
                expanded = false;
            } else if (occurrence.expression().depth() > limits.maxExpressionDepth()) {
                add(Reason.EXPRESSION_DEPTH_LIMIT, occurrence.identity().value(), rowId, List.of(occurrence.declarationEvidenceKey()));
                expanded = false;
            } else {
                Set<ConditionExpression.Identity> seen = new HashSet<>();
                Deque<Iterator<ConditionExpression>> pending = new ArrayDeque<>();
                pending.push(List.of(occurrence.expression()).iterator());
                while (!pending.isEmpty()) {
                    if (!pending.peek().hasNext()) { pending.pop(); continue; }
                    if (visits >= limits.maxExpressionVisits()) {
                        add(Reason.EXPRESSION_VISIT_LIMIT, occurrence.identity().value(), rowId, List.of(occurrence.declarationEvidenceKey()));
                        expanded = false; break;
                    }
                    visits++;
                    ConditionExpression node = pending.peek().next();
                    if (!seen.add(node.identity())) continue;
                    nodes.putIfAbsent(node.identity(), node);
                    inspect(node, occurrence);
                    // Iterator frames bound traversal memory by depth, even for a very wide input node.
                    if (!node.children().isEmpty()) pending.push(node.children().iterator());
                }
            }
            List<ContentDigest> linkedProblems = List.copyOf(rowProblems.getOrDefault(occurrence.identity(), Set.of()));
            rows.add(new Row(occurrence.identity(), occurrence.expression().identity(), occurrence.view(),
                    !expanded ? RowStatus.NOT_EXPANDED : linkedProblems.isEmpty() ? RowStatus.REPRESENTED : RowStatus.QUALIFIED, linkedProblems));
        }
        private void inspect(ConditionExpression node, ConditionOccurrence occurrence) {
            Optional<ConditionOccurrence.Identity> rowId = Optional.of(occurrence.identity());
            List<ConditionEvidence> evidence = List.of(occurrence.declarationEvidenceKey());
            switch (node.operand()) {
                case ConditionExpression.Opaque opaque -> add(switch (opaque.reason()) {
                    case CUSTOM_CODE, UNRESOLVED_EXPRESSION -> Reason.OPAQUE_CONDITION;
                    case UNSUPPORTED_PREDICATE -> Reason.UNSUPPORTED_PREDICATE;
                    case VERSION_NOT_VALIDATED -> Reason.VERSION_FRAGMENT_NOT_VALIDATED;
                }, node.identity().value(), rowId, evidence);
                case ConditionExpression.Build build -> {
                    evidence(build.evidence(), rowId);
                    if (!build.buildContext().equals(space.buildContext().identity())) add(Reason.BUILD_CONTEXT_MISMATCH, node.identity().value(), rowId, evidence);
                    if (build.observedValue() == LogicalValue.UNKNOWN) add(Reason.BUILD_EVIDENCE_UNKNOWN, node.identity().value(), rowId, evidence);
                }
                case ConditionExpression.Profile profile -> requireDomain(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, profile.name()), node, occurrence);
                case ConditionExpression.Property property -> property.keys().forEach(key -> requireDomain(
                        new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, key), node, occurrence));
                case ConditionExpression.Web ignored -> requireDomain(new FiniteDomain.Variable(FiniteDomain.Kind.WEB_MODE, "spring.web-mode"), node, occurrence);
                case ConditionExpression.Constant constant -> {
                    if (constant.value() == LogicalValue.UNKNOWN) add(Reason.UNRESOLVED_LOGICAL_CONSTANT, node.identity().value(), rowId, evidence);
                }
                default -> { }
            }
        }
        private void requireDomain(FiniteDomain.Variable variable, ConditionExpression node, ConditionOccurrence row) {
            if (!domains.containsKey(variable)) add(Reason.DOMAIN_NOT_DECLARED, node.identity().value(), Optional.of(row.identity()), List.of(row.declarationEvidenceKey()));
        }
        private void evidence(ConditionEvidence evidence, Optional<ConditionOccurrence.Identity> row) {
            if (evidence instanceof ConditionEvidence.Source source) {
                if (source.span().isEmpty()) add(Reason.MISSING_SOURCE_SPAN, source.identity().value(), row, List.of(source));
                if (!space.buildContext().containsSource(source)) add(Reason.SOURCE_EVIDENCE_MISMATCH, source.identity().value(), row, List.of(source));
            }
        }
        private void availability(ConfigurationEnvelope.Availability availability, String subject, ConditionEvidence evidence) {
            if (availability != ConfigurationEnvelope.Availability.AVAILABLE && availability != ConfigurationEnvelope.Availability.MISSING_OPTIONAL) {
                add(Reason.CONFIG_SOURCE_UNAVAILABLE, subject, Optional.empty(), List.of(evidence));
            }
        }
        private void global(Reason reason, String subject) {
            add(reason, subject, Optional.empty(), List.of(space.feasibilityPolicy().evidence()));
        }
        private void add(Reason reason, String subject, Optional<ConditionOccurrence.Identity> occurrence, List<ConditionEvidence> evidence) {
            Problem problem = new Problem(reason, subject, occurrence, evidence);
            ContentDigest identity = ConditionIdentitySupport.digest(problem);
            problems.putIfAbsent(identity, problem);
            occurrence.ifPresent(row -> rowProblems.computeIfAbsent(row, ignored -> new TreeSet<>()).add(identity));
        }
    }
}
