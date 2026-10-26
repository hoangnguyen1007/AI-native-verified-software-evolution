package com.evolution.analysis.javaevidence;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Strict bounded decoder for the packaged, closed subset of the Step-4 schema.
 * Shape acceptance is never a semantic authority or a verified evidence token. */
public final class JavaEvidenceCodec {
    public static final String RESOURCE="/java-evidence/m8-java-evidence-v1.schema.json";
    private static final Map<String,Object> SCHEMA=loadSchema();
    private static final Set<String> RECORD_KINDS=GraphJson.array(SCHEMA.get("oneOf")).stream()
            .map(GraphJson::object).map(rule->GraphJson.string(rule.get("$ref")).substring("#/$defs/".length()))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    static boolean recordKind(String kind){return RECORD_KINDS.contains(kind);}
    private final GraphJson.Limits limits;
    public JavaEvidenceCodec(){this(new GraphJson.Limits(2_000_000,64,200_000));}
    public JavaEvidenceCodec(GraphJson.Limits limits){this.limits=Objects.requireNonNull(limits);}
    public JavaEvidenceRecord read(String json) {
        return new JavaEvidenceRecord(GraphJson.object(GraphJson.read(json,limits)));
    }
    public JavaEvidenceRecord read(Reader input)throws IOException {
        return new JavaEvidenceRecord(GraphJson.object(GraphJson.read(input,limits)));
    }
    public JavaEvidenceRecord create(Map<String,Object> fields){return new JavaEvidenceRecord(fields);}
    static Map<String,Object> validateAndFreeze(Map<String,Object> fields){
        Objects.requireNonNull(fields);
        // Canonical serialization rejects floating-point values and unpaired surrogates.
        Object normalized=GraphJson.read(CanonicalJson.write(fields),new GraphJson.Limits(2_000_000,64,200_000));
        var map=GraphJson.object(normalized);Object name=map.get("schema");
        if(!(name instanceof String version)||!version.endsWith(":m8.4-v1"))throw invalid();
        String kind=version.substring(0,version.length()-":m8.4-v1".length());
        Object definition=GraphJson.object(SCHEMA.get("$defs")).get(kind);
        if(definition==null||!matches(GraphJson.object(definition),map))throw invalid();
        validateCounts(kind,map);
        return castMap(freeze(map));
    }
    private static void validateCounts(String kind,Map<String,Object> record){
        if(!Set.of("java-closure-projection","java-generated-overlay").contains(kind))return;
        var counts=GraphJson.object(record.get("counts"));boolean closure=kind.equals("java-closure-projection");
        var partitions=closure?List.of("pending","narrowed","satisfied","notApplicable","conflicted","reopened"):List.of("accepted","retained","absent","rejected","unavailable","notReached");
        try{
            long total=0;for(String key:partitions)total=Math.addExact(total,((BigDecimal)counts.get(key)).longValueExact());
            if(total!=((BigDecimal)counts.get("total")).longValueExact())throw invalid();
            var table=GraphJson.object(record.get(closure?"current":"outputs"));if(total!=((BigDecimal)table.get("rows")).longValueExact())throw invalid();
            if(closure){long active=0;for(String key:List.of("pending","narrowed","conflicted","reopened"))active=Math.addExact(active,((BigDecimal)counts.get(key)).longValueExact());if(active!=((BigDecimal)counts.get("active")).longValueExact())throw invalid();}
        }catch(ArithmeticException overflow){throw invalid();}
    }
    static IllegalArgumentException invalid(){return new IllegalArgumentException("JAVA_EVIDENCE_SCHEMA_INVALID");}
    private static Map<String,Object> loadSchema(){
        try(var input=JavaEvidenceCodec.class.getResourceAsStream(RESOURCE)){
            if(input==null)throw new IllegalStateException("JAVA_EVIDENCE_SCHEMA_MISSING");
            return GraphJson.object(GraphJson.read(new InputStreamReader(input,StandardCharsets.UTF_8),new GraphJson.Limits(1_000_000,64,100_000)));
        }catch(IOException failure){throw new ExceptionInInitializerError(failure);}
    }
    private static boolean matches(Map<String,Object> rule,Object value){
        if(rule.containsKey("$ref")){
            String reference=GraphJson.string(rule.get("$ref"));
            if(!reference.startsWith("#/$defs/"))throw new IllegalStateException("UNREGISTERED_SCHEMA_REFERENCE");
            return matches(GraphJson.object(GraphJson.object(SCHEMA.get("$defs")).get(reference.substring(8))),value);
        }
        if(rule.containsKey("const")&&!Objects.equals(rule.get("const"),value))return false;
        if(rule.containsKey("enum")&&!GraphJson.array(rule.get("enum")).contains(value))return false;
        if(rule.containsKey("type")&&!type(GraphJson.string(rule.get("type")),value))return false;
        if(rule.containsKey("not")&&matches(GraphJson.object(rule.get("not")),value))return false;
        for(String keyword:List.of("allOf","anyOf","oneOf"))if(rule.containsKey(keyword)){
            long count=GraphJson.array(rule.get(keyword)).stream().filter(r->matches(GraphJson.object(r),value)).count();
            int size=GraphJson.array(rule.get(keyword)).size();
            if(keyword.equals("allOf")&&count!=size||keyword.equals("anyOf")&&count==0||keyword.equals("oneOf")&&count!=1)return false;
        }
        if(rule.containsKey("if")&&matches(GraphJson.object(rule.get("if")),value)&&rule.containsKey("then")&&!matches(GraphJson.object(rule.get("then")),value))return false;
        if(value instanceof Map<?,?>){
            var map=GraphJson.object(value);
            if(rule.containsKey("required"))for(Object key:GraphJson.array(rule.get("required")))if(!map.containsKey(key))return false;
            var props=rule.containsKey("properties")?GraphJson.object(rule.get("properties")):Map.<String,Object>of();
            if(Boolean.FALSE.equals(rule.get("additionalProperties"))&&!props.keySet().containsAll(map.keySet()))return false;
            for(var entry:props.entrySet())if(map.containsKey(entry.getKey())&&!matches(GraphJson.object(entry.getValue()),map.get(entry.getKey())))return false;
        }
        if(value instanceof List<?> list){
            if(!within(rule,"minItems","maxItems",BigDecimal.valueOf(list.size())))return false;
            if(Boolean.TRUE.equals(rule.get("uniqueItems"))&&new HashSet<>(list).size()!=list.size())return false;
            if(rule.containsKey("items"))for(Object item:list)if(!matches(GraphJson.object(rule.get("items")),item))return false;
        }
        if(value instanceof String text){
            if(!within(rule,"minLength","maxLength",BigDecimal.valueOf(text.codePointCount(0,text.length()))))return false;
            if(rule.containsKey("pattern")&&!Pattern.compile(GraphJson.string(rule.get("pattern"))).matcher(text).find())return false;
        }
        if(value instanceof BigDecimal number&&!within(rule,"minimum","maximum",number))return false;
        return true;
    }
    private static boolean within(Map<String,Object> rule,String minimum,String maximum,BigDecimal n){
        return (!rule.containsKey(minimum)||n.compareTo((BigDecimal)rule.get(minimum))>=0)
                &&(!rule.containsKey(maximum)||n.compareTo((BigDecimal)rule.get(maximum))<=0);
    }
    private static boolean type(String type,Object value){return switch(type){
        case "object"->value instanceof Map<?,?>;case "array"->value instanceof List<?>;
        case "string"->value instanceof String;case "null"->value==null;case "boolean"->value instanceof Boolean;
        case "integer"->value instanceof BigDecimal n&&n.stripTrailingZeros().scale()<=0;
        default->throw new IllegalStateException("UNREGISTERED_SCHEMA_TYPE");};}
    private static Object freeze(Object value){
        if(value instanceof Map<?,?> map){var result=new TreeMap<String,Object>();map.forEach((key,item)->result.put((String)key,freeze(item)));return Collections.unmodifiableMap(result);}
        if(value instanceof List<?> list)return Collections.unmodifiableList(list.stream().map(JavaEvidenceCodec::freeze).toList());
        return value;
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object value){return (Map<String,Object>)value;}
}
