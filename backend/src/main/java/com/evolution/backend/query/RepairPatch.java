package com.evolution.backend.query;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Deterministic Git unified diff generator (Myers LCS multi-hunk) and in-memory applier
 * supporting MODIFY, CREATE ({@code /dev/null -> b/path}), and DELETE ({@code a/path -> /dev/null})
 * without spawning Git.
 */
public final class RepairPatch {
    private static final int CONTEXT = 3;

    public record ApplyResult(Map<String, String> updated, Set<String> created, Set<String> deleted) {
        public ApplyResult {
            updated = Collections.unmodifiableMap(new LinkedHashMap<>(updated));
            created = Collections.unmodifiableSet(new LinkedHashSet<>(created));
            deleted = Collections.unmodifiableSet(new LinkedHashSet<>(deleted));
        }
    }

    public static String file(String path, String before, String after) {
        if (before.equals(after)) throw new IllegalArgumentException("No edit");
        var oldLines = lines(before);
        var newLines = lines(after);
        var ops = myersDiff(oldLines, newLines);
        var hunks = groupHunks(ops, oldLines.size(), newLines.size());
        String a = gitPath("a/" + path), b = gitPath("b/" + path);
        var out = new StringBuilder("diff --git ").append(a).append(' ').append(b)
                .append("\n--- ").append(a).append("\n+++ ").append(b).append('\n');
        for (var hunk : hunks) {
            out.append("@@ -").append(location(hunk.oldStart, hunk.oldCount))
                    .append(" +").append(location(hunk.newStart, hunk.newCount)).append(" @@\n");
            for (var op : hunk.ops) {
                line(out, op.kind, op.text);
            }
        }
        return out.toString();
    }

    public static String createFile(String path, String after) {
        if (after == null || after.isEmpty()) throw new IllegalArgumentException("Empty created file");
        var newLines = lines(after);
        String a = gitPath("a/" + path), b = gitPath("b/" + path);
        var out = new StringBuilder("diff --git ").append(a).append(' ').append(b)
                .append("\nnew file mode 100644")
                .append("\n--- /dev/null\n+++ ").append(b)
                .append("\n@@ -0,0 +1,").append(newLines.size()).append(" @@\n");
        for (String l : newLines) line(out, '+', l);
        return out.toString();
    }

    public static String deleteFile(String path, String before) {
        var oldLines = lines(before);
        String a = gitPath("a/" + path), b = gitPath("b/" + path);
        var out = new StringBuilder("diff --git ").append(a).append(' ').append(b)
                .append("\ndeleted file mode 100644")
                .append("\n--- ").append(a).append("\n+++ /dev/null")
                .append("\n@@ -").append(oldLines.isEmpty() ? "0,0" : ("1," + oldLines.size())).append(" +0,0 @@\n");
        for (String l : oldLines) line(out, '-', l);
        return out.toString();
    }

