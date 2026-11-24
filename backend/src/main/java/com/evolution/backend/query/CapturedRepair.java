package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.common.VersionedIdentifier;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/**
 * Evidence-scoped repair proposal. Compiler-checked syntax is a gate to patch
 * output, not a verification verdict.
 */
public final class CapturedRepair {
    public static final String VERSION = "architecture.repair-proposal:captured-v1";
    private static final VersionedIdentifier PROVIDER = new VersionedIdentifier("repair.captured", "1");
    private static final VersionedIdentifier GAP_CATALOG = new VersionedIdentifier("repair.capability-gaps", "1");
    private final RepairChatModel model;

    public enum Strategy {
        RECIPE, AI
    }

    public enum Reason {
        DIAGNOSIS_WITHHELD, QUERY_LIMIT, SOURCE_UNAVAILABLE, RECIPE_NOT_APPLICABLE, AMBIGUOUS_RECIPE_TARGET,
        COMPILER_UNAVAILABLE, BASELINE_SYNTAX_REJECTED, CANDIDATE_SYNTAX_REJECTED, CANDIDATE_LIMIT,
        MODEL_NOT_CONFIGURED, MODEL_INPUT_LIMIT, MODEL_INPUT_MISMATCH, MODEL_UNAVAILABLE, INVALID_MODEL_OUTPUT, NO_EDIT
    }

    public record Source(String path, ContentDigest digest, List<ContentDigest> citations,
            Optional<RepairSource.Issue> reason) {
    }

    public record Offer(RepairChatModel.Provider provider, ContentDigest input, List<Source> files) {
    }

    public record Options(String version, List<JavaRepairRecipes.Spec> recipes, List<Source> sources,
            List<CapabilityGapRecord> gaps, Optional<Offer> ai, Optional<Reason> aiUnavailable,
            List<String> applicableRecipes, boolean isolatedRunnerConfigured) {
        public Options(String version, List<JavaRepairRecipes.Spec> recipes, List<Source> sources,
                List<CapabilityGapRecord> gaps, Optional<Offer> ai, Optional<Reason> aiUnavailable) {
            this(version, recipes, sources, gaps, ai, aiUnavailable, List.of(), false);
        }
        public Options {
            recipes = List.copyOf(recipes);
            sources = List.copyOf(sources);
            gaps = List.copyOf(gaps);
            applicableRecipes = List.copyOf(applicableRecipes);
        }
    }

    public record Syntax(String path, ContentDigest source, String phase, JavaRepairSyntax.Check result) {
    }

    public record Edit(String path, ContentDigest before, ContentDigest after, ContentDigest byteEvidence,
            List<ContentDigest> citations) {
    }

    public record Candidate(FindingDiagnosis.Pin baseline, ContentDigest diagnosisInput,
            FindingDiagnosis.Proof affectedScope,
            List<FindingDiagnosis.Gap> upstreamGaps, Strategy strategy, Optional<JavaRepairRecipes.Spec> recipe,
            Optional<Offer> model, Optional<ContentDigest> modelResponse, List<Edit> edits, List<Syntax> syntax,
            ContentDigest patchDigest, String patch, String verification) {
    }

    public record Proposal(ContentDigest identity, Candidate candidate) {
    }

    public record Outcome(String version, String status, Optional<Reason> reason, List<Source> sources,
            List<CapabilityGapRecord> gaps,
            List<Syntax> syntax, Optional<Proposal> proposal, int attempts) {
        public Outcome(String version, String status, Optional<Reason> reason, List<Source> sources,
                List<CapabilityGapRecord> gaps, List<Syntax> syntax, Optional<Proposal> proposal) {
            this(version, status, reason, sources, gaps, syntax, proposal, 1);
        }
    }

    private record Inputs(List<RepairSource.File> files, List<Source> sources, List<CapabilityGapRecord> gaps) {
    }

    private record Request(Optional<Offer> offer, byte[] bytes, String userPrompt, List<ContentDigest> offered,
            List<ModelSources.Selection> files, Optional<Reason> reason) {
    }

    private record Replacement(JavaRepairRecipes.Range range, String text) {
    }

    private record Changed(RepairSource.File source, String text) {
    }

