package com.evolution.analysis.execution.scip;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.semantic.TrustTier;

import java.util.*;

/**
 * Pure-Java SCIP semantic capture adapter ({@code I1–I4} compliant).
 *
 * <p><strong>Hard Sandbox Rule ({@code I1}):</strong> NEVER invokes {@code scip-typescript},
 * {@code scip-python}, {@code scip-go}, {@code scip-java}, or any external indexer on the host.
 * Only ingests verified {@code index.scip} byte payloads exported from an isolated worker or CI capture.
 *
 * <p>Performs SHA-256 artifact lineage verification, two-pass deterministic merge with a
 * single-definition guard, {@link TrustTier#SOURCE_SYNTAX} &rarr; {@link TrustTier#COMPILER}
 * upgrade tracking, and closed-denominator {@link ScipCoverageGap} reporting.
 */
public final class ScipSemanticCaptureAdapter {

    private static final int SAMPLE_VIEW_LIMIT = 25;

    public record ScipIngestRequest(
            byte[] scipBytes,
            Optional<ContentDigest> expectedSha256,
            Optional<String> sourceLabel
    ) {
        public ScipIngestRequest {
            Objects.requireNonNull(scipBytes, "scipBytes");
            expectedSha256 = expectedSha256 == null ? Optional.empty() : expectedSha256;
            sourceLabel = sourceLabel == null
                    ? Optional.empty()
                    : sourceLabel.map(String::strip).filter(s -> !s.isEmpty());
        }
    }

    public record ScipSymbolUpgrade(
            String symbol,
            String displayName,
            String language,
            String relativePath,
            int startLine,
            int endLine,
            TrustTier previousTier,
            TrustTier trustTier,
            boolean boundCallTargets,
            int referenceCount,
            List<String> relationshipTargets
    ) {
        public ScipSymbolUpgrade {
            Objects.requireNonNull(symbol, "symbol");
            displayName = displayName == null ? "" : displayName;
            language = language == null ? "" : language;
            relativePath = relativePath == null ? "" : relativePath;
            Objects.requireNonNull(previousTier, "previousTier");
            Objects.requireNonNull(trustTier, "trustTier");
            relationshipTargets = relationshipTargets == null ? List.of() : List.copyOf(relationshipTargets);
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("symbol", symbol);
            m.put("displayName", displayName);
            m.put("language", language);
            m.put("relativePath", relativePath);
            m.put("startLine", startLine);
            m.put("endLine", endLine);
            m.put("previousTier", previousTier.name());
            m.put("trustTier", trustTier.name());
            m.put("boundCallTargets", boundCallTargets);
            m.put("referenceCount", referenceCount);
            m.put("relationshipTargets", relationshipTargets);
            return Collections.unmodifiableMap(m);
        }
    }

    public record ScipCoverageGap(
            String reasonCode,
            String symbolOrPath,
            String detail
    ) {
        public ScipCoverageGap {
            Objects.requireNonNull(reasonCode, "reasonCode");
            Objects.requireNonNull(symbolOrPath, "symbolOrPath");
            detail = detail == null ? "" : detail;
        }

        public Map<String, Object> toView() {
            var m = new TreeMap<String, Object>();
            m.put("reasonCode", reasonCode);
            m.put("symbolOrPath", symbolOrPath);
            m.put("detail", detail);
            return Collections.unmodifiableMap(m);
        }
    }

