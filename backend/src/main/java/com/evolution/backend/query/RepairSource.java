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

/** Opens captured file bytes using only the validated finding and its graph partition. */
public final class RepairSource {
    static final int MAX_FILE_BYTES = 256 * 1024;
    private static final int MAX_ENCODED_BYTES = ((MAX_FILE_BYTES + 2) / 3) * 4;
    private static final GraphJson.Limits SOURCE_JSON = new GraphJson.Limits(MAX_ENCODED_BYTES + 8192,20,1024);

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
        var citation=citations.getFirst();
        String id=citation.span().document().value(), path=citation.path(), digest=citation.sourceDigest().value();
        if (!citations.stream().allMatch(c -> c.span().document().value().equals(id)
                && c.path().equals(path) && c.sourceDigest().value().equals(digest)))
            throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        if (!safePath(path)) throw new Unavailable(Issue.SOURCE_NOT_EDITABLE);
        if (!path.endsWith(".java")) throw new Unavailable(Issue.COMPILER_UNSUPPORTED);
        var rows=graph.nodes(graph.snapshot().value());
        int index=Collections.binarySearch(rows,new KnowledgeGraph.Node(id,graph.snapshot().value(),
                "SOURCE_DOCUMENT",List.of(citation.evidence())),Comparator.comparing(KnowledgeGraph.Node::identity));
        if (index<0 || !rows.get(index).kind().equals("SOURCE_DOCUMENT")) throw new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE);
        var evidence=rows.get(index).evidence().stream().flatMap(ref -> graph.evidence(ref).stream())
                .filter(e -> e.family().equals("source.bytes")).toList();
        if (evidence.isEmpty()) throw new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE);
        if (evidence.size()!=1) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
        try {
            var blob=evidence.getFirst();
            var row=payload(blob);
            var document=GraphJson.object(row.get("document"));
            if (!id.equals(document.get("identity")) || !path.equals(document.get("path"))
                    || !digest.equals(document.get("contentDigest")))
                throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
            var role=SourceClassification.valueOf(QueryJson.text(document,"classification"));
            if (role==SourceClassification.GENERATED_MAIN || role==SourceClassification.GENERATED_TEST)
                throw new Unavailable(Issue.SOURCE_NOT_EDITABLE);
            var decoding=GraphJson.object(row.get("decoding"));
            if (!Set.of("UTF-8","US-ASCII").contains(QueryJson.text(decoding,"charset")) || !"NONE".equals(decoding.get("bom")))
                throw new Unavailable(Issue.SOURCE_ENCODING_UNSUPPORTED);
            String encoded;
            if (row.get("bytesBase64") instanceof String inline && row.get("contentEvidence")==null) encoded=inline;
            else {
                if (!digest.equals(row.get("contentDigest"))) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
                var ref=new ContentDigest(QueryJson.text(row,"contentEvidence"));
                var content=payload(graph.evidence(ref).filter(e -> e.family().equals("source.content"))
                        .orElseThrow(() -> new Unavailable(Issue.SOURCE_BYTES_UNAVAILABLE)));
                if (!digest.equals(content.get("contentDigest"))) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
                encoded=GraphJson.string(content.get("bytesBase64"));
            }
            if (encoded.length()>MAX_ENCODED_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
            byte[] bytes=Base64.getDecoder().decode(encoded);
            if (bytes.length>MAX_FILE_BYTES) throw new Unavailable(Issue.SOURCE_LIMIT);
            if (!ContentDigest.sha256(bytes).equals(citation.sourceDigest())) throw new Unavailable(Issue.SOURCE_INTEGRITY_MISMATCH);
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
