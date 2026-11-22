package com.evolution.backend.query;

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
    }
    record Packet(List<Selection> files, byte[] bytes) {}

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