    private record DiffOp(char kind, String text, int oldIdx, int newIdx) {}
    private record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<DiffOp> ops) {}

    /** Standard O(ND) Myers shortest-edit-script diff; falls back to bounded prefix/suffix if edit distance is huge. */
    private static List<DiffOp> myersDiff(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        // Trim common prefix and suffix first for O(1) fast path on single-region edits
        int prefix = 0;
        int minLen = Math.min(n, m);
        while (prefix < minLen && a.get(prefix).equals(b.get(prefix))) prefix++;
        int suffix = 0;
        while (suffix < minLen - prefix && a.get(n - 1 - suffix).equals(b.get(m - 1 - suffix))) suffix++;

        var result = new ArrayList<DiffOp>(n + m);
        for (int i = 0; i < prefix; i++) result.add(new DiffOp(' ', a.get(i), i, i));

        int midN = n - prefix - suffix;
        int midM = m - prefix - suffix;
        int maxD = Math.min(midN + midM, 4096);
        List<DiffOp> middle = computeMyersMiddle(a, b, prefix, midN, midM, maxD);
        result.addAll(middle);

        for (int s = 0; s < suffix; s++) {
            int ai = n - suffix + s;
            int bi = m - suffix + s;
            result.add(new DiffOp(' ', a.get(ai), ai, bi));
        }
        return result;
    }

    private static List<DiffOp> computeMyersMiddle(List<String> a, List<String> b, int offset, int n, int m, int maxD) {
        if (n == 0) {
            var out = new ArrayList<DiffOp>(m);
            for (int j = 0; j < m; j++) out.add(new DiffOp('+', b.get(offset + j), offset, offset + j));
            return out;
        }
        if (m == 0) {
            var out = new ArrayList<DiffOp>(n);
            for (int i = 0; i < n; i++) out.add(new DiffOp('-', a.get(offset + i), offset + i, offset));
            return out;
        }
        int max = n + m;
        int limitD = Math.min(max, maxD);
        int size = 2 * limitD + 3;
        int shift = limitD + 1;
        int[] v = new int[size];
        var trace = new ArrayList<int[]>();
        int foundD = -1;
        for (int d = 0; d <= limitD; d++) {
            trace.add(v.clone());
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[shift + k - 1] < v[shift + k + 1])) {
                    x = v[shift + k + 1];
                } else {
                    x = v[shift + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(offset + x).equals(b.get(offset + y))) {
                    x++;
                    y++;
                }
                v[shift + k] = x;
                if (x >= n && y >= m) {
                    foundD = d;
                    break;
                }
            }
            if (foundD >= 0) break;
        }
        if (foundD < 0) {
            // Fallback when edit distance exceeds maxD: emit whole middle block
            var out = new ArrayList<DiffOp>(n + m);
            for (int i = 0; i < n; i++) out.add(new DiffOp('-', a.get(offset + i), offset + i, offset));
            for (int j = 0; j < m; j++) out.add(new DiffOp('+', b.get(offset + j), offset + n, offset + j));
            return out;
        }
        var rev = new ArrayList<DiffOp>(n + m);
        int x = n, y = m;
        for (int d = foundD; d > 0; d--) {
            int[] prev = trace.get(d);
            int k = x - y;
            int prevK;
            if (k == -d || (k != d && prev[shift + k - 1] < prev[shift + k + 1])) {
                prevK = k + 1;
            } else {
                prevK = k - 1;
            }
            int prevX = prev[shift + prevK];
            int prevY = prevX - prevK;
            while (x > prevX && y > prevY) {
                x--;
                y--;
                rev.add(new DiffOp(' ', a.get(offset + x), offset + x, offset + y));
            }
            if (x == prevX) {
                y--;
                rev.add(new DiffOp('+', b.get(offset + y), offset + x, offset + y));
            } else {
                x--;
                rev.add(new DiffOp('-', a.get(offset + x), offset + x, offset + y));
            }
        }
        while (x > 0 && y > 0) {
            x--;
            y--;
            rev.add(new DiffOp(' ', a.get(offset + x), offset + x, offset + y));
        }
        Collections.reverse(rev);
        return rev;
    }

    private static List<Hunk> groupHunks(List<DiffOp> ops, int oldTotal, int newTotal) {
        var changeRanges = new ArrayList<int[]>();
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind != ' ') {
                int start = i;
                while (i < ops.size() && ops.get(i).kind != ' ') i++;
                int end = i; // exclusive
                int ctxStart = Math.max(0, start - CONTEXT);
                int ctxEnd = Math.min(ops.size(), end + CONTEXT);
                if (!changeRanges.isEmpty() && ctxStart <= changeRanges.getLast()[1]) {
                    changeRanges.getLast()[1] = Math.max(changeRanges.getLast()[1], ctxEnd);
                } else {
                    changeRanges.add(new int[]{ctxStart, ctxEnd});
                }
            } else {
                i++;
            }
        }
        var hunks = new ArrayList<Hunk>(changeRanges.size());
        for (int[] r : changeRanges) {
            var slice = ops.subList(r[0], r[1]);
            int oldCount = 0, newCount = 0;
            int oldStart = -1, newStart = -1;
            for (var op : slice) {
                if (op.kind == ' ' || op.kind == '-') {
                    if (oldStart < 0) oldStart = op.oldIdx;
                    oldCount++;
                }
                if (op.kind == ' ' || op.kind == '+') {
                    if (newStart < 0) newStart = op.newIdx;
                    newCount++;
                }
            }
            if (oldStart < 0) oldStart = slice.isEmpty() ? 0 : Math.min(oldTotal, slice.getFirst().oldIdx);
            if (newStart < 0) newStart = slice.isEmpty() ? 0 : Math.min(newTotal, slice.getFirst().newIdx);
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, List.copyOf(slice)));
        }
        return hunks;
    }

    private static String location(int start, int count) { return (count == 0 ? start : start + 1) + "," + count; }

    private static List<String> lines(String text) {
        var result = new ArrayList<String>();
        int from = 0;
        while (from < text.length()) {
            int end = text.indexOf('\n', from);
            if (end < 0) end = text.length() - 1;
            result.add(text.substring(from, end + 1));
            from = end + 1;
        }
        return result;
    }

    private static void line(StringBuilder out, char type, String line) {
        out.append(type).append(line);
        if (!line.endsWith("\n")) out.append("\n\\ No newline at end of file\n");
    }

    private static String gitPath(String path) {
        if (path.chars().allMatch(ch -> ch > 32 && ch < 127 && ch != '"' && ch != '\\')) return path;
        var out = new StringBuilder("\"");
        for (byte value : path.getBytes(StandardCharsets.UTF_8)) {
            int c = value & 0xff;
            if (c == '"' || c == '\\') out.append('\\').append((char) c);
            else if (c < 32 || c >= 127) out.append('\\').append((char) ('0' + (c >> 6)))
                    .append((char) ('0' + ((c >> 3) & 7))).append((char) ('0' + (c & 7)));
            else out.append((char) c);
        }
        return out.append('"').toString();
    }

    public static Map<String, String> applyAll(Map<String, String> originalsByPath, String fullPatch) {
        return applyAllDetailed(originalsByPath, fullPatch).updated();
    }

    public static ApplyResult applyAllDetailed(Map<String, String> originalsByPath, String fullPatch) {
        if (fullPatch == null || fullPatch.isBlank()) throw new IllegalArgumentException("Empty patch");
        var rawLines = lines(fullPatch);
        var updated = new LinkedHashMap<String, String>();
        var created = new LinkedHashSet<String>();
        var deleted = new LinkedHashSet<String>();
        int idx = 0;
        while (idx < rawLines.size()) {
            String header = stripLf(rawLines.get(idx));
            if (!header.startsWith("diff --git ")) throw new IllegalArgumentException("Invalid patch header");
            idx++;
            // Skip optional git metadata headers (new file mode, deleted file mode, old mode, new mode, index)
            while (idx < rawLines.size()) {
                String meta = stripLf(rawLines.get(idx));
                if (meta.startsWith("new file mode ") || meta.startsWith("deleted file mode ")
                        || meta.startsWith("old mode ") || meta.startsWith("new mode ")
                        || meta.startsWith("index ")) {
                    idx++;
                } else {
                    break;
                }
            }
            if (idx + 1 >= rawLines.size()) throw new IllegalArgumentException("Incomplete patch headers");
            String minus = stripLf(rawLines.get(idx++));
            String plus = stripLf(rawLines.get(idx++));
            if (!minus.startsWith("--- ") || !plus.startsWith("+++ ")) throw new IllegalArgumentException("Invalid diff headers");
            String aPath = unquote(minus.substring(4));
            String bPath = unquote(plus.substring(4));
            boolean isCreate = "/dev/null".equals(aPath);
            boolean isDelete = "/dev/null".equals(bPath);
            String path;
            if (isCreate && !isDelete) {
                if (!bPath.startsWith("b/")) throw new IllegalArgumentException("Invalid create diff path");
                path = bPath.substring(2);
            } else if (isDelete && !isCreate) {
                if (!aPath.startsWith("a/")) throw new IllegalArgumentException("Invalid delete diff path");
                path = aPath.substring(2);
            } else {
                if (!aPath.startsWith("a/") || !bPath.startsWith("b/")) throw new IllegalArgumentException("Invalid diff path");
                path = aPath.substring(2);
                if (!path.equals(bPath.substring(2))) throw new IllegalArgumentException("Rename not supported");
            }
            List<String> oldLines;
            if (isCreate) {
                if (originalsByPath.containsKey(path) && !deleted.contains(path)) {
                    throw new IllegalArgumentException("Created file already exists: " + path);
                }
                oldLines = List.of();
            } else {
                String original = updated.getOrDefault(path, originalsByPath.get(path));
                if (original == null) throw new IllegalArgumentException("Missing original source for " + path);
                oldLines = lines(original);
            }
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
            if (isDelete) {
                if (out.length() != 0) throw new IllegalArgumentException("Deleted file had remaining lines");
                updated.remove(path);
                deleted.add(path);
            } else {
                updated.put(path, out.toString());
                if (isCreate) created.add(path);
            }
        }
        return new ApplyResult(updated, created, deleted);
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
