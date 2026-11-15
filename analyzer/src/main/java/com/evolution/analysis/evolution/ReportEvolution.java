package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SnapshotComparison.*;

/** Resolves report inputs against the already authenticated context correspondence. */
final class ReportEvolution {
    record Operand(EvolutionInput input, Map<String,Object> context, ReportRef reference, EvolutionReport report,
                   ReportPartition partition, String reason) {
        String id() { return context == null ? null : text(context,"context"); }
        Object referenceRow() {
            return row("graph",input.graph,"context",id(),"report",reference == null ? null : reference.identity(),
                    "evaluation",report == null ? null : report.evaluation().identity(),"reason",reason);
        }
    }
    private final EvolutionRun run;
    private final ReportReader reader;

    ReportEvolution(EvolutionRun run, ReportReader reader) {
        this.run=run;
        this.reader=reader;
    }

    void compare(EvolutionInput left, EvolutionInput right, Map<String,List<ReportRef>> refs) throws IOException {
        var rows = new Rows(run.index,Section.CONTEXTS.name(),"");
        var count = 0L;
        for (var item=rows.next();item!=null;item=rows.next()) {
            EvolutionSnapshot.check(run.cancelled);
            var c=item.value();
            var id=maybe(c,"context");
            if (id == null || !Set.of("analysis.manifest","spring.context").contains(c.get("family"))
                    || "NOT_SELECTED".equals(c.get("disposition"))) continue;
            boolean isLeft="LEFT".equals(c.get("side"));
            boolean matched="MATCHED".equals(c.get("disposition"));
            if (matched && !isLeft) continue;
            var a=isLeft ? c : null;
            var b=isLeft && matched ? run.index.get(Section.CONTEXTS.name(),"RIGHT|"+text(c,"opposite")).orElseThrow()
                    : isLeft ? null : c;
            var lr=refs.getOrDefault("LEFT|"+(a==null?"":text(a,"context")),List.of());
            var rr=refs.getOrDefault("RIGHT|"+(b==null?"":text(b,"context")),List.of());
            // Reports explicitly select the policy/metric population. Empty expected or unrelated contexts
            // remain visible in the context census.
            if (lr.isEmpty() && rr.isEmpty()) continue;
            count++;
            var scope=matched?text(c,"scope"):hash("evolution.report.scope:v1",List.of(text(c,"scope"),text(c,"side"),id)).value();
            var l=read(left,a,lr);
            var r=read(right,b,rr);
            compareScope(scope,l,r,matched);
        }
        for (var group : refs.values()) for(var ref:group) {
            var side=ref.side()==Side.LEFT?left:right;
            var context=run.index.get(Section.CONTEXTS.name(),side.name+"|"+ref.context()).orElse(Map.of());
            String state="NOT_SELECTED".equals(context.get("disposition"))?"NOT_SELECTED":
                    Set.of("analysis.manifest","spring.context").contains(Objects.toString(context.get("family"),""))?
                            "SELECTED":"CONTEXT_UNAVAILABLE_OR_UNSUPPORTED";
            run.put(Section.INPUTS,side.name+"|report-selection|"+ref.identity().value()+"|"+ref.context(),
                    row("side",side.name,"graph",side.graph,"context",ref.context(),"artifact",ref.identity(),"kind","REPORT_SELECTION","status",state));
            if(state.equals("CONTEXT_UNAVAILABLE_OR_UNSUPPORTED")) {
                run.unresolved();
                if(side.readable)run.gap(side,ref.context(),"REPORT_CONTEXT_UNSUPPORTED",List.of(ref.identity()));
            }
        }
        if (count == 0) {
            run.unresolved();
            var scope=hash("evolution.report.scope:v1",run.request.repository()).value();
            var evidence=List.of(left.graph,right.graph);
            for (var capability : List.of("FINDING","METRIC","SCORE")) if (selected(capability))
                run.compatibility(scope,capability,"UNKNOWN",Set.of("SELECTED_REPORT_UNAVAILABLE"),evidence);
            for (var input : List.of(left,right)) if (input.readable) run.gap(input,null,"SELECTED_REPORT_UNAVAILABLE",evidence);
        }
    }

    private boolean selected(String capability) {
        return switch (capability) {
            case "FINDING" -> run.request.selection().findings();
            case "METRIC" -> run.request.selection().metrics();
            case "SCORE" -> run.request.selection().score();
            default -> throw new IllegalArgumentException("Unknown report capability");
        };
    }

