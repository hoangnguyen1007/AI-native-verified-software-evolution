package com.evolution.analysis.spring.truth;

import com.evolution.analysis.contract.analysis.ClasspathEntry;
import com.evolution.analysis.contract.analysis.ClasspathEntryKind;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.frontend.BinaryInput;
import com.evolution.analysis.spring.condition.ConeOfInfluenceSlicer;
import com.evolution.analysis.spring.condition.StarterAutoConfigSatReasoner;
import com.evolution.analysis.spring.truth.IncrementalCdclReasoner.PbConstraint;
import com.evolution.analysis.spring.truth.SlicedDdnnfCompiler.CompiledDdnnf;
import com.evolution.analysis.spring.truth.SlicedDdnnfCompiler.SliceBuilder;
import com.evolution.analysis.spring.truth.SlicedDdnnfCompiler.SliceProblem;
import com.evolution.analysis.spring.universal.BytecodeAutoConfigScanner;
import com.evolution.analysis.spring.universal.FrameworkGeneration;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification proofs for WP2.3 ({@link SlicedDdnnfCompiler}) and WP2.5 Theorem-1 equivalence:
 * <ol>
 *   <li>100% exact {@code #SAT} agreement vs brute-force across UVL-style ({@code alt}/{@code Exactly1},
 *       group cardinality {@code [a..b]}, weighted sums) formulas ({@code |V| <= 16}).</li>
 *   <li>Theorem-1 Proof Check across 1,000 deterministic random 4-phase non-monotonic COI graphs
 *       comparing {@code COI-PB-d-DNNF} vs global brute-force (0 discrepancies on {@code MUST}/
 *       {@code MAY}/{@code NEVER} AND exact {@code #SAT} exposure ratio).</li>
 *   <li>Real {@code spring-boot-autoconfigure-3.5.14.jar} per-COI-slice d-DNNF compile benchmark
 *       ({@code < 2.0 s}, {@code 0%} timeout/bounded).</li>
 *   <li>Chi-square statistical uniformity of seeded uniform random sampling + symmetric
 *       {@code (witness+, witness-)} {@code MAY} pair generation + {@code DDNNF_COUNT_BOUNDED} fallback.</li>
 * </ol>
 */
class SlicedDdnnfCompilerTest {

    @Test
    void exactSharpSatMatchesBruteForceAcrossSyntheticUvlAltCardinalityAndWeightedPbFormulas() {
        var rng = new Random(2026112802L);
        int tested = 0;

        // 1. Structured UVL-style feature models: root + alt(Exactly1) + group cardinality [a..b] + weighted budget
        for (int trial = 0; trial < 40; trial++) {
            int altSize = 3 + (trial % 4);     // 3..6 alt features
            int grpSize = 3 + ((trial / 4) % 4); // 3..6 group cardinality features
            int extraSize = 2 + (trial % 3);   // 2..4 optional weighted features
            int totalVars = 1 + altSize + grpSize + extraSize; // 9..16 vars (<= 16)
            assertTrue(totalVars <= 16);

            var builder = new SliceBuilder();
            int root = builder.variable("root");
            builder.addClause(root); // root selected

            int[] altVars = new int[altSize];
            for (int i = 0; i < altSize; i++) altVars[i] = builder.variable("alt_" + i);
            // UVL alt (Exactly1)
            builder.addExactly1(altVars);

            int[] grpVars = new int[grpSize];
            for (int i = 0; i < grpSize; i++) grpVars[i] = builder.variable("grp_" + i);
            // UVL group cardinality [lo..hi]
            int lo = rng.nextInt(Math.max(1, grpSize - 1));
            int hi = lo + 1 + rng.nextInt(Math.max(1, grpSize - lo));
            hi = Math.min(hi, grpSize);
            builder.addAtLeastK(lo, grpVars);
            builder.addAtMostK(hi, grpVars);

            int[] extVars = new int[extraSize];
            for (int i = 0; i < extraSize; i++) extVars[i] = builder.variable("ext_" + i);

            // Cross-tree implication & exclusion constraints
            builder.addClause(-altVars[0], grpVars[0]);
            builder.addClause(-altVars[altSize - 1], -extVars[0]);

            // Weighted resource budget PB constraint (with mixed positive & negative weights)
            int[] wLits = new int[altSize + extraSize];
            int[] weights = new int[altSize + extraSize];
            for (int i = 0; i < altSize; i++) {
                wLits[i] = altVars[i];
                weights[i] = 1 + rng.nextInt(5);
            }
            for (int i = 0; i < extraSize; i++) {
                wLits[altSize + i] = rng.nextBoolean() ? extVars[i] : -extVars[i];
                weights[altSize + i] = rng.nextBoolean() ? (1 + rng.nextInt(4)) : -(1 + rng.nextInt(3));
            }
            int rhs = 1 + rng.nextInt(5);
            builder.addPbConstraint(new PbConstraint(wLits, weights, rhs));

            SliceProblem problem = builder.build();
            var compiledRes = SlicedDdnnfCompiler.compile(problem);
            assertTrue(compiledRes.isComplete(), "UVL slice should compile cleanly without hitting node bound");
            CompiledDdnnf ddnnf = compiledRes.ddnnf().orElseThrow();

            // Brute-force exact #SAT over all 2^totalVars worlds
            long bruteTotal = 0L;
            long bruteGrp0 = 0L;
            long bruteExt0 = 0L;
            int worlds = 1 << totalVars;
            boolean[] bits = new boolean[totalVars + 1];
            for (int mask = 0; mask < worlds; mask++) {
                for (int v = 1; v <= totalVars; v++) {
                    bits[v] = (mask & (1 << (v - 1))) != 0;
                }
                boolean sat = true;
                for (var pb : problem.constraints()) {
                    if (!pb.evaluate(bits)) {
                        sat = false;
                        break;
                    }
                }
                if (sat) {
                    bruteTotal++;
                    if (bits[grpVars[0]]) bruteGrp0++;
                    if (bits[extVars[0]]) bruteExt0++;
                }
            }

            assertEquals(BigInteger.valueOf(bruteTotal), ddnnf.countModels(),
                    "Total #SAT mismatch on UVL trial " + trial);
            assertEquals(BigInteger.valueOf(bruteGrp0), ddnnf.countModelsUnder(grpVars[0]),
                    "Conditioned #SAT (grp_0) mismatch on UVL trial " + trial);
            assertEquals(BigInteger.valueOf(bruteExt0), ddnnf.countModelsUnder(extVars[0]),
                    "Conditioned #SAT (ext_0) mismatch on UVL trial " + trial);
            assertEquals(BigInteger.valueOf(bruteTotal - bruteExt0), ddnnf.countModelsUnder(-extVars[0]),
                    "Complementary conditioned #SAT mismatch on UVL trial " + trial);
            tested++;
        }

        // 2. 60 arbitrary random mixed weighted PB + CNF formulas (6..12 vars)
        for (int trial = 0; trial < 60; trial++) {
            int nVars = 6 + (trial % 7); // 6..12 vars
            var names = new ArrayList<String>(nVars);
            for (int v = 1; v <= nVars; v++) names.add("v" + v);
            var cons = new ArrayList<PbConstraint>();
            int numCons = 3 + (trial % 5);
            for (int c = 0; c < numCons; c++) {
                int k = 2 + rng.nextInt(Math.min(5, nVars - 1));
                int[] lits = new int[k];
                int[] ws = new int[k];
                for (int i = 0; i < k; i++) {
                    int v = 1 + rng.nextInt(nVars);
                    lits[i] = rng.nextBoolean() ? v : -v;
                    ws[i] = rng.nextInt(7) - 2;
                    if (ws[i] == 0) ws[i] = 1;
                }
                int rhs = rng.nextInt(5) - 1;
                cons.add(new PbConstraint(lits, ws, rhs));
            }
            var problem = SliceProblem.of(nVars, names, cons);
            var res = SlicedDdnnfCompiler.compile(problem);
            assertTrue(res.isComplete());
            CompiledDdnnf ddnnf = res.ddnnf().orElseThrow();

            long bruteTotal = 0L;
            long bruteV1 = 0L;
            boolean[] bits = new boolean[nVars + 1];
            int worlds = 1 << nVars;
            for (int mask = 0; mask < worlds; mask++) {
                for (int v = 1; v <= nVars; v++) bits[v] = (mask & (1 << (v - 1))) != 0;
                boolean ok = true;
                for (var pb : cons) {
                    if (!pb.evaluate(bits)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    bruteTotal++;
                    if (bits[1]) bruteV1++;
                }
            }
            assertEquals(BigInteger.valueOf(bruteTotal), ddnnf.countModels(), "Random PB #SAT mismatch trial " + trial);
            assertEquals(BigInteger.valueOf(bruteV1), ddnnf.countModelsUnder(1), "Random PB #SAT(v1) mismatch trial " + trial);
            tested++;
        }
        assertEquals(100, tested);
    }

    @Test
    void theorem1ProofCheck1000Random4PhaseNonMonotonicCoiGraphsZeroDiscrepancies() {
        long t0 = System.nanoTime();
        var rng = new Random(2026112803L);
        int graphCount = 1_000;
        int mustCount = 0;
        int mayCount = 0;
        int neverCount = 0;

        for (int g = 0; g < graphCount; g++) {
            // 4-Phase Non-Monotonic Spring Graph (global exogenous space <= 2^10):
            // Phase 1: Multi-value property domain P0 (3 values, Exactly1) + 4 boolean profiles/props (b0..b3)
            //          + 2 unrelated distractor properties (d0, d1) with distractor constraints (outside COI(target)!)
            // Phase 2: User & regular @Configurations producing candidate beans u0, u1 (with optional @Primary)
            // Phase 3: Ordered auto-configurations a0, a1, a2 with non-monotonic @ConditionalOnMissingBean,
            //          @ConditionalOnBean, and @ConditionalOnSingleCandidate!
            // Phase 4: Target bean T depending on Phase 2/3 beans + independent distractor bean D depending on (d0, d1).

            int p0DomainSize = 3; // {0, 1, 2}
            boolean u0UsesProp = rng.nextBoolean();
            int u0MatchVal = rng.nextInt(p0DomainSize);
            int u0BoolIdx = rng.nextInt(4);
            boolean u0NegBool = rng.nextBoolean();
            boolean u0Primary = rng.nextBoolean();

            int u1BoolA = rng.nextInt(4);
            int u1BoolB = rng.nextInt(4);
            boolean u1NegB = rng.nextBoolean();
            boolean u1Primary = rng.nextBoolean();

            // Phase 3 auto-config a0: non-monotonic @ConditionalOnMissingBean(u0) AND (p0 == a0Val || b_k)
            int a0Val = rng.nextInt(p0DomainSize);
            int a0Bool = rng.nextInt(4);
            boolean a0MissingU0 = rng.nextBoolean();
            boolean a0OrMode = rng.nextBoolean();

            // Phase 3 auto-config a1: @ConditionalOnSingleCandidate({u0, u1, a0}) OR @ConditionalOnMissingBean({u0, u1})
            int a1Mode = rng.nextInt(3); // 0 = SingleCandidate(u0,u1,a0), 1 = MissingBean(u0,u1), 2 = OnBean(a0) & !b_k
            int a1GuardBool = rng.nextInt(4);
            boolean a1NegGuard = rng.nextBoolean();

            // Phase 4 target T: (a1 & guard) or (u0 & !a0) or (@ConditionalOnMissingBean(a1) & p0==tVal)
            int targetMode = rng.nextInt(3);
            int targetPropVal = rng.nextInt(p0DomainSize);
            int targetBool = rng.nextInt(4);
            boolean targetDependsOnU1Only = (g % 10 == 0); // sometimes u0/a0 are ALSO outside COI!

            // Distractor constraint over (d0, d1): forbids (false, false) half the time (non-trivial distractor count = 3 or 4)
            boolean distractorAtLeastOne = rng.nextBoolean();

            // --- 1. Global Brute-Force over ALL (3 * 2^4 * 2^2 = 192) valid global exogenous worlds ---
            long globalValid = 0L;
            long globalTargetTrue = 0L;
            for (int p0 = 0; p0 < p0DomainSize; p0++) {
                for (int bMask = 0; bMask < 16; bMask++) {
                    boolean[] b = new boolean[4];
                    for (int i = 0; i < 4; i++) b[i] = (bMask & (1 << i)) != 0;

                    for (int dMask = 0; dMask < 4; dMask++) {
                        boolean d0 = (dMask & 1) != 0;
                        boolean d1 = (dMask & 2) != 0;
                        if (distractorAtLeastOne && !d0 && !d1) {
                            continue;
                        }
                        globalValid++;

                        // Phase 2: user beans
                        boolean u0 = u0UsesProp
                                ? (p0 == u0MatchVal)
                                : (u0NegBool ? !b[u0BoolIdx] : b[u0BoolIdx]);
                        boolean u1 = b[u1BoolA] && (u1NegB ? !b[u1BoolB] : b[u1BoolB]);

                        // Phase 3: non-monotonic auto-configs
                        boolean a0Guard = a0OrMode ? (p0 == a0Val || b[a0Bool]) : (p0 == a0Val && b[a0Bool]);
                        boolean a0 = a0MissingU0 ? (!u0 && a0Guard) : (u0 && a0Guard);

                        boolean a1;
                        if (a1Mode == 0) {
                            int totalActive = (u0 ? 1 : 0) + (u1 ? 1 : 0) + (a0 ? 1 : 0);
                            int primActive = (u0 && u0Primary ? 1 : 0) + (u1 && u1Primary ? 1 : 0);
                            a1 = (totalActive == 1) || (primActive == 1);
                        } else if (a1Mode == 1) {
                            a1 = !u0 && !u1;
                        } else {
                            a1 = a0 && (a1NegGuard ? !b[a1GuardBool] : b[a1GuardBool]);
                        }

                        // Phase 4: target bean T (never depends on distractor d0, d1)
                        boolean target;
                        if (targetDependsOnU1Only) {
                            target = u1 && (p0 == targetPropVal);
                        } else if (targetMode == 0) {
                            target = a1 && b[targetBool];
                        } else if (targetMode == 1) {
                            target = (u0 && !a0) || a1;
                        } else {
                            target = !a1 && (p0 == targetPropVal);
                        }
                        if (target) {
                            globalTargetTrue++;
                        }
                    }
                }
            }

            var globalClass = globalTargetTrue == 0L ? TruthRegionEvaluation.Classification.NEVER
                    : globalTargetTrue == globalValid ? TruthRegionEvaluation.Classification.MUST
                    : TruthRegionEvaluation.Classification.MAY;

            // --- 2. Backward COI(target) Slice + Native PB-d-DNNF Compilation (distractors excluded!) ---
            var slice = new SliceBuilder();
            int[] p0Lits = new int[p0DomainSize];
            for (int v = 0; v < p0DomainSize; v++) p0Lits[v] = slice.variable("p0_" + v);
            slice.addExactly1(p0Lits);

            int[] bLits = new int[4];
            for (int i = 0; i < 4; i++) bLits[i] = slice.variable("b_" + i);

            // Only include nodes reachable backward in COI(target)
            int u1Lit = slice.variable("bean_u1");
            int u1Cond = slice.encodeFormula(SymbolicConfiguration.and(
                    SymbolicConfiguration.variable("b_" + u1BoolA),
                    u1NegB ? SymbolicConfiguration.not(SymbolicConfiguration.variable("b_" + u1BoolB))
                            : SymbolicConfiguration.variable("b_" + u1BoolB)));
            addIff(slice, u1Lit, u1Cond);

            int targetLit = slice.variable("bean_target");
            if (targetDependsOnU1Only) {
                int tCond = slice.encodeFormula(SymbolicConfiguration.and(
                        SymbolicConfiguration.variable("bean_u1"),
                        SymbolicConfiguration.variable("p0_" + targetPropVal)));
                addIff(slice, targetLit, tCond);
            } else {
                int u0Lit = slice.variable("bean_u0");
                int u0Cond = u0UsesProp
                        ? p0Lits[u0MatchVal]
                        : (u0NegBool ? -bLits[u0BoolIdx] : bLits[u0BoolIdx]);
                addIff(slice, u0Lit, u0Cond);

                int a0Lit = slice.variable("bean_a0");
                var p0Term = SymbolicConfiguration.variable("p0_" + a0Val);
                var bTerm = SymbolicConfiguration.variable("b_" + a0Bool);
                var a0GuardFormula = a0OrMode
                        ? SymbolicConfiguration.or(p0Term, bTerm)
                        : SymbolicConfiguration.and(p0Term, bTerm);
                var a0Full = SymbolicConfiguration.and(
                        a0MissingU0 ? SymbolicConfiguration.not(SymbolicConfiguration.variable("bean_u0"))
                                : SymbolicConfiguration.variable("bean_u0"),
                        a0GuardFormula);
                addIff(slice, a0Lit, slice.encodeFormula(a0Full));

                int a1Lit = slice.variable("bean_a1");
                if (a1Mode == 0) {
                    // Encode @ConditionalOnSingleCandidate({u0, u1, a0}) via native PB in SliceBuilder!
                    addSingleCandidatePb(slice, new int[] { u0Lit, u1Lit, a0Lit },
                            new boolean[] { u0Primary, u1Primary, false }, a1Lit);
                } else if (a1Mode == 1) {
                    int cond = slice.encodeFormula(SymbolicConfiguration.and(
                            SymbolicConfiguration.not(SymbolicConfiguration.variable("bean_u0")),
                            SymbolicConfiguration.not(SymbolicConfiguration.variable("bean_u1"))));
                    addIff(slice, a1Lit, cond);
                } else {
                    int cond = slice.encodeFormula(SymbolicConfiguration.and(
                            SymbolicConfiguration.variable("bean_a0"),
                            a1NegGuard ? SymbolicConfiguration.not(SymbolicConfiguration.variable("b_" + a1GuardBool))
                                    : SymbolicConfiguration.variable("b_" + a1GuardBool)));
                    addIff(slice, a1Lit, cond);
                }

                int tCond;
                if (targetMode == 0) {
                    tCond = slice.encodeFormula(SymbolicConfiguration.and(
                            SymbolicConfiguration.variable("bean_a1"),
                            SymbolicConfiguration.variable("b_" + targetBool)));
                } else if (targetMode == 1) {
                    tCond = slice.encodeFormula(SymbolicConfiguration.or(
                            SymbolicConfiguration.and(
                                    SymbolicConfiguration.variable("bean_u0"),
                                    SymbolicConfiguration.not(SymbolicConfiguration.variable("bean_a0"))),
                            SymbolicConfiguration.variable("bean_a1")));
                } else {
                    tCond = slice.encodeFormula(SymbolicConfiguration.and(
                            SymbolicConfiguration.not(SymbolicConfiguration.variable("bean_a1")),
                            SymbolicConfiguration.variable("p0_" + targetPropVal)));
                }
                addIff(slice, targetLit, tCond);
            }

            var compileRes = SlicedDdnnfCompiler.compile(slice.build());
            assertTrue(compileRes.isComplete(), "COI slice bounded on graph " + g);
            CompiledDdnnf ddnnf = compileRes.ddnnf().orElseThrow();

            BigInteger coiValid = ddnnf.countModels();
            BigInteger coiTrue = ddnnf.countModelsUnder(targetLit);
            var coiClass = ddnnf.classify(targetLit);

            // Theorem-1 Assertions:
            // 1. Zero discrepancies on MUST / MAY / NEVER classification
            assertEquals(globalClass, coiClass, "Theorem-1 classification mismatch on graph " + g);
            // 2. Exact #SAT exposure ratio invariance: coiTrue / coiValid == globalTargetTrue / globalValid
            assertEquals(
                    coiTrue.multiply(BigInteger.valueOf(globalValid)),
                    BigInteger.valueOf(globalTargetTrue).multiply(coiValid),
                    "Theorem-1 exact #SAT ratio mismatch on graph " + g
                            + " (coi=" + coiTrue + "/" + coiValid + " vs global=" + globalTargetTrue + "/" + globalValid + ")");
            // 3. Exact scaled world count matching distractor factor (3 or 4)
            long expectedDistractorWorlds = distractorAtLeastOne ? 3L : 4L;
            assertEquals(BigInteger.valueOf(globalValid), coiValid.multiply(BigInteger.valueOf(expectedDistractorWorlds)),
                    "Theorem-1 distractor scaling mismatch on graph " + g);

            switch (coiClass) {
                case MUST -> mustCount++;
                case MAY -> mayCount++;
                case NEVER -> neverCount++;
                default -> fail("Unexpected UNKNOWN in Theorem-1 graph " + g);
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(mustCount > 0 && mayCount > 0 && neverCount > 0,
                "Expected diverse MUST/MAY/NEVER across 1,000 random graphs, got MUST=" + mustCount
                        + " MAY=" + mayCount + " NEVER=" + neverCount);
        System.out.printf(Locale.ROOT,
                "[WP2.5-THEOREM1] graphs=%d discrepancies=0 MUST=%d MAY=%d NEVER=%d elapsedMs=%d%n",
                graphCount, mustCount, mayCount, neverCount, elapsedMs);
    }

    @Test
    void boot3514PerCoiSliceDdnnfCompilesUnderTwoSecondsWithZeroTimeouts() throws Exception {
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

        var detailedSlices = ConeOfInfluenceSlicer.computeDetailedSlices(
                scan, BytecodeAutoConfigScanner.UserSpringSummary.empty(), Map.of());
        assertTrue(detailedSlices.size() >= 300,
                () -> "Expected >= 300 Boot 3.5.14 COI slices, got " + detailedSlices.size());

        long compileNanos = 0L;
        int compiledOk = 0;
        int boundedCount = 0;
        long totalNodes = 0L;
        long totalCacheHits = 0L;
        int mustTargets = 0;
        int mayTargets = 0;
        int neverTargets = 0;

        // Pre-index Boot 3.5.14 configurations & property domains ONCE, then encode each COI slice with
        // native PB Exactly1 property domains + PB @ConditionalOnSingleCandidate (zero quadratic CNF bloat).
        var cfgByFqn = new HashMap<String, BytecodeAutoConfigScanner.StarterConfiguration>(scan.configurations().size() * 2);
        for (var cfg : scan.configurations()) {
            cfgByFqn.put(cfg.classFqn(), cfg);
        }
        var importedBy = new HashMap<String, Set<String>>();
        var propDomains = new TreeMap<String, List<String>>();
        var propHints = new TreeMap<String, LinkedHashSet<String>>();
        for (var cfg : scan.configurations()) {
            for (String imp : cfg.importedClasses()) {
                if (cfgByFqn.containsKey(imp)) importedBy.computeIfAbsent(imp, k -> new LinkedHashSet<>()).add(cfg.classFqn());
            }
            for (String nest : cfg.nestedConfigurationClasses()) {
                if (cfgByFqn.containsKey(nest)) importedBy.computeIfAbsent(nest, k -> new LinkedHashSet<>()).add(cfg.classFqn());
            }
            int dollar = cfg.classFqn().lastIndexOf('$');
            if (dollar > 0) {
                String outer = cfg.classFqn().substring(0, dollar);
                if (cfgByFqn.containsKey(outer)) importedBy.computeIfAbsent(cfg.classFqn(), k -> new LinkedHashSet<>()).add(outer);
            }
            for (var c : cfg.conditions()) collectPropHintsForBench(c, propHints);
            for (var b : cfg.beans()) {
                for (var c : b.conditions()) collectPropHintsForBench(c, propHints);
            }
        }
        propHints.forEach((k, vals) -> {
            var ordered = new LinkedHashSet<String>();
            ordered.add(StarterAutoConfigSatReasoner.MISSING_VALUE_TOKEN);
            ordered.addAll(vals);
            ordered.add(StarterAutoConfigSatReasoner.OTHER_VALUE_TOKEN);
            propDomains.put(k, List.copyOf(ordered));
        });

        for (var slice : detailedSlices) {
            var builder = new SliceBuilder();
            int targetLit = encodeBootCoiSliceNativePb(slice, cfgByFqn, importedBy, propDomains, builder);
            SliceProblem problem = builder.build();

            var res = SlicedDdnnfCompiler.compile(problem);
            compileNanos += res.elapsedNanos();
            if (res.isComplete()) {
                compiledOk++;
                CompiledDdnnf ddnnf = res.ddnnf().orElseThrow();
                totalNodes += ddnnf.nodeCount();
                totalCacheHits += res.cacheHits();
                assertTrue(ddnnf.countModels().signum() > 0, "COI slice must have >= 1 valid world: " + slice.targetId());
                switch (ddnnf.classify(targetLit)) {
                    case MUST -> mustTargets++;
                    case MAY -> mayTargets++;
                    case NEVER -> neverTargets++;
                    default -> fail("Unexpected UNKNOWN for Boot 3.5.14 target " + slice.targetId());
                }
            } else {
                boundedCount++;
            }
        }

        double compileMs = compileNanos / 1_000_000.0;
        System.out.printf(Locale.ROOT,
                "[WP2.3-BOOT35-DDNNF] targets=%d compiled=%d bounded=%d compileMs=%.1f totalNodes=%d cacheHits=%d MUST=%d MAY=%d NEVER=%d%n",
                detailedSlices.size(), compiledOk, boundedCount, compileMs, totalNodes, totalCacheHits,
                mustTargets, mayTargets, neverTargets);

        assertEquals(0, boundedCount, "Expected 0% bounded/timeout across all Boot 3.5.14 COI slices");
        assertEquals(detailedSlices.size(), compiledOk);
        assertTrue(compileMs < 2_000.0,
                () -> "Expected Boot 3.5.14 COI d-DNNF compile < 2,000 ms, took " + compileMs + " ms");
    }

    private static void collectPropHintsForBench(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            Map<String, LinkedHashSet<String>> out) {
        String fqn = cond.annotationFqn();
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        if ("ConditionalOnProperty".equals(simple) || "ConditionalOnBooleanProperty".equals(simple)) {
            boolean isBool = "ConditionalOnBooleanProperty".equals(simple);
            String prefix = cond.attributes().getOrDefault("prefix", List.of()).stream().findFirst().orElse("").trim();
            while (prefix.endsWith(".")) prefix = prefix.substring(0, prefix.length() - 1);
            String hv = cond.attributes().getOrDefault("havingValue", isBool ? List.of("true") : List.of(""))
                    .stream().findFirst().orElse(isBool ? "true" : "").trim().toLowerCase(Locale.ROOT);
            var names = new ArrayList<String>();
            names.addAll(cond.attributes().getOrDefault("name", List.of()));
            names.addAll(cond.attributes().getOrDefault("value", List.of()));
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                String key = BytecodeAutoConfigScanner.normalizePropertyKey(prefix.isEmpty() ? n.trim() : prefix + "." + n.trim());
                var set = out.computeIfAbsent(key, k -> new LinkedHashSet<>());
                if (hv.isEmpty()) { set.add("true"); set.add("false"); }
                else { set.add(hv); if ("true".equals(hv)) set.add("false"); else if ("false".equals(hv)) set.add("true"); }
            }
        } else if ("ConditionalOnThreading".equals(simple)) {
            var set = out.computeIfAbsent("spring.threads.virtual.enabled", k -> new LinkedHashSet<>());
            set.add("true"); set.add("false");
        }
    }

    private static int encodeBootCoiSliceNativePb(
            ConeOfInfluenceSlicer.DetailedSlice slice,
            Map<String, BytecodeAutoConfigScanner.StarterConfiguration> cfgByFqn,
            Map<String, Set<String>> importedBy,
            Map<String, List<String>> propDomains,
            SliceBuilder b) {
        // Build index of sliced configs & beans, then compute exact backward functional cone from targetId
        // (eliminating dead-sink sibling beans in upstream @AutoConfigureAfter configs that never feed targetId).
        var beanById = new HashMap<String, BytecodeAutoConfigScanner.StarterBean>(slice.beanNodeIds().size() * 2);
        var beansByType = new HashMap<String, List<String>>();
        var beansByName = new HashMap<String, List<String>>();
        for (String fqn : slice.configFqns()) {
            var cfg = cfgByFqn.get(fqn);
            if (cfg == null) continue;
            for (var bean : cfg.beans()) {
                String bId = ConeOfInfluenceSlicer.beanNodeId(bean);
                if (!slice.beanNodeIds().contains(bId)) continue;
                beanById.put(bId, bean);
                if (!bean.returnTypeFqn().equals("void") && !bean.returnTypeFqn().equals("java.lang.Object")) {
                    beansByType.computeIfAbsent(bean.returnTypeFqn(), k -> new ArrayList<>()).add(bId);
                }
                for (String bn : bean.beanNames()) {
                    beansByName.computeIfAbsent(bn, k -> new ArrayList<>()).add(bId);
                }
            }
        }

        var neededCfgs = new LinkedHashSet<String>();
        var neededBeans = new LinkedHashSet<String>();
        var work = new ArrayDeque<String>();
        if (beanById.containsKey(slice.targetId())) {
            neededBeans.add(slice.targetId());
            work.add("B:" + slice.targetId());
        }
        while (!work.isEmpty()) {
            String item = work.poll();
            if (item.startsWith("B:")) {
                String bId = item.substring(2);
                var bean = beanById.get(bId);
                if (bean == null) continue;
                if (neededCfgs.add(bean.declaringClassFqn())) {
                    work.add("C:" + bean.declaringClassFqn());
                }
                for (var cond : bean.conditions()) {
                    enqueueBeanCondProducers(cond, bId, bean.returnTypeFqn(), beansByType, beansByName, neededBeans, work);
                }
            } else {
                String cfgFqn = item.substring(2);
                var cfg = cfgByFqn.get(cfgFqn);
                if (cfg == null) continue;
                for (String parent : importedBy.getOrDefault(cfgFqn, Set.of())) {
                    if (slice.configFqns().contains(parent) && neededCfgs.add(parent)) {
                        work.add("C:" + parent);
                    }
                }
                for (var cond : cfg.conditions()) {
                    enqueueBeanCondProducers(cond, null, null, beansByType, beansByName, neededBeans, work);
                }
            }
        }

        // Order active configs deterministically
        var cfgs = new ArrayList<BytecodeAutoConfigScanner.StarterConfiguration>(neededCfgs.size());
        for (String fqn : new TreeSet<>(neededCfgs)) {
            var c = cfgByFqn.get(fqn);
            if (c != null) cfgs.add(c);
        }
        var cfgLit = new HashMap<String, Integer>(cfgs.size() * 2);
        var beanLit = new HashMap<String, Integer>(neededBeans.size() * 2);
        for (var cfg : cfgs) {
            cfgLit.put(cfg.classFqn(), b.newAuxiliaryVar());
            for (var bean : cfg.beans()) {
                String bId = ConeOfInfluenceSlicer.beanNodeId(bean);
                if (neededBeans.contains(bId)) {
                    beanLit.put(bId, bId.equals(slice.targetId()) ? b.variable(bId) : b.newAuxiliaryVar());
                }
            }
        }
        var usedProps = new TreeSet<String>();
        var priorByType = new HashMap<String, List<Integer>>();
        var priorByName = new HashMap<String, List<Integer>>();

        for (var cfg : cfgs) {
            var terms = new ArrayList<Integer>();
            var parents = importedBy.getOrDefault(cfg.classFqn(), Set.of());
            if (!parents.isEmpty() && !cfg.metadataSource().contains("AutoConfiguration.imports")) {
                var pLits = new ArrayList<Integer>();
                for (String p : parents) {
                    Integer pl = cfgLit.get(p);
                    if (pl != null) pLits.add(pl);
                }
                if (!pLits.isEmpty()) terms.add(encodeOrGate(b, pLits));
            }
            for (var cond : cfg.conditions()) {
                int cl = encodeBootCondNativePb(cond, cfg.classFqn(), null, propDomains, usedProps, priorByType, priorByName, b);
                if (cl != 0) terms.add(cl);
            }
            int cVar = cfgLit.get(cfg.classFqn());
            if (terms.isEmpty()) b.addClause(cVar);
            else encodeAndIff(b, cVar, terms);

            for (var bean : cfg.beans()) {
                String bId = ConeOfInfluenceSlicer.beanNodeId(bean);
                Integer bVar = beanLit.get(bId);
                if (bVar == null) continue;
                var bTerms = new ArrayList<Integer>();
                bTerms.add(cVar);
                for (var cond : bean.conditions()) {
                    int cl = encodeBootCondNativePb(cond, cfg.classFqn(), bean.returnTypeFqn(), propDomains, usedProps, priorByType, priorByName, b);
                    if (cl != 0) bTerms.add(cl);
                }
                encodeAndIff(b, bVar, bTerms);
                if (!bean.returnTypeFqn().equals("void") && !bean.returnTypeFqn().equals("java.lang.Object")) {
                    priorByType.computeIfAbsent(bean.returnTypeFqn(), k -> new ArrayList<>()).add(bVar);
                }
                for (String bn : bean.beanNames()) {
                    priorByName.computeIfAbsent(bn, k -> new ArrayList<>()).add(bVar);
                }
            }
        }
        // Native Exactly1 PB constraints for every referenced property domain (linear 2n lits, 0 quadratic pairs!)
        for (String key : usedProps) {
            List<String> dom = propDomains.getOrDefault(key, List.of(
                    StarterAutoConfigSatReasoner.MISSING_VALUE_TOKEN, "true", "false",
                    StarterAutoConfigSatReasoner.OTHER_VALUE_TOKEN));
            int[] lits = new int[dom.size()];
            for (int i = 0; i < dom.size(); i++) {
                lits[i] = b.variable(StarterAutoConfigSatReasoner.propValueVarName(key, dom.get(i)));
            }
            b.addExactly1(lits);
        }
        return beanLit.getOrDefault(slice.targetId(), b.variable(slice.targetId()));
    }

    private static void enqueueBeanCondProducers(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            String selfBeanId,
            String beanReturnType,
            Map<String, List<String>> beansByType,
            Map<String, List<String>> beansByName,
            Set<String> neededBeans,
            ArrayDeque<String> work) {
        String fqn = cond.annotationFqn();
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        if (!"ConditionalOnBean".equals(simple) && !"ConditionalOnMissingBean".equals(simple)
                && !"ConditionalOnSingleCandidate".equals(simple)) {
            return;
        }
        var types = new ArrayList<String>();
        types.addAll(cond.attributes().getOrDefault("value", List.of()));
        types.addAll(cond.attributes().getOrDefault("type", List.of()));
        var names = cond.attributes().getOrDefault("name", List.of());
        if (types.isEmpty() && names.isEmpty() && beanReturnType != null && !beanReturnType.equals("void")) {
            types.add(beanReturnType);
        }
        for (String t : types) {
            for (String pId : beansByType.getOrDefault(t, List.of())) {
                if (!pId.equals(selfBeanId) && neededBeans.add(pId)) work.add("B:" + pId);
            }
        }
        for (String n : names) {
            for (String pId : beansByName.getOrDefault(n, List.of())) {
                if (!pId.equals(selfBeanId) && neededBeans.add(pId)) work.add("B:" + pId);
            }
        }
    }

    private static int encodeBootCondNativePb(
            BytecodeAutoConfigScanner.ConditionAnnotation cond,
            String cfgFqn,
            String beanReturnType,
            Map<String, List<String>> propDomains,
            Set<String> usedProps,
            Map<String, List<Integer>> priorByType,
            Map<String, List<Integer>> priorByName,
            SliceBuilder b) {
        String fqn = cond.annotationFqn();
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        if ("ConditionalOnProperty".equals(simple) || "ConditionalOnBooleanProperty".equals(simple)) {
            boolean isBool = "ConditionalOnBooleanProperty".equals(simple);
            String prefix = cond.attributes().getOrDefault("prefix", List.of()).stream().findFirst().orElse("").trim();
            while (prefix.endsWith(".")) prefix = prefix.substring(0, prefix.length() - 1);
            String hv = cond.attributes().getOrDefault("havingValue", isBool ? List.of("true") : List.of(""))
                    .stream().findFirst().orElse(isBool ? "true" : "").trim();
            boolean matchIfMissing = Boolean.parseBoolean(
                    cond.attributes().getOrDefault("matchIfMissing", List.of("false")).getFirst());
            var names = new ArrayList<String>();
            names.addAll(cond.attributes().getOrDefault("name", List.of()));
            names.addAll(cond.attributes().getOrDefault("value", List.of()));
            var conj = new ArrayList<Integer>();
            for (String n : names) {
                if (n == null || n.isBlank()) continue;
                String key = BytecodeAutoConfigScanner.normalizePropertyKey(prefix.isEmpty() ? n.trim() : prefix + "." + n.trim());
                usedProps.add(key);
                List<String> dom = propDomains.getOrDefault(key, List.of(
                        StarterAutoConfigSatReasoner.MISSING_VALUE_TOKEN, "true", "false",
                        StarterAutoConfigSatReasoner.OTHER_VALUE_TOKEN));
                var matched = new ArrayList<Integer>();
                for (String v : dom) {
                    boolean ok = StarterAutoConfigSatReasoner.MISSING_VALUE_TOKEN.equals(v) ? matchIfMissing
                            : (hv.isEmpty() ? !"false".equalsIgnoreCase(v) : hv.equalsIgnoreCase(v));
                    if (ok) matched.add(b.variable(StarterAutoConfigSatReasoner.propValueVarName(key, v)));
                }
                if (matched.isEmpty()) {
                    int f = b.newAuxiliaryVar();
                    b.addClause(-f);
                    conj.add(f);
                } else {
                    conj.add(encodeOrGate(b, matched));
                }
            }
            return conj.isEmpty() ? 0 : encodeAndGate(b, conj);
        }
        if ("ConditionalOnThreading".equals(simple)) {
            String key = "spring.threads.virtual.enabled";
            usedProps.add(key);
            int virtLit = b.variable(StarterAutoConfigSatReasoner.propValueVarName(key, "true"));
            String val = cond.attributes().getOrDefault("value", List.of()).stream().findFirst().orElse("");
            return val.endsWith(".VIRTUAL") ? virtLit : -virtLit;
        }
        if ("ConditionalOnClass".equals(simple) || "ConditionalOnMissingClass".equals(simple)) {
            boolean want = "ConditionalOnClass".equals(simple);
            var targets = new ArrayList<String>();
            targets.addAll(cond.attributes().getOrDefault("value", List.of()));
            targets.addAll(cond.attributes().getOrDefault("name", List.of()));
            var terms = new ArrayList<Integer>();
            for (String t : targets) {
                int v = b.variable("class:" + t);
                terms.add(want ? v : -v);
            }
            return terms.isEmpty() ? 0 : encodeAndGate(b, terms);
        }
        if ("ConditionalOnBean".equals(simple) || "ConditionalOnMissingBean".equals(simple)
                || "ConditionalOnSingleCandidate".equals(simple)) {
            var types = new ArrayList<String>();
            types.addAll(cond.attributes().getOrDefault("value", List.of()));
            types.addAll(cond.attributes().getOrDefault("type", List.of()));
            var names = cond.attributes().getOrDefault("name", List.of());
            if (types.isEmpty() && names.isEmpty() && beanReturnType != null && !beanReturnType.equals("void")) {
                types.add(beanReturnType);
            }
            var cands = new ArrayList<Integer>();
            for (String t : types) {
                cands.add(b.variable("extbean:" + t));
                cands.addAll(priorByType.getOrDefault(t, List.of()));
            }
            for (String n : names) {
                cands.add(b.variable("extname:" + n));
                cands.addAll(priorByName.getOrDefault(n, List.of()));
            }
            if (cands.isEmpty()) return 0;
            if ("ConditionalOnSingleCandidate".equals(simple)) {
                int[] arr = cands.stream().distinct().mapToInt(Integer::intValue).toArray();
                if (arr.length == 1) return arr[0];
                return reifyExactly1InSlice(b, arr);
            }
            int any = encodeOrGate(b, cands);
            return "ConditionalOnMissingBean".equals(simple) ? -any : any;
        }
        if (cond.opaque() || simple.startsWith("Conditional")) {
            return b.variable("opaque:" + cfgFqn + "#" + simple);
        }
        return 0;
    }

    private static int encodeOrGate(SliceBuilder b, List<Integer> lits) {
        var unique = lits.stream().distinct().toList();
        if (unique.size() == 1) return unique.getFirst();
        int g = b.newAuxiliaryVar();
        int[] longClause = new int[unique.size() + 1];
        longClause[0] = -g;
        for (int i = 0; i < unique.size(); i++) {
            int l = unique.get(i);
            b.addClause(g, -l);
            longClause[i + 1] = l;
        }
        b.addClause(longClause);
        return g;
    }

    private static int encodeAndGate(SliceBuilder b, List<Integer> lits) {
        var unique = lits.stream().distinct().toList();
        if (unique.size() == 1) return unique.getFirst();
        int g = b.newAuxiliaryVar();
        encodeAndIff(b, g, unique);
        return g;
    }

    private static void encodeAndIff(SliceBuilder b, int target, List<Integer> lits) {
        var unique = lits.stream().distinct().toList();
        if (unique.size() == 1) {
            addIff(b, target, unique.getFirst());
            return;
        }
        int[] longClause = new int[unique.size() + 1];
        longClause[0] = target;
        for (int i = 0; i < unique.size(); i++) {
            int l = unique.get(i);
            b.addClause(-target, l);
            longClause[i + 1] = -l;
        }
        b.addClause(longClause);
    }

    @Test
    void uniformSamplerPassesChiSquareUniformityDeterminismAndSymmetricMayPairCheck() {
        // Asymmetric PB + CNF formula with a non-power-of-2 model count:
        // Exactly1(a0, a1, a2) [3 choices] AND (b0 + 2*b1 + 3*b2 >= 3) [5 choices out of 8]
        // minus 1 forbidden pair (a0 & b2) -> let's verify exact count = 11 or 12 (non-power-of-2!)
        var builder = new SliceBuilder();
        int a0 = builder.variable("a0");
        int a1 = builder.variable("a1");
        int a2 = builder.variable("a2");
        int b0 = builder.variable("b0");
        int b1 = builder.variable("b1");
        int b2 = builder.variable("b2");
        builder.addExactly1(a0, a1, a2);
        builder.addPbConstraint(new PbConstraint(new int[] { b0, b1, b2 }, new int[] { 1, 2, 3 }, 3));
        builder.addClause(-a0, -b2); // breaks independence between a and b!

        SliceProblem problem = builder.build();
        var compiled = SlicedDdnnfCompiler.compile(problem);
        assertTrue(compiled.isComplete());
        CompiledDdnnf ddnnf = compiled.ddnnf().orElseThrow();

        // Exact valid worlds:
        // b in {(1,1,0)=3, (0,0,1)=3, (1,0,1)=4, (0,1,1)=5, (1,1,1)=6} -> 5 choices for b (4 have b2=1, 1 has b2=0).
        // a1 -> 5 worlds, a2 -> 5 worlds, a0 -> only b2=0 -> 1 world. Total = 11 valid worlds (prime!)
        assertEquals(BigInteger.valueOf(11), ddnnf.countModels());

        int sampleSize = 11_000; // Expected exactly 1,000 per world!
        long seed = 2026112804L;
        var samples = ddnnf.sampleUniform(sampleSize, seed);
        assertEquals(sampleSize, samples.size());

        // I2 strict determinism: same seed yields bit-identical samples
        assertEquals(samples, ddnnf.sampleUniform(sampleSize, seed));

        var counts = new TreeMap<String, Integer>();
        for (var world : samples) {
            boolean[] bits = new boolean[7];
            bits[a0] = world.get("a0");
            bits[a1] = world.get("a1");
            bits[a2] = world.get("a2");
            bits[b0] = world.get("b0");
            bits[b1] = world.get("b1");
            bits[b2] = world.get("b2");
            for (var pb : problem.constraints()) {
                assertTrue(pb.evaluate(bits), "Sampled world violated constraint " + pb + ": " + world);
            }
            String sig = "" + (bits[a0] ? '1' : '0') + (bits[a1] ? '1' : '0') + (bits[a2] ? '1' : '0')
                    + (bits[b0] ? '1' : '0') + (bits[b1] ? '1' : '0') + (bits[b2] ? '1' : '0');
            counts.merge(sig, 1, Integer::sum);
        }

        assertEquals(11, counts.size(), "All 11 valid worlds must be sampled");
        double expected = sampleSize / 11.0;
        double chiSquare = 0.0;
        double maxRelDev = 0.0;
        for (int obs : counts.values()) {
            double diff = obs - expected;
            chiSquare += (diff * diff) / expected;
            maxRelDev = Math.max(maxRelDev, Math.abs(diff) / expected);
        }
        // df = 10; chi^2_{0.001}(10) = 29.59 (and uniform sample typically ~4-16)
        final double finalChiSquare = chiSquare;
        final double finalMaxRelDev = maxRelDev;
        assertTrue(finalChiSquare < 29.59,
                () -> "Uniform sampler failed chi-square goodness-of-fit: chi2=" + finalChiSquare + ", counts=" + counts);
        assertTrue(finalMaxRelDev < 0.12,
                () -> "Empirical max relative deviation too large: " + finalMaxRelDev);

        // Symmetric (witness+, witness-) pair for MAY literal a0 (1/11 exposure ratio!)
        var pairOpt = ddnnf.sampleSymmetricMayWitnessPair(a0, seed);
        assertTrue(pairOpt.isPresent());
        var pair = pairOpt.orElseThrow();
        assertTrue(pair.witnessPositive().get("a0"));
        assertFalse(pair.witnessPositive().get("b2"), "When a0=true, b2 must be false");
        assertFalse(pair.witnessNegative().get("a0"));
        assertEquals(BigInteger.ONE, pair.trueWorldCount());
        assertEquals(BigInteger.TEN, pair.falseWorldCount());
        assertEquals(BigInteger.valueOf(11), pair.validWorldCount());
        assertTrue(pair.mayExposureRatio().compareTo(BigDecimal.ZERO) > 0
                && pair.mayExposureRatio().compareTo(BigDecimal.ONE) < 0);

        // Forced MUST / NEVER literals return Optional.empty() for symmetric MAY pair
        int forcedAlwaysTrue = builder.variable("always_true");
        builder.addClause(forcedAlwaysTrue);
        var withMust = SlicedDdnnfCompiler.compile(builder.build()).ddnnf().orElseThrow();
        assertTrue(withMust.sampleSymmetricMayWitnessPair(forcedAlwaysTrue, seed).isEmpty());
        assertEquals(TruthRegionEvaluation.Classification.MUST, withMust.classify(forcedAlwaysTrue));
    }

    @Test
    void boundedNodeLimitAbortsCleanlyWithDdnnfCountBoundedGapCode() {
        var builder = new SliceBuilder();
        int[] xs = new int[10];
        for (int i = 0; i < 10; i++) xs[i] = builder.variable("x" + i);
        // Coupled XOR-like / cardinality chain requiring > 5 d-DNNF nodes
        builder.addAtLeastK(4, xs);
        builder.addAtMostK(6, xs);

        var bounded = SlicedDdnnfCompiler.compile(builder.build(), new SlicedDdnnfCompiler.Limits(5, 64));
        assertTrue(bounded.bounded());
        assertFalse(bounded.isComplete());
        assertTrue(bounded.ddnnf().isEmpty());
        assertEquals(Optional.of(SlicedDdnnfCompiler.GAP_DDNNF_COUNT_BOUNDED), bounded.gapReasonCode());

        // With normal limit, compiles completely to exact C(10,4)+C(10,5)+C(10,6) = 210+252+210 = 672
        var exact = SlicedDdnnfCompiler.compile(builder.build(), SlicedDdnnfCompiler.Limits.defaults());
        assertTrue(exact.isComplete());
        assertEquals(BigInteger.valueOf(672), exact.ddnnf().orElseThrow().countModels());
    }

    @Test
    void multiWavePbBcpCascadeReEnqueuingSameConstraintsBeyondInitialQueueLengthRemainsExact() {
        // Construct a 2-constraint alternating PB cascade over 22 variables so that only 3 constraints exist
        // (initial bcpQueue capacity = 16), yet a single root PB-BCP wave bounces between c0 and c1
        // 22 times (> 16!) by re-enqueueing c0 and c1 after each pop as exponentially weighted terms force
        // alternating variables x0 -> y0 -> x1 -> y1 -> ... -> x10 -> y10!
        int k = 11; // 22 variables total + 2 free tail variables = 24 vars, exact model count = 2^2 = 4!
        var builder = new SliceBuilder();
        int[] x = new int[k];
        int[] y = new int[k];
        for (int i = 0; i < k; i++) {
            x[i] = builder.variable("mx_" + i);
            y[i] = builder.variable("my_" + i);
        }
        int freeA = builder.variable("mfree_a");
        int freeB = builder.variable("mfree_b");

        // Trigger x[0] = true (1 constraint)
        builder.addClause(x[0]);

        // c_even (forces y[i] = true whenever x[i] = true and prior y[0..i-1] are true):
        // Use super-increasing weights on (-x[i], y[i]) pairs:
        // For i = 0..k-1: weight 2^(k-1-i) on (-x[i]) and 2^(k-1-i) on (y[i]), with rhs = sum(2^j) = 2^k - 1!
        // Trace:
        //  - Initially maxRem = 2*(2^k - 1), rhs = 2^k - 1, margin = 2^k - 1.
        //  - x[0]=T falsifies -x[0] (weight 2^(k-1)) -> margin drops to 2^(k-1) - 1 < 2^(k-1) -> forces y[0]=T!
        //    (y[0]=T reduces both remRhs & maxRem by 2^(k-1), leaving margin = 2^(k-1)-1, which does NOT force y[1]!)
        //  - Next constraint c_odd has weights 2^(k-2-i) on (-y[i]) and (x[i+1]) for i = 0..k-2, rhs = 2^(k-1) - 1!
        //    y[0]=T falsifies -y[0] (weight 2^(k-2)) -> margin drops to 2^(k-2) - 1 -> forces x[1]=T!
        //  - x[1]=T re-enqueues c_even (now falsifying -x[1] of weight 2^(k-2)) -> forces y[1]=T!
        //  - Alternates c_even -> c_odd -> c_even -> c_odd ... 21 times (> initial bcpQueue length 16) in ONE wave!
        int[] evenLits = new int[2 * k];
        int[] evenW = new int[2 * k];
        int evenRhs = (1 << k) - 1;
        for (int i = 0; i < k; i++) {
            int w = 1 << (k - 1 - i);
            evenLits[2 * i] = -x[i];
            evenW[2 * i] = w;
            evenLits[2 * i + 1] = y[i];
            evenW[2 * i + 1] = w;
        }
        builder.addPbConstraint(new PbConstraint(evenLits, evenW, evenRhs));

        int m = k - 1;
        int[] oddLits = new int[2 * m];
        int[] oddW = new int[2 * m];
        int oddRhs = (1 << m) - 1;
        for (int i = 0; i < m; i++) {
            int w = 1 << (m - 1 - i);
            oddLits[2 * i] = -y[i];
            oddW[2 * i] = w;
            oddLits[2 * i + 1] = x[i + 1];
            oddW[2 * i + 1] = w;
        }
        builder.addPbConstraint(new PbConstraint(oddLits, oddW, oddRhs));

        // Ensure freeA and freeB remain unconstrained (exact #SAT = 4)
        SliceProblem problem = builder.build();
        assertEquals(3, problem.constraints().size(), "Only 3 constraints so initial bcpQueue length is 16");

        var res = SlicedDdnnfCompiler.compile(problem);
        assertTrue(res.isComplete(), "Multi-wave PB-BCP cascade must resize bcpQueue cleanly without AIOOBE");
        CompiledDdnnf ddnnf = res.ddnnf().orElseThrow();
        assertEquals(BigInteger.valueOf(4), ddnnf.countModels());
        for (int i = 0; i < k; i++) {
            assertEquals(TruthRegionEvaluation.Classification.MUST, ddnnf.classify(x[i]),
                    "Cascade must force x[" + i + "]=true");
            assertEquals(TruthRegionEvaluation.Classification.MUST, ddnnf.classify(y[i]),
                    "Cascade must force y[" + i + "]=true");
        }
        assertEquals(TruthRegionEvaluation.Classification.MAY, ddnnf.classify(freeA));
        assertEquals(TruthRegionEvaluation.Classification.MAY, ddnnf.classify(freeB));
    }

    private static void addIff(SliceBuilder b, int lhs, int rhs) {
        b.addClause(-lhs, rhs);
        b.addClause(lhs, -rhs);
    }

    private static void addSingleCandidatePb(SliceBuilder b, int[] cands, boolean[] prims, int targetLit) {
        int eqAll = reifyExactly1InSlice(b, cands);
        var pList = new ArrayList<Integer>();
        for (int i = 0; i < cands.length; i++) {
            if (prims[i]) pList.add(cands[i]);
        }
        if (pList.isEmpty()) {
            addIff(b, targetLit, eqAll);
        } else {
            int eqPrim = pList.size() == 1
                    ? pList.getFirst()
                    : reifyExactly1InSlice(b, pList.stream().mapToInt(Integer::intValue).toArray());
            b.addClause(-targetLit, eqAll, eqPrim);
            b.addClause(targetLit, -eqAll);
            b.addClause(targetLit, -eqPrim);
        }
    }

    private static int reifyExactly1InSlice(SliceBuilder b, int[] lits) {
        int n = lits.length;
        int ge1 = b.newAuxiliaryVar();
        int le1 = b.newAuxiliaryVar();
        int eq1 = b.newAuxiliaryVar();

        int[] geFwdL = new int[n + 1];
        int[] geFwdW = new int[n + 1];
        geFwdL[0] = -ge1; geFwdW[0] = 1;
        for (int i = 0; i < n; i++) { geFwdL[i + 1] = lits[i]; geFwdW[i + 1] = 1; }
        b.addPbConstraint(new PbConstraint(geFwdL, geFwdW, 1));

        int[] geBwdL = new int[n + 1];
        int[] geBwdW = new int[n + 1];
        geBwdL[0] = ge1; geBwdW[0] = n;
        for (int i = 0; i < n; i++) { geBwdL[i + 1] = -lits[i]; geBwdW[i + 1] = 1; }
        b.addPbConstraint(new PbConstraint(geBwdL, geBwdW, n));

        int[] leFwdL = new int[n + 1];
        int[] leFwdW = new int[n + 1];
        leFwdL[0] = -le1; leFwdW[0] = n - 1;
        for (int i = 0; i < n; i++) { leFwdL[i + 1] = -lits[i]; leFwdW[i + 1] = 1; }
        b.addPbConstraint(new PbConstraint(leFwdL, leFwdW, n - 1));

        int[] leBwdL = new int[n + 1];
        int[] leBwdW = new int[n + 1];
        leBwdL[0] = le1; leBwdW[0] = 2;
        for (int i = 0; i < n; i++) { leBwdL[i + 1] = lits[i]; leBwdW[i + 1] = 1; }
        b.addPbConstraint(new PbConstraint(leBwdL, leBwdW, 2));

        b.addClause(-eq1, ge1);
        b.addClause(-eq1, le1);
        b.addClause(eq1, -ge1, -le1);
        return eq1;
    }
}
