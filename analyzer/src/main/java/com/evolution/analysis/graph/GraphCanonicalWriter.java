package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.IOException;
import java.io.Writer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * Zero-reflection canonical JSON writer and structural counter for core KnowledgeGraph records.
 *
 * <p>Produces byte-for-byte identical UTF-8 JSON to {@link CanonicalJson} for
 * {@link KnowledgeGraph}, {@link KnowledgeGraph.Context}, {@link KnowledgeGraph.Node},
 * {@link KnowledgeGraph.Edge}, {@link KnowledgeGraph.Target}, and {@link GraphEvidence}.
 */
public final class GraphCanonicalWriter {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private GraphCanonicalWriter() {}

    /** Fast toggle for performance codec paths; defaults to {@code true}. */
    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("evo.perf.fastGraphCodec", "true"));
    }

    /** Encodes a single core graph record or string into its canonical JSON string. */
    public static String encodeValue(Object value) {
        if (!isEnabled()) {
            return CanonicalJson.write(value);
        }
        if (value instanceof KnowledgeGraph.Node
                || value instanceof KnowledgeGraph.Edge
                || value instanceof KnowledgeGraph.Context
                || value instanceof GraphEvidence
                || value instanceof KnowledgeGraph.Target
                || value instanceof String) {
            int initial = value instanceof GraphEvidence g ? g.canonicalJson().length() + 140 : 256;
            var out = new StringBuilder(initial);
            try {
                writeValue(value, out);
            } catch (IOException impossible) {
                throw new IllegalStateException(impossible);
            }
            return out.toString();
        }
        return CanonicalJson.write(value);
    }

    /** Writes a core graph record or delegates to {@link CanonicalJson} for arbitrary values. */
    public static void writeValue(Object value, Appendable out) throws IOException {
        if (value instanceof String s) {
            appendJsonString(s, out);
        } else if (value instanceof KnowledgeGraph.Context c) {
            writeContext(c, out);
        } else if (value instanceof KnowledgeGraph.Node n) {
            writeNode(n, out);
        } else if (value instanceof KnowledgeGraph.Edge e) {
            writeEdge(e, out);
        } else if (value instanceof GraphEvidence g) {
            writeEvidence(g, out);
        } else if (value instanceof KnowledgeGraph.Target t) {
            writeTarget(t, out);
        } else if (out instanceof Writer w) {
            CanonicalJson.writeTo(value, w);
        } else {
            out.append(CanonicalJson.write(value));
        }
    }

    public static void writeContext(KnowledgeGraph.Context c, Appendable out) throws IOException {
        out.append("{\"analysis\":");
        if (c.analysis().isPresent()) {
            appendJsonString(c.analysis().orElseThrow().value(), out);
        } else {
            out.append("null");
        }
        out.append(",\"evidence\":");
        appendJsonString(c.evidence().value(), out);
        out.append(",\"identity\":");
        appendJsonString(c.identity(), out);
        out.append(",\"snapshot\":");
        appendJsonString(c.snapshot().value(), out);
        out.append('}');
    }

    public static void writeNode(KnowledgeGraph.Node n, Appendable out) throws IOException {
        out.append("{\"context\":");
        appendJsonString(n.context(), out);
        out.append(",\"evidence\":[");
        List<ContentDigest> ev = n.evidence();
        for (int i = 0, len = ev.size(); i < len; i++) {
            if (i > 0) out.append(',');
            appendJsonString(ev.get(i).value(), out);
        }
        out.append("],\"identity\":");
        appendJsonString(n.identity(), out);
        out.append(",\"kind\":");
        appendJsonString(n.kind(), out);
        out.append('}');
    }

    public static void writeEdge(KnowledgeGraph.Edge e, Appendable out) throws IOException {
        out.append("{\"context\":");
        appendJsonString(e.context(), out);
        out.append(",\"evidence\":[");
        List<ContentDigest> ev = e.evidence();
        for (int i = 0, len = ev.size(); i < len; i++) {
            if (i > 0) out.append(',');
            appendJsonString(ev.get(i).value(), out);
        }
        out.append("],\"identity\":");
        appendJsonString(e.identity(), out);
        out.append(",\"kind\":");
        appendJsonString(e.kind(), out);
        out.append(",\"region\":");
        if (e.region().isPresent()) {
            appendJsonString(e.region().orElseThrow(), out);
        } else {
            out.append("null");
        }
        out.append(",\"source\":");
        appendJsonString(e.source(), out);
        out.append(",\"target\":");
        writeTarget(e.target(), out);
        out.append('}');
    }

    public static void writeTarget(KnowledgeGraph.Target t, Appendable out) throws IOException {
        out.append("{\"identities\":[");
        List<String> ids = t.identities();
        for (int i = 0, len = ids.size(); i < len; i++) {
            if (i > 0) out.append(',');
            appendJsonString(ids.get(i), out);
        }
        out.append("],\"state\":");
        appendJsonString(t.state().name(), out);
        out.append(",\"unresolvedReference\":");
        if (t.unresolvedReference().isPresent()) {
            appendJsonString(t.unresolvedReference().orElseThrow(), out);
        } else {
            out.append("null");
        }
        out.append('}');
    }

    public static void writeEvidence(GraphEvidence g, Appendable out) throws IOException {
        out.append("{\"canonicalJson\":");
        appendJsonString(g.canonicalJson(), out);
        out.append(",\"family\":");
        appendJsonString(g.family(), out);
        out.append(",\"identity\":");
        appendJsonString(g.identity().value(), out);
        out.append('}');
    }

    /** Writes the canonical v1 graph body in exact lexicographic member order without reflection. */
    public static void writeGraphBody(KnowledgeGraph graph, Appendable out) throws IOException {
        writeGraphBody(graph.snapshot().value(), graph.contexts(), graph.edges(), graph.evidence(), graph.nodes(), out);
    }

    /** Writes canonical v1 graph body directly from pre-sorted immutable lists. */
    public static void writeGraphBody(
            String snapshot,
            List<KnowledgeGraph.Context> contexts,
            List<KnowledgeGraph.Edge> edges,
            List<GraphEvidence> evidence,
            List<KnowledgeGraph.Node> nodes,
            Appendable out) throws IOException {
        out.append("{\"contexts\":[");
        for (int i = 0, len = contexts.size(); i < len; i++) {
            if (i > 0) out.append(',');
            writeContext(contexts.get(i), out);
        }
        out.append("],\"edges\":[");
        for (int i = 0, len = edges.size(); i < len; i++) {
            if (i > 0) out.append(',');
            writeEdge(edges.get(i), out);
        }
        out.append("],\"evidence\":[");
        for (int i = 0, len = evidence.size(); i < len; i++) {
            if (i > 0) out.append(',');
            writeEvidence(evidence.get(i), out);
        }
        out.append("],\"nodes\":[");
        for (int i = 0, len = nodes.size(); i < len; i++) {
            if (i > 0) out.append(',');
            writeNode(nodes.get(i), out);
        }
        out.append("],\"schema\":");
        appendJsonString(KnowledgeGraph.SCHEMA, out);
        out.append(",\"snapshot\":");
        appendJsonString(snapshot, out);
        out.append('}');
    }

    /** Writes the full {@link GraphSnapshotCodec} v1 envelope without intermediate Map or reflection. */
    public static void writeEnvelope(KnowledgeGraph graph, Appendable out) throws IOException {
        out.append("{\"format\":");
        appendJsonString(GraphSnapshotCodec.FORMAT, out);
        out.append(",\"graph\":");
        writeGraphBody(graph, out);
        out.append(",\"identity\":");
        appendJsonString(graph.identity().value(), out);
        out.append('}');
    }

    /** Computes the canonical v1 graph SHA-256 directly without allocating intermediate Maps or Strings. */
    public static ContentDigest sha256GraphBody(
            String snapshot,
            List<KnowledgeGraph.Context> contexts,
            List<KnowledgeGraph.Edge> edges,
            List<GraphEvidence> evidence,
            List<KnowledgeGraph.Node> nodes) {
        MessageDigest digest = CanonicalJson.borrowSha256();
        try {
            try (var writer = CanonicalJson.newUtf8DigestWriter(digest)) {
                writeGraphBody(snapshot, contexts, edges, evidence, nodes, writer);
            } catch (IOException impossible) {
                throw new IllegalStateException("Canonical digest unavailable", impossible);
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
        } finally {
            CanonicalJson.releaseSha256(digest);
        }
    }

    /**
     * Computes exact JSON value count and nesting depth for the {@link GraphSnapshotCodec} envelope
     * in O(nodes + edges) pure integer arithmetic — identical to {@link CanonicalJson#fitsStructure}.
     */
    public static boolean fitsEnvelopeStructure(KnowledgeGraph graph, int maxDepth, long maxValues) {
        if (maxDepth < 1 || maxValues < 1) {
            throw new IllegalArgumentException("Positive structural limits required");
        }
        // Envelope(0) -> format(1), identity(1), graph(1) -> schema(2), snapshot(2),
        // contexts(2), nodes(2), edges(2), evidence(2) = 10 values, depth 2.
        if (maxDepth < 2) return false;
        long remaining = maxValues - 10L;
        if (remaining < 0) return false;

        var contexts = graph.contexts();
        if (!contexts.isEmpty()) {
            if (maxDepth < 4) return false;
            remaining -= 5L * contexts.size();
            if (remaining < 0) return false;
        }
        var evidence = graph.evidence();
        if (!evidence.isEmpty()) {
            if (maxDepth < 4) return false;
            remaining -= 4L * evidence.size();
            if (remaining < 0) return false;
        }
        var nodes = graph.nodes();
        if (!nodes.isEmpty()) {
            if (maxDepth < 5) return false;
            for (int i = 0, len = nodes.size(); i < len; i++) {
                remaining -= 5L + nodes.get(i).evidence().size();
                if (remaining < 0) return false;
            }
        }
        var edges = graph.edges();
        if (!edges.isEmpty()) {
            if (maxDepth < 5) return false;
            for (int i = 0, len = edges.size(); i < len; i++) {
                var e = edges.get(i);
                int targets = e.target().identities().size();
                if (targets > 0 && maxDepth < 6) return false;
                // Edge(1) + 5 scalars + evidence list(1+ev) + Target(1) + 2 scalars + identities list(1+t) = 11 + ev + t
                remaining -= 11L + e.evidence().size() + targets;
                if (remaining < 0) return false;
            }
        }
        return true;
    }

    /** Appends a canonical RFC-8785-compatible JSON string literal, rejecting unpaired surrogates. */
    public static void appendJsonString(String value, Appendable output) throws IOException {
        output.append('"');
        int start = 0;
        int len = value.length();
        for (int index = 0; index < len; index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\' || character < 0x20) {
                appendSlice(value, start, index, output);
                switch (character) {
                    case '"' -> output.append("\\\"");
                    case '\\' -> output.append("\\\\");
                    case '\b' -> output.append("\\b");
                    case '\f' -> output.append("\\f");
                    case '\n' -> output.append("\\n");
                    case '\r' -> output.append("\\r");
                    case '\t' -> output.append("\\t");
                    default -> output.append("\\u00")
                            .append(HEX[character >>> 4])
                            .append(HEX[character & 15]);
                }
                start = index + 1;
            } else if (Character.isHighSurrogate(character)) {
                if (index + 1 >= len || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException("String contains an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("String contains an unpaired surrogate");
            }
        }
        appendSlice(value, start, len, output);
        output.append('"');
    }

    private static void appendSlice(String value, int start, int end, Appendable output) throws IOException {
        if (start == end) return;
        if (output instanceof Writer writer) {
            writer.write(value, start, end - start);
        } else {
            output.append(value, start, end);
        }
    }
}
