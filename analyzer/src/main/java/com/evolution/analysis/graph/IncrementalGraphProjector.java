package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.graph.KnowledgeGraph.Edge;
import com.evolution.analysis.graph.KnowledgeGraph.Node;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Layer-4 Incremental Graph Projector.
 * <p>
 * Caches context-invariant {@link GraphEvidence} payloads (including Base64 {@code source.content}
 * evidence and per-module frontend {@link ProjectedFrontendTemplate}s) so unchanged modules across
 * incremental snapshots skip 95%+ of JSON serialization, SHA-256 evidence digesting, tuple parsing,
 * and node/edge construction while remapping {@code oldContext -> newContext} cleanly and emitting
 * context-bound rows ({@code analysis.manifest}, {@code evidence.ledger-index}, {@code evidence.gap},
 * {@code java.http-client-call}, {@code frontend.result-index}) fresh.
 * <p>
 * Correctness invariant: {@code IncrementalBuild(A -> B).identity().equals(CleanFullBuild(B).identity())}
 * bit-for-bit across both in-memory and disk-spilled {@link GraphProjectionStore}s.
 */
public final class IncrementalGraphProjector {
    private static final int MAX_TEMPLATES = Integer.getInteger("evo.incremental.graphTemplateMaxEntries", 256);
    private static final int MAX_SOURCE_CONTENT_ENTRIES = Integer.getInteger("evo.incremental.sourceContentMaxEntries", 16_384);

    public record Stats(int reusedContexts, int rebuiltContexts, int reusedSourceContents) {}

    record ContextNode(String identity, String kind, List<ContentDigest> evidence) {}

    record ContextEdge(String identity, String kind, String source, KnowledgeGraph.Target target,
            List<ContentDigest> evidence, Optional<String> region) {}

    record StatementRef(String family, String identity, ContentDigest payload) {}

    record MissingOwnerGap(String relationIdentity, ContentDigest payload) {}

    record DeclarationSupportRef(String entityIdentity, String entityKind, List<String> inputIdentities) {}

    record ProjectedFrontendTemplate(
            ContentDigest structuralKey,
            List<GraphEvidence> evidence,
            List<ContextNode> nodes,
            List<ContextEdge> edges,
            List<StatementRef> statements,
            List<DeclarationSupportRef> declarationSupports,
            List<MissingOwnerGap> missingOwnerGaps,
            List<String> projectEntityScopes,
            List<String> declarationDocIds,
            List<String> occurrenceDocIds,
            List<ContentDigest> declarationPayloads,
            List<ContentDigest> occurrencePayloads,
            List<ContentDigest> observationPayloads,
            List<ContentDigest> typeUsePayloads,
            List<ContentDigest> annotationPayloads,
            List<ContentDigest> derivedPayloads,
            List<ContentDigest> typeShapePayloads,
            List<ContentDigest> memberShapePayloads,
            List<ContentDigest> ctorCandidatePayloads,
            List<ContentDigest> diagnosticPayloads,
            List<Map<String, Object>> sourceSummaries) {}

