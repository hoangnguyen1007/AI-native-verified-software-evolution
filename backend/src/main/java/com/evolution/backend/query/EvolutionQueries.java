package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.filesystem.EvolutionResultStore;
import com.evolution.analysis.filesystem.PartitionedGraphStore;
import com.evolution.analysis.graph.GraphJson;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** The public comparison projection shared by the local consumers. Internal evidence bytes stay private. */
public final class EvolutionQueries {
    public static final String VERSION = "architecture.evolution.query:v1";
    public static final int EXPORT_BYTES = 1_048_576;
    private static final int PAGE_SCAN = 250;

    public record Filter(Optional<Side> side, Optional<String> kind, Optional<String> scope, Optional<String> subject) {
        public static Filter empty() { return new Filter(Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty()); }
        public Filter {
            for (var field : List.of(kind, scope, subject)) field.ifPresent(value -> {
                if (value.isEmpty() || value.length() > 512) throw new IllegalArgumentException("Invalid comparison filter");
            });
        }
        boolean matches(Map<String,Object> row) {
            return kind.map(value -> value.equals(row.get("kind")) || value.equals(row.get("family"))
                    || value.equals(row.get("capability"))).orElse(true) &&
                    scope.map(value -> value.equals(row.get("scope")) || value.equals(row.get("context"))).orElse(true) &&
                    subject.map(value -> value.equals(row.get("subject")) || value.equals(row.get("identity"))).orElse(true) &&
                    side.map(value -> belongs(row, value)).orElse(true);
        }
        private static boolean belongs(Map<String,Object> row, Side side) {
            if (row.get("side") instanceof String direct) return side.name().equals(direct);
            return row.get(side == Side.LEFT ? "left" : "right") != null ||
                    row.get(side == Side.LEFT ? "before" : "after") != null;
        }
    }

    public record Query(Section section, int size, Optional<String> cursor, Filter filter) {
        public Query {
            Objects.requireNonNull(section); Objects.requireNonNull(cursor); Objects.requireNonNull(filter);
            if (size < 1 || size > 50) throw new IllegalArgumentException("Invalid comparison page size");
            if (cursor.isPresent() && cursor.orElseThrow().length() > 256) throw new IllegalArgumentException("Invalid comparison cursor");
        }
    }
    public record SummaryView(ContentDigest result, ContentDigest comparison, ContentDigest request, Status status,
                              Map<String,Object> selection, Map<String,String> counts, List<Map<String,Object>> inputs) {}
    public record Page(ContentDigest result, Section section, Status status, List<Map<String,Object>> items,
                       Optional<String> exactTotal, String lowerBound, Optional<String> continuation) {}
    public record Export(SummaryView summary, Section section, Page page, Optional<Section> nextSection) {}
    private final EvolutionResultStore results;
    private final PartitionedGraphStore graphs;

    public EvolutionQueries(EvolutionResultStore results, PartitionedGraphStore graphs) {
        this.results = Objects.requireNonNull(results);
        this.graphs = Objects.requireNonNull(graphs);
    }

    public SummaryView summary(ContentDigest id, BooleanSupplier cancelled) throws IOException {
        return summary(results.open(id, cancelled), cancelled);
    }

    private SummaryView summary(EvolutionResultStore.View view, BooleanSupplier cancelled) throws IOException {
        var summary = view.summary();
        var counts = new TreeMap<String,String>();
        summary.sections().forEach((name,census) -> counts.put(name,Long.toString(census.rows())));
        var inputs = new ArrayList<Map<String,Object>>();
        for (int offset = 0; offset < 2; offset++) for (var row : view.rows(Section.INPUTS, offset, 1, cancelled))
            if ("GRAPH".equals(row.get("kind")) && row.containsKey("side")) inputs.add(EvolutionDisclosure.row(row));
        if (inputs.size() != 2 || !"LEFT".equals(inputs.getFirst().get("side"))
                || !"RIGHT".equals(inputs.getLast().get("side"))) throw new IOException("EVOLUTION_INPUT_CENSUS_CORRUPT");
        return new SummaryView(view.identity(), summary.identity(), summary.request(), summary.status(),
                EvolutionDisclosure.row(view.selection()), counts, List.copyOf(inputs));
    }

    public Page page(ContentDigest id, Query query, BooleanSupplier cancelled) throws IOException {
        return page(results.open(id, cancelled), query, cancelled);
    }

