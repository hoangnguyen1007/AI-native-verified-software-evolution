package com.evolution.backend.query;

import com.evolution.analysis.assessment.ArchitectureAssessment;
import com.evolution.analysis.assessment.ArchitectureAssessmentService;
import com.evolution.analysis.assessment.ArchitectureScoreModel;
import com.evolution.analysis.assessment.v2.InferredLayerClassifier;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.javaparser.ResolutionEnvironment;
import com.evolution.analysis.metrics.ArchitectureMetric;
import com.evolution.analysis.metrics.PolicyMetrics;
import com.evolution.analysis.metrics.PolicyMetricsService;
import com.evolution.analysis.policy.MetadataConsistencyRulePack;
import com.evolution.analysis.policy.PolicyCompiler;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.policy.PolicyEvaluator;
import com.evolution.analysis.policy.PolicyGraphMapper;
import com.evolution.analysis.query.ArchitectureQuery.Page;
import com.evolution.analysis.query.ArchitectureQuery.Population;
import com.evolution.analysis.query.ArchitectureQuery.Projection;
import com.evolution.analysis.query.ArchitectureQuery.Request;
import com.evolution.analysis.query.ArchitectureQuery.Direction;
import com.evolution.analysis.query.ArchitectureQuery.Limits;
import com.evolution.analysis.query.ArchitectureQuery.Scope;
import com.evolution.analysis.query.ArchitectureQueryService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** Evaluates built-in or owner-authored policy against one already-published context. */
final class WorkbenchPolicyAssessment {
    enum Kind { DEFAULT, CUSTOM }

    record Result(Kind policyKind, String policy, ContentDigest policyDigest, Scope scope,
                  AssessmentStatus status, Optional<ContentDigest> report,
                  Optional<ArchitectureAssessment.Result> assessment,
                  List<PolicyCompiler.Problem> problems, List<CapabilityGapRecord> gaps) {}

    record SuggestedPolicy(String yaml, String summary, List<String> detectedLayers, Scope scope) {}

    private static final int MAX_PACKAGE_PAGES = 20; // 20 * 50 = 1,000 packages

    static Scope scope(KnowledgeGraph graph, KnowledgeGraph.Context context) {
        return graph.evidence(context.evidence()).filter(evidence -> evidence.family().equals("spring.context"))
                .map(evidence -> Scope.SPRING_COMPONENT).orElse(Scope.TYPE);
    }

    static Result evaluate(ArchitectureQueryService queries, KnowledgeGraph.Context context, Scope scope,
                           Optional<String> custom, PublishedReports reports, Runnable checkpoint) {
        String text = custom.orElseGet(() -> defaultPolicy(scope));
        Kind kind = custom.isPresent() ? Kind.CUSTOM : Kind.DEFAULT;
        var compilation = new PolicyCompiler().compile(text.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(context.snapshot()), PolicyCompiler.Limits.defaults());
        if (compilation.policy().isEmpty()) {
            return new Result(kind, text, compilation.source(), scope, AssessmentStatus.WITHHELD,
                    Optional.empty(), Optional.empty(), compilation.problems(), compilation.gaps());
        }
        checkpoint.run();
        var plan = new PolicyGraphMapper().bind(compilation, queries, context.identity(), PolicyGraphMapper.Limits.defaults());
        var evaluation = new PolicyEvaluator().evaluate(compilation, plan, queries, PolicyEvaluation.Limits.defaults());
        var metrics = new PolicyMetricsService().compute(compilation, plan, evaluation, queries, PolicyMetrics.Limits.defaults());
        var score = new ArchitectureAssessmentService().assess(metrics, evaluation, queries,
                Optional.of(ArchitectureScoreModel.candidate(scope)), plan.limits().query(),
                ArchitectureMetric.Limits.defaults(), ArchitectureAssessment.Limits.defaults());
        checkpoint.run();
        var report = reports.publish(new PublishedReports.Report(evaluation, Optional.of(metrics), Optional.of(score)));
        return new Result(kind, text, compilation.source(), scope, score.status(),
                Optional.of(report), Optional.of(score), List.of(), score.gaps());
    }

    /**
     * Builds an exact ASM-backed {@link MetadataConsistencyRulePack.ClasspathTypeLookup} for passive
     * dependency JARs via {@link ResolutionEnvironment} (zero package-prefix regexes, {@code I1}/{@code I3}).
     */
    static MetadataConsistencyRulePack.ClasspathTypeLookup classpathLookup(
            java.util.Collection<java.nio.file.Path> jarPaths,
            int targetRelease,
            boolean classpathComplete) {
        return ResolutionEnvironment.classpathTypeLookupFromJars(jarPaths, targetRelease, classpathComplete);
    }

