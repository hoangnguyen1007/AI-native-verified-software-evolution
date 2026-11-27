package com.evolution.backend.query.repair;

import com.evolution.backend.query.GitHubProvenance;
import com.evolution.backend.query.RepairPatch;
import com.evolution.backend.query.llm.LlmProviderAdapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub-native Pull Request Review inline {@code suggestion} block formatter and Executive PR
 * Architecture Review Summary builder conforming to GitHub REST API {@code 2022-11-28}
 * ({@code path}, {@code line}, {@code start_line}, {@code side="RIGHT"}, {@code start_side="RIGHT"}).
 *
 * <p>Guarantees that clickable {@code suggestion} blocks are emitted ONLY when the entire replaced
 * line range lies within a single contiguous PR diff hunk on the {@code RIGHT} side (preventing
 * GitHub HTTP 422 Unprocessable Entity errors), and escapes nested triple-backticks using longer
 * Markdown fences (e.g., 4-backtick fences).
 */
public final class PrSuggestionFormatter {
    public static final String REVIEW_MARKER = "<!-- evo-pr-review:v1 -->";
    private static final int MAX_COMMENT_CHARS = 32_000;
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

    public record PrDiffHunk(int oldStart, int oldCount, int newStart, int newCount, Set<Integer> rightLines) {
        public PrDiffHunk {
            rightLines = Collections.unmodifiableSet(new TreeSet<>(rightLines));
        }

        public boolean coversRange(int startLine, int endLine) {
            if (startLine <= 0 || endLine < startLine) return false;
            for (int l = startLine; l <= endLine; l++) {
                if (!rightLines.contains(l)) return false;
            }
            return true;
        }
    }

    public record PrFileDiff(String path, String status, List<PrDiffHunk> hunks, Set<Integer> commentableRightLines) {
        public PrFileDiff {
            Objects.requireNonNull(path, "path");
            status = status == null ? "modified" : status;
            hunks = List.copyOf(hunks);
            commentableRightLines = Collections.unmodifiableSet(new TreeSet<>(commentableRightLines));
        }

        public boolean coversSingleHunkRange(int startLine, int endLine) {
            for (var h : hunks) {
                if (h.coversRange(startLine, endLine)) return true;
            }
            return false;
        }
    }

    public record FindingContext(
            String ruleId,
            String severity,
            String title,
            String explanation,
            String path,
            int startLine,
            int endLine,
            List<String> owners,
            Optional<String> permalink) {
        public FindingContext {
            ruleId = ruleId == null ? "architecture.rule" : ruleId;
            severity = severity == null ? "ERROR" : severity;
            title = title == null ? ruleId : title;
            explanation = explanation == null ? "" : explanation;
            path = normalizePath(path);
            owners = owners == null ? List.of() : List.copyOf(owners);
            permalink = permalink == null ? Optional.empty() : permalink;
        }
    }

