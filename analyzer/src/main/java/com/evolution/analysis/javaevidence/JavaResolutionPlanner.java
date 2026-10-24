package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exact native order or an explicit scoped gap; never a dependency superset. */
public final class JavaResolutionPlanner {
    @FunctionalInterface public interface BinaryMaterializer {BinaryInput materialize(JavaEvidenceRecord slot)throws IOException;}
    public record Result(Optional<List<BinaryInput>> binaries,List<CapabilityGapRecord> gaps){public Result{binaries=binaries.map(List::copyOf);gaps=List.copyOf(gaps);}}
    private final RepositoryContentStore content;private final JavaEvidenceTables tables;
    public JavaResolutionPlanner(RepositoryContentStore content,int rowCharacters){this.content=content;tables=new JavaEvidenceTables(content,rowCharacters);}
    /** The proof answer omits publication/overlay wrappers and operational capture identities. */
    public static Map<String,Object> answerFields(JavaEvidenceRecord manifest){var fields=new TreeMap<String,Object>();for(String key:List.of("scope","compiler","sourceLanguage","bytecodeTarget","release","preview","encoding","platform","sourceMembership","slots","arguments","argfiles"))fields.put(key,manifest.fields().get(key));return Collections.unmodifiableMap(fields);}
    public static ContentDigest answer(JavaEvidenceRecord manifest){return CanonicalJson.sha256(answerFields(manifest));}
    public Result plan(JavaEvidenceRecord manifest,JavaEvidenceRecord overlay,PlatformInput platform,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,BinaryMaterializer materializer,int workerSlots,BooleanSupplier cancelled)throws IOException{
        JavaEvidenceLedger.kind(manifest,"java-resolution-manifest");JavaEvidenceLedger.kind(overlay,"java-generated-overlay");
        JavaGeneratedOverlay.sameScope(overlay.object("scope"),manifest);var scope=manifest.object("scope");var gaps=new TreeSet<CapabilityGapRecord>();
        if(!manifest.digest("overlay").equals(overlay.identity()))throw new IOException("JAVA_RESOLUTION_OVERLAY_MISMATCH");
        if(!"EXACT".equals(manifest.text("status"))||!"COMPLETE".equals(overlay.text("closure")))return withheld(manifest,JavaEvidenceGaps.Reason.SEARCH_SPACE_OPEN);
        var proof=proofs.get(manifest.digest("contextProof"));
        if(proof==null||!proof.identity().equals(manifest.digest("contextProof"))||!proof.current()||!proof.record().text("question").equals("java.resolution-context")||!proof.record().text("subject").equals(scope.get("context"))||!proof.record().fields().get("scope").equals(scope)||!proof.answer().equals(answer(manifest)))return withheld(manifest,JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE);
        if(!platform.entry().contentDigest().value().equals(scopeValue(manifest,"platform"))||manifest.fields().get("release")!=null&&!manifest.text("release").equals(Integer.toString(platform.release())))return withheld(manifest,JavaEvidenceGaps.Reason.PLATFORM_VIEW_MISSING);
        var slots=JavaEvidenceTables.Table.from(manifest.fields().get("slots"));if(slots.rows()>workerSlots)return withheld(manifest,JavaEvidenceGaps.Reason.WORKER_CAPACITY_REQUIRED);
        var membership=JavaEvidenceTables.Table.from(manifest.fields().get("sourceMembership"));
        if(!content.verify(membership.content()))return withheld(manifest,JavaEvidenceGaps.Reason.SEARCH_SPACE_OPEN);
        var nativeCensus=BinaryInput.nativeCensus(manifest,proof,content,workerSlots,cancelled);if(nativeCensus.jpms())return withheld(manifest,JavaEvidenceGaps.Reason.JPMS_CONTEXT_UNPROVEN);if(nativeCensus.reactorSources())return withheld(manifest,JavaEvidenceGaps.Reason.CORRESPONDENCE_UNPROVEN);
        var next=new HashMap<String,Long>();var binaries=new ArrayList<BinaryInput>();
        tables.read(slots,slot->{
            JavaGeneratedOverlay.sameScope(scope,slot);String space=slot.text("space");long expected=next.getOrDefault(space,0L);
            if(slot.number("ordinal")!=expected)throw new IOException("JAVA_CLASSPATH_NONCONTIGUOUS_ORDER");next.put(space,Math.addExact(expected,1));
            JavaEvidenceGaps.Reason reason=null;
            if(!slot.text("disposition").equals("ACCEPTED"))reason=JavaEvidenceGaps.Reason.SEARCH_SPACE_OPEN;
            else if(space.equals("MODULE_PATH"))reason=JavaEvidenceGaps.Reason.JPMS_CONTEXT_UNPROVEN;
            else if(space.equals("CLASS_PATH")||space.equals("BINARY_EVIDENCE")){
                if(slot.text("kind").equals("REACTOR_SOURCE"))reason=JavaEvidenceGaps.Reason.CORRESPONDENCE_UNPROVEN;
                else if(slot.text("origin").equals("JDK"))reason=JavaEvidenceGaps.Reason.PLATFORM_VIEW_MISSING;
                else if(!slot.text("kind").equals("JAR")&&slot.fields().get("selectedView")==null)reason=JavaEvidenceGaps.Reason.CORRESPONDENCE_UNPROVEN;
                else if(!content.verify(JavaEvidenceRecord.handle(slot.fields().get("original"))))reason=JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH;
                else{
                    var originProof=proofs.get(slot.digest("proof"));
                    if(originProof==null||!originProof.identity().equals(slot.digest("proof"))||!originProof.current()||!originProof.record().text("question").equals("java.binary-shape")||!originProof.record().text("subject").equals(JavaEvidenceRecord.handle(slot.fields().get("original")).digest().value())||!originProof.record().fields().get("scope").equals(scope))reason=JavaEvidenceGaps.Reason.CORRESPONDENCE_UNPROVEN;
                    else try{
                        var binary=materializer.materialize(slot);var kind=slot.text("origin").equals("PROJECT")?com.evolution.analysis.contract.analysis.ClasspathEntryKind.MODULE_OUTPUT:com.evolution.analysis.contract.analysis.ClasspathEntryKind.DEPENDENCY;
                        var original=new com.evolution.analysis.contract.analysis.ClasspathEntry(kind,slot.text("logicalName"),JavaEvidenceRecord.handle(slot.fields().get("original")).digest());
                        if(binary.nativeOrigin().isEmpty()||!binary.nativeOrigin().orElseThrow().slot().equals(slot.identity())||!binary.originEntry().equals(original)||!binary.reactorModule().map(com.evolution.analysis.contract.identity.ModuleIdentity::value).equals(Optional.ofNullable(slot.fields().get("module")).map(GraphJson::string)))reason=JavaEvidenceGaps.Reason.CORRESPONDENCE_UNPROVEN;
                        else if(space.equals("CLASS_PATH"))binaries.add(binary.withValidatedOrigin(slot,originProof,nativeCensus));
                        else if(!slot.text("origin").equals("PROJECT")||!Objects.equals(slot.fields().get("module"),scope.get("module"))||slot.fields().get("selectedView")==null)reason=JavaEvidenceGaps.Reason.CORRESPONDENCE_UNPROVEN;
                    }catch(IOException unavailable){reason=JavaEvidenceGaps.Reason.BINARY_FORMAT_INVALID;}
                }
            }
            if(reason==null&&!content.verify(JavaEvidenceRecord.handle(slot.fields().get("original"))))reason=JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH;
            if(reason==null&&slot.fields().get("selectedView")!=null&&!content.verify(JavaEvidenceRecord.handle(slot.fields().get("selectedView"))))reason=JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH;
            if(reason!=null)gaps.add(JavaEvidenceGaps.create(scope,reason,manifest.identity(),slot.identity()));
        },cancelled);
        for(Object file:GraphJson.array(manifest.fields().get("argfiles")))if(!content.verify(JavaEvidenceRecord.handle(file)))gaps.add(JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.GENERATOR_INPUT_MISSING,manifest.identity(),manifest.identity()));
        return new Result(gaps.isEmpty()?Optional.of(binaries):Optional.empty(),List.copyOf(gaps));
    }
    private static String scopeValue(JavaEvidenceRecord record,String field){return record.text(field);}
    private static Result withheld(JavaEvidenceRecord record,JavaEvidenceGaps.Reason reason){return new Result(Optional.empty(),List.of(JavaEvidenceGaps.create(record.object("scope"),reason,record.identity(),record.identity())));}
}