    public Export export(ContentDigest id, Query query, BooleanSupplier cancelled) throws IOException {
        var view = results.open(id, cancelled);
        var page = page(view, query, cancelled);
        return new Export(summary(view, cancelled), query.section(), page, page.continuation().isEmpty()
                ? Arrays.stream(Section.values()).skip(query.section().ordinal() + 1L).findFirst() : Optional.empty());
    }

    private Page page(EvolutionResultStore.View view, Query query, BooleanSupplier cancelled) throws IOException {
        long count = view.summary().census(query.section()).rows();
        var binding = CanonicalJson.sha256(List.of(VERSION, view.identity(), "section-page", query.section(), query.size(),
                query.filter(), "CANONICAL_ORDER"));
        long offset = 0, matched = 0;
        if (query.cursor().isPresent()) {
            var cursor = query.cursor().orElseThrow().split("\\|", -1);
            if (cursor.length != 4 || !binding.value().equals(cursor[0])
                    || !CanonicalJson.sha256(List.of(cursor[0],cursor[1],cursor[2])).value().equals(cursor[3]))
                throw new IllegalArgumentException("Foreign comparison cursor");
            offset = Long.parseLong(cursor[1]);
            matched = Long.parseLong(cursor[2]);
            if (offset < 1 || offset >= count || matched < 0 || matched > offset)
                throw new IllegalArgumentException("Stale comparison cursor");
        }
        var rows = new ArrayList<Map<String,Object>>();
        int scanned = 0, bytes = 0;
        read:
        while (offset < count && rows.size() < query.size() && scanned < PAGE_SCAN) {
            var batch = view.rows(query.section(), offset, Math.min(8, PAGE_SCAN - scanned), cancelled);
            for (var row : batch) {
                if (query.filter().matches(row)) {
                    var projected = EvolutionDisclosure.row(row);
                    int weight;
                    try { weight = BoundedJson.encode(projected, EXPORT_BYTES / 2).length; }
                    catch (BoundedJson.TooLarge large) {
                        // Preserve the ordinal and a verified reference for a row too large for the transport.
                        projected = Map.of("descriptorStatus","ROW_TOO_LARGE","rowIdentity",CanonicalJson.sha256(row));
                        weight = 128;
                    }
                    if (!rows.isEmpty() && bytes + weight > EXPORT_BYTES / 2) break read;
                    rows.add(projected);
                    bytes += weight;
                    matched++;
                }
                offset++;
                scanned++;
                if (rows.size() == query.size() || scanned == PAGE_SCAN) break read;
            }
        }
        Optional<String> next = Optional.empty();
        if (offset < count) {
            var fields = List.of(binding.value(), Long.toString(offset), Long.toString(matched));
            next = Optional.of(String.join("|", fields) + "|" + CanonicalJson.sha256(fields).value());
        }
        var exact = query.filter().equals(Filter.empty()) ? Optional.of(count) :
                next.isEmpty() ? Optional.of(matched) : Optional.<Long>empty();
        return new Page(view.identity(), query.section(), view.summary().status(), List.copyOf(rows), exact.map(String::valueOf),
                Long.toString(exact.orElse(matched)), next);
    }

    public void writeExport(ContentDigest id, Writer writer, BooleanSupplier cancelled) throws IOException {
        var view = results.open(id, cancelled);
        writer.write("{\"schema\":");
        CanonicalJson.writeTo(VERSION, writer);
        writer.write(",\"sections\":{");
        boolean firstSection = true;
        for (var section : Arrays.stream(Section.values()).sorted(Comparator.comparing(Enum::name)).toList()) {
            if (!firstSection) writer.write(',');
            firstSection = false;
            CanonicalJson.writeTo(section.name(), writer);
            writer.write(":[");
            var first = new boolean[]{true};
            view.scan(section, row -> {
                if (!first[0]) writer.write(',');
                first[0] = false;
                CanonicalJson.writeTo(EvolutionDisclosure.row(row), writer);
            }, cancelled);
            writer.write(']');
        }
        writer.write("},\"summary\":");
        CanonicalJson.writeTo(summary(view, cancelled), writer);
        writer.write('}');
    }

