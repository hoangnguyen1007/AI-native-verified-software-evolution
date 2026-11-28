package com.evolution.backend.query.repair;

import com.evolution.analysis.assessment.v2.QmoodQualityModel;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.*;
import com.evolution.backend.query.advisory.RefactoringExemplarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end contract & adversarial tests for Phase 4 (WP4.1–WP4.4):
 * Dual-Gate AST Refactoring Purity, 6 OpenRewrite Compound Recipes, Quarantined RRF Exemplar Store,
 * Closed-Loop APR/Refactoring Orchestrator + QMOOD Guard, Dual-Slicing Fallback Gap, Real-Trace RTS
 * Calibration, Multi-JDK Matrix, JaCoCo Coverage Gate & 3-Layer Certification.
 */
class AstRefactoringPurityVerifierTest {

    // ─── WP4.3: Dual-Gate AST Purity & 100% Hollow-Stub Detection ───

    @Test
    void detectsHollowEmptyMethodStubOverfittingCheatWith100PercentRecall() {
        String before = """
                package com.acme;
                public class Billing {
                    public int compute(int a, int b) {
                        int subtotal = a * 2;
                        int tax = subtotal + b;
                        return tax;
                    }
                    public int untouched(int x) { return x + 1; }
                }
                """;
        // Adversarial cheat #1: empty extracted method body
        String emptyStubAfter = """
                package com.acme;
                public class Billing {
                    public int compute(int a, int b) {
                        extractedHelper();
                        int subtotal = a * 2;
                        int tax = subtotal + b;
                        return tax;
                    }
                    private void extractedHelper() {}
                    public int untouched(int x) { return x + 1; }
                }
                """;
        // Adversarial cheat #2: trivial `return 0;` stub
        String trivialStubAfter = """
                package com.acme;
                public class Billing {
                    public int compute(int a, int b) {
                        int ignore = extractedHelper();
                        int subtotal = a * 2;
                        int tax = subtotal + b;
                        return tax;
                    }
                    private int extractedHelper() { return 0; }
                    public int untouched(int x) { return x + 1; }
                }
                """;
        var spec = AstRefactoringPurityVerifier.RefactoringSpec.extractMethod(
                "src/main/java/com/acme/Billing.java", "compute", "extractedHelper");

        var res1 = AstRefactoringPurityVerifier.verify(spec,
                Map.of("src/main/java/com/acme/Billing.java", before),
                Map.of("src/main/java/com/acme/Billing.java", emptyStubAfter));
        assertFalse(res1.passed());
        assertTrue(res1.hollowStubDetected(), "Empty stub cheat must be caught");

        var res2 = AstRefactoringPurityVerifier.verify(spec,
                Map.of("src/main/java/com/acme/Billing.java", before),
                Map.of("src/main/java/com/acme/Billing.java", trivialStubAfter));
        assertFalse(res2.passed());
        assertTrue(res2.hollowStubDetected(), "Trivial literal stub cheat must be caught");
    }

    @Test
    void purityGateRejectsSilentOutOfScopeMethodMutation() {
        String before = """
                package com.acme;
                public class Ledger {
                    public int calc(int a) {
                        int x = a * 3;
                        return x + 1;
                    }
                    public int unrelatedFee(int amt) {
                        return amt + 10;
                    }
                }
                """;
        // Valid extract method PLUS sneaky out-of-scope mutation in unrelatedFee (+10 -> +99)
        String taintedAfter = """
                package com.acme;
                public class Ledger {
                    public int calc(int a) {
                        int x = scale(a);
                        return x + 1;
                    }
                    private int scale(int a) {
                        int x = a * 3;
                        return x;
                    }
                    public int unrelatedFee(int amt) {
                        return amt + 99;
                    }
                }
                """;
        var spec = AstRefactoringPurityVerifier.RefactoringSpec.extractMethod(
                "src/main/java/com/acme/Ledger.java", "calc", "scale");
        var res = AstRefactoringPurityVerifier.verify(spec,
                Map.of("src/main/java/com/acme/Ledger.java", before),
                Map.of("src/main/java/com/acme/Ledger.java", taintedAfter));
        assertTrue(res.intentPassed());
        assertFalse(res.purityPassed(), "Out-of-scope mutation must fail Gate 2 Purity");
        assertFalse(res.passed());
        assertTrue(res.outOfScopeMutations().stream().anyMatch(m -> m.contains("unrelatedFee")));
    }

    // ─── WP4.3: 6 Deterministic OpenRewrite Compound Recipes (Catalog = 22) ───

