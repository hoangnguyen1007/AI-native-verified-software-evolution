package com.evolution.analysis.spring.registration;

import com.evolution.analysis.spring.condition.*;
import java.util.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.registration.BeanRegistrationProcessing.Reason.*;

/** Boot 3.4.0 / Framework 6.2.0, one exact container. No final-state or fixpoint evaluation. */
final class EndogenousBeanConditions {
    record MatchRow(String selector, Map<String, LogicalValue> candidates, LogicalValue exists) {
        MatchRow { candidates = Collections.unmodifiableMap(new TreeMap<>(candidates)); }
    }
    record Result(LogicalValue truth, List<MatchRow> matches, Set<BeanRegistrationProcessing.Reason> reasons, int checks) {
        Result { matches = List.copyOf(matches); reasons = Set.copyOf(reasons); }
    }
    private final BeanDefinitionState state;
    private final Map<BeanDefinitionCandidate.Identity, BeanRegistrationEvidence.Definition> metadata;
    private final SpringBuildContext build;
    private final Map<String, BeanRegistrationEvidence.QueryType> queryTypes;
    private final int limit;
    private int checks;
    private final Set<BeanRegistrationProcessing.Reason> reasons = EnumSet.noneOf(BeanRegistrationProcessing.Reason.class);
    private final List<MatchRow> rows = new ArrayList<>();
    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private static final class QueryFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
    private EndogenousBeanConditions(BeanDefinitionState state,
                                    Map<BeanDefinitionCandidate.Identity, BeanRegistrationEvidence.Definition> metadata,
                                    Map<String, BeanRegistrationEvidence.QueryType> queryTypes, SpringBuildContext build, int limit) {
        this.state = state; this.metadata = metadata; this.queryTypes = queryTypes; this.build = build; this.limit = limit;
    }
    static Result evaluate(BeanRegistrationEvidence.Query query, BeanDefinitionState state,
                           Map<BeanDefinitionCandidate.Identity, BeanRegistrationEvidence.Definition> metadata,
                           Map<String, BeanRegistrationEvidence.QueryType> queryTypes, SpringBuildContext build, boolean noParent, int limit) {
        var evaluator = new EndogenousBeanConditions(state, metadata, queryTypes, build, limit);
        try { return evaluator.run(query, noParent); }
        catch (LimitReached | QueryFailed stopped) { return evaluator.result(UNKNOWN); }
    }
    private Result run(BeanRegistrationEvidence.Query query, boolean noParent) {
        var selector = query.selector();
        if (!state.executionEstablished()) reasons.add(PRIOR_EXECUTION_UNKNOWN);
        if (query.metadata() != RegistrationEvent.Completeness.COMPLETE) reasons.add(QUERY_METADATA_INCOMPLETE);
        if (!BeanRegistrationEvidence.valid(query.evidence(), build)) reasons.add(SOURCE_EVIDENCE_MISMATCH);
        if (!selector.containerKey().equals(state.containerKey())) reasons.add(CONTEXT_MISMATCH);
        if (selector.search() == ConditionExpression.Search.ANCESTORS
                || selector.search() == ConditionExpression.Search.ALL && !noParent) reasons.add(HIERARCHY_UNSUPPORTED);
        if (!query.parameterizedContainers().isEmpty()) reasons.add(PARAMETERIZED_CONTAINER_UNSUPPORTED);
        if (selector.predicate() != ConditionExpression.BeanPredicate.MISSING && !query.ignoredTypes().isEmpty())
            reasons.add(QUERY_METADATA_INCOMPLETE);
        if (!selector.annotations().isEmpty() && state.definitions().keySet().stream().anyMatch(state.aliases()::containsKey))
            reasons.add(ALIAS_SHADOW_ANNOTATION_UNSUPPORTED);
        if (selector.predicate() == ConditionExpression.BeanPredicate.SINGLE_CANDIDATE
                && (selector.types().size() != 1 || !selector.names().isEmpty() || !selector.annotations().isEmpty()
                || !query.ignoredTypes().isEmpty())) reasons.add(QUERY_METADATA_INCOMPLETE);
        if (!reasons.isEmpty()) return result(UNKNOWN);

        Map<String, LogicalValue> ignored = new TreeMap<>();
        // Resolve even against an empty registry: resolution itself can fail.
        for (String type : query.ignoredTypes()) typeStatus(BeanRegistrationEvidence.SelectorKind.TYPE, type);
        for (var entry : state.definitions().entrySet()) {
            spend();
            LogicalValue value = FALSE;
            if (!state.aliases().containsKey(entry.getKey())) for (String type : query.ignoredTypes())
                value = value.or(match(entry.getValue(), BeanRegistrationEvidence.SelectorKind.TYPE, type));
            ignored.put(entry.getKey(), value);
        }
        for (String type : selector.types()) selector(BeanRegistrationEvidence.SelectorKind.TYPE, type, ignored);
        for (String annotation : selector.annotations()) selector(BeanRegistrationEvidence.SelectorKind.ANNOTATION, annotation, ignored);
        for (String name : selector.names()) {
            LogicalValue value = name(name).and(ignored.getOrDefault(name, FALSE).not());
            rows.add(new MatchRow("NAME:" + name, Map.of(name, value), value));
        }
        LogicalValue truth;
        if (selector.predicate() == ConditionExpression.BeanPredicate.SINGLE_CANDIDATE) truth = single(rows.getFirst());
        else {
            truth = selector.predicate() == ConditionExpression.BeanPredicate.PRESENT ? TRUE : FALSE;
            for (var row : rows) truth = selector.predicate() == ConditionExpression.BeanPredicate.PRESENT
                    ? truth.and(row.exists()) : truth.or(row.exists());
            if (selector.predicate() == ConditionExpression.BeanPredicate.MISSING) truth = truth.not();
        }
        return result(truth);
    }
    private void selector(BeanRegistrationEvidence.SelectorKind kind, String query, Map<String, LogicalValue> ignored) {
        var status = typeStatus(kind, query);
        Map<String, LogicalValue> candidates = new TreeMap<>(); LogicalValue exists = state.registryClosed() ? FALSE : UNKNOWN;
        if (status == BeanRegistrationEvidence.TypeStatus.ABSENT) exists = FALSE;
        if (status == BeanRegistrationEvidence.TypeStatus.UNKNOWN || status == BeanRegistrationEvidence.TypeStatus.ERROR) exists = UNKNOWN;
        if (!state.registryClosed()) reasons.add(INITIAL_REGISTRY_OPEN);
        for (var entry : state.definitions().entrySet()) {
            spend();
            LogicalValue value = kind == BeanRegistrationEvidence.SelectorKind.TYPE
                    && (state.aliases().containsKey(entry.getKey()) || entry.getKey().startsWith("scopedTarget."))
                    ? FALSE : match(entry.getValue(), kind, query).and(ignored.get(entry.getKey()).not());
            if (value != FALSE) value = value.and(flag(entry.getValue(), "autowire"));
            if (value != FALSE) value = value.and(flag(entry.getValue(), "default"));
            candidates.put(entry.getKey(), value); exists = exists.or(value);
        }
        rows.add(new MatchRow(kind + ":" + query, candidates, exists));
    }
    private LogicalValue name(String requested) {
        if (!spend()) return UNKNOWN;
        String name = requested; boolean factory = name.startsWith("&");
        while (name.startsWith("&")) name = name.substring(1);
        int hops = 0;
        while (state.aliases().containsKey(name)) {
            spend();
            if (++hops > state.aliases().size()) { reasons.add(ALIAS_CYCLE); return UNKNOWN; }
            name = state.aliases().get(name);
        }
        var candidate = state.definitions().get(name);
        if (candidate == null) {
            if (!state.registryClosed()) reasons.add(INITIAL_REGISTRY_OPEN);
            return state.registryClosed() ? FALSE : UNKNOWN;
        }
        // containsBean/containsLocalBean do not filter autowire/default candidate flags.
        return factory ? flag(candidate, "factory") : TRUE;
    }
    private LogicalValue single(MatchRow row) {
        List<String> definite = new ArrayList<>(); List<String> possible = new ArrayList<>();
        row.candidates().forEach((name, value) -> { if (value == TRUE) definite.add(name); if (value != FALSE) possible.add(name); });
        if (possible.isEmpty() && state.registryClosed()) return FALSE;
        if (definite.size() == 1 && possible.size() == 1 && state.registryClosed()) return TRUE;
        int minPrimary = 0, maxPrimary = 0, minNonFallback = 0, maxNonFallback = 0;
        for (String name : possible) {
            var candidate = state.definitions().get(name);
            var primary = flag(candidate, "primary"); var nonFallback = flag(candidate, "fallback").not();
            boolean certain = row.candidates().get(name) == TRUE;
            if (certain && primary == TRUE) minPrimary++;
            if (primary != FALSE) maxPrimary++;
            if (certain && nonFallback == TRUE) minNonFallback++;
            if (nonFallback != FALSE) maxNonFallback++;
        }
        if (minPrimary > 1) return FALSE;
        if (!state.registryClosed()) return UNKNOWN;
        if (minPrimary == 1 && maxPrimary == 1) return TRUE;
        if (maxPrimary > 0) return UNKNOWN;
        // A sole fallback bean matches by the cardinality rule above. Multiple candidates
        // need exactly one non-fallback; do not confuse this with injection binding.
        if (minNonFallback == 1 && maxNonFallback == 1) return TRUE;
        if (minNonFallback > 1 || maxNonFallback == 0 && definite.size() > 1) return FALSE;
        return UNKNOWN;
    }
    private LogicalValue match(BeanDefinitionCandidate.Identity candidate, BeanRegistrationEvidence.SelectorKind kind, String selector) {
        if (!spend()) return UNKNOWN;
        var status = typeStatus(kind, selector);
        if (status == BeanRegistrationEvidence.TypeStatus.UNKNOWN || status == BeanRegistrationEvidence.TypeStatus.ERROR) return UNKNOWN;
        var definition = definition(candidate);
        if (definition != null) for (var match : definition.matches()) {
            spend();
            if (match.kind() == kind && match.selector().equals(selector)) {
                if (!BeanRegistrationEvidence.valid(match.evidence(), build)) { reasons.add(SOURCE_EVIDENCE_MISMATCH); return UNKNOWN; }
                if (status == BeanRegistrationEvidence.TypeStatus.ABSENT) {
                    if (match.value() == TRUE) { reasons.add(QUERY_TYPE_CONFLICT); return UNKNOWN; }
                    return FALSE;
                }
                // Product/factory lookup names can differ by '&'. The first fragment does not
                // collapse those names or silently apply ordinary-definition ignore/count rules.
                if (match.value() != FALSE && definition.factoryBean() != FALSE) { reasons.add(FACTORY_QUERY_UNSUPPORTED); return UNKNOWN; }
                if (match.value() == UNKNOWN) reasons.add(MATCH_EVIDENCE_MISSING);
                return match.value();
            }
        }
        if (status == BeanRegistrationEvidence.TypeStatus.ABSENT) return FALSE;
        reasons.add(MATCH_EVIDENCE_MISSING); return UNKNOWN;
    }
    private BeanRegistrationEvidence.TypeStatus typeStatus(BeanRegistrationEvidence.SelectorKind kind, String selector) {
        spend();
        var fact = queryTypes.get(kind + ":" + selector);
        if (fact == null || fact.status() == BeanRegistrationEvidence.TypeStatus.UNKNOWN) {
            reasons.add(QUERY_TYPE_UNKNOWN); return BeanRegistrationEvidence.TypeStatus.UNKNOWN;
        }
        if (!BeanRegistrationEvidence.valid(fact.evidence(), build)) {
            reasons.add(SOURCE_EVIDENCE_MISMATCH); reasons.add(QUERY_TYPE_UNKNOWN); return BeanRegistrationEvidence.TypeStatus.UNKNOWN;
        }
        if (fact.status() == BeanRegistrationEvidence.TypeStatus.ERROR) {
            reasons.add(QUERY_TYPE_ERROR); throw new QueryFailed();
        }
        return fact.status();
    }
    private BeanRegistrationEvidence.Definition definition(BeanDefinitionCandidate.Identity candidate) {
        var value = metadata.get(candidate);
        if (value != null && !BeanRegistrationEvidence.valid(value.evidence(), build)) {
            reasons.add(SOURCE_EVIDENCE_MISMATCH); return null;
        }
        return value;
    }
    private LogicalValue flag(BeanDefinitionCandidate.Identity candidate, String kind) {
        if (!spend()) return UNKNOWN;
        var definition = definition(candidate);
        LogicalValue value = definition == null ? UNKNOWN : switch (kind) {
            case "autowire" -> definition.autowireCandidate(); case "default" -> definition.defaultCandidate();
            case "primary" -> definition.primary(); case "fallback" -> definition.fallback();
            case "factory" -> definition.factoryBean(); default -> throw new IllegalArgumentException("Unknown metadata flag");
        };
        if (value == UNKNOWN) reasons.add(CANDIDATE_FLAGS_UNKNOWN);
        return value;
    }
    private boolean spend() {
        if (checks >= limit) { reasons.add(REGISTRATION_LIMIT); throw new LimitReached(); }
        checks++; return true;
    }
    private Result result(LogicalValue value) {
        if (reasons.contains(QUERY_TYPE_ERROR) || reasons.contains(QUERY_TYPE_CONFLICT)
                || reasons.contains(QUERY_TYPE_UNKNOWN) || reasons.contains(FACTORY_QUERY_UNSUPPORTED)) value = UNKNOWN;
        return new Result(value, rows, reasons, checks);
    }
}
