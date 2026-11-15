package com.evolution.analysis.evolution;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.assessment.ArchitectureScoreModel;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.metrics.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.query.ArchitectureQuery;
import java.io.IOException;
import java.math.*;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.ReportEvolution.Operand;
import static com.evolution.analysis.evolution.SnapshotComparison.Section;

/** Compares published operands, preserving applicability and the original score explanation. */
final class AssessmentEvolution {
    private record Numeric(ContentDigest identity,Object definition,Object scope,AssessmentStatus status,Optional<MetricValue> value,
                           Optional<RatioEvidence> ratio,boolean closed,Object observation) {}
    private final EvolutionRun run;
    private final String scope;
    private final Operand left,right;

    AssessmentEvolution(EvolutionRun run,String scope,Operand left,Operand right) {
        this.run=run;this.scope=scope;this.left=left;this.right=right;
    }

    void compare(Set<String> base,List<ContentDigest> refs,ReportEvolution owner) throws IOException {
        var numericBase=new TreeSet<>(base);
        if(left.partition()!=null && right.partition()!=null && !left.partition().sameClosedDomain(right.partition()))
            numericBase.add("NUMERIC_CONFIGURATION_DOMAIN_UNPROVED");
        var policy=policyCompatibility();
        var a=metrics(left);var b=metrics(right);
        if (run.request.selection().metrics()) {
            var all=new TreeSet<>(a.keySet());all.addAll(b.keySet());
            var metricReasons=new TreeSet<>(numericBase);
            if (all.isEmpty()) metricReasons.add("METRIC_OPERANDS_UNAVAILABLE");
            for (var key : all) {
                EvolutionSnapshot.check(run.cancelled);
                var x=a.get(key);var y=b.get(key);
                var reasons=new TreeSet<>(numericBase);
                if (key.startsWith("policy|") && !key.endsWith("policy-audit") && !key.endsWith("policy-waivers")) reasons.addAll(policy);
                check(x,y,reasons);
                var change=reasons.isEmpty()&&x.value().isPresent()&&y.value().isPresent()?arithmetic(x,y):null;
                run.put(Section.METRICS,scope+"|"+key,row("scope",scope,"metric",key.substring(key.indexOf('|')+1),
                        "family",key.substring(0,key.indexOf('|')),"left",metricRef(left,x),"right",metricRef(right,y),
                        "disposition",disposition(reasons,change),"delta",change,"reasons",reasons));
                if (reasons.isEmpty()) run.comparable();
                metricReasons.addAll(reasons);
            }
            owner.qualify(scope,"METRIC",left,right,refs,metricReasons);
        }
        if (run.request.selection().score()) {
            var reasons=new TreeSet<>(numericBase);reasons.addAll(policy);
            score(score(left),score(right),a,b,reasons);
            owner.qualify(scope,"SCORE",left,right,refs,reasons);
        }
    }

    private Set<String> policyCompatibility() {
        var reasons=new TreeSet<String>();
        if (left.report()==null || right.report()==null) return Set.of("POLICY_REPORT_UNAVAILABLE");
        var a=FindingEvolution.rules(left);var b=FindingEvolution.rules(right);
        if (a.isEmpty() || b.isEmpty()) reasons.add("RULE_DENOMINATOR_UNAVAILABLE");
        if (!a.keySet().equals(b.keySet())) reasons.add("RULE_POPULATION_DRIFT");
        if (!left.report().evaluation().limits().equals(right.report().evaluation().limits())) reasons.add("EVALUATION_LIMITS_DRIFT");
        for (var key : a.keySet()) if (b.containsKey(key)) {
            var x=a.get(key);var y=b.get(key);
            var l=FindingEvolution.ruleKey(left,x);var r=FindingEvolution.ruleKey(right,y);
            if (l==null || r==null) reasons.add("POLICY_NAMESPACE_UNPROVED");
            else if (!l.equals(r)) reasons.add("RULE_DRIFT");
            if (x.rule().header().severity()!=y.rule().header().severity()) reasons.add("SEVERITY_DRIFT");
            var lp=FindingEvolution.request(left,x);var rp=FindingEvolution.request(right,y);
            if (lp==null || rp==null) reasons.add("RULE_PROJECTION_UNAVAILABLE");
            else if (!FindingEvolution.projection(lp).equals(FindingEvolution.projection(rp))) reasons.add("RULE_PROJECTION_DRIFT");
        }
        return reasons;
    }

