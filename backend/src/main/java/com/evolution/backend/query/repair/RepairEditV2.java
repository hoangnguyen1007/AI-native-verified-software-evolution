package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.query.llm.LlmTransport;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Structured multi-file, multi-operation repair edit format supporting both JSON ({@code {"edits":[...]}})
 * and Aider-style {@code <<<<<<< SEARCH / ======= / >>>>>>> REPLACE} fenced blocks.
 */
public final class RepairEditV2 {
    private static final GraphJson.Limits JSON = new GraphJson.Limits(LlmTransport.MAX_RESPONSE_BYTES, 24, 32_768);
    private static final Pattern SEARCH_MARKER = Pattern.compile("^<{7}\\s*SEARCH\\s*$");
    private static final Pattern DIVIDER_MARKER = Pattern.compile("^={7}\\s*$");
    private static final Pattern REPLACE_MARKER = Pattern.compile("^>{7}\\s*REPLACE\\s*$");
    private static final Pattern FILE_HEADER = Pattern.compile("^(?:FILE|PATH)\\s*:\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern OP_HEADER = Pattern.compile("^OP(?:ERATION)?\\s*:\\s*(MODIFY|CREATE|DELETE)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINE_HEADER = Pattern.compile("^START_?LINE\\s*:\\s*(\\d+)\\s*$", Pattern.CASE_INSENSITIVE);

    public enum Op {
        MODIFY,
        CREATE,
        DELETE;

        public static Op parse(String raw) {
            if (raw == null || raw.isBlank()) return MODIFY;
            return switch (raw.strip().toUpperCase(Locale.ROOT)) {
                case "MODIFY", "EDIT", "UPDATE", "REPLACE" -> MODIFY;
                case "CREATE", "ADD", "NEW" -> CREATE;
                case "DELETE", "REMOVE" -> DELETE;
                default -> throw new IllegalArgumentException("Unsupported repair edit op: " + raw);
            };
        }
    }

    public record EditSpec(
            Op op,
            OptionalInt fileIndex,
            Optional<String> path,
            Optional<ContentDigest> before,
            String search,
            String replace,
            Integer startLine,
            Optional<String> rationale) {
        public EditSpec {
            Objects.requireNonNull(op);
            fileIndex = fileIndex == null ? OptionalInt.empty() : fileIndex;
            path = path == null ? Optional.empty() : path;
            before = before == null ? Optional.empty() : before;
            search = search == null ? "" : search;
            replace = replace == null ? "" : replace;
            rationale = rationale == null ? Optional.empty() : rationale;
        }
    }

    private RepairEditV2() {}

    public static List<EditSpec> parseResponse(String rawContent, int maxEdits) {
        if (rawContent == null || rawContent.isBlank()) {
            throw new IllegalArgumentException("Empty model repair response");
        }
        String visible = LlmTransport.separateThinking(rawContent).visible();
        if (visible.isBlank()) {
            throw new IllegalArgumentException("Model response contained only reasoning");
        }
        if (containsSearchReplaceBlock(visible)) {
            return parseSearchReplaceBlocks(visible, maxEdits);
        }
        return parseJsonEdits(visible, maxEdits);
    }

    public static boolean containsSearchReplaceBlock(String text) {
        return text != null && text.contains("<<<<<<< SEARCH")
                && text.contains("=======")
                && text.contains(">>>>>>> REPLACE");
    }

    public static List<EditSpec> parseSearchReplaceBlocks(String visible, int maxEdits) {
        String[] lines = visible.split("\\r?\\n", -1);
        var specs = new ArrayList<EditSpec>();
        String currentPath = null;
        Op currentOp = Op.MODIFY;
        Integer currentStartLine = null;

        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].strip();
            var fm = FILE_HEADER.matcher(trimmed);
            if (fm.matches()) {
                currentPath = stripQuotesAndTicks(fm.group(1));
                currentOp = Op.MODIFY;
                currentStartLine = null;
                i++;
                continue;
            }
            var om = OP_HEADER.matcher(trimmed);
            if (om.matches()) {
                currentOp = Op.parse(om.group(1));
                i++;
                continue;
            }
            var lm = LINE_HEADER.matcher(trimmed);
            if (lm.matches()) {
                currentStartLine = Integer.parseInt(lm.group(1));
                if (currentStartLine < 1) throw new IllegalArgumentException("Invalid START_LINE");
                i++;
                continue;
            }
            if (SEARCH_MARKER.matcher(trimmed).matches()) {
                // Also support path on the immediately preceding non-blank line (Aider style)
                if (currentPath == null && i > 0) {
                    String prev = stripQuotesAndTicks(lines[i - 1].strip());
                    if (!prev.isEmpty() && !prev.startsWith("```") && (prev.contains("/") || prev.contains("."))) {
                        currentPath = prev;
                    }
                }
                if (currentPath == null || currentPath.isBlank()) {
                    throw new IllegalArgumentException("SEARCH/REPLACE block missing FILE: path header");
                }
                var searchBuf = new StringBuilder();
                var replaceBuf = new StringBuilder();
                i++;
                boolean foundDivider = false;
                while (i < lines.length) {
                    if (DIVIDER_MARKER.matcher(lines[i].strip()).matches()) {
                        foundDivider = true;
                        i++;
                        break;
                    }
                    if (!searchBuf.isEmpty()) searchBuf.append('\n');
                    searchBuf.append(lines[i]);
                    i++;
                }
                if (!foundDivider) throw new IllegalArgumentException("Unclosed SEARCH block missing =======");
                boolean foundEnd = false;
                while (i < lines.length) {
                    if (REPLACE_MARKER.matcher(lines[i].strip()).matches()) {
                        foundEnd = true;
                        i++;
                        break;
                    }
                    if (!replaceBuf.isEmpty()) replaceBuf.append('\n');
                    replaceBuf.append(lines[i]);
                    i++;
                }
                if (!foundEnd) throw new IllegalArgumentException("Unclosed REPLACE block missing >>>>>>> REPLACE");
                String searchText = searchBuf.toString();
                String replaceText = replaceBuf.toString();
                Op effectiveOp = currentOp;
                if (effectiveOp == Op.MODIFY && searchText.isBlank() && !replaceText.isBlank()) {
                    effectiveOp = Op.CREATE;
                }
                validateEdit(effectiveOp, OptionalInt.empty(), Optional.of(currentPath), searchText, replaceText);
                specs.add(new EditSpec(effectiveOp, OptionalInt.empty(), Optional.of(currentPath),
                        Optional.empty(), searchText, replaceText, currentStartLine, Optional.empty()));
                if (specs.size() > maxEdits) throw new IllegalArgumentException("Exceeded max edits");
                currentStartLine = null;
                continue;
            }
            i++;
        }
        return List.copyOf(specs);
    }

    private static List<EditSpec> parseJsonEdits(String visible, int maxEdits) {
        String unwrapped = LlmTransport.extractJsonObject(visible);
        var root = GraphJson.object(GraphJson.read(unwrapped, JSON));
        if (!root.containsKey("edits")) {
            throw new IllegalArgumentException("Repair JSON missing 'edits' key");
        }
        var edits = GraphJson.array(root.get("edits"));
        if (edits.size() > maxEdits) {
            throw new IllegalArgumentException("Too many edits: " + edits.size());
        }
        var result = new ArrayList<EditSpec>(edits.size());
        for (Object item : edits) {
            var row = GraphJson.object(item);
            Op op = row.get("op") instanceof String s ? Op.parse(s) : Op.MODIFY;
            OptionalInt fileIndex = OptionalInt.empty();
            if (row.get("file") instanceof BigDecimal num) {
                int idx = num.intValueExact();
                if (idx < 1) throw new IllegalArgumentException("Invalid file index");
                fileIndex = OptionalInt.of(idx);
            } else if (row.containsKey("file") && row.get("file") != null && !(row.get("file") instanceof String)) {
                throw new IllegalArgumentException("Invalid file field");
            }
            Optional<String> path = Optional.empty();
            if (row.get("path") instanceof String p && !p.isBlank()) {
                path = Optional.of(p.strip());
            } else if (row.get("file") instanceof String p && !p.isBlank()) {
                path = Optional.of(p.strip());
            }
            Optional<ContentDigest> before = Optional.empty();
            if (row.get("before") instanceof String b && !b.isBlank()) {
                before = Optional.of(new ContentDigest(b.strip()));
            }
            String search = firstString(row, "oldText", "search", "old_text");
            String replace = firstString(row, "newText", "replace", "new_text", "content");
            Integer startLine = null;
            if (row.get("startLine") != null) {
                if (!(row.get("startLine") instanceof BigDecimal line) || line.intValueExact() < 1) {
                    throw new IllegalArgumentException("Invalid startLine");
                }
                startLine = line.intValueExact();
            }
            Optional<String> rationale = row.get("rationale") instanceof String r && !r.isBlank()
                    ? Optional.of(r.strip()) : Optional.empty();

            validateEdit(op, fileIndex, path, search, replace);
            result.add(new EditSpec(op, fileIndex, path, before, search, replace, startLine, rationale));
        }
        return List.copyOf(result);
    }

    private static void validateEdit(Op op, OptionalInt fileIndex, Optional<String> path, String search, String replace) {
        if (fileIndex.isEmpty() && path.isEmpty()) {
            throw new IllegalArgumentException("Edit must specify 'file' or 'path'");
        }
        switch (op) {
            case MODIFY -> {
                if (search == null || search.isBlank() || search.equals(replace)) {
                    throw new IllegalArgumentException("MODIFY edit requires non-blank distinct oldText/search and newText/replace");
                }
            }
            case CREATE -> {
                if (path.isEmpty() || replace == null || replace.isBlank()) {
                    throw new IllegalArgumentException("CREATE edit requires path and non-blank content/newText");
                }
            }
            case DELETE -> {
                // Deleting an offered file requires fileIndex or path.
            }
        }
    }

    private static String firstString(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            if (row.containsKey(key) && row.get(key) != null) {
                return GraphJson.string(row.get(key));
            }
        }
        return "";
    }

    private static String stripQuotesAndTicks(String s) {
        String t = s.strip();
        if (t.length() >= 2 && ((t.startsWith("`") && t.endsWith("`"))
                || (t.startsWith("\"") && t.endsWith("\""))
                || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1).strip();
        }
        return t;
    }
}
