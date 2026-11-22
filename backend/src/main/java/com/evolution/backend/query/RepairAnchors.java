package com.evolution.backend.query;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Resolves model text to unique offsets in the authenticated original file. */
final class RepairAnchors {
    private static final Pattern NEWLINE = Pattern.compile("\\r\\n|\\r|\\n");
    record Edit(int start, int end, String replacement) {}

    static Edit locate(String source, RepairChatModel.Change edit) {
        String old = edit.oldText();
        var lineStarts = new ArrayList<Integer>();
        lineStarts.add(0);
        NEWLINE.matcher(source).results().forEach(match -> lineStarts.add(match.end()));
        Integer line = edit.startLine();
        if (line != null && line > lineStarts.size()) throw new IllegalArgumentException();
        int from = line == null ? 0 : lineStarts.get(line - 1);
        int until = line == null || line == lineStarts.size() ? source.length() : lineStarts.get(line);
        int start = exact(source,old,from);
        if (start >= 0 && start < until) {
            int duplicate = exact(source,old,start + 1);
            if (duplicate >= 0 && duplicate < until) throw new IllegalArgumentException();
            return new Edit(start,start + old.length(),lineEndings(source,start,edit.newText()));
        }
        // Java text blocks and Unicode escapes can give otherwise leading indentation semantic meaning.
        if (literalIndentation(source) || literalIndentation(edit.newText())) throw new IllegalArgumentException();
        return wholeLines(source,lineStarts,edit);
    }

    private static int exact(String source, String anchor, int from) {
        int start = source.indexOf(anchor,from);
        if (indent(anchor) == 0) return start;
        while (start > 0 && startsInsideIndentation(source,start)) start = source.indexOf(anchor,start + 1);
        return start;
    }

    private static boolean startsInsideIndentation(String source, int start) {
        int previous = start - 1;
        while (previous >= 0 && (source.charAt(previous) == ' ' || source.charAt(previous) == '\t')) previous--;
        return previous != start - 1 && (previous < 0 || source.charAt(previous) == '\n' || source.charAt(previous) == '\r');
    }

    private static Edit wholeLines(String source, ArrayList<Integer> starts, RepairChatModel.Change edit) {
        String[] expected = NEWLINE.split(edit.oldText(),-1);
        boolean trailingBreak = expected[expected.length - 1].isEmpty();
        int count = expected.length - (trailingBreak ? 1 : 0);
        if (count == 0 || expected[0].isBlank()) throw new IllegalArgumentException();

        String[] captured = NEWLINE.split(source,-1);
        StringBuilder lines = new StringBuilder("\n");
        var positions = new ArrayList<Integer>();
        for (String value : captured) {
            positions.add(lines.length() - 1);
            lines.append(value,indent(value),value.length()).append('\n');
        }
        String needle = "\n" + Arrays.stream(expected,0,count).map(value -> value.substring(indent(value)))
                .collect(Collectors.joining("\n")) + "\n";
        Integer requested = edit.startLine();
        int match = requested == null ? lines.indexOf(needle) : positions.get(requested - 1);
        if (match < 0 || lines.indexOf(needle,match) != match
                || requested == null && lines.indexOf(needle,match + 1) >= 0) throw new IllegalArgumentException();
        int first = Collections.binarySearch(positions,match);
        if (first < 0 || first + count > captured.length || trailingBreak && first + count >= starts.size())
            throw new IllegalArgumentException();
        int from = starts.get(first), last = first + count - 1;
        int to = trailingBreak ? starts.get(last + 1) : starts.get(last) + captured[last].length();
        String originalIndent = captured[first].substring(0,indent(captured[first]));
        String modelIndent = expected[0].substring(0,indent(expected[0]));
        String next = Arrays.stream(NEWLINE.split(edit.newText(),-1)).map(value -> {
            if (value.isBlank() || !value.startsWith(modelIndent)) return value;
            return originalIndent + value.substring(modelIndent.length());
        }).collect(Collectors.joining("\n"));
        return new Edit(from,to,lineEndings(source,from,next));
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
