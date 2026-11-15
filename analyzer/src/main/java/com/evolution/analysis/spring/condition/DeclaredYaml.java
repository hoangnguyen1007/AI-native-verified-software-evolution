package com.evolution.analysis.spring.condition;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.*;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Passive YAML declaration reader. Only source coordinates and scalar digests cross this boundary. */
public final class DeclaredYaml {
    private static final int MAX_BYTES = 1_000_000;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_DOCUMENTS = 256;
    private static final int MAX_DECLARATIONS = 50_000;
    private static final int MAX_KEY_LENGTH = 256;
    private static final int MAX_PROJECTED_COORDINATE_BYTES = 8_000_000;
    private static final Set<Tag> SCALARS = Set.of(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL);

    private DeclaredYaml() {}

    public enum Rejection { INPUT_LIMIT, ENCODING_UNSUPPORTED, SYNTAX_REJECTED, STRUCTURE_UNSUPPORTED }
    public static final class Rejected extends Exception {
        private final Rejection reason;
        private Rejected(Rejection reason) { super(reason.name()); this.reason = reason; }
        public Rejection reason() { return reason; }
    }
    public record Span(int startLine, int startColumn, int endLine, int endColumn) {}
    public record Declaration(int ordinal, List<Object> key, ContentDigest valueDigest, Span sourceSpan) {
        public Declaration { key = List.copyOf(key); }
    }

    public static List<Declaration> read(byte[] bytes) throws Rejected {
        if (bytes.length > MAX_BYTES) throw new Rejected(Rejection.INPUT_LIMIT);
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException unsupported) {
            throw new Rejected(Rejection.ENCODING_UNSUPPORTED);
        }
        var options = new LoaderOptions();
        options.setMaxAliasesForCollections(0);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(MAX_BYTES);
        var declarations = new ArrayList<Declaration>();
        var budget = new CoordinateBudget();
        try {
            int ordinal = 0;
            for (var root : new Yaml(options).composeAll(new StringReader(source))) {
                if (ordinal >= MAX_DOCUMENTS) throw new Rejected(Rejection.INPUT_LIMIT);
                if (root instanceof MappingNode && Tag.MAP.equals(root.getTag())) {
                    visit(root, ordinal, new ArrayList<>(), declarations, budget, 16);
                } else if (!(root instanceof ScalarNode blank && Tag.NULL.equals(root.getTag()) && blank.getValue().isEmpty())) {
                    throw new Rejected(Rejection.STRUCTURE_UNSUPPORTED);
                }
                ordinal++;
            }
        } catch (YAMLException rejected) {
            throw new Rejected(Rejection.SYNTAX_REJECTED);
        }
        return List.copyOf(declarations);
    }

    private static void visit(Node node, int ordinal, List<Object> path, List<Declaration> declarations,
                              CoordinateBudget budget, int coordinateBytes) throws Rejected {
        if (path.size() > MAX_DEPTH || declarations.size() >= MAX_DECLARATIONS)
            throw new Rejected(Rejection.INPUT_LIMIT);
        if (node.getAnchor() != null) throw new Rejected(Rejection.STRUCTURE_UNSUPPORTED);
        if (node instanceof MappingNode mapping && Tag.MAP.equals(node.getTag()) && !mapping.getValue().isEmpty()) {
            var keys = new HashSet<String>();
            for (var pair : mapping.getValue()) {
                if (!(pair.getKeyNode() instanceof ScalarNode key) || !Tag.STR.equals(key.getTag())
                        || key.getAnchor() != null || key.getValue().isBlank() || !keys.add(key.getValue()))
                    throw new Rejected(Rejection.STRUCTURE_UNSUPPORTED);
                if (key.getValue().length() > MAX_KEY_LENGTH) throw new Rejected(Rejection.INPUT_LIMIT);
                path.add(key.getValue());
                // JSON escaping takes at most six bytes per UTF-16 code unit.
                visit(pair.getValueNode(), ordinal, path, declarations, budget,
                        coordinateBytes + 3 + 6 * key.getValue().length());
                path.removeLast();
            }
            return;
        }
        if (node instanceof SequenceNode sequence && Tag.SEQ.equals(node.getTag()) && !sequence.getValue().isEmpty()) {
            for (int i = 0; i < sequence.getValue().size(); i++) {
                path.add(i);
                visit(sequence.getValue().get(i), ordinal, path, declarations, budget, coordinateBytes + 12);
                path.removeLast();
            }
            return;
        }
        String value;
        if (node instanceof ScalarNode scalar && SCALARS.contains(node.getTag())) value = scalar.getValue();
        else if (node instanceof MappingNode && Tag.MAP.equals(node.getTag())
                || node instanceof SequenceNode && Tag.SEQ.equals(node.getTag())) value = "";
        else throw new Rejected(Rejection.STRUCTURE_UNSUPPORTED);
        var start = node.getStartMark();
        var end = node.getEndMark();
        if (!path.isEmpty()) {
            budget.charge(coordinateBytes);
            declarations.add(new Declaration(ordinal, path, CanonicalJson.sha256(List.of(node.getTag().getValue(), value)),
                    new Span(start.getLine() + 1, start.getColumn() + 1, end.getLine() + 1, end.getColumn() + 1)));
        }
    }

    private static final class CoordinateBudget {
        private int remaining = MAX_PROJECTED_COORDINATE_BYTES;

        void charge(int bytes) throws Rejected {
            remaining -= bytes;
            if (remaining < 0) throw new Rejected(Rejection.INPUT_LIMIT);
        }
    }
}
