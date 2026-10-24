package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.analysis.AnalysisManifest;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.ModuleIdentity;
import com.evolution.analysis.contract.source.SourceClassification;
import java.util.*;

/** One explicitly inventoried module/source-set and ordered resolution environment. */
public record FrontendRequest(AnalysisManifest manifest, ModuleIdentity module,
        SourceClassification sourceSet, FrontendPlan plan, List<SourceInput> sources,
        PlatformInput platform, List<BinaryInput> dependencies, List<ReactorSourceInput> reactorSources,
        Optional<com.evolution.analysis.javaevidence.JavaSourceCorrespondence.Verified> correspondence) {
    public static final String CATALOG = "m2-java-4";
    public static final List<String> CATEGORIES = List.of("declares", "extends", "implements", "permits", "type-uses", "calls", "constructor-calls", "reads-field", "writes-field", "method-references", "has-parameter", "parameter-type", "returns", "field-type", "throws", "annotated-with", "type-parameter-bound", "type-argument");
    public FrontendRequest(AnalysisManifest manifest, ModuleIdentity module,
            SourceClassification sourceSet, FrontendPlan plan,List<SourceInput> sources,
            PlatformInput platform,List<BinaryInput> dependencies,List<ReactorSourceInput> reactorSources){
        this(manifest,module,sourceSet,plan,sources,platform,dependencies,reactorSources,Optional.empty());
    }
    public FrontendRequest(AnalysisManifest manifest, ModuleIdentity module,
            SourceClassification sourceSet, List<SourceInput> sources,
            PlatformInput platform, List<BinaryInput> dependencies) {
        this(manifest, module, sourceSet, FrontendPlan.legacy(), sources, platform, dependencies, List.of());
    }

    public FrontendRequest(AnalysisManifest manifest, ModuleIdentity module,
            SourceClassification sourceSet, FrontendPlan plan, List<SourceInput> sources,
            PlatformInput platform, List<BinaryInput> dependencies) {
        this(manifest, module, sourceSet, plan, sources, platform, dependencies, List.of());
    }

    public FrontendRequest {
        Objects.requireNonNull(manifest); Objects.requireNonNull(module); Objects.requireNonNull(sourceSet);
        Objects.requireNonNull(plan); Objects.requireNonNull(platform);
        correspondence=Objects.requireNonNull(correspondence);
        sources = sources.stream().sorted(Comparator.comparing(s -> s.document().path())).toList();
        dependencies = List.copyOf(dependencies);
        reactorSources = reactorSources.stream().sorted(Comparator.comparingInt(ReactorSourceInput::order)).toList();
        if (manifest.modules().stream().noneMatch(m -> m.identity().equals(module))) reject("module", "Requested module is absent");
        var expected = manifest.snapshot().documents().stream().filter(d -> d.module().equals(module)
                && (d.classification() == sourceSet
                        || sourceSet == SourceClassification.MAIN && d.classification() == SourceClassification.GENERATED_MAIN
                        || sourceSet == SourceClassification.TEST && d.classification() == SourceClassification.GENERATED_TEST))
                .sorted().toList();
        var actual = sources.stream().map(SourceInput::document).sorted().toList();
        if (!actual.equals(expected)) reject("source-coverage", "Sources must equal the complete inventoried module and source-set subset");
        if (new HashSet<>(actual).size() != actual.size()) reject("duplicate-source", "Duplicate source input");
        var entries = new ArrayList<com.evolution.analysis.contract.analysis.ClasspathEntry>();
        entries.add(platform.entry()); dependencies.forEach(d -> entries.add(d.entry()));
        if (!entries.equals(manifest.classpath())) reject("classpath", "Supplied resolution inputs must match the ordered manifest classpath");
        if (dependencies.stream().map(BinaryInput::path).distinct().count() != dependencies.size()) reject("duplicate-binary", "Duplicate physical binary input");
        var resolutionDocuments = reactorSources.stream().flatMap(value -> value.sources().stream())
                .map(SourceInput::document).toList();
        if (resolutionDocuments.stream().distinct().count() != resolutionDocuments.size()
                || resolutionDocuments.stream().anyMatch(actual::contains)
                || resolutionDocuments.stream().anyMatch(document -> !manifest.snapshot().documents().contains(document))) {
            reject("reactor-source-coverage", "Reactor sources must be distinct members of the analyzed snapshot");
        }
        if (!reactorSources.isEmpty() && plan.equals(FrontendPlan.legacy())) {
            reject("reactor-source-plan", "Source-level reactor resolution requires an evidence-bound frontend plan");
        }
        int resolutionSize = dependencies.size() + reactorSources.size();
        if (reactorSources.stream().map(ReactorSourceInput::order).distinct().count() != reactorSources.size()
                || reactorSources.stream().anyMatch(value -> value.order() >= resolutionSize)) {
            reject("reactor-source-order", "Reactor source positions must be unique members of the exact classpath order");
        }
        for (var entry : options(plan, module, sourceSet, actual, platform, reactorSources).entrySet()) {
            if (!entry.getValue().equals(manifest.configuration().values().get(entry.getKey()))) reject("configuration", "Manifest must bind the frontend request plan and versions");
        }
        if(!Objects.equals(binaryOriginOptions(dependencies).get("java.binary-native-origins"),manifest.configuration().values().get("java.binary-native-origins")))reject("native-binary-binding","Manifest must bind native origins and ordered solver views");
        validateNativeOrigins(plan,module,sourceSet,manifest.configuration().values(),dependencies,reactorSources);
        if(!Objects.equals(correspondenceOptions(correspondence).get("java.source-binary-correspondence"),manifest.configuration().values().get("java.source-binary-correspondence")))reject("correspondence-binding","Manifest must bind the exact source/native correspondence");
        if(correspondence.isPresent()){
            var value=correspondence.orElseThrow();var scope=value.scope();
            try{if(!value.current())reject("correspondence-revoked","Correspondence references are unavailable");}catch(java.io.IOException unavailable){reject("correspondence-revoked","Correspondence references are unavailable");}
            if(!module.value().equals(scope.get("module"))||!Objects.equals(manifest.configuration().values().get("java.native-context"),scope.get("context"))||!Objects.equals(manifest.configuration().values().getOrDefault("ingestion.source-set-name",sourceSet.name().toLowerCase(Locale.ROOT)),scope.get("sourceSet")))reject("correspondence-scope","Correspondence belongs to another context");
            if(!Objects.equals(manifest.configuration().values().getOrDefault("java.base-snapshot",manifest.snapshot().identity().value()),scope.get("baseSnapshot")))reject("correspondence-snapshot","Correspondence belongs to another base snapshot");
            for(var mapping:value.mappings()){
                var document=mapping.source().declaration().orElseThrow().document();var source=sources.stream().filter(s->s.document().identity().equals(document)).findFirst();
                if(source.isEmpty()||!source.orElseThrow().document().contentDigest().equals(com.evolution.analysis.javaevidence.JavaEvidenceRecord.handle(mapping.record().fields().get("sourceContent")).digest()))reject("correspondence-source","Correspondence source differs from the request");
                if(!value.binaryViews().containsKey(mapping.endpoint().slot())&&dependencies.stream().noneMatch(b->b.reactorModule().filter(module::equals).isPresent()&&b.nativeOrigin().filter(o->o.slot().equals(mapping.endpoint().slot())&&o.original().contentDigest().equals(com.evolution.analysis.javaevidence.JavaEvidenceRecord.handle(mapping.record().fields().get("outputContent")).digest())&&o.proof().filter(p->p.correspondenceMatches(value)).isPresent()).isPresent()))reject("correspondence-output","Correspondence output is not in the exact native classpath or verified member evidence");
            }
        }
        for(var binary:dependencies)if(binary.nativeOrigin().isPresent()&&binary.reactorModule().filter(module::equals).isPresent()&&(correspondence.isEmpty()||correspondence.orElseThrow().mappings().stream().noneMatch(m->m.endpoint().slot().equals(binary.nativeOrigin().orElseThrow().slot()))))reject("correspondence-required","Same-module native output requires per-member correspondence");
    }
    private static void validateNativeOrigins(FrontendPlan plan,ModuleIdentity module,SourceClassification sourceSet,Map<String,String> options,List<BinaryInput> dependencies,List<ReactorSourceInput> reactorSources){
        if(dependencies.stream().anyMatch(b->b.nativeOrigin().isPresent())){
            if(!reactorSources.isEmpty()||dependencies.stream().anyMatch(b->b.nativeOrigin().isEmpty()))reject("native-origin-unverified","Native resolution cannot mix unbound or source-substituted slots");
            var checkedContent=new IdentityHashMap<com.evolution.analysis.acquisition.streaming.RepositoryContentStore,Set<com.evolution.analysis.acquisition.streaming.ContentHandle>>();Set<com.evolution.analysis.javaevidence.JavaEvidenceAuthority.Verified> checkedProofs=Collections.newSetFromMap(new IdentityHashMap<>());
            for(int position=0;position<dependencies.size();position++){var origin=dependencies.get(position).nativeOrigin().orElseThrow();if(origin.proof().isEmpty()||!origin.proof().orElseThrow().matches(plan,module,sourceSet,options,position,dependencies.size(),checkedContent,checkedProofs))reject("native-origin-unverified","Native origin requires a current protected proof for this exact context");}
        }
    }
    /** Origin membership for consumers that must not mistake a solver view for its native artifact. */
    public List<com.evolution.analysis.contract.analysis.ClasspathEntry> originClasspath(){
        validateNativeOrigins(plan,module,sourceSet,manifest.configuration().values(),dependencies,reactorSources);
        var origins=new HashMap<com.evolution.analysis.contract.analysis.ClasspathEntry,com.evolution.analysis.contract.analysis.ClasspathEntry>();dependencies.forEach(b->origins.put(b.entry(),b.originEntry()));
        return manifest.classpath().stream().map(entry->origins.getOrDefault(entry,entry)).toList();
    }
    public static Map<String,String> binaryOriginOptions(List<BinaryInput> binaries){
        if(binaries.stream().noneMatch(b->b.nativeOrigin().isPresent()))return Map.of();
        var rows=binaries.stream().map(b->Map.of("solver",b.entry(),"native",b.nativeOrigin().map(BinaryInput.NativeOrigin::canonicalForm),"module",b.reactorModule())).toList();
        return Map.of("java.binary-native-origins",com.evolution.analysis.contract.serialization.CanonicalJson.sha256(Map.of("schema","java-binary-native-origins:m8.4-v1","slots",rows)).value());
    }
    public static Map<String,String> correspondenceOptions(Optional<com.evolution.analysis.javaevidence.JavaSourceCorrespondence.Verified> value){return value.<Map<String,String>>map(v->Map.of("java.source-binary-correspondence",com.evolution.analysis.contract.serialization.CanonicalJson.sha256(v.canonicalForm()).value())).orElse(Map.of());}
    /** Fixed plan for this slice: immutable in-memory sources, no root discovery, no preview, no truncation. */
    public static Map<String, String> options(ModuleIdentity module, SourceClassification sourceSet, List<com.evolution.analysis.contract.source.SourceDocument> documents) {
        return legacyOptions(module, sourceSet, documents);
    }

    public static Map<String, String> options(
            FrontendPlan plan,
            ModuleIdentity module,
            SourceClassification sourceSet,
            List<com.evolution.analysis.contract.source.SourceDocument> documents,
            PlatformInput platform) {
        return options(plan, module, sourceSet, documents, platform, List.of());
    }

    public static Map<String, String> options(
            FrontendPlan plan,
            ModuleIdentity module,
            SourceClassification sourceSet,
            List<com.evolution.analysis.contract.source.SourceDocument> documents,
            PlatformInput platform,
            List<ReactorSourceInput> reactorSources) {
        if (plan.equals(FrontendPlan.legacy())) return legacyOptions(module, sourceSet, documents);
        Map<String, String> values = new TreeMap<>(legacyOptions(module, sourceSet, documents));
        values.put("java.release", Integer.toString(platform.release()));
        values.put("java.syntax", plan.syntaxLevel().map(String::valueOf).orElse("unspecified"));
        values.put("java.bytecode-target", plan.bytecodeTarget().map(String::valueOf).orElse("unspecified"));
        values.put("java.preview", Boolean.toString(plan.preview()));
        values.put("java.platform.version", platform.version());
        values.put("java.platform.vendor", platform.vendor());
        values.put("java.platform.symbol-digest", platform.entry().contentDigest().value());
        values.put("java.classpath-manifest", plan.classpathManifest().value());
        values.put("java.source-decoding", plan.sourceDecoding().value());
        values.put("java.reactor-source-resolution", "reactor-source-input-v1");
        values.put("java.reactor-sources", ContentDigest.sha256Utf8(
                com.evolution.analysis.contract.serialization.CanonicalJson.write(
                        reactorSources.stream().map(ReactorSourceInput::identity).sorted().toList())).value());
        return Map.copyOf(values);
    }

    private static Map<String, String> legacyOptions(
            ModuleIdentity module,
            SourceClassification sourceSet,
            List<com.evolution.analysis.contract.source.SourceDocument> documents) {
        Map<String, String> values = new TreeMap<>();
        values.put("java.release", "21"); values.put("java.preview", "false");
        values.put("java.symbols", "java:v1"); values.put("java.coordinates", "original-utf16-v1");
        values.put("java.frontend.catalog", CATALOG); values.put("java.module", module.value());
        values.put("java.source-set", sourceSet.name()); values.put("java.sources", "exact-manifest-subset-v1");
        values.put("java.limits", "unbounded");
        values.put("java.source-plan", com.evolution.analysis.contract.common.ContentDigest.sha256Utf8(
                com.evolution.analysis.contract.serialization.CanonicalJson.write(documents.stream().sorted().toList())).value());
        return Map.copyOf(values);
    }
    private static void reject(String code, String message) { throw new FrontendInputException("frontend." + code, message); }
}