    public record ScipIngestResult(
            ContentDigest artifactSha256,
            String toolName,
            String toolVersion,
            int documentCount,
            int symbolCount,
            int upgradedToCompilerCount,
            int occurrenceCount,
            int ambiguousDefinitionCount,
            List<ScipSymbolUpgrade> symbols,
            List<ScipCoverageGap> coverageGaps,
            String trustTier
    ) {
        public ScipIngestResult {
            Objects.requireNonNull(artifactSha256, "artifactSha256");
            toolName = toolName == null ? "" : toolName;
            toolVersion = toolVersion == null ? "" : toolVersion;
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
            coverageGaps = coverageGaps == null ? List.of() : List.copyOf(coverageGaps);
            trustTier = trustTier == null ? TrustTier.COMPILER.name() : trustTier;
        }

        /** JSON-safe canonical view map with exact contract keys. */
        public Map<String, Object> toView() {
            var view = new TreeMap<String, Object>();
            view.put("artifactSha256", artifactSha256.value());
            view.put("toolName", toolName);
            view.put("toolVersion", toolVersion);
            view.put("documentCount", documentCount);
            view.put("symbolCount", symbolCount);
            view.put("upgradedToCompilerCount", upgradedToCompilerCount);
            view.put("occurrenceCount", occurrenceCount);
            view.put("ambiguousDefinitionCount", ambiguousDefinitionCount);
            view.put("coverageGaps", coverageGaps.stream().map(ScipCoverageGap::toView).toList());
            view.put("samples", symbols.stream()
                    .limit(SAMPLE_VIEW_LIMIT)
                    .map(ScipSymbolUpgrade::toView)
                    .toList());
            view.put("trustTier", trustTier);
            return Collections.unmodifiableMap(view);
        }
    }

    private ScipSemanticCaptureAdapter() {}

    /**
     * Verifies and ingests an isolated-worker {@code index.scip} byte payload using a
     * deterministic two-pass merge and single-definition guard.
     */
    public static ScipIngestResult ingest(ScipIngestRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] bytes = request.scipBytes();

        // 1. Cryptographic SHA-256 lineage verification before decoding
        ContentDigest actualSha256 = ContentDigest.sha256(bytes);
        if (request.expectedSha256().isPresent()) {
            ContentDigest expected = request.expectedSha256().get();
            if (!expected.equals(actualSha256)) {
                throw new IllegalArgumentException(
                        "SCIP_SHA256_MISMATCH: expected " + expected.value()
                                + ", actual " + actualSha256.value());
            }
        }

        // 2. Decode binary SCIP Index (rejects empty/corrupt/truncated/oversized payloads)
        ScipProtobufReader.ScipIndex index = ScipProtobufReader.read(bytes);

        String toolName = index.metadata().toolInfo().name();
        String toolVersion = index.metadata().toolInfo().version();
        List<ScipProtobufReader.Document> documents = index.documents();

        // 3. Pass 1: Definitions + Single-Definition Guard
        record DefLocation(
                String relativePath,
                int startLine,
                int endLine,
                String language,
                String displayName,
                TreeSet<String> relationshipTargets
        ) {}

        var candidatesBySymbol = new TreeMap<String, List<DefLocation>>();
        int totalOccurrences = 0;

