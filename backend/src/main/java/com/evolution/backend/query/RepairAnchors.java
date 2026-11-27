package com.evolution.backend.query;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Resolves model text to unique offsets in the authenticated original file using 4 progressive
 * tiers while strictly preserving string/char literal interior whitespace and emitting nearest-line
 * diagnostics on anchor mismatch for self-healing retries.
 */
final class RepairAnchors {
    private static final Pattern NEWLINE = Pattern.compile("\\r\\n|\\r|\\n");
    private static final int WINDOW_RADIUS = 15;

    record Edit(int start, int end, String replacement) {}

    static final class AnchorFailure extends IllegalArgumentException {
        private final String nearestLines;
        AnchorFailure(String message, String nearestLines) {
            super(message);
            this.nearestLines = nearestLines == null ? "" : nearestLines;
        }
        String nearestLines() { return nearestLines; }
    }

    static Edit locate(String source, RepairChatModel.Change edit) {
        String old = edit.oldText();
        var lineStarts = computeLineStarts(source);
        Integer line = edit.startLine();
        if (line != null && (line < 1 || line > lineStarts.size())) {
            throw new AnchorFailure("startLine " + line + " out of range (1.." + lineStarts.size() + ")",
                    excerptAround(source, lineStarts, Math.max(1, Math.min(line, lineStarts.size())), 6));
        }

        // Tier 1: Exact match (scoped to exact line if startLine provided, else unique in file)
        int from = line == null ? 0 : lineStarts.get(line - 1);
        int until = line == null || line == lineStarts.size() ? source.length() : lineStarts.get(line);
        int start = exact(source, old, from);
        if (start >= 0 && start < until) {
            int duplicate = exact(source, old, start + 1);
            if (duplicate >= 0 && duplicate < until) {
                throw new AnchorFailure("Ambiguous exact match on line " + line,
                        excerptAround(source, lineStarts, line == null ? 1 : line, 6));
            }
            return new Edit(start, start + old.length(), lineEndings(source, start, edit.newText()));
        }

        // Tier 2: Line-window exact match (handles model startLine off by +/- WINDOW_RADIUS lines)
        if (line != null) {
            int winStartLine = Math.max(1, line - WINDOW_RADIUS);
            int winEndLine = Math.min(lineStarts.size(), line + WINDOW_RADIUS);
            int winFrom = lineStarts.get(winStartLine - 1);
            int winUntil = winEndLine == lineStarts.size() ? source.length() : lineStarts.get(winEndLine);
            int winMatch = exact(source, old, winFrom);
            if (winMatch >= 0 && winMatch < winUntil) {
                int dup = exact(source, old, winMatch + 1);
                if (dup < 0 || dup >= winUntil) {
                    return new Edit(winMatch, winMatch + old.length(), lineEndings(source, winMatch, edit.newText()));
                }
            }
        }

        // Java text blocks and Unicode escapes can give leading indentation semantic meaning.
        if (literalIndentation(source) || literalIndentation(edit.newText())) {
            throw new AnchorFailure("Anchor not found (text block or unicode escape present)",
                    bestNearestExcerpt(source, lineStarts, old, line));
        }

        // Tier 3: Indentation & line-ending normalized whole-line match
        Edit tier3 = tryWholeLines(source, lineStarts, edit, false);
        if (tier3 != null) return tier3;

        // Tier 4: Bounded whitespace-outside-literals whole-line match (strictly preserves string/char literals!)
        Edit tier4 = tryWholeLines(source, lineStarts, edit, true);
        if (tier4 != null) return tier4;

        throw new AnchorFailure("Could not unambiguously locate oldText in source",
                bestNearestExcerpt(source, lineStarts, old, line));
    }

    private static ArrayList<Integer> computeLineStarts(String source) {
        var lineStarts = new ArrayList<Integer>();
        lineStarts.add(0);
        NEWLINE.matcher(source).results().forEach(match -> lineStarts.add(match.end()));
        return lineStarts;
    }

    private static int exact(String source, String anchor, int from) {
        int start = source.indexOf(anchor, from);
        if (indent(anchor) == 0) return start;
        while (start > 0 && startsInsideIndentation(source, start)) start = source.indexOf(anchor, start + 1);
        return start;
    }

    private static boolean startsInsideIndentation(String source, int start) {
        int previous = start - 1;
        while (previous >= 0 && (source.charAt(previous) == ' ' || source.charAt(previous) == '\t')) previous--;
        return previous != start - 1 && (previous < 0 || source.charAt(previous) == '\n' || source.charAt(previous) == '\r');
    }