    private final Map<ContentDigest, ProjectedFrontendTemplate> frontendTemplates = new LinkedHashMap<>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<ContentDigest, ProjectedFrontendTemplate> eldest) {
            return size() > MAX_TEMPLATES;
        }
    };
    private final Map<String, GraphEvidence> sourceContentCache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, GraphEvidence> eldest) {
            return size() > MAX_SOURCE_CONTENT_ENTRIES;
        }
    };
    private final AtomicInteger reusedContexts = new AtomicInteger();
    private final AtomicInteger rebuiltContexts = new AtomicInteger();
    private final AtomicInteger reusedSourceContents = new AtomicInteger();

    public void resetStats() {
        reusedContexts.set(0);
        rebuiltContexts.set(0);
        reusedSourceContents.set(0);
    }

    public Stats stats() {
        return new Stats(reusedContexts.get(), rebuiltContexts.get(), reusedSourceContents.get());
    }

    synchronized GraphEvidence getSourceContent(ContentDigest contentDigest, boolean fastArchitecture) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return null;
        GraphEvidence found = sourceContentCache.get((fastArchitecture ? "F:" : "B:") + contentDigest.value());
        if (found != null) {
            reusedSourceContents.incrementAndGet();
        }
        return found;
    }

    synchronized void putSourceContent(ContentDigest contentDigest, boolean fastArchitecture, GraphEvidence evidence) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        sourceContentCache.put((fastArchitecture ? "F:" : "B:") + contentDigest.value(), evidence);
    }

    synchronized ProjectedFrontendTemplate getFrontendTemplate(ContentDigest structuralKey) {
        if (Boolean.getBoolean("evo.incremental.disabled")) return null;
        ProjectedFrontendTemplate found = frontendTemplates.get(structuralKey);
        if (found != null) {
            reusedContexts.incrementAndGet();
        }
        return found;
    }

    synchronized void putFrontendTemplate(ProjectedFrontendTemplate template) {
        rebuiltContexts.incrementAndGet();
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        frontendTemplates.put(template.structuralKey(), template);
    }

    /**
     * Computes a cryptographically exact SHA-256 structural key over ALL fields of {@link FrontendResult}
     * that contribute to context-invariant graph evidence, nodes, and edges (deliberately excluding
     * {@code frontend.analysis()} and {@code httpClientCalls}, which are emitted live per context).
     */
    public static ContentDigest frontendStructuralKey(FrontendResult f) {
        return frontendStructuralKey(f, false);
    }

    public static ContentDigest frontendStructuralKey(FrontendResult f, boolean fastArchitecture) {
        Objects.requireNonNull(f);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), md))) {
                writeLongUtf(out, "graph-frontend-template:v2");
                out.writeBoolean(fastArchitecture);
                writeLongUtf(out, f.frontend().id());
                writeLongUtf(out, f.frontend().version());
                out.writeByte(f.state().ordinal());

                out.writeInt(f.declarations().size());
                for (var d : f.declarations()) {
                    writeEntity(out, d.entity());
                    writeLongUtf(out, d.spelling());
                    out.writeByte(d.status().ordinal());
                    writeDerivation(out, d.derivation());
                    writeUncertainties(out, d.uncertainties());
                    writeDiagnostics(out, d.diagnostics());
                }
                out.writeInt(f.occurrences().size());
                for (var o : f.occurrences()) {
                    writeLongUtf(out, o.identity().value());
                    writeRelationship(out, o.relationship());
                    writeSpan(out, o.span());
                    out.writeInt(o.ordinal());
                    out.writeByte(o.status().ordinal());
                    writeDerivation(out, o.derivation());
                    writeUncertainties(out, o.uncertainties());
                    writeDiagnostics(out, o.diagnostics());
                }
                out.writeInt(f.observations().size());
                for (var ob : f.observations()) {
                    writeLongUtf(out, ob.document().value());
                    writeLongUtf(out, ob.category().value());
                    out.writeBoolean(ob.span().isPresent());
                    if (ob.span().isPresent()) writeSpan(out, ob.span().orElseThrow());
                    out.writeByte(ob.attribution().ordinal());
                    out.writeByte(ob.origin().ordinal());
                    out.writeByte(ob.provenance().ordinal());
                    writeLongUtf(out, ob.mappedOccurrence().map(id -> id.value()).orElse(""));
                    writeLongUtf(out, ob.reference());
                    writeDiagnostics(out, ob.diagnostics());
                }
                out.writeInt(f.sources().size());
                for (var s : f.sources()) {
                    writeLongUtf(out, s.document().value());
                    out.writeByte(s.state().ordinal());
                    writeDiagnostics(out, s.diagnostics());
                }
                out.writeInt(f.diagnostics().size());
                writeDiagnostics(out, f.diagnostics());
                out.writeInt(f.types().size());
                for (var t : f.types()) {
                    writeLongUtf(out, t.owner().map(EntityIdentity::value).orElse(""));
                    writeLongUtf(out, t.role().value());
                    writeSpan(out, t.span());
                    writeJavaType(out, t.type());
                    out.writeBoolean(t.variadic());
                }
                out.writeInt(f.annotations().size());
                for (var a : f.annotations()) {
                    writeLongUtf(out, a.canonicalName());
                    writeLongUtf(out, a.owner().value());
                    writeSpan(out, a.span());
                    writeLongUtf(out, a.site());
                    writeLongUtf(out, a.spelling());
                }
                out.writeInt(f.derivedRelationships().size());
                for (var dr : f.derivedRelationships()) {
                    writeRelationship(out, dr.relationship());
                    out.writeByte(dr.status().ordinal());
                    writeDerivation(out, dr.derivation());
                    out.writeInt(dr.supportingSpans().size());
                    for (var sp : dr.supportingSpans()) writeSpan(out, sp);
                    writeUncertainties(out, dr.uncertainties());
                    writeDiagnostics(out, dr.diagnostics());
                }
                out.writeInt(f.typeDeclarations().size());
                for (var td : f.typeDeclarations()) {
                    writeLongUtf(out, td.type().value());
                    out.writeByte(td.kind().ordinal());
                    out.writeBoolean(td.abstractType());
                    out.writeBoolean(td.independent());
                }
                out.writeInt(f.memberDeclarations().size());
                for (var m : f.memberDeclarations()) {
                    writeLongUtf(out, m.member().value());
                    writeLongUtf(out, m.name());
                    out.writeBoolean(m.staticMember());
                    out.writeBoolean(m.abstractMember());
                    out.writeBoolean(m.genericMethod());
                }
                out.writeInt(f.constructorCandidates().size());
                for (var c : f.constructorCandidates()) {
                    writeLongUtf(out, c.owner().value());
                    writeLongUtf(out, c.field().value());
                    out.writeInt(c.fieldOrder());
                    writeLongUtf(out, c.generator());
                    writeJavaType(out, c.type());
                    out.writeInt(c.supportingSpans().size());
                    for (var sp : c.supportingSpans()) writeSpan(out, sp);
                    out.writeByte(c.status().ordinal());
                }
                out.flush();
            }
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(md.digest()));
        } catch (NoSuchAlgorithmException | IOException impossible) {
            throw new IllegalStateException("Failed computing frontend structural key", impossible);
        }
    }

    private static void writeLongUtf(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static void writeEntity(DataOutputStream out, Entity e) throws IOException {
        writeLongUtf(out, e.identity().value());
        out.writeByte(e.origin().ordinal());
        writeLongUtf(out, e.stableScope().value());
        out.writeByte(e.kind().ordinal());
        writeLongUtf(out, e.canonicalName());
        out.writeBoolean(e.declaration().isPresent());
        if (e.declaration().isPresent()) writeSpan(out, e.declaration().orElseThrow());
    }

    private static void writeSpan(DataOutputStream out, com.evolution.analysis.contract.source.SourceSpan sp) throws IOException {
        writeLongUtf(out, sp.document().value());
        out.writeInt(sp.startLine());
        out.writeInt(sp.startColumn());
        out.writeInt(sp.endLine());
        out.writeInt(sp.endColumn());
    }

    private static void writeDerivation(DataOutputStream out, com.evolution.analysis.contract.semantic.Derivation d) throws IOException {
        out.writeByte(d.kind().ordinal());
        writeLongUtf(out, d.method().id());
        writeLongUtf(out, d.method().version());
        out.writeInt(d.inputIdentities().size());
        for (String id : d.inputIdentities()) writeLongUtf(out, id);
    }

    private static void writeUncertainties(DataOutputStream out, List<com.evolution.analysis.contract.semantic.Uncertainty> list) throws IOException {
        out.writeInt(list.size());
        for (var u : list) {
            writeLongUtf(out, u.code());
            writeLongUtf(out, u.message());
            out.writeInt(u.missingInputs().size());
            for (String m : u.missingInputs()) writeLongUtf(out, m);
        }
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
        writeLongUtf(out, t.target().map(EntityIdentity::value).orElse(""));
        out.writeInt(t.components().size());
        for (var c : t.components()) writeJavaType(out, c);
        out.writeBoolean(t.qualifier().isPresent());
        if (t.qualifier().isPresent()) writeJavaType(out, t.qualifier().orElseThrow());
        out.writeByte(t.status().ordinal());
    }
}
