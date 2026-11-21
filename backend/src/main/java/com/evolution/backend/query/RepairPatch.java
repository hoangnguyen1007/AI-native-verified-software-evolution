package com.evolution.backend.query;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deterministic Git unified diff for existing UTF-8 text files; no checkout, index or Git command. */
public final class RepairPatch {
    private static final int CONTEXT=3;

    static String file(String path,String before,String after) {
        if (before.equals(after)) throw new IllegalArgumentException("No edit");
        var old=lines(before);
        var next=lines(after);
        int prefix=0,suffix=0;
        while (prefix<Math.min(old.size(),next.size()) && old.get(prefix).equals(next.get(prefix))) prefix++;
        while (suffix<Math.min(old.size(),next.size())-prefix
                && old.get(old.size()-1-suffix).equals(next.get(next.size()-1-suffix))) suffix++;
        int start=Math.max(0,prefix-CONTEXT);
        int oldEnd=old.size()-suffix, newEnd=next.size()-suffix, tail=Math.min(suffix,CONTEXT);
        String a=gitPath("a/"+path), b=gitPath("b/"+path);
        var out=new StringBuilder("diff --git ").append(a).append(' ').append(b)
                .append("\n--- ").append(a).append("\n+++ ").append(b);
        out.append("\n@@ -").append(location(start,oldEnd+tail-start))
                .append(" +").append(location(start,newEnd+tail-start)).append(" @@\n");
        for (int i=start;i<prefix;i++) line(out,' ',old.get(i));
        for (int i=prefix;i<oldEnd;i++) line(out,'-',old.get(i));
        for (int i=prefix;i<newEnd;i++) line(out,'+',next.get(i));
        for (int i=oldEnd;i<oldEnd+tail;i++) line(out,' ',old.get(i));
        return out.toString();
    }

    private static String location(int start,int count) { return (count==0 ? start : start+1)+","+count; }
    private static List<String> lines(String text) {
        var result=new ArrayList<String>();
        int from=0;
        while (from<text.length()) {
            int end=text.indexOf('\n',from);
            if (end<0) end=text.length()-1;
            result.add(text.substring(from,end+1));
            from=end+1;
        }
        return result;
    }
    private static void line(StringBuilder out,char type,String line) {
        out.append(type).append(line);
        if (!line.endsWith("\n")) out.append("\n\\ No newline at end of file\n");
    }
    private static String gitPath(String path) {
        if (path.chars().allMatch(ch -> ch>32 && ch<127 && ch!='"' && ch!='\\')) return path;
        var out=new StringBuilder("\"");
        for (byte value:path.getBytes(StandardCharsets.UTF_8)) {
            int c=value&0xff;
            if (c=='"' || c=='\\') out.append('\\').append((char)c);
            else if (c<32 || c>=127) out.append('\\').append((char)('0'+(c>>6)))
                    .append((char)('0'+((c>>3)&7))).append((char)('0'+(c&7)));
            else out.append((char)c);
        }
        return out.append('"').toString();
    }

