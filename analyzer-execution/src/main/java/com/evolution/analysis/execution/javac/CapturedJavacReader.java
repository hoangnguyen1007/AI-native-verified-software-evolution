package com.evolution.analysis.execution.javac;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Non-executing, strict reader of a captured selective javac index.
 * Byte/input consistency is separate from semantic qualification: this adapter
 * cannot construct a VerifiedClaim, a Java evidence proof, or a closed census.
 */
public final class CapturedJavacReader {
    public static final Set<String> CATEGORIES=Set.of("java.declares","java.extends","java.implements","java.permits",
            "java.type-uses","java.calls","java.constructor-calls","java.reads-field","java.writes-field","java.method-references",
            "java.has-parameter","java.parameter-type","java.returns","java.field-type","java.throws","java.annotated-with","java.type-parameter-bound","java.type-argument");
    public static final Set<String> OBSERVED=Set.of("java.type-uses","java.calls","java.constructor-calls","java.reads-field","java.writes-field","java.method-references");
    public record Context(String runtime,List<String> options,Map<String,ContentHandle> sources,Map<String,ContentHandle> artifacts){
        public Context {
            Objects.requireNonNull(runtime);options=List.copyOf(options);sources=Map.copyOf(sources);artifacts=Map.copyOf(artifacts);
            if(Runtime.Version.parse(runtime).feature()!=21||options.size()!=8||!options.equals(List.of("--release","21","-encoding","UTF-8","-proc:none","-classpath",options.get(6),"-Xlint:none")))
                throw new IllegalArgumentException("JAVAC_CAPTURE_CONTEXT_OPTIONS");
        }
    }
    public record Artifact(String uri,String kind,ContentHandle content,String state,boolean bytesBound){}
    public static final class Capture {
        private final RepositoryContentStore content;private final Set<ContentHandle> references;private final ContentHandle document;
        private final Map<String,Object> raw;private final Map<String,Artifact> artifacts;
        private final Set<String> frontier,limitations;
        private final Context context;
        private Capture(RepositoryContentStore content,ContentHandle document,Set<ContentHandle> references,Map<String,Object> raw,Map<String,Artifact> artifacts,Set<String> frontier,Set<String> limitations,Context context){
            this.content=content;this.document=document;this.references=Set.copyOf(references);this.raw=freeze(raw);this.artifacts=Map.copyOf(artifacts);this.frontier=Set.copyOf(frontier);this.limitations=Set.copyOf(limitations);
            this.context=context;
        }
        public Context context(){return context;}
        public ContentHandle document(){return document;}
        public Set<String> knownModules(){return Set.copyOf(GraphJson.array(raw.getOrDefault("knownModules",List.of())).stream().map(GraphJson::string).toList());}
        public Set<String> observedCategories(){return "java-native-selective-index:m8.4-v2".equals(raw.get("schema"))?CATEGORIES:OBSERVED;}
        public String state(){return (String)raw.get("state");}
        public boolean traversalComplete(){return raw.get("failure")==null&&frontier.isEmpty();}
        public boolean qualified(){return false;}
        public Set<String> frontier(){return frontier;}
        public Set<String> limitations(){return limitations;}
        public Map<String,Object> categories(){return GraphJson.object(raw.get("categories"));}
        public List<Map<String,Object>> observations(){return GraphJson.array(raw.get("observations")).stream().map(GraphJson::object).toList();}
        public List<Map<String,Object>> declarations(){return GraphJson.array(raw.get("declarations")).stream().map(GraphJson::object).toList();}
        public List<Map<String,Object>> diagnostics(){return GraphJson.array(raw.get("diagnostics")).stream().map(GraphJson::object).toList();}
        public Map<String,Artifact> artifacts(){return artifacts;}
        /** Caller supplies the real M1 context; no snapshot identity is invented from an attempt UUID. */
        public List<CapabilityGapRecord> gaps(EvidenceContext context){
            Objects.requireNonNull(context);var provider=new VersionedIdentifier("reader.java-captured-javac","m8.4-v1");
            var observation=ProviderObservationReference.create(provider,"java.compiler-index",document.digest(),document.digest());
            var subject=EvidenceSubject.observation(observation.identity());var gaps=new TreeSet<CapabilityGapRecord>();
            for(String code:limitations){
                var kind=code.equals("PLATFORM_SELECTION_UNESTABLISHED")?EvidenceRequirement.Kind.PLATFORM_SYMBOLS
                        :code.contains("CAPACITY")?EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT
                        :code.equals("BINARY_INPUT_BINDING_UNAVAILABLE")?EvidenceRequirement.Kind.BYTECODE:EvidenceRequirement.Kind.ALTERNATE_FRONTEND;
                var requirement=new EvidenceRequirement(kind,"java.native-index."+code.toLowerCase(Locale.ROOT).replace('_','-'),List.of(subject),
                        EvidenceRequirement.AuthorizationClass.PASSIVE,List.of("Supply matching scoped, qualified evidence for this captured compiler limitation."));
                gaps.add(CapabilityGapRecord.create(new VersionedIdentifier("evidence.java-captured-javac","m8.4-v1"),context,provider,"java.compiler-index",code,
                        subject,List.of(),List.of(observation),List.of(requirement),List.of(),List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE,"java.compiler-index")),
                        List.of(),List.of(),List.of("Raw observations, diagnostic spans and provider coverage remain in the referenced index; captured bytes do not qualify a semantic census.")));
            }
            return List.copyOf(gaps);
        }
        public boolean current()throws IOException {for(var ref:references)if(!content.verify(ref))return false;return true;}
    }
    private final RepositoryContentStore content;
    public CapturedJavacReader(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}
    public Capture read(ContentHandle document,Context context,int workerCharacters,BooleanSupplier cancelled)throws IOException {
        if(workerCharacters<1)throw new IllegalArgumentException("JAVAC_CAPTURE_WORKER_BUDGET");
        var references=new LinkedHashSet<ContentHandle>();require(document,references,cancelled);
        if(document.size()>workerCharacters)throw invalid("WORKER_CAPACITY_REQUIRED");
        try(var input=content.open(document);var reader=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))){
            var raw=GraphJson.object(GraphJson.read(reader,new GraphJson.Limits(workerCharacters,64,workerCharacters)));
            var required=new TreeSet<>(Set.of("schema","provider","runtime","options","sourceFiles","state","failure","diagnostics","declarations","observations","artifacts","walkedSources","frontier","categories","qualified","semanticTrust"));
            var keys=new TreeSet<>(raw.keySet());keys.remove("knownModules");if(!keys.equals(required))throw invalid("FIELDS");
            boolean full="java-native-selective-index:m8.4-v2".equals(raw.get("schema"));var observedCategories=full?CATEGORIES:OBSERVED;
            if(!(full?"isolated.javac-index:m8.4-v2".equals(raw.get("provider")):"java-native-selective-index:m8.4-v1".equals(raw.get("schema"))&&"isolated.javac-index:m8.4-v1".equals(raw.get("provider")))
                    ||!Boolean.FALSE.equals(raw.get("qualified"))||!"PRODUCER_ASSERTION".equals(raw.get("semanticTrust")))throw invalid("TRUST_OR_SCHEMA");
            if(!context.runtime().equals(raw.get("runtime"))||!strings(raw.get("options"),false).equals(context.options()))throw invalid("COMPILER_CONTEXT");
            String state=GraphJson.string(raw.get("state"));if(!Set.of("PARTIAL","ERROR").contains(state))throw invalid("STATE");
            var limitations=new TreeSet<>(Set.of("OCCURRENCE_CENSUS_UNQUALIFIED","PLATFORM_SELECTION_UNESTABLISHED"));
            if(raw.get("failure")!=null){String failure=GraphJson.string(raw.get("failure"));if(!failure.matches("[A-Za-z][A-Za-z0-9_]{0,127}"))throw invalid("FAILURE_CODE");limitations.add(failure);}
            var sources=new TreeMap<String,ContentHandle>();var texts=new TreeMap<String,String>();
            for(Object value:GraphJson.array(raw.get("sourceFiles"))){
                var row=GraphJson.object(value);fields(row,"uri","digest","size");String uri=GraphJson.string(row.get("uri"));
                var ref=handle(row);if(sources.putIfAbsent(uri,ref)!=null||!ref.equals(context.sources().get(uri)))throw invalid("SOURCE_BINDING");
                require(ref,references,cancelled);if(ref.size()>32_000_000)throw invalid("SOURCE_WORKER_CAPACITY_REQUIRED");
                try(var bytes=content.open(ref)){
                    texts.put(uri,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.readNBytes(Math.toIntExact(ref.size()+1)))).toString());
                }
            }
            if(!sources.equals(context.sources()))throw invalid("SOURCE_DENOMINATOR");
            var walked=new TreeSet<>(strings(raw.get("walkedSources"),true));var frontier=new TreeSet<>(strings(raw.get("frontier"),true));
            var rest=new TreeSet<>(sources.keySet());rest.removeAll(walked);
            if(!sources.keySet().containsAll(walked)||!rest.equals(frontier))throw invalid("FRONTIER_DENOMINATOR");
            if(!frontier.isEmpty())limitations.add("SOURCE_FRONTIER_OPEN");
            if(raw.containsKey("knownModules"))strings(raw.get("knownModules"),true);
            var artifacts=new TreeMap<String,Artifact>();
            for(var entry:GraphJson.object(raw.get("artifacts")).entrySet()){
                var row=GraphJson.object(entry.getValue());fields(row,"uri","kind","digest","size","state");
                if(!entry.getKey().equals(row.get("uri")))throw invalid("ARTIFACT_URI");
                String kind=GraphJson.string(row.get("kind")),origin=GraphJson.string(row.get("state"));var ref=handle(row);
                if(!Set.of("SOURCE","CLASS").contains(kind)||!Set.of("CAPTURED_SOURCE","OBSERVED_COMPILER_INPUT").contains(origin))throw invalid("ARTIFACT_STATE");
                var expected=origin.equals("CAPTURED_SOURCE")?sources.get(entry.getKey()):context.artifacts().get(entry.getKey());
                if(expected!=null&&!expected.equals(ref)||origin.equals("CAPTURED_SOURCE")&&expected==null)throw invalid("ARTIFACT_BINDING");
                if(expected!=null)require(expected,references,cancelled);else limitations.add("BINARY_INPUT_BINDING_UNAVAILABLE");
                artifacts.put(entry.getKey(),new Artifact(entry.getKey(),kind,ref,origin,expected!=null));
            }
            var observed=new TreeMap<String,Long>();
            for(Object value:GraphJson.array(raw.get("declarations"))){
                checkCancellation(cancelled);var row=GraphJson.object(value);fields(row,"source","element","treeKind","span");
                String text=texts.get(GraphJson.string(row.get("source")));if(text==null)throw invalid("FOREIGN_DECLARATION");
                position(row.get("span"),text,limitations);element(row.get("element"),artifacts,texts,limitations);com.sun.source.tree.Tree.Kind.valueOf(GraphJson.string(row.get("treeKind")));
            }
            for(Object value:GraphJson.array(raw.get("observations"))){
                checkCancellation(cancelled);var row=GraphJson.object(value);if(full)fields(row,"source","sourceContent","category","treeKind","span","nativeSpan","caller","target","enclosingScopes","binding","type");
                else fields(row,"source","sourceContent","category","treeKind","span","caller","target","enclosingScopes","binding","type");
                String uri=GraphJson.string(row.get("source")),category=GraphJson.string(row.get("category"));String text=texts.get(uri);
                if(text==null||!sources.get(uri).digest().value().equals(row.get("sourceContent"))||!observedCategories.contains(category))throw invalid("OBSERVATION_SOURCE");
                position(row.get("span"),text,limitations);if(full)position(row.get("nativeSpan"),text,limitations);com.sun.source.tree.Tree.Kind.valueOf(GraphJson.string(row.get("treeKind")));
                element(row.get("caller"),artifacts,texts,limitations);element(row.get("target"),artifacts,texts,limitations);
                String binding=GraphJson.string(row.get("binding"));boolean syntaxOnly=full&&binding.equals("SYNTAX_ONLY")&&category.equals("java.declares")&&row.get("target")==null&&Set.of("LAMBDA_EXPRESSION","BLOCK").contains(row.get("treeKind"));
                if(!Set.of("RESOLVED","UNRESOLVED","ERROR").contains(binding)&&!syntaxOnly||binding.equals("RESOLVED")&&row.get("target")==null)throw invalid("BINDING");
                if(!binding.equals("RESOLVED"))limitations.add("NATIVE_BINDING_"+binding);
                if(row.get("type")!=null)GraphJson.string(row.get("type"));
                for(Object scope:GraphJson.array(row.get("enclosingScopes"))){
                    var nested=GraphJson.object(scope);fields(nested,"kind","startOffset","endOffset");
                    if(!Set.of("LAMBDA_EXPRESSION","BLOCK").contains(nested.get("kind")))throw invalid("ENCLOSING_SCOPE");
                    long start=number(nested.get("startOffset")),end=number(nested.get("endOffset"));
                    if(start< -1||end< -1)throw invalid("ENCLOSING_SCOPE_SPAN");
                    if(start==-1||end==-1)limitations.add("SOURCE_POSITION_UNAVAILABLE");
                    else if(CapturedJavacIndex.span(text,start,end)==null)throw invalid("ENCLOSING_SCOPE_SPAN");
                    limitations.add("ENCLOSING_SCOPE_MAPPING_REQUIRED");
                }
                observed.merge(category,1L,Math::addExact);
            }
            var categories=GraphJson.object(raw.get("categories"));if(!categories.keySet().equals(CATEGORIES))throw invalid("CATEGORY_DENOMINATOR");
            for(var entry:categories.entrySet()){
                var row=GraphJson.object(entry.getValue());fields(row,"state","observed");boolean known=observedCategories.contains(entry.getKey());
                if(!(known?"OBSERVED_NOT_QUALIFIED_CENSUS":"UNAVAILABLE").equals(row.get("state"))
                        ||(known?(row.get("observed")!=null&&number(row.get("observed"))!=observed.getOrDefault(entry.getKey(),0L)):row.get("observed")!=null))throw invalid("CATEGORY_COUNT");
                if(known&&row.get("observed")==null&&(raw.get("failure")==null||!GraphJson.array(raw.get("observations")).isEmpty()
                        ||!GraphJson.array(raw.get("declarations")).isEmpty()||!walked.isEmpty()))throw invalid("CATEGORY_COUNT");
            }
            for(Object value:GraphJson.array(raw.get("diagnostics"))){
                var row=GraphJson.object(value);fields(row,"kind","code","source","span");String kind=GraphJson.string(row.get("kind"));GraphJson.string(row.get("code"));
                if(!Set.of("ERROR","WARNING","MANDATORY_WARNING","NOTE","OTHER").contains(kind))throw invalid("DIAGNOSTIC_KIND");
                if(kind.equals("ERROR")&&!state.equals("ERROR"))throw invalid("HIDDEN_COMPILER_ERROR");
                if(row.get("source")!=null){String text=texts.get(GraphJson.string(row.get("source")));if(text==null)throw invalid("FOREIGN_DIAGNOSTIC");position(row.get("span"),text,limitations);}
                else if(row.get("span")!=null)throw invalid("DIAGNOSTIC_POSITION");
                if(kind.equals("ERROR"))limitations.add("NATIVE_DIAGNOSTIC_ERROR");
            }
            var result=new Capture(content,document,references,raw,artifacts,frontier,limitations,context);
            if(!result.current())throw invalid("CONTENT_CHANGED");return result;
        }catch(IllegalArgumentException|ArithmeticException malformed){throw new IOException("JAVAC_CAPTURE_INVALID",malformed);}
    }
    private void require(ContentHandle ref,Set<ContentHandle> references,BooleanSupplier cancelled)throws IOException {checkCancellation(cancelled);if(!content.verify(ref))throw invalid("CONTENT_CHANGED");references.add(ref);}
    private static void checkCancellation(BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw invalid("CANCELLED_WITH_FRONTIER");}
    private static void position(Object raw,String text,Set<String> limitations)throws IOException {
        if(raw==null){limitations.add("SOURCE_POSITION_UNAVAILABLE");return;}
        var row=GraphJson.object(raw);fields(row,"startOffset","endOffset","startLine","startColumn","endLine","endColumn");
        var expected=CapturedJavacIndex.span(text,number(row.get("startOffset")),number(row.get("endOffset")));
        if(expected==null)throw invalid("SOURCE_POSITION");
        for(var entry:expected.entrySet())if(number(row.get(entry.getKey()))!=((Number)entry.getValue()).longValue())throw invalid("SOURCE_POSITION");
    }
    private static void element(Object value,Map<String,Artifact> artifacts,Map<String,String> texts,Set<String> limitations)throws IOException {
        if(value==null){limitations.add("ELEMENT_MAPPING_UNAVAILABLE");return;}
        var row=GraphJson.object(value);var required=Set.of("kind","name","owner","module","modelOrigin","descriptor","artifact");var keys=new TreeSet<>(row.keySet());keys.removeAll(Set.of("artifactFailure","declarationSource","declarationSpan"));
        if(!keys.equals(required)||row.containsKey("declarationSource")!=row.containsKey("declarationSpan"))throw invalid("ELEMENT_FIELDS");
        for(String key:List.of("kind","name","modelOrigin"))GraphJson.string(row.get(key));
        javax.lang.model.element.ElementKind.valueOf(GraphJson.string(row.get("kind")));
        if(!Set.of("EXPLICIT","MANDATED","SYNTHETIC").contains(row.get("modelOrigin")))throw invalid("ELEMENT_ORIGIN");
        for(String key:List.of("owner","module","descriptor","artifact","artifactFailure"))if(row.get(key)!=null)GraphJson.string(row.get(key));
        if(row.get("artifact")!=null&&!artifacts.containsKey(row.get("artifact")))throw invalid("ELEMENT_ARTIFACT");
        if(!"PACKAGE".equals(row.get("kind"))&&(row.get("artifact")==null||row.get("descriptor")==null))limitations.add("ELEMENT_MAPPING_UNAVAILABLE");
        if(row.containsKey("declarationSource")){String text=texts.get(GraphJson.string(row.get("declarationSource")));if(text==null)throw invalid("ELEMENT_SOURCE");position(row.get("declarationSpan"),text,limitations);}
    }
    private static ContentHandle handle(Map<String,Object> row){return new ContentHandle(new ContentDigest(GraphJson.string(row.get("digest"))),number(row.get("size")));}
    private static long number(Object value){if(!(value instanceof java.math.BigDecimal number))throw new IllegalArgumentException("JAVAC_CAPTURE_INTEGER");return number.longValueExact();}
    private static List<String> strings(Object value,boolean unique)throws IOException {var list=GraphJson.array(value).stream().map(GraphJson::string).toList();if(unique&&new HashSet<>(list).size()!=list.size())throw invalid("DUPLICATE_ROW");return list;}
    private static void fields(Map<String,Object> row,String... names)throws IOException {if(!row.keySet().equals(Set.of(names)))throw invalid("FIELDS");}
    @SuppressWarnings("unchecked") private static <T> T freeze(T value){
        if(value instanceof Map<?,?> map){var copy=new TreeMap<String,Object>();map.forEach((key,item)->copy.put((String)key,freeze(item)));return (T)Collections.unmodifiableMap(copy);}
        if(value instanceof List<?> list){var copy=new ArrayList<Object>();list.forEach(item->copy.add(freeze(item)));return (T)Collections.unmodifiableList(copy);}return value;
    }
    private static IOException invalid(String code){return new IOException("JAVAC_CAPTURE_"+code);}
}
