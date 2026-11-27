package com.evolution.backend.query;

import com.evolution.backend.query.llm.LlmTransport;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Fits only cited captured files, retaining exact bytes and coordinates for every offered slice. */
final class ModelSources {
    private static final Pattern NEWLINE = Pattern.compile("\\r\\n|\\r|\\n");

    record Slice(int startLine, int start, int end) {}
    record Selection(RepairSource.File file, List<Slice> slices) {
        boolean full() { return slices.size() == 1 && contains(0, file.text().length()); }

        boolean contains(int start, int end) {
            return slices.stream().anyMatch(slice -> slice.start() <= start && slice.end() >= end);
        }

        /** Raw exact-byte payload for repair offset/SHA-256 verification (never silently mutated). */
        Map<String,Object> payload() {
            var row = new LinkedHashMap<String,Object>();
            row.put("path",file.path());
            row.put("before",file.digest());
            row.put("citations",file.citations().stream().map(c -> Map.of(
                    "identity",c.identity(),"family",c.family(),"span",c.span())).toList());
            if (full()) row.put("source",file.text());
            else row.put("excerpts",slices.stream().map(slice -> Map.of("startLine",slice.startLine(),
                    "source",file.text().substring(slice.start(),slice.end()))).toList());
            return row;
        }

        /**
         * Secret-redacted, sentinel-neutralized, {@code <untrusted_source>} framed payload for
         * read-only conversational chat and explanation turns.
         */
        Map<String,Object> chatPayload() {
            var row = new LinkedHashMap<String,Object>();
            String sha = file.digest().value();
            row.put("path", LlmTransport.neutralizeInjectionSentinels(file.path()));
            row.put("before", file.digest());
            row.put("citations", file.citations().stream().map(c -> Map.of(
                    "identity", c.identity(), "family", c.family(), "span", c.span())).toList());
            // Redact the FULL file first (preserveLineCount=true) so multi-line secrets (PEM keys,
            // YAML block scalars) can never leak across partial slice boundaries.
            var doc = LlmTransport.redactDocumentForPath(file.path(), file.text());
            if (full()) {
                row.put("source", LlmTransport.wrapPreRedactedUntrustedSource(file.path(), sha, doc.text()));
                if (doc.totalRedactedCount() > 0) row.put("redactedSecretsCount", doc.totalRedactedCount());
            } else {
                var lineStarts = computeLineStarts(file.text());
                var touchedSpans = new LinkedHashSet<Integer>();
                var excerpts = new ArrayList<Map<String,Object>>(slices.size());
                for (var slice : slices) {
                    int sIdx = Math.max(0, Math.min(slice.start(), file.text().length()));
                    int eIdx = Math.max(sIdx, Math.min(slice.end(), file.text().length()));
                    String redactedExcerpt;
                    if (eIdx > sIdx) {
                        int startLine = lineNumberForOffset(lineStarts, sIdx);
                        int endLine = lineNumberForOffset(lineStarts, eIdx - 1);
                        for (int si = 0; si < doc.spans().size(); si++) {
                            var sp = doc.spans().get(si);
                            if (sp.startLine() <= endLine && sp.endLine() >= startLine) {
                                touchedSpans.add(si);
                            }
                        }
                        redactedExcerpt = extractRedactedSlice(
                                file.text(), doc, lineStarts, sIdx, eIdx, startLine, endLine);
                    } else {
                        redactedExcerpt = "";
                    }
                    excerpts.add(Map.of(
                            "startLine", slice.startLine(),
                            "source", LlmTransport.wrapPreRedactedUntrustedSource(file.path(), sha, redactedExcerpt)));
                }
                row.put("excerpts", List.copyOf(excerpts));
                if (!touchedSpans.isEmpty()) row.put("redactedSecretsCount", touchedSpans.size());
            }
            return row;
        }
    }

