package com.evolution.backend.query.advisory;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.llm.LlmTransport;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Markdown &amp; ADR extractor for the quarantined {@code advisory.doc-link:v1} overlay.
 *
 * <p>Extracts headings, ADR identifiers ({@code ADR-001}), Markdown/wikilinks, and backtick code
 * spans matched deterministically against canonical {@code KnowledgeGraph} symbols, file paths,
 * and HTTP routes. Never embeds prose into {@code KnowledgeGraph}; pairs cleanly with on-demand
 * {@link CapturedFileIndex#readMarkdownSection}.
 */
public final class MarkdownAdrExtractor {
    private static final Pattern ADR_ID = Pattern.compile("\\b(ADR-\\d{2,4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]{1,120})\\]\\(([^)\\s]{1,240})\\)");
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]|]{1,160})(?:\\|([^\\]]{1,120}))?\\]\\]");
    private static final Pattern BACKTICK_CODE = Pattern.compile("`([^`\\r\\n]{2,140})`");
    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})");

    public record GraphSymbolRef(String identity, String label, String kind, String path, String route) {}

    public record ExtractionOutput(List<AdvisoryOverlay.AdvisoryNode> nodes,
                                   List<AdvisoryOverlay.AdvisoryLink> links,
                                   int redactedSecretsCount) {}

    private MarkdownAdrExtractor() {}

    public static ExtractionOutput extract(
            String path,
            String contentSha256,
            String rawText,
            Map<String, GraphSymbolRef> byFqn,
            Map<String, List<GraphSymbolRef>> bySimpleName,
            Map<String, GraphSymbolRef> byRoute,
            Set<String> allCapturedPaths) {
        if (rawText == null || rawText.isBlank()) {
            return new ExtractionOutput(List.of(), List.of(), 0);
        }
        String normPath = CapturedFileIndex.normalizePath(path);
        String[] lines = rawText.split("\\R", -1);
        var headings = CapturedFileIndex.parseMarkdownHeadings(lines);
        var nodes = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        var links = new ArrayList<AdvisoryOverlay.AdvisoryLink>();
        var seenLinks = new HashSet<String>();
        int redactedCount = 0;

        String docNodeId = advisoryId("MD_DOCUMENT", normPath, 1);
        String docTitle = headings.isEmpty() ? normPath : headings.getFirst().title();
        var docMeta = new LinkedHashMap<String, Object>();
        docMeta.put("headingCount", headings.size());
        docMeta.put("lineCount", lines.length);
        Matcher pathAdr = ADR_ID.matcher(normPath);
        if (pathAdr.find()) {
            docMeta.put("adrId", pathAdr.group(1).toUpperCase(Locale.ROOT));
        }
        nodes.add(new AdvisoryOverlay.AdvisoryNode(
                docNodeId, docMeta.containsKey("adrId") ? "ADR_DOCUMENT" : "MD_DOCUMENT",
                docTitle, normPath, 1, lines.length, contentSha256, docMeta));

        for (var h : headings) {
            String hid = advisoryId("MD_HEADING", normPath + "#" + h.anchor(), h.startLine());
            nodes.add(new AdvisoryOverlay.AdvisoryNode(
                    hid, "MD_HEADING", h.title(), normPath, h.startLine(), h.endLine(), contentSha256,
                    Map.of("level", h.level(), "anchor", h.anchor())));
        }

        // Scan lines outside fenced code blocks for ADRs, links, wikilinks, and backtick symbol mentions
        boolean inFence = false;
        char fenceChar = 0;
        int fenceLen = 0;
        var seenAdrNodes = new HashSet<String>();

        for (int idx = 0; idx < lines.length; idx++) {
            String line = lines[idx];
            int lineNo = idx + 1;
            Matcher fm = FENCE.matcher(line);
            if (fm.find()) {
                String tok = fm.group(1);
                if (!inFence) {
                    inFence = true;
                    fenceChar = tok.charAt(0);
                    fenceLen = tok.length();
                } else {
                    final char expectedFence = fenceChar;
                    if (tok.charAt(0) == expectedFence && tok.length() >= fenceLen
                            && line.strip().chars().allMatch(c -> c == expectedFence)) {
                        inFence = false;
                    }
                }
                continue;
            }
            if (inFence) continue;

            var red = LlmTransport.redactSecrets(line);
            redactedCount += red.redactedCount();
            String safeLine = red.text();

            // Active enclosing heading (fallback to doc node)
            String activeSourceId = docNodeId;
            String activeSourceLabel = docTitle;
            for (var h : headings) {
                if (h.startLine() <= lineNo && lineNo <= h.endLine()) {
                    activeSourceId = advisoryId("MD_HEADING", normPath + "#" + h.anchor(), h.startLine());
                    activeSourceLabel = normPath + " § " + h.title();
                }
            }

            // 1. ADR references
            Matcher adrMatcher = ADR_ID.matcher(safeLine);
            while (adrMatcher.find()) {
                String adr = adrMatcher.group(1).toUpperCase(Locale.ROOT);
                String adrId = advisoryId("ADR_REFERENCE", normPath + ":" + adr, lineNo);
                if (seenAdrNodes.add(adr)) {
                    nodes.add(new AdvisoryOverlay.AdvisoryNode(
                            adrId, "ADR_RECORD", adr, normPath, lineNo, lineNo, contentSha256,
                            Map.of("adrCode", adr)));
                }
            }

            // 2. Internal Markdown links [label](target)
            Matcher linkMatcher = MD_LINK.matcher(safeLine);
            while (linkMatcher.find()) {
                String targetRaw = linkMatcher.group(2).strip();
                if (targetRaw.startsWith("http://") || targetRaw.startsWith("https://")
                        || targetRaw.startsWith("mailto:") || targetRaw.startsWith("#")) continue;
                int hashIdx = targetRaw.indexOf('#');
                String targetPath = hashIdx >= 0 ? targetRaw.substring(0, hashIdx) : targetRaw;
                String resolved = resolveRelative(normPath, targetPath, allCapturedPaths);
                if (resolved != null && !resolved.equals(normPath)) {
                    String linkKey = activeSourceId + "|DOC_LINKS_FILE|" + resolved;
                    if (seenLinks.add(linkKey)) {
                        links.add(new AdvisoryOverlay.AdvisoryLink(
                                advisoryId("DOC_LINKS_FILE", linkKey, lineNo),
                                "DOC_LINKS_FILE",
                                activeSourceId, activeSourceLabel,
                                "file:" + resolved, resolved, "CAPTURED_FILE",
                                normPath, lineNo, lineNo, contentSha256, "markdown-link"));
                    }
                }
            }

            // 3. Wikilinks [[target]]
            Matcher wikiMatcher = WIKILINK.matcher(safeLine);
            while (wikiMatcher.find()) {
                String target = wikiMatcher.group(1).strip();
                String resolved = resolveWikilink(target, allCapturedPaths);
                if (resolved != null) {
                    String linkKey = activeSourceId + "|DOC_WIKILINK|" + resolved;
                    if (seenLinks.add(linkKey)) {
                        links.add(new AdvisoryOverlay.AdvisoryLink(
                                advisoryId("DOC_WIKILINK", linkKey, lineNo),
                                "DOC_WIKILINK",
                                activeSourceId, activeSourceLabel,
                                "file:" + resolved, resolved, "CAPTURED_FILE",
                                normPath, lineNo, lineNo, contentSha256, "wikilink"));
                    }
                }
            }

            // 4. Backtick code spans matching graph symbols, routes, or captured paths
            Matcher codeMatcher = BACKTICK_CODE.matcher(safeLine);
            while (codeMatcher.find()) {
                String token = codeMatcher.group(1).strip();
                if (token.length() < 3 || token.contains(" ")) continue;
                // Exact FQN match
                GraphSymbolRef fqnHit = byFqn.get(token);
                if (fqnHit != null) {
                    addSymbolLink(links, seenLinks, activeSourceId, activeSourceLabel, fqnHit,
                            normPath, lineNo, contentSha256, "backtick-fqn");
                    continue;
                }
                // Route match (e.g. /api/v1/orders)
                if (token.startsWith("/")) {
                    GraphSymbolRef routeHit = byRoute.get(token);
                    if (routeHit != null) {
                        addSymbolLink(links, seenLinks, activeSourceId, activeSourceLabel, routeHit,
                                normPath, lineNo, contentSha256, "backtick-route");
                        continue;
                    }
                }
                // Unique simple class/type name match (avoid ambiguous short generic tokens)
                String cleanSym = token.endsWith("()") ? token.substring(0, token.length() - 2) : token;
                if (cleanSym.length() >= 4 && Character.isUpperCase(cleanSym.charAt(0))) {
                    List<GraphSymbolRef> simpleHits = bySimpleName.get(cleanSym);
                    if (simpleHits != null && simpleHits.size() == 1) {
                        addSymbolLink(links, seenLinks, activeSourceId, activeSourceLabel, simpleHits.getFirst(),
                                normPath, lineNo, contentSha256, "backtick-unique-symbol");
                        continue;
                    }
                }
                // Captured file path match
                String normTok = CapturedFileIndex.normalizePath(token);
                if (allCapturedPaths.contains(normTok) && !normTok.equals(normPath)) {
                    String linkKey = activeSourceId + "|DOC_MENTIONS_FILE|" + normTok;
                    if (seenLinks.add(linkKey)) {
                        links.add(new AdvisoryOverlay.AdvisoryLink(
                                advisoryId("DOC_MENTIONS_FILE", linkKey, lineNo),
                                "DOC_MENTIONS_FILE",
                                activeSourceId, activeSourceLabel,
                                "file:" + normTok, normTok, "CAPTURED_FILE",
                                normPath, lineNo, lineNo, contentSha256, "backtick-path"));
                    }
                }
            }
        }
        return new ExtractionOutput(List.copyOf(nodes), List.copyOf(links), redactedCount);
    }

    private static void addSymbolLink(
            List<AdvisoryOverlay.AdvisoryLink> links,
            Set<String> seenLinks,
            String sourceId,
            String sourceLabel,
            GraphSymbolRef target,
            String sourcePath,
            int lineNo,
            String contentSha256,
            String basis) {
        String key = sourceId + "|DOC_MENTIONS_SYMBOL|" + target.identity();
        if (seenLinks.add(key)) {
            links.add(new AdvisoryOverlay.AdvisoryLink(
                    advisoryId("DOC_MENTIONS_SYMBOL", key, lineNo),
                    "DOC_MENTIONS_SYMBOL",
                    sourceId, sourceLabel,
                    target.identity(), target.label(), target.kind(),
                    sourcePath, lineNo, lineNo, contentSha256, basis));
        }
    }

    private static String resolveRelative(String docPath, String rawTarget, Set<String> allPaths) {
        String clean = rawTarget.replace('\\', '/').strip();
        if (clean.isEmpty()) return null;
        if (allPaths.contains(CapturedFileIndex.normalizePath(clean))) {
            return CapturedFileIndex.normalizePath(clean);
        }
        int slash = docPath.lastIndexOf('/');
        String baseDir = slash > 0 ? docPath.substring(0, slash) : "";
        String combined = baseDir.isEmpty() ? clean : baseDir + "/" + clean;
        String[] parts = combined.split("/");
        var stack = new ArrayDeque<String>();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.addLast(p);
            }
        }
        String normalized = String.join("/", stack);
        return allPaths.contains(normalized) ? normalized : null;
    }

    private static String resolveWikilink(String target, Set<String> allPaths) {
        String norm = CapturedFileIndex.normalizePath(target);
        if (allPaths.contains(norm)) return norm;
        if (allPaths.contains(norm + ".md")) return norm + ".md";
        String suffix = "/" + norm.toLowerCase(Locale.ROOT);
        String suffixMd = suffix + ".md";
        for (String p : allPaths) {
            String low = p.toLowerCase(Locale.ROOT);
            if (low.endsWith(suffix) || low.endsWith(suffixMd)) return p;
        }
        return null;
    }

    static String advisoryId(String kind, String key, int line) {
        String digest = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(AdvisoryOverlay.SCHEMA, kind, key, line))).value();
        String hex = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        return "adv:" + kind.toLowerCase(Locale.ROOT) + ":" + hex.substring(0, 16);
    }
}
