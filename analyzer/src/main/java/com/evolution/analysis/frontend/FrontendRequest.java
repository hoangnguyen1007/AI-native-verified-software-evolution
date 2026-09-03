package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.source.SourceClassification;
import java.util.*;

/** One explicitly inventoried module/source-set and ordered resolution environment. */
public record FrontendRequest(AnalysisManifest manifest, ModuleIdentity module,
        SourceClassification sourceSet, List<SourceInput> sources,
        PlatformInput platform, List<BinaryInput> dependencies) {
    public static final String CATALOG = "m2-java-4";
    public static final List<String> CATEGORIES = List.of("declares", "extends", "implements", "permits", "type-uses", "calls", "constructor-calls", "reads-field", "writes-field", "method-references", "has-parameter", "parameter-type", "returns", "field-type", "throws", "annotated-with", "type-parameter-bound", "type-argument");
    public FrontendRequest {
        Objects.requireNonNull(manifest); Objects.requireNonNull(module); Objects.requireNonNull(sourceSet); Objects.requireNonNull(platform);
        sources = sources.stream().sorted(Comparator.comparing(s -> s.document().path())).toList();
        dependencies = List.copyOf(dependencies);
        if (manifest.modules().stream().noneMatch(m -> m.identity().equals(module))) reject("module", "Requested module is absent");
        var expected = manifest.snapshot().documents().stream().filter(d -> d.module().equals(module) && d.classification() == sourceSet).sorted().toList();
        var actual = sources.stream().map(SourceInput::document).sorted().toList();
        if (!actual.equals(expected)) reject("source-coverage", "Sources must equal the complete inventoried module and source-set subset");
        if (new HashSet<>(actual).size() != actual.size()) reject("duplicate-source", "Duplicate source input");
        var entries = new ArrayList<com.evolution.analysis.contract.analysis.ClasspathEntry>();
        entries.add(platform.entry()); dependencies.forEach(d -> entries.add(d.entry()));
        if (!entries.equals(manifest.classpath())) reject("classpath", "Supplied resolution inputs must match the ordered manifest classpath");
        if (dependencies.stream().map(BinaryInput::path).distinct().count() != dependencies.size()) reject("duplicate-binary", "Duplicate physical binary input");
        for (var entry : options(module, sourceSet, actual).entrySet()) {
            if (!entry.getValue().equals(manifest.configuration().values().get(entry.getKey()))) reject("configuration", "Manifest must bind the frontend request plan and versions");
        }
    }
    /** Fixed plan for this slice: immutable in-memory sources, no root discovery, no preview, no truncation. */
    public static Map<String, String> options(ModuleIdentity module, SourceClassification sourceSet, List<com.evolution.analysis.contract.source.SourceDocument> documents) {
        return Map.of("java.release", "21", "java.preview", "false", "java.symbols", "java:v1",
                "java.coordinates", "original-utf16-v1", "java.frontend.catalog", CATALOG,
                "java.module", module.value(), "java.source-set", sourceSet.name(),
                "java.sources", "exact-manifest-subset-v1", "java.limits", "unbounded",
                "java.source-plan", com.evolution.analysis.contract.common.ContentDigest.sha256Utf8(
                        com.evolution.analysis.contract.serialization.CanonicalJson.write(documents.stream().sorted().toList())).value());
    }
    private static void reject(String code, String message) { throw new FrontendInputException("frontend." + code, message); }
}
