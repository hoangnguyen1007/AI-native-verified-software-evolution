package com.evolution.backend.query.repair;

import com.evolution.analysis.contract.semantic.TrustTier;
import com.evolution.analysis.graph.GraphEvidence;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.graph.KnowledgeGraph;
import com.evolution.analysis.query.ArchitectureQuery.StopReason;
import com.evolution.analysis.query.trace.VerticalTraceEngine;
import com.evolution.backend.query.CapturedFileIndex;
import com.evolution.backend.query.advisory.AdvisoryOverlay;
import com.evolution.backend.query.advisory.AdvisoryOverlayService;
import com.evolution.backend.query.llm.LlmTransport;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic pre-edit "Anti-Edit-Bias" configuration and architecture gate.
 *
 * <p>Inspects captured {@code ConfigDataIngestion} evidence ({@code spring.config-data},
 * {@code source.configuration}, {@code application*.yml}/.properties, {@code @ConfigurationProperties},
 * {@code @ConditionalOnProperty}, {@code @Profile}, {@code @Value}, environment variables),
 * quarantined ADR/README configuration flags, and {@link VerticalTraceEngine} conditional hops
 * BEFORE proposing any source-code patch.
 *
 * <p>When a repair or architectural task directly matches an existing configuration property,
 * Spring profile, feature flag, or environment variable, emits {@link GateDecision#CONFIG_ONLY_RESOLUTION}
 * with exact {@code file:line} provenance and a recommended configuration change, blocking invasive
 * code-patch Edit Bias while strictly preserving {@code I1} (read-only), {@code I2} (zero graph mutation),
 * {@code I4} (trust-tier & secret redaction quarantine), and {@code I5} (zero benchmark literals).
 */
public final class PreEditConfigGate {
    public static final String SCHEMA = "evo.pre-edit-config-gate:v1";
    public static final double DEFAULT_MATCH_THRESHOLD = 0.65;
    private static final int MAX_MATCHES = 8;
    private static final int MAX_VERTICAL_SEEDS = 3;

    public enum GateDecision {
        CONFIG_ONLY_RESOLUTION,
        ALLOW_CODE_EDIT
    }

    public enum ConfigSourceKind {
        CONFIG_FILE_PROPERTY,
        SPRING_CONFIG_DATA_EVIDENCE,
        CONFIGURATION_PROPERTIES_BINDING,
        SPRING_PROFILE_OR_CONDITION,
        ENVIRONMENT_VARIABLE,
        ADR_OR_README_FLAG,
        VERTICAL_TRACE_CONDITIONAL_HOP
    }

    public record ConfigEvidenceMatch(
            String configKey,
            String currentValue,
            String recommendedChange,
            String filePath,
            int startLine,
            int endLine,
            ConfigSourceKind sourceKind,
            String trustTier,
            double matchScore,
            String rationale,
            List<String> matchedTokens) {
        public ConfigEvidenceMatch {
            configKey = configKey == null ? "" : configKey.strip();
            currentValue = currentValue == null ? "" : currentValue;
            recommendedChange = recommendedChange == null ? "" : recommendedChange;
            filePath = filePath == null ? "" : CapturedFileIndex.normalizePath(filePath);
            startLine = Math.max(1, startLine);
            endLine = Math.max(startLine, endLine);
            sourceKind = sourceKind == null ? ConfigSourceKind.CONFIG_FILE_PROPERTY : sourceKind;
            trustTier = (trustTier == null || trustTier.isBlank()) ? "DECLARATIVE_CONFIG" : trustTier;
            matchedTokens = List.copyOf(matchedTokens == null ? List.of() : matchedTokens);
        }

        public String fileLine() {
            return filePath.isEmpty() ? ("line:" + startLine) : (filePath + ":" + startLine);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("configKey", configKey);
            map.put("currentValue", currentValue);
            map.put("recommendedChange", recommendedChange);
            map.put("filePath", filePath);
            map.put("startLine", startLine);
            map.put("endLine", endLine);
            map.put("fileLine", fileLine());
            map.put("sourceKind", sourceKind.name());
            map.put("trustTier", trustTier);
            map.put("matchScore", BigDecimal.valueOf(Double.isFinite(matchScore) ? matchScore : 0.0)
                    .setScale(3, RoundingMode.HALF_UP));
            map.put("rationale", rationale);
            map.put("matchedTokens", matchedTokens);
            return Collections.unmodifiableMap(map);
        }
    }

    public record GateResult(
            GateDecision decision,
            boolean blocksCodePatch,
            String summary,
            String recommendedAction,
            List<ConfigEvidenceMatch> matches,
            List<String> inspectedSources,
            int verticalTraceHopsChecked) {
        public static final GateResult ALLOW = new GateResult(
                GateDecision.ALLOW_CODE_EDIT,
                false,
                "No direct configuration-only resolution matched the task; code edit allowed.",
                "",
                List.of(),
                List.of(),
                0);

        public GateResult {
            decision = decision == null ? GateDecision.ALLOW_CODE_EDIT : decision;
            summary = summary == null ? "" : summary;
            recommendedAction = recommendedAction == null ? "" : recommendedAction;
            matches = List.copyOf(matches == null ? List.of() : matches);
            inspectedSources = List.copyOf(inspectedSources == null ? List.of() : inspectedSources);
        }

        public Map<String, Object> toView() {
            var map = new LinkedHashMap<String, Object>();
            map.put("schema", SCHEMA);
            map.put("decision", decision.name());
            map.put("blocksCodePatch", blocksCodePatch);
            map.put("summary", summary);
            map.put("recommendedAction", recommendedAction);
            map.put("matches", matches.stream().map(ConfigEvidenceMatch::toView).toList());
            map.put("inspectedSources", inspectedSources);
            map.put("verticalTraceHopsChecked", verticalTraceHopsChecked);
            return Collections.unmodifiableMap(map);
        }
    }

    private record ConfigCandidate(
            String key,
            String currentValue,
            String filePath,
            int startLine,
            int endLine,
            ConfigSourceKind sourceKind,
            String trustTier,
            String profile,
            String extraContext) {
        String dedupeKey() {
            return sourceKind.name() + "|" + normalizeRelaxed(key) + "|" + filePath + "|" + startLine;
        }
    }

    private static final Pattern DOTTED_PROP_RE = Pattern.compile(
            "(?<![A-Za-z0-9_.-])([a-z][a-z0-9-]*(?:\\.[a-z0-9_-]+){1,7})(?![A-Za-z0-9_.-])");
    private static final Pattern ENV_VAR_RE = Pattern.compile(
            "(?<![A-Za-z0-9_])([A-Z][A-Z0-9]{1,}(?:_[A-Z0-9]+){1,6})(?![A-Za-z0-9_])");
    private static final Pattern CONFIG_PROPS_ANN_RE = Pattern.compile(
            "@ConfigurationProperties\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED_ATTR_PREFIX_RE = Pattern.compile(
            "(?:prefix|value)\\s*=\\s*\"([^\"]+)\"|^\\s*\"([^\"]+)\"");
    private static final Pattern VALUE_PLACEHOLDER_RE = Pattern.compile(
            "@Value\\s*\\(\\s*\"\\$\\{([A-Za-z0-9_.-]+)(?::([^}]*))?\\}\"\\s*\\)");
    private static final Pattern CONDITIONAL_PROP_RE = Pattern.compile(
            "@ConditionalOnProperty\\s*\\(([^)]*)\\)");
    private static final Pattern PROFILE_ANN_RE = Pattern.compile(
            "@Profile\\s*\\(([^)]*)\\)");
    private static final Pattern JAVA_FIELD_RE = Pattern.compile(
            "^\\s*(?:private|protected|public)\\s+(?:final\\s+)?(?:boolean|Boolean|int|Integer|long|Long|double|Double|String|Duration|List<[^>]+>|Set<[^>]+>)\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\s*(?:=\\s*([^;]+))?;");
    private static final Pattern ENV_LOOKUP_RE = Pattern.compile(
            "(?:System\\.getenv\\s*\\(\\s*\"([A-Z][A-Z0-9_]{2,})\"\\s*\\)"
                    + "|process\\.env\\.([A-Z][A-Z0-9_]{2,})"
                    + "|process\\.env\\[['\"]([A-Z][A-Z0-9_]{2,})['\"]\\]"
                    + "|os\\.(?:getenv|environ\\.get)\\s*\\(\\s*['\"]([A-Z][A-Z0-9_]{2,})['\"]"
                    + "|os\\.Getenv\\s*\\(\\s*\"([A-Z][A-Z0-9_]{2,})\"\\s*\\)"
                    + "|env::var\\s*\\(\\s*\"([A-Z][A-Z0-9_]{2,})\"\\s*\\))");
    private static final Pattern QUOTED_VALUE_RE = Pattern.compile("\"([^\"]+)\"");
    private static final Pattern PROFILE_INTENT_RE = Pattern.compile(
            "\\b(?:profile|profiles|spring\\.profiles\\.active)\\b(?:\\s+(?:to|as|is|named|called|=|:))*\\s+[\"'`]?([a-z][a-z0-9_-]{1,32})[\"'`]?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ENABLE_INTENT_RE = Pattern.compile(
            "\\b(enable|enabled|activate|turn\\s+on|switch\\s+on|set\\s+to\\s+true)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DISABLE_INTENT_RE = Pattern.compile(
            "\\b(disable|disabled|deactivate|turn\\s+off|switch\\s+off|set\\s+to\\s+false)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_VALUE_RE = Pattern.compile(
            "\\b(?:to|as|=|of)\\s+(\\d+(?:\\.\\d+)?(?:ms|s|m|h|kb|mb|gb)?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFIG_ACTION_INTENT_RE = Pattern.compile(
            "\\b(config|configure|configuration|property|properties|setting|settings|flag|flags|toggle|enable|disable|activate|profile|env|environment|variable|timeout|limit|threshold|port|host|url|mode|switch|increase|decrease|change|set|override|feature)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PURE_CODE_REFACTOR_RE = Pattern.compile(
            "\\b(nullpointerexception|npe|stack\\s*overflow|extract\\s+interface|break\\s+(?:the\\s+)?(?:circular|cycle)|circular\\s+dependency|refactor\\s+class|memory\\s+leak|race\\s+condition|deadlock|indexoutofbounds|classcastexception)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP_TOKENS = Set.of(
            "a", "an", "the", "to", "for", "of", "in", "on", "at", "by", "with", "from", "and", "or",
            "is", "are", "be", "can", "how", "what", "why", "when", "where", "do", "does", "should",
            "we", "i", "it", "this", "that", "as", "into", "instead", "code", "patch", "edit", "fix",
            "change", "update", "modify", "set", "use", "using", "app", "application", "spring",
            "service", "default", "value", "true", "false", "config", "configuration", "property",
            "properties", "flag", "setting", "settings", "feature", "enable", "enabled", "disable",
            "disabled", "mode", "name", "type", "class", "file", "line", "method", "java");

    private static final Set<String> GENERIC_SINGLE_KEYS = Set.of(
            "name", "id", "type", "value", "path", "url", "host", "port", "mode", "level",
            "enabled", "active", "default", "version", "description", "title", "status", "group");

    private static final Set<String> IGNORED_DOTTED_EXTS = Set.of(
            "java", "kt", "kts", "py", "ts", "tsx", "js", "jsx", "go", "rs", "cs", "php", "rb",
            "yml", "yaml", "properties", "json", "xml", "md", "mdx", "sql", "tf", "proto", "txt",
            "gradle", "toml", "sh", "bat", "cmd", "html", "css", "scss", "class", "jar");

    private PreEditConfigGate() {}

    /**
     * Evaluates whether {@code taskPrompt} can be resolved purely via an existing configuration
     * property, Spring profile, feature flag, or environment variable in {@code graph}.
     */
    public static GateResult evaluate(
            KnowledgeGraph graph,
            String context,
            String taskPrompt,
            Collection<String> citedPaths,
            String targetEntityId,
            Supplier<StopReason> stop) {
        if (graph == null || taskPrompt == null || taskPrompt.isBlank()) {
            return GateResult.ALLOW;
        }
        if (stop != null && stop.get() != StopReason.NONE) {
            return GateResult.ALLOW;
        }

        var fileIndex = CapturedFileIndex.forGraph(graph);
        var candidates = new LinkedHashMap<String, ConfigCandidate>();
        var inspectedSources = new LinkedHashSet<String>();
        var propertyLineIndex = new HashMap<String, List<FileLineLocation>>();

        // 1. Inspect captured application*.yml/yaml/properties, bootstrap*, config/*, .env* files
        inspectCapturedConfigFiles(graph, fileIndex, candidates, inspectedSources, propertyLineIndex, stop);

        // 2. Inspect canonical ConfigDataIngestion graph evidence (spring.config-data, source.configuration, spring.space, spring.condition)
        inspectGraphConfigEvidence(graph, candidates, inspectedSources, propertyLineIndex, stop);

        // 3. Inspect @ConfigurationProperties, @ConditionalOnProperty, @Profile, @Value, and env lookups in captured source
        inspectCodeConfigBindings(graph, fileIndex, candidates, inspectedSources, stop);

        // 4. Inspect ADR / README / Markdown config flags (quarantined ADVISORY_UNVERIFIED)
        inspectMarkdownAndAdvisoryFlags(graph, fileIndex, candidates, inspectedSources, stop);

        // 5. Inspect VerticalTraceEngine for conditional Spring DI / profile-gated hops
        int traceHopsChecked = inspectVerticalTraceConditionalHops(
                graph, context, taskPrompt, citedPaths, targetEntityId, candidates, inspectedSources, stop);

        if (candidates.isEmpty()) {
            return new GateResult(
                    GateDecision.ALLOW_CODE_EDIT,
                    false,
                    "No configuration properties, profiles, or flags discovered in captured snapshot; code edit allowed.",
                    "",
                    List.of(),
                    List.copyOf(inspectedSources),
                    traceHopsChecked);
        }

        // Score all discovered config candidates against taskPrompt deterministically
        PromptSignals signals = analyzePrompt(taskPrompt);
        var scored = new ArrayList<ConfigEvidenceMatch>();
        for (var cand : candidates.values()) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            ConfigEvidenceMatch match = scoreCandidate(cand, signals);
            if (match != null && match.matchScore() >= DEFAULT_MATCH_THRESHOLD) {
                scored.add(match);
            }
        }

        if (scored.isEmpty()) {
            return new GateResult(
                    GateDecision.ALLOW_CODE_EDIT,
                    false,
                    "Inspected " + candidates.size() + " configuration entries across " + inspectedSources.size()
                            + " source(s); no direct config-only match above threshold. Code edit allowed.",
                    "",
                    List.of(),
                    List.copyOf(inspectedSources),
                    traceHopsChecked);
        }

        scored.sort(Comparator
                .comparingDouble(ConfigEvidenceMatch::matchScore).reversed()
                .thenComparingInt(m -> sourcePriority(m.sourceKind()))
                .thenComparing(Comparator.comparingInt((ConfigEvidenceMatch m) -> m.configKey().length()).reversed())
                .thenComparing(ConfigEvidenceMatch::filePath)
                .thenComparingInt(ConfigEvidenceMatch::startLine)
                .thenComparing(ConfigEvidenceMatch::configKey));

        // Deduplicate by normalized configKey keeping the highest-scoring / highest-trust exact file:line
        var bestByKey = new LinkedHashMap<String, ConfigEvidenceMatch>();
        for (var m : scored) {
            if (bestByKey.size() >= MAX_MATCHES) break;
            String normKey = normalizeRelaxed(m.configKey());
            bestByKey.putIfAbsent(normKey + "|" + m.filePath(), m);
        }
        var topMatches = List.copyOf(bestByKey.values());
        var primary = topMatches.getFirst();

        String summary = "CONFIG_ONLY_RESOLUTION: Task directly matches existing configuration '"
                + primary.configKey() + "' at " + primary.fileLine()
                + " (" + primary.sourceKind().name() + ", trust=" + primary.trustTier()
                + "). Blocking invasive code-patch Edit Bias.";

        return new GateResult(
                GateDecision.CONFIG_ONLY_RESOLUTION,
                true,
                sanitize(summary),
                sanitize(primary.recommendedChange()),
                topMatches,
                List.copyOf(inspectedSources),
                traceHopsChecked);
    }

    private record FileLineLocation(String path, int line, String redactedValue) {}

    private record PromptSignals(
            String rawPrompt,
            String lowerPrompt,
            Set<String> explicitDottedKeysNorm,
            Set<String> explicitEnvVarsNorm,
            Set<String> explicitProfilesLower,
            Set<String> distinctiveTokens,
            boolean hasConfigActionIntent,
            boolean wantsEnable,
            boolean wantsDisable,
            Optional<String> requestedNumericValue,
            boolean strongPureCodeDefect) {}

    private static PromptSignals analyzePrompt(String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        var dotted = new LinkedHashSet<String>();
        Matcher dm = DOTTED_PROP_RE.matcher(lower);
        while (dm.find()) {
            String key = dm.group(1);
            if (!looksLikeFileName(key)) {
                dotted.add(normalizeRelaxed(key));
            }
        }
        var envs = new LinkedHashSet<String>();
        Matcher em = ENV_VAR_RE.matcher(prompt);
        while (em.find()) {
            envs.add(normalizeRelaxed(em.group(1)));
        }
        var profiles = new LinkedHashSet<String>();
        Matcher pm = PROFILE_INTENT_RE.matcher(prompt);
        while (pm.find()) {
            String p = pm.group(1).toLowerCase(Locale.ROOT);
            if (!STOP_TOKENS.contains(p)) {
                profiles.add(p);
            }
        }
        var tokens = tokenizeDistinctive(prompt);
        boolean hasConfigIntent = CONFIG_ACTION_INTENT_RE.matcher(prompt).find()
                || !dotted.isEmpty() || !envs.isEmpty() || !profiles.isEmpty();
        boolean wantsEnable = ENABLE_INTENT_RE.matcher(prompt).find();
        boolean wantsDisable = DISABLE_INTENT_RE.matcher(prompt).find();
        Optional<String> numVal = Optional.empty();
        Matcher nm = NUMERIC_VALUE_RE.matcher(prompt);
        if (nm.find()) {
            numVal = Optional.of(nm.group(1));
        }
        boolean pureCode = PURE_CODE_REFACTOR_RE.matcher(prompt).find() && dotted.isEmpty() && envs.isEmpty();
        return new PromptSignals(prompt, lower, dotted, envs, profiles, tokens,
                hasConfigIntent, wantsEnable, wantsDisable, numVal, pureCode);
    }

    private static ConfigEvidenceMatch scoreCandidate(ConfigCandidate cand, PromptSignals signals) {
        if (cand.key().isBlank()) return null;
        if (signals.strongPureCodeDefect()) return null;

        String normKey = normalizeRelaxed(cand.key());
        String keyLower = cand.key().toLowerCase(Locale.ROOT);
        if (normKey.length() < 3) return null;

        // Guard against noisy bare single-segment keys unless explicitly mentioned as dotted/env
        boolean singleSegment = !cand.key().contains(".") && !cand.key().contains("_") && !cand.key().contains("-");
        if (singleSegment && GENERIC_SINGLE_KEYS.contains(keyLower)
                && cand.sourceKind() != ConfigSourceKind.SPRING_PROFILE_OR_CONDITION) {
            return null;
        }

        double score = 0.0;
        var matchedTokens = new ArrayList<String>();
        String reason = "";

        // Tier A: Exact relaxed property key or environment variable match (full key strictly outranks prefix substring)
        if (signals.explicitDottedKeysNorm().contains(normKey)
                || signals.explicitEnvVarsNorm().contains(normKey)) {
            score = 0.98;
            matchedTokens.add(cand.key());
            reason = "Exact configuration key match ('" + cand.key() + "')";
        } else if (cand.key().length() >= 6 && signals.lowerPrompt().contains(keyLower)) {
            score = 0.92;
            matchedTokens.add(cand.key());
            reason = "Configuration prefix/key match ('" + cand.key() + "')";
        }

        // Tier B: Spring profile match (e.g. @Profile("prod") or application-prod.yml)
        if (score < 0.90 && cand.sourceKind() == ConfigSourceKind.SPRING_PROFILE_OR_CONDITION
                && !cand.profile().isBlank()) {
            String pLow = cand.profile().toLowerCase(Locale.ROOT);
            boolean profileWordHit = signals.lowerPrompt().contains("profile")
                    && (signals.distinctiveTokens().contains(pLow)
                    || containsStandaloneToken(signals.lowerPrompt(), pLow));
            if (signals.explicitProfilesLower().contains(pLow) || profileWordHit) {
                score = Math.max(score, 0.92);
                matchedTokens.add("profile:" + cand.profile());
                reason = "Matches existing Spring profile '" + cand.profile() + "'";
            }
        }

        // Tier C: Multi-token distinctive overlap on structured config key segments
        if (score < DEFAULT_MATCH_THRESHOLD) {
            var keyTokens = tokenizeDistinctive(cand.key());
            if (!cand.extraContext().isBlank()
                    && (cand.sourceKind() == ConfigSourceKind.CONFIGURATION_PROPERTIES_BINDING
                    || cand.sourceKind() == ConfigSourceKind.VERTICAL_TRACE_CONDITIONAL_HOP)) {
                // Keep extra context separate from denominator dilution
            }
            if (!keyTokens.isEmpty()) {
                var overlap = new ArrayList<String>();
                for (String kt : keyTokens) {
                    if (signals.distinctiveTokens().contains(kt)) {
                        overlap.add(kt);
                    }
                }
                double coverage = (double) overlap.size() / keyTokens.size();
                if (overlap.size() >= 2 && coverage >= 0.50) {
                    double computed = 0.62 + 0.25 * coverage + (signals.hasConfigActionIntent() ? 0.08 : 0.0);
                    if (cand.sourceKind() == ConfigSourceKind.ADR_OR_README_FLAG) {
                        computed -= 0.04; // Advisory docs require slightly stronger overlap
                    }
                    if (computed > score) {
                        score = Math.min(0.94, computed);
                        matchedTokens.clear();
                        matchedTokens.addAll(overlap);
                        reason = "Multi-token configuration match on segments " + overlap;
                    }
                } else if (overlap.size() == 1 && keyTokens.size() == 1 && signals.hasConfigActionIntent()
                        && overlap.getFirst().length() >= 6 && !GENERIC_SINGLE_KEYS.contains(overlap.getFirst())) {
                    // Single highly distinctive config segment (e.g., "compression", "ratelimit", "cors") + explicit config/toggle intent
                    double computed = 0.70;
                    if (cand.sourceKind() == ConfigSourceKind.CONFIG_FILE_PROPERTY
                            || cand.sourceKind() == ConfigSourceKind.SPRING_CONFIG_DATA_EVIDENCE
                            || cand.sourceKind() == ConfigSourceKind.CONFIGURATION_PROPERTIES_BINDING) {
                        score = computed;
                        matchedTokens.addAll(overlap);
                        reason = "Distinctive configuration token match '" + overlap.getFirst() + "' with configuration intent";
                    }
                }
            }
        }

        if (score < DEFAULT_MATCH_THRESHOLD) {
            return null;
        }

        // Bonus for canonical config file / Spring config data / @ConfigurationProperties over advisory docs
        if (cand.sourceKind() == ConfigSourceKind.CONFIG_FILE_PROPERTY
                || cand.sourceKind() == ConfigSourceKind.SPRING_CONFIG_DATA_EVIDENCE) {
            score = Math.min(1.0, score + 0.02);
        }
        // Prefer base config (or explicitly requested profile) over unrequested profile-specific overrides,
        // and prefer entries whose current value actually needs to be toggled.
        boolean unrequestedProfileOverride = !cand.profile().isBlank()
                && cand.sourceKind() != ConfigSourceKind.SPRING_PROFILE_OR_CONDITION
                && !signals.explicitProfilesLower().contains(cand.profile().toLowerCase(Locale.ROOT));
        if (unrequestedProfileOverride) {
            score = Math.max(DEFAULT_MATCH_THRESHOLD, score - 0.03);
        }
        if ((signals.wantsEnable() && "false".equalsIgnoreCase(cand.currentValue()))
                || (signals.wantsDisable() && "true".equalsIgnoreCase(cand.currentValue()))) {
            score = Math.min(1.0, score + 0.01);
        }

        String safeVal = sanitizeValue(cand.currentValue());
        String rec = buildRecommendedChange(cand, safeVal, signals);
        return new ConfigEvidenceMatch(
                sanitize(cand.key()),
                safeVal,
                sanitize(rec),
                cand.filePath(),
                cand.startLine(),
                cand.endLine(),
                cand.sourceKind(),
                cand.trustTier(),
                score,
                sanitize(reason),
                matchedTokens);
    }

    private static String buildRecommendedChange(ConfigCandidate cand, String currentValue, PromptSignals signals) {
        String loc = cand.filePath().isEmpty() ? "configuration" : (cand.filePath() + ":" + cand.startLine());
        if (cand.sourceKind() == ConfigSourceKind.SPRING_PROFILE_OR_CONDITION && !cand.profile().isBlank()) {
            return "Activate or switch Spring profile '" + cand.profile() + "' via spring.profiles.active (defined at "
                    + loc + ") instead of modifying application source code.";
        }
        if (cand.sourceKind() == ConfigSourceKind.ENVIRONMENT_VARIABLE) {
            String target = suggestedValueHint(currentValue, signals);
            return "Configure environment variable '" + cand.key() + "'" + target
                    + " (referenced at " + loc + ") instead of patching source code.";
        }
        String target = suggestedValueHint(currentValue, signals);
        String curNote = currentValue.isBlank() ? "" : (" (currently '" + currentValue + "')");
        return "Update configuration property '" + cand.key() + "'" + target + " in " + loc + curNote
                + " instead of proposing an invasive code patch.";
    }

    private static String suggestedValueHint(String currentValue, PromptSignals signals) {
        if (signals.wantsEnable() && !signals.wantsDisable()) {
            return " to 'true'";
        }
        if (signals.wantsDisable() && !signals.wantsEnable()) {
            return " to 'false'";
        }
        if (signals.requestedNumericValue().isPresent()) {
            return " to '" + signals.requestedNumericValue().orElseThrow() + "'";
        }
        if ("false".equalsIgnoreCase(currentValue)) {
            return " (e.g., to 'true')";
        }
        if ("true".equalsIgnoreCase(currentValue)) {
            return " (e.g., to 'false')";
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // 1. Captured application*.yml / .properties / bootstrap* / .env* files
    // -------------------------------------------------------------------------

    private static void inspectCapturedConfigFiles(
            KnowledgeGraph graph,
            CapturedFileIndex fileIndex,
            Map<String, ConfigCandidate> out,
            Set<String> inspectedSources,
            Map<String, List<FileLineLocation>> propertyLineIndex,
            Supplier<StopReason> stop) {
        for (var entry : fileIndex.entries()) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            if (!entry.readable()) continue;
            String path = entry.path();
            if (!isConfigFilePath(path)) continue;

            var textOpt = fileIndex.readText(graph, entry);
            if (textOpt.isEmpty()) continue;
            // Always pre-redact full document before extracting ANY key/value to guarantee zero secret leakage
            var redactedDoc = LlmTransport.redactDocumentForPath(path, textOpt.orElseThrow());
            inspectedSources.add(path);

            String low = path.toLowerCase(Locale.ROOT);
            String fileProfile = extractProfileFromFilename(path).orElse("");
            if (!fileProfile.isBlank()) {
                addCandidate(out, new ConfigCandidate(
                        "spring.profiles.active=" + fileProfile,
                        fileProfile,
                        path,
                        1,
                        1,
                        ConfigSourceKind.SPRING_PROFILE_OR_CONDITION,
                        "DECLARATIVE_CONFIG",
                        fileProfile,
                        "profile-specific config file"));
            }

            if (low.endsWith(".properties") || isEnvFileName(low)) {
                parsePropertiesLines(path, redactedDoc.lines(), fileProfile, isEnvFileName(low), out, propertyLineIndex);
            } else if (low.endsWith(".yml") || low.endsWith(".yaml")) {
                parseYamlLines(path, redactedDoc.lines(), fileProfile, out, propertyLineIndex);
            }
        }
    }

    private static void parsePropertiesLines(
            String path,
            List<String> lines,
            String fileProfile,
            boolean isEnvFile,
            Map<String, ConfigCandidate> out,
            Map<String, List<FileLineLocation>> propertyLineIndex) {
        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i).strip();
            if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("!")) continue;
            int eq = findKeyValueSeparator(raw);
            if (eq <= 0) continue;
            String key = raw.substring(0, eq).strip();
            String val = raw.substring(eq + 1).strip();
            if (key.isEmpty()) continue;
            int lineNo = i + 1;
            ConfigSourceKind kind = isEnvFile || ENV_VAR_RE.matcher(key).matches()
                    ? ConfigSourceKind.ENVIRONMENT_VARIABLE
                    : ConfigSourceKind.CONFIG_FILE_PROPERTY;
            addCandidate(out, new ConfigCandidate(key, val, path, lineNo, lineNo, kind,
                    "DECLARATIVE_CONFIG", fileProfile, ""));
            propertyLineIndex.computeIfAbsent(normalizeRelaxed(key), k -> new ArrayList<>())
                    .add(new FileLineLocation(path, lineNo, val));
            if ("spring.profiles.active".equalsIgnoreCase(key) && !val.isBlank()) {
                for (String prof : val.split("[,\\s]+")) {
                    if (!prof.isBlank()) {
                        addCandidate(out, new ConfigCandidate(
                                "spring.profiles.active", prof.strip(), path, lineNo, lineNo,
                                ConfigSourceKind.SPRING_PROFILE_OR_CONDITION, "DECLARATIVE_CONFIG", prof.strip(), ""));
                    }
                }
            }
        }
    }

    private record YamlIndentKey(int indent, String key) {}

    private static void parseYamlLines(
            String path,
            List<String> lines,
            String fileProfile,
            Map<String, ConfigCandidate> out,
            Map<String, List<FileLineLocation>> propertyLineIndex) {
        var stack = new ArrayDeque<YamlIndentKey>();
        String docProfile = fileProfile;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) continue;
            if (stripped.equals("---") || stripped.equals("...")) {
                stack.clear();
                docProfile = fileProfile;
                continue;
            }
            if (stripped.startsWith("- ")) continue;

            int indent = leadingSpaces(line);
            int colon = findYamlColon(line);
            if (colon < 0) continue;
            String rawKey = line.substring(indent, colon).strip();
            rawKey = stripQuotes(rawKey);
            if (rawKey.isEmpty() || rawKey.contains(" ")) continue;

            String rawVal = line.substring(colon + 1).strip();
            // Strip inline comments not inside quotes
            int commentIdx = findInlineComment(rawVal);
            if (commentIdx >= 0) {
                rawVal = rawVal.substring(0, commentIdx).strip();
            }
            rawVal = stripQuotes(rawVal);

            while (!stack.isEmpty() && stack.peek().indent() >= indent) {
                stack.pop();
            }
            stack.push(new YamlIndentKey(indent, rawKey));

            if (!rawVal.isEmpty() && !rawVal.equals("|") && !rawVal.equals(">")
                    && !rawVal.equals("|-") && !rawVal.equals(">-")) {
                String fullKey = joinYamlStack(stack);
                int lineNo = i + 1;
                addCandidate(out, new ConfigCandidate(fullKey, rawVal, path, lineNo, lineNo,
                        ConfigSourceKind.CONFIG_FILE_PROPERTY, "DECLARATIVE_CONFIG", docProfile, ""));
                propertyLineIndex.computeIfAbsent(normalizeRelaxed(fullKey), k -> new ArrayList<>())
                        .add(new FileLineLocation(path, lineNo, rawVal));

                if ("spring.config.activate.on-profile".equalsIgnoreCase(fullKey)
                        || "spring.profiles".equalsIgnoreCase(fullKey)
                        || "spring.profiles.active".equalsIgnoreCase(fullKey)) {
                    for (String prof : rawVal.split("[,|&\\s]+")) {
                        String p = prof.strip().replaceFirst("^!", "");
                        if (!p.isBlank()) {
                            docProfile = p;
                            addCandidate(out, new ConfigCandidate(
                                    fullKey, p, path, lineNo, lineNo,
                                    ConfigSourceKind.SPRING_PROFILE_OR_CONDITION, "DECLARATIVE_CONFIG", p, ""));
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 2. Canonical Graph ConfigDataIngestion & Spring Condition Evidence
    // -------------------------------------------------------------------------

    private static void inspectGraphConfigEvidence(
            KnowledgeGraph graph,
            Map<String, ConfigCandidate> out,
            Set<String> inspectedSources,
            Map<String, List<FileLineLocation>> propertyLineIndex,
            Supplier<StopReason> stop) {
        for (var ev : graph.evidence()) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            String fam = ev.family();
            if ("spring.config-data".equals(fam) || "source.configuration".equals(fam)) {
                var obj = readSmall(ev);
                if (obj.isEmpty()) continue;
                inspectedSources.add("evidence:" + fam);
                if (obj.get("activeProfiles") instanceof List<?> ap) {
                    for (Object pObj : ap) {
                        if (pObj instanceof String p && !p.isBlank()) {
                            addCandidate(out, new ConfigCandidate(
                                    "spring.profiles.active", p.strip(),
                                    firstString(obj, "path", "src/main/resources/application.yml"), 1, 1,
                                    ConfigSourceKind.SPRING_PROFILE_OR_CONDITION, TrustTier.SPRING_SAT.name(), p.strip(), ""));
                        }
                    }
                }
                if (obj.get("documents") instanceof List<?> docs) {
                    for (Object dObj : docs) {
                        if (!(dObj instanceof Map<?, ?> dm)) continue;
                        String path = dm.get("path") instanceof String ps ? CapturedFileIndex.normalizePath(ps) : "";
                        String prof = dm.get("profile") instanceof String pr ? pr : "";
                        if (dm.get("properties") instanceof Map<?, ?> props) {
                            recordEvidenceProperties(path, prof, props, out, propertyLineIndex);
                        }
                    }
                }
                if (obj.get("properties") instanceof Map<?, ?> props) {
                    String path = firstString(obj, "path", firstString(obj, "sourceFile", ""));
                    String prof = firstString(obj, "profile", "");
                    recordEvidenceProperties(path, prof, props, out, propertyLineIndex);
                }
            } else if ("spring.space".equals(fam) || "spring.condition".equals(fam)) {
                var obj = readSmall(ev);
                if (obj.isEmpty()) continue;
                if (obj.get("domains") instanceof List<?> domains) {
                    inspectedSources.add("evidence:" + fam);
                    for (Object dObj : domains) {
                        if (!(dObj instanceof Map<?, ?> dm)) continue;
                        if (!(dm.get("variable") instanceof Map<?, ?> vm)) continue;
                        String kind = vm.get("kind") instanceof String s ? s : "";
                        String name = vm.get("name") instanceof String s ? s.strip() : "";
                        if (name.isBlank()) continue;
                        var loc = findBestLocation(propertyLineIndex, name);
                        String path = loc != null ? loc.path() : "";
                        int line = loc != null ? loc.line() : 1;
                        if ("PROFILE".equalsIgnoreCase(kind)) {
                            addCandidate(out, new ConfigCandidate(
                                    "spring.profiles.active", name, path, line, line,
                                    ConfigSourceKind.SPRING_PROFILE_OR_CONDITION, TrustTier.SPRING_SAT.name(), name, ""));
                        } else if ("PROPERTY".equalsIgnoreCase(kind)) {
                            addCandidate(out, new ConfigCandidate(
                                    name, loc != null ? loc.redactedValue() : "", path, line, line,
                                    ConfigSourceKind.SPRING_CONFIG_DATA_EVIDENCE, TrustTier.SPRING_SAT.name(), "", ""));
                        }
                    }
                }
            }
        }
    }

    private static void recordEvidenceProperties(
            String path,
            String profile,
            Map<?, ?> props,
            Map<String, ConfigCandidate> out,
            Map<String, List<FileLineLocation>> propertyLineIndex) {
        for (var e : props.entrySet()) {
            if (!(e.getKey() instanceof String rawKey) || rawKey.isBlank()) continue;
            String val = e.getValue() == null ? "" : sanitizeValue(String.valueOf(e.getValue()));
            var loc = findBestLocation(propertyLineIndex, rawKey);
            String effPath = !path.isBlank() ? CapturedFileIndex.normalizePath(path) : (loc != null ? loc.path() : "");
            int effLine = loc != null && (effPath.isEmpty() || effPath.equals(loc.path())) ? loc.line() : 1;
            addCandidate(out, new ConfigCandidate(
                    rawKey.strip(), val, effPath, effLine, effLine,
                    ConfigSourceKind.SPRING_CONFIG_DATA_EVIDENCE, "DECLARATIVE_CONFIG", profile, ""));
        }
    }

    // -------------------------------------------------------------------------
    // 3. @ConfigurationProperties / @ConditionalOnProperty / @Profile / @Value / Env vars
    // -------------------------------------------------------------------------

    private static void inspectCodeConfigBindings(
            KnowledgeGraph graph,
            CapturedFileIndex fileIndex,
            Map<String, ConfigCandidate> out,
            Set<String> inspectedSources,
            Supplier<StopReason> stop) {
        int scannedFiles = 0;
        for (var entry : fileIndex.entries()) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            if (!entry.readable()) continue;
            String lang = entry.language().toLowerCase(Locale.ROOT);
            if (!Set.of("java", "kotlin", "typescript", "tsx", "javascript", "python", "go", "rust").contains(lang)) {
                continue;
            }
            if (++scannedFiles > 400) break;

            var textOpt = fileIndex.readText(graph, entry);
            if (textOpt.isEmpty()) continue;
            String raw = textOpt.orElseThrow();
            boolean hasSpringMarker = raw.contains("@ConfigurationProperties")
                    || raw.contains("@ConditionalOnProperty")
                    || raw.contains("@Value(")
                    || raw.contains("@Profile(");
            boolean hasEnvMarker = raw.contains("getenv(") || raw.contains("process.env")
                    || raw.contains("os.environ") || raw.contains("os.Getenv") || raw.contains("env::var");
            if (!hasSpringMarker && !hasEnvMarker) continue;

            var redactedDoc = LlmTransport.redactDocumentForPath(entry.path(), raw);
            var lines = redactedDoc.lines();
            inspectedSources.add(entry.path());

            String activeConfigPrefix = null;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNo = i + 1;

                Matcher cp = CONFIG_PROPS_ANN_RE.matcher(line);
                if (cp.find()) {
                    Matcher qm = QUOTED_ATTR_PREFIX_RE.matcher(cp.group(1));
                    if (qm.find()) {
                        String pref = qm.group(1) != null ? qm.group(1) : qm.group(2);
                        if (pref != null && !pref.isBlank()) {
                            activeConfigPrefix = pref.strip();
                            addCandidate(out, new ConfigCandidate(
                                    activeConfigPrefix, "", entry.path(), lineNo, lineNo,
                                    ConfigSourceKind.CONFIGURATION_PROPERTIES_BINDING,
                                    TrustTier.SOURCE_SYNTAX.name(), "", "@ConfigurationProperties"));
                        }
                    }
                }

                if (activeConfigPrefix != null) {
                    Matcher fm = JAVA_FIELD_RE.matcher(line);
                    if (fm.find()) {
                        String fieldName = fm.group(1);
                        String defaultVal = fm.group(2) == null ? "" : stripQuotes(fm.group(2).strip());
                        String kebab = camelToKebab(fieldName);
                        String fullKey = activeConfigPrefix + "." + kebab;
                        addCandidate(out, new ConfigCandidate(
                                fullKey, defaultVal, entry.path(), lineNo, lineNo,
                                ConfigSourceKind.CONFIGURATION_PROPERTIES_BINDING,
                                TrustTier.SOURCE_SYNTAX.name(), "", "@ConfigurationProperties field " + fieldName));
                    }
                }

                Matcher vm = VALUE_PLACEHOLDER_RE.matcher(line);
                while (vm.find()) {
                    String propKey = vm.group(1).strip();
                    String defVal = vm.group(2) == null ? "" : vm.group(2).strip();
                    addCandidate(out, new ConfigCandidate(
                            propKey, defVal, entry.path(), lineNo, lineNo,
                            ConfigSourceKind.CONFIGURATION_PROPERTIES_BINDING,
                            TrustTier.SOURCE_SYNTAX.name(), "", "@Value"));
                }

                Matcher cond = CONDITIONAL_PROP_RE.matcher(line);
                if (cond.find()) {
                    String body = cond.group(1);
                    String prefix = extractAnnotationAttr(body, "prefix");
                    String name = extractAnnotationAttr(body, "name");
                    if (name.isBlank()) name = extractAnnotationAttr(body, "value");
                    String having = extractAnnotationAttr(body, "havingValue");
                    if (!name.isBlank()) {
                        String fullKey = prefix.isBlank() ? name : (prefix + "." + name);
                        addCandidate(out, new ConfigCandidate(
                                fullKey, having, entry.path(), lineNo, lineNo,
                                ConfigSourceKind.SPRING_PROFILE_OR_CONDITION,
                                TrustTier.SPRING_SAT.name(), "", "@ConditionalOnProperty"));
                    }
                }

                Matcher prof = PROFILE_ANN_RE.matcher(line);
                if (prof.find()) {
                    Matcher qm = QUOTED_VALUE_RE.matcher(prof.group(1));
                    while (qm.find()) {
                        String p = qm.group(1).strip().replaceFirst("^!", "");
                        if (!p.isBlank()) {
                            addCandidate(out, new ConfigCandidate(
                                    "spring.profiles.active", p, entry.path(), lineNo, lineNo,
                                    ConfigSourceKind.SPRING_PROFILE_OR_CONDITION,
                                    TrustTier.SPRING_SAT.name(), p, "@Profile"));
                        }
                    }
                }

                Matcher env = ENV_LOOKUP_RE.matcher(line);
                while (env.find()) {
                    for (int g = 1; g <= env.groupCount(); g++) {
                        String varName = env.group(g);
                        if (varName != null && !varName.isBlank()) {
                            addCandidate(out, new ConfigCandidate(
                                    varName, "", entry.path(), lineNo, lineNo,
                                    ConfigSourceKind.ENVIRONMENT_VARIABLE,
                                    TrustTier.SOURCE_SYNTAX.name(), "", "environment variable lookup"));
                            break;
                        }
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. ADR / README / Markdown config flags (ADVISORY_UNVERIFIED)
    // -------------------------------------------------------------------------

    private static void inspectMarkdownAndAdvisoryFlags(
            KnowledgeGraph graph,
            CapturedFileIndex fileIndex,
            Map<String, ConfigCandidate> out,
            Set<String> inspectedSources,
            Supplier<StopReason> stop) {
        int mdDocs = 0;
        for (var entry : fileIndex.entries()) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            if (!entry.readable() || !"markdown".equalsIgnoreCase(entry.language())) continue;
            String low = entry.path().toLowerCase(Locale.ROOT);
            boolean relevantDoc = low.endsWith("readme.md") || low.contains("adr")
                    || low.contains("decision") || low.contains("config") || low.contains("architecture");
            if (!relevantDoc) continue;
            if (++mdDocs > 20) break;

            var textOpt = fileIndex.readText(graph, entry);
            if (textOpt.isEmpty()) continue;
            var redactedDoc = LlmTransport.redactDocumentForPath(entry.path(), textOpt.orElseThrow());
            var lines = redactedDoc.lines();
            inspectedSources.add(entry.path());

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int lineNo = i + 1;
                Matcher dm = DOTTED_PROP_RE.matcher(line);
                while (dm.find()) {
                    String prop = dm.group(1);
                    if (!looksLikeFileName(prop) && !prop.startsWith("com.") && !prop.startsWith("org.junit")) {
                        addCandidate(out, new ConfigCandidate(
                                prop, "", entry.path(), lineNo, lineNo,
                                ConfigSourceKind.ADR_OR_README_FLAG,
                                AdvisoryOverlay.TRUST, "", "documented flag"));
                    }
                }
                Matcher em = ENV_VAR_RE.matcher(line);
                while (em.find()) {
                    String envName = em.group(1);
                    if (envName.length() >= 5 && !Set.of("README_MD", "ADR_001", "UTF_8", "HTTP_OK").contains(envName)) {
                        addCandidate(out, new ConfigCandidate(
                                envName, "", entry.path(), lineNo, lineNo,
                                ConfigSourceKind.ADR_OR_README_FLAG,
                                AdvisoryOverlay.TRUST, "", "documented env flag"));
                    }
                }
            }
        }

        // Also touch AdvisoryOverlayService opportunistically so ADR links stay wired
        try {
            if (stop == null || stop.get() == StopReason.NONE) {
                var overlay = AdvisoryOverlayService.forGraph(graph);
                if (!overlay.links().isEmpty()) {
                    inspectedSources.add("advisory:" + overlay.schema());
                }
            }
        } catch (RuntimeException ignored) {
            // Opportunistic read-only advisory check never fails the gate.
        }
    }

    // -------------------------------------------------------------------------
    // 5. VerticalTraceEngine conditional Spring DI hops
    // -------------------------------------------------------------------------

    private static int inspectVerticalTraceConditionalHops(
            KnowledgeGraph graph,
            String context,
            String taskPrompt,
            Collection<String> citedPaths,
            String targetEntityId,
            Map<String, ConfigCandidate> out,
            Set<String> inspectedSources,
            Supplier<StopReason> stop) {
        var seeds = new LinkedHashSet<String>();
        if (targetEntityId != null && !targetEntityId.isBlank()) {
            seeds.add(targetEntityId.strip());
        }
        if (citedPaths != null) {
            for (String p : citedPaths) {
                if (p != null && !p.isBlank() && seeds.size() < MAX_VERTICAL_SEEDS) {
                    seeds.add(CapturedFileIndex.normalizePath(p));
                }
            }
        }
        if (seeds.size() < MAX_VERTICAL_SEEDS) {
            for (String tok : extractCamelCaseIdentifiers(taskPrompt)) {
                if (seeds.size() >= MAX_VERTICAL_SEEDS) break;
                seeds.add(tok);
            }
        }
        if (seeds.isEmpty()) return 0;

        String effectiveCtx = (context == null || context.isBlank()) ? graph.snapshot().value() : context;
        int hopsChecked = 0;
        for (String seed : seeds) {
            if (stop != null && stop.get() != StopReason.NONE) break;
            try {
                var req = new VerticalTraceEngine.TraceRequest(effectiveCtx, seed, Optional.empty(), Optional.empty(), 4, 24);
                var trace = VerticalTraceEngine.trace(graph, req);
                if (trace.totalHops() == 0) continue;
                inspectedSources.add("vertical_trace:" + seed);
                for (var hop : trace.hops()) {
                    hopsChecked++;
                    boolean conditional = hop.classification()
                            .map(c -> "MAY".equalsIgnoreCase(c) || "UNKNOWN".equalsIgnoreCase(c))
                            .orElse(false);
                    if (conditional && hop.witnessId().isPresent() && hop.sourcePath().isPresent()) {
                        String sp = hop.sourcePath().orElseThrow();
                        int line = hop.startLine().orElse(1);
                        addCandidate(out, new ConfigCandidate(
                                hop.label(),
                                hop.classification().orElse("MAY") + " (witness=" + hop.witnessId().orElse("") + ")",
                                sp,
                                line,
                                hop.endLine().orElse(line),
                                ConfigSourceKind.VERTICAL_TRACE_CONDITIONAL_HOP,
                                hop.trustTier().name(),
                                "",
                                "conditional Spring DI hop"));
                    }
                }
            } catch (RuntimeException ignored) {
                // Bounded vertical trace is opportunistic; never fail the gate on minimal graphs.
            }
        }
        return hopsChecked;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void addCandidate(Map<String, ConfigCandidate> out, ConfigCandidate cand) {
        if (cand.key().isBlank()) return;
        out.putIfAbsent(cand.dedupeKey(), cand);
    }

    private static FileLineLocation findBestLocation(Map<String, List<FileLineLocation>> index, String key) {
        var list = index.get(normalizeRelaxed(key));
        return (list == null || list.isEmpty()) ? null : list.getFirst();
    }

    private static int sourcePriority(ConfigSourceKind kind) {
        return switch (kind) {
            case CONFIG_FILE_PROPERTY -> 0;
            case SPRING_CONFIG_DATA_EVIDENCE -> 1;
            case CONFIGURATION_PROPERTIES_BINDING -> 2;
            case SPRING_PROFILE_OR_CONDITION -> 3;
            case ENVIRONMENT_VARIABLE -> 4;
            case VERTICAL_TRACE_CONDITIONAL_HOP -> 5;
            case ADR_OR_README_FLAG -> 6;
        };
    }

    static boolean isConfigFilePath(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        String name = low.substring(low.lastIndexOf('/') + 1);
        if (name.startsWith("application") || name.startsWith("bootstrap")) {
            return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
        }
        if (low.contains("/config/") || low.startsWith("config/")) {
            return name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".properties");
        }
        return isEnvFileName(low);
    }

    private static boolean isEnvFileName(String lowPath) {
        String name = lowPath.substring(lowPath.lastIndexOf('/') + 1);
        return name.equals(".env") || name.startsWith(".env.");
    }

    private static Optional<String> extractProfileFromFilename(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) return Optional.empty();
        String base = name.substring(0, dot);
        if (base.startsWith("application-") && base.length() > 12) {
            return Optional.of(base.substring(12));
        }
        if (base.startsWith("bootstrap-") && base.length() > 10) {
            return Optional.of(base.substring(10));
        }
        return Optional.empty();
    }

    private static int findKeyValueSeparator(String line) {
        int eq = line.indexOf('=');
        int col = line.indexOf(':');
        if (eq > 0 && (col < 0 || eq < col)) return eq;
        return col;
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    private static int findYamlColon(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble) {
                if (i + 1 == line.length() || Character.isWhitespace(line.charAt(i + 1))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int findInlineComment(String val) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < val.length(); i++) {
            char c = val.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || Character.isWhitespace(val.charAt(i - 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String joinYamlStack(Deque<YamlIndentKey> stack) {
        var parts = new ArrayList<String>(stack.size());
        for (var entry : stack) {
            parts.add(entry.key());
        }
        Collections.reverse(parts);
        return String.join(".", parts);
    }

    private static String extractAnnotationAttr(String body, String attr) {
        Pattern p = Pattern.compile("\\b" + attr + "\\s*=\\s*(?:\\{\\s*)?\"([^\"]+)\"");
        Matcher m = p.matcher(body);
        if (m.find()) return m.group(1).strip();
        if ("value".equals(attr)) {
            Matcher bare = Pattern.compile("^\\s*\"([^\"]+)\"").matcher(body);
            if (bare.find()) return bare.group(1).strip();
        }
        return "";
    }

    private static String camelToKebab(String camel) {
        var sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('-').append(Character.toLowerCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    static String normalizeRelaxed(String raw) {
        if (raw == null) return "";
        var sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static Set<String> tokenizeDistinctive(String text) {
        var out = new LinkedHashSet<String>();
        if (text == null || text.isBlank()) return out;
        // Split camelCase as well as non-alphanumeric delimiters
        String splitCamel = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        for (String tok : splitCamel.split("[^A-Za-z0-9]+")) {
            String low = tok.toLowerCase(Locale.ROOT);
            if (low.length() >= 3 && !STOP_TOKENS.contains(low)) {
                out.add(low);
            }
        }
        return out;
    }

    private static List<String> extractCamelCaseIdentifiers(String text) {
        var out = new ArrayList<String>();
        Matcher m = Pattern.compile("\\b([A-Z][a-z0-9]+[A-Z][A-Za-z0-9_]{1,64})\\b").matcher(text);
        while (m.find() && out.size() < 4) {
            out.add(m.group(1));
        }
        return out;
    }

    private static boolean containsStandaloneToken(String lowerText, String tokenLower) {
        if (tokenLower == null || tokenLower.isEmpty()) return false;
        int idx = lowerText.indexOf(tokenLower);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !isProfileChar(lowerText.charAt(idx - 1));
            int end = idx + tokenLower.length();
            boolean rightOk = end == lowerText.length() || !isProfileChar(lowerText.charAt(end));
            if (leftOk && rightOk) return true;
            idx = lowerText.indexOf(tokenLower, idx + 1);
        }
        return false;
    }

    private static boolean isProfileChar(char c) {
        return Character.isLetterOrDigit(c) || c == '-' || c == '_';
    }

    private static boolean looksLikeFileName(String dotted) {
        int lastDot = dotted.lastIndexOf('.');
        if (lastDot < 0 || lastDot == dotted.length() - 1) return false;
        String tail = dotted.substring(lastDot + 1).toLowerCase(Locale.ROOT);
        return IGNORED_DOTTED_EXTS.contains(tail);
    }

    private static String firstString(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String s && !s.isBlank() ? s.strip() : fallback;
    }

    private static String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        return LlmTransport.neutralizeInjectionSentinels(LlmTransport.redactSecrets(raw).text()).strip();
    }

    private static String sanitizeValue(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String safe = sanitize(raw);
        return safe.length() > 120 ? safe.substring(0, 117) + "..." : safe;
    }

    private static Map<String, Object> readSmall(GraphEvidence ev) {
        if (ev.canonicalJson().length() > 131_072) return Map.of();
        try {
            return GraphJson.object(GraphJson.read(ev.canonicalJson(), new GraphJson.Limits(131_072, 32, 8_192)));
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }
}