    public Map<String,Object> sideGraph(ContentDigest result, Side side, String context, boolean edges, Optional<String> node, int size,
                                         Optional<String> cursor, BooleanSupplier cancelled) throws IOException {
        if (size < 1 || size > 50 || !edges && node.isPresent()) throw new IllegalArgumentException("Invalid graph query");
        if (context.length() > 512 || context.isBlank() || context.contains("|") || context.indexOf(0) >= 0)
            throw new IllegalArgumentException("Invalid graph context");
        var graph = snapshot(result, side, cancelled);
        var binding = CanonicalJson.sha256(List.of(VERSION,result,side,context,edges,node,size)).value() + "|";
        var nested = cursor.map(value -> {
            if (!value.startsWith(binding)) throw new IllegalArgumentException("Foreign comparison graph cursor");
            return value.substring(binding.length());
        });
        var partition = graphs.partition(graph.header().graph(), context)
                .orElseThrow(() -> new FileNotFoundException("EVOLUTION_CONTEXT_NOT_FOUND"));
        var page = node.isPresent() ? graphs.reader(cancelled).adjacency(partition,node.orElseThrow(),false,size,nested) :
                edges ? graph.edges(context, size, nested) : graph.nodes(context, size, nested);
        var items = page.items().stream().map(value -> EvolutionDisclosure.row(GraphJson.object(
                GraphJson.read(CanonicalJson.write(value), GraphJson.Limits.defaults())))).toList();
        return Map.of("side", side, "graph", graph.header().graph(), "context", context,
                "projection",node.isPresent()?"RAW_CONTEXT_OUTGOING":"RAW_CONTEXT_FACTS",
                "items",items,"continuation",page.continuation().map(value -> binding + value));
    }

    public Map<String,Object> evidence(ContentDigest result, Side side, ContentDigest evidence, BooleanSupplier cancelled) throws IOException {
        var graph = snapshot(result, side, cancelled);
        var found = graph.evidence(evidence).orElseThrow(() -> new FileNotFoundException("EVOLUTION_EVIDENCE_NOT_FOUND"));
        var metadata = new TreeMap<String,Object>();
        if (Set.of("source.document", "source.bytes").contains(found.family())) {
            var family = found.family();
            var canonical = found.canonicalJson();
            GraphJson.streamObject(new StringReader(canonical),new GraphJson.StreamLimits(canonical.length(),128,Long.MAX_VALUE),
                    (name,member) -> {
                        if (family.equals("source.bytes") && name.equals("document")) member.object((field,value) -> {
                            if (Set.of("identity","path","classification","contentDigest","module").contains(field))
                                metadata.put(field,value.read());
                            else value.skip();
                        });
                        else if (family.equals("source.document") && Set.of("identity","path","classification","contentDigest","module").contains(name))
                            metadata.put(name,member.read());
                        else member.skip();
                    });
        } else if (found.canonicalJson().length() <= 262_144 && Set.of("java.declaration","java.occurrence",
                "spring.world","spring.witness","spring.witness-replay","spring.explicit-region","spring.symbolic-region",
                "evidence.gap","evidence.attempt","evidence.conflict","evidence.resolution").contains(found.family())) {
            metadata.putAll(GraphJson.object(GraphJson.read(found.canonicalJson(),new GraphJson.Limits(262_144,128,262_144))));
        }
        return Map.of("side", side, "graph", graph.header().graph(), "snapshot", graph.header().snapshot(),
                "identity", found.identity(), "family", found.family(), "redacted", true,
                "metadata", EvolutionDisclosure.row(metadata));
    }

    private EvolutionSnapshot snapshot(ContentDigest result, Side side, BooleanSupplier cancelled) throws IOException {
        var summary = summary(result, cancelled);
        var input = summary.inputs().stream().filter(row -> side.name().equals(row.get("side"))).findFirst()
                .orElseThrow(() -> new FileNotFoundException("EVOLUTION_INPUT_NOT_FOUND"));
        if (!"AVAILABLE".equals(input.get("status"))) throw new FileNotFoundException("EVOLUTION_INPUT_NOT_AVAILABLE");
        var graph = graphs.openEvolution(new ContentDigest(GraphJson.string(input.get("graph"))), cancelled);
        if (!graph.header().snapshot().value().equals(input.get("snapshot"))) throw new IOException("EVOLUTION_INPUT_CHANGED");
        return graph;
    }
}
