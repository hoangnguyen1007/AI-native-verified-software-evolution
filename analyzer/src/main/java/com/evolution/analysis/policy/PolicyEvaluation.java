package com.evolution.analysis.policy;

import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.query.*;
import java.util.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/** Versioned, storage-neutral policy outcomes. NEVER is retained only as a clean rule result. */
public final class PolicyEvaluation {
    public static final VersionedIdentifier VERSION=new VersionedIdentifier("policy.evaluation","m6.2-v1");
    public static final VersionedIdentifier EVIDENCE_VERSION=new VersionedIdentifier("policy.evidence-bundle","m6.2-v1");
    public record Limits(int maxFindings,int maxEvidenceReads,int maxInputItems,long maxEvidenceCharacters) {
        public Limits(int maxFindings,int maxEvidenceReads,int maxInputItems){this(maxFindings,maxEvidenceReads,maxInputItems,8_000_000);}
        public Limits {if(maxFindings<1||maxFindings>50||maxEvidenceReads<1||maxEvidenceReads>4096||maxInputItems<1||maxInputItems>1_000_000
                ||maxEvidenceCharacters<1||maxEvidenceCharacters>16_000_000)
            throw new IllegalArgumentException("Invalid policy evaluation limits");}
        public static Limits defaults(){return new Limits(50,1024,500_000);}
    }
    public record SourceEvidence(SourceSpan span,ContentDigest sourceDigest,ContentDigest evidence) {
        public SourceEvidence {Objects.requireNonNull(span);Objects.requireNonNull(sourceDigest);Objects.requireNonNull(evidence);}
    }
    public record EvidenceReference(ContentDigest identity,String family) {
        public EvidenceReference {Objects.requireNonNull(identity);Objects.requireNonNull(family);}
    }
    /** Original gap/conflict payload, including the context needed for stable evidence drill-down. */
    public record UpstreamEvidence(String context,ContentDigest identity,String family) {}
    public record Support(String identity,String source,String target,String kind,Optional<String> region,
                          List<ContentDigest> evidence,List<String> semanticStatuses,List<String> derivations) {
        public Support {evidence=List.copyOf(evidence);semanticStatuses=List.copyOf(semanticStatuses);derivations=List.copyOf(derivations);}
    }
    /** Content references point into the immutable graph; no source/configuration literals are copied. */
    public record EvidenceBundle(SnapshotIdentity snapshot,Optional<AnalysisIdentity> analysis,ContentDigest graph,String context,
                                 ContentDigest policy,ContentDigest policySource,ContentDigest compilation,ContentDigest mapping,
                                 List<String> configurationSpaces,List<EvidenceReference> provenance,List<UpstreamEvidence> upstreamEvidence,
                                 ArchitecturePolicy.Rule rule,Request request,ContentDigest query,ArchitectureCondition.RulePredicate predicate,
                                 ArchitectureCondition.Evaluation region,List<String> path,List<EdgeView> relationships,List<Support> supports,
                                 List<SourceEvidence> sources,List<EvidenceReference> evidence,AssessmentStatus status,
                                 List<CapabilityGapRecord> gaps,List<String> limitations) {
        public EvidenceBundle {
            Objects.requireNonNull(snapshot);Objects.requireNonNull(analysis);Objects.requireNonNull(graph);Objects.requireNonNull(context);
            Objects.requireNonNull(policy);Objects.requireNonNull(policySource);Objects.requireNonNull(compilation);Objects.requireNonNull(mapping);
            Objects.requireNonNull(rule);Objects.requireNonNull(request);Objects.requireNonNull(query);Objects.requireNonNull(predicate);Objects.requireNonNull(region);Objects.requireNonNull(status);
            configurationSpaces=List.copyOf(configurationSpaces);provenance=List.copyOf(provenance);
            upstreamEvidence=List.copyOf(upstreamEvidence);
            path=List.copyOf(path);relationships=List.copyOf(relationships);supports=List.copyOf(supports);sources=List.copyOf(sources);
            evidence=List.copyOf(evidence);gaps=List.copyOf(gaps);limitations=List.copyOf(limitations);
            if(!graph.equals(request.graph())||!context.equals(request.context())||rule.header().scope()!=request.scope()
                    ||!rule.header().relationships().equals(request.relationshipKinds())||status==AssessmentStatus.COMPLETE&&!gaps.isEmpty())
                throw new IllegalArgumentException("Incompatible evidence bundle");
            for(var witness:List.of(region.witness(),region.counterWitness()))if(witness.isPresent()&&!region.jointRegion().equals(witness.orElseThrow().jointRegion()))
                throw new IllegalArgumentException("Foreign witness region");
        }
        public ContentDigest identity(){return digest(EVIDENCE_VERSION,this);}
    }
    public record Finding(ArchitecturePolicy.Header rule,ArchitecturePolicy.Kind kind,Classification classification,EvidenceBundle bundle) {
        public Finding {Objects.requireNonNull(rule);Objects.requireNonNull(kind);Objects.requireNonNull(classification);Objects.requireNonNull(bundle);
            if(classification==Classification.NEVER||classification!=bundle.region().classification())throw new IllegalArgumentException("Invalid violation classification");}
        public ContentDigest identity(){return digest(VERSION,this);}
        /** Stable logical key within compatible inputs; evidence changes still alter the full content identity. */
        public ContentDigest key(){return digest(VERSION,List.of("finding-key",bundle.snapshot(),bundle.context(),bundle.policy(),bundle.rule().identity(),
                bundle.path(),bundle.relationships().stream().map(EdgeView::identity).toList()));}
    }
    public record RuleResult(ArchitecturePolicy.Rule rule,AssessmentStatus status,Classification classification,
                             ContentDigest query,ArchitectureCondition.RulePredicate predicate,
                             ArchitectureCondition.Evaluation region,List<Finding> findings,List<CapabilityGapRecord> gaps) {
        public RuleResult {Objects.requireNonNull(rule);Objects.requireNonNull(status);Objects.requireNonNull(classification);Objects.requireNonNull(query);Objects.requireNonNull(predicate);Objects.requireNonNull(region);
            findings=List.copyOf(findings);gaps=List.copyOf(gaps);
            if(classification!=region.classification()||status==AssessmentStatus.COMPLETE&&!gaps.isEmpty()
                    ||findings.stream().anyMatch(f->!f.rule().equals(rule.header())||f.kind()!=rule.kind()))throw new IllegalArgumentException("Incompatible rule result");}
        public ContentDigest identity(){return digest(VERSION,this);}
    }
    public record Result(ContentDigest compilation,ContentDigest mapping,ContentDigest graph,String context,Limits limits,
                         AssessmentStatus status,List<RuleResult> rules,List<CapabilityGapRecord> gaps,List<UpstreamEvidence> upstreamEvidence) {
        public Result {rules=List.copyOf(rules);gaps=List.copyOf(gaps);upstreamEvidence=List.copyOf(upstreamEvidence);}
        public ContentDigest identity(){return digest(VERSION,this);}
        public List<Finding> findings(){return rules.stream().flatMap(r->r.findings().stream()).toList();}
    }
    static ContentDigest digest(VersionedIdentifier version,Object value){return ContentDigest.sha256Utf8(CanonicalJson.write(List.of(version,value)));}
    private PolicyEvaluation() {}
}
