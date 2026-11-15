package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphEvidence;
import java.io.IOException;
import java.util.*;
import static com.evolution.analysis.evolution.EvolutionRows.*;
import static com.evolution.analysis.evolution.SnapshotComparison.Section;

/** Changes in authenticated evidence availability. Omission is never a closure decision. */
final class EvolutionEvidence {
    private final EvolutionRun run;
    private final EvolutionInput left,right;
    EvolutionEvidence(EvolutionRun run,EvolutionInput left,EvolutionInput right){this.run=run;this.left=left;this.right=right;}

    static void index(EvolutionInput side,GraphEvidence evidence)throws IOException {
        String family=evidence.family();
        if(!(family.startsWith("java.evidence.")||family.startsWith("polyglot.")||
                Set.of("analysis.manifest","evidence.attempt","evidence.conflict","evidence.resolution","evidence.gap","graph.unavailable-reference").contains(family)))return;
        if(family.equals("analysis.manifest")) {
            var raw=EvolutionInput.payload(evidence);
            for(var artifact:list(raw.get("classpath"))) {
                var entry=obj(artifact);
                String digest=hash("evolution.observed-artifact:v1",entry).value();
                String slot=hash("evolution.artifact-slot:v1",SpringOperands.fields(entry,"kind","logicalName")).value();
                observe(side,"build.artifact",slot+"|"+digest,evidence.identity(),entry);
            }
            return;
        }
        ContentDigest exact=evidence.identity();
        String key=exact.value();
        Map<String,Object> metadata=Map.of();
        // For known operand types keep the producer's original identity and trust lane.
        // Evidence rows carry authenticated references, not embedded native payloads.
        if(Set.of("java.evidence.source-map","java.evidence.lineage","java.evidence.output","java.evidence.proof","java.evidence.binary-origin",
                "java.evidence.post-conflict","java.evidence.gap-disposition",
                "evidence.gap","evidence.conflict","evidence.resolution","polyglot.context",
                "polyglot.http-uri-match","polyglot.grpc-source-match").contains(family)){
            var raw=EvolutionInput.payload(evidence);
            var fields=new TreeMap<String,Object>();
            for(String field:List.of("identity","context","scope","trust","confidence","state","outcome","gapIdentity","gap",
                    "conflictIdentity","adjudicationStatus","lineage","content","projectedDocument","projectedPath",
                    "artifact","sourceContext","targetContext","source","target","sourcePartition","targetPartition",
                    "reasonCode","attemptReferences","evidenceReferences","generator","locator","tool","inputs","validationMethod"))
                if(raw.containsKey(field))fields.put(field,raw.get(field));
            metadata=fields;
        }
        observe(side,family,key,exact,metadata);
    }

    private static void observe(EvolutionInput side,String family,String key,ContentDigest ref,Map<String,Object> metadata)throws IOException {
        var index=side.run.index;
        String lookup=side.name+"|"+family+"|"+key;
        var previous=index.get("evidence-observed",lookup);
        // The same classpath operand can occur in many manifests. Its publisher references
        // are already in the authenticated graph; use the least one as a stable representative.
        if(previous.isPresent()&&text(previous.orElseThrow(),"digest").compareTo(ref.value())<=0)return;
        index.put("evidence-observed",lookup,row("side",side.name,"graph",side.graph,"family",family,"digest",ref,"metadata",metadata));
    }

    static void bridge(EvolutionInput side,SnapshotComparison.BridgeRef ref,String family,ContentDigest identity,Map<String,Object> data)throws IOException {
        String type="bridge."+family;
        observe(side,type,identity.value(),ref.publication().digest(),
                row("capturedOperand",identity,"operands",data,"publication",ref.publication()));
    }

    void compare(List<SnapshotComparison.BridgeRef> bridges)throws IOException {
        var l=new Rows(run.index,"evidence-observed",left.name+"|");
        var r=new Rows(run.index,"evidence-observed",right.name+"|");
        while(l.head()!=null||r.head()!=null) {
            EvolutionSnapshot.check(run.cancelled);
            var a=l.head();var b=r.head();
            String ak=key(a),bk=key(b);
            int cmp=ak==null?1:bk==null?-1:ak.compareTo(bk);
            if(cmp==0){
                l.next();r.next();
                var family=text(a.value(),"family");
                if(Set.of("evidence.conflict","java.evidence.post-conflict","evidence.resolution").contains(family))
                    publish(row("kind","LEDGER_OBSERVATION_RETAINED","left",a.value(),"right",b.value(),"family",family,
                            "disposition","EXACT_REFERENCE_ON_BOTH_SIDES","authority","PINNED_GRAPH_OBSERVATION","semanticAbsenceProved",false));
                continue;
            }
            var current=cmp<0?l.next():r.next();
            var data=current.value();
            String family=text(data,"family");
            String kind=family.equals("evidence.gap")?"GAP_OBSERVATION_CHANGED":
                    Set.of("evidence.conflict","java.evidence.post-conflict").contains(family)?"CONFLICT_OBSERVATION_CHANGED":
                    family.equals("evidence.resolution")?"LEDGER_RESOLUTION_OBSERVED":
                    Set.of("java.evidence.source-map","java.evidence.lineage","java.evidence.output").contains(family)?"GENERATED_LINEAGE_OBSERVATION_CHANGED":
                    family.startsWith("polyglot.")||family.startsWith("bridge.")?"PRODUCER_OR_BRIDGE_OBSERVATION_CHANGED":"ARTIFACT_OBSERVATION_CHANGED";
            if(family.startsWith("bridge.")&&!bridgeAvailable((cmp<0?left:right).name))continue;
            String disposition=family.startsWith("bridge.")&&!bridgeAvailable((cmp<0?right:left).name)?"OPPOSITE_BRIDGE_NOT_VERIFIED":
                    cmp<0?"NO_EXACT_REFERENCE_ON_RIGHT":"NO_EXACT_REFERENCE_ON_LEFT";
            var row=row("kind",kind,"left",cmp<0?data:null,"right",cmp>0?data:null,"family",family,
                    "disposition",disposition,"authority",family.startsWith("bridge.")?"PINNED_BRIDGE_OBSERVATION":"PINNED_GRAPH_OBSERVATION",
                    "semanticAbsenceProved",false);
            publish(row);
        }
        if(!bridges.isEmpty()&&(!bridgeAvailable(left.name)||!bridgeAvailable(right.name))) {
            run.unresolved();
            run.compatibility(hash("evolution.bridge-scope:v1",run.request.repository()).value(),"EVIDENCE","UNKNOWN",
                    Set.of("BRIDGE_OPERAND_UNAVAILABLE"),bridges.stream().map(b->b.publication().digest()).toList());
        }
        run.compatibility(hash("evolution.evidence-scope:v1",run.request.repository()).value(),"EVIDENCE",
                "COMPARABLE",Set.of(),List.of(left.graph,right.graph));
    }

    private void publish(Map<String,Object> row)throws IOException {
        var id=hash("evolution.evidence.delta:v1",row);
        var data=new TreeMap<>(row);data.put("identity",id);
        run.put(Section.EVIDENCE,"capture|"+id.value(),data);
    }

    private boolean bridgeAvailable(String side)throws IOException {
        return "AVAILABLE".equals(run.index.get(Section.INPUTS.name(),side+"|bridge").orElse(Map.of()).get("status"));
    }
    private static String key(EvolutionIndex.Entry row){return row==null?null:row.key().substring(row.key().indexOf('|')+1);}
}
