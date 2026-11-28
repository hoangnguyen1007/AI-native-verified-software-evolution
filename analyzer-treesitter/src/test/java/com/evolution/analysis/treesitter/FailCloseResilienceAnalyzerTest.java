package com.evolution.analysis.treesitter;

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
        GoErrorPropagationSummarizer.clearCache();
    }

    @Test
    void goErrPropagationWrappingAndSuppressionAreClassifiedAndCached() {
        String goSrc = """
                package checkout

                import (
                    "context"
                    "fmt"
                    "log"
                    "github.com/pkg/errors"
                )

                func DirectReturn(ctx context.Context, client PaymentClient) (string, error) {
                    res, err := client.Charge(ctx)
                    if err != nil {
                        return "", err
                    }
                    return res, nil
                }

                func WrappedFmtErrorf(ctx context.Context, client LedgerClient) (string, error) {
                    res, err := client.Post(ctx)
                    if err != nil {
                        return "", fmt.Errorf("ledger failed: %w", err)
                    }
                    return res, nil
                }

                func WrappedErrorsWrap(ctx context.Context, client TaxClient) (string, error) {
                    res, err := client.Compute(ctx)
                    if err != nil {
                        return "", errors.Wrap(err, "tax error")
                    }
                    return res, nil
                }

                func SuppressedDefault(ctx context.Context, client PromoClient) (string, error) {
                    res, err := client.FetchBanner(ctx)
                    if err != nil {
                        log.Printf("promo failed: %v", err)
                        return "default-banner", nil
                    }
                    return res, nil
                }

                func IgnoredError(ctx context.Context, client AnalyticsClient) string {
                    res, _ := client.Track(ctx)
                    return res
                }
                """;

        List<MethodErrorSummary> summaries = GoErrorPropagationSummarizer.summarizeErrorPropagation(
                "services/checkout/handler.go", goSrc);
        assertEquals(5, summaries.size(), () -> "Expected 5 Go summaries; got " + summaries);
        assertEquals(1, GoErrorPropagationSummarizer.cacheSize());
        assertEquals(summaries, GoErrorPropagationSummarizer.summarizeErrorPropagation(
                "services/checkout/handler.go", goSrc));

        MethodErrorSummary direct = findByTarget(summaries, "client.Charge");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, direct.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, direct.localVerdict());
        assertTrue(direct.counterfactualOkPathExists());
        assertEquals(TrustTier.SOURCE_SYNTAX, direct.trustTier());

        MethodErrorSummary fmtWrap = findByTarget(summaries, "client.Post");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, fmtWrap.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, fmtWrap.localVerdict());
        assertTrue(fmtWrap.transformations().stream()
                .anyMatch(t -> t.targetException().equals("fmt.Errorf") && t.mechanism().equals("GO_ERR_WRAP")));

        MethodErrorSummary errWrap = findByTarget(summaries, "client.Compute");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, errWrap.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, errWrap.localVerdict());
        assertTrue(errWrap.transformations().stream()
                .anyMatch(t -> t.targetException().equals("errors.Wrap") && t.mechanism().equals("GO_ERR_WRAP")));

        MethodErrorSummary suppressed = findByTarget(summaries, "client.FetchBanner");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, suppressed.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, suppressed.localVerdict());

        MethodErrorSummary ignored = findByTarget(summaries, "client.Track");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, ignored.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, ignored.localVerdict());
    }

    @Test
    void goD22FallbackLastFailureAttributionVsConjunctionBothFailClose() {
        String goSrc = """
                package router

                import "context"

                func WithFallback(ctx context.Context, primary PrimaryClient, fallback FallbackClient) (string, error) {
                    val, err := primary.Lookup(ctx)
                    if err != nil {
                        var err2 error
                        val, err2 = fallback.Lookup(ctx)
                        if err2 != nil {
                            return "", err2
                        }
                    }
                    return val, nil
                }

                func WithConjunction(ctx context.Context, clientA ServiceAClient, clientB ServiceBClient) (string, error) {
                    val1, err1 := clientA.Query(ctx)
                    val2, err2 := clientB.Query(ctx)
                    if err1 != nil && err2 != nil {
                        return "", err1
                    }
                    if err1 == nil {
                        return val1, nil
                    }
                    return val2, nil
                }
                """;

        List<MethodErrorSummary> summaries = GoErrorPropagationSummarizer.summarizeErrorPropagation(
                "services/router/router.go", goSrc);

        // D2.2 Fallback block: primary -> FAIL_OPEN (lastFailurePrimarySuppressed=true), fallback -> FAIL_CLOSE!
        MethodErrorSummary primary = findByTarget(summaries, "primary.Lookup");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, primary.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, primary.localVerdict());
        assertTrue(primary.lastFailurePrimarySuppressed());
        assertTrue(primary.fallbackMechanism().orElse("").contains("GO_FALLBACK_BLOCK:fallback.Lookup"));

        MethodErrorSummary fallback = findByTarget(summaries, "fallback.Lookup");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, fallback.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, fallback.localVerdict());
        assertFalse(fallback.lastFailurePrimarySuppressed());

        // D2.2 Conjunction exception (`err1 != nil && err2 != nil`): BOTH marked FAIL_CLOSE!
        MethodErrorSummary clientA = findByTarget(summaries, "clientA.Query");
        MethodErrorSummary clientB = findByTarget(summaries, "clientB.Query");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, clientA.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, clientA.localVerdict());
        assertTrue(clientA.conjunctionFailureGroup());

        assertEquals(PropagationDisposition.PROPAGATES_ERROR, clientB.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, clientB.localVerdict());
        assertTrue(clientB.conjunctionFailureGroup());
    }

    @Test
    void goErrgroupWaitPropagationSuppressionOpaqueAsyncAndCounterfactualD13() {
        String goSrc = """
                package fanout

                import (
                    "context"
                    "errors"
                    "golang.org/x/sync/errgroup"
                )

                func ErrgroupPropagates(ctx context.Context, svcA ClientA, svcB ClientB) error {
                    g, gctx := errgroup.WithContext(ctx)
                    g.Go(func() error {
                        _, err := svcA.Fetch(gctx)
                        return err
                    })
                    g.Go(func() error {
                        _, err := svcB.Fetch(gctx)
                        if err != nil {
                            return nil
                        }
                        return nil
                    })
                    if err := g.Wait(); err != nil {
                        return err
                    }
                    return nil
                }

                func ErrgroupSuppressed(ctx context.Context, svcC ClientC) error {
                    g, gctx := errgroup.WithContext(ctx)
                    g.Go(func() error {
                        _, err := svcC.Fetch(gctx)
                        return err
                    })
                    _ = g.Wait()
                    return nil
                }

                func ErrgroupOpaque(ctx context.Context, externalTask func() error) error {
                    g, _ := errgroup.WithContext(ctx)
                    g.Go(externalTask)
                    return g.Wait()
                }

                func AlwaysFailsNoOkPath(ctx context.Context, client BrokenClient) (string, error) {
                    _, err := client.Call(ctx)
                    if err != nil {
                        return "", err
                    }
                    return "", errors.New("always fails")
                }
                """;

        List<MethodErrorSummary> summaries = GoErrorPropagationSummarizer.summarizeErrorPropagation(
                "services/fanout/fanout.go", goSrc);

        MethodErrorSummary svcA = findByTarget(summaries, "svcA.Fetch");
        assertEquals(PropagationDisposition.PROPAGATES_ERROR, svcA.disposition());
        assertEquals(ResilienceVerdict.FAIL_CLOSE, svcA.localVerdict());
        assertTrue(svcA.transformations().stream().anyMatch(t -> t.targetException().equals("errgroup.Wait")));

        MethodErrorSummary svcB = findByTarget(summaries, "svcB.Fetch");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, svcB.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, svcB.localVerdict());

        MethodErrorSummary svcC = findByTarget(summaries, "svcC.Fetch");
        assertEquals(PropagationDisposition.SUPPRESSED_FAIL_OPEN, svcC.disposition());
        assertEquals(ResilienceVerdict.FAIL_OPEN, svcC.localVerdict());

        MethodErrorSummary opaque = findByTarget(summaries, "externalTask");
        assertEquals(PropagationDisposition.UNKNOWN_OPAQUE, opaque.disposition());
        assertEquals(ResilienceVerdict.UNKNOWN, opaque.localVerdict());
        assertTrue(opaque.opaqueHandler());
        assertEquals(Optional.of(FailCloseResilienceAnalyzer.GAP_REASON_UNKNOWN_ASYNC), opaque.opaqueReason());

        MethodErrorSummary alwaysFails = findByTarget(summaries, "client.Call");
        assertFalse(alwaysFails.counterfactualOkPathExists());
        assertEquals(ResilienceVerdict.FAIL_OPEN, alwaysFails.localVerdict());
    }

    private static MethodErrorSummary findByTarget(List<MethodErrorSummary> list, String target) {
        return list.stream()
                .filter(s -> s.targetCall().equals(target))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing summary for target " + target + "; got " + list));
    }
}
