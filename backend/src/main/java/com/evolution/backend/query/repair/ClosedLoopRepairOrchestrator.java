package com.evolution.backend.query.repair;

import com.evolution.analysis.assessment.v2.QmoodQualityModel;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.backend.query.CapturedRepair;
import com.evolution.backend.query.CapturedRepairExecution;

import java.util.*;

/**
 * Closed-Loop Repair & Smell-Verified Refactoring Orchestrator (WP4.2 — RefAgent ICSE '26 & CausalRepair ISSTA '26).
 *
 * <p>Implements:
 * <ul>
 *   <li><b>APR Mode (CausalRepair Alg. 1):</b> {@code m <= 5} outer restarts x {@code n <= 3} inner
 *       conversational refinement turns with early exit on first plausible patch.</li>
 *   <li><b>Architectural Refactoring Mode (RefAgent Fig. 8):</b> hard ceiling of {@code <= 12} closed-loop
 *       iterations, pre-edit characterization test lock, {@code patchDigest} Oscillation Guard, and
 *       automatic rollback + {@link #GAP_REPAIR_BUDGET_EXHAUSTED} on budget exhaustion ({@code I3}).</li>
 *   <li><b>Patch Augmentation & 4-Gate Deterministic Ranking (CausalRepair Sec. 3.4):</b> evaluates up to
 *       {@code k = 5..10} equivalent patch variants (global cap {@value #MAX_TOTAL_PATCH_BUDGET} patches/task)
 *       through 4 deterministic gates: (1) {@link SpringPreservationChecker}, (2) {@link BehavioralDiffService},
 *       (3) {@link QmoodQualityModel} Understandability ({@code >= -2.0%}) & SCC non-regression guard, and
 *       (4) {@link AstRefactoringPurityVerifier} Dual-Gate.</li>
 * </ul>
 */
public final class ClosedLoopRepairOrchestrator {

    public static final int APR_MAX_OUTER_RESTARTS = 5;
    public static final int APR_MAX_INNER_TURNS = 3;
    public static final int REFACTORING_MAX_ITERATIONS = 12;
    public static final int MAX_AUGMENTATION_VARIANTS = 10;
    public static final int MAX_TOTAL_PATCH_BUDGET = 25;
    public static final String GAP_REPAIR_BUDGET_EXHAUSTED = "REPAIR_BUDGET_EXHAUSTED";
    public static final String GAP_OSCILLATION_DETECTED = "REPAIR_OSCILLATION_REJECTED";

    private static final VersionedIdentifier PROVIDER =
            new VersionedIdentifier("repair.closed-loop-orchestrator", "wp4.2-v1");
    private static final VersionedIdentifier GAP_CATALOG =
            new VersionedIdentifier("repair.closed-loop-gaps", "1");

    private ClosedLoopRepairOrchestrator() {}

    public enum LoopMode {
        APR,
        ARCHITECTURAL_REFACTORING
    }

    /** Configuration bounds enforcing RefAgent (<=12) and CausalRepair (m<=5, n<=3, total<=25) budgets. */
    public record LoopConfig(
            LoopMode mode,
            int maxOuterRestarts,
            int maxInnerTurns,
            int maxRefactoringIterations,
            int augmentationVariants,
            int totalPatchBudget,
            double qmoodUnderstandabilityFloorPercent) {
        public LoopConfig {
            mode = mode == null ? LoopMode.APR : mode;
            maxOuterRestarts = Math.max(1, Math.min(APR_MAX_OUTER_RESTARTS, maxOuterRestarts));
            maxInnerTurns = Math.max(1, Math.min(APR_MAX_INNER_TURNS, maxInnerTurns));
            maxRefactoringIterations = Math.max(1, Math.min(REFACTORING_MAX_ITERATIONS, maxRefactoringIterations));
            augmentationVariants = Math.max(0, Math.min(MAX_AUGMENTATION_VARIANTS, augmentationVariants));
            totalPatchBudget = Math.max(1, Math.min(MAX_TOTAL_PATCH_BUDGET, totalPatchBudget));
        }

        public static LoopConfig aprDefault() {
            return new LoopConfig(LoopMode.APR, APR_MAX_OUTER_RESTARTS, APR_MAX_INNER_TURNS,
                    REFACTORING_MAX_ITERATIONS, MAX_AUGMENTATION_VARIANTS, MAX_TOTAL_PATCH_BUDGET,
                    QmoodQualityModel.DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT);
        }

        public static LoopConfig refactoringDefault() {
            return new LoopConfig(LoopMode.ARCHITECTURAL_REFACTORING, 1, 1,
                    REFACTORING_MAX_ITERATIONS, 5, MAX_TOTAL_PATCH_BUDGET,
                    QmoodQualityModel.DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT);
        }
    }

