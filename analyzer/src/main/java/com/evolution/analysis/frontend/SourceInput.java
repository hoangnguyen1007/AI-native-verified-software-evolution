package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.source.SourceDocument;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Immutable exact source snapshot. Decoding never repairs invalid bytes. */
public final class SourceInput {
    private final SourceDocument document;
    private final byte[] bytes;
    private final String text;
    public SourceInput(SourceDocument document, byte[] bytes) {
        this.document = Objects.requireNonNull(document);
        this.bytes = bytes.clone();
        if (!ContentDigest.sha256(this.bytes).equals(document.contentDigest())) throw new FrontendInputException("frontend.source-digest", "Source bytes differ from the inventory");
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(this.bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new FrontendInputException("frontend.encoding", "Only strictly decoded UTF-8 sources are supported");
        }
    }
    public SourceDocument document() { return document; }
    public byte[] bytes() { return bytes.clone(); }
    public String text() { return text; }
}
