package com.evolution.backend.query.advisory;

import com.evolution.backend.query.llm.LlmTransport;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic Terraform / HCL extractor ({@code *.tf}) for the quarantined
 * {@code advisory.doc-link:v1} overlay.
 *
 * <p>Extracts {@code TF_RESOURCE}, {@code TF_MODULE}, {@code TF_DATA}, {@code TF_OUTPUT}, and
 * internal reference edges, applying recursive parse-time redaction on sensitive attribute keys
 * and secret-bearing values so raw credentials never enter extracted metadata.
 */
public final class TerraformHclExtractor {
    private static final Pattern BLOCK_HEADER = Pattern.compile(
            "^\\s*(resource|data|module|output)\\s+\"([A-Za-z0-9_\\-]+)\"(?:\\s+\"([A-Za-z0-9_\\-]+)\")?\\s*\\{");
    private static final Pattern ATTR_ASSIGN = Pattern.compile(
            "^\\s*([A-Za-z_][A-Za-z0-9_\\-]*)\\s*=\\s*(.+)$");
    private static final Pattern HEREDOC_START = Pattern.compile(
            "^<<(-)?([A-Za-z_][A-Za-z0-9_\\-]*)\\s*$");
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(?:password|passwd|pwd|secret|token|api_?key|private_?key|access_?key|credential|connection_?string|auth|signing_?key|client_?secret|master_?key)");
    private static final Pattern REF_MODULE = Pattern.compile("\\bmodule\\.([A-Za-z0-9_\\-]+)\\b");
    private static final Pattern REF_DATA = Pattern.compile("\\bdata\\.([A-Za-z0-9_\\-]+)\\.([A-Za-z0-9_\\-]+)\\b");
    private static final Pattern REF_RESOURCE = Pattern.compile("\\b([a-z][a-z0-9_]{2,})\\.([A-Za-z0-9_\\-]+)\\b");
    private static final Set<String> NON_RESOURCE_PREFIXES = Set.of(
            "var", "local", "path", "each", "count", "self", "terraform", "module", "data", "aws_region", "null");

    public record ExtractionOutput(List<AdvisoryOverlay.AdvisoryNode> nodes,
                                   List<AdvisoryOverlay.AdvisoryLink> links,
                                   int redactedSecretsCount) {}

    private TerraformHclExtractor() {}

