package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.contract.source.SourceClassification;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Immutable sibling-module sources occupying one exact reactor classpath position. */
public record ReactorSourceInput(
        ContentDigest identity,
        ModuleIdentity module,
        SourceClassification sourceSet,
        int order,
        List<SourceInput> sources) {
    public static final String SCHEMA = "reactor-source-input-v1";

    public ReactorSourceInput {
        ContractChecks.notNull(identity, "reactor source identity");
        ContractChecks.notNull(module, "reactor source module");
        ContractChecks.notNull(sourceSet, "reactor source set");
        if (order < 0) throw new IllegalArgumentException("Reactor source order must not be negative");
        sources = sources.stream().sorted(Comparator.comparing(value -> value.document().path())).toList();
        if (sources.isEmpty()) throw new IllegalArgumentException("Reactor source input must contain source evidence");
        if (sources.stream().map(value -> value.document().identity()).distinct().count() != sources.size()) {
            throw new IllegalArgumentException("Reactor source input contains duplicate documents");
        }
        for (SourceInput source : sources) {
            if (!source.document().module().equals(module) || source.document().classification() != sourceSet) {
                throw new IllegalArgumentException("Reactor source document belongs to a different module or source set");
            }
        }
        if (!identity.equals(derive(module, sourceSet, order, sources))) {
            throw new IllegalArgumentException("Reactor source identity does not match its evidence");
        }
    }

    public static ReactorSourceInput create(
            ModuleIdentity module, SourceClassification sourceSet, int order, List<SourceInput> sources) {
        List<SourceInput> sorted = sources.stream()
                .sorted(Comparator.comparing(value -> value.document().path())).toList();
        return new ReactorSourceInput(derive(module, sourceSet, order, sorted), module, sourceSet, order, sorted);
    }

    private static ContentDigest derive(
            ModuleIdentity module, SourceClassification sourceSet, int order, List<SourceInput> sources) {
        List<SourceView> views = sources.stream()
                .map(value -> new SourceView(value.document(), value.decoding())).toList();
        return ContentDigest.sha256Utf8(CanonicalJson.write(Map.of(
                "schema", SCHEMA,
                "module", module,
                "sourceSet", sourceSet,
                "order", order,
                "sources", views)));
    }

    private record SourceView(
            com.evolution.analysis.contract.source.SourceDocument document,
            SourceInput.Decoding decoding) {}
}
