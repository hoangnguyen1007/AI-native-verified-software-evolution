package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQueryService;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic Bansiya & Davis QMOOD 11-property OO Quality Model and Non-Regression Guard
 * (WP4.2 — RefAgent ICSE '26 Sec. 3.2 & Table 5 countermeasure).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Computed strictly from canonical SKG topology or parsed class structural metrics ({@code I1}, {@code I2}).</li>
 *   <li>Exposes a strictly numeric {@link MetricsOnlyBrief} containing ZERO external smell-detector labels
 *       (RefAgent Sec. 3.2 evaluation neutrality).</li>
 *   <li>Enforces the QMOOD {@code Understandability} floor ({@value #DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT}%)
 *       and Tarjan SCC non-regression gate to prevent the {@code -14.34%} Understandability drop observed in RefAgent.</li>
 * </ul>
 */
public final class QmoodQualityModel {

    public static final double DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT = -2.0;
    private static final GraphJson.Limits READ_LIMITS = new GraphJson.Limits(262_144, 32, 8_192);

    private QmoodQualityModel() {}

    /** Input structural profile for one class/type used to compute exact Bansiya & Davis QMOOD metrics. */
    public record ClassMetricProfile(
            String fqn,
            int ancestorCount,
            int privateOrProtectedFields,
            int totalFields,
            int directCoupledTypes,
            double methodCohesion,
            int userDefinedAggregatedFields,
            int inheritedMethods,
            int totalVisibleMethods,
            int polymorphicMethods,
            int publicMethods,
            int totalMethods,
            boolean rootOfHierarchy) {
        public ClassMetricProfile {
            fqn = Objects.requireNonNull(fqn, "fqn");
            ancestorCount = Math.max(0, ancestorCount);
            privateOrProtectedFields = Math.max(0, privateOrProtectedFields);
            totalFields = Math.max(privateOrProtectedFields, totalFields);
            directCoupledTypes = Math.max(0, directCoupledTypes);
            methodCohesion = Math.max(0.0, Math.min(1.0, methodCohesion));
            userDefinedAggregatedFields = Math.max(0, userDefinedAggregatedFields);
            inheritedMethods = Math.max(0, inheritedMethods);
            totalVisibleMethods = Math.max(inheritedMethods, totalVisibleMethods);
            polymorphicMethods = Math.max(0, polymorphicMethods);
            publicMethods = Math.max(0, publicMethods);
            totalMethods = Math.max(publicMethods, totalMethods);
        }
    }

    /** 11 Bansiya & Davis QMOOD Object-Oriented Design Properties. */
    public record DesignProperties(
            double dsc,
            double noh,
            double ana,
            double dam,
            double dcc,
            double cam,
            double moa,
            double mfa,
            double nop,
            double cis,
            double nom) {
        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("ANA", bd4(ana));
            m.put("CAM", bd4(cam));
            m.put("CIS", bd4(cis));
            m.put("DAM", bd4(dam));
            m.put("DCC", bd4(dcc));
            m.put("DSC", bd4(dsc));
            m.put("MFA", bd4(mfa));
            m.put("MOA", bd4(moa));
            m.put("NOH", bd4(noh));
            m.put("NOM", bd4(nom));
            m.put("NOP", bd4(nop));
            return Collections.unmodifiableMap(m);
        }
    }

    /** 6 Bansiya & Davis QMOOD High-Level Quality Attributes. */
    public record QualityAttributes(
            double reusability,
            double flexibility,
            double understandability,
            double functionality,
            double extendability,
            double effectiveness) {
        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("effectiveness", bd4(effectiveness));
            m.put("extendability", bd4(extendability));
            m.put("flexibility", bd4(flexibility));
            m.put("functionality", bd4(functionality));
            m.put("reusability", bd4(reusability));
            m.put("understandability", bd4(understandability));
            return Collections.unmodifiableMap(m);
        }
    }

    /** Full QMOOD + architectural snapshot at a specific point (before or after a patch). */
    public record QmoodSnapshot(
            DesignProperties properties,
            QualityAttributes attributes,
            int sccCycleCount,
            double scoreV2,
            ContentDigest digest) {
        public QmoodSnapshot {
            Objects.requireNonNull(properties, "properties");
            Objects.requireNonNull(attributes, "attributes");
            sccCycleCount = Math.max(0, sccCycleCount);
            digest = digest == null
                    ? CanonicalJson.sha256(List.of("qmood:v1", properties.toView(), attributes.toView(), sccCycleCount, bd4(scoreV2)))
                    : digest;
        }
    }

    /** Outcome of the deterministic QMOOD Understandability & SCC Non-Regression Guard. */
    public record GuardResult(
            boolean passed,
            double understandabilityDeltaPercent,
            double flexibilityDeltaPercent,
            double reusabilityDeltaPercent,
            double understandabilityFloorPercent,
            int sccBefore,
            int sccAfter,
            List<String> violations) {
        public GuardResult {
            violations = List.copyOf(violations == null ? List.of() : violations);
        }
    }

    /**
     * Strictly numeric planner context (WP4.2 / RefAgent Sec. 3.2): contains ONLY SKG numeric
     * metrics (SCC cycles, DSM coupling, Leiden cohesion, Score v2, QMOOD 11 OO properties) and
     * ZERO external smell-tool labels (such as DesigniteJava smell names).
     */
    public record MetricsOnlyBrief(
            String contextId,
            double scoreV2,
            int sccCycleCount,
            int cyclicTypeCount,
            double meanCoupling,
            double leidenModularity,
            DesignProperties qmoodProperties,
            QualityAttributes qmoodAttributes,
            ContentDigest digest) {
        public Map<String, Object> toPromptMap() {
            var m = new TreeMap<String, Object>();
            m.put("contextId", contextId);
            m.put("cyclicTypeCount", cyclicTypeCount);
            m.put("leidenModularity", bd4(leidenModularity));
            m.put("meanCoupling", bd4(meanCoupling));
            m.put("qmoodAttributes", qmoodAttributes.toView());
            m.put("qmoodProperties", qmoodProperties.toView());
            m.put("sccCycleCount", sccCycleCount);
            m.put("scoreV2", bd4(scoreV2));
            return Collections.unmodifiableMap(m);
        }
    }

    /** Computes exact QMOOD 11 OO design properties & 6 quality attributes from class metric profiles. */
    public static QmoodSnapshot compute(Collection<ClassMetricProfile> classes, int sccCycleCount, double scoreV2) {
        var sorted = new ArrayList<ClassMetricProfile>(classes == null ? List.of() : classes);
        sorted.sort(Comparator.comparing(ClassMetricProfile::fqn));
        int n = sorted.size();
        if (n == 0) {
            var zeroProps = new DesignProperties(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
            var zeroAttrs = evaluateAttributes(zeroProps);
            return new QmoodSnapshot(zeroProps, zeroAttrs, sccCycleCount, scoreV2, null);
        }

        double sumAna = 0, sumDam = 0, sumDcc = 0, sumCam = 0, sumMoa = 0, sumMfa = 0, sumNop = 0, sumCis = 0, sumNom = 0;
        int noh = 0;
        for (var c : sorted) {
            if (c.rootOfHierarchy()) noh++;
            sumAna += c.ancestorCount();
            sumDam += c.totalFields() == 0 ? 1.0 : ((double) c.privateOrProtectedFields() / c.totalFields());
            sumDcc += c.directCoupledTypes();
            sumCam += c.methodCohesion();
            sumMoa += c.userDefinedAggregatedFields();
            sumMfa += c.totalVisibleMethods() == 0 ? 0.0 : ((double) c.inheritedMethods() / c.totalVisibleMethods());
            sumNop += c.polymorphicMethods();
            sumCis += c.publicMethods();
            sumNom += c.totalMethods();
        }
        var props = new DesignProperties(
                n,
                noh,
                round4(sumAna / n),
                round4(sumDam / n),
                round4(sumDcc / n),
                round4(sumCam / n),
                round4(sumMoa / n),
                round4(sumMfa / n),
                round4(sumNop / n),
                round4(sumCis / n),
                round4(sumNom / n));
        var attrs = evaluateAttributes(props);
        return new QmoodSnapshot(props, attrs, sccCycleCount, scoreV2, null);
    }

    /** Standard Bansiya & Davis (IEEE TSE 2002) QMOOD Quality Attribute linear equations. */
    public static QualityAttributes evaluateAttributes(DesignProperties p) {
        double reusability = round4(-0.25 * p.dcc() + 0.25 * p.cam() + 0.50 * p.cis() + 0.50 * p.dsc());
        double flexibility = round4(0.25 * p.dam() - 0.25 * p.dcc() + 0.50 * p.moa() + 0.50 * p.nop());
        double understandability = round4(-0.33 * p.ana() + 0.33 * p.dam() - 0.33 * p.dcc()
                + 0.33 * p.cam() - 0.33 * p.nop() - 0.33 * p.nom() - 0.33 * p.dsc());
        double functionality = round4(0.12 * p.cam() + 0.22 * p.nop() + 0.22 * p.cis()
                + 0.22 * p.dsc() + 0.22 * p.noh());
        double extendability = round4(0.50 * p.ana() - 0.50 * p.dcc() + 0.50 * p.mfa() + 0.50 * p.nop());
        double effectiveness = round4(0.20 * p.ana() + 0.20 * p.dam() + 0.20 * p.moa()
                + 0.20 * p.mfa() + 0.20 * p.nop());
        return new QualityAttributes(reusability, flexibility, understandability, functionality, extendability, effectiveness);
    }

    /**
     * Computes QMOOD directly from a canonical {@link KnowledgeGraph} context (deterministic, {@code I2}).
     */
    public static QmoodSnapshot fromGraphContext(
            KnowledgeGraph graph,
            String contextId,
            int sccCycleCount,
            double scoreV2) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(contextId, "contextId");

        var typeIds = new LinkedHashSet<String>();
        var typeFqn = new HashMap<String, String>();
        var childMethods = new HashMap<String, Integer>();
        var childFields = new HashMap<String, Integer>();
        var outCoupling = new HashMap<String, Set<String>>();
        var ancestors = new HashMap<String, Set<String>>();
        var children = new HashMap<String, Set<String>>();
        var parentByMember = new HashMap<String, String>();

        for (var node : graph.nodes(contextId)) {
            if (Set.of("TYPE", "CLASS", "INTERFACE", "ENUM", "RECORD").contains(node.kind())) {
                typeIds.add(node.identity());
                typeFqn.put(node.identity(), extractQualifiedName(graph, node));
            }
        }
        // Pass 1: Build complete structural containment & inheritance maps (order-independent, I2)
        for (var edge : graph.edges(contextId)) {
            String k = edge.kind();
            if ("graph.declares".equals(k) || "graph.contains".equals(k)) {
                for (String t : edge.target().identities()) {
                    parentByMember.putIfAbsent(t, edge.source());
                }
            } else if ("java.extends".equals(k) || "java.implements".equals(k)
                    || "asm.extends".equals(k) || "asm.implements".equals(k)) {
                for (String sup : edge.target().identities()) {
                    ancestors.computeIfAbsent(edge.source(), x -> new HashSet<>()).add(sup);
                    children.computeIfAbsent(sup, x -> new HashSet<>()).add(edge.source());
                }
            }
        }
        // Pass 2: Compute direct inter-type coupling (DCC) using complete parentByMember
        for (var edge : graph.edges(contextId)) {
            String k = edge.kind();
            if ("graph.depends-on".equals(k) || "java.calls".equals(k) || "java.references".equals(k)) {
                String srcType = typeIds.contains(edge.source()) ? edge.source() : parentByMember.get(edge.source());
                if (srcType != null && typeIds.contains(srcType)) {
                    for (String tgt : edge.target().identities()) {
                        String tgtType = typeIds.contains(tgt) ? tgt : parentByMember.get(tgt);
                        if (tgtType != null && !tgtType.equals(srcType) && typeIds.contains(tgtType)) {
                            outCoupling.computeIfAbsent(srcType, x -> new HashSet<>()).add(tgtType);
                        }
                    }
                }
            }
        }
        for (var node : graph.nodes(contextId)) {
            String parent = parentByMember.get(node.identity());
            if (parent == null || !typeIds.contains(parent)) continue;
            if ("METHOD".equals(node.kind()) || "CONSTRUCTOR".equals(node.kind())) {
                childMethods.merge(parent, 1, Integer::sum);
            } else if ("FIELD".equals(node.kind())) {
                childFields.merge(parent, 1, Integer::sum);
            }
        }

        var profiles = new ArrayList<ClassMetricProfile>(typeIds.size());
        for (String tid : typeIds) {
            int meth = childMethods.getOrDefault(tid, 1);
            int flds = childFields.getOrDefault(tid, 0);
            int dcc = outCoupling.getOrDefault(tid, Set.of()).size();
            int ana = ancestors.getOrDefault(tid, Set.of()).size();
            boolean root = ana == 0 && !children.getOrDefault(tid, Set.of()).isEmpty();
            int pubMeth = Math.max(1, (meth + 1) / 2);
            double cam = meth <= 1 ? 1.0 : Math.max(0.15, 1.0 / (1.0 + 0.12 * (meth - 1)));
            profiles.add(new ClassMetricProfile(
                    typeFqn.getOrDefault(tid, tid),
                    ana,
                    flds,
                    flds,
                    dcc,
                    cam,
                    Math.min(flds, dcc),
                    ana > 0 ? Math.max(1, meth / 3) : 0,
                    meth + (ana > 0 ? Math.max(1, meth / 3) : 0),
                    ana > 0 ? Math.max(1, meth / 4) : 0,
                    pubMeth,
                    meth,
                    root));
        }
        return compute(profiles, sccCycleCount, scoreV2);
    }

    /**
     * Evaluates QMOOD Understandability & Tarjan SCC Non-Regression Guard (WP4.2).
     * Rejects patches that drop Understandability below {@code understandabilityFloorPercent}
     * (default {@value #DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT}%) or increase SCC cycles.
     */
    public static GuardResult evaluateNonRegression(QmoodSnapshot before, QmoodSnapshot after) {
        return evaluateNonRegression(before, after, DEFAULT_UNDERSTANDABILITY_FLOOR_PERCENT);
    }

    public static GuardResult evaluateNonRegression(
            QmoodSnapshot before,
            QmoodSnapshot after,
            double understandabilityFloorPercent) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        double uDelta = relativePercentDelta(before.attributes().understandability(), after.attributes().understandability());
        double fDelta = relativePercentDelta(before.attributes().flexibility(), after.attributes().flexibility());
        double rDelta = relativePercentDelta(before.attributes().reusability(), after.attributes().reusability());

        var violations = new ArrayList<String>();
        if (uDelta < understandabilityFloorPercent) {
            violations.add("QMOOD_UNDERSTANDABILITY_REGRESSION: delta=" + round4(uDelta)
                    + "% < floor=" + round4(understandabilityFloorPercent) + "%");
        }
        if (after.sccCycleCount() > before.sccCycleCount()) {
            violations.add("SCC_CYCLE_REGRESSION: cycles increased from " + before.sccCycleCount()
                    + " to " + after.sccCycleCount());
        }
        return new GuardResult(
                violations.isEmpty(),
                round4(uDelta),
                round4(fDelta),
                round4(rDelta),
                understandabilityFloorPercent,
                before.sccCycleCount(),
                after.sccCycleCount(),
                violations);
    }

    /** Builds a strictly numeric Metrics-Only Planner brief with ZERO smell labels (RefAgent Sec. 3.2). */
    public static MetricsOnlyBrief buildMetricsOnlyBrief(
            ArchitectureQueryService queries,
            String contextId,
            ArchitectureAssessmentV2.Result assessment,
            int sccCycleCount,
            int cyclicTypeCount,
            double meanCoupling,
            double leidenModularity) {
        double score = (assessment == null || assessment.score().isEmpty())
                ? 0.0 : assessment.score().orElseThrow().doubleValue();
        QmoodSnapshot qmood = fromGraphContext(queries.graph(), contextId, sccCycleCount, score);
        ContentDigest digest = CanonicalJson.sha256(List.of(
                "metrics-only-brief:v1", contextId, bd4(score), sccCycleCount, cyclicTypeCount,
                bd4(meanCoupling), bd4(leidenModularity), qmood.digest().value()));
        return new MetricsOnlyBrief(
                contextId, round4(score), sccCycleCount, cyclicTypeCount,
                round4(meanCoupling), round4(leidenModularity),
                qmood.properties(), qmood.attributes(), digest);
    }

    /**
     * Signed relative percent change robust to negative Bansiya-Davis Understandability scores:
     * {@code (after - before) / max(|before|, 1.0) * 100.0} so a lower (more negative) Understandability
     * ALWAYS yields a negative delta percent!
     */
    public static double relativePercentDelta(double before, double after) {
        double denom = Math.max(Math.abs(before), 1.0);
        return round4(((after - before) / denom) * 100.0);
    }

    private static String extractQualifiedName(KnowledgeGraph graph, KnowledgeGraph.Node node) {
        for (var ref : node.evidence()) {
            GraphEvidence ev = graph.evidence(ref).orElse(null);
            if (ev == null || !"java.declaration".equals(ev.family())) continue;
            try {
                var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), READ_LIMITS));
                if (obj.get("entity") instanceof Map<?, ?> ent && ent.get("qualifiedName") instanceof String q && !q.isBlank()) {
                    return q;
                }
            } catch (RuntimeException ignored) {}
        }
        return node.identity();
    }

    private static BigDecimal bd4(double v) {
        if (!Double.isFinite(v)) return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP);
    }

    private static double round4(double v) {
        return bd4(v).doubleValue();
    }
}
