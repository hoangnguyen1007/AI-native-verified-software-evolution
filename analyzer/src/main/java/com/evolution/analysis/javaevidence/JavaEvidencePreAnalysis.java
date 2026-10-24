package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Independently enumerated minimum obligations exist before any provider answers. */
public final class JavaEvidencePreAnalysis {
    public static List<JavaEvidenceRecord> baseline(Map<String,Object> scope){
        String context=com.evolution.analysis.graph.GraphJson.string(scope.get("context"));
        return List.of(requirement(scope,"java.resolution-context",context),requirement(scope,"java.build-validation",context));
    }
    public static JavaEvidenceRecord requirement(Map<String,Object> scope,String question,String subject){
        JavaEvidenceAuthority.requiredChecks(question);
        return new JavaEvidenceCodec().create(Map.of("schema","java-closure-requirement:m8.4-v1","scope",scope,"question",question,"subject",subject,"category","java.pre-analysis","predicate",new VersionedIdentifier(question,JavaEvidenceAuthority.VERSION),"gapRefs",List.of(),"occurrences",List.of(),"readinessRefs",List.of(),"prerequisites",List.of()));
    }
    public static List<JavaEvidenceRecord> footprint(Map<String,Object> scope,JavaEvidenceRecord overlay,JavaEvidenceRecord manifest,JavaEvidenceTables tables,BooleanSupplier cancelled)throws IOException{
        JavaGeneratedOverlay.sameScope(scope,overlay);JavaGeneratedOverlay.sameScope(scope,manifest);
        var rows=new TreeMap<ContentDigest,JavaEvidenceRecord>();
        for(var requirement:baseline(scope))rows.put(JavaEvidenceLedger.requirementKey(requirement),requirement);
        JavaEvidenceTables.Consumer addOutput=output->{
            var bytes=requirement(scope,"java.output-bytes",output.digest("key").value());rows.put(JavaEvidenceLedger.requirementKey(bytes),bytes);
            if(output.fields().get("lineage")!=null){var lineage=requirement(scope,"java.generated-lineage",output.text("lineage"));rows.put(JavaEvidenceLedger.requirementKey(lineage),lineage);}
        };
        var outputs=JavaEvidenceTables.Table.from(overlay.fields().get("outputs"));tables.read(outputs,addOutput,cancelled);
        tables.read(JavaEvidenceTables.Table.from(overlay.fields().get("lineages")),lineage->{var requirement=requirement(scope,"java.generated-lineage",lineage.identity().value());rows.put(JavaEvidenceLedger.requirementKey(requirement),requirement);},cancelled);
        if(JavaEvidenceTables.Table.from(overlay.fields().get("lineages")).rows()==0){var requirement=requirement(scope,"java.generator-not-applicable",com.evolution.analysis.graph.GraphJson.string(scope.get("context")));rows.put(JavaEvidenceLedger.requirementKey(requirement),requirement);}
        tables.read(JavaEvidenceTables.Table.from(manifest.fields().get("slots")),slot->{
            if(!Set.of("CLASS_PATH","MODULE_PATH","BINARY_EVIDENCE").contains(slot.text("space")))return;
            var requirement=requirement(scope,"java.binary-shape",JavaEvidenceRecord.handle(slot.fields().get("original")).digest().value());rows.put(JavaEvidenceLedger.requirementKey(requirement),requirement);
            if(slot.text("origin").equals("PROJECT")&&Objects.equals(slot.fields().get("module"),scope.get("module"))){
                var correspondence=requirement(scope,"java.source-binary-correspondence",slot.identity().value());rows.put(JavaEvidenceLedger.requirementKey(correspondence),correspondence);
            }
        },cancelled);
        return List.copyOf(rows.values());
    }
    public static void requireFootprint(JavaEvidenceLedger.Published value,JavaEvidenceRecord overlay,JavaEvidenceRecord manifest,JavaEvidenceTables tables,BooleanSupplier cancelled)throws IOException{
        requireBaseline(value,tables,cancelled);
        for(var requirement:footprint(value.projection().object("scope"),overlay,manifest,tables,cancelled))
            if(!value.current().rows().containsKey(JavaEvidenceLedger.requirementKey(requirement)))throw new IOException("JAVA_PRE_ANALYSIS_CENSUS_INCOMPLETE");
    }
    public static List<CapabilityGapRecord> activeGaps(JavaEvidenceLedger.Published value){
        var scope=value.projection().object("scope");var gaps=new ArrayList<CapabilityGapRecord>();
        for(var row:value.current().rows().entrySet())if(row.getValue().state()!=JavaEvidenceLedger.State.SATISFIED&&row.getValue().state()!=JavaEvidenceLedger.State.NOT_APPLICABLE){
            var reason=row.getValue().state()==JavaEvidenceLedger.State.REOPENED?JavaEvidenceGaps.Reason.EVIDENCE_REVOKED:row.getValue().state()==JavaEvidenceLedger.State.CONFLICTED?JavaEvidenceGaps.Reason.VALIDATION_REJECTED:JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE;
            gaps.add(JavaEvidenceGaps.create(scope,reason,value.projection().identity(),row.getKey()));
        }
        if(!value.projection().text("denominator").equals("CLOSED"))gaps.add(JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.OUTPUT_CENSUS_OPEN,value.projection().identity(),value.projection().digest("census")));
        return List.copyOf(gaps);
    }
    public static void requireBaseline(JavaEvidenceLedger.Published value,JavaEvidenceTables tables,BooleanSupplier cancelled)throws IOException{
        var required=baseline(value.projection().object("scope")).stream().map(JavaEvidenceLedger::requirementKey).collect(java.util.stream.Collectors.toSet());
        var actual=new HashSet<ContentDigest>();tables.read(JavaEvidenceTables.Table.from(value.ledger().fields().get("requirements")),r->actual.add(JavaEvidenceLedger.requirementKey(r)),cancelled);
        if(!actual.containsAll(required)||!actual.equals(value.current().rows().keySet()))throw new IOException("JAVA_PRE_ANALYSIS_CENSUS_INCOMPLETE");
    }
    private JavaEvidencePreAnalysis(){}
}
