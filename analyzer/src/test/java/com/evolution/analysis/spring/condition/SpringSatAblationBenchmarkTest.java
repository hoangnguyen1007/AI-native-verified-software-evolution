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
import com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import com.evolution.analysis.spring.universal.FrameworkGeneration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.regStep;
import static com.evolution.analysis.spring.condition.BeanRegistrationTransitionsTest.rp;
import static com.evolution.analysis.spring.condition.InjectionBindingsTest.*;
import static com.evolution.analysis.spring.condition.RegistrationDiscoveryTest.*;
import static com.evolution.analysis.spring.truth.ConfigurationReasoner.Satisfiability.*;
import static com.evolution.analysis.spring.truth.SymbolicConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * E5 Publication Benchmark & 4-Tier Ablation Suite (A0 -> A3):
 * <ul>
 *   <li>A0: Legacy Naive DPLL (array cloning, no clause learning) + global 32-dim ceiling</li>
 *   <li>A3-CDCL: Pure-Java 2-WL + 1-UIP + phase saving + deterministic VSIDS (S5a)</li>
 *   <li>A2-verify / A3-prod: Kleene prime-implicant cube generalization (S5b) +/- witness double-eval toggle (S5c)</li>
 *   <li>A3-COI: Variable-connected component decomposition + directed backward starter slicing (S4)</li>
 * </ul>
 */
class SpringSatAblationBenchmarkTest {

    private static final ContentDigest ARTIFACT = ContentDigest.sha256Utf8("e5-ablation-evaluator");

    @Test
    void solverScalingAblationNaiveDpllVsIncrementalCdcl() {
        record CaseSpec(String label, Formula formula, Satisfiability expected) {}
        var cases = List.of(
                new CaseSpec("PHP(4,3)", pigeonhole(4, 3), UNSATISFIABLE),
                new CaseSpec("PHP(5,4)", pigeonhole(5, 4), UNSATISFIABLE),
                new CaseSpec("Chain-UNSAT(N=64)", dependencyChainUnsat(64), UNSATISFIABLE),
                new CaseSpec("Chain-UNSAT(N=128)", dependencyChainUnsat(128), UNSATISFIABLE),
                new CaseSpec("Chain-SAT(N=128)", dependencyChainSat(128), SATISFIABLE));

        var strictLimits = new Limits(100_000, 500_000, 120_000L, 256);
        int a0Solved = 0;
        int a3Solved = 0;

        for (var c : cases) {
            // Warmup x2, Measure x5 -> Median +/- IQR
            for (int w = 0; w < 2; w++) {
                IncrementalCdclReasoner.INSTANCE.solve(new Query(c.formula(), strictLimits));
                LegacyNaiveDpllBaseline.solve(new Query(c.formula(), strictLimits));
            }
            double[] a0Times = new double[5];
            double[] a3Times = new double[5];
            Answer a0Last = null;
            Answer a3Last = null;
            for (int r = 0; r < 5; r++) {
                long t0 = System.nanoTime();
                a0Last = LegacyNaiveDpllBaseline.solve(new Query(c.formula(), strictLimits));
                a0Times[r] = (System.nanoTime() - t0) / 1_000_000.0;

                long t1 = System.nanoTime();
                a3Last = IncrementalCdclReasoner.INSTANCE.solve(new Query(c.formula(), strictLimits));
                a3Times[r] = (System.nanoTime() - t1) / 1_000_000.0;
            }
            if (a0Last.status() == c.expected()) a0Solved++;
            if (a3Last.status() == c.expected()) a3Solved++;

            assertEquals(c.expected(), a3Last.status(), () -> "A3-CDCL failed to decide " + c.label());
            if (a0Last.status() != UNKNOWN) {
                assertEquals(c.expected(), a0Last.status(), () -> "Agreement check on " + c.label());
            }

            var a0Stats = medianIqr(a0Times);
            var a3Stats = medianIqr(a3Times);
            System.out.printf(Locale.ROOT,
                    "[E5-ABLATION-SOLVER] case=%s expected=%s | A0_NaiveDPLL status=%s steps=%d medianMs=%.3f iqrMs=%.3f | A3_CDCL status=%s steps=%d medianMs=%.3f iqrMs=%.3f%n",
                    c.label(), c.expected(),
                    a0Last.status(), a0Last.steps(), a0Stats[0], a0Stats[1],
                    a3Last.status(), a3Last.steps(), a3Stats[0], a3Stats[1]);
        }

        // At 120k step budget on PHP(5,4) and deep chains, CDCL achieves 5/5 solved while Naive DPLL hits SOLVER_LIMIT on hard UNSAT cases.
        final int fA3 = a3Solved, fA0 = a0Solved;
        assertEquals(cases.size(), fA3, "CDCL must solve all benchmark cases within step budget");
        assertTrue(fA3 > fA0,
                () -> "Expected CDCL solved count (" + fA3 + ") > Naive DPLL (" + fA0 + ")");
    }

