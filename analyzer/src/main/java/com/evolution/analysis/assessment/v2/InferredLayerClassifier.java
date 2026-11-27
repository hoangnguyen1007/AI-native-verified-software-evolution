package com.evolution.analysis.assessment.v2;

import com.evolution.analysis.contract.common.ContentDigest;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Deterministic architectural layer &amp; style classifier (Hexagonal, Clean, Layered)
 * driven by package naming segments and verified framework stereotype annotations.
 *
 * <p>Every inferred boundary finding is explicitly tagged {@code INFERRED_LAYER_CONVENTION}
 * and kept strictly distinct from owner-authored {@code architecture-policy:v1} rules.
 */
public final class InferredLayerClassifier {
    public static final String LAYER_WEB = "WEB";
    public static final String LAYER_APPLICATION = "APPLICATION";
    public static final String LAYER_DOMAIN = "DOMAIN";
    public static final String LAYER_PORT = "PORT";
    public static final String LAYER_ADAPTER = "ADAPTER";
    public static final String LAYER_DATA = "DATA";

    private static final Pattern WEB_PKG = Pattern.compile(
            "^(?:web|controller|controllers|rest|api|endpoint|endpoints|resource|resources|http|graphql|grpc|presenter|presenters|inbound|driving|ui|view|views)$");
    private static final Pattern APP_PKG = Pattern.compile(
            "^(?:application|app|usecase|usecases|interactor|interactors|service|services|business|workflow|workflows|orchestrator|orchestrators)$");
    private static final Pattern DOMAIN_PKG = Pattern.compile(
            "^(?:domain|core|model|models|entity|entities|aggregate|aggregates|vo|valueobject|valueobjects)$");
    private static final Pattern PORT_PKG = Pattern.compile(
            "^(?:port|ports|spi)$");
    private static final Pattern ADAPTER_PKG = Pattern.compile(
            "^(?:adapter|adapters|outbound|driven|client|clients|gateway|gateways)$");
    private static final Pattern DATA_PKG = Pattern.compile(
            "^(?:repository|repositories|repo|repos|dao|daos|persistence|data|db|database|store|storage|infra|infrastructure|framework|frameworks)$");

    private static final BigDecimal WEIGHT_UPWARD_LEAK = BigDecimal.ONE;
    private static final BigDecimal WEIGHT_LAYER_SKIP = new BigDecimal("0.5");

    public record LayerSummary(String layer, int packageCount, int typeCount) {}

    public record LayerMap(
            String detectedStyle,
            Map<String, String> packageLayers,
            Map<String, String> typeLayers,
            List<LayerSummary> layers) {
        public LayerMap {
            Objects.requireNonNull(detectedStyle);
            packageLayers = Collections.unmodifiableMap(new TreeMap<>(packageLayers));
            typeLayers = Collections.unmodifiableMap(new TreeMap<>(typeLayers));
            layers = List.copyOf(layers);
        }
    }

    public record LayerViolation(
            String edgeIdentity,
            String source,
            String sourceLabel,
            String target,
            String targetLabel,
            String fromLayer,
            String toLayer,
            String violationKind,
            BigDecimal weight,
            List<ContentDigest> evidence) {
        public LayerViolation {
            evidence = List.copyOf(evidence);
        }
    }

    public record LayerAssessment(
            LayerMap layerMap,
            int crossLayerEdges,
            int upwardLeakCount,
            int layerSkipCount,
            BigDecimal weightedLeakSum,
            Optional<BigDecimal> violationRatio,
            List<LayerViolation> topViolations) {
        public LayerAssessment {
            topViolations = List.copyOf(topViolations);
        }
    }

