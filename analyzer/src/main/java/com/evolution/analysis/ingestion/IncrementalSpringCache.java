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
     * Computes a deterministic digest over ONLY the Spring-relevant slices of a {@link FrontendResult}
     * (annotations, type/member shapes, declarations, constructor candidates, types, derived relationships)
     * plus the {@link SpringFrameworkEvidence} identity. Pure non-Spring method-body edits keep this digest identical.
     */
    public static ContentDigest springFrontendDigest(FrontendResult frontend, SpringFrameworkEvidence framework) {
        Objects.requireNonNull(frontend);
        Objects.requireNonNull(framework);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                writeLongUtf(out, "spring-frontend-slice:v2");
                writeLongUtf(out, frontend.frontend().id());
                writeLongUtf(out, frontend.frontend().version());
                writeLongUtf(out, frontend.state().name());
                writeLongUtf(out, framework.identity().value());
                out.writeInt(frontend.annotations().size());
                for (var a : frontend.annotations()) {
                    writeLongUtf(out, a.canonicalName());
                    writeLongUtf(out, a.owner().value());
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
                    writeLongUtf(out, c.type().spelling());
                    writeLongUtf(out, c.status().name());
                }
                out.writeInt(frontend.declarations().size());
                for (var d : frontend.declarations()) {
                    writeLongUtf(out, d.entity().identity().value());
                    writeLongUtf(out, d.entity().canonicalName());
                    writeLongUtf(out, d.spelling());
                    writeLongUtf(out, d.status().name());
                }
                out.writeInt(frontend.types().size());
                for (var t : frontend.types()) {
                    writeLongUtf(out, t.owner().map(id -> id.value()).orElse(""));
                    writeLongUtf(out, t.role().value());
                    writeLongUtf(out, t.type().spelling());
                    writeLongUtf(out, t.type().target().map(id -> id.value()).orElse(""));
                    out.writeBoolean(t.variadic());
                }
                out.writeInt(frontend.derivedRelationships().size());
                for (var dr : frontend.derivedRelationships()) {
                    writeLongUtf(out, dr.relationship().identity().value());
                    writeLongUtf(out, dr.status().name());
                }
                out.flush();
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Failed computing Spring frontend digest", impossible);
        }
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
        SnapshotIdentity snapshot = request.manifest().snapshot().identity();
        AnalysisIdentity analysis = request.manifest().identity();
        var gapIds = new ArrayList<String>(relatedGaps.size());
        for (var g : relatedGaps) gapIds.add(g.gapIdentity().value());
        return CanonicalJson.sha256(List.of(
                "spring-sat-exact:v1",
                snapshot,
                analysis,
                frontend.canonicalDigest(),
                springFrontendDigest,
                configDataDigest,
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
        return CanonicalJson.sha256(List.of(
                "spring-sat-semantic:v1",
                sourceSet,
                springFrontendDigest,
                configDataDigest,
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
