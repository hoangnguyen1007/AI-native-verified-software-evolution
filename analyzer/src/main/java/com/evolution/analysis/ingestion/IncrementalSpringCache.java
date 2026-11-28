package com.evolution.analysis.ingestion;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.AnalysisIdentity;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.frontend.FrontendRequest;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.graph.CanonicalGraphInput;
import com.evolution.analysis.spring.SpringFrameworkEvidence;
import com.evolution.analysis.spring.condition.ConfigurationAssignment;
import com.evolution.analysis.spring.condition.ConfigurationSpace;
import com.evolution.analysis.spring.truth.TruthRegionEvaluation;
import com.evolution.analysis.spring.universal.SourceToSpringPlan;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer-3 Spring Plan & SAT Truth Evaluation Incremental Cache.
 * <p>
 * Tracks two complementary keys per source set:
 * <ul>
 *   <li>{@code exactKey}: cryptographically includes {@link AnalysisIdentity}, {@link SnapshotIdentity},
 *       Spring-relevant frontend slices, {@link ConfigDataIngestion.Result#identity()}, Spring framework
 *       evidence, explicit scopes/spaces/baselines, related gaps, and SAT evaluation policy. A hit returns
 *       the exact {@link CanonicalGraphInput.SpringSlice} and {@code builtGaps} with guaranteed bit-for-bit
 *       canonical graph identity parity.</li>
 *   <li>{@code semanticDigest}: hashes ONLY Spring-relevant Java frontend slices (declarations, annotations,
 *       type/member shapes, constructor candidates, derived relationships, types) + Spring framework evidence
 *       + Spring config data, excluding non-Spring method-body occurrences and unrelated modules, so callers
 *       can detect whether a module's Spring semantics actually changed across commits.</li>
 * </ul>
 */
public final class IncrementalSpringCache {
    private static final int MAX_ENTRIES = Integer.getInteger("evo.incremental.springCacheMaxEntries", 256);

    public record CachedOutcome(
            CanonicalGraphInput.SpringSlice slice,
            List<CapabilityGapRecord> builtGaps,
            ContentDigest springFrontendDigest,
            ContentDigest configDataDigest) {
        public CachedOutcome {
            Objects.requireNonNull(slice);
            slice = detachFromWorkspace(slice);
            builtGaps = List.copyOf(builtGaps);
            Objects.requireNonNull(springFrontendDigest);
            Objects.requireNonNull(configDataDigest);
        }

        /**
         * Materializes any workspace-spool-backed {@link AnalysisWorkspace.ReplayList} inside the cached
         * {@link CanonicalGraphInput.SpringSlice} into plain immutable in-memory lists so reuse on later
         * runs never dereferences a closed {@code FileAnalysisWorkspace} spool ({@code ANALYSIS_SPOOL_CLOSED}).
         */
        private static CanonicalGraphInput.SpringSlice detachFromWorkspace(CanonicalGraphInput.SpringSlice slice) {
            var plan = slice.plan();
            var materializedGaps = List.copyOf(plan.gaps());
            if (materializedGaps == plan.gaps()) {
                return slice;
            }
            var materializedPlan = new SourceToSpringPlan.Result(
                    plan.inputIdentity(),
                    plan.inventory(),
                    plan.conditions(),
                    plan.binding(),
                    materializedGaps,
                    plan.methodSelections());
            return new CanonicalGraphInput.SpringSlice(
                    slice.sourceSet(),
                    materializedPlan,
                    slice.space(),
                    slice.baseline(),
                    slice.explicitTruth(),
                    slice.symbolicTruth(),
                    slice.parameters());
        }
    }

    public record Stats(int reusedContexts, int recomputedContexts, int semanticUnchangedContexts) {}

    private final Map<ContentDigest, CachedOutcome> exactCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, CachedOutcome> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final Map<UniversalSourceIngestion.SourceSet, ContentDigest> lastSemanticDigestBySet = new HashMap<>();
    private final AtomicInteger reusedContexts = new AtomicInteger();
    private final AtomicInteger recomputedContexts = new AtomicInteger();
    private final AtomicInteger semanticUnchangedContexts = new AtomicInteger();

    public void resetStats() {
        reusedContexts.set(0);
        recomputedContexts.set(0);
        semanticUnchangedContexts.set(0);
    }

    public Stats stats() {
        return new Stats(reusedContexts.get(), recomputedContexts.get(), semanticUnchangedContexts.get());
    }

    /**
     * Computes a deterministic digest over ALL Spring-relevant slices of a {@link FrontendResult}
     * consumed by {@link com.evolution.analysis.spring.universal.SpringSourceEvidence}
     * (sources, diagnostics, annotations with spans, type/member shapes, declarations with entity metadata
     * and derivations, constructor candidates with types and spans, types with full JavaType and spans,
     * derived relationships, and Spring-relevant resolved occurrences) plus {@link SpringFrameworkEvidence}.
     * Pure non-Spring method-body call occurrences keep this digest identical.
     */
    public static ContentDigest springFrontendDigest(FrontendResult frontend, SpringFrameworkEvidence framework) {
        Objects.requireNonNull(frontend);
        Objects.requireNonNull(framework);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                writeSpringFrontendCore(out, frontend, framework);
                out.flush();
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Failed computing Spring frontend digest", impossible);
        }
    }

    /**
     * Computes a complete deterministic digest over all {@link com.evolution.analysis.spring.universal.SpringSourceEvidence}
     * inputs ({@link FrontendResult}, {@link SpringFrameworkEvidence}, {@link FrontendRequest} source/reactor
     * document digests &amp; origin classpath) plus polyglot service-syntax inputs (including reused
     * {@code IncrementalPolyglotCache} entries).
     */
    public static ContentDigest springFrontendDigest(
            FrontendRequest request,
            FrontendResult frontend,
            SpringFrameworkEvidence framework,
            ContentDigest polyglotSyntaxDigest) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(frontend);
        Objects.requireNonNull(framework);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                writeLongUtf(out, "spring-frontend-complete:v3");
                writeSpringFrontendCore(out, frontend, framework);
                out.writeBoolean(polyglotSyntaxDigest != null);
                if (polyglotSyntaxDigest != null) {
                    writeLongUtf(out, polyglotSyntaxDigest.value());
                }
                var origins = request.originClasspath();
                out.writeInt(origins.size());
                for (var cp : origins) {
                    writeLongUtf(out, cp.logicalName());
                    out.writeByte(cp.kind().ordinal());
                    writeLongUtf(out, cp.contentDigest().value());
                }
                var sources = request.sources();
                out.writeInt(sources.size());
                for (var s : sources) {
                    writeLongUtf(out, s.document().identity().value());
                    writeLongUtf(out, s.document().path());
                    writeLongUtf(out, s.document().contentDigest().value());
                }
                var reactorSources = request.reactorSources();
                out.writeInt(reactorSources.size());
                for (var rs : reactorSources) {
                    out.writeInt(rs.sources().size());
                    for (var s : rs.sources()) {
                        writeLongUtf(out, s.document().identity().value());
                        writeLongUtf(out, s.document().path());
                        writeLongUtf(out, s.document().contentDigest().value());
                    }
                }
                out.flush();
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Failed computing complete Spring frontend digest", impossible);
        }
    }

    private static final Set<String> SPRING_OCCURRENCE_KINDS = Set.of(
            "java.extends", "java.implements", "java.declares", "java.annotated-with");

    private static void writeSpringFrontendCore(
            DataOutputStream out, FrontendResult frontend, SpringFrameworkEvidence framework) throws IOException {
        writeLongUtf(out, "spring-frontend-slice:v3");
        writeLongUtf(out, frontend.frontend().id());
        writeLongUtf(out, frontend.frontend().version());
        writeLongUtf(out, frontend.state().name());
        writeLongUtf(out, framework.identity().value());
        out.writeBoolean(framework.completeClasspath());
        out.writeInt(framework.artifacts().size());
        for (var art : framework.artifacts()) {
            writeLongUtf(out, art.coordinate());
            writeLongUtf(out, art.classpathLogicalName());
            writeLongUtf(out, art.contentDigest().value());
        }
        out.writeInt(frontend.sources().size());
        for (var s : frontend.sources()) {
            writeLongUtf(out, s.document().value());
            out.writeByte(s.state().ordinal());
            writeDiagnostics(out, s.diagnostics());
        }
        writeDiagnostics(out, frontend.diagnostics());
        out.writeInt(frontend.annotations().size());
        for (var a : frontend.annotations()) {
            writeLongUtf(out, a.canonicalName());
            writeLongUtf(out, a.owner().value());
            writeSpan(out, a.span());
            writeLongUtf(out, a.site());
            writeLongUtf(out, a.spelling());
        }
        out.writeInt(frontend.typeDeclarations().size());
        for (var td : frontend.typeDeclarations()) {
            writeLongUtf(out, td.type().value());
            writeLongUtf(out, td.kind().name());
            out.writeBoolean(td.abstractType());
            out.writeBoolean(td.independent());
        }
        out.writeInt(frontend.memberDeclarations().size());
        for (var m : frontend.memberDeclarations()) {
            writeLongUtf(out, m.member().value());
            writeLongUtf(out, m.name());
            out.writeBoolean(m.staticMember());
            out.writeBoolean(m.abstractMember());
            out.writeBoolean(m.genericMethod());
        }
        out.writeInt(frontend.constructorCandidates().size());
        for (var c : frontend.constructorCandidates()) {
            writeLongUtf(out, c.owner().value());
            writeLongUtf(out, c.field().value());
            out.writeInt(c.fieldOrder());
            writeLongUtf(out, c.generator());
            writeJavaType(out, c.type());
            out.writeInt(c.supportingSpans().size());
            for (var sp : c.supportingSpans()) writeSpan(out, sp);
            writeLongUtf(out, c.status().name());
        }
        out.writeInt(frontend.declarations().size());
        for (var d : frontend.declarations()) {
            var e = d.entity();
            writeLongUtf(out, e.identity().value());
            out.writeByte(e.origin().ordinal());
            writeLongUtf(out, e.stableScope().value());
            out.writeByte(e.kind().ordinal());
            writeLongUtf(out, e.canonicalName());
            out.writeBoolean(e.declaration().isPresent());
            if (e.declaration().isPresent()) writeSpan(out, e.declaration().orElseThrow());
            writeLongUtf(out, d.spelling());
            writeLongUtf(out, d.status().name());
            out.writeByte(d.derivation().kind().ordinal());
            writeLongUtf(out, d.derivation().method().id());
            writeLongUtf(out, d.derivation().method().version());
            out.writeInt(d.derivation().inputIdentities().size());
            for (String inId : d.derivation().inputIdentities()) writeLongUtf(out, inId);
        }
        out.writeInt(frontend.types().size());
        for (var t : frontend.types()) {
            writeLongUtf(out, t.owner().map(id -> id.value()).orElse(""));
            writeLongUtf(out, t.role().value());
            writeSpan(out, t.span());
            writeJavaType(out, t.type());
            out.writeBoolean(t.variadic());
        }
        out.writeInt(frontend.derivedRelationships().size());
        for (var dr : frontend.derivedRelationships()) {
            writeRelationship(out, dr.relationship());
            writeLongUtf(out, dr.status().name());
            out.writeInt(dr.supportingSpans().size());
            for (var sp : dr.supportingSpans()) writeSpan(out, sp);
        }
        int springOccCount = 0;
        for (var o : frontend.occurrences()) {
            if (o.status() == com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED
                    && SPRING_OCCURRENCE_KINDS.contains(o.relationship().kind().value())) {
                springOccCount++;
            }
        }
        out.writeInt(springOccCount);
        for (var o : frontend.occurrences()) {
            if (o.status() == com.evolution.analysis.contract.semantic.SemanticStatus.RESOLVED
                    && SPRING_OCCURRENCE_KINDS.contains(o.relationship().kind().value())) {
                writeLongUtf(out, o.identity().value());
                writeRelationship(out, o.relationship());
                writeSpan(out, o.span());
                out.writeInt(o.ordinal());
            }
        }
    }

    private static void writeSpan(DataOutputStream out, com.evolution.analysis.contract.source.SourceSpan sp) throws IOException {
        writeLongUtf(out, sp.document().value());
        out.writeInt(sp.startLine());
        out.writeInt(sp.startColumn());
        out.writeInt(sp.endLine());
        out.writeInt(sp.endColumn());
    }

    private static void writeDiagnostics(DataOutputStream out, List<com.evolution.analysis.contract.semantic.Diagnostic> list) throws IOException {
        out.writeInt(list.size());
        for (var d : list) {
            out.writeByte(d.severity().ordinal());
            writeLongUtf(out, d.code());
            writeLongUtf(out, d.message());
            out.writeBoolean(d.span().isPresent());
            if (d.span().isPresent()) writeSpan(out, d.span().orElseThrow());
            out.writeInt(d.details().size());
            for (var e : d.details().entrySet()) {
                writeLongUtf(out, e.getKey());
                writeLongUtf(out, e.getValue());
            }
        }
    }

    private static void writeRelationship(DataOutputStream out, com.evolution.analysis.contract.semantic.SemanticRelationship r) throws IOException {
        writeLongUtf(out, r.identity().value());
        writeLongUtf(out, r.source().value());
        writeLongUtf(out, r.kind().value());
        if (r.target() instanceof com.evolution.analysis.contract.semantic.RelationshipTarget.Resolved res) {
            out.writeByte(0);
            writeLongUtf(out, res.target().value());
        } else if (r.target() instanceof com.evolution.analysis.contract.semantic.RelationshipTarget.Candidates c) {
            out.writeByte(1);
            out.writeInt(c.candidates().size());
            for (var id : c.candidates()) writeLongUtf(out, id.value());
        } else if (r.target() instanceof com.evolution.analysis.contract.semantic.RelationshipTarget.Unresolved u) {
            out.writeByte(2);
            writeLongUtf(out, u.stableReference());
        }
    }

    private static void writeJavaType(DataOutputStream out, com.evolution.analysis.frontend.JavaType t) throws IOException {
        out.writeByte(t.kind().ordinal());
        writeLongUtf(out, t.spelling());
        writeLongUtf(out, t.target().map(id -> id.value()).orElse(""));
        out.writeInt(t.components().size());
        for (var c : t.components()) writeJavaType(out, c);
        out.writeBoolean(t.qualifier().isPresent());
        if (t.qualifier().isPresent()) writeJavaType(out, t.qualifier().orElseThrow());
        out.writeByte(t.status().ordinal());
    }

    private static void writeLongUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** Computes the exact cryptographic cache key guaranteeing bit-for-bit identical {@link CanonicalGraphInput.SpringSlice}. */
    public static ContentDigest exactKey(
            FrontendRequest request,
            FrontendResult frontend,
            ContentDigest springFrontendDigest,
            ContentDigest configDataDigest,
            List<CapabilityGapRecord> relatedGaps,
            Optional<SourceToSpringPlan.Scope> explicitScope,
            Optional<ConfigurationSpace> policySpace,
            Optional<ConfigurationAssignment> policyBaseline,
            UniversalArchitecturePipeline.SpringSatMode satMode,
            UniversalArchitecturePipeline.TruthEvaluationMode truthMode,
            TruthRegionEvaluation.Limits truthLimits,
            int maxTypes,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        return exactKey(request, frontend, springFrontendDigest, configDataDigest, relatedGaps,
                explicitScope, policySpace, policyBaseline, satMode, truthMode, truthLimits,
                maxTypes, validationMode, null);
    }

    public static ContentDigest exactKey(
            FrontendRequest request,
            FrontendResult frontend,
            ContentDigest springFrontendDigest,
            ContentDigest configDataDigest,
            List<CapabilityGapRecord> relatedGaps,
            Optional<SourceToSpringPlan.Scope> explicitScope,
            Optional<ConfigurationSpace> policySpace,
            Optional<ConfigurationAssignment> policyBaseline,
            UniversalArchitecturePipeline.SpringSatMode satMode,
            UniversalArchitecturePipeline.TruthEvaluationMode truthMode,
            TruthRegionEvaluation.Limits truthLimits,
            int maxTypes,
            SpringFrameworkEvidence.ValidationMode validationMode,
            ContentDigest polyglotSyntaxDigest) {
        SnapshotIdentity snapshot = request.manifest().snapshot().identity();
        AnalysisIdentity analysis = request.manifest().identity();
        var gapIds = new ArrayList<String>(relatedGaps.size());
        for (var g : relatedGaps) gapIds.add(g.gapIdentity().value());
        return CanonicalJson.sha256(List.of(
                "spring-sat-exact:v2",
                snapshot,
                analysis,
                frontend.canonicalDigest(),
                springFrontendDigest,
                configDataDigest,
                polyglotSyntaxDigest != null ? polyglotSyntaxDigest.value() : "polyglot-syntax:none",
                gapIds,
                explicitScope.map(s -> CanonicalJson.sha256(s).value()).orElse("auto"),
                policySpace.map(s -> s.identity().value()).orElse("auto-space"),
                policyBaseline.map(a -> a.identity().value()).orElse("auto-baseline"),
                satMode.name(),
                truthMode.name(),
                truthLimits,
                maxTypes,
                validationMode.name()));
    }

    /** Computes the snapshot-independent semantic fingerprint of a module's Spring configuration and structure. */
    public static ContentDigest semanticKey(
            UniversalSourceIngestion.SourceSet sourceSet,
            ContentDigest springFrontendDigest,
            ContentDigest configDataDigest,
            UniversalArchitecturePipeline.SpringSatMode satMode,
            UniversalArchitecturePipeline.TruthEvaluationMode truthMode,
            TruthRegionEvaluation.Limits truthLimits,
            SpringFrameworkEvidence.ValidationMode validationMode) {
        return semanticKey(sourceSet, springFrontendDigest, configDataDigest, satMode, truthMode,
                truthLimits, validationMode, null);
    }

    public static ContentDigest semanticKey(
            UniversalSourceIngestion.SourceSet sourceSet,
            ContentDigest springFrontendDigest,
            ContentDigest configDataDigest,
            UniversalArchitecturePipeline.SpringSatMode satMode,
            UniversalArchitecturePipeline.TruthEvaluationMode truthMode,
            TruthRegionEvaluation.Limits truthLimits,
            SpringFrameworkEvidence.ValidationMode validationMode,
            ContentDigest polyglotSyntaxDigest) {
        return CanonicalJson.sha256(List.of(
                "spring-sat-semantic:v2",
                sourceSet,
                springFrontendDigest,
                configDataDigest,
                polyglotSyntaxDigest != null ? polyglotSyntaxDigest.value() : "polyglot-syntax:none",
                satMode.name(),
                truthMode.name(),
                truthLimits,
                validationMode.name()));
    }

    public synchronized Optional<CachedOutcome> getExact(ContentDigest exactKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return Optional.empty();
        CachedOutcome found = exactCache.get(exactKey);
        if (found != null) {
            reusedContexts.incrementAndGet();
            return Optional.of(found);
        }
        return Optional.empty();
    }

    public synchronized void recordComputed(
            UniversalSourceIngestion.SourceSet sourceSet,
            ContentDigest exactKey,
            ContentDigest semanticKey,
            CachedOutcome outcome) {
        recomputedContexts.incrementAndGet();
        ContentDigest priorSemantic = lastSemanticDigestBySet.put(sourceSet, semanticKey);
        if (priorSemantic != null && priorSemantic.equals(semanticKey)) {
            semanticUnchangedContexts.incrementAndGet();
        }
        if (!Boolean.getBoolean("evo.incremental.disabled")) {
            exactCache.put(exactKey, outcome);
        }
    }

    public synchronized boolean isSemanticallyUnchanged(
            UniversalSourceIngestion.SourceSet sourceSet,
            ContentDigest currentSemanticKey) {
        ContentDigest prior = lastSemanticDigestBySet.get(sourceSet);
        return prior != null && prior.equals(currentSemanticKey);
    }
}
