package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.source.SourceClassification;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiFunction;

/** Opens captured file bytes using only the validated finding and its graph partition. */
public final class RepairSource {
    static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_ENCODED_BYTES = ((MAX_FILE_BYTES + 2) / 3) * 4;
    private static final GraphJson.Limits SOURCE_JSON = new GraphJson.Limits(MAX_ENCODED_BYTES + 8192,20,1024);
    private static volatile BiFunction<ContentDigest, OptionalLong, Optional<byte[]>> contentStoreFallback =
            (digest, size) -> Optional.empty();

    public static void registerContentFallback(BiFunction<ContentDigest, OptionalLong, Optional<byte[]>> fallback) {
        contentStoreFallback = fallback == null ? (digest, size) -> Optional.empty() : fallback;
    }

    static Optional<byte[]> resolveContentFromStore(ContentDigest digest, OptionalLong expectedSize) {
        var rawOpt = contentStoreFallback.apply(digest, expectedSize);
        if (rawOpt.isEmpty()) return Optional.empty();
        byte[] bytes = rawOpt.orElseThrow();
        if (bytes.length > MAX_FILE_BYTES || !ContentDigest.sha256(bytes).equals(digest)) {
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    record ContextFile(String path, String language, Optional<ContentDigest> digest, ContentDigest evidenceId, String text) {}

    /**
     * Read-only context helper for non-Java and Java captured files (covering {@code source.bytes}
     * and {@code polyglot.inventory-entry}). Never relaxes {@link #open}'s Java compiler boundaries.
     */
    static Optional<ContextFile> readContextFile(KnowledgeGraph graph, String path) {
        if (!safePath(path)) return Optional.empty();
        var index = CapturedFileIndex.forGraph(graph);
        var entryOpt = index.findExact(path);
        if (entryOpt.isEmpty()) return Optional.empty();
        var entry = entryOpt.orElseThrow();
        var textOpt = index.readText(graph, entry);
        if (textOpt.isEmpty()) return Optional.empty();
        return Optional.of(new ContextFile(entry.path(), entry.language(), entry.digest(), entry.evidenceId(), textOpt.orElseThrow()));
    }

    static Optional<byte[]> resolveSourceBytes(KnowledgeGraph graph, Map<String, Object> sourceBytesRow,
                                               Optional<ContentDigest> expectedDigest) {
        return resolveSourceBytes(graph, sourceBytesRow, expectedDigest, contentStoreFallback);
    }

    static Optional<byte[]> resolveSourceBytes(KnowledgeGraph graph, Map<String, Object> sourceBytesRow,
                                               Optional<ContentDigest> expectedDigest,
                                               BiFunction<ContentDigest, OptionalLong, Optional<byte[]>> fallback) {
        if (sourceBytesRow.get("bytesBase64") instanceof String inline && sourceBytesRow.get("contentEvidence") == null) {
            return verifyEncoded(inline, expectedDigest);
        }
        if (sourceBytesRow.get("base64") instanceof String legacy && sourceBytesRow.get("contentEvidence") == null) {
            return verifyEncoded(legacy, expectedDigest);
        }
        if (sourceBytesRow.get("contentEvidence") instanceof String refId) {
            var contentEvidence = graph.evidence(new ContentDigest(refId))
                    .filter(e -> "source.content".equals(e.family()));
            if (contentEvidence.isEmpty()) return Optional.empty();
            var content = payload(contentEvidence.orElseThrow());
            if (!(content.get("contentDigest") instanceof String contentDigestStr)) return Optional.empty();
            var contentDigest = new ContentDigest(contentDigestStr);
            if (expectedDigest.isPresent() && !expectedDigest.orElseThrow().equals(contentDigest)) {
                return Optional.empty();
            }
            if (content.get("bytesBase64") instanceof String encoded) {
                return verifyEncoded(encoded, Optional.of(contentDigest));
            }
            OptionalLong expectedSize = content.get("size") instanceof Number n
                    ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
            var rawOpt = (fallback != null ? fallback : contentStoreFallback).apply(contentDigest, expectedSize);
            if (rawOpt.isPresent()) {
                byte[] bytes = rawOpt.orElseThrow();
                if (bytes.length <= MAX_FILE_BYTES && ContentDigest.sha256(bytes).equals(contentDigest)) {
                    return Optional.of(bytes);
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<byte[]> verifyEncoded(String encoded, Optional<ContentDigest> expectedDigest) {
        if (encoded.length() > MAX_ENCODED_BYTES) return Optional.empty();
        byte[] bytes = Base64.getDecoder().decode(encoded);
        if (bytes.length > MAX_FILE_BYTES) return Optional.empty();
        if (expectedDigest.isPresent() && !ContentDigest.sha256(bytes).equals(expectedDigest.orElseThrow())) {
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    public enum Issue { SOURCE_BYTES_UNAVAILABLE, SOURCE_INTEGRITY_MISMATCH, SOURCE_NOT_EDITABLE,
        SOURCE_ENCODING_UNSUPPORTED, SOURCE_LIMIT, COMPILER_UNSUPPORTED, QUERY_LIMIT }
    static final class Unavailable extends RuntimeException {
        final Issue issue;
        Unavailable(Issue issue) { this.issue=issue; }
    }
    record File(String path, ContentDigest digest, ContentDigest byteEvidence, String text, boolean ascii,
                String module, SourceClassification role,
                List<FindingDiagnosis.Citation> citations) {}

    static File open(KnowledgeGraph graph,List<FindingDiagnosis.Citation> citations) {
        return read(graph,citations,true);
    }

    static File read(KnowledgeGraph graph,List<FindingDiagnosis.Citation> citations,boolean editable) {
        var citation=citations.getFirst();
        String id=citation.span().document().value(), path=citation.path(), digest=citation.sourceDigest().value();
        if (!citations.stream().allMatch(c -> c.span().document().value().equals(id)
                && c.path().equals(path) && c.sourceDigest().value().equals(digest)))
            throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        if (!safePath(path)) throw new Unavailable(Issue.SOURCE_NOT_EDITABLE);
        if (editable && !path.endsWith(".java")) throw new Unavailable(Issue.COMPILER_UNSUPPORTED);
        var rows=graph.nodes(graph.snapshot().value());
        int index=Collections.binarySearch(rows,new KnowledgeGraph.Node(id,graph.snapshot().value(),
                "SOURCE_DOCUMENT",List.of(citation.evidence())),Comparator.comparing(KnowledgeGraph.Node::identity));
        if (index<0 || !rows.get(index).kind().equals("SOURCE_DOCUMENT")) {
            if (!editable) {
                return readNonJavaContextFallback(graph, path, citation.sourceDigest(), citations);
            }
            throw new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE);
        }
        var evidence=rows.get(index).evidence().stream().flatMap(ref -> graph.evidence(ref).stream())
                .filter(e -> e.family().equals("source.bytes")).toList();
        if (evidence.isEmpty()) {
            if (!editable) {
                return readNonJavaContextFallback(graph, path, citation.sourceDigest(), citations);
            }
            throw new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE);
        }
        if (evidence.size()!=1) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        try {
            var blob=evidence.getFirst();
            var row=payload(blob);
            var document=GraphJson.object(row.get("document"));
            if (!id.equals(document.get("identity")) || !path.equals(document.get("path"))
                    || !digest.equals(document.get("contentDigest")))
                throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
            var role=SourceClassification.valueOf(QueryJson.text(document,"classification"));
            if (editable && (role==SourceClassification.GENERATED_MAIN || role==SourceClassification.GENERATED_TEST))
                throw new Unavailable(Issue.SOURCE_NOT_EDITABLE);
            var decoding=GraphJson.object(row.get("decoding"));
            if (!Set.of("UTF-8","US-ASCII").contains(QueryJson.text(decoding,"charset")) || !"NONE".equals(decoding.get("bom")))
                throw new Unavailable(Issue.SOURCE_ENCODING_UNSUPPORTED);
            byte[] bytes;
            if (row.get("bytesBase64") instanceof String inline && row.get("contentEvidence")==null) {
                if (inline.length()>MAX_ENCODED_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
                bytes=Base64.getDecoder().decode(inline);
                if (bytes.length>MAX_FILE_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
                if (!ContentDigest.sha256(bytes).equals(citation.sourceDigest())) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
            } else {
                if (!digest.equals(row.get("contentDigest"))) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
                var ref=new ContentDigest(QueryJson.text(row,"contentEvidence"));
                var content=payload(graph.evidence(ref).filter(e -> e.family().equals("source.content"))
                        .orElseThrow(() -> new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE)));
                if (!digest.equals(content.get("contentDigest"))) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
                if (content.get("bytesBase64") instanceof String encoded) {
                    if (encoded.length()>MAX_ENCODED_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
                    bytes=Base64.getDecoder().decode(encoded);
                    if (bytes.length>MAX_FILE_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
                    if (!ContentDigest.sha256(bytes).equals(citation.sourceDigest())) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
                } else {
                    OptionalLong expectedSize = content.get("size") instanceof Number n
                            ? OptionalLong.of(n.longValue()) : OptionalLong.empty();
                    bytes = contentStoreFallback.apply(citation.sourceDigest(), expectedSize)
                            .orElseThrow(() -> new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE));
                    if (bytes.length>MAX_FILE_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
                    if (!ContentDigest.sha256(bytes).equals(citation.sourceDigest())) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
                }
            }
            var charset="US-ASCII".equals(decoding.get("charset")) ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
            String text=charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf(0)>=0 || text.startsWith("\uFEFF")) throw new Unavailable(Issue.SOURCE_ENCODING_UNSUPPORTED);
            return new File(path,citation.sourceDigest(),blob.identity(),text,charset.equals(StandardCharsets.US_ASCII),
                    QueryJson.text(document,"module"),role,List.copyOf(citations));
        } catch (CharacterCodingException invalid) {
            throw new Unavailable(Issue.SOURCE_ENCODING_UNSUPPORTED);
        } catch (IllegalArgumentException invalid) {
            throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        }
    }

    private static File readNonJavaContextFallback(KnowledgeGraph graph, String path, ContentDigest expectedDigest,
                                                   List<FindingDiagnosis.Citation> citations) {
        var fileIndex = CapturedFileIndex.forGraph(graph);
        var entry = fileIndex.findExact(path).orElseThrow(() -> new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE));
        if (entry.digest().isPresent() && !entry.digest().orElseThrow().equals(expectedDigest)) {
            throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        }
        byte[] bytes = fileIndex.readBytes(graph, entry).orElseThrow(() -> new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE));
        if (bytes.length > MAX_FILE_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
        if (!ContentDigest.sha256(bytes).equals(expectedDigest)) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf(0) >= 0 || text.startsWith("\uFEFF")) throw new Unavailable(Issue.SOURCE_ENCODING_UNSUPPORTED);
            return new File(entry.path(), expectedDigest, entry.evidenceId(), text, false, "",
                    SourceClassification.MAIN, List.copyOf(citations));
        } catch (CharacterCodingException invalid) {
            throw new Unavailable(Issue.SOURCE_ENCODING_UNSUPPORTED);
        }
    }

    private static Map<String,Object> payload(GraphEvidence evidence) {
        if (evidence.canonicalJson().length()>SOURCE_JSON.maxCharacters()) throw new Unavailable(Issue.SOURCE_LIMIT);
        return GraphJson.object(GraphJson.read(evidence.canonicalJson(),SOURCE_JSON));
    }

    private static boolean safePath(String path) {
        try { ContractChecks.repositoryRelativePath(path,"repair source path"); }
        catch (IllegalArgumentException invalid) { return false; }
        return path.length()<=4096 && path.codePoints().noneMatch(Character::isISOControl)
                && Arrays.stream(path.split("/")).noneMatch(segment -> segment.equalsIgnoreCase(".git")
                    || segment.toLowerCase(Locale.ROOT).matches("git~[0-9]+") || segment.endsWith(".") || segment.endsWith(" "));
    }

    private RepairSource() {}
}