        for (ScipProtobufReader.Document doc : documents) {
            String relPath = doc.relativePath();
            String lang = doc.language();

            // Coalesce SymbolInformation metadata per symbol inside this document
            var docMetaDisplay = new HashMap<String, String>();
            var docMetaRels = new HashMap<String, TreeSet<String>>();
            var declaredSymbolsInDoc = new LinkedHashSet<String>();
            for (ScipProtobufReader.SymbolInformation info : doc.symbols()) {
                String sym = info.symbol();
                if (sym == null || sym.isBlank()) continue;
                declaredSymbolsInDoc.add(sym);
                if (!info.displayName().isBlank()) {
                    docMetaDisplay.putIfAbsent(sym, info.displayName());
                }
                var relSet = docMetaRels.computeIfAbsent(sym, k -> new TreeSet<>());
                for (ScipProtobufReader.Relationship rel : info.relationships()) {
                    if (rel.symbol() != null && !rel.symbol().isBlank()) {
                        relSet.add(rel.symbol());
                    }
                }
            }

            // Collect Definition occurrences inside this document
            var defLocationsInDoc = new HashMap<String, LinkedHashSet<LineSpan>>();
            for (ScipProtobufReader.Occurrence occ : doc.occurrences()) {
                totalOccurrences++;
                String sym = occ.symbol();
                if (sym == null || sym.isBlank()) continue;
                if ((occ.symbolRoles() & ScipProtobufReader.ROLE_DEFINITION) != 0) {
                    LineSpan span = extractLineSpan(occ.range());
                    defLocationsInDoc.computeIfAbsent(sym, k -> new LinkedHashSet<>()).add(span);
                }
            }

            // Emit definition candidates coalesced per (symbol, relativePath, startLine)
            var allDocDefined = new LinkedHashSet<String>(defLocationsInDoc.keySet());
            allDocDefined.addAll(declaredSymbolsInDoc);
            for (String sym : allDocDefined) {
                String display = docMetaDisplay.getOrDefault(sym, "");
                TreeSet<String> rels = docMetaRels.getOrDefault(sym, new TreeSet<>());
                var spans = defLocationsInDoc.get(sym);
                if (spans == null || spans.isEmpty()) {
                    candidatesBySymbol.computeIfAbsent(sym, k -> new ArrayList<>())
                            .add(new DefLocation(relPath, 0, 0, lang, display, new TreeSet<>(rels)));
                } else {
                    for (LineSpan span : spans) {
                        candidatesBySymbol.computeIfAbsent(sym, k -> new ArrayList<>())
                                .add(new DefLocation(relPath, span.startLine(), span.endLine(), lang, display, new TreeSet<>(rels)));
                    }
                }
            }
        }

        // Apply Single-Definition Guard deterministically keeping lexicographically first (relativePath, startLine)
        var canonicalDefs = new TreeMap<String, DefLocation>();
        var gaps = new ArrayList<ScipCoverageGap>();
        int ambiguousDefinitionCount = 0;

        for (var entry : candidatesBySymbol.entrySet()) {
            String sym = entry.getKey();
            var sorted = new ArrayList<>(entry.getValue());
            sorted.sort(Comparator
                    .comparing(DefLocation::relativePath)
                    .thenComparingInt(DefLocation::startLine)
                    .thenComparingInt(DefLocation::endLine)
                    .thenComparing(DefLocation::language));

            // Coalesce identical (relativePath, startLine) entries
            var distinct = new ArrayList<DefLocation>(sorted.size());
            for (DefLocation loc : sorted) {
                if (!distinct.isEmpty()) {
                    DefLocation prev = distinct.getLast();
                    if (prev.relativePath().equals(loc.relativePath()) && prev.startLine() == loc.startLine()) {
                        String mergedDisplay = !prev.displayName().isBlank() ? prev.displayName() : loc.displayName();
                        String mergedLang = !prev.language().isBlank() ? prev.language() : loc.language();
                        var mergedRels = new TreeSet<>(prev.relationshipTargets());
                        mergedRels.addAll(loc.relationshipTargets());
                        distinct.set(distinct.size() - 1, new DefLocation(
                                prev.relativePath(),
                                prev.startLine(),
                                Math.max(prev.endLine(), loc.endLine()),
                                mergedLang,
                                mergedDisplay,
                                mergedRels));
                        continue;
                    }
                }
                distinct.add(loc);
            }

            DefLocation kept = distinct.getFirst();
            canonicalDefs.put(sym, kept);

            for (int i = 1; i < distinct.size(); i++) {
                DefLocation conflict = distinct.get(i);
                ambiguousDefinitionCount++;
                gaps.add(new ScipCoverageGap(
                        "AMBIGUOUS_MULTIPLE_DEFINITIONS",
                        sym,
                        "Incompatible duplicate definition at "
                                + formatLocation(conflict.relativePath(), conflict.startLine())
                                + "; kept " + formatLocation(kept.relativePath(), kept.startLine())));
            }
        }

        // 4. Pass 2: References, Relationships & External Unresolved Gap Accounting
        var referenceCounts = new HashMap<String, Integer>(Math.max(16, canonicalDefs.size() * 2));
        var unresolvedExternals = new TreeMap<String, String>();

