package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.execution.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionApiTest {
    @Test void onlyRegisteredIdentifiersCanCrossTheExecutionBoundary(){
        var api=new ArchitectureQueryApi(new QueryFixture().store());
        for(String field:new String[]{"argv","env","path","image","broker","vmName","command"}){
            var body=CanonicalJson.write(Map.of("registration","sha256:"+"a".repeat(64),field,"PRIVATE_SENTINEL"));
            var result=api.handle("POST","/api/v2/executions/submit","application/json",body.getBytes(StandardCharsets.UTF_8),()->false);
            assertEquals(400,result.status(),field);
            assertFalse(new String(result.body(),StandardCharsets.UTF_8).contains("PRIVATE_SENTINEL"));
        }
    }
    @Test void missingExecutorHasAnExplicitUnavailableLaneAndNoHostFallback(){
        var api=new ArchitectureQueryApi(new QueryFixture().store());
        var body=CanonicalJson.write(Map.of("registration","sha256:"+"a".repeat(64))).getBytes(StandardCharsets.UTF_8);
        var result=api.handle("POST","/api/v2/executions/submit","application/json",body,()->false);
        assertEquals(503,result.status());
        assertTrue(new String(result.body(),StandardCharsets.UTF_8).contains("EXECUTIONS_UNAVAILABLE"));
        assertEquals(405,api.handle("GET","/api/v2/executions/submit","application/json",body,()->false).status());
    }
    @Test void registeredOperationsUseOnePortAndLosslessPublicCounters(){
        var id=ContentDigest.sha256Utf8("registration");var seen=new java.util.ArrayList<RegisteredExecutionPort.Operation>();
        var view=new RegisteredExecutionPort.View(id,ContentDigest.sha256Utf8("plan"),ContentDigest.sha256Utf8("task"),"attempt","RUNNING",ExecutionState.Phase.RUNNING,"9007199254740993","2",SandboxExecutor.Availability.AVAILABLE,"");
        var api=ArchitectureQueryApi.withExecutions(new QueryFixture().store(),new PublishedReports(),ApiLimits.defaults(),(op,registration)->{assertEquals(id,registration);seen.add(op);return view;});
        for(var op:RegisteredExecutionPort.Operation.values()){
            var response=api.handle("POST","/api/v2/executions/"+op.name().toLowerCase(java.util.Locale.ROOT),"application/json",CanonicalJson.write(Map.of("registration",id)).getBytes(StandardCharsets.UTF_8),()->false);
            assertEquals(200,response.status());String json=new String(response.body(),StandardCharsets.UTF_8);
            assertTrue(json.contains("\"revision\":\"9007199254740993\""));assertTrue(json.contains(RegisteredExecutionPort.VERSION));
        }
        assertEquals(java.util.List.of(RegisteredExecutionPort.Operation.values()),seen);
    }
    @Test void invalidAndCancelledRequestsNeverInvokeTheRegisteredPort(){
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var api=ArchitectureQueryApi.withExecutions(new QueryFixture().store(),new PublishedReports(),ApiLimits.defaults(),(op,id)->{calls.incrementAndGet();throw new java.io.IOException("PRIVATE_SENTINEL");});
        for(String body:new String[]{"{}","{\"registration\":2}","{\"registration\":\"bad\"}","{\"registration\":\"a\",\"registration\":\"b\"}"})
            assertEquals(400,api.handle("POST","/api/v2/executions/submit","application/json",body.getBytes(StandardCharsets.UTF_8),()->false).status());
        byte[] body=CanonicalJson.write(Map.of("registration",ContentDigest.sha256Utf8("registered"))).getBytes(StandardCharsets.UTF_8);
        assertEquals(409,api.handle("POST","/api/v2/executions/submit","application/json",body,()->true).status());assertEquals(0,calls.get());
        var unavailable=api.handle("POST","/api/v2/executions/submit","application/json",body,()->false);
        assertEquals(503,unavailable.status());assertFalse(new String(unavailable.body(),StandardCharsets.UTF_8).contains("PRIVATE_SENTINEL"));
    }
    @Test void missingAndForeignReceiptsHaveTypedErrorsAndCannotBePublishedAsRequested(){
        var id=ContentDigest.sha256Utf8("registration");byte[] body=CanonicalJson.write(Map.of("registration",id)).getBytes(StandardCharsets.UTF_8);
        for(var reason:RegisteredExecutionPort.Failure.values()){
            var api=ArchitectureQueryApi.withExecutions(new QueryFixture().store(),new PublishedReports(),ApiLimits.defaults(),(op,registration)->{throw new RegisteredExecutionPort.Rejected(reason);});
            var result=api.handle("POST","/api/v2/executions/status","application/json",body,()->false);
            assertEquals(reason==RegisteredExecutionPort.Failure.SCOPE_CONFLICT?409:404,result.status());
            assertTrue(new String(result.body(),StandardCharsets.UTF_8).contains("EXECUTION_"+reason.name()));
        }
        var api=ArchitectureQueryApi.withExecutions(new QueryFixture().store(),new PublishedReports(),ApiLimits.defaults(),(op,registration)->new RegisteredExecutionPort.View(ContentDigest.sha256Utf8("foreign"),id,id,"attempt","RUNNING",ExecutionState.Phase.RUNNING,"1","1",SandboxExecutor.Availability.AVAILABLE,""));
        assertEquals(503,api.handle("POST","/api/v2/executions/status","application/json",body,()->false).status());
    }
    @Test void realLoopbackHttpRetainsRegistrationScopeAndRejectsCommandOverrides()throws Exception{
        var id=ContentDigest.sha256Utf8("registered");var calls=new java.util.concurrent.atomic.AtomicInteger();
        var api=ArchitectureQueryApi.withExecutions(new QueryFixture().store(),new PublishedReports(),ApiLimits.defaults(),(op,registration)->{
            calls.incrementAndGet();return new RegisteredExecutionPort.View(registration,id,id,"attempt","RUNNING",ExecutionState.Phase.RUNNING,"1","1",SandboxExecutor.Availability.AVAILABLE,"");
        });
        try(var server=new ArchitectureHttpServer(api,0);var client=java.net.http.HttpClient.newHttpClient()){
            server.start();var endpoint=server.uri().resolve("/api/v2/executions/submit");
            var body=CanonicalJson.write(Map.of("registration",id));
            var request=java.net.http.HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(5)).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            assertArrayEquals(api.handle("POST","/api/v2/executions/submit","application/json",body.getBytes(StandardCharsets.UTF_8),()->false).body(),response.body().getBytes(StandardCharsets.UTF_8));
            int accepted=calls.get();
            request=java.net.http.HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(5)).header("Content-Type","application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(CanonicalJson.write(Map.of("registration",id,"argv",java.util.List.of("PRIVATE_SENTINEL"))))).build();
            response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());assertEquals(400,response.statusCode());assertEquals(accepted,calls.get());
            assertFalse(response.body().contains("PRIVATE_SENTINEL"));
            response=client.send(java.net.http.HttpRequest.newBuilder(endpoint).timeout(java.time.Duration.ofSeconds(5)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(405,response.statusCode());assertEquals("POST",response.headers().firstValue("Allow").orElseThrow());
        }
    }
    @Test void composingExecutionDoesNotReplaceOrMutateExistingApiBindings(){
        var original=new ArchitectureQueryApi(new QueryFixture().store());
        RegisteredExecutionPort port=(op,id)->{throw new RegisteredExecutionPort.Rejected(RegisteredExecutionPort.Failure.NOT_REGISTERED);};
        var composed=ArchitectureQueryApi.withExecutions(original,port);
        assertArrayEquals(original.handle("GET","/api/v1",null,new byte[0],()->false).body(),composed.handle("GET","/api/v1",null,new byte[0],()->false).body());
        var body=CanonicalJson.write(Map.of("registration",ContentDigest.sha256Utf8("registration"))).getBytes(StandardCharsets.UTF_8);
        assertEquals(503,original.handle("POST","/api/v2/executions/status","application/json",body,()->false).status());
        assertEquals(404,composed.handle("POST","/api/v2/executions/status","application/json",body,()->false).status());
        assertThrows(IllegalArgumentException.class,()->ArchitectureQueryApi.withExecutions(composed,port));
    }
}
