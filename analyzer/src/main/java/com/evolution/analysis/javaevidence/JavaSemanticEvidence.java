package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.SourceSpan;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Non-executing import of an independently captured occurrence census. */
public final class JavaSemanticEvidence {
    public static final String QUESTION="java.semantic-occurrence";
    public record Bundle(RepositoryContentStore content,ContentHandle census,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,int workerRows,BooleanSupplier cancelled,Optional<JavaEvidenceLedger.Published> previous){
        public Bundle(RepositoryContentStore content,ContentHandle census,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,int workerRows,BooleanSupplier cancelled){this(content,census,proofs,workerRows,cancelled,Optional.empty());}
        public Bundle{Objects.requireNonNull(content);Objects.requireNonNull(census);proofs=Map.copyOf(proofs);if(workerRows<1)throw new IllegalArgumentException("JAVA_SEMANTIC_WORKER_BUDGET");Objects.requireNonNull(cancelled);Objects.requireNonNull(previous);}
    }
    public Result reconcile(Map<String,Object> scope,ContentDigest view,ContentDigest policy,FrontendRequest request,FrontendResult actual,Bundle bundle)throws IOException{
        return reconcile(scope,view,policy,request,actual,bundle,Set.of());
    }
    public Result reconcile(Map<String,Object> scope,ContentDigest view,ContentDigest policy,FrontendRequest request,FrontendResult actual,Bundle bundle,Set<String> attributionProviders)throws IOException{
        actual.validateFor(request);
        request.originClasspath();
        if(!request.module().value().equals(scope.get("module"))||!view.value().equals(request.manifest().configuration().values().get("java.evidence-view"))||!Objects.equals(scope.get("context"),request.manifest().configuration().values().get("java.native-context"))||!Objects.equals(scope.get("sourceSet"),request.manifest().configuration().values().get("ingestion.source-set-name"))||!request.reactorSources().isEmpty())throw invalid("REQUEST_SCOPE");
        var io=new Reader(bundle);var census=io.read(bundle.census());fields(census,"schema","scope","inputView","analysis","provider","documents","proof");
        if(!"java-semantic-census:m8.4-v1".equals(census.get("schema"))||!scope.equals(census.get("scope"))||!view.value().equals(census.get("inputView"))||!actual.analysis().value().equals(census.get("analysis")))throw invalid("CENSUS_SCOPE");
        var provider=GraphJson.object(census.get("provider"));fields(provider,"id","version");new VersionedIdentifier(string(provider,"id"),string(provider,"version"));if(provider.get("id").equals(actual.frontend().id())||attributionProviders.contains(string(provider,"id")))throw invalid("INDEPENDENT_PROVIDER_REQUIRED");
        var sourceIndex=new HashMap<SourceDocumentIdentity,SourceInput>();request.sources().forEach(source->sourceIndex.put(source.document().identity(),source));
        var documentTable=JavaEvidenceTables.Table.from(census.get("documents"));if(documentTable.rows()!=sourceIndex.size())throw invalid("DOCUMENT_CENSUS_INCOMPLETE");
        var requirements=new ArrayList<JavaEvidenceRecord>();var decisions=new ArrayList<JavaEvidenceRecord>();var expected=new TreeMap<ContentDigest,Expected>();var seen=new HashSet<SourceDocumentIdentity>();
        var history=new ArrayList<JavaEvidenceRecord>();if(bundle.previous().isPresent())io.tables.read(JavaEvidenceTables.Table.from(bundle.previous().orElseThrow().ledger().fields().get("decisions")),history::add,bundle.cancelled());
        ContentDigest censusKey=censusKey(scope,view,actual.analysis());var censusRequirement=requirement(scope,censusKey,"java.semantic-census",List.of(),List.of());requirements.add(censusRequirement);var censusRequirementKey=JavaEvidenceLedger.requirementKey(censusRequirement);
        boolean[] closed={true};
        io.table(documentTable,input->{
            input(input,provider);var handle=JavaEvidenceRecord.handle(input.fields().get("content"));var document=io.read(handle);fields(document,"schema","document","content","disposition","categories","occurrences");
            if(!"java-semantic-document:m8.4-v1".equals(document.get("schema")))throw invalid("DOCUMENT_SCHEMA");var id=new SourceDocumentIdentity(string(document,"document"));var source=sourceIndex.get(id);if(source==null||!seen.add(id))throw invalid("DOCUMENT_CENSUS_DUPLICATE_OR_FOREIGN");
            var content=JavaEvidenceRecord.handle(document.get("content"));io.reference(content);if(!content.digest().equals(source.document().contentDigest())||content.size()!=source.bytes().length)throw invalid("SOURCE_HASH");
            String disposition=string(document,"disposition");if(!Set.of("CLOSED","PARTIAL","OPAQUE").contains(disposition))throw invalid("DOCUMENT_DISPOSITION");closed[0]&=disposition.equals("CLOSED");
            var categories=GraphJson.object(document.get("categories"));var categoryNames=new TreeSet<String>();FrontendRequest.CATEGORIES.forEach(c->categoryNames.add("java."+c));if(!categories.keySet().equals(categoryNames))throw invalid("CATEGORY_CENSUS_INCOMPLETE");var counts=new TreeMap<String,Long>();for(String category:categoryNames){long count=number(categories.get(category));if(count<0)throw invalid("CATEGORY_COUNT");counts.put(category,0L);}
            io.table(JavaEvidenceTables.Table.from(document.get("occurrences")),row->{
                input(row,provider);var payload=JavaEvidenceRecord.handle(row.fields().get("content"));var answer=io.read(payload);fields(answer,"schema","inputView","analysis","document","category","span","caller","targets","status","proof");
                if(!"java-semantic-occurrence:m8.4-v1".equals(answer.get("schema"))||!view.value().equals(answer.get("inputView"))||!actual.analysis().value().equals(answer.get("analysis"))||!id.value().equals(answer.get("document")))throw invalid("OCCURRENCE_SCOPE");String category=string(answer,"category");if(!categoryNames.contains(category))throw invalid("OCCURRENCE_CATEGORY");
                Optional<SourceSpan> span=Optional.ofNullable(answer.get("span")).map(GraphJson::object).map(JavaSemanticEvidence::span);if(span.isPresent())validateSpan(span.orElseThrow(),source);
                if(answer.get("caller")!=null)io.read(JavaEvidenceRecord.handle(answer.get("caller")));var targets=GraphJson.array(answer.get("targets")).stream().map(JavaEvidenceRecord::handle).toList();if(targets.stream().distinct().count()!=targets.size())throw invalid("DUPLICATE_TARGET");for(var target:targets)io.read(target);
                var status=SemanticStatus.valueOf(string(answer,"status"));if(status==SemanticStatus.RESOLVED&&(targets.size()!=1||answer.get("caller")==null||span.isEmpty())||status==SemanticStatus.AMBIGUOUS&&targets.size()<2||Set.of(SemanticStatus.UNRESOLVED,SemanticStatus.UNSUPPORTED,SemanticStatus.ERROR).contains(status)&&!targets.isEmpty())throw invalid("OCCURRENCE_STATUS");
                var key=locationKey(scope,view,actual.analysis(),id,category,span);if(expected.putIfAbsent(key,new Expected(answer,payload,category,span))!=null)throw invalid("DUPLICATE_LOCATION");counts.put(category,Math.addExact(counts.get(category),1));
            });
            for(String category:categoryNames)if(counts.get(category)!=number(categories.get(category)))throw invalid("CATEGORY_COUNT_MISMATCH");
        });
        if(!seen.equals(sourceIndex.keySet()))throw invalid("DOCUMENT_CENSUS_INCOMPLETE");
        var observed=new TreeMap<ContentDigest,List<ObservationRecord>>();for(var observation:actual.observations()){var key=locationKey(scope,view,actual.analysis(),observation.document(),observation.category().value(),observation.span());observed.computeIfAbsent(key,unused->new ArrayList<>()).add(observation);if(!expected.containsKey(key)){closed[0]=false;requirements.add(requirement(scope,key,observation.category().value(),List.of(),List.of(censusRequirementKey)));}}
        closed[0]&=actual.sources().stream().allMatch(source->source.state()==SourceOutcome.State.PROCESSED)&&observed.values().stream().allMatch(rows->rows.size()==1);
        var censusProof=io.proof(census,censusKey,scope,policy);boolean censusAccepted=closed[0]&&censusProof.isPresent();var censusDecision=withHistory(decision(censusRequirementKey,censusAccepted?censusProof:Optional.empty(),List.of()),history);decisions.add(censusDecision);
        var declarations=new HashMap<EntityIdentity,Entity>();actual.declarations().forEach(d->declarations.put(d.entity().identity(),d.entity()));var occurrences=new HashMap<OccurrenceIdentity,RelationshipOccurrence>();actual.occurrences().forEach(o->occurrences.put(o.identity(),o));
        for(var entry:expected.entrySet()){
            var row=entry.getValue();var requirement=requirement(scope,entry.getKey(),row.category(),List.of(row.payload()),List.of(censusRequirementKey));requirements.add(requirement);
            var observations=observed.getOrDefault(entry.getKey(),List.of());boolean matches=observations.size()==1&&matches(row.fields(),observations.getFirst(),occurrences,declarations);
            var proof=io.proof(row.fields(),entry.getKey(),scope,policy);decisions.add(withHistory(decision(JavaEvidenceLedger.requirementKey(requirement),censusAccepted&&matches?proof:Optional.empty(),censusAccepted?List.of(censusDecision.identity()):List.of()),history));
        }
        var frontend=CanonicalJson.sha256(actual);
        var ids=requirements.stream().map(JavaEvidenceLedger::requirementKey).sorted().toList();var closureFields=Map.of("schema","java-post-analysis-census:m8.4-v1","scope",scope,"inputView",view,"analysis",actual.analysis(),"frontendResult",frontend,"captured",bundle.census(),"requirements",ids);var closureCensus=io.tables.putCanonical(closureFields,bundle.cancelled());
        decisions.addAll(history);decisions=new ArrayList<>(decisions.stream().distinct().toList());
        var publication=new JavaEvidenceLedger().publish(scope,closureCensus,policy,requirements,decisions,List.of(),bundle.previous().map(JavaEvidenceLedger.Published::ledger),bundle.proofs(),Set.of(),censusAccepted?Set.copyOf(ids):Set.of(),io.tables,bundle.cancelled());
        io.reference(io.tables.put(publication.ledger(),bundle.cancelled()));io.reference(io.tables.put(publication.projection(),bundle.cancelled()));
        for(var table:List.of(JavaEvidenceTables.Table.from(publication.ledger().fields().get("requirements")),JavaEvidenceTables.Table.from(publication.ledger().fields().get("decisions")),JavaEvidenceTables.Table.from(publication.ledger().fields().get("bindings")),JavaEvidenceTables.Table.from(publication.projection().fields().get("current"))))io.reference(table.content());
        for(var proofId:publication.current().rows().values().stream().flatMap(row->row.proofs().stream()).distinct().toList()){
            var proof=bundle.proofs().get(proofId);if(proof!=null){io.evidence.add(GraphEvidence.capture("java.evidence.proof",proof.record().fields()));io.read(JavaEvidenceRecord.handle(proof.record().fields().get("answer")));io.checked.add(proof);}
        }
        io.reference(closureCensus);var evidence=new ArrayList<GraphEvidence>(io.evidence);publication.current().conflicts().values().forEach(conflict->evidence.add(GraphEvidence.capture("java.evidence.post-conflict",conflict)));evidence.add(GraphEvidence.capture("java.evidence.post-census",closureFields));for(var requirement:requirements)evidence.add(GraphEvidence.capture("java.evidence.post-requirement",requirement.fields()));for(var decision:decisions)evidence.add(GraphEvidence.capture("java.evidence.post-decision",decision.fields()));evidence.add(GraphEvidence.capture("java.evidence.post-ledger",publication.ledger().fields()));evidence.add(GraphEvidence.capture("java.evidence.post-projection",publication.projection().fields()));
        io.tables.read(JavaEvidenceTables.Table.from(publication.projection().fields().get("current")),row->evidence.add(GraphEvidence.capture("java.evidence.post-current",row.fields())),bundle.cancelled());
        var gaps=new ArrayList<CapabilityGapRecord>();for(var row:publication.current().rows().entrySet())if(row.getValue().state()!=JavaEvidenceLedger.State.SATISFIED)gaps.add(JavaEvidenceGaps.create(scope,row.getValue().state()==JavaEvidenceLedger.State.CONFLICTED?JavaEvidenceGaps.Reason.VALIDATION_REJECTED:row.getValue().state()==JavaEvidenceLedger.State.REOPENED?JavaEvidenceGaps.Reason.EVIDENCE_REVOKED:JavaEvidenceGaps.Reason.SEMANTIC_MAPPING_UNPROVEN,publication.projection().identity(),row.getKey()));
        return new Result(view,frontend,request.manifest().snapshot().identity(),publication,gaps,evidence,io.references,List.copyOf(io.checked),bundle);
    }
    public static final class Result{
        private final ContentDigest view,frontend;private final SnapshotIdentity snapshot;private final JavaEvidenceLedger.Published publication;private final List<CapabilityGapRecord> gaps;private final List<GraphEvidence> evidence;private final Set<ContentHandle> references;private final List<JavaEvidenceAuthority.Verified> proofs;private final Bundle bundle;
        private Result(ContentDigest view,ContentDigest frontend,SnapshotIdentity snapshot,JavaEvidenceLedger.Published publication,List<CapabilityGapRecord> gaps,List<GraphEvidence> evidence,Set<ContentHandle> references,List<JavaEvidenceAuthority.Verified> proofs,Bundle bundle){this.view=view;this.frontend=frontend;this.snapshot=snapshot;this.publication=publication;this.gaps=List.copyOf(gaps);this.evidence=List.copyOf(evidence);this.references=Set.copyOf(references);this.proofs=List.copyOf(proofs);this.bundle=bundle;}
        public ContentDigest frontend(){return frontend;}
        public long active(){return publication.current().active();}public ContentDigest view(){return view;}public SnapshotIdentity snapshot(){return snapshot;}public JavaEvidenceLedger.Published publication(){return publication;}public List<CapabilityGapRecord> gaps(){return gaps;}public List<GraphEvidence> evidence(){return evidence;}
        public boolean current()throws IOException{for(var reference:references)if(!bundle.content().verify(reference))return false;for(var proof:proofs)if(!proof.current())return false;return true;}
    }
    private record Expected(Map<String,Object> fields,ContentHandle payload,String category,Optional<SourceSpan> span){}
    public static Map<String,Object> answerFields(Map<String,Object> fields){var result=new TreeMap<>(fields);result.remove("proof");return Collections.unmodifiableMap(result);}
    public static ContentDigest censusKey(Map<String,Object> scope,ContentDigest view,AnalysisIdentity analysis){return CanonicalJson.sha256(Map.of("schema","java-semantic-census-key:m8.4-v1","scope",scope,"inputView",view,"analysis",analysis));}
    public static ContentDigest locationKey(Map<String,Object> scope,ContentDigest view,AnalysisIdentity analysis,SourceDocumentIdentity document,String category,Optional<SourceSpan> span){return CanonicalJson.sha256(Map.of("schema","java-semantic-location:m8.4-v1","scope",scope,"inputView",view,"analysis",analysis,"document",document,"category",category,"span",span));}
    private static JavaEvidenceRecord requirement(Map<String,Object> scope,ContentDigest subject,String category,List<ContentHandle> occurrences,List<ContentDigest> prerequisites){return new JavaEvidenceCodec().create(Map.of("schema","java-closure-requirement:m8.4-v1","scope",scope,"question",QUESTION,"subject",subject,"category",category,"predicate",new VersionedIdentifier(QUESTION,JavaEvidenceAuthority.VERSION),"gapRefs",List.of(),"occurrences",occurrences,"readinessRefs",List.of(),"prerequisites",prerequisites));}
    private static JavaEvidenceRecord decision(ContentDigest requirement,Optional<JavaEvidenceAuthority.Verified> proof,List<ContentDigest> prerequisites){var fields=new TreeMap<String,Object>();fields.put("schema","java-closure-decision:m8.4-v1");fields.put("requirement",requirement);fields.put("state",proof.isPresent()?"SATISFIED":"PENDING");fields.put("proofs",proof.stream().map(JavaEvidenceAuthority.Verified::identity).toList());fields.put("prerequisites",prerequisites);fields.put("conflicts",List.of());fields.put("supersedes",List.of());fields.put("reason",proof.isPresent()?null:"PRODUCER_UNAVAILABLE");return new JavaEvidenceCodec().create(fields);}
    private static JavaEvidenceRecord withHistory(JavaEvidenceRecord current,List<JavaEvidenceRecord> history){
        var superseded=new HashSet<ContentDigest>();history.forEach(d->superseded.addAll(JavaEvidenceLedger.refs(d,"supersedes")));
        var heads=history.stream().filter(d->d.digest("requirement").equals(current.digest("requirement"))&&!superseded.contains(d.identity())).toList();
        boolean reopen=current.text("state").equals("PENDING")&&heads.stream().anyMatch(d->d.text("state").equals("SATISFIED"));
        boolean recover=current.text("state").equals("SATISFIED")&&heads.stream().anyMatch(d->d.text("state").equals("REOPENED"));
        if(!reopen&&!recover)return current;var fields=new TreeMap<>(current.fields());fields.put("supersedes",heads.stream().map(JavaEvidenceRecord::identity).sorted().toList());if(reopen){fields.put("state","REOPENED");fields.put("reason","EVIDENCE_REVOKED");}return new JavaEvidenceCodec().create(fields);
    }
    private static boolean matches(Map<String,Object> expected,ObservationRecord observation,Map<OccurrenceIdentity,RelationshipOccurrence> occurrences,Map<EntityIdentity,Entity> declarations){
        if(!"RESOLVED".equals(expected.get("status"))||observation.attribution()!=SemanticStatus.RESOLVED||observation.origin()!=ObservationRecord.EvidenceState.VERIFIED||observation.provenance()!=ObservationRecord.EvidenceState.VERIFIED||observation.mappedOccurrence().isEmpty())return false;
        var actual=occurrences.get(observation.mappedOccurrence().orElseThrow());if(actual==null||actual.status()!=SemanticStatus.RESOLVED||!(actual.relationship().target() instanceof RelationshipTarget.Resolved target))return false;
        var caller=declarations.get(actual.relationship().source());var entity=declarations.get(target.target());return caller!=null&&entity!=null&&CanonicalJson.sha256(caller).equals(JavaEvidenceRecord.handle(expected.get("caller")).digest())&&CanonicalJson.sha256(entity).equals(JavaEvidenceRecord.handle(GraphJson.array(expected.get("targets")).getFirst()).digest());
    }
    private static void input(JavaEvidenceRecord row,Map<String,Object> provider)throws IOException{if(!row.text("role").equals("SOURCE")||!row.object("provider").equals(provider))throw invalid("TABLE_PROVIDER_OR_ROLE");}
    private static String string(Map<String,Object> fields,String key){return GraphJson.string(fields.get(key));}
    private static long number(Object value){return ((java.math.BigDecimal)value).longValueExact();}
    private static void fields(Map<String,Object> map,String... names)throws IOException{if(!map.keySet().equals(Set.of(names)))throw invalid("FIELDS");}
    private static SourceSpan span(Map<String,Object> map){if(!map.keySet().equals(Set.of("document","startLine","startColumn","endLine","endColumn")))throw new IllegalArgumentException("JAVA_SEMANTIC_SPAN_FIELDS");return new SourceSpan(new SourceDocumentIdentity(string(map,"document")),Math.toIntExact(number(map.get("startLine"))),Math.toIntExact(number(map.get("startColumn"))),Math.toIntExact(number(map.get("endLine"))),Math.toIntExact(number(map.get("endColumn"))));}
    private static void validateSpan(SourceSpan span,SourceInput source)throws IOException{String[] lines=source.text().split("\\r\\n|\\r|\\n",-1);if(!span.document().equals(source.document().identity())||span.startLine()>lines.length||span.endLine()>lines.length||span.startColumn()>lines[span.startLine()-1].length()+1||span.endColumn()>lines[span.endLine()-1].length()+1)throw invalid("SPAN_OUTSIDE_SOURCE");}
    private static IOException invalid(String reason){return new IOException("JAVA_SEMANTIC_"+reason);}
    private static final class Reader{
        private final Bundle bundle;private final JavaEvidenceTables tables;private final Set<ContentHandle> references=new LinkedHashSet<>();private final List<GraphEvidence> evidence=new ArrayList<>();private long rows;private final Set<JavaEvidenceAuthority.Verified> checked=Collections.newSetFromMap(new IdentityHashMap<>());
        Reader(Bundle bundle){this.bundle=bundle;tables=new JavaEvidenceTables(bundle.content(),2_000_000);}
        void reference(ContentHandle handle)throws IOException{if(bundle.cancelled().getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid("CANCELLED_WITH_FRONTIER");if(references.add(handle))tables.requireContent(handle);}
        Map<String,Object> read(ContentHandle handle)throws IOException{reference(handle);if(handle.size()>2_000_000)throw invalid("WORKER_CAPACITY_REQUIRED");try(var in=bundle.content().open(handle);var reader=new InputStreamReader(in,StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))){var data=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(2_000_000,64,2_000_000)));if(!CanonicalJson.sha256(data).equals(handle.digest()))throw invalid("NONCANONICAL_PAYLOAD");evidence.add(GraphEvidence.capture("java.evidence.semantic-capture",data));return data;}}
        void table(JavaEvidenceTables.Table table,JavaEvidenceTables.Consumer consumer)throws IOException{if(!table.kind().equals("java-evidence-input"))throw invalid("TABLE_KIND");rows=Math.addExact(rows,table.rows());if(rows>bundle.workerRows())throw invalid("WORKER_CAPACITY_REQUIRED");reference(table.content());tables.read(table,row->{evidence.add(GraphEvidence.capture("java.evidence.semantic-input",row.fields()));consumer.accept(row);},bundle.cancelled());}
        Optional<JavaEvidenceAuthority.Verified> proof(Map<String,Object> fields,ContentDigest subject,Map<String,Object> scope,ContentDigest policy)throws IOException{if(fields.get("proof")==null)return Optional.empty();var id=new ContentDigest(string(fields,"proof"));var proof=bundle.proofs().get(id);if(proof==null||!proof.identity().equals(id)||!proof.record().text("question").equals(QUESTION)||!proof.record().text("subject").equals(subject.value())||!proof.record().fields().get("scope").equals(scope)||!proof.record().digest("policy").equals(policy)||!proof.answer().equals(CanonicalJson.sha256(answerFields(fields))))return Optional.empty();if(!checked.contains(proof)){if(!proof.current())return Optional.empty();checked.add(proof);evidence.add(GraphEvidence.capture("java.evidence.proof",proof.record().fields()));evidence.add(GraphEvidence.capture("java.evidence.semantic-answer",answerFields(fields)));}return Optional.of(proof);}
    }
}
