package com.evolution.analysis.graph;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.identity.EntityIdentity;
import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.semantic.Entity;
import com.evolution.analysis.contract.semantic.EntityKind;
import com.evolution.analysis.contract.semantic.EntityOrigin;
import com.evolution.analysis.frontend.FrontendResult;
import com.evolution.analysis.frontend.SourceOutcome;
import com.evolution.analysis.graph.KnowledgeGraph.Edge;
import com.evolution.analysis.graph.KnowledgeGraph.Node;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

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
    private static final String DISK_MAGIC = "EVOGT2";
    private final Path cacheDirectory;

    public IncrementalGraphProjector() {
        this(null);
    }

    public IncrementalGraphProjector(Path cacheDirectory) {
        this.cacheDirectory = cacheDirectory == null ? null : cacheDirectory.toAbsolutePath().normalize();
    }

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
        if (found == null && cacheDirectory != null) {
            found = loadTemplateFromDisk(structuralKey);
            if (found != null) {
                frontendTemplates.put(structuralKey, found);
            }
        }
        if (found != null) {
            reusedContexts.incrementAndGet();
        }
        return found;
    }

    synchronized void putFrontendTemplate(ProjectedFrontendTemplate template) {
        rebuiltContexts.incrementAndGet();
        if (Boolean.getBoolean("evo.incremental.disabled")) return;
        frontendTemplates.put(template.structuralKey(), template);
        if (cacheDirectory != null) {
            saveTemplateToDisk(template);
        }
    }

    private Path templatePath(ContentDigest structuralKey) {
        String raw = structuralKey.value();
        String hex = raw.startsWith("sha256:") ? raw.substring(7) : raw;
        return cacheDirectory.resolve(hex + ".gt2");
    }

    private ProjectedFrontendTemplate loadTemplateFromDisk(ContentDigest structuralKey) {
        Path file = templatePath(structuralKey);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return null;
        try (var gz = new GZIPInputStream(Files.newInputStream(file))) {
            byte[] all = gz.readAllBytes();
            if (all.length < 32) return null;
            int payloadLen = all.length - 32;
            byte[] payload = Arrays.copyOfRange(all, 0, payloadLen);
            byte[] expectedHash = Arrays.copyOfRange(all, payloadLen, all.length);
            byte[] actualHash = MessageDigest.getInstance("SHA-256").digest(payload);
            if (!MessageDigest.isEqual(actualHash, expectedHash)) return null;
            try (var in = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (!DISK_MAGIC.equals(readLongUtf(in))) return null;
                ContentDigest storedKey = new ContentDigest(readLongUtf(in));
                if (!structuralKey.equals(storedKey)) return null;
                int evCount = in.readInt();
                var evList = new ArrayList<GraphEvidence>(evCount);
                for (int i = 0; i < evCount; i++) {
                    ContentDigest id = new ContentDigest(readLongUtf(in));
                    String family = readLongUtf(in);
                    String json = readLongUtf(in);
                    evList.add(GraphEvidence.fromAuthenticatedCache(id, family, json));
                }
                int nodeCount = in.readInt();
                var nodeList = new ArrayList<ContextNode>(nodeCount);
                for (int i = 0; i < nodeCount; i++) {
                    String id = readLongUtf(in);
                    String kind = readLongUtf(in);
                    nodeList.add(new ContextNode(id, kind, readDigestList(in)));
                }
                int edgeCount = in.readInt();
                var edgeList = new ArrayList<ContextEdge>(edgeCount);
                for (int i = 0; i < edgeCount; i++) {
                    String id = readLongUtf(in);
                    String kind = readLongUtf(in);
                    String source = readLongUtf(in);
                    var state = KnowledgeGraph.TargetState.values()[in.readUnsignedByte()];
                    var targetIds = readStringList(in);
                    Optional<String> unresolved = in.readBoolean() ? Optional.of(readLongUtf(in)) : Optional.empty();
                    var edgeEv = readDigestList(in);
                    Optional<String> region = in.readBoolean() ? Optional.of(readLongUtf(in)) : Optional.empty();
                    edgeList.add(new ContextEdge(id, kind, source, new KnowledgeGraph.Target(state, targetIds, unresolved), edgeEv, region));
                }
                int stCount = in.readInt();
                var stList = new ArrayList<StatementRef>(stCount);
                for (int i = 0; i < stCount; i++) {
                    stList.add(new StatementRef(readLongUtf(in), readLongUtf(in), new ContentDigest(readLongUtf(in))));
                }
                int dsCount = in.readInt();
                var dsList = new ArrayList<DeclarationSupportRef>(dsCount);
                for (int i = 0; i < dsCount; i++) {
                    dsList.add(new DeclarationSupportRef(readLongUtf(in), readLongUtf(in), readStringList(in)));
                }
                int mgCount = in.readInt();
                var mgList = new ArrayList<MissingOwnerGap>(mgCount);
                for (int i = 0; i < mgCount; i++) {
                    mgList.add(new MissingOwnerGap(readLongUtf(in), new ContentDigest(readLongUtf(in))));
                }
                var scopes = readStringList(in);
                var declDocs = readStringList(in);
                var occDocs = readStringList(in);
                var declPayloads = readDigestList(in);
                var occPayloads = readDigestList(in);
                var obsPayloads = readDigestList(in);
                var typeUsePayloads = readDigestList(in);
                var annPayloads = readDigestList(in);
                var derivedPayloads = readDigestList(in);
                var typeShapePayloads = readDigestList(in);
                var memberShapePayloads = readDigestList(in);
                var ctorPayloads = readDigestList(in);
                var diagPayloads = readDigestList(in);
                int sumCount = in.readInt();
                var sourceSummaries = new ArrayList<Map<String, Object>>(sumCount);
                for (int i = 0; i < sumCount; i++) {
                    var doc = new SourceDocumentIdentity(readLongUtf(in));
                    var state = SourceOutcome.State.valueOf(readLongUtf(in));
                    var ev = new ContentDigest(readLongUtf(in));
                    sourceSummaries.add(Map.of("document", doc, "state", state, "evidence", ev));
                }
                return new ProjectedFrontendTemplate(
                        storedKey, List.copyOf(evList), List.copyOf(nodeList), List.copyOf(edgeList),
                        List.copyOf(stList), List.copyOf(dsList), List.copyOf(mgList),
                        scopes, declDocs, occDocs,
                        declPayloads, occPayloads, obsPayloads, typeUsePayloads, annPayloads,
                        derivedPayloads, typeShapePayloads, memberShapePayloads, ctorPayloads, diagPayloads,
                        List.copyOf(sourceSummaries));
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException corrupt) {
            return null;
        }
    }

    private void saveTemplateToDisk(ProjectedFrontendTemplate template) {
        try {
            Files.createDirectories(cacheDirectory);
            var baos = new ByteArrayOutputStream(8192);
            try (var out = new DataOutputStream(baos)) {
                writeLongUtf(out, DISK_MAGIC);
                writeLongUtf(out, template.structuralKey().value());
                out.writeInt(template.evidence().size());
                for (var ev : template.evidence()) {
                    writeLongUtf(out, ev.identity().value());
                    writeLongUtf(out, ev.family());
                    writeLongUtf(out, ev.canonicalJson());
                }
                out.writeInt(template.nodes().size());
                for (var n : template.nodes()) {
                    writeLongUtf(out, n.identity());
                    writeLongUtf(out, n.kind());
                    writeDigestList(out, n.evidence());
                }
                out.writeInt(template.edges().size());
                for (var e : template.edges()) {
                    writeLongUtf(out, e.identity());
                    writeLongUtf(out, e.kind());
                    writeLongUtf(out, e.source());
                    out.writeByte(e.target().state().ordinal());
                    writeStringList(out, e.target().identities());
                    out.writeBoolean(e.target().unresolvedReference().isPresent());
                    if (e.target().unresolvedReference().isPresent()) writeLongUtf(out, e.target().unresolvedReference().orElseThrow());
                    writeDigestList(out, e.evidence());
                    out.writeBoolean(e.region().isPresent());
                    if (e.region().isPresent()) writeLongUtf(out, e.region().orElseThrow());
                }
                out.writeInt(template.statements().size());
                for (var s : template.statements()) {
                    writeLongUtf(out, s.family());
                    writeLongUtf(out, s.identity());
                    writeLongUtf(out, s.payload().value());
                }
                out.writeInt(template.declarationSupports().size());
                for (var ds : template.declarationSupports()) {
                    writeLongUtf(out, ds.entityIdentity());
                    writeLongUtf(out, ds.entityKind());
                    writeStringList(out, ds.inputIdentities());
                }
                out.writeInt(template.missingOwnerGaps().size());
                for (var mg : template.missingOwnerGaps()) {
                    writeLongUtf(out, mg.relationIdentity());
                    writeLongUtf(out, mg.payload().value());
                }
                writeStringList(out, template.projectEntityScopes());
                writeStringList(out, template.declarationDocIds());
                writeStringList(out, template.occurrenceDocIds());
                writeDigestList(out, template.declarationPayloads());
                writeDigestList(out, template.occurrencePayloads());
                writeDigestList(out, template.observationPayloads());
                writeDigestList(out, template.typeUsePayloads());
                writeDigestList(out, template.annotationPayloads());
                writeDigestList(out, template.derivedPayloads());
                writeDigestList(out, template.typeShapePayloads());
                writeDigestList(out, template.memberShapePayloads());
                writeDigestList(out, template.ctorCandidatePayloads());
                writeDigestList(out, template.diagnosticPayloads());
                out.writeInt(template.sourceSummaries().size());
                for (var sum : template.sourceSummaries()) {
                    var doc = (SourceDocumentIdentity) Objects.requireNonNull(sum.get("document"));
                    var st = (SourceOutcome.State) Objects.requireNonNull(sum.get("state"));
                    var ev = (ContentDigest) Objects.requireNonNull(sum.get("evidence"));
                    writeLongUtf(out, doc.value());
                    writeLongUtf(out, st.name());
                    writeLongUtf(out, ev.value());
                }
                out.flush();
            }
            byte[] payload = baos.toByteArray();
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload);
            Path target = templatePath(template.structuralKey());
            Path tmp = Files.createTempFile(cacheDirectory, "gt2-", ".tmp");
            try {
                try (var gz = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                    gz.write(payload);
                    gz.write(hash);
                }
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ignored) {
            // Operational cache writes never fail canonical builds.
        }
    }

    private static String readLongUtf(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 128_000_000) throw new IOException("INVALID_STRING_LENGTH");
        byte[] bytes = in.readNBytes(len);
        if (bytes.length != len) throw new IOException("TRUNCATED_STRING");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> readStringList(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 10_000_000) throw new IOException("INVALID_LIST_LENGTH");
        var list = new ArrayList<String>(Math.min(n, 4096));
        for (int i = 0; i < n; i++) list.add(readLongUtf(in));
        return List.copyOf(list);
    }

    private static List<ContentDigest> readDigestList(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 10_000_000) throw new IOException("INVALID_LIST_LENGTH");
        var list = new ArrayList<ContentDigest>(Math.min(n, 4096));
        for (int i = 0; i < n; i++) list.add(new ContentDigest(readLongUtf(in)));
        return List.copyOf(list);
    }

    private static void writeStringList(DataOutputStream out, List<String> list) throws IOException {
        out.writeInt(list.size());
        for (String s : list) writeLongUtf(out, s);
    }

    private static void writeDigestList(DataOutputStream out, List<ContentDigest> list) throws IOException {
        out.writeInt(list.size());
        for (var d : list) writeLongUtf(out, d.value());
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