    private Map<String,Numeric> metrics(Operand side) throws IOException {
        var result=new TreeMap<String,Numeric>();
        if (side.report()==null) return result;
        side.report().metrics().ifPresent(report -> {
            var context=report.context();
            var counts=report.counts();
            var declared=side.report().evaluation().rules();
            var ruleIds=new HashSet<>(declared.stream().map(r -> r.rule().identity()).toList());
            boolean accounted=counts.declaredRules()==declared.size() && ruleIds.size()==declared.size()
                    && counts.coverage().size()==declared.size()
                    && ruleIds.equals(new HashSet<>(counts.coverage().stream().map(PolicyMetrics.Coverage::rule).toList()));
            boolean bound=context.snapshot().equals(side.input().header.snapshot()) && context.partition().equals(side.id());
            var policyScope=row("limits",context.limits(),"requests",context.requests().stream().map(FindingEvolution::projection).toList());
            for (var metric : report.metrics()) {
                var definition=metric.definition();
                boolean known=PolicyMetrics.catalog().contains(definition);
                var observation=row("definition",definition,"definitionDigest",definition.identity(),"context",context,
                        "status",metric.status(),"value",metric.value(),"ratio",metric.ratio(),"reasons",metric.reasons(),
                        "declaredRules",counts.declaredRules(),"completeRules",counts.completeRules(),"completeBundles",counts.completeBundles(),
                        "ruleQuantifiers",counts.ruleQuantifiers(),"findingQuantifiers",counts.findingQuantifiers(),
                        "gaps",metric.gaps().stream().map(g -> g.gapIdentity()).toList());
                add(result,"policy|"+definition.id(),new Numeric(metric.identity(),definition.identity(),policyScope,
                        metric.status(),metric.value(),metric.ratio(),bound&&known&&accounted&&metric.context().equals(context)
                        && (metric.gaps().isEmpty() || definition.id().equals("metric.policy-waivers")
                                && metric.status()==AssessmentStatus.NOT_APPLICABLE),observation));
            }
        });
        var score=side.report().assessment().orElse(null);
        if(score!=null) for(var metric:score.structuralInputs())
            add(result,"structural|"+metric.metric().id(),structural(side,metric));
        return result;
    }

    private static void add(Map<String,Numeric> rows,String key,Numeric numeric) {
        var previous=rows.putIfAbsent(key,numeric);
        if (previous!=null) rows.put(key,new Numeric(previous.identity(),previous.definition(),previous.scope(),
                AssessmentStatus.WITHHELD,Optional.empty(),Optional.empty(),false,
                row("reason","AMBIGUOUS_METRIC_OPERANDS","observations",List.of(previous.observation(),numeric.observation()))));
    }

