package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.util.*;
import static com.evolution.analysis.spring.condition.ConditionProcessing.Reason.*;

/** Bounded interpreter of normalized, evidence-qualified exogenous IR, before bean registration. */
public final class ExogenousConditionEvaluator {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.exogenous-evaluator", "m4b.2");
    public static final VersionedIdentifier SEMANTICS = new VersionedIdentifier("spring.exogenous-semantics", "m4b.2-v1");
    public static final VersionedIdentifier PRECEDENCE = new VersionedIdentifier("spring.normalized-precedence", "last-active-v1");
    public static final VersionedIdentifier PROFILES = new VersionedIdentifier("spring.normalized-profiles", "defaults-includes-groups-v1");
    public static final VersionedIdentifier CONVERSION = new VersionedIdentifier("spring.normalized-values", "exact-v1");
    public record Limits(int maxSteps, int maxSourceEntries) {
        public Limits { if (maxSteps < 1 || maxSourceEntries < 1) throw new IllegalArgumentException("Positive evaluation limits required"); }
        public static Limits conservative() { return new Limits(100000, 10000); }
    }
    public enum Status { COMPLETE, PARTIAL, INVALID_ASSIGNMENT, INVALID_MODEL }
    public record Row(ConditionOccurrence.Identity occurrence, LogicalValue truth) {}
    public record EffectiveValue(FiniteDomain.Variable variable, Optional<FiniteDomain.Value> value,
                                 List<ContentDigest> supportingInputs) {
        public EffectiveValue { Objects.requireNonNull(variable); Objects.requireNonNull(value); supportingInputs = supportingInputs.stream().distinct().sorted().toList(); }
    }
    public record Coverage(int inputRows, int trueRows, int falseRows, int unknownRows) {
        public Coverage {
            if (inputRows < 0 || trueRows < 0 || falseRows < 0 || unknownRows < 0 || (long) trueRows + falseRows + unknownRows != inputRows)
                throw new IllegalArgumentException("Evaluation denominator must be closed");
        }
    }
    public static final class Result {
        private final ContentDigest inputIdentity;
        private final List<Row> rows;
        private final List<EffectiveValue> environment;
        private final Map<String, LogicalValue> profiles;
        private final List<ConditionProcessing.Issue> issues;
        private final List<CapabilityGapRecord> gaps;
        private final Status status;
        private final LogicalValue assignmentFeasibility;
        private final int steps;
        Result(ContentDigest input, List<Row> rows, List<EffectiveValue> environment, Map<String, LogicalValue> profiles,
               Collection<ConditionProcessing.Issue> issues, List<CapabilityGapRecord> inherited,
               SpringBuildContext build, Status status, LogicalValue feasibility, int steps) {
            inputIdentity = input; this.rows = List.copyOf(rows); this.environment = List.copyOf(environment);
            this.profiles = Collections.unmodifiableMap(new TreeMap<>(profiles));
            this.issues = issues.stream().distinct().sorted(Comparator.comparing(ConditionProcessing.Issue::identity)).toList();
            var all = new TreeSet<>(inherited); all.addAll(ConditionProcessing.gaps(PROVIDER, input, build, this.issues)); gaps = List.copyOf(all);
            this.status = status; assignmentFeasibility = feasibility; this.steps = steps;
        }
        public ContentDigest inputIdentity() { return inputIdentity; }
        public ContentDigest identity() { return ConditionIdentitySupport.digest(canonicalForm()); }
        public List<Row> rows() { return rows; }
        public List<EffectiveValue> environment() { return environment; }
        public Map<String, LogicalValue> effectiveProfiles() { return profiles; }
        public List<ConditionProcessing.Issue> issues() { return issues; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public Status status() { return status; }
        /** Only this supplied assignment's normalized constraints; not complete Spring startup feasibility. */
        public LogicalValue assignmentFeasibility() { return assignmentFeasibility; }
        public int steps() { return steps; }
        public Coverage coverage() {
            return new Coverage(rows.size(), (int) rows.stream().filter(r -> r.truth == LogicalValue.TRUE).count(),
                    (int) rows.stream().filter(r -> r.truth == LogicalValue.FALSE).count(), (int) rows.stream().filter(r -> r.truth == LogicalValue.UNKNOWN).count());
        }
        public Object canonicalForm() {
            return Map.of("schema", "spring-exogenous-evaluation-v1", "provider", PROVIDER, "inputIdentity", inputIdentity,
                    "rows", rows, "environment", Map.of("values", environment, "profiles", profiles), "issues", issues, "capabilityGaps", gaps,
                    "coverage", coverage(), "outcome", Map.of("status", status, "assignmentFeasibility", assignmentFeasibility, "steps", steps));
        }
    }
    private ExogenousConditionEvaluator() {}
    public static Result evaluate(ConditionModel model, ConditionExpression.Semantics semantics,
                                  ConfigurationAssignment assignment, Limits limits) {
        return new Evaluation(model, semantics, assignment, limits).run();
    }

