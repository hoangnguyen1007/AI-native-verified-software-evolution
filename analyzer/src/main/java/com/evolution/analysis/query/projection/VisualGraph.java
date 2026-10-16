package com.evolution.analysis.query.projection;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.*;

/** Visual graph representation for workbench presentation, layout, and visual export. */
public record VisualGraph(
        ContentDigest id,
        List<VisualNode> nodes,
        List<VisualEdge> edges,
        Map<String, Object> metadata
) {
    public record VisualNode(
            String id,
            String label,
            String kind,
            String scope,
            Optional<String> parent,
            Optional<String> origin,
            Optional<String> sourceSet,
            int violationCount,
            Optional<String> worstSeverity,
            boolean isCycleParticipant,
            Map<String, Object> properties
    ) {
        public VisualNode {
            Objects.requireNonNull(id);
            Objects.requireNonNull(label);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(scope);
            Objects.requireNonNull(parent);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(sourceSet);
            Objects.requireNonNull(worstSeverity);
            properties = Map.copyOf(properties);
        }
    }

    public record VisualEdge(
            String id,
            String source,
            String target,
            String kind,
            String classification,
            boolean isViolation,
            boolean isCycle,
            int supportCount,
            boolean supportsTruncated,
            Map<String, Object> properties
    ) {
        public VisualEdge {
            Objects.requireNonNull(id);
            Objects.requireNonNull(source);
            Objects.requireNonNull(target);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(classification);
            properties = Map.copyOf(properties);
        }
    }

    public VisualGraph {
        Objects.requireNonNull(id);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        metadata = Map.copyOf(metadata);
    }

    public Map<String, Object> toCytoscape() {
        var cyNodes = new ArrayList<Map<String, Object>>();
        for (var node : nodes) {
            var data = new TreeMap<String, Object>();
            data.put("id", node.id());
            data.put("label", node.label());
            data.put("kind", node.kind());
            data.put("scope", node.scope());
            node.parent().ifPresent(p -> data.put("parent", p));
            node.origin().ifPresent(o -> data.put("origin", o));
            node.sourceSet().ifPresent(s -> data.put("sourceSet", s));
            data.put("violationCount", node.violationCount());
            node.worstSeverity().ifPresent(w -> data.put("worstSeverity", w));
            data.put("isCycle", node.isCycleParticipant());
            if (!node.properties().isEmpty()) {
                data.put("properties", node.properties());
            }
            cyNodes.add(Map.of("data", data));
        }

        var cyEdges = new ArrayList<Map<String, Object>>();
        for (var edge : edges) {
            var data = new TreeMap<String, Object>();
            data.put("id", edge.id());
            data.put("source", edge.source());
            data.put("target", edge.target());
            data.put("kind", edge.kind());
            data.put("classification", edge.classification());
            data.put("isViolation", edge.isViolation());
            data.put("isCycle", edge.isCycle());
            data.put("supportCount", edge.supportCount());
            data.put("supportsTruncated", edge.supportsTruncated());
            if (!edge.properties().isEmpty()) {
                data.put("properties", edge.properties());
            }
            cyEdges.add(Map.of("data", data));
        }

        var elements = new TreeMap<String, Object>();
        elements.put("nodes", cyNodes);
        elements.put("edges", cyEdges);

        var result = new TreeMap<String, Object>();
        result.put("format", "cytoscape.js");
        result.put("graphId", id.value());
        result.put("elements", elements);
        result.put("metadata", metadata);
        return result;
    }

    public String toGraphML() {
        var xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n");
        xml.append("<key id=\"label\" for=\"node\" attr.name=\"label\" attr.type=\"string\"/>\n");
        xml.append("<key id=\"kind\" for=\"node\" attr.name=\"kind\" attr.type=\"string\"/>\n");
        xml.append("<key id=\"violations\" for=\"node\" attr.name=\"violations\" attr.type=\"int\"/>\n");
        xml.append("<key id=\"severity\" for=\"node\" attr.name=\"worstSeverity\" attr.type=\"string\"/>\n");
        xml.append("<key id=\"isCycle\" for=\"all\" attr.name=\"isCycle\" attr.type=\"boolean\"/>\n");
        xml.append("<key id=\"kind\" for=\"edge\" attr.name=\"kind\" attr.type=\"string\"/>\n");
        xml.append("<key id=\"classification\" for=\"edge\" attr.name=\"classification\" attr.type=\"string\"/>\n");
        xml.append("<key id=\"isViolation\" for=\"edge\" attr.name=\"isViolation\" attr.type=\"boolean\"/>\n");
        xml.append("<graph id=\"").append(escape(id.value())).append("\" edgedefault=\"directed\">\n");

        for (var node : nodes) {
            xml.append("  <node id=\"").append(escape(node.id())).append("\">\n");
            xml.append("    <data key=\"label\">").append(escape(node.label())).append("</data>\n");
            xml.append("    <data key=\"kind\">").append(escape(node.kind())).append("</data>\n");
            xml.append("    <data key=\"violations\">").append(node.violationCount()).append("</data>\n");
            node.worstSeverity().ifPresent(w -> xml.append("    <data key=\"severity\">").append(escape(w)).append("</data>\n"));
            xml.append("    <data key=\"isCycle\">").append(node.isCycleParticipant()).append("</data>\n");
            xml.append("  </node>\n");
        }

        for (var edge : edges) {
            xml.append("  <edge id=\"").append(escape(edge.id()))
               .append("\" source=\"").append(escape(edge.source()))
               .append("\" target=\"").append(escape(edge.target())).append("\">\n");
            xml.append("    <data key=\"kind\">").append(escape(edge.kind())).append("</data>\n");
            xml.append("    <data key=\"classification\">").append(escape(edge.classification())).append("</data>\n");
            xml.append("    <data key=\"isViolation\">").append(edge.isViolation()).append("</data>\n");
            xml.append("    <data key=\"isCycle\">").append(edge.isCycle()).append("</data>\n");
            xml.append("  </edge>\n");
        }

        xml.append("</graph>\n</graphml>\n");
        return xml.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }
}
