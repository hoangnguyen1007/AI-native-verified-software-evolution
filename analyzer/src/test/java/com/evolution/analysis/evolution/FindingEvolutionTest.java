package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.analysis.assessment.*;
import com.evolution.analysis.metrics.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.evolution.analysis.evolution.EvolutionCompatibilityTest.*;
import static com.evolution.analysis.evolution.SnapshotComparison.*;
import static org.junit.jupiter.api.Assertions.*;

class FindingEvolutionTest {
    private static final String POLICY = """
            schema: architecture-policy:v1
            id: evolution.fixture
            version: "1"
            rules:
              - {id: dependency.rule, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], from: {origins: [PROJECT]}, to: {origins: [PROJECT]}}
            """;

    @Test void missingPublicationAndChangedRuleCannotResolveAnObservedFinding() throws Exception {
        var before = graph(1, "tool", "before");
        var after = graph(0, "tool", "after");
        var oldReport = report(before, POLICY);
        var clean = report(after, POLICY);
        assertEquals(1, oldReport.evaluation().findings().size(), () -> oldReport.evaluation().rules().stream().map(r -> r.classification()+" "+
                r.gaps().stream().map(g -> g.reasonCode()).toList()).toList()+" "+
                oldReport.compilation().orElseThrow().problems());
        assertTrue(clean.evaluation().findings().isEmpty());
        assertEquals(List.of("RESOLVED"), lifecycle(compare(before, after, oldReport, clean)));
        assertEquals(List.of("PRESENT_TO_UNKNOWN"), lifecycle(compare(before, after, oldReport, null)));
        var drift = report(after, POLICY.replace("dependency.rule, version: \"1\"", "dependency.rule, version: \"2\""));
        assertEquals(List.of("PRESENT_TO_UNKNOWN"), lifecycle(compare(before, after, oldReport, drift)));
        var severity = report(before, POLICY.replace("severity: ERROR", "severity: WARNING"));
        assertEquals(List.of("PERSISTED"), lifecycle(compare(before, before, oldReport, severity)));
        var cut = new KnowledgeGraph(before.snapshot(),before.contexts(),before.nodes(),
                before.edges().stream().filter(e -> !"graph.depends-on".equals(e.kind())).toList(),before.evidence());
        assertEquals(List.of("PRESENT_TO_UNKNOWN"),lifecycle(compare(before,cut,oldReport,report(cut,POLICY))));
        var original=oldReport.evaluation();
        var rule=original.rules().getFirst();
        var incomplete=new PolicyEvaluation.RuleResult(rule.rule(),com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,
                rule.classification(),rule.query(),new com.evolution.analysis.query.ArchitectureCondition.RulePredicate(List.of(),false),
                rule.region(),List.of(),List.of());
        var truncated=new PolicyEvaluation.Result(original.compilation(),original.mapping(),original.graph(),original.context(),
                original.limits(),com.evolution.analysis.contract.assessment.AssessmentStatus.PARTIAL,List.of(incomplete),List.of(),List.of());
        var page=new EvolutionReport(truncated,Optional.empty(),Optional.empty(),oldReport.compilation(),oldReport.mapping());
        assertEquals(List.of("PRESENT_TO_UNKNOWN"),lifecycle(compare(before,before,oldReport,page)));
    }

    @Test void boundedHistoryRequiresTheSameProvedCleanOperandInBothIntervals() throws Exception {
        var before=graph(1,"tool","first");
        var clean=graph(0,"tool","second");
        var returned=graph(2,"tool","third");
        var a=report(before,POLICY);var b=report(clean,POLICY);var c=report(returned,POLICY);
        var first=historyProof(before,clean,List.of(before.identity()));
        var second=historyProof(clean,returned,List.of(clean.identity()));
        var replay=history(before,clean,returned,a,b,c,first,second);
        var rows=items(replay,Section.HISTORY);
        var reintroduced=rows.stream().filter(r -> "REINTRODUCED".equals(r.get("transition"))).toList();
        assertEquals(1,reintroduced.size(),() -> rows.stream().map(r -> Arrays.asList(r.get("kind"),r.get("transition"),
                r.get("continuity"),r.get("reason"))).toList().toString());
        assertEquals(before.identity().value(),reintroduced.getFirst().get("earliestObserved"));
        assertNotNull(reintroduced.getFirst().get("previousAbsenceProof"));
        var absentReport=history(before,clean,returned,a,null,c,first,second);
        assertTrue(items(absentReport,Section.HISTORY).stream().noneMatch(r -> "REINTRODUCED".equals(r.get("transition"))));
        var merge=historyProof(clean,returned,List.of(clean.identity(),before.identity()));
        assertTrue(items(history(before,clean,returned,a,b,c,first,merge),Section.HISTORY).stream()
                .noneMatch(r -> "REINTRODUCED".equals(r.get("transition"))));
    }

