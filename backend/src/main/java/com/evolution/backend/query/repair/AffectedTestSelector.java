package com.evolution.backend.query.repair;

import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.backend.query.CapturedFileIndex;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Software Knowledge Graph Regression Test Selection (SKG-RTS).
 * Computes transitive reverse-dependent test closure over captured semantic edges (including
 * cross-context canonical type bridges, member-to-enclosing-file escalation, and conservative
 * unresolved {@code java.observation} recovery under passive offline analysis) and fails closed
 * to {@link SelectionMode#FULL_SUITE} on build, configuration, or unmapped edits.
 */
public final class AffectedTestSelector {
    private static final int MAX_BFS_DEPTH = 12;
    private static final Set<String> STRUCTURAL_EDGE_KINDS = Set.of(
            "graph.contains", "graph.declares", "graph.owns-source");
    private static final Set<String> TYPE_NODE_KINDS = Set.of(
            "TYPE", "CLASS", "INTERFACE", "ENUM", "RECORD");
    private static final Pattern FQN_PATTERN = Pattern.compile(
            "\\b([a-z][A-Za-z0-9_]*(?:\\.[a-z][A-Za-z0-9_]*)+\\.[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*)\\b");
    private static final Pattern TYPE_IDENT_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]{1,80})\\b");

    public enum SelectionMode {
        SELECTED_TESTS,
        FULL_SUITE
    }

    public record SelectedTest(
            String testClass,
            String path,
            int hopDistance,
            String rationale) {
    }

    public record Result(
            SelectionMode mode,
            List<SelectedTest> selectedTests,
            int totalTestClasses,
            double reductionPercent,
            Optional<String> mavenTestFilter,
            String rationale) {
        public Result {
            Objects.requireNonNull(mode);
            selectedTests = List.copyOf(selectedTests == null ? List.of() : selectedTests);
            Objects.requireNonNull(mavenTestFilter);
            rationale = rationale == null ? "" : rationale;
        }
    }

    private AffectedTestSelector() {}

    public static Result select(
            KnowledgeGraph graph,
            String context,
            Collection<String> modifiedPaths) {
        if (graph == null || modifiedPaths == null || modifiedPaths.isEmpty()) {
            return new Result(SelectionMode.FULL_SUITE, List.of(), 0, 0.0,
                    Optional.empty(), "NO_MODIFIED_PATHS_OR_GRAPH");
        }

        var normalizedModified = new LinkedHashSet<String>();
        for (String p : modifiedPaths) {
            if (p != null && !p.isBlank()) {
                normalizedModified.add(CapturedFileIndex.normalizePath(p));
            }
        }
        if (normalizedModified.isEmpty()) {
            return new Result(SelectionMode.FULL_SUITE, List.of(), 0, 0.0,
                    Optional.empty(), "NO_MODIFIED_PATHS_OR_GRAPH");
        }

        var fileIndex = CapturedFileIndex.forGraph(graph);
        var testFilesByPath = new TreeMap<String, String>();
        var testFilesBySimpleName = new TreeMap<String, String>();
        var docPaths = RepairContextPlanner.indexDocumentPaths(graph);

        var candidateTestPaths = new TreeSet<String>();
        for (var entry : fileIndex.entries()) {
            candidateTestPaths.add(CapturedFileIndex.normalizePath(entry.path()));
        }
        for (String p : docPaths.values()) {
            if (p != null && !p.isBlank()) {
                candidateTestPaths.add(CapturedFileIndex.normalizePath(p));
            }
        }
        for (String p : candidateTestPaths) {
            if (isTestFilePath(p)) {
                String cls = simpleTestName(p);
                testFilesByPath.put(p, cls);
                testFilesBySimpleName.putIfAbsent(cls.toLowerCase(Locale.ROOT), p);
            }
        }
        int totalTests = testFilesByPath.size();

        // Conservative safety gate: any build/config/Spring metadata edit triggers full regression suite.
        for (String p : normalizedModified) {
            if (isGlobalConfigOrBuildFile(p)) {
                return new Result(SelectionMode.FULL_SUITE, List.of(), totalTests, 0.0,
                        Optional.empty(), "SAFETY_FALLBACK_CONFIG_OR_BUILD_EDIT:" + p);
            }
        }

        var contexts = new LinkedHashSet<String>();
        if (context != null && !context.isBlank()) {
            contexts.add(context);
        } else {
            for (var c : graph.contexts()) {
                contexts.add(c.identity());
            }
        }

        var nodePath = new HashMap<String, String>();
        var nodeLabel = new HashMap<String, String>();
        var nodesByPath = new HashMap<String, Set<String>>();
        var typeNodesByFqn = new HashMap<String, Set<String>>();
        var typeNodesBySimpleName = new HashMap<String, Set<String>>();
        var packageByPath = new HashMap<String, String>();
        var reverseAdj = new HashMap<String, Set<String>>();
        var parentByChild = new HashMap<String, String>();
        var unresolvedObservations = new ArrayList<KnowledgeGraph.Node>();

        for (String ctx : contexts) {
            for (var node : graph.nodes(ctx)) {
                if ("OBSERVATION".equals(node.kind())) {
                    unresolvedObservations.add(node);
                    continue;
                }
                RepairContextPlanner.inspectDeclaration(graph, node, docPaths, nodePath, nodeLabel);
                String p = nodePath.get(node.identity());
                if (p != null) {
                    String norm = CapturedFileIndex.normalizePath(p);
                    nodePath.put(node.identity(), norm);
                    nodesByPath.computeIfAbsent(norm, k -> new LinkedHashSet<>()).add(node.identity());
                }
                if (TYPE_NODE_KINDS.contains(node.kind())) {
                    String label = nodeLabel.get(node.identity());
                    if (label != null && !label.isBlank()) {
                        typeNodesByFqn.computeIfAbsent(label, k -> new LinkedHashSet<>()).add(node.identity());
                        String simple = simpleTypeName(label);
                        if (!simple.isBlank()) {
                            typeNodesBySimpleName.computeIfAbsent(simple, k -> new LinkedHashSet<>()).add(node.identity());
                        }
                        if (p != null) {
                            String pkg = packageOfFqn(label);
                            if (!pkg.isEmpty()) {
                                packageByPath.putIfAbsent(nodePath.get(node.identity()), pkg);
                            }
                        }
                    }
                }
            }
            for (var edge : graph.edges(ctx)) {
                if (STRUCTURAL_EDGE_KINDS.contains(edge.kind())) {
                    for (String targetId : edge.target().identities()) {
                        parentByChild.putIfAbsent(targetId, edge.source());
                    }
                    continue;
                }
                for (String targetId : edge.target().identities()) {
                    reverseAdj.computeIfAbsent(targetId, k -> new LinkedHashSet<>()).add(edge.source());
                }
            }
        }

        // Cross-context canonical type bridge: connect equivalent TYPE nodes across main/test/module contexts.
        for (var equiv : typeNodesByFqn.values()) {
            if (equiv.size() <= 1) continue;
            for (String a : equiv) {
                for (String b : equiv) {
                    if (!a.equals(b)) {
                        reverseAdj.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(b);
                    }
                }
            }
        }

        // Conservative recovery for passive/offline UNRESOLVED AST observations already recorded in the SKG:
        // when external dependencies/reactor bytecode are absent, test->main or cross-module references remain
        // recorded as OBSERVATION nodes with attribution != RESOLVED and their exact source reference spelling.
        if (!unresolvedObservations.isEmpty() && !typeNodesBySimpleName.isEmpty()) {
            indexUnresolvedObservations(
                    graph, unresolvedObservations, docPaths, nodesByPath, nodePath,
                    packageByPath, typeNodesByFqn, typeNodesBySimpleName, reverseAdj);
        }

        var seedQueue = new ArrayDeque<String>();
        var distance = new HashMap<String, Integer>();
        var selectedByPath = new TreeMap<String, SelectedTest>();

        for (String modPath : normalizedModified) {
            if (testFilesByPath.containsKey(modPath)) {
                selectedByPath.put(modPath, new SelectedTest(
                        testFilesByPath.get(modPath), modPath, 0, "Directly modified test file"));
                continue;
            }
            var modNodes = nodesByPath.getOrDefault(modPath, Set.of());
            String stem = fileStem(modPath);
            boolean hasDirectSibling = false;
            if (!stem.isEmpty()) {
                var stems = Set.of(stem);
                for (var entry : testFilesByPath.entrySet()) {
                    if (RepairContextPlanner.isMatchingSiblingTest(entry.getKey(), stems)) {
                        hasDirectSibling = true;
                        selectedByPath.putIfAbsent(entry.getKey(), new SelectedTest(
                                entry.getValue(), entry.getKey(), 1,
                                "Direct sibling test of modified " + modPath));
                    }
                }
            }
            if (modNodes.isEmpty() && !hasDirectSibling) {
                return new Result(SelectionMode.FULL_SUITE, List.of(), totalTests, 0.0,
                        Optional.empty(), "SAFETY_FALLBACK_UNMAPPED_SOURCE:" + modPath);
            }
            for (String nid : modNodes) {
                if (!distance.containsKey(nid)) {
                    distance.put(nid, 0);
                    seedQueue.add(nid);
                }
            }
        }

        while (!seedQueue.isEmpty()) {
            String cur = seedQueue.poll();
            int d = distance.get(cur);
            if (d >= MAX_BFS_DEPTH) continue;
            for (String rev : reverseAdj.getOrDefault(cur, Set.of())) {
                int nextDist = d + 1;
                if (!distance.containsKey(rev) || nextDist < distance.get(rev)) {
                    distance.put(rev, nextDist);
                    seedQueue.add(rev);
                }
                String revPath = resolveNodePath(rev, nodePath, parentByChild);
                if (revPath != null && testFilesByPath.containsKey(revPath)) {
                    selectedByPath.putIfAbsent(revPath, new SelectedTest(
                            testFilesByPath.get(revPath), revPath, nextDist,
                            "SKG reverse dependency hop " + nextDist));
                } else {
                    // Escalate visited non-test member/nested nodes to their enclosing type and co-file
                    // declarations at the same hop distance so member->type->test chains never stall.
                    String parent = parentByChild.get(rev);
                    if (parent != null && (!distance.containsKey(parent) || nextDist < distance.get(parent))) {
                        distance.put(parent, nextDist);
                        seedQueue.add(parent);
                    }
                    if (revPath != null) {
                        for (String coNode : nodesByPath.getOrDefault(revPath, Set.of())) {
                            if (!distance.containsKey(coNode) || nextDist < distance.get(coNode)) {
                                distance.put(coNode, nextDist);
                                seedQueue.add(coNode);
                            }
                        }
                    }
                }
            }
        }

        if (selectedByPath.isEmpty()) {
            return new Result(SelectionMode.FULL_SUITE, List.of(), totalTests, 0.0,
                    Optional.empty(), totalTests > 0 ? "SAFETY_FALLBACK_NO_TEST_CLOSURE" : "NO_TEST_FILES_IN_GRAPH");
        }

        var ordered = selectedByPath.values().stream()
                .sorted(Comparator.comparingInt(SelectedTest::hopDistance).thenComparing(SelectedTest::testClass))
                .toList();
        double reduction = totalTests > 0
                ? BigDecimal.valueOf(100.0 * (totalTests - ordered.size()) / totalTests)
                        .setScale(2, RoundingMode.HALF_UP)
                        .doubleValue()
                : 0.0;
        String filter = String.join(",", ordered.stream().map(SelectedTest::testClass).distinct().toList());
        return new Result(SelectionMode.SELECTED_TESTS, ordered, totalTests, reduction,
                Optional.of(filter),
                "SKG_RTS_SELECTED(" + ordered.size() + "/" + totalTests + ")");
    }

    static boolean isGlobalConfigOrBuildFile(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        String name = low.substring(low.lastIndexOf('/') + 1);
        if (name.equals("pom.xml") || name.equals("build.gradle") || name.equals("build.gradle.kts")
                || name.equals("settings.gradle") || name.equals("settings.gradle.kts")
                || name.equals("package.json") || name.equals("go.mod") || name.equals("pyproject.toml")
                || name.equals("dockerfile")) {
            return true;
        }
        if ((name.startsWith("application") || name.startsWith("bootstrap"))
                && (name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties"))) {
            return true;
        }
        return low.contains("meta-inf/spring");
    }

    static boolean isTestFilePath(String path) {
        String norm = path.replace('\\', '/');
        String low = norm.toLowerCase(Locale.ROOT);
        int slash = norm.lastIndexOf('/');
        String origFile = slash >= 0 ? norm.substring(slash + 1) : norm;
        String file = origFile.toLowerCase(Locale.ROOT);
        // For Java/Kotlin files, require a test source directory (never src/main/) AND standard
        // Surefire/Failsafe/JUnit runnable naming patterns (case-sensitive on the original filename
        // stem so production/helper classes like Visit.java, Audit.java, or Init.java never match).
        if (low.endsWith(".java") || low.endsWith(".kt")) {
            if (!isJavaTestSourceDirectory(low)) {
                return false;
            }
            int dot = origFile.lastIndexOf('.');
            String origStem = dot > 0 ? origFile.substring(0, dot) : origFile;
            return origStem.endsWith("Test")
                    || origStem.endsWith("Tests")
                    || origStem.endsWith("TestCase")
                    || origStem.endsWith("IT")
                    || origStem.endsWith("ITCase")
                    || origStem.endsWith("Spec")
                    || (origStem.startsWith("Test") && origStem.length() > 4 && Character.isUpperCase(origStem.charAt(4)))
                    || (origStem.startsWith("IT") && origStem.length() > 2 && Character.isUpperCase(origStem.charAt(2)));
        }
        if (low.contains("/src/test/") || low.startsWith("src/test/") || low.contains("/tests/") || low.contains("/__tests__/")) {
            return low.endsWith(".ts") || low.endsWith(".tsx") || low.endsWith(".js") || low.endsWith(".jsx")
                    || low.endsWith(".py") || low.endsWith(".go");
        }
        return low.endsWith(".test.ts") || low.endsWith(".spec.ts") || low.endsWith(".test.js")
                || low.endsWith(".spec.js") || low.endsWith("_test.go")
                || file.startsWith("test_") || low.endsWith("_test.py");
    }

    private static boolean isJavaTestSourceDirectory(String lowPath) {
        if (lowPath.contains("/src/main/") || lowPath.startsWith("src/main/")) {
            return false;
        }
        return lowPath.contains("/src/test/") || lowPath.startsWith("src/test/")
                || lowPath.contains("/src/it/") || lowPath.startsWith("src/it/")
                || lowPath.contains("/src/integrationtest/") || lowPath.startsWith("src/integrationtest/")
                || lowPath.contains("/src/integration-test/") || lowPath.startsWith("src/integration-test/")
                || lowPath.contains("/src/functionaltest/") || lowPath.startsWith("src/functionaltest/")
                || lowPath.contains("/test/") || lowPath.startsWith("test/")
                || lowPath.contains("/tests/") || lowPath.startsWith("tests/");
    }

    private static String resolveNodePath(String nodeId, Map<String, String> nodePath, Map<String, String> parentByChild) {
        String direct = nodePath.get(nodeId);
        if (direct != null) return direct;
        String cur = parentByChild.get(nodeId);
        for (int hops = 0; cur != null && hops < 8; hops++) {
            String p = nodePath.get(cur);
            if (p != null) return p;
            cur = parentByChild.get(cur);
        }
        return null;
    }

    private static void indexUnresolvedObservations(
            KnowledgeGraph graph,
            List<KnowledgeGraph.Node> observationNodes,
            Map<String, String> docPaths,
            Map<String, Set<String>> nodesByPath,
            Map<String, String> nodePath,
            Map<String, String> packageByPath,
            Map<String, Set<String>> typeNodesByFqn,
            Map<String, Set<String>> typeNodesBySimpleName,
            Map<String, Set<String>> reverseAdj) {
        var anchorByPath = new HashMap<String, String>();
        for (var obsNode : observationNodes) {
            for (var ref : obsNode.evidence()) {
                GraphEvidence ev = graph.evidence(ref).orElse(null);
                if (ev == null || !"java.observation".equals(ev.family())) continue;
                if (ev.canonicalJson().length() > 32_768) continue;
                Map<String, Object> obj;
                try {
                    obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(32_768, 16, 2_048)));
                } catch (RuntimeException ignored) {
                    continue;
                }
                if ("RESOLVED".equals(obj.get("attribution"))) continue;
                if (!(obj.get("document") instanceof String docId)) continue;
                String obsPath = docPaths.get(docId);
                if (obsPath == null || obsPath.isBlank()) continue;
                obsPath = CapturedFileIndex.normalizePath(obsPath);
                if (!(obj.get("reference") instanceof String reference) || reference.isBlank()) continue;

                String sourceAnchor = anchorByPath.computeIfAbsent(obsPath, p -> {
                    var existing = nodesByPath.get(p);
                    if (existing != null && !existing.isEmpty()) {
                        return existing.iterator().next();
                    }
                    String synthetic = "obs-file:" + p;
                    nodePath.putIfAbsent(synthetic, p);
                    return synthetic;
                });
                String obsPkg = packageByPath.getOrDefault(obsPath, "");

                // 1. Exact dotted FQN matches inside the unresolved observation reference.
                //    Track explicitly qualified simple names so e.g. java.util.List never falls back to a project class.
                var fqnClaimedSimples = new HashSet<String>();
                Matcher fqnMatcher = FQN_PATTERN.matcher(reference);
                while (fqnMatcher.find()) {
                    String fqn = fqnMatcher.group(1);
                    fqnClaimedSimples.add(simpleTypeName(fqn));
                    var targets = typeNodesByFqn.get(fqn);
                    if (targets != null) {
                        for (String tId : targets) {
                            if (!obsPath.equals(nodePath.get(tId))) {
                                reverseAdj.computeIfAbsent(tId, k -> new LinkedHashSet<>()).add(sourceAnchor);
                            }
                        }
                    }
                }

                // 2. Simple type identifier matches (prefer same-package FQN match, else conservatively match
                //    project-declared TYPE nodes with that simple name).
                Matcher identMatcher = TYPE_IDENT_PATTERN.matcher(reference);
                while (identMatcher.find()) {
                    String simple = identMatcher.group(1);
                    if (fqnClaimedSimples.contains(simple) || !typeNodesBySimpleName.containsKey(simple)) continue;
                    Set<String> targets = !obsPkg.isEmpty() ? typeNodesByFqn.get(obsPkg + "." + simple) : null;
                    if (targets == null || targets.isEmpty()) {
                        targets = typeNodesBySimpleName.get(simple);
                    }
                    if (targets == null) continue;
                    for (String tId : targets) {
                        if (!obsPath.equals(nodePath.get(tId))) {
                            reverseAdj.computeIfAbsent(tId, k -> new LinkedHashSet<>()).add(sourceAnchor);
                        }
                    }
                }
            }
        }
    }

    private static String simpleTypeName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fqn.length() ? fqn.substring(dot + 1) : fqn;
    }

    private static String packageOfFqn(String fqn) {
        int dot = fqn.lastIndexOf('.');
        while (dot > 0) {
            String prefix = fqn.substring(0, dot);
            int prevDot = prefix.lastIndexOf('.');
            String seg = prevDot >= 0 ? prefix.substring(prevDot + 1) : prefix;
            if (!seg.isEmpty() && Character.isLowerCase(seg.charAt(0))) {
                return prefix;
            }
            dot = prevDot;
        }
        return "";
    }

    private static String simpleTestName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String fileStem(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
