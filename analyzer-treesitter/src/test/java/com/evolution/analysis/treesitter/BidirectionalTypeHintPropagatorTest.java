package com.evolution.analysis.treesitter;

import static org.junit.jupiter.api.Assertions.*;

import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.treesitter.BidirectionalTypeHintPropagator.Result;
import com.evolution.analysis.treesitter.BidirectionalTypeHintPropagator.SlotKind;
import com.evolution.analysis.treesitter.BidirectionalTypeHintPropagator.SymbolTypeHint;
import com.evolution.analysis.treesitter.BidirectionalTypeHintPropagator.TypeStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BidirectionalTypeHintPropagatorTest {

    private static Map<String, byte[]> utf8Files(Map<String, String> textByPath) {
        var out = new LinkedHashMap<String, byte[]>();
        for (var e : textByPath.entrySet()) {
            out.put(e.getKey(), e.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return out;
    }

    @Test
    void conservativeSaltCombinationEquationFollowsExactFormula() {
        // B_w == {t} && F == {Unknown} -> B_s U {t}
        assertEquals(
                Set.of("Order"),
                BidirectionalTypeHintPropagator.combineSaltTypes(
                        Set.of("Unknown"), Set.of(), Set.of("Order")));
        assertEquals(
                Set.of("Base", "Order"),
                BidirectionalTypeHintPropagator.combineSaltTypes(
                        Set.of("Unknown"), Set.of("Base"), Set.of("Order")));

        // Conflicting B_w (|B_w| > 1) with F == {Unknown} MUST NOT guess -> B_s U {Unknown}
        assertEquals(
                Set.of("Unknown"),
                BidirectionalTypeHintPropagator.combineSaltTypes(
                        Set.of("Unknown"), Set.of(), Set.of("Order", "User")));

        // Concrete F present -> B_s U F (ignores weak B_w)
        assertEquals(
                Set.of("Invoice"),
                BidirectionalTypeHintPropagator.combineSaltTypes(
                        Set.of("Invoice"), Set.of(), Set.of("Order")));

        // Partial unknown in F -> F != {Unknown}, so Unknown is preserved
        assertEquals(
                Set.of("Invoice", "Unknown"),
                BidirectionalTypeHintPropagator.combineSaltTypes(
                        Set.of("Invoice", "Unknown"), Set.of(), Set.of("Order")));
    }

    @Test
    void pythonBidirectionalPropagationCoversForwardStrongGuardsWeakBackwardAndAntiOverNarrowing() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("src/billing/models.py", """
                class Invoice:
                    def validate(self) -> bool:
                        return True

                class CardPayment:
                    pass

                class BankTransfer:
                    pass

                class Account:
                    pass
                """);
        files.put("src/billing/ledger.py", """
                from .models import Invoice, Account

                def charge_invoice(inv: Invoice) -> str:
                    return "charged"

                def notify_account(acc: Account) -> None:
                    pass

                def _private_forward(item):
                    return item.validate()

                def public_api_processor(payload):
                    return payload.validate()

                def _guarded_handler(raw):
                    if isinstance(raw, (CardPayment, BankTransfer)):
                        return "ok"
                    return "other"

                def _weak_backward_singleton(order_arg):
                    return charge_invoice(order_arg)

                def _weak_backward_conflict(ambig_arg):
                    charge_invoice(ambig_arg)
                    notify_account(ambig_arg)

                def _untyped_orphan(mystery):
                    return mystery
                """);
        files.put("src/billing/app.py", """
                from .models import Invoice
                from .ledger import _private_forward, public_api_processor

                def run_job():
                    inv = Invoice()
                    _private_forward(inv)
                    public_api_processor(inv)
                """);

        var frontend = new TreeSitterSourceFrontend();
        Result result = frontend.propagateTypeHints(utf8Files(files), () -> false);

        // 1. Forward F(s) into private helper + return propagation + recovered receiver edge
        SymbolTypeHint privParam = result.findHint(
                "src/billing/ledger.py", "_private_forward", SlotKind.PARAMETER, "item").orElseThrow();
        assertTrue(privParam.forwardTypes().contains("Invoice"), () -> "Expected Invoice in F: " + privParam);
        assertEquals(Set.of("Invoice"), privParam.inferredTypes());
        assertFalse(privParam.exportedPublicApi());
        assertFalse(privParam.overNarrowingGuarded());
        assertEquals(TypeStatus.INFERRED_LOCAL_HINT, privParam.status());
        assertEquals(TrustTier.SOURCE_SYNTAX, privParam.trustTier());
        assertEquals(BidirectionalTypeHintPropagator.PROVENANCE_SOURCE_SYNTAX_HINT, privParam.provenance());

        SymbolTypeHint privRet = result.findHint(
                "src/billing/ledger.py", "_private_forward", SlotKind.RETURN, "return").orElseThrow();
        assertEquals(Set.of("bool"), privRet.inferredTypes(), () -> "Expected return bool via item.validate(): " + privRet);

        // Verify broken receiver call edge (item.validate -> Invoice.validate) recovered via bidirectional hints
        assertTrue(result.recoveredCallEdges().stream().anyMatch(e ->
                        e.sourcePath().equals("src/billing/ledger.py")
                                && e.callerSymbol().equals("_private_forward")
                                && e.receiverTypeHint().equals("Invoice")
                                && e.targetPath().equals("src/billing/models.py")
                                && e.targetSymbol().equals("Invoice.validate")
                                && e.trustTier() == TrustTier.SOURCE_SYNTAX
                                && e.provenance().equals(BidirectionalTypeHintPropagator.PROVENANCE_SOURCE_SYNTAX_HINT)),
                () -> "Expected recovered Invoice.validate call edge: " + result.recoveredCallEdges());

        // 2. Anti-Over-Narrowing Guard on exported Python public API (not starting with '_')
        SymbolTypeHint pubParam = result.findHint(
                "src/billing/ledger.py", "public_api_processor", SlotKind.PARAMETER, "payload").orElseThrow();
        assertTrue(pubParam.exportedPublicApi(), "Python function without leading '_' must be public API");
        assertTrue(pubParam.overNarrowingGuarded(), "Public API inferred from local caller subset must be over-narrowing guarded");
        assertTrue(pubParam.openWorldExtensible(), "Public API must preserve open/extensible provenance");
        assertEquals(TypeStatus.INFERRED_OPEN_HINT, pubParam.status());
        assertNotEquals(TrustTier.COMPILER, pubParam.trustTier(), "NEVER lock public API to COMPILER tier from local callers (I4)");
        assertEquals(TrustTier.SOURCE_SYNTAX, pubParam.trustTier());
        assertEquals(BidirectionalTypeHintPropagator.PROVENANCE_SOURCE_SYNTAX_HINT, pubParam.provenance());
        assertTrue(pubParam.inferredTypes().contains("Invoice"));

        // 3. Strong intra-body guard B_s(s) via isinstance(raw, (CardPayment, BankTransfer))
        SymbolTypeHint guardedParam = result.findHint(
                "src/billing/ledger.py", "_guarded_handler", SlotKind.PARAMETER, "raw").orElseThrow();
        assertEquals(Set.of("BankTransfer", "CardPayment"), guardedParam.strongBackwardTypes());
        assertTrue(guardedParam.inferredTypes().containsAll(Set.of("CardPayment", "BankTransfer")));

        // 4. Weak backward B_w(s) singleton rescues Unknown F(s) via exact SALT equation
        SymbolTypeHint bwSingle = result.findHint(
                "src/billing/ledger.py", "_weak_backward_singleton", SlotKind.PARAMETER, "order_arg").orElseThrow();
        assertEquals(Set.of("Unknown"), bwSingle.forwardTypes());
        assertEquals(Set.of("Invoice"), bwSingle.weakBackwardTypes());
        assertEquals(Set.of("Invoice"), bwSingle.inferredTypes(), "Singleton B_w with F={Unknown} must yield {Invoice}");
        assertFalse(bwSingle.hasUnknown());

        // 5. Conflicting weak backward B_w(s) ({Invoice, Account}) conservatively preserves Unknown + emits gap
        SymbolTypeHint bwConflict = result.findHint(
                "src/billing/ledger.py", "_weak_backward_conflict", SlotKind.PARAMETER, "ambig_arg").orElseThrow();
        assertEquals(Set.of("Account", "Invoice"), bwConflict.weakBackwardTypes());
        assertTrue(bwConflict.hasUnknown(), "Conflicting B_w must keep Unknown");

        // 6. Untyped orphan emits explicit CapabilityGapRecord("BIDIRECTIONAL_TYPE_UNKNOWN") (I3)
        SymbolTypeHint orphan = result.findHint(
                "src/billing/ledger.py", "_untyped_orphan", SlotKind.PARAMETER, "mystery").orElseThrow();
        assertTrue(orphan.hasUnknown());
        assertTrue(result.capabilityGaps().stream().anyMatch(g ->
                        BidirectionalTypeHintPropagator.GAP_REASON_UNKNOWN.equals(g.reasonCode())
                                && g.limitations().stream().anyMatch(l -> l.contains(orphan.symbolId()))),
                () -> "Missing BIDIRECTIONAL_TYPE_UNKNOWN gap for orphan: " + result.capabilityGaps());
        assertTrue(result.capabilityGaps().stream().allMatch(g ->
                BidirectionalTypeHintPropagator.GAP_REASON_UNKNOWN.equals(g.reasonCode())));
    }

    @Test
    void typescriptBidirectionalPropagationHandlesTypeofInstanceofPublicExportGuardAndDeterminism() throws Exception {
        var files = new LinkedHashMap<String, String>();
        files.put("src/types.ts", """
                export class OrderRecord {
                  summary(): string {
                    return "order";
                  }
                }
                export class CustomerRecord {}
                """);
        files.put("src/service.ts", """
                import { OrderRecord, CustomerRecord } from './types';

                export function persistOrder(order: OrderRecord): number {
                  return 1;
                }

                export function auditCustomer(cust: CustomerRecord): void {}

                function internalRunner(item) {
                  return item.summary();
                }

                export function publicEndpoint(input) {
                  if (typeof input === 'string') {
                    return input;
                  }
                  return "fallback";
                }

                export function publicCallerOnly(arg) {
                  return arg.summary();
                }

                function weakSingleton(x) {
                  return persistOrder(x);
                }

                function guardInstanceof(target) {
                  if (target instanceof OrderRecord) {
                    return target.summary();
                  }
                  return "none";
                }

                function weakConflict(y) {
                  persistOrder(y);
                  auditCustomer(y);
                }

                export function bootstrap(): void {
                  const rec = new OrderRecord();
                  internalRunner(rec);
                  publicCallerOnly(rec);
                }
                """);

        var frontend = new TreeSitterSourceFrontend();
        Result first = frontend.propagateTypeHints(utf8Files(files), () -> false);

        // Forward F(s) into private TS helper + return propagation
        SymbolTypeHint internalParam = first.findHint(
                "src/service.ts", "internalRunner", SlotKind.PARAMETER, "item").orElseThrow();
        assertEquals(Set.of("OrderRecord"), internalParam.inferredTypes());
        assertFalse(internalParam.exportedPublicApi());
        assertFalse(internalParam.overNarrowingGuarded());
        assertEquals(TrustTier.SOURCE_SYNTAX, internalParam.trustTier());

        SymbolTypeHint internalRet = first.findHint(
                "src/service.ts", "internalRunner", SlotKind.RETURN, "return").orElseThrow();
        assertEquals(Set.of("string"), internalRet.inferredTypes());

        // Strong guard typeof input === 'string' -> B_s contains string; exported function guarded against over-narrowing
        SymbolTypeHint typeofParam = first.findHint(
                "src/service.ts", "publicEndpoint", SlotKind.PARAMETER, "input").orElseThrow();
        assertEquals(Set.of("string"), typeofParam.strongBackwardTypes());
        assertTrue(typeofParam.exportedPublicApi());
        assertTrue(typeofParam.overNarrowingGuarded());
        assertEquals(TrustTier.SOURCE_SYNTAX, typeofParam.trustTier());
        assertEquals(BidirectionalTypeHintPropagator.PROVENANCE_SOURCE_SYNTAX_HINT, typeofParam.provenance());

        // Exported function called locally with OrderRecord -> NEVER COMPILER tier, overNarrowingGuarded=true
        SymbolTypeHint pubCallerParam = first.findHint(
                "src/service.ts", "publicCallerOnly", SlotKind.PARAMETER, "arg").orElseThrow();
        assertTrue(pubCallerParam.exportedPublicApi());
        assertTrue(pubCallerParam.overNarrowingGuarded());
        assertTrue(pubCallerParam.openWorldExtensible());
        assertEquals(TypeStatus.INFERRED_OPEN_HINT, pubCallerParam.status());
        assertNotEquals(TrustTier.COMPILER, pubCallerParam.trustTier());
        assertEquals(Set.of("OrderRecord"), pubCallerParam.inferredTypes());

        // instanceof strong guard B_s
        SymbolTypeHint instParam = first.findHint(
                "src/service.ts", "guardInstanceof", SlotKind.PARAMETER, "target").orElseThrow();
        assertEquals(Set.of("OrderRecord"), instParam.strongBackwardTypes());

        // Weak backward singleton B_w rescues Unknown F
        SymbolTypeHint weakSingle = first.findHint(
                "src/service.ts", "weakSingleton", SlotKind.PARAMETER, "x").orElseThrow();
        assertEquals(Set.of("OrderRecord"), weakSingle.weakBackwardTypes());
        assertEquals(Set.of("OrderRecord"), weakSingle.inferredTypes());
        assertFalse(weakSingle.hasUnknown());

        // Conflicting weak backward emits BIDIRECTIONAL_TYPE_UNKNOWN gap
        SymbolTypeHint weakConf = first.findHint(
                "src/service.ts", "weakConflict", SlotKind.PARAMETER, "y").orElseThrow();
        assertEquals(Set.of("CustomerRecord", "OrderRecord"), weakConf.weakBackwardTypes());
        assertTrue(weakConf.hasUnknown());
        assertTrue(first.capabilityGaps().stream().anyMatch(g ->
                BidirectionalTypeHintPropagator.GAP_REASON_UNKNOWN.equals(g.reasonCode())
                        && g.limitations().stream().anyMatch(l -> l.contains(weakConf.symbolId()))));

        // I2 SHA-256 replay determinism regardless of file insertion order
        var reversed = new LinkedHashMap<String, byte[]>();
        var keys = new ArrayList<>(files.keySet());
        Collections.reverse(keys);
        for (String k : keys) reversed.put(k, files.get(k).getBytes(StandardCharsets.UTF_8));
        Result second = frontend.propagateTypeHints(reversed, () -> false);
        assertEquals(first.canonicalDigest(), second.canonicalDigest(), "Canonical SHA-256 digest must be insertion-order invariant (I2)");
        assertEquals(first.hints(), second.hints());
        assertEquals(first.recoveredCallEdges(), second.recoveredCallEdges());
        assertEquals(first.capabilityGaps(), second.capabilityGaps());
    }
}