    @Test
    void executesAllSixDeterministicOpenRewriteCompoundRecipesAndPassesAstPurityGate() {
        assertEquals(22, CapturedRepair.verifiedRecipeToolActions().size(), "16 DI + 6 OO compound recipes");

        // 1. EXTRACT_METHOD_SAFE
        String billingPath = "src/main/java/com/acme/Billing.java";
        String billingBefore = """
                package com.acme;
                public class Billing {
                    public int compute(int base, int fee) {
                        int doubled = base * 2;
                        int adjusted = doubled + fee;
                        return adjusted;
                    }
                    public int untouched(int z) { return z; }
                }
                """;
        var extractReq = new OpenRewriteCompoundRecipes.CompoundRequest(
                JavaRepairRecipes.Recipe.EXTRACT_METHOD_SAFE, billingPath, "compute", 4, 5,
                "", "", "computeAdjusted", "", Set.of(), Set.of());
        var extractOut = CapturedRepair.applyVerifiedCompoundRecipe(
                extractReq,
                AstRefactoringPurityVerifier.RefactoringSpec.extractMethod(billingPath, "compute", "computeAdjusted"),
                Map.of(billingPath, billingBefore));
        assertTrue(extractOut.certified(), () -> "EXTRACT_METHOD_SAFE failed: " + extractOut);

        // 2. MOVE_INSTANCE_METHOD (preserves OO instance receiver, NOT forced static)
        String svcPath = "src/main/java/com/acme/OrderService.java";
        String calcPath = "src/main/java/com/acme/TaxCalculator.java";
        String svcBefore = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int computeOrderTax(int subtotal) {
                        int rate = taxCalculator.baseRate();
                        return subtotal * rate;
                    }
                }
                """;
        String calcBefore = """
                package com.acme;
                public class TaxCalculator {
                    public int baseRate() { return 2; }
                }
                """;
        var moveReq = new OpenRewriteCompoundRecipes.CompoundRequest(
                JavaRepairRecipes.Recipe.MOVE_INSTANCE_METHOD, svcPath, "computeOrderTax", 0, 0,
                calcPath, "TaxCalculator", "", "taxCalculator", Set.of(), Set.of());
        var moveOut = CapturedRepair.applyVerifiedCompoundRecipe(
                moveReq,
                AstRefactoringPurityVerifier.RefactoringSpec.moveMethod(svcPath, calcPath, "computeOrderTax"),
                Map.of(svcPath, svcBefore, calcPath, calcBefore));
        assertTrue(moveOut.certified(), () -> "MOVE_INSTANCE_METHOD failed: " + moveOut);
        String movedTargetCode = moveOut.recipeOutcome().changes().stream()
                .filter(c -> c.path().equals(calcPath)).findFirst().orElseThrow().afterText();
        assertFalse(movedTargetCode.contains("public static int computeOrderTax"),
                "WP4.3: must preserve OO instance method semantics (not force public static)");

        // 3. EXTRACT_AND_MOVE_METHOD (atomic 2-step compound attacking SWE-Refactor 39.4% cliff)
        String compoundSvc = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int checkout(int subtotal) {
                        int rate = taxCalculator.baseRate();
                        int tax = subtotal * rate;
                        return subtotal + tax;
                    }
                }
                """;
        var extMoveReq = new OpenRewriteCompoundRecipes.CompoundRequest(
                JavaRepairRecipes.Recipe.EXTRACT_AND_MOVE_METHOD, svcPath, "checkout", 5, 6,
                calcPath, "TaxCalculator", "calculateTax", "taxCalculator", Set.of(), Set.of());
        var extMoveOut = CapturedRepair.applyVerifiedCompoundRecipe(
                extMoveReq,
                AstRefactoringPurityVerifier.RefactoringSpec.extractAndMove(svcPath, "checkout", calcPath, "calculateTax"),
                Map.of(svcPath, compoundSvc, calcPath, calcBefore));
        assertTrue(extMoveOut.certified(), () -> "EXTRACT_AND_MOVE_METHOD failed: " + extMoveOut);

        // 4. MOVE_AND_RENAME_METHOD (updates callers across context files)
        String callerPath = "src/main/java/com/acme/CheckoutClient.java";
        String callerBefore = """
                package com.acme;
                public class CheckoutClient {
                    public int run(OrderService s) {
                        return s.computeOrderTax(10);
                    }
                }
                """;
        var moveRenReq = new OpenRewriteCompoundRecipes.CompoundRequest(
                JavaRepairRecipes.Recipe.MOVE_AND_RENAME_METHOD, svcPath, "computeOrderTax", 0, 0,
                calcPath, "TaxCalculator", "estimateTax", "taxCalculator", Set.of(), Set.of());
        var moveRenOut = CapturedRepair.applyVerifiedCompoundRecipe(
                moveRenReq,
                AstRefactoringPurityVerifier.RefactoringSpec.moveAndRename(
                        svcPath, "computeOrderTax", calcPath, "estimateTax", Set.of("run")),
                Map.of(svcPath, svcBefore, calcPath, calcBefore, callerPath, callerBefore));
        assertTrue(moveRenOut.certified(), () -> "MOVE_AND_RENAME_METHOD failed: " + moveRenOut);
        assertTrue(moveRenOut.recipeOutcome().changes().stream()
                .anyMatch(c -> c.path().equals(callerPath) && c.afterText().contains(".estimateTax(")));

        // 5. EXTRACT_CLASS_GOD_DECOMPOSE
        String godPath = "src/main/java/com/acme/GodManager.java";
        String godBefore = """
                package com.acme;
                public class GodManager {
                    private int currencyRate = 2;
                    private int auditCounter = 0;
                    public int convert(int amt) { return amt * currencyRate; }
                    public void recordAudit() { auditCounter++; }
                }
                """;
        var godReq = new OpenRewriteCompoundRecipes.CompoundRequest(
                JavaRepairRecipes.Recipe.EXTRACT_CLASS_GOD_DECOMPOSE, godPath, "", 0, 0,
                "src/main/java/com/acme/CurrencyConverter.java", "CurrencyConverter", "", "currencyConverter",
                Set.of("currencyRate"), Set.of("convert"));
        var godOut = CapturedRepair.applyVerifiedCompoundRecipe(
                godReq,
                AstRefactoringPurityVerifier.RefactoringSpec.extractClass(
                        godPath, "src/main/java/com/acme/CurrencyConverter.java", Set.of("convert")),
                Map.of(godPath, godBefore));
        assertTrue(godOut.certified(), () -> "EXTRACT_CLASS_GOD_DECOMPOSE failed: " + godOut);