        for (ScipProtobufReader.Document doc : documents) {
            for (ScipProtobufReader.Occurrence occ : doc.occurrences()) {
                String sym = occ.symbol();
                if (sym == null || sym.isBlank()) continue;
                if ((occ.symbolRoles() & ScipProtobufReader.ROLE_DEFINITION) == 0) {
                    if (canonicalDefs.containsKey(sym)) {
                        referenceCounts.merge(sym, 1, Integer::sum);
                    } else {
                        unresolvedExternals.putIfAbsent(
                                sym,
                                "Referenced in " + (doc.relativePath().isBlank() ? "<unknown>" : doc.relativePath())
                                        + " without project definition");
                    }
                }
            }
        }

        for (var entry : canonicalDefs.entrySet()) {
            String sym = entry.getKey();
            for (String relTarget : entry.getValue().relationshipTargets()) {
                if (!canonicalDefs.containsKey(relTarget)) {
                    unresolvedExternals.putIfAbsent(
                            relTarget,
                            "Relationship target of " + sym + " without project definition");
                }
            }
        }

        for (var ext : unresolvedExternals.entrySet()) {
            gaps.add(new ScipCoverageGap("UNRESOLVED_EXTERNAL_SYMBOL", ext.getKey(), ext.getValue()));
        }

        var upgrades = new ArrayList<ScipSymbolUpgrade>(canonicalDefs.size());
        for (var entry : canonicalDefs.entrySet()) {
            String sym = entry.getKey();
            DefLocation def = entry.getValue();
            String displayName = !def.displayName().isBlank() ? def.displayName() : deriveDisplayName(sym);
            upgrades.add(new ScipSymbolUpgrade(
                    sym,
                    displayName,
                    def.language(),
                    def.relativePath(),
                    def.startLine(),
                    def.endLine(),
                    TrustTier.SOURCE_SYNTAX,
                    TrustTier.COMPILER,
                    true,
                    referenceCounts.getOrDefault(sym, 0),
                    List.copyOf(def.relationshipTargets())));
        }

        gaps.sort(Comparator
                .comparing(ScipCoverageGap::reasonCode)
                .thenComparing(ScipCoverageGap::symbolOrPath)
                .thenComparing(ScipCoverageGap::detail));

        return new ScipIngestResult(
                actualSha256,
                toolName,
                toolVersion,
                documents.size(),
                upgrades.size(),
                upgrades.size(),
                totalOccurrences,
                ambiguousDefinitionCount,
                upgrades,
                gaps,
                TrustTier.COMPILER.name());
    }

    private record LineSpan(int startLine, int endLine) {}

    private static LineSpan extractLineSpan(List<Integer> range) {
        if (range == null || range.isEmpty()) {
            return new LineSpan(0, 0);
        }
        // SCIP Occurrence.range uses 0-based line indices: [startLine, startChar, endChar]
        // or [startLine, startChar, endLine, endChar]. Normalize to 1-based lines.
        int start = Math.max(0, range.get(0)) + 1;
        int end = range.size() >= 4 ? Math.max(start, Math.max(0, range.get(2)) + 1) : start;
        return new LineSpan(start, end);
    }

    private static String formatLocation(String path, int line) {
        String p = (path == null || path.isBlank()) ? "<unknown>" : path;
        return line > 0 ? p + ":" + line : p;
    }

    private static String deriveDisplayName(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return "";
        }
        String trimmed = symbol.strip();
        while (trimmed.endsWith("#") || trimmed.endsWith(".") || trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("()")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        int slash = trimmed.lastIndexOf('/');
        int hash = trimmed.lastIndexOf('#');
        int dot = trimmed.lastIndexOf('.');
        int cut = Math.max(slash, Math.max(hash, dot));
        return cut >= 0 && cut + 1 < trimmed.length() ? trimmed.substring(cut + 1) : trimmed;
    }
}