    public static ExtractionOutput extract(String path, String contentSha256, String rawHcl) {
        if (rawHcl == null || rawHcl.isBlank()) {
            return new ExtractionOutput(List.of(), List.of(), 0);
        }
        String[] lines = rawHcl.split("\\R", -1);
        var nodes = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
        var links = new ArrayList<AdvisoryOverlay.AdvisoryLink>();
        var seenLinks = new HashSet<String>();
        var knownAddresses = new LinkedHashMap<String, String>(); // address -> advisory node id
        int redactedSecrets = 0;

        record ParsedBlock(String nodeId, String kind, String address, int startLine, int endLine, List<String> bodyLines) {}
        var blocks = new ArrayList<ParsedBlock>();

        boolean[] inBlockComment = {false};
        int i = 0;
        while (i < lines.length) {
            String cleanHeaderLine = stripCommentsStateful(lines[i], inBlockComment);
            Matcher m = BLOCK_HEADER.matcher(cleanHeaderLine);
            if (!m.find()) {
                i++;
                continue;
            }
            String blockType = m.group(1);
            String label1 = m.group(2);
            String label2 = m.group(3);
            int startLine = i + 1;

            // Walk balanced braces across the block, respecting /* */ comments and <<EOF / <<-EOF heredocs
            int braceDepth = countBraceDeltaClean(cleanHeaderLine);
            var bodyLines = new ArrayList<String>();
            var bodyStrippedLines = new ArrayList<String>();
            var bodyIsHeredocLine = new ArrayList<Boolean>();
            String activeHeredoc = null;
            boolean heredocIndented = false;

            int cursor = i + 1;
            while (cursor < lines.length && braceDepth > 0) {
                String bl = lines[cursor];
                if (activeHeredoc != null) {
                    bodyLines.add(bl);
                    bodyStrippedLines.add(bl);
                    bodyIsHeredocLine.add(true);
                    String check = heredocIndented ? bl.strip() : bl.stripTrailing();
                    if (check.equals(activeHeredoc)) {
                        activeHeredoc = null;
                        heredocIndented = false;
                    }
                    cursor++;
                    continue;
                }
                String cleanBl = stripCommentsStateful(bl, inBlockComment);
                Matcher am = ATTR_ASSIGN.matcher(cleanBl);
                if (am.matches()) {
                    Matcher hm = HEREDOC_START.matcher(am.group(2).strip());
                    if (hm.matches()) {
                        heredocIndented = "-".equals(hm.group(1));
                        activeHeredoc = hm.group(2);
                    }
                }
                braceDepth += countBraceDeltaClean(cleanBl);
                bodyLines.add(bl);
                bodyStrippedLines.add(cleanBl);
                bodyIsHeredocLine.add(false);
                cursor++;
            }
            int endLine = Math.max(startLine, cursor);

            String kind;
            String address;
            var meta = new LinkedHashMap<String, Object>();
            switch (blockType) {
                case "resource" -> {
                    if (label2 == null) { i = Math.max(i + 1, cursor); continue; }
                    kind = "TF_RESOURCE";
                    address = label1 + "." + label2;
                    meta.put("resourceType", label1);
                    meta.put("resourceName", label2);
                }
                case "data" -> {
                    if (label2 == null) { i = Math.max(i + 1, cursor); continue; }
                    kind = "TF_DATA";
                    address = "data." + label1 + "." + label2;
                    meta.put("dataType", label1);
                    meta.put("dataName", label2);
                }
                case "module" -> {
                    kind = "TF_MODULE";
                    address = "module." + label1;
                    meta.put("moduleName", label1);
                }
                default -> {
                    kind = "TF_OUTPUT";
                    address = "output." + label1;
                    meta.put("outputName", label1);
                }
            }

            // Recursive parse-time key/value secret redaction on body attributes (including sensitive heredocs)
            var safeAttrs = new LinkedHashMap<String, String>();
            var redactedKeys = new ArrayList<String>();
            var safeBodyLines = new ArrayList<String>(bodyLines.size());
            boolean redactHeredocBody = false;
            for (int bi = 0; bi < bodyLines.size(); bi++) {
                boolean isHeredocBody = bodyIsHeredocLine.get(bi);
                String stripped = bodyStrippedLines.get(bi);
                if (isHeredocBody) {
                    if (redactHeredocBody) {
                        // Preserve 1-to-1 line correspondence without leaking sensitive heredoc content
                        safeBodyLines.add("");
                    } else {
                        var red = LlmTransport.redactSecrets(stripped);
                        redactedSecrets += red.redactedCount();
                        safeBodyLines.add(red.text());
                    }
                    continue;
                }
                redactHeredocBody = false;
                Matcher am = ATTR_ASSIGN.matcher(stripped);
                if (am.matches()) {
                    String key = am.group(1);
                    String val = am.group(2).strip();
                    boolean startsHeredoc = HEREDOC_START.matcher(val).matches();
                    if (SENSITIVE_KEY.matcher(key).find()) {
                        redactedSecrets++;
                        redactedKeys.add(key);
                        redactHeredocBody = startsHeredoc;
                        safeBodyLines.add(key + " = " + LlmTransport.REDACTED_PLACEHOLDER);
                        continue;
                    }
                    var red = LlmTransport.redactSecrets(val);
                    redactedSecrets += red.redactedCount();
                    if (!startsHeredoc && (key.equals("source") || key.equals("version") || key.equals("region")
                            || key.equals("bucket") || key.equals("name") || key.equals("engine"))) {
                        String cleanVal = red.text().replaceAll("^\"|\"$", "");
                        if (cleanVal.length() <= 160) safeAttrs.put(key, cleanVal);
                    }
                    safeBodyLines.add(key + " = " + red.text());
                } else {
                    var red = LlmTransport.redactSecrets(stripped);
                    redactedSecrets += red.redactedCount();
                    safeBodyLines.add(red.text());
                }
            }
            if (!safeAttrs.isEmpty()) meta.put("attributes", safeAttrs);
            if (!redactedKeys.isEmpty()) meta.put("redactedAttributeKeys", List.copyOf(redactedKeys));

            String nodeId = MarkdownAdrExtractor.advisoryId(kind, path + ":" + address, startLine);
            nodes.add(new AdvisoryOverlay.AdvisoryNode(nodeId, kind, address, path, startLine, endLine, contentSha256, meta));
            knownAddresses.put(address, nodeId);
            blocks.add(new ParsedBlock(nodeId, kind, address, startLine, endLine, safeBodyLines));
            i = Math.max(i + 1, cursor);
        }

        // Pass 2: Extract internal HCL references between blocks
        for (var block : blocks) {
            for (int bi = 0; bi < block.bodyLines().size(); bi++) {
                String bl = block.bodyLines().get(bi);
                if (bl.isBlank()) continue;
                int lineNo = block.startLine() + 1 + bi;

                Matcher mm = REF_MODULE.matcher(bl);
                while (mm.find()) {
                    if (mm.start() > 0 && bl.charAt(mm.start() - 1) == '.') continue;
                    String targetAddr = "module." + mm.group(1);
                    addTfRef(links, seenLinks, knownAddresses, block.nodeId(), block.address(),
                            targetAddr, "TF_MODULE", path, lineNo, contentSha256);
                }

                Matcher dm = REF_DATA.matcher(bl);
                while (dm.find()) {
                    if (dm.start() > 0 && bl.charAt(dm.start() - 1) == '.') continue;
                    String targetAddr = "data." + dm.group(1) + "." + dm.group(2);
                    addTfRef(links, seenLinks, knownAddresses, block.nodeId(), block.address(),
                            targetAddr, "TF_DATA", path, lineNo, contentSha256);
                }

                Matcher rm = REF_RESOURCE.matcher(bl);
                while (rm.find()) {
                    int st = rm.start();
                    // Never double-match data.<type>.<name>, module.<name>.<attr>, or chained attributes
                    if (st > 0 && (bl.charAt(st - 1) == '.' || bl.charAt(st - 1) == '/')) continue;
                    String type = rm.group(1);
                    String name = rm.group(2);
                    if (NON_RESOURCE_PREFIXES.contains(type) || !type.contains("_")) continue;
                    String targetAddr = type + "." + name;
                    if (targetAddr.equals(block.address())) continue;
                    addTfRef(links, seenLinks, knownAddresses, block.nodeId(), block.address(),
                            targetAddr, "TF_RESOURCE", path, lineNo, contentSha256);
                }
            }
        }

        return new ExtractionOutput(List.copyOf(nodes), List.copyOf(links), redactedSecrets);
    }

