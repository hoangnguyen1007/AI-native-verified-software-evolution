package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.ingestion.IngestionFixtures;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.binding.BindingEvidence;
import com.evolution.analysis.spring.binding.InjectionBindingPlan;
import com.evolution.analysis.spring.registration.RegistrationEvent;
import com.evolution.analysis.spring.truth.*;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import com.evolution.analysis.spring.universal.FrameworkGeneration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.regStep;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.rp;
import static com.evolution.analysis.spring.condition.InjectionBindingsTest.*;
import static com.evolution.analysis.spring.condition.LogicalValue.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalCdclAndCoiSlicerTest {

    private static final ContentDigest ARTIFACT = ContentDigest.sha256Utf8("authored-s4s5-evaluator");

    @Test
    void cdclMatchesExhaustiveOracleAcross500RandomFormulas() {
        var random = new Random(20261127L);
        for (int trial = 0; trial < 500; trial++) {
            int varCount = 4 + (trial % 3); // 4..6 vars (<= 64 worlds for exhaustive oracle)
            var vars = new ArrayList<Formula>(varCount);
            for (int v = 0; v < varCount; v++) {
                vars.add(variable("v" + v));
            }
            int clauseCount = 6 + (trial % 16);
            var clauses = new ArrayList<Formula>(clauseCount);
            for (int c = 0; c < clauseCount; c++) {
                int len = 1 + random.nextInt(3);
                var lits = new ArrayList<Formula>(len);
                for (int l = 0; l < len; l++) {
                    Formula v = vars.get(random.nextInt(varCount));
                    lits.add(random.nextBoolean() ? v : not(v));
                }
                clauses.add(len == 1 ? lits.getFirst() : or(lits));
            }
            Formula formula = and(clauses);

            boolean exhaustiveSat = false;
            int worlds = 1 << varCount;
            for (int mask = 0; mask < worlds; mask++) {
                var assign = new HashMap<String, Boolean>(varCount * 2);
                for (int v = 0; v < varCount; v++) {
                    assign.put("v" + v, (mask & (1 << v)) != 0);
                }
                if (formula.evaluate(assign)) {
                    exhaustiveSat = true;
                    break;
                }
            }

            var answer = IncrementalCdclReasoner.INSTANCE.solve(new Query(formula, Limits.defaults()));
            assertEquals(exhaustiveSat ? SATISFIABLE : UNSATISFIABLE, answer.status(),
                    "Mismatch on trial " + trial);
            if (exhaustiveSat) {
                assertTrue(formula.evaluate(answer.witness()), "Witness invalid on trial " + trial);
            }
            assertEquals(answer, SatConfigurationReasoner.INSTANCE.solve(new Query(formula, Limits.defaults())));
        }
    }

    @Test
    void cdclSolvesPigeonholeUnsatAndLargeIncrementalBlockingSessionFast() {
        long t0 = System.nanoTime();
        // PHP(4, 3): 4 pigeons into 3 holes -> 12 vars, classic non-trivial CDCL conflict learning benchmark
        int pigeons = 4;
        int holes = 3;
        Formula[][] x = new Formula[pigeons][holes];
        for (int p = 0; p < pigeons; p++) {
            for (int h = 0; h < holes; h++) {
                x[p][h] = variable("p" + p + "_h" + h);
            }
        }
        var clauses = new ArrayList<Formula>();
        for (int p = 0; p < pigeons; p++) {
            clauses.add(or(List.of(x[p][0], x[p][1], x[p][2])));
        }
        for (int h = 0; h < holes; h++) {
            for (int p1 = 0; p1 < pigeons; p1++) {
                for (int p2 = p1 + 1; p2 < pigeons; p2++) {
                    clauses.add(or(not(x[p1][h]), not(x[p2][h])));
                }
            }
        }
        Formula php = and(clauses);
        var phpAns = IncrementalCdclReasoner.INSTANCE.solve(new Query(php, Limits.defaults()));
        assertEquals(UNSATISFIABLE, phpAns.status());

        // Incremental session with 120 vars, assumptions, and repeated blocking clauses
        var session = new IncrementalCdclReasoner.Session(Limits.defaults());
        var vars = java.util.stream.IntStream.range(0, 120).mapToObj(i -> variable("c" + i)).toList();
        var chain = new ArrayList<Formula>();
        chain.add(or(not(vars.get(0)), vars.get(2)));
        for (int i = 1; i < vars.size() - 1; i++) {
            chain.add(or(not(vars.get(i)), vars.get(i + 1)));
        }
        session.addRoot(or(vars.get(0), vars.get(1)));
        session.addRoot(and(chain));
        int l0 = session.encode(vars.get(0));
        int l1 = session.encode(vars.get(1));

        // Assume c0 = false -> must force c1 = true and all c1..c119 = true
        var underNegC0 = session.solveIncremental(ContentDigest.sha256Utf8("assump-neg-c0"), -l0);
        assertEquals(SATISFIABLE, underNegC0.status());
        assertFalse(underNegC0.witness().get("c0"));
        assertTrue(underNegC0.witness().get("c119"));

        // Block c1 = true AND assume c0 = false -> UNSAT
        session.addBlockingClause(-l1);
        var conflictUnderBoth = session.solveIncremental(ContentDigest.sha256Utf8("both-blocked"), -l0);
        assertEquals(UNSATISFIABLE, conflictUnderBoth.status());

        // Without -l0 assumption -> c0 = true, c1 = false, c119 = true
        var forcedC0 = session.solveIncremental(ContentDigest.sha256Utf8("free-c0"));
        assertEquals(SATISFIABLE, forcedC0.status());
        assertTrue(forcedC0.witness().get("c0"));
        assertFalse(forcedC0.witness().get("c1"));
        assertTrue(forcedC0.witness().get("c119"));

        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(elapsedMs < 2_000, "CDCL PHP(4,3) + 120-var incremental session took " + elapsedMs + "ms");
    }

    @Test
    void policyIdentifierReflectsCdclV2AcrossBothReasonerFacades() {
        var expected = new VersionedIdentifier("spring.configuration-reasoner", "cdcl-2wl-1uip-v2");
        assertEquals(expected, SatConfigurationReasoner.INSTANCE.policy());
        assertEquals(expected, IncrementalCdclReasoner.INSTANCE.policy());
    }

    @Test
    void unitImpliedAssumptionChainAndBackjumpAcrossAssumptionLevelsRemainSound() {
        // Part A: 3-assumption chain where assumption #1 (a1) unit-propagates #2 (a2) at decision level 1,
        // and assumption #3 (a3) opens decision level 2 (< assumptionPtr == 3) and triggers a conflict.
        Formula a1 = variable("a1");
        Formula a2 = variable("a2");
        Formula a3 = variable("a3");
        Formula u = variable("u");
        Formula freeVar = variable("freeVar");
        Formula conflictFormula = and(
                or(not(a1), a2),
                or(not(a2), not(a3), u),
                or(not(a1), not(a3), not(u)),
                or(a1, a3, freeVar));

        var conflictSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        conflictSession.addRoot(conflictFormula);
        int lA1 = conflictSession.encode(a1);
        int lA2 = conflictSession.encode(a2);
        int lA3 = conflictSession.encode(a3);

        // [a1, a2, a3]: a1 propagates a2 at level 1; a3 conflicts at level 2 < assumptionPtr(3) -> UNSAT
        var unsatChain = conflictSession.solveIncremental(ContentDigest.sha256Utf8("chain-3-unsat"), lA1, lA2, lA3);
        assertEquals(UNSATISFIABLE, unsatChain.status());

        // Session must remain unpoisoned (topLevelUnsat must NOT be set) for subsequent assumption queries
        var satFlipA3 = conflictSession.solveIncremental(ContentDigest.sha256Utf8("chain-3-flip-a3"), lA1, lA2, -lA3);
        assertEquals(SATISFIABLE, satFlipA3.status());
        assertTrue(satFlipA3.witness().get("a1"));
        assertTrue(satFlipA3.witness().get("a2"));
        assertFalse(satFlipA3.witness().get("a3"));
        assertTrue(conflictFormula.evaluate(satFlipA3.witness()));

        var satFlipA1 = conflictSession.solveIncremental(ContentDigest.sha256Utf8("chain-3-flip-a1"), -lA1, lA2, lA3);
        assertEquals(SATISFIABLE, satFlipA1.status());
        assertFalse(satFlipA1.witness().get("a1"));
        assertTrue(satFlipA1.witness().get("a2"));
        assertTrue(satFlipA1.witness().get("a3"));
        assertTrue(conflictFormula.evaluate(satFlipA1.witness()));

        // Part B: 3-assumption chain [a1, a2, a3] where a1 unit-propagates a2 (maxAssumptionDecisionLevel = 2,
        // assumptionPtr = 3), followed by free search at level 3 whose 1-UIP learned clause (x | -a1) backjumps
        // to btLevel = 1 < maxAssumptionDecisionLevel (2), requiring assumptionPtr to unwind to index 2 (a3).
        Formula x = variable("x");
        Formula y = variable("y");
        Formula z = variable("z");
        Formula w = variable("w");
        Formula backjumpSatFormula = and(
                or(not(a1), a2),
                or(not(a1), x, y),
                or(not(a1), x, not(y), z),
                or(not(a1), x, not(y), not(z)),
                or(not(a3), not(x), w));

        var btSatSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        btSatSession.addRoot(backjumpSatFormula);
        int bA1 = btSatSession.encode(a1);
        int bA2 = btSatSession.encode(a2);
        int bA3 = btSatSession.encode(a3);
        var btSatAns = btSatSession.solveIncremental(ContentDigest.sha256Utf8("bt-below-assump-sat"), bA1, bA2, bA3);
        assertEquals(SATISFIABLE, btSatAns.status());
        assertTrue(btSatAns.witness().get("a1"));
        assertTrue(btSatAns.witness().get("a2"));
        assertTrue(btSatAns.witness().get("a3"));
        assertTrue(btSatAns.witness().get("x"));
        assertTrue(btSatAns.witness().get("w"));
        assertTrue(backjumpSatFormula.evaluate(btSatAns.witness()));

        // Part B2: Same backjump to btLevel = 1 asserts x=true, which then unit-propagates -a3 at level 1 -> UNSAT
        Formula backjumpUnsatFormula = and(
                or(not(a1), a2),
                or(not(a1), x, y),
                or(not(a1), x, not(y), z),
                or(not(a1), x, not(y), not(z)),
                or(not(x), not(a3)));

        var btUnsatSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        btUnsatSession.addRoot(backjumpUnsatFormula);
        int uA1 = btUnsatSession.encode(a1);
        int uA2 = btUnsatSession.encode(a2);
        int uA3 = btUnsatSession.encode(a3);
        var btUnsatAns = btUnsatSession.solveIncremental(ContentDigest.sha256Utf8("bt-below-assump-unsat"), uA1, uA2, uA3);
        assertEquals(UNSATISFIABLE, btUnsatAns.status());

        // Subsequent query on same session with -a3 must succeed cleanly
        var btRecover = btUnsatSession.solveIncremental(ContentDigest.sha256Utf8("bt-below-assump-recover"), uA1, uA2, -uA3);
        assertEquals(SATISFIABLE, btRecover.status());
        assertTrue(btRecover.witness().get("a1"));
        assertTrue(btRecover.witness().get("a2"));
        assertFalse(btRecover.witness().get("a3"));
        assertTrue(backjumpUnsatFormula.evaluate(btRecover.witness()));
    }

    @Test
    void cdclMatchesExhaustiveOracleAcross500RandomCnfFormulasPlainAndWithAssumptions() {
        var random = new Random(20261128L);
        for (int trial = 0; trial < 500; trial++) {
            int varCount = 4 + (trial % 3); // 4..6 vars (16..64 exhaustive worlds)
            var vars = new ArrayList<Formula>(varCount);
            for (int v = 0; v < varCount; v++) {
                vars.add(variable("v" + v));
            }
            int clauseCount = 6 + (trial % 15);
            var clauses = new ArrayList<Formula>(clauseCount);
            for (int c = 0; c < clauseCount; c++) {
                int len = 1 + random.nextInt(3);
                var lits = new ArrayList<Formula>(len);
                for (int l = 0; l < len; l++) {
                    Formula v = vars.get(random.nextInt(varCount));
                    lits.add(random.nextBoolean() ? v : not(v));
                }
                clauses.add(len == 1 ? lits.getFirst() : or(lits));
            }
            Formula formula = and(clauses);

            int worlds = 1 << varCount;
            boolean[] worldSat = new boolean[worlds];
            boolean plainOracleSat = false;
            for (int mask = 0; mask < worlds; mask++) {
                var assign = new HashMap<String, Boolean>(varCount * 2);
                for (int v = 0; v < varCount; v++) {
                    assign.put("v" + v, (mask & (1 << v)) != 0);
                }
                if (formula.evaluate(assign)) {
                    worldSat[mask] = true;
                    plainOracleSat = true;
                }
            }

            // 1. Plain solve agreement & determinism
            var plainAns = IncrementalCdclReasoner.INSTANCE.solve(new Query(formula, Limits.defaults()));
            assertEquals(plainOracleSat ? SATISFIABLE : UNSATISFIABLE, plainAns.status(),
                    "Plain mismatch on trial " + trial);
            if (plainOracleSat) {
                assertTrue(formula.evaluate(plainAns.witness()), "Plain witness invalid on trial " + trial);
            }
            assertEquals(plainAns, SatConfigurationReasoner.INSTANCE.solve(new Query(formula, Limits.defaults())),
                    "Determinism / facade parity mismatch on trial " + trial);

            // 2. Incremental session solve across 5 random assumption lists per formula (2,500 total)
            var session = new IncrementalCdclReasoner.Session(Limits.defaults());
            session.addRoot(formula);
            int[] varLits = new int[varCount];
            for (int v = 0; v < varCount; v++) {
                varLits[v] = session.encode(vars.get(v));
            }
            for (int q = 0; q < 5; q++) {
                int assumpCount = random.nextInt(5); // 0..4 assumptions (includes repeats & contradictions)
                int[] assumpVarIdx = new int[assumpCount];
                boolean[] assumpSign = new boolean[assumpCount];
                int[] assumpLits = new int[assumpCount];
                for (int a = 0; a < assumpCount; a++) {
                    int vi = random.nextInt(varCount);
                    boolean pos = random.nextBoolean();
                    assumpVarIdx[a] = vi;
                    assumpSign[a] = pos;
                    assumpLits[a] = pos ? varLits[vi] : -varLits[vi];
                }

                boolean expectedSat = false;
                for (int mask = 0; mask < worlds; mask++) {
                    if (!worldSat[mask]) {
                        continue;
                    }
                    boolean matchesAll = true;
                    for (int a = 0; a < assumpCount; a++) {
                        boolean bit = (mask & (1 << assumpVarIdx[a])) != 0;
                        if (bit != assumpSign[a]) {
                            matchesAll = false;
                            break;
                        }
                    }
                    if (matchesAll) {
                        expectedSat = true;
                        break;
                    }
                }

                ContentDigest qid = ContentDigest.sha256Utf8("prop-t" + trial + "-q" + q);
                var ans = session.solveIncremental(qid, assumpLits);
                assertEquals(expectedSat ? SATISFIABLE : UNSATISFIABLE, ans.status(),
                        "Assumption mismatch on trial " + trial + " query " + q);
                if (expectedSat) {
                    assertTrue(formula.evaluate(ans.witness()),
                            "Assumption witness fails formula on trial " + trial + " query " + q);
                    for (int a = 0; a < assumpCount; a++) {
                        assertEquals(assumpSign[a], ans.witness().get("v" + assumpVarIdx[a]),
                                "Assumption literal violated on trial " + trial + " query " + q);
                    }
                }
            }
        }
    }

    @Test
    void binaryMaxHeapLubyRestartsAndLbdReductionStaySoundAndDeterministicUnderAssumptions() {
        // Verify exact Luby restart sequence values
        int[] expectedLuby = {1, 1, 2, 1, 1, 2, 4, 1, 1, 2, 1, 1, 2, 4, 8};
        for (int i = 0; i < expectedLuby.length; i++) {
            assertEquals(expectedLuby[i], IncrementalCdclReasoner.luby(i), "Luby mismatch at index " + i);
        }

        // Guard PHP(7, 6) behind assumption a3 with a1 -> a2 so solving under [a1, a2, a3]
        // exercises Luby restarts (base = 100) and LBD learned-clause reduction (> 200 learnts)
        // while strictly preserving active assumption levels and retaining glue clauses (LBD <= 2).
        Formula a1 = variable("guard_a1");
        Formula a2 = variable("guard_a2");
        Formula a3 = variable("guard_a3");
        int pigeons = 7;
        int holes = 6;
        Formula[][] x = new Formula[pigeons][holes];
        for (int p = 0; p < pigeons; p++) {
            for (int h = 0; h < holes; h++) {
                x[p][h] = variable("php76_" + p + "_" + h);
            }
        }
        var clauses = new ArrayList<Formula>();
        clauses.add(or(not(a1), a2));
        for (int p = 0; p < pigeons; p++) {
            var row = new ArrayList<Formula>(holes + 1);
            row.add(not(a3));
            for (int h = 0; h < holes; h++) {
                row.add(x[p][h]);
            }
            clauses.add(or(row));
        }
        for (int h = 0; h < holes; h++) {
            for (int p1 = 0; p1 < pigeons; p1++) {
                for (int p2 = p1 + 1; p2 < pigeons; p2++) {
                    clauses.add(or(not(a3), not(x[p1][h]), not(x[p2][h])));
                }
            }
        }
        Formula guardedPhp = and(clauses);

        var session1 = new IncrementalCdclReasoner.Session(Limits.defaults());
        session1.addRoot(guardedPhp);
        int l1 = session1.encode(a1);
        int l2 = session1.encode(a2);
        int l3 = session1.encode(a3);
        var unsatAns1 = session1.solveIncremental(ContentDigest.sha256Utf8("php76-assump-1"), l1, l2, l3);
        assertEquals(UNSATISFIABLE, unsatAns1.status());
        assertTrue(session1.restarts() >= 1,
                () -> "Expected Luby restarts (>= 1) on guarded PHP(7,6), got " + session1.restarts()
                        + " (conflicts=" + session1.conflicts() + ")");
        assertTrue(session1.learnedClauseReductions() >= 1,
                () -> "Expected LBD learned-clause reduction (>= 1) on guarded PHP(7,6), got "
                        + session1.learnedClauseReductions() + " (conflicts=" + session1.conflicts() + ")");
        assertTrue(session1.prunedLearnedClauses() >= 1,
                () -> "Expected LBD reduction to prune high-LBD clauses, pruned=" + session1.prunedLearnedClauses());
        assertTrue(session1.learntClauseCount() < session1.conflicts(),
                () -> "Expected retained learnt clauses (" + session1.learntClauseCount()
                        + ") < total conflicts (" + session1.conflicts() + ")");

        // Same session must still satisfy [a1, a2, -a3] cleanly after restarts & LBD reductions
        var satAfterReduction = session1.solveIncremental(ContentDigest.sha256Utf8("php76-flip-a3"), l1, l2, -l3);
        assertEquals(SATISFIABLE, satAfterReduction.status());
        assertTrue(satAfterReduction.witness().get("guard_a1"));
        assertTrue(satAfterReduction.witness().get("guard_a2"));
        assertFalse(satAfterReduction.witness().get("guard_a3"));
        assertTrue(guardedPhp.evaluate(satAfterReduction.witness()));

        // Strict I2 determinism: independent fresh session produces bit-identical Answer & search trajectory
        var session2 = new IncrementalCdclReasoner.Session(Limits.defaults());
        session2.addRoot(guardedPhp);
        int m1 = session2.encode(a1);
        int m2 = session2.encode(a2);
        int m3 = session2.encode(a3);
        var unsatAns2 = session2.solveIncremental(ContentDigest.sha256Utf8("php76-assump-1"), m1, m2, m3);
        assertEquals(unsatAns1, unsatAns2);
        assertEquals(session1.conflicts(), session2.conflicts());
        assertEquals(session1.restarts(), session2.restarts());
        assertEquals(session1.prunedLearnedClauses(), session2.prunedLearnedClauses());
    }

    @Test
    void kleeneCubeGeneralizationCompressesDontCareSignaturesWithoutLoss() throws Exception {
        // 12 profile dimensions (2^12 = 4096 total worlds!), but condition only needs @Profile("dev")
        // -> Kleene cube generalization collapses 4096 worlds into ONLY 2 signatures cleanly!
        var source = fixture("@Profile(\"dev\")");
        var original = source.space();
        var domains = new ArrayList<>(original.domains());
        var baselineValues = new TreeMap<FiniteDomain.Variable, FiniteDomain.Value>();
        baselineValues.put(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(false));
        for (int i = 0; i < 11; i++) {
            var extra = new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "unrelated-" + i);
            domains.add(new FiniteDomain(extra, List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)),
                    source.source().evidence()));
            baselineValues.put(extra, FiniteDomain.Value.bool(false));
        }
        var expandedSpace = new ConfigurationSpace(
                original.buildContext(), original.repositoryEnvelope(), original.deploymentEnvelope(),
                domains, original.constraints(), original.precedencePolicy(), original.profilePolicy(),
                original.abstractionVersion(),
                new ConfigurationSpace.FeasibilityPolicy(original.feasibilityPolicy().version(),
                        new ConfigurationSpace.Limits(64, 4096, Long.MAX_VALUE, 10_000, 100_000, 256),
                        original.feasibilityPolicy().evidence()));

        var event = event(source, "token", RegistrationEvent.Kind.CONFIGURATION, null,
                RegistrationEvent.RequiredPhase.ORDINARY);
        var discovery = plan(source, List.of(event));
        var registration = rp(source, discovery,
                List.of(regStep("register-token", event, Optional.empty(), source.source().evidence())),
                List.of("register-token"));
        var bridge = new InjectionBindingsTest.Fixture(source, List.of(event), registration);
        var dependency = dependency(bridge, InjectionBindingPlan.Shape.SINGLE,
                InjectionBindingPlan.Name.absent(), InjectionBindingPlan.Required.OPTIONAL);
        var match = match(bridge, dependency, "token", BindingEvidence.Lane.DIRECT,
                LogicalValue.TRUE, LogicalValue.TRUE, LogicalValue.TRUE, LogicalValue.TRUE);
        var binding = bindingPlan(bridge, List.of(dependency), ordinary(bridge), List.of(match));

        var request = new TruthRegionEvaluation.Request(
                binding,
                ConditionModel.create(expandedSpace, source.lowering().occurrences()),
                source.lowering().semantics(),
                new ConfigurationAssignment(baselineValues, source.source().evidence()),
                IncrementalCdclReasoner.INSTANCE,
                TruthRegionEvaluation.Limits.conservative(),
                ARTIFACT);

        var symbolic = TruthRegionEvaluation.evaluateSymbolic(request, 8, Limits.defaults(), true);
        assertTrue(symbolic.signaturesComplete(), symbolic.issues().toString());
        assertEquals(2, symbolic.traces().size(),
                () -> "Kleene cube should collapse 4096 worlds into 2 traces, got " + symbolic.traces().size());
        assertTrue(symbolic.traces().stream().allMatch(SymbolicTruthRegions.Trace::replayed));
        assertTrue(symbolic.regions().stream().allMatch(r -> r.classification() == TruthRegionEvaluation.Classification.MAY));
    }

    @Test
    void productionDoubleEvalFlagControlsReplayHonestlyWithoutAlteringClassifications() throws Exception {
        var source = fixture("@Profile(\"dev\")");
        var event = event(source, "token", RegistrationEvent.Kind.CONFIGURATION, null,
                RegistrationEvent.RequiredPhase.ORDINARY);
        var discovery = plan(source, List.of(event));
        var registration = rp(source, discovery,
                List.of(regStep("register-token", event, Optional.empty(), source.source().evidence())),
                List.of("register-token"));
        var bridge = new InjectionBindingsTest.Fixture(source, List.of(event), registration);
        var dependency = dependency(bridge, InjectionBindingPlan.Shape.SINGLE,
                InjectionBindingPlan.Name.absent(), InjectionBindingPlan.Required.OPTIONAL);
        var match = match(bridge, dependency, "token", BindingEvidence.Lane.DIRECT,
                LogicalValue.TRUE, LogicalValue.TRUE, LogicalValue.TRUE, LogicalValue.TRUE);
        var binding = bindingPlan(bridge, List.of(dependency), ordinary(bridge), List.of(match));
        var baseline = new ConfigurationAssignment(
                Map.of(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"), FiniteDomain.Value.bool(false)),
                source.source().evidence());
        var request = new TruthRegionEvaluation.Request(
                binding,
                ConditionModel.create(source.space(), source.lowering().occurrences()),
                source.lowering().semantics(),
                baseline,
                SatConfigurationReasoner.INSTANCE,
                TruthRegionEvaluation.Limits.conservative(),
                ARTIFACT);

        var verified = TruthRegionEvaluation.evaluateSymbolic(request, 4, Limits.defaults(), true);
        var fastProd = TruthRegionEvaluation.evaluateSymbolic(request, 4, Limits.defaults(), false);

        assertTrue(verified.traces().stream().allMatch(SymbolicTruthRegions.Trace::replayed));
        assertTrue(fastProd.traces().stream().noneMatch(SymbolicTruthRegions.Trace::replayed));
        assertEquals(
                verified.regions().stream().map(r -> List.of(r.fact(), r.classification())).toList(),
                fastProd.regions().stream().map(r -> List.of(r.fact(), r.classification())).toList());
        assertTrue(fastProd.gaps().stream().noneMatch(g -> "WITNESS_REPLAY_FAILED".equals(g.reasonCode())));
    }

    @Test
    void coiModelDecompositionExceedsGlobal32DimensionCeilingWithoutTruncatingComponents() throws Exception {
        var build = ConditionLoweringTest.fixture(List.of()).build();
        var config = new ConfigDataIngestion().ingest(
                IngestionFixtures.inputs(Map.of("src/main/resources/application.properties", "")),
                ConfigDataIngestion.Policy.defaults());
        var semantics = ConditionEvidenceLowering.semantics(
                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
        var derived = new ConditionEvidence.Derived(List.of(config.identity()),
                new com.evolution.analysis.contract.common.VersionedIdentifier("s4-test", "1"),
                "s4-test");

        // 50 variables partitioned cleanly into 5 independent 10-variable components
        var occurrences = new ArrayList<ConditionOccurrence>();
        int idx = 0;
        for (int comp = 0; comp < 5; comp++) {
            var terms = new ArrayList<ConditionExpression>(10);
            for (int v = 0; v < 10; v++) {
                terms.add(ConditionExpression.atom(semantics,
                        new ConditionExpression.Profile("c" + comp + "-p" + v)));
            }
            occurrences.add(new ConditionOccurrence(
                    ConditionExpression.all(semantics, terms),
                    derived, "cond/comp-" + comp, "BEAN", idx++));
        }

        // Legacy global 32-var builder truncates 18 variables
        var legacyGlobal = RepositoryConfigurationSpaceBuilder.build(build, config, occurrences);
        assertEquals(32, legacyGlobal.space().domains().size());
        assertTrue(legacyGlobal.gaps().stream().anyMatch(g -> "CONFIGURATION_SPACE_DIMENSION_BOUNDED".equals(g.reasonCode())));

        // S4 COI component decomposition preserves all 50 direct variables across 5 component spaces
        // (each component also keeps the ambient "default" profile = 11 domains) with 0 dimension-bounded truncation gaps!
        var sliced = ConeOfInfluenceSlicer.buildComponentSpaces(
                build, config, occurrences, ConeOfInfluenceSlicer.MAX_COMPONENT_VARS);
        assertEquals(5, sliced.size());
        var preservedDirectVars = new HashSet<String>();
        for (var sel : sliced) {
            assertEquals(11, sel.space().domains().size());
            assertTrue(sel.gaps().stream().noneMatch(g -> "CONFIGURATION_SPACE_DIMENSION_BOUNDED".equals(g.reasonCode())),
                    () -> "Unexpected COI dimension truncation gaps: " + sel.gaps());
            for (var domain : sel.space().domains()) {
                if (domain.variable().name().startsWith("c")) {
                    preservedDirectVars.add(domain.variable().name());
                }
            }
        }
        assertEquals(50, preservedDirectVars.size());
    }

    @Test
    void empiricalDirectedCoiCollapsesBoot35UndirectedHub() throws Exception {
        Path bootJar = Path.of(System.getProperty("user.home"),
                ".m2/repository/org/springframework/boot/spring-boot-autoconfigure/3.5.14/spring-boot-autoconfigure-3.5.14.jar");
        Assumptions.assumeTrue(Files.isRegularFile(bootJar), "Offline spring-boot-autoconfigure-3.5.14.jar absent");

        ContentDigest jarDigest = ContentDigest.sha256(Files.readAllBytes(bootJar));
        var entry = new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                "org.springframework.boot:spring-boot-autoconfigure:3.5.14", jarDigest);
        var scan = BytecodeAutoConfigScanner.scan(
                List.of(new BinaryInput(entry, bootJar)),
                FrameworkGeneration.MetadataPolicy.BOTH,
                BytecodeAutoConfigScanner.Limits.defaults());

        var result = ConeOfInfluenceSlicer.sliceStarterGraph(
                scan,
                BytecodeAutoConfigScanner.UserSpringSummary.empty(),
                ConeOfInfluenceSlicer.MAX_COMPONENT_VARS);

        assertTrue(result.totalTargets() >= 100);
        assertTrue(result.directedMaxSliceVars() < result.undirectedMaxComponentVars(),
                () -> "Sound directed COI should reduce undirected hub: undirectedMax="
                        + result.undirectedMaxComponentVars() + " directedMax=" + result.directedMaxSliceVars());
        assertTrue(result.directedMedianSliceVars() <= result.directedMaxSliceVars());

        System.out.println("[S4-EMPIRICAL] targets=" + result.totalTargets()
                + " globalVars=" + result.totalGlobalVars()
                + " undirectedMax=" + result.undirectedMaxComponentVars()
                + " directedMax=" + result.directedMaxSliceVars()
                + " directedMedian=" + result.directedMedianSliceVars()
                + " exceeding64=" + result.slicesExceedingBudget()
                + " elapsedMs=" + result.elapsedMs());
    }

    @Test
    void coiDirectedSlicingIsProvablySoundAgainstFullSatFormulaAcrossAdversarialCases() {
        var jarDigest = ContentDigest.sha256Utf8("adversarial-coi-soundness-jar");
        var ev = new ConditionEvidence.Artifact(jarDigest, ContentDigest.sha256Utf8("entry"), "test.class");

        // --- Adversarial Case 1 (Line 408 bug): Competing same-type @Bean providers with implicit @ConditionalOnMissingBean ---
        var primaryDsBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.PrimaryDsConfig", "primaryDataSource", "()Ljavax/sql/DataSource;",
                "javax.sql.DataSource", List.of("primaryDataSource"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("app.ds.primary.enabled")), false)),
                ev);
        var fallbackDsBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.FallbackDsConfig", "fallbackDataSource", "()Ljavax/sql/DataSource;",
                "javax.sql.DataSource", List.of("fallbackDataSource"), List.of(),
                false, false, false,
                // Implicit @ConditionalOnMissingBean (empty attrs -> defaults to own return type javax.sql.DataSource!)
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                        Map.of(), false)),
                ev);

        // --- Adversarial Case 2 (Line 246 bug): 4-hop @Bean parameter dependency chain with NO @ConditionalOnBean ---
        var hopABean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.HopAConfig", "hopA", "()Lcom/example/TypeA;",
                "com.example.TypeA", List.of("hopA"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("chain.hop.a")), false)),
                ev);
        var hopBBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.HopBConfig", "hopB", "(Lcom/example/TypeA;)Lcom/example/TypeB;",
                "com.example.TypeB", List.of("hopB"), List.of("com.example.TypeA"),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("chain.hop.b")), false)),
                ev);
        var hopCBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.HopCConfig", "hopC", "(Lcom/example/TypeB;)Lcom/example/TypeC;",
                "com.example.TypeC", List.of("hopC"), List.of("com.example.TypeB"),
                false, false, false, List.of(), ev);
        var hopDBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.HopDConfig", "hopD", "(Lcom/example/TypeC;)Lcom/example/TypeD;",
                "com.example.TypeD", List.of("hopD"), List.of("com.example.TypeC"),
                false, false, false, List.of(), ev);

        // --- Adversarial Case 3: Polymorphic subtype producer + @EnableConfigurationProperties + @AutoConfigureAfter ---
        var customStoreBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.CustomStoreConfig", "customStore", "(Lcom/example/StoreProps;)Lcom/example/CustomStore;",
                "com.example.CustomStore", List.of("customStore"), List.of("com.example.StoreProps"),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.context.annotation.Profile",
                        Map.of("value", List.of("(cloud | hybrid) & !airgapped")), false)),
                ev);
        var defaultStoreBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.DefaultStoreConfig", "defaultStore", "()Lcom/example/Store;",
                "com.example.Store", List.of("defaultStore"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                        Map.of("value", List.of("com.example.Store")), false)),
                ev);

        // --- Adversarial Case 4: Outer @Configuration guard + @Import + nested @Configuration ---
        var importedBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.ImportedLeafConfig", "importedService", "()Lcom/example/ImportedService;",
                "com.example.ImportedService", List.of("importedService"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("imported.leaf.on")), false)),
                ev);

        // --- Adversarial Case 5: Directed @AutoConfigureAfter fan-out (siblings MUST NOT leak into each other!) ---
        var hubCoreBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.HubCoreConfig", "hubCore", "()Lcom/example/HubCore;",
                "com.example.HubCore", List.of("hubCore"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("hub.core.on")), false)),
                ev);
        var consumer1Bean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.Consumer1Config", "consumer1", "()Lcom/example/Consumer1;",
                "com.example.Consumer1", List.of("consumer1"), List.of(),
                false, false, false,
                List.of(
                        new BytecodeAutoConfigScanner.ConditionAnnotation(
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
                                Map.of("value", List.of("com.example.HubCore")), false),
                        new BytecodeAutoConfigScanner.ConditionAnnotation(
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                                Map.of("name", List.of("consumer1.on")), false)),
                ev);
        var consumer2Bean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.Consumer2Config", "consumer2", "()Lcom/example/Consumer2;",
                "com.example.Consumer2", List.of("consumer2"), List.of(),
                false, false, false,
                List.of(
                        new BytecodeAutoConfigScanner.ConditionAnnotation(
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnBean",
                                Map.of("value", List.of("com.example.HubCore")), false),
                        new BytecodeAutoConfigScanner.ConditionAnnotation(
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                                Map.of("name", List.of("consumer2.on")), false)),
                ev);

        // --- Unrelated Distractor Configs (must NOT pollute slices of unrelated targets!) ---
        var distractorBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.DistractorConfig", "distractor", "()Lcom/example/Distractor;",
                "com.example.Distractor", List.of("distractor"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("distractor.flag")), false)),
                ev);

        var configs = List.of(
                starterCfg("com.example.PrimaryDsConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(primaryDsBean)),
                starterCfg("com.example.FallbackDsConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.PrimaryDsConfig"), List.of(), Optional.empty())),
                        List.of(fallbackDsBean)),
                starterCfg("com.example.HopAConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(hopABean)),
                starterCfg("com.example.HopBConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(hopBBean)),
                starterCfg("com.example.HopCConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(hopCBean)),
                starterCfg("com.example.HopDConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(hopDBean)),
                starterCfg("com.example.StorePropsOwnerConfig", jarDigest, ev, List.of(), List.of("com.example.StoreProps"), List.of(),
                        List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                                Map.of("name", List.of("store.props.owner.on")), false)),
                        List.of(), List.of()),
                starterCfg("com.example.CustomStoreConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(customStoreBean)),
                starterCfg("com.example.DefaultStoreConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.CustomStoreConfig"), List.of(), Optional.empty())),
                        List.of(defaultStoreBean)),
                starterCfg("com.example.OuterGuardConfig", jarDigest, ev, List.of("com.example.ImportedLeafConfig"), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                                "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                                Map.of("name", List.of("outer.guard.on")), false)),
                        List.of(), List.of()),
                starterCfg("com.example.ImportedLeafConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(importedBean)),
                starterCfg("com.example.HubCoreConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(hubCoreBean)),
                starterCfg("com.example.Consumer1Config", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.HubCoreConfig"), List.of(), Optional.empty())),
                        List.of(consumer1Bean)),
                starterCfg("com.example.Consumer2Config", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.HubCoreConfig"), List.of(), Optional.empty())),
                        List.of(consumer2Bean)),
                starterCfg("com.example.DistractorConfig", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(distractorBean)));

        var scan = new BytecodeAutoConfigScanner.ScanResult(
                ContentDigest.sha256Utf8("adversarial-scan"), 1, 1, configs.size(),
                configs, List.of(), Map.of(), Set.of(), 1L);
        var supertypes = Map.of("com.example.CustomStore", Set.of("com.example.Store"));

        // Test BOTH with explicit supertypes AND with empty supertypes (relying on directed @AutoConfigureAfter!)
        for (var supertypeMap : List.of(supertypes, Map.<String, Set<String>>of())) {
            var slices = ConeOfInfluenceSlicer.computeDetailedSlices(
                    scan, BytecodeAutoConfigScanner.UserSpringSummary.empty(), supertypeMap);
            var sliceByTarget = new HashMap<String, ConeOfInfluenceSlicer.DetailedSlice>();
            for (var s : slices) {
                sliceByTarget.put(s.targetId(), s);
            }

            // 1. Line 408 check: fallbackDataSource MUST contain primaryDataSource + prop:app.ds.primary.enabled
            var fbSlice = sliceByTarget.get(ConeOfInfluenceSlicer.beanNodeId(fallbackDsBean));
            assertNotNull(fbSlice);
            assertTrue(fbSlice.beanNodeIds().contains(ConeOfInfluenceSlicer.beanNodeId(primaryDsBean)),
                    "COI must include competing same-type producer primaryDataSource: " + fbSlice);
            assertTrue(fbSlice.variableIds().contains("prop:app.ds.primary.enabled"),
                    "COI must retain competing same-type producer's condition var: " + fbSlice);
            assertFalse(fbSlice.variableIds().contains("prop:distractor.flag"),
                    "COI must exclude unrelated distractor: " + fbSlice);

            // 2. Line 246 check: hopD MUST transitively include hopC, hopB, hopA + chain.hop.a + chain.hop.b
            var hopDSlice = sliceByTarget.get(ConeOfInfluenceSlicer.beanNodeId(hopDBean));
            assertNotNull(hopDSlice);
            assertTrue(hopDSlice.beanNodeIds().containsAll(List.of(
                    ConeOfInfluenceSlicer.beanNodeId(hopABean),
                    ConeOfInfluenceSlicer.beanNodeId(hopBBean),
                    ConeOfInfluenceSlicer.beanNodeId(hopCBean),
                    ConeOfInfluenceSlicer.beanNodeId(hopDBean))),
                    "COI must transitively include all @Bean parameter producers: " + hopDSlice);
            assertTrue(hopDSlice.variableIds().containsAll(List.of("prop:chain.hop.a", "prop:chain.hop.b")),
                    "COI must collect transitive parameter producer properties: " + hopDSlice);
            assertFalse(hopDSlice.variableIds().contains("prop:distractor.flag"));

            // 3. Polymorphic subtype + @EnableConfigurationProperties + boolean @Profile expression check
            var storeSlice = sliceByTarget.get(ConeOfInfluenceSlicer.beanNodeId(defaultStoreBean));
            assertNotNull(storeSlice);
            assertTrue(storeSlice.beanNodeIds().contains(ConeOfInfluenceSlicer.beanNodeId(customStoreBean)),
                    "COI must capture polymorphic/ordered upstream CustomStore: " + storeSlice);
            assertTrue(storeSlice.configFqns().contains("com.example.StorePropsOwnerConfig"),
                    "COI must follow @EnableConfigurationProperties parameter binding: " + storeSlice);
            assertTrue(storeSlice.variableIds().containsAll(List.of(
                    "profile:cloud", "profile:hybrid", "profile:airgapped", "prop:store.props.owner.on")),
                    "COI must extract all boolean @Profile atoms and @EnableConfigurationProperties guard: " + storeSlice);
            assertFalse(storeSlice.variableIds().contains("prop:distractor.flag"));

            // 4. Enclosing/@Import guard check
            var impSlice = sliceByTarget.get(ConeOfInfluenceSlicer.beanNodeId(importedBean));
            assertNotNull(impSlice);
            assertTrue(impSlice.configFqns().contains("com.example.OuterGuardConfig"));
            assertTrue(impSlice.variableIds().containsAll(List.of("prop:outer.guard.on", "prop:imported.leaf.on")));
            assertFalse(impSlice.variableIds().contains("prop:distractor.flag"));

            // 5. Directed @AutoConfigureAfter fan-out: Consumer1 sees HubCore, NOT Consumer2; HubCore sees NEITHER!
            var c1Slice = sliceByTarget.get(ConeOfInfluenceSlicer.beanNodeId(consumer1Bean));
            var hubSlice = sliceByTarget.get(ConeOfInfluenceSlicer.beanNodeId(hubCoreBean));
            assertTrue(c1Slice.variableIds().containsAll(List.of("prop:hub.core.on", "prop:consumer1.on")));
            assertFalse(c1Slice.variableIds().contains("prop:consumer2.on"),
                    "Directed COI must not leak downstream sibling Consumer2 into Consumer1: " + c1Slice);
            assertFalse(hubSlice.variableIds().contains("prop:consumer1.on"),
                    "Directed COI must not leak downstream Consumer1 into HubCore: " + hubSlice);
            assertFalse(hubSlice.variableIds().contains("prop:consumer2.on"));

            // 6. Formal SAT Equivalence Proof via IncrementalCdclReasoner for EVERY target bean!
            //    Prove: SAT(Phi_full AND Phi_slice AND (Active_full(target) XOR Active_slice(target))) == UNSATISFIABLE!
            var fullOrder = List.of(
                    primaryDsBean, fallbackDsBean, hopABean, hopBBean, hopCBean, hopDBean,
                    customStoreBean, defaultStoreBean, importedBean, hubCoreBean, consumer1Bean, consumer2Bean, distractorBean);
            var fullEncoded = encodeStarterGraphSat(configs, fullOrder, null, null, supertypes, "full");
            for (var target : fullOrder) {
                String tId = ConeOfInfluenceSlicer.beanNodeId(target);
                var slice = sliceByTarget.get(tId);
                var sliceEncoded = encodeStarterGraphSat(
                        configs, fullOrder, slice.configFqns(), slice.beanNodeIds(), supertypes, "slice");

                Formula activeFull = fullEncoded.beanActiveVar().get(tId);
                Formula activeSlice = sliceEncoded.beanActiveVar().get(tId);
                Formula xor = or(and(activeFull, not(activeSlice)), and(not(activeFull), activeSlice));
                Formula mismatchQuery = and(fullEncoded.formula(), sliceEncoded.formula(), xor);

                var equivAns = IncrementalCdclReasoner.INSTANCE.solve(new Query(mismatchQuery, Limits.defaults()));
                assertEquals(UNSATISFIABLE, equivAns.status(),
                        () -> "SOUNDNESS VIOLATION for target " + tId + " (supertypeMap=" + supertypeMap.size()
                                + "): sliced formula disagrees with full formula under witness=" + equivAns.witness());
            }
        }
    }

    @Test
    void decomposeConditionModelCouplesDefaultProfileAndSharedBeanSelectorsSoundly() throws Exception {
        var config = new ConfigDataIngestion().ingest(
                IngestionFixtures.inputs(Map.of("src/main/resources/application.properties", "")),
                ConfigDataIngestion.Policy.defaults());
        var semantics = ConditionEvidenceLowering.semantics(
                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
        var derived = new ConditionEvidence.Derived(List.of(config.identity()),
                new com.evolution.analysis.contract.common.VersionedIdentifier("s4-sound", "1"), "s4-sound");

        // 1. Occurrences with disjoint properties sharing a @ConditionalOnMissingBean(DataSource) selector MUST stay coupled
        var occBean1 = new ConditionOccurrence(
                ConditionExpression.all(semantics, List.of(
                        ConditionExpression.atom(semantics, new ConditionExpression.Property("app", List.of("a"), "true", false)),
                        ConditionExpression.atom(semantics, new ConditionExpression.Bean(
                                ConditionExpression.BeanPredicate.MISSING, "ctx", List.of("javax.sql.DataSource"),
                                List.of(), List.of(), ConditionExpression.Search.ALL)))),
                derived, "cond/b1", "BEAN", 0);
        var occBean2 = new ConditionOccurrence(
                ConditionExpression.all(semantics, List.of(
                        ConditionExpression.atom(semantics, new ConditionExpression.Property("app", List.of("b"), "true", false)),
                        ConditionExpression.atom(semantics, new ConditionExpression.Bean(
                                ConditionExpression.BeanPredicate.PRESENT, "ctx", List.of("javax.sql.DataSource"),
                                List.of(), List.of(), ConditionExpression.Search.ALL)))),
                derived, "cond/b2", "BEAN", 1);
        var occUnrelated = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Property("app", List.of("c"), "true", false)),
                derived, "cond/u", "BEAN", 2);

        var beanDecomp = ConeOfInfluenceSlicer.decomposeConditionModel(List.of(occBean1, occBean2, occUnrelated));
        assertEquals(2, beanDecomp.components().size(),
                "Shared Bean selector occurrences must merge into 1 component while unrelated stays separate");

        // 2. @Profile("default") depends on the absence of all other profiles, so profile occurrences must merge
        var occDefault = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Profile("default")),
                derived, "cond/def", "BEAN", 0);
        var occProd = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Profile("prod")),
                derived, "cond/prod", "BEAN", 1);
        var occPropOnly = new ConditionOccurrence(
                ConditionExpression.atom(semantics, new ConditionExpression.Property("app", List.of("solo"), "true", false)),
                derived, "cond/prop", "BEAN", 2);

        var defaultDecomp = ConeOfInfluenceSlicer.decomposeConditionModel(List.of(occDefault, occProd, occPropOnly));
        assertEquals(2, defaultDecomp.components().size(),
                "@Profile(default) must couple with @Profile(prod) while property-only occurrence stays separate");
    }

    @Test
    void coiTransitiveThreeHopAutoConfigureAfterChainAndErasedParamMarkerAreSound() {
        var jarDigest = ContentDigest.sha256Utf8("three-hop-ordering-coi");
        var ev = new ConditionEvidence.Artifact(jarDigest, ContentDigest.sha256Utf8("entry"), "chain.class");

        // 3-hop chain: CfgC (@AutoConfigureAfter CfgB, checks @ConditionalOnMissingBean(BaseEngine))
        //           -> CfgB (@AutoConfigureAfter CfgA, NO bean-state cond on CfgB itself!)
        //           -> CfgA (produces SubEngine <: BaseEngine gated by prop:hop3.cfgA.on)
        // Plus downstream sibling CfgSibling (@AutoConfigureAfter CfgA) that must NOT leak into CfgC!
        var beanA = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.chain.CfgA", "subEngine", "()Lcom/example/chain/SubEngine;",
                "com.example.chain.SubEngine", List.of("subEngine"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("hop3.cfgA.on")), false)),
                ev);
        var beanB = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.chain.CfgB", "midHelper", "()Lcom/example/chain/MidHelper;",
                "com.example.chain.MidHelper", List.of("midHelper"), List.of(),
                false, false, false, List.of(), ev);
        var beanC = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.chain.CfgC", "fallbackEngine", "(Lorg/springframework/beans/factory/ObjectProvider;)Lcom/example/chain/BaseEngine;",
                "com.example.chain.BaseEngine", List.of("fallbackEngine"),
                List.of("org.springframework.beans.factory.ObjectProvider"),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean",
                        Map.of("value", List.of("com.example.chain.BaseEngine")), false)),
                ev);
        var siblingBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.chain.CfgSibling", "siblingBean", "()Lcom/example/chain/Sibling;",
                "com.example.chain.Sibling", List.of("siblingBean"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("name", List.of("sibling.only.flag")), false)),
                ev);

        var configs = List.of(
                starterCfg("com.example.chain.CfgA", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(beanA)),
                starterCfg("com.example.chain.CfgB", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.chain.CfgA"), List.of(), Optional.empty())),
                        List.of(beanB)),
                starterCfg("com.example.chain.CfgC", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.chain.CfgB"), List.of(), Optional.empty())),
                        List.of(beanC)),
                starterCfg("com.example.chain.CfgSibling", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.chain.CfgA"), List.of(), Optional.empty())),
                        List.of(siblingBean)));

        var scan = new BytecodeAutoConfigScanner.ScanResult(
                ContentDigest.sha256Utf8("three-hop-scan"), 1, 1, configs.size(),
                configs, List.of(), Map.of(), Set.of(), 1L);

        // Even with EMPTY supertypesByType, transitive 3-hop orderedBefore MUST reach CfgA & beanA!
        var slices = ConeOfInfluenceSlicer.computeDetailedSlices(
                scan, BytecodeAutoConfigScanner.UserSpringSummary.empty(), Map.of());
        var sliceC = slices.stream()
                .filter(s -> s.targetId().equals(ConeOfInfluenceSlicer.beanNodeId(beanC)))
                .findFirst().orElseThrow();

        assertTrue(sliceC.configFqns().containsAll(List.of(
                "com.example.chain.CfgA", "com.example.chain.CfgB", "com.example.chain.CfgC")),
                "3-hop transitive @AutoConfigureAfter must reach CfgA through intermediate CfgB: " + sliceC);
        assertTrue(sliceC.beanNodeIds().contains(ConeOfInfluenceSlicer.beanNodeId(beanA)),
                "3-hop transitive @AutoConfigureAfter must include CfgA's beanA: " + sliceC);
        assertTrue(sliceC.variableIds().contains("prop:hop3.cfg-a.on"),
                "3-hop slice must capture CfgA's property variable: " + sliceC);
        assertTrue(sliceC.variableIds().stream().anyMatch(v -> v.startsWith("erased-param:") && v.endsWith("ObjectProvider")),
                "Erased ObjectProvider parameter must be explicitly recorded (zero silent omission): " + sliceC);
        assertFalse(sliceC.configFqns().contains("com.example.chain.CfgSibling"),
                "Downstream sibling CfgSibling must NOT leak into CfgC's backward slice: " + sliceC);
        assertFalse(sliceC.variableIds().contains("prop:sibling.only.flag"));

        // Formal SAT XOR-equivalence check using production StarterAutoConfigSatReasoner!
        var supertypes = Map.of("com.example.chain.SubEngine", Set.of("com.example.chain.BaseEngine"));
        var fullSat = StarterAutoConfigSatReasoner.encodeStarterGraphSat(scan, null, null, supertypes, "full");
        var sliceSat = StarterAutoConfigSatReasoner.encodeStarterGraphSat(
                scan, sliceC.configFqns(), sliceC.beanNodeIds(), supertypes, "slice");
        Formula actFull = fullSat.beanActiveVars().get(ConeOfInfluenceSlicer.beanNodeId(beanC));
        Formula actSlice = sliceSat.beanActiveVars().get(ConeOfInfluenceSlicer.beanNodeId(beanC));
        Formula xor = or(and(actFull, not(actSlice)), and(not(actFull), actSlice));
        var ans = IncrementalCdclReasoner.INSTANCE.solve(new Query(and(fullSat.formula(), sliceSat.formula(), xor), Limits.defaults()));
        assertEquals(UNSATISFIABLE, ans.status(),
                () -> "3-hop COI slice must be formally equivalent to full formula; mismatch witness=" + ans.witness());
    }

    @Test
    void starterSatReasonerHandlesSingleCandidateUniquenessAndMutuallyExclusiveHavingValuesSoundly() {
        var jarDigest = ContentDigest.sha256Utf8("single-cand-and-mutex-props");
        var ev = new ConditionEvidence.Artifact(jarDigest, ContentDigest.sha256Utf8("entry"), "sc.class");

        // 1. Mutually exclusive @ConditionalOnProperty havingValue="redis" vs havingValue="caffeine" on same key!
        var redisBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.cache.RedisCacheCfg", "redisCache", "()Lcom/example/cache/CacheMgr;",
                "com.example.cache.CacheMgr", List.of("redisCache"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("prefix", List.of("app.cache"), "name", List.of("type"), "havingValue", List.of("redis")), false)),
                ev);
        var caffeineBean = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.cache.CaffeineCacheCfg", "caffeineCache", "()Lcom/example/cache/CacheMgr;",
                "com.example.cache.CacheMgr", List.of("caffeineCache"), List.of(),
                false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnProperty",
                        Map.of("prefix", List.of("app.cache"), "name", List.of("type"), "havingValue", List.of("caffeine")), false)),
                ev);

        // 2. @ConditionalOnSingleCandidate with 2 non-primary beans vs 1 @Primary + 1 non-primary
        var ds1 = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.ds.TwoDsCfg", "dsOne", "()Ljavax/sql/DataSource;",
                "javax.sql.DataSource", List.of("dsOne"), List.of(), false, false, false, List.of(), ev);
        var ds2 = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.ds.TwoDsCfg", "dsTwo", "()Ljavax/sql/DataSource;",
                "javax.sql.DataSource", List.of("dsTwo"), List.of(), false, false, false, List.of(), ev);
        var consumerSingle = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.ds.ConsumerCfg", "jdbcConsumer", "()Lcom/example/ds/JdbcConsumer;",
                "com.example.ds.JdbcConsumer", List.of("jdbcConsumer"), List.of(), false, false, false,
                List.of(new BytecodeAutoConfigScanner.ConditionAnnotation(
                        "org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate",
                        Map.of("value", List.of("javax.sql.DataSource")), false)),
                ev);

        var configs = List.of(
                starterCfg("com.example.cache.RedisCacheCfg", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(redisBean)),
                starterCfg("com.example.cache.CaffeineCacheCfg", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(caffeineBean)),
                starterCfg("com.example.ds.TwoDsCfg", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(ds1, ds2)),
                starterCfg("com.example.ds.ConsumerCfg", jarDigest, ev, List.of(), List.of(), List.of(), List.of(),
                        List.of(new BytecodeAutoConfigScanner.OrderingAnnotation(
                                "org.springframework.boot.autoconfigure.AutoConfigureAfter",
                                List.of("com.example.ds.TwoDsCfg"), List.of(), Optional.empty())),
                        List.of(consumerSingle)));
        var scan = new BytecodeAutoConfigScanner.ScanResult(
                ContentDigest.sha256Utf8("mutex-and-single-scan"), 1, 1, configs.size(),
                configs, List.of(), Map.of(), Set.of(), 1L);

        // Mutually exclusive havingValue("redis") & havingValue("caffeine") MUST be UNSATISFIABLE simultaneously!
        var sat = StarterAutoConfigSatReasoner.encodeStarterGraphSat(scan, null, null, Map.of(), "test");
        Formula fRedis = sat.beanActiveVars().get(ConeOfInfluenceSlicer.beanNodeId(redisBean));
        Formula fCaff = sat.beanActiveVars().get(ConeOfInfluenceSlicer.beanNodeId(caffeineBean));
        assertEquals(SATISFIABLE, IncrementalCdclReasoner.INSTANCE.solve(new Query(and(sat.formula(), fRedis), Limits.defaults())).status());
        assertEquals(SATISFIABLE, IncrementalCdclReasoner.INSTANCE.solve(new Query(and(sat.formula(), fCaff), Limits.defaults())).status());
        assertEquals(UNSATISFIABLE, IncrementalCdclReasoner.INSTANCE.solve(new Query(and(sat.formula(), fRedis, fCaff), Limits.defaults())).status(),
                "One-hot property domain must prove havingValue=redis AND havingValue=caffeine mutually exclusive (UNSAT)!");

        // @ConditionalOnSingleCandidate with 2 always-active non-@Primary beans MUST be NEVER (not False-MUST!)
        var cohortNoPrimary = StarterAutoConfigSatReasoner.evaluateAutoConfigCohort(
                scan,
                List.of("com.example.ds.TwoDsCfg", "com.example.ds.ConsumerCfg"),
                List.of(StarterAutoConfigSatReasoner.Dimension.profile("check")),
                Set.of(),
                StarterAutoConfigSatReasoner.UserBeanInventory.empty(),
                Map.of(),
                List.of("jdbcConsumer"));
        assertEquals("NEVER", cohortNoPrimary.beanClassifications().get("jdbcConsumer"),
                "Two non-@Primary DataSource beans must NOT satisfy @ConditionalOnSingleCandidate");

        // When one of the 2 beans is marked @Primary, @ConditionalOnSingleCandidate MUST evaluate to MUST!
        var ds1Primary = new BytecodeAutoConfigScanner.StarterBean(
                "com.example.ds.TwoDsCfg", "dsOne", "()Ljavax/sql/DataSource;",
                "javax.sql.DataSource", List.of("dsOne"), List.of(), true, false, false, List.of(), ev);
        var configsWithPrimary = List.of(
                starterCfg("com.example.ds.TwoDsCfg", jarDigest, ev, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(ds1Primary, ds2)),
                configs.get(3));
        var scanPrimary = new BytecodeAutoConfigScanner.ScanResult(
                ContentDigest.sha256Utf8("single-primary-scan"), 1, 1, configsWithPrimary.size(),
                configsWithPrimary, List.of(), Map.of(), Set.of(), 1L);
        var cohortWithPrimary = StarterAutoConfigSatReasoner.evaluateAutoConfigCohort(
                scanPrimary,
                List.of("com.example.ds.TwoDsCfg", "com.example.ds.ConsumerCfg"),
                List.of(StarterAutoConfigSatReasoner.Dimension.profile("check")),
                Set.of(),
                StarterAutoConfigSatReasoner.UserBeanInventory.empty(),
                Map.of(),
                List.of("jdbcConsumer"));
        assertEquals("MUST", cohortWithPrimary.beanClassifications().get("jdbcConsumer"),
                "Unique @Primary among 2 DataSource beans must satisfy @ConditionalOnSingleCandidate");
    }

    @Test
    void nativePbChaiKuehlmannPropagationAnd1UipMatchBruteForceAcross300RandomMixedFormulas() {
        var rng = new Random(2026112801L);
        for (int trial = 0; trial < 300; trial++) {
            int nVars = 5 + (trial % 4); // 5..8 vars (32..256 worlds)
            int pbCount = 3 + (trial % 5);
            int cnfCount = 2 + (trial % 4);

            var pbs = new ArrayList<IncrementalCdclReasoner.PbConstraint>(pbCount + cnfCount);
            for (int c = 0; c < pbCount; c++) {
                int len = 2 + rng.nextInt(nVars - 1);
                var chosenVars = new ArrayList<Integer>(nVars);
                for (int v = 1; v <= nVars; v++) chosenVars.add(v);
                Collections.shuffle(chosenVars, rng);
                int[] lits = new int[len];
                int[] weights = new int[len];
                int maxPos = 0;
                int minNeg = 0;
                for (int i = 0; i < len; i++) {
                    int v = chosenVars.get(i);
                    lits[i] = rng.nextBoolean() ? v : -v;
                    int w = rng.nextInt(7) - 2; // includes negative weights (-2..4)
                    if (w == 0) w = 1;
                    weights[i] = w;
                    if (w > 0) maxPos += w; else minNeg += w;
                }
                int rhs = minNeg + rng.nextInt(Math.max(1, maxPos - minNeg + 2));
                pbs.add(new IncrementalCdclReasoner.PbConstraint(lits, weights, rhs));
            }
            for (int c = 0; c < cnfCount; c++) {
                int len = 2 + rng.nextInt(3);
                int[] lits = new int[len];
                for (int i = 0; i < len; i++) {
                    int v = 1 + rng.nextInt(nVars);
                    lits[i] = rng.nextBoolean() ? v : -v;
                }
                pbs.add(IncrementalCdclReasoner.PbConstraint.clause(lits));
            }

            int worlds = 1 << nVars;
            boolean[] worldSat = new boolean[worlds];
            boolean anySat = false;
            boolean[] bits = new boolean[nVars + 1];
            for (int mask = 0; mask < worlds; mask++) {
                for (int v = 1; v <= nVars; v++) {
                    bits[v] = (mask & (1 << (v - 1))) != 0;
                }
                boolean ok = true;
                for (var pb : pbs) {
                    if (!pb.evaluate(bits)) {
                        ok = false;
                        break;
                    }
                }
                worldSat[mask] = ok;
                anySat |= ok;
            }

            var session = new IncrementalCdclReasoner.Session(Limits.defaults());
            for (int v = 1; v <= nVars; v++) {
                session.newVariable("x" + v);
            }
            for (var pb : pbs) {
                session.addPbConstraint(pb);
            }

            var plainAns = session.solveIncremental(ContentDigest.sha256Utf8("pb-trial-" + trial));
            assertEquals(anySat ? SATISFIABLE : UNSATISFIABLE, plainAns.status(),
                    "PB plain SAT mismatch on trial " + trial);
            if (anySat) {
                boolean[] wBits = new boolean[nVars + 1];
                for (int v = 1; v <= nVars; v++) {
                    wBits[v] = Boolean.TRUE.equals(plainAns.witness().get("x" + v));
                }
                for (var pb : pbs) {
                    assertTrue(pb.evaluate(wBits), "PB witness violates " + pb + " on trial " + trial);
                }
            }

            // Test 4 assumption queries per trial (1,200 PB assumption queries)
            for (int q = 0; q < 4; q++) {
                int aLen = 1 + rng.nextInt(3);
                int[] aLits = new int[aLen];
                for (int a = 0; a < aLen; a++) {
                    int v = 1 + rng.nextInt(nVars);
                    aLits[a] = rng.nextBoolean() ? v : -v;
                }
                boolean expectedSat = false;
                for (int mask = 0; mask < worlds; mask++) {
                    if (!worldSat[mask]) continue;
                    boolean match = true;
                    for (int lit : aLits) {
                        int v = Math.abs(lit);
                        boolean val = (mask & (1 << (v - 1))) != 0;
                        if (val != (lit > 0)) {
                            match = false;
                            break;
                        }
                    }
                    if (match) {
                        expectedSat = true;
                        break;
                    }
                }
                var ans = session.solveIncremental(ContentDigest.sha256Utf8("pb-t" + trial + "-q" + q), aLits);
                assertEquals(expectedSat ? SATISFIABLE : UNSATISFIABLE, ans.status(),
                        "PB assumption mismatch on trial " + trial + " q=" + q);
                if (expectedSat) {
                    boolean[] wBits = new boolean[nVars + 1];
                    for (int v = 1; v <= nVars; v++) {
                        wBits[v] = Boolean.TRUE.equals(ans.witness().get("x" + v));
                    }
                    for (var pb : pbs) {
                        assertTrue(pb.evaluate(wBits), "PB assumption witness violates " + pb);
                    }
                    for (int lit : aLits) {
                        assertEquals(lit > 0, wBits[Math.abs(lit)], "Assumption violated: " + lit);
                    }
                }
            }
        }
    }

    @Test
    void nativePbPigeonholeAndMultiValueDomainReductionExceed60Percent() throws Exception {
        // 1. PHP(5, 4) with native AtLeast1 + AtMost1 PB constraints -> UNSAT quickly
        int pigeons = 5;
        int holes = 4;
        var phpSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        int[][] x = new int[pigeons][holes];
        for (int p = 0; p < pigeons; p++) {
            for (int h = 0; h < holes; h++) {
                x[p][h] = phpSession.newVariable("p" + p + "_h" + h);
            }
            phpSession.addAtLeastK(1, x[p]);
        }
        for (int h = 0; h < holes; h++) {
            int[] col = new int[pigeons];
            for (int p = 0; p < pigeons; p++) col[p] = x[p][h];
            phpSession.addAtMostK(1, col);
        }
        var phpAns = phpSession.solveIncremental(ContentDigest.sha256Utf8("pb-php54"));
        assertEquals(UNSATISFIABLE, phpAns.status());
        assertEquals(pigeons + holes, phpSession.pbConstraintCount());

        // 2. RepositoryConfigurationSpaceBuilder MAX_VALUES_PER_PROPERTY=8 native Exactly1 PB encoding >= 60% savings
        var build = ConditionLoweringTest.fixture(List.of()).build();
        var config = new ConfigDataIngestion().ingest(
                IngestionFixtures.inputs(Map.of("src/main/resources/application.properties", "app.mode=v1\n")),
                ConfigDataIngestion.Policy.defaults());
        var ev = new ConditionEvidence.Derived(List.of(config.identity()),
                new VersionedIdentifier("pb-domain-test", "1"), "pb-domain-test");
        var domains = new ArrayList<FiniteDomain>();
        for (int d = 0; d < 4; d++) {
            var vals = new ArrayList<FiniteDomain.Value>(RepositoryConfigurationSpaceBuilder.MAX_VALUES_PER_PROPERTY);
            vals.add(FiniteDomain.Value.missing());
            for (int v = 1; v < RepositoryConfigurationSpaceBuilder.MAX_VALUES_PER_PROPERTY; v++) {
                vals.add(FiniteDomain.Value.exact("opt-" + v));
            }
            domains.add(new FiniteDomain(
                    new FiniteDomain.Variable(FiniteDomain.Kind.PROPERTY, "app.tier." + d), vals, ev));
        }

        var domainSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        var varMap = new LinkedHashMap<FiniteDomain.Variable, Map<FiniteDomain.Value, Integer>>();
        var encoding = RepositoryConfigurationSpaceBuilder.encodeDomainExclusivityPb(domains, domainSession, varMap);
        assertEquals(4, encoding.domainCount());
        assertEquals(8, encoding.pbConstraintCount(), "4 domains x 2 PB constraints (AtLeast1 + AtMost1)");
        assertEquals(64, encoding.pbLiteralCount(), "4 domains x 16 PB literals");
        assertEquals(116, encoding.pairwiseClauseCount(), "4 domains x (1 + C(8,2)=28) pairwise clauses");
        assertEquals(256, encoding.pairwiseLiteralCount(), "4 domains x 64 pairwise literals");
        assertTrue(encoding.literalReductionRatio() >= 0.75 - 1e-9,
                () -> "Expected >= 75% literal reduction on 8-value domains, got " + encoding.literalReductionRatio());
        assertTrue(encoding.constraintReductionRatio() >= 0.93,
                () -> "Expected >= 93% constraint reduction on 8-value domains, got " + encoding.constraintReductionRatio());

        // Verify domain exclusivity semantically: picking two distinct values in the same domain is UNSAT
        var d0Lits = varMap.get(domains.getFirst().variable());
        int litMissing = d0Lits.get(FiniteDomain.Value.missing());
        int litOpt1 = d0Lits.get(FiniteDomain.Value.exact("opt-1"));
        assertEquals(UNSATISFIABLE, domainSession.solveIncremental(
                ContentDigest.sha256Utf8("dom-mutex"), litMissing, litOpt1).status());
        assertEquals(SATISFIABLE, domainSession.solveIncremental(
                ContentDigest.sha256Utf8("dom-single"), litOpt1).status());

        // 3. ConditionEvidenceLowering native PB for @ConditionalOnSingleCandidate (8 candidates -> >= 60% reduction)
        var scSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        int[] cands = new int[8];
        boolean[] prims = new boolean[8];
        for (int i = 0; i < 8; i++) {
            cands[i] = scSession.newVariable("bean_" + i);
        }
        prims[0] = true;
        int scRes = scSession.newVariable("single_cand_ok");
        var scSummary = ConditionEvidenceLowering.lowerSingleCandidateToPb(cands, prims, scRes, scSession);
        assertTrue(scSummary.literalReductionRatio() >= 0.60,
                () -> "Expected >= 60% literal reduction for 8-candidate @ConditionalOnSingleCandidate, got "
                        + scSummary.literalReductionRatio());
        // Check exact semantics: 0 active -> false; 1 active -> true; 2 non-primary -> false; primary + non-primary -> true
        assertEquals(UNSATISFIABLE, scSession.solveIncremental(ContentDigest.sha256Utf8("sc-0"),
                scRes, -cands[0], -cands[1], -cands[2], -cands[3], -cands[4], -cands[5], -cands[6], -cands[7]).status());
        assertEquals(SATISFIABLE, scSession.solveIncremental(ContentDigest.sha256Utf8("sc-1"),
                scRes, -cands[0], cands[1], -cands[2], -cands[3], -cands[4], -cands[5], -cands[6], -cands[7]).status());
        assertEquals(UNSATISFIABLE, scSession.solveIncremental(ContentDigest.sha256Utf8("sc-2-noprim"),
                scRes, -cands[0], cands[1], cands[2]).status());
        assertEquals(SATISFIABLE, scSession.solveIncremental(ContentDigest.sha256Utf8("sc-prim-plus"),
                scRes, cands[0], cands[1], cands[2]).status());

        // 4. ConditionEvidenceLowering native PB for SpEL numeric thresholds #{2 * ${app.tier:1} + ${app.boost:0} >= 5}
        var parsedSpel = ConditionEvidenceLowering.parseSpelNumericThreshold(
                "#{2 * ${app.tier:1} + ${app.boost:0} >= 5}").orElseThrow();
        assertEquals(2, parsedSpel.terms().size());
        assertEquals(ConditionEvidenceLowering.ComparisonOp.GE, parsedSpel.operator());
        assertEquals(5, parsedSpel.threshold());

        var spelSession = new IncrementalCdclReasoner.Session(Limits.defaults());
        var tierMap = new LinkedHashMap<Integer, Integer>();
        var boostMap = new LinkedHashMap<Integer, Integer>();
        int[] tierLits = new int[4];
        int[] boostLits = new int[4];
        for (int i = 0; i < 4; i++) {
            tierLits[i] = spelSession.newVariable("tier_" + i);
            tierMap.put(i, tierLits[i]);
            boostLits[i] = spelSession.newVariable("boost_" + i);
            boostMap.put(i, boostLits[i]);
        }
        spelSession.addExactly1(tierLits);
        spelSession.addExactly1(boostLits);
        int spelOk = spelSession.newVariable("spel_ok");
        ConditionEvidenceLowering.lowerSpelNumericThresholdToPb(
                parsedSpel, Map.of("app.tier", tierMap, "app.boost", boostMap), spelOk, spelSession);

        // Exhaustively verify all 4x4 = 16 combinations match (2*tier + boost >= 5)
        for (int t = 0; t < 4; t++) {
            for (int b = 0; b < 4; b++) {
                boolean expected = (2 * t + b) >= 5;
                var ansPos = spelSession.solveIncremental(
                        ContentDigest.sha256Utf8("spel-" + t + "-" + b + "-pos"),
                        tierLits[t], boostLits[b], spelOk);
                var ansNeg = spelSession.solveIncremental(
                        ContentDigest.sha256Utf8("spel-" + t + "-" + b + "-neg"),
                        tierLits[t], boostLits[b], -spelOk);
                assertEquals(expected ? SATISFIABLE : UNSATISFIABLE, ansPos.status());
                assertEquals(expected ? UNSATISFIABLE : SATISFIABLE, ansNeg.status());
            }
        }
        System.out.printf(Locale.ROOT,
                "[WP2.3-PB-ENCODING] 8-val-domains: litReduction=%.1f%% constraintReduction=%.1f%% | 8-cand-singleCandidate: litReduction=%.1f%%%n",
                encoding.literalReductionRatio() * 100.0,
                encoding.constraintReductionRatio() * 100.0,
                scSummary.literalReductionRatio() * 100.0);
    }

    private static BytecodeAutoConfigScanner.StarterConfiguration starterCfg(
            String fqn,
            ContentDigest jarDigest,
            ConditionEvidence.Artifact ev,
            List<String> imports,
            List<String> enableProps,
            List<String> nested,
            List<BytecodeAutoConfigScanner.ConditionAnnotation> conditions,
            List<BytecodeAutoConfigScanner.OrderingAnnotation> orderings,
            List<BytecodeAutoConfigScanner.StarterBean> beans) {
        return new BytecodeAutoConfigScanner.StarterConfiguration(
                fqn, "test-starter", jarDigest, "AutoConfiguration.imports",
                true, false, false, imports, enableProps, nested, conditions, orderings, beans, ev);
    }

    private record EncodedStarterSat(Formula formula, Map<String, Formula> beanActiveVar) {}

    private static EncodedStarterSat encodeStarterGraphSat(
            List<BytecodeAutoConfigScanner.StarterConfiguration> allConfigs,
            List<BytecodeAutoConfigScanner.StarterBean> orderedBeans,
            Set<String> allowedConfigs,
            Set<String> allowedBeans,
            Map<String, Set<String>> supertypes,
            String tag) {
        var cfgMap = new LinkedHashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>();
        var importedBy = new HashMap<String, List<String>>();
        var propOwners = new HashMap<String, List<String>>();
        for (var c : allConfigs) {
            if (allowedConfigs != null && !allowedConfigs.contains(c.classFqn())) {
                continue;
            }
            cfgMap.put(c.classFqn(), c);
            for (String imp : c.importedClasses()) {
                importedBy.computeIfAbsent(imp, ignored -> new ArrayList<>()).add(c.classFqn());
            }
            for (String ep : c.enabledConfigProperties()) {
                propOwners.computeIfAbsent(ep, ignored -> new ArrayList<>()).add(c.classFqn());
            }
        }

        var clauses = new ArrayList<Formula>();
        var cfgVar = new HashMap<String, Formula>();
        for (var c : cfgMap.values()) {
            cfgVar.put(c.classFqn(), variable(tag + ":cfg:" + c.classFqn()));
        }
        for (var c : cfgMap.values()) {
            var rhsTerms = new ArrayList<Formula>();
            for (var cond : c.conditions()) {
                rhsTerms.add(encodeSimpleCond(cond, null, List.of(), Map.of(), supertypes));
            }
            var parents = importedBy.getOrDefault(c.classFqn(), List.of());
            if (!parents.isEmpty()) {
                var pOr = new ArrayList<Formula>();
                for (String p : parents) {
                    if (cfgVar.containsKey(p)) {
                        pOr.add(cfgVar.get(p));
                    }
                }
                if (!pOr.isEmpty()) {
                    rhsTerms.add(or(pOr));
                }
            }
            Formula rhs = rhsTerms.isEmpty() ? SymbolicConfiguration.TRUE : and(rhsTerms);
            clauses.add(iffFormula(cfgVar.get(c.classFqn()), rhs));
        }

        var beanVars = new LinkedHashMap<String, Formula>();
        var priorBeans = new ArrayList<BytecodeAutoConfigScanner.StarterBean>();
        for (var b : orderedBeans) {
            String bId = ConeOfInfluenceSlicer.beanNodeId(b);
            if (allowedBeans != null && !allowedBeans.contains(bId)) {
                continue;
            }
            Formula bVar = variable(tag + ":bean:" + bId);
            beanVars.put(bId, bVar);
            var rhsTerms = new ArrayList<Formula>();
            rhsTerms.add(cfgVar.getOrDefault(b.declaringClassFqn(), SymbolicConfiguration.FALSE));
            for (var cond : b.conditions()) {
                rhsTerms.add(encodeSimpleCond(cond, b.returnTypeFqn(), priorBeans, beanVars, supertypes));
            }
            for (String pType : b.parameterTypeFqns()) {
                var providers = new ArrayList<Formula>();
                for (String ownerCfg : propOwners.getOrDefault(pType, List.of())) {
                    if (cfgVar.containsKey(ownerCfg)) {
                        providers.add(cfgVar.get(ownerCfg));
                    }
                }
                for (var pb : priorBeans) {
                    if (isAssignableType(pb.returnTypeFqn(), pType, supertypes)) {
                        providers.add(beanVars.get(ConeOfInfluenceSlicer.beanNodeId(pb)));
                    }
                }
                if (!propOwners.containsKey(pType)) {
                    providers.add(variable("extbean:" + pType));
                }
                rhsTerms.add(providers.isEmpty() ? SymbolicConfiguration.FALSE : or(providers));
            }
            clauses.add(iffFormula(bVar, and(rhsTerms)));
            priorBeans.add(b);
        }
        return new EncodedStarterSat(clauses.isEmpty() ? SymbolicConfiguration.TRUE : and(clauses), beanVars);
    }

    private static Formula encodeSimpleCond(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            String beanReturnType,
            List<BytecodeAutoConfigScanner.StarterBean> priorBeans,
            Map<String, Formula> beanVars,
            Map<String, Set<String>> supertypes) {
        String fqn = cond.annotationFqn();
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        if (simple.equals("ConditionalOnProperty")) {
            var terms = new ArrayList<Formula>();
            for (String n : cond.attributes().getOrDefault("name", List.of())) {
                terms.add(variable("exo:prop:" + BytecodeAutoConfigScanner.normalizePropertyKey(n)));
            }
            return terms.isEmpty() ? SymbolicConfiguration.TRUE : and(terms);
        }
        if (simple.equals("Profile")) {
            var terms = new ArrayList<Formula>();
            for (String expr : cond.attributes().getOrDefault("value", List.of())) {
                terms.add(parseTestProfile(expr));
            }
            return terms.isEmpty() ? SymbolicConfiguration.TRUE : or(terms);
        }
        if (simple.equals("ConditionalOnMissingBean") || simple.equals("ConditionalOnBean")) {
            boolean missing = simple.equals("ConditionalOnMissingBean");
            var types = new ArrayList<String>();
            types.addAll(cond.attributes().getOrDefault("value", List.of()));
            types.addAll(cond.attributes().getOrDefault("type", List.of()));
            if (types.isEmpty() && beanReturnType != null) {
                types.add(beanReturnType);
            }
            var presentDisj = new ArrayList<Formula>();
            for (String t : types) {
                presentDisj.add(variable("extbean:" + t));
                for (var pb : priorBeans) {
                    if (isAssignableType(pb.returnTypeFqn(), t, supertypes)) {
                        presentDisj.add(beanVars.get(ConeOfInfluenceSlicer.beanNodeId(pb)));
                    }
                }
            }
            Formula anyPresent = presentDisj.isEmpty() ? SymbolicConfiguration.FALSE : or(presentDisj);
            return missing ? not(anyPresent) : anyPresent;
        }
        return SymbolicConfiguration.TRUE;
    }

    private static boolean isAssignableType(String sub, String sup, Map<String, Set<String>> supertypes) {
        if (sub.equals(sup)) {
            return true;
        }
        return supertypes.getOrDefault(sub, Set.of()).contains(sup);
    }

    private static Formula parseTestProfile(String expr) {
        String s = expr.trim();
        while (s.startsWith("(") && s.endsWith(")") && matchingParens(s)) {
            s = s.substring(1, s.length() - 1).trim();
        }
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '|') {
                return or(parseTestProfile(s.substring(0, i)), parseTestProfile(s.substring(i + 1)));
            }
        }
        depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && c == '&') {
                return and(parseTestProfile(s.substring(0, i)), parseTestProfile(s.substring(i + 1)));
            }
        }
        if (s.startsWith("!")) {
            return not(parseTestProfile(s.substring(1)));
        }
        return variable("exo:profile:" + s);
    }

    private static boolean matchingParens(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') depth++;
            else if (s.charAt(i) == ')') depth--;
            if (depth == 0 && i < s.length() - 1) return false;
        }
        return depth == 0;
    }

    private static Formula iffFormula(Formula a, Formula b) {
        return and(or(not(a), b), or(not(b), a));
    }
}
