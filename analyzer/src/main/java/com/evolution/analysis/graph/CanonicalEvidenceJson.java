package com.evolution.analysis.graph;

/** Checks the exact CanonicalJson spelling without allocating a decoded payload or rewriting it. */
final class CanonicalEvidenceJson {
    private static final GraphJson.Limits LIMITS = GraphJson.Limits.defaults();
    private final String text;
    private int offset;
    private int values;

    static boolean isCanonical(String text) {
        if (text.length() > LIMITS.maxCharacters())
            throw new GraphJson.LimitExceededException("Graph JSON character limit");
        var check = new CanonicalEvidenceJson(text);
        try {
            check.value(0);
            return check.offset == text.length();
        } catch (Noncanonical | GraphJson.LimitExceededException rejected) {
            return false;
        }
    }

    private CanonicalEvidenceJson(String text) { this.text = text; }

    private void value(int depth) {
        if (depth > LIMITS.maxDepth() || ++values > LIMITS.maxValues())
            throw new GraphJson.LimitExceededException("Graph JSON structural limit");
        switch (peek()) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true");
            case 'f' -> literal("false");
            case 'n' -> literal("null");
            default -> number();
        }
    }

    private void object(int depth) {
        require('{');
        if (take('}')) return;
        int previous = -1;
        do {
            int key = offset;
            string();
            if (previous >= 0 && compareKeys(previous, key) >= 0) throw invalid();
            previous = key;
            require(':');
            value(depth + 1);
        } while (take(','));
        require('}');
    }

    private void array(int depth) {
        require('[');
        if (take(']')) return;
        do { value(depth + 1); } while (take(','));
        require(']');
    }

    // CanonicalJson sorts decoded UTF-16 keys, including escaped controls and quotes.
    private int compareKeys(int first, int second) {
        first++;
        second++;
        while (true) {
            int a = text.charAt(first++);
            int b = text.charAt(second++);
            if (a == '"' || b == '"') return a == b ? 0 : a == '"' ? -1 : 1;
            if (a == '\\') {
                a = escaped(first);
                first += text.charAt(first) == 'u' ? 5 : 1;
            }
            if (b == '\\') {
                b = escaped(second);
                second += text.charAt(second) == 'u' ? 5 : 1;
            }
            if (a != b) return Integer.compare(a, b);
        }
    }

    private void string() {
        require('"');
        while (offset < text.length()) {
            char c = text.charAt(offset++);
            if (c == '"') return;
            if (c < 0x20) throw invalid();
            if (c == '\\') {
                int escaped = escaped(offset);
                if (text.charAt(offset) == 'u') {
                    if (escaped >= 0x20 || escaped == '\b' || escaped == '\f' || escaped == '\n'
                            || escaped == '\r' || escaped == '\t') throw invalid();
                    offset += 5;
                } else {
                    offset++;
                }
            } else if (Character.isHighSurrogate(c)) {
                if (offset == text.length() || !Character.isLowSurrogate(text.charAt(offset++))) throw invalid();
            } else if (Character.isLowSurrogate(c)) {
                throw invalid();
            }
        }
        throw invalid();
    }

    private int escaped(int at) {
        if (at == text.length()) throw invalid();
        return switch (text.charAt(at)) {
            case '"' -> '"';
            case '\\' -> '\\';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> {
                if (text.length() - at < 5 || text.charAt(at + 1) != '0' || text.charAt(at + 2) != '0')
                    throw invalid();
                yield (hex(text.charAt(at + 3)) << 4) | hex(text.charAt(at + 4));
            }
            default -> throw invalid();
        };
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        throw invalid();
    }

    private void number() {
        boolean negative = take('-');
        boolean zero = take('0');
        int whole = zero ? 1 : digits();
        if (whole == 0 || zero && digit(peek())) throw invalid();
        int fraction = 0;
        if (take('.')) {
            fraction = digits();
            if (fraction == 0 || text.charAt(offset - 1) == '0') throw invalid();
        }
        if (fraction > 4096 || (long) whole + fraction - (zero ? 1 : 0) > 4096)
            throw new GraphJson.LimitExceededException("Graph JSON number expansion limit");
        if (negative && zero && fraction == 0) throw invalid();
    }

    private int digits() {
        int start = offset;
        while (digit(peek())) offset++;
        return offset - start;
    }

    private static boolean digit(int c) { return c >= '0' && c <= '9'; }

    private void literal(String literal) {
        if (!text.startsWith(literal, offset)) throw invalid();
        offset += literal.length();
    }

    private int peek() { return offset < text.length() ? text.charAt(offset) : -1; }
    private boolean take(char c) {
        if (peek() != c) return false;
        offset++;
        return true;
    }
    private void require(char c) { if (!take(c)) throw invalid(); }
    private static Noncanonical invalid() { return new Noncanonical(); }
    private static final class Noncanonical extends IllegalArgumentException {}
}