    private static void addTfRef(
            List<AdvisoryOverlay.AdvisoryLink> links,
            Set<String> seenLinks,
            Map<String, String> knownAddresses,
            String sourceId,
            String sourceLabel,
            String targetAddr,
            String targetKind,
            String path,
            int lineNo,
            String contentSha256) {
        if (targetAddr.equals(sourceLabel)) return;
        String targetId = knownAddresses.getOrDefault(targetAddr, "tf:" + targetAddr);
        String key = sourceId + "|TF_REFERENCES|" + targetId;
        if (seenLinks.add(key)) {
            links.add(new AdvisoryOverlay.AdvisoryLink(
                    MarkdownAdrExtractor.advisoryId("TF_REFERENCES", path + ":" + key, lineNo),
                    "TF_REFERENCES", sourceId, sourceLabel,
                    targetId, targetAddr, targetKind,
                    path, lineNo, lineNo, contentSha256, "hcl-reference"));
        }
    }

    private static int countBraceDeltaClean(String s) {
        int delta = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') delta++;
            else if (c == '}') delta--;
        }
        return delta;
    }

    /** Strips HCL line comments ({@code #}, {@code //}) and multi-line block comments ({@code /* ... *\/}). */
    private static String stripCommentsStateful(String line, boolean[] inBlockComment) {
        var sb = new StringBuilder(line.length());
        boolean inStr = false;
        boolean esc = false;
        int len = line.length();
        int i = 0;
        while (i < len) {
            char c = line.charAt(i);
            if (inBlockComment[0]) {
                if (c == '*' && i + 1 < len && line.charAt(i + 1) == '/') {
                    inBlockComment[0] = false;
                    sb.append(' ');
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }
            if (inStr) {
                sb.append(c);
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
                sb.append(c);
                i++;
            } else if (c == '/' && i + 1 < len && line.charAt(i + 1) == '*') {
                inBlockComment[0] = true;
                sb.append(' ');
                i += 2;
            } else if (c == '#' || (c == '/' && i + 1 < len && line.charAt(i + 1) == '/')) {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
