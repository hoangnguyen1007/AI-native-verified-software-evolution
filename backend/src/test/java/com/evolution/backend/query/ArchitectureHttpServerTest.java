package com.evolution.backend.query;

import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphManifest;
import com.evolution.analysis.graph.GraphStore;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.graph.GraphJson;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
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
        var f=new QueryFixture().node("a").node("b").edge("a","b"); var r=f.request();
        var verifiedGraphs = f.store();
        var api = new ArchitectureQueryApi(new GraphStore() {
            public void save(KnowledgeGraph graph, BooleanSupplier cancelled) { verifiedGraphs.save(graph, cancelled); }
            public Optional<KnowledgeGraph> load(ContentDigest id) { return verifiedGraphs.load(id); }
            public Optional<GraphManifest> manifest(ContentDigest id) {
                throw new IllegalStateException("Graph discovery does not need a semantic replay digest");
            }
        });
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
            assertEquals(200, graph.statusCode());
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
            assertFalse(index.body().contains("fonts.googleapis.com"));
            assertFalse(index.body().contains("cytoscape-3.30.2.min.js"));
            for (var asset : List.of("/styles.css","/app.js","/model.js","/architecture-chat.js","/architecture-chat.css",
                    "/vendor/cytoscape-3.30.2.min.js")) {
                assertEquals(200,send(server,"GET",asset,null,"").statusCode(),asset);
                var head=send(server,"HEAD",asset,null,""); assertEquals(200,head.statusCode()); assertTrue(head.body().isEmpty());
            }

            var logo=send(server,"GET","/logo.svg",null,"");
            assertEquals(200,logo.statusCode());
            assertEquals("image/svg+xml",logo.headers().firstValue("Content-Type").orElseThrow());

            for (var family : List.of("newsreader", "source-sans-3")) {
                for (var subset : List.of("latin", "vietnamese")) {
                    var font = send(server, "HEAD", "/fonts/" + family + "-" + subset + ".woff2", null, "");
                    assertEquals(200, font.statusCode());
                    assertEquals("font/woff2", font.headers().firstValue("Content-Type").orElseThrow());
                }
            }

            var missing=send(server,"GET","/non-existent-file.xyz",null,"");
            assertEquals(404,missing.statusCode());

            var postRoot=send(server,"POST","/","text/plain","hello");
            assertEquals(405,postRoot.statusCode());
            assertEquals("GET, HEAD",postRoot.headers().firstValue("Allow").orElseThrow());
        }
    }

    @Test void cspAllowsGitHubAvatarsOnlyWhenOAuthOrMultiUserEnabled() throws Exception {
        var store = new QueryFixture().store();
        var transport = (com.evolution.backend.github.GitHubRestClient.Transport) (u, m, h, b, c) ->
                new com.evolution.backend.github.GitHubRestClient.RawResponse(404, Map.of(), new byte[0]);
        try (var sessions = new com.evolution.backend.auth.UserSessionStore()) {
            // Single-operator mode without OAuth client ID keeps strict zero-https: CSP
            var localCfg = new com.evolution.backend.auth.GitHubOAuthService.Config(
                    false, "", "", "github.com", com.evolution.backend.auth.GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.empty(), Optional.empty());
            var localAuth = new com.evolution.backend.auth.AuthHttpApi(
                    new com.evolution.backend.auth.GitHubOAuthService(localCfg, sessions, transport),
                    null, ApiLimits.defaults());
            try (var server = new ArchitectureHttpServer(
                    ArchitectureQueryApi.withAuth(new ArchitectureQueryApi(store), localAuth), 0)) {
                server.start();
                String csp = send(server, "GET", "/", null, "").headers().firstValue("Content-Security-Policy").orElseThrow();
                assertFalse(csp.contains("https:"), "Local offline mode must not allow https: origins in CSP: " + csp);
                assertTrue(csp.contains("img-src 'self' data:;"));
            }

            // Multi-user mode explicitly allows GitHub avatars in img-src while keeping script-src/connect-src strictly 'self'
            var multiCfg = new com.evolution.backend.auth.GitHubOAuthService.Config(
                    true, "", "", "github.com", com.evolution.backend.auth.GitHubOAuthService.DEFAULT_SCOPES,
                    false, Optional.empty(), Optional.empty(), Optional.empty());
            var multiAuth = new com.evolution.backend.auth.AuthHttpApi(
                    new com.evolution.backend.auth.GitHubOAuthService(multiCfg, sessions, transport),
                    null, ApiLimits.defaults());
            try (var server = new ArchitectureHttpServer(
                    ArchitectureQueryApi.withAuth(new ArchitectureQueryApi(store), multiAuth), 0)) {
                server.start();
                String csp = send(server, "GET", "/", null, "").headers().firstValue("Content-Security-Policy").orElseThrow();
                assertTrue(csp.contains("img-src 'self' data: https://avatars.githubusercontent.com;"), csp);
                assertTrue(csp.contains("script-src 'self'"));
                assertTrue(csp.contains("connect-src 'self'"));
            }
        }
    }
    @Test void staticAssetsAndControlRoutesStayResponsiveWhileGraphSlotsAreOccupied() throws Exception {
        var f = new QueryFixture().node("a");
        var slowStore = new GraphStore() {
            final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(2);
            final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            public void save(KnowledgeGraph graph, BooleanSupplier cancelled) { f.store().save(graph, cancelled); }
            public Optional<KnowledgeGraph> load(ContentDigest id) {
                entered.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return f.store().load(id);
            }
        };
        var api = new ArchitectureQueryApi(slowStore, new PublishedReports(), new ApiLimits(65_536, 16_777_216, 32, 100_000, 4, 10_000));
        try (var server = new ArchitectureHttpServer(api, 0)) {
            server.start();
            var r = f.request();
            String input = CanonicalJson.write(Map.of("request", r));
            var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var f1 = pool.submit(() -> send(server, "POST", route(r, "summary"), "application/json", input));
                var f2 = pool.submit(() -> send(server, "POST", route(r, "summary"), "application/json", input));
                assertTrue(slowStore.entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals(200, send(server, "GET", "/app.js", null, "").statusCode());
                assertEquals(200, send(server, "GET", "/api/v1", null, "").statusCode());
                slowStore.release.countDown();
                assertEquals(200, f1.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
                assertEquals(200, f2.get(5, java.util.concurrent.TimeUnit.SECONDS).statusCode());
            } finally {
                slowStore.release.countDown();
                pool.shutdownNow();
            }
        }
    }

    @Test void bindHostTrustProxyHeadersAndWorkbenchLaunchOptionsSupportLocalAndPaasDeploy() {
        assertEquals("127.0.0.1", ArchitectureHttpServer.bindHostFromEnvironment(k -> null, k -> null));
        assertEquals("0.0.0.0", ArchitectureHttpServer.bindHostFromEnvironment(
                Map.of("EVO_BIND_HOST", "0.0.0.0")::get, k -> null));
        assertEquals("127.0.0.1", ArchitectureHttpServer.bindHostFromEnvironment(
                Map.of("EVO_BIND_HOST", "0.0.0.0")::get,
                Map.of("evo.http.bindHost", "127.0.0.1")::get));

        assertFalse(ArchitectureHttpServer.trustProxyHeadersFromEnvironment(k -> null, k -> null));
        assertTrue(ArchitectureHttpServer.trustProxyHeadersFromEnvironment(
                Map.of("EVO_TRUST_PROXY_HEADERS", "true")::get, k -> null));
        assertTrue(ArchitectureHttpServer.trustProxyHeadersFromEnvironment(
                Map.of("EVO_TRUST_PROXY_HEADERS", "1")::get, k -> null));
        assertFalse(ArchitectureHttpServer.trustProxyHeadersFromEnvironment(
                Map.of("EVO_TRUST_PROXY_HEADERS", "true")::get,
                Map.of("evo.http.trustProxyHeaders", "false")::get));

        String[] baseArgs = {"src", "urn:evo:demo:app", "jdk", "21", "m2", "store"};
        var defaults = com.evolution.backend.analysis.WorkbenchApiMain.parseLaunchOptions(baseArgs, k -> null, k -> null);
        assertEquals("127.0.0.1", defaults.bindHost());
        assertEquals(8080, defaults.port());
        assertEquals("urn:evo:demo:app", defaults.coordinate());
        assertEquals(21, defaults.release());

        var paas = com.evolution.backend.analysis.WorkbenchApiMain.parseLaunchOptions(
                baseArgs, Map.of("EVO_BIND_HOST", "0.0.0.0", "PORT", "9091")::get, k -> null);
        assertEquals("0.0.0.0", paas.bindHost());
        assertEquals(9091, paas.port());

        String[] flagArgs = {"--host", "0.0.0.0", "--port=9443", "src", "urn:evo:demo:app", "jdk", "21", "m2", "store"};
        var explicitFlags = com.evolution.backend.analysis.WorkbenchApiMain.parseLaunchOptions(
                flagArgs, Map.of("PORT", "9091")::get, k -> null);
        assertEquals("0.0.0.0", explicitFlags.bindHost());
        assertEquals(9443, explicitFlags.port());
        assertEquals("urn:evo:demo:app", explicitFlags.coordinate());
        assertEquals(21, explicitFlags.release());
    }
}