    private Numeric structural(Operand side,ArchitectureMetric.Result result) throws IOException {
        var c=result.context();var p=c.projection();
        var catalog=MetricCatalog.entries().stream().filter(e -> e.metric().id().equals(result.metric().id())).findFirst();
        boolean known=catalog.filter(e -> e.identity().equals(result.definition()) && e.metric().equals(result.metric())
                && e.population().equals(c.population()) && result.value().stream().allMatch(v -> v.unit().equals(e.unit()))).isPresent();
        boolean bound=c.graph().equals(side.input().graph) && c.partition().equals(side.id())
                && p.graph().equals(side.input().graph) && p.context().equals(side.id()) && result.subject().equals(side.id())
                && c.analyses().equals(side.input().analysis(side.id()).stream().toList());
        boolean closed=result.gaps().isEmpty() && !truncated(result.details());
        if (p.projection()==ArchitectureQuery.Projection.REALIZED) closed=false; // needs an explicit realization correspondence proof
        if (side.partition()!=null && side.partition().spring==null && p.relationshipKinds().contains("graph.depends-on"))
            closed&=side.partition().dependenciesClosed(side.context());
        if (side.partition()!=null && side.partition().spring!=null)
            closed&=side.partition().spring.complete && side.partition().spring.domain.supported;
        if ("metric.density".equals(result.metric().id()) && result.ratio().isPresent() && result.details().containsKey("vertices")) {
            var n=BigDecimal.valueOf(number(result.details().get("vertices")));
            var pairs=BigDecimal.valueOf(number(result.details().get("distinctInternalPairs")));
            var ratio=result.ratio().orElseThrow();
            closed&=n.signum()>=0 && pairs.signum()>=0 && pairs.compareTo(ratio.denominator())<=0
                    && ratio.denominator().compareTo(n.multiply(n.subtract(BigDecimal.ONE)))==0 && ratio.numerator().compareTo(pairs)==0;
        }
        var projection=row("query",FindingEvolution.projection(p),"population",c.population(),"limits",c.limits(),"layers",c.layers());
        return new Numeric(result.identity(),List.of(result.metric(),result.definition()),projection,result.status(),result.value(),result.ratio(),
                known&&bound&&closed,result.semanticForm());
    }

    private static boolean truncated(Map<String,Object> details) {
        if (!(details.get("queryExecutions") instanceof List<?> executions)) return false;
        for (var raw : executions) {
            var bound=obj(raw);
            if (!Boolean.FALSE.equals(bound.get("truncated")) || !List.of().equals(bound.get("frontier"))) return true;
        }
        return false;
    }

    private static void check(Numeric x,Numeric y,Set<String> reasons) {
        if (x==null || y==null) { reasons.add("METRIC_OPERAND_UNAVAILABLE");return; }
        if (!x.definition().equals(y.definition())) reasons.add("METRIC_DEFINITION_DRIFT");
        if (!x.scope().equals(y.scope())) reasons.add("METRIC_PROJECTION_DRIFT");
        if (!x.closed() || !y.closed()) reasons.add("METRIC_POPULATION_UNPROVED");
        if (x.status()==AssessmentStatus.NOT_APPLICABLE && y.status()==AssessmentStatus.NOT_APPLICABLE) return;
        if (x.status()==AssessmentStatus.NOT_APPLICABLE || y.status()==AssessmentStatus.NOT_APPLICABLE) {
            reasons.add("METRIC_APPLICABILITY_CHANGED");return;
        }
        if (x.status()!=AssessmentStatus.COMPLETE || y.status()!=AssessmentStatus.COMPLETE || x.value().isEmpty() || y.value().isEmpty()) {
            reasons.add("METRIC_EVIDENCE_INCOMPLETE");return;
        }
        if (!x.value().orElseThrow().unit().equals(y.value().orElseThrow().unit()) || x.ratio().isPresent()!=y.ratio().isPresent())
            reasons.add("METRIC_UNIT_OR_OPERAND_DRIFT");
        if (!validRatio(x) || !validRatio(y)) reasons.add("METRIC_ARITHMETIC_UNPROVED");
    }

    private static boolean validRatio(Numeric operand) {
        if (operand.ratio().isEmpty()) return true;
        var r=operand.ratio().orElseThrow();
        return r.denominator().signum()>0 && operand.value().orElseThrow().amount().compareTo(
                ArchitectureMetric.divide(r.numerator(),r.denominator()))==0;
    }

    private static Object metricRef(Operand side,Numeric metric) {
        return row("input",side.referenceRow(),"identity",metric==null?null:metric.identity(),
                "observation",metric==null?null:metric.observation());
    }

