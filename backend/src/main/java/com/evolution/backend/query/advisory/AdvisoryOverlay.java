package com.evolution.backend.query.advisory;

import java.util.*;

/**
 * Isolated read-only advisory overlay for deterministic non-code repository artifacts
 * (Markdown/ADR, SQL DDL <-> JPA, Terraform/HCL, JS/TS workspace aliases, MCP configs).
 *
 * <p>Quarantine invariant: every record carries {@code schema = advisory.doc-link:v1},
 * {@code derivation = INFERRED}, and {@code trust = ADVISORY_UNVERIFIED}, and NEVER mutates
 * canonical {@code KnowledgeGraph} digests, {@code PolicyEvaluator}, {@code ArchitectureAssessmentV2},
 * {@code M11 STRICT}, or repair verification verdicts.
 */
public final class AdvisoryOverlay {
    public static final String SCHEMA = "advisory.doc-link:v1";
    public static final String DERIVATION = "INFERRED";
    public static final String TRUST = "ADVISORY_UNVERIFIED";

    private AdvisoryOverlay() {}

    public record AdvisoryNode(
            String id,
            String kind,
            String label,
            String sourcePath,
            int startLine,
            int endLine,
            String contentSha256,
            String derivation,
            String trust,
            Map<String, Object> metadata) {
        public AdvisoryNode(
                String id,
                String kind,
                String label,
                String sourcePath,
                int startLine,
                int endLine,
                String contentSha256,
                Map<String, Object> metadata) {
            this(id, kind, label, sourcePath, startLine, endLine, contentSha256, DERIVATION, TRUST, metadata);
        }

        public AdvisoryNode {
            id = Objects.requireNonNull(id, "id");
            kind = Objects.requireNonNull(kind, "kind");
            label = label == null ? id : label;
            sourcePath = sourcePath == null ? "" : sourcePath;
            startLine = Math.max(1, startLine);
            endLine = Math.max(startLine, endLine);
            contentSha256 = contentSha256 == null ? "" : contentSha256;
            derivation = DERIVATION;
            trust = TRUST;
            metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("label", label);
            m.put("sourcePath", sourcePath);
            m.put("startLine", startLine);
            m.put("endLine", endLine);
            m.put("contentSha256", contentSha256);
            m.put("derivation", derivation);
            m.put("trust", trust);
            if (!metadata.isEmpty()) m.put("metadata", metadata);
            return Collections.unmodifiableMap(m);
        }
    }

    public record AdvisoryLink(
            String id,
            String kind,
            String sourceId,
            String sourceLabel,
            String targetId,
            String targetLabel,
            String targetKind,
            String sourcePath,
            int startLine,
            int endLine,
            String contentSha256,
            String derivation,
            String trust,
            String matchBasis) {
        public AdvisoryLink(
                String id,
                String kind,
                String sourceId,
                String sourceLabel,
                String targetId,
                String targetLabel,
                String targetKind,
                String sourcePath,
                int startLine,
                int endLine,
                String contentSha256,
                String matchBasis) {
            this(id, kind, sourceId, sourceLabel, targetId, targetLabel, targetKind,
                    sourcePath, startLine, endLine, contentSha256, DERIVATION, TRUST, matchBasis);
        }

        public AdvisoryLink {
            id = Objects.requireNonNull(id, "id");
            kind = Objects.requireNonNull(kind, "kind");
            sourceId = Objects.requireNonNull(sourceId, "sourceId");
            sourceLabel = sourceLabel == null ? sourceId : sourceLabel;
            targetId = Objects.requireNonNull(targetId, "targetId");
            targetLabel = targetLabel == null ? targetId : targetLabel;
            targetKind = targetKind == null ? "UNKNOWN" : targetKind;
            sourcePath = sourcePath == null ? "" : sourcePath;
            startLine = Math.max(1, startLine);
            endLine = Math.max(startLine, endLine);
            contentSha256 = contentSha256 == null ? "" : contentSha256;
            derivation = DERIVATION;
            trust = TRUST;
            matchBasis = matchBasis == null ? "" : matchBasis;
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("sourceId", sourceId);
            m.put("sourceLabel", sourceLabel);
            m.put("targetId", targetId);
            m.put("targetLabel", targetLabel);
            m.put("targetKind", targetKind);
            m.put("sourcePath", sourcePath);
            m.put("startLine", startLine);
            m.put("endLine", endLine);
            m.put("contentSha256", contentSha256);
            m.put("derivation", derivation);
            m.put("trust", trust);
            m.put("matchBasis", matchBasis);
            return Collections.unmodifiableMap(m);
        }
    }

    public record Snapshot(
            String schema,
            String derivation,
            String trust,
            String graphIdentity,
            List<AdvisoryNode> nodes,
            List<AdvisoryLink> links,
            Map<String, Integer> nodeCountsByKind,
            Map<String, Integer> linkCountsByKind,
            int redactedSecretsCount) {
        public Snapshot {
            schema = SCHEMA;
            derivation = DERIVATION;
            trust = TRUST;
            graphIdentity = graphIdentity == null ? "" : graphIdentity;
            nodes = List.copyOf(nodes == null ? List.of() : nodes);
            links = List.copyOf(links == null ? List.of() : links);
            nodeCountsByKind = Collections.unmodifiableMap(new TreeMap<>(nodeCountsByKind == null ? Map.of() : nodeCountsByKind));
            linkCountsByKind = Collections.unmodifiableMap(new TreeMap<>(linkCountsByKind == null ? Map.of() : linkCountsByKind));
        }

        public Map<String, Object> toView(int nodeLimit, int linkLimit) {
            int nl = Math.max(1, Math.min(500, nodeLimit));
            int ll = Math.max(1, Math.min(500, linkLimit));
            var m = new LinkedHashMap<String, Object>();
            m.put("schema", schema);
            m.put("derivation", derivation);
            m.put("trust", trust);
            m.put("quarantined", true);
            m.put("notice", "Advisory links are deterministically extracted from non-code artifacts and strictly quarantined from canonical graph digests, policy verdicts, and Architecture Score v2.");
            m.put("graphIdentity", graphIdentity);
            m.put("totalNodes", nodes.size());
            m.put("totalLinks", links.size());
            m.put("nodeCountsByKind", nodeCountsByKind);
            m.put("linkCountsByKind", linkCountsByKind);
            m.put("redactedSecretsCount", redactedSecretsCount);
            m.put("nodesTruncated", nodes.size() > nl);
            m.put("linksTruncated", links.size() > ll);
            m.put("nodes", nodes.stream().limit(nl).map(AdvisoryNode::toView).toList());
            m.put("links", links.stream().limit(ll).map(AdvisoryLink::toView).toList());
            return Collections.unmodifiableMap(m);
        }
    }
}
