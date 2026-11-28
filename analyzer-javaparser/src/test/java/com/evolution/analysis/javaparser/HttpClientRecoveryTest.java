package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.analysis.*;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HttpClientRecoveryTest {
    @org.junit.jupiter.api.io.TempDir Path compiled;
    private static final class ProductSdk {
        static final PlatformInput INPUT=new com.evolution.analysis.filesystem.FilesystemJdkPlatformProvider().acquire(Path.of(System.getProperty("java.home")),
                new com.evolution.analysis.input.PlatformSymbolRequest(21,128,128_000_000,512_000_000)).platform().orElseThrow();
    }
    private FrontendResult analyze(String source)throws Exception {
        var base=ComponentIngestionTest.fixture(Map.of("fixture/C.java",source),false).request();var binaries=new ArrayList<>(base.dependencies());
        for(var entry:new TreeMap<>(Map.<String,Class<?>>of("org.springframework:spring-webflux:6.2.0",org.springframework.web.reactive.function.client.WebClient.class,
                "io.projectreactor:reactor-core:3.7.0",reactor.core.publisher.Mono.class,"org.reactivestreams:reactive-streams:1.0.4",org.reactivestreams.Publisher.class,
                "org.springframework.cloud:spring-cloud-commons:4.2.0",org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker.class)).entrySet()) {
            var jar=Path.of(entry.getValue().getProtectionDomain().getCodeSource().getLocation().toURI());
            binaries.add(new BinaryInput(new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,entry.getKey()+"@jar",ContentDigest.sha256(Files.readAllBytes(jar))),jar));
        }
        Path file=compiled.resolve("C.java");Files.writeString(file,source);
        var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(diagnostics,null,null)) {
            assertTrue(compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",compiled.toString()),null,manager.getJavaFileObjects(file)).call(),()->diagnostics.getDiagnostics().toString());
        }
        return new JavaParserFrontend().analyze(LombokIngestionTest.recovery(TestInputs.request(base.sources(),ProductSdk.INPUT,binaries)));
    }
    @Test void finalStringConcatenationsReachHttpCandidatesWithoutTreatingMutableOrShadowedValuesAsConstants()throws Exception {
        var result=analyze("""
            class C {
              static final String HOST="http://customers-service/";
              final String owners=HOST+"owners";
              private String privateConst="pets";
              String mutable="/wrong";
              void run(org.springframework.web.client.RestTemplate client,String owners) {
                final String suffix="/{id}";
                String effectivelyFinal="/summary";
                client.getForObject(this.owners+suffix,String.class,1);
                client.getForObject(owners,String.class);
                client.getForObject(mutable,String.class);
                client.getForObject("/literal",String.class);
                client.getForObject(String.format("http://visits-service/%s/%d%s",privateConst,7,effectivelyFinal),String.class);
              }
            }
            """);
        assertEquals(5,result.httpClientCalls().size());
        assertEquals(Set.of("http://customers-service/owners/{id}","/literal","http://visits-service/pets/7/summary",""),result.httpClientCalls().stream().map(HttpClientCallRecord::uri).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,result.httpClientCalls().stream().filter(c->c.uri().isEmpty()).count());
    }
    @Test void springCloudDiscoveryClientLoadBalancerClientAndRestClientEmitHostAndUriCandidates()throws Exception {
        var result=analyze("""
            class C {
              void run(org.springframework.web.client.RestClient restClient,
                       org.springframework.cloud.client.discovery.DiscoveryClient discovery,
                       org.springframework.cloud.client.loadbalancer.LoadBalancerClient lb) {
                restClient.get().uri("http://owners-service/owners");
                discovery.getInstances("vets-service");
                lb.choose("visits-service");
              }
            }
            """);
        assertEquals(3,result.httpClientCalls().size());
        assertEquals(Set.of("http://owners-service/owners","lb://vets-service/","lb://visits-service/"),
                result.httpClientCalls().stream().map(HttpClientCallRecord::uri).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.httpClientCalls().stream().anyMatch(c->c.uri().equals("http://owners-service/owners")&&c.methods().equals(List.of("GET"))));
    }
    @Test void webClientUriCallsKeepTheirResolvedGetAndPostMethods()throws Exception {
        var result=analyze("""
            class C { void run(org.springframework.web.reactive.function.client.WebClient client) {
              client.get().uri("http://customers-service/owners");
              client.post().uri("http://customers-service/owners");
            } }
            """);
        assertEquals(2,result.httpClientCalls().size());
        assertEquals(Set.of(List.of("GET"),List.of("POST")),result.httpClientCalls().stream().map(HttpClientCallRecord::methods).collect(java.util.stream.Collectors.toSet()));
    }
    @Test void constantAliasesAreMemoizedAndCyclesRemainUnknown()throws Exception {
        var source=new StringBuilder("class C { static final String s0=\"\"; ");
        for(int i=1;i<=40;i++)source.append("static final String s").append(i).append("=s").append(i-1).append("+s").append(i-1).append("; ");
        source.append("void run(org.springframework.web.client.RestTemplate client){client.getForObject(\"/memo\"+s40,String.class);} }");
        assertEquals("/memo",analyze(source.toString()).httpClientCalls().getFirst().uri());
        var cycle=analyze("class C { static final String A=C.B; static final String B=C.A; void run(org.springframework.web.client.RestTemplate c){c.getForObject(A,String.class);} }");
        assertEquals(1,cycle.httpClientCalls().size());assertEquals("",cycle.httpClientCalls().getFirst().uri());
    }
    @Test void providerInferenceCollisionsRetainTypedUnsupportedInsteadOfAdapterErrors()throws Exception {
        var result=analyze("""
            import reactor.core.publisher.Mono; import java.util.*; import java.util.function.*;
            record Owner(List<Integer> ids) {}
            record Visits(List<Integer> ids) {}
            interface Clients { Mono<Owner> owner(int id); Mono<Visits> visits(List<Integer> ids); }
            class C {
              Function<Visits,Owner> attach(Owner o) { return visits -> o; }
              Mono<Visits> emptyVisits() { return Mono.just(new Visits(List.of())); }
              Mono<Owner> run(Clients c,org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory factory) { return c.owner(1).flatMap(owner -> c.visits(owner.ids())
                .transform(it -> { var cb=factory.create("id"); return cb.run(it,t -> emptyVisits()); }).map(attach(owner))); }
            }
            """);
        var failures=result.observations().stream().filter(o->o.attribution()==com.evolution.analysis.contract.semantic.SemanticStatus.ERROR).toList();
        assertTrue(failures.isEmpty(),()->failures.stream().map(o->o.reference()+":"+o.diagnostics()).toList().toString());
        var collisions=result.observations().stream().filter(o->o.attribution()==com.evolution.analysis.contract.semantic.SemanticStatus.UNSUPPORTED
                &&o.diagnostics().stream().anyMatch(d->d.code().equals("java.parser-inference-collision"))).toList();
        assertEquals(4,collisions.size());
        assertTrue(collisions.stream().allMatch(o -> o.category().value().equals("java.calls")));
        var spans=collisions.stream().map(o -> {
            var span=o.span().orElseThrow();
            return List.of(span.startLine(),span.startColumn(),span.endLine(),span.endColumn());
        }).collect(java.util.stream.Collectors.toSet());
        // Each nested call remains separately auditable when the pinned provider conflates its type parameters.
        assertEquals(Set.of(List.of(8,125,9,113),List.of(8,153,9,112),
                List.of(8,153,9,93),List.of(9,60,9,89)),spans);
    }
}