    /**
     * Evaluates the WP2.2 built-in 6-rule {@code metadata-consistency-pack} against a published context
     * backed by passive {@link ResolutionEnvironment}/{@code AsmBytecodeIndex} classpath resolution.
     */
    static MetadataConsistencyRulePack.EvaluationReport evaluateMetadataConsistency(
            ArchitectureQueryService queries,
            KnowledgeGraph.Context context,
            java.util.Collection<java.nio.file.Path> jarPaths,
            int targetRelease,
            boolean classpathComplete) {
        var lookup = classpathLookup(jarPaths, targetRelease, classpathComplete);
        var options = classpathComplete
                ? MetadataConsistencyRulePack.EvaluationOptions.defaults()
                : MetadataConsistencyRulePack.EvaluationOptions.offlineIncomplete();
        return new PolicyEvaluator().evaluateMetadataConsistencyPack(queries, context.identity(), lookup, options);
    }

    static SuggestedPolicy suggestPolicy(ArchitectureQueryService queries, KnowledgeGraph.Context context, Scope scope, Runnable checkpoint) {
        checkpoint.run();
        var webPkgs = new TreeSet<String>();
        var appPkgs = new TreeSet<String>();
        var domainPkgs = new TreeSet<String>();
        var portPkgs = new TreeSet<String>();
        var adapterPkgs = new TreeSet<String>();
        var dataPkgs = new TreeSet<String>();
        var allProjectPkgs = new TreeSet<String>();
        Optional<String> cursor = Optional.empty();
        for (int pageIndex = 0; pageIndex < MAX_PACKAGE_PAGES; pageIndex++) {
            checkpoint.run();
            var pkgReq = new Request(queries.graph().identity(), context.identity(), Scope.PACKAGE, Projection.BOUNDED_UNION,
                    Direction.OUTGOING, Set.of("graph.depends-on"), new Population(Set.of(), Set.of(), Set.of("PROJECT"), Set.of()),
                    false, Optional.empty(), Limits.defaults());
            var pkgs = queries.entities(pkgReq, "", new Page(50, cursor));
            for (var entity : pkgs.items()) {
                String name = entity.qualifiedName().or(entity::canonicalName).orElse("").trim();
                if (name.isEmpty()) continue;
                allProjectPkgs.add(name);
                classifyPackage(name, webPkgs, appPkgs, domainPkgs, portPkgs, adapterPkgs, dataPkgs);
            }
            if (pkgs.continuation().isEmpty()) break;
            cursor = pkgs.continuation();
        }
        String style = detectArchitectureStyle(webPkgs, appPkgs, domainPkgs, portPkgs, adapterPkgs, dataPkgs);
        var layers = new ArrayList<String>();
        if (!webPkgs.isEmpty()) layers.add("Web / API (" + webPkgs.size() + ")");
        if (!appPkgs.isEmpty()) layers.add("Application / Use-Case (" + appPkgs.size() + ")");
        if (!domainPkgs.isEmpty()) layers.add("Domain / Core (" + domainPkgs.size() + ")");
        if (!portPkgs.isEmpty()) layers.add("Ports / SPI (" + portPkgs.size() + ")");
        if (!adapterPkgs.isEmpty()) layers.add("Adapters (" + adapterPkgs.size() + ")");
        if (!dataPkgs.isEmpty()) layers.add("Data / Infrastructure (" + dataPkgs.size() + ")");

        var infraPkgs = new TreeSet<String>(dataPkgs);
        infraPkgs.addAll(adapterPkgs);

        var yaml = new StringBuilder();
        yaml.append("schema: architecture-policy:v1\n");
        yaml.append("id: workbench.auto-detected\n");
        yaml.append("version: \"1\"\n");
        yaml.append("rules:\n");
        yaml.append("  - id: auto.no-type-cycles\n");
        yaml.append("    version: \"1\"\n");
        yaml.append("    kind: ACYCLIC\n");
        yaml.append("    severity: ERROR\n");
        yaml.append("    scope: ").append(scope).append("\n");
        yaml.append("    relationships: [").append(scope == Scope.SPRING_COMPONENT ? "spring.selected-binding" : "graph.depends-on").append("]\n");
        yaml.append("    within: {origins: [PROJECT]}\n");
        if (scope != Scope.SPRING_COMPONENT && allProjectPkgs.size() >= 2) {
            yaml.append("  - id: auto.no-package-cycles\n");
            yaml.append("    version: \"1\"\n");
            yaml.append("    kind: ACYCLIC\n");
            yaml.append("    severity: WARNING\n");
            yaml.append("    scope: PACKAGE\n");
            yaml.append("    relationships: [graph.depends-on]\n");
            yaml.append("    within: {origins: [PROJECT]}\n");
        }
        if (!webPkgs.isEmpty() && !dataPkgs.isEmpty()) {
            appendForbidden(yaml, "auto.web-no-direct-persistence", "WARNING", webPkgs, dataPkgs);
        }
        if (!domainPkgs.isEmpty() && !webPkgs.isEmpty()) {
            appendForbidden(yaml, "auto.domain-no-web-leakage", "ERROR", domainPkgs, webPkgs);
        }
        if (!domainPkgs.isEmpty() && !infraPkgs.isEmpty()) {
            appendForbidden(yaml, "auto.domain-no-infrastructure-leakage", "ERROR", domainPkgs, infraPkgs);
        }
        if (!domainPkgs.isEmpty() && !appPkgs.isEmpty()) {
            appendForbidden(yaml, "auto.domain-no-application-leakage", "WARNING", domainPkgs, appPkgs);
        }
        if (!appPkgs.isEmpty() && !webPkgs.isEmpty()) {
            appendForbidden(yaml, "auto.application-no-web-leakage", "WARNING", appPkgs, webPkgs);
        }
        if (!portPkgs.isEmpty() && !adapterPkgs.isEmpty()) {
            appendForbidden(yaml, "auto.ports-no-adapter-leakage", "ERROR", portPkgs, adapterPkgs);
        }
        String candidate = yaml.toString();
        var check = new PolicyCompiler().compile(candidate.getBytes(StandardCharsets.UTF_8),
                EvidenceContext.forSnapshot(context.snapshot()), PolicyCompiler.Limits.defaults());
        if (check.policy().isEmpty()) {
            return new SuggestedPolicy(defaultPolicy(scope), "Default dependency cycle policy.", List.copyOf(layers), scope);
        }
        String summary = layers.isEmpty()
                ? "Generated cycle checks across " + allProjectPkgs.size() + " project packages."
                : "Detected " + style + " (" + allProjectPkgs.size() + " packages): " + String.join(", ", layers) + ".";
        return new SuggestedPolicy(candidate, summary, List.copyOf(layers), scope);
    }