        // 6. BREAK_PACKAGE_SCC_CYCLE
        String aPath = "src/main/java/com/acme/a/AlphaService.java";
        String bPath = "src/main/java/com/acme/b/BetaService.java";
        String aSrc = """
                package com.acme.a;
                import com.acme.b.BetaService;
                public class AlphaService {
                    private final BetaService beta;
                    public AlphaService(BetaService beta) { this.beta = beta; }
                    public String ping() { return beta.pong(); }
                }
                """;
        String bSrc = """
                package com.acme.b;
                public class BetaService {
                    public String pong() { return "ok"; }
                }
                """;
        var sccReq = new OpenRewriteCompoundRecipes.CompoundRequest(
                JavaRepairRecipes.Recipe.BREAK_PACKAGE_SCC_CYCLE, aPath, "", 0, 0,
                bPath, "BetaPort", "", "", Set.of(), Set.of());
        var sccOut = CapturedRepair.applyVerifiedCompoundRecipe(
                sccReq,
                AstRefactoringPurityVerifier.RefactoringSpec.breakSccCycle(
                        aPath, "src/main/java/com/acme/a/BetaPort.java", Set.of("ping", "pong")),
                Map.of(aPath, aSrc, bPath, bSrc));
        assertTrue(sccOut.certified(), () -> "BREAK_PACKAGE_SCC_CYCLE failed: " + sccOut);
    }

    // ─── WP4.3: Quarantined RRF Refactoring Exemplar Store & Zero-Overlap Anti-Leakage ───

    @Test
    void quarantinedExemplarStoreRanksViaBm25StructuralRrfAndEnforcesZeroEvalOverlap() {
        var store = new RefactoringExemplarStore(List.of(
                new RefactoringExemplarStore.Exemplar(
                        "ex-1", AstRefactoringPurityVerifier.RefactoringKind.EXTRACT_AND_MOVE_METHOD,
                        new RefactoringExemplarStore.RepoCommit("org/clean-corpus-a", "aaa111"),
                        "Extract tax calculation into collaborator TaxEngine instance method",
                        "int tax = amount * taxEngine.rate();", "int tax = taxEngine.computeTax(amount);",
                        Set.of("collaborator", "instance-method", "compound"), "", null),
                new RefactoringExemplarStore.Exemplar(
                        "ex-2", AstRefactoringPurityVerifier.RefactoringKind.EXTRACT_AND_MOVE_METHOD,
                        new RefactoringExemplarStore.RepoCommit("org/clean-corpus-b", "bbb222"),
                        "Move formatting helper to Formatter component",
                        "String s = fmt.prefix() + x;", "String s = fmt.format(x);",
                        Set.of("formatter", "compound"), "", null)));

        assertTrue(store.all().stream().allMatch(e -> RefactoringExemplarStore.TRUST_TIER.equals(e.trustTier())));
        var top = store.retrieveTopK(
                "extract tax calculation into collaborator TaxEngine",
                Set.of("collaborator", "instance-method", "compound"),
                Optional.of(AstRefactoringPurityVerifier.RefactoringKind.EXTRACT_AND_MOVE_METHOD),
                Set.of(), 3);
        assertEquals(2, top.size());
        assertEquals("ex-1", top.getFirst().exemplar().id());
        assertTrue(RefactoringExemplarStore.formatQuarantinedExemplarBlock(top).startsWith("[ADVISORY_EXEMPLAR"));

        // Zero-overlap anti-leakage check (I5)
        assertDoesNotThrow(() -> store.assertZeroOverlap(Set.of(
                new RefactoringExemplarStore.RepoCommit("org/eval-holdout", "ccc333"))));
        assertThrows(IllegalStateException.class, () -> store.assertZeroOverlap(Set.of(
                new RefactoringExemplarStore.RepoCommit("org/clean-corpus-a", "aaa111"))));
    }

    // ─── WP4.2: Closed-Loop APR/Refactoring + Oscillation Guard + Rollback + 4-Gate Ranking ───

    @Test
    void closedLoopEnforcesOscillationGuardQmoodGateBudgetRollbackAndPatchAugmentation() {
        var snapshot = new SnapshotIdentity("snapshot:sha256:" + "1".repeat(64));
        var qmoodBefore = QmoodQualityModel.compute(List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.Big", 0, 4, 4, 3, 0.70, 2, 0, 8, 0, 4, 8, false)),
                1, 80.0);
        var qmoodDegraded = QmoodQualityModel.compute(List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.Big", 3, 0, 4, 9, 0.15, 5, 2, 16, 5, 8, 14, false),
                new QmoodQualityModel.ClassMetricProfile("com.acme.H1", 3, 0, 4, 9, 0.15, 5, 2, 16, 5, 8, 14, false)),
                2, 65.0);
        var qmoodClean = QmoodQualityModel.compute(List.of(
                new QmoodQualityModel.ClassMetricProfile("com.acme.Big", 0, 4, 4, 1, 0.90, 1, 0, 6, 0, 4, 6, false)),
                0, 92.0);

        // Case A: Refactoring loop rejects repeated patchDigest (Oscillation Guard) and rolls back on exhaustion
        AtomicBoolean rolledBack = new AtomicBoolean(false);
        var exhausted = CapturedRepair.executeClosedLoopCampaign(
                snapshot,
                new ClosedLoopRepairOrchestrator.LoopConfig(
                        ClosedLoopRepairOrchestrator.LoopMode.ARCHITECTURAL_REFACTORING, 1, 1, 4, 0, 25, -2.0),
                Optional.of(qmoodBefore),
                true,
                (outer, inner, fb) -> Optional.of(new ClosedLoopRepairOrchestrator.PatchCandidate(
                        "dup-" + inner, null, "same-bad-patch", false, "", 10, Map.of())),
                null,
                cand -> new ClosedLoopRepairOrchestrator.SandboxRoundOutcome(
                        true, true, 0, 0, List.of(), List.of(), Optional.empty(), Optional.empty(),
                        Optional.of(qmoodDegraded), Optional.empty()),
                () -> rolledBack.set(true));
        assertFalse(exhausted.succeeded());
        assertTrue(rolledBack.get(), "Budget exhaustion MUST trigger SafePatchApplier rollback");
        assertTrue(exhausted.oscillationRejections() >= 3, "Duplicate patchDigest must trigger Oscillation Guard");
        assertTrue(exhausted.capabilityGaps().stream().anyMatch(
                g -> ClosedLoopRepairOrchestrator.GAP_REPAIR_BUDGET_EXHAUSTED.equals(g.reasonCode())));

        // Case B: APR mode with Patch Augmentation ranks clean 4-gate deterministic recipe above QMOOD-degraded plausible patch
        var aprResult = CapturedRepair.executeClosedLoopCampaign(
                snapshot,
                ClosedLoopRepairOrchestrator.LoopConfig.aprDefault(),
                Optional.of(qmoodBefore),
                false,
                (outer, inner, fb) -> Optional.of(new ClosedLoopRepairOrchestrator.PatchCandidate(
                        "seed", null, "patch-seed-degraded", false, "", 12, Map.of())),
                (seed, maxVars) -> List.of(
                        new ClosedLoopRepairOrchestrator.PatchCandidate(
                                "aug-clean", null, "patch-aug-clean", true, "java.extract-and-move-method", 6, Map.of())),
                cand -> new ClosedLoopRepairOrchestrator.SandboxRoundOutcome(
                        true, true, 0, 0, List.of(), List.of(),
                        Optional.empty(), Optional.empty(),
                        Optional.of(cand.candidateId().equals("aug-clean") ? qmoodClean : qmoodDegraded),
                        Optional.empty()),
                () -> {});
        assertTrue(aprResult.succeeded());
        assertEquals("aug-clean", aprResult.winningCandidate().orElseThrow().candidate().candidateId());
    }

    // ─── WP4.1 & WP4.4: Real-Trace RTS Calibration, Multi-JDK Matrix, JaCoCo UNKNOWN_UNTESTED & 3-Layer Cert ───

    @Test
    void calibratesRtsAgainstRealTraceAndDetectsMultiJdkAndJacocoUnknownUntested(@TempDir Path tempDir) throws Exception {
        // 1. WP4.1 Real-Trace RTS calibration
        var selection = new AffectedTestSelector.Result(
                AffectedTestSelector.SelectionMode.SELECTED_TESTS,
                List.of(new AffectedTestSelector.SelectedTest("OrderServiceTest", "src/test/java/OrderServiceTest.java", 1, "direct")),
                4, 75.0, Optional.of("OrderServiceTest"), "SKG_RTS_SELECTED(1/4)");
        var realTrace = Map.of(
                "OrderServiceTest", Set.of("src/main/java/OrderService.java"),
                "BillingTest", Set.of("src/main/java/Billing.java"),
                "UserTest", Set.of("src/main/java/User.java"),
                "HealthTest", Set.of("src/main/java/Health.java"));
        var safeCal = AffectedTestSelector.calibrateAgainstRealTrace(
                selection, realTrace, List.of("src/main/java/OrderService.java"));
        assertTrue(safeCal.safe());
        assertEquals(0.0, safeCal.missedTestRatePercent());
        assertEquals(100.0, safeCal.recallPercent());
        assertEquals(75.0, safeCal.reductionPercent());

        // Detect unsafe miss if another test actually executed modified file
        var unsafeTrace = Map.of(
                "OrderServiceTest", Set.of("src/main/java/OrderService.java"),
                "HiddenReflectionTest", Set.of("src/main/java/OrderService.java"));
        var unsafeCal = AffectedTestSelector.calibrateAgainstRealTrace(
                selection, unsafeTrace, List.of("src/main/java/OrderService.java"));
        assertFalse(unsafeCal.safe());
        assertEquals(Set.of("HiddenReflectionTest"), unsafeCal.missedTests());

        // 2. WP4.4 Multi-JDK passive detection & Two-Tier Certification
        Path pom8 = tempDir.resolve("jdk8");
        Files.createDirectories(pom8);
        Files.writeString(pom8.resolve("pom.xml"), "<project><properties><maven.compiler.source>1.8</maven.compiler.source></properties></project>");
        assertEquals(8, CapturedRepairRunner.detectRequiredJdkVersion(pom8));

        Path gradle17 = tempDir.resolve("jdk17");
        Files.createDirectories(gradle17);
        Files.writeString(gradle17.resolve("build.gradle.kts"), "java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }");
        assertEquals(17, CapturedRepairRunner.detectRequiredJdkVersion(gradle17));
        assertTrue(CapturedRepairRunner.PINNED_BASE_IMAGE_PATTERN.matcher(
                CapturedRepairRunner.resolveBaseImageForJdk(17, Optional.empty())).matches());

        // Two-Tier orchestration skips Tier-2 hypervisor when Tier-1 has no winner (0 wasted VM boots)
        AtomicInteger vmBoots = new AtomicInteger();
        var failedCampaign = new ClosedLoopRepairOrchestrator.CampaignResult(
                ClosedLoopRepairOrchestrator.LoopMode.APR, false, Optional.empty(), List.of(),
                3, 3, 0, true, false, List.of(), null);
        var skippedTier2 = CapturedRepairRunner.orchestrateTwoTier(gradle17, failedCampaign, w -> {
            vmBoots.incrementAndGet();
            return null;
        });
        assertFalse(skippedTier2.certified());
        assertFalse(skippedTier2.tier2HypervisorExecuted());
        assertEquals(0, vmBoots.get());

        // 3. WP4.4 JaCoCo XML coverage gate -> UNKNOWN_UNTESTED when modified file has 0 covered lines
        String jacocoXml = """
                <report name="demo">
                  <package name="com/acme">
                    <sourcefile name="Covered.java">
                      <counter type="LINE" missed="2" covered="8"/>
                      <counter type="BRANCH" missed="1" covered="3"/>
                    </sourcefile>
                    <sourcefile name="Untested.java">
                      <counter type="LINE" missed="15" covered="0"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        var cov = CapturedRepairVerification.parseJaCoCoXml(jacocoXml);
        assertEquals(8, cov.totalLinesCovered());
        assertEquals(List.of("com/acme/Untested.java"), cov.zeroCoverageFiles());

        var gateUntested = CapturedRepairVerification.evaluateCoverageGate(
                Optional.of(cov), List.of("src/main/java/com/acme/Untested.java"), false);
        assertEquals(CapturedRepairVerification.CoverageGateStatus.UNKNOWN_UNTESTED, gateUntested.status());

        var gateCovered = CapturedRepairVerification.evaluateCoverageGate(
                Optional.of(cov), List.of("src/main/java/com/acme/Covered.java"), false);
        assertEquals(CapturedRepairVerification.CoverageGateStatus.COVERED, gateCovered.status());

        // 4. WP4.4 Offline dependency missing gap detection (I3)
        var depGap = CapturedRepairVerification.detectSandboxDependencyGap(
                new SnapshotIdentity("snapshot:sha256:" + "a".repeat(64)),
                "[ERROR] Cannot access central in offline mode and the artifact com.foo:bar:1.0 has not been downloaded from it before.");
        assertTrue(depGap.isPresent());
        assertEquals(CapturedRepairVerification.GAP_SANDBOX_DEPENDENCY_MISSING, depGap.orElseThrow().reasonCode());

        // 5. WP4.1 Dynamic slice timeout explicitly emits DYNAMIC_SLICE_TIMEOUT_OR_UNAVAILABLE gap (I3)
        var minimalGraph = new KnowledgeGraph(
                new SnapshotIdentity("snapshot:sha256:" + "b".repeat(64)),
                List.of(), List.of(), List.of(), List.of());
        var dual = RepairContextPlanner.buildDualSliceContext(
                minimalGraph, "", "", 0, Map.of(),
                Optional.of(RepairContextPlanner.DynamicSliceTrace.timedOut(300, "Slicer4J exceeded 300s")),
                RepairContextPlanner.CompressedContext.EMPTY);
        assertEquals(RepairContextPlanner.DualSliceMode.STATIC_SAFE_FALLBACK, dual.mode());
        assertTrue(dual.capabilityGaps().stream().anyMatch(
                g -> RepairContextPlanner.GAP_DYNAMIC_SLICE_UNAVAILABLE.equals(g.reasonCode())));
    }

    @Test
    void enforcesThreeLayerCertificationAndNegativeControlBeforeConfirmApply() throws Exception {
        var snap = new SnapshotIdentity("snapshot:sha256:" + "c".repeat(64));
        var d1 = ContentDigest.sha256Utf8("prop");
        var d2 = ContentDigest.sha256Utf8("patch");
        var plan = new CapturedRepairVerification.Plan(
                d1, d2, d1, d1,
                new CapturedRepairVerification.TreeSummary(snap, 2),
                new CapturedRepairVerification.TreeSummary(snap, 2),
                new CapturedRepairVerification.TreeSummary(snap, 2),
                List.of(), List.of("."), List.of("candidate-compile", "candidate-test"));
        var isolatedPass = new CapturedRepairVerification.IsolatedProof(
                d1, d1, d1, "maven", List.of("."), 0, 0, 12, 0, 0, d1, 5,
                List.of("candidate.compile", "candidate.test"));
        var candResolved = new CapturedRepairVerification.Comparison(
                d1, d1, snap, d1, com.evolution.analysis.evolution.SnapshotComparison.Status.COMPLETE,
                Set.of("RESOLVED"), Set.of(), false, false);
        var ctrlPersisted = new CapturedRepairVerification.Comparison(
                d1, d1, snap, d1, com.evolution.analysis.evolution.SnapshotComparison.Status.COMPLETE,
                Set.of("PERSISTED"), Set.of(), false, false);
        var verifiedRes = new CapturedRepairVerification.Result(
                CapturedRepairVerification.VERSION, CapturedRepairVerification.Verdict.VERIFIED,
                d1, plan.identity(), plan, List.of(),
                Optional.of(candResolved), Optional.of(ctrlPersisted), Optional.of(isolatedPass), "");

        var cov = CapturedRepairVerification.parseJaCoCoXml("""
                <report name="t">
                  <package name="com/acme">
                    <sourcefile name="OrderService.java"><counter type="LINE" missed="0" covered="10"/></sourcefile>
                    <sourcefile name="UntestedService.java"><counter type="LINE" missed="10" covered="0"/></sourcefile>
                  </package>
                </report>
                """);
        var purityPass = new AstRefactoringPurityVerifier.VerificationResult(
                AstRefactoringPurityVerifier.VERSION, true, true, true, true, false,
                AstRefactoringPurityVerifier.RefactoringKind.EXTRACT_AND_MOVE_METHOD, List.of(), List.of(), null);
        var purityHollowFail = new AstRefactoringPurityVerifier.VerificationResult(
                AstRefactoringPurityVerifier.VERSION, false, false, true, true, true,
                AstRefactoringPurityVerifier.RefactoringKind.EXTRACT_AND_MOVE_METHOD,
                List.of("HOLLOW_STUB_DETECTED"), List.of(), null);

        // All 3 layers pass -> allowsConfirmApply() == true
        var certOk = CapturedRepairVerification.certifyThreeLayer(
                verifiedRes, Optional.of(cov), List.of("src/main/java/com/acme/OrderService.java"),
                false, Optional.of(purityPass));
        assertTrue(certOk.allowsConfirmApply());
        assertEquals(CapturedRepairVerification.Verdict.VERIFIED, certOk.verdict());

        // Hollow stub fails Layer 2 -> REJECTED
        var certHollow = CapturedRepairVerification.certifyThreeLayer(
                verifiedRes, Optional.of(cov), List.of("src/main/java/com/acme/OrderService.java"),
                false, Optional.of(purityHollowFail));
        assertFalse(certHollow.allowsConfirmApply());
        assertEquals(CapturedRepairVerification.Verdict.REJECTED, certHollow.verdict());

        // 0% JaCoCo coverage & no characterization probes -> UNKNOWN_UNTESTED / UNVERIFIED
        var certUntested = CapturedRepairVerification.certifyThreeLayer(
                verifiedRes, Optional.of(cov), List.of("src/main/java/com/acme/UntestedService.java"),
                false, Optional.of(purityPass));
        assertFalse(certUntested.allowsConfirmApply());
        assertEquals(CapturedRepairVerification.CoverageGateStatus.UNKNOWN_UNTESTED, certUntested.coverageStatus());
        assertEquals(CapturedRepairVerification.Verdict.UNVERIFIED, certUntested.verdict());

        // Missing Negative Control (control did not PERSIST) -> UNVERIFIED
        var noNegCtrlRes = new CapturedRepairVerification.Result(
                CapturedRepairVerification.VERSION, CapturedRepairVerification.Verdict.VERIFIED,
                d1, plan.identity(), plan, List.of(),
                Optional.of(candResolved), Optional.empty(), Optional.of(isolatedPass), "");
        var certNoNeg = CapturedRepairVerification.certifyThreeLayer(
                noNegCtrlRes, Optional.of(cov), List.of("src/main/java/com/acme/OrderService.java"),
                false, Optional.of(purityPass));
        assertFalse(certNoNeg.allowsConfirmApply());
        assertEquals(CapturedRepairVerification.Verdict.UNVERIFIED, certNoNeg.verdict());
    }

    // ─── Step 2 Adversarial Regression Suite (Review Hardening) ───

    @Test
    void jacocoParserAcceptsRealDoctypeHeaderWhileBlockingXxeEntitiesAndJdkDetectionIgnoresComments(@TempDir Path tempDir) throws Exception {
        String realJacocoXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
                <report name="real-jacoco">
                  <package name="com/acme">
                    <sourcefile name="RealService.java">
                      <counter type="LINE" missed="1" covered="9"/>
                    </sourcefile>
                  </package>
                </report>
                """;
        var parsed = CapturedRepairVerification.parseJaCoCoXml(realJacocoXml);
        assertEquals(9, parsed.totalLinesCovered());
        assertEquals(90.0, parsed.overallLineCoveragePercent());

        String xxePayload = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE report [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
                <report name="&xxe;"/>
                """;
        assertThrows(java.io.IOException.class, () -> CapturedRepairVerification.parseJaCoCoXml(xxePayload));

        // Commented pom.xml & build.gradle must not mis-detect JDK 8
        Path commentedPom = tempDir.resolve("commented-pom");
        Files.createDirectories(commentedPom);
        Files.writeString(commentedPom.resolve("pom.xml"),
                "<project><!-- <java.version>8</java.version> --><properties><java.version>17</java.version></properties></project>");
        assertEquals(17, CapturedRepairRunner.detectRequiredJdkVersion(commentedPom));

        Path commentedGradle = tempDir.resolve("commented-gradle");
        Files.createDirectories(commentedGradle);
        Files.writeString(commentedGradle.resolve("build.gradle"),
                "// sourceCompatibility = JavaVersion.VERSION_1_8\nsourceCompatibility = JavaVersion.VERSION_11\n");
        assertEquals(11, CapturedRepairRunner.detectRequiredJdkVersion(commentedGradle));
    }

    @Test
    void compoundRecipesAndPurityVerifierHandleAdversarialOverloadsLoopsConstructorsAndBudgetCaps() {
        // 1. EXTRACT_METHOD_SAFE rejects bare `break` inside enclosing loop & partial early return,
        //    and handles reassigned outer local variable cleanly
        String loopSrc = """
                package com.acme;
                public class Loops {
                    public int run(int n) {
                        int acc = 0;
                        for (int i = 0; i < n; i++) {
                            if (i == 3) break;
                            acc += i;
                        }
                        return acc;
                    }
                    public int partialReturn(int x) {
                        if (x < 0) return -1;
                        int y = x * 2;
                        return y;
                    }
                    public int reassignLocal(int x) {
                        int acc = x;
                        acc = acc + 5;
                        return acc;
                    }
                }
                """;
        var bareBreakOut = OpenRewriteCompoundRecipes.apply(
                new OpenRewriteCompoundRecipes.CompoundRequest(
                        JavaRepairRecipes.Recipe.EXTRACT_METHOD_SAFE, "Loops.java", "run", 6, 7,
                        "", "", "badBreak", "", Set.of(), Set.of()),
                Map.of("Loops.java", loopSrc));
        assertFalse(bareBreakOut.applied(), "Bare break inside enclosing loop must be rejected");

        var partialRetOut = OpenRewriteCompoundRecipes.apply(
                new OpenRewriteCompoundRecipes.CompoundRequest(
                        JavaRepairRecipes.Recipe.EXTRACT_METHOD_SAFE, "Loops.java", "partialReturn", 12, 13,
                        "", "", "badReturn", "", Set.of(), Set.of()),
                Map.of("Loops.java", loopSrc));
        assertFalse(partialRetOut.applied(), "Partial conditional return must be rejected");

        var reassignOut = OpenRewriteCompoundRecipes.apply(
                new OpenRewriteCompoundRecipes.CompoundRequest(
                        JavaRepairRecipes.Recipe.EXTRACT_METHOD_SAFE, "Loops.java", "reassignLocal", 18, 18,
                        "", "", "bumpAcc", "", Set.of(), Set.of()),
                Map.of("Loops.java", loopSrc));
        assertTrue(reassignOut.applied(), () -> "Reassigned outer local should extract cleanly: " + reassignOut);
        assertTrue(reassignOut.changes().getFirst().afterText().contains("acc = bumpAcc(acc);"));

        // 2. MOVE_AND_RENAME_METHOD OpenRewrite LST visitor only renames matching receiver type & arity
        String svcPath = "src/main/java/com/acme/OrderService.java";
        String calcPath = "src/main/java/com/acme/TaxCalculator.java";
        String callerPath = "src/main/java/com/acme/MixedCaller.java";
        String svcBefore = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int computeOrderTax(int subtotal) {
                        return subtotal * taxCalculator.baseRate();
                    }
                }
                """;
        String calcBefore = """
                package com.acme;
                public class TaxCalculator {
                    public int baseRate() { return 2; }
                }
                """;
        String mixedCaller = """
                package com.acme;
                public class MixedCaller {
                    public int run(OrderService s, UnrelatedService u) {
                        String msg = "u.computeOrderTax(10)";
                        int keepUnrelated = u.computeOrderTax(10);
                        int keepOverload = s.computeOrderTax(10, 20);
                        return s.computeOrderTax(10) + keepUnrelated + keepOverload + msg.length();
                    }
                }
                """;
        var moveRenOut = OpenRewriteCompoundRecipes.apply(
                new OpenRewriteCompoundRecipes.CompoundRequest(
                        JavaRepairRecipes.Recipe.MOVE_AND_RENAME_METHOD, svcPath, "computeOrderTax", 0, 0,
                        calcPath, "TaxCalculator", "estimateTax", "taxCalculator", Set.of(), Set.of()),
                Map.of(svcPath, svcBefore, calcPath, calcBefore, callerPath, mixedCaller));
        assertTrue(moveRenOut.applied(), () -> "MOVE_AND_RENAME failed: " + moveRenOut);
        String callerAfter = moveRenOut.changes().stream()
                .filter(c -> c.path().equals(callerPath)).findFirst().orElseThrow().afterText();
        assertTrue(callerAfter.contains("s.estimateTax(10)"), "Matching OrderService(1-arg) call must be renamed");
        assertTrue(callerAfter.contains("u.computeOrderTax(10)"), "Unrelated receiver call must NOT be corrupted");
        assertTrue(callerAfter.contains("s.computeOrderTax(10, 20)"), "Different-arity overload must NOT be corrupted");
        assertTrue(callerAfter.contains("\"u.computeOrderTax(10)\""), "String literal must NOT be corrupted");
        String svcAfter = moveRenOut.changes().stream()
                .filter(c -> c.path().equals(svcPath)).findFirst().orElseThrow().afterText();
        assertTrue(svcAfter.contains("public int estimateTax(int subtotal)"), "Source forwarding delegate must match renamed signature");

        // 3. BREAK_PACKAGE_SCC_CYCLE copies referenced imports, preserves `new BetaService()` and Javadoc
        String aPath = "src/main/java/com/acme/a/Alpha.java";
        String bPath = "src/main/java/com/acme/b/Beta.java";
        String aSrc = """
                package com.acme.a;
                import com.acme.b.Beta;
                /** Uses Beta safely */
                public class Alpha {
                    private final Beta beta;
                    public Alpha(Beta beta) { this.beta = beta; }
                    public Beta createDefault() { return new Beta(); }
                }
                """;
        String bSrc = """
                package com.acme.b;
                import java.util.List;
                /** Documentation mentioning class Beta in comment */
                public class Beta {
                    public List<String> items() { return List.of("a"); }
                }
                """;
        var sccOut = OpenRewriteCompoundRecipes.apply(
                new OpenRewriteCompoundRecipes.CompoundRequest(
                        JavaRepairRecipes.Recipe.BREAK_PACKAGE_SCC_CYCLE, aPath, "", 0, 0,
                        bPath, "BetaPort", "", "", Set.of(), Set.of()),
                Map.of(aPath, aSrc, bPath, bSrc));
        assertTrue(sccOut.applied(), () -> "BREAK_PACKAGE_SCC_CYCLE failed: " + sccOut);
        String ifaceCode = sccOut.changes().stream().filter(c -> c.path().endsWith("BetaPort.java")).findFirst().orElseThrow().afterText();
        assertTrue(ifaceCode.contains("import java.util.List;"), "Interface must import types used in signatures");
        String aAfter = sccOut.changes().stream().filter(c -> c.path().equals(aPath)).findFirst().orElseThrow().afterText();
        assertTrue(aAfter.contains("private final BetaPort beta;"));
        assertTrue(aAfter.contains("new Beta()"), "Must NOT turn `new Beta()` into illegal `new BetaPort()`");
        String bAfter = sccOut.changes().stream().filter(c -> c.path().equals(bPath)).findFirst().orElseThrow().afterText();
        assertTrue(bAfter.contains("/** Documentation mentioning class Beta in comment */"), "Javadoc must not be corrupted");
        assertTrue(bAfter.contains("public class Beta implements BetaPort"));

        // 4. Purity verifier catches dummy no-op stub + constructor mutation + field mutation
        String pBefore = """
                package com.acme;
                public class Guarded {
                    private int factor = 2;
                    public Guarded() { this.factor = 2; }
                    public int work(int a) {
                        int x = a * factor;
                        return x + 1;
                    }
                }
                """;
        String pNoopStub = """
                package com.acme;
                public class Guarded {
                    private int factor = 2;
                    public Guarded() { this.factor = 2; }
                    public int work(int a) {
                        noopHelper();
                        int x = a * factor;
                        return x + 1;
                    }
                    private void noopHelper() { int dummy = 1; }
                }
                """;
        var noopRes = AstRefactoringPurityVerifier.verify(
                AstRefactoringPurityVerifier.RefactoringSpec.extractMethod("Guarded.java", "work", "noopHelper"),
                Map.of("Guarded.java", pBefore), Map.of("Guarded.java", pNoopStub));
        assertFalse(noopRes.passed());
        assertTrue(noopRes.hollowStubDetected(), "No-op stub retaining all original statements must be flagged");

        String pCtorMutated = """
                package com.acme;
                public class Guarded {
                    private int factor = 999;
                    public Guarded() { this.factor = 999; }
                    public int work(int a) {
                        int x = mul(a);
                        return x + 1;
                    }
                    private int mul(int a) { return a * factor; }
                }
                """;
        var ctorRes = AstRefactoringPurityVerifier.verify(
                AstRefactoringPurityVerifier.RefactoringSpec.extractMethod("Guarded.java", "work", "mul"),
                Map.of("Guarded.java", pBefore), Map.of("Guarded.java", pCtorMutated));
        assertFalse(ctorRes.purityPassed(), "Out-of-scope constructor/field mutation must fail Gate 2 Purity");

        // 5. ClosedLoopRepairOrchestrator enforces augmentationVariants cap strictly
        var snap = new SnapshotIdentity("snapshot:sha256:" + "9".repeat(64));
        var cfg = new ClosedLoopRepairOrchestrator.LoopConfig(
                ClosedLoopRepairOrchestrator.LoopMode.APR, 1, 1, 1, 2, 25, -2.0);
        var augCapRes = ClosedLoopRepairOrchestrator.execute(
                snap, cfg, Optional.empty(), false,
                (o, i, fb) -> Optional.of(new ClosedLoopRepairOrchestrator.PatchCandidate("seed", null, "p0", false, "", 1, Map.of())),
                (seed, maxVars) -> List.of(
                        new ClosedLoopRepairOrchestrator.PatchCandidate("v1", null, "p1", true, "r", 1, Map.of()),
                        new ClosedLoopRepairOrchestrator.PatchCandidate("v2", null, "p2", true, "r", 1, Map.of()),
                        new ClosedLoopRepairOrchestrator.PatchCandidate("v3", null, "p3", true, "r", 1, Map.of()),
                        new ClosedLoopRepairOrchestrator.PatchCandidate("v4", null, "p4", true, "r", 1, Map.of())),
                cand -> new ClosedLoopRepairOrchestrator.SandboxRoundOutcome(
                        true, true, 0, 0, List.of(), List.of(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                () -> {});
        assertEquals(3, augCapRes.totalAttempts(), "1 seed + at most augmentationVariants=2 must be evaluated");
    }

    @Test
    void moveAndRenameGateRejectsNonDelegatingDuplicateAndSelfRecursionWhileAcceptingScopedDelegate() {
        String svcPath = "src/main/java/com/acme/OrderService.java";
        String calcPath = "src/main/java/com/acme/TaxCalculator.java";
        String callerPath = "src/main/java/com/acme/CheckoutClient.java";
        String svcBefore = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int computeOrderTax(int subtotal) {
                        int rate = taxCalculator.baseRate();
                        return subtotal * rate;
                    }
                }
                """;
        String calcBefore = """
                package com.acme;
                public class TaxCalculator {
                    public int baseRate() { return 2; }
                }
                """;
        String callerBefore = """
                package com.acme;
                public class CheckoutClient {
                    public int run(OrderService s) {
                        return s.computeOrderTax(10);
                    }
                }
                """;
        var spec = AstRefactoringPurityVerifier.RefactoringSpec.moveAndRename(
                svcPath, "computeOrderTax", calcPath, "estimateTax", Set.of("run"));

        // 1. Legitimate OpenRewriteCompoundRecipes MOVE_AND_RENAME_METHOD forwarding delegate passes Gate 1 + Gate 2
        var legitOut = CapturedRepair.applyVerifiedCompoundRecipe(
                new OpenRewriteCompoundRecipes.CompoundRequest(
                        JavaRepairRecipes.Recipe.MOVE_AND_RENAME_METHOD, svcPath, "computeOrderTax", 0, 0,
                        calcPath, "TaxCalculator", "estimateTax", "taxCalculator", Set.of(), Set.of()),
                spec,
                Map.of(svcPath, svcBefore, calcPath, calcBefore, callerPath, callerBefore));
        assertTrue(legitOut.certified(), () -> "Legitimate scoped delegate must pass: " + legitOut);

        String calcMoved = """
                package com.acme;
                public class TaxCalculator {
                    public int baseRate() { return 2; }
                    public int estimateTax(int subtotal) {
                        int rate = baseRate();
                        return subtotal * rate;
                    }
                }
                """;
        String callerRenamed = """
                package com.acme;
                public class CheckoutClient {
                    public int run(OrderService s) {
                        return s.estimateTax(10);
                    }
                }
                """;
        var beforeMap = Map.of(svcPath, svcBefore, calcPath, calcBefore, callerPath, callerBefore);

        // 2. Adversarial cheat A: renames in source AND duplicates full non-delegating body in both files
        String cheatDuplicatedSrc = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int estimateTax(int subtotal) {
                        int rate = taxCalculator.baseRate();
                        return subtotal * rate;
                    }
                }
                """;
        var dupRes = AstRefactoringPurityVerifier.verify(
                spec, beforeMap, Map.of(svcPath, cheatDuplicatedSrc, calcPath, calcMoved, callerPath, callerRenamed));
        assertFalse(dupRes.passed(), "Duplicated non-delegating rename in source must be rejected");
        assertFalse(dupRes.intentPassed());
        assertTrue(dupRes.violations().stream().anyMatch(v -> v.startsWith("INTENT_DUPLICATED_NOT_MOVED")),
                () -> "Expected INTENT_DUPLICATED_NOT_MOVED, got " + dupRes.violations());

        // 3. Adversarial cheat B: renames in source with unscoped or `this.` self-recursion
        String cheatSelfRecurseSrc = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int estimateTax(int subtotal) {
                        return this.estimateTax(subtotal);
                    }
                }
                """;
        var selfRecRes = AstRefactoringPurityVerifier.verify(
                spec, beforeMap, Map.of(svcPath, cheatSelfRecurseSrc, calcPath, calcMoved, callerPath, callerRenamed));
        assertFalse(selfRecRes.passed(), "Self-recursive rename in source must be rejected");
        assertTrue(selfRecRes.violations().stream().anyMatch(v -> v.startsWith("INTENT_DUPLICATED_NOT_MOVED")),
                () -> "Expected INTENT_DUPLICATED_NOT_MOVED, got " + selfRecRes.violations());

        // 4. Adversarial cheat C: duplicates full body AND adds a dead receiver call
        String cheatDupPlusDeadCallSrc = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int estimateTax(int subtotal) {
                        taxCalculator.estimateTax(subtotal);
                        int rate = taxCalculator.baseRate();
                        return subtotal * rate;
                    }
                }
                """;
        var deadCallRes = AstRefactoringPurityVerifier.verify(
                spec, beforeMap, Map.of(svcPath, cheatDupPlusDeadCallSrc, calcPath, calcMoved, callerPath, callerRenamed));
        assertFalse(deadCallRes.passed(), "Duplicated body with extra receiver call must be rejected");
        assertTrue(deadCallRes.violations().stream().anyMatch(v -> v.startsWith("INTENT_DUPLICATED_NOT_MOVED")),
                () -> "Expected INTENT_DUPLICATED_NOT_MOVED, got " + deadCallRes.violations());

        // 5. Adversarial cheat D: MOVE_METHOD with self-recursive remaining method in source
        String moveSelfRecSrc = """
                package com.acme;
                public class OrderService {
                    private final TaxCalculator taxCalculator = new TaxCalculator();
                    public int computeOrderTax(int subtotal) {
                        return computeOrderTax(subtotal);
                    }
                }
                """;
        String calcMoveOnly = """
                package com.acme;
                public class TaxCalculator {
                    public int baseRate() { return 2; }
                    public int computeOrderTax(int subtotal) {
                        int rate = baseRate();
                        return subtotal * rate;
                    }
                }
                """;
        var moveSelfRes = AstRefactoringPurityVerifier.verify(
                AstRefactoringPurityVerifier.RefactoringSpec.moveMethod(svcPath, calcPath, "computeOrderTax"),
                Map.of(svcPath, svcBefore, calcPath, calcBefore),
                Map.of(svcPath, moveSelfRecSrc, calcPath, calcMoveOnly));
        assertFalse(moveSelfRes.passed(), "Self-recursive remaining method in MOVE_METHOD must be rejected");
        assertTrue(moveSelfRes.violations().stream().anyMatch(v -> v.startsWith("INTENT_DUPLICATED_NOT_MOVED")),
                () -> "Expected INTENT_DUPLICATED_NOT_MOVED, got " + moveSelfRes.violations());
    }
}