    CapturedRepair(RepairChatModel model) {
        this.model = model;
    }

    Options options(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Supplier<StopReason> stop) {
        return options(diagnosis, graph, stop, false, List.of());
    }

    Options options(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Supplier<StopReason> stop,
            boolean isolatedRunnerConfigured) {
        return options(diagnosis, graph, stop, isolatedRunnerConfigured, List.of());
    }

    Options options(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Supplier<StopReason> stop,
            boolean isolatedRunnerConfigured, List<String> bundlePath) {
        var inputs = collect(diagnosis, graph, stop);
        var request = offer(diagnosis, graph, inputs, bundlePath, stop);
        var applicable = computeApplicableRecipes(diagnosis, graph, inputs, stop);
        return new Options(VERSION, JavaRepairRecipes.catalog(), inputs.sources(), inputs.gaps(), request.offer(),
                request.reason(), applicable, isolatedRunnerConfigured);
    }

    private static List<String> computeApplicableRecipes(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph,
            Inputs inputs, Supplier<StopReason> stop) {
        if (stop.get() != StopReason.NONE
                || diagnosis.status() != FindingDiagnosis.Status.CITATIONS_VALIDATED
                || inputs.files().isEmpty()) {
            return List.of();
        }
        var profile = JavaRepairSyntax.profile(graph, diagnosis.pin());
        var parsedByPath = new LinkedHashMap<String, JavaRepairSyntax.Parsed>();
        for (var file : inputs.files()) {
            if (stop.get() != StopReason.NONE) return List.of();
            parsedByPath.put(file.path(), JavaRepairSyntax.parse(file.text(), profile.forFile(file)));
        }
        var applicable = new ArrayList<String>();
        for (var recipe : JavaRepairRecipes.Recipe.values()) {
            if (stop.get() != StopReason.NONE) break;
            boolean matches = inputs.files().stream().anyMatch(
                    file -> !JavaRepairRecipes.candidates(recipe, file, parsedByPath.get(file.path())).isEmpty());
            if (matches) {
                applicable.add(recipe.id);
            }
        }
        return List.copyOf(applicable);
    }

    Outcome propose(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Strategy strategy,
            Optional<JavaRepairRecipes.Recipe> recipe, Optional<ContentDigest> modelInput, Supplier<StopReason> stop) {
        return propose(diagnosis, graph, strategy, recipe, modelInput, 1, List.of(), stop);
    }

    Outcome propose(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Strategy strategy,
            Optional<JavaRepairRecipes.Recipe> recipe, Optional<ContentDigest> modelInput, int maxRepairAttempts,
            Supplier<StopReason> stop) {
        return propose(diagnosis, graph, strategy, recipe, modelInput, maxRepairAttempts, List.of(), stop);
    }

