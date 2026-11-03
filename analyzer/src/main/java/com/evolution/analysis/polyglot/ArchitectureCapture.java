package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;

/** Data-only architecture capture. A valid producer report is never semantic authority.
 * Work is bounded to one explicitly captured worker context; repository callers partition it.
 */
public final class ArchitectureCapture {
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("semantic.architecture-capture","m8.4-v2");
    public static final VersionedIdentifier CATALOG=new VersionedIdentifier("evidence.architecture-gaps","m8.4-v1");
    public static final List<String> CATEGORIES=java.util.stream.IntStream.rangeClosed(1,12).mapToObj(i->String.format(Locale.ROOT,"F%02d",i)).toList();
    public record Request(EvidenceContext context,ContentDigest scope,String repository,String app,
            Map<String,Object> configuration,List<Map<String,Object>> profiles,SemanticTables.Table documents,ContentDigest tool) {
        public Request {
            Objects.requireNonNull(context);Objects.requireNonNull(scope);Objects.requireNonNull(tool);Objects.requireNonNull(documents);
            if(repository==null||repository.isBlank()||app==null||app.isBlank()||documents.kind()!=SemanticTables.Kind.DOCUMENT)throw new IllegalArgumentException("ARCHITECTURE_REQUEST_SCOPE");
            configuration=neutral(configuration);profiles=profiles.stream().map(ArchitectureCapture::neutral).toList();
        }
    }
    public record Result(ContentHandle raw,ContentDigest identity,SemanticTables.Table facts,SemanticTables.Table obligations,SemanticTables.Table artifacts,long documents,long census,long factCount,long gapCount) {}
    /** Strict data-only request codec for replaying a referenced child capture. */
    public static Request request(Object value){
        var row=GraphJson.object(value);if(!row.keySet().equals(Set.of("context","scope","repository","app","configuration","profiles","documents","tool")))throw new IllegalArgumentException("ARCHITECTURE_REQUEST_FIELDS");var context=GraphJson.object(row.get("context"));if(!context.keySet().equals(Set.of("snapshotIdentity","analysisIdentity")))throw new IllegalArgumentException("ARCHITECTURE_REQUEST_CONTEXT");
        return new Request(new EvidenceContext(new com.evolution.analysis.contract.identity.SnapshotIdentity(GraphJson.string(context.get("snapshotIdentity"))),Optional.ofNullable(context.get("analysisIdentity")).map(v->new com.evolution.analysis.contract.identity.AnalysisIdentity(GraphJson.string(v)))),new ContentDigest(GraphJson.string(row.get("scope"))),GraphJson.string(row.get("repository")),GraphJson.string(row.get("app")),GraphJson.object(row.get("configuration")),GraphJson.array(row.get("profiles")).stream().map(GraphJson::object).toList(),LanguageSemanticRequest.table(row.get("documents")),new ContentDigest(GraphJson.string(row.get("tool"))));
    }
    public static ContentDigest inputClosure(Request request,SemanticTables tables,BooleanSupplier cancelled)throws IOException {
        var manifest=new ArrayList<Map<String,Object>>();
        try(var reader=tables.open(request.documents(),cancelled)){SemanticTables.Row row;while((row=reader.next())!=null){
            if(!row.scope().equals(request.scope()))throw new IOException("ARCHITECTURE_DOCUMENT_SCOPE");
            var handle=LanguageSemanticRequest.handle(row.fields().get("content"));
            manifest.add(Map.of("path",row.key(),"sha256",handle.digest(),"size",handle.size(),"role",row.fields().getOrDefault("role","unknown")));
        }}
        return CanonicalJson.sha256(List.of("architecture-input:m8.4-v1",request.scope(),request.repository(),request.app(),request.configuration(),request.profiles(),manifest));
    }
    public static Result read(Request request,ContentHandle raw,SemanticTables tables,RepositoryContentStore content,int workerBytes,
            Consumer<CapabilityGapRecord> gaps,BooleanSupplier cancelled)throws IOException {
        if(workerBytes<1||raw.size()>workerBytes)throw new IOException("ARCHITECTURE_CAPTURE_WORKER_REQUIRED");
        if(!content.verify(raw))throw new IOException("ARCHITECTURE_CAPTURE_UNAVAILABLE");
        Map<String,Object> report;
        try(var stream=content.open(raw)){
            var bytes=stream.readNBytes(workerBytes);if(bytes.length!=raw.size()||stream.read()!=-1)throw new IOException("ARCHITECTURE_CAPTURE_SIZE");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            report=GraphJson.object(GraphJson.read(text,new GraphJson.Limits(workerBytes,64,workerBytes)));
        }catch(IllegalArgumentException invalid){throw new IOException("ARCHITECTURE_CAPTURE_INVALID",invalid);}
        try {
            boolean v2="architecture-capture:m8.4-v2".equals(report.get("schema"));var fields=new HashSet<>(Set.of("schema","scope","context","tool","trust","qualified","inputClosure","profiles","documents","census","dispositions","facts","obligations","native","identity"));if(v2)fields.add("artifacts");
            if(!report.keySet().equals(fields)||!(v2||"architecture-capture:m8.4-v1".equals(report.get("schema")))||!"PRODUCER_ASSERTION".equals(report.get("trust"))||!Boolean.FALSE.equals(report.get("qualified")))throw new IllegalArgumentException("ARCHITECTURE_CAPTURE_HEADER");
            var body=new TreeMap<>(report);body.remove("identity");var identity=CanonicalJson.sha256(body);
            if(!identity.value().equals(report.get("identity"))||!request.scope().value().equals(report.get("scope"))||!request.tool().value().equals(report.get("tool"))
                    ||!CanonicalJson.write(request.configuration()).equals(CanonicalJson.write(report.get("context")))||!CanonicalJson.write(request.profiles()).equals(CanonicalJson.write(report.get("profiles")))
                    ||!inputClosure(request,tables,cancelled).value().equals(report.get("inputClosure")))throw new IllegalArgumentException("ARCHITECTURE_CAPTURE_BINDING");
            var documents=new TreeMap<String,Map<String,Object>>();var coordinates=new HashMap<String,SourceCoordinates>();
            try(var reader=tables.open(request.documents(),cancelled)){SemanticTables.Row row;while((row=reader.next())!=null){
                check(cancelled);var handle=LanguageSemanticRequest.handle(row.fields().get("content"));if(!content.verify(handle))throw new IOException("ARCHITECTURE_SOURCE_UNAVAILABLE");
                var doc=new TreeMap<String,Object>();doc.put("path",row.key());doc.put("content",handle);doc.put("role",row.fields().getOrDefault("role","unknown"));doc.put("encoding",row.fields().get("encoding"));documents.put(row.key(),doc);
                if("UTF-8".equals(doc.get("encoding"))){try(var source=content.open(handle)){if(handle.size()>workerBytes)throw new IOException("ARCHITECTURE_SOURCE_WORKER_REQUIRED");coordinates.put(row.key(),new SourceCoordinates(source.readNBytes(workerBytes),"UTF-8"));}}
                else if(doc.get("encoding")!=null)throw new IllegalArgumentException("ARCHITECTURE_SOURCE_ENCODING");
            }}
            var supplied=new TreeMap<String,Map<String,Object>>();for(Object value:GraphJson.array(report.get("documents"))){var doc=GraphJson.object(value);String path=GraphJson.string(doc.get("path"));if(supplied.put(path,doc)!=null)throw new IllegalArgumentException("ARCHITECTURE_DUPLICATE_DOCUMENT");}
            if(!CanonicalJson.write(documents).equals(CanonicalJson.write(supplied)))throw new IllegalArgumentException("ARCHITECTURE_DOCUMENT_DENOMINATOR");
            var census=new HashSet<String>();
            for(Object value:GraphJson.array(report.get("census"))){var row=GraphJson.object(value);String path=GraphJson.string(row.get("path")),category=category(row);var doc=documents.get(path);
                if(doc==null||!row.keySet().equals(Set.of("id","path","content","category")))throw new IllegalArgumentException("ARCHITECTURE_CENSUS_SCOPE");
                var handle=handle(doc.get("content"));String expected=CanonicalJson.sha256(List.of("architecture-obligation:m8.4-v1",request.scope(),path,handle.digest(),category)).value();
                if(!expected.equals(row.get("id"))||!handle.digest().value().equals(row.get("content"))||!census.add(path+"\0"+category))throw new IllegalArgumentException("ARCHITECTURE_CENSUS_BINDING");
            }
            if(census.size()!=Math.multiplyExact(documents.size(),CATEGORIES.size()))throw new IllegalArgumentException("ARCHITECTURE_CENSUS_OPEN");
            var dispositions=new HashSet<String>();for(Object value:GraphJson.array(report.get("dispositions"))){var row=GraphJson.object(value);String path=GraphJson.string(row.get("path"));var doc=documents.get(path);
                if(doc==null||!dispositions.add(path)||!handle(doc.get("content")).digest().value().equals(row.get("content")))throw new IllegalArgumentException("ARCHITECTURE_DISPOSITION_DENOMINATOR");
                if(!row.keySet().equals(Set.of("path","content","role","disposition"))||!Objects.equals(doc.get("role"),row.get("role"))
                        ||!Set.of("CAPTURED_RESOLUTION_INPUT","BINARY","NATIVE_CONTEXT_INPUT","ANALYSIS_INPUT","DECLARATIVE_OR_EXECUTABLE_INPUT","DECLARED_DOCUMENT_OR_ASSET","NON_CODE_RESOURCE","UNCLASSIFIED").contains(GraphJson.string(row.get("disposition"))))throw new IllegalArgumentException("ARCHITECTURE_DISPOSITION_INVALID");}
            if(!dispositions.equals(documents.keySet()))throw new IllegalArgumentException("ARCHITECTURE_DISPOSITION_OPEN");
            var facts=rows(request,report,"facts",documents,coordinates,cancelled);var obligations=rows(request,report,"obligations",documents,coordinates,cancelled);
            var uncertain=new HashSet<String>();var factIds=new HashMap<String,SemanticTables.Row>();for(var fact:facts){factIds.put(fact.key(),fact);String status=GraphJson.string(fact.fields().get("status"));if(!Set.of("RESOLVED","PARTIAL","CONDITIONAL","UNRESOLVED","UNSUPPORTED","AMBIGUOUS","ERROR","CANDIDATES").contains(status))throw new IllegalArgumentException("ARCHITECTURE_FACT_STATUS");if(!status.equals("RESOLVED"))uncertain.add(fact.key());
                if(!CanonicalJson.write(request.configuration().getOrDefault("conditions",Map.of())).equals(CanonicalJson.write(fact.fields().get("conditions"))))throw new IllegalArgumentException("ARCHITECTURE_FACT_CONDITIONS");
                var supports=new ArrayList<>(GraphJson.array(fact.fields().getOrDefault("supports",List.of())));supports.addAll(GraphJson.array(GraphJson.object(fact.fields().get("attributes")).getOrDefault("wrapperFootprint",List.of())));
                for(Object value:supports){var support=GraphJson.object(value);var source=coordinates.get(GraphJson.string(support.get("path")));if(source==null||number(support,"end")<number(support,"start"))throw new IllegalArgumentException("ARCHITECTURE_SUPPORT_SCOPE");source.point(number(support,"start"),SourceCoordinates.Encoding.UTF16);source.point(number(support,"end"),SourceCoordinates.Encoding.UTF16);}
            }
            for(var fact:facts){var fieldsOfFact=fact.fields();var attributes=GraphJson.object(fieldsOfFact.get("attributes"));if(!"native-module-reference".equals(attributes.get("binding")))continue;
                var nativeFact=factIds.get(GraphJson.string(attributes.get("nativeReference")));if(nativeFact==null)throw new IllegalArgumentException("ARCHITECTURE_NATIVE_MODULE_REFERENCE");var observed=nativeFact.fields();
                if(!"SFC_MODULE_REFERENCE".equals(fieldsOfFact.get("kind"))||!"MODULE_REFERENCE".equals(observed.get("kind"))||!"RESOLVED".equals(observed.get("status"))||!Objects.equals(fieldsOfFact.get("path"),observed.get("path"))||!Objects.equals(fieldsOfFact.get("target"),observed.get("target"))
                        ||number(GraphJson.object(fieldsOfFact.get("span")),"start")!=number(GraphJson.object(observed.get("span")),"start")+1||number(GraphJson.object(fieldsOfFact.get("span")),"end")!=number(GraphJson.object(observed.get("span")),"end")-1)throw new IllegalArgumentException("ARCHITECTURE_NATIVE_MODULE_REFERENCE");
            }
            for(var obligation:obligations){String reason=GraphJson.string(obligation.fields().get("reason"));ContractChecks.token(reason,"architecture gap reason");var details=GraphJson.object(obligation.fields().get("details"));if(reason.startsWith("ARCHITECTURE_")&&details.get("fact") instanceof String id){
                var fact=factIds.get(id);if(fact==null||!reason.equals("ARCHITECTURE_"+fact.fields().get("status"))
                        ||!List.of("path","category","span").stream().allMatch(field->Objects.equals(fact.fields().get(field),obligation.fields().get(field))))throw new IllegalArgumentException("ARCHITECTURE_UNCERTAINTY_GAP_BINDING");
                uncertain.remove(id);
            }}
            if(!uncertain.isEmpty())throw new IllegalArgumentException("ARCHITECTURE_UNCERTAINTY_GAP_OMITTED");
            for(Object value:GraphJson.array(report.get("native"))){var nativeRow=GraphJson.object(value);String path=GraphJson.string(nativeRow.get("path"));if(!documents.containsKey(path))throw new IllegalArgumentException("ARCHITECTURE_NATIVE_SCOPE");
                if(nativeRow.containsKey("sourceProjection")){var projection=GraphJson.object(nativeRow.get("sourceProjection"));var source=coordinates.get(path);
                    if(source==null||!projection.keySet().equals(Set.of("schema","language","start","end","digest"))||!"source-excerpt:m8.4-v1".equals(projection.get("schema"))||!Set.of("js","ts","jsx","tsx","css","html").contains(GraphJson.string(projection.get("language"))))throw new IllegalArgumentException("ARCHITECTURE_NATIVE_PROJECTION");
                    var start=source.point(number(projection,"start"),SourceCoordinates.Encoding.UTF16);var end=source.point(number(projection,"end"),SourceCoordinates.Encoding.UTF16);
                    if(end.offset()<start.offset()||!ContentDigest.sha256Utf8(source.text().substring(start.offset(),end.offset())).value().equals(projection.get("digest")))throw new IllegalArgumentException("ARCHITECTURE_NATIVE_PROJECTION_BYTES");
                }
            }
            var factTable=tables.write(SemanticTables.Kind.OBSERVATION,facts.iterator(),cancelled);
            var gapTable=tables.write(SemanticTables.Kind.OBSERVATION,obligations.iterator(),cancelled);
            var artifactRows=new TreeMap<String,SemanticTables.Row>();
            for(Object value:v2?GraphJson.array(report.get("artifacts")):List.of()){
                check(cancelled);var artifact=GraphJson.object(value);String source=GraphJson.string(artifact.get("source")),kind=GraphJson.string(artifact.get("kind")),id=GraphJson.string(artifact.get("id"));var doc=documents.get(source);
                if(doc==null||!"generated-helper".equals(artifact.get("population")))throw new IllegalArgumentException("ARCHITECTURE_DERIVED_SOURCE");
                var sourceHandle=handle(doc.get("content"));var derived=handle(artifact.get("content"));String encoded=GraphJson.string(artifact.get("data"));byte[] bytes=Base64.getDecoder().decode(encoded);
                if(!Base64.getEncoder().encodeToString(bytes).equals(encoded)||!sourceHandle.digest().value().equals(artifact.get("sourceDigest"))||!derived.equals(new ContentHandle(ContentDigest.sha256(bytes),bytes.length))
                        ||!CanonicalJson.sha256(List.of("architecture-derived:m8.4-v1",request.scope(),source,sourceHandle.digest(),kind,derived)).value().equals(id))throw new IllegalArgumentException("ARCHITECTURE_DERIVED_BYTES");
                content.put(new ByteArrayInputStream(bytes),cancelled);var metadata=new TreeMap<>(artifact);metadata.remove("data");
                if(artifactRows.put(id,new SemanticTables.Row(SemanticTables.Kind.DOCUMENT,id,request.scope(),metadata))!=null)throw new IllegalArgumentException("ARCHITECTURE_DERIVED_DUPLICATE");
            }
            for(Object value:GraphJson.array(report.get("native"))){var nativeRow=GraphJson.object(value);
                if("configured-sfc-program-noEmit".equals(nativeRow.get("mode"))&&"@vue/language-core".equals(nativeRow.get("engine")))for(String field:List.of("generated","mapping")){
                    var artifact=artifactRows.get(GraphJson.string(nativeRow.get(field)));String expectedKind=field.equals("generated")?"vue-typecheck-virtual":"vue-typecheck-mappings";
                    if(artifact==null||!expectedKind.equals(artifact.fields().get("kind"))||!nativeRow.get("path").equals(artifact.fields().get("source")))throw new IllegalArgumentException("ARCHITECTURE_NATIVE_ARTIFACT_BINDING");
                }
            }
            var artifactTable=tables.write(SemanticTables.Kind.DOCUMENT,artifactRows.values().iterator(),cancelled);
            for(var row:obligations)gap(request,raw,row,gaps);
            gap(request,raw,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,"ARCHITECTURE_METHOD_UNQUALIFIED",request.scope(),Map.of("reason","ARCHITECTURE_METHOD_UNQUALIFIED")),gaps);
            return new Result(raw,identity,factTable,gapTable,artifactTable,documents.size(),census.size(),facts.size(),obligations.size());
        }catch(IllegalArgumentException|ArithmeticException|ClassCastException invalid){throw new IOException("ARCHITECTURE_CAPTURE_INVALID",invalid);}
    }
    private static List<SemanticTables.Row> rows(Request request,Map<String,Object> report,String field,Map<String,Map<String,Object>> documents,Map<String,SourceCoordinates> coordinates,BooleanSupplier cancelled)throws IOException {
        var result=new TreeMap<String,SemanticTables.Row>();
        for(Object value:GraphJson.array(report.get(field))){check(cancelled);var row=GraphJson.object(value);String path=GraphJson.string(row.get("path"));category(row);
            if(!documents.containsKey(path))throw new IllegalArgumentException("ARCHITECTURE_ROW_DOCUMENT");
            var body=new TreeMap<>(row);String id=GraphJson.string(body.remove("id"));var expected=field.equals("facts")?CanonicalJson.sha256(body):CanonicalJson.sha256(List.of(request.scope(),body));
            if(!expected.value().equals(id))throw new IllegalArgumentException("ARCHITECTURE_ROW_IDENTITY");
            if(row.get("span")!=null){var span=GraphJson.object(row.get("span"));var source=coordinates.get(path);if(source==null)throw new IllegalArgumentException("ARCHITECTURE_BINARY_SPAN");
                var start=source.point(number(span,"start"),SourceCoordinates.Encoding.UTF16);var end=source.point(number(span,"end"),SourceCoordinates.Encoding.UTF16);
                if(end.offset()<start.offset()||start.line()!=number(span,"startLine")||start.column()!=number(span,"startColumn")||end.line()!=number(span,"endLine")||end.column()!=number(span,"endColumn"))throw new IllegalArgumentException("ARCHITECTURE_SOURCE_SPAN");
            }else if(coordinates.containsKey(path))throw new IllegalArgumentException("ARCHITECTURE_TEXT_SPAN_REQUIRED");
            if(field.equals("facts")&&!request.scope().value().equals(row.get("context")))throw new IllegalArgumentException("ARCHITECTURE_FACT_CONTEXT");
            if(field.equals("facts")){
                Object role=documents.get(path).get("role"),population=row.get("population");
                // Legacy producer filename inference is only admissible for an unknown input role.
                if(!Objects.equals(role,population)&&!("unknown".equals(role)&&"nonproduction".equals(population)))throw new IllegalArgumentException("ARCHITECTURE_FACT_POPULATION");
            }
            if(result.put(id,new SemanticTables.Row(SemanticTables.Kind.OBSERVATION,id,request.scope(),row))!=null)throw new IllegalArgumentException("ARCHITECTURE_DUPLICATE_ROW");
        }
        return List.copyOf(result.values());
    }
    private static void gap(Request request,ContentHandle raw,SemanticTables.Row row,Consumer<CapabilityGapRecord> consumer){
        String reason=GraphJson.string(row.fields().get("reason"));var subject=new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION,row.identity().value());
        var observation=ProviderObservationReference.create(PROVIDER,"architecture.capture",raw.digest(),row.identity());
        var need=new EvidenceRequirement(EvidenceRequirement.Kind.ALTERNATE_FRONTEND,"architecture.semantic-evidence",List.of(subject),EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,List.of("Capture and validate the exact missing framework, schema or resource evidence."));
        consumer.accept(CapabilityGapRecord.create(CATALOG,request.context(),PROVIDER,"architecture.semantic",reason,subject,List.of(),List.of(observation),List.of(need),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"architecture.semantic")),List.of(),List.of(),List.of("Producer capture and native-tool success do not certify semantic completeness.")));
    }
    private static String category(Map<String,Object> row){String value=GraphJson.string(row.get("category"));if(!CATEGORIES.contains(value))throw new IllegalArgumentException("ARCHITECTURE_CATEGORY");return value;}
    private static long number(Map<String,Object> row,String name){if(!(row.get(name) instanceof java.math.BigDecimal value))throw new IllegalArgumentException("ARCHITECTURE_INTEGER_REQUIRED");return value.longValueExact();}
    private static ContentHandle handle(Object value){return value instanceof ContentHandle handle?handle:LanguageSemanticRequest.handle(value);}
    @SuppressWarnings("unchecked") private static Map<String,Object> neutral(Map<String,Object> value){return (Map<String,Object>)new SemanticTables.Row(SemanticTables.Kind.CONTEXT,"neutral",ContentDigest.sha256Utf8("neutral"),value).fields();}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("ARCHITECTURE_CANCELLED_WITH_FRONTIER");}
}