    private Operand read(EvolutionInput side, Map<String,Object> context, List<ReportRef> refs) throws IOException {
        if (context==null || !side.readable) return new Operand(side,context,null,null,null,"CONTEXT_UNAVAILABLE");
        if (refs.size()!=1) {
            var reason=refs.isEmpty()?"REPORT_NOT_SUPPLIED":"REPORT_AMBIGUOUS";
            for(var ref:refs)run.gap(side,ref.context(),reason,List.of(ref.identity()));
            return new Operand(side,context,null,null,null,reason);
        }
        var ref=refs.getFirst();
        var input=run.index.get(Section.INPUTS.name(),side.name+"|report|"+ref.identity().value()+"|"+ref.context()).orElseThrow();
        if (!ReportStatus.AVAILABLE.name().equals(input.get("status")))
            return new Operand(side,context,ref,null,null,"REPORT_"+input.get("status"));
        EvolutionReport report=null;
        try {
            report=reader.read(side.graph,ref,run.cancelled).orElse(null);
            if (report==null) return new Operand(side,context,ref,null,null,"REPORT_BODY_UNAVAILABLE");
            if (!ref.identity().equals(report.identity()) || !side.graph.equals(report.evaluation().graph())
                    || !ref.context().equals(report.evaluation().context())) throw EvolutionInput.corrupt();
            var compiledPolicy=report.compilation().flatMap(c->c.policy()).map(p->p.identity());
            var metricPolicy=report.metrics().flatMap(m->m.context().policy());
            if(compiledPolicy.isPresent() && metricPolicy.isPresent() && !compiledPolicy.equals(metricPolicy))
                throw EvolutionInput.corrupt();
            var exactPolicy=compiledPolicy.isPresent()?compiledPolicy:metricPolicy;
            for (var finding : report.evaluation().findings()) {
                var bundle=finding.bundle();
                if (!side.header.snapshot().equals(bundle.snapshot()) || !side.graph.equals(bundle.graph())
                        || !side.analysis(ref.context()).equals(bundle.analysis())
                        || !ref.context().equals(bundle.context()) || !report.evaluation().compilation().equals(bundle.compilation())
                        || !report.evaluation().mapping().equals(bundle.mapping())
                        || exactPolicy.isPresent() && !exactPolicy.orElseThrow().equals(bundle.policy())) throw EvolutionInput.corrupt();
            }
            var rules=report.evaluation().rules();
            if(rules.size()>256 || report.evaluation().findings().size()>report.evaluation().limits().maxFindings()
                    || rules.stream().map(r->r.rule().header().id()).distinct().count()!=rules.size()) throw EvolutionInput.corrupt();
            var compiled=report.compilation().flatMap(c->c.policy());
            if(report.compilation().isPresent() && !compiled.stream().flatMap(p->p.rules().stream()).toList()
                    .equals(rules.stream().map(r->r.rule()).toList())) throw EvolutionInput.corrupt();
        } catch (EvolutionSnapshot.ReadFailure invalid) {
            return new Operand(side,context,ref,null,null,"REPORT_"+invalid.reason().name());
        } catch (java.nio.file.NoSuchFileException missing) {
            return new Operand(side,context,ref,null,null,"REPORT_MISSING");
        } catch (IllegalArgumentException invalid) {
            return new Operand(side,context,ref,null,null,
                    invalid instanceof GraphJson.LimitExceededException?"REPORT_RESOURCE_LIMIT":"REPORT_CORRUPT");
        }
        try {
            var data=run.index.get("context-id",side.name+"|"+ref.context()).orElseThrow(EvolutionInput::corrupt);
            return new Operand(side,data,ref,report,new ReportPartition(side,data),null);
        } catch (EvolutionSnapshot.ReadFailure invalid) {
            return new Operand(side,context,ref,report,null,"REPORT_PARTITION_"+invalid.reason().name());
        } catch (IllegalArgumentException invalid) {
            return new Operand(side,context,ref,report,null,
                    invalid instanceof GraphJson.LimitExceededException?"REPORT_PARTITION_RESOURCE_LIMIT":"REPORT_PARTITION_CORRUPT");
        }
    }

