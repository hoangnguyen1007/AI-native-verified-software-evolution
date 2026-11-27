package com.evolution.analysis.javaparser.slice;

import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.query.trace.MethodSliceResult;
import com.evolution.analysis.query.trace.MethodSliceResult.SliceDirection;
import com.evolution.analysis.query.trace.MethodSliceResult.SliceStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavaMethodSlicerTest {

    // Line-numbered sample class so assertions check exact 1-based line numbers
    private static final String SAMPLE_JAVA = """
            package com.acme.billing;

            import java.util.List;

            public class InvoiceCalculator {
                private int auditCount;
                private int lastTotal;

                public int computeInvoice(List<Integer> items, boolean vip, int fallbackTax) {
                    int unrelatedDead = 42;
                    String noiseLabel = "ignore-" + unrelatedDead;
                    int subtotal = 0;
                    for (int price : items) {
                        if (price > 0) {
                            subtotal += price;
                        }
                    }
                    int discount = 0;
                    if (vip) {
                        discount = subtotal / 10;
                    } else {
                        discount = 5;
                    }
                    int tax = 0;
                    try {
                        tax = parseTax(subtotal);
                    } catch (IllegalArgumentException ex) {
                        tax = fallbackTax;
                    }
                    int unrelatedAfter = noiseLabel.length();
                    this.auditCount++;
                    int finalTotal = subtotal - discount + tax;
                    this.lastTotal = finalTotal;
                    return finalTotal;
                }

                private int parseTax(int amount) {
                    return amount / 20;
                }
            }
            """;

    @Test
    void backwardForwardAndBothSlicesExcludeUnrelatedStatementsAccurately() {
        // Verify exact line numbers from SAMPLE_JAVA:
        // Line 10: int unrelatedDead = 42;
        // Line 11: String noiseLabel = "ignore-" + unrelatedDead;
        // Line 12: int subtotal = 0;
        // Line 13: for (int price : items) {
        // Line 14:     if (price > 0) {
        // Line 15:         subtotal += price;
        // Line 18: int discount = 0;
        // Line 19: if (vip) {
        // Line 20:     discount = subtotal / 10;
        // Line 22:     discount = 5;
        // Line 24: int tax = 0;
        // Line 26:     tax = parseTax(subtotal);
        // Line 27: } catch (IllegalArgumentException ex) {
        // Line 28:     tax = fallbackTax;
        // Line 30: int unrelatedAfter = noiseLabel.length();
        // Line 31: this.auditCount++;
        // Line 32: int finalTotal = subtotal - discount + tax;
        // Line 33: this.lastTotal = finalTotal;
        // Line 34: return finalTotal;

        // 1. BACKWARD slice from line 34 (`return finalTotal;`)
        MethodSliceResult backward = JavaMethodSlicer.slice(
                "src/main/java/com/acme/billing/InvoiceCalculator.java",
                SAMPLE_JAVA,
                Optional.of("computeInvoice(List<Integer>, boolean, int)"),
                34,
                SliceDirection.BACKWARD,
                80);

        assertEquals(TrustTier.SOURCE_SYNTAX, backward.trustTier());
        assertEquals(SliceDirection.BACKWARD, backward.direction());
        assertEquals("computeInvoice(List, boolean, int)", backward.methodSignature());
        assertFalse(backward.truncated());
        assertTrue(backward.totalMethodStatements() >= 15);
        assertFalse(backward.limitations().isEmpty());

        List<Integer> bLines = backward.sliceLines();
        // Must include seed + all data & control deps reaching `return finalTotal;`
        assertTrue(bLines.containsAll(List.of(12, 13, 14, 15, 19, 20, 22, 26, 27, 28, 32, 34)),
                "Expected backward dependencies in slice, got: " + bLines);

        // Must EXCLUDE all unrelated dead/independent statements!
        assertFalse(bLines.contains(10), "unrelatedDead must be excluded from backward slice: " + bLines);
        assertFalse(bLines.contains(11), "noiseLabel must be excluded from backward slice: " + bLines);
        assertFalse(bLines.contains(30), "unrelatedAfter must be excluded from backward slice: " + bLines);
        assertFalse(bLines.contains(31), "this.auditCount++ must be excluded from backward slice: " + bLines);
        assertFalse(bLines.contains(33), "this.lastTotal = finalTotal must be excluded from backward slice of return: " + bLines);

        // Seed statement must be tagged SEED and statements sorted strictly ascending by line
        SliceStatement seedStmt = backward.statements().stream()
                .filter(s -> s.line() == 34)
                .findFirst()
                .orElseThrow();
        assertEquals("SEED", seedStmt.dependencyKind());
        for (int i = 1; i < backward.statements().size(); i++) {
            assertTrue(backward.statements().get(i - 1).line() <= backward.statements().get(i).line());
        }

        // CanonicalJson serialization must succeed & be deterministic
        String json1 = CanonicalJson.write(backward.toView());
        String json2 = CanonicalJson.write(JavaMethodSlicer.slice(
                "src/main/java/com/acme/billing/InvoiceCalculator.java",
                SAMPLE_JAVA,
                Optional.of("computeInvoice(List<Integer>, boolean, int)"),
                34,
                SliceDirection.BACKWARD,
                80).toView());
        assertEquals(json1, json2);

        // 2. FORWARD slice from line 10 (`int unrelatedDead = 42;`)
        MethodSliceResult forwardDead = JavaMethodSlicer.slice(
                "src/main/java/com/acme/billing/InvoiceCalculator.java",
                SAMPLE_JAVA,
                Optional.empty(),
                10,
                SliceDirection.FORWARD,
                80);
        assertEquals(List.of(10, 11, 30), forwardDead.sliceLines(),
                "Forward slice from unrelatedDead must ONLY hit lines 10 -> 11 -> 30");

        // 3. FORWARD slice from line 19 (`if (vip)`)
        MethodSliceResult forwardVip = JavaMethodSlicer.slice(
                "src/main/java/com/acme/billing/InvoiceCalculator.java",
                SAMPLE_JAVA,
                Optional.empty(),
                19,
                SliceDirection.FORWARD,
                80);
        List<Integer> fVipLines = forwardVip.sliceLines();
        assertTrue(fVipLines.containsAll(List.of(19, 20, 22, 32, 33, 34)),
                "Forward slice from if(vip) must propagate control->data into discount, finalTotal, lastTotal, return: " + fVipLines);
        assertFalse(fVipLines.contains(10));
        assertFalse(fVipLines.contains(12));
        assertFalse(fVipLines.contains(26));
        assertFalse(fVipLines.contains(31));

        // 4. BOTH slice from line 32 (`int finalTotal = subtotal - discount + tax;`)
        // Includes backward deps (12..15, 19..22, 26..28) AND forward dependents (33 this.lastTotal, 34 return)
        // while STILL excluding unrelated lines (10, 11, 30, 31)!
        MethodSliceResult both = JavaMethodSlicer.slice(
                "src/main/java/com/acme/billing/InvoiceCalculator.java",
                SAMPLE_JAVA,
                Optional.empty(),
                32,
                SliceDirection.BOTH,
                80);
        List<Integer> bothLines = both.sliceLines();
        assertTrue(bothLines.containsAll(List.of(12, 13, 14, 15, 19, 20, 22, 26, 27, 28, 32, 33, 34)),
                "BOTH slice must cover both backward producers and forward consumers: " + bothLines);
        assertFalse(bothLines.contains(10));
        assertFalse(bothLines.contains(11));
        assertFalse(bothLines.contains(30));
        assertFalse(bothLines.contains(31));
    }

    @Test
    void supportsConstructorsFieldMutationsLambdasAndMaxStatementsTruncation() {
        String source = """
                public class OrderState {
                    private int count;
                    private String tag;

                    public OrderState(int initial, java.util.List<String> names) {
                        int ignored = 100;
                        this.count = initial;
                        this.tag = names.stream().map(n -> n + this.count).findFirst().orElse("none");
                        int result = this.count + this.tag.length();
                        System.out.println(result);
                    }
                }
                """;
        // Line 9: int result = this.count + this.tag.length();
        MethodSliceResult slice = JavaMethodSlicer.slice(
                "OrderState.java", source, Optional.empty(), 9, SliceDirection.BACKWARD, 80);
        assertEquals("OrderState(int, java.util.List)", slice.methodSignature());
        assertTrue(slice.sliceLines().containsAll(List.of(7, 8, 9)));
        assertFalse(slice.sliceLines().contains(6), "ignored variable must not appear in backward slice");

        // Verify maxStatements truncation is honest and bounded
        MethodSliceResult truncated = JavaMethodSlicer.slice(
                "OrderState.java", source, Optional.empty(), 9, SliceDirection.BACKWARD, 2);
        assertTrue(truncated.truncated());
        assertEquals(2, truncated.statements().size());
        assertTrue(truncated.limitations().stream().anyMatch(l -> l.contains("truncated")));
    }
}