    private static void classifyPackage(String pkg, Set<String> webPkgs, Set<String> appPkgs, Set<String> domainPkgs,
                                        Set<String> portPkgs, Set<String> adapterPkgs, Set<String> dataPkgs) {
        InferredLayerClassifier.classifyPackageName(pkg).ifPresent(layer -> {
            String pattern = pkg + ".**";
            switch (layer) {
                case InferredLayerClassifier.LAYER_PORT -> portPkgs.add(pattern);
                case InferredLayerClassifier.LAYER_ADAPTER -> adapterPkgs.add(pattern);
                case InferredLayerClassifier.LAYER_WEB -> webPkgs.add(pattern);
                case InferredLayerClassifier.LAYER_DATA -> dataPkgs.add(pattern);
                case InferredLayerClassifier.LAYER_APPLICATION -> appPkgs.add(pattern);
                case InferredLayerClassifier.LAYER_DOMAIN -> domainPkgs.add(pattern);
                default -> {}
            }
        });
    }

    private static String detectArchitectureStyle(Set<String> webPkgs, Set<String> appPkgs, Set<String> domainPkgs,
                                                  Set<String> portPkgs, Set<String> adapterPkgs, Set<String> dataPkgs) {
        var present = new LinkedHashSet<String>();
        if (!webPkgs.isEmpty()) present.add(InferredLayerClassifier.LAYER_WEB);
        if (!appPkgs.isEmpty()) present.add(InferredLayerClassifier.LAYER_APPLICATION);
        if (!domainPkgs.isEmpty()) present.add(InferredLayerClassifier.LAYER_DOMAIN);
        if (!portPkgs.isEmpty()) present.add(InferredLayerClassifier.LAYER_PORT);
        if (!adapterPkgs.isEmpty()) present.add(InferredLayerClassifier.LAYER_ADAPTER);
        if (!dataPkgs.isEmpty()) present.add(InferredLayerClassifier.LAYER_DATA);
        return InferredLayerClassifier.detectStyle(present);
    }

    private static void appendForbidden(StringBuilder yaml, String id, String severity, Set<String> from, Set<String> to) {
        yaml.append("  - id: ").append(id).append("\n");
        yaml.append("    version: \"1\"\n");
        yaml.append("    kind: FORBIDDEN_DEPENDENCY\n");
        yaml.append("    severity: ").append(severity).append("\n");
        yaml.append("    scope: TYPE\n");
        yaml.append("    relationships: [graph.depends-on]\n");
        yaml.append("    from: {packages: [").append(yamlList(from, 24)).append("], origins: [PROJECT]}\n");
        yaml.append("    to: {packages: [").append(yamlList(to, 24)).append("], origins: [PROJECT]}\n");
    }

    private static String yamlList(Set<String> items, int limit) {
        return items.stream().limit(limit).map(item -> "\"" + item.replace("\"", "") + "\"").collect(java.util.stream.Collectors.joining(", "));
    }

    private static String defaultPolicy(Scope scope) {
        return """
                schema: architecture-policy:v1
                id: workbench.default
                version: "1"
                rules:
                  - id: workbench.no-dependency-cycles
                    version: "1"
                    kind: ACYCLIC
                    severity: ERROR
                    scope: %s
                    relationships: [%s]
                    within: {origins: [PROJECT]}
                """.formatted(scope, scope == Scope.SPRING_COMPONENT ? "spring.selected-binding" : "graph.depends-on");
    }
}
