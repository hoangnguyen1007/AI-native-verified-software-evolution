package com.evolution.backend.query;

import com.evolution.analysis.acquisition.streaming.InventoryEntry;
import com.evolution.analysis.acquisition.streaming.LogicalPath;
import com.evolution.analysis.acquisition.streaming.InventoryReader;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import java.io.IOException;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** A repository practices rubric over captured paths. Semantic quality is assessed separately. */
final class RepositoryScore {
    static final VersionedIdentifier VERSION = new VersionedIdentifier("workbench.repository-score", "v1");
    private static final Pattern DOCUMENT = Pattern.compile(".*\\.(md|mdx|rst|adoc|txt|org)");
    private static final Pattern TEST = Pattern.compile(
            "(?:^|.*/)(?:tests?|__tests__|spec|src/integrationtest)(?:/.*)?|"
            + ".*(?:_test\\.(go|py|rs|c|cpp)|(?:^|/)test_[^/]+\\.py|[._](spec|test)\\.[^/]+|(?:tests?|spec)\\.(java|kt|scala|cs|swift|vb)|_spec\\.rb)");
    private static final Pattern BUILD = Pattern.compile(
            "(?:pom\\.xml|(?:build|settings)\\.gradle(?:\\.kts)?|package\\.json|deno\\.jsonc?|go\\.(mod|work)|cargo\\.toml|"
            + "pyproject\\.toml|requirements(?:-[a-z0-9_-]+)?\\.txt|setup\\.cfg|pipfile|gemfile|composer\\.json|"
            + "package\\.swift|pubspec\\.yaml|mix\\.exs|rebar\\.config|stack\\.yaml|.*\\.(csproj|fsproj|vbproj|sln|cabal|pro)|"
            + "cmakelists\\.txt|meson\\.build|(?:gnumakefile|makefile|justfile|taskfile\\.ya?ml)|"
            + "(?:module|workspace|build)(?:\\.bazel)?|dockerfile(?:\\..+)?|mkdocs\\.ya?ml|hugo\\.toml)");
    private static final Pattern CI = Pattern.compile(
            "\\.github/workflows/[^/]+\\.ya?ml|\\.gitlab-ci\\.ya?ml|\\.circleci/config\\.ya?ml|"
            + "jenkinsfile|azure-pipelines(?:[-.][^/]+)?\\.ya?ml|bitbucket-pipelines\\.ya?ml|"
            + "\\.travis\\.ya?ml|\\.buildkite/pipeline\\.ya?ml|\\.woodpecker(?:/[^/]+)?\\.ya?ml");
    private static final Set<String> IGNORED = Set.of("VENDORED", "GENERATED", "CACHE");
    private static final Set<String> REPOSITORY_METADATA = Set.of(".gitignore", ".gitattributes", ".editorconfig",
            ".gitmodules", ".nojekyll", "license", "copying", "notice", "codeowners", ".github/codeowners");
    private static final Set<String> CHECK_IDS = Set.of("documentation", "tests", "automation", "build");

    enum State { OBSERVED, ABSENT, UNKNOWN, NOT_APPLICABLE }
    record Evidence(String path, ContentDigest identity) {}
    record Check(String name, String criterion, int weight, State state, Optional<Evidence> evidence) {}
    record Result(VersionedIdentifier model, Optional<ContentDigest> graph, SnapshotIdentity snapshot, AssessmentStatus status,
                  Optional<Integer> score, int lower, int upper, int applicableWeight, long inspectedFiles,
                  Optional<ContentDigest> inventoryEvidence, Map<String,String> paths, List<Check> checks) {}

    private final Map<String, Evidence> matches = new HashMap<>();
    private final Map<String, String> paths;
    private long files;
    private boolean incomplete;
    private boolean documentationOnly = true;
    private boolean rootReadmeSeen;

    private RepositoryScore(Map<String, String> paths) {
        this.paths = paths;
    }