    private void compareScope(String scope, Operand left, Operand right, boolean matched) throws IOException {
        var reasons=new TreeSet<String>();
        var refs=new ArrayList<ContentDigest>();
        for (var side : List.of(left,right)) {
            if (side.reference()!=null) refs.add(side.reference().identity());
            else refs.add(side.input().graph);
            if (side.reason()!=null) reasons.add(side.reason());
            if (!side.input().lineage || side.input().foreign) reasons.add("LINEAGE_UNPROVED");
        }
        if (!matched) reasons.add("CONTEXT_CORRESPONDENCE_UNPROVED");
        if (matched) compareProfiles(left,right,reasons);
        if (left.report()!=null || right.report()!=null) run.observed();
        if (selected("FINDING")) {
            var findingReasons=new TreeSet<>(reasons);
            new FindingEvolution(run,scope,left,right).compare(findingReasons);
            qualify(scope,"FINDING",left,right,refs,findingReasons);
        }
        if (selected("METRIC") || selected("SCORE")) {
            try {
                new AssessmentEvolution(run,scope,left,right).compare(reasons,refs,this);
            } catch (EvolutionSnapshot.ReadFailure invalid) {
                assessmentFailure(scope,left,right,refs,"ASSESSMENT_OPERAND_"+invalid.reason());
            } catch (IllegalArgumentException | ArithmeticException | ClassCastException invalid) {
                assessmentFailure(scope,left,right,refs,invalid instanceof GraphJson.LimitExceededException?
                        "ASSESSMENT_OPERAND_RESOURCE_LIMIT":"ASSESSMENT_OPERAND_CORRUPT");
            }
        }
    }

    private void assessmentFailure(String scope,Operand left,Operand right,List<ContentDigest> refs,String reason)throws IOException {
        for (var capability : List.of("METRIC","SCORE")) if(selected(capability))
            qualify(scope,capability,left,right,refs,Set.of(reason));
    }

    private void compareProfiles(Operand left,Operand right,Set<String> reasons) throws IOException {
        var a=left.context();var b=right.context();
        if ("spring.context".equals(a.get("family"))) {
            a=run.index.get("spring-parent","LEFT|"+text(a,"parentAnalysis")).orElse(Map.of());
            b=run.index.get("spring-parent","RIGHT|"+text(b,"parentAnalysis")).orElse(Map.of());
        }
        if (!(a.get("profile") instanceof Map<?,?>) || !(b.get("profile") instanceof Map<?,?>)) {
            reasons.add("PROFILE_UNPROVED");return;
        }
        var lp=obj(a.get("profile"));var rp=obj(b.get("profile"));
        for (var field : List.of("tool","rules","classpath","platform","schema","provider","configuration"))
            if (!Objects.equals(lp.get(field),rp.get(field))) reasons.add(field.toUpperCase(Locale.ROOT)+"_DRIFT");
        for (var field : List.of("toolKnown","rulesKnown","platformKnown","schemaKnown"))
            if (!Boolean.TRUE.equals(lp.get(field)) || !Boolean.TRUE.equals(rp.get(field))) reasons.add("PROFILE_UNPROVED");
        if (left.partition()!=null && right.partition()!=null && left.partition().spring!=null) {
            var x=left.partition().spring;var y=right.partition().spring;
            if (y==null || !x.domain.alignable(y.domain)) reasons.add("DOMAIN_CORRESPONDENCE_UNPROVED");
            else {
                if (!x.semantics.equals(y.semantics)) reasons.add("SPRING_SEMANTICS_DRIFT");
                if (x.encoding.equals(y.encoding) && !x.workPolicy.equals(y.workPolicy)) reasons.add("REASONER_POLICY_DRIFT");
                if (!x.orderComplete || !y.orderComplete) reasons.add("REGISTRATION_ORDER_UNPROVED");
            }
        }
    }

    void qualify(String scope,String capability,Operand left,Operand right,List<ContentDigest> refs,Set<String> reasons) throws IOException {
        run.compatibility(scope,capability,reasons.isEmpty()?"COMPARABLE":
                reasons.stream().anyMatch(r->r.endsWith("_DRIFT"))?"QUALIFIED":"UNKNOWN",reasons,refs);
        if (reasons.isEmpty()) run.comparable();
        else {
            run.unresolved();
            for (var side : List.of(left,right)) if (side.input().readable)
                for (var reason : reasons) run.gap(side.input(),side.id(),reason,refs);
        }
    }
}
