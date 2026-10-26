package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.GraphEvidence;
import java.io.IOException;
import java.util.*;

/** An opaque, revalidated binding from immutable gap history to a causal closure census.
 * It cannot be constructed from uploaded current rows or an ACCEPT assertion. */
public final class JavaEvidenceGapClosure {
    public static final String QUESTION="java.gap-requirement-binding";
    public record Current(JavaEvidenceLedger.View requirements, boolean closedDenominator,
                          List<JavaEvidenceLedger.GapDisposition> dispositions,List<CapabilityGapRecord> unmetGaps) {
        public Current { dispositions=List.copyOf(dispositions);unmetGaps=List.copyOf(unmetGaps); }
        public long activeRequirements(){return requirements.active();}
        public List<CapabilityGapRecord> activeGaps(){return dispositions.stream().filter(JavaEvidenceLedger.GapDisposition::active).map(JavaEvidenceLedger.GapDisposition::gap).toList();}
        public List<CapabilityGapRecord> historicalGaps(){return dispositions.stream().filter(d->!d.active()).map(JavaEvidenceLedger.GapDisposition::gap).toList();}
    }
    private final JavaEvidenceLedger.Published publication;
    private final List<CapabilityGapRecord> history;
    private final List<JavaEvidenceAuthority.Verified> bindings;
    public JavaEvidenceGapClosure(JavaEvidenceLedger.Published publication,Collection<CapabilityGapRecord> history){
        this(publication,history,List.of());
    }
    public JavaEvidenceGapClosure(JavaEvidenceLedger.Published publication,Collection<CapabilityGapRecord> history,
            Collection<JavaEvidenceAuthority.Verified> bindings){
        this.publication=Objects.requireNonNull(publication);
        this.history=history.stream().distinct().sorted().toList();
        this.bindings=List.copyOf(bindings);
        String snapshot=publication.ledger().object("scope").get("baseSnapshot").toString();
        for(var gap:this.history)if(!gap.context().snapshotIdentity().value().equals(snapshot))
            throw new IllegalArgumentException("JAVA_GAP_HISTORY_SCOPE_MISMATCH");
    }
    public Map<String,Object> scope(){return publication.ledger().object("scope");}
    public ContentDigest policy(){return publication.projection().digest("policy");}
    public List<CapabilityGapRecord> history(){return history;}
    public JavaEvidenceLedger.Published publication(){return publication;}
    public Current current(ContentDigest policy)throws IOException {
        var view=publication.revalidate(policy,Set.of());
        var requirements=publication.requirements();
        boolean closed=!view.rows().isEmpty()&&publication.projection().text("denominator").equals("CLOSED");
        var dispositions=JavaEvidenceLedger.gapDispositions(history,requirements,view);
        // A frontier cannot establish that every required producer has supplied its answer.
        var checked=new ArrayList<JavaEvidenceLedger.GapDisposition>();
        for(var disposition:dispositions){
            boolean active=disposition.active()||!closed||!bindingAccepted(disposition.gap(),requirements,policy);
            checked.add(new JavaEvidenceLedger.GapDisposition(disposition.gap(),disposition.requirements(),active));
        }
        var unmet=new ArrayList<CapabilityGapRecord>();
        for(var entry:view.rows().entrySet())if(entry.getValue().state()!=JavaEvidenceLedger.State.SATISFIED&&entry.getValue().state()!=JavaEvidenceLedger.State.NOT_APPLICABLE){
            var reason=switch(entry.getValue().state()){
                case REOPENED->JavaEvidenceGaps.Reason.EVIDENCE_REVOKED;
                case CONFLICTED->JavaEvidenceGaps.Reason.VALIDATION_REJECTED;
                default->JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE;
            };
            unmet.add(JavaEvidenceGaps.create(scope(),reason,view.identity(),entry.getKey()));
        }
        if(!closed)unmet.add(JavaEvidenceGaps.create(scope(),JavaEvidenceGaps.Reason.OUTPUT_CENSUS_OPEN,view.identity(),publication.ledger().digest("census")));
        return new Current(view,closed,checked,unmet);
    }
    /** The mapping itself is evidence. gapRefs are audit fields outside requirement identity. */
    public static Map<String,Object> bindingAnswer(Map<String,Object> scope,CapabilityGapRecord gap,List<JavaEvidenceRecord> requirements){
        // Gap IDs use their existing gap: prefix and are intentionally not plain digests.
        var bound=requirements.stream().filter(r->com.evolution.analysis.graph.GraphJson.array(r.fields().get("gapRefs")).stream()
                .anyMatch(value->gap.gapIdentity().value().equals(value))).sorted(Comparator.comparing(JavaEvidenceLedger::requirementKey)).toList();
        return Map.of("schema","java-gap-requirement-binding:m8.4-v1","scope",scope,"gap",gap.gapIdentity(),
                "gapContent",com.evolution.analysis.contract.serialization.CanonicalJson.sha256(gap),
                "requirements",bound.stream().map(r->Map.of("key",JavaEvidenceLedger.requirementKey(r),"question",r.text("question"),"subject",r.text("subject"),"predicate",r.fields().get("predicate"))).toList());
    }
    private boolean bindingAccepted(CapabilityGapRecord gap,List<JavaEvidenceRecord> requirements,ContentDigest policy)throws IOException{
        var bound=requirements.stream().filter(r->com.evolution.analysis.graph.GraphJson.array(r.fields().get("gapRefs")).contains(gap.gapIdentity().value())).toList();
        if(bound.isEmpty())return false;
        var questions=bound.stream().map(r->r.text("question")).collect(java.util.stream.Collectors.toSet());
        for(var need:gap.evidenceRequirements())if(questions.stream().noneMatch(question->compatible(need.kind(),question)))return false;
        var answer=com.evolution.analysis.contract.serialization.CanonicalJson.sha256(bindingAnswer(scope(),gap,requirements));
        var answers=new HashSet<ContentDigest>();
        for(var proof:bindings)if(proof.record().text("question").equals(QUESTION)
                    &&proof.record().text("subject").equals(gap.gapIdentity().value())&&proof.record().object("scope").equals(scope())
                    &&proof.record().digest("policy").equals(policy)&&proof.current())answers.add(proof.answer());
        return answers.equals(Set.of(answer));
    }
    private static boolean compatible(com.evolution.analysis.evidence.EvidenceRequirement.Kind kind,String question){
        return switch(kind){
            case REPOSITORY_CONTENT->question.equals("java.output-bytes");
            case GENERATED_SOURCE,SOURCE_OWNERSHIP->question.equals("java.generated-lineage");
            case PLATFORM_SYMBOLS,EXACT_CLASSPATH,BUILD_MODEL,DEPENDENCY_ARTIFACT->question.equals("java.resolution-context");
            case BYTECODE,REACTOR_OUTPUT->Set.of("java.binary-shape","java.source-binary-correspondence").contains(question);
            case ALTERNATE_FRONTEND,DECODED_SOURCE->question.equals("java.semantic-occurrence");
            case ISOLATED_BUILD_OUTPUT->question.equals("java.build-validation");
            case CONFIGURATION,RUNTIME_OBSERVATION->false;
        };
    }
    public List<GraphEvidence> evidence(Current current)throws IOException{
        var evidence=new ArrayList<GraphEvidence>();
        evidence.addAll(publication.evidence());
        for(var proof:bindings)evidence.add(GraphEvidence.capture("java.evidence.gap-binding-proof",proof.record().fields()));
        evidence.add(GraphEvidence.capture("java.evidence.gap-closure-ledger",publication.ledger().fields()));
        var summary=new TreeMap<String,Object>(Map.of("schema","java-gap-closure-result:m8.4-v1",
                "scope",scope(),"ledger",publication.ledger().identity(),"policy",publication.projection().digest("policy"),
                "denominator",current.closedDenominator()?"CLOSED":"OPEN","counts",current.requirements().counts(),
                "active",current.activeRequirements(),"current",current.requirements().identity()));
        var before=new TreeMap<String,Set<String>>();var after=new TreeMap<String,Set<String>>();var groups=new TreeMap<String,Map<String,Object>>();
        for(var disposition:current.dispositions()){
            var gap=disposition.gap();String key=com.evolution.analysis.contract.serialization.CanonicalJson.write(List.of(gap.catalog(),gap.detectingProvider(),gap.reasonCode()));
            groups.putIfAbsent(key,Map.of("catalog",gap.catalog(),"provider",gap.detectingProvider(),"reasonCode",gap.reasonCode()));
            before.computeIfAbsent(key,k->new TreeSet<>()).add(gap.gapIdentity().value());
            if(disposition.active())after.computeIfAbsent(key,k->new TreeSet<>()).add(gap.gapIdentity().value());
        }
        var delta=new ArrayList<Map<String,Object>>();for(var entry:groups.entrySet()){
            var row=new TreeMap<String,Object>(entry.getValue());row.put("before",(long)before.get(entry.getKey()).size());row.put("after",(long)after.getOrDefault(entry.getKey(),Set.of()).size());delta.add(row);
        }
        summary.put("historyGapCount",current.dispositions().stream().map(d->d.gap().gapIdentity()).distinct().count());
        summary.put("activeHistoryGapCount",current.activeGaps().stream().map(CapabilityGapRecord::gapIdentity).distinct().count());
        summary.put("rootCauseDelta",delta);summary.put("population","BOUND_GAP_HISTORY");
        evidence.add(GraphEvidence.capture("java.evidence.gap-closure",summary));
        for(var disposition:current.dispositions())evidence.add(GraphEvidence.capture("java.evidence.gap-disposition",
                Map.of("gap",disposition.gap().gapIdentity(),"requirements",disposition.requirements(),"active",disposition.active(),
                        "ledger",publication.ledger().identity())));
        return List.copyOf(evidence);
    }
}
