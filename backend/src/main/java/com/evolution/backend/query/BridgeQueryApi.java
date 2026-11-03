package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only adapter; requests select immutable scope/capabilities, never paths,
 * policies, native commands, proof issuers or semantic authority. */
public final class BridgeQueryApi {
    private static final java.util.logging.Logger LOGGER=java.util.logging.Logger.getLogger(BridgeQueryApi.class.getName());
    private final BridgeQueryService queries;private final ApiLimits limits;
    public BridgeQueryApi(BridgeQueryService queries,ApiLimits limits){this.queries=Objects.requireNonNull(queries);this.limits=Objects.requireNonNull(limits);}
    public ArchitectureQueryApi.Response handle(String method,String path,String contentType,byte[] body,BooleanSupplier cancelled){
        try{
            if(body.length>limits.requestBytes())return error(413,"REQUEST_TOO_LARGE");if(!Set.of("/api/v1/bridges/catalog","/api/v1/bridges/summary","/api/v1/bridges/page","/api/v1/bridges/impact","/api/v1/bridges/topology").contains(path))return error(404,"NOT_FOUND");if(!method.equals("POST"))return error(405,"METHOD_NOT_ALLOWED");if(!ArchitectureQueryApi.jsonMediaType(contentType))return error(415,"UNSUPPORTED_MEDIA_TYPE");
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));var value=QueryJson.object(GraphJson.read(text.toString(),new GraphJson.Limits(limits.requestBytes(),limits.jsonDepth(),limits.jsonValues())),path.endsWith("catalog")?new String[]{"after","size"}:path.endsWith("summary")?new String[]{"scope"}:(path.endsWith("impact")||path.endsWith("topology"))?new String[]{"scope","target","depth","nodes","scanBudget"}:new String[]{"scope","section","category","partition","symbol","document","search","size","cursor"});Object data;
            if(path.endsWith("catalog"))data=queries.catalog(optional(value,"after").map(ContentDigest::new),number(value,"size",5),cancelled);
            else{var scope=new ContentDigest(QueryJson.text(value,"scope"));if(path.endsWith("summary"))data=queries.summary(scope,cancelled);
            else if(path.endsWith("topology"))data=queries.topology(scope,new ContentDigest(QueryJson.text(value,"target")),number(value,"depth",3),number(value,"nodes",20),number(value,"scanBudget",1000),cancelled);
            else if(path.endsWith("impact"))data=queries.impact(scope,new ContentDigest(QueryJson.text(value,"target")),number(value,"depth",3),number(value,"nodes",20),number(value,"scanBudget",1000),cancelled);
            else{int size=value.containsKey("size")?((java.math.BigDecimal)value.get("size")).intValueExact():20;var selection=new BridgeQueryService.Selection(BridgeQueryService.Section.valueOf(QueryJson.text(value,"section")),optional(value,"category"),optional(value,"partition"),optional(value,"symbol"),optional(value,"document"),optional(value,"search"));data=queries.page(scope,selection,size,Optional.ofNullable(value.get("cursor")).map(BridgeQueryService::cursor),cancelled);}}
            return new ArchitectureQueryApi.Response(200,BoundedJson.encode(Map.of("apiVersion",BridgeQueryService.VERSION,"resultIdentity",com.evolution.analysis.contract.serialization.CanonicalJson.sha256(data),"data",data),limits.responseBytes()));
        }catch(GraphJson.LimitExceededException large){return error(413,"JSON_LIMIT_EXCEEDED");}
        catch(BoundedJson.TooLarge large){return error(422,"RESPONSE_TOO_LARGE");}
        catch(CharacterCodingException|IllegalArgumentException|ArithmeticException|ClassCastException|NullPointerException invalid){return error(400,"INVALID_REQUEST");}
        catch(IOException|UncheckedIOException failure){String code=failure.getMessage();if(code==null||!code.matches("BRIDGE_[A-Z_0-9]{1,100}"))code="BRIDGE_STORE_UNAVAILABLE";int status=Set.of("BRIDGE_QUERY_NOT_FOUND","BRIDGE_QUERY_PARTITION_NOT_FOUND").contains(code)?404:code.contains("CURSOR")||code.contains("STALE")||code.contains("POLICY")||code.contains("CANCELLED")?409:503;if(status>=500)diagnose(path,code,failure);return error(status,code);}
    }
    /** Raw messages, Throwable formatting, request bodies and local paths can
     * contain secrets. Retain bounded cause codes and code locations instead. */
    private static void diagnose(String path,String publicCode,Throwable failure){
        var causes=new ArrayList<Object>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for(var cause=failure;cause!=null&&causes.size()<4&&seen.add(cause);cause=cause.getCause()){
            var frames=new ArrayList<Object>();for(var frame:cause.getStackTrace()){if(frames.size()==8)break;frames.add(Map.of("class",frame.getClassName(),"method",frame.getMethodName(),"line",frame.getLineNumber()));}
            String message=cause.getMessage();var detail=new TreeMap<String,Object>();detail.put("type",cause.getClass().getName());detail.put("frames",frames);if(message!=null&&message.matches("(?:BRIDGE|SEMANTIC|STREAMING|SQLITE)_[A-Z_0-9]{1,100}"))detail.put("code",message);causes.add(detail);
        }
        LOGGER.log(java.util.logging.Level.WARNING,com.evolution.analysis.contract.serialization.CanonicalJson.write(Map.of("event","BRIDGE_QUERY_FAILURE","operation",path.substring(path.lastIndexOf('/')+1),"code",publicCode,"causes",causes)));
    }
    private static Optional<String> optional(Map<String,Object> value,String field){if(value.containsKey(field)&&value.get(field)==null)throw new IllegalArgumentException("BRIDGE_FILTER_REQUIRED");return Optional.ofNullable(value.get(field)).map(GraphJson::string);}
    private static int number(Map<String,Object> value,String field,int fallback){return value.containsKey(field)?((java.math.BigDecimal)value.get(field)).intValueExact():fallback;}
    private ArchitectureQueryApi.Response error(int status,String code){return new ArchitectureQueryApi.Response(status,BoundedJson.encode(Map.of("apiVersion",BridgeQueryService.VERSION,"error",Map.of("code",code)),limits.responseBytes()));}
}