    private static String disposition(Set<String> reasons,Object delta) {
        return reasons.isEmpty()?(delta==null?"NOT_APPLICABLE":"COMPARABLE"):
                reasons.stream().anyMatch(r -> r.endsWith("_DRIFT") || r.endsWith("_CHANGED"))?"QUALIFIED":"WITHHELD";
    }

    private static Object arithmetic(Numeric x,Numeric y) {
        var a=x.value().orElseThrow();var b=y.value().orElseThrow();
        var l=x.ratio().orElse(new RatioEvidence(a.amount(),BigDecimal.ONE));
        var r=y.ratio().orElse(new RatioEvidence(b.amount(),BigDecimal.ONE));
        var numerator=r.numerator().multiply(l.denominator()).subtract(l.numerator().multiply(r.denominator()));
        var denominator=l.denominator().multiply(r.denominator());
        var scale=Math.max(0,Math.max(numerator.scale(),denominator.scale()));
        var n=numerator.movePointRight(scale).toBigIntegerExact();
        var d=denominator.movePointRight(scale).toBigIntegerExact();
        var divisor=n.gcd(d);
        var exact=new RatioEvidence(new BigDecimal(n.divide(divisor)),new BigDecimal(d.divide(divisor)));
        return row("unit",a.unit(),"amount",ArchitectureMetric.divide(numerator,denominator),
                "exact",exact,"rounding","SCALE_12_HALF_EVEN",
                "relativePercent",l.numerator().signum()==0?null:
                        ArchitectureMetric.divide(numerator.multiply(BigDecimal.valueOf(100)),r.denominator().multiply(l.numerator())),
                "denominatorChange",x.ratio().isEmpty()?null:r.denominator().subtract(l.denominator()));
    }

    private static ArchitectureAssessment.Result score(Operand side) {
        return side.report()==null?null:side.report().assessment().orElse(null);
    }

    private void score(ArchitectureAssessment.Result a,ArchitectureAssessment.Result b,Map<String,Numeric> before,
                       Map<String,Numeric> after,Set<String> reasons) throws IOException {
        var dimensions=new TreeSet<String>();
        var ad=dimensions(a);var bd=dimensions(b);dimensions.addAll(ad.keySet());dimensions.addAll(bd.keySet());
        if (a==null || b==null) reasons.add("SCORE_OPERAND_UNAVAILABLE");
        else {
            if (a.model().isEmpty() || b.model().isEmpty()) reasons.add("SCORE_MODEL_UNAVAILABLE");
            else if (!a.model().orElseThrow().identity().equals(b.model().orElseThrow().identity()))
                reasons.add("SCORE_MODEL_DRIFT");
            if (!List.of(a.formula(),a.regionPolicy(),a.calibration(),a.limits()).equals(List.of(b.formula(),b.regionPolicy(),b.calibration(),b.limits())))
                reasons.add("SCORE_FORMULA_OR_LIMITS_DRIFT");
            if (!a.confidence().sufficient() || !b.confidence().sufficient()) reasons.add("SCORE_EVIDENCE_INCOMPLETE");
            if (!ad.keySet().equals(bd.keySet()) || ad.entrySet().stream().anyMatch(e -> bd.containsKey(e.getKey()) &&
                    e.getValue().status()!=bd.get(e.getKey()).status())) reasons.add("SCORE_DIMENSION_APPLICABILITY_CHANGED");
            if (!validScore(a,ad) || !validScore(b,bd)) reasons.add("SCORE_ARITHMETIC_UNPROVED");
            var keys=new TreeSet<>(before.keySet());keys.addAll(after.keySet());
            for (var key : keys) if (key.startsWith("structural|")) check(before.get(key),after.get(key),reasons);
        }
        BigDecimal delta=reasons.isEmpty()&&a.score().isPresent()&&b.score().isPresent()?
                b.score().orElseThrow().subtract(a.score().orElseThrow()):null;
        var explanation=new ArrayList<Object>();
        for (var id : dimensions) {
            var x=ad.get(id);var y=bd.get(id);
            explanation.add(row("dimension",id,"left",x,"right",y,
                    "disposition",x==null?"RIGHT_ONLY":y==null?"LEFT_ONLY":x.status()==y.status()?"PAIRED":"APPLICABILITY_CHANGED",
                    "delta",delta==null||x==null||y==null||x.score().isEmpty()||y.score().isEmpty()?null:
                            y.score().orElseThrow().subtract(x.score().orElseThrow())));
        }
        run.put(Section.SCORES,scope,row("scope",scope,"left",scoreRef(left,a),"right",scoreRef(right,b),
                "disposition",disposition(reasons,delta),"delta",delta,"unit","score-points","dimensions",explanation,"reasons",reasons));
    }

