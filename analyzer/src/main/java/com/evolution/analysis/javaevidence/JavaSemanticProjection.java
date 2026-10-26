package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.ingestion.UniversalArchitecturePipeline;
import java.io.IOException;
import java.util.*;

/** Separate identities for canonical Java meaning and its captured audit binding. */
public final class JavaSemanticProjection {
    public static final VersionedIdentifier VERSION=new VersionedIdentifier("java.captured-semantic-projection","m8.4-v1");
    // Only these documented reference fields are replaced by their typed semantic inputs.
    // Every other option, diagnostic and uncertainty is preserved byte-for-byte.
    private static final Set<String> REFERENCES=Set.of("java.evidence-view","java.generated-overlay","java.base-inventory","java.base-snapshot",
            "java.pre-analysis-closure","java.native-context","java.binary-native-origins","java.classpath-manifest","java.source-decoding",
            "java.source-binary-correspondence","java.compiler-attribution","ingestion.build-model","ingestion.source-input");
    public record Result(ContentDigest semanticIdentity,ContentDigest auditIdentity,String canonicalProjection,ContentDigest inputsIdentity,ContentDigest component,FrontendResult.State state,GraphEvidence audit){
        public Result {
            if(!ContentDigest.sha256Utf8(canonicalProjection).equals(semanticIdentity)||!audit.identity().equals(auditIdentity))throw new IllegalArgumentException("JAVA_SEMANTIC_PROJECTION_DIGEST");
        }
        /** A fresh decoded copy cannot mutate the captured canonical bytes or their identity. */
        public Map<String,Object> projection(){return GraphJson.object(GraphJson.read(canonicalProjection,new GraphJson.Limits(canonicalProjection.length(),128,canonicalProjection.length())));}
        public List<GraphEvidence> evidence(){
            return List.of(GraphEvidence.capture("java.evidence.semantic-projection",Map.of("version",VERSION,"semanticIdentity",semanticIdentity,
                    "component",component,"auditIdentity",auditIdentity,"scope","CAPTURED_JAVA_CONTEXT","inputIdentity",inputsIdentity,"state",state)),audit);
        }
    }
    /** Pin the implementation of this additive projection independently of its nominal version. */
    public static ContentDigest componentIdentity()throws IOException{
        var classes=new TreeMap<String,ContentDigest>();var pending=new ArrayDeque<Class<?>>();pending.add(JavaSemanticProjection.class);
        while(!pending.isEmpty()){
            var type=pending.removeFirst();Collections.addAll(pending,type.getDeclaredClasses());
            try(var input=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){
                if(input==null)throw invalid("COMPONENT_BYTES_MISSING");classes.put(type.getName(),ContentDigest.sha256(input.readAllBytes()));
            }
        }
        return CanonicalJson.sha256(classes);
    }
    public Result project(JavaEvidenceAssembler.Prepared prepared,UniversalArchitecturePipeline.DetailedResult detailed)throws IOException{
        if(!prepared.activeGaps().isEmpty()||!prepared.invalidationGaps().isEmpty())throw invalid("PRE_ANALYSIS_OPEN");
        var graphInput=detailed.graphInput().orElseThrow(()->invalid("GRAPH_INPUT_MISSING"));
        if(!graphInput.repository().snapshot().identity().equals(prepared.inputs().snapshot().identity()))throw invalid("INPUT_SCOPE");
        var binding=graphInput.javaEvidence().orElseThrow(()->invalid("PROTECTED_BINDING_MISSING"));binding.requireCurrent();binding.validateFor(graphInput.ingestion());
        var ingestion=graphInput.ingestion().orElseThrow(()->invalid("INGESTION_MISSING"));
        var frontends=ingestion.units().stream().flatMap(u->u.frontend().stream()).toList();if(frontends.size()!=1)throw invalid("CONTEXT_ARITY");
        var actual=frontends.getFirst();
        var request=ingestion.sourceIngestion().outcomes().stream().flatMap(o->o.request().stream()).filter(r->r.manifest().identity().equals(actual.analysis())).findFirst().orElseThrow(()->invalid("REQUEST_MISSING"));
        if(!prepared.view().identity().value().equals(request.manifest().configuration().values().get("java.evidence-view")))throw invalid("VIEW_SCOPE");
        for(var expected:prepared.options().entrySet())if(!expected.getValue().equals(request.manifest().configuration().values().get(expected.getKey())))throw invalid("VIEW_OPTIONS");
        actual.validateFor(request);request.originClasspath();
        var post=binding.evidence().stream().filter(e->e.family().equals("java.evidence.post-projection")).findFirst().orElseThrow(()->invalid("SEMANTIC_CENSUS_MISSING"));
        var postFields=read(post);var counts=GraphJson.object(postFields.get("counts"));
        if(!"CLOSED".equals(postFields.get("denominator"))||((java.math.BigDecimal)counts.get("active")).signum()!=0)throw invalid("SEMANTIC_CENSUS_OPEN");
        var normalizer=new Normalizer(prepared,request,binding.evidence());
        var options=semanticOptions(request.manifest().configuration().values());
        var component=componentIdentity();
        var inputs=new LinkedHashMap<String,Object>();inputs.put("schema","java-semantic-inputs:m8.4-v1");inputs.put("version",VERSION);inputs.put("component",component);
        inputs.put("baseSnapshot",prepared.base().snapshot().identity());inputs.put("snapshot",prepared.inputs().snapshot().identity());
        inputs.put("documents",prepared.inputs().snapshot().documents());inputs.put("files",prepared.inputs().snapshot().files());
        inputs.put("module",request.module());inputs.put("sourceSet",prepared.view().object("scope").get("sourceSet"));inputs.put("variant",prepared.view().object("scope").get("variant"));
        inputs.put("platform",request.platform().entry());inputs.put("analyzer",request.manifest().analyzer());inputs.put("rules",request.manifest().ruleSet());inputs.put("graphSchema",request.manifest().graphSchema());
        inputs.put("policy",prepared.policy());inputs.put("options",options);inputs.put("slots",normalizer.slots.values().stream().sorted(Comparator.comparing(CanonicalJson::write)).toList());
        inputs.put("sources",request.sources().stream().map(s->Map.of("document",s.document(),"decoding",Map.of("schema",s.decoding().schemaVersion(),"charset",s.decoding().charset(),"origin",s.decoding().origin(),"bom",s.decoding().bom(),"lineEndings",s.decoding().lineEndings()))).toList());
        inputs.put("generators",prepared.graph().evidence().stream().filter(e->e.family().equals("java.evidence.lineage")).map(JavaSemanticProjection::read)
                .map(row->selected(row,List.of("generator","compiler","toolchain","recipe","closure"))).sorted(Comparator.comparing(CanonicalJson::write)).toList());
        inputs.put("compiler",normalizer.compilerRows.values().stream().map(JavaSemanticProjection::compilerAnswer).sorted(Comparator.comparing(CanonicalJson::write)).toList());
        var resolution=prepared.graph().evidence().stream().filter(e->e.family().equals("java.evidence.resolution")).findFirst().orElseThrow(()->invalid("RESOLUTION_MISSING"));
        inputs.put("compilerOptions",selected(read(resolution),List.of("compiler","sourceLanguage","bytecodeTarget","release","preview","encoding","arguments","argfiles")));
        var facts=new LinkedHashMap<String,Object>();facts.put("schema","java-semantic-projection:m8.4-v1");facts.put("component",component);facts.put("inputsIdentity",CanonicalJson.sha256(inputs));facts.put("inputs",inputs);
        facts.put("frontend",actual.frontend());facts.put("state",actual.state());facts.put("semanticCensus",selected(postFields,List.of("denominator","counts")));
        var declarations=new ArrayList<DeclarationRecord>();for(var d:actual.declarations())declarations.add(new DeclarationRecord(d.entity(),d.spelling(),d.status(),normalizer.derivation(d.derivation()),d.uncertainties(),d.diagnostics()));
        var occurrences=new ArrayList<RelationshipOccurrence>();for(var o:actual.occurrences())occurrences.add(new RelationshipOccurrence(o.identity(),o.relationship(),o.span(),o.ordinal(),o.status(),normalizer.derivation(o.derivation()),o.uncertainties(),o.diagnostics()));
        var derived=new ArrayList<DerivedRelationshipRecord>();for(var d:actual.derivedRelationships())derived.add(new DerivedRelationshipRecord(d.relationship(),d.status(),normalizer.derivation(d.derivation()),d.supportingSpans(),d.uncertainties(),d.diagnostics()));
        facts.put("declarations",declarations);facts.put("occurrences",occurrences);facts.put("derivedRelationships",derived);
        facts.put("observations",actual.observations());facts.put("sources",actual.sources());facts.put("coverage",actual.coverage());facts.put("diagnostics",actual.diagnostics());
        facts.put("types",actual.types());facts.put("annotations",actual.annotations());facts.put("typeDeclarations",actual.typeDeclarations());facts.put("memberDeclarations",actual.memberDeclarations());
        facts.put("semanticReferences",normalizer.references.entrySet().stream().map(e->Map.of("identity",e.getKey(),"body",e.getValue())).toList());
        String canonical=CanonicalJson.write(facts);var semantic=ContentDigest.sha256Utf8(canonical);
        var audit=GraphEvidence.capture("java.evidence.semantic-audit-binding",Map.of("version",VERSION,"projection",semantic,"inputView",prepared.view().identity(),
                "analysis",actual.analysis(),"frontendResult",CanonicalJson.sha256(actual),"report",detailed.report().identity(),"policy",prepared.policy(),
                "capturedEvidence",binding.evidence().stream().map(GraphEvidence::identity).sorted().toList()));
        return new Result(semantic,audit.identity(),canonical,CanonicalJson.sha256(inputs),component,actual.state(),audit);
    }
    static Map<String,String> semanticOptions(Map<String,String> options){var values=new TreeMap<>(options);REFERENCES.forEach(values::remove);return Collections.unmodifiableMap(values);}
    static Object semanticSlot(JavaEvidenceRecord slot)throws IOException{return Normalizer.semanticSlot(slot);}
    private static Map<String,Object> read(GraphEvidence evidence){return GraphJson.object(GraphJson.read(evidence.canonicalJson(),GraphJson.Limits.defaults()));}
    private static Map<String,Object> selected(Map<String,Object> value,List<String> fields){var result=new TreeMap<String,Object>();for(String field:fields)if(value.containsKey(field))result.put(field,value.get(field));return result;}
    private static Map<String,Object> compilerAnswer(Map<String,Object> row){return selected(row,List.of("primary","provider","document","sourceContent","category","span","caller","targets","status","recovery"));}
    private static IOException invalid(String reason){return new IOException("JAVA_SEMANTIC_PROJECTION_"+reason);}
    private static final class Normalizer {
        final JavaEvidenceAssembler.Prepared prepared;final FrontendRequest request;
        final Map<ContentDigest,Object> slots=new HashMap<>();final Map<ContentDigest,Object> references=new TreeMap<>();
        final Map<ContentDigest,Map<String,Object>> compilerRows=new HashMap<>();final Map<ContentDigest,Map<String,Object>> proofs=new HashMap<>();
        final Set<String> auditReferences=new HashSet<>();final Set<String> compilerIndexes=new HashSet<>();
        Normalizer(JavaEvidenceAssembler.Prepared prepared,FrontendRequest request,List<GraphEvidence> evidence)throws IOException{
            this.prepared=prepared;this.request=request;
            auditReferences.add(prepared.view().identity().value());auditReferences.add(prepared.overlay().identity().value());auditReferences.add(request.manifest().identity().value());
            var codec=new JavaEvidenceCodec();
            for(var item:evidence){var value=read(item);
                if(item.family().equals("java.evidence.resolution-slot")){var slot=codec.create(value);slots.put(slot.identity(),semanticSlot(slot));auditReferences.add(slot.identity().value());}
                if(item.family().equals("java.evidence.proof")){var id=CanonicalJson.sha256(value);proofs.put(id,value);auditReferences.add(id.value());}
                if(item.family().equals("java.evidence.compiler-capture")&&"java-compiler-attribution-row:m8.4-v1".equals(value.get("schema")))compilerRows.put(CanonicalJson.sha256(value),value);
                if(item.family().equals("java.evidence.compiler-outcomes"))compilerIndexes.add(JavaEvidenceRecord.handle(value.get("index")).digest().value());
            }
            auditReferences.addAll(compilerIndexes);
        }
        static Object semanticSlot(JavaEvidenceRecord slot)throws IOException{
            var fields=new LinkedHashMap<String,Object>();fields.put("space",slot.text("space"));fields.put("ordinal",slot.number("ordinal"));fields.put("kind",slot.text("kind"));fields.put("origin",slot.text("origin"));fields.put("disposition",slot.text("disposition"));
            fields.put("owner",slot.text("origin").equals("PROJECT")?slot.fields().get("module"):slot.text("logicalName"));
            if(slot.text("kind").equals("CLASS_DIRECTORY")){
                if(slot.fields().get("selectedView")==null)throw invalid("DIRECTORY_VIEW_REQUIRED");
                fields.put("capturedTreeBytes",JavaEvidenceRecord.handle(slot.fields().get("selectedView")));
            }else{
                fields.put("original",JavaEvidenceRecord.handle(slot.fields().get("original")));
                fields.put("selectedView",Optional.ofNullable(slot.fields().get("selectedView")).map(JavaEvidenceRecord::handle));
            }
            return fields;
        }
        ContentDigest reference(Object body){var key=CanonicalJson.sha256(body);references.putIfAbsent(key,body);return key;}
        Derivation derivation(Derivation source)throws IOException{
            var ids=Set.copyOf(source.inputIdentities());Object semantic=null;
            if(source.method().id().equals("java.generated-source-import")){
                boolean content=request.sources().stream().anyMatch(s->ids.contains(s.document().contentDigest().value()));
                if(ids.size()!=2||!ids.contains(prepared.overlay().identity().value())||!content)throw invalid("GENERATED_DERIVATION_BINDING");
                semantic=Map.of("schema","java-semantic-generated-source:m8.4-v1","snapshot",prepared.inputs().snapshot().identity(),"content",ids.stream().filter(id->!id.equals(prepared.overlay().identity().value())).findFirst().orElseThrow());
            }else if(source.method().id().equals("java.native-binary-import")){
                for(var binary:request.dependencies())if(binary.nativeOrigin().isPresent()){
                    var origin=binary.nativeOrigin().orElseThrow();
                    if(ids.equals(Set.of(origin.slot().value(),CanonicalJson.sha256(origin.canonicalForm()).value(),origin.original().contentDigest().value()))){semantic=slots.get(origin.slot());break;}
                }
                if(semantic==null)throw invalid("BINARY_DERIVATION_BINDING");
            }else if(source.method().id().equals("java.native-correspondence")){
                if(request.correspondence().isPresent())for(var mapping:request.correspondence().orElseThrow().mappings())
                    if(ids.equals(Set.of(mapping.record().identity().value(),mapping.record().text("proof"),mapping.source().identity().value()))){
                        var endpoint=mapping.endpoint();var slot=slots.get(endpoint.slot());if(slot==null)throw invalid("CORRESPONDENCE_SLOT_MISSING");
                        semantic=Map.of("schema","java-semantic-source-binary-member:m8.4-v1","slot",slot,"source",mapping.source(),
                                "endpoint",Map.of("package",endpoint.packageName(),"owners",endpoint.owners(),"kind",endpoint.kind(),"name",endpoint.name(),"descriptor",endpoint.descriptor()),
                                "sourceContent",mapping.record().fields().get("sourceContent"),"outcome",mapping.record().fields().get("outcome"),"supportingSpans",mapping.record().fields().get("supportingSpans"));break;
                    }
                if(semantic==null)throw invalid("CORRESPONDENCE_DERIVATION_BINDING");
            }else if(source.kind()==DerivationKind.DERIVED&&ids.contains(prepared.view().identity().value())&&!compilerIndexes.isEmpty()){
                for(var row:compilerRows.entrySet())if(ids.contains(row.getKey().value())&&source.method().equals(version(row.getValue().get("provider"))))
                    for(var proof:proofs.entrySet())if(JavaEvidenceRecord.handle(proof.getValue().get("answer")).digest().equals(row.getKey()))
                        for(var index:compilerIndexes)if(ids.equals(Set.of(prepared.view().identity().value(),index,row.getKey().value(),proof.getKey().value())))semantic=compilerAnswer(row.getValue());
                if(semantic==null)throw invalid("COMPILER_DERIVATION_BINDING");
            }else if(ids.stream().anyMatch(auditReferences::contains))throw invalid("DERIVATION_SCHEMA_REQUIRED");
            return semantic==null?source:new Derivation(source.kind(),source.method(),List.of(reference(semantic).value()));
        }
        private static VersionedIdentifier version(Object value){var map=GraphJson.object(value);return new VersionedIdentifier(GraphJson.string(map.get("id")),GraphJson.string(map.get("version")));}
    }
}
