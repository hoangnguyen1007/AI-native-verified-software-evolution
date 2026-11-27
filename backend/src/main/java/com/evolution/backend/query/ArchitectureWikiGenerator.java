package com.evolution.backend.query;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.assessment.v2.LeidenCommunityDetector;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.evidence.CapabilityGapRecord;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.query.ArchitectureQuery.*;
import com.evolution.analysis.query.ArchitectureQueryService;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deterministic multi-page Architecture Wiki &amp; Dossier generator for the Web App and CLI.
 *
 * <p>Produces agent-crawlable Markdown pages backed strictly by canonical {@link KnowledgeGraph}
 * facts, Architecture Health Score v2, Leiden communities/drift, Tarjan tangles, Spring conditional
 * regions, capability-gap obligations, and quarantined {@code advisory.doc-link:v1} summaries.
 */
public final class ArchitectureWikiGenerator {
    public static final String SCHEMA = "architecture.wiki-dossier:v1";
    private static final int MAX_COMMUNITY_PAGES = 40;

    public record WikiPage(String path, String title, String category, String sha256, String content) {
        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("path", path);
            m.put("title", title);
            m.put("category", category);
            m.put("sha256", sha256);
            m.put("bytes", content.getBytes(StandardCharsets.UTF_8).length);
            m.put("content", content);
            return Collections.unmodifiableMap(m);
        }
    }

    public record WikiBundle(
            String schema,
            String graphIdentity,
            String context,
            String bundleSha256,
            int totalCommunities,
            int renderedCommunityPages,
            boolean communitiesTruncated,
            List<WikiPage> pages,
            String combinedMarkdown) {
        public WikiBundle {
            pages = List.copyOf(pages);
        }

        public Map<String, Object> toView() {
            var m = new LinkedHashMap<String, Object>();
            m.put("schema", schema);
            m.put("graphIdentity", graphIdentity);
            m.put("context", context);
            m.put("bundleSha256", bundleSha256);
            m.put("pageCount", pages.size());
            m.put("totalCommunities", totalCommunities);
            m.put("renderedCommunityPages", renderedCommunityPages);
            m.put("communitiesTruncated", communitiesTruncated);
            m.put("pages", pages.stream().map(WikiPage::toView).toList());
            return Collections.unmodifiableMap(m);
        }
    }

    private ArchitectureWikiGenerator() {}

    public static WikiBundle generate(
            ArchitectureQueryService queries,
            String requestedContext,
            PublishedReports reports) {
        Objects.requireNonNull(queries, "queries");
        KnowledgeGraph graph = queries.graph();
        String ctx = resolveTargetContext(graph, requestedContext);

        // 1. Architecture Health Score v2 (wired with published custom policy reports when present)
        var scorer = new ArchitectureAssessmentV2Service();
        ArchitectureAssessmentV2.Result score;
        if (ctx.equals(graph.snapshot().value())) {
            var customByContext = new LinkedHashMap<String, PolicyEvaluation.Result>();
            Optional<ContentDigest> firstCustomDigest = Optional.empty();
            if (reports != null) {
                for (var rid : reports.forGraph(graph.identity())) {
                    var opt = reports.get(rid);
                    if (opt.isEmpty()) continue;
                    var eval = opt.orElseThrow().evaluation();
                    if (isCustomPolicy(eval)) {
                        customByContext.putIfAbsent(eval.context(), eval);
                        if (firstCustomDigest.isEmpty()) firstCustomDigest = Optional.of(rid);
                    }
                }
            }
            score = scorer.assessRepository(queries, customByContext, firstCustomDigest, ArchitectureScoreModelV2.standard());
        } else {
            Optional<PolicyEvaluation.Result> customPolicy = Optional.empty();
            Optional<ContentDigest> customDigest = Optional.empty();
            if (reports != null) {
                for (var rid : reports.forGraph(graph.identity())) {
                    var opt = reports.get(rid);
                    if (opt.isEmpty()) continue;
                    var eval = opt.orElseThrow().evaluation();
                    if (eval.context().equals(ctx) && isCustomPolicy(eval)) {
                        customPolicy = Optional.of(eval);
                        customDigest = Optional.of(rid);
                        break;
                    }
                }
            }
            score = scorer.assess(queries, ctx, customPolicy, customDigest, ArchitectureScoreModelV2.standard());
        }

        // 2. Insights across ALL modules when whole-repository context is requested, or the specific requested context
        List<String> targetContexts;
        if (ctx.equals(graph.snapshot().value())) {
            var allAnalysis = graph.contexts().stream()
                    .filter(c -> c.analysis().isPresent())
                    .filter(c -> !isSpringOnlyContext(graph, c))
                    .sorted(Comparator.<KnowledgeGraph.Context>comparingInt(c -> graph.nodes(c.identity()).size()).reversed()
                            .thenComparing(KnowledgeGraph.Context::identity))
                    .map(KnowledgeGraph.Context::identity)
                    .toList();
            targetContexts = allAnalysis.isEmpty() ? List.of(ctx) : allAnalysis;
        } else {
            targetContexts = List.of(ctx);
        }

        var moduleReports = new ArrayList<ModuleInsightsSlice>();
        var allCommunities = new ArrayList<LeidenCommunityDetector.EmergentCommunity>();
        var allMisplaced = new ArrayList<LeidenCommunityDetector.MisplacedType>();
        var allBridges = new ArrayList<LeidenCommunityDetector.CommunityBridge>();
        var allTangles = new ArrayList<ArchitectureInsightsAdvisor.Tangle>();
        var allHotspots = new ArrayList<ArchitectureInsightsAdvisor.Hotspot>();
        var combinedInsightsMarkdown = new StringBuilder();
        double weightedModNum = 0.0;
        int weightedModDen = 0;

        boolean multiModule = targetContexts.size() > 1;
        var usedModSlugs = new HashSet<String>();
        for (String targetCtx : targetContexts) {
            var insightsReq = new Request(
                    graph.identity(), targetCtx, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                    Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            Optional<PublishedReports.Report> matchedReport = findReportForContext(reports, graph.identity(), targetCtx);
            ArchitectureInsightsAdvisor.InsightsReport rep;
            try {
                rep = ArchitectureInsightsAdvisor.analyze(queries, insightsReq, matchedReport, 20);
            } catch (RuntimeException skipped) {
                continue;
            }
            if (multiModule && rep.summary().analyzedEntities() == 0 && !moduleReports.isEmpty()) {
                continue;
            }
            String modLabel = resolveContextLabel(graph, targetCtx);
            moduleReports.add(new ModuleInsightsSlice(targetCtx, modLabel, rep));

            if (multiModule) {
                // Prefix community IDs with unique module slug so communities from distinct modules/source-sets never collide
                String baseSlug = slugify(modLabel);
                if (baseSlug.isEmpty()) baseSlug = "mod" + moduleReports.size();
                String modSlug = baseSlug;
                int suffix = 2;
                while (!usedModSlugs.add(modSlug)) modSlug = baseSlug + "-" + (suffix++);
                var idRemap = new HashMap<String, String>();
                for (var c : rep.communities()) {
                    String scopedId = modSlug + ":" + c.id();
                    idRemap.put(c.id(), scopedId);
                    allCommunities.add(new LeidenCommunityDetector.EmergentCommunity(
                            scopedId,
                            c.memberSetDigest(),
                            modLabel + " · " + c.label(),
                            c.dominantPackage(),
                            c.hubIdentity(),
                            c.hubLabel(),
                            c.memberCount(),
                            c.internalEdgeCount(),
                            c.externalEdgeCount(),
                            c.cohesion(),
                            c.misplacedCount(),
                            c.bridgeEdgeCount(),
                            c.members()));
                }
                for (var m : rep.misplacedTypes()) {
                    allMisplaced.add(new LeidenCommunityDetector.MisplacedType(
                            m.identity(), m.label(), m.declaredPackage(),
                            idRemap.getOrDefault(m.communityId(), modSlug + ":" + m.communityId()),
                            modLabel + " · " + m.communityLabel(),
                            m.dominantPackage(), m.internalCommunityEdges(), m.declaredPackageEdges(), m.rationale()));
                }
                for (var b : rep.communityBridges()) {
                    allBridges.add(new LeidenCommunityDetector.CommunityBridge(
                            idRemap.getOrDefault(b.fromCommunityId(), modSlug + ":" + b.fromCommunityId()),
                            modLabel + " · " + b.fromCommunityLabel(),
                            idRemap.getOrDefault(b.toCommunityId(), modSlug + ":" + b.toCommunityId()),
                            modLabel + " · " + b.toCommunityLabel(),
                            b.edgeCount(), b.totalWeight(), b.sampleEdges()));
                }
            } else {
                allCommunities.addAll(rep.communities());
                allMisplaced.addAll(rep.misplacedTypes());
                allBridges.addAll(rep.communityBridges());
            }
            allTangles.addAll(rep.tangles());
            allHotspots.addAll(rep.hotspots());
            if (rep.summary().analyzedEntities() > 0) {
                weightedModNum += rep.communityModularity().doubleValue() * rep.summary().analyzedEntities();
                weightedModDen += rep.summary().analyzedEntities();
            }
            if (combinedInsightsMarkdown.length() > 0) {
                combinedInsightsMarkdown.append("\n\n---\n\n");
            }
            if (multiModule) {
                combinedInsightsMarkdown.append("## Module: ").append(modLabel).append(" (`").append(targetCtx).append("`)\n\n");
            }
            combinedInsightsMarkdown.append(rep.markdownReport());
        }

        // Deterministic sort across merged multi-module artifacts
        allCommunities.sort(Comparator.comparingInt(LeidenCommunityDetector.EmergentCommunity::memberCount).reversed()
                .thenComparing(LeidenCommunityDetector.EmergentCommunity::id));
        allHotspots.sort(Comparator.comparing(ArchitectureInsightsAdvisor.Hotspot::hotspotScore).reversed()
                .thenComparing(ArchitectureInsightsAdvisor.Hotspot::label));
        BigDecimal aggregateModularity = weightedModDen == 0
                ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(weightedModNum / weightedModDen).setScale(4, RoundingMode.HALF_UP);

        var memberDisplayIndex = buildMemberDisplayIndex(graph);

        // 3. Spring components & realizations across ALL relevant contexts
        var springEntityList = new LinkedHashMap<String, EntityView>();
        var realizationList = new LinkedHashMap<String, RealizationView>();
        var springContexts = new LinkedHashSet<String>();
        if (ctx.equals(graph.snapshot().value())) {
            for (var c : graph.contexts()) {
                if (c.analysis().isPresent()) springContexts.add(c.identity());
            }
        } else {
            springContexts.add(ctx);
        }
        for (String sc : springContexts) {
            try {
                var springReq = new Request(
                        graph.identity(), sc, Scope.SPRING_COMPONENT, Projection.BOUNDED_UNION, Direction.OUTGOING,
                        Set.of("spring.selected-binding", "spring.injection-candidate"),
                        Population.all(), false, Optional.empty(), Limits.defaults());
                var sp = queries.spring(springReq,
                        Set.of("BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER", "INJECTION_POINT", "ENDPOINT"),
                        new Page(50, Optional.empty()));
                for (var item : sp.items()) springEntityList.putIfAbsent(item.identity(), item);
                var rz = queries.realizations(springReq, new Page(30, Optional.empty()));
                for (var r : rz.items()) realizationList.putIfAbsent(r.identity(), r);
            } catch (RuntimeException ignored) {}
        }

        // 4. Gaps across ALL analyzed contexts
        var allGaps = new ArrayList<CapabilityGapRecord>();
        var seenGapIds = new HashSet<String>();
        for (String tc : targetContexts) {
            try {
                var gapReq = new Request(
                        graph.identity(), tc, Scope.TYPE, Projection.BOUNDED_UNION, Direction.OUTGOING,
                        Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                var gp = queries.gaps(gapReq, GapView.CURRENT, new Page(50, Optional.empty()));
                for (var g : gp.gaps()) {
                    if (seenGapIds.add(g.gapIdentity().value())) allGaps.add(g);
                }
            } catch (RuntimeException ignored) {}
        }

        // 5. Quarantined Advisory Overlay
        var advisory = AdvisoryOverlayService.forGraph(graph);

        // Build individual Wiki pages
        var pages = new ArrayList<WikiPage>();
        int totalComms = allCommunities.size();
        int renderedComms = Math.min(totalComms, MAX_COMMUNITY_PAGES);
        boolean commsTruncated = totalComms > MAX_COMMUNITY_PAGES;

        var slugByCommId = new LinkedHashMap<String, String>();
        var usedSlugs = new HashSet<String>();
        for (int i = 0; i < renderedComms; i++) {
            var comm = allCommunities.get(i);
            String base = slugify(comm.label());
            if (base.isEmpty()) base = comm.id().replace(':', '-');
            String slug = base;
            int n = 2;
            while (!usedSlugs.add(slug)) slug = base + "-" + (n++);
            slugByCommId.put(comm.id(), slug);
        }

        // Page 1: index.md
        String indexMd = renderIndexPage(graph, ctx, score, moduleReports, aggregateModularity,
                allCommunities, allMisplaced, allBridges, advisory, slugByCommId, commsTruncated);
        pages.add(page("index.md", "Architecture Overview", "overview", indexMd));

        // Page 2: hotspots-and-tangles.md
        String tanglesMd = renderHotspotsAndTanglesPage(combinedInsightsMarkdown.toString(), allMisplaced);
        pages.add(page("hotspots-and-tangles.md", "Hotspots, Tangles & Drift", "quality", tanglesMd));

        // Page 3: spring-wiring.md
        String springMd = renderSpringWiringPage(List.copyOf(springEntityList.values()), List.copyOf(realizationList.values()));
        pages.add(page("spring-wiring.md", "Spring Wiring & Configurations", "spring", springMd));

        // Page 4: advisory-links.md
        String advisoryMd = renderAdvisoryPage(advisory);
        pages.add(page("advisory-links.md", "Advisory Docs, SQL & IaC Links", "advisory", advisoryMd));

        // Page 5: suggested-questions.md
        String questionsMd = renderSuggestedQuestionsPage(score, allTangles, allMisplaced, allHotspots,
                List.copyOf(springEntityList.values()), allGaps, advisory);
        pages.add(page("suggested-questions.md", "Suggested Architecture Questions", "questions", questionsMd));

        // Community pages
        var misplacedByComm = new LinkedHashMap<String, List<LeidenCommunityDetector.MisplacedType>>();
        for (var m : allMisplaced) {
            misplacedByComm.computeIfAbsent(m.communityId(), k -> new ArrayList<>()).add(m);
        }
        var outBridgesByComm = new LinkedHashMap<String, List<LeidenCommunityDetector.CommunityBridge>>();
        var inBridgesByComm = new LinkedHashMap<String, List<LeidenCommunityDetector.CommunityBridge>>();
        for (var b : allBridges) {
            outBridgesByComm.computeIfAbsent(b.fromCommunityId(), k -> new ArrayList<>()).add(b);
            inBridgesByComm.computeIfAbsent(b.toCommunityId(), k -> new ArrayList<>()).add(b);
        }
        for (int i = 0; i < renderedComms; i++) {
            var comm = allCommunities.get(i);
            String slug = slugByCommId.get(comm.id());
            String cmMd = renderCommunityPage(comm,
                    misplacedByComm.getOrDefault(comm.id(), List.of()),
                    outBridgesByComm.getOrDefault(comm.id(), List.of()),
                    inBridgesByComm.getOrDefault(comm.id(), List.of()),
                    memberDisplayIndex);
            pages.add(page("communities/" + slug + ".md", "Community: " + comm.label(), "community", cmMd));
        }

        // Combined single-file dossier Markdown
        var combined = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) combined.append("\n\n---\n\n");
            combined.append("<!-- PAGE: ").append(pages.get(i).path()).append(" -->\n\n");
            combined.append(pages.get(i).content());
        }
        String combinedMd = combined.toString();
        String bundleSha = ContentDigest.sha256Utf8(combinedMd).value();

        return new WikiBundle(SCHEMA, graph.identity().value(), ctx, bundleSha,
                totalComms, renderedComms, commsTruncated, pages, combinedMd);
    }

    /** Produces a reproducible deterministic ZIP archive (fixed entry order and epoch-0 timestamps). */
    public static byte[] toDeterministicZip(WikiBundle bundle) throws IOException {
        var baos = new ByteArrayOutputStream(32_768);
        try (var zos = new ZipOutputStream(baos, StandardCharsets.UTF_8)) {
            var sorted = bundle.pages().stream()
                    .sorted(Comparator.comparing(WikiPage::path))
                    .toList();
            for (var p : sorted) {
                var entry = new ZipEntry(p.path());
                entry.setTime(0L);
                zos.putNextEntry(entry);
                zos.write(p.content().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    /**
     * Atomically writes changed pages only (preserving mtimes on unchanged files) and sweeps orphan
     * {@code *.md} files under {@code communities/}.
     */
    public static Map<String, Object> writeToDirectoryAtomic(WikiBundle bundle, java.nio.file.Path outDir) throws IOException {
        Objects.requireNonNull(bundle);
        Objects.requireNonNull(outDir);
        java.nio.file.Path root = outDir.toAbsolutePath().normalize();
        Files.createDirectories(root);
        int written = 0;
        int unchanged = 0;
        int swept = 0;

        var expectedPaths = new HashSet<java.nio.file.Path>();
        for (var p : bundle.pages()) {
            java.nio.file.Path target = root.resolve(p.path()).normalize();
            if (!target.startsWith(root)) throw new IOException("Path escapes output directory: " + p.path());
            expectedPaths.add(target);
            Files.createDirectories(target.getParent());
            byte[] nextBytes = p.content().getBytes(StandardCharsets.UTF_8);
            if (Files.isRegularFile(target)) {
                byte[] existing = Files.readAllBytes(target);
                if (Arrays.equals(existing, nextBytes)) {
                    unchanged++;
                    continue;
                }
            }
            java.nio.file.Path tmp = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(tmp, nextBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException fallback) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            written++;
        }

        // Sweep stale community pages that no longer exist
        java.nio.file.Path commDir = root.resolve("communities");
        if (Files.isDirectory(commDir)) {
            try (var stream = Files.list(commDir)) {
                for (java.nio.file.Path existing : stream.toList()) {
                    if (Files.isRegularFile(existing) && existing.toString().endsWith(".md")
                            && !expectedPaths.contains(existing.toAbsolutePath().normalize())) {
                        Files.deleteIfExists(existing);
                        swept++;
                    }
                }
            }
        }
        return Map.of("outputDir", root.toString(), "pages", bundle.pages().size(),
                "written", written, "unchanged", unchanged, "sweptOrphans", swept,
                "bundleSha256", bundle.bundleSha256());
    }

    private record ModuleInsightsSlice(String contextId, String label, ArchitectureInsightsAdvisor.InsightsReport report) {}

    private record MemberDisplay(String name, String sourcePath) {}

    private static String renderIndexPage(
            KnowledgeGraph graph,
            String ctx,
            ArchitectureAssessmentV2.Result score,
            List<ModuleInsightsSlice> modules,
            BigDecimal modularity,
            List<LeidenCommunityDetector.EmergentCommunity> communities,
            List<LeidenCommunityDetector.MisplacedType> misplacedTypes,
            List<LeidenCommunityDetector.CommunityBridge> communityBridges,
            com.evolution.backend.query.advisory.AdvisoryOverlay.Snapshot advisory,
            Map<String, String> slugByCommId,
            boolean commsTruncated) {
        var md = new StringBuilder();
        md.append("# Architecture Wiki & Intelligence Dossier\n\n");
        md.append("- **Graph Digest:** `").append(graph.identity().value()).append("`\n");
        md.append("- **Snapshot:** `").append(graph.snapshot().value()).append("`\n");
        md.append("- **Context:** `").append(ctx).append("` (").append(modules.size()).append(" analyzed module context(s))\n");
        md.append("- **Health Score v2:** ")
                .append(score.score().map(s -> s.setScale(2, RoundingMode.HALF_EVEN).toPlainString()).orElse("Withheld"))
                .append(" (**").append(score.healthBand()).append("**, status `").append(score.status()).append("`)\n");
        md.append("- **Analysis Confidence:** `").append(score.confidence().tier()).append("` (")
                .append(score.confidence().confidenceScore().setScale(2, RoundingMode.HALF_EVEN))
                .append(" · evidence tier `").append(score.confidence().evidenceTier()).append("` · open gaps: ")
                .append(score.confidence().openGapCount()).append(")\n");
        md.append("- **Leiden Modularity Q:** `").append(modularity).append("` (")
                .append(communities.size()).append(" communities, ")
                .append(misplacedTypes.size()).append(" misplaced types, ")
                .append(communityBridges.size()).append(" cross-community bridges)\n\n");

        md.append("## Wiki Navigation\n");
        md.append("- [Hotspots, Tangles & Drift](hotspots-and-tangles.md)\n");
        md.append("- [Spring Wiring & Configurations](spring-wiring.md)\n");
        md.append("- [Advisory Docs, SQL & IaC Links (UNVERIFIED)](advisory-links.md)\n");
        md.append("- [Suggested Architecture Questions](suggested-questions.md)\n\n");

        if (modules.size() > 1) {
            md.append("## Analyzed Modules (").append(modules.size()).append(")\n");
            md.append("| Module / Context | Entities | Internal Deps | Tangles | Communities | Misplaced |\n");
            md.append("|---|---:|---:|---:|---:|---:|\n");
            for (var m : modules) {
                var s = m.report().summary();
                md.append("| ").append(mdCell(m.label()))
                        .append(" | ").append(s.analyzedEntities())
                        .append(" | ").append(s.internalDependencies())
                        .append(" | ").append(s.tangleCount())
                        .append(" | ").append(m.report().communities().size())
                        .append(" | ").append(m.report().misplacedTypes().size()).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Score v2 Dimensions\n");
        md.append("| Dimension | Score | Lower Bound | Weight | Status |\n|---|---:|---:|---:|---|\n");
        for (var d : score.dimensions()) {
            md.append("| `").append(d.id()).append("` ").append(d.displayName())
                    .append(" | ").append(d.score().map(s -> s.setScale(2, RoundingMode.HALF_EVEN).toPlainString()).orElse("—"))
                    .append(" | ").append(d.conservativeLowerBound().map(s -> s.setScale(2, RoundingMode.HALF_EVEN).toPlainString()).orElse("—"))
                    .append(" | ").append(d.weight().setScale(2, RoundingMode.HALF_EVEN))
                    .append(" | `").append(d.status()).append("` |\n");
        }
        md.append('\n');

        md.append("## Emergent Leiden Communities\n");
        if (communities.isEmpty()) {
            md.append("_No structural project entities found in the selected context._\n\n");
        } else {
            md.append("| Community | Dominant Package | Members | Cohesion | Misplaced | Bridges | Page |\n");
            md.append("|---|---|---:|---:|---:|---:|---|\n");
            for (var c : communities) {
                String slug = slugByCommId.get(c.id());
                String link = slug != null ? "[Open](communities/" + slug + ".md)" : "_(truncated)_";
                md.append("| ").append(mdCell(c.label()))
                        .append(" | `").append(mdCell(c.dominantPackage().isEmpty() ? "—" : c.dominantPackage())).append("`")
                        .append(" | ").append(c.memberCount())
                        .append(" | ").append(c.cohesion())
                        .append(" | ").append(c.misplacedCount())
                        .append(" | ").append(c.bridgeEdgeCount())
                        .append(" | ").append(link).append(" |\n");
            }
            if (commsTruncated) {
                md.append("\n> **Truncation Notice:** Showing pages for top ").append(MAX_COMMUNITY_PAGES)
                        .append(" of ").append(communities.size())
                        .append(" communities ordered by size.\n");
            }
            md.append('\n');
        }

        md.append("## Advisory Overlay Summary (`ADVISORY_UNVERIFIED`)\n");
        md.append("Quarantined non-code links extracted deterministically: **").append(advisory.nodes().size())
                .append("** nodes, **").append(advisory.links().size()).append("** links (excluded from canonical digests & Score v2).\n");
        return md.toString();
    }

    private static String renderCommunityPage(
            LeidenCommunityDetector.EmergentCommunity comm,
            List<LeidenCommunityDetector.MisplacedType> misplaced,
            List<LeidenCommunityDetector.CommunityBridge> outgoing,
            List<LeidenCommunityDetector.CommunityBridge> incoming,
            Map<String, MemberDisplay> memberDisplayIndex) {
        var md = new StringBuilder();
        md.append("# Community: ").append(comm.label()).append("\n\n");
        md.append("[← Architecture Overview](../index.md)\n\n");
        md.append("- **Community ID:** `").append(comm.id()).append("`\n");
        md.append("- **Member-Set SHA-256:** `").append(comm.memberSetDigest()).append("`\n");
        md.append("- **Dominant Package:** `").append(comm.dominantPackage().isEmpty() ? "—" : comm.dominantPackage()).append("`\n");
        md.append("- **Primary Hub:** `").append(comm.hubLabel()).append("`\n");
        md.append("- **Members:** ").append(comm.memberCount())
                .append(" · **Internal Edges:** ").append(comm.internalEdgeCount())
                .append(" · **External Edges:** ").append(comm.externalEdgeCount())
                .append(" · **Cohesion:** ").append(comm.cohesion()).append("\n\n");

        if (!misplaced.isEmpty()) {
            md.append("## Package-vs-Community Drift (Misplaced Types)\n");
            md.append("| Symbol | Declared Package | Community Edges | Declared Pkg Edges | Rationale |\n");
            md.append("|---|---|---:|---:|---|\n");
            for (var m : misplaced) {
                md.append("| `").append(mdCell(m.label())).append("` | `").append(mdCell(m.declaredPackage()))
                        .append("` | ").append(m.internalCommunityEdges())
                        .append(" | ").append(m.declaredPackageEdges())
                        .append(" | ").append(mdCell(m.rationale())).append(" |\n");
            }
            md.append('\n');
        }

        if (!outgoing.isEmpty() || !incoming.isEmpty()) {
            md.append("## Cross-Community Bridges\n");
            for (var b : outgoing) {
                md.append("- **Out ->** ").append(b.toCommunityLabel()).append(" (`").append(b.edgeCount())
                        .append("` edges, weight ").append(b.totalWeight()).append("): ")
                        .append(String.join("; ", b.sampleEdges())).append('\n');
            }
            for (var b : incoming) {
                md.append("- **In <-** ").append(b.fromCommunityLabel()).append(" (`").append(b.edgeCount())
                        .append("` edges, weight ").append(b.totalWeight()).append("): ")
                        .append(String.join("; ", b.sampleEdges())).append('\n');
            }
            md.append('\n');
        }

        md.append("## Members (").append(comm.memberCount()).append(")\n");
        md.append("| Class / Symbol | Source Path |\n|---|---|\n");
        int cap = 100;
        for (int i = 0; i < Math.min(comm.members().size(), cap); i++) {
            String memberId = comm.members().get(i);
            MemberDisplay disp = memberDisplayIndex.get(memberId);
            String name = (disp != null && disp.name() != null && !disp.name().isBlank()) ? disp.name() : memberId;
            String path = (disp != null && disp.sourcePath() != null && !disp.sourcePath().isBlank()) ? disp.sourcePath() : "—";
            md.append("| `").append(mdCell(name)).append("` | `").append(mdCell(path)).append("` |\n");
        }
        if (comm.members().size() > cap) {
            md.append("\n> **Truncation Notice:** Listed first ").append(cap)
                    .append(" of ").append(comm.members().size()).append(" member symbols.\n");
        }
        return md.toString();
    }

    private static String renderHotspotsAndTanglesPage(
            String combinedMarkdown,
            List<LeidenCommunityDetector.MisplacedType> misplacedTypes) {
        var md = new StringBuilder();
        md.append("# Hotspots, Tangles & Architectural Drift\n\n");
        md.append(combinedMarkdown);
        if (!misplacedTypes.isEmpty()) {
            md.append("\n## Package-vs-Community Drift (Top Misplaced Types)\n");
            md.append("| Symbol | Declared Package | Emergent Community | Community Edges | Declared Pkg Edges |\n");
            md.append("|---|---|---|---:|---:|\n");
            for (var m : misplacedTypes.stream().limit(30).toList()) {
                md.append("| `").append(mdCell(m.label())).append("` | `").append(mdCell(m.declaredPackage()))
                        .append("` | ").append(mdCell(m.communityLabel()))
                        .append(" | ").append(m.internalCommunityEdges())
                        .append(" | ").append(m.declaredPackageEdges()).append(" |\n");
            }
        }
        return md.toString();
    }

    private static String renderSpringWiringPage(List<EntityView> springItems, List<RealizationView> realizations) {
        var md = new StringBuilder();
        md.append("# Spring Wiring & Configuration Space\n\n");
        md.append("- **Observed Spring Entities:** ").append(springItems.size()).append('\n');
        md.append("- **Configuration Realizations:** ").append(realizations.size()).append("\n\n");
        if (!realizations.isEmpty()) {
            md.append("## Realized Worlds\n");
            for (var r : realizations.stream().limit(20).toList()) {
                md.append("- `").append(r.identity()).append("` (`").append(r.kind()).append("`): ")
                        .append(r.profiles()).append('\n');
            }
            md.append('\n');
        }
        if (!springItems.isEmpty()) {
            md.append("## Spring Components & Endpoints\n");
            md.append("| Kind | Name | Identity |\n|---|---|---|\n");
            for (var item : springItems.stream().limit(50).toList()) {
                String label = item.qualifiedName().or(item::spelling).or(item::canonicalName).orElse(item.identity());
                md.append("| `").append(mdCell(item.kind())).append("` | `").append(mdCell(label)).append("` | `").append(mdCell(item.identity())).append("` |\n");
            }
        } else {
            md.append("_No Spring wiring entities modeled in this context._\n");
        }
        return md.toString();
    }

    private static String renderAdvisoryPage(com.evolution.backend.query.advisory.AdvisoryOverlay.Snapshot advisory) {
        var md = new StringBuilder();
        md.append("# Advisory Links — Docs, SQL DDL <-> JPA, Terraform & MCP (`ADVISORY_UNVERIFIED`)\n\n");
        md.append("> **Quarantine Notice:** Schema `").append(advisory.schema()).append("` · derivation `")
                .append(advisory.derivation()).append("` · trust `").append(advisory.trust())
                .append("`. Excluded from canonical `KnowledgeGraph` digests, policy verdicts, and Score v2.\n\n");
        md.append("- **Nodes:** ").append(advisory.nodes().size()).append(" · ")
                .append(advisory.nodeCountsByKind()).append('\n');
        md.append("- **Links:** ").append(advisory.links().size()).append(" · ")
                .append(advisory.linkCountsByKind()).append('\n');
        md.append("- **Secrets Redacted at Parse Time:** ").append(advisory.redactedSecretsCount()).append("\n\n");

        if (!advisory.links().isEmpty()) {
            md.append("## Extracted Advisory Links (Top 60)\n");
            md.append("| Link Kind | Source | Target | Match Basis | Provenance |\n|---|---|---|---|---|\n");
            for (var l : advisory.links().stream().limit(60).toList()) {
                md.append("| `").append(mdCell(l.kind())).append("` | ").append(mdCell(l.sourceLabel()))
                        .append(" | `").append(mdCell(l.targetLabel())).append("` | `").append(mdCell(l.matchBasis()))
                        .append("` | `").append(mdCell(l.sourcePath())).append(':').append(l.startLine()).append("` |\n");
            }
            if (advisory.links().size() > 60) {
                md.append("\n> **Truncation Notice:** Listed first 60 of ").append(advisory.links().size()).append(" advisory links.\n");
            }
        }
        return md.toString();
    }

    private static String renderSuggestedQuestionsPage(
            ArchitectureAssessmentV2.Result score,
            List<ArchitectureInsightsAdvisor.Tangle> tangles,
            List<LeidenCommunityDetector.MisplacedType> misplacedTypes,
            List<ArchitectureInsightsAdvisor.Hotspot> hotspots,
            List<EntityView> springItems,
            List<CapabilityGapRecord> gaps,
            com.evolution.backend.query.advisory.AdvisoryOverlay.Snapshot advisory) {
        var md = new StringBuilder();
        md.append("# Suggested Architecture Questions\n\n");
        md.append("_Automatically synthesized from verified `MAY`/`UNKNOWN` regions, capability gaps, tangles, drift, and hotspots._\n\n");
        int q = 1;
        for (var t : tangles.stream().limit(3).toList()) {
            md.append(q++).append(". **Break Dependency Tangle (`").append(t.id()).append("`, ")
                    .append(t.memberCount()).append(" nodes):** Which interface extraction or event boundary cleanly severs the ")
                    .append(t.severity()).append(" cycle involving ")
                    .append(String.join(", ", t.memberLabels().stream().limit(4).toList())).append("?\n");
        }
        for (var m : misplacedTypes.stream().limit(3).toList()) {
            md.append(q++).append(". **Resolve Architectural Drift (`").append(m.label()).append("`):** Should `")
                    .append(m.label()).append("` move from `").append(m.declaredPackage())
                    .append("` to `").append(m.dominantPackage()).append("` (")
                    .append(m.internalCommunityEdges()).append(" vs ").append(m.declaredPackageEdges())
                    .append(" structural edges)?\n");
        }
        for (var h : hotspots.stream().limit(3).toList()) {
            md.append(q++).append(". **Mitigate Blast Radius (`").append(h.label()).append("`):** `")
                    .append(h.label()).append("` has ").append(h.transitiveDependents())
                    .append(" transitive dependents (instability ").append(h.instability())
                    .append("); what contract tests guard changes to it?\n");
        }
        if (score.confidence().openGapCount() > 0) {
            md.append(q++).append(". **Close Capability Gaps (").append(score.confidence().openGapCount())
                    .append(" open):** Which missing dependencies, generated sources, or conditional configs are driving `")
                    .append(score.confidence().tier()).append("` confidence (`")
                    .append(score.confidence().gapBuckets()).append("`)?\n");
        }
        if (!gaps.isEmpty()) {
            var g0 = gaps.getFirst();
            md.append(q++).append(". **Investigate Query Gap (`").append(g0.reasonCode()).append("`):** How does ")
                    .append(g0.mechanismCategory()).append(" uncertainty affect downstream policy and Spring wiring?\n");
        }
        if (!springItems.isEmpty()) {
            md.append(q++).append(". **Verify Conditional Spring Wiring:** Which `@Profile` or `@Conditional*` property combinations change bean selection between `MUST` and `MAY`/`UNKNOWN` worlds?\n");
        }
        long jpaLinks = advisory.links().stream().filter(l -> "SQL_TABLE_MAPS_JPA_ENTITY".equals(l.kind())).count();
        if (jpaLinks > 0) {
            md.append(q++).append(". **Audit Schema-to-Entity Drift:** Do all ").append(jpaLinks)
                    .append(" advisory SQL DDL <-> JPA entity mappings remain aligned with current migrations?\n");
        }
        if (q == 1) {
            md.append("1. Which high-level modules carry the greatest change risk when introducing a new feature?\n");
        }
        return md.toString();
    }

    private static boolean isSpringOnlyContext(KnowledgeGraph graph, KnowledgeGraph.Context ctx) {
        return graph.evidence(ctx.evidence()).map(e -> "spring.context".equals(e.family())).orElse(false);
    }

    private static Optional<PublishedReports.Report> findReportForContext(
            PublishedReports reports, ContentDigest graphId, String ctxId) {
        if (reports == null) return Optional.empty();
        for (var rid : reports.forGraph(graphId)) {
            var r = reports.get(rid);
            if (r.isPresent() && r.orElseThrow().evaluation().context().equals(ctxId)) {
                return r;
            }
        }
        return Optional.empty();
    }

    private static String resolveContextLabel(KnowledgeGraph graph, String ctxId) {
        var ctx = graph.contexts().stream().filter(c -> c.identity().equals(ctxId)).findFirst().orElse(null);
        if (ctx == null) return ctxId;
        var ev = graph.evidence(ctx.evidence()).orElse(null);
        if (ev == null) return ctxId;
        try {
            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(262_144, 32, 16_384)));
            if ("analysis.manifest".equals(ev.family())) {
                String sourceSet = null;
                if (obj.get("configuration") instanceof Map<?, ?> cfg
                        && cfg.get("values") instanceof Map<?, ?> vals
                        && vals.get("java.source-set") instanceof String ss && !ss.isBlank()) {
                    sourceSet = ss.strip();
                }
                if (obj.get("modules") instanceof List<?> mods && mods.size() == 1
                        && mods.getFirst() instanceof Map<?, ?> m && m.get("path") instanceof String p && !p.isBlank()) {
                    String base = ".".equals(p) ? "root" : p;
                    return (sourceSet != null && !"main".equalsIgnoreCase(sourceSet)) ? (base + " (" + sourceSet + ")") : base;
                }
                if (obj.get("configuration") instanceof Map<?, ?> cfg
                        && cfg.get("values") instanceof Map<?, ?> vals
                        && vals.get("java.module") instanceof String jm && !jm.isBlank()) {
                    String base = jm.startsWith("module:") ? jm.substring(0, Math.min(20, jm.length())) : jm;
                    return (sourceSet != null && !"main".equalsIgnoreCase(sourceSet)) ? (base + " (" + sourceSet + ")") : base;
                }
            } else if ("polyglot.context".equals(ev.family()) && obj.get("request") instanceof Map<?, ?> req) {
                String lang = Objects.toString(req.get("language"), "");
                String mod = Objects.toString(req.get("module"), Objects.toString(req.get("root"), ""));
                String label = (mod.isBlank() || ".".equals(mod)) ? lang : (mod + " (" + lang + ")");
                if (!label.isBlank()) return label;
            }
        } catch (RuntimeException ignored) {}
        return ctxId.length() > 24 ? ctxId.substring(0, 24) : ctxId;
    }

    private static Map<String, MemberDisplay> buildMemberDisplayIndex(KnowledgeGraph graph) {
        var docPaths = new HashMap<String, String>();
        var limits = new GraphJson.Limits(262_144, 32, 16_384);
        for (var node : graph.nodes(graph.snapshot().value())) {
            if (!"SOURCE_DOCUMENT".equals(node.kind())) continue;
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null) continue;
                try {
                    if ("source.document".equals(ev.family())) {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                        if (obj.get("path") instanceof String p && !p.isBlank()) {
                            docPaths.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                            break;
                        }
                    } else if ("source.bytes".equals(ev.family())) {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                        if (obj.get("document") instanceof Map<?, ?> d && d.get("path") instanceof String p && !p.isBlank()) {
                            docPaths.putIfAbsent(node.identity(), CapturedFileIndex.normalizePath(p));
                            break;
                        }
                    }
                } catch (RuntimeException ignored) {}
            }
        }

        var index = new HashMap<String, MemberDisplay>();
        for (var c : graph.contexts()) {
            for (var node : graph.nodes(c.identity())) {
                if (index.containsKey(node.identity())) continue;
                String name = null;
                String path = null;
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null) continue;
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), limits));
                        if ("java.declaration".equals(ev.family()) && obj.get("entity") instanceof Map<?, ?> ent) {
                            if (ent.get("qualifiedName") instanceof String q && !q.isBlank()) name = q;
                            else if (ent.get("canonicalName") instanceof String cn && !cn.isBlank()) {
                                name = RepoMapBuilder.readableCanonicalName(cn);
                            }
                            if (ent.get("declaration") instanceof Map<?, ?> sp && sp.get("document") instanceof String docId) {
                                path = docPaths.get(docId);
                            }
                        } else if ("polyglot.source-syntax".equals(ev.family())) {
                            Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                            if (name == null && fields.get("qualifiedName") instanceof String q && !q.isBlank()) name = q;
                            if (name == null && fields.get("name") instanceof String nm && !nm.isBlank()) name = nm;
                            if (path == null && fields.get("path") instanceof String p && !p.isBlank()) {
                                path = CapturedFileIndex.normalizePath(p);
                            }
                        } else if ("spring.candidate".equals(ev.family())
                                && obj.get("declaredNameKey") instanceof Map<?, ?> dk
                                && dk.get("primary") instanceof String p && !p.isBlank() && name == null) {
                            name = p;
                        }
                    } catch (RuntimeException ignored) {}
                }
                if (name != null || path != null) {
                    index.put(node.identity(), new MemberDisplay(name == null ? node.identity() : name, path == null ? "" : path));
                }
            }
        }
        return index;
    }

    private static String resolveTargetContext(KnowledgeGraph graph, String requested) {
        if (requested != null && !requested.isBlank()) {
            if (requested.equals(graph.snapshot().value())
                    || graph.contexts().stream().anyMatch(c -> c.identity().equals(requested))) {
                return requested;
            }
        }
        return graph.snapshot().value();
    }

    private static WikiPage page(String path, String title, String category, String content) {
        return new WikiPage(path, title, category, ContentDigest.sha256Utf8(content).value(), content);
    }

    private static boolean isCustomPolicy(PolicyEvaluation.Result eval) {
        if (eval == null || eval.rules().isEmpty()) return false;
        return !(eval.rules().size() == 1
                && "workbench.no-dependency-cycles".equals(eval.rules().getFirst().rule().header().id()));
    }

    private static String mdCell(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw.replace("\r", " ")
                .replace("\n", " ")
                .replace("|", "\\|")
                .trim();
    }

    private static String slugify(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        return s.length() > 64 ? s.substring(0, 64).replaceAll("-+$", "") : s;
    }
}