    /** Classifies one dotted package name (or empty string) into a canonical layer, if recognized. */
    public static Optional<String> classifyPackageName(String packageName) {
        if (packageName == null || packageName.isBlank()) {
            return Optional.empty();
        }
        String[] segments = packageName.toLowerCase(Locale.ROOT).split("[./\\\\]+");
        // Check explicit Hexagonal port/adapter boundaries first (e.g. adapter.persistence -> ADAPTER).
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i];
            if (PORT_PKG.matcher(seg).matches()) return Optional.of(LAYER_PORT);
            if (ADAPTER_PKG.matcher(seg).matches()) return Optional.of(LAYER_ADAPTER);
        }
        boolean hasDomainAncestor = false;
        for (String seg : segments) {
            if (DOMAIN_PKG.matcher(seg).matches()) {
                hasDomainAncestor = true;
                break;
            }
        }
        for (int i = segments.length - 1; i >= 0; i--) {
            String seg = segments[i];
            if ("api".equals(seg) && hasDomainAncestor) {
                // Shared domain/core API contract packages (e.g. com.acme.core.api) are ports, not web controllers.
                return Optional.of(LAYER_PORT);
            }
            if (WEB_PKG.matcher(seg).matches()) return Optional.of(LAYER_WEB);
            if (DATA_PKG.matcher(seg).matches()) return Optional.of(LAYER_DATA);
            if (APP_PKG.matcher(seg).matches()) return Optional.of(LAYER_APPLICATION);
            if (DOMAIN_PKG.matcher(seg).matches()) return Optional.of(LAYER_DOMAIN);
        }
        return Optional.empty();
    }

    /** Classifies a Java/Spring stereotype annotation name into a canonical layer, if recognized. */
    public static Optional<String> classifyStereotype(String annotationName) {
        if (annotationName == null || annotationName.isBlank()) return Optional.empty();
        String trimmed = annotationName.trim();
        if (trimmed.startsWith("@")) trimmed = trimmed.substring(1).trim();
        int paren = trimmed.indexOf('(');
        if (paren >= 0) trimmed = trimmed.substring(0, paren).trim();
        // MapStruct @Mapper is a DTO/domain translator, NOT a persistence repository!
        // Only classify MyBatis org.apache.ibatis.annotations.Mapper as DATA.
        if (trimmed.equals("org.mapstruct.Mapper")) return Optional.empty();
        if (trimmed.equals("org.apache.ibatis.annotations.Mapper")) return Optional.of(LAYER_DATA);
        String simple = trimmed.contains(".")
                ? trimmed.substring(trimmed.lastIndexOf('.') + 1)
                : trimmed;
        return switch (simple) {
            case "RestController", "Controller", "Path", "GraphQLApi", "GrpcService" -> Optional.of(LAYER_WEB);
            case "Service" -> Optional.of(LAYER_APPLICATION);
            case "Repository", "Dao" -> Optional.of(LAYER_DATA);
            case "Entity", "Document", "Embeddable", "MappedSuperclass" -> Optional.of(LAYER_DOMAIN);
            default -> Optional.empty();
        };
    }

    private static int stereotypePriority(String layer) {
        return switch (layer) {
            case LAYER_WEB -> 4;
            case LAYER_APPLICATION -> 3;
            case LAYER_DATA -> 2;
            case LAYER_DOMAIN -> 1;
            default -> 0;
        };
    }

    public static String detectStyle(Set<String> presentLayers) {
        boolean hasPort = presentLayers.contains(LAYER_PORT);
        boolean hasAdapter = presentLayers.contains(LAYER_ADAPTER);
        boolean hasDomain = presentLayers.contains(LAYER_DOMAIN);
        boolean hasApp = presentLayers.contains(LAYER_APPLICATION);
        boolean hasWeb = presentLayers.contains(LAYER_WEB);
        boolean hasData = presentLayers.contains(LAYER_DATA);
        if (hasPort && hasAdapter) return "Hexagonal Architecture";
        if (hasDomain && hasApp && (hasWeb || hasData || hasAdapter)) return "Clean Architecture";
        if ((hasWeb && hasData) || (hasDomain && (hasWeb || hasData)) || (hasWeb && hasApp)) {
            return "Layered Architecture";
        }
        return "Modular Architecture";
    }

    public record DirectedTypeEdge(
            String edgeIdentity,
            String source,
            String sourceLabel,
            String target,
            String targetLabel,
            List<ContentDigest> evidence) {}

    /**
     * Classifies packages and types, then evaluates cross-layer dependencies against
     * universally accepted inward-dependency and non-skipping invariants.
     */
    public static LayerAssessment evaluate(
            Map<String, String> typeToPackageName,
            Map<String, Set<String>> typeAnnotations,
            List<DirectedTypeEdge> internalTypeEdges,
            int maxWitnesses) {
        var packageLayers = new TreeMap<String, String>();
        var typeLayers = new TreeMap<String, String>();
        var pkgCounts = new TreeMap<String, Set<String>>();
        var typeCounts = new TreeMap<String, Integer>();

        for (var entry : typeToPackageName.entrySet()) {
            String typeId = entry.getKey();
            String pkgName = entry.getValue() == null ? "" : entry.getValue();
            Optional<String> fromPkg = classifyPackageName(pkgName);
            fromPkg.ifPresent(layer -> packageLayers.putIfAbsent(pkgName, layer));

            // Stereotype annotation takes precedence by semantic priority (WEB > APPLICATION > DATA > DOMAIN),
            // never alphabetical annotation name order.
            Optional<String> fromAnnotation = Optional.empty();
            int bestPriority = -1;
            for (String ann : typeAnnotations.getOrDefault(typeId, Set.of())) {
                var classified = classifyStereotype(ann);
                if (classified.isPresent()) {
                    int prio = stereotypePriority(classified.orElseThrow());
                    if (prio > bestPriority) {
                        bestPriority = prio;
                        fromAnnotation = classified;
                    }
                }
            }
            Optional<String> effective = fromAnnotation.isPresent() ? fromAnnotation : fromPkg;
            if (effective.isPresent()) {
                String layer = effective.orElseThrow();
                typeLayers.put(typeId, layer);
                typeCounts.merge(layer, 1, Integer::sum);
                if (!pkgName.isEmpty()) {
                    pkgCounts.computeIfAbsent(layer, k -> new TreeSet<>()).add(pkgName);
                }
            }
        }

        var summaries = new ArrayList<LayerSummary>();
        for (String layer : List.of(LAYER_WEB, LAYER_APPLICATION, LAYER_DOMAIN, LAYER_PORT, LAYER_ADAPTER, LAYER_DATA)) {
            int tc = typeCounts.getOrDefault(layer, 0);
            int pc = pkgCounts.getOrDefault(layer, Set.of()).size();
            if (tc > 0 || pc > 0) {
                summaries.add(new LayerSummary(layer, pc, tc));
            }
        }
        String style = detectStyle(typeCounts.keySet());
        var layerMap = new LayerMap(style, packageLayers, typeLayers, summaries);

        if (summaries.size() < 2) {
            return new LayerAssessment(layerMap, 0, 0, 0, BigDecimal.ZERO, Optional.empty(), List.of());
        }

        boolean hasApplicationLayer = typeCounts.getOrDefault(LAYER_APPLICATION, 0) > 0;
        int crossLayerEdges = 0;
        int upwardLeaks = 0;
        int layerSkips = 0;
        BigDecimal weightedLeakSum = BigDecimal.ZERO;
        var violations = new ArrayList<LayerViolation>();

        for (var edge : internalTypeEdges) {
            String fromLayer = typeLayers.get(edge.source());
            String toLayer = typeLayers.get(edge.target());
            if (fromLayer == null || toLayer == null || fromLayer.equals(toLayer)) {
                continue;
            }
            crossLayerEdges++;
            String kind = classifyViolation(fromLayer, toLayer, hasApplicationLayer);
            if (kind == null) continue;
            BigDecimal weight = kind.equals("LAYER_SKIP") ? WEIGHT_LAYER_SKIP : WEIGHT_UPWARD_LEAK;
            if (kind.equals("LAYER_SKIP")) layerSkips++;
            else upwardLeaks++;
            weightedLeakSum = weightedLeakSum.add(weight);
            violations.add(new LayerViolation(
                    edge.edgeIdentity(), edge.source(), edge.sourceLabel(),
                    edge.target(), edge.targetLabel(), fromLayer, toLayer, kind, weight, edge.evidence()));
        }

        violations.sort(Comparator.comparing(LayerViolation::weight).reversed()
                .thenComparing(LayerViolation::fromLayer)
                .thenComparing(LayerViolation::toLayer)
                .thenComparing(LayerViolation::sourceLabel)
                .thenComparing(LayerViolation::targetLabel)
                .thenComparing(LayerViolation::edgeIdentity));
        var bounded = violations.size() > maxWitnesses ? violations.subList(0, maxWitnesses) : violations;
        Optional<BigDecimal> ratio = crossLayerEdges == 0
                ? Optional.empty()
                : Optional.of(CalibrationCurve.divide(weightedLeakSum, BigDecimal.valueOf(crossLayerEdges)));
        return new LayerAssessment(layerMap, crossLayerEdges, upwardLeaks, layerSkips,
                CalibrationCurve.normalize(weightedLeakSum), ratio, bounded);
    }

    private static String classifyViolation(String from, String to, boolean hasAppLayer) {
        // Hard upward/inversion leaks (weight 1.0)
        if (from.equals(LAYER_DOMAIN) && (to.equals(LAYER_WEB) || to.equals(LAYER_DATA)
                || to.equals(LAYER_ADAPTER) || to.equals(LAYER_APPLICATION))) {
            return "UPWARD_DEPENDENCY_LEAK";
        }
        if (from.equals(LAYER_PORT) && (to.equals(LAYER_ADAPTER) || to.equals(LAYER_WEB) || to.equals(LAYER_DATA))) {
            return "PORT_ADAPTER_LEAK";
        }
        if (from.equals(LAYER_DATA) && (to.equals(LAYER_WEB) || to.equals(LAYER_APPLICATION))) {
            return "UPWARD_DEPENDENCY_LEAK";
        }
        if (from.equals(LAYER_APPLICATION) && to.equals(LAYER_WEB)) {
            return "UPWARD_DEPENDENCY_LEAK";
        }
        // Layer skipping shortcut (weight 0.5): Web directly accessing Data/Repository when Service/App layer exists.
        if (from.equals(LAYER_WEB) && to.equals(LAYER_DATA) && hasAppLayer) {
            return "LAYER_SKIP";
        }
        return null;
    }

    private InferredLayerClassifier() {}
}
