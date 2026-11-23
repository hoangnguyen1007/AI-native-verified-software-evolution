package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchMcpTest {
    private static Map<String,Object> message(int id, String method, Map<String,Object> params) {
        return Map.of("jsonrpc","2.0","id",id,"method",method,"params",params);
    }
    private static Map<String,Object> parse(String json) {
        return GraphJson.object(GraphJson.read(json,GraphJson.Limits.defaults()));
    }
    @Test void stdioToolsReadRealGraphAndRejectUnlistedOperations() throws Exception {
        var fixture=new QueryFixture().node("a");
        try (var server=new ArchitectureHttpServer(new ArchitectureQueryApi(fixture.store()),0)) {
            server.start();
            var bridge=new WorkbenchMcpMain(server.uri().getPort());
            String graph=fixture.graph().identity().value();
            var calls=List.of(
                message(1,"initialize",Map.of("protocolVersion","2025-11-25","capabilities",Map.of(),"clientInfo",Map.of("name","test","version","1"))),
                Map.of("jsonrpc","2.0","method","notifications/initialized"),
                message(2,"tools/list",Map.of()),
                message(3,"tools/call",Map.of("name","graph_get","arguments",Map.of("graph",graph))),
                message(4,"tools/call",Map.of("name","graph_query","arguments",Map.of("graph",graph,"context",fixture.context,"operation","summary"))),
                message(5,"tools/call",Map.of("name","architecture_insights","arguments",Map.of("graph",graph,"context",fixture.context,"scope","TYPE","topN",5))),
                message(6,"tools/call",Map.of("name","graph_query","arguments",Map.of("graph",graph,"context",fixture.context,"operation","delete")))
            );
            var input=new StringReader(String.join("\n",calls.stream().map(CanonicalJson::write).toList())+"\n");
            var output=new StringWriter();
            bridge.run(input,new PrintWriter(output,true));
            var responses=output.toString().lines().map(WorkbenchMcpTest::parse).toList();
            assertEquals(6,responses.size());
            assertEquals("2025-11-25",GraphJson.object(responses.get(0).get("result")).get("protocolVersion"));
            assertEquals(7,GraphJson.array(GraphJson.object(responses.get(1).get("result")).get("tools")).size());
            for (int index : List.of(2,3,4)) {
                var result=GraphJson.object(responses.get(index).get("result"));
                assertEquals(false,result.get("isError"));
                var text=GraphJson.object(GraphJson.array(result.get("content")).get(0)).get("text").toString();
                assertTrue(text.contains(graph));
            }
            assertEquals(-32602,((Number)GraphJson.object(responses.get(5).get("error")).get("code")).intValue());
        }
    }
}
