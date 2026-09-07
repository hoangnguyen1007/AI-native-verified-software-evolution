package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.ContractChecks;
import com.evolution.analysis.contract.source.SourceDocument;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Immutable exact source snapshot. Decoding never repairs invalid bytes. */
public final class SourceInput {
    private final SourceDocument document;
    private final byte[] bytes;
    private final String text;
    private final Decoding decoding;

    public SourceInput(SourceDocument document, byte[] bytes) {
        this(document, bytes, new Decoding(
                "source-decoding-v1",
                StandardCharsets.UTF_8.name(),
                EncodingOrigin.ANALYSIS_POLICY,
                List.of(),
                Bom.NONE,
                LineEndings.from("")));
    }

    public SourceInput(SourceDocument document, byte[] bytes, Decoding requested) {
        this.document = Objects.requireNonNull(document);
        this.bytes = Objects.requireNonNull(bytes).clone();
        if (!ContentDigest.sha256(this.bytes).equals(document.contentDigest())) throw new FrontendInputException("frontend.source-digest", "Source bytes differ from the inventory");
        Objects.requireNonNull(requested);
        try {
            Charset charset = portableCharset(requested.charset());
            Bom detected = Bom.detect(this.bytes);
            if (requested.bom() != detected || !detected.compatibleWith(charset)) {
                throw new FrontendInputException("frontend.encoding-bom", "Source BOM conflicts with the selected charset");
            }
            int offset = detected.byteCount();
            Charset decoderCharset = charset.name().equals("UTF-16") && detected == Bom.UTF16_LE
                    ? StandardCharsets.UTF_16LE
                    : charset.name().equals("UTF-16") && detected == Bom.UTF16_BE
                            ? StandardCharsets.UTF_16BE : charset;
            text = decoderCharset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(this.bytes, offset, this.bytes.length - offset)).toString();
            LineEndings actual = LineEndings.from(text);
            decoding = new Decoding(
                    requested.schemaVersion(),
                    charset.name(),
                    requested.origin(),
                    requested.evidence(),
                    detected,
                    actual);
        } catch (CharacterCodingException exception) {
            throw new FrontendInputException("frontend.encoding", "Source bytes are invalid under the selected charset");
        }
    }
    public SourceDocument document() { return document; }
    public byte[] bytes() { return bytes.clone(); }
    public String text() { return text; }
    public Decoding decoding() { return decoding; }

    private static Charset portableCharset(String name) {
        return switch (name.toUpperCase(java.util.Locale.ROOT)) {
            case "UTF-8" -> StandardCharsets.UTF_8;
            case "UTF-16" -> StandardCharsets.UTF_16;
            case "UTF-16BE" -> StandardCharsets.UTF_16BE;
            case "UTF-16LE" -> StandardCharsets.UTF_16LE;
            case "ISO-8859-1" -> StandardCharsets.ISO_8859_1;
            case "US-ASCII" -> StandardCharsets.US_ASCII;
            default -> throw new FrontendInputException("frontend.encoding-unsupported", "Selected charset is outside the portable decoding catalog");
        };
    }

    public enum EncodingOrigin { BUILD_DECLARATION, ANALYSIS_POLICY }
    public enum Bom {
        NONE(0), UTF8(3), UTF16_BE(2), UTF16_LE(2);
        private final int byteCount;
        Bom(int byteCount) { this.byteCount = byteCount; }
        int byteCount() { return byteCount; }
        boolean compatibleWith(Charset charset) {
            String name = charset.name();
            return this == NONE
                    || this == UTF8 && name.equals("UTF-8")
                    || this == UTF16_BE && (name.equals("UTF-16") || name.equals("UTF-16BE"))
                    || this == UTF16_LE && (name.equals("UTF-16") || name.equals("UTF-16LE"));
        }
        public static Bom detect(byte[] bytes) {
            if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) return UTF8;
            if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) return UTF16_BE;
            if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) return UTF16_LE;
            return NONE;
        }
    }

    public enum LineEndingKind { NONE, LF, CRLF, CR, MIXED }

    public record Evidence(String logicalId, ContentDigest digest) {
        public Evidence {
            ContractChecks.text(logicalId, "decoding evidence ID");
            ContractChecks.notNull(digest, "decoding evidence digest");
        }
    }

    public record LineEndings(LineEndingKind kind, int lf, int crlf, int cr) {
        public LineEndings {
            ContractChecks.notNull(kind, "line-ending kind");
            if (lf < 0 || crlf < 0 || cr < 0) throw new IllegalArgumentException("Line-ending counts must not be negative");
            int forms = (lf > 0 ? 1 : 0) + (crlf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
            LineEndingKind expected = forms == 0 ? LineEndingKind.NONE
                    : forms > 1 ? LineEndingKind.MIXED
                    : lf > 0 ? LineEndingKind.LF : crlf > 0 ? LineEndingKind.CRLF : LineEndingKind.CR;
            if (kind != expected) throw new IllegalArgumentException("Line-ending kind does not match counts");
        }
        public static LineEndings from(String text) {
            int lf = 0, crlf = 0, cr = 0;
            for (int index = 0; index < text.length(); index++) {
                char value = text.charAt(index);
                if (value == '\r') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '\n') { crlf++; index++; }
                    else cr++;
                } else if (value == '\n') lf++;
            }
            int forms = (lf > 0 ? 1 : 0) + (crlf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0);
            LineEndingKind kind = forms == 0 ? LineEndingKind.NONE
                    : forms > 1 ? LineEndingKind.MIXED
                    : lf > 0 ? LineEndingKind.LF : crlf > 0 ? LineEndingKind.CRLF : LineEndingKind.CR;
            return new LineEndings(kind, lf, crlf, cr);
        }
    }

    public record Decoding(
            String schemaVersion,
            String charset,
            EncodingOrigin origin,
            List<Evidence> evidence,
            Bom bom,
            LineEndings lineEndings) {
        public Decoding {
            if (!"source-decoding-v1".equals(schemaVersion)) throw new IllegalArgumentException("Unsupported source-decoding schema");
            charset = portableCharset(charset).name();
            ContractChecks.notNull(origin, "encoding origin");
            evidence = ContractChecks.sortedDistinct(evidence, Comparator.comparing(Evidence::logicalId), "decoding evidence");
            ContractChecks.notNull(bom, "source BOM");
            ContractChecks.notNull(lineEndings, "source line endings");
        }
    }
}
