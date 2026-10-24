package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Imports captured compiler answers; never executes a compiler or target hook. */
public final class JavaCompilerAttribution {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("frontend.java-captured-compiler","m8.4-step4-v1");
    public record Bundle(RepositoryContentStore content,ContentHandle index,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,int workerRows,BooleanSupplier cancelled){
        public Bundle{Objects.requireNonNull(content);Objects.requireNonNull(index);proofs=Map.copyOf(proofs);Objects.requireNonNull(cancelled);if(workerRows<1)throw new IllegalArgumentException("JAVA_COMPILER_WORKER_BUDGET");}
        public Map<String,String> options(ContentDigest policy){return Map.of("java.compiler-attribution",index.digest().value(),"java.compiler-attribution-policy",policy.value(),"java.compiler-attribution-adapter",PROVIDER.id()+":"+PROVIDER.version());}
    }
    public static final class Result {
        private final FrontendResult frontend;private final ContentDigest view;private final SnapshotIdentity snapshot;private final VersionedIdentifier compilerProvider;
        private final List<CapabilityGapRecord> gaps;private final List<GraphEvidence> evidence;
        private final Reader reader;
        private Result(FrontendResult frontend,ContentDigest view,SnapshotIdentity snapshot,VersionedIdentifier compilerProvider,List<CapabilityGapRecord> gaps,Reader reader){this.frontend=frontend;this.view=view;this.snapshot=snapshot;this.compilerProvider=compilerProvider;this.gaps=List.copyOf(gaps);this.reader=reader;this.evidence=List.copyOf(reader.evidence);}
        public VersionedIdentifier compilerProvider(){return compilerProvider;}
        public FrontendResult frontend(){return frontend;}public ContentDigest view(){return view;}public SnapshotIdentity snapshot(){return snapshot;}
        public List<CapabilityGapRecord> gaps(){return gaps;}public List<GraphEvidence> evidence(){return evidence;}
        public boolean current()throws IOException{for(var ref:reader.references)if(!reader.bundle.content().verify(ref))return false;for(var proof:reader.checked)if(!proof.current())return false;return true;}
    }
    public Result apply(Map<String,Object> scope,ContentDigest view,ContentDigest policy,FrontendRequest request,FrontendResult primary,Bundle bundle)throws IOException{
        primary.validateFor(request);request.originClasspath();
        var options=request.manifest().configuration().values();
        if(!request.module().value().equals(scope.get("module"))||!view.value().equals(options.get("java.evidence-view"))||!Objects.equals(scope.get("context"),options.get("java.native-context"))||!Objects.equals(scope.get("sourceSet"),options.get("ingestion.source-set-name")))throw invalid("REQUEST_SCOPE");
        for(var entry:bundle.options(policy).entrySet())if(!entry.getValue().equals(options.get(entry.getKey())))throw invalid("UNBOUND_INDEX");
        if(!request.reactorSources().isEmpty())throw invalid("REACTOR_CENSUS_REQUIRED");
        var io=new Reader(bundle);var index=io.read(bundle.index());fields(index,"schema","scope","inputView","primary","provider","rows");
        if(!"java-compiler-attribution:m8.4-v1".equals(index.get("schema"))||!scope.equals(index.get("scope"))||!view.value().equals(index.get("inputView"))||!version(index.get("primary")).equals(primary.frontend()))throw invalid("INDEX_SCOPE");
        var provider=version(index.get("provider"));if(provider.id().equals(primary.frontend().id())||provider.id().equals(PROVIDER.id()))throw invalid("COMPILER_PROVIDER_REQUIRED");
        var table=JavaEvidenceTables.Table.from(index.get("rows"));if(!table.kind().equals("java-evidence-input"))throw invalid("TABLE_KIND");if(table.rows()>bundle.workerRows())throw invalid("WORKER_CAPACITY_REQUIRED");io.reference(table.content());
        var sources=new HashMap<SourceDocumentIdentity,SourceInput>();request.sources().forEach(s->sources.put(s.document().identity(),s));
        var byContent=new HashMap<ContentDigest,DeclarationRecord>();primary.declarations().forEach(d->byContent.put(CanonicalJson.sha256(d.entity()),d));
        var oldOccurrences=new HashMap<OccurrenceIdentity,RelationshipOccurrence>();primary.occurrences().forEach(o->oldOccurrences.put(o.identity(),o));
        var observed=new HashMap<ContentDigest,List<ObservationRecord>>();for(var o:primary.observations())if(o.span().isPresent())observed.computeIfAbsent(subject(scope,view,o.document(),o.category().value(),o.span().orElseThrow()),key->new ArrayList<>()).add(o);
        var replacements=new HashMap<OccurrenceIdentity,RelationshipOccurrence>();var outcomes=new ArrayList<Object>();var gaps=new ArrayList<CapabilityGapRecord>();var seen=new HashSet<ContentDigest>();
        io.tables.read(table,input->{
            if(!input.text("role").equals("SOURCE")||!version(input.fields().get("provider")).equals(provider))throw invalid("ROW_PROVIDER");
            io.evidence.add(GraphEvidence.capture("java.evidence.compiler-input",input.fields()));
            var handle=JavaEvidenceRecord.handle(input.fields().get("content"));var row=io.read(handle);fields(row,"schema","scope","inputView","primary","provider","document","sourceContent","category","span","caller","targets","status","recovery");
            if(!"java-compiler-attribution-row:m8.4-v1".equals(row.get("schema"))||!scope.equals(row.get("scope"))||!view.value().equals(row.get("inputView"))||!version(row.get("primary")).equals(primary.frontend())||!version(row.get("provider")).equals(provider))throw invalid("ROW_SCOPE");
            var doc=new SourceDocumentIdentity(GraphJson.string(row.get("document")));var source=sources.get(doc);if(source==null)throw invalid("FOREIGN_DOCUMENT");
            var bytes=JavaEvidenceRecord.handle(row.get("sourceContent"));if(!bytes.digest().equals(source.document().contentDigest())||bytes.size()!=source.bytes().length)throw invalid("SOURCE_HASH");io.reference(bytes);
            var span=span(GraphJson.object(row.get("span")));validateSpan(span,source);String category=GraphJson.string(row.get("category"));if(FrontendRequest.CATEGORIES.stream().noneMatch(c->category.equals("java."+c)))throw invalid("CATEGORY");
            var key=subject(scope,view,doc,category,span);if(!seen.add(key))throw invalid("DUPLICATE_LOCATION");
            var caller=JavaEvidenceRecord.handle(row.get("caller"));io.read(caller);var targets=GraphJson.array(row.get("targets")).stream().map(JavaEvidenceRecord::handle).toList();if(targets.stream().distinct().count()!=targets.size())throw invalid("DUPLICATE_TARGET");for(var target:targets)io.read(target);
            var status=SemanticStatus.valueOf(GraphJson.string(row.get("status")));if(!(row.get("recovery") instanceof Boolean recovery))throw invalid("RECOVERY_FLAG");
            if(status==SemanticStatus.RESOLVED&&targets.size()!=1||status==SemanticStatus.AMBIGUOUS&&targets.size()<2||Set.of(SemanticStatus.UNRESOLVED,SemanticStatus.UNSUPPORTED,SemanticStatus.ERROR).contains(status)&&!targets.isEmpty())throw invalid("STATUS");
            var accepted=new ArrayList<JavaEvidenceAuthority.Verified>();
            for(var entry:new TreeMap<>(bundle.proofs()).entrySet()){
                var proof=entry.getValue();if(!entry.getKey().equals(proof.identity())||!proof.record().text("question").equals(JavaSemanticEvidence.QUESTION)||!proof.record().text("subject").equals(key.value())||!proof.record().fields().get("scope").equals(scope)||!proof.record().digest("policy").equals(policy))continue;
                if(!io.checked.contains(proof)){if(!proof.current())continue;io.checked.add(proof);}accepted.add(proof);io.evidence.add(GraphEvidence.capture("java.evidence.proof",proof.record().fields()));io.read(JavaEvidenceRecord.handle(proof.record().fields().get("answer")));
            }
            boolean contrary=accepted.stream().anyMatch(p->!p.answer().equals(handle.digest())&&accepted.stream().noneMatch(a->a.answer().equals(handle.digest())&&a.adjudicationInputs().contains(p.answer())));
            String disposition="UNAVAILABLE";
            var rows=observed.getOrDefault(key,List.of());var expectedCaller=byContent.get(caller.digest());var expectedTarget=targets.size()==1?byContent.get(targets.getFirst().digest()):null;
            if(contrary)disposition="CONFLICT";
            else if(accepted.stream().anyMatch(p->p.answer().equals(handle.digest()))){
                if(recovery)disposition="RECOVERY_REJECTED";
                else if(status!=SemanticStatus.RESOLVED)disposition="NONRESOLVED_ANSWER";
                else if(rows.size()!=1)disposition="UNREQUESTED_LOCATION";
                else if(expectedCaller==null||expectedTarget==null||expectedCaller.status()!=SemanticStatus.RESOLVED||expectedTarget.status()!=SemanticStatus.RESOLVED)disposition="ENTITY_MAPPING_REQUIRED";
                else if(!targetKind(category,expectedTarget.entity().kind()))disposition="TARGET_KIND_CONFLICT";
                else {
                    var observation=rows.getFirst();var old=observation.mappedOccurrence().map(oldOccurrences::get).orElse(null);
                    boolean sourceProcessed=primary.sources().stream().anyMatch(s->s.document().equals(doc)&&s.state()==SourceOutcome.State.PROCESSED);
                    if(!sourceProcessed||Set.of(FrontendResult.State.FAILED,FrontendResult.State.CANCELED,FrontendResult.State.INVALID_INPUT).contains(primary.state()))disposition="SOURCE_ERROR";
                    else if(old==null||observation.provenance()!=ObservationRecord.EvidenceState.VERIFIED||!old.relationship().source().equals(expectedCaller.entity().identity()))disposition="CALLER_MAPPING_REQUIRED";
                    else if(observation.attribution()==SemanticStatus.RESOLVED)disposition=old.relationship().target().equals(new RelationshipTarget.Resolved(expectedTarget.entity().identity()))?"AGREES":"CONFLICT";
                    else if(!Set.of(SemanticStatus.UNRESOLVED,SemanticStatus.UNSUPPORTED).contains(observation.attribution()))disposition="ADJUDICATION_REQUIRED";
                    else {
                        var proof=accepted.stream().filter(p->p.answer().equals(handle.digest())).findFirst().orElseThrow();
                        var relation=SemanticRelationship.create(old.relationship().source(),old.relationship().kind(),new RelationshipTarget.Resolved(expectedTarget.entity().identity()));
                        var replacement=RelationshipOccurrence.create(relation,old.span(),old.ordinal(),SemanticStatus.RESOLVED,new Derivation(DerivationKind.DERIVED,provider,List.of(view.value(),bundle.index().digest().value(),handle.digest().value(),proof.identity().value())),List.of(),List.of());replacements.put(old.identity(),replacement);disposition="APPLIED";
                    }
                }
            }
            outcomes.add(Map.of("subject",key,"answer",handle,"disposition",disposition));
            if(!Set.of("APPLIED","AGREES").contains(disposition))gaps.add(JavaEvidenceGaps.create(scope,Set.of("CONFLICT","TARGET_KIND_CONFLICT").contains(disposition)?JavaEvidenceGaps.Reason.SOURCE_BINARY_CONFLICT:JavaEvidenceGaps.Reason.SEMANTIC_MAPPING_UNPROVEN,bundle.index().digest(),key));
        },bundle.cancelled());
        var occurrences=primary.occurrences().stream().map(o->replacements.getOrDefault(o.identity(),o)).toList();
        var observations=primary.observations().stream().map(o->{var replacement=o.mappedOccurrence().map(replacements::get).orElse(null);return replacement==null?o:new ObservationRecord(o.document(),o.category(),o.span(),SemanticStatus.RESOLVED,ObservationRecord.EvidenceState.VERIFIED,ObservationRecord.EvidenceState.VERIFIED,Optional.of(replacement.identity()),o.reference(),List.of());}).toList();
        var composed=copy(primary,occurrences,observations);
        io.evidence.add(GraphEvidence.capture("java.evidence.compiler-primary",primary));io.evidence.add(GraphEvidence.capture("java.evidence.compiler-outcomes",Map.of("schema","java-compiler-attribution-result:m8.4-v1","inputView",view,"analysis",primary.analysis(),"primary",CanonicalJson.sha256(primary),"result",CanonicalJson.sha256(composed),"index",bundle.index(),"rows",outcomes)));
        var result=new Result(composed,view,request.manifest().snapshot().identity(),provider,gaps,io);if(!result.current())throw invalid("EVIDENCE_REVOKED");return result;
    }
    public static FrontendResult unavailable(FrontendResult primary){return copy(primary,primary.occurrences(),primary.observations());}
    private static boolean targetKind(String category,EntityKind kind){return switch(category){
        case "java.calls"->kind==EntityKind.METHOD;
        case "java.constructor-calls"->kind==EntityKind.CONSTRUCTOR;
        case "java.method-references"->Set.of(EntityKind.METHOD,EntityKind.CONSTRUCTOR).contains(kind);
        case "java.reads-field","java.writes-field"->kind==EntityKind.FIELD;
        case "java.has-parameter"->kind==EntityKind.PARAMETER;
        case "java.declares"->Set.of(EntityKind.TYPE,EntityKind.METHOD,EntityKind.CONSTRUCTOR,EntityKind.FIELD,EntityKind.PARAMETER,EntityKind.TYPE_PARAMETER,EntityKind.RECORD_COMPONENT,EntityKind.INITIALIZER,EntityKind.LAMBDA).contains(kind);
        case "java.type-uses","java.type-argument","java.parameter-type","java.field-type","java.returns","java.throws","java.type-parameter-bound"->Set.of(EntityKind.TYPE,EntityKind.TYPE_PARAMETER).contains(kind);
        default->kind==EntityKind.TYPE;
    };}
    private static FrontendResult copy(FrontendResult p,List<RelationshipOccurrence> occurrences,List<ObservationRecord> observations){return new FrontendResult(p.analysis(),PROVIDER,p.state(),p.declarations(),occurrences,observations,p.sources(),p.coverage(),p.diagnostics(),p.types(),p.annotations(),p.derivedRelationships(),p.typeDeclarations(),p.memberDeclarations());}
    public static ContentDigest subject(Map<String,Object> scope,ContentDigest view,SourceDocumentIdentity document,String category,SourceSpan span){return CanonicalJson.sha256(Map.of("schema","java-compiler-attribution-key:m8.4-v1","scope",scope,"inputView",view,"document",document,"category",category,"span",span));}
    private static IOException invalid(String code){return new IOException("JAVA_COMPILER_"+code);}
    private static void fields(Map<String,Object> value,String... names)throws IOException{if(!value.keySet().equals(Set.of(names)))throw invalid("FIELDS");}
    private static VersionedIdentifier version(Object value)throws IOException{var map=GraphJson.object(value);fields(map,"id","version");return new VersionedIdentifier(GraphJson.string(map.get("id")),GraphJson.string(map.get("version")));}
    private static SourceSpan span(Map<String,Object> value)throws IOException{fields(value,"document","startLine","startColumn","endLine","endColumn");return new SourceSpan(new SourceDocumentIdentity(GraphJson.string(value.get("document"))),integer(value.get("startLine")),integer(value.get("startColumn")),integer(value.get("endLine")),integer(value.get("endColumn")));}
    private static int integer(Object value){return ((java.math.BigDecimal)value).intValueExact();}
    private static void validateSpan(SourceSpan span,SourceInput source)throws IOException{var lines=source.text().split("\\r\\n|\\r|\\n",-1);if(!span.document().equals(source.document().identity())||span.startLine()>lines.length||span.endLine()>lines.length||span.startColumn()>lines[span.startLine()-1].length()+1||span.endColumn()>lines[span.endLine()-1].length()+1)throw invalid("SPAN");}
    private static final class Reader {
        final Bundle bundle;final JavaEvidenceTables tables;final Set<ContentHandle> references=new LinkedHashSet<>();final List<GraphEvidence> evidence=new ArrayList<>();final Set<JavaEvidenceAuthority.Verified> checked=Collections.newSetFromMap(new IdentityHashMap<>());
        Reader(Bundle bundle){this.bundle=bundle;tables=new JavaEvidenceTables(bundle.content(),2_000_000);}
        void reference(ContentHandle handle)throws IOException{if(bundle.cancelled().getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid("CANCELLED_WITH_FRONTIER");tables.requireContent(handle);references.add(handle);}
        Map<String,Object> read(ContentHandle handle)throws IOException{if(handle.size()>2_000_000)throw invalid("WORKER_CAPACITY_REQUIRED");reference(handle);try(var in=bundle.content().open(handle);var reader=new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))){var value=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(2_000_000,64,2_000_000)));if(!CanonicalJson.sha256(value).equals(handle.digest()))throw invalid("NONCANONICAL_PAYLOAD");evidence.add(GraphEvidence.capture("java.evidence.compiler-capture",value));return value;}}
    }
}
