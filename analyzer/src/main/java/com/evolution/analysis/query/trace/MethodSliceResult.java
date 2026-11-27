package com.evolution.analysis.query.trace;

import com.evolution.analysis.contract.semantic.TrustTier;

import java.util.*;

/**
 * Parser-neutral contract for lazy, query-time intra-method CFG/DFG/CDG slices.
 *
 * <p>Emitted on demand by language slicers (such as {@code JavaMethodSlicer} in
 * {@code analyzer-javaparser}) without mutating the canonical {@code KnowledgeGraph}
 * or executing any target compiler/toolchain on the host.
 */
public record MethodSliceResult(
        String path,
        String methodSignature,
        int seedLine,
        SliceDirection direction,
        TrustTier trustTier,
        boolean truncated,
        int totalMethodStatements,
        List<SliceStatement> statements,
        List<Integer> sliceLines,
        List<String> limitations
) {
    /** Traversal direction for intra-method program slicing. */
    public enum SliceDirection {
        BACKWARD,
        FORWARD,
        BOTH;

        public static SliceDirection parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return BACKWARD;
            }
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "BACKWARD", "BACK", "UPSTREAM" -> BACKWARD;
                case "FORWARD", "FWD", "DOWNSTREAM" -> FORWARD;
                case "BOTH", "BIDIRECTIONAL", "FULL" -> BOTH;
                default -> throw new IllegalArgumentException("Unsupported slice direction: " + raw);
            };
        }
    }

    /** Single executable statement or control predicate included in an intra-method slice. */
    public record SliceStatement(
            int line,
            int endLine,
            String kind,
            String code,
            List<Integer> dependsOnLines,
            String dependencyKind
    ) {
        private static final Set<String> VALID_DEPENDENCY_KINDS = Set.of("DATA", "CONTROL", "BOTH", "SEED");

        public SliceStatement {
            if (line < 1) {
                throw new IllegalArgumentException("Statement line must be >= 1: " + line);
            }
            if (endLine < line) {
                endLine = line;
            }
            kind = Objects.requireNonNull(kind, "kind").strip();
            code = Objects.requireNonNull(code, "code");
            Objects.requireNonNull(dependsOnLines, "dependsOnLines");
            dependsOnLines = dependsOnLines.stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            dependencyKind = Objects.requireNonNull(dependencyKind, "dependencyKind").strip().toUpperCase(Locale.ROOT);
            if (!VALID_DEPENDENCY_KINDS.contains(dependencyKind)) {
                throw new IllegalArgumentException("Unsupported dependencyKind: " + dependencyKind);
            }
        }

        /** CanonicalJson-safe map representation. */
        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("line", line);
            m.put("endLine", endLine);
            m.put("kind", kind);
            m.put("code", code);
            m.put("dependsOnLines", dependsOnLines);
            m.put("dependencyKind", dependencyKind);
            return Collections.unmodifiableMap(m);
        }
    }

    public MethodSliceResult {
        path = Objects.requireNonNull(path, "path");
        methodSignature = Objects.requireNonNull(methodSignature, "methodSignature");
        direction = Objects.requireNonNull(direction, "direction");
        trustTier = Objects.requireNonNull(trustTier, "trustTier");
        if (totalMethodStatements < 0) {
            throw new IllegalArgumentException("totalMethodStatements must be >= 0");
        }
        statements = List.copyOf(Objects.requireNonNull(statements, "statements"));
        sliceLines = Objects.requireNonNull(sliceLines, "sliceLines").stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
    }

    /** CanonicalJson-safe map representation suitable for REST, MCP, and deterministic serialization. */
    public Map<String, Object> toView() {
        var view = new TreeMap<String, Object>();
        view.put("path", path);
        view.put("methodSignature", methodSignature);
        view.put("seedLine", seedLine);
        view.put("direction", direction.name());
        view.put("trustTier", trustTier.name());
        view.put("truncated", truncated);
        view.put("totalMethodStatements", totalMethodStatements);
        view.put("statements", statements.stream().map(SliceStatement::toView).toList());
        view.put("sliceLines", sliceLines);
        view.put("limitations", limitations);
        return Collections.unmodifiableMap(view);
    }
}