    @Test
    void signatureCompressionAndReplayAblation() throws Exception {
        for (int dims : List.of(8, 12, 16)) {
            long totalWorlds = 1L << dims;
            var source = fixture("@Profile(\"dev\")");
            var original = source.space();
            var domains = new ArrayList<>(original.domains());
            var baselineValues = new TreeMap<FiniteDomain.Variable, FiniteDomain.Value>();
            baselineValues.put(new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "dev"),
                    FiniteDomain.Value.bool(false));
            for (int i = 0; i < dims - 1; i++) {
                var extra = new FiniteDomain.Variable(FiniteDomain.Kind.PROFILE, "unrelated-" + i);
                domains.add(new FiniteDomain(extra,
                        List.of(FiniteDomain.Value.bool(false), FiniteDomain.Value.bool(true)),
                        source.source().evidence()));
                baselineValues.put(extra, FiniteDomain.Value.bool(false));
            }
            var space = new ConfigurationSpace(
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
                    ConditionModel.create(space, source.lowering().occurrences()),
                    source.lowering().semantics(),
                    new ConfigurationAssignment(baselineValues, source.source().evidence()),
                    IncrementalCdclReasoner.INSTANCE,
                    TruthRegionEvaluation.Limits.conservative(),
                    ARTIFACT);

            for (int w = 0; w < 2; w++) {
                TruthRegionEvaluation.evaluateSymbolic(request, 64, Limits.defaults(), true);
                TruthRegionEvaluation.evaluateSymbolic(request, 64, Limits.defaults(), false);
            }
            double[] verifyTimes = new double[5];
            double[] prodTimes = new double[5];
            SymbolicTruthRegions.StagedResult verified = null;
            SymbolicTruthRegions.StagedResult prod = null;
            for (int r = 0; r < 5; r++) {
                long t0 = System.nanoTime();
                verified = TruthRegionEvaluation.evaluateSymbolic(request, 64, Limits.defaults(), true);
                verifyTimes[r] = (System.nanoTime() - t0) / 1_000_000.0;

                long t1 = System.nanoTime();
                prod = TruthRegionEvaluation.evaluateSymbolic(request, 64, Limits.defaults(), false);
                prodTimes[r] = (System.nanoTime() - t1) / 1_000_000.0;
            }

            assertTrue(verified.signaturesComplete());
            assertTrue(prod.signaturesComplete());
            assertEquals(2, verified.traces().size());
            assertEquals(2, prod.traces().size());
            assertTrue(verified.queries().size() <= 4);
            assertEquals(
                    verified.regions().stream().map(r -> List.of(r.fact(), r.classification())).toList(),
                    prod.regions().stream().map(r -> List.of(r.fact(), r.classification())).toList());

            var vStats = medianIqr(verifyTimes);
            var pStats = medianIqr(prodTimes);
            System.out.printf(Locale.ROOT,
                    "[E5-ABLATION-KLEENE] dims=%d concreteWorlds=%d kleeneTraces=%d satQueries=%d verifyMedianMs=%.3f verifyIqr=%.3f prodMedianMs=%.3f prodIqr=%.3f compressionRatio=%.1fx%n",
                    dims, totalWorlds, prod.traces().size(), prod.queries().size(),
                    vStats[0], vStats[1], pStats[0], pStats[1], (double) totalWorlds / prod.traces().size());
        }
    }

    @Test
    void coiScalingAndRealStarterJarAblation() throws Exception {
        var build = ConditionLoweringTest.fixture(List.of()).build();
        var config = new ConfigDataIngestion().ingest(
                IngestionFixtures.inputs(Map.of("src/main/resources/application.properties", "")),
                ConfigDataIngestion.Policy.defaults());
        var semantics = ConditionEvidenceLowering.semantics(
                new SpringFrameworkEvidence(false, Optional.empty(), List.of()));
        var derived = new ConditionEvidence.Derived(List.of(config.identity()),
                new VersionedIdentifier("e5-coi-bench", "1"), "e5-coi-bench");

        for (int compCount : List.of(4, 8, 16)) {
            int varsPerComp = 12;
            int totalDirectVars = compCount * varsPerComp;
            var occurrences = new ArrayList<ConditionOccurrence>(compCount);
            int idx = 0;
            for (int c = 0; c < compCount; c++) {
                var terms = new ArrayList<ConditionExpression>(varsPerComp);
                for (int v = 0; v < varsPerComp; v++) {
                    terms.add(ConditionExpression.atom(semantics,
                            new ConditionExpression.Profile("m" + c + "-p" + v)));
                }
                occurrences.add(new ConditionOccurrence(
                        ConditionExpression.all(semantics, terms), derived, "cond/m-" + c, "BEAN", idx++));
            }

            var legacy = RepositoryConfigurationSpaceBuilder.build(build, config, occurrences);
            int legacyDropped = Math.max(0, totalDirectVars - countPrefixedDomains(legacy.space().domains(), "m"));

            var coiSpaces = ConeOfInfluenceSlicer.buildComponentSpaces(
                    build, config, occurrences, ConeOfInfluenceSlicer.MAX_COMPONENT_VARS);
            var coiPreserved = new HashSet<String>();
            for (var s : coiSpaces) {
                for (var d : s.space().domains()) {
                    if (d.variable().name().startsWith("m")) coiPreserved.add(d.variable().name());
                }
            }
            int coiDropped = totalDirectVars - coiPreserved.size();
            assertTrue(legacyDropped > 0, "Expected legacy global 32-var ceiling to truncate variables");
            assertEquals(0, coiDropped, "Expected S4 COI decomposition to keep 100% of direct variables");

            System.out.printf(Locale.ROOT,
                    "[E5-ABLATION-COI] components=%d totalVars=%d | A0_Global32 droppedVars=%d (%.1f%% loss) | A3_COI64 spaces=%d droppedVars=%d (0.0%% loss)%n",
                    compCount, totalDirectVars, legacyDropped, 100.0 * legacyDropped / totalDirectVars,
                    coiSpaces.size(), coiDropped);
        }

        // Empirical offline check against real spring-boot-autoconfigure-3.5.14.jar (assumes local ~/.m2 cache)
        Path bootJar = Path.of(System.getProperty("user.home"),
                ".m2/repository/org/springframework/boot/spring-boot-autoconfigure/3.5.14/spring-boot-autoconfigure-3.5.14.jar");
        if (Files.isRegularFile(bootJar)) {
            ContentDigest digest = ContentDigest.sha256(Files.readAllBytes(bootJar));
            var entry = new ClasspathEntry(ClasspathEntryKind.DEPENDENCY,
                    "org.springframework.boot:spring-boot-autoconfigure:3.5.14", digest);
            var scan = BytecodeAutoConfigScanner.scan(
                    List.of(new BinaryInput(entry, bootJar)),
                    FrameworkGeneration.MetadataPolicy.BOTH,
                    BytecodeAutoConfigScanner.Limits.defaults());
            for (int w = 0; w < 2; w++) {
                ConeOfInfluenceSlicer.sliceStarterGraph(scan, BytecodeAutoConfigScanner.UserSpringSummary.empty(), 64);
            }
            double[] sliceMs = new double[5];
            ConeOfInfluenceSlicer.StarterSlicingResult res = null;
            for (int r = 0; r < 5; r++) {
                long t0 = System.nanoTime();
                res = ConeOfInfluenceSlicer.sliceStarterGraph(scan, BytecodeAutoConfigScanner.UserSpringSummary.empty(), 64);
                sliceMs[r] = (System.nanoTime() - t0) / 1_000_000.0;
            }
            var stats = medianIqr(sliceMs);
            final var finalRes = Objects.requireNonNull(res);
            assertTrue(finalRes.totalTargets() >= 100);
            assertTrue(finalRes.directedMaxSliceVars() < finalRes.undirectedMaxComponentVars(),
                    () -> "Sound directed COI must stay strictly smaller than undirected hub: undirectedMax="
                            + finalRes.undirectedMaxComponentVars() + " directedMax=" + finalRes.directedMaxSliceVars());
            System.out.printf(Locale.ROOT,
                    "[E5-ABLATION-STARTERS] jar=spring-boot-autoconfigure-3.5.14 targets=%d globalVars=%d undirectedMax=%d directedMax=%d directedMedian=%.1f exceeding64=%d sliceMedianMs=%.3f iqrMs=%.3f%n",
                    finalRes.totalTargets(), finalRes.totalGlobalVars(), finalRes.undirectedMaxComponentVars(),
                    finalRes.directedMaxSliceVars(), finalRes.directedMedianSliceVars(), finalRes.slicesExceedingBudget(),
                    stats[0], stats[1]);
            for (int i = 0; i < Math.min(5, finalRes.topSlices().size()); i++) {
                var ts = finalRes.topSlices().get(i);
                System.out.printf(Locale.ROOT,
                        "[E5-ABLATION-STARTERS-TOP] rank=%d target=%s vars=%d configs=%d beans=%d%n",
                        i + 1, ts.targetId(), ts.sliceVars(), ts.sliceConfigs(), ts.sliceBeans());
            }
        }
    }

    private static int countPrefixedDomains(List<FiniteDomain> domains, String prefix) {
        int count = 0;
        for (var d : domains) {
            if (d.variable().name().startsWith(prefix)) count++;
        }
        return count;
    }

    private static double[] medianIqr(double[] samples) {
        double[] sorted = Arrays.copyOf(samples, samples.length);
        Arrays.sort(sorted);
        int n = sorted.length;
        double median = sorted[n / 2];
        double q1 = sorted[n / 4];
        double q3 = sorted[(3 * n) / 4];
        return new double[]{median, Math.max(0.0, q3 - q1)};
    }

    private static Formula pigeonhole(int pigeons, int holes) {
        Formula[][] x = new Formula[pigeons][holes];
        for (int p = 0; p < pigeons; p++) {
            for (int h = 0; h < holes; h++) {
                x[p][h] = variable("php_" + p + "_" + h);
            }
        }
        var clauses = new ArrayList<Formula>();
        for (int p = 0; p < pigeons; p++) {
            clauses.add(or(Arrays.asList(x[p])));
        }
        for (int h = 0; h < holes; h++) {
            for (int p1 = 0; p1 < pigeons; p1++) {
                for (int p2 = p1 + 1; p2 < pigeons; p2++) {
                    clauses.add(or(not(x[p1][h]), not(x[p2][h])));
                }
            }
        }
        return and(clauses);
    }

    private static Formula dependencyChainUnsat(int n) {
        var vars = java.util.stream.IntStream.range(0, n).mapToObj(i -> variable("d" + i)).toList();
        var clauses = new ArrayList<Formula>();
        // Dual-path implication diamond at root then long implication tail ending in contradiction
        clauses.add(or(vars.get(0), vars.get(1)));
        for (int i = 0; i < n - 1; i++) {
            clauses.add(or(not(vars.get(i)), vars.get(i + 1)));
        }
        clauses.add(or(not(vars.get(n - 1)), not(vars.get(0))));
        clauses.add(vars.get(0));
        return and(clauses);
    }

    private static Formula dependencyChainSat(int n) {
        var vars = java.util.stream.IntStream.range(0, n).mapToObj(i -> variable("s" + i)).toList();
        var clauses = new ArrayList<Formula>();
        clauses.add(vars.get(0));
        for (int i = 0; i < n - 1; i++) {
            clauses.add(or(not(vars.get(i)), vars.get(i + 1)));
        }
        return and(clauses);
    }

    /**
     * Pre-S5 Legacy Naive DPLL Baseline (Tseitin CNF + byte[] assignment cloning + no clause learning)
     * kept strictly in test scope for fair A0 vs A3-CDCL comparative ablation.
     */
    private static final class LegacyNaiveDpllBaseline {
        private static final class Limit extends RuntimeException {}

        static Answer solve(Query query) {
            long[] steps = {0L};
            try {
                if (query.formula().depth() > query.limits().maxDepth()) throw new Limit();
                var ids = new HashMap<ContentDigest, Integer>();
                var vars = new TreeMap<String, Integer>();
                var clauses = new ArrayList<int[]>();
                int root = encode(query.formula(), 0, query.limits(), ids, vars, clauses, steps);
                clauses.add(new int[]{root});
                byte[] vals = new byte[ids.size() + 1];
                boolean sat = dpll(vals, clauses, query.limits(), steps);
                if (!sat) {
                    return new Answer(query.identity(), UNSATISFIABLE, Map.of(), steps[0], Optional.empty());
                }
                var witness = new TreeMap<String, Boolean>();
                vars.forEach((k, id) -> witness.put(k, vals[id] == 1));
                return new Answer(query.identity(), SATISFIABLE, witness, steps[0], Optional.empty());
            } catch (Limit e) {
                return new Answer(query.identity(), UNKNOWN, Map.of(), steps[0], Optional.of("SOLVER_LIMIT"));
            }
        }

        private static int encode(Formula f, int depth, Limits lim,
                                  Map<ContentDigest, Integer> ids, Map<String, Integer> vars,
                                  List<int[]> clauses, long[] steps) {
            if (steps[0]++ >= lim.maxSteps()) throw new Limit();
            Integer known = ids.get(f.identity());
            if (known != null) return known;
            if (depth >= lim.maxDepth() || ids.size() >= lim.maxNodes()) throw new Limit();
            int id = ids.size() + 1;
            ids.put(f.identity(), id);
            var ch = f.children();
            int[] cl = new int[ch.size()];
            for (int i = 0; i < ch.size(); i++) {
                cl[i] = encode(ch.get(i), depth + 1, lim, ids, vars, clauses, steps);
            }
            switch (f.operator()) {
                case TRUE -> clauses.add(new int[]{id});
                case FALSE -> clauses.add(new int[]{-id});
                case VARIABLE -> vars.put(f.variable(), id);
                case NOT -> {
                    clauses.add(new int[]{-id, -cl[0]});
                    clauses.add(new int[]{id, cl[0]});
                }
                case AND, OR -> {
                    boolean isAnd = f.operator() == Operator.AND;
                    int[] big = new int[cl.length + 1];
                    big[0] = isAnd ? id : -id;
                    for (int i = 0; i < cl.length; i++) {
                        clauses.add(new int[]{isAnd ? -id : id, isAnd ? cl[i] : -cl[i]});
                        big[i + 1] = isAnd ? -cl[i] : cl[i];
                    }
                    clauses.add(big);
                }
            }
            return id;
        }

        private static boolean dpll(byte[] vals, List<int[]> clauses, Limits lim, long[] steps) {
            while (true) {
                boolean changed = false;
                for (int[] c : clauses) {
                    if (steps[0]++ >= lim.maxSteps()) throw new Limit();
                    int open = 0;
                    int openLit = 0;
                    boolean sat = false;
                    for (int lit : c) {
                        byte v = vals[Math.abs(lit)];
                        int lv = v == 0 ? 0 : (lit > 0 ? v : -v);
                        if (lv == 1) { sat = true; break; }
                        if (lv == 0) { open++; openLit = lit; }
                    }
                    if (sat) continue;
                    if (open == 0) return false;
                    if (open == 1) {
                        vals[Math.abs(openLit)] = (byte) (openLit > 0 ? 1 : -1);
                        changed = true;
                        break;
                    }
                }
                if (!changed) break;
            }
            int next = 0;
            for (int v = 1; v < vals.length; v++) {
                if (vals[v] == 0) { next = v; break; }
            }
            if (next == 0) return true;
            byte[] t = vals.clone();
            t[next] = 1;
            if (dpll(t, clauses, lim, steps)) {
                System.arraycopy(t, 0, vals, 0, vals.length);
                return true;
            }
            byte[] f = vals.clone();
            f[next] = -1;
            if (dpll(f, clauses, lim, steps)) {
                System.arraycopy(f, 0, vals, 0, vals.length);
                return true;
            }
            return false;
        }
    }
}