    /** Structured candidate patch produced by a deterministic recipe or LLM generator turn. */
    public record PatchCandidate(
            String candidateId,
            ContentDigest patchDigest,
            String patchText,
            boolean deterministicRecipe,
            String recipeId,
            int changedLineCount,
            Map<String, String> afterFiles) {
        public PatchCandidate {
            candidateId = Objects.requireNonNull(candidateId, "candidateId");
            patchText = patchText == null ? "" : patchText;
            patchDigest = patchDigest == null ? ContentDigest.sha256Utf8(patchText) : patchDigest;
            recipeId = recipeId == null ? "" : recipeId;
            changedLineCount = Math.max(0, changedLineCount);
            afterFiles = afterFiles == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(afterFiles));
        }
    }

    /** Structured feedback returned from Tier-1 hardened sandbox verification. */
    public record SandboxRoundOutcome(
            boolean compilePassed,
            boolean testsPassed,
            int compileExit,
            int testExit,
            List<String> compilerDiagnostics,
            List<CapturedRepairExecution.TestFailure> failingTestTraces,
            Optional<SpringPreservationChecker.SpringPreservationReport> springPreservation,
            Optional<BehavioralDiffService.BehavioralDiffReport> behavioralDiff,
            Optional<QmoodQualityModel.QmoodSnapshot> qmoodAfter,
            Optional<AstRefactoringPurityVerifier.VerificationResult> astPurity) {
        public SandboxRoundOutcome {
            compilerDiagnostics = List.copyOf(compilerDiagnostics == null ? List.of() : compilerDiagnostics);
            failingTestTraces = List.copyOf(failingTestTraces == null ? List.of() : failingTestTraces);
            springPreservation = springPreservation == null ? Optional.empty() : springPreservation;
            behavioralDiff = behavioralDiff == null ? Optional.empty() : behavioralDiff;
            qmoodAfter = qmoodAfter == null ? Optional.empty() : qmoodAfter;
            astPurity = astPurity == null ? Optional.empty() : astPurity;
        }

        public boolean plausible() {
            return compilePassed && testsPassed && compileExit == 0 && testExit == 0;
        }

        public CapturedRepair.ClosedLoopFeedback toClosedLoopFeedback(int round) {
            var springDrifts = new ArrayList<String>();
            springPreservation.ifPresent(sp -> {
                if (sp.status() == SpringPreservationChecker.Status.DRIFT_DETECTED) {
                    springDrifts.add(sp.status().name() + ": " + sp.summary());
                }
            });
            var behWitnesses = new ArrayList<String>();
            behavioralDiff.ifPresent(bd -> {
                if (bd.verdict() == BehavioralDiffService.BehavioralVerdict.BEHAVIOR_CHANGED) {
                    behWitnesses.add(bd.summary());
                }
            });
            var policyIssues = new ArrayList<String>();
            astPurity.ifPresent(ap -> {
                if (!ap.passed()) policyIssues.addAll(ap.violations());
            });
            String tail = String.join("\n", compilerDiagnostics);
            return new CapturedRepair.ClosedLoopFeedback(
                    round, compileExit, testExit, failingTestTraces, tail, policyIssues, springDrifts, behWitnesses);
        }
    }

    /** 4-Gate deterministic evaluation record for a plausible candidate patch. */
    public record FourGateEvaluation(
            PatchCandidate candidate,
            boolean springPreserved,
            boolean behavioralAcceptable,
            BehavioralDiffService.BehavioralVerdict behavioralVerdict,
            boolean qmoodGuardPassed,
            Optional<QmoodQualityModel.GuardResult> qmoodGuard,
            boolean astPurityPassed,
            Optional<AstRefactoringPurityVerifier.VerificationResult> astPurity,
            boolean allFourGatesPassed,
            List<String> gateFailures) {
        public FourGateEvaluation {
            gateFailures = List.copyOf(gateFailures == null ? List.of() : gateFailures);
        }
    }

    /** Complete trace and winning candidate of a closed-loop repair or refactoring campaign. */
    public record CampaignResult(
            LoopMode mode,
            boolean succeeded,
            Optional<FourGateEvaluation> winningCandidate,
            List<FourGateEvaluation> rankedPlausibleCandidates,
            int totalAttempts,
            int uniquePatchesEvaluated,
            int oscillationRejections,
            boolean rollbackTriggered,
            boolean preEditProbesLocked,
            List<CapabilityGapRecord> capabilityGaps,
            ContentDigest digest) {
        public CampaignResult {
            rankedPlausibleCandidates = List.copyOf(rankedPlausibleCandidates == null ? List.of() : rankedPlausibleCandidates);
            capabilityGaps = List.copyOf(capabilityGaps == null ? List.of() : capabilityGaps);
            digest = digest == null
                    ? CanonicalJson.sha256(List.of(
                    "closed-loop-campaign:v1", mode.name(), succeeded,
                    winningCandidate.map(w -> w.candidate().patchDigest().value()).orElse(""),
                    totalAttempts, uniquePatchesEvaluated, oscillationRejections, rollbackTriggered))
                    : digest;
        }
    }

    @FunctionalInterface
    public interface CandidateGenerator {
        Optional<PatchCandidate> generate(int outerRestart, int innerTurn, Optional<CapturedRepair.ClosedLoopFeedback> feedback);
    }

    @FunctionalInterface
    public interface PatchAugmentor {
        List<PatchCandidate> augment(PatchCandidate seedPlausible, int maxVariants);
    }

    @FunctionalInterface
    public interface Tier1SandboxEvaluator {
        SandboxRoundOutcome evaluate(PatchCandidate candidate);
    }

    /**
     * Executes the closed-loop campaign (APR {@code m<=5 x n<=3} or Architectural Refactoring {@code <=12}
     * with pre-edit characterization lock + Oscillation Guard + Patch Augmentation + 4-Gate Ranking +
     * automatic rollback on budget exhaustion).
     */
    public static CampaignResult execute(
            SnapshotIdentity snapshot,
            LoopConfig config,
            Optional<QmoodQualityModel.QmoodSnapshot> qmoodBefore,
            boolean preEditProbesLocked,
            CandidateGenerator generator,
            PatchAugmentor augmentor,
            Tier1SandboxEvaluator sandboxEvaluator,
            Runnable rollbackHandler) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(generator, "generator");
        Objects.requireNonNull(sandboxEvaluator, "sandboxEvaluator");

        var seenPatchDigests = new LinkedHashSet<ContentDigest>();
        var plausibleEvaluations = new ArrayList<FourGateEvaluation>();
        var gaps = new ArrayList<CapabilityGapRecord>();
        int totalAttempts = 0;
        int oscillationRejections = 0;

        PatchCandidate firstPlausible = null;

        if (config.mode() == LoopMode.APR) {
            outerLoop:
            for (int outer = 1; outer <= config.maxOuterRestarts(); outer++) {
                Optional<CapturedRepair.ClosedLoopFeedback> feedback = Optional.empty();
                for (int inner = 1; inner <= config.maxInnerTurns(); inner++) {
                    if (totalAttempts >= config.totalPatchBudget()) break outerLoop;
                    Optional<PatchCandidate> candOpt = generator.generate(outer, inner, feedback);
                    if (candOpt.isEmpty()) break;
                    totalAttempts++;
                    PatchCandidate cand = candOpt.orElseThrow();
                    if (!seenPatchDigests.add(cand.patchDigest())) {
                        oscillationRejections++;
                        feedback = Optional.of(new CapturedRepair.ClosedLoopFeedback(
                                totalAttempts, 1, 1, List.of(),
                                "OSCILLATION_GUARD: patchDigest " + cand.patchDigest().value() + " already failed; produce a distinct fix.",
                                List.of(GAP_OSCILLATION_DETECTED), List.of(), List.of()));
                        continue;
                    }
                    SandboxRoundOutcome outcome = sandboxEvaluator.evaluate(cand);
                    if (outcome.plausible()) {
                        firstPlausible = cand;
                        plausibleEvaluations.add(evaluateFourGates(cand, outcome, qmoodBefore, config.qmoodUnderstandabilityFloorPercent()));
                        break outerLoop;
                    }
                    feedback = Optional.of(outcome.toClosedLoopFeedback(totalAttempts));
                }
            }
        } else {
            // ARCHITECTURAL_REFACTORING mode: <= 12 closed-loop iterations (RefAgent Fig. 8 saturation)
            Optional<CapturedRepair.ClosedLoopFeedback> feedback = Optional.empty();
            for (int iter = 1; iter <= config.maxRefactoringIterations(); iter++) {
                if (totalAttempts >= config.totalPatchBudget()) break;
                Optional<PatchCandidate> candOpt = generator.generate(1, iter, feedback);
                if (candOpt.isEmpty()) break;
                totalAttempts++;
                PatchCandidate cand = candOpt.orElseThrow();
                if (!seenPatchDigests.add(cand.patchDigest())) {
                    oscillationRejections++;
                    feedback = Optional.of(new CapturedRepair.ClosedLoopFeedback(
                            iter, 1, 1, List.of(),
                            "OSCILLATION_GUARD: patchDigest " + cand.patchDigest().value() + " repeated; explore alternative decomposition.",
                            List.of(GAP_OSCILLATION_DETECTED), List.of(), List.of()));
                    continue;
                }
                SandboxRoundOutcome outcome = sandboxEvaluator.evaluate(cand);
                if (outcome.plausible()) {
                    FourGateEvaluation eval = evaluateFourGates(cand, outcome, qmoodBefore, config.qmoodUnderstandabilityFloorPercent());
                    plausibleEvaluations.add(eval);
                    if (eval.allFourGatesPassed()) {
                        firstPlausible = cand;
                        break;
                    }
                    // Plausible compile+test but failed deterministic quality/purity/Spring gate -> feed back!
                    feedback = Optional.of(new CapturedRepair.ClosedLoopFeedback(
                            iter, 0, 0, List.of(),
                            "DETERMINISTIC_GATE_REJECTED: " + String.join("; ", eval.gateFailures()),
                            eval.gateFailures(), List.of(), List.of()));
                } else {
                    feedback = Optional.of(outcome.toClosedLoopFeedback(iter));
                }
            }
        }

        // Phase 3: Patch Augmentation (CausalRepair Sec. 3.4: up to k=5..10 variants within totalPatchBudget <= 25)
        if (firstPlausible != null && augmentor != null && config.augmentationVariants() > 0) {
            int remaining = Math.min(config.augmentationVariants(), config.totalPatchBudget() - totalAttempts);
            if (remaining > 0) {
                List<PatchCandidate> variants = augmentor.augment(firstPlausible, remaining);
                if (variants != null) {
                    int evaluatedAugmentations = 0;
                    for (PatchCandidate v : variants) {
                        if (evaluatedAugmentations >= remaining || totalAttempts >= config.totalPatchBudget()) break;
                        if (v == null) continue;
                        if (!seenPatchDigests.add(v.patchDigest())) {
                            oscillationRejections++;
                            continue;
                        }
                        evaluatedAugmentations++;
                        totalAttempts++;
                        SandboxRoundOutcome vOut = sandboxEvaluator.evaluate(v);
                        if (vOut.plausible()) {
                            plausibleEvaluations.add(evaluateFourGates(
                                    v, vOut, qmoodBefore, config.qmoodUnderstandabilityFloorPercent()));
                        }
                    }
                }
            }
        }

        List<FourGateEvaluation> ranked = rankCandidates(plausibleEvaluations);
        Optional<FourGateEvaluation> winner = ranked.stream().filter(FourGateEvaluation::allFourGatesPassed).findFirst();

        boolean rollbackTriggered = false;
        if (winner.isEmpty()) {
            if (rollbackHandler != null) {
                rollbackHandler.run();
                rollbackTriggered = true;
            }
            String reason = "Closed-loop " + config.mode().name() + " exhausted budget after "
                    + totalAttempts + " attempt(s) (" + seenPatchDigests.size() + " unique patch(es), "
                    + oscillationRejections + " oscillation rejection(s)) without a 4-gate certified winner.";
            var subject = new EvidenceSubject(EvidenceSubject.Kind.REPOSITORY, snapshot.value());
            var obs = ProviderObservationReference.create(
                    PROVIDER, "repair.closed-loop-budget",
                    ContentDigest.sha256Utf8(reason),
                    ContentDigest.sha256Utf8(GAP_REPAIR_BUDGET_EXHAUSTED + "|" + reason));
            var req = new EvidenceRequirement(
                    EvidenceRequirement.Kind.ISOLATED_BUILD_OUTPUT, "repair.certified-candidate",
                    List.of(subject), EvidenceRequirement.AuthorizationClass.ISOLATED_EXECUTION,
                    List.of(reason));
            gaps.add(CapabilityGapRecord.create(
                    GAP_CATALOG,
                    new EvidenceContext(snapshot, Optional.empty()),
                    PROVIDER,
                    "repair.closed-loop",
                    GAP_REPAIR_BUDGET_EXHAUSTED,
                    subject,
                    List.of(),
                    List.of(obs),
                    List.of(req),
                    List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.POLICY, "repair.proposal")),
                    List.of(),
                    List.of(),
                    List.of(reason)));
        }

        return new CampaignResult(
                config.mode(),
                winner.isPresent(),
                winner,
                ranked,
                totalAttempts,
                seenPatchDigests.size(),
                oscillationRejections,
                rollbackTriggered,
                preEditProbesLocked,
                gaps,
                null);
    }

    /** Evaluates Evo's 4 Deterministic Gates on a single plausible candidate patch. */
    public static FourGateEvaluation evaluateFourGates(
            PatchCandidate candidate,
            SandboxRoundOutcome outcome,
            Optional<QmoodQualityModel.QmoodSnapshot> qmoodBefore,
            double understandabilityFloorPercent) {
        var failures = new ArrayList<String>();

        // Gate 1: SpringPreservationChecker == PRESERVED (or NOT_APPLICABLE when non-Spring)
        boolean springOk = true;
        if (outcome.springPreservation().isPresent()) {
            var sp = outcome.springPreservation().orElseThrow();
            springOk = sp.status() != SpringPreservationChecker.Status.DRIFT_DETECTED;
            if (!springOk) {
                failures.add("SPRING_PRESERVATION_" + sp.status().name() + ": " + sp.summary());
            }
        }

        // Gate 2: BehavioralDiffService == EQUIVALENT_ON_BOUNDED_CHECKS (UNKNOWN_UNTESTED honestly preserved)
        BehavioralDiffService.BehavioralVerdict behVerdict = outcome.behavioralDiff()
                .map(BehavioralDiffService.BehavioralDiffReport::verdict)
                .orElse(BehavioralDiffService.BehavioralVerdict.UNKNOWN_UNTESTED);
        boolean behOk = behVerdict != BehavioralDiffService.BehavioralVerdict.BEHAVIOR_CHANGED;
        if (!behOk) {
            failures.add("BEHAVIORAL_REGRESSION: " + outcome.behavioralDiff().orElseThrow().summary());
        }

        // Gate 3: QMOOD Understandability (>= -2.0%) & Tarjan SCC Non-Regression Guard
        boolean qmoodOk = true;
        Optional<QmoodQualityModel.GuardResult> qmoodGuard = Optional.empty();
        if (qmoodBefore != null && qmoodBefore.isPresent() && outcome.qmoodAfter().isPresent()) {
            var guard = QmoodQualityModel.evaluateNonRegression(
                    qmoodBefore.orElseThrow(), outcome.qmoodAfter().orElseThrow(), understandabilityFloorPercent);
            qmoodGuard = Optional.of(guard);
            qmoodOk = guard.passed();
            if (!qmoodOk) {
                failures.addAll(guard.violations());
            }
        }

        // Gate 4: AstRefactoringPurityVerifier Dual-Gate (WP4.3)
        boolean astPurityOk = true;
        if (outcome.astPurity().isPresent()) {
            var ap = outcome.astPurity().orElseThrow();
            astPurityOk = ap.passed();
            if (!astPurityOk) {
                failures.addAll(ap.violations());
            }
        }

        boolean allFour = springOk && behOk && qmoodOk && astPurityOk;
        return new FourGateEvaluation(
                candidate, springOk, behOk, behVerdict, qmoodOk, qmoodGuard,
                astPurityOk, outcome.astPurity(), allFour, failures);
    }

    /**
     * Deterministically ranks plausible candidates ({@code I2}):
     * all-4-gates-passed -> deterministic OpenRewrite recipe -> behavioral PRESERVED over UNKNOWN_UNTESTED
     * -> higher QMOOD Understandability delta -> smaller edit diff -> canonical patchDigest tie-breaker.
     */
    public static List<FourGateEvaluation> rankCandidates(Collection<FourGateEvaluation> evaluations) {
        var list = new ArrayList<FourGateEvaluation>(evaluations == null ? List.of() : evaluations);
        list.sort((a, b) -> {
            int c = Boolean.compare(b.allFourGatesPassed(), a.allFourGatesPassed());
            if (c != 0) return c;
            c = Boolean.compare(b.candidate().deterministicRecipe(), a.candidate().deterministicRecipe());
            if (c != 0) return c;
            c = Boolean.compare(
                    b.behavioralVerdict() == BehavioralDiffService.BehavioralVerdict.EQUIVALENT_ON_BOUNDED_CHECKS,
                    a.behavioralVerdict() == BehavioralDiffService.BehavioralVerdict.EQUIVALENT_ON_BOUNDED_CHECKS);
            if (c != 0) return c;
            double ua = a.qmoodGuard().map(QmoodQualityModel.GuardResult::understandabilityDeltaPercent).orElse(0.0);
            double ub = b.qmoodGuard().map(QmoodQualityModel.GuardResult::understandabilityDeltaPercent).orElse(0.0);
            c = Double.compare(ub, ua);
            if (c != 0) return c;
            c = Integer.compare(a.candidate().changedLineCount(), b.candidate().changedLineCount());
            if (c != 0) return c;
            return a.candidate().patchDigest().value().compareTo(b.candidate().patchDigest().value());
        });
        return List.copyOf(list);
    }
}