    @Test void metricsUseExactDenominatorsAndScoreKeepsCalibrationAndModelDrift() throws Exception {
        var empty=graph(0,"tool","empty");
        var present=graph(1,"tool","present");
        var duplicate=graph(2,"tool","duplicate");
        var metricPolicy=POLICY+"""
              - {id: cycle.rule, version: "1", kind: ACYCLIC, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], within: {origins: [PROJECT]}}
            """;
        var a=report(empty,metricPolicy);var b=report(present,metricPolicy);var c=report(duplicate,metricPolicy);
        var selection=new Selection(Set.of("MAIN"),Set.of(),Set.of(),false,false,false,true,true);
        var reportMap=List.of(a,b,c).stream().collect(java.util.stream.Collectors.toMap(EvolutionReport::identity,r->r));
        var pair=compareSelected(empty,present,a,b,selection,reader(reportMap),EvolutionSnapshot.memory(empty,present),Optional.empty(),Map.of());
        var density=items(pair,Section.METRICS).stream().filter(r -> "metric.density".equals(r.get("metric"))).findFirst().orElseThrow();
        var change=com.evolution.analysis.graph.GraphJson.object(density.get("delta"));
        assertEquals(new java.math.BigDecimal("0.5"),change.get("amount"));
        assertEquals(Map.of("numerator",java.math.BigDecimal.ONE,"denominator",new java.math.BigDecimal("2")),change.get("exact"));
        assertNull(change.get("relativePercent"));
        var withheld=items(pair,Section.SCORES).getFirst();
        assertNull(withheld.get("delta"));
        var dedup=compareSelected(present,duplicate,b,c,selection,reader(reportMap),EvolutionSnapshot.memory(present,duplicate),Optional.empty(),Map.of());
        var score=items(dedup,Section.SCORES).getFirst();
        assertEquals("COMPARABLE",score.get("disposition"),() -> String.valueOf(score.get("reasons")));
        assertEquals(java.math.BigDecimal.ZERO,score.get("delta"));
        for(var name:List.of("left","right")) {
            var value=com.evolution.analysis.graph.GraphJson.object(com.evolution.analysis.graph.GraphJson.object(score.get(name)).get("score"));
            assertEquals(new java.math.BigDecimal("67.5"),value.get("value"));
            assertEquals("PROVISIONAL",value.get("calibration"));
        }
        var original=c.assessment().orElseThrow();
        var model=original.model().orElseThrow();
        var changedModel=new ArchitectureScoreModel(new com.evolution.analysis.contract.common.VersionedIdentifier(model.model().id(),"changed"),
                model.dimensions(),model.severityPenalties());
        var changed=new ArchitectureAssessment.Result(original.context(),original.policyMetrics(),Optional.of(changedModel),original.formula(),
                original.regionPolicy(),original.calibration(),original.limits(),original.status(),original.score(),original.dimensions(),
                original.weightedNumerator(),original.applicableWeight(),original.structuralInputs(),original.confidence(),original.gaps(),
                original.reasons(),original.computedAt());
        var drift=new EvolutionReport(c.evaluation(),c.metrics(),Optional.of(changed),c.compilation(),c.mapping());
        var modified=new HashMap<>(reportMap);modified.put(drift.identity(),drift);
        var result=compareSelected(present,duplicate,b,drift,selection,reader(modified),EvolutionSnapshot.memory(present,duplicate),Optional.empty(),Map.of());
        var drifted=items(result,Section.SCORES).getFirst();
        assertNull(drifted.get("delta"));
        assertTrue(((List<?>)drifted.get("reasons")).contains("SCORE_MODEL_DRIFT"));

        var densityOperand=original.structuralInputs().stream().filter(r->r.metric().id().equals("metric.density")).findFirst().orElseThrow();
        var driftMetric=new ArchitectureMetric.Result(densityOperand.metric(),ContentDigest.sha256Utf8("changed definition"),densityOperand.context(),
                densityOperand.subject(),densityOperand.status(),densityOperand.value(),densityOperand.ratio(),densityOperand.details(),
                densityOperand.evidence(),densityOperand.gaps(),densityOperand.limitations(),densityOperand.telemetry());
        var malformed=new ArchitectureAssessment.Result(original.context(),original.policyMetrics(),original.model(),original.formula(),original.regionPolicy(),
                original.calibration(),original.limits(),original.status(),original.score(),original.dimensions(),original.weightedNumerator(),
                original.applicableWeight(),List.of(driftMetric),original.confidence(),original.gaps(),original.reasons(),original.computedAt());
        var changedMetric=new EvolutionReport(c.evaluation(),c.metrics(),Optional.of(malformed),c.compilation(),c.mapping());
        modified.put(changedMetric.identity(),changedMetric);
        var metricResult=compareSelected(present,duplicate,b,changedMetric,selection,reader(modified),
                EvolutionSnapshot.memory(present,duplicate),Optional.empty(),Map.of());
        var driftedDensity=items(metricResult,Section.METRICS).stream().filter(r->"metric.density".equals(r.get("metric"))).findFirst().orElseThrow();
        assertNull(driftedDensity.get("delta"));
        assertTrue(((List<?>)driftedDensity.get("reasons")).contains("METRIC_DEFINITION_DRIFT"));
        assertNull(items(metricResult,Section.SCORES).getFirst().get("delta"));
    }