    private static final class Evaluation {
        final ConditionModel model;
        final ConfigurationSpace space;
        final ConditionExpression.Semantics semantics;
        final ConfigurationAssignment assignment;
        final Limits limits;
        final ContentDigest input;
        final Map<FiniteDomain.Variable, FiniteDomain> domains = new TreeMap<>();
        final Map<FiniteDomain.Variable, EffectiveValue> environment = new TreeMap<>();
        final Map<String, LogicalValue> profiles = new TreeMap<>();
        final Map<ConditionExpression.Identity, ConditionExpression.View> nodes = new HashMap<>();
        final Map<ContentDigest, ConditionProcessing.Issue> issues = new TreeMap<>();
        Optional<ConditionOccurrence.Identity> current = Optional.empty();
        ConditionEvidence currentEvidence;
        int steps;
        boolean invalidAssignment, environmentIncomplete, requiredSourceMissing;

        Evaluation(ConditionModel model, ConditionExpression.Semantics semantics, ConfigurationAssignment assignment, Limits limits) {
            this.model = Objects.requireNonNull(model); space = model.space(); this.semantics = Objects.requireNonNull(semantics);
            this.assignment = Objects.requireNonNull(assignment); this.limits = Objects.requireNonNull(limits);
            input = ConditionIdentitySupport.digest(Map.of("model", model.identity(), "semantics", semantics,
                    "assignment", assignment.identity(), "limits", limits, "provider", PROVIDER));
            space.domains().forEach(d -> domains.put(d.variable(), d));
            model.inspectedExpressions().forEach(n -> nodes.put(n.identity(), n));
            currentEvidence = assignment.evidence();
        }
        Result run() {
            boolean invalidModel = model.status() == ConditionModel.Status.INVALID_MODEL;
            boolean validSemantics = SEMANTICS.equals(semantics.version());
            if (!validSemantics) issue(VERSION_FRAGMENT_NOT_VALIDATED, semantics.version().toString(), currentEvidence);
            if (invalidModel) issue(INVALID_MODEL, space.identity().value(), space.feasibilityPolicy().evidence());
            prepareEnvironment();
            List<Row> rows = new ArrayList<>();
            Map<ConditionOccurrence.Identity, LogicalValue> truths = new HashMap<>();
            Map<ConditionOccurrence.Identity, ConditionModel.RowStatus> rowStatus = new HashMap<>();
            model.rows().forEach(r -> rowStatus.put(r.identity(), r.status()));
            for (var occurrence : model.sourceRows()) {
                current = Optional.of(occurrence.identity()); currentEvidence = occurrence.declarationEvidenceKey();
                LogicalValue value = LogicalValue.UNKNOWN;
                boolean evidenced = evidence(currentEvidence);
                if (rowStatus.get(occurrence.identity()) == ConditionModel.RowStatus.NOT_EXPANDED) {
                    issue(EXPRESSION_NOT_EXPANDED, occurrence.identity().value(), currentEvidence);
                } else if (!invalidModel && !invalidAssignment && validSemantics && evidenced) {
                    value = expression(occurrence.expression().identity());
                }
                rows.add(new Row(occurrence.identity(), value)); truths.put(occurrence.identity(), value);
            }
            LogicalValue feasible = LogicalValue.TRUE;
            for (var constraint : space.constraints()) feasible = feasible.and(truths.get(constraint.identity()));
            if (invalidModel || invalidAssignment || !validSemantics || requiredSourceMissing || environmentIncomplete) {
                feasible = feasible.and(LogicalValue.UNKNOWN);
            }
            Status status = invalidModel ? Status.INVALID_MODEL : invalidAssignment ? Status.INVALID_ASSIGNMENT
                    : !issues.isEmpty() || !model.capabilityGaps().isEmpty() || rows.stream().anyMatch(r -> r.truth == LogicalValue.UNKNOWN)
                    ? Status.PARTIAL : Status.COMPLETE;
            return new Result(input, rows, List.copyOf(environment.values()), profiles, issues.values(), model.capabilityGaps(),
                    space.buildContext(), status, feasible, steps);
        }
        void prepareEnvironment() {
            boolean assignmentEvidence = evidence(assignment.evidence());
            if (!evidence(space.feasibilityPolicy().evidence())) environmentIncomplete = true;
            for (var supplied : assignment.baseline().entrySet()) {
                FiniteDomain domain = domains.get(supplied.getKey());
                if (domain == null || !domain.values().contains(supplied.getValue())) {
                    invalidAssignment = true; issue(ASSIGNMENT_OUTSIDE_DOMAIN, variableKey(supplied.getKey()), assignment.evidence());
                }
            }
            for (var domain : domains.values()) {
                var value = assignment.baseline().get(domain.variable());
                if (value == null) { issue(ASSIGNMENT_MISSING, variableKey(domain.variable()), assignment.evidence()); environmentIncomplete = true; }
                if (!assignmentEvidence || !evidence(domain.evidence())) { value = null; environmentIncomplete = true; }
                if (value != null && value.kind() == FiniteDomain.ValueKind.OTHER) {
                    issue(OTHER_ABSTRACTION_NOT_VALIDATED, variableKey(domain.variable()), domain.evidence()); environmentIncomplete = true;
                }
                put(domain.variable(), value, List.of(assignment.identity(), domain.evidence().identity()));
            }
            long sourceSize = 0;
            for (var envelope : space.envelopes()) {
                sourceSize += envelope.documents().size() + envelope.declaredSources().size();
                for (var source : envelope.declaredSources()) sourceSize += source.values().size();
            }
            sourceSize += assignment.sourceChoices().size();
            if (sourceSize > limits.maxSourceEntries() || domains.size() > space.feasibilityPolicy().limits().maxVariables()
                    || domains.values().stream().anyMatch(d -> d.values().size() > space.feasibilityPolicy().limits().maxValuesPerDomain())) {
                issue(EVALUATION_LIMIT, "environment-inputs", assignment.evidence());
                environmentIncomplete = true; taint(domains.keySet(), assignment.evidence());
                profilesUnknown(); return;
            }
            resolveProfiles();
            Map<ConfigurationSpace.SourceReference, ConfigurationEnvelope.DeclaredSource> sources = new HashMap<>();
            for (var envelope : space.envelopes()) for (var source : envelope.declaredSources()) {
                sources.put(new ConfigurationSpace.SourceReference(envelope.layer(), source.identity()), source);
            }
            for (var choice : assignment.sourceChoices()) {
                var source = sources.get(choice.source());
                if (source == null || !source.values().getOrDefault(choice.variable(), List.of()).contains(choice.value())) {
                    invalidAssignment = true; issue(SOURCE_CHOICE_INVALID, ConditionIdentitySupport.digest(choice).value(), assignment.evidence());
                }
            }
            // An unlinked document has neither a proved key footprint nor a proved precedence position.
            List<ConditionEvidence> unplacedUnavailableDocuments = new ArrayList<>();
            for (var envelope : space.envelopes()) for (var document : envelope.documents()) {
                if (envelope.declaredSources().stream().noneMatch(s -> s.evidence().equals(document.evidence()))
                        && guard(document.activation()) != LogicalValue.FALSE) {
                    if (!evidence(document.evidence()) || unavailable(document.availability(), document.evidence())) {
                        environmentIncomplete = true; unplacedUnavailableDocuments.add(document.evidence()); profilesUnknown();
                    }
                }
            }
            if (!PRECEDENCE.equals(space.precedencePolicy().version()) || !evidence(space.precedencePolicy().evidence())) {
                issue(POLICY_NOT_SUPPORTED, "precedence", space.precedencePolicy().evidence());
                environmentIncomplete = true; taint(domains.keySet(), space.precedencePolicy().evidence()); profilesUnknown(); return;
            }
            if (!sources.keySet().equals(new HashSet<>(space.precedencePolicy().lowToHigh()))) {
                issue(PRECEDENCE_INCOMPLETE, space.identity().value(), space.precedencePolicy().evidence());
                environmentIncomplete = true; taint(domains.keySet(), space.precedencePolicy().evidence()); profilesUnknown(); return;
            }
            for (var reference : space.precedencePolicy().lowToHigh()) applySource(reference, sources.get(reference));
            for (var document : unplacedUnavailableDocuments) taint(domains.keySet(), document);
        }
        void applySource(ConfigurationSpace.SourceReference reference, ConfigurationEnvelope.DeclaredSource source) {
            currentEvidence = source.evidence();
            if (!step(source.identity().value())) { environmentIncomplete = true; taint(domains.keySet(), source.evidence()); profilesUnknown(); return; }
            LogicalValue active = guard(source.activation());
            var envelope = space.envelopes().stream().filter(e -> e.layer() == reference.layer()).findFirst().orElseThrow();
            boolean unavailable = false;
            for (var document : envelope.documents()) if (document.evidence().equals(source.evidence())) {
                active = active.and(guard(document.activation()));
                if (active != LogicalValue.FALSE) {
                    if (document.availability() == ConfigurationEnvelope.Availability.MISSING_OPTIONAL) active = LogicalValue.FALSE;
                    else unavailable |= unavailable(document.availability(), document.evidence()) || !evidence(document.evidence());
                }
            }
            if (active == LogicalValue.FALSE || source.availability() == ConfigurationEnvelope.Availability.MISSING_OPTIONAL) return;
            if (active == LogicalValue.UNKNOWN) environmentIncomplete = true;
            unavailable |= unavailable(source.availability(), source.evidence()) || !evidence(source.evidence());
            Collection<FiniteDomain.Variable> footprint = source.values().isEmpty() ? domains.keySet() : source.values().keySet();
            if (unavailable) {
                environmentIncomplete = true; taint(footprint, source.evidence());
                if (footprint.stream().anyMatch(v -> v.kind() == FiniteDomain.Kind.PROFILE)) profilesUnknown();
                return;
            }
            if (!CONVERSION.equals(source.conversionPolicy())) {
                issue(POLICY_NOT_SUPPORTED, source.identity().value(), source.evidence()); taint(footprint, source.evidence()); environmentIncomplete = true;
                if (footprint.stream().anyMatch(v -> v.kind() == FiniteDomain.Kind.PROFILE)) profilesUnknown();
                return;
            }
            for (var entry : source.values().entrySet()) {
                var variable = entry.getKey();
                if (variable.kind() != FiniteDomain.Kind.PROPERTY) {
                    issue(SOURCE_VALUE_UNSUPPORTED, source.identity().value(), source.evidence());
                    taint(List.of(variable), source.evidence()); environmentIncomplete = true;
                    if (variable.kind() == FiniteDomain.Kind.PROFILE) profilesUnknown();
                    continue;
                }
                FiniteDomain.Value selected = null;
                var choice = assignment.sourceChoices().stream().filter(c -> c.source().equals(reference) && c.variable().equals(variable)).findFirst();
                if (choice.isPresent()) selected = choice.orElseThrow().value();
                else if (entry.getValue().size() == 1) selected = entry.getValue().getFirst();
                else { issue(SOURCE_CHOICE_MISSING, source.identity().value(), source.evidence()); environmentIncomplete = true; }
                var domain = domains.get(variable);
                if (domain == null || !domain.values().containsAll(entry.getValue())) {
                    issue(ASSIGNMENT_OUTSIDE_DOMAIN, source.identity().value(), source.evidence()); selected = null; environmentIncomplete = true;
                }
                // MISSING means this source has no definition. It never deletes a lower value.
                if (selected != null && selected.kind() == FiniteDomain.ValueKind.MISSING) continue;
                if (selected != null && selected.kind() == FiniteDomain.ValueKind.OTHER) {
                    issue(OTHER_ABSTRACTION_NOT_VALIDATED, source.identity().value(), source.evidence()); environmentIncomplete = true;
                }
                EffectiveValue previous = environment.get(variable);
                if (active == LogicalValue.UNKNOWN && (previous == null || !previous.value().equals(Optional.ofNullable(selected)))) selected = null;
                List<ContentDigest> inputs = new ArrayList<>(previous == null ? List.of() : previous.supportingInputs());
                inputs.add(source.identity()); inputs.add(source.evidence().identity());
                put(variable, selected, inputs);
            }
        }
        boolean unavailable(ConfigurationEnvelope.Availability availability, ConditionEvidence evidence) {
            if (availability == ConfigurationEnvelope.Availability.AVAILABLE || availability == ConfigurationEnvelope.Availability.MISSING_OPTIONAL) return false;
            if (availability == ConfigurationEnvelope.Availability.MISSING_REQUIRED) {
                requiredSourceMissing = true; issue(REQUIRED_SOURCE_MISSING, availability.name(), evidence);
            } else issue(SOURCE_UNAVAILABLE, availability.name(), evidence);
            return true;
        }
        void resolveProfiles() {
            if (!PROFILES.equals(space.profilePolicy().version()) || !evidence(space.profilePolicy().evidence())) {
                issue(POLICY_NOT_SUPPORTED, "profiles", space.profilePolicy().evidence()); profilesUnknown(); environmentIncomplete = true; return;
            }
            environment.forEach((variable, value) -> {
                if (variable.kind() == FiniteDomain.Kind.PROFILE) profiles.put(variable.name(),
                        value.value().flatMap(FiniteDomain.Value::bool).map(Evaluation::truth).orElse(LogicalValue.UNKNOWN));
            });
            Set<String> referenced = new TreeSet<>(space.profilePolicy().defaultProfiles());
            referenced.addAll(space.profilePolicy().includes());
            for (var group : space.profilePolicy().groups().entrySet()) { referenced.add(group.getKey()); referenced.addAll(group.getValue()); }
            if (!profiles.keySet().containsAll(referenced)) {
                issue(PROFILE_CLOSURE_INCOMPLETE, space.identity().value(), space.profilePolicy().evidence());
                profilesUnknown(); environmentIncomplete = true; return;
            }
            space.profilePolicy().includes().forEach(p -> profiles.put(p, LogicalValue.TRUE));
            expandGroups();
            LogicalValue anyActive = LogicalValue.FALSE;
            for (var value : profiles.values()) anyActive = anyActive.or(value);
            for (String name : space.profilePolicy().defaultProfiles()) profiles.put(name, profiles.get(name).or(anyActive.not()));
            expandGroups();
        }
        void expandGroups() {
            // Monotone finite reachability is valid for this supplied positive group relation only.
            Deque<String> queue = new ArrayDeque<>(profiles.keySet());
            while (!queue.isEmpty()) {
                String group = queue.removeFirst();
                for (String member : space.profilePolicy().groups().getOrDefault(group, List.of())) {
                    if (!step("profile-group-closure")) { profilesUnknown(); environmentIncomplete = true; return; }
                    LogicalValue next = profiles.get(member).or(profiles.get(group));
                    if (next != profiles.get(member)) { profiles.put(member, next); queue.addLast(member); }
                }
            }
        }
        void profilesUnknown() {
            domains.keySet().stream().filter(v -> v.kind() == FiniteDomain.Kind.PROFILE).forEach(v -> profiles.put(v.name(), LogicalValue.UNKNOWN));
        }
        LogicalValue guard(Optional<ConditionExpression.Identity> activation) {
            if (activation.isEmpty()) return LogicalValue.TRUE;
            var id = activation.orElseThrow();
            // A guard must have its own source occurrence; an inspected child hash is not declaration provenance.
            boolean supported = false;
            for (var row : model.sourceRows()) if (row.expression().identity().equals(id)) {
                var status = model.rows().stream().filter(r -> r.identity().equals(row.identity())).findFirst().orElseThrow().status();
                if (status != ConditionModel.RowStatus.NOT_EXPANDED && evidence(row.declarationEvidenceKey())) supported = true;
            }
            if (!supported) { issue(ACTIVATION_NOT_SUPPORTED, id.value(), currentEvidence); return LogicalValue.UNKNOWN; }
            // Guards consume bootstrap profile/build evidence. Property-dependent loading needs a loader/phase model.
            Set<ConditionExpression.Identity> seen = new HashSet<>();
            Deque<Iterator<ConditionExpression.Identity>> pending = new ArrayDeque<>(); pending.push(List.of(id).iterator());
            while (!pending.isEmpty()) {
                if (!pending.peek().hasNext()) { pending.pop(); continue; }
                if (!step(id.value())) return LogicalValue.UNKNOWN;
                var next = pending.peek().next(); if (!seen.add(next)) continue;
                var node = nodes.get(next);
                if (node == null || node.operator() == ConditionExpression.Operator.PROPERTY
                        || node.operator() == ConditionExpression.Operator.BEAN_STATE || node.operator() == ConditionExpression.Operator.OPAQUE) {
                    issue(ACTIVATION_NOT_SUPPORTED, id.value(), currentEvidence); return LogicalValue.UNKNOWN;
                }
                if (!node.childExpressionIdentities().isEmpty()) pending.push(node.childExpressionIdentities().iterator());
            }
            return expression(id);
        }
        LogicalValue expression(ConditionExpression.Identity root) {
            Map<ConditionExpression.Identity, LogicalValue> evaluated = new HashMap<>();
            Deque<Frame> pending = new ArrayDeque<>();
            var first = nodes.get(root);
            if (first == null) { issue(EXPRESSION_NOT_EXPANDED, root.value(), currentEvidence); return LogicalValue.UNKNOWN; }
            pending.push(new Frame(first));
            while (!pending.isEmpty()) {
                if (!step(root.value())) return LogicalValue.UNKNOWN;
                var frame = pending.peek();
                if (pending.size() > space.feasibilityPolicy().limits().maxExpressionDepth()) {
                    issue(EVALUATION_LIMIT, root.value(), currentEvidence); return LogicalValue.UNKNOWN;
                }
                if (frame.children.hasNext()) {
                    var child = frame.children.next();
                    if (evaluated.containsKey(child)) continue;
                    var node = nodes.get(child);
                    if (node == null) { issue(EXPRESSION_NOT_EXPANDED, child.value(), currentEvidence); evaluated.put(child, LogicalValue.UNKNOWN); }
                    else pending.push(new Frame(node));
                } else {
                    var node = frame.node;
                    LogicalValue value;
                    if (!semantics.equals(node.frameworkSemantics()) || !SEMANTICS.equals(semantics.version())) {
                        issue(VERSION_FRAGMENT_NOT_VALIDATED, node.identity().value(), currentEvidence); value = LogicalValue.UNKNOWN;
                    } else if (node.operator() == ConditionExpression.Operator.ALL || node.operator() == ConditionExpression.Operator.ANY) {
                        value = node.operator() == ConditionExpression.Operator.ALL ? LogicalValue.TRUE : LogicalValue.FALSE;
                        for (var child : node.childExpressionIdentities()) value = node.operator() == ConditionExpression.Operator.ALL
                                ? value.and(evaluated.get(child)) : value.or(evaluated.get(child));
                    } else if (node.operator() == ConditionExpression.Operator.NOT) value = evaluated.get(node.childExpressionIdentities().getFirst()).not();
                    else value = atomic(node);
                    evaluated.put(node.identity(), value); pending.pop();
                }
            }
            return evaluated.get(root);
        }
        LogicalValue atomic(ConditionExpression.View node) {
            return switch (node.typedOperands()) {
                case ConditionExpression.Constant constant -> {
                    if (constant.value() == LogicalValue.UNKNOWN) issue(UNRESOLVED_CONSTANT, node.identity().value(), currentEvidence);
                    yield constant.value();
                }
                case ConditionExpression.Profile profile -> {
                    if (!profiles.containsKey(profile.name())) issue(PROFILE_CLOSURE_INCOMPLETE, node.identity().value(), currentEvidence);
                    yield profiles.getOrDefault(profile.name(), LogicalValue.UNKNOWN);
                }
                case ConditionExpression.Property property -> property(property, node.identity());
                case ConditionExpression.Web web -> value(new FiniteDomain.Variable(FiniteDomain.Kind.WEB_MODE, "spring.web-mode"))
                        .flatMap(FiniteDomain.Value::webMode).map(v -> truth(v == web.mode())).orElse(LogicalValue.UNKNOWN);
                case ConditionExpression.Build build -> {
                    if (!build.buildContext().equals(space.buildContext().identity())) {
                        issue(BUILD_CONTEXT_MISMATCH, node.identity().value(), build.evidence()); yield LogicalValue.UNKNOWN;
                    }
                    if (!evidence(build.evidence())) yield LogicalValue.UNKNOWN;
                    if (build.observedValue() == LogicalValue.UNKNOWN) issue(BUILD_EVIDENCE_UNAVAILABLE, node.identity().value(), build.evidence());
                    yield build.observedValue();
                }
                case ConditionExpression.Bean ignored -> { issue(BEAN_STATE_REQUIRED, node.identity().value(), currentEvidence); yield LogicalValue.UNKNOWN; }
                case ConditionExpression.Opaque ignored -> { issue(OPAQUE_CONDITION, node.identity().value(), currentEvidence); yield LogicalValue.UNKNOWN; }
                case ConditionExpression.None ignored -> throw new IllegalStateException("Boolean operand reached atomic evaluator");
            };
        }
        LogicalValue property(ConditionExpression.Property property, ConditionExpression.Identity identity) {
            if (!property.prefix().equals(property.prefix().trim())) {
                issue(PROPERTY_SEMANTICS_UNSUPPORTED, identity.value(), currentEvidence); return LogicalValue.UNKNOWN;
            }
            LogicalValue result = LogicalValue.TRUE;
            for (String key : property.keys()) {
                if (!step(identity.value())) return LogicalValue.UNKNOWN;
                var selected = value(new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, key));
                LogicalValue item = LogicalValue.UNKNOWN;
                if (selected.isPresent()) {
                    var v = selected.orElseThrow();
                    if (v.kind() == FiniteDomain.ValueKind.MISSING) item = truth(property.matchIfMissing());
                    else if (v.kind() == FiniteDomain.ValueKind.OTHER) issue(OTHER_ABSTRACTION_NOT_VALIDATED, identity.value(), currentEvidence);
                    else if (v.kind() == FiniteDomain.ValueKind.EXACT) {
                        String text = v.exact().orElseThrow();
                        if (!ascii(text) || !ascii(property.havingValue()) || text.contains("${") || key.contains("${") || key.contains("[") || key.contains("]")) {
                            issue(PROPERTY_SEMANTICS_UNSUPPORTED, identity.value(), currentEvidence);
                        } else item = truth(property.havingValue().isEmpty() ? !text.equalsIgnoreCase("false") : text.equalsIgnoreCase(property.havingValue()));
                    }
                }
                result = result.and(item);
            }
            return result;
        }
        Optional<FiniteDomain.Value> value(FiniteDomain.Variable variable) {
            var result = environment.get(variable);
            if (result == null) { issue(ASSIGNMENT_MISSING, variableKey(variable), currentEvidence); return Optional.empty(); }
            return result.value();
        }
        void put(FiniteDomain.Variable variable, FiniteDomain.Value value, List<ContentDigest> evidence) {
            environment.put(variable, new EffectiveValue(variable, Optional.ofNullable(value), evidence));
        }
        void taint(Collection<FiniteDomain.Variable> variables, ConditionEvidence evidence) {
            for (var variable : variables) {
                var previous = environment.get(variable);
                var inputs = new ArrayList<>(previous == null ? List.of() : previous.supportingInputs()); inputs.add(evidence.identity());
                put(variable, null, inputs);
            }
        }
        boolean evidence(ConditionEvidence evidence) {
            if (evidence instanceof ConditionEvidence.Source source) {
                boolean valid = true;
                if (source.span().isEmpty()) { issue(MISSING_SOURCE_SPAN, source.identity().value(), source); valid = false; }
                if (!space.buildContext().containsSource(source)) { issue(SOURCE_EVIDENCE_MISMATCH, source.identity().value(), source); valid = false; }
                return valid;
            }
            return true; // Supplying artifact/derivation providers own payload/proof validation.
        }
        boolean step(String subject) {
            if (steps == limits.maxSteps()) { issue(EVALUATION_LIMIT, subject, currentEvidence); return false; }
            steps++; return true;
        }
        void issue(ConditionProcessing.Reason reason, String subject, ConditionEvidence evidence) {
            var issue = new ConditionProcessing.Issue(reason, subject, current, List.of(evidence)); issues.putIfAbsent(issue.identity(), issue);
        }
        static boolean ascii(String value) { return value.chars().allMatch(c -> c < 128); }
        static LogicalValue truth(boolean value) { return value ? LogicalValue.TRUE : LogicalValue.FALSE; }
        static String variableKey(FiniteDomain.Variable variable) { return ConditionIdentitySupport.digest(variable).value(); }
        static final class Frame {
            final ConditionExpression.View node;
            final Iterator<ConditionExpression.Identity> children;
            Frame(ConditionExpression.View node) { this.node = node; children = node.childExpressionIdentities().iterator(); }
        }
    }
}