    private static List<Integer> computeLineStarts(String text) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        NEWLINE.matcher(text).results().forEach(match -> starts.add(match.end()));
        return starts;
    }

    private static int lineNumberForOffset(List<Integer> lineStarts, int offset) {
        int idx = Collections.binarySearch(lineStarts, offset);
        if (idx >= 0) return idx + 1;
        int insertionPoint = -idx - 1;
        return Math.max(1, Math.min(lineStarts.size(), insertionPoint));
    }

    private static String extractRedactedSlice(
            String raw,
            LlmTransport.RedactedDocument doc,
            List<Integer> lineStarts,
            int sIdx,
            int eIdx,
            int startLine,
            int endLine) {
        var sb = new StringBuilder(Math.max(16, eIdx - sIdx));
        int docLines = doc.lineCount();
        for (int ln = startLine; ln <= endLine && ln <= docLines; ln++) {
            int rawLineStart = lineStarts.get(ln - 1);
            int rawLineEndWithNl = (ln < lineStarts.size()) ? lineStarts.get(ln) : raw.length();
            int rawLineContentEnd = rawLineEndWithNl;
            while (rawLineContentEnd > rawLineStart
                    && (raw.charAt(rawLineContentEnd - 1) == '\n' || raw.charAt(rawLineContentEnd - 1) == '\r')) {
                rawLineContentEnd--;
            }
            if (doc.redactedCountInLineRange(ln, ln) > 0) {
                sb.append(doc.lines().get(ln - 1));
            } else {
                int from = (ln == startLine) ? Math.max(rawLineStart, Math.min(sIdx, rawLineContentEnd)) : rawLineStart;
                int to = (ln == endLine) ? Math.max(from, Math.min(eIdx, rawLineContentEnd)) : rawLineContentEnd;
                sb.append(raw, from, to);
            }
            if (ln < endLine || eIdx > rawLineContentEnd) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }
    record Packet(List<Selection> files, byte[] bytes) {}

    /** Scans offered edit source files for secrets (including config-file assignments) without mutating exact CAS bytes. */
    static List<String> filesContainingSecrets(Collection<RepairSource.File> files) {
        if (files == null || files.isEmpty()) return List.of();
        var flagged = new ArrayList<String>();
        for (var file : files) {
            if (file != null && LlmTransport.containsSecretForPath(file.path(), file.text())) {
                flagged.add(file.path());
            }
        }
        return List.copyOf(flagged);
    }

    static Packet fit(List<RepairSource.File> files, Function<List<Selection>, byte[]> encode) {
        var full = files.stream().map(file -> new Selection(file,List.of(new Slice(1,0,file.text().length())))).toList();
        var packet = attempt(full,encode);
        if (packet.isPresent()) return packet.orElseThrow();
        List<Selection> focused = List.of();
        for (int radius : new int[] {48, 12, 0}) {
            focused = files.stream().map(file -> focus(file,radius)).filter(entry -> !entry.slices().isEmpty()).toList();
            packet = attempt(focused,encode);
            if (packet.isPresent()) return packet.orElseThrow();
        }
        // Keep making progress when a single unusually long line would otherwise crowd out other files.
        var kept = new ArrayList<Selection>();
        for (var file : focused) {
            var slices = new ArrayList<Slice>();
            for (var slice : file.slices()) {
                slices.add(slice);
                kept.add(new Selection(file.file(),List.copyOf(slices)));
                if (attempt(kept,encode).isEmpty()) slices.removeLast();
                kept.removeLast();
            }
            if (!slices.isEmpty()) kept.add(new Selection(file.file(),List.copyOf(slices)));
        }
        return new Packet(List.copyOf(kept),encode.apply(kept));
    }

    private static Optional<Packet> attempt(List<Selection> files, Function<List<Selection>, byte[]> encode) {
        try { return Optional.of(new Packet(List.copyOf(files),encode.apply(files))); }
        catch (BoundedJson.TooLarge exceedsBudget) { return Optional.empty(); }
    }

    private static Selection focus(RepairSource.File file, int radius) {
        var starts = new ArrayList<Integer>();
        starts.add(0);
        NEWLINE.matcher(file.text()).results().forEach(match -> starts.add(match.end()));
        var windows = new TreeMap<Integer,Integer>();
        for (var citation : file.citations()) {
            int first = citation.span().startLine(), last = citation.span().endLine();
            if (last > starts.size()) continue;
            if (last - first <= radius * 2) addWindow(windows,starts.size(),first,last,radius);
            else {
                addWindow(windows,starts.size(),first,first,radius);
                addWindow(windows,starts.size(),last,last,radius);
            }
        }
        var slices = new ArrayList<Slice>();
        for (var window : windows.entrySet()) {
            int begin = starts.get(window.getKey() - 1);
            int end = window.getValue() == starts.size() ? file.text().length() : starts.get(window.getValue());
            if (begin == end) continue;
            if (!slices.isEmpty() && slices.getLast().end() >= begin) {
                var previous = slices.removeLast();
                slices.add(new Slice(previous.startLine(),previous.start(),Math.max(end,previous.end())));
            } else slices.add(new Slice(window.getKey(),begin,end));
        }
        return new Selection(file,List.copyOf(slices));
    }

    private static void addWindow(Map<Integer,Integer> windows, int lines, int first, int last, int radius) {
        windows.merge(Math.max(1,first - radius),Math.min(lines,last + radius),Math::max);
    }

    private ModelSources() {}
}