    Outcome propose(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Strategy strategy,
            Optional<JavaRepairRecipes.Recipe> recipe, Optional<ContentDigest> modelInput, int maxRepairAttempts,
            List<String> bundlePath, Supplier<StopReason> stop) {
        int maxAttempts = strategy == Strategy.AI ? Math.max(1, Math.min(3, maxRepairAttempts)) : 1;
        var inputs = collect(diagnosis, graph, stop);
        var checks = new ArrayList<Syntax>();
        if (stop.get() != StopReason.NONE)
            return withheld(Reason.QUERY_LIMIT, inputs, checks, 1);
        if (diagnosis.status() != FindingDiagnosis.Status.CITATIONS_VALIDATED)
            return withheld(Reason.DIAGNOSIS_WITHHELD, inputs, checks, 1);
        if (inputs.files().isEmpty())
            return withheld(Reason.SOURCE_UNAVAILABLE, inputs, checks, 1);
        var profile = JavaRepairSyntax.profile(graph, diagnosis.pin());
        var changes = new ArrayList<Changed>();
        Optional<Offer> modelOffer = Optional.empty();
        Optional<ContentDigest> modelResponse = Optional.empty();
        int usedAttempts = 1;
        if (strategy == Strategy.RECIPE) {
            for (var file : inputs.files()) {
                if (stop.get() != StopReason.NONE)
                    return withheld(Reason.QUERY_LIMIT, inputs, checks, 1);
                var parsed = JavaRepairSyntax.parse(file.text(), profile.forFile(file));
                checks.add(new Syntax(file.path(), file.digest(), "BASELINE", parsed.check()));
                try {
                    for (var nextText : JavaRepairRecipes.candidates(recipe.orElseThrow(), file, parsed))
                        changes.add(new Changed(file, nextText));
                } catch (StackOverflowError depth) {
                    return withheld(Reason.CANDIDATE_LIMIT, inputs, checks, 1);
                }
            }
            if (changes.size() > 1)
                return withheld(Reason.AMBIGUOUS_RECIPE_TARGET, inputs, checks, 1);
            if (changes.isEmpty()) {
                var reason = checks.stream().anyMatch(c -> c.result().status() == JavaRepairSyntax.Status.UNAVAILABLE)
                        ? Reason.COMPILER_UNAVAILABLE
                        : checks.stream().anyMatch(c -> c.result().status() == JavaRepairSyntax.Status.REJECTED)
                                ? Reason.BASELINE_SYNTAX_REJECTED
                                : Reason.RECIPE_NOT_APPLICABLE;
                return withheld(reason, inputs, checks, 1);
            }
        } else {
            var request = offer(diagnosis, graph, inputs, bundlePath, stop);
            if (request.reason().isPresent())
                return withheld(request.reason().orElseThrow(), inputs, checks, 1);
            modelOffer = request.offer();
            if (!modelOffer.map(Offer::input).equals(modelInput))
                return withheld(Reason.MODEL_INPUT_MISMATCH, inputs, checks, 1);
            var conversation = new ArrayList<Map<String, Object>>();
            conversation.add(Map.of("role", "system", "content", RepairChatModel.PROMPT));
            conversation.add(Map.of("role", "user", "content", request.userPrompt()));
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                usedAttempts = attempt;
                checks.clear();
                changes.clear();
                RepairChatModel.Completion reply;
                try {
                    reply = attempt == 1
                            ? model.propose(request.bytes(), stop)
                            : model.proposeConversation(conversation, request.offered(), stop);
                } catch (RepairChatModel.Rejected rejected) {
                    return withheld(Reason.valueOf(rejected.reason.name()), inputs, checks, usedAttempts);
                }
                if (reply.edits().isEmpty())
                    return withheld(Reason.NO_EDIT, inputs, checks, usedAttempts);
                String feedback = null;
                try {
                    changes.addAll(apply(reply.edits(), request.files()));
                    modelResponse = Optional.of(reply.contentDigest());
                } catch (IllegalArgumentException invalid) {
                    if (attempt >= maxAttempts || stop.get() != StopReason.NONE) {
                        return withheld(Reason.INVALID_MODEL_OUTPUT, inputs, checks, usedAttempts);
                    }
                    feedback = "Previous edits failed anchor or span validation (ensure oldText matches the offered file uniquely, "
                            + "stays inside offered lines, does not overlap, and at least one edit overlaps a cited span). "
                            + "Propose corrected JSON edits.";
                }
                if (feedback == null) {
                    var syntaxFailures = new ArrayList<String>();
                    for (var change : changes) {
                        var file = change.source();
                        String next = change.text();
                        var encoding = file.ascii() ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
                        if (next.isBlank() || next.indexOf(0) >= 0 || !encoding.newEncoder().canEncode(next)) {
                            return withheld(Reason.INVALID_MODEL_OUTPUT, inputs, checks, usedAttempts);
                        }
                        byte[] bytes = next.getBytes(encoding);
                        if (bytes.length > RepairSource.MAX_FILE_BYTES) {
                            return withheld(Reason.CANDIDATE_LIMIT, inputs, checks, usedAttempts);
                        }
                        var digest = ContentDigest.sha256(bytes);
                        if (digest.equals(file.digest())) {
                            return withheld(Reason.NO_EDIT, inputs, checks, usedAttempts);
                        }
                        var checked = JavaRepairSyntax.parse(next, profile.forFile(file)).check();
                        checks.add(new Syntax(file.path(), digest, "CANDIDATE", checked));
                        if (checked.status() == JavaRepairSyntax.Status.REJECTED) {
                            for (var err : checked.diagnostics()) {
                                syntaxFailures.add(file.path() + " line " + err.line() + " col " + err.column() + ": " + err.code());
                            }
                        } else if (checked.status() == JavaRepairSyntax.Status.UNAVAILABLE) {
                            return withheld(Reason.COMPILER_UNAVAILABLE, inputs, checks, usedAttempts);
                        }
                    }
                    if (syntaxFailures.isEmpty()) break;
                    if (attempt >= maxAttempts || stop.get() != StopReason.NONE) {
                        return withheld(Reason.CANDIDATE_SYNTAX_REJECTED, inputs, checks, usedAttempts);
                    }
                    feedback = "Java compiler syntax check rejected the previous candidate with diagnostics: "
                            + String.join("; ", syntaxFailures)
                            + ". Fix these syntax errors against the original offered file coordinates and return valid JSON edits.";
                }
                conversation.add(Map.of("role", "assistant", "content", reply.rawContent()));
                conversation.add(Map.of("role", "user", "content", feedback));
            }
        }

