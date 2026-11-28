package com.evolution.analysis.javaparser.slice;

import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.MethodErrorSummary;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.PropagationDisposition;
import com.evolution.analysis.polyglot.FailCloseResilienceAnalyzer.ResilienceVerdict;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class FailCloseResilienceAnalyzerTest {

    @BeforeEach
    void clearCache() {
        JavaMethodSlicer.clearErrorSummaryCache();
    }

    @Test
    void javaTryCatchSuppressionWrapRethrowAndHttp5xxAreClassifiedAccurately() {
        String source = """
                package com.acme.gateway;
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.client.RestTemplate;
                import org.springframework.web.client.RestClientException;
                import org.springframework.cloud.openfeign.FeignClient;
                import java.util.Collections;
                import java.util.List;

                public class GatewayService {
                    private RestTemplate restTemplate;
                    private CatalogClient catalogClient;

                    public List<String> getSuppressedFallback(String id) {
                        try {
                            return catalogClient.fetchItems(id);
                        } catch (Exception e) {
                            return Collections.emptyList();
                        }
                    }

                    public String getWrappedRethrow(String id) {
                        try {
                            return restTemplate.getForObject("/api/price/" + id, String.class);
                        } catch (RestClientException e) {
                            throw new PricingUnavailableException("upstream failed", e);
                        }
                    }

                    public ResponseEntity<String> getHttp5xxReturn(String id) {
                        try {
                            String body = restTemplate.getForObject("/api/tax/" + id, String.class);
                            return ResponseEntity.ok(body);
                        } catch (Exception e) {
                            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("upstream error");
                        }
                    }

                    public String getUncaughtGrpc(InventoryBlockingStub inventoryStub, String sku) {
                        return inventoryStub.checkStock(sku);
                    }

                    public String getRootRpcCatchSuppressed(String id) {
                        try {
                            return restTemplate.getForObject("/api/opt/" + id, String.class);
                        } catch (RestClientException e) {
                            return "default-opt";
                        }
                    }

                    public String getPartialSubExceptionCatch(String id) {
                        try {
                            return restTemplate.getForObject("/api/partial/" + id, String.class);
                        } catch (HttpStatusCodeException e) {
                            return "handled-http-status-only";
                        }
                    }
                }
                """;

        List<MethodErrorSummary> summaries = JavaMethodSlicer.summarizeErrorPropagation(
                "src/main/java/com/acme/gateway/GatewayService.java", source);

        assertEquals(6, summaries.size(), () -> "Expected 6 outbound summaries; got " + summaries);
        assertEquals(1, JavaMethodSlicer.errorSummaryCacheSize());

        // Cached replay yields bit-identical summaries
        List<MethodErrorSummary> cached = JavaMethodSlicer.summarizeErrorPropagation(
                "src/main/java/com/acme/gateway/GatewayService.java", source);
        assertEquals(summaries, cached);

        MethodErrorSummary suppressed = findByMethod(summaries, "getSuppressedFallback");
        assertEquals("FEIGN", suppressed.callProtocol());
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, suppressed.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, suppressed.localVerdict());
        assertTrue(suppressed.counterfactualOkPathExists());
        assertTrue(suppressed.propagatedExceptions().isEmpty());
        assertEquals(TrustTier.SOURCE_SYNTAX, suppressed.trustTier());

        MethodErrorSummary wrapped = findByMethod(summaries, "getWrappedRethrow");
        assertEquals("REST_TEMPLATE", wrapped.callProtocol());
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, wrapped.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, wrapped.localVerdict());
        assertTrue(wrapped.propagatedExceptions().contains("PricingUnavailableException"));
        assertTrue(wrapped.transformations().stream()
                .anyMatch(t -> t.targetException().equals("PricingUnavailableException")
                        && t.mechanism().equals("CATCH_WRAP_RETHROW")));

        MethodErrorSummary http5xx = findByMethod(summaries, "getHttp5xxReturn");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, http5xx.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, http5xx.localVerdict());
        assertTrue(http5xx.propagatedExceptions().contains("HTTP_5XX"));
        assertTrue(http5xx.transformations().stream()
                .anyMatch(t -> t.targetException().equals("HTTP_5XX")
                        && t.mechanism().equals("CATCH_HTTP_5XX_RETURN")));

        MethodErrorSummary grpc = findByMethod(summaries, "getUncaughtGrpc");
        assertEquals("GRPC_STUB", grpc.callProtocol());
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, grpc.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, grpc.localVerdict());
        assertTrue(grpc.propagatedExceptions().contains("StatusRuntimeException"));

        MethodErrorSummary rootCatch = findByMethod(summaries, "getRootRpcCatchSuppressed");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, rootCatch.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, rootCatch.localVerdict());
        assertTrue(rootCatch.propagatedExceptions().isEmpty());

        MethodErrorSummary partialCatch = findByMethod(summaries, "getPartialSubExceptionCatch");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, partialCatch.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, partialCatch.localVerdict());
        assertTrue(partialCatch.propagatedExceptions().contains("ResourceAccessException"));
    }

    @Test
    void javaCompletableFutureCircuitBreakerRetryCounterfactualD13AndOpaqueHandler() {
        String source = """
                package com.acme.resilience;
                import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
                import io.github.resilience4j.retry.annotation.Retry;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.reactive.function.client.WebClient;
                import java.util.concurrent.CompletableFuture;

                public class AsyncResilienceService {
                    private WebClient webClient;
                    private PriceClient priceClient;
                    private ExternalErrorHandler externalHandler;

                    public CompletableFuture<String> asyncFailOpen(String id) {
                        return priceClient.fetchAsync(id).exceptionally(ex -> "default-price");
                    }

                    public CompletableFuture<String> asyncHandleFailOpen(String id) {
                        return priceClient.fetchAsync(id).handle((res, ex) -> ex != null ? "fallback" : res);
                    }

                    public CompletableFuture<String> asyncOpaqueHandler(String id) {
                        return priceClient.fetchAsync(id).exceptionally(ExternalErrorHandler::recover);
                    }

                    @CircuitBreaker(name = "pricing", fallbackMethod = "defaultQuote")
                    public String circuitBreakerProtected(String id) {
                        return priceClient.getQuote(id);
                    }

                    private String defaultQuote(String id, Throwable t) {
                        return "0.00";
                    }

                    @Retry(name = "shipping", fallbackMethod = "defaultShipping")
                    public String retryProtected(String id) {
                        return priceClient.getShipping(id);
                    }

                    private String defaultShipping(String id, Throwable t) {
                        return "standard";
                    }

                    public String webClientFailOpen(String id) {
                        return webClient.get().uri("/api/items/" + id).retrieve().bodyToMono(String.class)
                                .onErrorReturn("cached").block();
                    }

                    public ResponseEntity<String> counterfactualNoOkPath(String id) {
                        priceClient.getQuote(id);
                        return ResponseEntity.status(503).body("always unavailable");
                    }

                    public String opaqueCatchDelegation(String id) {
                        try {
                            return priceClient.getQuote(id);
                        } catch (Exception e) {
                            return externalHandler.translateOrRecover(e);
                        }
                    }
                }
                """;

        List<MethodErrorSummary> summaries = JavaMethodSlicer.summarizeErrorPropagation(
                "src/main/java/com/acme/resilience/AsyncResilienceService.java", source);

        MethodErrorSummary asyncOpen = findByMethod(summaries, "asyncFailOpen");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, asyncOpen.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, asyncOpen.localVerdict());

        MethodErrorSummary handleOpen = findByMethod(summaries, "asyncHandleFailOpen");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, handleOpen.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, handleOpen.localVerdict());

        MethodErrorSummary asyncOpaque = findByMethod(summaries, "asyncOpaqueHandler");
        assertEquals(PropagationDisposition.UNKNOWN_OPAQUE, asyncOpaque.disposition());
        assertEquals(ResilienceVerdict.UNKNOWN, asyncOpaque.localVerdict());
        assertTrue(asyncOpaque.opaqueHandler());
        assertEquals(Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_UNKNOWN_ASYNC), asyncOpaque.opaqueReason());

        MethodErrorSummary cb = findByMethod(summaries, "circuitBreakerProtected");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, cb.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, cb.localVerdict());
        assertTrue(cb.fallbackMechanism().orElse("").contains("CIRCUIT_BREAKER_FALLBACK:defaultQuote"));

        MethodErrorSummary retry = findByMethod(summaries, "retryProtected");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, retry.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, retry.localVerdict());
        assertTrue(retry.fallbackMechanism().orElse("").contains("RETRY_FALLBACK:defaultShipping"));

        MethodErrorSummary webClient = findByMethod(summaries, "webClientFailOpen");
        assertEquals("WEB_CLIENT", webClient.callProtocol());
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, webClient.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, webClient.localVerdict());

        // Counterfactual D1.3: method ALWAYS returns 503 even when priceClient.getQuote succeeds!
        MethodErrorSummary noOk = findByMethod(summaries, "counterfactualNoOkPath");
        assertFalse(noOk.counterfactualOkPathExists());
        assertEquals(ResilienceVerdict.FAIL_OPEN, noOk.localVerdict());

        // Opaque catch delegation emits UNKNOWN_OPAQUE
        MethodErrorSummary opaqueCatch = findByMethod(summaries, "opaqueCatchDelegation");
        assertEquals(PropagationDisposition.UNKNOWN_OPAQUE, opaqueCatch.disposition());
        assertTrue(opaqueCatch.opaqueHandler());
        assertEquals(ResilienceVerdict.UNKNOWN, opaqueCatch.localVerdict());
    }

    private static MethodErrorSummary findByMethod(List<MethodErrorSummary> list, String methodName) {
        return list.stream()
                .filter(s -> s.methodId().contains("." + methodName + "("))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No summary for method " + methodName + "; got " + list));
    }
}
