package com.evolution.analysis.execution.output;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.javaevidence.JavaEvidenceGaps;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Compares verified captured entries under caller-registered input/context footprints.
 * Records content and layout changes without editing revisions or issuing semantic trust.
 */
public final class NativeOutputComparison {
    public enum Role { OUTPUT, AUDIT }
    public enum State { IDENTICAL, DIFFERENT, INCOMPARABLE, INCOMPLETE }
    public record Inputs(ContentHandle files,ContentHandle environment){public Inputs{Objects.requireNonNull(files);Objects.requireNonNull(environment);}}
    public record Change(LogicalPath path,Optional<ContentHandle> before,Optional<ContentHandle> after){public Change{Objects.requireNonNull(path);Objects.requireNonNull(before);Objects.requireNonNull(after);}}
    public record MetadataChange(LogicalPath path,Optional<ContentDigest> before,Optional<ContentDigest> after){public MetadataChange{Objects.requireNonNull(path);Objects.requireNonNull(before);Objects.requireNonNull(after);}}
    public static final class Revision {
        private final RepositoryContentStore content;private final Inputs inputs;private final ContentHandle header;
        private final OutputCensus.Result census;private final SortedMap<LogicalPath,ContentHandle> files;private final SortedMap<LogicalPath,ContentDigest> metadata;private final Set<ContentHandle> references;
        private Revision(RepositoryContentStore content,Inputs inputs,ContentHandle header,OutputCensus.Result census,SortedMap<LogicalPath,ContentHandle> files,SortedMap<LogicalPath,ContentDigest> metadata,Set<ContentHandle> references){this.content=content;this.inputs=inputs;this.header=header;this.census=census;this.files=Collections.unmodifiableSortedMap(new TreeMap<>(files));this.metadata=Collections.unmodifiableSortedMap(new TreeMap<>(metadata));this.references=Set.copyOf(references);}
        public Set<LogicalPath> paths(){return Collections.unmodifiableSet(new TreeSet<>(metadata.keySet()));}
        private boolean current()throws IOException{for(var ref:references)if(!content.verify(ref))return false;return true;}
    }
    public static final class Result {
        private final Revision before,after;private final State state;private final List<Change> outputs,audit;
        private final Set<LogicalPath> unclassified;private final List<CapabilityGapRecord> gaps;private final List<ContentHandle> findings;
        private final List<MetadataChange> metadata;
        private final Map<String,Object> canonical;
        private Result(Map<String,Object> scope,Revision before,Revision after,State state,List<Change> outputs,List<Change> audit,List<MetadataChange> metadata,Set<LogicalPath> unclassified,List<CapabilityGapRecord> gaps,List<ContentHandle> findings,List<Object> roles){
            this.before=before;this.after=after;this.state=state;this.outputs=List.copyOf(outputs);this.audit=List.copyOf(audit);this.unclassified=Collections.unmodifiableSet(new TreeSet<>(unclassified));this.gaps=List.copyOf(gaps);this.findings=List.copyOf(findings);
            this.metadata=List.copyOf(metadata);
            var fields=new TreeMap<String,Object>();fields.put("schema","native-output-comparison:m8.4-v1");fields.put("scope",Map.copyOf(scope));fields.put("before",before.header);fields.put("after",after.header);fields.put("beforeInputs",before.inputs);fields.put("afterInputs",after.inputs);fields.put("state",state);fields.put("outputChanges",this.outputs);fields.put("auditChanges",this.audit);fields.put("metadataChanges",this.metadata);fields.put("unclassified",List.copyOf(this.unclassified));fields.put("roles",List.copyOf(roles));fields.put("findings",this.findings);fields.put("gaps",this.gaps);fields.put("semanticTrust","PRODUCER_ASSERTION");fields.put("qualified",false);canonical=Collections.unmodifiableMap(fields);
        }
        public State state(){return state;}public List<Change> outputs(){return outputs;}public List<Change> audit(){return audit;}
        public Set<LogicalPath> unclassified(){return unclassified;}public List<CapabilityGapRecord> gaps(){return gaps;}
        public List<MetadataChange> metadata(){return metadata;}
        public Map<String,Object> canonicalForm(){return canonical;}
        public boolean current()throws IOException{if(!before.current()||!after.current())return false;for(var ref:findings)if(!before.content.verify(ref))return false;return true;}
    }
    public Revision read(RepositoryContentStore content,Inputs inputs,ContentHandle header,OutputCensus.Scope expected,int workerRows,BooleanSupplier cancelled)throws IOException{
        Objects.requireNonNull(content);Objects.requireNonNull(inputs);Objects.requireNonNull(cancelled);
        if(workerRows<1)throw new IllegalArgumentException("NATIVE_COMPARISON_WORKER_BUDGET");
        if(!content.verify(inputs.files())||!content.verify(inputs.environment()))throw new IOException("NATIVE_COMPARISON_INPUT_UNAVAILABLE");
        var parser=new OutputCensus(content);var parsed=parser.header(header,expected);var files=new TreeMap<LogicalPath,ContentHandle>();var metadata=new TreeMap<LogicalPath,ContentDigest>();
        var references=new LinkedHashSet<>(List.of(inputs.files(),inputs.environment(),header,parsed.manifest(),parsed.scopeRecord()));
        long[] rows={0};var result=parser.verify(parsed,content::open,row->{
            if(++rows[0]>workerRows)throw new IOException("NATIVE_COMPARISON_WORKER_CAPACITY_REQUIRED");
            if(row.path().isPresent()&&metadata.putIfAbsent(row.path().orElseThrow(),row.rawRow())!=null)throw new IOException("NATIVE_COMPARISON_DUPLICATE_PATH");
            if(row.captured().isPresent()){
                var path=row.path().orElseThrow();var value=row.captured().orElseThrow();
                if(files.putIfAbsent(path,value)!=null)throw new IOException("NATIVE_COMPARISON_DUPLICATE_PATH");references.add(value);
            }
        },cancelled);
        var revision=new Revision(content,inputs,header,result,files,metadata,references);
        if(!revision.current())throw new IOException("NATIVE_COMPARISON_EVIDENCE_REVOKED");return revision;
    }
    public Result compare(Map<String,Object> scope,Revision before,Revision after,Map<LogicalPath,Role> roles)throws IOException{
        Objects.requireNonNull(scope);Objects.requireNonNull(before);Objects.requireNonNull(after);scope=Map.copyOf(scope);roles=Map.copyOf(roles);
        if(scope.values().stream().anyMatch(value->!(value instanceof String))||!(scope.get("baseSnapshot") instanceof String snapshot))
            throw new IllegalArgumentException("NATIVE_COMPARISON_SCOPE_REQUIRED");
        new SnapshotIdentity(snapshot);
        if(!before.current()||!after.current())throw new IOException("NATIVE_COMPARISON_EVIDENCE_REVOKED");
        // Results reference both captures. Copy by verified content identity; never replace a revision.
        for(var ref:after.references){
            boolean available;try{available=before.content.verify(ref);}catch(IOException missing){available=false;}
            // put also verifies an existing destination; an unreadable/corrupt object is never overwritten.
            if(!available)try(var input=after.content.open(ref)){
                if(!before.content.put(input,()->false).equals(ref))throw new IOException("NATIVE_COMPARISON_CONTENT_CHANGED");
            }
        }
        var paths=new TreeSet<>(before.metadata.keySet());paths.addAll(after.metadata.keySet());var unclassified=new TreeSet<>(paths);unclassified.removeAll(roles.keySet());
        var extra=new TreeSet<>(roles.keySet());extra.removeAll(paths);unclassified.addAll(extra);
        var outputs=new ArrayList<Change>();var audit=new ArrayList<Change>();var metadata=new ArrayList<MetadataChange>();
        for(var path:paths)if(!Objects.equals(before.files.get(path),after.files.get(path))||!Objects.equals(before.metadata.get(path),after.metadata.get(path))){
            var change=new Change(path,Optional.ofNullable(before.files.get(path)),Optional.ofNullable(after.files.get(path)));
            metadata.add(new MetadataChange(path,Optional.ofNullable(before.metadata.get(path)),Optional.ofNullable(after.metadata.get(path))));
            if(roles.get(path)==Role.OUTPUT)outputs.add(change);else if(roles.get(path)==Role.AUDIT)audit.add(change);
        }
        boolean closed=before.census.decision()==OutputCensus.Decision.CAPTURED&&after.census.decision()==OutputCensus.Decision.CAPTURED;
        var gaps=new ArrayList<CapabilityGapRecord>();var findings=new ArrayList<ContentHandle>();
        if(!closed)find(scope,before,after,JavaEvidenceGaps.Reason.OUTPUT_CENSUS_OPEN,Map.of("before",before.census.decision(),"after",after.census.decision()),gaps,findings);
        for(var path:unclassified)find(scope,before,after,JavaEvidenceGaps.Reason.OUTPUT_OWNER_UNPROVEN,path,gaps,findings);
        boolean sameInputs=before.inputs.equals(after.inputs);
        if(!sameInputs)find(scope,before,after,JavaEvidenceGaps.Reason.LINEAGE_SCOPE_UNPROVEN,Map.of("before",before.inputs,"after",after.inputs),gaps,findings);
        State state=!closed||!unclassified.isEmpty()?State.INCOMPLETE:!sameInputs?State.INCOMPARABLE:outputs.isEmpty()?State.IDENTICAL:State.DIFFERENT;
        if(state==State.DIFFERENT)for(var change:outputs)find(scope,before,after,JavaEvidenceGaps.Reason.GENERATOR_NONREPRODUCIBLE,
                Map.of("content",change,"metadata",new MetadataChange(change.path(),Optional.ofNullable(before.metadata.get(change.path())),Optional.ofNullable(after.metadata.get(change.path())))),gaps,findings);
        var roleRows=new ArrayList<Object>();for(var entry:new TreeMap<>(roles).entrySet())roleRows.add(Map.of("path",entry.getKey(),"role",entry.getValue()));
        var result=new Result(scope,before,after,state,outputs,audit,metadata,unclassified,gaps,findings,roleRows);
        if(!result.current())throw new IOException("NATIVE_COMPARISON_EVIDENCE_REVOKED");return result;
    }
    private static void find(Map<String,Object> scope,Revision before,Revision after,JavaEvidenceGaps.Reason reason,Object value,List<CapabilityGapRecord> gaps,List<ContentHandle> findings)throws IOException{
        var payload=Map.of("schema","native-output-comparison-finding:m8.4-v1","reason",reason,"before",before.header,"after",after.header,"detail",value);
        byte[] bytes=CanonicalJson.write(payload).getBytes(StandardCharsets.UTF_8);var finding=before.content.put(new ByteArrayInputStream(bytes),()->false);findings.add(finding);
        gaps.add(JavaEvidenceGaps.create(scope,reason,before.header.digest(),finding.digest()));
    }
}