    private static Map<String,ArchitectureAssessment.Dimension> dimensions(ArchitectureAssessment.Result score) {
        var rows=new TreeMap<String,ArchitectureAssessment.Dimension>();
        if (score!=null) for (var d : score.dimensions()) rows.put(d.id(),d);
        return rows;
    }

    private static boolean validScore(ArchitectureAssessment.Result score,Map<String,ArchitectureAssessment.Dimension> dimensions) {
        if (score.status()==AssessmentStatus.NOT_APPLICABLE) return score.applicableWeight().signum()==0;
        if (score.score().isEmpty() || score.applicableWeight().signum()<=0 || dimensions.size()!=score.dimensions().size()
                || !score.confidence().sufficient() || score.model().isEmpty()
                || !score.formula().equals(ArchitectureScoreModel.FORMULA)
                || !score.regionPolicy().equals(ArchitectureAssessment.REGION_POLICY)
                || !score.confidence().gaps().isEmpty()
                || score.gaps().stream().anyMatch(g->!"SCORE_MODEL_NOT_APPROVED".equals(g.reasonCode()))) return false;
        var model=score.model().orElseThrow();
        if (!dimensions.keySet().equals(new TreeSet<>(model.dimensions().stream().map(d -> d.id()).toList()))) return false;
        var weight=BigDecimal.ZERO;var weighted=BigDecimal.ZERO;
        for (var spec : model.dimensions()) {
            var d=dimensions.get(spec.id());
            if (spec.weight().compareTo(d.weight())!=0 || spec.penaltyCap().compareTo(d.cap())!=0) return false;
            if (d.contributions().stream().map(ArchitectureAssessment.Contribution::root).distinct().count()!=d.contributions().size()) return false;
            var raw=d.contributions().stream().map(ArchitectureAssessment.Contribution::penalty).reduce(BigDecimal.ZERO,BigDecimal::add);
            if (raw.compareTo(d.uncappedPenalty())!=0 || raw.min(d.cap()).compareTo(d.appliedPenalty())!=0) return false;
            if (d.score().isPresent()) {
                var value=d.score().orElseThrow();
                if (d.baseline().subtract(d.appliedPenalty()).compareTo(value)!=0) return false;
                weight=weight.add(d.weight());weighted=weighted.add(value.multiply(d.weight()));
            }
        }
        return weight.compareTo(score.applicableWeight())==0 && weighted.compareTo(score.weightedNumerator())==0
                && ArchitectureMetric.divide(weighted,weight).compareTo(score.score().orElseThrow())==0;
    }

    private static Object scoreRef(Operand side,ArchitectureAssessment.Result score) {
        return row("input",side.referenceRow(),"score",score==null?null:row("identity",score.identity(),"model",score.model(),
                "modelDigest",score.model().map(m -> m.identity()),"formula",score.formula(),"regionPolicy",score.regionPolicy(),
                "calibration",score.calibration(),"status",score.status(),"value",score.score(),"numerator",score.weightedNumerator(),
                "applicableWeight",score.applicableWeight(),"rounding","SCALE_12_HALF_EVEN","confidence",score.confidence(),"reasons",score.reasons(),
                "structuralInputs",score.structuralInputs().stream().map(ArchitectureMetric.Result::identity).toList()));
    }
}