    public record GitHubReviewComment(
            String path,
            int line,
            Optional<Integer> startLine,
            String side,
            Optional<String> startSide,
            String body,
            boolean hasSuggestion,
            Optional<String> suggestedCode,
            Optional<String> ruleId,
            Optional<String> severity,
            List<String> owners,
            Optional<String> permalink) {
        public GitHubReviewComment {
            path = normalizePath(path);
            if (line <= 0) throw new IllegalArgumentException("line must be >= 1");
            startLine = startLine == null ? Optional.empty() : startLine;
            side = (side == null || side.isBlank()) ? "RIGHT" : side;
            startSide = startLine.isPresent()
                    ? Optional.of((startSide == null || startSide.isEmpty()) ? side : startSide.orElseThrow())
                    : Optional.empty();
            body = body == null ? "" : body;
            suggestedCode = suggestedCode == null ? Optional.empty() : suggestedCode;
            ruleId = ruleId == null ? Optional.empty() : ruleId;
            severity = severity == null ? Optional.empty() : severity;
            owners = owners == null ? List.of() : List.copyOf(owners);
            permalink = permalink == null ? Optional.empty() : permalink;
        }

        /** Produces the exact GitHub REST API {@code 2022-11-28} review comment JSON payload map. */
        public Map<String, Object> toGitHubApiComment() {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            map.put("line", line);
            map.put("side", side);
            if (startLine.isPresent() && startLine.orElseThrow() < line) {
                map.put("start_line", startLine.orElseThrow());
                map.put("start_side", startSide.orElse(side));
            }
            map.put("body", body);
            return Collections.unmodifiableMap(map);
        }

        /** Produces an enriched workbench preview JSON view including metadata alongside GitHub comment fields. */
        public Map<String, Object> toPreviewView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("path", path);
            startLine.ifPresent(s -> map.put("startLine", s));
            map.put("line", line);
            map.put("side", side);
            startSide.ifPresent(ss -> map.put("startSide", ss));
            map.put("body", body);
            map.put("hasSuggestion", hasSuggestion);
            suggestedCode.ifPresent(sc -> map.put("suggestedCode", sc));
            ruleId.ifPresent(r -> map.put("ruleId", r));
            severity.ifPresent(sev -> map.put("severity", sev));
            map.put("owners", owners);
            permalink.ifPresent(url -> map.put("permalink", url));
            return Collections.unmodifiableMap(map);
        }
    }

    public record QualityGateSummary(String verdict, List<String> failedChecks, List<String> warnings) {
        public QualityGateSummary {
            verdict = verdict == null ? "WARN" : verdict.toUpperCase(Locale.ROOT);
            failedChecks = failedChecks == null ? List.of() : List.copyOf(failedChecks);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record ExecutivePrReview(
            String verdict,
            String markdownBody,
            List<GitHubReviewComment> inlineComments,
            List<FindingContext> outOfDiffFindings,
            Map<String, List<String>> ownersByPath) {
        public ExecutivePrReview {
            verdict = verdict == null ? "WARN" : verdict;
            markdownBody = markdownBody == null ? "" : markdownBody;
            inlineComments = inlineComments == null ? List.of() : List.copyOf(inlineComments);
            outOfDiffFindings = outOfDiffFindings == null ? List.of() : List.copyOf(outOfDiffFindings);
            var copy = new TreeMap<String, List<String>>();
            if (ownersByPath != null) {
                ownersByPath.forEach((k, v) -> copy.put(normalizePath(k), List.copyOf(v)));
            }
            ownersByPath = Collections.unmodifiableMap(copy);
        }
    }

    private PrSuggestionFormatter() {}

    /** Parses a full multi-file unified diff (`diff --git ...`) into per-file commentable PR hunks. */
    public static Map<String, PrFileDiff> parseUnifiedDiff(String unifiedDiff) {
        var result = new LinkedHashMap<String, PrFileDiff>();
        if (unifiedDiff == null || unifiedDiff.isBlank()) return Collections.unmodifiableMap(result);
        String[] lines = unifiedDiff.split("\\r?\\n", -1);
        String currentPath = null;
        String currentStatus = "modified";
        var currentPatch = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("diff --git ")) {
                if (currentPath != null) {
                    result.put(currentPath, parseFilePatch(currentPath, currentStatus, currentPatch.toString()));
                }
                currentPath = extractBPathFromDiffGit(line);
                currentStatus = "modified";
                currentPatch.setLength(0);
            } else if (line.startsWith("new file mode ")) {
                currentStatus = "added";
            } else if (line.startsWith("deleted file mode ")) {
                currentStatus = "removed";
            } else if (line.startsWith("+++ b/")) {
                currentPath = normalizePath(line.substring("+++ b/".length()));
            } else if (line.startsWith("+++ /dev/null")) {
                currentStatus = "removed";
            } else if (currentPath != null && (line.startsWith("@@ ") || !currentPatch.isEmpty())) {
                currentPatch.append(line).append('\n');
            }
        }
        if (currentPath != null) {
            result.put(currentPath, parseFilePatch(currentPath, currentStatus, currentPatch.toString()));
        }
        return Collections.unmodifiableMap(result);
    }

    /** Parses a GitHub REST API per-file {@code patch} hunk string (starting at {@code @@ -a,b +c,d @@}). */
    public static PrFileDiff parseFilePatch(String path, String status, String hunkPatch) {
        String normPath = normalizePath(path);
        if (hunkPatch == null || hunkPatch.isBlank()) {
            return new PrFileDiff(normPath, status, List.of(), Set.of());
        }
        var hunks = new ArrayList<PrDiffHunk>();
        var allRight = new TreeSet<Integer>();
        String[] lines = hunkPatch.split("\\r?\\n", -1);

        int oldStart = 0, oldCount = 0, newStart = 0, newCount = 0;
        int curNew = 0;
        TreeSet<Integer> curRight = null;

        for (String line : lines) {
            Matcher m = HUNK_HEADER.matcher(line);
            if (m.matches()) {
                if (curRight != null) {
                    hunks.add(new PrDiffHunk(oldStart, oldCount, newStart, newCount, curRight));
                    allRight.addAll(curRight);
                }
                oldStart = Integer.parseInt(m.group(1));
                oldCount = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                newStart = Integer.parseInt(m.group(3));
                newCount = m.group(4) == null ? 1 : Integer.parseInt(m.group(4));
                curNew = newStart;
                curRight = new TreeSet<>();
                continue;
            }
            if (curRight == null || line.isEmpty()) continue;
            char prefix = line.charAt(0);
            if (prefix == ' ' || prefix == '+') {
                curRight.add(curNew);
                curNew++;
            } else if (prefix == '-' || prefix == '\\') {
                // LEFT-only deletion or "\ No newline at end of file" does not advance RIGHT line number
            }
        }
        if (curRight != null) {
            hunks.add(new PrDiffHunk(oldStart, oldCount, newStart, newCount, curRight));
            allRight.addAll(curRight);
        }
        return new PrFileDiff(normPath, status, hunks, allRight);
    }

    /**
     * Wraps {@code replacementCode} in a valid GitHub {@code suggestion} Markdown block, automatically
     * using 4+ backticks whenever the replacement contains triple-backtick sequences (e.g., Java text blocks
     * or embedded Markdown fences).
     */
    public static String formatSuggestionBlock(String replacementCode) {
        String code = replacementCode == null ? "" : replacementCode.replace("\r\n", "\n");
        if (code.endsWith("\n")) {
            code = code.substring(0, code.length() - 1);
        }
        int maxRun = longestBacktickRun(code);
        String fence = "`".repeat(Math.max(3, maxRun + 1));
        if (code.isEmpty()) {
            return fence + "suggestion\n" + fence;
        }
        return fence + "suggestion\n" + code + "\n" + fence;
    }

    /**
     * Converts a verified or syntax-checked before->after file edit into a clickable GitHub inline
     * {@code suggestion} review comment when the replaced lines lie within a single contiguous PR diff hunk.
     * Falls back cleanly to a finding comment with collapsible unified diff preview when the edit spans
     * multiple hunks or falls outside the PR diff hunk.
     */
    public static Optional<GitHubReviewComment> formatEditSuggestion(
            PrFileDiff fileDiff,
            String path,
            String beforeText,
            String afterText,
            FindingContext finding,
            Optional<String> trustNote) {
        if (fileDiff == null || beforeText == null || afterText == null || beforeText.equals(afterText)) {
            return formatFindingComment(fileDiff, finding);
        }
        String normPath = normalizePath(path != null ? path : finding.path());
        var beforeLines = splitLinesPreserveEmpty(beforeText);
        var afterLines = splitLinesPreserveEmpty(afterText);

        // Trim common prefix and suffix to locate exact minimal contiguous edited span in beforeLines
        int prefix = 0;
        int minLen = Math.min(beforeLines.size(), afterLines.size());
        while (prefix < minLen && beforeLines.get(prefix).equals(afterLines.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < minLen - prefix
                && beforeLines.get(beforeLines.size() - 1 - suffix).equals(afterLines.get(afterLines.size() - 1 - suffix))) {
            suffix++;
        }

        int oldStart0 = prefix; // 0-based inclusive
        int oldEnd0 = beforeLines.size() - suffix; // 0-based exclusive
        int newStart0 = prefix;
        int newEnd0 = afterLines.size() - suffix;

        int startLine;
        int endLine;
        String replacementCode;

        if (oldEnd0 > oldStart0) {
            // Standard modification or deletion of lines [oldStart0+1 .. oldEnd0]
            startLine = oldStart0 + 1;
            endLine = oldEnd0;
            replacementCode = String.join("\n", afterLines.subList(newStart0, newEnd0));
        } else {
            // Pure insertion at index oldStart0: anchor onto adjacent line so GitHub suggestion is valid
            if (oldStart0 > 0) {
                startLine = oldStart0;
                endLine = oldStart0;
                var block = new ArrayList<String>();
                block.add(beforeLines.get(oldStart0 - 1));
                block.addAll(afterLines.subList(newStart0, newEnd0));
                replacementCode = String.join("\n", block);
            } else if (!beforeLines.isEmpty()) {
                startLine = 1;
                endLine = 1;
                var block = new ArrayList<String>(afterLines.subList(newStart0, newEnd0));
                block.add(beforeLines.getFirst());
                replacementCode = String.join("\n", block);
            } else {
                return formatFindingComment(fileDiff, finding);
            }
        }

        if (fileDiff.coversSingleHunkRange(startLine, endLine)) {
            String suggestionBlock = formatSuggestionBlock(replacementCode);
            var body = new StringBuilder();
            appendFindingHeader(body, finding, trustNote);
            body.append("\n").append(suggestionBlock);
            String finalBody = truncateComment(body.toString());
            Optional<Integer> optStart = endLine > startLine ? Optional.of(startLine) : Optional.empty();
            return Optional.of(new GitHubReviewComment(
                    normPath, endLine, optStart, "RIGHT",
                    optStart.isPresent() ? Optional.of("RIGHT") : Optional.empty(),
                    finalBody, true, Optional.of(replacementCode),
                    Optional.of(finding.ruleId()), Optional.of(finding.severity()),
                    finding.owners(), finding.permalink()));
        }

        // Edit lies outside or across multiple PR hunks -> degrade safely to anchored finding comment + diff preview
        var anchored = formatFindingComment(fileDiff, finding);
        if (anchored.isEmpty()) return Optional.empty();
        var baseComment = anchored.orElseThrow();
        String diffSnippet = "";
        try {
            diffSnippet = RepairPatch.file(normPath, beforeText, afterText);
        } catch (IllegalArgumentException ignored) {}
        if (diffSnippet.isEmpty()) return anchored;
        String fence = "`".repeat(Math.max(3, longestBacktickRun(diffSnippet) + 1));
        String enrichedBody = truncateComment(baseComment.body()
                + "\n\n<details>\n<summary>Proposed repair patch (touches lines outside this PR diff hunk)</summary>\n\n"
                + fence + "diff\n" + diffSnippet + (diffSnippet.endsWith("\n") ? "" : "\n") + fence
                + "\n</details>");
        return Optional.of(new GitHubReviewComment(
                baseComment.path(), baseComment.line(), baseComment.startLine(),
                baseComment.side(), baseComment.startSide(), enrichedBody,
                false, Optional.empty(), baseComment.ruleId(), baseComment.severity(),
                baseComment.owners(), baseComment.permalink()));
    }

    /** Anchors a pure architectural finding review comment on a commentable {@code RIGHT} PR diff line. */
    public static Optional<GitHubReviewComment> formatFindingComment(PrFileDiff fileDiff, FindingContext finding) {
        if (fileDiff == null || finding == null || fileDiff.commentableRightLines().isEmpty()) {
            return Optional.empty();
        }
        int endLine = finding.endLine() > 0 ? finding.endLine() : finding.startLine();
        int startLine = finding.startLine() > 0 ? finding.startLine() : endLine;
        if (endLine <= 0) return Optional.empty();

        Optional<Integer> resolvedStart = Optional.empty();
        int resolvedEnd;
        if (fileDiff.coversSingleHunkRange(startLine, endLine)) {
            resolvedEnd = endLine;
            if (endLine > startLine) resolvedStart = Optional.of(startLine);
        } else if (fileDiff.commentableRightLines().contains(endLine)) {
            resolvedEnd = endLine;
        } else if (fileDiff.commentableRightLines().contains(startLine)) {
            resolvedEnd = startLine;
        } else {
            return Optional.empty();
        }

        var body = new StringBuilder();
        appendFindingHeader(body, finding, Optional.empty());
        return Optional.of(new GitHubReviewComment(
                fileDiff.path(), resolvedEnd, resolvedStart, "RIGHT",
                resolvedStart.isPresent() ? Optional.of("RIGHT") : Optional.empty(),
                truncateComment(body.toString()), false, Optional.empty(),
                Optional.of(finding.ruleId()), Optional.of(finding.severity()),
                finding.owners(), finding.permalink()));
    }

    /**
     * Builds an idempotent, publication-ready Executive PR Architecture Review Summary combining
     * Quality Gate verdict, Score v2 D1–D7 delta, structural metrics delta, CODEOWNERS attribution,
     * validated GitHub permalinks, and optional advisory AI narrative strictly grounded in pinned facts.
     */
    public static ExecutivePrReview buildExecutiveReview(
            String owner,
            String repo,
            int pullNumber,
            String headSha,
            String baseRef,
            String headRef,
            QualityGateSummary qualityGate,
            Optional<RepairVerificationReport.ScoreDelta> scoreDelta,
            Optional<RepairVerificationReport.StructuralDelta> structuralDelta,
            List<FindingContext> introducedFindings,
            List<GitHubReviewComment> inlineComments,
            Map<String, List<String>> ownersByPath,
            String trustLabel,
            Optional<LlmProviderAdapter> optionalNarrator,
            BooleanSupplier cancelled) {
        var gate = qualityGate == null ? new QualityGateSummary("WARN", List.of(), List.of()) : qualityGate;
        var findings = introducedFindings == null ? List.<FindingContext>of() : List.copyOf(introducedFindings);
        var comments = inlineComments == null ? List.<GitHubReviewComment>of() : List.copyOf(inlineComments);
        var ownersMap = ownersByPath == null ? Map.<String, List<String>>of() : ownersByPath;
        String effectiveTrust = (trustLabel == null || trustLabel.isBlank()) ? "PASSIVE_STATIC_ANALYSIS" : trustLabel;

        // Partition introduced findings into those already anchored inline vs out-of-diff
        var anchoredKeys = new HashSet<String>();
        for (var c : comments) {
            anchoredKeys.add(c.path() + ":" + c.ruleId().orElse("") + ":" + c.line());
        }
        var outOfDiff = new ArrayList<FindingContext>();
        for (var f : findings) {
            String keyEnd = f.path() + ":" + f.ruleId() + ":" + f.endLine();
            String keyStart = f.path() + ":" + f.ruleId() + ":" + f.startLine();
            if (!anchoredKeys.contains(keyEnd) && !anchoredKeys.contains(keyStart)) {
                outOfDiff.add(f);
            }
        }

        String gateIcon = switch (gate.verdict()) {
            case "PASSED", "PASS" -> "PASS";
            case "FAILED", "FAIL" -> "FAIL";
            default -> "WARN";
        };

        var md = new StringBuilder();
        md.append(REVIEW_MARKER).append('\n');
        md.append("## Evo Architecture PR Review — `").append(owner).append('/').append(repo)
                .append("` #").append(pullNumber).append('\n');
        md.append("**Quality Gate:** `").append(gateIcon).append("` | ")
                .append("**Branch:** `").append(safeMd(headRef)).append("` -> `").append(safeMd(baseRef)).append("` | ")
                .append("**Commit:** `").append(shortSha(headSha)).append("` | ")
                .append("**Trust:** `").append(effectiveTrust).append("`\n\n");

        if (!gate.failedChecks().isEmpty() || !gate.warnings().isEmpty()) {
            md.append("### Quality Gate Checks\n");
            for (String fail : gate.failedChecks()) {
                md.append("- [FAILED] ").append(fail).append('\n');
            }
            for (String warn : gate.warnings()) {
                md.append("- [WARN] ").append(warn).append('\n');
            }
            md.append('\n');
        }

        if (scoreDelta != null && scoreDelta.isPresent()) {
            var sd = scoreDelta.orElseThrow();
            md.append("### Architecture Health Score v2\n");
            md.append("| Metric | Base | PR Head | Delta |\n");
            md.append("| --- | ---: | ---: | ---: |\n");
            md.append("| **Composite Score** | ").append(fmtScore(sd.beforeScore()))
                    .append(" (").append(sd.beforeBand()).append(") | ")
                    .append(fmtScore(sd.afterScore())).append(" (").append(sd.afterBand()).append(") | ")
                    .append(fmtDelta(sd.delta())).append(" |\n");
            md.append("| **Confidence v2** | ").append(sd.beforeConfidence()).append(" (").append(sd.beforeConfidenceTier())
                    .append(") | ").append(sd.afterConfidence()).append(" (").append(sd.afterConfidenceTier())
                    .append(") | ").append(fmtDelta(Optional.of(sd.afterConfidence().subtract(sd.beforeConfidence())))).append(" |\n");
            for (var dim : sd.dimensions()) {
                md.append("| ").append(dim.id()).append(' ').append(dim.displayName()).append(" | ")
                        .append(fmtScore(dim.beforeScore())).append(" | ")
                        .append(fmtScore(dim.afterScore())).append(" | ")
                        .append(fmtDelta(dim.delta())).append(" |\n");
            }
            md.append('\n');
        }

        if (structuralDelta != null && structuralDelta.isPresent()) {
            var st = structuralDelta.orElseThrow();
            md.append("### Structural Metrics Delta\n");
            md.append("| Metric | Base | PR Head | Delta |\n");
            md.append("| --- | ---: | ---: | ---: |\n");
            md.append("| Dependency Tangles (Cycles) | ").append(st.beforeTangleCount()).append(" | ")
                    .append(st.afterTangleCount()).append(" | ").append(fmtIntDelta(st.afterTangleCount() - st.beforeTangleCount())).append(" |\n");
            md.append("| Max Blast Radius | ").append(st.beforeMaxBlastRadius()).append(" | ")
                    .append(st.afterMaxBlastRadius()).append(" | ").append(fmtIntDelta(st.afterMaxBlastRadius() - st.beforeMaxBlastRadius())).append(" |\n");
            md.append("| SDP Violations | ").append(st.beforeSdpViolations()).append(" | ")
                    .append(st.afterSdpViolations()).append(" | ").append(fmtIntDelta(st.afterSdpViolations() - st.beforeSdpViolations())).append(" |\n\n");
        }

        long suggestionCount = comments.stream().filter(GitHubReviewComment::hasSuggestion).count();
        md.append("### Findings & Inline Suggestions\n");
        md.append("- Introduced violations: **").append(findings.size()).append("**\n");
        md.append("- Inline diff review comments: **").append(comments.size()).append("** (")
                .append(suggestionCount).append(" clickable GitHub `suggestion` block")
                .append(suggestionCount == 1 ? "" : "s").append(")\n\n");

        if (!findings.isEmpty()) {
            md.append("| Severity | Rule | Location | Owners |\n");
            md.append("| --- | --- | --- | --- |\n");
            for (var f : findings.stream().limit(20).toList()) {
                String locText = f.path() + (f.startLine() > 0 ? (":" + f.startLine()) : "");
                Optional<String> link = f.permalink().or(() -> GitHubProvenance.blobPermalink(
                        null, owner, repo, headSha, f.path(),
                        f.startLine() > 0 ? f.startLine() : null,
                        f.endLine() > f.startLine() ? f.endLine() : null));
                String locMd = link.map(u -> "[`" + locText + "`](" + u + ")").orElse("`" + locText + "`");
                List<String> effOwners = !f.owners().isEmpty() ? f.owners() : ownersMap.getOrDefault(f.path(), List.of());
                String ownerStr = effOwners.isEmpty() ? "—" : String.join(", ", effOwners);
                md.append("| `").append(f.severity()).append("` | `").append(f.ruleId()).append("` | ")
                        .append(locMd).append(" | ").append(ownerStr).append(" |\n");
            }
            if (findings.size() > 20) {
                md.append("\n_...and ").append(findings.size() - 20).append(" additional finding(s)._\n");
            }
            md.append('\n');
        }

        if (!ownersMap.isEmpty()) {
            var distinctOwners = new TreeSet<String>();
            ownersMap.values().forEach(distinctOwners::addAll);
            if (!distinctOwners.isEmpty()) {
                md.append("### CODEOWNERS Attribution\n");
                md.append("Affected owners: ").append(String.join(", ", distinctOwners)).append("\n\n");
            }
        }

        if (optionalNarrator != null && optionalNarrator.isPresent()
                && (cancelled == null || !cancelled.getAsBoolean())) {
            generateAdvisoryNarrative(optionalNarrator.orElseThrow(), md.toString(), cancelled)
                    .ifPresent(note -> md.append("### Advisory AI Summary\n")
                            .append("_AI narrative grounded strictly in pinned metrics above; never overrides canonical verdicts._\n\n")
                            .append(note).append("\n\n"));
        }

        return new ExecutivePrReview(gate.verdict(), truncateComment(md.toString()), comments, outOfDiff, ownersMap);
    }

    private static Optional<String> generateAdvisoryNarrative(LlmProviderAdapter adapter, String pinnedMarkdown,
                                                              BooleanSupplier cancelled) {
        try {
            var req = new LlmProviderAdapter.ChatRequest(
                    List.of(
                            Map.of("role", "system", "content",
                                    "You are a concise software architecture PR reviewer. Summarize the top 2-3 actionable architectural "
                                            + "risks and remediation steps in <= 120 words using ONLY the pinned facts provided. "
                                            + "Never invent files, scores, or rules."),
                            Map.of("role", "user", "content", pinnedMarkdown)),
                    List.of(), Optional.empty(), false, Optional.of(0.1), OptionalInt.of(256), false);
            var resp = adapter.complete(req, cancelled == null ? () -> false : cancelled);
            String text = resp.content() == null ? "" : resp.content().strip();
            if (text.isEmpty()) return Optional.empty();
            return Optional.of(text.length() > 1500 ? text.substring(0, 1500) + "..." : text);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private static void appendFindingHeader(StringBuilder body, FindingContext finding, Optional<String> trustNote) {
        body.append("**[Evo ").append(finding.severity()).append("] `").append(finding.ruleId()).append("`**");
        if (!finding.title().equals(finding.ruleId())) {
            body.append(" — ").append(finding.title());
        }
        body.append('\n');
        if (!finding.explanation().isBlank()) {
            body.append(finding.explanation().strip()).append('\n');
        }
        var meta = new ArrayList<String>();
        if (!finding.owners().isEmpty()) {
            meta.add("Owners: " + String.join(", ", finding.owners()));
        }
        finding.permalink().ifPresent(url -> meta.add("[View on GitHub](" + url + ")"));
        trustNote.filter(s -> !s.isBlank()).ifPresent(t -> meta.add("Trust: `" + t + "`"));
        if (!meta.isEmpty()) {
            body.append("_").append(String.join(" · ", meta)).append("_\n");
        }
    }

    private static int longestBacktickRun(String text) {
        int max = 0, cur = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '`') {
                cur++;
                if (cur > max) max = cur;
            } else {
                cur = 0;
            }
        }
        return max;
    }

    private static List<String> splitLinesPreserveEmpty(String text) {
        String norm = text.replace("\r\n", "\n");
        if (norm.endsWith("\n")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        if (norm.isEmpty()) return List.of();
        return Arrays.asList(norm.split("\n", -1));
    }

    private static String extractBPathFromDiffGit(String line) {
        int bIdx = line.lastIndexOf(" b/");
        if (bIdx > 0) return normalizePath(line.substring(bIdx + 3));
        return "";
    }

    private static String normalizePath(String raw) {
        if (raw == null) return "";
        String p = raw.strip().replace('\\', '/');
        while (p.startsWith("./")) p = p.substring(2);
        while (p.startsWith("/")) p = p.substring(1);
        return p;
    }

    private static String truncateComment(String s) {
        if (s.length() <= MAX_COMMENT_CHARS) return s;
        return s.substring(0, MAX_COMMENT_CHARS - 64) + "\n\n_(Truncated to stay within GitHub comment size limits.)_";
    }

    private static String fmtScore(Optional<BigDecimal> v) {
        return v.map(b -> b.setScale(2, RoundingMode.HALF_EVEN).toPlainString()).orElse("N/A");
    }

    private static String fmtDelta(Optional<BigDecimal> v) {
        if (v.isEmpty()) return "—";
        BigDecimal d = v.orElseThrow().setScale(2, RoundingMode.HALF_EVEN);
        return (d.signum() > 0 ? "+" : "") + d.toPlainString();
    }

    private static String fmtIntDelta(int d) {
        return (d > 0 ? "+" : "") + d;
    }

    private static String shortSha(String sha) {
        if (sha == null || sha.isBlank()) return "unknown";
        return sha.length() > 10 ? sha.substring(0, 10) : sha;
    }

    private static String safeMd(String s) {
        return s == null ? "" : s.replace("`", "'");
    }
}