    private static Edit tryWholeLines(String source, ArrayList<Integer> starts, RepairChatModel.Change edit, boolean normalizeOuterWhitespace) {
        String[] expected = NEWLINE.split(edit.oldText(), -1);
        boolean trailingBreak = expected[expected.length - 1].isEmpty();
        int count = expected.length - (trailingBreak ? 1 : 0);
        if (count == 0 || expected[0].isBlank()) return null;

        String[] captured = NEWLINE.split(source, -1);
        var normalizedCaptured = new String[captured.length];
        for (int i = 0; i < captured.length; i++) {
            String stripped = captured[i].substring(indent(captured[i]));
            normalizedCaptured[i] = normalizeOuterWhitespace ? normalizeWhitespaceOutsideLiterals(stripped) : stripped;
        }
        var normalizedExpected = new String[count];
        for (int i = 0; i < count; i++) {
            String stripped = expected[i].substring(indent(expected[i]));
            normalizedExpected[i] = normalizeOuterWhitespace ? normalizeWhitespaceOutsideLiterals(stripped) : stripped;
        }

        Integer requested = edit.startLine();
        var matches = new ArrayList<Integer>();
        int searchFrom = 0;
        int searchLimit = captured.length - count;
        if (requested != null) {
            int exactIdx = requested - 1;
            if (exactIdx >= 0 && exactIdx <= searchLimit && matchesAt(normalizedCaptured, normalizedExpected, exactIdx)) {
                matches.add(exactIdx);
            } else {
                searchFrom = Math.max(0, exactIdx - WINDOW_RADIUS);
                searchLimit = Math.min(searchLimit, exactIdx + WINDOW_RADIUS);
                for (int i = searchFrom; i <= searchLimit; i++) {
                    if (matchesAt(normalizedCaptured, normalizedExpected, i)) matches.add(i);
                }
            }
        } else {
            for (int i = 0; i <= searchLimit; i++) {
                if (matchesAt(normalizedCaptured, normalizedExpected, i)) matches.add(i);
            }
        }
        if (matches.size() != 1) return null;

        int first = matches.getFirst();
        if (first + count > captured.length || (trailingBreak && first + count >= starts.size())) return null;
        int from = starts.get(first), last = first + count - 1;
        int to = trailingBreak ? starts.get(last + 1) : starts.get(last) + captured[last].length();
        String originalIndent = captured[first].substring(0, indent(captured[first]));
        String modelIndent = expected[0].substring(0, indent(expected[0]));
        String next = Arrays.stream(NEWLINE.split(edit.newText(), -1)).map(value -> {
            if (value.isBlank() || !value.startsWith(modelIndent)) return value;
            return originalIndent + value.substring(modelIndent.length());
        }).collect(Collectors.joining("\n"));
        return new Edit(from, to, lineEndings(source, from, next));
    }

    private static boolean matchesAt(String[] captured, String[] expected, int startIdx) {
        for (int i = 0; i < expected.length; i++) {
            if (!captured[startIdx + i].equals(expected[i])) return false;
        }
        return true;
    }

    /**
     * Collapses consecutive horizontal spaces/tabs OUTSIDE string/char literals and trims trailing
     * whitespace, while preserving EVERY character inside quoted strings/chars verbatim.
     */
    static String normalizeWhitespaceOutsideLiterals(String line) {
        var out = new StringBuilder(line.length());
        boolean inQuote = false;
        char quoteChar = 0;
        boolean escape = false;
        boolean lastWasSpace = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == quoteChar) {
                    inQuote = false;
                }
                lastWasSpace = false;
            } else if (c == '"' || c == '\'') {
                inQuote = true;
                quoteChar = c;
                out.append(c);
                lastWasSpace = false;
            } else if (c == ' ' || c == '\t') {
                if (!lastWasSpace && !out.isEmpty()) {
                    out.append(' ');
                    lastWasSpace = true;
                }
            } else {
                out.append(c);
                lastWasSpace = false;
            }
        }
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == ' ') end--;
        return out.substring(0, end);
    }

    private static String bestNearestExcerpt(String source, ArrayList<Integer> lineStarts, String needle, Integer requestedLine) {
        if (requestedLine != null && requestedLine >= 1 && requestedLine <= lineStarts.size()) {
            return excerptAround(source, lineStarts, requestedLine, 5);
        }
        String[] lines = NEWLINE.split(source, -1);
        var needleTokens = tokenize(needle);
        int bestLine = 1;
        int bestScore = -1;
        for (int i = 0; i < lines.length; i++) {
            int score = 0;
            String low = lines[i].toLowerCase(Locale.ROOT);
            for (String tok : needleTokens) {
                if (low.contains(tok)) score++;
            }
            if (score > bestScore) {
                bestScore = score;
                bestLine = i + 1;
            }
        }
        return excerptAround(source, lineStarts, bestLine, 5);
    }

    private static Set<String> tokenize(String text) {
        var tokens = new LinkedHashSet<String>();
        for (String part : text.split("[^A-Za-z0-9_]+")) {
            if (part.length() >= 3 && tokens.size() < 12) tokens.add(part.toLowerCase(Locale.ROOT));
        }
        return tokens;
    }

    private static String excerptAround(String source, ArrayList<Integer> lineStarts, int centerLine, int radius) {
        String[] lines = NEWLINE.split(source, -1);
        int lo = Math.max(1, centerLine - radius);
        int hi = Math.min(lines.length, centerLine + radius);
        var sb = new StringBuilder();
        for (int ln = lo; ln <= hi; ln++) {
            sb.append(ln).append(": ").append(lines[ln - 1]).append('\n');
        }
        return sb.toString();
    }

    private static int indent(String text) {
        int index = 0;
        while (index < text.length() && (text.charAt(index) == ' ' || text.charAt(index) == '\t')) index++;
        return index;
    }

    private static boolean literalIndentation(String text) {
        return text.contains("\"\"\"") || text.contains("\\u");
    }

    private static String lineEndings(String source, int from, String next) {
        var newline = NEWLINE.matcher(source);
        if (newline.find(from) || newline.find(0)) return NEWLINE.matcher(next).replaceAll(newline.group());
        return next;
    }

    private RepairAnchors() {}
}
