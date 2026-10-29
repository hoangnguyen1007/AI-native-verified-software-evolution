package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.polyglot.SemanticQueryService;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only neutral semantic queries. Paths, native commands and trust policy
 * are supplied by operator composition, never by request JSON. */
public final class SemanticQueryApi {
    private final SemanticQueryService queries;private final ApiLimits limits;
    public SemanticQueryApi(SemanticQueryService queries,ApiLimits limits){this.queries=Objects.requireNonNull(queries);this.limits=Objects.requireNonNull(limits);}
    public static ArchitectureQueryApi.Response unavailable(ApiLimits limits){return error(limits,503,"SEMANTIC_QUERIES_UNAVAILABLE");}
    public ArchitectureQueryApi.Response handle(String method,String path,String contentType,byte[] body,BooleanSupplier cancelled){
        try{
            if(body.length>limits.requestBytes())return error(413,"REQUEST_TOO_LARGE");if(cancelled.getAsBoolean())return error(409,"SEMANTIC_QUERY_CANCELLED");
            if(!Set.of("/api/v1/semantic/summary","/api/v1/semantic/contexts","/api/v1/semantic/gaps","/api/v1/semantic/root-gaps","/api/v1/semantic/answers").contains(path))return error(404,"NOT_FOUND");if(!method.equals("POST"))return error(405,"METHOD_NOT_ALLOWED");if(!ArchitectureQueryApi.jsonMediaType(contentType))return error(415,"UNSUPPORTED_MEDIA_TYPE");
            var text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body));
            var input=QueryJson.object(GraphJson.read(text.toString(),new GraphJson.Limits(limits.requestBytes(),limits.jsonDepth(),limits.jsonValues())),path.endsWith("summary")?new String[]{"plan"}:path.endsWith("contexts")||path.endsWith("root-gaps")?new String[]{"plan","size","cursor"}:path.endsWith("answers")?new String[]{"plan","context","view","size","cursor"}:new String[]{"plan","context","size","cursor"});
            var plan=new ContentDigest(QueryJson.text(input,"plan"));Object data;
            if(path.endsWith("summary"))data=queries.summary(plan,cancelled);
            else{
                if(input.containsKey("size")&&input.get("size")==null||input.containsKey("view")&&input.get("view")==null)throw new IllegalArgumentException("SEMANTIC_PAGE_VALUE_REQUIRED");int size=input.get("size")==null?20:((java.math.BigDecimal)input.get("size")).intValueExact();var cursor=Optional.ofNullable(input.get("cursor")).map(SemanticQueryService::cursor);
                data=path.endsWith("contexts")?queries.contexts(plan,size,cursor,cancelled):path.endsWith("root-gaps")?queries.rootGaps(plan,size,cursor,cancelled):path.endsWith("answers")?queries.answers(plan,QueryJson.text(input,"context"),input.get("view")==null?"CURRENT":QueryJson.text(input,"view"),size,cursor,cancelled):queries.gaps(plan,QueryJson.text(input,"context"),size,cursor,cancelled);
            }
            byte[] encoded=BoundedJson.encode(data,limits.responseBytes());return new ArchitectureQueryApi.Response(200,BoundedJson.encode(Map.of("apiVersion",SemanticQueryService.VERSION,"resultIdentity",ContentDigest.sha256(encoded),"data",data),limits.responseBytes()));
        }catch(GraphJson.LimitExceededException large){return error(413,"JSON_LIMIT_EXCEEDED");}
        catch(CharacterCodingException|IllegalArgumentException|ArithmeticException|ClassCastException invalid){return error(400,"INVALID_REQUEST");}
        catch(BoundedJson.TooLarge large){return error(422,"RESPONSE_TOO_LARGE");}
        catch(IOException unavailable){String code=Objects.toString(unavailable.getMessage(),"");if(!code.matches("SEMANTIC_[A-Z_0-9]{1,100}"))code="SEMANTIC_STORE_UNAVAILABLE";int status=Set.of("SEMANTIC_QUERY_NOT_FOUND","SEMANTIC_QUERY_CONTEXT_NOT_FOUND").contains(code)?404:code.contains("CURSOR")||code.contains("POLICY")||code.contains("CANCELLED")?409:503;return error(status,code);}
    }
    private ArchitectureQueryApi.Response error(int status,String code){return error(limits,status,code);}
    private static ArchitectureQueryApi.Response error(ApiLimits limits,int status,String code){return new ArchitectureQueryApi.Response(status,BoundedJson.encode(Map.of("apiVersion",SemanticQueryService.VERSION,"error",Map.of("code",code,"message","The semantic query could not be completed.")),limits.responseBytes()));}
}