    static Map<String, String> customPaths(Object input) {
        var values = GraphJson.object(input);
        var paths = new TreeMap<String, String>();
        for (var value : values.entrySet()) {
            if (!CHECK_IDS.contains(value.getKey())) throw new IllegalArgumentException("Unknown repository score check");
            var path = GraphJson.string(value.getValue());
            if (path.length() > 1024 || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                    || path.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid repository-relative path");
            LogicalPath.utf8(path);
            paths.put(value.getKey(), path);
        }
        return Map.copyOf(paths);
    }

    static Optional<Result> assessInventory(InventoryReader reader, String revision, Optional<ContentDigest> graph,
                                            Optional<SnapshotIdentity> snapshot, Map<String, String> paths,
                                            Runnable checkpoint) throws IOException {
        checkpoint.run();
        var inventory = reader.manifest(revision);
        if (inventory.isEmpty()) return Optional.empty();
        var scorer = new RepositoryScore(paths);
        long rows = 0;
        var cursor = Optional.<String>empty();
        do {
            checkpoint.run();
            var page = reader.page(revision, 256, cursor);
            rows += page.entries().size();
            for (var entry : page.entries()) {
                if (IGNORED.contains(entry.population().name())) continue;
                if (entry.disposition() != InventoryEntry.Disposition.ACQUIRED) {
                    if (!administrative(entry.path().utf8())) scorer.incomplete = true;
                    continue;
                }
                if (entry.kind() != InventoryEntry.Kind.FILE) continue;
                var path = entry.path().utf8();
                if (path.isPresent()) scorer.classify(path.orElseThrow(), () -> CanonicalJson.sha256(entry));
                else scorer.incomplete = true;
            }
            if (page.continuation().isPresent() && page.continuation().equals(cursor))
                throw new IOException("INVENTORY_CURSOR_DID_NOT_ADVANCE");
            cursor = page.continuation();
        } while (cursor.isPresent());
        var manifest = inventory.orElseThrow();
        if (!reader.manifest(revision).equals(inventory) || rows != manifest.entries())
            throw new IOException("REPOSITORY_SCORE_CENSUS_CHANGED");
        return Optional.of(scorer.result(graph, snapshot.orElseGet(() ->
                SnapshotIdentity.from(manifest.repository(), manifest.inventory())), Optional.of(manifest.inventory())));
    }

    static Result assess(KnowledgeGraph graph, Map<String, String> paths, Runnable checkpoint) {
        var scorer = new RepositoryScore(paths);
        GraphEvidence inventory = null;
        for (var item : graph.evidence()) {
            if ("polyglot.scan".equals(item.family())) {
                inventory = item;
                break;
            }
        }
        var report = inventory == null ? Map.<String,Object>of() : GraphJson.object(json(inventory).get("inventory"));
        String scope = report.get("inventory") instanceof String digest ? digest : "";
        long rows = 0;
        for (var node : graph.nodes()) {
            if (!node.context().equals(graph.snapshot().value()) || !node.kind().equals("INVENTORY_ENTRY")) continue;
            if ((rows++ & 1023) == 0) checkpoint.run();
            var entry = node.evidence().stream().map(graph::evidence).flatMap(Optional::stream)
                    .filter(proof -> proof.family().equals("polyglot.inventory-entry")).findFirst();
            if (entry.isPresent()) scorer.inspect(entry.orElseThrow(), scope);
            else scorer.incomplete = true;
        }
        if (inventory == null) {
            scorer.incomplete = true;
            scorer.useLegacySnapshot(graph);
        } else {
            var table = GraphJson.object(report.get("entries"));
            scorer.incomplete |= !(table.get("rows") instanceof Number count) || count.longValue() != rows;
        }
        checkpoint.run();
        return scorer.result(Optional.of(graph.identity()), graph.snapshot(), Optional.ofNullable(inventory).map(GraphEvidence::identity));
    }

    private void inspect(GraphEvidence proof, String scope) {
        var row = json(proof);
        if (!scope.equals(row.get("scope"))) {
            incomplete = true;
            return;
        }
        var fields = GraphJson.object(row.get("fields"));
        String disposition = GraphJson.string(fields.get("disposition"));
        if (IGNORED.contains(fields.get("population"))) return;
        var path = new LogicalPath(GraphJson.string(fields.get("rawPath"))).utf8();
        if (!"ACQUIRED".equals(disposition)) {
            if (!administrative(path)) incomplete = true;
            return;
        }
        if (!"FILE".equals(fields.get("kind"))) return;
        if (path.isEmpty()) {
            incomplete = true;
            return;
        }
        classify(path.orElseThrow(), proof::identity);
    }

    private static boolean administrative(Optional<String> path) {
        return path.filter(name -> name.equals(".git") || name.startsWith(".git/")).isPresent();
    }

    private void useLegacySnapshot(KnowledgeGraph graph) {
        graph.contexts().stream().filter(context -> context.identity().equals(graph.snapshot().value())).findFirst()
                .flatMap(context -> graph.evidence(context.evidence()))
                .filter(evidence -> evidence.family().equals("repository.snapshot")).ifPresent(evidence -> {
                    try {
                        for (var row : GraphJson.array(json(evidence).get("files"))) {
                            classify(GraphJson.string(GraphJson.object(row).get("path")), evidence::identity);
                        }
                    } catch (GraphJson.LimitExceededException unavailable) {
                        // An older snapshot may lack a separately paged denominator. Absence stays unknown.
                    }
                });
    }

    private void classify(String path, Supplier<ContentDigest> proof) {
        if (administrative(Optional.of(path))) return;
        if (path.startsWith(".agents/skills/") || path.startsWith(".codex/skills/")) {
            // Bundled assistant tooling is not evidence for the local project's default checks.
            paths.keySet().forEach(id -> observe(id, path, proof, false));
            return;
        }
        files++;
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        boolean doc = DOCUMENT.matcher(name).matches();
        boolean ci = CI.matcher(lower).matches();
        boolean build = BUILD.matcher(name).matches();
        boolean readme = lower.startsWith("readme") && !lower.contains("/") && (doc || lower.equals("readme"));
        rootReadmeSeen |= readme;
        observe("documentation", path, proof, readme);
        observe("tests", path, proof, !doc && TEST.matcher(lower).matches());
        observe("automation", path, proof, ci);
        observe("build", path, proof, build);
        documentationOnly &= (doc || ci || REPOSITORY_METADATA.contains(lower)) && !build;
    }

    private void observe(String id, String path, Supplier<ContentDigest> proof, boolean convention) {
        String required = paths.get(id);
        if (required == null ? !convention : !required.equals(path)) return;
        var previous = matches.get(id);
        if (previous == null || witnessOrder(path, previous.path()) < 0) matches.put(id, new Evidence(path, proof.get()));
    }

    private static int witnessOrder(String path, String prior) {
        int compared = Long.compare(path.chars().filter(c -> c == '/').count(),
                prior.chars().filter(c -> c == '/').count());
        return compared == 0 ? path.compareTo(prior) : compared;
    }

    private Result result(Optional<ContentDigest> graph, SnapshotIdentity snapshot, Optional<ContentDigest> inventory) {
        boolean docs = !incomplete && documentationOnly && rootReadmeSeen;
        var checks = List.of(
                check("documentation", "Root README", 25, true),
                check("tests", "Test files in a recognized naming or folder convention", 30, !docs),
                check("automation", "Configuration for a recognized CI system", 20, true),
                check("build", "Recognized build or package manifest", 25, !docs));
        int weight = checks.stream().filter(check -> check.state() != State.NOT_APPLICABLE).mapToInt(Check::weight).sum();
        int earned = checks.stream().filter(check -> check.state() == State.OBSERVED).mapToInt(Check::weight).sum();
        int unknown = checks.stream().filter(check -> check.state() == State.UNKNOWN).mapToInt(Check::weight).sum();
        int lower = 100 * earned / weight;
        int upper = (100 * (earned + unknown) + weight - 1) / weight;
        var status = !incomplete ? AssessmentStatus.COMPLETE : files > 0 ? AssessmentStatus.PARTIAL : AssessmentStatus.WITHHELD;
        return new Result(VERSION, graph, snapshot, status,
                status == AssessmentStatus.COMPLETE ? Optional.of((100 * earned + weight / 2) / weight) : Optional.empty(),
                lower, upper, weight, files, inventory, paths, checks);
    }

    private Check check(String id, String criterion, int weight, boolean applies) {
        var evidence = Optional.ofNullable(matches.get(id));
        var state = evidence.isPresent() ? State.OBSERVED : !applies && !paths.containsKey(id) ? State.NOT_APPLICABLE
                : incomplete ? State.UNKNOWN : State.ABSENT;
        String required = paths.get(id);
        return new Check(id, required == null ? criterion : "Captured file at " + required, weight, state, evidence);
    }

    private static Map<String, Object> json(GraphEvidence evidence) {
        return GraphJson.object(GraphJson.read(evidence.canonicalJson(), GraphJson.Limits.defaults()));
    }
}
