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
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("spring.condition-lowering", "m8.4-native-spel-v3");
    private static final java.util.regex.Pattern WRITTEN_ANNOTATION=java.util.regex.Pattern.compile("^\\s*@\\s*([\\p{javaJavaIdentifierPart}.]+)");
    private static final List<String> CONDITION_TYPES=SpringMechanismCatalog.entries().stream().filter(e->e.id().startsWith("spring.condition."))
            .flatMap(e->e.annotationTypes().stream()).distinct().sorted().toList();
    private static final List<String> NON_CONDITION_SPRING_TYPES=SpringMechanismCatalog.entries().stream().filter(e->!e.id().startsWith("spring.condition."))
            .flatMap(e->e.annotationTypes().stream()).distinct().sorted().toList();
    private static final Set<String> CONDITION_TYPE_SET = Set.copyOf(CONDITION_TYPES);
    private static final Map<String, List<String>> CONDITION_TYPES_BY_SIMPLE = indexBySimpleName(CONDITION_TYPES);
    private static final Set<String> NON_CONDITION_TYPE_SET = Set.copyOf(NON_CONDITION_SPRING_TYPES);
    private static final Set<String> NON_CONDITION_SIMPLE_NAMES = indexSimpleNames(NON_CONDITION_SPRING_TYPES);
    private static final Map<String, String> CONDITION_CANONICAL_TO_TYPE = indexConditionCanonicalTypes();

    private static Map<String, List<String>> indexBySimpleName(List<String> types) {
        var map = new HashMap<String, List<String>>();
        for (String type : types) {
            int dot = type.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < type.length()) {
                map.computeIfAbsent(type.substring(dot + 1), ignored -> new ArrayList<>()).add(type);
            }
        }
        var frozen = new HashMap<String, List<String>>(map.size() * 2);
        map.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Map.copyOf(frozen);
    }

    private static Set<String> indexSimpleNames(List<String> types) {
        var set = new HashSet<String>();
        for (String type : types) {
            int dot = type.lastIndexOf('.');
            if (dot >= 0 && dot + 1 < type.length()) set.add(type.substring(dot + 1));
        }
        return Set.copyOf(set);
    }

    private static Map<String, String> indexConditionCanonicalTypes() {
        var map = new HashMap<String, String>();
        for (var entry : SpringMechanismCatalog.entries()) {
            if (!entry.id().startsWith("spring.condition.")) continue;
            for (String type : entry.annotationTypes()) {
                int dot = type.lastIndexOf('.');
                String key = JavaSymbolName.topLevelType(type.substring(0, dot), type.substring(dot + 1)).canonicalName();
                map.putIfAbsent(key, type);
            }
        }
        return Map.copyOf(map);
    }
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
    /** Written catalog-name candidates are navigation/acquisition evidence only.
     * They never enter the condition model or establish annotation authenticity. */
    public record SourceCandidate(ContentDigest rawObservation,List<String> candidateTypes,
                                  String typeBinding,boolean evaluationAllowed,ConditionEvidence evidence) {
        public SourceCandidate {candidateTypes=List.copyOf(candidateTypes);if(evaluationAllowed)throw new IllegalArgumentException("Unbound source candidate cannot be evaluated");}
    }
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
        private final SpringFrameworkEvidence.ValidationMode validationMode;
        private final List<Row> rows;
        private final List<ConditionOccurrence> occurrences;
        private final List<ConditionProcessing.Issue> issues;
        private final List<CapabilityGapRecord> gaps;
        private final List<SourceCandidate> sourceCandidates;
        private volatile ContentDigest cachedIdentity;
        Result(ContentDigest input, SpringMechanismInventory inventory, SpringBuildContext build,
               List<Row> rows, List<ConditionOccurrence> occurrences, Collection<ConditionProcessing.Issue> issues) {
            this(input, inventory, build, rows, occurrences, issues, SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
        }
        Result(ContentDigest input, SpringMechanismInventory inventory, SpringBuildContext build,
               List<Row> rows, List<ConditionOccurrence> occurrences, Collection<ConditionProcessing.Issue> issues,
               SpringFrameworkEvidence.ValidationMode validationMode) {
            this.input = input; this.inventory = inventory.identity(); semantics = ConditionEvidenceLowering.semantics(inventory.frameworkEvidence());
            this.validationMode = Objects.requireNonNull(validationMode);
            this.rows = List.copyOf(rows); this.occurrences = List.copyOf(occurrences);
            this.issues = issues.stream().distinct().sorted(Comparator.comparing(ConditionProcessing.Issue::identity)).toList();
            var combinedGaps = new TreeSet<>(ConditionProcessing.gaps(PROVIDER, input, build, this.issues));
            if (validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED
                    && !inventory.frameworkEvidence().acceptedConditionFragment(true)) {
                combinedGaps.add(compatGap(build, input, inventory.frameworkEvidence()));
            }
            gaps = List.copyOf(combinedGaps);
            var candidates=new ArrayList<SourceCandidate>();
            for(var raw:inventory.rawObservations())if(raw.kind()==RawKind.ANNOTATION_USE&&raw.resolvedTarget().isEmpty()
                    && (validationMode == SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED || !inventory.frameworkEvidence().artifacts().isEmpty())) {
                var types=sourceCandidateTypes(raw);
                if(!types.isEmpty())build.sourceDigest(raw.document()).ifPresent(digest->candidates.add(new SourceCandidate(raw.identity(),types,"NOT_ESTABLISHED",false,
                        new ConditionEvidence.Source(raw.document(),digest,raw.span(),raw.ordinal()))));
            }
            sourceCandidates=List.copyOf(candidates);
        }
        public ContentDigest inputIdentity() { return input; }
        public ContentDigest inventoryIdentity() { return inventory; }
        public ConditionExpression.Semantics semantics() { return semantics; }
        public SpringFrameworkEvidence.ValidationMode validationMode() { return validationMode; }
        public List<Row> rows() { return rows; }
        public List<ConditionOccurrence> occurrences() { return occurrences; }
        public List<ConditionProcessing.Issue> issues() { return issues; }
        public List<CapabilityGapRecord> capabilityGaps() { return gaps; }
        public List<SourceCandidate> sourceCandidates() {return sourceCandidates;}
        public ContentDigest identity() {
            ContentDigest cached = cachedIdentity;
            if (cached != null) return cached;
            ContentDigest computed = ConditionIdentitySupport.digest(canonicalForm());
            cachedIdentity = computed;
            return computed;
        }
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
            var entries = new LinkedHashMap<String, Object>();
            entries.put("schema", "spring-condition-lowering-v1");
            entries.put("provider", PROVIDER);
            entries.put("inputIdentity", input);
            entries.put("inventoryIdentity", inventory);
            entries.put("semantics", semantics);
            if (validationMode != SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED) {
                entries.put("validationMode", validationMode.name());
            }
            entries.put("rows", rows);
            entries.put("expressions", List.copyOf(expressions.values()));
            entries.put("issues", issues);
            entries.put("capabilityGaps", gaps);
            entries.put("coverage", coverage());
            entries.put("sourceCandidates", sourceCandidates);
            return Collections.unmodifiableMap(entries);
        }
    }
    private ConditionEvidenceLowering() {}
    public static ConditionExpression.Semantics semantics(SpringFrameworkEvidence evidence) {
        return new ConditionExpression.Semantics(ExogenousConditionEvaluator.SEMANTICS, evidence.identity());
    }
    public static Result lower(SpringBuildContext build, SpringMechanismInventory inventory,
                               List<BuildObservation> observations, Limits limits) {
        return lower(build, inventory, observations, limits, SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }
    public static Result lower(SpringBuildContext build, SpringMechanismInventory inventory,
                               List<BuildObservation> observations, Limits limits,
                               SpringFrameworkEvidence.ValidationMode validationMode) {
        Objects.requireNonNull(build); Objects.requireNonNull(inventory); Objects.requireNonNull(limits);
        Objects.requireNonNull(validationMode);
        observations = ContractChecks.sortedDistinct(observations, Comparator.comparing(BuildObservation::identity), "build condition observations");
        var inputMap = new LinkedHashMap<String, Object>();
        inputMap.put("provider", PROVIDER);
        inputMap.put("build", build.identity());
        inputMap.put("inventory", inventory.identity());
        inputMap.put("observations", observations);
        inputMap.put("limits", limits);
        if (validationMode != SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED) {
            inputMap.put("validationMode", validationMode.name());
        }
        var input = ConditionIdentitySupport.digest(inputMap);
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
            boolean compatSourceScoped = validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED
                    && inventory.frameworkEvidence().artifacts().isEmpty();
            boolean sourceSpanValid = observation.kind() == RawKind.ANNOTATION_USE
                    && observation.span().isPresent()
                    && build.sourceDigest(observation.document()).isPresent()
                    && (observation.evidenceState() == EvidenceState.VERIFIED
                        || (compatSourceScoped && observation.evidenceState() == EvidenceState.UNRESOLVED));
            List<String> cachedCandidates = null;
            if (compatSourceScoped
                    && !condition && unclassified
                    && sourceSpanValid) {
                cachedCandidates = sourceCandidateTypes(observation);
                if (cachedCandidates.isEmpty() && isKnownNonConditionSpringAnnotation(observation)) {
                    rows.add(new Row(obligation.identity(), observation.identity(), Outcome.NOT_A_CONDITION, Optional.empty())); continue;
                }
            }
            ConditionEvidence evidence = build.sourceDigest(observation.document())
                    .<ConditionEvidence>map(digest -> new ConditionEvidence.Source(observation.document(), digest, observation.span(), observation.ordinal()))
                    .orElseGet(() -> new ConditionEvidence.Derived(List.of(inventory.identity(), observation.identity()), PROVIDER, "unavailable-source"));
            ConditionProcessing.Reason reason = null;
            ConditionExpression expression = null;
            String rawType = exactAnnotationType(observation, inventory.frameworkEvidence(), validationMode);
            boolean sourceScoped = false;
            if (compatSourceScoped
                    && sourceSpanValid
                    && (rawType == null || unclassified)) {
                if (cachedCandidates == null) cachedCandidates = sourceCandidateTypes(observation);
                if (cachedCandidates.size() == 1) {
                    rawType = cachedCandidates.getFirst();
                    sourceScoped = true;
                }
            }
            final String type = rawType;
            boolean effectiveUnclassified = unclassified && !sourceScoped;
            if (!matchingBuild) reason = BUILD_CONTEXT_MISMATCH;
            else if (build.sourceDigest(observation.document()).isEmpty()) reason = SOURCE_EVIDENCE_MISMATCH;
            else if (observation.span().isEmpty()) reason = MISSING_SOURCE_SPAN;
            else if (effectiveUnclassified) {
                if (cachedCandidates == null) cachedCandidates = sourceCandidateTypes(observation);
                reason = !cachedCandidates.isEmpty() && observation.resolvedTarget().isEmpty()
                        ? ANNOTATION_TYPE_EVIDENCE_MISSING : POTENTIAL_CONDITION_UNCLASSIFIED;
            }
            else if (++attempted > limits.maxLoweredRows() || observation.spelling().length() > limits.maxMetadataCharacters()) reason = LOWERING_LIMIT;
            else if (obligation.secondaryTags().contains("spring.annotation.composed")) reason = ANNOTATION_COMPOSITION_UNSUPPORTED;
            else if (type == null || observation.kind() != RawKind.ANNOTATION_USE || (!sourceScoped && observation.evidenceState() != EvidenceState.VERIFIED)) reason = ANNOTATION_METADATA_UNSUPPORTED;
            else if (!inventory.frameworkEvidence().allows(type.startsWith("org.springframework.boot."), validationMode)) reason = VERSION_FRAGMENT_NOT_VALIDATED;
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
                    } else if (type.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnExpression")) {
                        var attributes=LiteralConditionAnnotation.parse(observation.spelling());
                        if(!Set.of("value").containsAll(attributes.keySet()))throw LiteralConditionAnnotation.unsupported();
                        expression=ConditionExpression.atom(semantics,new ConditionExpression.BasicSpel(LiteralConditionAnnotation.string(attributes,"value","true")));
                    } else if (type.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication")) {
                        var attributes = LiteralConditionAnnotation.parse(observation.spelling());
                        if (!Set.of("type").containsAll(attributes.keySet())) throw LiteralConditionAnnotation.unsupported();
                        String webType = enumSimpleName(attributes.get("type"), "ANY");
                        expression = switch (webType) {
                            case "SERVLET" -> ConditionExpression.atom(semantics, new ConditionExpression.Web(FiniteDomain.WebMode.SERVLET));
                            case "REACTIVE" -> ConditionExpression.atom(semantics, new ConditionExpression.Web(FiniteDomain.WebMode.REACTIVE));
                            case "ANY" -> ConditionExpression.any(semantics, List.of(
                                    ConditionExpression.atom(semantics, new ConditionExpression.Web(FiniteDomain.WebMode.SERVLET)),
                                    ConditionExpression.atom(semantics, new ConditionExpression.Web(FiniteDomain.WebMode.REACTIVE))));
                            default -> throw LiteralConditionAnnotation.unsupported();
                        };
                    } else if (type.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication")) {
                        var attributes = LiteralConditionAnnotation.parse(observation.spelling());
                        if (!attributes.isEmpty()) throw LiteralConditionAnnotation.unsupported();
                        expression = ConditionExpression.atom(semantics, new ConditionExpression.Web(FiniteDomain.WebMode.NONE));
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
                    } else if (obligation.primaryMechanism().equals("spring.condition.bean-state")
                            || type.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnBean")
                            || type.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean")
                            || type.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate")) reason = BEAN_STATE_REQUIRED;
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
        return new Result(input, inventory, build, rows, occurrences, issues, validationMode);
    }
    private static CapabilityGapRecord compatGap(SpringBuildContext build, ContentDigest input, SpringFrameworkEvidence framework) {
        String code = framework.artifacts().isEmpty() ? "FRAMEWORK_JARS_UNRESOLVED_SOURCE_SCOPED" : "VERSION_FAMILY_COMPATIBLE_UNPINNED";
        var observation = com.evolution.analysis.evidence.ProviderObservationReference.create(PROVIDER, "spring.condition.compatibility", input, ContentDigest.sha256Utf8(code));
        var subject = com.evolution.analysis.evidence.EvidenceSubject.observation(observation.identity());
        var req = new com.evolution.analysis.evidence.EvidenceRequirement(
                com.evolution.analysis.evidence.EvidenceRequirement.Kind.DEPENDENCY_ARTIFACT,
                "spring.condition." + code.toLowerCase(Locale.ROOT).replace('_', '-'),
                List.of(subject),
                com.evolution.analysis.evidence.EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Framework JAR bytes are not pinned to the R0 tuple; condition lowering ran in compatible/source-scoped mode."));
        return CapabilityGapRecord.create(ConditionProcessing.GAP_CATALOG,
                com.evolution.analysis.evidence.EvidenceContext.forSnapshot(build.snapshotIdentity()),
                PROVIDER, "spring.condition-processing", code, subject, List.of(),
                List.of(observation), List.of(req), List.of(),
                List.of(new com.evolution.analysis.evidence.AffectedOutput(
                        com.evolution.analysis.evidence.AffectedOutput.Kind.ANALYSIS_COVERAGE, "spring.condition-processing")),
                List.of(), List.of(),
                List.of("Compatible Spring version family or source-scoped analysis is not a substitute for pinned R0 artifact hashes."));
    }
    static List<String> sourceCandidateTypes(RawObservation observation) {
        var name = WRITTEN_ANNOTATION.matcher(observation.spelling());
        if (!name.find()) return List.of();
        String written = name.group(1);
        if (written.indexOf('.') >= 0) {
            return CONDITION_TYPE_SET.contains(written) ? List.of(written) : List.of();
        }
        return CONDITION_TYPES_BY_SIMPLE.getOrDefault(written, List.of());
    }
    private static boolean isKnownNonConditionSpringAnnotation(RawObservation observation) {
        var name = WRITTEN_ANNOTATION.matcher(observation.spelling());
        if (!name.find()) return false;
        String written = name.group(1);
        return written.indexOf('.') >= 0
                ? NON_CONDITION_TYPE_SET.contains(written)
                : NON_CONDITION_SIMPLE_NAMES.contains(written);
    }
    private static Optional<ConditionExpression.BuildPredicate> buildPredicate(String type) {
        return switch (type) {
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnClass", "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass" -> Optional.of(ConditionExpression.BuildPredicate.CLASS_PRESENT);
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnResource" -> Optional.of(ConditionExpression.BuildPredicate.RESOURCE_PRESENT);
            case "org.springframework.boot.autoconfigure.condition.ConditionalOnJava" -> Optional.of(ConditionExpression.BuildPredicate.JAVA_VERSION);
            default -> Optional.empty();
        };
    }
    private static String enumSimpleName(LiteralConditionAnnotation.Value value, String defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof LiteralConditionAnnotation.Enums enums && enums.names().size() == 1) {
            String raw = enums.names().getFirst();
            int dot = raw.lastIndexOf('.');
            return dot >= 0 ? raw.substring(dot + 1) : raw;
        }
        throw LiteralConditionAnnotation.unsupported();
    }
    private static final Set<String> TRUSTED_SPRING_GROUPS = Set.of(
            "org.springframework",
            "org.springframework.boot",
            "org.springframework.cloud",
            "org.springframework.ai",
            "org.springframework.security");

    static String exactAnnotationType(RawObservation observation, SpringFrameworkEvidence framework) {
        return exactAnnotationType(observation, framework, SpringFrameworkEvidence.ValidationMode.STRICT_R0_PINNED);
    }

    static String exactAnnotationType(
            RawObservation observation,
            SpringFrameworkEvidence framework,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        String key = observation.stableReference();
        if (observation.resolvedTarget().isEmpty()) return null;
        EntityIdentity resolved = observation.resolvedTarget().orElseThrow();
        String knownType = CONDITION_CANONICAL_TO_TYPE.get(key);
        if (validationMode == SpringFrameworkEvidence.ValidationMode.COMPATIBLE_FAMILY_OR_SOURCE_SCOPED) {
            boolean matchesTrustedArtifact = false;
            for (var artifact : framework.artifacts()) {
                if (TRUSTED_SPRING_GROUPS.contains(artifact.groupId())
                        && EntityIdentity.from(EntityOrigin.DEPENDENCY, artifact.entityScope(), EntityKind.TYPE, key)
                                .equals(resolved)) {
                    matchesTrustedArtifact = true;
                    break;
                }
            }
            if (!matchesTrustedArtifact) {
                return null;
            }
            if (knownType != null) {
                return knownType;
            }
            String parsedFqn = parseCanonicalTopLevelTypeFqn(key);
            if (parsedFqn != null && (parsedFqn.contains(".ConditionalOn") || parsedFqn.endsWith(".Conditional"))) {
                return parsedFqn;
            }
            return null;
        }
        if (knownType == null) return null;
        boolean isBoot = knownType.startsWith("org.springframework.boot.");
        String expectedGroup = isBoot ? "org.springframework.boot" : "org.springframework";
        String expectedArtifact = isBoot ? "spring-boot-autoconfigure" : "spring-context";
        for (var artifact : framework.artifacts()) {
            if (artifact.groupId().equals(expectedGroup)
                    && artifact.artifactId().equals(expectedArtifact)
                    && EntityIdentity.from(EntityOrigin.DEPENDENCY, artifact.entityScope(), EntityKind.TYPE, key).equals(resolved)) {
                return knownType;
            }
        }
        return null;
    }

    private static String parseCanonicalTopLevelTypeFqn(String canonicalKey) {
        try {
            String json = canonicalKey != null && canonicalKey.startsWith("java:v1:")
                    ? canonicalKey.substring(8) : canonicalKey;
            var parsed = com.evolution.analysis.graph.GraphJson.array(
                    com.evolution.analysis.graph.GraphJson.read(
                            json, com.evolution.analysis.graph.GraphJson.Limits.defaults()));
            if (parsed.size() == 3 && "type".equals(parsed.getFirst())) {
                var pkgParts = com.evolution.analysis.graph.GraphJson.array(parsed.get(1));
                var sb = new StringBuilder();
                for (Object part : pkgParts) {
                    if (!sb.isEmpty()) sb.append('.');
                    sb.append(com.evolution.analysis.graph.GraphJson.string(part));
                }
                String simple = com.evolution.analysis.graph.GraphJson.string(parsed.get(2));
                return sb.isEmpty() ? simple : sb + "." + simple;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }
}
