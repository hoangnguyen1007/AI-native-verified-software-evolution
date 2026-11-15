package com.evolution.analysis.evolution;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.spring.condition.DeclaredYaml;

import java.io.IOException;
import java.util.*;

import static com.evolution.analysis.evolution.EvolutionRows.*;

/** Compares local YAML coordinates whose bytes are bound to both repository inventories. */
final class DeclaredYamlEvolution {
    private static final String FILES = "declared-yaml-files";
    private static final String SOURCES = "declared-yaml-sources";
    private final EvolutionRun run;

    DeclaredYamlEvolution(EvolutionRun run) { this.run = run; }

    private static boolean selects(String path) {
        return path.substring(path.lastIndexOf('/') + 1).matches("application(?:-.+)?\\.ya?ml");
    }

    static void inventory(EvolutionInput side, Map<String,Object> file, ContentDigest repository) throws IOException {
        String path = text(file, "path");
        if (selects(path)) {
            unique(side.run.index, FILES, side.name + "|" + fileKey(path),
                    row("path", path, "contentDigest", file.get("contentDigest"), "evidence", repository));
        }
    }

    static void source(EvolutionInput side, GraphEvidence evidence, Map<String,Object> source,
                       Map<String,Object> document) throws IOException {
        String path = text(document, "path");
        var repository = side.run.request.repository();
        if (!selects(path) || !repository.value().equals(document.get("repository"))
                || !SourceDocumentIdentity.from(repository, path).value().equals(document.get("identity"))) return;
        String key = side.name + "|" + fileKey(path) + "|" + text(document, "contentDigest");
        var earlier = side.run.index.get(SOURCES, key);
        if (earlier.isEmpty() || evidence.identity().value().compareTo(text(earlier.orElseThrow(), "evidence")) < 0) {
            side.run.index.put(SOURCES, key, row("evidence", evidence.identity(), "content", source.get("contentEvidence")));
        }
    }

    void compare(EvolutionInput left, EvolutionInput right) throws IOException {
        var a = new Rows(run.index, FILES, left.name + "|");
        var b = new Rows(run.index, FILES, right.name + "|");
        while (a.head() != null || b.head() != null) {
            EvolutionSnapshot.check(run.cancelled);
            var l = a.head();
            var r = b.head();
            int order = l == null ? 1 : r == null ? -1 : suffix(l).compareTo(suffix(r));
            if (order < 0) missingCounterpart(left, a.next().value());
            else if (order > 0) missingCounterpart(right, b.next().value());
            else compareFile(left, a.next().value(), right, b.next().value());
        }
    }

    private void missingCounterpart(EvolutionInput side, Map<String,Object> file) throws IOException {
        gap(side, "COUNTERPART_UNAVAILABLE", file);
    }

    private void compareFile(EvolutionInput left, Map<String,Object> a, EvolutionInput right, Map<String,Object> b)
            throws IOException {
        if (a.get("contentDigest").equals(b.get("contentDigest"))) return;
        var before = read(left, a);
        var after = read(right, b);
        if (before.isEmpty() || after.isEmpty()) return;
        var previous = before.orElseThrow();
        var next = after.orElseThrow();
        var path = text(a, "path");
        var keys = new TreeSet<>(previous.declarations().keySet());
        keys.addAll(next.declarations().keySet());
        for (var key : keys) {
            EvolutionSnapshot.check(run.cancelled);
            var old = previous.declarations().get(key);
            var current = next.declarations().get(key);
            var declaration = old == null ? current : old;
            String kind;
            if (old == null) kind = "DECLARATION_ADDED";
            else if (current == null) kind = "DECLARATION_REMOVED";
            else if (!old.valueDigest().equals(current.valueDigest())) kind = "DECLARATION_CHANGED";
            else if (isImport(declaration.key())) kind = "IMPORT_DECLARATION_RETAINED";
            else continue;
            var identity = hash("evolution.yaml-declaration:v1", List.of(run.requestIdentity, path, key, kind));
            run.put(SnapshotComparison.Section.EVIDENCE, "declared-yaml|" + identity.value(), row(
                    "identity", identity, "family", "spring.declared-yaml", "kind", kind,
                    "path", path, "ordinal", declaration.ordinal(), "configurationKey", declaration.key(),
                    "left", operand(left, a, previous, old), "right", operand(right, b, next, current),
                    "authority", "PINNED_LOCAL_DECLARATION", "semanticAbsenceProved", false));
        }
    }

    private Optional<FileDeclarations> read(EvolutionInput side, Map<String,Object> file) throws IOException {
        String key = side.name + "|" + fileKey(text(file, "path")) + "|" + text(file, "contentDigest");
        var binding = run.index.get(SOURCES, key);
        if (binding.isEmpty()) {
            gap(side, "BYTES_UNAVAILABLE", file);
            return Optional.empty();
        }
        var source = new ContentDigest(text(binding.orElseThrow(), "evidence"));
        var content = new ContentDigest(text(binding.orElseThrow(), "content"));
        try {
            var artifact = side.evidence(content);
            if (!artifact.family().equals("source.content")) throw EvolutionInput.corrupt();
            var bytes = Base64.getDecoder().decode(text(EvolutionInput.payload(artifact), "bytesBase64"));
            if (!ContentDigest.sha256(bytes).value().equals(file.get("contentDigest"))) throw EvolutionInput.corrupt();
            var result = new TreeMap<String,DeclaredYaml.Declaration>();
            for (var declaration : DeclaredYaml.read(bytes)) {
                var coordinate = CanonicalJson.write(List.of(declaration.ordinal(), declaration.key()));
                result.put(coordinate, declaration);
            }
            return Optional.of(new FileDeclarations(result, source, content));
        } catch (DeclaredYaml.Rejected rejected) {
            gap(side, rejected.reason().name(), file, source);
        } catch (EvolutionSnapshot.ReadFailure failure) {
            gap(side, failure.reason().name(), file, source);
        }
        return Optional.empty();
    }

    private void gap(EvolutionInput side, String reason, Map<String,Object> file, ContentDigest... sources) throws IOException {
        run.unresolved();
        var refs = new ArrayList<>(List.of(sources));
        refs.add(new ContentDigest(text(file, "evidence")));
        run.gap(side, null, "DECLARED_YAML_" + reason, refs, text(file, "path"));
    }

    private static Map<String,Object> operand(EvolutionInput side, Map<String,Object> file,
                                              FileDeclarations parsed, DeclaredYaml.Declaration observed) {
        return row("graph", side.graph, "contentDigest", file.get("contentDigest"),
                "present", observed != null, "evidence", List.of(file.get("evidence"), parsed.source(), parsed.content()),
                "valueDigest", observed == null ? null : observed.valueDigest(),
                "sourceSpan", observed == null ? null : observed.sourceSpan());
    }

    private static boolean isImport(List<Object> path) {
        return importAt(path, List.of("spring", "config", "import")) || importAt(path, List.of("spring.config.import"));
    }

    private static boolean importAt(List<Object> path, List<String> prefix) {
        return path.equals(prefix) || path.size() == prefix.size() + 1
                && path.subList(0, prefix.size()).equals(prefix) && path.getLast() instanceof Integer;
    }

    private static String suffix(EvolutionIndex.Entry entry) { return entry.key().substring(entry.key().indexOf('|') + 1); }
    private static String fileKey(String path) { return hash("evolution.declared-yaml-file:v1", path).value(); }
    private record FileDeclarations(Map<String,DeclaredYaml.Declaration> declarations, ContentDigest source,
                                    ContentDigest content) {}
}