    static EvolutionReport report(KnowledgeGraph graph, String policy) {
        var service = new ArchitectureQueryService(graph);
        var context = graph.contexts().stream().filter(c -> graph.nodes(c.identity()).stream().anyMatch(n -> n.kind().equals("TYPE")))
                .findFirst().orElseThrow().identity();
        var compilation = new PolicyCompiler().compile(policy.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
        var plan = new PolicyGraphMapper().bind(compilation, service, context, PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compilation, plan, service, PolicyEvaluation.Limits.defaults());
        var metrics=new PolicyMetricsService().compute(compilation,plan,evaluation,service,PolicyMetrics.Limits.defaults());
        var score=new ArchitectureAssessmentService().assess(metrics,evaluation,service,
                Optional.of(ArchitectureScoreModel.candidate(com.evolution.analysis.query.ArchitectureQuery.Scope.TYPE)),
                plan.limits().query(),ArchitectureMetric.Limits.defaults(),ArchitectureAssessment.Limits.defaults());
        return new EvolutionReport(evaluation, Optional.of(metrics), Optional.of(score), Optional.of(compilation), Optional.of(plan));
    }

    private static List<Object> lifecycle(Result result) throws Exception {
        return items(result, Section.FINDINGS).stream().filter(r -> "FINDING_TRANSITION".equals(r.get("kind")))
                .map(r -> r.get("transition")).toList();
    }

    private static Result compare(KnowledgeGraph before, KnowledgeGraph after, EvolutionReport left, EvolutionReport right) throws Exception {
        var reports=new HashMap<ContentDigest,EvolutionReport>();
        if(left!=null)reports.put(left.identity(),left);
        if(right!=null)reports.put(right.identity(),right);
        var selection=new Selection(Set.of("MAIN"),Set.of(),Set.of(),false,false,true,false,false);
        return compareSelected(before,after,left,right,selection,reader(reports),EvolutionSnapshot.memory(before,after),Optional.empty(),Map.of());
    }

    private static Result compareSelected(KnowledgeGraph before,KnowledgeGraph after,EvolutionReport left,EvolutionReport right,
                                          Selection selection,ReportReader reader,EvolutionSnapshot.Reader graphs,Optional<History> history,
                                          Map<ContentDigest,HistoryProof> proofs) throws Exception {
        var refs = new ArrayList<ReportRef>();
        for (var side : Side.values()) {
            var report = side == Side.LEFT ? left : right;
            if (report == null) continue;
            refs.add(new ReportRef(side, report.identity(), report.evaluation().context()));
        }
        return SnapshotComparison.compare(new Request(REPO, before.identity(), after.identity(), Mode.STRICT, selection),
                graphs,refs,reader,List.of(),(b,s,o,c)->ReportStatus.UNAVAILABLE,history,(id,c)->Optional.ofNullable(proofs.get(id)),
                EvolutionIndex.memory(),2,()->false);
    }

    private static ReportReader reader(Map<ContentDigest,EvolutionReport> reports) {
        return new ReportReader() {
                    public ReportStatus verify(ContentDigest graph, ReportRef ref, java.util.function.BooleanSupplier stop) {
                        return ReportStatus.AVAILABLE;
                    }
                    public Optional<EvolutionReport> read(ContentDigest graph, ReportRef ref, java.util.function.BooleanSupplier stop) {
                        return Optional.ofNullable(reports.get(ref.identity()));
                    }
                };
    }

    private static HistoryProof historyProof(KnowledgeGraph a,KnowledgeGraph b,List<ContentDigest> parents) {
        return new HistoryProof(HistoryBasis.AUTHENTICATED_PARENT,REPO,a.identity(),b.identity(),a.snapshot(),b.snapshot(),parents,true,
                ContentDigest.sha256Utf8("authored parent evidence"));
    }

    private static Result history(KnowledgeGraph a,KnowledgeGraph b,KnowledgeGraph c,EvolutionReport x,EvolutionReport y,EvolutionReport z,
                                  HistoryProof first,HistoryProof second) throws Exception {
        var revisions=new ArrayList<HistoryStep>();
        revisions.add(step(a,x,null));revisions.add(step(b,y,first));revisions.add(step(c,z,second));
        var reports=new HashMap<ContentDigest,EvolutionReport>();
        for(var r:Arrays.asList(x,y,z))if(r!=null)reports.put(r.identity(),r);
        var selection=new Selection(Set.of("MAIN"),Set.of(),Set.of(),false,false,true,false,false);
        return compareSelected(b,c,y,z,selection,reader(reports),EvolutionSnapshot.memory(a,b,c),
                Optional.of(new History(revisions)),Map.of(first.identity(),first,second.identity(),second));
    }

    private static HistoryStep step(KnowledgeGraph graph,EvolutionReport report,HistoryProof proof) {
        return new HistoryStep(new HistoryRevision(graph.identity(),report==null?List.of():
                List.of(new HistoryReport(report.identity(),report.evaluation().context()))),Optional.ofNullable(proof).map(HistoryProof::identity));
    }
}
