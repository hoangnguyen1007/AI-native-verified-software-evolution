package com.evolution.analysis.execution.scip;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Clean-room, zero-dependency pure-Java Protobuf v3 wire-format decoder (and test encoder)
 * for the Apache-2.0 {@code scip.proto} schema.
 *
 * <p>Enforces a strict {@value #MAX_INPUT_BYTES}-byte ({@code 64 MiB}) payload cap,
 * bounded recursion depth ({@value #MAX_RECURSION_DEPTH}), and bounded collection counts
 * so corrupt, empty, truncated, or adversarial {@code index.scip} payloads fail cleanly
 * with {@link IllegalArgumentException}.
 */
public final class ScipProtobufReader {

    /** Strict 64 MiB maximum input payload size. */
    public static final int MAX_INPUT_BYTES = 64 * 1024 * 1024;
    /** Maximum nested submessage depth (guards against recursive {@code signature_documentation}). */
    public static final int MAX_RECURSION_DEPTH = 16;
    /** Maximum total decoded elements per collection/document stream. */
    public static final int MAX_COLLECTION_ELEMENTS = 5_000_000;

    public static final int ROLE_DEFINITION = 1;
    public static final int ROLE_IMPORT = 2;
    public static final int ROLE_WRITE_ACCESS = 4;
    public static final int ROLE_READ_ACCESS = 8;

    private static final int WIRE_VARINT = 0;
    private static final int WIRE_FIXED64 = 1;
    private static final int WIRE_LEN = 2;
    private static final int WIRE_FIXED32 = 5;

    public record ToolInfo(String name, String version, List<String> arguments) {
        public ToolInfo {
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        public static ToolInfo empty() {
            return new ToolInfo("", "", List.of());
        }
    }

    public record Metadata(int version, ToolInfo toolInfo, String projectRoot, int textDocumentEncoding) {
        public Metadata {
            toolInfo = toolInfo == null ? ToolInfo.empty() : toolInfo;
            projectRoot = projectRoot == null ? "" : projectRoot;
        }

        public static Metadata empty() {
            return new Metadata(0, ToolInfo.empty(), "", 0);
        }
    }

    public record Relationship(
            String symbol,
            boolean isReference,
            boolean isImplementation,
            boolean isTypeDefinition,
            boolean isDefinition
    ) {
        public Relationship {
            symbol = symbol == null ? "" : symbol;
        }
    }

    public record SymbolInformation(
            String symbol,
            List<String> documentation,
            List<Relationship> relationships,
            int kind,
            String displayName,
            Optional<Document> signatureDocumentation,
            String enclosingSymbol
    ) {
        public SymbolInformation {
            symbol = symbol == null ? "" : symbol;
            documentation = documentation == null ? List.of() : List.copyOf(documentation);
            relationships = relationships == null ? List.of() : List.copyOf(relationships);
            displayName = displayName == null ? "" : displayName;
            signatureDocumentation = signatureDocumentation == null ? Optional.empty() : signatureDocumentation;
            enclosingSymbol = enclosingSymbol == null ? "" : enclosingSymbol;
        }
    }

    public record Occurrence(
            List<Integer> range,
            String symbol,
            int symbolRoles,
            int syntaxKind,
            List<Integer> enclosingRange
    ) {
        public Occurrence {
            range = range == null ? List.of() : List.copyOf(range);
            symbol = symbol == null ? "" : symbol;
            enclosingRange = enclosingRange == null ? List.of() : List.copyOf(enclosingRange);
        }
    }

    public record Document(
            String language,
            String relativePath,
            List<Occurrence> occurrences,
            List<SymbolInformation> symbols,
            int positionEncoding
    ) {
        public Document {
            language = language == null ? "" : language;
            relativePath = relativePath == null ? "" : relativePath;
            occurrences = occurrences == null ? List.of() : List.copyOf(occurrences);
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    public record ScipIndex(
            Metadata metadata,
            List<Document> documents,
            List<SymbolInformation> externalSymbols
    ) {
        public ScipIndex {
            metadata = metadata == null ? Metadata.empty() : metadata;
            documents = documents == null ? List.of() : List.copyOf(documents);
            externalSymbols = externalSymbols == null ? List.of() : List.copyOf(externalSymbols);
        }
    }

    private ScipProtobufReader() {}

    /** Decodes ZigZag-encoded 32-bit signed integer. */
    public static int decodeZigZag32(int n) {
        return (n >>> 1) ^ -(n & 1);
    }

    /** Decodes ZigZag-encoded 64-bit signed integer. */
    public static long decodeZigZag64(long n) {
        return (n >>> 1) ^ -(n & 1L);
    }

    /** Encodes a 32-bit signed integer using ZigZag encoding. */
    public static int encodeZigZag32(int n) {
        return (n << 1) ^ (n >> 31);
    }

    /** Encodes a 64-bit signed integer using ZigZag encoding. */
    public static long encodeZigZag64(long n) {
        return (n << 1) ^ (n >> 63);
    }

    /**
     * Decodes a binary {@code index.scip} payload into a {@link ScipIndex}.
     *
     * @throws IllegalArgumentException if {@code bytes} is null, empty, oversized (&gt; 64 MiB),
     *                                  truncated, deeply nested, or wire-malformed
     */
    public static ScipIndex read(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("SCIP payload must not be null");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("SCIP payload must not be empty");
        }
        if (bytes.length > MAX_INPUT_BYTES) {
            throw new IllegalArgumentException(
                    "SCIP payload exceeds 64 MiB cap: " + bytes.length + " bytes");
        }
        var cursor = new Cursor(bytes, 0, bytes.length);
        return readIndex(cursor, 0);
    }

    /**
     * Deterministically encodes a {@link ScipIndex} to canonical Protobuf v3 wire bytes.
     * Useful for tests and reproducible SCIP archive construction.
     */
    public static byte[] encodeIndex(ScipIndex index) {
        Objects.requireNonNull(index, "index");
        var out = new ByteArrayOutputStream(256);
        byte[] metaBytes = encodeMetadata(index.metadata());
        if (metaBytes.length > 0) {
            writeLengthDelimited(out, 1, metaBytes);
        }
        for (Document doc : index.documents()) {
            writeLengthDelimited(out, 2, encodeDocument(doc));
        }
        for (SymbolInformation ext : index.externalSymbols()) {
            writeLengthDelimited(out, 3, encodeSymbolInformation(ext));
        }
        return out.toByteArray();
    }

    // ─── Wire Decoder ───────────────────────────────────────────────────────────

    private static ScipIndex readIndex(Cursor c, int depth) {
        checkDepth(depth);
        Metadata metadata = Metadata.empty();
        var documents = new ArrayList<Document>();
        var externalSymbols = new ArrayList<SymbolInformation>();

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> {
                    Cursor sub = c.readLengthDelimitedSlice(WIRE_LEN, wire);
                    metadata = readMetadata(sub, depth + 1);
                }
                case 2 -> {
                    checkElementLimit(documents.size());
                    Cursor sub = c.readLengthDelimitedSlice(WIRE_LEN, wire);
                    documents.add(readDocument(sub, depth + 1));
                }
                case 3 -> {
                    checkElementLimit(externalSymbols.size());
                    Cursor sub = c.readLengthDelimitedSlice(WIRE_LEN, wire);
                    externalSymbols.add(readSymbolInformation(sub, depth + 1));
                }
                default -> c.skipField(wire);
            }
        }
        return new ScipIndex(metadata, documents, externalSymbols);
    }

    private static Metadata readMetadata(Cursor c, int depth) {
        checkDepth(depth);
        int version = 0;
        ToolInfo toolInfo = ToolInfo.empty();
        String projectRoot = "";
        int textDocumentEncoding = 0;

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> version = c.readInt32(wire);
                case 2 -> toolInfo = readToolInfo(c.readLengthDelimitedSlice(WIRE_LEN, wire), depth + 1);
                case 3 -> projectRoot = c.readString(wire);
                case 4 -> textDocumentEncoding = c.readInt32(wire);
                default -> c.skipField(wire);
            }
        }
        return new Metadata(version, toolInfo, projectRoot, textDocumentEncoding);
    }

    private static ToolInfo readToolInfo(Cursor c, int depth) {
        checkDepth(depth);
        String name = "";
        String version = "";
        var arguments = new ArrayList<String>();

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> name = c.readString(wire);
                case 2 -> version = c.readString(wire);
                case 3 -> {
                    checkElementLimit(arguments.size());
                    arguments.add(c.readString(wire));
                }
                default -> c.skipField(wire);
            }
        }
        return new ToolInfo(name, version, arguments);
    }

    private static Document readDocument(Cursor c, int depth) {
        checkDepth(depth);
        String language = "";
        String relativePath = "";
        var occurrences = new ArrayList<Occurrence>();
        var symbols = new ArrayList<SymbolInformation>();
        int positionEncoding = 0;

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> relativePath = c.readString(wire);
                case 2 -> {
                    checkElementLimit(occurrences.size());
                    occurrences.add(readOccurrence(c.readLengthDelimitedSlice(WIRE_LEN, wire), depth + 1));
                }
                case 3 -> {
                    checkElementLimit(symbols.size());
                    symbols.add(readSymbolInformation(c.readLengthDelimitedSlice(WIRE_LEN, wire), depth + 1));
                }
                case 4 -> language = c.readString(wire);
                case 5 -> positionEncoding = c.readInt32(wire);
                default -> c.skipField(wire);
            }
        }
        return new Document(language, relativePath, occurrences, symbols, positionEncoding);
    }

    private static Occurrence readOccurrence(Cursor c, int depth) {
        checkDepth(depth);
        var range = new ArrayList<Integer>(4);
        String symbol = "";
        int symbolRoles = 0;
        int syntaxKind = 0;
        var enclosingRange = new ArrayList<Integer>(4);

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> c.readRepeatedInt32(wire, range);
                case 2 -> symbol = c.readString(wire);
                case 3 -> symbolRoles = c.readInt32(wire);
                case 5 -> syntaxKind = c.readInt32(wire);
                case 6 -> c.readRepeatedInt32(wire, enclosingRange);
                default -> c.skipField(wire);
            }
        }
        return new Occurrence(range, symbol, symbolRoles, syntaxKind, enclosingRange);
    }

    private static SymbolInformation readSymbolInformation(Cursor c, int depth) {
        checkDepth(depth);
        String symbol = "";
        var documentation = new ArrayList<String>();
        var relationships = new ArrayList<Relationship>();
        int kind = 0;
        String displayName = "";
        Optional<Document> signatureDocumentation = Optional.empty();
        String enclosingSymbol = "";

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> symbol = c.readString(wire);
                case 3 -> {
                    checkElementLimit(documentation.size());
                    documentation.add(c.readString(wire));
                }
                case 4 -> {
                    checkElementLimit(relationships.size());
                    relationships.add(readRelationship(c.readLengthDelimitedSlice(WIRE_LEN, wire), depth + 1));
                }
                case 5 -> kind = c.readInt32(wire);
                case 6 -> displayName = c.readString(wire);
                case 7 -> signatureDocumentation = Optional.of(
                        readDocument(c.readLengthDelimitedSlice(WIRE_LEN, wire), depth + 1));
                case 8 -> enclosingSymbol = c.readString(wire);
                default -> c.skipField(wire);
            }
        }
        return new SymbolInformation(
                symbol,
                documentation,
                relationships,
                kind,
                displayName,
                signatureDocumentation,
                enclosingSymbol);
    }

    private static Relationship readRelationship(Cursor c, int depth) {
        checkDepth(depth);
        String symbol = "";
        boolean isReference = false;
        boolean isImplementation = false;
        boolean isTypeDefinition = false;
        boolean isDefinition = false;

        while (c.hasRemaining()) {
            int tag = c.readTag();
            int field = tag >>> 3;
            int wire = tag & 0x07;
            switch (field) {
                case 1 -> symbol = c.readString(wire);
                case 2 -> isReference = c.readBool(wire);
                case 3 -> isImplementation = c.readBool(wire);
                case 4 -> isTypeDefinition = c.readBool(wire);
                case 5 -> isDefinition = c.readBool(wire);
                default -> c.skipField(wire);
            }
        }
        return new Relationship(symbol, isReference, isImplementation, isTypeDefinition, isDefinition);
    }

    private static void checkDepth(int depth) {
        if (depth > MAX_RECURSION_DEPTH) {
            throw new IllegalArgumentException(
                    "SCIP protobuf nesting depth exceeded limit: " + MAX_RECURSION_DEPTH);
        }
    }

    private static void checkElementLimit(int currentSize) {
        if (currentSize >= MAX_COLLECTION_ELEMENTS) {
            throw new IllegalArgumentException(
                    "SCIP protobuf collection element count exceeded limit: " + MAX_COLLECTION_ELEMENTS);
        }
    }

    private static final class Cursor {
        private final byte[] buf;
        private int pos;
        private final int limit;

        Cursor(byte[] buf, int pos, int limit) {
            this.buf = buf;
            this.pos = pos;
            this.limit = limit;
        }

        boolean hasRemaining() {
            return pos < limit;
        }

        int readTag() {
            long raw = readVarint64();
            if (raw <= 0 || raw > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid protobuf tag: " + raw);
            }
            int tag = (int) raw;
            if ((tag >>> 3) == 0) {
                throw new IllegalArgumentException("Invalid protobuf field number 0");
            }
            return tag;
        }

        int readInt32(int wireType) {
            expectWire(WIRE_VARINT, wireType);
            return (int) readVarint64();
        }

        boolean readBool(int wireType) {
            expectWire(WIRE_VARINT, wireType);
            return readVarint64() != 0L;
        }

        String readString(int wireType) {
            Cursor slice = readLengthDelimitedSlice(WIRE_LEN, wireType);
            int len = slice.limit - slice.pos;
            if (len == 0) {
                return "";
            }
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(slice.buf, slice.pos, len))
                        .toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Malformed UTF-8 in SCIP protobuf string", e);
            }
        }

        void readRepeatedInt32(int wireType, List<Integer> target) {
            if (wireType == WIRE_LEN) {
                Cursor sub = readLengthDelimitedSlice(WIRE_LEN, wireType);
                while (sub.hasRemaining()) {
                    checkElementLimit(target.size());
                    target.add((int) sub.readVarint64());
                }
            } else if (wireType == WIRE_VARINT) {
                checkElementLimit(target.size());
                target.add((int) readVarint64());
            } else {
                throw new IllegalArgumentException("Expected packed or varint int32 wire type, got " + wireType);
            }
        }

        Cursor readLengthDelimitedSlice(int expectedWire, int actualWire) {
            expectWire(expectedWire, actualWire);
            long lenLong = readVarint64();
            if (lenLong < 0 || lenLong > (limit - pos)) {
                throw new IllegalArgumentException(
                        "Truncated or invalid protobuf length-delimited field: " + lenLong);
            }
            int len = (int) lenLong;
            int start = pos;
            pos += len;
            return new Cursor(buf, start, start + len);
        }

        void skipField(int wireType) {
            switch (wireType) {
                case WIRE_VARINT -> readVarint64();
                case WIRE_FIXED64 -> skipBytes(8);
                case WIRE_LEN -> {
                    long lenLong = readVarint64();
                    if (lenLong < 0 || lenLong > (limit - pos)) {
                        throw new IllegalArgumentException("Truncated length-delimited skip: " + lenLong);
                    }
                    pos += (int) lenLong;
                }
                case WIRE_FIXED32 -> skipBytes(4);
                default -> throw new IllegalArgumentException("Unsupported protobuf wire type: " + wireType);
            }
        }

        private void skipBytes(int count) {
            if (limit - pos < count) {
                throw new IllegalArgumentException("Truncated fixed-width protobuf field");
            }
            pos += count;
        }

        private long readVarint64() {
            long result = 0L;
            int shift = 0;
            for (int i = 0; i < 10; i++) {
                if (pos >= limit) {
                    throw new IllegalArgumentException("Truncated protobuf varint");
                }
                int b = buf[pos++] & 0xFF;
                if (i == 9 && b > 0x01) {
                    throw new IllegalArgumentException("Overflowing 64-bit protobuf varint");
                }
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("Malformed protobuf varint exceeding 10 bytes");
        }

        private static void expectWire(int expected, int actual) {
            if (expected != actual) {
                throw new IllegalArgumentException(
                        "Protobuf wire type mismatch: expected " + expected + ", got " + actual);
            }
        }
    }

    // ─── Deterministic Protobuf v3 Wire Encoder ─────────────────────────────────

    private static byte[] encodeMetadata(Metadata meta) {
        if (meta == null) return new byte[0];
        var out = new ByteArrayOutputStream(64);
        if (meta.version() != 0) {
            writeInt32(out, 1, meta.version());
        }
        byte[] toolBytes = encodeToolInfo(meta.toolInfo());
        if (toolBytes.length > 0) {
            writeLengthDelimited(out, 2, toolBytes);
        }
        if (!meta.projectRoot().isEmpty()) {
            writeString(out, 3, meta.projectRoot());
        }
        if (meta.textDocumentEncoding() != 0) {
            writeInt32(out, 4, meta.textDocumentEncoding());
        }
        return out.toByteArray();
    }

    private static byte[] encodeToolInfo(ToolInfo info) {
        if (info == null) return new byte[0];
        var out = new ByteArrayOutputStream(64);
        if (!info.name().isEmpty()) {
            writeString(out, 1, info.name());
        }
        if (!info.version().isEmpty()) {
            writeString(out, 2, info.version());
        }
        for (String arg : info.arguments()) {
            writeString(out, 3, arg == null ? "" : arg);
        }
        return out.toByteArray();
    }

    private static byte[] encodeDocument(Document doc) {
        var out = new ByteArrayOutputStream(128);
        if (!doc.relativePath().isEmpty()) {
            writeString(out, 1, doc.relativePath());
        }
        for (Occurrence occ : doc.occurrences()) {
            writeLengthDelimited(out, 2, encodeOccurrence(occ));
        }
        for (SymbolInformation sym : doc.symbols()) {
            writeLengthDelimited(out, 3, encodeSymbolInformation(sym));
        }
        if (!doc.language().isEmpty()) {
            writeString(out, 4, doc.language());
        }
        if (doc.positionEncoding() != 0) {
            writeInt32(out, 5, doc.positionEncoding());
        }
        return out.toByteArray();
    }

    private static byte[] encodeOccurrence(Occurrence occ) {
        var out = new ByteArrayOutputStream(48);
        if (!occ.range().isEmpty()) {
            writePackedInt32(out, 1, occ.range());
        }
        if (!occ.symbol().isEmpty()) {
            writeString(out, 2, occ.symbol());
        }
        if (occ.symbolRoles() != 0) {
            writeInt32(out, 3, occ.symbolRoles());
        }
        if (occ.syntaxKind() != 0) {
            writeInt32(out, 5, occ.syntaxKind());
        }
        if (!occ.enclosingRange().isEmpty()) {
            writePackedInt32(out, 6, occ.enclosingRange());
        }
        return out.toByteArray();
    }

    private static byte[] encodeSymbolInformation(SymbolInformation sym) {
        var out = new ByteArrayOutputStream(64);
        if (!sym.symbol().isEmpty()) {
            writeString(out, 1, sym.symbol());
        }
        for (String doc : sym.documentation()) {
            writeString(out, 3, doc == null ? "" : doc);
        }
        for (Relationship rel : sym.relationships()) {
            writeLengthDelimited(out, 4, encodeRelationship(rel));
        }
        if (sym.kind() != 0) {
            writeInt32(out, 5, sym.kind());
        }
        if (!sym.displayName().isEmpty()) {
            writeString(out, 6, sym.displayName());
        }
        if (sym.signatureDocumentation().isPresent()) {
            writeLengthDelimited(out, 7, encodeDocument(sym.signatureDocumentation().get()));
        }
        if (!sym.enclosingSymbol().isEmpty()) {
            writeString(out, 8, sym.enclosingSymbol());
        }
        return out.toByteArray();
    }

    private static byte[] encodeRelationship(Relationship rel) {
        var out = new ByteArrayOutputStream(32);
        if (!rel.symbol().isEmpty()) {
            writeString(out, 1, rel.symbol());
        }
        if (rel.isReference()) {
            writeBool(out, 2, true);
        }
        if (rel.isImplementation()) {
            writeBool(out, 3, true);
        }
        if (rel.isTypeDefinition()) {
            writeBool(out, 4, true);
        }
        if (rel.isDefinition()) {
            writeBool(out, 5, true);
        }
        return out.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint64(out, ((long) fieldNumber << 3) | (wireType & 0x07L));
    }

    private static void writeInt32(ByteArrayOutputStream out, int fieldNumber, int value) {
        writeTag(out, fieldNumber, WIRE_VARINT);
        writeVarint64(out, value);
    }

    private static void writeBool(ByteArrayOutputStream out, int fieldNumber, boolean value) {
        writeTag(out, fieldNumber, WIRE_VARINT);
        writeVarint64(out, value ? 1L : 0L);
    }

    private static void writeString(ByteArrayOutputStream out, int fieldNumber, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        writeLengthDelimited(out, fieldNumber, utf8);
    }

    private static void writeLengthDelimited(ByteArrayOutputStream out, int fieldNumber, byte[] payload) {
        writeTag(out, fieldNumber, WIRE_LEN);
        writeVarint64(out, payload.length);
        out.write(payload, 0, payload.length);
    }

    private static void writePackedInt32(ByteArrayOutputStream out, int fieldNumber, List<Integer> values) {
        var packed = new ByteArrayOutputStream(values.size() * 2);
        for (int v : values) {
            writeVarint64(packed, v);
        }
        writeLengthDelimited(out, fieldNumber, packed.toByteArray());
    }

    private static void writeVarint64(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0L) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }
}
