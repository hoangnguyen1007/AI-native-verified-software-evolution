package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.query.*;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Associates incoming structural dependencies with configuration-qualified policy rules and findings. */
public final class PolicyImpactService {
    public static final VersionedIdentifier VERSION = PolicyImpactRecord.VERSION;

    private final ArchitectureQueryService queries;

    public PolicyImpactService() {
        this(null);
    }

    public PolicyImpactService(ArchitectureQueryService queries) {
        this.queries = queries;
    }

    public PolicyImpactRecord.Result evaluate(Request incomingRequest,
                                              PolicyEvaluation.Result evaluation,
                                              String changedEntity) {
        if (this.queries == null) {
            throw new IllegalStateException("ArchitectureQueryService must be supplied");
        }
        return evaluate(this.queries, incomingRequest, evaluation, changedEntity);
    }

    public PolicyImpactRecord.Result evaluate(ArchitectureQueryService queries,
                                              Request incomingRequest,
                                              PolicyEvaluation.Result evaluation,
                                              String changedEntity) {
        Objects.requireNonNull(queries, "queries");
        Objects.requireNonNull(incomingRequest, "incomingRequest");
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(changedEntity, "changedEntity");

        if (incomingRequest.direction() != Direction.INCOMING) {
            throw new IllegalArgumentException("Impact analysis requires INCOMING request direction");
        }

        // 1. Check if changedEntity exists in the query population
        var entityCheck = queries.entity(incomingRequest, changedEntity);
        var gaps = new ArrayList<CapabilityGapRecord>();
        if (entityCheck.items().isEmpty()) {
            gaps.addAll(entityCheck.gaps());
            return new PolicyImpactRecord.Result(
                changedEntity,
                List.of(),
                List.of(changedEntity),
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                Optional.empty(),
                AssessmentStatus.PARTIAL,
                List.copyOf(gaps)
            );
        }

        // 2. Perform structural impact traversal: incoming reachability
        var structuralResult = queries.impact(incomingRequest, changedEntity);
        gaps.addAll(structuralResult.gaps());
        gaps.addAll(evaluation.gaps());

        // Collect all affected entities (changedEntity + dependent vertices along reachable paths)
        var affectedEntitiesSet = new TreeSet<String>();
        affectedEntitiesSet.add(changedEntity);
        for (var path : structuralResult.items()) {
            affectedEntitiesSet.addAll(path.vertices());
            for (var edge : path.edges()) {
                affectedEntitiesSet.add(edge.source());
                affectedEntitiesSet.add(edge.target());
            }
        }
        var affectedEntities = List.copyOf(affectedEntitiesSet);

        // 3. Match findings against affected entities
        var directFindings = new ArrayList<PolicyImpactRecord.FindingImpact>();
        var transitiveFindings = new ArrayList<PolicyImpactRecord.FindingImpact>();

        for (var finding : evaluation.findings()) {
            var bundle = finding.bundle();
            var findingVertices = new TreeSet<String>();
            findingVertices.addAll(bundle.path());
            for (var edge : bundle.relationships()) {
                findingVertices.add(edge.source());
                findingVertices.add(edge.target());
            }
            for (var support : bundle.supports()) {
                findingVertices.add(support.source());
                findingVertices.add(support.target());
            }

            // Check intersection with affectedEntities
            var matched = new TreeSet<String>();
            for (var v : findingVertices) {
                if (affectedEntitiesSet.contains(v)) {
                    matched.add(v);
                }
            }

            if (!matched.isEmpty()) {
                boolean isDirect = matched.contains(changedEntity);
                var impact = new PolicyImpactRecord.FindingImpact(
                    finding.identity(),
                    bundle.identity(),
                    finding.rule().id(),
                    finding.rule().severity(),
                    finding.classification(),
                    isDirect,
                    List.copyOf(matched),
                    bundle.path(),
                    bundle.region().witness(),
                    bundle.region().counterWitness()
                );
                if (isDirect) {
                    directFindings.add(impact);
                } else {
                    transitiveFindings.add(impact);
                }
            }
        }

        directFindings.sort(Comparator.comparing(f -> f.findingIdentity().value()));
        transitiveFindings.sort(Comparator.comparing(f -> f.findingIdentity().value()));

        // 4. Group by rule for RuleImpactSummary
        var ruleKinds = new HashMap<String, ArchitecturePolicy.Kind>();
        var ruleSeverities = new HashMap<String, ArchitecturePolicy.Severity>();
        var directCounts = new HashMap<String, Integer>();
        var transitiveCounts = new HashMap<String, Integer>();

        for (var f : directFindings) {
            ruleKinds.put(f.ruleId(), findRuleKind(evaluation, f.ruleId()));
            ruleSeverities.put(f.ruleId(), f.severity());
            directCounts.merge(f.ruleId(), 1, Integer::sum);
        }
        for (var f : transitiveFindings) {
            ruleKinds.put(f.ruleId(), findRuleKind(evaluation, f.ruleId()));
            ruleSeverities.put(f.ruleId(), f.severity());
            transitiveCounts.merge(f.ruleId(), 1, Integer::sum);
        }

        var allRuleIds = new TreeSet<String>();
        allRuleIds.addAll(directCounts.keySet());
        allRuleIds.addAll(transitiveCounts.keySet());

        var affectedRules = new ArrayList<PolicyImpactRecord.RuleImpactSummary>();
        for (var ruleId : allRuleIds) {
            affectedRules.add(new PolicyImpactRecord.RuleImpactSummary(
                ruleId,
                ruleKinds.get(ruleId),
                ruleSeverities.get(ruleId),
                directCounts.getOrDefault(ruleId, 0),
                transitiveCounts.getOrDefault(ruleId, 0)
            ));
        }

        // 5. Compute worstSeverity and highestQuantifier
        Optional<ArchitecturePolicy.Severity> worstSeverity = Optional.empty();
        Optional<Classification> highestQuantifier = Optional.empty();

        var allImpactFindings = new ArrayList<PolicyImpactRecord.FindingImpact>();
        allImpactFindings.addAll(directFindings);
        allImpactFindings.addAll(transitiveFindings);

        if (!allImpactFindings.isEmpty()) {
            ArchitecturePolicy.Severity worst = ArchitecturePolicy.Severity.INFO;
            for (var f : allImpactFindings) {
                if (severityRank(f.severity()) > severityRank(worst)) {
                    worst = f.severity();
                }
            }
            worstSeverity = Optional.of(worst);

            Classification highest = Classification.UNKNOWN;
            for (var f : allImpactFindings) {
                if (quantifierRank(f.classification()) > quantifierRank(highest)) {
                    highest = f.classification();
                }
            }
            highestQuantifier = Optional.of(highest);
        }

        AssessmentStatus status = (gaps.isEmpty() && structuralResult.status() == AssessmentStatus.COMPLETE
            && evaluation.status() == AssessmentStatus.COMPLETE)
            ? AssessmentStatus.COMPLETE : AssessmentStatus.PARTIAL;

        return new PolicyImpactRecord.Result(
            changedEntity,
            structuralResult.items(),
            affectedEntities,
            List.copyOf(directFindings),
            List.copyOf(transitiveFindings),
            List.copyOf(affectedRules),
            worstSeverity,
            highestQuantifier,
            status,
            List.copyOf(gaps)
        );
    }

    private static ArchitecturePolicy.Kind findRuleKind(PolicyEvaluation.Result evaluation, String ruleId) {
        for (var ruleResult : evaluation.rules()) {
            if (ruleResult.rule().header().id().equals(ruleId)) {
                return ruleResult.rule().kind();
            }
        }
        return ArchitecturePolicy.Kind.FORBIDDEN_DEPENDENCY;
    }

    private static int severityRank(ArchitecturePolicy.Severity s) {
        return switch (s) {
            case INFO -> 1;
            case WARNING -> 2;
            case ERROR -> 3;
        };
    }

    private static int quantifierRank(Classification c) {
        return switch (c) {
            case UNKNOWN -> 1;
            case MAY -> 2;
            case MUST -> 3;
            case NEVER -> 0;
        };
    }
}