    public static Map<String, String> applyAll(Map<String, String> originalsByPath, String fullPatch) {
        if (fullPatch == null || fullPatch.isBlank()) throw new IllegalArgumentException("Empty patch");
        var rawLines = lines(fullPatch);
        var updated = new LinkedHashMap<String, String>();
        int idx = 0;
        while (idx < rawLines.size()) {
            String header = stripLf(rawLines.get(idx));
            if (!header.startsWith("diff --git ")) throw new IllegalArgumentException("Invalid patch header");
            idx++;
            if (idx + 1 >= rawLines.size()) throw new IllegalArgumentException("Incomplete patch headers");
            String minus = stripLf(rawLines.get(idx++));
            String plus = stripLf(rawLines.get(idx++));
            if (!minus.startsWith("--- ") || !plus.startsWith("+++ ")) throw new IllegalArgumentException("Invalid diff headers");
            String aPath = unquote(minus.substring(4));
            String bPath = unquote(plus.substring(4));
            if (!aPath.startsWith("a/") || !bPath.startsWith("b/")) throw new IllegalArgumentException("Invalid diff path");
            String path = aPath.substring(2);
            if (!path.equals(bPath.substring(2))) throw new IllegalArgumentException("Rename not supported");
            String original = updated.getOrDefault(path, originalsByPath.get(path));
            if (original == null) throw new IllegalArgumentException("Missing original source for " + path);
            var oldLines = lines(original);
            var out = new StringBuilder();
            int cursor = 0;
            boolean sawHunk = false;
            while (idx < rawLines.size() && !rawLines.get(idx).startsWith("diff --git ")) {
                String hunk = stripLf(rawLines.get(idx));
                if (!hunk.startsWith("@@ ")) throw new IllegalArgumentException("Expected hunk header");
                sawHunk = true;
                int atEnd = hunk.indexOf(" @@", 3);
                if (atEnd < 0) throw new IllegalArgumentException("Malformed hunk header");
                String[] parts = hunk.substring(3, atEnd).split(" ");
                if (parts.length != 2 || !parts[0].startsWith("-") || !parts[1].startsWith("+"))
                    throw new IllegalArgumentException("Malformed hunk ranges");
                int[] oldRange = parseRange(parts[0].substring(1));
                int oldTarget = oldRange[1] == 0 ? oldRange[0] : oldRange[0] - 1;
                if (oldTarget < cursor || oldTarget > oldLines.size()) throw new IllegalArgumentException("Invalid hunk offset");
                while (cursor < oldTarget) out.append(oldLines.get(cursor++));
                idx++;
                int oldRem = oldRange[1], newRem = parseRange(parts[1].substring(1))[1];
                while ((oldRem > 0 || newRem > 0) && idx < rawLines.size()) {
                    String line = rawLines.get(idx++);
                    if (line.isEmpty()) throw new IllegalArgumentException("Unexpected empty diff line");
                    char tag = line.charAt(0);
                    String body = line.substring(1);
                    boolean noNewline = idx < rawLines.size() && stripLf(rawLines.get(idx)).equals("\\ No newline at end of file");
                    if (noNewline) {
                        idx++;
                        if (body.endsWith("\n")) body = body.substring(0, body.length() - 1);
                    }
                    if (tag == ' ') {
                        if (cursor >= oldLines.size() || !oldLines.get(cursor).equals(body))
                            throw new IllegalArgumentException("Patch context mismatch");
                        out.append(oldLines.get(cursor++));
                        oldRem--; newRem--;
                    } else if (tag == '-') {
                        if (cursor >= oldLines.size() || !oldLines.get(cursor).equals(body))
                            throw new IllegalArgumentException("Patch deletion mismatch");
                        cursor++;
                        oldRem--;
                    } else if (tag == '+') {
                        out.append(body);
                        newRem--;
                    } else {
                        throw new IllegalArgumentException("Unexpected diff prefix");
                    }
                }
                if (oldRem != 0 || newRem != 0) throw new IllegalArgumentException("Truncated diff hunk");
            }
            if (!sawHunk) throw new IllegalArgumentException("No hunks in patch");
            while (cursor < oldLines.size()) out.append(oldLines.get(cursor++));
            updated.put(path, out.toString());
        }
        return Collections.unmodifiableMap(updated);
    }

    private static int[] parseRange(String spec) {
        int comma = spec.indexOf(',');
        if (comma < 0) return new int[]{Integer.parseInt(spec), 1};
        return new int[]{Integer.parseInt(spec.substring(0, comma)), Integer.parseInt(spec.substring(comma + 1))};
    }

    private static String stripLf(String line) {
        return line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
    }

    private static String unquote(String path) {
        if (path.length() < 2 || path.charAt(0) != '"' || path.charAt(path.length() - 1) != '"') return path;
        var bytes = new java.io.ByteArrayOutputStream();
        for (int i = 1; i < path.length() - 1; i++) {
            char c = path.charAt(i);
            if (c != '\\') {
                bytes.write((byte) c);
            } else {
                if (i + 1 >= path.length() - 1) throw new IllegalArgumentException("Malformed quoted path");
                char next = path.charAt(++i);
                if (next == '"' || next == '\\') bytes.write((byte) next);
                else if (next >= '0' && next <= '3' && i + 2 < path.length() - 1) {
                    int val = ((next - '0') << 6) | ((path.charAt(++i) - '0') << 3) | (path.charAt(++i) - '0');
                    bytes.write(val);
                } else throw new IllegalArgumentException("Unsupported patch escape");
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
    private RepairPatch() {}
}
