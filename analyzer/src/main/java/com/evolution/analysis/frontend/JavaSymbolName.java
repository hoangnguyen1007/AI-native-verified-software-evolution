package com.evolution.analysis.frontend;

import com.evolution.analysis.contract.identity.SourceDocumentIdentity;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.lang.model.SourceVersion;

/** Java equality encoded into NFC-safe, structural M1 language keys. Input is already Unicode-translated. */
public final class JavaSymbolName {
    private final List<Object> tuple;

    private JavaSymbolName(Object... components) { tuple = List.of(components); }

    public static JavaSymbolName packageName(String pkg) { return new JavaSymbolName("package", packageParts(pkg)); }
    public static JavaSymbolName topLevelType(String pkg, String name) {
        return new JavaSymbolName("type", packageParts(pkg), identifier(name));
    }
    public static JavaSymbolName memberType(JavaSymbolName owner, String name) {
        return new JavaSymbolName("member-type", typeOwner(owner), identifier(name));
    }
    public static JavaSymbolName method(JavaSymbolName owner, String name, List<ErasedType> parameters) {
        return new JavaSymbolName("method", typeOwner(owner), identifier(name), parameters.stream().map(ErasedType::tuple).toList());
    }
    public static JavaSymbolName constructor(JavaSymbolName owner, List<ErasedType> parameters) {
        return new JavaSymbolName("constructor", typeOwner(owner), parameters.stream().map(ErasedType::tuple).toList());
    }
    public static JavaSymbolName field(JavaSymbolName owner, String name) {
        return new JavaSymbolName("field", typeOwner(owner), identifier(name));
    }
    public static JavaSymbolName recordComponent(JavaSymbolName owner, String name) {
        return new JavaSymbolName("record-component", typeOwner(owner), identifier(name));
    }
    public static JavaSymbolName parameter(JavaSymbolName owner, int index) {
        if (!List.of("method", "constructor").contains(owner.tuple.getFirst())) throw new IllegalArgumentException("callable owner required");
        return indexed("parameter", owner, index);
    }
    public static JavaSymbolName typeParameter(JavaSymbolName owner, int index) { return indexed("type-parameter", owner, index); }
    private static JavaSymbolName indexed(String tag, JavaSymbolName owner, int index) {
        if (index < 0) throw new IllegalArgumentException("negative parameter index");
        return new JavaSymbolName(tag, owner.tuple, index);
    }
    public static JavaSymbolName localType(JavaSymbolName owner, SourceDocumentIdentity document, int offset, String name, boolean anonymous) {
        anchor(document, offset);
        if (anonymous && !name.isEmpty()) throw new IllegalArgumentException("anonymous name must be empty");
        return new JavaSymbolName("local-type", owner.tuple, document.value(), offset, anonymous ? "anonymous" : "local", anonymous ? "" : identifier(name));
    }
    public static JavaSymbolName execution(String kind, JavaSymbolName owner, SourceDocumentIdentity document, int offset, String syntaxKind) {
        if (!List.of("lambda", "initializer").contains(kind)) throw new IllegalArgumentException("execution kind");
        if (!List.of("lambda", "static", "instance", "field-static", "field-instance", "enum-constant").contains(syntaxKind)) throw new IllegalArgumentException("syntax kind");
        if (kind.equals("lambda") != syntaxKind.equals("lambda")) throw new IllegalArgumentException("execution kind and syntax disagree");
        anchor(document, offset);
        return new JavaSymbolName(kind, owner.tuple, document.value(), offset, syntaxKind);
    }
    private static void anchor(SourceDocumentIdentity document, int offset) {
        Objects.requireNonNull(document);
        if (offset < 0) throw new IllegalArgumentException("negative source anchor");
    }
    static List<Object> typeOwner(JavaSymbolName owner) {
        if (!List.of("type", "member-type", "local-type").contains(owner.tuple.getFirst())) throw new IllegalArgumentException("type owner required");
        return owner.tuple;
    }
    static String identifier(String raw) {
        Objects.requireNonNull(raw);
        if (!SourceVersion.isIdentifier(raw)) throw new IllegalArgumentException("invalid Java identifier");
        StringBuilder semantic = new StringBuilder();
        raw.codePoints().filter(c -> !Character.isIdentifierIgnorable(c)).forEach(semantic::appendCodePoint);
        if (!SourceVersion.isIdentifier(semantic) || SourceVersion.isKeyword(semantic, SourceVersion.RELEASE_21)) throw new IllegalArgumentException("invalid Java identifier");
        StringBuilder encoded = new StringBuilder();
        semantic.codePoints().forEach(c -> {
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_') encoded.appendCodePoint(c);
            else encoded.append(String.format(Locale.ROOT, "%%%06X", c));
        });
        return encoded.toString();
    }
    private static List<String> packageParts(String pkg) {
        return pkg.isEmpty() ? List.of() : Arrays.stream(pkg.split("\\.", -1)).map(JavaSymbolName::identifier).toList();
    }
    List<Object> tuple() { return tuple; }
    public String canonicalName() { return "java:v1:" + CanonicalJson.write(tuple); }
    @Override public boolean equals(Object other) { return other instanceof JavaSymbolName name && tuple.equals(name.tuple); }
    @Override public int hashCode() { return tuple.hashCode(); }
    @Override public String toString() { return canonicalName(); }
}