        var edits = new ArrayList<Edit>();
        Reason failed = null;
        if (strategy == Strategy.RECIPE) {
            for (var change : changes) {
                if (stop.get() != StopReason.NONE)
                    return withheld(Reason.QUERY_LIMIT, inputs, checks, usedAttempts);
                var file = change.source();
                String next = change.text();
                var encoding = file.ascii() ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
                if (next.isBlank() || next.indexOf(0) >= 0 || !encoding.newEncoder().canEncode(next))
                    return withheld(Reason.NO_EDIT, inputs, checks, usedAttempts);
                byte[] bytes = next.getBytes(encoding);
                if (bytes.length > RepairSource.MAX_FILE_BYTES)
                    return withheld(Reason.CANDIDATE_LIMIT, inputs, checks, usedAttempts);
                var digest = ContentDigest.sha256(bytes);
                if (digest.equals(file.digest()))
                    return withheld(Reason.NO_EDIT, inputs, checks, usedAttempts);
                var checked = JavaRepairSyntax.parse(next, profile.forFile(file)).check();
                checks.add(new Syntax(file.path(), digest, "CANDIDATE", checked));
                if (checked.status() == JavaRepairSyntax.Status.REJECTED)
                    failed = Reason.CANDIDATE_SYNTAX_REJECTED;
                else if (checked.status() == JavaRepairSyntax.Status.UNAVAILABLE && failed == null)
                    failed = Reason.COMPILER_UNAVAILABLE;
                edits.add(new Edit(file.path(), file.digest(), digest, file.byteEvidence(), citationIds(file.citations())));
            }
            if (failed != null)
                return withheld(failed, inputs, checks, usedAttempts);
        } else {
            for (var change : changes) {
                var file = change.source();
                var encoding = file.ascii() ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8;
                var digest = ContentDigest.sha256(change.text().getBytes(encoding));
                edits.add(new Edit(file.path(), file.digest(), digest, file.byteEvidence(), citationIds(file.citations())));
            }
        }
        if (stop.get() != StopReason.NONE)
            return withheld(Reason.QUERY_LIMIT, inputs, checks, usedAttempts);
        var patch = new StringBuilder();
        for (var change : changes) {
            patch.append(RepairPatch.file(change.source().path(), change.source().text(), change.text()));
            if (patch.length() > RepairProposalStore.MAX_FILE_BYTES)
                return withheld(Reason.CANDIDATE_LIMIT, inputs, checks, usedAttempts);
        }
        String diff = patch.toString();
        var candidate = new Candidate(diagnosis.pin(), diagnosis.input().orElseThrow(), diagnosis.proof().orElseThrow(),
                diagnosis.gaps(),
                strategy, recipe.map(JavaRepairRecipes.Recipe::spec), modelOffer, modelResponse, List.copyOf(edits),
                List.copyOf(checks),
                ContentDigest.sha256Utf8(diff), diff, "UNVERIFIED");
        var proposal = new Proposal(CanonicalJson.sha256(List.of(VERSION, candidate)), candidate);
        try { BoundedJson.encode(proposal, RepairProposalStore.MAX_FILE_BYTES); }
        catch (BoundedJson.TooLarge limit) { return withheld(Reason.CANDIDATE_LIMIT, inputs, checks, usedAttempts); }
        return new Outcome(VERSION, "PROPOSED", Optional.empty(), inputs.sources(), inputs.gaps(), List.of(),
                Optional.of(proposal), usedAttempts);
    }

    private static Inputs collect(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Supplier<StopReason> stop) {
        var groups = new TreeMap<String, List<FindingDiagnosis.Citation>>();
        for (var c : diagnosis.citations())
            groups.computeIfAbsent(c.path(), unused -> new ArrayList<>()).add(c);
        var files = new ArrayList<RepairSource.File>();
        var sources = new ArrayList<Source>();
        var gaps = new ArrayList<CapabilityGapRecord>();
        for (var row : groups.entrySet()) {
            var citations = row.getValue();
            Optional<RepairSource.Issue> issue = Optional.empty();
            try {
                if (stop.get() != StopReason.NONE)
                    throw new RepairSource.Unavailable(RepairSource.Issue.QUERY_LIMIT);
                files.add(RepairSource.open(graph, citations));
            } catch (RepairSource.Unavailable unavailable) {
                issue = Optional.of(unavailable.issue);
                gaps.add(gap(diagnosis, citations, unavailable.issue));
            }
            sources.add(new Source(row.getKey(), citations.getFirst().sourceDigest(), citationIds(citations), issue));
        }
        return new Inputs(List.copyOf(files), List.copyOf(sources), List.copyOf(gaps));
    }

    private Request offer(FindingDiagnosis.Outcome diagnosis, KnowledgeGraph graph, Inputs inputs,
            List<String> bundlePath, Supplier<StopReason> stop) {
        if (stop.get() != StopReason.NONE)
            return unavailable(Reason.QUERY_LIMIT);
        if (diagnosis.status() != FindingDiagnosis.Status.CITATIONS_VALIDATED)
            return unavailable(Reason.DIAGNOSIS_WITHHELD);
        if (model == null)
            return unavailable(Reason.MODEL_NOT_CONFIGURED);
        if (inputs.files().isEmpty())
            return unavailable(Reason.SOURCE_UNAVAILABLE);
        var proof = diagnosis.proof().orElseThrow();
        String ctx = diagnosis.pin().analysis().map(a -> a.value()).orElse(diagnosis.pin().context());
        String pinCtx = diagnosis.pin().context();
        String sourceName = entityLabel(graph, ctx, pinCtx, proof.source());
        String targetName = entityLabel(graph, ctx, pinCtx, proof.target());
        var rawPath = (bundlePath != null && !bundlePath.isEmpty())
                ? bundlePath
                : (proof.source().equals(proof.target()) ? List.of(proof.source()) : List.of(proof.source(), proof.target()));
        var pathSteps = rawPath.stream().limit(16)
                .map(id -> Map.<String, Object>of("identity", id, "label", entityLabel(graph, ctx, pinCtx, id)))
                .toList();
        var callers = collectNeighbors(graph, ctx, pinCtx, proof.source(), true, 10);
        var dependents = collectNeighbors(graph, ctx, pinCtx, proof.target(), true, 10);
        var springSummary = collectSpringSummary(graph, pinCtx, rawPath);
        ModelSources.Packet packet;
        var lastPrompt = new String[1];
        var lastOffered = new List[1];
        try {
            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("rule", proof.rule());
            metadata.put("scope", proof.scope());
            metadata.put("classification", proof.classification());
            metadata.put("relationships", proof.relationships());
            metadata.put("sourceEntity", proof.source());
            metadata.put("sourceName", sourceName);
            metadata.put("entityLabel", sourceName);
            metadata.put("targetEntity", proof.target());
            metadata.put("targetName", targetName);
            metadata.put("path", pathSteps);
            if (!callers.isEmpty()) metadata.put("callers", callers);
            if (!dependents.isEmpty()) metadata.put("dependents", dependents);
            if (!springSummary.isEmpty()) metadata.put("springSummary", springSummary);
            packet = ModelSources.fit(inputs.files(), offered -> {
                if (stop.get() != StopReason.NONE) throw new RepairChatModel.Rejected(RepairChatModel.Failure.QUERY_LIMIT);
                var files = new ArrayList<Map<String,Object>>();
                for (var selected : offered) {
                    var row = selected.payload();
                    row.put("file",files.size() + 1);
                    files.add(row);
                }
                var request = new HashMap<>(metadata);
                request.put("files",files);
                var digests = offered.stream().map(selected -> selected.file().digest()).toList();
                byte[] encoded = model.request(request, digests);
                var reqObj = GraphJson.object(GraphJson.read(new String(encoded, StandardCharsets.UTF_8), GraphJson.Limits.defaults()));
                var msgs = GraphJson.array(reqObj.get("messages"));
                lastPrompt[0] = GraphJson.string(GraphJson.object(msgs.getLast()).get("content"));
                lastOffered[0] = digests;
                return encoded;
            });
        } catch (BoundedJson.TooLarge limit) {
            return unavailable(Reason.MODEL_INPUT_LIMIT);
        } catch (RepairChatModel.Rejected stopped) {
            return unavailable(Reason.QUERY_LIMIT);
        }
        if (packet.files().isEmpty()) return unavailable(Reason.MODEL_INPUT_LIMIT);
        var files = packet.files().stream().map(ModelSources.Selection::file)
                .map(file -> new Source(file.path(),file.digest(),citationIds(file.citations()),Optional.empty())).toList();
        var id = CanonicalJson.sha256(List.of(VERSION, model.provider(), diagnosis.pin(),
                diagnosis.input().orElseThrow(), ContentDigest.sha256(packet.bytes())));
        @SuppressWarnings("unchecked")
        List<ContentDigest> digests = (List<ContentDigest>) lastOffered[0];
        return new Request(Optional.of(new Offer(model.provider(),id,files)),packet.bytes(),lastPrompt[0],digests,packet.files(),Optional.empty());
    }

    private static List<Map<String, Object>> collectNeighbors(KnowledgeGraph graph, String primaryContext,
            String secondaryContext, String entityId, boolean incoming, int limit) {
        var contexts = primaryContext.equals(secondaryContext) ? List.of(primaryContext)
                : List.of(primaryContext, secondaryContext);
        var out = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<String>();
        for (var context : contexts) {
            for (var edge : graph.edges(context)) {
                if (out.size() >= limit) break;
                if (edge.kind().equals("graph.contains") || edge.kind().equals("graph.declares")
                        || edge.kind().equals("graph.owns-source")) continue;
                if (incoming) {
                    if (edge.target().identities().contains(entityId) && !edge.source().equals(entityId)
                            && seen.add(edge.source() + "|" + edge.kind())) {
                        out.add(Map.of("identity", edge.source(),
                                "label", entityLabel(graph, primaryContext, secondaryContext, edge.source()),
                                "kind", edge.kind()));
                    }
                } else if (edge.source().equals(entityId)) {
                    for (var target : edge.target().identities()) {
                        if (out.size() >= limit) break;
                        if (!target.equals(entityId) && seen.add(target + "|" + edge.kind())) {
                            out.add(Map.of("identity", target,
                                    "label", entityLabel(graph, primaryContext, secondaryContext, target),
                                    "kind", edge.kind()));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static List<Map<String, Object>> collectSpringSummary(KnowledgeGraph graph, String pinContext,
            List<String> pathEntities) {
        var pathSet = new HashSet<>(pathEntities);
        var springContexts = new LinkedHashSet<String>();
        springContexts.add(pinContext);
        for (var c : graph.contexts()) {
            var ev = graph.evidence(c.evidence()).orElse(null);
            if (ev != null && "spring.context".equals(ev.family())) {
                springContexts.add(c.identity());
            }
        }
        var summary = new ArrayList<Map<String, Object>>();
        for (String ctx : springContexts) {
            for (var node : graph.nodes(ctx)) {
                if (summary.size() >= 10) break;
                if (!pathSet.contains(node.identity())
                        && !Set.of("BEAN_DEFINITION_CANDIDATE", "BEAN_PRODUCER", "ENDPOINT", "CONFIGURATION_CONDITION", "TRUTH_REGION")
                        .contains(node.kind())) {
                    continue;
                }
                if (!pathSet.contains(node.identity()) && !ctx.equals(pinContext)) continue;
                var row = new LinkedHashMap<String, Object>();
                row.put("identity", node.identity());
                row.put("kind", node.kind());
                row.put("label", entityLabel(graph, ctx, pinContext, node.identity()));
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null) continue;
                    if (Set.of("spring.candidate", "spring.condition", "spring.explicit-region", "spring.symbolic-region")
                            .contains(ev.family())) {
                        row.put("evidenceFamily", ev.family());
                        try {
                            var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                            if (obj.get("classification") instanceof String cls) row.put("classification", cls);
                            if (obj.get("region") instanceof Map<?, ?> reg && reg.get("classification") instanceof String cls)
                                row.put("classification", cls);
                            if (obj.get("kind") instanceof String k) row.put("conditionKind", k);
                        } catch (RuntimeException ignored) {}
                        break;
                    }
                }
                if (row.containsKey("evidenceFamily") || pathSet.contains(node.identity()) && !node.kind().equals("TYPE")) {
                    summary.add(row);
                }
            }
        }
        return summary;
    }

    static boolean authentic(Proposal proposal) {
        if (proposal == null || proposal.candidate() == null)
            return false;
        var candidate = proposal.candidate();
        return CanonicalJson.sha256(List.of(VERSION, candidate)).equals(proposal.identity())
                && ContentDigest.sha256Utf8(candidate.patch()).equals(candidate.patchDigest())
                && !candidate.edits().isEmpty();
    }

    static String entityLabel(KnowledgeGraph graph, String primaryContext, String secondaryContext,
            String entityId) {
        var contexts = primaryContext.equals(secondaryContext) ? List.of(primaryContext)
                : List.of(primaryContext, secondaryContext);
        for (var context : contexts) {
            for (var node : graph.nodes(context)) {
                if (!node.identity().equals(entityId))
                    continue;
                for (var ref : node.evidence()) {
                    var ev = graph.evidence(ref).orElse(null);
                    if (ev == null)
                        continue;
                    try {
                        var obj = GraphJson.object(GraphJson.read(ev.canonicalJson(), GraphJson.Limits.defaults()));
                        if ("java.declaration".equals(ev.family()) && obj.get("entity") instanceof Map<?, ?> ent) {
                            if (ent.get("qualifiedName") instanceof String q && !q.isBlank())
                                return q;
                            if (ent.get("canonicalName") instanceof String c && !c.isBlank())
                                return RepoMapBuilder.readableCanonicalName(c);
                        } else if ("spring.candidate".equals(ev.family())
                                && obj.get("declaredNameKey") instanceof Map<?, ?> names
                                && names.get("primary") instanceof String p && !p.isBlank()) {
                            return p;
                        } else if ("spring.endpoint-declaration".equals(ev.family())
                                && obj.get("route") instanceof Map<?, ?> route) {
                            String methods = route.get("methods") instanceof List<?> list
                                    ? String.join(",", list.stream().map(Object::toString).toList()) : "";
                            String routePath = route.get("path") instanceof String rp ? rp : "";
                            String label = (methods + " " + routePath).trim();
                            if (!label.isBlank()) return label;
                        } else if ("polyglot.source-syntax".equals(ev.family())) {
                            Map<?, ?> fields = obj.get("fields") instanceof Map<?, ?> f ? f : obj;
                            if (fields.get("name") instanceof String n && !n.isBlank()) return n;
                            if (fields.get("symbol") instanceof String s && !s.isBlank()) return s;
                            if (fields.get("path") instanceof String p && !p.isBlank()) return p;
                        } else if ("source.document".equals(ev.family())
                                && obj.get("path") instanceof String p && !p.isBlank()) {
                            return p;
                        } else if ("source.bytes".equals(ev.family())
                                && obj.get("document") instanceof Map<?, ?> doc
                                && doc.get("path") instanceof String p && !p.isBlank()) {
                            return p;
                        }
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        }
        return entityId;
    }

    private static List<Changed> apply(List<RepairChatModel.Change> edits, List<ModelSources.Selection> files) {
        var groups = new TreeMap<Integer, List<Replacement>>();
        for (var edit : edits) {
            if (edit.file() < 1 || edit.file() > files.size())
                throw new IllegalArgumentException();
            var selection = files.get(edit.file() - 1);
            var file = selection.file();
            if (!file.digest().equals(edit.before()))
                throw new IllegalArgumentException();
            var match = RepairAnchors.locate(file.text(),edit);
            if (!selection.contains(match.start(), match.end()))
                throw new IllegalArgumentException();
            var range = new JavaRepairRecipes.Range(match.start(), match.end());
            groups.computeIfAbsent(edit.file(), unused -> new ArrayList<>())
                    .add(new Replacement(range, match.replacement()));
        }
        boolean touchesEvidence = false;
        var result = new ArrayList<Changed>();
        for (var group : groups.entrySet()) {
            var file = files.get(group.getKey() - 1).file();
            var replacements = group.getValue();
            var citations = JavaRepairRecipes.citationRanges(file);
            replacements.sort(Comparator.comparingInt(r -> r.range().start()));
            var candidate = new StringBuilder();
            int previousEnd = 0;
            for (var edit : replacements) {
                var range = edit.range();
                if (range.start() < previousEnd)
                    throw new IllegalArgumentException();
                touchesEvidence |= citations.stream().anyMatch(c -> JavaRepairRecipes.overlaps(range, c));
                candidate.append(file.text(), previousEnd, range.start()).append(edit.text());
                previousEnd = range.end();
            }
            result.add(new Changed(file, candidate.append(file.text(), previousEnd, file.text().length()).toString()));
        }
        if (!touchesEvidence)
            throw new IllegalArgumentException();
        return List.copyOf(result);
    }

    private static CapabilityGapRecord gap(FindingDiagnosis.Outcome diagnosis,
            List<FindingDiagnosis.Citation> citations, RepairSource.Issue issue) {
        var first = citations.getFirst();
        var subject = new EvidenceSubject(EvidenceSubject.Kind.DOCUMENT, first.span().document().value());
        var kind = switch (issue) {
            case SOURCE_BYTES_UNAVAILABLE, SOURCE_INTEGRITY_MISMATCH, SOURCE_LIMIT, QUERY_LIMIT ->
                EvidenceRequirement.Kind.REPOSITORY_CONTENT;
            case SOURCE_NOT_EDITABLE -> EvidenceRequirement.Kind.SOURCE_OWNERSHIP;
            case SOURCE_ENCODING_UNSUPPORTED -> EvidenceRequirement.Kind.DECODED_SOURCE;
            case COMPILER_UNSUPPORTED -> EvidenceRequirement.Kind.ALTERNATE_FRONTEND;
        };
        var requirement = new EvidenceRequirement(kind, "repair.checked-source", List.of(subject),
                EvidenceRequirement.AuthorizationClass.PASSIVE,
                List.of("Obtain editable source bytes and a supported syntax check for this citation."));
        var observation = ProviderObservationReference.create(PROVIDER, "repair.source-availability",
                diagnosis.input().orElseThrow(),
                CanonicalJson.sha256(List.of(first.path(), first.sourceDigest(), citationIds(citations), issue)));
        return CapabilityGapRecord.create(GAP_CATALOG,
                new EvidenceContext(diagnosis.pin().snapshot(), diagnosis.pin().analysis()),
                PROVIDER, "repair.source", issue.name(), subject,
                citations.stream().map(FindingDiagnosis.Citation::span).toList(),
                List.of(observation), List.of(requirement), List.of(),
                List.of(new AffectedOutput(AffectedOutput.Kind.POLICY, "repair.proposal")),
                List.of(), List.of(), List.of());
    }

    private static List<ContentDigest> citationIds(List<FindingDiagnosis.Citation> citations) {
        return citations.stream().map(FindingDiagnosis.Citation::identity).distinct().sorted().toList();
    }

    private static Request unavailable(Reason reason) {
        return new Request(Optional.empty(), new byte[0], "", List.of(), List.of(), Optional.of(reason));
    }

    private static Outcome withheld(Reason reason, Inputs inputs, List<Syntax> checks, int attempts) {
        return new Outcome(VERSION, "WITHHELD", Optional.of(reason), inputs.sources(), inputs.gaps(),
                List.copyOf(checks), Optional.empty(), attempts);
    }
}
