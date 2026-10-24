package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Context-qualified overlay import. Bounded compatibility workers retain original table handles. */
public final class JavaGeneratedOverlay {
    public record Result(JavaEvidenceRecord overlay,List<CapabilityGapRecord> gaps){public Result{gaps=List.copyOf(gaps);}}
    private final RepositoryContentStore content;private final JavaEvidenceTables tables;private final JavaEvidenceCodec codec=new JavaEvidenceCodec();
    public JavaGeneratedOverlay(RepositoryContentStore content,int rowBudget){this.content=Objects.requireNonNull(content);tables=new JavaEvidenceTables(content,rowBudget);}
    public static ContentDigest outputKey(JavaEvidenceRecord row){return CanonicalJson.sha256(Map.of("schema","java-output-key:m8.4-v1","scope",row.object("scope").get("logicalScope"),"role",row.text("role"),"locator",row.fields().get("locator"),"expectation",row.text("expectation")));}
    public Result importOverlay(Map<String,Object> scope,ContentHandle census,JavaEvidenceTables.Table lineageTable,JavaEvidenceTables.Table outputTable,
            Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,boolean censusClosed,int workerRows,BooleanSupplier cancelled)throws IOException{
        if(workerRows<1)throw new IllegalArgumentException("JAVA_OVERLAY_WORKER_BUDGET");
        if(!content.verify(census))throw new IOException("JAVA_OUTPUT_CENSUS_UNAVAILABLE");
        // An incapable worker retains both input tables; it never publishes a smaller denominator.
        if(lineageTable.rows()>workerRows||outputTable.rows()>workerRows)throw new IOException("JAVA_OVERLAY_WORKER_CAPACITY_REQUIRED");
        var lineages=new TreeMap<ContentDigest,JavaEvidenceRecord>();
        tables.read(lineageTable,row->{JavaEvidenceLedger.kind(row,"java-generation-lineage");sameScope(scope,row);
            var inputs=JavaEvidenceTables.Table.from(row.fields().get("inputs"));tables.read(inputs,input->{
                if(!content.verify(JavaEvidenceRecord.handle(input.fields().get("content"))))throw new IOException("JAVA_GENERATOR_INPUT_UNAVAILABLE");
                if(input.fields().get("locator")!=null)path(input.object("locator").get("path"));
            },cancelled);lineages.put(row.identity(),row);},cancelled);
        var outputs=new ArrayList<JavaEvidenceRecord>();var gaps=new TreeSet<CapabilityGapRecord>();var paths=new HashSet<String>();
        tables.read(outputTable,row->{
            JavaEvidenceLedger.kind(row,"java-output-row");sameScope(scope,row);
            if(!row.digest("key").equals(outputKey(row)))throw new IOException("JAVA_OUTPUT_KEY_MISMATCH");
            var values=new LinkedHashMap<>(row.fields());JavaEvidenceGaps.Reason problem=null;
            try{
                var locator=row.object("locator");var path=path(locator.get("path"));
                if(!paths.add(path.key()))problem=JavaEvidenceGaps.Reason.OUTPUT_COLLISION;
                if(!content.verify(JavaEvidenceRecord.handle(locator.get("container"))))problem=JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH;
                for(Object member:GraphJson.array(locator.get("members"))){var step=GraphJson.object(member);path(step.get("path"));if(!content.verify(JavaEvidenceRecord.handle(step.get("content"))))problem=JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH;}
                if(row.fields().get("content")!=null&&!content.verify(JavaEvidenceRecord.handle(row.fields().get("content"))))problem=JavaEvidenceGaps.Reason.CONTENT_HASH_MISMATCH;
                if(row.text("disposition").equals("ACCEPTED")){
                    var lineage=lineages.get(row.digest("lineage"));var proof=proofs.get(row.digest("rootProof"));
                    if(lineage==null||!lineage.text("closure").equals("COMPLETE")||!lineage.fields().get("outputCensus").equals(normalize(census)))problem=JavaEvidenceGaps.Reason.GENERATOR_CLOSURE_OPEN;
                    else if(proof==null||!proof.identity().equals(row.digest("rootProof"))||!proof.current()||!proof.record().text("question").equals("java.generated-lineage")||!proof.record().text("subject").equals(lineage.identity().value())||!proof.record().fields().get("scope").equals(scope)||!proof.record().digest("inputClosure").equals(lineage.digest("inputClosure")))problem=JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE;
                }else if(row.text("expectation").equals("REQUIRED")&&!row.text("disposition").equals("ACCEPTED"))problem=JavaEvidenceGaps.Reason.GENERATOR_CLOSURE_OPEN;
            }catch(IllegalArgumentException malformed){problem=JavaEvidenceGaps.Reason.UNSAFE_OUTPUT_LOCATOR;}
            if(problem!=null){values.put("disposition",row.fields().get("content")==null?"UNAVAILABLE":"RETAINED");values.put("reason",problem.name());var gap=JavaEvidenceGaps.create(scope,problem,outputTable.content().digest(),row.identity());gaps.add(gap);}
            // A closed denominator can still contain rejected or unavailable observations.
            // Retain their original disposition and make the inability explicit.
            if(problem==null&&!row.text("disposition").equals("ACCEPTED")&&!row.text("disposition").equals("ABSENT"))
                gaps.add(JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.GENERATOR_CLOSURE_OPEN,outputTable.content().digest(),row.identity()));
            outputs.add(codec.create(values));
        },cancelled);
        if(outputs.isEmpty()||lineages.isEmpty()){
            boolean noGenerator=false;
            for(var entry:proofs.entrySet()){
                var proof=entry.getValue();if(entry.getKey().equals(proof.identity())&&proof.current()&&proof.record().text("question").equals("java.generator-not-applicable")&&proof.record().text("subject").equals(scope.get("context"))&&proof.record().fields().get("scope").equals(scope)){noGenerator=true;break;}
            }
            // A present zero-output generator is a different case: every lineage still needs
            // a matching validated closed census, rather than a proof that no generator exists.
            boolean zeroOutputGenerators=!lineages.isEmpty();
            for(var lineage:lineages.values()){
                boolean validated=false;
                for(var entry:proofs.entrySet()){var proof=entry.getValue();if(entry.getKey().equals(proof.identity())&&proof.current()&&proof.record().text("question").equals("java.generated-lineage")&&proof.record().text("subject").equals(lineage.identity().value())&&proof.record().fields().get("scope").equals(scope)&&proof.record().digest("inputClosure").equals(lineage.digest("inputClosure"))){validated=true;break;}}
                zeroOutputGenerators&=validated&&lineage.text("closure").equals("COMPLETE")&&lineage.fields().get("outputCensus").equals(normalize(census));
            }
            if(!(noGenerator&&lineages.isEmpty())&&!zeroOutputGenerators)gaps.add(JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.GENERATOR_CLOSURE_OPEN,census.digest(),outputTable.content().digest()));
        }
        var acceptedLineages=outputs.stream().filter(row->row.text("disposition").equals("ACCEPTED")&&row.fields().get("lineage")!=null).map(row->row.text("lineage")).collect(java.util.stream.Collectors.toSet());
        for(var lineage:lineages.values())if(!acceptedLineages.contains(lineage.identity().value())){
            boolean validated=false;for(var entry:proofs.entrySet()){var proof=entry.getValue();if(entry.getKey().equals(proof.identity())&&proof.current()&&proof.record().text("question").equals("java.generated-lineage")&&proof.record().text("subject").equals(lineage.identity().value())&&proof.record().fields().get("scope").equals(scope)&&proof.record().digest("inputClosure").equals(lineage.digest("inputClosure"))){validated=true;break;}}
            var reason=!lineage.text("closure").equals("COMPLETE")||!lineage.fields().get("outputCensus").equals(normalize(census))?JavaEvidenceGaps.Reason.GENERATOR_CLOSURE_OPEN:!validated?JavaEvidenceGaps.Reason.VALIDATION_METHOD_UNAVAILABLE:null;
            if(reason!=null)gaps.add(JavaEvidenceGaps.create(scope,reason,lineageTable.content().digest(),lineage.identity()));
        }
        if(!censusClosed)gaps.add(JavaEvidenceGaps.create(scope,JavaEvidenceGaps.Reason.OUTPUT_CENSUS_OPEN,census.digest(),census.digest()));
        outputs.sort(JavaEvidenceTables::compare);var table=tables.write("java-output-row",outputs.iterator(),cancelled);
        var counts=new TreeMap<String,Long>();for(String key:List.of("total","accepted","retained","absent","rejected","unavailable","notReached"))counts.put(key,0L);
        for(var row:outputs){counts.compute("total",(k,v)->Math.addExact(v,1));String key=switch(row.text("disposition")){case "NOT_REACHED"->"notReached";default->row.text("disposition").toLowerCase(Locale.ROOT);};counts.compute(key,(k,v)->Math.addExact(v,1));}
        var fields=new LinkedHashMap<String,Object>();fields.put("schema","java-generated-overlay:m8.4-v1");fields.put("scope",scope);fields.put("outputCensus",census);fields.put("lineages",lineageTable.wire());fields.put("outputs",table.wire());fields.put("provider",JavaEvidenceGaps.PROVIDER);fields.put("census",censusClosed?"SEALED":"OPEN");fields.put("closure",gaps.isEmpty()?"COMPLETE":"PARTIAL");fields.put("frontier",censusClosed?List.of():List.of(census.digest().value()));fields.put("gaps",gaps.stream().map(g->g.gapIdentity().value()).toList());fields.put("counts",counts);
        return new Result(codec.create(fields),List.copyOf(gaps));
    }
    public static LogicalPath path(Object value){return new LogicalPath(GraphJson.string(GraphJson.object(value).get("rawBase64")));}
    static void sameScope(Map<String,Object> scope,JavaEvidenceRecord row)throws IOException{if(!row.fields().get("scope").equals(scope))throw new IOException("JAVA_EVIDENCE_SCOPE_MISMATCH");}
    private static Object normalize(Object value){return GraphJson.read(CanonicalJson.write(value),GraphJson.Limits.defaults());}
}
