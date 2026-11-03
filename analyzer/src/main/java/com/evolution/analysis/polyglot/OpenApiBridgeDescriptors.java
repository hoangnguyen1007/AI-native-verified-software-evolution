package com.evolution.analysis.polyglot;

import com.evolution.analysis.acquisition.streaming.*;
import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Captured JSON contract metadata; never fetches a reference or runs a target. */
public final class OpenApiBridgeDescriptors {
    // This profile reads OpenAPI 3.0/3.1 fixed operations; CONNECT and the 3.2
    // additionalOperations extension are not silently treated as supported.
    public static final VersionedIdentifier PROVIDER=new VersionedIdentifier("architecture.openapi-descriptors","m8.4-v1");
    public record Result(Map<String,String> operands,Optional<ContentHandle> parameters,List<String> diagnostics){
        public Result{operands=Collections.unmodifiableMap(new TreeMap<>(operands));diagnostics=List.copyOf(diagnostics);}
    }
    private final RepositoryContentStore content;
    public OpenApiBridgeDescriptors(RepositoryContentStore content){this.content=Objects.requireNonNull(content);}
    public Result read(ContentHandle source,String method,String path,int workerBytes,BooleanSupplier cancelled)throws IOException{
        check(cancelled);if(workerBytes<1)throw new IllegalArgumentException("OPENAPI_WORKER_BUDGET");if(!content.verify(source))throw new IOException("BRIDGE_OPENAPI_SOURCE_UNAVAILABLE");
        if(source.size()>workerBytes)return new Result(Map.of(),Optional.empty(),List.of("OPENAPI_SOURCE_WORKER_REQUIRED"));
        Map<String,Object> root;
        try(var stream=content.open(source)){
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(stream.readNBytes(workerBytes))).toString();
            if(text.startsWith("\uFEFF"))text=text.substring(1);
            root=GraphJson.object(GraphJson.read(text,new GraphJson.Limits(workerBytes,128,workerBytes)));
        }catch(IllegalArgumentException|CharacterCodingException invalid){return new Result(Map.of(),Optional.empty(),List.of("OPENAPI_CAPTURED_JSON_UNSUPPORTED"));}
        var operands=new TreeMap<String,String>();var diagnostics=new TreeSet<String>();
        if(!(root.get("openapi") instanceof String dialect)||!dialect.matches("3\\.[01]\\.[0-9]+"))return new Result(operands,Optional.empty(),List.of("OPENAPI_DIALECT_UNSUPPORTED"));
        operands.put("dialect",dialect);
        if(root.get("info") instanceof Map<?,?> info){if(info.get("version") instanceof String version&&!version.isBlank())operands.put("version",version);else diagnostics.add("OPENAPI_INFO_INCOMPLETE");if(!(info.get("title") instanceof String title)||title.isBlank())diagnostics.add("OPENAPI_INFO_INCOMPLETE");}
        else diagnostics.add("OPENAPI_INFO_INCOMPLETE");
        var paths=object(root.get("paths"));var item=object(paths.get(path));var operation=object(item.get(method.toLowerCase(Locale.ROOT)));
        if(!Set.of("GET","PUT","POST","DELETE","OPTIONS","HEAD","PATCH","TRACE").contains(method)||operation.isEmpty()){diagnostics.add("OPENAPI_OPERATION_NOT_CAPTURED");return new Result(operands,Optional.empty(),List.copyOf(diagnostics));}
        if(item.containsKey("$ref"))diagnostics.add("OPENAPI_PATH_ITEM_REFERENCE_REQUIRED");
        var resolver=new Resolver(root,dialect,diagnostics,cancelled);var parameters=new TreeMap<String,Object>();
        resolver.parameters(item.get("parameters"),parameters);resolver.parameters(operation.get("parameters"),parameters);
        for(Object value:parameters.values()){var parameter=(Map<?,?>)value;if(parameter.containsKey("schema")){if(parameter.get("schema")==null)diagnostics.add("OPENAPI_SCHEMA_INVALID");else resolver.schemaQueue.add(parameter.get("schema"));}if(parameter.containsKey("content"))resolver.contentSchemas(parameter.get("content"));}
        var templates=new TreeSet<String>();var matcher=java.util.regex.Pattern.compile("\\{([^{}]+)}").matcher(path);while(matcher.find())templates.add(matcher.group(1));
        if(path.replaceAll("\\{[^{}]+}","").matches(".*[{}].*"))diagnostics.add("OPENAPI_PATH_TEMPLATE_INVALID");
        for(String name:templates)if(!parameters.containsKey("path:"+name))diagnostics.add("OPENAPI_PATH_PARAMETER_MISSING");
        for(var entry:parameters.entrySet())if(entry.getKey().startsWith("path:")&&!templates.contains(entry.getKey().substring(5)))diagnostics.add("OPENAPI_PATH_PARAMETER_NOT_IN_TEMPLATE");
        Object body=null;if(operation.containsKey("requestBody")){body=resolver.referenceObject(operation.get("requestBody"));if(body instanceof Map<?,?> map)resolver.contentSchemas(map.get("content"));else diagnostics.add("OPENAPI_REQUEST_BODY_INVALID");}
        resolver.schemas();check(cancelled);
        if(!diagnostics.isEmpty())return new Result(operands,Optional.empty(),List.copyOf(diagnostics));
        var closure=new TreeMap<String,Object>();closure.put("schema","openapi-request-closure:m8.4-v1");closure.put("provider",PROVIDER);closure.put("source",source);closure.put("dialect",dialect);closure.put("method",method);closure.put("path",path);closure.put("parameters",parameters);closure.put("requestBody",body);closure.put("references",resolver.references);
        var captured=content.put(new ByteArrayInputStream(CanonicalJson.write(closure).getBytes(StandardCharsets.UTF_8)),cancelled);operands.put("parameters",captured.digest().value());return new Result(operands,Optional.of(captured),List.of());
    }
    private static Map<?,?> object(Object value){return value instanceof Map<?,?> map?map:Map.of();}
    private static void check(BooleanSupplier cancelled)throws IOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("BRIDGE_OPENAPI_CANCELLED");}
    private static final class Resolver {
        final Map<String,Object> root;final String dialect;final Set<String> diagnostics;final BooleanSupplier cancelled;
        final Map<String,Object> references=new TreeMap<>();final ArrayDeque<Object> schemaQueue=new ArrayDeque<>();final Set<Object> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        Resolver(Map<String,Object> root,String dialect,Set<String> diagnostics,BooleanSupplier cancelled){this.root=root;this.dialect=dialect;this.diagnostics=diagnostics;this.cancelled=cancelled;}
        Object pointer(String reference)throws IOException{
            check(cancelled);if(references.containsKey(reference))return references.get(reference);
            String fragment;try{var uri=java.net.URI.create(reference);if(!reference.startsWith("#")||uri.getRawFragment()==null){diagnostics.add("OPENAPI_EXTERNAL_REFERENCE_REQUIRED");return null;}fragment=uri.getFragment();}catch(IllegalArgumentException invalid){diagnostics.add("OPENAPI_REFERENCE_INVALID");return null;}
            Object current=root;if(!fragment.isEmpty()){
                if(!fragment.startsWith("/")){diagnostics.add("OPENAPI_ANCHOR_REFERENCE_REQUIRED");return null;}
                for(String raw:fragment.substring(1).split("/",-1)){
                    if(java.util.regex.Pattern.compile("~(?![01])").matcher(raw).find()){diagnostics.add("OPENAPI_REFERENCE_INVALID");return null;}
                    String token=raw.replace("~1","/").replace("~0","~");if(current instanceof Map<?,?> map&&map.containsKey(token))current=map.get(token);
                    else if(current instanceof List<?> list&&token.matches("0|[1-9][0-9]*")){try{current=list.get(Integer.parseInt(token));}catch(IndexOutOfBoundsException|NumberFormatException invalid){current=null;}}
                    else current=null;
                    if(current==null){diagnostics.add("OPENAPI_LOCAL_REFERENCE_MISSING");return null;}
                }
            }
            references.put(reference,current);return current;
        }
        Object referenceObject(Object value)throws IOException{
            var seen=new HashSet<String>();while(value instanceof Map<?,?> map&&map.containsKey("$ref")){
                if(!(map.get("$ref") instanceof String ref)){diagnostics.add("OPENAPI_REFERENCE_INVALID");return null;}
                if(!seen.add(ref)){diagnostics.add("OPENAPI_REFERENCE_OBJECT_CYCLE");return null;}value=pointer(ref);
            }return value;
        }
        void parameters(Object value,Map<String,Object> merged)throws IOException{
            if(value==null)return;if(!(value instanceof List<?> list)){diagnostics.add("OPENAPI_PARAMETERS_INVALID");return;}
            var local=new HashSet<String>();for(Object entry:list){check(cancelled);Object resolved=referenceObject(entry);if(!(resolved instanceof Map<?,?> parameter)||!(parameter.get("name") instanceof String name)||name.isBlank()||!(parameter.get("in") instanceof String location)||!Set.of("path","query","header","cookie").contains(location)){diagnostics.add("OPENAPI_PARAMETER_INVALID");continue;}
                String key=location+":"+name;if(!local.add(key))diagnostics.add("OPENAPI_DUPLICATE_PARAMETER");if(location.equals("path")&&!Boolean.TRUE.equals(parameter.get("required")))diagnostics.add("OPENAPI_PATH_PARAMETER_NOT_REQUIRED");
                if(parameter.containsKey("schema")&&parameter.containsKey("content"))diagnostics.add("OPENAPI_PARAMETER_SCHEMA_CONTENT_CONFLICT");
                if(!parameter.containsKey("schema")&&!parameter.containsKey("content"))diagnostics.add("OPENAPI_PARAMETER_SCHEMA_REQUIRED");
                if(parameter.containsKey("required")&&!(parameter.get("required") instanceof Boolean))diagnostics.add("OPENAPI_PARAMETER_REQUIRED_INVALID");
                merged.put(key,resolved);
            }
        }
        void contentSchemas(Object value){if(!(value instanceof Map<?,?> media)||media.isEmpty()){diagnostics.add("OPENAPI_MEDIA_CONTENT_INVALID");return;}for(Object entry:media.values()){if(!(entry instanceof Map<?,?> map)){diagnostics.add("OPENAPI_MEDIA_CONTENT_INVALID");continue;}if(map.containsKey("schema")){if(map.get("schema")==null)diagnostics.add("OPENAPI_SCHEMA_INVALID");else schemaQueue.add(map.get("schema"));}if(map.containsKey("encoding"))diagnostics.add("OPENAPI_MEDIA_ENCODING_REQUIRED");}}
        void schemas()throws IOException{
            while(!schemaQueue.isEmpty()){check(cancelled);Object value=schemaQueue.removeFirst();if(value instanceof Boolean){if(dialect.startsWith("3.0."))diagnostics.add("OPENAPI_BOOLEAN_SCHEMA_UNSUPPORTED");continue;}if(!(value instanceof Map<?,?> schema)){diagnostics.add("OPENAPI_SCHEMA_INVALID");continue;}if(!visited.add(value))continue;
                for(String key:List.of("$id","$anchor","$dynamicAnchor","$dynamicRef","$recursiveRef","$vocabulary"))if(schema.containsKey(key))diagnostics.add("OPENAPI_SCHEMA_SCOPE_UNSUPPORTED");
                if(schema.get("$schema") instanceof String declared&&!Set.of("https://spec.openapis.org/oas/3.1/dialect/base","https://json-schema.org/draft/2020-12/schema").contains(declared))diagnostics.add("OPENAPI_SCHEMA_DIALECT_UNSUPPORTED");
                if(schema.containsKey("$ref")){if(schema.get("$ref") instanceof String ref){Object target=pointer(ref);if(target!=null)schemaQueue.add(target);}else diagnostics.add("OPENAPI_REFERENCE_INVALID");}
                for(String key:List.of("items","additionalProperties","contains","not","if","then","else","propertyNames","unevaluatedProperties","unevaluatedItems"))if(schema.get(key)!=null&&!(key.equals("additionalProperties")&&schema.get(key) instanceof Boolean))schemaQueue.add(schema.get(key));
                for(String key:List.of("properties","patternProperties","$defs","definitions","dependentSchemas"))if(schema.get(key) instanceof Map<?,?> members)for(Object member:members.values())if(member!=null)schemaQueue.add(member);
                for(String key:List.of("allOf","anyOf","oneOf","prefixItems"))if(schema.get(key) instanceof List<?> members)for(Object member:members)if(member!=null)schemaQueue.add(member);
            }
        }
    }
}
