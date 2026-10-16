package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphJson;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.evolution.backend.query.ArchitectureQueryApiTest.*;

class ArchitectureHttpServerTest {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpResponse<String> send(ArchitectureHttpServer server,String method,String route,String media,String body,String... headers) throws Exception {
        var request=HttpRequest.newBuilder(server.uri().resolve(route)).timeout(Duration.ofSeconds(5));
        if (media!=null) request.header("Content-Type",media);
        for(int i=0;i<headers.length;i+=2) request.header(headers[i],headers[i+1]);
        return client.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    @Test void actualHttpPreservesCanonicalResponseAndDiscoveryAndSecurityHeaders() throws Exception {
        var f=new QueryFixture().node("a").node("b").edge("a","b"); var api=new ArchitectureQueryApi(f.store()); var r=f.request();
        try(var server=new ArchitectureHttpServer(api,0)) {
            server.start();
            String input=CanonicalJson.write(Map.of("request",r));
            var result=send(server,"POST",route(r,"summary"),"application/json; charset=UTF-8",input);
            assertEquals(200,result.statusCode());
            assertArrayEquals(api.handle("POST",route(r,"summary"),"application/json",input.getBytes(StandardCharsets.UTF_8),() -> false).body(),result.body().getBytes(StandardCharsets.UTF_8));
            assertEquals("application/json; charset=utf-8",result.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("no-store",result.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff",result.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(result.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals(200,send(server,"GET","/api/v1",null,"").statusCode());
            var graph=send(server,"GET","/api/v1/graphs/"+r.graph().value(),null,"");
            var data=GraphJson.object(GraphJson.object(GraphJson.read(graph.body(),GraphJson.Limits.defaults())).get("data"));
            assertEquals(r.graph().value(),data.get("graph")); assertEquals(1,GraphJson.array(data.get("contexts")).size());
            assertEquals(200,send(server,"GET","/api/v1",null,"","Origin",server.uri().toString()).statusCode());
        }
    }
    @Test void specificJsonExclusionOverridesAnAcceptWildcard() throws Exception {
        var api=new ArchitectureQueryApi(new QueryFixture().store());
        try(var server=new ArchitectureHttpServer(api,0)) {
            server.start();
            assertEquals(406,send(server,"GET","/api/v1",null,"","Accept","application/json;q=0, */*;q=1").statusCode());
        }
    }
    @Test void unsafeOriginsMethodsEncodingsQueriesAndMediaAreRejectedOverHttp() throws Exception {
        var f=new QueryFixture().node("a"); var r=f.request(); var api=new ArchitectureQueryApi(f.store());
        try(var server=new ArchitectureHttpServer(api,0)) {
            server.start();
            String input=CanonicalJson.write(Map.of("request",r));
            assertEquals(403,send(server,"GET","/api/v1",null,"","Origin","https://evil.test").statusCode());
            assertEquals(403,send(server,"GET","/api/v1",null,"","Origin","null").statusCode());
            assertEquals(406,send(server,"GET","/api/v1",null,"","Accept","text/html").statusCode());
            assertEquals(415,send(server,"POST",route(r,"summary"),"application/json",input,"Content-Encoding","gzip").statusCode());
            assertEquals(415,send(server,"POST",route(r,"summary"),"text/plain",input).statusCode());
            assertEquals(400,send(server,"GET","/api/v1?unmodeled=value",null,"").statusCode());
            assertEquals(400,send(server,"GET","/api/v1",null,"{}").statusCode());
            var method=send(server,"GET",route(r,"summary"),null,"");
            assertEquals(405,method.statusCode()); assertEquals("POST",method.headers().firstValue("Allow").orElseThrow());
            var head=send(server,"HEAD","/api/v1",null,""); assertEquals(405,head.statusCode()); assertTrue(head.body().isEmpty());
            // A raw socket permits a forged Host control without changing JVM-global HttpClient restrictions.
            try(var socket=new Socket("127.0.0.1",server.uri().getPort())) {
                socket.setSoTimeout(2000);
                socket.getOutputStream().write("GET /api/v1 HTTP/1.1\r\nHost: attacker.test\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertTrue(new String(socket.getInputStream().readNBytes(32),StandardCharsets.US_ASCII).startsWith("HTTP/1.1 403"));
            }
        }
    }
    @Test void actualHttpRequestLimitAndServerCloseAreEnforced() throws Exception {
        var f=new QueryFixture().node("a"); var api=new ArchitectureQueryApi(f.store(),new PublishedReports(),new ApiLimits(1024,4096,32,1000,1,5000));
        URI address;
        try(var server=new ArchitectureHttpServer(api,0)) {
            server.start(); address=server.uri();
            assertEquals(413,send(server,"POST",route(f.request(),"summary"),"application/json"," ".repeat(1025)).statusCode());
        }
        assertThrows(java.io.IOException.class,() -> client.send(HttpRequest.newBuilder(address.resolve("/api/v1")).timeout(Duration.ofSeconds(2)).build(),HttpResponse.BodyHandlers.discarding()));
    }
    @Test void stalledRequestBodyIsClosedAtTheOperationalDeadline() throws Exception {
        var api=new ArchitectureQueryApi(new QueryFixture().store(),new PublishedReports(),new ApiLimits(1024,4096,32,1000,1,100));
        try(var server=new ArchitectureHttpServer(api,0)) {
            server.start();
            try(var socket=new Socket("127.0.0.1",server.uri().getPort())) {
                socket.setSoTimeout(2000);
                String head="POST /api/v1/graphs/"+com.evolution.analysis.contract.common.ContentDigest.sha256Utf8("anything").value()+"/queries/summary HTTP/1.1\r\nHost: 127.0.0.1:"+server.uri().getPort()+"\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n";
                socket.getOutputStream().write(head.getBytes(StandardCharsets.US_ASCII));
                assertEquals(-1,socket.getInputStream().read());
            }
            // The stopped body reader released its request slot.
            assertEquals(200,send(server,"GET","/api/v1",null,"").statusCode());
        }
    }
    @Test void staticWebAssetsAreServedForNonApiRoutes() throws Exception {
        var api=new ArchitectureQueryApi(new QueryFixture().store());
        try(var server=new ArchitectureHttpServer(api,0)) {
            server.start();
            var index=send(server,"GET","/",null,"");
            assertEquals(200,index.statusCode());
            assertEquals("text/html; charset=utf-8",index.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(index.body().contains("Evo Architecture Workbench"));
            var csp=index.headers().firstValue("Content-Security-Policy").orElseThrow();
            assertTrue(csp.contains("script-src 'self'")); assertTrue(csp.contains("connect-src 'self'"));
            assertFalse(csp.contains("https:")); assertFalse(index.body().contains("onclick="));
            for (var asset : List.of("/styles.css","/app.js","/model.js","/vendor/cytoscape-3.30.2.min.js")) {
                assertEquals(200,send(server,"GET",asset,null,"").statusCode(),asset);
                var head=send(server,"HEAD",asset,null,""); assertEquals(200,head.statusCode()); assertTrue(head.body().isEmpty());
            }

            var logo=send(server,"GET","/logo.svg",null,"");
            assertEquals(200,logo.statusCode());
            assertEquals("image/svg+xml",logo.headers().firstValue("Content-Type").orElseThrow());

            var missing=send(server,"GET","/non-existent-file.xyz",null,"");
            assertEquals(404,missing.statusCode());

            var postRoot=send(server,"POST","/","text/plain","hello");
            assertEquals(405,postRoot.statusCode());
            assertEquals("GET, HEAD",postRoot.headers().firstValue("Allow").orElseThrow());
        }
    }
}
