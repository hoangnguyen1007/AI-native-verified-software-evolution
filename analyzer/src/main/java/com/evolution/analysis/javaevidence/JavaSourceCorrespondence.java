package com.evolution.analysis.javaevidence;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.*;
import com.evolution.analysis.contract.semantic.*;
import com.evolution.analysis.contract.source.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.frontend.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Protected per-member source/native correspondence. JSON alone is not an augmentation capability. */
public final class JavaSourceCorrespondence {
    public static final String PREDICATE="java.source-binary-correspondence";
    private final RepositoryContentStore content;
    public JavaSourceCorrespondence(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}
    public static Object answerFields(Map<String,Object> scope,JavaEvidenceRecord slot,List<JavaEvidenceRecord> rows){
        return Map.of("schema","java-source-binary-census:m8.4-v1","scope",scope,"slot",slot.identity(),"members",rows.stream().map(row->{var map=new TreeMap<>(row.fields());map.remove("proof");return map;}).sorted(Comparator.comparing(com.evolution.analysis.contract.serialization.CanonicalJson::write)).toList());
    }
    public Verified importTable(Map<String,Object> scope,JavaEvidenceTables.Table table,List<JavaEvidenceRecord> slots,List<SourceInput> sources,Map<ContentDigest,JavaEvidenceAuthority.Verified> proofs,int workerRows,BooleanSupplier cancelled)throws IOException{
        if(workerRows<1||table.rows()>workerRows||slots.size()>workerRows)throw new IOException("JAVA_CORRESPONDENCE_WORKER_REQUIRED");
        if(!table.kind().equals("java-evidence-input"))throw new IOException("JAVA_CORRESPONDENCE_TABLE_KIND");
        var slotIndex=new HashMap<ContentDigest,JavaEvidenceRecord>();
        for(var slot:slots){JavaEvidenceLedger.kind(slot,"java-resolution-slot");JavaGeneratedOverlay.sameScope(scope,slot);if(slotIndex.putIfAbsent(slot.identity(),slot)!=null)throw new IOException("JAVA_CORRESPONDENCE_DUPLICATE_SLOT");}
        var sourceIndex=new HashMap<SourceDocumentIdentity,SourceInput>();for(var source:sources)if(sourceIndex.putIfAbsent(source.document().identity(),source)!=null)throw new IOException("JAVA_CORRESPONDENCE_DUPLICATE_SOURCE");
        var tables=new JavaEvidenceTables(content,2_000_000);var references=new LinkedHashSet<ContentHandle>();references.add(table.content());
        var mappings=new ArrayList<Mapping>();var bySlot=new HashMap<ContentDigest,List<JavaEvidenceRecord>>();var keys=new HashSet<String>();
        tables.read(table,input->{
            if(!input.text("role").equals("CORRESPONDENCE"))throw new IOException("JAVA_CORRESPONDENCE_ROW_ROLE");
            var payloadHandle=JavaEvidenceRecord.handle(input.fields().get("content"));references.add(payloadHandle);var row=tables.read(payloadHandle);JavaEvidenceLedger.kind(row,"java-correspondence");JavaGeneratedOverlay.sameScope(scope,row);
            if(!Set.of("SAME_DECLARATION","AUGMENTS").contains(row.text("outcome")))throw new IOException("JAVA_CORRESPONDENCE_UNPROVEN");
            if(!row.object("predicate").equals(Map.of("id",PREDICATE,"version",JavaEvidenceAuthority.VERSION)))throw new IOException("JAVA_CORRESPONDENCE_PREDICATE");
            var sourceHandle=JavaEvidenceRecord.handle(row.fields().get("sourceEvidence"));var endpointHandle=JavaEvidenceRecord.handle(row.fields().get("binaryOrGeneratedEvidence"));references.add(sourceHandle);references.add(endpointHandle);
            Entity sourceEntity=entity(read(sourceHandle));var endpoint=endpoint(read(endpointHandle));references.add(endpoint.transformation());
            if(!CanonicalJson.sha256(sourceEntity).equals(sourceHandle.digest())||!CanonicalJson.sha256(endpoint.canonicalForm()).equals(endpointHandle.digest()))throw new IOException("JAVA_CORRESPONDENCE_TYPED_REFERENCE_MISMATCH");
            var source=sourceEntity.declaration().map(SourceSpan::document).map(sourceIndex::get).orElseThrow(()->new IOException("JAVA_CORRESPONDENCE_SOURCE_REQUIRED"));
            if(sourceEntity.origin()!=EntityOrigin.PROJECT||!sourceEntity.stableScope().value().equals(scope.get("module"))||!source.document().module().value().equals(scope.get("module")))throw new IOException("JAVA_CORRESPONDENCE_SOURCE_SCOPE");
            var sourceBytes=JavaEvidenceRecord.handle(row.fields().get("sourceContent"));references.add(sourceBytes);
            if(!sourceBytes.digest().equals(source.document().contentDigest())||sourceBytes.size()!=source.bytes().length)throw new IOException("JAVA_CORRESPONDENCE_SOURCE_HASH");
            validateSpan(sourceEntity.declaration().orElseThrow(),source);
            if(row.text("outcome").equals("AUGMENTS")&&sourceEntity.kind()!=EntityKind.TYPE||row.text("outcome").equals("SAME_DECLARATION")&&sourceEntity.kind()!=endpoint.kind())throw new IOException("JAVA_CORRESPONDENCE_SOURCE_KIND");
            var slot=slotIndex.get(endpoint.slot());
            if(slot==null||!Set.of("CLASS_PATH","BINARY_EVIDENCE").contains(slot.text("space"))||!slot.text("disposition").equals("ACCEPTED")||!slot.text("origin").equals("PROJECT")||!Objects.equals(slot.fields().get("module"),scope.get("module")))throw new IOException("JAVA_CORRESPONDENCE_OUTPUT_SCOPE");
            var output=JavaEvidenceRecord.handle(row.fields().get("outputContent"));references.add(output);if(!output.equals(JavaEvidenceRecord.handle(slot.fields().get("original"))))throw new IOException("JAVA_CORRESPONDENCE_OUTPUT_HASH");
            for(Object value:GraphJson.array(row.fields().get("supportingSpans"))){var handle=JavaEvidenceRecord.handle(value);references.add(handle);var span=span(read(handle));if(!CanonicalJson.sha256(span).equals(handle.digest()))throw new IOException("JAVA_CORRESPONDENCE_TYPED_REFERENCE_MISMATCH");if(!span.document().equals(source.document().identity()))throw new IOException("JAVA_CORRESPONDENCE_SUPPORTING_DOCUMENT");validateSpan(span,source);}
            String key=endpoint.slot()+"/"+endpoint.binaryName()+"/"+endpoint.kind()+"/"+endpoint.name()+"/"+endpoint.descriptor();if(!keys.add(key))throw new IOException("JAVA_CORRESPONDENCE_DUPLICATE_MEMBER");
            bySlot.computeIfAbsent(endpoint.slot(),unused->new ArrayList<>()).add(row);mappings.add(new Mapping(row,sourceEntity,endpoint));
        },cancelled);
        var accepted=new ArrayList<JavaEvidenceAuthority.Verified>();var checked=Collections.newSetFromMap(new IdentityHashMap<JavaEvidenceAuthority.Verified,Boolean>());var current=new IdentityHashMap<JavaEvidenceAuthority.Verified,Boolean>();ContentDigest policy=null;
        for(var entry:bySlot.entrySet()){
            var expected=CanonicalJson.sha256(answerFields(scope,slotIndex.get(entry.getKey()),entry.getValue()));
            for(var row:entry.getValue()){
                var proof=proofs.get(row.digest("proof"));
                if(proof==null||!proof.identity().equals(row.digest("proof"))||!proof.record().text("question").equals(PREDICATE)||!proof.record().text("subject").equals(entry.getKey().value())||!proof.record().fields().get("scope").equals(scope)||!proof.answer().equals(expected))throw new IOException("JAVA_CORRESPONDENCE_PROOF_REQUIRED");
                if(checked.add(proof)){
                    if(!isCurrent(proof,current))throw new IOException("JAVA_CORRESPONDENCE_PROOF_REQUIRED");
                    for(var contrary:proofs.values())if(contrary.record().text("question").equals(PREDICATE)&&contrary.record().text("subject").equals(entry.getKey().value())&&contrary.record().fields().get("scope").equals(scope)&&contrary.record().digest("policy").equals(proof.record().digest("policy"))&&!contrary.answer().equals(expected)&&isCurrent(contrary,current)&&!proof.adjudicationInputs().contains(contrary.answer()))throw new IOException("JAVA_CORRESPONDENCE_CONTRADICTORY_PROOF");
                    accepted.add(proof);
                }
                if(policy==null)policy=proof.record().digest("policy");else if(!policy.equals(proof.record().digest("policy")))throw new IOException("JAVA_CORRESPONDENCE_POLICY_CONFLICT");
            }
        }
        var binaryViews=new TreeMap<ContentDigest,ContentHandle>();
        for(var slotId:bySlot.keySet()){
            var slot=slotIndex.get(slotId);if(!slot.text("space").equals("BINARY_EVIDENCE"))continue;
            var shape=proofs.get(slot.digest("proof"));var original=JavaEvidenceRecord.handle(slot.fields().get("original"));
            if(slot.fields().get("selectedView")==null||shape==null||!shape.identity().equals(slot.digest("proof"))||!shape.current()||!shape.record().text("question").equals("java.binary-shape")||!shape.record().text("subject").equals(original.digest().value())||!shape.record().fields().get("scope").equals(scope)||!shape.record().digest("policy").equals(policy))throw new IOException("JAVA_CORRESPONDENCE_BINARY_VIEW_PROOF");
            JavaEvidenceAuthority.Verified context=null;
            for(var candidate:new TreeMap<>(proofs).entrySet()){
                var proof=candidate.getValue();if(!candidate.getKey().equals(proof.identity())||!proof.record().text("question").equals("java.resolution-context")||!proof.record().text("subject").equals(scope.get("context"))||!proof.record().fields().get("scope").equals(scope)||!proof.record().digest("policy").equals(policy)||!proof.current())continue;
                var answer=JavaEvidenceRecord.handle(proof.record().fields().get("answer"));var payload=read(answer);if(!scope.equals(payload.get("scope"))||payload.get("slots")==null)continue;
                var slotsTable=JavaEvidenceTables.Table.from(payload.get("slots"));boolean[] found={false};tables.read(slotsTable,row->{if(row.identity().equals(slotId))found[0]=true;},cancelled);
                if(found[0]){context=proof;references.add(answer);references.add(slotsTable.content());break;}
            }
            if(context==null)throw new IOException("JAVA_CORRESPONDENCE_BINARY_VIEW_CONTEXT");if(!accepted.contains(context))accepted.add(context);
            if(slot.fields().get("tree")!=null){
                var tree=JavaEvidenceTables.Table.from(slot.fields().get("tree"));if(tree.rows()>workerRows)throw new IOException("JAVA_CORRESPONDENCE_WORKER_REQUIRED");references.add(tree.content());
                tables.read(tree,row->{JavaGeneratedOverlay.sameScope(scope,row);if(!row.text("disposition").equals("ACCEPTED"))throw new IOException("JAVA_CORRESPONDENCE_BINARY_TREE_OPEN");
                    JavaGeneratedOverlay.path(row.object("locator").get("path"));if(row.fields().get("content")!=null)references.add(JavaEvidenceRecord.handle(row.fields().get("content")));references.add(JavaEvidenceRecord.handle(row.object("locator").get("container")));
                    for(Object member:GraphJson.array(row.object("locator").get("members")))references.add(JavaEvidenceRecord.handle(GraphJson.object(member).get("content")));
                },cancelled);
            }
            var view=JavaEvidenceRecord.handle(slot.fields().get("selectedView"));references.add(view);binaryViews.put(slotId,view);if(!accepted.contains(shape))accepted.add(shape);
        }
        for(var reference:references)tables.requireContent(reference);
        mappings.sort(Comparator.comparing(m->m.record().identity()));
        return new Verified(scope,table,mappings,references,accepted,content,binaryViews);
    }
    private static boolean isCurrent(JavaEvidenceAuthority.Verified proof,IdentityHashMap<JavaEvidenceAuthority.Verified,Boolean> checked)throws IOException{var value=checked.get(proof);if(value!=null)return value;boolean current=proof.current();checked.put(proof,current);return current;}
    public record Endpoint(ContentDigest slot,String packageName,List<String> owners,EntityKind kind,String name,String descriptor,ContentHandle transformation){
        public Endpoint{owners=List.copyOf(owners);}
        public String binaryName(){return (packageName.isEmpty()?"":packageName+".")+String.join("$",owners);}
        public JavaSymbolName ownerName(){var result=JavaSymbolName.topLevelType(packageName,owners.getFirst());for(String nested:owners.subList(1,owners.size()))result=JavaSymbolName.memberType(result,nested);return result;}
        public Object canonicalForm(){return Map.of("schema","java-binary-member:m8.4-v1","slot",slot,"package",packageName,"owners",owners,"kind",kind,"name",name,"descriptor",descriptor,"transformation",transformation);}
    }
    public record Mapping(JavaEvidenceRecord record,Entity source,Endpoint endpoint){}
    public static final class Verified {
        private final Map<String,Object> scope;private final JavaEvidenceTables.Table table;private final List<Mapping> mappings;private final Set<ContentHandle> references;private final List<JavaEvidenceAuthority.Verified> proofs;private final RepositoryContentStore content;
        private final Map<ContentDigest,ContentHandle> binaryViews;
        private Verified(Map<String,Object> scope,JavaEvidenceTables.Table table,List<Mapping> mappings,Set<ContentHandle> references,List<JavaEvidenceAuthority.Verified> proofs,RepositoryContentStore content,Map<ContentDigest,ContentHandle> binaryViews){this.scope=Map.copyOf(scope);this.table=table;this.mappings=List.copyOf(mappings);this.references=Set.copyOf(references);this.proofs=List.copyOf(proofs);this.content=content;this.binaryViews=Map.copyOf(binaryViews);}
        /** Output witnesses are private member solvers, never entries on the compiler search path. */
        public Map<ContentDigest,ContentHandle> binaryViews(){return binaryViews;}
        public java.io.InputStream openBinaryView(ContentDigest slot)throws IOException{var view=binaryViews.get(slot);if(view==null||!current())throw new IOException("JAVA_CORRESPONDENCE_BINARY_VIEW_UNAVAILABLE");return content.open(view);}
        public int size(){return mappings.size();}
        public List<Mapping> mappings(){return mappings;}
        public Map<String,Object> scope(){return scope;}
        public JavaEvidenceTables.Table table(){return table;}
        public boolean current()throws IOException{for(var reference:references)if(!content.verify(reference))return false;for(var proof:proofs)if(!proof.current())return false;return true;}
        public Optional<ContentDigest> policy(){return proofs.stream().findFirst().map(p->p.record().digest("policy"));}
        public List<JavaEvidenceAuthority.Verified> proofs(){return proofs;}
        public Object canonicalForm(){return Map.of("schema","java-source-binary-binding:m8.4-v1","scope",scope,"table",table.wire());}
        public List<com.evolution.analysis.graph.GraphEvidence> evidence(){var result=new ArrayList<com.evolution.analysis.graph.GraphEvidence>();result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.correspondence-binding",canonicalForm()));for(var mapping:mappings){result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.correspondence",mapping.record().fields()));result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.correspondence-source",mapping.source()));result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.correspondence-endpoint",mapping.endpoint().canonicalForm()));}for(var proof:proofs)result.add(com.evolution.analysis.graph.GraphEvidence.capture("java.evidence.proof",proof.record().fields()));return List.copyOf(result);}
    }
    private Map<String,Object> read(ContentHandle handle)throws IOException{
        if(handle.size()>2_000_000||!content.verify(handle))throw new IOException("JAVA_CORRESPONDENCE_REFERENCE_UNAVAILABLE");
        try(var input=content.open(handle);var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT))){var result=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(2_000_000,64,2_000_000)));if(!CanonicalJson.sha256(result).equals(handle.digest()))throw new IOException("JAVA_CORRESPONDENCE_NONCANONICAL_REFERENCE");return result;}
    }
    private static Endpoint endpoint(Map<String,Object> map)throws IOException{
        try{
            fields(map,"schema","slot","package","owners","kind","name","descriptor","transformation");if(!"java-binary-member:m8.4-v1".equals(map.get("schema")))throw new IllegalArgumentException();
            String pkg=GraphJson.string(map.get("package"));var owners=GraphJson.array(map.get("owners")).stream().map(GraphJson::string).toList();if(owners.isEmpty())throw new IllegalArgumentException();
            var kind=EntityKind.valueOf(GraphJson.string(map.get("kind")));String name=GraphJson.string(map.get("name")),descriptor=GraphJson.string(map.get("descriptor"));
            var result=new Endpoint(new ContentDigest(GraphJson.string(map.get("slot"))),pkg,owners,kind,name,descriptor,JavaEvidenceRecord.handle(map.get("transformation")));var owner=result.ownerName();
            switch(kind){
                case TYPE->{if(!name.equals(owners.getLast())||!descriptor.isEmpty())throw new IllegalArgumentException();}
                case CONSTRUCTOR->{if(!name.equals("<init>"))throw new IllegalArgumentException();descriptor(descriptor,true,true);}
                case METHOD->{JavaSymbolName.method(owner,name,List.of());descriptor(descriptor,true,false);}
                case FIELD->{JavaSymbolName.field(owner,name);descriptor(descriptor,false,false);}
                default->throw new IllegalArgumentException();
            }
            return result;
        }catch(IllegalArgumentException malformed){throw new IOException("JAVA_CORRESPONDENCE_ENDPOINT_INVALID");}
    }
    private static void descriptor(String value,boolean method,boolean constructor){
        int[] at={0};if(method){if(value.isEmpty()||value.charAt(at[0]++)!='(')throw new IllegalArgumentException();while(at[0]<value.length()&&value.charAt(at[0])!=')')type(value,at,false);if(at[0]>=value.length()||value.charAt(at[0]++)!=')')throw new IllegalArgumentException();if(constructor){if(at[0]>=value.length()||value.charAt(at[0]++)!='V')throw new IllegalArgumentException();}else type(value,at,true);}else type(value,at,false);if(at[0]!=value.length())throw new IllegalArgumentException();
    }
    private static void type(String value,int[] at,boolean allowVoid){
        if(at[0]>=value.length())throw new IllegalArgumentException();char c=value.charAt(at[0]++);if("ZBSCIJFD".indexOf(c)>=0||c=='V'&&allowVoid)return;
        if(c=='['){int rank=1;while(at[0]<value.length()&&value.charAt(at[0])=='['){at[0]++;rank++;}if(rank>255)throw new IllegalArgumentException();type(value,at,false);return;}
        if(c!='L')throw new IllegalArgumentException();int end=value.indexOf(';',at[0]);if(end<at[0]+1)throw new IllegalArgumentException();String name=value.substring(at[0],end);if(name.startsWith("/")||name.endsWith("/")||name.contains("//")||name.chars().anyMatch(ch->".;[()".indexOf(ch)>=0))throw new IllegalArgumentException();at[0]=end+1;
    }
    private static Entity entity(Map<String,Object> map)throws IOException{
        try{fields(map,"identity","origin","stableScope","kind","canonicalName","declaration");return new Entity(new EntityIdentity(GraphJson.string(map.get("identity"))),EntityOrigin.valueOf(GraphJson.string(map.get("origin"))),new EntityScope(GraphJson.string(map.get("stableScope"))),EntityKind.valueOf(GraphJson.string(map.get("kind"))),GraphJson.string(map.get("canonicalName")),Optional.ofNullable(map.get("declaration")).map(GraphJson::object).map(JavaSourceCorrespondence::span));}catch(IllegalArgumentException malformed){throw new IOException("JAVA_CORRESPONDENCE_SOURCE_ENTITY_INVALID");}
    }
    private static SourceSpan span(Map<String,Object> map){fields(map,"document","startLine","startColumn","endLine","endColumn");return new SourceSpan(new SourceDocumentIdentity(GraphJson.string(map.get("document"))),integer(map.get("startLine")),integer(map.get("startColumn")),integer(map.get("endLine")),integer(map.get("endColumn")));}
    private static int integer(Object value){return ((java.math.BigDecimal)value).intValueExact();}
    private static void fields(Map<String,Object> map,String... names){if(!map.keySet().equals(Set.of(names)))throw new IllegalArgumentException("JAVA_CORRESPONDENCE_FIELDS");}
    private static void validateSpan(SourceSpan span,SourceInput source)throws IOException{
        String[] lines=source.text().split("\\r\\n|\\r|\\n",-1);if(span.startLine()>lines.length||span.endLine()>lines.length||span.startColumn()>lines[span.startLine()-1].length()+1||span.endColumn()>lines[span.endLine()-1].length()+1)throw new IOException("JAVA_CORRESPONDENCE_SOURCE_SPAN_INVALID");
    }
}
