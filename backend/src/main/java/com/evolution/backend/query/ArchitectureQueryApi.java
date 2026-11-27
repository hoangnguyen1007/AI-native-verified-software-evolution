package com.evolution.backend.query;

import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2;
import com.evolution.analysis.assessment.v2.ArchitectureAssessmentV2Service;
import com.evolution.analysis.assessment.v2.ArchitectureScoreModelV2;
import com.evolution.analysis.contract.assessment.AssessmentStatus;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evidence.EvidenceContext;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.PolicyCompiler;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.policy.PolicyEvaluator;
import com.evolution.analysis.policy.PolicyGraphMapper;
import com.evolution.analysis.policy.PolicyImpactService;
import com.evolution.analysis.javaparser.slice.JavaMethodSlicer;
import com.evolution.analysis.query.*;
import com.evolution.analysis.query.projection.DsmMatrixBuilder;
import com.evolution.analysis.query.projection.GraphProjectionService;
import com.evolution.analysis.query.projection.TarjanSccAnalyzer;
import com.evolution.analysis.query.trace.MethodSliceResult;
import com.evolution.analysis.query.trace.VerticalTraceEngine;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.AnalysisMode;
import com.evolution.backend.analysis.RepositoryConnections;
import com.evolution.backend.analysis.WorkbenchPreviewStore;
import com.evolution.backend.github.GitHubHttpApi;
import com.evolution.backend.query.chat.ChatConversationStore;
import com.evolution.backend.query.llm.LlmConfigRegistry;
import com.evolution.backend.query.repair.BehavioralDiffService;
import com.evolution.backend.query.repair.GitWorktreeManager;
import com.evolution.backend.query.repair.RepairAgentTools;
import com.evolution.backend.query.repair.RepairVerificationJobs;
import com.evolution.backend.query.repair.RepairVerificationReport;
import com.evolution.backend.query.repair.SafePatchApplier;
import com.evolution.backend.query.repair.SandboxSetupService;
import com.evolution.backend.query.repair.SnapshotTreeMaterializer;
import com.evolution.backend.query.repair.SpringPreservationChecker;
import com.evolution.backend.query.search.CapturedSearchIndex;
import com.evolution.backend.query.trace.FullStackVerticalTraceService;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import static com.evolution.analysis.query.ArchitectureQuery.*;

/**
 * Replaceable HTTP/JSON adapter. Domain queries and all evidence arithmetic
 * remain in analyzer.
 */
public final class ArchitectureQueryApi {
    public static final String VERSION = "architecture.http:m7.1-v1";
    public static final List<String> OPERATIONS = List.of("summary", "provenance", "entities", "entity", "edges",
            "dependencies",
            "relationships", "expand", "paths", "shortest-path", "cycles", "reachability", "impact", "evidence",
            "explain", "gaps",
            "spring", "configurations", "artifacts", "metric-catalog", "metric", "comparison-preparation",
            "visual-graph", "children", "realizations", "metric-definition");
    public static final List<String> REPORT_OPERATIONS = List.of("rules", "findings", "finding", "bundle",
            "policy-metrics", "assessment", "policy-impact", "visual-graph", "replay", "diagnosis", "repair-options",
            "repair-proposal", "repair-reanalysis", "repair-verification");
    private static final FindingDiagnosis REPLAY_DIAGNOSIS = FindingDiagnosis.localReplay();
    private static final RepairProposal REPAIR = new RepairProposal();

    public record Response(int status, byte[] body, Map<String, List<String>> headers) {
        public Response(int status, byte[] body) {
            this(status, body, Map.of());
        }

        public Response {
            body = body == null ? new byte[0] : body.clone();
            if (headers == null || headers.isEmpty()) {
                headers = Map.of();
            } else {
                var copy = new LinkedHashMap<String, List<String>>();
                for (var e : headers.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        copy.put(e.getKey(), List.copyOf(e.getValue()));
                    }
                }
                headers = Collections.unmodifiableMap(copy);
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    /** Optional capability for GraphStore wrappers exposing durable .graph.json paths for discovery caching. */
    public interface GraphStorageLocation {
        java.nio.file.Path jsonPath(ContentDigest identity);
    }

    private static final class Failure extends RuntimeException {
        final int status;
        final String code;

        Failure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }

    private static final class SupersededFailure extends RuntimeException {
        final ContentDigest graphId;
        final Optional<ContentDigest> supersededBy;

        SupersededFailure(ContentDigest graphId, Optional<ContentDigest> supersededBy) {
            super("PREVIEW_SUPERSEDED");
            this.graphId = graphId;
            this.supersededBy = supersededBy;
        }
    }

    private final GraphStore store;
    private WorkbenchPreviewStore previewStore;
    private com.evolution.analysis.execution.RegisteredExecutionPort executions;
    private final PublishedReports reports;
    private final ApiLimits limits;
    private final BiFunction<KnowledgeGraph, Supplier<StopReason>, ArchitectureQueryService> factory;
    private AnalysisJobs analysisJobs;
    private AnalysisJobApi jobs;
    private GitHubHttpApi github;
    private com.evolution.backend.auth.AuthHttpApi auth;
    private SemanticQueryApi semantics;
    private BridgeQueryApi bridges;
    private EvolutionHttpApi evolution;
    private Function<BooleanSupplier, PartitionedGraphReader> partitionReaders;
    private RepositoryConnections connections;
    private RepairVerification.WorkerEvidence repairWorker;
    private DemoRepairCapture demoRepairCapture;
    private FindingDiagnosis diagnosis = REPLAY_DIAGNOSIS;
    private RepairChatModel repairModel;
    private ArchitectureChatModel architectureChat;
    private RepairProposalStore repairStore = new RepairProposalStore();
    private RepairReanalysis.Port repairReanalysis;
    private CapturedRepairExecution capturedRepairExecution;
    private volatile CapturedRepairRunner capturedRepairRunner;
    private volatile SandboxSetupService sandboxSetup;
    private LlmConfigRegistry llmRegistry;
    private RepairVerificationJobs repairVerificationJobs;
    private SafePatchApplier safePatchApplier;
    private java.nio.file.Path acquisitionContentDir;
    private java.nio.file.Path storeDirectory;
    private CapturedSearchIndex searchIndex;
    private ChatConversationStore chatStore;
    private java.util.Map<com.evolution.analysis.contract.identity.SnapshotIdentity, ContentDigest> reanalyzedGraphs = java.util.Collections
            .synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<com.evolution.analysis.contract.identity.SnapshotIdentity, ContentDigest> eldest) {
                    return size() > 128;
                }
            });

    private record CachedDiscovery(String snapshot, List<Map<String, Object>> contexts) {
    }

    private java.util.Map<ContentDigest, CachedDiscovery> discoveryContextCache = java.util.Collections
            .synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<ContentDigest, CachedDiscovery> eldest) {
                    return size() > 64;
                }
            });

    public ArchitectureQueryApi(GraphStore store) {
        this(store, new PublishedReports(), ApiLimits.defaults());
    }

    public ArchitectureQueryApi(GraphStore store, PublishedReports reports, ApiLimits limits) {
        this(store, reports, limits, (graph, guard) -> new ArchitectureMetricsService(graph,
                ArchitectureMetric.Limits.defaults(), guard, Optional.empty(), List.of()).queries());
    }

    /**
     * Provider injection supports embedding and explicit
     * absent-provider/cancellation contract checks.
     */
    public ArchitectureQueryApi(GraphStore store, PublishedReports reports, ApiLimits limits,
            BiFunction<KnowledgeGraph, Supplier<StopReason>, ArchitectureQueryService> factory) {
        this.store = Objects.requireNonNull(store);
        this.reports = Objects.requireNonNull(reports);
        this.limits = Objects.requireNonNull(limits);
        this.factory = Objects.requireNonNull(factory);
    }

    public ApiLimits limits() {
        return limits;
    }

    /**
     * A local operator can opt in one exact diagnosis input. No request can set a
     * model route or change its pin.
     */
    public static ArchitectureQueryApi withConfiguredDemoDiagnosis(ArchitectureQueryApi existing) {
        var port = System.getProperty("evo.demo.diagnosis.port");
        var model = System.getProperty("evo.demo.diagnosis.model");
        var input = System.getProperty("evo.demo.diagnosis.input");
        if (port == null && model == null && input == null)
            return existing;
        if (port == null || model == null || input == null)
            throw new IllegalArgumentException("DEMO_DIAGNOSIS_CONFIGURATION");
        return withDemoDiagnosis(existing, Integer.parseInt(port), model, new ContentDigest(input));
    }

    static ArchitectureQueryApi withDemoDiagnosis(ArchitectureQueryApi existing, int port, String model,
            ContentDigest input) {
        if (existing.diagnosis != REPLAY_DIAGNOSIS)
            throw new IllegalArgumentException("DEMO_DIAGNOSIS_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.diagnosis = new FindingDiagnosis(new LoopbackDiagnosisModel(port, model, input));
        return api;
    }

    /**
     * Opt in a fixed model endpoint at startup; client requests can only approve a
     * pinned input.
     */
    public static ArchitectureQueryApi withConfiguredRepairModel(ArchitectureQueryApi existing) {
        var endpoint = System.getProperty("evo.repair.ai.endpoint", environment("EVO_REPAIR_AI_ENDPOINT"));
        var model = System.getProperty("evo.repair.ai.model", environment("EVO_REPAIR_AI_MODEL"));
        boolean remote = Boolean.parseBoolean(
                System.getProperty("evo.repair.ai.allowRemote", environment("EVO_REPAIR_AI_ALLOW_REMOTE")));
        if (endpoint == null && model == null && !remote)
            return existing;
        if (endpoint == null || model == null)
            throw new IllegalArgumentException("REPAIR_MODEL_CONFIGURATION");
        var uri = ChatCompletions.parse(endpoint, "REPAIR_MODEL_CONFIGURATION");
        if (remoteDisabled(uri, remote, "EVO_REPAIR_AI_ALLOW_REMOTE"))
            return existing;
        return withRepairModel(existing, new RepairChatModel(uri, model, environment("EVO_REPAIR_AI_API_KEY"), remote,
                ChatCompletions.REPAIR.configured(setting -> environment("EVO_REPAIR_AI_" + setting))));
    }

    /**
     * Configure a fixed provider for conversational advice independently of
     * diagnosis and patch generation.
     */
    public static ArchitectureQueryApi withConfiguredArchitectureChat(ArchitectureQueryApi existing) {
        var endpoint = environment("EVO_ARCH_CHAT_ENDPOINT");
        var model = environment("EVO_ARCH_CHAT_MODEL");
        boolean remote = Boolean.parseBoolean(environment("EVO_ARCH_CHAT_ALLOW_REMOTE"));
        if (endpoint == null && model == null && !remote)
            return existing;
        if (endpoint == null || model == null)
            throw new IllegalArgumentException("ARCHITECTURE_CHAT_CONFIGURATION");
        var uri = ChatCompletions.parse(endpoint, "ARCHITECTURE_CHAT_CONFIGURATION");
        if (remoteDisabled(uri, remote, "EVO_ARCH_CHAT_ALLOW_REMOTE"))
            return existing;
        return withArchitectureChat(existing,
                new ArchitectureChatModel(uri, model, environment("EVO_ARCH_CHAT_API_KEY"), remote,
                        ChatCompletions.CHAT.configured(setting -> environment("EVO_ARCH_CHAT_" + setting))));
    }

    private static boolean remoteDisabled(java.net.URI uri, boolean enabled, String flag) {
        if (enabled || ChatCompletions.loopback(uri) || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null)
            return false;
        System.err.println("Remote model disabled. Set " + flag + "=true to enable it.");
        return true;
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }

    static ArchitectureQueryApi withArchitectureChat(ArchitectureQueryApi existing, ArchitectureChatModel model) {
        if (existing.architectureChat != null)
            throw new IllegalArgumentException("ARCHITECTURE_CHAT_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.architectureChat = Objects.requireNonNull(model);
        return api;
    }

    static ArchitectureQueryApi withRepairModel(ArchitectureQueryApi existing, RepairChatModel model) {
        if (existing.repairModel != null)
            throw new IllegalArgumentException("REPAIR_MODEL_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.repairModel = Objects.requireNonNull(model);
        return api;
    }

    private ArchitectureQueryApi(ArchitectureQueryApi existing) {
        this(existing.store, existing.reports, existing.limits, existing.factory);
        previewStore = existing.previewStore;
        analysisJobs = existing.analysisJobs;
        jobs = existing.jobs;
        github = existing.github;
        auth = existing.auth;
        connections = existing.connections;
        executions = existing.executions;
        semantics = existing.semantics;
        bridges = existing.bridges;
        evolution = existing.evolution;
        partitionReaders = existing.partitionReaders;
        repairWorker = existing.repairWorker;
        demoRepairCapture = existing.demoRepairCapture;
        diagnosis = existing.diagnosis;
        repairModel = existing.repairModel;
        architectureChat = existing.architectureChat;
        repairStore = existing.repairStore;
        repairReanalysis = existing.repairReanalysis;
        capturedRepairExecution = existing.capturedRepairExecution;
        capturedRepairRunner = existing.capturedRepairRunner;
        sandboxSetup = existing.sandboxSetup;
        llmRegistry = existing.llmRegistry;
        repairVerificationJobs = existing.repairVerificationJobs;
        safePatchApplier = existing.safePatchApplier;
        acquisitionContentDir = existing.acquisitionContentDir;
        storeDirectory = existing.storeDirectory;
        searchIndex = existing.searchIndex;
        chatStore = existing.chatStore;
        reanalyzedGraphs = existing.reanalyzedGraphs;
        discoveryContextCache = existing.discoveryContextCache;
    }

    /**
     * Persist and replay syntax-checked captured proposals outside the demo
     * fixture.
     */
    public static ArchitectureQueryApi withRepairStore(ArchitectureQueryApi existing, RepairProposalStore store) {
        var api = new ArchitectureQueryApi(existing);
        api.repairStore = Objects.requireNonNull(store);
        return api;
    }

    /**
     * Attach passive multi-module candidate reanalysis without target code
     * execution.
     */
    public static ArchitectureQueryApi withRepairReanalysis(ArchitectureQueryApi existing, RepairReanalysis.Port port) {
        if (existing.repairReanalysis != null)
            throw new IllegalArgumentException("REPAIR_REANALYSIS_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.repairReanalysis = Objects.requireNonNull(port);
        return api;
    }

    /**
     * Operator-configured isolated QEMU VM capture or capture directory for
     * arbitrary multi-module repos.
     */
    public static ArchitectureQueryApi withConfiguredCapturedRepairCapture(ArchitectureQueryApi existing)
            throws java.io.IOException {
        var path = System.getProperty("evo.repair.capture");
        var pin = System.getProperty("evo.repair.capture.pin");
        var dir = System.getProperty("evo.repair.capture.dir");
        if (path == null && pin == null && dir == null)
            return existing;
        if (existing.capturedRepairExecution != null)
            throw new IllegalArgumentException("CAPTURED_REPAIR_CAPTURE_ALREADY_CONFIGURED");
        if (dir != null && (path != null || pin != null))
            throw new IllegalArgumentException("CAPTURED_REPAIR_CAPTURE_CONFIGURATION");
        if (dir == null && (path == null || pin == null))
            throw new IllegalArgumentException("CAPTURED_REPAIR_CAPTURE_CONFIGURATION");
        var api = new ArchitectureQueryApi(existing);
        api.capturedRepairExecution = dir != null
                ? new CapturedRepairExecution(java.nio.file.Path.of(dir))
                : new CapturedRepairExecution(java.nio.file.Path.of(path), new ContentDigest(pin));
        return api;
    }

    static ArchitectureQueryApi withCapturedRepairExecution(ArchitectureQueryApi existing,
            CapturedRepairExecution execution) {
        if (existing.capturedRepairExecution != null)
            throw new IllegalArgumentException("CAPTURED_REPAIR_CAPTURE_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.capturedRepairExecution = Objects.requireNonNull(execution);
        return api;
    }

    /**
     * Opt in generic isolated QEMU-in-Docker runner for repair verification outside
     * the fixture.
     */
    public static ArchitectureQueryApi withCapturedRepairRunner(ArchitectureQueryApi existing,
            CapturedRepairRunner runner) {
        if (existing.capturedRepairRunner != null)
            throw new IllegalArgumentException("CAPTURED_REPAIR_RUNNER_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.capturedRepairRunner = Objects.requireNonNull(runner);
        if (api.sandboxSetup != null) {
            api.sandboxSetup.setExternalRunner(runner, "SYSTEM_PROPERTIES");
        }
        return api;
    }

    public static ArchitectureQueryApi withConfiguredCapturedRepairRunner(ArchitectureQueryApi existing) {
        var config = CapturedRepairRunner.fromSystemProperties();
        if (config.isEmpty())
            return existing;
        return withCapturedRepairRunner(existing, new CapturedRepairRunner(config.orElseThrow()));
    }

    public static ArchitectureQueryApi withSandboxSetup(ArchitectureQueryApi existing, SandboxSetupService setup) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.sandboxSetup = Objects.requireNonNull(setup);
        api.sandboxSetup.bindServerPolicy(api.storeDirectory, api.auth != null && api.auth.isMultiUserMode());
        if (api.capturedRepairRunner != null && !setup.isConfigured()) {
            setup.setExternalRunner(api.capturedRepairRunner, "SYSTEM_PROPERTIES");
        }
        return api;
    }

    private CapturedRepairRunner activeRepairRunner() {
        return sandboxSetup != null ? sandboxSetup.activeRunner() : capturedRepairRunner;
    }

    private synchronized SandboxSetupService ensureSandboxSetup() {
        boolean multi = (auth != null && auth.isMultiUserMode())
                || com.evolution.backend.auth.RequestContext.isMultiUserMode();
        if (sandboxSetup == null) {
            java.nio.file.Path defaultWork = storeDirectory != null
                    ? storeDirectory.resolve("repairs").resolve("sandbox-work")
                    : java.nio.file.Path.of(System.getProperty("java.io.tmpdir", "."), "evo-sandbox-work");
            var jobStore = repairVerificationJobs != null ? repairVerificationJobs.store() : null;
            sandboxSetup = new SandboxSetupService(defaultWork, jobStore, capturedRepairRunner, storeDirectory, multi);
        } else {
            sandboxSetup.bindServerPolicy(storeDirectory, multi);
        }
        return sandboxSetup;
    }

    /**
     * Opt in a general operator-configured Chat Completions model for structural
     * finding diagnosis.
     */
    public static ArchitectureQueryApi withConfiguredLiveDiagnosis(ArchitectureQueryApi existing) {
        var endpoint = System.getProperty("evo.diagnosis.ai.endpoint");
        var model = System.getProperty("evo.diagnosis.ai.model");
        boolean remote = Boolean.getBoolean("evo.diagnosis.ai.allowRemote");
        if (endpoint == null && model == null && !remote)
            return existing;
        if (endpoint == null || model == null)
            throw new IllegalArgumentException("DIAGNOSIS_MODEL_CONFIGURATION");
        return withLiveDiagnosis(existing, new ChatDiagnosisModel(java.net.URI.create(endpoint), model,
                System.getenv("EVO_DIAGNOSIS_AI_API_KEY"), remote));
    }

    static ArchitectureQueryApi withLiveDiagnosis(ArchitectureQueryApi existing, FindingDiagnosis.Model model) {
        if (existing.diagnosis != REPLAY_DIAGNOSIS)
            throw new IllegalArgumentException("DIAGNOSIS_MODEL_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.diagnosis = new FindingDiagnosis(Objects.requireNonNull(model));
        return api;
    }

    /** Local operator configuration outside all selected source and HTTP inputs. */
    public static ArchitectureQueryApi withConfiguredDemoRepairCapture(ArchitectureQueryApi existing)
            throws java.io.IOException {
        var path = System.getProperty("evo.demo.repair.capture");
        var pin = System.getProperty("evo.demo.repair.capture.pin");
        if (path == null && pin == null)
            return existing;
        if (path == null || pin == null || existing.demoRepairCapture != null)
            throw new IllegalArgumentException("DEMO_CAPTURE_CONFIGURATION");
        var api = new ArchitectureQueryApi(existing);
        api.demoRepairCapture = new DemoRepairCapture(java.nio.file.Path.of(path), new ContentDigest(pin));
        return api;
    }

    /**
     * Local operator composition. Target inputs and request JSON never register
     * evidence authorities.
     */
    public static ArchitectureQueryApi withRepairWorkerEvidence(ArchitectureQueryApi existing,
            RepairVerification.WorkerEvidence evidence) {
        if (existing.repairWorker != null)
            throw new IllegalArgumentException("REPAIR_WORKER_EVIDENCE_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.repairWorker = Objects.requireNonNull(evidence);
        return api;
    }

    /**
     * Operator composition only. Request JSON cannot register an executor or launch
     * policy.
     */
    public static ArchitectureQueryApi withExecutions(GraphStore store, PublishedReports reports, ApiLimits limits,
            com.evolution.analysis.execution.RegisteredExecutionPort executions) {
        return withExecutions(new ArchitectureQueryApi(store, reports, limits), executions);
    }

    /**
     * Preserve existing analysis jobs, connections and query factory when adding
     * execution access.
     */
    public static ArchitectureQueryApi withExecutions(ArchitectureQueryApi existing,
            com.evolution.analysis.execution.RegisteredExecutionPort executions) {
        Objects.requireNonNull(existing);
        if (existing.executions != null)
            throw new IllegalArgumentException("EXECUTION_PORT_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.executions = Objects.requireNonNull(executions);
        return api;
    }

    public static ArchitectureQueryApi withSemanticQueries(ArchitectureQueryApi existing,
            com.evolution.analysis.polyglot.SemanticQueryService queries) {
        Objects.requireNonNull(existing);
        if (existing.semantics != null)
            throw new IllegalArgumentException("SEMANTIC_QUERY_SERVICE_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.semantics = new SemanticQueryApi(queries, existing.limits);
        return api;
    }

    public static ArchitectureQueryApi withBridgeQueries(ArchitectureQueryApi existing,
            com.evolution.analysis.polyglot.BridgeQueryService queries) {
        Objects.requireNonNull(existing);
        if (existing.bridges != null)
            throw new IllegalArgumentException("BRIDGE_QUERY_SERVICE_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.bridges = new BridgeQueryApi(queries, existing.limits);
        return api;
    }

    public static ArchitectureQueryApi withPartitionedGraphs(ArchitectureQueryApi existing,
            Function<BooleanSupplier, PartitionedGraphReader> readers) {
        Objects.requireNonNull(existing);
        if (existing.partitionReaders != null)
            throw new IllegalArgumentException("PARTITION_READER_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.partitionReaders = Objects.requireNonNull(readers);
        return api;
    }

    public static ArchitectureQueryApi withEvolution(ArchitectureQueryApi existing, EvolutionJobs jobs,
            EvolutionQueries queries) {
        if (existing.evolution != null)
            throw new IllegalArgumentException("EVOLUTION_SERVICE_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.evolution = new EvolutionHttpApi(jobs, queries, existing.limits);
        return api;
    }

    public static ArchitectureQueryApi withPreviewStore(ArchitectureQueryApi existing,
            WorkbenchPreviewStore previewStore) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.previewStore = Objects.requireNonNull(previewStore);
        return api;
    }

    public static ArchitectureQueryApi withGitHub(ArchitectureQueryApi existing, GitHubHttpApi github) {
        Objects.requireNonNull(existing);
        if (existing.github != null)
            throw new IllegalArgumentException("GITHUB_API_ALREADY_CONFIGURED");
        var api = new ArchitectureQueryApi(existing);
        api.github = Objects.requireNonNull(github);
        return api;
    }

    public static ArchitectureQueryApi withAuth(ArchitectureQueryApi existing,
            com.evolution.backend.auth.AuthHttpApi auth) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.auth = Objects.requireNonNull(auth);
        return api;
    }

    public com.evolution.backend.auth.AuthHttpApi auth() {
        return auth;
    }

    public GitHubHttpApi github() {
        return github;
    }

    /** Attach a unified multi-provider LLM registry and bind unconfigured roles (Chat, Repair, Diagnosis). */
    public static ArchitectureQueryApi withLlmRegistry(ArchitectureQueryApi existing, LlmConfigRegistry registry) {
        Objects.requireNonNull(existing);
        Objects.requireNonNull(registry);
        var api = new ArchitectureQueryApi(existing);
        api.llmRegistry = registry;
        if (api.architectureChat == null) {
            registry.adapter(LlmConfigRegistry.Role.CHAT)
                    .ifPresent(adapter -> api.architectureChat = ArchitectureChatModel.fromAdapter(adapter));
        }
        if (api.repairModel == null) {
            registry.adapter(LlmConfigRegistry.Role.REPAIR)
                    .ifPresent(adapter -> api.repairModel = RepairChatModel.fromAdapter(adapter));
        }
        if (api.diagnosis == REPLAY_DIAGNOSIS) {
            registry.adapter(LlmConfigRegistry.Role.DIAGNOSIS)
                    .ifPresent(adapter -> api.diagnosis = new FindingDiagnosis(new ChatDiagnosisModel(adapter)));
        }
        return api;
    }

    public static ArchitectureQueryApi withConfiguredLlmRegistry(ArchitectureQueryApi existing) {
        return withLlmRegistry(existing, LlmConfigRegistry.fromEnvironment());
    }

    public static ArchitectureQueryApi withRepairVerificationJobs(ArchitectureQueryApi existing,
            RepairVerificationJobs jobs) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.repairVerificationJobs = Objects.requireNonNull(jobs);
        return api;
    }

    public static ArchitectureQueryApi withSafePatchApplier(ArchitectureQueryApi existing,
            SafePatchApplier applier) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.safePatchApplier = Objects.requireNonNull(applier);
        return api;
    }

    public static ArchitectureQueryApi withAcquisitionContentDir(ArchitectureQueryApi existing,
            java.nio.file.Path contentDir) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.acquisitionContentDir = Objects.requireNonNull(contentDir).toAbsolutePath().normalize();
        return api;
    }

    public static ArchitectureQueryApi withStoreDirectory(ArchitectureQueryApi existing,
            java.nio.file.Path storeDir) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.storeDirectory = Objects.requireNonNull(storeDir).toAbsolutePath().normalize();
        return api;
    }

    /** Attach a single-owner SQLite FTS5 BM25 pre-redacted search index ({@code <store>/search/fts5.db}). */
    public static ArchitectureQueryApi withSearchIndex(ArchitectureQueryApi existing,
            CapturedSearchIndex index) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.searchIndex = Objects.requireNonNull(index);
        return api;
    }

    /** Attach a single-owner SQLite persistent chat conversation store ({@code <store>/chat/conversations.db}). */
    public static ArchitectureQueryApi withChatStore(ArchitectureQueryApi existing,
            ChatConversationStore chatStore) {
        Objects.requireNonNull(existing);
        var api = new ArchitectureQueryApi(existing);
        api.chatStore = Objects.requireNonNull(chatStore);
        return api;
    }

    RepairVerificationJobs repairVerificationJobs() {
        return repairVerificationJobs;
    }

    Optional<String> repairJobReportMarkdown(UUID jobId) {
        return repairVerificationJobs == null
                ? Optional.empty()
                : repairVerificationJobs.reportMarkdown(com.evolution.backend.auth.RequestContext.currentUserId(), jobId);
    }

    /** Job and query adapters share the same canonical publication stores. */
    public ArchitectureQueryApi(GraphStore store, PublishedReports reports, ApiLimits limits, AnalysisJobs jobs) {
        this(store, reports, limits);
        if (!Objects.requireNonNull(jobs).uses(store, reports))
            throw new IllegalArgumentException("Job and query publication stores differ");
        this.analysisJobs = jobs;
        this.previewStore = jobs.previewStore();
        this.jobs = new AnalysisJobApi(Objects.requireNonNull(jobs), limits);
    }

    public ArchitectureQueryApi(GraphStore store, PublishedReports reports, ApiLimits limits, AnalysisJobs jobs,
            RepositoryConnections connections) {
        this(store, reports, limits, jobs);
        this.connections = Objects.requireNonNull(connections);
        this.jobs = new AnalysisJobApi(jobs, limits, connections);
    }

    public Response handle(String method, String rawPath, String contentType, byte[] body, BooleanSupplier cancelled) {
        return handle(method, rawPath, null, contentType, body, "http://127.0.0.1", cancelled);
    }

    public Response handle(String method, String rawPath, String rawQuery, String contentType, byte[] body,
            BooleanSupplier cancelled) {
        return handle(method, rawPath, rawQuery, contentType, body, "http://127.0.0.1", cancelled);
    }

    public Response handle(String method, String rawPath, String rawQuery, String contentType, byte[] body,
            String serverOrigin, BooleanSupplier cancelled) {
        try {
            var caller = com.evolution.backend.auth.RequestContext.currentUser();
            if (caller.authMethod() == com.evolution.backend.auth.AuthenticatedUser.AuthMethod.PERSONAL_ACCESS_TOKEN
                    && !isPatAuthorized(caller.scopes(), method, rawPath)) {
                return error(403, "INSUFFICIENT_TOKEN_SCOPE");
            }
            if (rawPath.startsWith(com.evolution.backend.auth.AuthHttpApi.PREFIX)) {
                return auth == null ? error(503, "AUTH_UNAVAILABLE")
                        : auth.handle(method, rawPath, rawQuery, contentType, body, serverOrigin, cancelled);
            }
            if (auth != null && auth.isMultiUserMode() && !isPublicMultiUserRoute(method, rawPath)
                    && !com.evolution.backend.auth.RequestContext.isAuthenticatedSession()) {
                return error(401, "AUTH_REQUIRED");
            }
            if (rawPath.startsWith(GitHubHttpApi.PREFIX))
                return github == null ? error(503, "GITHUB_INTEGRATION_UNAVAILABLE")
                        : github.handle(method, rawPath, rawQuery, contentType, body, cancelled);
            if (rawPath.startsWith("/api/v1/graphs/") && rawPath.endsWith("/codeowners")) {
                return handleGraphCodeowners(method, rawPath, rawQuery, body);
            }
            if (rawPath.startsWith("/api/v1/graphs/") && rawPath.endsWith("/advisory-links")) {
                return handleGraphAdvisoryLinks(method, rawPath, rawQuery, body);
            }
            if (rawPath.startsWith("/api/v1/graphs/") && rawPath.endsWith("/wiki-dossier")) {
                return handleGraphWikiDossier(method, rawPath, rawQuery, body, cancelled);
            }
            if (rawPath.startsWith("/api/v1/graphs/") && rawPath.endsWith("/conversations")) {
                return handleGraphConversations(method, rawPath, rawQuery, contentType, body);
            }
            if (rawPath.startsWith("/api/v1/conversations/")) {
                return handleConversationById(method, rawPath, body);
            }
            if (rawPath.equals("/api/v1/mcp/setup")) {
                return handleMcpSetup(method, rawQuery, serverOrigin, body);
            }
            if (rawPath.startsWith("/api/v1/graphs/") && rawPath.endsWith("/sarif")) {
                return handleReportSarif(method, rawPath, rawQuery, body);
            }
            if (rawPath.equals("/api/v1/ai/providers") || rawPath.equals("/api/v1/ai/providers/probe"))
                return handleAiProviders(method, rawPath, contentType, body, cancelled);
            if (rawPath.startsWith("/api/v1/repairs/"))
                return handleRepairsRoute(method, rawPath, contentType, body, cancelled);
            if (rawPath.startsWith("/api/v1/bridges/"))
                return bridges == null ? error(503, "BRIDGE_QUERIES_UNAVAILABLE")
                        : bridges.handle(method, rawPath, contentType, body, cancelled);
            if (rawPath.startsWith("/api/v1/semantic/"))
                return semantics == null ? SemanticQueryApi.unavailable(limits)
                        : semantics.handle(method, rawPath, contentType, body, cancelled);
            if (body.length > limits.requestBytes())
                throw new Failure(413, "REQUEST_TOO_LARGE");
            if (rawPath.startsWith(EvolutionHttpApi.PREFIX)) {
                if (evolution == null)
                    return error(503, "EVOLUTION_SERVICE_UNAVAILABLE");
                if (!Arrays.asList(allowedMethods(rawPath).split(", ")).contains(method))
                    return error(405, "METHOD_NOT_ALLOWED");
                if (!method.equals("POST"))
                    requireEmpty(body);
                return evolution.handle(this, method, rawPath,
                        method.equals("POST") ? decode(body, contentType) : Map.of(), cancelled);
            }
            if (rawPath.startsWith("/api/v1/graph-pages/"))
                return graphPages(method, rawPath, contentType, body, cancelled);
            if (rawPath.startsWith("/api/v2/executions/"))
                return execution(method, rawPath, contentType, body, cancelled);
            if (rawPath.startsWith("/api/v2/analysis/jobs/"))
                return jobs == null ? error(503, "JOBS_UNAVAILABLE")
                        : jobs.handle(method, rawPath, contentType, body, cancelled);
            if (rawPath.equals("/api/v1/analysis/jobs") && "POST".equals(method) && analysisJobs != null) {
                return submitAnalysisJobWithRef(contentType, body, cancelled);
            }
            if (rawPath.startsWith("/api/v2/acquisitions/")) {
                if (connections == null)
                    throw new Failure(503, "CONNECTIONS_UNAVAILABLE");
                if (!method.equals("POST"))
                    throw new Failure(405, "METHOD_NOT_ALLOWED");
                var input = decode(body, contentType);
                Object data;
                try {
                    if (rawPath.equals("/api/v2/acquisitions/history")) {
                        if (!Set.of("after", "size").containsAll(input.keySet()))
                            throw new IllegalArgumentException();
                        long after = input.containsKey("after")
                                ? new java.math.BigDecimal(input.get("after").toString()).longValueExact()
                                : 0;
                        int size = input.containsKey("size")
                                ? new java.math.BigDecimal(input.get("size").toString()).intValueExact()
                                : 20;
                        data = connections.acquisitionHistory(after, size);
                    } else if (rawPath.endsWith("/overview")) {
                        QueryJson.object(input);
                        data = connections.publishedOverview(rawPath.substring("/api/v2/acquisitions/".length(),
                                rawPath.length() - "/overview".length()));
                    } else if (rawPath.endsWith("/readiness")) {
                        if (!Set.of("section", "size", "cursor").containsAll(input.keySet()))
                            throw new IllegalArgumentException();
                        var paging = new TreeMap<String, Object>(input);
                        Object section = paging.remove("section");
                        var page = QueryJson.page(paging);
                        if (section != null && !(section instanceof String))
                            throw new IllegalArgumentException();
                        data = connections.readiness(
                                rawPath.substring("/api/v2/acquisitions/".length(),
                                        rawPath.length() - "/readiness".length()),
                                section == null ? "requirements" : (String) section, page.size(), page.cursor());
                    } else {
                        if (!Set.of("size", "cursor").containsAll(input.keySet()))
                            throw new IllegalArgumentException();
                        var page = QueryJson.page(input);
                        data = connections.acquisition(rawPath.substring("/api/v2/acquisitions/".length()), page.size(),
                                page.cursor());
                    }
                } catch (java.io.IOException unavailable) {
                    throw new Failure(503, "ACQUISITION_STORE_UNAVAILABLE");
                }
                byte[] encoded = BoundedJson.encode(data, limits.responseBytes());
                return new Response(200, BoundedJson.encode(Map.of("apiVersion", "repository.acquisition-api:m8.4-v2",
                        "data", data, "resultIdentity", ContentDigest.sha256(encoded)), limits.responseBytes()));
            }
            if (rawPath.equals("/api/v1/analysis") || rawPath.startsWith("/api/v1/analysis/"))
                return jobs == null ? error(503, "JOBS_UNAVAILABLE")
                        : jobs.handle(method, rawPath, contentType, body, cancelled);
            Object data = route(method, rawPath, contentType, body, cancelled);
            byte[] canonicalData = BoundedJson.encode(data, limits.responseBytes());
            return new Response(200,
                    BoundedJson.encode(Map.of("apiVersion", VERSION, "queryVersion", ArchitectureQuery.VERSION,
                            "resultIdentity", ContentDigest.sha256(canonicalData), "data", data),
                            limits.responseBytes()));
        } catch (SupersededFailure gone) {
            return supersededError(gone);
        } catch (Failure failure) {
            return error(failure.status, failure.code);
        } catch (ArchitectureInsightsAdvisor.CalculationInterrupted stopped) {
            return stopped.reason == StopReason.CANCELLED
                    ? error(409, "REQUEST_CANCELLED")
                    : error(503, "INSIGHTS_RESOURCE_LIMIT");
        } catch (ArchitectureAssessmentV2Service.Interrupted stopped) {
            return stopped.reason == StopReason.CANCELLED
                    ? error(409, "REQUEST_CANCELLED")
                    : error(503, "SCORING_RESOURCE_LIMIT");
        } catch (GraphProjectionService.ProjectionInterrupted stopped) {
            return stopped.reason() == StopReason.CANCELLED
                    ? error(409, "REQUEST_CANCELLED")
                    : error(503, "PROJECTION_RESOURCE_LIMIT");
        } catch (BoundedJson.TooLarge large) {
            return error(422, "RESPONSE_TOO_LARGE");
        } catch (GraphJson.LimitExceededException large) {
            return error(413, "JSON_LIMIT_EXCEEDED");
        } catch (GraphStoreException unavailable) {
            return error(503, "GRAPH_STORE_UNAVAILABLE");
        } catch (IllegalArgumentException invalid) {
            return error(400, "INVALID_REQUEST");
        } catch (RuntimeException failed) {
            return error(500, "INTERNAL_ERROR");
        }
    }

    private Response submitAnalysisJobWithRef(String contentType, byte[] body, BooleanSupplier cancelled) {
        try {
            if (cancelled.getAsBoolean())
                throw new Failure(409, "REQUEST_CANCELLED");
            var input = QueryJson.object(decode(body, contentType), "setup", "idempotencyKey", "force", "mode", "ref");
            if (input.containsKey("force") && !(input.get("force") instanceof Boolean))
                throw new IllegalArgumentException("Expected boolean force");
            var mode = input.containsKey("mode") ? AnalysisMode.parse(QueryJson.text(input, "mode")) : AnalysisMode.FULL;
            var ref = QueryJson.optionalText(input, "ref");
            String setupId = QueryJson.text(input, "setup");
            if (connections != null && connections.existsOwnedByOther(setupId)) {
                return error(403, "ACCESS_DENIED");
            }
            var submission = analysisJobs.submit(setupId,
                    QueryJson.text(input, "idempotencyKey"),
                    Boolean.TRUE.equals(input.get("force")), mode, ref);
            var data = submission.job();
            var bytes = BoundedJson.encode(data, limits.responseBytes());
            return new Response(submission.created() ? 202 : 200,
                    BoundedJson.encode(Map.of("apiVersion", VERSION, "queryVersion", ArchitectureQuery.VERSION,
                            "jobVersion", AnalysisJobs.VERSION, "resultIdentity", ContentDigest.sha256(bytes),
                            "data", data), limits.responseBytes()));
        } catch (AnalysisJobs.Rejected rejected) {
            int status = switch (rejected.code()) {
                case "ACCESS_DENIED" -> 403;
                case "SETUP_NOT_FOUND", "JOB_NOT_FOUND" -> 404;
                case "IDEMPOTENCY_CONFLICT", "JOB_NOT_FINISHED", "JOB_ALREADY_PUBLISHED", "SETUP_CONFIGURATION_CHANGED" -> 409;
                default -> 503;
            };
            return new Response(status, BoundedJson.encode(Map.of("apiVersion", VERSION,
                    "queryVersion", ArchitectureQuery.VERSION, "jobVersion", AnalysisJobs.VERSION,
                    "error", Map.of("code", rejected.code(),
                            "message", "The local analysis job request could not be completed.")),
                    limits.responseBytes()));
        }
    }

    private Response supersededError(SupersededFailure gone) {
        var err = new LinkedHashMap<String, Object>();
        err.put("code", "PREVIEW_SUPERSEDED");
        err.put("message", message("PREVIEW_SUPERSEDED"));
        gone.supersededBy.ifPresent(rep -> err.put("supersededBy", rep.value()));
        return new Response(410,
                CanonicalJson.write(Map.of("apiVersion", VERSION, "queryVersion", ArchitectureQuery.VERSION,
                        "error", err)).getBytes(StandardCharsets.UTF_8));
    }

    public Response error(int status, String code) {
        return new Response(status,
                CanonicalJson.write(Map.of("apiVersion", VERSION, "queryVersion", ArchitectureQuery.VERSION,
                        "error", Map.of("code", code, "message", message(code)))).getBytes(StandardCharsets.UTF_8));
    }

    private Response execution(String method, String path, String contentType, byte[] body, BooleanSupplier cancelled) {
        if (!method.equals("POST"))
            throw new Failure(405, "METHOD_NOT_ALLOWED");
        var operation = switch (path) {
            case "/api/v2/executions/submit" ->
                com.evolution.analysis.execution.RegisteredExecutionPort.Operation.SUBMIT;
            case "/api/v2/executions/status" ->
                com.evolution.analysis.execution.RegisteredExecutionPort.Operation.STATUS;
            case "/api/v2/executions/cancel" ->
                com.evolution.analysis.execution.RegisteredExecutionPort.Operation.CANCEL;
            case "/api/v2/executions/recover" ->
                com.evolution.analysis.execution.RegisteredExecutionPort.Operation.RECOVER;
            default -> throw new Failure(404, "NOT_FOUND");
        };
        var input = decode(body, contentType);
        if (!input.keySet().equals(Set.of("registration")) || !(input.get("registration") instanceof String id))
            throw new Failure(400, "INVALID_REQUEST");
        var registration = new ContentDigest(id);
        if (cancelled.getAsBoolean())
            throw new Failure(409, "REQUEST_CANCELLED");
        if (executions == null)
            throw new Failure(503, "EXECUTIONS_UNAVAILABLE");
        try {
            var data = executions.execute(operation, registration);
            if (!data.registration().equals(registration))
                throw new Failure(503, "EXECUTION_SCOPE_MISMATCH");
            var canonical = BoundedJson.encode(data, limits.responseBytes());
            return new Response(200,
                    BoundedJson.encode(
                            Map.of("apiVersion", com.evolution.analysis.execution.RegisteredExecutionPort.VERSION,
                                    "resultIdentity", ContentDigest.sha256(canonical), "data", data),
                            limits.responseBytes()));
        } catch (com.evolution.analysis.execution.RegisteredExecutionPort.Rejected rejected) {
            throw new Failure(rejected
                    .failure() == com.evolution.analysis.execution.RegisteredExecutionPort.Failure.SCOPE_CONFLICT ? 409
                            : 404,
                    "EXECUTION_" + rejected.failure().name());
        } catch (java.io.IOException unavailable) {
            throw new Failure(503, "EXECUTIONS_UNAVAILABLE");
        }
    }

    private Response graphPages(String method, String path, String contentType, byte[] body,
            BooleanSupplier cancelled) {
        requireMethod(method, "POST");
        var parts = path.split("/", -1);
        if (parts.length != 6 || !Set.of("partitions", "nodes", "adjacency").contains(parts[5]))
            throw new Failure(404, "NOT_FOUND");
        var graph = new ContentDigest(parts[4]);
        var input = decode(body, contentType);
        String operation = parts[5];
        Set<String> allowed = switch (operation) {
            case "partitions" -> Set.of("size", "cursor");
            case "nodes" -> Set.of("size", "cursor", "partition");
            default -> Set.of("size", "cursor", "partition", "node", "incoming");
        };
        if (!allowed.containsAll(input.keySet()))
            throw new IllegalArgumentException();
        var paging = new TreeMap<String, Object>();
        for (String key : List.of("size", "cursor"))
            if (input.containsKey(key))
                paging.put(key, input.get(key));
        var page = QueryJson.page(paging);
        if (cancelled.getAsBoolean())
            throw new Failure(409, "REQUEST_CANCELLED");
        if (partitionReaders == null)
            throw new Failure(503, "PARTITION_READER_UNAVAILABLE");
        try {
            var reader = partitionReaders.apply(cancelled);
            Object result;
            if (operation.equals("partitions"))
                result = reader.partitions(graph, page.size(), page.cursor());
            else {
                var row = GraphJson.object(input.get("partition"));
                if (!row.keySet().equals(Set.of("schema", "graph", "context", "segment", "nodes", "edges")))
                    throw new IllegalArgumentException();
                var segment = GraphJson.object(row.get("segment"));
                if (!segment.keySet().equals(Set.of("digest", "size")))
                    throw new IllegalArgumentException();
                var partition = new PartitionedGraphReader.Partition(GraphJson.string(row.get("schema")),
                        new ContentDigest(GraphJson.string(row.get("graph"))), GraphJson.string(row.get("context")),
                        new com.evolution.analysis.acquisition.streaming.ContentHandle(
                                new ContentDigest(GraphJson.string(segment.get("digest"))),
                                integer64(segment.get("size"))),
                        integer64(row.get("nodes")), integer64(row.get("edges")));
                if (!partition.graph().equals(graph))
                    throw new IllegalArgumentException();
                if (operation.equals("nodes"))
                    result = reader.nodes(partition, page.size(), page.cursor());
                else {
                    Object direction = input.getOrDefault("incoming", false);
                    if (!(direction instanceof Boolean incoming))
                        throw new IllegalArgumentException();
                    result = reader.adjacency(partition, GraphJson.string(input.get("node")), incoming, page.size(),
                            page.cursor());
                }
            }
            if (cancelled.getAsBoolean())
                throw new Failure(409, "REQUEST_CANCELLED");
            var data = Map.of("graph", graph, "scope", "RAW_CONTEXT_FACTS", "page", result, "semanticAccuracy",
                    "NOT_CERTIFIED");
            byte[] canonical = BoundedJson.encode(data, limits.responseBytes());
            return new Response(200, BoundedJson.encode(Map.of("apiVersion", "architecture.graph-pages:m8.4-v1",
                    "resultIdentity", ContentDigest.sha256(canonical), "data", data), limits.responseBytes()));
        } catch (PartitionedGraphReader.IndexPending pending) {
            throw new Failure(503, pending.workerBusy() ? "PARTITION_INDEX_BUSY" : "PARTITION_INDEX_PREPARING");
        } catch (java.util.concurrent.CancellationException stopped) {
            throw new Failure(409, "REQUEST_CANCELLED");
        } catch (java.io.IOException unavailable) {
            throw new Failure(503, "PARTITION_STORE_UNAVAILABLE");
        }
    }

    private static long integer64(Object value) {
        if (!(value instanceof Number))
            throw new IllegalArgumentException();
        try {
            return new java.math.BigDecimal(value.toString()).longValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException();
        }
    }

    private static boolean isPublicMultiUserRoute(String method, String rawPath) {
        return ("GET".equals(method) && rawPath.equals("/api/v1"))
                || rawPath.startsWith(com.evolution.backend.auth.AuthHttpApi.PREFIX)
                || ("POST".equals(method) && rawPath.equals("/api/v1/github/auth/session"))
                || rawPath.equals("/api/v1/github/webhooks");
    }

    private Response handleGraphCodeowners(String method, String rawPath, String rawQuery, byte[] body) {
        requireMethod(method, "GET");
        requireEmpty(body);
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 6 || !"graphs".equals(parts[3]) || !"codeowners".equals(parts[5])) {
            throw new Failure(404, "NOT_FOUND");
        }
        var graphId = new ContentDigest(parts[4]);
        var graph = load(graphId);
        Optional<String> pathQuery = extractQueryParam(rawQuery, "path");
        var view = GitHubProvenance.CodeownersIndex.fromGraph(graph).toView(pathQuery);
        return okEnvelope(200, view);
    }

    private Response handleGraphAdvisoryLinks(String method, String rawPath, String rawQuery, byte[] body) {
        requireMethod(method, "GET");
        requireEmpty(body);
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 6 || !"graphs".equals(parts[3]) || !"advisory-links".equals(parts[5])) {
            throw new Failure(404, "NOT_FOUND");
        }
        var graphId = new ContentDigest(parts[4]);
        var graph = load(graphId);
        String entity = extractQueryParam(rawQuery, "entity").orElse("");
        String kind = extractQueryParam(rawQuery, "kind").orElse("");
        String path = extractQueryParam(rawQuery, "path").orElse("");
        int limit = 100;
        var limitOpt = extractQueryParam(rawQuery, "limit");
        if (limitOpt.isPresent()) {
            try {
                limit = Math.max(1, Math.min(500, Integer.parseInt(limitOpt.orElseThrow())));
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid limit");
            }
        }
        var full = com.evolution.backend.query.advisory.AdvisoryOverlayService.forGraph(graph);
        var filtered = com.evolution.backend.query.advisory.AdvisoryOverlayService.filter(full, entity, kind, path);
        return okEnvelope(200, filtered.toView(limit, limit));
    }

    private Response handleGraphWikiDossier(String method, String rawPath, String rawQuery, byte[] body,
            BooleanSupplier cancelled) {
        requireMethod(method, "GET");
        requireEmpty(body);
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 6 || !"graphs".equals(parts[3]) || !"wiki-dossier".equals(parts[5])) {
            throw new Failure(404, "NOT_FOUND");
        }
        var graphId = new ContentDigest(parts[4]);
        var graph = load(graphId);
        String ctx = extractQueryParam(rawQuery, "context").orElse(graph.snapshot().value());
        String format = extractQueryParam(rawQuery, "format").orElse("json").toLowerCase(Locale.ROOT);
        if (!Set.of("json", "zip", "markdown").contains(format)) {
            throw new IllegalArgumentException("Unsupported wiki format");
        }
        var guard = ArchitectureQueryService.operationalGuard(cancelled,
                System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
        var queries = factory.apply(graph, guard);
        var bundle = ArchitectureWikiGenerator.generate(queries, ctx, reports);
        if ("zip".equals(format)) {
            try {
                byte[] zipBytes = ArchitectureWikiGenerator.toDeterministicZip(bundle);
                if (zipBytes.length > limits.responseBytes()) {
                    throw new Failure(422, "RESPONSE_TOO_LARGE");
                }
                var headers = new LinkedHashMap<String, List<String>>();
                headers.put("Content-Type", List.of("application/zip"));
                headers.put("Content-Disposition", List.of("attachment; filename=\"evo-architecture-wiki.zip\""));
                headers.put("X-Evo-Wiki-Digest", List.of(bundle.bundleSha256()));
                return new Response(200, zipBytes, headers);
            } catch (java.io.IOException io) {
                throw new Failure(500, "INTERNAL_ERROR");
            }
        }
        if ("markdown".equals(format)) {
            byte[] markdownBytes = bundle.combinedMarkdown().getBytes(StandardCharsets.UTF_8);
            if (markdownBytes.length > limits.responseBytes()) {
                throw new Failure(422, "RESPONSE_TOO_LARGE");
            }
            var headers = new LinkedHashMap<String, List<String>>();
            headers.put("Content-Type", List.of("text/markdown; charset=utf-8"));
            headers.put("Content-Disposition", List.of("attachment; filename=\"evo-architecture-dossier.md\""));
            headers.put("X-Evo-Wiki-Digest", List.of(bundle.bundleSha256()));
            return new Response(200, markdownBytes, headers);
        }
        return okEnvelope(200, bundle.toView());
    }

    private Response handleMcpSetup(String method, String rawQuery, String serverOrigin, byte[] body) {
        requireMethod(method, "GET");
        requireEmpty(body);
        int port = 8080;
        if (serverOrigin != null && !serverOrigin.isBlank()) {
            try {
                int p = java.net.URI.create(serverOrigin).getPort();
                if (p > 0) port = p;
            } catch (IllegalArgumentException ignored) {}
        }
        boolean multiUser = auth != null && auth.isMultiUserMode();
        String resolvedCp = resolveMcpClasspath();
        String resolvedStore = resolveMcpStoreDirectory();
        var proxyArgs = new ArrayList<String>(List.of("-cp", resolvedCp,
                "com.evolution.backend.query.WorkbenchMcpMain", "--port", String.valueOf(port)));
        if (multiUser) {
            proxyArgs.add("--auth-token-env");
            proxyArgs.add("EVO_MCP_TOKEN");
        }
        var directArgs = List.of("-cp", resolvedCp,
                "com.evolution.backend.query.WorkbenchMcpMain", "--store", resolvedStore);
        var serverEntry = new LinkedHashMap<String, Object>();
        serverEntry.put("command", "java");
        serverEntry.put("args", List.copyOf(proxyArgs));
        if (multiUser) {
            serverEntry.put("env", Map.of("EVO_MCP_TOKEN", "<mint-scoped-pat-in-account-settings>"));
        }
        var directEntry = new LinkedHashMap<String, Object>();
        directEntry.put("command", "java");
        directEntry.put("args", directArgs);

        var out = new LinkedHashMap<String, Object>();
        out.put("protocolVersion", "2025-11-25");
        out.put("multiUserMode", multiUser);
        out.put("patMintSupported", auth != null && auth.accountStore() != null);
        out.put("workbenchPort", port);
        out.put("resolvedJarPath", resolvedCp);
        out.put("resolvedStorePath", resolvedStore);
        out.put("toolCount", 19);
        out.put("resources", List.of(
                Map.of("uri", "evo://score-v2", "description", "7-dimension Architecture Health Score v2 & Confidence"),
                Map.of("uri", "evo://report", "description", "Executive Architecture Insights Markdown report (incl. Leiden communities)"),
                Map.of("uri", "evo://gaps", "description", "Active capability gaps & resolution obligations"),
                Map.of("uri", "evo://hotspots", "description", "Top architectural hotspots & blast-radius centers"),
                Map.of("uri", "evo://cycles", "description", "Tarjan SCC dependency tangles & minimal break cuts"),
                Map.of("uri", "evo://spring-summary", "description", "Spring beans, endpoints & SAT conditional realizations")));
        out.put("workbenchProxyConfig", Map.of("mcpServers", Map.of("evo-workbench", serverEntry)));
        out.put("directOfflineConfig", Map.of("mcpServers", Map.of("evo-local", directEntry)));
        out.put("agentRuleSnippet",
                "Prefer Evo MCP tools (`architecture_brief`, `repo_map`, `graph_query`, `search_captured_source`, `inspect_advisory_links`, `inspect_codeowners`, `read_markdown_section`) before raw file grep. Treat `ADVISORY_UNVERIFIED` links as non-authoritative.");
        return okEnvelope(200, Collections.unmodifiableMap(out));
    }

    private String resolveMcpClasspath() {
        String explicit = System.getProperty("evo.mcp.jar");
        if (explicit != null && !explicit.isBlank()) {
            return java.nio.file.Path.of(explicit.strip()).toAbsolutePath().normalize().toString();
        }
        try {
            var cs = ArchitectureQueryApi.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                var loc = java.nio.file.Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
                String fileName = loc.getFileName() == null ? "" : loc.getFileName().toString().toLowerCase(Locale.ROOT);
                String normPath = loc.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
                boolean isThinRepoJar = normPath.contains("/.m2/repository/")
                        || (fileName.startsWith("backend-") && !fileName.contains("shaded") && !fileName.contains("all"));
                if (fileName.endsWith(".jar") && !isThinRepoJar && java.nio.file.Files.isRegularFile(loc)) {
                    return loc.toString();
                }
            }
        } catch (Exception ignored) {}
        for (String candidate : List.of("cli/target/evo.jar", "target/evo.jar", "evo.jar")) {
            var p = java.nio.file.Path.of(candidate).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(p)) {
                return p.toString();
            }
        }
        String cp = System.getProperty("java.class.path");
        if (cp != null && !cp.isBlank()) {
            for (String entry : cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                String low = entry.replace('\\', '/').toLowerCase(Locale.ROOT);
                String name = low.substring(low.lastIndexOf('/') + 1);
                if (name.endsWith(".jar") && (name.equals("evo.jar") || name.startsWith("evo-") || name.contains("workbench"))
                        && !name.contains("surefire") && !name.contains("junit")) {
                    var p = java.nio.file.Path.of(entry);
                    if (java.nio.file.Files.isRegularFile(p)) {
                        return p.toAbsolutePath().normalize().toString();
                    }
                }
            }
        }
        return java.nio.file.Path.of("cli/target/evo.jar").toAbsolutePath().normalize().toString();
    }

    private String resolveMcpStoreDirectory() {
        if (storeDirectory != null) {
            return storeDirectory.toAbsolutePath().normalize().toString();
        }
        if (acquisitionContentDir != null) {
            var parent = acquisitionContentDir.getParent();
            if (parent != null && parent.getFileName() != null
                    && "acquisition".equalsIgnoreCase(parent.getFileName().toString())
                    && parent.getParent() != null) {
                return parent.getParent().toAbsolutePath().normalize().toString();
            }
            return acquisitionContentDir.toAbsolutePath().normalize().toString();
        }
        String prop = System.getProperty("evo.store.dir");
        if (prop != null && !prop.isBlank()) {
            return java.nio.file.Path.of(prop.strip()).toAbsolutePath().normalize().toString();
        }
        return java.nio.file.Path.of(".evo-workbench").toAbsolutePath().normalize().toString();
    }

    private static final Set<String> MCP_GET_GRAPH_SUFFIXES = Set.of(
            "codeowners", "advisory-links", "wiki-dossier", "captured-files", "repo-map", "repository-score",
            "conversations");
    private static final Set<String> MCP_POST_GRAPH_SUFFIXES = Set.of(
            "architecture-insights", "architecture-score", "captured-files", "captured-source",
            "search-captured-source", "markdown-section", "repo-map", "suggested-policy",
            "community-evolution", "repository-score", "visual-graph");
    private static final Set<String> MCP_POST_REPORT_READ_OPS = Set.of(
            "rules", "findings", "finding", "bundle", "assessment", "policy-metrics", "policy-impact",
            "visual-graph", "replay", "repair-options");

    /**
     * Authoritative tiered Personal Access Token scope policy shared across {@link ArchitectureQueryApi},
     * {@link com.evolution.backend.auth.AuthHttpApi}, and {@link ArchitectureHttpServer} (SSE/Markdown lanes).
     * <ul>
     *   <li>{@code api:write}: all authenticated endpoints (user role/ownership checks still apply).</li>
     *   <li>{@code api:read}: general read-only REST + SSE/Markdown read routes (NO mutations, NO live-LLM egress).</li>
     *   <li>{@code mcp:read}: strictly scoped to {@code /api/v1/auth/me}, {@code /api/v1/mcp/setup}, and explicit
     *       read-only MCP graph/query/insights/score/coverage/advisory/markdown-section/wiki/repo-map/source/jobs-list routes.</li>
     * </ul>
     */
    public static boolean isPatAuthorized(Collection<String> scopes, String method, String path) {
        if (scopes == null || scopes.isEmpty() || method == null || path == null) {
            return false;
        }
        String lowerPath = path.toLowerCase(Locale.ROOT);
        if (path.contains("..") || path.contains("\\") || path.contains("//") || path.indexOf('\0') >= 0
                || lowerPath.contains("%2e%2e") || lowerPath.contains("%2f")
                || lowerPath.contains("%5c") || lowerPath.contains("%00")) {
            return false;
        }
        if (scopes.contains("api:write")) {
            return true;
        }
        if (scopes.contains("api:read") && isApiReadPatRoute(method, path)) {
            return true;
        }
        return scopes.contains("mcp:read") && isMcpReadPatRoute(method, path);
    }

    private static boolean isMcpReadPatRoute(String method, String path) {
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            if (path.equals("/api/v1") || path.equals("/api/v1/auth/me")
                    || path.equals("/api/v1/auth/config") || path.equals("/api/v1/mcp/setup")
                    || path.equals("/api/v1/analysis/jobs")) {
                return true;
            }
            if (path.startsWith("/api/v1/analysis/jobs/")) {
                String[] parts = path.split("/", -1);
                if (parts.length == 6 && !parts[5].isEmpty()) {
                    return true;
                }
                return parts.length == 7 && !parts[5].isEmpty()
                        && ("coverage".equals(parts[6]) || "repository-score".equals(parts[6])
                                || "progress".equals(parts[6]) || "telemetry".equals(parts[6]));
            }
            if (path.startsWith("/api/v1/graphs/")) {
                String[] parts = path.split("/", -1);
                if (parts.length == 5 && !parts[4].isEmpty()) {
                    return true;
                }
                if (parts.length == 6 && !parts[4].isEmpty() && MCP_GET_GRAPH_SUFFIXES.contains(parts[5])) {
                    return true;
                }
                if (parts.length == 7 && !parts[4].isEmpty() && "reports".equals(parts[5]) && !parts[6].isEmpty()) {
                    return true;
                }
                return parts.length == 8 && !parts[4].isEmpty() && "reports".equals(parts[5])
                        && !parts[6].isEmpty() && "sarif".equals(parts[7]);
            }
            if (path.startsWith("/api/v1/conversations/")) {
                String[] parts = path.split("/", -1);
                return parts.length == 5 && !parts[4].isEmpty();
            }
            return false;
        }
        if (!"POST".equals(method)) {
            return false;
        }
        if (path.startsWith("/api/v1/graphs/")) {
            String[] parts = path.split("/", -1);
            if (parts.length == 7 && !parts[4].isEmpty() && "queries".equals(parts[5]) && !parts[6].isEmpty()) {
                return true;
            }
            if (parts.length == 6 && !parts[4].isEmpty() && MCP_POST_GRAPH_SUFFIXES.contains(parts[5])) {
                return true;
            }
            return parts.length == 8 && !parts[4].isEmpty() && "reports".equals(parts[5])
                    && !parts[6].isEmpty() && MCP_POST_REPORT_READ_OPS.contains(parts[7]);
        }
        if (path.equals("/api/v1/analysis/jobs/history")) {
            return true;
        }
        if (path.startsWith("/api/v1/analysis/jobs/")) {
            String[] parts = path.split("/", -1);
            return parts.length == 7 && !parts[5].isEmpty()
                    && ("gaps".equals(parts[6]) || "input-diagnostics".equals(parts[6]));
        }
        return path.startsWith("/api/v1/bridges/") || path.startsWith("/api/v1/semantic/")
                || path.startsWith("/api/v1/graph-pages/");
    }

    private static boolean isApiReadPatRoute(String method, String path) {
        if (isMcpReadPatRoute(method, path)) {
            return true;
        }
        if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) {
            if (path.equals("/api/v1/auth/users") || path.equals("/api/v1/auth/tokens")
                    || path.equals("/api/v1/auth/github/setup")
                    || path.equals("/api/v1/ai/providers")
                    || path.equals("/api/v1/analysis/connections")
                    || path.equals("/api/v1/analysis/setups")
                    || path.equals("/api/v1/repairs/sandbox/setup")
                    || path.equals("/api/v1/repairs/jobs")
                    || path.startsWith("/api/v1/repairs/jobs/")
                    || path.startsWith("/api/v1/github/")
                    || path.startsWith("/api/v1/evolution/")) {
                return true;
            }
            if (path.startsWith("/api/v1/analysis/jobs/")) {
                String[] parts = path.split("/", -1);
                return parts.length == 7 && !parts[5].isEmpty() && "events".equals(parts[6]);
            }
            return false;
        }
        if (!"POST".equals(method)) {
            return false;
        }
        if (path.startsWith("/api/v1/analysis/jobs/")) {
            String[] parts = path.split("/", -1);
            if (parts.length == 7 && !parts[5].isEmpty() && "repository-score".equals(parts[6])) {
                return true;
            }
        }
        if (path.startsWith("/api/v1/evolution/results/")) {
            return path.endsWith("/query") || path.endsWith("/graph")
                    || path.endsWith("/evidence") || path.endsWith("/export");
        }
        return path.startsWith("/api/v2/acquisitions/") || path.equals("/api/v2/executions/status");
    }

    private Response handleReportSarif(String method, String rawPath, String rawQuery, byte[] body) {
        requireMethod(method, "GET");
        requireEmpty(body);
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 8 || !"graphs".equals(parts[3]) || !"reports".equals(parts[5]) || !"sarif".equals(parts[7])) {
            throw new Failure(404, "NOT_FOUND");
        }
        var graphId = new ContentDigest(parts[4]);
        requireAuthoritativeGraph(graphId);
        var reportId = new ContentDigest(parts[6]);
        var graph = load(graphId);
        var report = reports.get(reportId).orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
        var exported = com.evolution.backend.github.SarifExporter.export(graph, report);
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("Content-Type", List.of("application/sarif+json; charset=utf-8"));
        headers.put("X-Evo-Sarif-Digest", List.of(exported.sarifDigest().value()));
        if ("1".equals(extractQueryParam(rawQuery, "download").orElse(""))
                || "true".equalsIgnoreCase(extractQueryParam(rawQuery, "download").orElse(""))) {
            headers.put("Content-Disposition", List.of("attachment; filename=\"evo-architecture.sarif\""));
        }
        return new Response(200, exported.json().getBytes(StandardCharsets.UTF_8), headers);
    }

    private static Optional<String> extractQueryParam(String rawQuery, String key) {
        if (rawQuery == null || rawQuery.isBlank()) return Optional.empty();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            if (key.equals(java.net.URLDecoder.decode(k, StandardCharsets.UTF_8))) {
                String decoded = java.net.URLDecoder.decode(v, StandardCharsets.UTF_8).strip();
                return decoded.isEmpty() ? Optional.empty() : Optional.of(decoded);
            }
        }
        return Optional.empty();
    }

    static String allowedMethods(String path) {
        if (path.startsWith(com.evolution.backend.auth.AuthHttpApi.PREFIX)) {
            return com.evolution.backend.auth.AuthHttpApi.allowedMethods(path);
        }
        if (path.startsWith(GitHubHttpApi.PREFIX)) {
            return GitHubHttpApi.allowedMethods(path);
        }
        if (path.equals("/api/v1/mcp/setup")) {
            return "GET";
        }
        if (path.startsWith("/api/v1/graphs/") && (path.endsWith("/codeowners") || path.endsWith("/sarif")
                || path.endsWith("/advisory-links") || path.endsWith("/wiki-dossier"))) {
            return "GET";
        }
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/conversations")) {
            return "GET, POST";
        }
        if (path.startsWith("/api/v1/conversations/")) {
            return "GET, DELETE";
        }
        if (path.equals("/api/v1/ai/providers"))
            return "GET";
        if (path.equals("/api/v1/ai/providers/probe"))
            return "POST";
        if (path.equals("/api/v1/repairs/sandbox/setup"))
            return "GET, POST, DELETE";
        if (path.equals("/api/v1/repairs/sandbox/probe"))
            return "POST";
        if (path.equals("/api/v1/repairs/jobs"))
            return "GET";
        if (path.startsWith("/api/v1/repairs/jobs/")) {
            if (path.endsWith("/cancel"))
                return "POST";
            return "GET";
        }
        if (path.startsWith("/api/v1/repairs/"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && (path.endsWith("/repair-proposal/ad-hoc")
                || path.endsWith("/repair-verification/jobs")))
            return "POST";
        if (path.startsWith(EvolutionHttpApi.PREFIX)) {
            if (path.endsWith("/export"))
                return "GET, POST";
            if (path.endsWith("/jobs") || path.endsWith("/query") || path.endsWith("/graph") ||
                    path.endsWith("/evidence") || path.endsWith("/resume") || path.endsWith("/cancel"))
                return "POST";
            return "GET";
        }
        if (path.startsWith("/api/v1/graph-pages/"))
            return "POST";
        if (path.startsWith("/api/v2/acquisitions/"))
            return "POST";
        if (path.startsWith("/api/v1/bridges/"))
            return "POST";
        if (path.startsWith("/api/v2/executions/"))
            return "POST";
        if (path.equals("/api/v1/analysis/connections"))
            return "GET, POST";
        if (path.startsWith("/api/v1/analysis/connections/"))
            return "DELETE, POST";
        if (path.equals("/api/v1/analysis/jobs"))
            return "GET, POST";
        if (path.startsWith("/api/v1/analysis/jobs/") && (path.endsWith("/events") || path.endsWith("/progress")
                || path.endsWith("/telemetry") || path.endsWith("/coverage")))
            return "GET";
        if (path.equals("/api/v1/analysis/jobs/history") || path.startsWith("/api/v1/analysis/jobs/")
                && (path.endsWith("/cancel") || path.endsWith("/gaps") || path.endsWith("/input-diagnostics")))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && (path.endsWith("/architecture-insights")
                || path.endsWith("/architecture-chat") || path.endsWith("/architecture-chat/stream")
                || path.endsWith("/policy-assessment") || path.endsWith("/suggested-policy")
                || path.endsWith("/architecture-score") || path.endsWith("/captured-source")
                || path.endsWith("/search-captured-source")
                || path.endsWith("/markdown-section") || path.endsWith("/community-evolution")
                || path.endsWith("/visual-graph")))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && (path.endsWith("/captured-files") || path.endsWith("/repo-map")))
            return "GET, POST";
        if (path.endsWith("/repository-score"))
            return "GET, POST";
        return path.contains("/queries/") || path.split("/", -1).length == 8 ? "POST" : "GET";
    }

    private static String message(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "The request does not satisfy the API contract.";
            case "GRAPH_NOT_FOUND" -> "The requested graph is unavailable.";
            case "CONTEXT_NOT_FOUND" -> "This analysis context is unavailable.";
            case "SOURCE_NOT_FOUND" -> "The requested captured source file is unavailable in this snapshot.";
            case "POLICY_SCOPE_UNAVAILABLE" ->
                "Select a Java or Spring analysis context to evaluate architecture rules.";
            case "SCORING_RESOURCE_LIMIT" -> "Scoring reached the workbench resource limit. Try again shortly.";
            case "PROJECTION_RESOURCE_LIMIT" ->
                "Visual graph projection reached the workbench resource limit. Try again shortly.";
            case "REPORT_NOT_FOUND", "REPORT_ARTIFACT_UNAVAILABLE" ->
                "The requested canonical report artifact is unavailable.";
            case "DIAGNOSIS_SCOPE_MISMATCH" ->
                "The selected context or evidence bundle no longer matches this finding.";
            case "DIAGNOSIS_INPUT_MISMATCH" ->
                "The diagnosis input changed. Run diagnosis again before requesting a proposal.";
            case "CHAT_CONSENT_REQUIRED" ->
                "Approve sending this turn's conversation and architecture evidence to the configured provider.";
            case "CHAT_PROVIDER_UNAVAILABLE" ->
                "The chat provider could not answer. Check the endpoint, model and credentials, then retry.";
            case "CHAT_INVALID_MODEL_OUTPUT" ->
                "The provider returned an incomplete answer or references outside the offered evidence.";
            case "CHAT_RESOURCE_LIMIT" ->
                "The chat request reached its resource limit. Narrow the question or start a fresh conversation.";
            case "CHAT_BUSY" -> "The architecture chat provider is handling other requests. Try again shortly.";
            case "REPAIR_PROPOSAL_MISMATCH" ->
                "The proposal could not be reproduced for this finding and its pinned inputs.";
            case "REPAIR_REANALYSIS_UNAVAILABLE" -> "Candidate reanalysis is not configured on this server.";
            case CapturedRepairRunner.NOT_CONFIGURED ->
                "Isolated QEMU-in-Docker repair runner is not configured on this server.";
            case "ISOLATED_RUNNER_EXECUTION_FAILED" ->
                "Isolated QEMU-in-Docker repair verification failed to produce a valid capture.";
            case "NOT_FOUND" -> "The requested API operation is unavailable.";
            case "METHOD_NOT_ALLOWED" -> "This route does not support the requested HTTP method.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "Use application/json with UTF-8 and no content encoding.";
            case "NOT_ACCEPTABLE" -> "The API produces application/json.";
            case "REQUEST_TOO_LARGE", "JSON_LIMIT_EXCEEDED" -> "The request exceeds the configured transport limit.";
            case "RESPONSE_TOO_LARGE" -> "The complete response exceeds the transport limit; narrow the query or page.";
            case "FORBIDDEN_ORIGIN" -> "The local API accepts only same-origin requests.";
            case "BUSY" -> "The local API is at its concurrent request limit.";
            case "PARTITION_INDEX_PREPARING" -> "The graph page index is being prepared. Retry this request shortly.";
            case "PARTITION_INDEX_BUSY" -> "Another graph page index is being prepared. Retry this request shortly.";
            case "GRAPH_STORE_UNAVAILABLE" -> "Graph storage could not supply a verified snapshot.";
            case "JOBS_UNAVAILABLE" -> "Analysis jobs are not configured in this local server.";
            case "PRELIMINARY_GRAPH_PENDING" ->
                "This endpoint requires the authoritative semantic graph; wait for Stage 3 publication.";
            case "PREVIEW_SUPERSEDED" ->
                "This preliminary preview graph has been superseded by a newer or authoritative snapshot.";
            case "GITHUB_INTEGRATION_UNAVAILABLE" ->
                "GitHub integration is not configured on this local server.";
            case "PROPOSAL_NOT_FOUND" -> "The requested repair proposal is not stored on this server.";
            case "REPAIR_JOB_NOT_FOUND" -> "The requested repair verification job was not found.";
            case "REPAIR_VERIFICATION_JOBS_UNAVAILABLE" ->
                "Async repair verification jobs are not configured on this server.";
            case "PATCH_APPLIER_UNAVAILABLE" ->
                "Safe local patch applier is not configured on this server.";
            case "PATCH_APPLY_CONFLICT" ->
                "Patch preflight or application failed due to baseline drift, rejection, or Git conflict.";
            case "PATCH_APPLY_REJECTED", "REPAIR_VERIFICATION_REJECTED" ->
                "Rejected repair proposals cannot be applied to repository source files.";
            case "REPO_CHECKOUT_UNAVAILABLE" ->
                "A local repository checkout directory is required for local patch apply.";
            case "REPO_DIR_NOT_REGISTERED" ->
                "The requested repository directory is not within a registered local checkout.";
            case "AUTH_REQUIRED" ->
                "Sign in with GitHub is required to access this workspace.";
            case "AUTH_UNAVAILABLE" ->
                "Multi-user authentication is not configured on this server.";
            case "CSRF_TOKEN_INVALID" ->
                "Missing or invalid X-Evo-CSRF token for this authenticated session.";
            case "ACCESS_DENIED", "APPLY_ACCESS_DENIED" ->
                "Access denied: this resource belongs to another user session.";
            case "ADMIN_REQUIRED" ->
                "Administrator privileges are required to inspect or modify isolated sandbox configuration.";
            case "OFFLINE_TOOLCHAIN_MISSING" ->
                "Required offline dependency cache or container toolchain is missing for isolated sandbox verification.";
            case "SANDBOX_CONFIG_INVALID" ->
                "Isolated sandbox configuration failed validation; pinned official container digests and safe paths are required.";
            case "INSUFFICIENT_TOKEN_SCOPE" ->
                "This personal access token does not include the required scope for this operation.";
            case "CONVERSATION_NOT_FOUND" -> "The requested chat conversation was not found.";
            case "CONVERSATION_STORE_UNAVAILABLE" ->
                "Persistent chat conversation storage is not configured on this server.";
            case "CONVERSATION_GRAPH_MISMATCH" ->
                "The requested conversation belongs to a different graph snapshot.";
            case "LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER" ->
                "Host local filesystem checkouts are disabled for OAuth users in multi-user mode.";
            default -> "The request could not be completed.";
        };
    }

    private Object route(String method, String path, String contentType, byte[] body, BooleanSupplier cancelled) {
        if (path.equals("/api/v1")) {
            requireMethod(method, "GET");
            requireEmpty(body);
            var capabilities = new TreeMap<String, Object>();
            capabilities.putAll(
                    Map.of("operations", OPERATIONS, "reportOperations", REPORT_OPERATIONS, "transportLimits", limits,
                            "queryLimits", Limits.defaults(), "graphSchema", KnowledgeGraph.SCHEMA));
            capabilities.put("jobVersion", AnalysisJobs.VERSION);
            capabilities.put("analysisJobsEnabled", jobs != null);
            capabilities.put("analysisCoverageEnabled", jobs != null);
            capabilities.put("analysisResultReuseEnabled", jobs != null && jobs.resultReuseEnabled());
            capabilities.put("repositoryConnectionsEnabled", connections != null);
            capabilities.put("partitionedGraphsEnabled", partitionReaders != null);
            capabilities.put("evolutionEnabled", evolution != null);
            capabilities.put("bridgesEnabled", bridges != null);
            capabilities.put("acquisitionVersion", "repository.acquisition-api:m8.4-v2");
            capabilities.put("readinessVersion", com.evolution.analysis.readiness.ReadinessPlan.SCHEMA);
            capabilities.put("acquisitionRoutes",
                    List.of("/api/v2/acquisitions/history", "/api/v2/acquisitions/{revision}",
                            "/api/v2/acquisitions/{revision}/readiness", "/api/v2/acquisitions/{revision}/overview",
                            "/api/v2/analysis/jobs/{id}/resume"));
            if (jobs != null)
                capabilities.put("jobLimits", jobs.limits());
            capabilities.put("analysisModes", List.of("FULL", "FAST_ARCHITECTURE"));
            capabilities.put("architectureInsightsEnabled", true);
            capabilities.put("architectureChatEnabled", true);
            capabilities.put("architectureChatStreamEnabled", true);
            capabilities.put("architectureAgentEnabled", true);
            capabilities.put("chatConversationsEnabled", chatStore != null);
            capabilities.put("resolvedCitationsEnabled", true);
            capabilities.put("multiScaleVisualGraphEnabled", true);
            capabilities.put("dsmMatrixEnabled", true);
            if (architectureChat != null)
                capabilities.put("architectureChatProvider", architectureChat.provider());
            capabilities.put("repositoryScoringEnabled", true);
            capabilities.put("suggestedPolicyEnabled", true);
            var activeRunner = activeRepairRunner();
            capabilities.put("repairReanalysisEnabled", repairReanalysis != null);
            capabilities.put("repairModelConfigured", repairModel != null);
            capabilities.put("isolatedRepairRunnerEnabled", activeRunner != null);
            capabilities.put("repairIsolatedRunnerConfigured", activeRunner != null);
            capabilities.put("repairSandboxMode", activeRunner != null
                    ? activeRunner.config().isolationMode().name()
                    : "PASSIVE_REANALYSIS_ONLY");
            capabilities.put("repairSandboxSetupEnabled", true);
            capabilities.put("repairClosedLoopEnabled", true);
            capabilities.put("repairBehavioralDiffEnabled", true);
            capabilities.put("repairSupportedBuildTools", List.of("auto", "maven", "gradle", "node", "python", "go"));
            capabilities.put("repairRecipeCount", JavaRepairRecipes.catalog().size());
            capabilities.put("aiProvidersEnabled", true);
            capabilities.put("adHocRepairEnabled", true);
            capabilities.put("repairVerificationJobsEnabled", repairVerificationJobs != null);
            capabilities.put("safePatchApplyEnabled", safePatchApplier != null);
            capabilities.put("contextMetadataEnabled", true);
            capabilities.put("architectureScoreV2Enabled", true);
            capabilities.put("progressiveAnalysisEnabled", true);
            capabilities.put("analysisJobEventsEnabled", jobs != null);
            capabilities.put("incrementalDeltaEnabled", true);
            capabilities.put("capturedFilesEnabled", true);
            capabilities.put("capturedSourceEnabled", true);
            capabilities.put("repoMapEnabled", true);
            capabilities.put("githubIntegrationEnabled", github != null);
            capabilities.put("multiUserAuthEnabled", auth != null);
            capabilities.put("multiUserMode", auth != null && auth.isMultiUserMode());
            capabilities.put("localAccountsEnabled", auth != null && auth.accountStore() != null);
            capabilities.put("oauthWebEnabled", auth != null && auth.oauthService().config().oauthWebEnabled());
            capabilities.put("deviceFlowEnabled", auth != null && auth.oauthService().isDeviceFlowEnabled());
            capabilities.put("githubManifestSetupEnabled", auth != null && auth.oauthService().configStore() != null);
            capabilities.put("githubEnterpriseEnabled", github != null && github.hasEnterpriseServices());
            capabilities.put("webhooksEnabled", github != null && github.isWebhookConfigured());
            capabilities.put("sarifExportEnabled", true);
            return capabilities;
        }
        String[] parts = path.split("/", -1);
        if (parts.length < 5 || !parts[0].isEmpty() || !parts[1].equals("api") || !parts[2].equals("v1")
                || !parts[3].equals("graphs"))
            throw new Failure(404, "NOT_FOUND");
        var graphId = new ContentDigest(parts[4]);
        if (parts.length == 5) {
            requireMethod(method, "GET");
            requireEmpty(body);
            var previewMeta = activePreviewMeta(graphId);
            if (previewMeta.isPresent()) {
                var graph = load(graphId);
                return discoveryPayload(graphId, graph.snapshot().value(), describeContexts(graph),
                        List.of(), previewMeta);
            }
            if (!store.available(graphId) && previewStore != null
                    && previewStore.lookupState(graphId) == WorkbenchPreviewStore.LookupState.SUPERSEDED) {
                throw new SupersededFailure(graphId, previewStore.supersededBy(graphId));
            }
            var cached = discoveryContextCache.get(graphId);
            if (cached == null) {
                cached = readDiskDiscoveryCache(graphId).orElse(null);
                if (cached != null)
                    discoveryContextCache.put(graphId, cached);
            }
            if (cached != null) {
                return discoveryPayload(graphId, cached.snapshot(), cached.contexts(),
                        reports.forGraph(graphId), Optional.empty());
            }
            var graph = load(graphId);
            var described = new CachedDiscovery(graph.snapshot().value(), describeContexts(graph));
            discoveryContextCache.put(graphId, described);
            writeDiskDiscoveryCache(graphId, described);
            return discoveryPayload(graphId, described.snapshot(), described.contexts(),
                    reports.forGraph(graphId), Optional.empty());
        }
        if (parts.length == 6 && parts[5].equals("architecture-score")) {
            requireMethod(method, "POST");
            Map<String, Object> input = body.length == 0
                    ? Map.of()
                    : QueryJson.object(decode(body, contentType), "context", "policy", "report");
            return evaluateArchitectureScoreV2(graphId, input, cancelled);
        }
        if (parts.length == 6 && parts[5].equals("captured-files")) {
            Map<String, Object> input;
            if (method.equals("GET")) {
                requireEmpty(body);
                input = Map.of();
            } else {
                requireMethod(method, "POST");
                input = body.length == 0
                        ? Map.of()
                        : QueryJson.object(decode(body, contentType), "query", "language", "offset", "limit");
            }
            var graph = load(graphId);
            String queryText = QueryJson.optionalText(input, "query").orElse("");
            String language = QueryJson.optionalText(input, "language").orElse("");
            int offset = optionalInt(input, "offset", 0, 0, 1_000_000);
            int limit = optionalInt(input, "limit", 200, 1, 500);
            var listed = CapturedFileIndex.forGraph(graph).list(queryText, language, offset, limit);
            var out = new LinkedHashMap<String, Object>();
            out.put("graph", graphId);
            out.put("totalFiles", listed.totalFiles());
            out.put("matchedFiles", listed.matchedFiles());
            out.put("offset", listed.offset());
            out.put("limit", listed.limit());
            out.put("truncated", listed.truncated());
            out.put("languages", listed.languages());
            out.put("files", listed.files().stream().map(CapturedFileIndex.Entry::toView).toList());
            return out;
        }
        if (parts.length == 6 && parts[5].equals("captured-source")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType), "path", "startLine", "endLine");
            String requestedPath = QueryJson.text(input, "path");
            int startLine = optionalInt(input, "startLine", 1, 1, 10_000_000);
            int endLine = optionalInt(input, "endLine", startLine + CapturedFileIndex.MAX_LINES_PER_READ - 1,
                    startLine, 10_000_000);
            var graph = load(graphId);
            var read = CapturedFileIndex.forGraph(graph).read(graph, requestedPath, startLine, endLine);
            var out = new LinkedHashMap<String, Object>();
            out.put("graph", graphId);
            out.put("found", read.found());
            out.put("ambiguous", read.ambiguous());
            out.put("path", read.path());
            out.put("language", read.language());
            out.put("digest", read.digest().map(ContentDigest::value).orElse(null));
            out.put("evidenceId", read.evidenceId().map(ContentDigest::value).orElse(null));
            out.put("startLine", read.startLine());
            out.put("endLine", read.endLine());
            out.put("totalLines", read.totalLines());
            out.put("truncated", read.truncated());
            out.put("content", read.content());
            out.put("numberedSource", read.numberedSource());
            out.put("redactedSecretsCount", read.redactedSecretsCount());
            out.put("candidates", read.candidates());
            return out;
        }
        if (parts.length == 6 && parts[5].equals("search-captured-source")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType),
                    "query", "regex", "pathFilter", "language", "maxMatches");
            String queryText = QueryJson.text(input, "query");
            boolean regex = Boolean.TRUE.equals(input.get("regex"));
            String pathFilter = QueryJson.optionalText(input, "pathFilter").orElse("");
            String language = QueryJson.optionalText(input, "language").orElse("");
            int maxMatches = optionalInt(input, "maxMatches", 30, 1, 100);
            var graph = load(graphId);
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            var res = CapturedFileIndex.forGraph(graph)
                    .search(graph, queryText, regex, pathFilter, language, maxMatches, guard, searchIndex);
            var out = new LinkedHashMap<String, Object>();
            out.put("graph", graphId);
            out.put("query", res.query());
            out.put("regex", res.regex());
            out.put("engine", res.engine());
            out.put("scannedFiles", res.scannedFiles());
            out.put("matchedFiles", res.matchedFiles());
            out.put("omittedFiles", res.omittedFiles());
            out.put("redactedSecretCount", res.redactedSecretCount());
            out.put("truncated", res.truncated());
            out.put("matches", res.matches().stream().map(CapturedFileIndex.SearchMatch::toView).toList());
            return out;
        }
        if (parts.length == 6 && parts[5].equals("markdown-section")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType), "path", "heading");
            String requestedPath = QueryJson.text(input, "path");
            String heading = QueryJson.optionalText(input, "heading").orElse("");
            var graph = load(graphId);
            var index = CapturedFileIndex.forGraph(graph);
            var out = new LinkedHashMap<String, Object>();
            out.put("graph", graphId);
            if (heading.isBlank()) {
                var listRes = index.listMarkdownHeadings(graph, requestedPath);
                out.put("mode", "HEADINGS");
                out.put("found", listRes.found());
                out.put("ambiguous", listRes.ambiguous());
                out.put("path", listRes.path());
                out.put("digest", listRes.digest().map(ContentDigest::value).orElse(null));
                out.put("evidenceId", listRes.evidenceId().map(ContentDigest::value).orElse(null));
                out.put("headings", listRes.headings().stream().map(CapturedFileIndex.MarkdownHeading::toView).toList());
                out.put("candidates", listRes.candidates());
                return out;
            }
            var sec = index.readMarkdownSection(graph, requestedPath, heading);
            out.put("mode", "SECTION");
            out.put("found", sec.found());
            out.put("ambiguous", sec.ambiguous());
            out.put("path", sec.path());
            out.put("heading", sec.heading());
            out.put("level", sec.level());
            out.put("digest", sec.digest().map(ContentDigest::value).orElse(null));
            out.put("evidenceId", sec.evidenceId().map(ContentDigest::value).orElse(null));
            out.put("startLine", sec.startLine());
            out.put("endLine", sec.endLine());
            out.put("totalLines", sec.totalLines());
            out.put("truncated", sec.truncated());
            out.put("content", sec.content());
            out.put("numberedSource", sec.numberedSource());
            out.put("redactedSecretsCount", sec.redactedSecretsCount());
            out.put("candidates", sec.candidates());
            return out;
        }
        if (parts.length == 6 && parts[5].equals("community-evolution")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType), "context", "otherGraph", "otherContext", "scope");
            var afterGraphId = new ContentDigest(QueryJson.text(input, "otherGraph"));
            var scope = input.containsKey("scope")
                    ? Scope.valueOf(QueryJson.text(input, "scope")) : Scope.TYPE;
            var beforeGraph = load(graphId);
            var afterGraph = load(afterGraphId);
            String beforeCtx = QueryJson.optionalText(input, "context")
                    .filter(s -> !s.isBlank())
                    .orElseGet(() -> defaultAnalysisContext(beforeGraph));
            String afterCtx = QueryJson.optionalText(input, "otherContext")
                    .filter(s -> !s.isBlank())
                    .orElseGet(() -> defaultAnalysisContext(afterGraph));
            var guard = ArchitectureQueryService.operationalGuard(cancelled,
                    System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
            var beforeReq = new Request(graphId, beforeCtx, scope, Projection.BOUNDED_UNION, Direction.OUTGOING,
                    Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var afterReq = new Request(afterGraphId, afterCtx, scope, Projection.BOUNDED_UNION, Direction.OUTGOING,
                    Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
            var beforeLeiden = ArchitectureInsightsAdvisor.detectCommunities(factory.apply(beforeGraph, guard), beforeReq);
            var afterLeiden = ArchitectureInsightsAdvisor.detectCommunities(factory.apply(afterGraph, guard), afterReq);
            return com.evolution.analysis.evolution.CommunityEvolutionMatcher.match(beforeLeiden, afterLeiden);
        }
        if (parts.length == 6 && parts[5].equals("repo-map")) {
            Map<String, Object> input;
            if (method.equals("GET")) {
                requireEmpty(body);
                input = Map.of();
            } else {
                requireMethod(method, "POST");
                input = body.length == 0
                        ? Map.of()
                        : QueryJson.object(decode(body, contentType), "context", "seeds", "maxFiles", "maxTokens");
            }
            var graph = load(graphId);
            Optional<String> preferredCtx = QueryJson.optionalText(input, "context");
            List<String> seeds = List.of();
            if (input.containsKey("seeds") && input.get("seeds") != null) {
                var arr = GraphJson.array(input.get("seeds"));
                var seedList = new ArrayList<String>(arr.size());
                for (var item : arr) {
                    seedList.add(GraphJson.string(item));
                }
                seeds = List.copyOf(seedList);
            }
            int maxFiles = optionalInt(input, "maxFiles", 30, 1, 100);
            int maxTokens = optionalInt(input, "maxTokens", 6000, 500, 24_000);
            var guard = ArchitectureQueryService.operationalGuard(cancelled,
                    System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
            return RepoMapBuilder.build(graph, preferredCtx, seeds, maxFiles, maxTokens, guard, searchIndex);
        }
        if (parts.length == 6 && parts[5].equals("repository-score")) {
            Map<String, String> paths;
            if (method.equals("GET")) {
                requireEmpty(body);
                paths = Map.of();
            } else {
                requireMethod(method, "POST");
                paths = RepositoryScore.customPaths(QueryJson.object(decode(body, contentType), "paths").get("paths"));
            }
            var guard = ArchitectureQueryService.operationalGuard(cancelled,
                    System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
            scoringCheckpoint(guard);
            return RepositoryScore.assess(load(graphId), paths, () -> scoringCheckpoint(guard));
        }
        if (parts.length == 6 && parts[5].equals("policy-assessment")) {
            requireMethod(method, "POST");
            requireAuthoritativeGraph(graphId);
            var input = QueryJson.object(decode(body, contentType), "context", "policy");
            var guard = ArchitectureQueryService.operationalGuard(cancelled,
                    System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
            scoringCheckpoint(guard);
            var graph = load(graphId);
            String contextId = QueryJson.text(input, "context");
            var context = graph.contexts().stream().filter(candidate -> candidate.identity().equals(contextId))
                    .findFirst()
                    .orElseThrow(() -> new Failure(404, "CONTEXT_NOT_FOUND"));
            var scope = WorkbenchPolicyAssessment.scope(graph, context);
            var queries = factory.apply(graph, guard);
            if (!queries.supportsArchitectureScope(context, scope))
                throw new Failure(422, "POLICY_SCOPE_UNAVAILABLE");
            return WorkbenchPolicyAssessment.evaluate(queries, context, scope, QueryJson.optionalText(input, "policy"),
                    reports, () -> scoringCheckpoint(guard));
        }
        if (parts.length == 6 && parts[5].equals("suggested-policy")) {
            requireMethod(method, "POST");
            requireAuthoritativeGraph(graphId);
            var input = QueryJson.object(decode(body, contentType), "context", "scope");
            var guard = ArchitectureQueryService.operationalGuard(cancelled,
                    System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
            scoringCheckpoint(guard);
            var graph = load(graphId);
            String contextId = QueryJson.text(input, "context");
            var context = graph.contexts().stream().filter(candidate -> candidate.identity().equals(contextId))
                    .findFirst()
                    .orElseThrow(() -> new Failure(404, "CONTEXT_NOT_FOUND"));
            var scope = input.containsKey("scope")
                    ? ArchitectureQuery.Scope.valueOf(QueryJson.text(input, "scope"))
                    : WorkbenchPolicyAssessment.scope(graph, context);
            var queries = factory.apply(graph, guard);
            if (!queries.supportsArchitectureScope(context, scope))
                throw new Failure(422, "POLICY_SCOPE_UNAVAILABLE");
            return WorkbenchPolicyAssessment.suggestPolicy(queries, context, scope, () -> scoringCheckpoint(guard));
        }
        if (parts.length == 6 && parts[5].equals("architecture-chat")) {
            requireMethod(method, "POST");
            return architectureChat(graphId, ArchitectureChat.Input.read(decode(body, contentType)), cancelled);
        }
        if (parts.length == 6 && parts[5].equals("architecture-insights")) {
            requireMethod(method, "POST");
            var input = decode(body, contentType);
            QueryJson.object(input, "request", "arguments");
            var request = QueryJson.request(graphId, input.get("request"));
            var args = input.containsKey("arguments") ? GraphJson.object(input.get("arguments"))
                    : Map.<String, Object>of();
            QueryJson.object(args, "report", "topN");
            int topN = 15;
            if (args.containsKey("topN")) {
                if (!(args.get("topN") instanceof java.math.BigDecimal bd))
                    throw new IllegalArgumentException("Invalid topN");
                try {
                    topN = bd.intValueExact();
                } catch (ArithmeticException invalid) {
                    throw new IllegalArgumentException("Invalid topN");
                }
                if (topN < 1 || topN > 50)
                    throw new IllegalArgumentException("Invalid topN range");
            }
            var graphLoaded = load(graphId);
            Optional<PublishedReports.Report> published = Optional.empty();
            if (args.containsKey("report")) {
                var reportId = new ContentDigest(QueryJson.text(args, "report"));
                published = Optional.of(reports.get(reportId)
                        .filter(r -> r.evaluation().graph().equals(graphId)
                                && r.evaluation().context().equals(request.context()))
                        .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND")));
            } else {
                for (var reportDigest : reports.forGraph(graphId)) {
                    var candidate = reports.get(reportDigest);
                    if (candidate.isPresent()
                            && candidate.orElseThrow().evaluation().context().equals(request.context())) {
                        published = candidate;
                        break;
                    }
                }
            }
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            var service = factory.apply(graphLoaded, guard);
            return ArchitectureInsightsAdvisor.analyze(service, request, published, topN);
        }
        if (parts.length == 7 && parts[5].equals("repair-proposal") && parts[6].equals("ad-hoc")) {
            requireMethod(method, "POST");
            requireAuthoritativeGraph(graphId);
            return handleAdHocRepairProposal(graphId, decode(body, contentType), cancelled);
        }
        if (parts.length == 9 && parts[5].equals("reports") && parts[7].equals("repair-verification")
                && parts[8].equals("jobs")) {
            requireMethod(method, "POST");
            requireAuthoritativeGraph(graphId);
            var reportId = new ContentDigest(parts[6]);
            var report = reports.get(reportId)
                    .filter(r -> r.evaluation().graph().equals(graphId))
                    .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
            var input = decode(body, contentType);
            if (input.containsKey("arguments")) {
                QueryJson.object(input, "arguments");
            }
            var args = input.containsKey("arguments") ? GraphJson.object(input.get("arguments")) : input;
            var merged = new LinkedHashMap<String, Object>(args);
            merged.put("graph", graphId.value());
            merged.put("report", report.identity().value());
            var proposalId = new ContentDigest(QueryJson.text(merged, "proposal"));
            return submitRepairVerificationJob(proposalId, merged, cancelled);
        }
        if (parts.length == 6 && parts[5].equals("visual-graph")) {
            requireMethod(method, "POST");
            Map<String, Object> input = body.length == 0
                    ? Map.of()
                    : QueryJson.object(decode(body, contentType),
                            "context", "level", "expandCommunities", "focusNodes", "cycleMode",
                            "maxNodes", "maxEdges", "groupBy", "seed", "depth", "report");
            var graph = load(graphId);
            String contextId = resolveVisualOrDsmContext(graph, QueryJson.optionalText(input, "context"));
            Optional<PolicyEvaluation.Result> policyOpt = Optional.empty();
            if (input.containsKey("report") && input.get("report") != null) {
                var reportId = new ContentDigest(QueryJson.text(input, "report"));
                var published = reports.get(reportId)
                        .filter(r -> r.evaluation().graph().equals(graphId)
                                && r.evaluation().context().equals(contextId))
                        .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
                policyOpt = Optional.of(published.evaluation());
            }
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            var service = factory.apply(graph, guard);
            var projection = new GraphProjectionService(service);
            boolean hasMultiScale = input.containsKey("level") || input.containsKey("expandCommunities")
                    || input.containsKey("focusNodes") || input.containsKey("cycleMode")
                    || input.containsKey("maxNodes") || input.containsKey("maxEdges");
            boolean legacyOnly = !hasMultiScale
                    && (input.containsKey("groupBy") || input.containsKey("seed") || input.containsKey("depth"));
            if (legacyOnly) {
                var req = new Request(graphId, contextId, Scope.TYPE, Projection.BOUNDED_UNION,
                        Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(),
                        Limits.defaults());
                int depth = visualDepth(input);
                var groupBy = GraphProjectionService.GroupBy.parse(
                        QueryJson.optionalText(input, "groupBy").orElse("PACKAGE"));
                if (input.containsKey("seed")) {
                    return projection.focusedSubgraph(req, QueryJson.text(input, "seed"), depth, policyOpt, groupBy);
                }
                return projection.project(req, policyOpt, groupBy);
            }
            var msReq = buildMultiScaleRequest(contextId, input, policyOpt);
            return projection.projectMultiScale(graph, msReq);
        }
        if (parts.length == 7 && parts[5].equals("queries") && parts[6].equals("dsm")) {
            requireMethod(method, "POST");
            Map<String, Object> input = body.length == 0
                    ? Map.of()
                    : QueryJson.object(decode(body, contentType),
                            "context", "granularity", "communityId", "maxItems", "report");
            var graph = load(graphId);
            String contextId = resolveVisualOrDsmContext(graph, QueryJson.optionalText(input, "context"));
            var granularity = DsmMatrixBuilder.Granularity.parse(
                    QueryJson.optionalText(input, "granularity").orElse("PACKAGE"));
            Optional<String> communityId = QueryJson.optionalText(input, "communityId");
            if (communityId.filter(s -> s.length() > 256).isPresent()) {
                throw new IllegalArgumentException("communityId exceeds 256 characters");
            }
            int maxItems = optionalInt(input, "maxItems",
                    DsmMatrixBuilder.DEFAULT_MAX_ITEMS,
                    DsmMatrixBuilder.MIN_MAX_ITEMS,
                    DsmMatrixBuilder.MAX_MAX_ITEMS);
            Set<String> violatedEdges = Set.of();
            if (input.containsKey("report") && input.get("report") != null) {
                var reportId = new ContentDigest(QueryJson.text(input, "report"));
                var published = reports.get(reportId)
                        .filter(r -> r.evaluation().graph().equals(graphId)
                                && r.evaluation().context().equals(contextId))
                        .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
                violatedEdges = extractViolatedEdgeKeys(published.evaluation());
            }
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            Runnable checkpoint = () -> {
                var stop = guard.get();
                if (stop != StopReason.NONE) {
                    throw new GraphProjectionService.ProjectionInterrupted(stop);
                }
            };
            var dsmReq = new DsmMatrixBuilder.DsmRequest(contextId, granularity, communityId, maxItems, violatedEdges);
            return DsmMatrixBuilder.build(graph, dsmReq, checkpoint).toView();
        }
        if (parts.length == 7 && parts[5].equals("queries") && parts[6].equals("code-search")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType),
                    "query", "languages", "includeAdvisory", "limit", "offset");
            String queryText = QueryJson.text(input, "query");
            if (queryText.length() > 512) {
                throw new IllegalArgumentException("Code search query exceeds 512 characters");
            }
            var languages = new ArrayList<String>();
            if (input.containsKey("languages") && input.get("languages") != null) {
                var arr = GraphJson.array(input.get("languages"));
                if (arr.size() > 32) {
                    throw new IllegalArgumentException("Too many language filters");
                }
                for (var item : arr) {
                    String lang = GraphJson.string(item).strip().toLowerCase(Locale.ROOT);
                    if (lang.isEmpty() || lang.length() > 64) {
                        throw new IllegalArgumentException("Invalid language filter");
                    }
                    languages.add(lang);
                }
            }
            boolean includeAdvisory = true;
            if (input.containsKey("includeAdvisory") && input.get("includeAdvisory") != null) {
                if (!(input.get("includeAdvisory") instanceof Boolean b)) {
                    throw new IllegalArgumentException("includeAdvisory must be a boolean");
                }
                includeAdvisory = b;
            }
            final boolean includeAdv = includeAdvisory;
            int limit = optionalInt(input, "limit", 25, 1, 100);
            int offset = optionalInt(input, "offset", 0, 0, 100_000);
            var graph = load(graphId);
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            var res = (searchIndex != null
                    ? searchIndex.codeSearch(graph, queryText, languages, includeAdv, limit, offset, guard)
                    : Optional.<CapturedSearchIndex.CodeSearchResult>empty())
                    .orElseGet(() -> CapturedFileIndex.forGraph(graph)
                            .codeSearchFallback(graph, queryText, languages, includeAdv, limit, offset, guard));
            var out = new LinkedHashMap<String, Object>();
            out.put("graph", graphId);
            out.put("query", queryText);
            out.put("engine", res.engine());
            out.put("totalHits", res.totalHits());
            out.put("truncated", res.truncated());
            out.put("omittedFiles", res.omittedFiles());
            out.put("redactedSecretCount", res.redactedSecretCount());
            out.put("offset", res.offset());
            out.put("limit", res.limit());
            out.put("items", res.items().stream().map(CapturedSearchIndex.CodeSearchHit::toView).toList());
            return out;
        }
        if (parts.length == 7 && parts[5].equals("queries") && parts[6].equals("vertical-trace")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType),
                    "context", "seed", "seedKind", "world", "maxDepth", "maxHops", "includeAdvisory");
            String seed = QueryJson.text(input, "seed");
            if (seed.length() > 2048) {
                throw new IllegalArgumentException("Trace seed exceeds 2048 characters");
            }
            String contextId = QueryJson.optionalText(input, "context").orElse("");
            Optional<String> seedKind = QueryJson.optionalText(input, "seedKind");
            if (seedKind.isPresent() && seedKind.orElseThrow().length() > 64) {
                throw new IllegalArgumentException("seedKind exceeds 64 characters");
            }
            Optional<String> world = QueryJson.optionalText(input, "world");
            if (world.isPresent() && world.orElseThrow().length() > 512) {
                throw new IllegalArgumentException("world exceeds 512 characters");
            }
            int maxDepth = optionalInt(input, "maxDepth",
                    VerticalTraceEngine.DEFAULT_MAX_DEPTH,
                    VerticalTraceEngine.MIN_MAX_DEPTH,
                    VerticalTraceEngine.MAX_MAX_DEPTH);
            int maxHops = optionalInt(input, "maxHops",
                    VerticalTraceEngine.DEFAULT_MAX_HOPS,
                    VerticalTraceEngine.MIN_MAX_HOPS,
                    VerticalTraceEngine.MAX_MAX_HOPS);
            boolean includeAdvisory = true;
            if (input.containsKey("includeAdvisory") && input.get("includeAdvisory") != null) {
                if (!(input.get("includeAdvisory") instanceof Boolean b)) {
                    throw new IllegalArgumentException("includeAdvisory must be a boolean");
                }
                includeAdvisory = b;
            }
            var graph = load(graphId);
            var traceReq = new VerticalTraceEngine.TraceRequest(
                    contextId, seed, seedKind, world, maxDepth, maxHops);
            return FullStackVerticalTraceService.trace(graph, traceReq, includeAdvisory).toView();
        }
        if (parts.length == 7 && parts[5].equals("queries") && parts[6].equals("code-slice")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType),
                    "path", "methodSignature", "line", "direction", "maxStatements");
            String sourcePath = QueryJson.text(input, "path");
            if (sourcePath.length() > 1024) {
                throw new IllegalArgumentException("Source path exceeds 1024 characters");
            }
            Optional<String> methodSignature = QueryJson.optionalText(input, "methodSignature");
            if (methodSignature.isPresent() && methodSignature.orElseThrow().length() > 512) {
                throw new IllegalArgumentException("methodSignature exceeds 512 characters");
            }
            if (!input.containsKey("line") || input.get("line") == null) {
                throw new IllegalArgumentException("line is required");
            }
            int line = optionalInt(input, "line", 1, 1, 1_000_000);
            String rawDir = QueryJson.optionalText(input, "direction").orElse("BACKWARD");
            MethodSliceResult.SliceDirection direction = MethodSliceResult.SliceDirection.parse(rawDir);
            int maxStatements = optionalInt(input, "maxStatements",
                    JavaMethodSlicer.DEFAULT_MAX_STATEMENTS,
                    JavaMethodSlicer.MIN_MAX_STATEMENTS,
                    JavaMethodSlicer.MAX_MAX_STATEMENTS);
            var graph = load(graphId);
            try {
                return FullStackVerticalTraceService.sliceCapturedJava(
                        graph, sourcePath, methodSignature, line, direction, maxStatements).toView();
            } catch (FullStackVerticalTraceService.CapturedSourceNotFoundException missing) {
                throw new Failure(404, "SOURCE_NOT_FOUND");
            }
        }
        if (parts.length == 7 && parts[5].equals("queries") && OPERATIONS.contains(parts[6])) {
            requireMethod(method, "POST");
            var input = decode(body, contentType);
            QueryJson.object(input, "request", "arguments", "page");
            if (input.containsKey("page") && input.get("page") == null)
                throw new IllegalArgumentException("Page object required");
            var request = QueryJson.request(graphId, input.get("request"));
            var page = QueryJson.page(input.get("page"));
            var args = input.containsKey("arguments") ? GraphJson.object(input.get("arguments"))
                    : Map.<String, Object>of();
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            var service = factory.apply(load(graphId), guard);
            return query(parts[6], service, request, page, args, input.containsKey("page"));
        }
        if ((parts.length == 7 || parts.length == 8) && parts[5].equals("reports")) {
            var reportId = new ContentDigest(parts[6]);
            if (parts.length == 7) {
                requireMethod(method, "GET");
                requireEmpty(body);
            } else if (REPORT_OPERATIONS.contains(parts[7]))
                requireMethod(method, "POST");
            else
                throw new Failure(404, "NOT_FOUND");
            requireAuthoritativeGraph(graphId);
            var contexts = manifest(graphId).contexts();
            var report = reports.get(reportId).filter(r -> r.evaluation().graph().equals(graphId))
                    .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
            var partition = contexts.stream().filter(c -> c.identity().equals(report.evaluation().context()))
                    .findFirst()
                    .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
            if (report.metrics().isPresent()
                    && (!report.metrics().orElseThrow().context().snapshot().equals(partition.snapshot())
                            || !report.metrics().orElseThrow().context().analysis().equals(partition.analysis())))
                throw new Failure(404, "REPORT_NOT_FOUND");
            if (report.evaluation().findings().stream()
                    .anyMatch(f -> !f.bundle().snapshot().equals(partition.snapshot())
                            || !f.bundle().analysis().equals(partition.analysis())))
                throw new Failure(404, "REPORT_NOT_FOUND");
            if (parts.length == 7)
                return Map.of("identity", reportId, "context", report.evaluation().context(), "evaluation",
                        report.evaluation().identity(),
                        "status", report.evaluation().status(), "gaps", report.evaluation().gaps(), "ruleCount",
                        report.evaluation().rules().size(),
                        "findingCount", report.evaluation().findings().size(), "hasMetrics",
                        report.metrics().isPresent(), "hasAssessment", report.assessment().isPresent());
            var input = decode(body, contentType);
            QueryJson.object(input, "arguments", "page");
            if (input.containsKey("page") && input.get("page") == null)
                throw new IllegalArgumentException("Page object required");
            var args = input.containsKey("arguments") ? GraphJson.object(input.get("arguments"))
                    : Map.<String, Object>of();
            long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
            var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
            var service = factory.apply(load(graphId), guard);
            return report(parts[7], reportId, report, args, QueryJson.page(input.get("page")),
                    input.containsKey("page"), service);
        }
        throw new Failure(404, "NOT_FOUND");
    }

    private static void scoringCheckpoint(Supplier<StopReason> guard) {
        var stop = guard.get();
        if (stop == StopReason.CANCELLED)
            throw new Failure(409, "REQUEST_CANCELLED");
        if (stop != StopReason.NONE)
            throw new Failure(503, "SCORING_RESOURCE_LIMIT");
    }

    interface SseEmitter {
        void stage(String message);

        void tool(Map<String, Object> payload);

        void delta(String text);

        void complete(byte[] envelopeJson);

        void error(int status, String code, String message);
    }

    void streamArchitectureChat(ContentDigest graphId, byte[] body, String contentType,
            BooleanSupplier cancelled, SseEmitter emitter) {
        try {
            if (body.length > limits.requestBytes())
                throw new Failure(413, "REQUEST_TOO_LARGE");
            var input = ArchitectureChat.Input.read(decode(body, contentType));
            var result = runArchitectureChat(graphId, input, cancelled, new ArchitectureAgent.ProgressListener() {
                @Override
                public void onStage(String message) {
                    emitter.stage(message);
                }

                @Override
                public void onToolStart(int step, String tool, Map<String, Object> arguments) {
                    emitter.tool(Map.of("phase", "start", "step", step, "tool", tool, "arguments", arguments));
                }

                @Override
                public void onToolComplete(ArchitectureChat.AgentStep step) {
                    emitter.tool(Map.of("phase", "complete", "step", step.step(), "tool", step.tool(),
                            "summary", step.summary(), "evidenceIds", step.evidenceIds(), "durationMs",
                            step.durationMs()));
                }

                @Override
                public void onDelta(String textChunk) {
                    emitter.delta(textChunk);
                }
            });
            byte[] canonicalData = BoundedJson.encode(result, limits.responseBytes());
            byte[] envelope = BoundedJson.encode(
                    Map.of("apiVersion", VERSION, "queryVersion", ArchitectureQuery.VERSION,
                            "resultIdentity", ContentDigest.sha256(canonicalData), "data", result),
                    limits.responseBytes());
            emitter.complete(envelope);
        } catch (Failure failure) {
            emitter.error(failure.status, failure.code, message(failure.code));
        } catch (BoundedJson.TooLarge large) {
            emitter.error(422, "RESPONSE_TOO_LARGE", message("RESPONSE_TOO_LARGE"));
        } catch (GraphJson.LimitExceededException large) {
            emitter.error(413, "JSON_LIMIT_EXCEEDED", message("JSON_LIMIT_EXCEEDED"));
        } catch (GraphStoreException unavailable) {
            emitter.error(503, "GRAPH_STORE_UNAVAILABLE", message("GRAPH_STORE_UNAVAILABLE"));
        } catch (IllegalArgumentException invalid) {
            emitter.error(400, "INVALID_REQUEST", message("INVALID_REQUEST"));
        } catch (RuntimeException failed) {
            emitter.error(500, "INTERNAL_ERROR", message("INTERNAL_ERROR"));
        }
    }

    private ArchitectureChat.Result architectureChat(ContentDigest graphId, ArchitectureChat.Input input,
            BooleanSupplier cancelled) {
        return runArchitectureChat(graphId, input, cancelled, null);
    }

    private Response handleGraphConversations(String method, String rawPath, String rawQuery, String contentType, byte[] body) {
        if (chatStore == null) {
            throw new Failure(503, "CONVERSATION_STORE_UNAVAILABLE");
        }
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 6 || !"graphs".equals(parts[3]) || !"conversations".equals(parts[5])) {
            throw new Failure(404, "NOT_FOUND");
        }
        ContentDigest graphId = new ContentDigest(parts[4]);
        String owner = com.evolution.backend.auth.RequestContext.currentUserId();
        if ("GET".equals(method)) {
            requireEmpty(body);
            // Ensure graph exists cleanly (404 GRAPH_NOT_FOUND if unknown).
            manifest(graphId);
            int limit = ChatConversationStore.DEFAULT_LIST_LIMIT;
            var rawLimit = extractQueryParam(rawQuery, "limit");
            if (rawLimit.isPresent()) {
                try {
                    limit = Integer.parseInt(rawLimit.orElseThrow());
                } catch (NumberFormatException invalid) {
                    throw new IllegalArgumentException("Invalid limit");
                }
                if (limit < 1 || limit > ChatConversationStore.MAX_LIST_LIMIT) {
                    throw new IllegalArgumentException("Limit out of range");
                }
            }
            var items = chatStore.listConversations(owner, graphId.value(), limit).stream()
                    .map(ChatConversationStore.ConversationSummary::toView)
                    .toList();
            var out = new LinkedHashMap<String, Object>();
            out.put("graph", graphId);
            out.put("conversations", items);
            return okEnvelope(200, Collections.unmodifiableMap(out));
        }
        if ("POST".equals(method)) {
            if (body.length > limits.requestBytes()) {
                throw new Failure(413, "REQUEST_TOO_LARGE");
            }
            Map<String, Object> input = body.length == 0
                    ? Map.of()
                    : QueryJson.object(decode(body, contentType),
                            "context", "contextId", "snapshotDigest", "mode", "title", "findingId");
            var graph = load(graphId);
            Optional<String> requestedContext = QueryJson.optionalText(input, "context")
                    .or(() -> QueryJson.optionalText(input, "contextId"));
            String contextId;
            if (requestedContext.isPresent()) {
                String reqCtx = requestedContext.orElseThrow();
                var partition = graph.contexts().stream()
                        .filter(c -> c.identity().equals(reqCtx))
                        .findFirst()
                        .orElseThrow(() -> new Failure(404, "CONTEXT_NOT_FOUND"));
                contextId = partition.identity();
            } else {
                contextId = defaultAnalysisContext(graph);
            }
            QueryJson.optionalText(input, "snapshotDigest"); // validate string if present; authoritative snapshot comes from graph
            String mode = QueryJson.optionalText(input, "mode").orElse("EXPLORE");
            String title = QueryJson.optionalText(input, "title").orElse(ChatConversationStore.DEFAULT_TITLE);
            Optional<String> findingId = QueryJson.optionalText(input, "findingId");
            var created = chatStore.createConversation(
                    owner, graphId.value(), graph.snapshot().value(), contextId, mode, title, findingId);
            return okEnvelope(201, Map.of("conversation", created.toView()));
        }
        throw new Failure(405, "METHOD_NOT_ALLOWED");
    }

    private Response handleConversationById(String method, String rawPath, byte[] body) {
        if (chatStore == null) {
            throw new Failure(503, "CONVERSATION_STORE_UNAVAILABLE");
        }
        String[] parts = rawPath.split("/", -1);
        if (parts.length != 5 || !"conversations".equals(parts[3]) || parts[4].isEmpty()) {
            throw new Failure(404, "NOT_FOUND");
        }
        String conversationId = parts[4];
        if (!ChatConversationStore.isValidConversationId(conversationId)) {
            throw new IllegalArgumentException("Invalid conversationId");
        }
        String owner = com.evolution.backend.auth.RequestContext.currentUserId();
        if ("GET".equals(method)) {
            requireEmpty(body);
            try {
                var detail = chatStore.getConversation(owner, conversationId);
                return okEnvelope(200, detail.toView());
            } catch (ChatConversationStore.NotFoundException missing) {
                throw new Failure(404, "CONVERSATION_NOT_FOUND");
            } catch (ChatConversationStore.AccessDeniedException denied) {
                throw new Failure(403, "ACCESS_DENIED");
            }
        }
        if ("DELETE".equals(method)) {
            requireEmpty(body);
            try {
                boolean deleted = chatStore.deleteConversation(owner, conversationId);
                return okEnvelope(200, Map.of("deleted", deleted, "conversationId", conversationId));
            } catch (ChatConversationStore.NotFoundException missing) {
                throw new Failure(404, "CONVERSATION_NOT_FOUND");
            } catch (ChatConversationStore.AccessDeniedException denied) {
                throw new Failure(403, "ACCESS_DENIED");
            }
        }
        throw new Failure(405, "METHOD_NOT_ALLOWED");
    }

    private ArchitectureChat.Result runArchitectureChat(ContentDigest graphId, ArchitectureChat.Input input,
            BooleanSupplier cancelled,
            ArchitectureAgent.ProgressListener listener) {
        if (input.mode() == ArchitectureChat.Mode.REPAIR) {
            requireAuthoritativeGraph(graphId);
        }
        String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        if (input.conversationId().isPresent()) {
            if (chatStore == null) {
                throw new Failure(503, "CONVERSATION_STORE_UNAVAILABLE");
            }
            try {
                chatStore.verifyAccess(ownerUserId, input.conversationId().orElseThrow(), graphId.value());
            } catch (ChatConversationStore.NotFoundException missing) {
                throw new Failure(404, "CONVERSATION_NOT_FOUND");
            } catch (ChatConversationStore.AccessDeniedException denied) {
                throw new Failure(403, "ACCESS_DENIED");
            } catch (IllegalArgumentException mismatch) {
                if ("CONVERSATION_GRAPH_MISMATCH".equals(mismatch.getMessage())) {
                    throw new Failure(400, "CONVERSATION_GRAPH_MISMATCH");
                }
                throw mismatch;
            }
        }
        var result = computeArchitectureChatResult(graphId, input, cancelled, listener);
        if (input.conversationId().isPresent() && chatStore != null && !input.reviewer()) {
            String convId = input.conversationId().orElseThrow();
            String assistantContent = result.answer()
                    .filter(s -> !s.isBlank())
                    .orElseGet(() -> "Evidence retrieved (" + result.evidence().size() + " items).");
            Map<String, Object> promptCacheMetrics = result.model()
                    .map(ArchitectureChatModel.Receipt::usage)
                    .filter(u -> u != null && u.hasData())
                    .map(u -> {
                        var m = new LinkedHashMap<String, Object>(u.toView());
                        if (architectureChat != null && architectureChat.adapter() != null) {
                            m.put("provider", architectureChat.adapter().descriptor().kind().name());
                        }
                        return (Map<String, Object>) Collections.unmodifiableMap(m);
                    })
                    .orElse(Map.of());
            Optional<String> modelId = result.model().map(ArchitectureChatModel.Receipt::returnedModel);
            try {
                var appended = chatStore.appendTurnPair(
                        ownerUserId,
                        convId,
                        graphId.value(),
                        input.question(),
                        assistantContent,
                        result.evidence(),
                        result.agentTrace(),
                        result.resolvedCitations(),
                        promptCacheMetrics,
                        Optional.of(result.evidenceIdentity().value()),
                        modelId,
                        result.conversationTitle());
                return result.withPersistence(convId, appended.assistantTurnId());
            } catch (ChatConversationStore.NotFoundException missing) {
                throw new Failure(404, "CONVERSATION_NOT_FOUND");
            } catch (ChatConversationStore.AccessDeniedException denied) {
                throw new Failure(403, "ACCESS_DENIED");
            } catch (IllegalArgumentException mismatch) {
                if ("CONVERSATION_GRAPH_MISMATCH".equals(mismatch.getMessage())) {
                    throw new Failure(400, "CONVERSATION_GRAPH_MISMATCH");
                }
                throw mismatch;
            }
        }
        return result;
    }

    private ArchitectureChat.Result computeArchitectureChatResult(ContentDigest graphId, ArchitectureChat.Input input,
            BooleanSupplier cancelled,
            ArchitectureAgent.ProgressListener listener) {
        var model = architectureChat;
        boolean offTopic = ArchitectureChat.directlyOutOfScope(input);
        boolean hasConsentedModel = model != null && (!model.provider().remote() || input.allowRemote());
        boolean shortCircuitOverview = ArchitectureChatOverview.shouldShortCircuitLocal(
                input.question(), hasConsentedModel, input.evidenceOnly());
        if (!offTopic && !shortCircuitOverview && !input.evidenceOnly() && model != null && model.provider().remote()
                && !input.allowRemote())
            throw new Failure(403, "CHAT_CONSENT_REQUIRED");
        var guard = ArchitectureQueryService.operationalGuard(cancelled,
                System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
        var graph = load(graphId);
        var partition = graph.contexts().stream().filter(context -> context.identity().equals(input.context()))
                .findFirst()
                .orElseThrow(() -> new Failure(404, "CONTEXT_NOT_FOUND"));
        var finding = input.finding().map(ref -> reports.get(ref.report())
                .filter(report -> report.evaluation().graph().equals(graphId)
                        && report.evaluation().context().equals(partition.identity()))
                .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND")).evaluation().findings().stream()
                .filter(row -> row.identity().equals(ref.identity())).findFirst()
                .filter(row -> row.bundle().identity().equals(ref.bundle())
                        && row.bundle().snapshot().equals(partition.snapshot())
                        && row.bundle().analysis().equals(partition.analysis()))
                .orElseThrow(() -> new Failure(409, "DIAGNOSIS_SCOPE_MISMATCH")));
        if (offTopic) {
            return ArchitectureChat.Result.offTopic(ArchitectureChat.pin(input, partition, graphId),
                    input.question(), Optional.empty());
        }
        if (listener != null)
            listener.onStage("Retrieving snapshot evidence…");
        var queries = factory.apply(graph, guard);
        var context = ArchitectureChat.retrieve(input, partition, queries, finding);
        if (input.job().isPresent() && jobs != null) {
            var job = jobs.view(input.job().orElseThrow());
            var result = job.result().orElseThrow(() -> new IllegalArgumentException("Job has no result"));
            if (!result.graph().equals(Optional.of(graphId))
                    || !result.snapshot().equals(Optional.of(partition.snapshot())))
                throw new Failure(409, "CHAT_JOB_SCOPE_MISMATCH");
            context = context.withJob(job, result);
            try {
                context = context.withGaps(jobs.gaps(job.id()), job.id());
            } catch (AnalysisJobs.Rejected unavailable) {
                if (!"JOB_STORAGE_UNAVAILABLE".equals(unavailable.code()))
                    throw unavailable;
            }
            if (connections != null) {
                try {
                    context = context.withReadiness(connections.publishedOverview(job.id(), job.mode()), job.id());
                } catch (java.io.IOException unavailable) {
                    /* Keep using the pinned graph and job. */ }
            }
        }
        if (guard.get() != StopReason.NONE)
            throw new Failure(503, "CHAT_RESOURCE_LIMIT");
        if (shortCircuitOverview)
            return ArchitectureChat.Result.overview(context,
                    ArchitectureChatOverview.render(input.question(), context));
        if (model == null || input.evidenceOnly())
            return ArchitectureChat.Result.local(context, model != null);
        try {
            if (input.agentic() && !model.settings().jsonMode()) {
                var agentResult = ArchitectureAgent.run(model, input, context, queries, reports,
                        Optional.ofNullable(jobs), Optional.ofNullable(searchIndex), guard, listener);
                if ("OUT_OF_SCOPE".equals(agentResult.status()))
                    return ArchitectureChat.Result.offTopic(context.pin(), input.question(), agentResult.model());
                return agentResult;
            }
            if (listener != null)
                listener.onStage("Generating architecture response…");
            var completion = model.complete(input, context, graph, guard, listener == null ? null : listener::onDelta);
            if ("OUT_OF_SCOPE".equals(completion.status()))
                return ArchitectureChat.Result.offTopic(context.pin(), input.question(),
                        Optional.of(completion.receipt()));
            return ArchitectureChat.Result.advice(context, completion);
        } catch (ArchitectureChatModel.Rejected rejected) {
            int status = switch (rejected.reason) {
                case CHAT_INVALID_MODEL_OUTPUT, CHAT_PROVIDER_UNAVAILABLE -> 502;
                case CHAT_BUSY, CHAT_RESOURCE_LIMIT -> 503;
            };
            throw new Failure(status, rejected.reason.name());
        }
    }

    private KnowledgeGraph load(ContentDigest identity) {
        var durable = store.load(identity);
        if (durable.isPresent())
            return durable.orElseThrow();
        if (previewStore != null) {
            var state = previewStore.lookupState(identity);
            if (state == WorkbenchPreviewStore.LookupState.ACTIVE) {
                return previewStore.load(identity).orElseThrow(() -> new Failure(404, "GRAPH_NOT_FOUND"));
            }
            if (state == WorkbenchPreviewStore.LookupState.SUPERSEDED) {
                throw new SupersededFailure(identity, previewStore.supersededBy(identity));
            }
        }
        throw new Failure(404, "GRAPH_NOT_FOUND");
    }

    private GraphManifest manifest(ContentDigest identity) {
        var durable = store.manifest(identity);
        if (durable.isPresent())
            return durable.orElseThrow();
        if (previewStore != null) {
            var state = previewStore.lookupState(identity);
            if (state == WorkbenchPreviewStore.LookupState.ACTIVE) {
                return previewStore.manifest(identity).orElseThrow(() -> new Failure(404, "GRAPH_NOT_FOUND"));
            }
            if (state == WorkbenchPreviewStore.LookupState.SUPERSEDED) {
                throw new SupersededFailure(identity, previewStore.supersededBy(identity));
            }
        }
        throw new Failure(404, "GRAPH_NOT_FOUND");
    }

    private Optional<WorkbenchPreviewStore.PreviewMeta> activePreviewMeta(ContentDigest graphId) {
        if (previewStore == null || store.available(graphId))
            return Optional.empty();
        return previewStore.previewMeta(graphId);
    }

    private void requireAuthoritativeGraph(ContentDigest graphId) {
        if (previewStore == null || store.available(graphId))
            return;
        var state = previewStore.lookupState(graphId);
        if (state == WorkbenchPreviewStore.LookupState.SUPERSEDED)
            throw new SupersededFailure(graphId, previewStore.supersededBy(graphId));
        if (state == WorkbenchPreviewStore.LookupState.ACTIVE)
            throw new Failure(409, "PRELIMINARY_GRAPH_PENDING");
    }

    private static Map<String, Object> discoveryPayload(ContentDigest graphId, String snapshot,
            List<Map<String, Object>> contexts, List<ContentDigest> reportIds,
            Optional<WorkbenchPreviewStore.PreviewMeta> previewMeta) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("graph", graphId);
        payload.put("snapshot", snapshot);
        payload.put("contexts", contexts);
        payload.put("reports", reportIds);
        if (previewMeta.isPresent()) {
            var meta = previewMeta.orElseThrow();
            payload.put("authoritative", false);
            payload.put("truthTier", meta.truthTier().name());
            var metaMap = new LinkedHashMap<String, Object>();
            metaMap.put("jobId", meta.jobId());
            metaMap.put("truthTier", meta.truthTier().name());
            metaMap.put("completedContexts", meta.completedContexts());
            metaMap.put("totalContexts", meta.totalContexts());
            metaMap.put("pendingModules", meta.pendingModules());
            metaMap.put("publishedAt", meta.publishedAt());
            meta.supersededByGraphId().ifPresent(id -> metaMap.put("supersededByGraphId", id.value()));
            payload.put("previewMeta", metaMap);
        } else {
            payload.put("authoritative", true);
            payload.put("truthTier", AnalysisJobs.TruthTier.DEEP_SEMANTIC_COMPLETE.name());
        }
        return payload;
    }

    private ArchitectureAssessmentV2.Result evaluateArchitectureScoreV2(ContentDigest graphId,
            Map<String, Object> input, BooleanSupplier cancelled) {
        if (input.containsKey("policy") && input.containsKey("report"))
            throw new IllegalArgumentException("Specify either policy or report, not both");
        var previewMeta = activePreviewMeta(graphId);
        var graph = load(graphId);
        Optional<String> requestedCtx = QueryJson.optionalText(input, "context");
        String targetCtx = requestedCtx.orElse(graph.snapshot().value());
        if (previewMeta.isPresent()
                && previewMeta.orElseThrow().truthTier() == AnalysisJobs.TruthTier.PRELIMINARY_STRUCTURE) {
            var model = ArchitectureScoreModelV2.standard();
            var confidence = new ArchitectureAssessmentV2.AnalysisConfidenceV2(
                    BigDecimal.ZERO, ArchitectureAssessmentV2.ConfidenceTier.INSUFFICIENT, "PRELIMINARY_STRUCTURE",
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty(),
                    0L, Map.of(), List.of("Preliminary structural preview only; wait for semantic analysis completion."));
            return new ArchitectureAssessmentV2.Result(
                    graphId, targetCtx,
                    targetCtx.equals(graph.snapshot().value()) ? "WHOLE_REPOSITORY" : "CONTEXT",
                    ArchitectureScoreModelV2.FORMULA, model.identity(), ArchitectureScoreModelV2.CALIBRATION, AssessmentStatus.WITHHELD,
                    Optional.empty(), Optional.empty(), BigDecimal.ZERO, "Withheld", BigDecimal.ZERO,
                    List.of(), confidence, Optional.empty(), List.of(), Optional.empty(),
                    List.of(), List.of("PRELIMINARY_GRAPH_PENDING"), Optional.empty());
        }
        var guard = ArchitectureQueryService.operationalGuard(cancelled,
                System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
        scoringCheckpoint(guard);
        var queries = factory.apply(graph, guard);
        var model = ArchitectureScoreModelV2.standard();
        var service = new ArchitectureAssessmentV2Service();
        Optional<String> policyText = QueryJson.optionalText(input, "policy");
        Optional<ContentDigest> reportDigest = QueryJson.optionalText(input, "report").map(ContentDigest::new);

        if (targetCtx.equals(graph.snapshot().value())) {
            var policyMap = new HashMap<String, PolicyEvaluation.Result>();
            if (reportDigest.isPresent()) {
                var rep = reports.get(reportDigest.orElseThrow())
                        .filter(r -> r.evaluation().graph().equals(graphId))
                        .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
                policyMap.put(rep.evaluation().context(), rep.evaluation());
            } else if (policyText.isPresent()) {
                var compiled = new PolicyCompiler().compile(policyText.orElseThrow().getBytes(StandardCharsets.UTF_8),
                        EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
                for (var ctx : graph.contexts()) {
                    if (ctx.identity().equals(graph.snapshot().value())) continue;
                    try {
                        var plan = new PolicyGraphMapper().bind(compiled, queries, ctx.identity(),
                                PolicyGraphMapper.Limits.defaults());
                        var eval = new PolicyEvaluator().evaluate(compiled, plan, queries,
                                PolicyEvaluation.Limits.defaults());
                        policyMap.put(ctx.identity(), eval);
                    } catch (RuntimeException ignored) {}
                }
            }
            return service.assessRepository(queries, policyMap, reportDigest, model);
        }

        boolean exists = graph.contexts().stream().anyMatch(c -> c.identity().equals(targetCtx));
        if (!exists)
            throw new Failure(404, "CONTEXT_NOT_FOUND");
        Optional<PolicyEvaluation.Result> customPolicy = Optional.empty();
        if (reportDigest.isPresent()) {
            var rep = reports.get(reportDigest.orElseThrow())
                    .filter(r -> r.evaluation().graph().equals(graphId) && r.evaluation().context().equals(targetCtx))
                    .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND"));
            customPolicy = Optional.of(rep.evaluation());
        } else if (policyText.isPresent()) {
            var compiled = new PolicyCompiler().compile(policyText.orElseThrow().getBytes(StandardCharsets.UTF_8),
                    EvidenceContext.forSnapshot(graph.snapshot()), PolicyCompiler.Limits.defaults());
            var plan = new PolicyGraphMapper().bind(compiled, queries, targetCtx, PolicyGraphMapper.Limits.defaults());
            customPolicy = Optional.of(new PolicyEvaluator().evaluate(compiled, plan, queries,
                    PolicyEvaluation.Limits.defaults()));
        }
        return service.assessContext(queries, targetCtx, customPolicy, reportDigest, model);
    }

    private static int optionalInt(Map<String, Object> input, String key, int defaultValue, int min, int max) {
        if (!input.containsKey(key) || input.get(key) == null)
            return defaultValue;
        if (!(input.get(key) instanceof BigDecimal bd))
            throw new IllegalArgumentException("Expected integer field: " + key);
        int val;
        try {
            val = bd.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid integer field: " + key);
        }
        if (val < min || val > max)
            throw new IllegalArgumentException("Out-of-range field: " + key);
        return val;
    }

    private Optional<java.nio.file.Path> resolveDiskJsonPath(ContentDigest graphId) {
        if (store instanceof com.evolution.analysis.filesystem.FileGraphStore fgs)
            return Optional.of(fgs.jsonPath(graphId));
        if (store instanceof GraphStorageLocation loc)
            return Optional.ofNullable(loc.jsonPath(graphId));
        return Optional.empty();
    }

    private static final String CONTEXTS_CACHE_VERSION = "contexts-cache:v1";

    private Optional<CachedDiscovery> readDiskDiscoveryCache(ContentDigest graphId) {
        var jsonPathOpt = resolveDiskJsonPath(graphId);
        if (jsonPathOpt.isEmpty())
            return Optional.empty();
        try {
            var jsonPath = jsonPathOpt.orElseThrow();
            if (!java.nio.file.Files.isRegularFile(jsonPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return Optional.empty();
            var cachePath = jsonPath.getParent()
                    .resolve(com.evolution.analysis.filesystem.GraphBinaryCache.CACHE_DIR_NAME)
                    .resolve(graphId.value().substring(7) + ".contexts.v1.json");
            if (!java.nio.file.Files.isRegularFile(cachePath, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || java.nio.file.Files.size(cachePath) > 2_000_000L)
                return Optional.empty();
            var raw = java.nio.file.Files.readString(cachePath, StandardCharsets.UTF_8);
            var envelope = GraphJson.object(GraphJson.read(raw, new GraphJson.Limits(2_000_000, 32, 200_000)));
            if (!(envelope.get("payload") instanceof Map<?, ?> rawPayload)
                    || !(envelope.get("digest") instanceof String digest))
                return Optional.empty();
            var payload = GraphJson.object(rawPayload);
            if (!ContentDigest.sha256Utf8(CanonicalJson.write(payload)).value().equals(digest))
                return Optional.empty();
            if (!CONTEXTS_CACHE_VERSION.equals(payload.get("version"))
                    || !graphId.value().equals(payload.get("graph"))
                    || !(payload.get("snapshot") instanceof String snapshot)
                    || !(payload.get("jsonSize") instanceof java.math.BigDecimal size)
                    || !(payload.get("jsonMtime") instanceof java.math.BigDecimal mtime)
                    || !(payload.get("contexts") instanceof List<?> rawContexts))
                return Optional.empty();
            if (size.longValueExact() != java.nio.file.Files.size(jsonPath)
                    || mtime.longValueExact() != java.nio.file.Files.getLastModifiedTime(jsonPath).toMillis())
                return Optional.empty();
            var contexts = new ArrayList<Map<String, Object>>(rawContexts.size());
            for (var item : rawContexts) {
                contexts.add(Collections.unmodifiableMap(new TreeMap<>(GraphJson.object(item))));
            }
            return Optional.of(new CachedDiscovery(snapshot, Collections.unmodifiableList(contexts)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void writeDiskDiscoveryCache(ContentDigest graphId, CachedDiscovery cached) {
        var jsonPathOpt = resolveDiskJsonPath(graphId);
        if (jsonPathOpt.isEmpty())
            return;
        try {
            var jsonPath = jsonPathOpt.orElseThrow();
            if (!java.nio.file.Files.isRegularFile(jsonPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return;
            var cacheDir = jsonPath.getParent()
                    .resolve(com.evolution.analysis.filesystem.GraphBinaryCache.CACHE_DIR_NAME);
            java.nio.file.Files.createDirectories(cacheDir);
            var cachePath = cacheDir.resolve(graphId.value().substring(7) + ".contexts.v1.json");
            var payload = new TreeMap<String, Object>();
            payload.put("version", CONTEXTS_CACHE_VERSION);
            payload.put("graph", graphId.value());
            payload.put("snapshot", cached.snapshot());
            payload.put("jsonSize", java.nio.file.Files.size(jsonPath));
            payload.put("jsonMtime", java.nio.file.Files.getLastModifiedTime(jsonPath).toMillis());
            payload.put("contexts", cached.contexts());
            var envelope = Map.of("payload", payload,
                    "digest", ContentDigest.sha256Utf8(CanonicalJson.write(payload)).value());
            var bytes = CanonicalJson.write(envelope).getBytes(StandardCharsets.UTF_8);
            var tmp = java.nio.file.Files.createTempFile(cacheDir, "contexts-", ".tmp");
            try {
                java.nio.file.Files.write(tmp, bytes);
                try {
                    java.nio.file.Files.move(tmp, cachePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                    java.nio.file.Files.move(tmp, cachePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                java.nio.file.Files.deleteIfExists(tmp);
            }
        } catch (Exception ignored) {
            // Disposable discovery cache is best-effort only.
        }
    }

    private record ContextAnalysisMeta(String modulePath, String sourceSet) {
    }

    private static final GraphJson.Limits CONTEXT_METADATA_LIMITS = new GraphJson.Limits(4_000_000, 128, 1_000_000);
    private static final Set<String> KNOWN_SOURCE_SETS = Set.of("MAIN", "TEST", "GENERATED_MAIN", "GENERATED_TEST");

    /**
     * Enrich discovery contexts with sanitized, already-captured provenance fields
     * while preserving every canonical KnowledgeGraph.Context field verbatim.
     */
    private static List<Map<String, Object>> describeContexts(KnowledgeGraph graph) {
        var modulePaths = new HashMap<String, String>();
        var analysisMeta = new HashMap<String, ContextAnalysisMeta>();
        var parsedPayloads = new HashMap<String, Map<String, Object>>();

        for (var node : graph.nodes(graph.snapshot().value())) {
            if (!"MODULE".equals(node.kind()))
                continue;
            for (var ref : node.evidence()) {
                var ev = graph.evidence(ref).orElse(null);
                if (ev == null || !"build.module".equals(ev.family()))
                    continue;
                var payload = parseContextPayload(ev);
                if (payload.get("descriptor") instanceof Map<?, ?> rawDescriptor) {
                    var descriptor = GraphJson.object(rawDescriptor);
                    if (descriptor.get("identity") instanceof String modId) {
                        String safePath = safeModulePath(descriptor.get("path"));
                        if (safePath != null)
                            modulePaths.putIfAbsent(modId, safePath);
                    }
                }
            }
        }

        for (var ctx : graph.contexts()) {
            var ev = graph.evidence(ctx.evidence()).orElse(null);
            if (ev == null || !"analysis.manifest".equals(ev.family()))
                continue;
            var payload = parseContextPayload(ev);
            parsedPayloads.put(ctx.identity(), payload);
            String singleModulePath = null;
            int moduleCount = 0;
            if (payload.get("modules") instanceof List<?> modules) {
                moduleCount = modules.size();
                for (var raw : modules) {
                    if (!(raw instanceof Map<?, ?> row))
                        continue;
                    String safePath = safeModulePath(row.get("path"));
                    if (row.get("identity") instanceof String modId && safePath != null) {
                        modulePaths.put(modId, safePath);
                    }
                    if (moduleCount == 1 && safePath != null) {
                        singleModulePath = safePath;
                    }
                }
            }
            String modulePath = null;
            String sourceSet = null;
            if (payload.get("configuration") instanceof Map<?, ?> cfg
                    && cfg.get("values") instanceof Map<?, ?> values) {
                if (values.get("java.module") instanceof String modId) {
                    modulePath = modulePaths.get(modId);
                }
                sourceSet = safeSourceSet(values.get("java.source-set"));
            }
            if (modulePath == null && singleModulePath != null) {
                modulePath = singleModulePath;
            }
            analysisMeta.put(ctx.identity(), new ContextAnalysisMeta(modulePath, sourceSet));
        }

        var described = new ArrayList<Map<String, Object>>(graph.contexts().size());
        String snapshotId = graph.snapshot().value();
        for (var ctx : graph.contexts()) {
            var item = new TreeMap<String, Object>();
            item.put("identity", ctx.identity());
            item.put("snapshot", ctx.snapshot().value());
            item.put("analysis", ctx.analysis().map(com.evolution.analysis.contract.identity.AnalysisIdentity::value)
                    .orElse(null));
            item.put("evidence", ctx.evidence().value());

            var ev = graph.evidence(ctx.evidence()).orElse(null);
            String family = ev != null ? ev.family() : null;
            if (family != null)
                item.put("family", family);

            if (ctx.identity().equals(snapshotId)) {
                item.put("kind", "repository");
                item.put("label", "Whole repository");
            } else if ("analysis.manifest".equals(family)) {
                var meta = analysisMeta.get(ctx.identity());
                String modulePath = meta != null ? meta.modulePath() : null;
                String sourceSet = meta != null ? meta.sourceSet() : null;
                item.put("kind", "java");
                item.put("language", "java");
                if (modulePath != null)
                    item.put("modulePath", modulePath);
                if (sourceSet != null)
                    item.put("sourceSet", sourceSet);
                if (modulePath != null) {
                    String modLabel = ".".equals(modulePath) ? "Root module" : modulePath;
                    item.put("label",
                            sourceSet != null ? modLabel + " / " + formatSourceSetLabel(sourceSet) : modLabel);
                } else if (sourceSet != null) {
                    item.put("label", "Java / " + formatSourceSetLabel(sourceSet));
                } else {
                    item.put("label", "Java analysis");
                }
            } else if ("spring.context".equals(family)) {
                var payload = parseContextPayload(ev);
                String linkedAnalysis = payload.get("analysis") instanceof String s ? s
                        : ctx.analysis().map(com.evolution.analysis.contract.identity.AnalysisIdentity::value)
                                .orElse(null);
                var linked = linkedAnalysis != null ? analysisMeta.get(linkedAnalysis) : null;
                String modulePath = null;
                String sourceSet = null;
                if (payload.get("build") instanceof Map<?, ?> build
                        && build.get("moduleSourceSet") instanceof Map<?, ?> mss) {
                    if (mss.get("module") instanceof String modId) {
                        modulePath = modulePaths.get(modId);
                        if (modulePath == null && !modId.contains("sha256:")) {
                            modulePath = safeModulePath(modId);
                        }
                    }
                    sourceSet = safeSourceSet(mss.get("sourceSet"));
                }
                if (modulePath == null && linked != null)
                    modulePath = linked.modulePath();
                if (sourceSet == null && linked != null)
                    sourceSet = linked.sourceSet();
                item.put("kind", "spring");
                item.put("language", "java");
                if (modulePath != null)
                    item.put("modulePath", modulePath);
                if (sourceSet != null)
                    item.put("sourceSet", sourceSet);
                if (modulePath != null) {
                    String modLabel = ".".equals(modulePath) ? "Root module" : modulePath;
                    item.put("label", sourceSet != null && !"MAIN".equals(sourceSet)
                            ? modLabel + " / Spring / " + formatSourceSetLabel(sourceSet)
                            : modLabel + " / Spring");
                } else {
                    item.put("label", "Spring configuration");
                }
            } else if ("polyglot.context".equals(family)) {
                var payload = parseContextPayload(ev);
                var req = payload.get("request") instanceof Map<?, ?> r ? r : Map.of();
                String language = safeLanguageToken(req.get("language"));
                String modulePath = safeModulePath(req.get("module"));
                if (modulePath == null)
                    modulePath = safeModulePath(req.get("root"));
                String sourceSet = safeShortToken(req.get("sourceSet"));
                item.put("kind", "polyglot");
                if (language != null)
                    item.put("language", language);
                if (modulePath != null)
                    item.put("modulePath", modulePath);
                if (sourceSet != null)
                    item.put("sourceSet", sourceSet);
                String langDisplay = displayLanguageName(language);
                if (modulePath != null && !".".equals(modulePath) && langDisplay != null) {
                    item.put("label", modulePath + " · " + langDisplay);
                } else if (langDisplay != null) {
                    item.put("label", langDisplay);
                } else if (modulePath != null) {
                    item.put("label", ".".equals(modulePath) ? "Root module" : modulePath);
                }
            } else {
                String kind = ctx.identity().startsWith("spring-context:") ? "spring"
                        : ctx.identity().startsWith("analysis:") ? "java"
                                : family != null && family.startsWith("polyglot.") ? "polyglot" : "context";
                item.put("kind", kind);
            }
            item.put("nodeCount", graph.nodes(ctx.identity()).size());
            described.add(Collections.unmodifiableMap(item));
        }
        return Collections.unmodifiableList(described);
    }

    private static Map<String, Object> parseContextPayload(GraphEvidence evidence) {
        if (evidence == null)
            return Map.of();
        try {
            return GraphJson.object(GraphJson.read(evidence.canonicalJson(), CONTEXT_METADATA_LIMITS));
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    private static String safeModulePath(Object raw) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 256)
            return null;
        try {
            return com.evolution.analysis.contract.common.ContractChecks.modulePath(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String safeSourceSet(Object raw) {
        if (!(raw instanceof String value))
            return null;
        String upper = value.toUpperCase(Locale.ROOT);
        return KNOWN_SOURCE_SETS.contains(upper) ? upper : null;
    }

    private static String safeLanguageToken(Object raw) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 32)
            return null;
        String lower = value.strip().toLowerCase(Locale.ROOT);
        return lower.matches("[a-z0-9][a-z0-9.+#_-]*") ? lower : null;
    }

    private static String safeShortToken(Object raw) {
        if (!(raw instanceof String value) || value.isBlank() || value.length() > 64)
            return null;
        String stripped = value.strip();
        return stripped.matches("[A-Za-z0-9][A-Za-z0-9._/-]*") ? stripped : null;
    }

    private static String formatSourceSetLabel(String sourceSet) {
        return switch (sourceSet) {
            case "MAIN" -> "main";
            case "TEST" -> "test";
            case "GENERATED_MAIN" -> "generated main";
            case "GENERATED_TEST" -> "generated test";
            default -> sourceSet.toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    private static String displayLanguageName(String language) {
        if (language == null)
            return null;
        return switch (language) {
            case "java" -> "Java";
            case "typescript", "ts" -> "TypeScript";
            case "javascript", "js" -> "JavaScript";
            case "python", "py" -> "Python";
            case "go", "golang" -> "Go";
            case "rust", "rs" -> "Rust";
            case "kotlin", "kt" -> "Kotlin";
            case "csharp", "c#" -> "C#";
            case "cpp", "c++" -> "C++";
            case "c" -> "C";
            case "ruby", "rb" -> "Ruby";
            case "php" -> "PHP";
            case "swift" -> "Swift";
            case "scala" -> "Scala";
            case "sql" -> "SQL";
            case "docker", "dockerfile" -> "Docker";
            case "protobuf", "proto" -> "Protobuf";
            case "yaml", "yml" -> "YAML";
            case "json" -> "JSON";
            case "shell", "bash", "sh" -> "Shell";
            case "html" -> "HTML";
            case "css" -> "CSS";
            default -> Character.toUpperCase(language.charAt(0)) + language.substring(1);
        };
    }

    private Object query(String op, ArchitectureQueryService q, Request r, Page p, Map<String, Object> a,
            boolean hasPage) {
        boolean paged = Set.of("entities", "edges", "dependencies", "relationships", "explain", "gaps", "spring",
                "configurations", "artifacts", "metric-catalog", "children", "realizations").contains(op);
        if (hasPage && !paged)
            throw new IllegalArgumentException("Page not applicable");
        switch (op) {
            case "entities" -> QueryJson.object(a, "search");
            case "gaps" -> QueryJson.object(a, "view");
            case "entity", "dependencies", "expand", "reachability", "impact", "explain", "children" ->
                QueryJson.object(a, "identity");
            case "paths", "shortest-path" -> QueryJson.object(a, "source", "target");
            case "evidence" -> QueryJson.object(a, "reference");
            case "spring" -> QueryJson.object(a, "kinds");
            case "artifacts" -> QueryJson.object(a, "families");
            case "metric" -> QueryJson.object(a, "metric", "subject");
            case "metric-definition" -> QueryJson.object(a, "metric");
            case "comparison-preparation" -> QueryJson.object(a, "graph", "context");
            case "visual-graph" -> QueryJson.object(a, "seed", "depth", "groupBy",
                    "level", "expandCommunities", "focusNodes", "cycleMode", "maxNodes", "maxEdges");
            default -> QueryJson.object(a);
        }
        return switch (op) {
            case "summary" -> q.summary(r);
            case "provenance" -> q.provenance(r);
            case "entities" -> q.entities(r, a.containsKey("search") ? GraphJson.string(a.get("search")) : "", p);
            case "entity" -> q.entity(r, QueryJson.text(a, "identity"));
            case "children" -> q.children(r, QueryJson.text(a, "identity"), p);
            case "realizations" -> q.realizations(r, p);
            case "edges" -> q.edges(r, p);
            case "dependencies" -> q.dependencies(r, QueryJson.text(a, "identity"), p);
            case "relationships" -> q.relationships(r, p);
            case "expand" -> q.expand(r, QueryJson.text(a, "identity"));
            case "paths" -> q.findPaths(r, QueryJson.text(a, "source"), QueryJson.text(a, "target"));
            case "shortest-path" -> q.shortestPath(r, QueryJson.text(a, "source"), QueryJson.text(a, "target"));
            case "cycles" -> q.findCycles(r);
            case "reachability" -> q.reachability(r, QueryJson.text(a, "identity"));
            case "impact" -> q.impact(r, QueryJson.text(a, "identity"));
            case "evidence" -> q.evidence(r, new ContentDigest(QueryJson.text(a, "reference")));
            case "explain" -> q.explain(r, QueryJson.text(a, "identity"), p);
            case "gaps" ->
                q.gaps(r, a.containsKey("view") ? GapView.valueOf(QueryJson.text(a, "view")) : GapView.CURRENT, p);
            case "spring" -> q.spring(r, QueryJson.stringsRequired(a, "kinds"), p);
            case "configurations" -> q.configurations(r, p);
            case "artifacts" -> q.artifacts(r, QueryJson.stringsRequired(a, "families"), p);
            case "metric-catalog" -> q.metricCatalog(r, p);
            case "metric-definition" -> q.metricDefinition(r, QueryJson.text(a, "metric"));
            case "metric" -> q.metric(r, QueryJson.text(a, "metric"), QueryJson.text(a, "subject"));
            case "comparison-preparation" -> q.comparisonPreparation(r,
                    load(new ContentDigest(QueryJson.text(a, "graph"))), QueryJson.text(a, "context"));
            case "visual-graph" -> {
                var projection = new GraphProjectionService(q);
                boolean hasMultiScale = a.containsKey("level") || a.containsKey("expandCommunities")
                        || a.containsKey("focusNodes") || a.containsKey("cycleMode")
                        || a.containsKey("maxNodes") || a.containsKey("maxEdges");
                if (hasMultiScale) {
                    var msReq = buildMultiScaleRequest(r.context(), a, Optional.empty());
                    yield projection.projectMultiScale(msReq);
                }
                int depth = visualDepth(a);
                var groupBy = GraphProjectionService.GroupBy.parse(QueryJson.optionalText(a, "groupBy").orElse("PACKAGE"));
                if (a.containsKey("seed")) {
                    yield projection.focusedSubgraph(r, QueryJson.text(a, "seed"), depth, Optional.empty(), groupBy);
                }
                yield projection.project(r, Optional.empty(), groupBy);
            }
            default -> throw new Failure(404, "NOT_FOUND");
        };
    }

    private Object report(String op, ContentDigest id, PublishedReports.Report report, Map<String, Object> args,
            Page page, boolean hasPage, ArchitectureQueryService service) {
        boolean paged = op.equals("rules") || op.equals("findings");
        if (hasPage && !paged)
            throw new IllegalArgumentException("Page not applicable");
        if (op.equals("repair-verification")) {
            QueryJson.object(args, "identity", "bundle", "context", "diagnosisInput", "proposal", "candidateReport",
                    "controlReport", "runIsolated");
            if (args.containsKey("runIsolated") && !(args.get("runIsolated") instanceof Boolean))
                throw new IllegalArgumentException("Invalid runIsolated flag");
        } else if (op.equals("repair-reanalysis")) {
            QueryJson.object(args, "identity", "bundle", "context", "diagnosisInput", "proposal", "includeControl");
        } else if (op.equals("repair-proposal")) {
            QueryJson.object(args, "identity", "bundle", "context", "diagnosisInput", "strategy", "recipe",
                    "modelInput", "maxRepairAttempts");
        } else if (op.equals("repair-options")) {
            QueryJson.object(args, "identity", "bundle", "context", "diagnosisInput");
        } else if (op.equals("diagnosis")) {
            QueryJson.object(args, "identity", "bundle", "context");
        } else if (op.equals("finding") || op.equals("bundle") || op.equals("policy-impact")) {
            QueryJson.object(args, "identity");
        } else if (op.equals("replay")) {
            QueryJson.object(args, "identity", "counterWitness");
            if (!(args.get("counterWitness") instanceof Boolean))
                throw new IllegalArgumentException("Explicit witness kind required");
        } else if (op.equals("visual-graph")) {
            QueryJson.object(args, "seed", "depth", "violationsOnly", "groupBy");
            if (args.containsKey("violationsOnly") && !(args.get("violationsOnly") instanceof Boolean))
                throw new IllegalArgumentException("Boolean required");
            if (Boolean.TRUE.equals(args.get("violationsOnly")) && args.containsKey("seed"))
                throw new IllegalArgumentException("Choose one graph selection");
        } else {
            QueryJson.object(args);
        }
        var e = report.evaluation();
        return switch (op) {
            case "rules" -> reportPage(id, op, page, e.rules().stream().sorted(Comparator.comparing(r -> r.identity()))
                    .map(r -> (Object) Map.of("identity", r.identity(), "result", r)).toList(), e);
            case "findings" -> reportPage(id, op, page,
                    e.findings().stream().sorted(Comparator.comparing(PolicyEvaluation.Finding::identity))
                            .map(f -> (Object) Map.of("identity", f.identity(), "key", f.key(), "bundleIdentity",
                                    f.bundle().identity(), "result", f))
                            .toList(),
                    e);
            case "finding", "bundle" -> {
                var target = new ContentDigest(QueryJson.text(args, "identity"));
                var finding = e.findings().stream()
                        .filter(f -> (op.equals("finding") ? f.identity() : f.bundle().identity()).equals(target))
                        .findFirst()
                        .orElseThrow(() -> new Failure(404, "REPORT_ARTIFACT_UNAVAILABLE"));
                yield op.equals("finding") ? finding : finding.bundle();
            }
            case "policy-metrics" ->
                report.metrics().orElseThrow(() -> new Failure(404, "REPORT_ARTIFACT_UNAVAILABLE"));
            case "diagnosis", "repair-options", "repair-proposal", "repair-reanalysis", "repair-verification" -> {
                var target = new ContentDigest(QueryJson.text(args, "identity"));
                var bundle = new ContentDigest(QueryJson.text(args, "bundle"));
                var context = QueryJson.text(args, "context");
                var input = op.equals("diagnosis") ? Optional.<ContentDigest>empty()
                        : Optional.of(new ContentDigest(QueryJson.text(args, "diagnosisInput")));
                var finding = e.findings().stream().filter(f -> f.identity().equals(target)).findFirst()
                        .orElseThrow(() -> new Failure(404, "REPORT_ARTIFACT_UNAVAILABLE"));
                if (!finding.bundle().identity().equals(bundle) || !e.context().equals(context))
                    throw new Failure(409, "DIAGNOSIS_SCOPE_MISMATCH");
                if (input.isEmpty())
                    yield diagnosis.diagnose(id, finding, service);
                // Recipe and verification recompute canonical evidence without depending on
                // live model availability or selection.
                var replay = REPLAY_DIAGNOSIS.diagnose(id, finding, service);
                if (replay.input().isPresent() && !replay.input().equals(input))
                    throw new Failure(409, "DIAGNOSIS_INPUT_MISMATCH");
                var captured = new CapturedRepair(repairModel);
                if (op.equals("repair-options"))
                    yield captured.options(replay, service.graph(), service::assessmentStopReason,
                            activeRepairRunner() != null, finding.bundle().path());
                if (op.equals("repair-proposal") && args.containsKey("strategy")) {
                    var strategy = CapturedRepair.Strategy.valueOf(QueryJson.text(args, "strategy"));
                    Optional<JavaRepairRecipes.Recipe> recipe = Optional.empty();
                    Optional<ContentDigest> modelInput = Optional.empty();
                    if (strategy == CapturedRepair.Strategy.RECIPE) {
                        if (args.containsKey("modelInput"))
                            throw new IllegalArgumentException("Model input is only valid for AI");
                        recipe = Optional.of(JavaRepairRecipes.find(QueryJson.text(args, "recipe"))
                                .orElseThrow(() -> new IllegalArgumentException("Unknown repair recipe")));
                    } else {
                        if (args.containsKey("recipe"))
                            throw new IllegalArgumentException("Recipe is only valid for RECIPE");
                        modelInput = Optional.of(new ContentDigest(QueryJson.text(args, "modelInput")));
                    }
                    int maxRepairAttempts = 1;
                    if (args.containsKey("maxRepairAttempts")) {
                        if (!(args.get("maxRepairAttempts") instanceof java.math.BigDecimal bd))
                            throw new IllegalArgumentException("Invalid maxRepairAttempts");
                        try {
                            maxRepairAttempts = bd.intValueExact();
                        } catch (ArithmeticException invalid) {
                            throw new IllegalArgumentException("Invalid maxRepairAttempts");
                        }
                        if (maxRepairAttempts < 1 || maxRepairAttempts > 4)
                            throw new IllegalArgumentException("maxRepairAttempts must be between 1 and 4");
                    }
                    var outcome = captured.propose(replay, service.graph(), strategy, recipe, modelInput,
                            maxRepairAttempts, finding.bundle().path(), service::assessmentStopReason);
                    if ("PROPOSED".equals(outcome.status()) && outcome.proposal().isPresent()) {
                        repairStore.put(outcome.proposal().orElseThrow());
                    }
                    yield outcome;
                }
                if (args.containsKey("recipe") || args.containsKey("modelInput"))
                    throw new IllegalArgumentException("Repair strategy required");
                var proposed = REPAIR.prepare(replay, finding, service::assessmentStopReason);
                if (op.equals("repair-proposal"))
                    yield proposed;
                var proposal = new ContentDigest(QueryJson.text(args, "proposal"));
                if (op.equals("repair-reanalysis")) {
                    if (repairReanalysis == null)
                        throw new Failure(501, "REPAIR_REANALYSIS_UNAVAILABLE");
                    Object rawControl = args.get("includeControl");
                    if (rawControl != null && !(rawControl instanceof Boolean))
                        throw new IllegalArgumentException("Invalid includeControl flag");
                    boolean includeControl = Boolean.TRUE.equals(rawControl);
                    var stored = repairStore.get(proposal)
                            .filter(p -> p.candidate().baseline().finding().equals(target)
                                    && p.candidate().baseline().bundle().equals(bundle)
                                    && p.candidate().baseline().context().equals(context)
                                    && p.candidate().diagnosisInput().equals(input.orElseThrow()))
                            .orElseThrow(() -> new Failure(409, "REPAIR_PROPOSAL_MISMATCH"));
                    var reanalyzed = repairReanalysis.reanalyze(stored, service.graph(), report, includeControl,
                            () -> service.assessmentStopReason() != StopReason.NONE);
                    reanalyzed.candidateGraph().ifPresent(g -> reanalyzedGraphs.put(reanalyzed.candidateSnapshot(), g));
                    if (reanalyzed.controlSnapshot().isPresent() && reanalyzed.controlGraph().isPresent()) {
                        reanalyzedGraphs.put(reanalyzed.controlSnapshot().orElseThrow(),
                                reanalyzed.controlGraph().orElseThrow());
                    }
                    yield reanalyzed;
                }
                Optional<ContentDigest> candidate = Optional.empty(), control = Optional.empty();
                if (args.containsKey("candidateReport"))
                    candidate = Optional.of(new ContentDigest(QueryJson.text(args, "candidateReport")));
                if (args.containsKey("controlReport"))
                    control = Optional.of(new ContentDigest(QueryJson.text(args, "controlReport")));
                var demoPinned = proposed.proposal().filter(p -> p.identity().equals(proposal));
                if (demoPinned.isPresent()) {
                    yield new RepairVerification(repairWorker, demoRepairCapture).verify(demoPinned.orElseThrow(),
                            service.graph(), report, candidate, control, store, reports,
                            () -> service.assessmentStopReason() != StopReason.NONE);
                }
                var capturedPinned = repairStore.get(proposal)
                        .filter(p -> p.candidate().baseline().finding().equals(target)
                                && p.candidate().baseline().bundle().equals(bundle)
                                && p.candidate().baseline().context().equals(context)
                                && p.candidate().diagnosisInput().equals(input.orElseThrow()))
                        .orElseThrow(() -> new Failure(409, "REPAIR_PROPOSAL_MISMATCH"));
                java.util.Map<com.evolution.analysis.contract.identity.SnapshotIdentity, ContentDigest> snapshotGraphs;
                synchronized (reanalyzedGraphs) {
                    snapshotGraphs = java.util.Map.copyOf(reanalyzedGraphs);
                }
                boolean runIsolated = false;
                if (args.containsKey("runIsolated")) {
                    if (!(args.get("runIsolated") instanceof Boolean flag)) {
                        throw new Failure(400, "INVALID_REQUEST");
                    }
                    runIsolated = flag;
                }
                var activeRunnerSync = activeRepairRunner();
                if (runIsolated && activeRunnerSync == null) {
                    throw new Failure(422, CapturedRepairRunner.NOT_CONFIGURED);
                }
                var verifier = new CapturedRepairVerification(repairWorker, capturedRepairExecution, snapshotGraphs);
                var result = verifier.verify(
                        capturedPinned, service.graph(), report, candidate, control, store, reports,
                        () -> service.assessmentStopReason() != StopReason.NONE);
                boolean isolatedAlreadyPassed = result.checks().stream()
                        .anyMatch(c -> "isolated-build-and-test".equals(c.id())
                                && c.status() == CapturedRepairVerification.Status.PASSED);
                if (runIsolated && !isolatedAlreadyPassed) {
                    var repoRoot = connections == null
                            ? Optional.<java.nio.file.Path>empty()
                            : CapturedRepairVerification.repository(service.graph())
                                    .flatMap(connections::localCheckout);
                    if (repoRoot.isEmpty()) {
                        throw new Failure(422, "ISOLATED_RUNNER_REPO_UNAVAILABLE");
                    }
                    try {
                        var syntheticOutcome = new CapturedRepair.Outcome(
                                CapturedRepair.VERSION, "PROPOSED", Optional.empty(), List.of(), List.of(),
                                List.of(), Optional.of(capturedPinned), 1);
                        var inv = activeRunnerSync.run(repoRoot.orElseThrow(), syntheticOutcome, result,
                                () -> Thread.currentThread().isInterrupted());
                        result = new CapturedRepairVerification(repairWorker, inv.execution(), snapshotGraphs)
                                .verify(capturedPinned, service.graph(), report, candidate, control, store, reports,
                                        () -> Thread.currentThread().isInterrupted());
                    } catch (java.io.IOException isolatedFailure) {
                        throw new Failure(500, "ISOLATED_RUNNER_EXECUTION_FAILED");
                    }
                }
                yield result;
            }
            case "replay" -> {
                var target = new ContentDigest(QueryJson.text(args, "identity"));
                boolean counter = Boolean.TRUE.equals(args.get("counterWitness"));
                var bundle = e.findings().stream().filter(f -> f.identity().equals(target)).findFirst()
                        .orElseThrow(() -> new Failure(404, "REPORT_ARTIFACT_UNAVAILABLE")).bundle();
                var witness = (counter ? bundle.region().counterWitness() : bundle.region().witness())
                        .orElseThrow(() -> new Failure(404, "REPORT_ARTIFACT_UNAVAILABLE"));
                yield service.replayRuleWitness(bundle.request(), bundle.predicate(), witness, counter);
            }
            case "assessment" -> report.assessment().orElseThrow(() -> new Failure(404, "REPORT_ARTIFACT_UNAVAILABLE"));
            case "policy-impact" -> {
                var target = QueryJson.text(args, "identity");
                var req = new Request(e.graph(), e.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                        Direction.INCOMING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(),
                        Limits.defaults());
                yield new PolicyImpactService(service).evaluate(req, e, target);
            }
            case "visual-graph" -> {
                var projection = new GraphProjectionService(service);
                int depth = visualDepth(args);
                var groupBy = GraphProjectionService.GroupBy.parse(QueryJson.optionalText(args, "groupBy").orElse("PACKAGE"));
                var req = new Request(e.graph(), e.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                        Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(),
                        Limits.defaults());
                if (Boolean.TRUE.equals(args.get("violationsOnly"))) {
                    yield projection.violationsSubgraph(req, e);
                }
                if (args.containsKey("seed")) {
                    yield projection.focusedSubgraph(req, QueryJson.text(args, "seed"), depth, Optional.of(e), groupBy);
                }
                yield projection.project(req, Optional.of(e), groupBy);
            }
            default -> throw new Failure(404, "NOT_FOUND");
        };
    }

    private static String resolveVisualOrDsmContext(KnowledgeGraph graph, Optional<String> requestedContext) {
        if (requestedContext.isPresent()) {
            String ctx = requestedContext.orElseThrow();
            boolean exists = graph.contexts().stream().anyMatch(c -> c.identity().equals(ctx));
            if (!exists) {
                throw new Failure(404, "CONTEXT_NOT_FOUND");
            }
            return ctx;
        }
        return defaultAnalysisContext(graph);
    }

    private static GraphProjectionService.MultiScaleRequest buildMultiScaleRequest(
            String contextId,
            Map<String, Object> input,
            Optional<PolicyEvaluation.Result> policyOpt) {
        var level = GraphProjectionService.ProjectionLevel.parse(
                QueryJson.optionalText(input, "level").orElse("AUTO"));
        var cycleMode = GraphProjectionService.CycleMode.parse(
                QueryJson.optionalText(input, "cycleMode").orElse("SCC"));
        List<String> expandCommunities = optionalStringList(input, "expandCommunities", 200, 512);
        List<String> focusNodes = optionalStringList(input, "focusNodes", 200, 512);
        int maxNodes = optionalInt(input, "maxNodes",
                GraphProjectionService.DEFAULT_MAX_NODES, 1, GraphProjectionService.MAX_ALLOWED_NODES);
        int maxEdges = optionalInt(input, "maxEdges",
                GraphProjectionService.DEFAULT_MAX_EDGES, 1, GraphProjectionService.MAX_ALLOWED_EDGES);
        Set<String> violatedEdges = policyOpt.map(ArchitectureQueryApi::extractViolatedEdgeKeys).orElse(Set.of());
        Map<String, Integer> nodeViolations = policyOpt.map(ArchitectureQueryApi::extractNodeViolationCounts).orElse(Map.of());
        return new GraphProjectionService.MultiScaleRequest(
                contextId, level, expandCommunities, focusNodes, cycleMode,
                maxNodes, maxEdges, violatedEdges, nodeViolations);
    }

    private static List<String> optionalStringList(Map<String, Object> input, String key, int maxItems, int maxLen) {
        if (!input.containsKey(key) || input.get(key) == null) {
            return List.of();
        }
        var arr = GraphJson.array(input.get(key));
        if (arr.size() > maxItems) {
            throw new IllegalArgumentException(key + " exceeds " + maxItems + " items");
        }
        var result = new ArrayList<String>(arr.size());
        for (Object item : arr) {
            String s = GraphJson.string(item).strip();
            if (s.isEmpty() || s.length() > maxLen) {
                throw new IllegalArgumentException("Invalid item in " + key);
            }
            result.add(s);
        }
        return List.copyOf(result);
    }

    private static Set<String> extractViolatedEdgeKeys(PolicyEvaluation.Result evaluation) {
        var keys = new TreeSet<String>();
        for (var finding : evaluation.findings()) {
            for (var rel : finding.bundle().relationships()) {
                keys.add(rel.identity());
                keys.add(TarjanSccAnalyzer.edgeKey(rel.source(), rel.target()));
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    private static Map<String, Integer> extractNodeViolationCounts(PolicyEvaluation.Result evaluation) {
        var counts = new TreeMap<String, Integer>();
        for (var finding : evaluation.findings()) {
            for (var v : finding.bundle().path()) {
                counts.merge(v, 1, Integer::sum);
            }
            for (var rel : finding.bundle().relationships()) {
                counts.merge(rel.source(), 1, Integer::sum);
                counts.merge(rel.target(), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    private static int visualDepth(Map<String, Object> args) {
        if (!args.containsKey("depth"))
            return 1;
        if (!args.containsKey("seed") || !(args.get("depth") instanceof java.math.BigDecimal value))
            throw new IllegalArgumentException("Depth requires a seed and exact integer");
        int depth;
        try {
            depth = value.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid graph depth");
        }
        if (depth < 1 || depth > 19)
            throw new IllegalArgumentException("Invalid dependency-step depth");
        return depth;
    }

    private Object reportPage(ContentDigest report, String op, Page page, List<Object> rows,
            PolicyEvaluation.Result evaluation) {
        var query = ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION, report, op, page.size())));
        int start = 0;
        if (page.cursor().isPresent()) {
            String decoded = new String(Base64.getUrlDecoder().decode(page.cursor().orElseThrow()),
                    StandardCharsets.UTF_8);
            String prefix = query.value() + "|";
            if (!decoded.startsWith(prefix))
                throw new IllegalArgumentException("Foreign report cursor");
            start = Integer.parseInt(decoded.substring(prefix.length()));
            if (start < 1 || start >= rows.size() || start % page.size() != 0)
                throw new IllegalArgumentException("Invalid report cursor");
        }
        int end = Math.min(rows.size(), start + page.size());
        Optional<String> cursor = end < rows.size()
                ? Optional.of(Base64.getUrlEncoder().withoutPadding()
                        .encodeToString((query.value() + "|" + end).getBytes(StandardCharsets.UTF_8)))
                : Optional.empty();
        return Map.of("query", query, "items", rows.subList(start, end), "continuation", cursor, "returnedTotal",
                rows.size(),
                "evaluationStatus", evaluation.status(), "gaps", evaluation.gaps(), "truncated", cursor.isPresent(),
                "denominatorMeaning", "Published observed rows; evaluation gaps may withhold completeness.");
    }

    private Map<String, Object> decode(byte[] bytes, String contentType) {
        if (!jsonMediaType(contentType))
            throw new Failure(415, "UNSUPPORTED_MEDIA_TYPE");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return GraphJson.object(GraphJson.read(text,
                    new GraphJson.Limits(limits.requestBytes(), limits.jsonDepth(), limits.jsonValues())));
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Invalid UTF-8");
        }
    }

    static boolean jsonMediaType(String value) {
        if (value == null)
            return false;
        String normalized = value.toLowerCase(Locale.ROOT).replace(" ", "");
        return normalized.equals("application/json") || normalized.equals("application/json;charset=utf-8");
    }

    private static void requireMethod(String actual, String required) {
        if (!actual.equals(required))
            throw new Failure(405, "METHOD_NOT_ALLOWED");
    }

    private static void requireEmpty(byte[] bytes) {
        if (bytes.length != 0)
            throw new IllegalArgumentException("Body not applicable");
    }

    private Response okEnvelope(int status, Object data) {
        byte[] canonicalData = BoundedJson.encode(data, limits.responseBytes());
        return new Response(status, BoundedJson.encode(Map.of(
                "apiVersion", VERSION,
                "queryVersion", ArchitectureQuery.VERSION,
                "resultIdentity", ContentDigest.sha256(canonicalData),
                "data", data), limits.responseBytes()));
    }

    private Response handleAiProviders(String method, String rawPath, String contentType, byte[] body,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new Failure(409, "REQUEST_CANCELLED");
        var registry = llmRegistry != null ? llmRegistry : LlmConfigRegistry.fromEnvironment();
        if (rawPath.equals("/api/v1/ai/providers")) {
            requireMethod(method, "GET");
            requireEmpty(body);
            return okEnvelope(200, registry.describeProviders());
        }
        requireMethod(method, "POST");
        Optional<String> role = Optional.empty();
        if (body.length > 0) {
            var input = QueryJson.object(decode(body, contentType), "role");
            role = QueryJson.optionalText(input, "role");
        }
        return okEnvelope(200, registry.probeProviders(role, cancelled));
    }

    private Object handleAdHocRepairProposal(ContentDigest graphId, Map<String, Object> input,
            BooleanSupplier cancelled) {
        QueryJson.object(input, "context", "title", "description", "prompt", "targetPaths", "files",
                "targetSymbols", "strategy", "recipeId", "recipe", "maxRepairAttempts");
        String contextId = QueryJson.text(input, "context");
        var graph = load(graphId);
        graph.contexts().stream().filter(c -> c.identity().equals(contextId)).findFirst()
                .orElseThrow(() -> new Failure(404, "CONTEXT_NOT_FOUND"));
        String promptText = QueryJson.optionalText(input, "prompt").orElse(null);
        String title = QueryJson.optionalText(input, "title").orElseGet(() -> {
            if (promptText == null || promptText.isBlank()) {
                throw new IllegalArgumentException("Either title or prompt is required");
            }
            String firstLine = promptText.split("\\R", 2)[0].strip();
            return firstLine.length() > 120 ? firstLine.substring(0, 120) : firstLine;
        });
        String description = QueryJson.optionalText(input, "description")
                .or(() -> Optional.ofNullable(promptText))
                .orElse(title);
        var targetPaths = new ArrayList<String>();
        if (input.containsKey("targetPaths") && input.get("targetPaths") != null) {
            for (var item : GraphJson.array(input.get("targetPaths"))) targetPaths.add(GraphJson.string(item));
        }
        if (input.containsKey("files") && input.get("files") != null) {
            for (var item : GraphJson.array(input.get("files"))) targetPaths.add(GraphJson.string(item));
        }
        var targetSymbols = new ArrayList<String>();
        if (input.containsKey("targetSymbols") && input.get("targetSymbols") != null) {
            for (var item : GraphJson.array(input.get("targetSymbols"))) targetSymbols.add(GraphJson.string(item));
        }
        Optional<String> recipeId = QueryJson.optionalText(input, "recipeId")
                .or(() -> QueryJson.optionalText(input, "recipe"));
        CapturedRepair.Strategy strategy = input.containsKey("strategy") && input.get("strategy") != null
                ? CapturedRepair.Strategy.valueOf(QueryJson.text(input, "strategy"))
                : (recipeId.isPresent() ? CapturedRepair.Strategy.RECIPE : CapturedRepair.Strategy.AI);
        int maxAttempts = 3;
        if (input.containsKey("maxRepairAttempts") && input.get("maxRepairAttempts") != null) {
            if (!(input.get("maxRepairAttempts") instanceof BigDecimal bd))
                throw new IllegalArgumentException("Invalid maxRepairAttempts");
            try {
                maxAttempts = bd.intValueExact();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Invalid maxRepairAttempts");
            }
            if (maxAttempts < 1 || maxAttempts > 4)
                throw new IllegalArgumentException("maxRepairAttempts must be between 1 and 4");
        }
        long deadline = System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000;
        var guard = ArchitectureQueryService.operationalGuard(cancelled, deadline, 8_000_000);
        var service = factory.apply(graph, guard);
        var req = new CapturedRepair.AdHocRequest(contextId, title, description,
                List.copyOf(targetPaths), List.copyOf(targetSymbols), strategy, recipeId, maxAttempts);
        var outcome = new CapturedRepair(repairModel).proposeAdHoc(req, graph, service, service::assessmentStopReason);
        if ("PROPOSED".equals(outcome.status()) && outcome.proposal().isPresent()) {
            repairStore.put(outcome.proposal().orElseThrow());
        }
        return outcome;
    }

    private Response handleRepairsRoute(String method, String rawPath, String contentType, byte[] body,
            BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean())
            throw new Failure(409, "REQUEST_CANCELLED");
        String[] parts = rawPath.split("/", -1);
        // GET|POST|DELETE /api/v1/repairs/sandbox/setup
        if (parts.length == 6 && "sandbox".equals(parts[4]) && "setup".equals(parts[5])) {
            requireSandboxAdminAccess();
            var setupSvc = ensureSandboxSetup();
            if ("GET".equals(method)) {
                requireEmpty(body);
                return okEnvelope(200, setupSvc.statusView());
            }
            if ("POST".equals(method)) {
                var input = body.length == 0 ? Map.<String, Object>of() : decode(body, contentType);
                QueryJson.object(input,
                        "enabled", "autoDetect", "isolationMode", "buildTool", "baseImage",
                        "pythonExecutable", "dockerContext", "baseCacheTag",
                        "m2Repository", "mavenZip", "gradleZip", "gradleHome",
                        "npmCache", "pipWheelhouse", "goModCache",
                        "workRoot", "skipTests", "timeoutSeconds");
                var updated = setupSvc.configure(input);
                this.capturedRepairRunner = setupSvc.activeRunner();
                return okEnvelope(200, updated);
            }
            if ("DELETE".equals(method)) {
                requireEmpty(body);
                var cleared = setupSvc.disable();
                this.capturedRepairRunner = null;
                return okEnvelope(200, cleared);
            }
            throw new Failure(405, "METHOD_NOT_ALLOWED");
        }
        // POST /api/v1/repairs/sandbox/probe
        if (parts.length == 6 && "sandbox".equals(parts[4]) && "probe".equals(parts[5])) {
            requireMethod(method, "POST");
            requireSandboxAdminAccess();
            var input = body.length == 0 ? Map.<String, Object>of() : decode(body, contentType);
            QueryJson.object(input, "pythonExecutable", "dockerContext");
            var setupSvc = ensureSandboxSetup();
            return okEnvelope(200, setupSvc.probe(
                    QueryJson.optionalText(input, "pythonExecutable"),
                    QueryJson.optionalText(input, "dockerContext")));
        }
        // GET /api/v1/repairs/jobs
        if (parts.length == 5 && "jobs".equals(parts[4])) {
            requireMethod(method, "GET");
            requireEmpty(body);
            if (repairVerificationJobs == null)
                throw new Failure(503, "REPAIR_VERIFICATION_JOBS_UNAVAILABLE");
            String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
            var items = repairVerificationJobs.list(ownerUserId).stream().map(this::serializeRepairJob).toList();
            return okEnvelope(200, Map.of("items", items));
        }
        // GET /api/v1/repairs/jobs/{jobId}
        if (parts.length == 6 && "jobs".equals(parts[4])) {
            requireMethod(method, "GET");
            requireEmpty(body);
            if (repairVerificationJobs == null)
                throw new Failure(503, "REPAIR_VERIFICATION_JOBS_UNAVAILABLE");
            UUID jobId = parseUuid(parts[5]);
            String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
            try {
                var view = repairVerificationJobs.get(ownerUserId, jobId)
                        .orElseThrow(() -> new Failure(404, "REPAIR_JOB_NOT_FOUND"));
                return okEnvelope(200, serializeRepairJob(view));
            } catch (SafePatchApplier.AccessDeniedException denied) {
                throw new Failure(403, "APPLY_ACCESS_DENIED");
            }
        }
        // POST /api/v1/repairs/jobs/{jobId}/cancel
        if (parts.length == 7 && "jobs".equals(parts[4]) && "cancel".equals(parts[6])) {
            requireMethod(method, "POST");
            if (repairVerificationJobs == null)
                throw new Failure(503, "REPAIR_VERIFICATION_JOBS_UNAVAILABLE");
            UUID jobId = parseUuid(parts[5]);
            String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
            try {
                if (!repairVerificationJobs.cancel(ownerUserId, jobId))
                    throw new Failure(404, "REPAIR_JOB_NOT_FOUND");
                var view = repairVerificationJobs.get(ownerUserId, jobId)
                        .orElseThrow(() -> new Failure(404, "REPAIR_JOB_NOT_FOUND"));
                return okEnvelope(200, serializeRepairJob(view));
            } catch (SafePatchApplier.AccessDeniedException denied) {
                throw new Failure(403, "APPLY_ACCESS_DENIED");
            }
        }
        // POST /api/v1/repairs/applies/{applyId}/rollback
        if (parts.length == 7 && "applies".equals(parts[4]) && "rollback".equals(parts[6])) {
            requireMethod(method, "POST");
            if (safePatchApplier == null)
                throw new Failure(503, "PATCH_APPLIER_UNAVAILABLE");
            String applyId = parts[5];
            if (body.length == 0)
                throw new IllegalArgumentException("EXPLICIT_USER_CONFIRMATION_REQUIRED");
            var input = decode(body, contentType);
            QueryJson.object(input, "confirmedByUser", "force");
            if (!Boolean.TRUE.equals(input.get("confirmedByUser")))
                throw new IllegalArgumentException("EXPLICIT_USER_CONFIRMATION_REQUIRED");
            boolean force = Boolean.TRUE.equals(input.get("force"));
            String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
            try {
                return okEnvelope(200, safePatchApplier.rollbackApply(ownerUserId, applyId, force));
            } catch (SafePatchApplier.AccessDeniedException denied) {
                throw new Failure(403, "APPLY_ACCESS_DENIED");
            } catch (java.io.IOException conflict) {
                throw new Failure(409, "PATCH_APPLY_CONFLICT");
            }
        }
        // POST /api/v1/repairs/{proposalId}/verify
        if (parts.length == 6 && "verify".equals(parts[5])) {
            requireMethod(method, "POST");
            var proposalId = new ContentDigest(parts[4]);
            var input = body.length == 0 ? Map.<String, Object>of() : decode(body, contentType);
            return okEnvelope(202, submitRepairVerificationJob(proposalId, input, cancelled));
        }
        // POST /api/v1/repairs/{proposalId}/apply/preview
        if (parts.length == 7 && "apply".equals(parts[5]) && "preview".equals(parts[6])) {
            requireMethod(method, "POST");
            var proposalId = new ContentDigest(parts[4]);
            var input = body.length == 0 ? Map.<String, Object>of() : decode(body, contentType);
            return okEnvelope(200, handleApplyPreview(proposalId, input));
        }
        // POST /api/v1/repairs/{proposalId}/apply/confirm
        if (parts.length == 7 && "apply".equals(parts[5]) && "confirm".equals(parts[6])) {
            requireMethod(method, "POST");
            var proposalId = new ContentDigest(parts[4]);
            var input = decode(body, contentType);
            return okEnvelope(200, handleApplyConfirm(proposalId, input));
        }
        throw new Failure(404, "NOT_FOUND");
    }

    private void requireSandboxAdminAccess() {
        boolean multi = (auth != null && auth.isMultiUserMode())
                || com.evolution.backend.auth.RequestContext.isMultiUserMode();
        if (!multi) {
            return;
        }
        var caller = com.evolution.backend.auth.RequestContext.currentUser();
        if (!caller.isAuthenticated()) {
            throw new Failure(401, "AUTH_REQUIRED");
        }
        if (!caller.isAdmin()) {
            throw new Failure(403, "ADMIN_REQUIRED");
        }
    }

    private Map<String, Object> submitRepairVerificationJob(ContentDigest proposalId, Map<String, Object> input,
            BooleanSupplier cancelled) {
        if (repairVerificationJobs == null)
            throw new Failure(503, "REPAIR_VERIFICATION_JOBS_UNAVAILABLE");
        QueryJson.object(input, "graph", "report", "context", "finding", "identity", "bundle", "diagnosisInput",
                "proposal", "runIsolated", "includeControl", "closedLoop", "maxRounds",
                "runDiffTestGen", "runBehavioralDiff", "proposeCharacterizationTests");
        var initialProposal = repairStore.get(proposalId)
                .orElseThrow(() -> new Failure(404, "PROPOSAL_NOT_FOUND"));
        var graphId = QueryJson.optionalText(input, "graph")
                .map(ContentDigest::new)
                .orElse(initialProposal.candidate().baseline().graph());
        var baselineGraph = load(graphId);
        Optional<ContentDigest> explicitReportDigest = QueryJson.optionalText(input, "report")
                .map(ContentDigest::new);
        Optional<PublishedReports.Report> baselineReportOpt;
        if (explicitReportDigest.isPresent()) {
            baselineReportOpt = Optional.of(reports.get(explicitReportDigest.orElseThrow())
                    .filter(r -> r.evaluation().graph().equals(graphId))
                    .orElseThrow(() -> new Failure(404, "REPORT_NOT_FOUND")));
        } else {
            baselineReportOpt = Optional.ofNullable(initialProposal.candidate().baseline().report())
                    .flatMap(reports::get)
                    .filter(r -> r.evaluation().graph().equals(graphId));
        }
        var activeRunner = activeRepairRunner();
        boolean runIsolated = input.containsKey("runIsolated")
                ? Boolean.TRUE.equals(input.get("runIsolated"))
                : activeRunner != null;
        boolean includeControl = Boolean.TRUE.equals(input.get("includeControl"));
        int maxRounds = 1;
        boolean explicitMaxRounds = input.containsKey("maxRounds") && input.get("maxRounds") != null;
        if (explicitMaxRounds) {
            if (!(input.get("maxRounds") instanceof BigDecimal bd)) {
                throw new IllegalArgumentException("Invalid maxRounds");
            }
            try {
                maxRounds = bd.intValueExact();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Invalid maxRounds");
            }
            if (maxRounds < 1 || maxRounds > 3) {
                throw new IllegalArgumentException("maxRounds must be between 1 and 3");
            }
        }
        boolean closedLoop = (input.containsKey("closedLoop") && input.get("closedLoop") != null)
                ? Boolean.TRUE.equals(input.get("closedLoop"))
                : (explicitMaxRounds && maxRounds > 1);
        if (closedLoop && !explicitMaxRounds) {
            maxRounds = 3;
        }
        final int effectiveMaxRounds = closedLoop ? Math.max(1, Math.min(3, maxRounds)) : 1;
        boolean runDiffTestGen = Boolean.TRUE.equals(input.get("runDiffTestGen"))
                || Boolean.TRUE.equals(input.get("runBehavioralDiff"));
        boolean proposeCharacterizationTests = Boolean.TRUE.equals(input.get("proposeCharacterizationTests"));

        String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        boolean hostLocalDisabled = isHostLocalCheckoutDisabledForCaller();
        var jobView = repairVerificationJobs.submit(
                ownerUserId,
                proposalId,
                baselineGraph.identity().value(),
                baselineReportOpt.map(r -> r.identity().value()),
                (progress, jobCancelled) -> {
            java.nio.file.Path materializedDir = null;
            try {
                var currentProposal = initialProposal;
                var seenPatchDigests = new HashSet<ContentDigest>();
                seenPatchDigests.add(currentProposal.candidate().patchDigest());
                var rounds = new ArrayList<RepairVerificationReport.RoundSummary>();
                var lastToolTrace = List.<RepairAgentTools.ToolTraceEntry>of();
                CapturedRepairVerification.Result finalVerification = null;
                Optional<ContentDigest> finalCandGraphId = Optional.empty();
                Optional<ContentDigest> finalCandReportId = Optional.empty();
                BehavioralDiffService.BehavioralDiffReport finalBehavioral = null;
                SpringPreservationChecker.SpringPreservationReport finalSpring = null;

                for (int round = 1; round <= effectiveMaxRounds; round++) {
                    if (jobCancelled.getAsBoolean()) break;
                    long roundStartNs = System.nanoTime();
                    var runnerForRound = activeRepairRunner();
                    progress.stage(RepairVerificationJobs.Stage.STAGING,
                            "Round " + round + "/" + effectiveMaxRounds + ": validating pinned proposal and baseline tree",
                            Map.of("round", round, "maxRounds", effectiveMaxRounds, "proposalId", currentProposal.identity().value()));

                    Optional<ContentDigest> candidateGraphId = Optional.empty();
                    Optional<ContentDigest> candidateReportId = Optional.empty();
                    Optional<ContentDigest> controlReportId = Optional.empty();

                    if (repairReanalysis != null) {
                        progress.stage(RepairVerificationJobs.Stage.PASSIVE_REANALYSIS,
                                "Round " + round + "/" + effectiveMaxRounds + ": reanalyzing candidate snapshot passively",
                                Map.of("round", round, "maxRounds", effectiveMaxRounds));
                        var reanalyzed = baselineReportOpt.isPresent()
                                ? repairReanalysis.reanalyze(currentProposal, baselineGraph,
                                        baselineReportOpt.orElseThrow(), includeControl && round == 1, jobCancelled)
                                : repairReanalysis.reanalyzeAdHoc(currentProposal, baselineGraph, jobCancelled);
                        candidateGraphId = reanalyzed.candidateGraph();
                        candidateReportId = reanalyzed.candidateReport();
                        controlReportId = reanalyzed.controlReport();
                        reanalyzed.candidateGraph().ifPresent(g -> reanalyzedGraphs.put(reanalyzed.candidateSnapshot(), g));
                        if (reanalyzed.controlSnapshot().isPresent() && reanalyzed.controlGraph().isPresent()) {
                            reanalyzedGraphs.put(reanalyzed.controlSnapshot().orElseThrow(),
                                    reanalyzed.controlGraph().orElseThrow());
                        }
                    }

                    Map<com.evolution.analysis.contract.identity.SnapshotIdentity, ContentDigest> snapshotGraphs;
                    synchronized (reanalyzedGraphs) {
                        snapshotGraphs = Map.copyOf(reanalyzedGraphs);
                    }
                    var verifier = new CapturedRepairVerification(repairWorker, capturedRepairExecution, snapshotGraphs);
                    CapturedRepairVerification.Result verification = baselineReportOpt.isPresent()
                            ? verifier.verify(currentProposal, baselineGraph, baselineReportOpt.orElseThrow(),
                                    candidateReportId, controlReportId, store, reports, jobCancelled)
                            : verifier.verifyAdHoc(currentProposal, baselineGraph, candidateGraphId, store, jobCancelled);

                    var candGraphOpt = candidateGraphId.flatMap(store::load);
                    var springPreservation = SpringPreservationChecker.compare(baselineGraph, candGraphOpt);

                    boolean skipTestsActive = runIsolated && runnerForRound != null && runnerForRound.config().skipTests();
                    boolean effectiveDiffTestGen = runDiffTestGen && !skipTestsActive;

                    var probes = List.<BehavioralDiffService.CharacterizationProbe>of();
                    if (effectiveDiffTestGen && proposeCharacterizationTests && repairModel != null && !jobCancelled.getAsBoolean()) {
                        progress.stage(RepairVerificationJobs.Stage.DIFF_TESTGEN,
                                "Round " + round + "/" + effectiveMaxRounds + ": synthesizing syntax-gated characterization probes",
                                Map.of("round", round, "maxRounds", effectiveMaxRounds));
                        var probeGuard = ArchitectureQueryService.operationalGuard(jobCancelled,
                                System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
                        probes = BehavioralDiffService.proposeCharacterizationProbes(
                                repairModel, currentProposal, baselineGraph, probeGuard);
                    }

                    boolean isolatedAlreadyPassed = verification.checks().stream()
                            .anyMatch(c -> "isolated-build-and-test".equals(c.id())
                                    && c.status() == CapturedRepairVerification.Status.PASSED);
                    if (runIsolated && runnerForRound != null && !isolatedAlreadyPassed) {
                        progress.stage(RepairVerificationJobs.Stage.SANDBOX_BUILD,
                                "Round " + round + "/" + effectiveMaxRounds + ": preparing isolated sandbox build ("
                                        + runnerForRound.config().isolationMode().name() + ")",
                                Map.of("round", round, "maxRounds", effectiveMaxRounds,
                                        "isolationMode", runnerForRound.config().isolationMode().name(),
                                        "buildTool", runnerForRound.config().buildTool()));
                        Optional<java.nio.file.Path> localRoot = (connections == null || hostLocalDisabled)
                                ? Optional.empty()
                                : CapturedRepairVerification.repository(baselineGraph)
                                        .flatMap(connections::localCheckout);
                        java.nio.file.Path runnerSourceRoot;
                        if (localRoot.isPresent()) {
                            runnerSourceRoot = localRoot.orElseThrow();
                        } else {
                            if (materializedDir == null) {
                                materializedDir = java.nio.file.Files.createTempDirectory("evo-repair-materialize-");
                                Optional<com.evolution.analysis.filesystem.StreamingContentStore> cas = Optional.empty();
                                if (acquisitionContentDir != null
                                        && java.nio.file.Files.isDirectory(acquisitionContentDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                    cas = Optional.of(new com.evolution.analysis.filesystem.StreamingContentStore(acquisitionContentDir));
                                }
                                var mat = SnapshotTreeMaterializer.materialize(baselineGraph, cas, Optional.empty(),
                                        materializedDir, jobCancelled);
                                if (!mat.complete()) {
                                    throw new java.io.IOException("SNAPSHOT_MATERIALIZATION_INCOMPLETE: missing " + mat.missingPaths().size() + " files");
                                }
                            }
                            runnerSourceRoot = materializedDir;
                        }
                        String sandboxPhaseLabel = skipTestsActive
                                ? " (tests skipped; compile-only)"
                                : (effectiveDiffTestGen ? " & baseline-vs-candidate behavioral diff" : " & tests");
                        progress.stage(RepairVerificationJobs.Stage.SANDBOX_TEST,
                                "Round " + round + "/" + effectiveMaxRounds + ": executing isolated build"
                                        + sandboxPhaseLabel
                                        + " inside disposable sandbox",
                                Map.of("round", round, "maxRounds", effectiveMaxRounds,
                                        "behavioralDiff", effectiveDiffTestGen,
                                        "skipTests", skipTestsActive));
                        var syntheticOutcome = new CapturedRepair.Outcome(
                                CapturedRepair.VERSION, "PROPOSED", Optional.empty(), List.of(), List.of(),
                                List.of(), Optional.of(currentProposal), 1);
                        var inv = runnerForRound.run(runnerSourceRoot, syntheticOutcome, verification,
                                effectiveDiffTestGen,
                                effectiveDiffTestGen ? BehavioralDiffService.encodeStagedProbesJson(probes) : Optional.empty(),
                                jobCancelled);
                        var postVerifier = new CapturedRepairVerification(repairWorker, inv.execution(), snapshotGraphs);
                        verification = baselineReportOpt.isPresent()
                                ? postVerifier.verify(currentProposal, baselineGraph, baselineReportOpt.orElseThrow(),
                                        candidateReportId, controlReportId, store, reports, jobCancelled)
                                : postVerifier.verifyAdHoc(currentProposal, baselineGraph, candidateGraphId, store, jobCancelled);
                    }

                    var behavioralDiff = BehavioralDiffService.evaluate(verification.isolatedProof(), probes);
                    long roundDurationMs = Math.max(1L, (System.nanoTime() - roundStartNs) / 1_000_000L);

                    int compExit = verification.isolatedProof().map(CapturedRepairVerification.IsolatedProof::compileExit).orElse(-1);
                    int tstExit = verification.isolatedProof().map(CapturedRepairVerification.IsolatedProof::testExit).orElse(-1);
                    int tstRun = verification.isolatedProof().map(CapturedRepairVerification.IsolatedProof::testsRun).orElse(0);
                    int tstFailed = verification.isolatedProof().map(p -> p.testsFailed() + p.testsErrors()).orElse(0);

                    var policyIssues = new ArrayList<String>();
                    for (var c : verification.checks()) {
                        if (c.status() == CapturedRepairVerification.Status.FAILED) {
                            policyIssues.add(c.id() + ": " + c.reason());
                        }
                    }
                    var springDrifts = springPreservation.drifts().stream()
                            .map(d -> d.symbolLabel() + " (" + d.beforeClassification() + " -> " + d.afterClassification() + ")")
                            .toList();
                    var behavRegressions = behavioralDiff.witnesses().stream()
                            .filter(w -> w.intentClassification() == BehavioralDiffService.IntentClassification.UNINTENDED_REGRESSION)
                            .map(w -> w.testPath() + " (baseExit=" + w.baseExit() + ", candExit=" + w.candExit() + ")")
                            .toList();

                    boolean sandboxFailed = verification.isolatedProof().isPresent()
                            && (compExit != 0 || tstExit != 0 || tstFailed > 0);
                    boolean needsRefinement = closedLoop
                            && round < effectiveMaxRounds
                            && repairModel != null
                            && !jobCancelled.getAsBoolean()
                            && (sandboxFailed
                                    || verification.verdict() == CapturedRepairVerification.Verdict.REJECTED
                                    || springPreservation.status() == SpringPreservationChecker.Status.DRIFT_DETECTED
                                    || !behavRegressions.isEmpty());

                    var feedback = needsRefinement
                            ? new CapturedRepair.ClosedLoopFeedback(
                                    round,
                                    compExit,
                                    tstExit,
                                    verification.isolatedProof().map(CapturedRepairVerification.IsolatedProof::failingTests).orElse(List.of()),
                                    verification.isolatedProof().map(CapturedRepairVerification.IsolatedProof::logTail).orElse(""),
                                    policyIssues,
                                    springDrifts,
                                    behavRegressions)
                            : null;

                    var roundSummary = new RepairVerificationReport.RoundSummary(
                            round,
                            currentProposal.identity(),
                            currentProposal.candidate().patchDigest(),
                            true,
                            compExit,
                            tstExit,
                            tstRun,
                            tstFailed,
                            verification.verdict().name(),
                            behavioralDiff.verdict().name(),
                            springPreservation.status().name(),
                            lastToolTrace.size(),
                            lastToolTrace,
                            feedback != null ? feedback.summaryLine() : "Round " + round + " completed (" + verification.verdict().name() + ")",
                            roundDurationMs);
                    rounds.add(roundSummary);
                    progress.recordRound(roundSummary);

                    finalVerification = verification;
                    finalCandGraphId = candidateGraphId;
                    finalCandReportId = candidateReportId;
                    finalBehavioral = behavioralDiff;
                    finalSpring = springPreservation;

                    if (!needsRefinement) {
                        break;
                    }

                    progress.stage(RepairVerificationJobs.Stage.CLOSED_LOOP_REPAIR,
                            "Round " + (round + 1) + "/" + effectiveMaxRounds + ": feeding redacted sandbox/policy diagnostics back to Repair LLM",
                            Map.of("round", round + 1, "maxRounds", effectiveMaxRounds,
                                    "failedRound", round, "feedbackSummary", feedback.summaryLine()));
                    var refineGuard = ArchitectureQueryService.operationalGuard(jobCancelled,
                            System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
                    var refineQueries = factory.apply(baselineGraph, refineGuard);
                    var refined = new CapturedRepair(repairModel).refineFromFeedback(
                            currentProposal, baselineGraph, Optional.of(refineQueries), feedback, 2,
                            refineQueries::assessmentStopReason);
                    if (!"PROPOSED".equals(refined.status()) || refined.proposal().isEmpty()) {
                        progress.stage(RepairVerificationJobs.Stage.CLOSED_LOOP_REPAIR,
                                "Closed-loop refinement halted before round " + (round + 1) + ": "
                                        + refined.reason().map(Enum::name).orElse("WITHHELD"),
                                Map.of("round", round + 1, "haltedReason", refined.reason().map(Enum::name).orElse("WITHHELD")));
                        break;
                    }
                    var nextProposal = refined.proposal().orElseThrow();
                    if (!seenPatchDigests.add(nextProposal.candidate().patchDigest())) {
                        progress.stage(RepairVerificationJobs.Stage.CLOSED_LOOP_REPAIR,
                                "Closed-loop refinement halted before round " + (round + 1)
                                        + ": model proposed an identical patch already verified in an earlier round.",
                                Map.of("round", round + 1, "haltedReason", "IDENTICAL_PATCH_PROPOSED",
                                        "patchDigest", nextProposal.candidate().patchDigest().value(),
                                        "proposalId", nextProposal.identity().value()));
                        break;
                    }
                    currentProposal = nextProposal;
                    repairStore.put(currentProposal);
                    lastToolTrace = refined.toolTrace();
                }

                if (jobCancelled.getAsBoolean() || finalVerification == null) {
                    return new RepairVerificationJobs.ExecutionOutcome(null, null);
                }

                progress.stage(RepairVerificationJobs.Stage.IMPACT_SCORING,
                        "Computing Score v2 delta, violation transitions, behavioral diff, and Spring preservation report");
                var candGraphOpt = finalCandGraphId.flatMap(store::load);
                var candReportOpt = finalCandReportId.flatMap(reports::get);
                var guard = ArchitectureQueryService.operationalGuard(jobCancelled,
                        System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
                var baseQueries = factory.apply(baselineGraph, guard);
                Optional<ArchitectureQueryService> candQueries = candGraphOpt.map(cg -> factory.apply(cg, guard));
                var richReport = RepairVerificationReport.build(
                        currentProposal, finalVerification, baselineGraph, candGraphOpt,
                        baselineReportOpt, candReportOpt, baseQueries, candQueries,
                        Optional.ofNullable(finalBehavioral), Optional.ofNullable(finalSpring), rounds);
                return new RepairVerificationJobs.ExecutionOutcome(finalVerification, richReport);
            } finally {
                if (materializedDir != null) {
                    deleteRecursivelyQuietly(materializedDir);
                }
            }
        });
        return serializeRepairJob(jobView);
    }

    private Map<String, Object> serializeRepairJob(RepairVerificationJobs.JobView view) {
        var out = new LinkedHashMap<String, Object>();
        out.put("jobId", view.jobId().toString());
        out.put("id", view.jobId().toString());
        out.put("proposalId", view.proposalId().value());
        view.finalProposalId().ifPresent(fp -> out.put("finalProposalId", fp.value()));
        out.put("stage", view.stage().name());
        String status = switch (view.stage()) {
            case REPORT_READY -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case INTERRUPTED_ON_RESTART -> "INTERRUPTED_ON_RESTART";
            case QUEUED -> "QUEUED";
            default -> "RUNNING";
        };
        out.put("status", status);
        out.put("terminal", view.terminal());
        out.put("interruptedOnRestart", view.stage() == RepairVerificationJobs.Stage.INTERRUPTED_ON_RESTART);
        out.put("createdAt", view.createdAt());
        out.put("updatedAt", view.updatedAt());
        out.put("eventsUrl", "/api/v1/repairs/jobs/" + view.jobId() + "/events");
        out.put("reportMarkdownUrl", "/api/v1/repairs/jobs/" + view.jobId() + "/report.md");
        out.put("events", view.events());
        out.put("rounds", view.rounds());
        view.error().ifPresent(err -> out.put("error", err));
        view.errorCode().ifPresent(code -> out.put("errorCode", code));
        if (!out.containsKey("errorCode") && view.stage() == RepairVerificationJobs.Stage.INTERRUPTED_ON_RESTART) {
            out.put("errorCode", "INTERRUPTED_ON_RESTART");
        }
        if (view.report().isPresent()) {
            var rep = view.report().orElseThrow();
            out.put("report", rep);
            out.put("verdict", rep.verdict().name());
            out.put("trustLabel", rep.trustLabel().name());
            out.put("executiveSummary", rep.executiveSummary());
            out.put("isolationBackend", rep.sandbox().isolationMode());
            out.put("checks", rep.checks());
            out.put("provenance", rep.provenance());
            out.put("markdownReport", rep.markdownReport());
            out.put("logTail", rep.sandbox().logTail());
            if (!rep.rounds().isEmpty()) {
                out.put("rounds", rep.rounds());
            }
            rep.behavioralDiff().ifPresent(bd -> {
                out.put("behavioralVerdict", bd.verdict().name());
                out.put("behavioralDiff", bd);
            });
            rep.springPreservation().ifPresent(sp -> {
                out.put("springPreservationStatus", sp.status().name());
                out.put("springPreservation", sp);
            });
            // UI-aligned aliases for Repair Studio Step 4 card
            var sd = rep.scoreDelta();
            var scoreMap = new LinkedHashMap<String, Object>();
            scoreMap.put("before", sd.beforeScore().orElse(null));
            scoreMap.put("after", sd.afterScore().orElse(null));
            scoreMap.put("delta", sd.delta().orElse(null));
            scoreMap.put("beforeBand", sd.beforeBand());
            scoreMap.put("afterBand", sd.afterBand());
            scoreMap.put("beforeConfidence", sd.beforeConfidence());
            scoreMap.put("afterConfidence", sd.afterConfidence());
            var dims = new ArrayList<Map<String, Object>>(sd.dimensions().size());
            for (var d : sd.dimensions()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", d.id());
                row.put("displayName", d.displayName());
                row.put("before", d.beforeScore().orElse(null));
                row.put("after", d.afterScore().orElse(null));
                row.put("delta", d.delta().orElse(null));
                dims.add(row);
            }
            scoreMap.put("dimensions", dims);
            out.put("scoreV2Delta", scoreMap);
            var vd = rep.violationDelta();
            out.put("violationsDelta", Map.of(
                    "resolved", vd.resolved().size(),
                    "introduced", vd.introduced().size(),
                    "persisted", vd.persisted().size()));
            var gd = rep.capabilityGapDelta();
            out.put("capabilityGapsDelta", Map.of(
                    "before", gd.beforeCount(),
                    "after", gd.afterCount(),
                    "delta", gd.delta()));
            var sb = rep.sandbox();
            out.put("build", Map.of(
                    "executed", sb.executed(),
                    "compiled", sb.executed() && sb.compileExit() == 0,
                    "compileExit", sb.compileExit(),
                    "buildTool", sb.buildTool(),
                    "status", sb.executed() ? (sb.compileExit() == 0 ? "PASSED" : "FAILED") : "NOT_RUN"));
            out.put("tests", Map.of(
                    "executed", sb.executed(),
                    "run", sb.testsRun(),
                    "failed", sb.testsFailed(),
                    "errors", sb.testsErrors(),
                    "failingTests", sb.failingTests(),
                    "status", sb.executed() ? (sb.testExit() == 0 ? "PASSED" : "FAILED") : "NOT_RUN"));
        }
        return out;
    }

    private Map<String, Object> handleApplyPreview(ContentDigest proposalId, Map<String, Object> input) {
        if (safePatchApplier == null)
            throw new Failure(503, "PATCH_APPLIER_UNAVAILABLE");
        QueryJson.object(input, "graph", "setup", "repoDir", "mode", "branchName", "verificationJobId");
        var proposal = repairStore.get(proposalId)
                .orElseThrow(() -> new Failure(404, "PROPOSAL_NOT_FOUND"));
        var repoDir = resolveLocalRepoDir(proposal, input);
        var mode = QueryJson.optionalText(input, "mode")
                .map(SafePatchApplier.ApplyMode::valueOf)
                .orElse(SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE);
        var branch = QueryJson.optionalText(input, "branchName");
        var report = resolveRepairReport(proposalId, QueryJson.optionalText(input, "verificationJobId"), false);
        String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        try {
            var preview = safePatchApplier.previewApply(ownerUserId, proposal, report, repoDir, mode, branch);
            var git = GitWorktreeManager.inspect(repoDir);
            var out = new LinkedHashMap<String, Object>();
            out.put("canApply", preview.canApply());
            out.put("mode", preview.mode().name());
            out.put("suggestedBranch", preview.suggestedBranch());
            out.put("verdict", preview.verdict().map(Enum::name).orElse("UNVERIFIED"));
            out.put("trustLabel", preview.trustLabel().map(Enum::name).orElse("UNVERIFIED_CANDIDATE"));
            out.put("requiresUnverifiedAck", preview.requiresUnverifiedAck());
            out.put("isGitRepo", preview.isGitRepo());
            out.put("cleanWorkingTree", preview.cleanWorkingTree());
            out.put("gitApplyCheckPassed", preview.gitApplyCheckPassed());
            out.put("beforeHashesValid", preview.fileChecks().stream().allMatch(SafePatchApplier.FileCheck::matchesBaseline));
            out.put("currentBranch", git.headBranch());
            out.put("gitStatus", !git.isGitRepo() ? "NOT_A_GIT_REPO" : (git.cleanWorkingTree() ? "CLEAN" : "DIRTY"));
            out.put("fileChecks", preview.fileChecks());
            out.put("conflicts", preview.conflicts());
            out.put("warnings", preview.warnings());
            preview.approvalToken().ifPresent(tok -> out.put("approvalToken", tok));
            preview.tokenExpiresAt().ifPresent(exp -> {
                out.put("tokenExpiresAt", exp);
                out.put("expiresAt", exp);
            });
            return out;
        } catch (java.io.IOException io) {
            throw new Failure(409, "PATCH_APPLY_CONFLICT");
        }
    }

    private Map<String, Object> handleApplyConfirm(ContentDigest proposalId, Map<String, Object> input) {
        if (safePatchApplier == null)
            throw new Failure(503, "PATCH_APPLIER_UNAVAILABLE");
        QueryJson.object(input, "approvalToken", "userConfirmed", "mode", "branchName",
                "commitMessage", "acknowledgeUnverified", "graph", "setup", "repoDir", "verificationJobId");
        var proposal = repairStore.get(proposalId)
                .orElseThrow(() -> new Failure(404, "PROPOSAL_NOT_FOUND"));
        var repoDir = resolveLocalRepoDir(proposal, input);
        String approvalToken = QueryJson.text(input, "approvalToken");
        boolean userConfirmed = Boolean.TRUE.equals(input.get("userConfirmed"));
        var mode = QueryJson.optionalText(input, "mode")
                .map(SafePatchApplier.ApplyMode::valueOf)
                .orElse(SafePatchApplier.ApplyMode.NEW_BRANCH_WORKTREE);
        var branch = QueryJson.optionalText(input, "branchName");
        var commitMessage = QueryJson.optionalText(input, "commitMessage");
        boolean ackUnverified = Boolean.TRUE.equals(input.get("acknowledgeUnverified"));
        var report = resolveRepairReport(proposalId, QueryJson.optionalText(input, "verificationJobId"), true);
        var actor = com.evolution.backend.auth.RequestContext.currentUser();
        Optional<GitWorktreeManager.CommitAuthor> commitAuthor = actor.isAuthenticated()
                ? Optional.of(GitWorktreeManager.CommitAuthor.forGitHubUser(
                        actor.name(), Optional.ofNullable(actor.email()), actor.login(), actor.numericGitHubId()))
                : Optional.empty();
        try {
            var res = safePatchApplier.confirmApply(actor.userId(), proposal, report, approvalToken, userConfirmed,
                    repoDir, mode, branch, commitMessage, ackUnverified, commitAuthor);
            var out = new LinkedHashMap<String, Object>();
            out.put("applyId", res.applyId());
            out.put("id", res.applyId());
            out.put("ownerUserId", res.ownerUserId());
            out.put("mode", res.mode().name());
            out.put("proposalIdentity", res.proposalIdentity().value());
            out.put("patchDigest", res.patchDigest().value());
            res.branchName().ifPresent(b -> out.put("branchName", b));
            res.commitSha().ifPresent(c -> out.put("commitSha", c));
            res.baseCommitSha().ifPresent(c -> out.put("baseCommitSha", c));
            out.put("modifiedFiles", res.modifiedFiles());
            out.put("createdFiles", res.createdFiles());
            out.put("deletedFiles", res.deletedFiles());
            res.backupDir().ifPresent(p -> out.put("backupDir", p.toString()));
            out.put("rollbackAvailable", res.rollbackAvailable());
            out.put("appliedAt", res.appliedAt());
            return out;
        } catch (SafePatchApplier.AccessDeniedException denied) {
            throw new Failure(403, "APPLY_ACCESS_DENIED");
        } catch (IllegalStateException rejected) {
            throw new Failure(409, "PATCH_APPLY_REJECTED");
        } catch (java.io.IOException conflict) {
            throw new Failure(409, "PATCH_APPLY_CONFLICT");
        }
    }

    private boolean isHostLocalCheckoutDisabledForCaller() {
        boolean multi = (auth != null && auth.isMultiUserMode())
                || com.evolution.backend.auth.RequestContext.isMultiUserMode();
        return multi && !com.evolution.backend.auth.AuthenticatedUser.LOCAL_USER_ID
                .equals(com.evolution.backend.auth.RequestContext.currentUserId());
    }

    private java.nio.file.Path resolveLocalRepoDir(CapturedRepair.Proposal proposal, Map<String, Object> input) {
        if (isHostLocalCheckoutDisabledForCaller()) {
            throw new Failure(403, "LOCAL_CHECKOUT_DISABLED_IN_MULTI_USER");
        }
        var explicit = QueryJson.optionalText(input, "repoDir");
        if (explicit.isPresent()) {
            if (connections == null) {
                throw new Failure(403, "REPO_DIR_NOT_REGISTERED");
            }
            var candidate = java.nio.file.Path.of(explicit.orElseThrow()).toAbsolutePath().normalize();
            if (!connections.isRegisteredLocalCheckout(candidate)) {
                throw new Failure(403, "REPO_DIR_NOT_REGISTERED");
            }
            return candidate;
        }
        var graphId = QueryJson.optionalText(input, "graph")
                .map(ContentDigest::new)
                .orElse(proposal.candidate().baseline().graph());
        if (connections != null) {
            var setupOpt = QueryJson.optionalText(input, "setup");
            if (setupOpt.isPresent()) {
                var conn = connections.connection(setupOpt.orElseThrow());
                if (conn.isPresent()) {
                    var checkout = connections.localCheckout(conn.orElseThrow().repository());
                    if (checkout.isPresent()) {
                        return checkout.orElseThrow();
                    }
                }
            }
            var baseline = store.load(graphId);
            if (baseline.isPresent()) {
                var checkout = CapturedRepairVerification.repository(baseline.orElseThrow())
                        .flatMap(connections::localCheckout);
                if (checkout.isPresent()) {
                    return checkout.orElseThrow();
                }
            }
        }
        throw new Failure(422, "REPO_CHECKOUT_UNAVAILABLE");
    }

    private Optional<RepairVerificationReport.Report> resolveRepairReport(ContentDigest proposalId,
            Optional<String> jobIdText, boolean blockHistoricalRejectionOnMismatch) {
        if (repairVerificationJobs == null) return Optional.empty();
        String ownerUserId = com.evolution.backend.auth.RequestContext.currentUserId();
        if (jobIdText.isPresent()) {
            try {
                var viewOpt = repairVerificationJobs.get(ownerUserId, parseUuid(jobIdText.orElseThrow()));
                if (viewOpt.isPresent()) {
                    var view = viewOpt.orElseThrow();
                    if (view.report().isPresent()
                            && view.report().orElseThrow().provenance().proposalIdentity().equals(proposalId)) {
                        return view.report();
                    }
                    if (view.effectiveProposalId().equals(proposalId)
                            && view.verification().isPresent()
                            && view.verification().orElseThrow().verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                        throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
                    }
                    if (isProposalRejectedInRounds(view, proposalId)) {
                        throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
                    }
                }
                if (!blockHistoricalRejectionOnMismatch) {
                    return Optional.empty();
                }
                // On apply/confirm, never allow a bogus or unrelated verificationJobId to bypass a caller-owned REJECTED verdict.
                for (var v : repairVerificationJobs.list(ownerUserId)) {
                    if (v.report().isPresent()
                            && v.report().orElseThrow().provenance().proposalIdentity().equals(proposalId)
                            && v.report().orElseThrow().verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                        throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
                    }
                    if (v.effectiveProposalId().equals(proposalId)
                            && v.verification().isPresent()
                            && v.verification().orElseThrow().verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                        throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
                    }
                    if (isProposalRejectedInRounds(v, proposalId)) {
                        throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
                    }
                }
                return Optional.empty();
            } catch (SafePatchApplier.AccessDeniedException denied) {
                throw new Failure(403, "APPLY_ACCESS_DENIED");
            }
        }
        // Fallback: latest completed verification report owned by the caller whose verified proposal matches proposalId
        for (var v : repairVerificationJobs.list(ownerUserId)) {
            if (v.report().isPresent() && v.report().orElseThrow().provenance().proposalIdentity().equals(proposalId)) {
                return v.report();
            }
            if (v.effectiveProposalId().equals(proposalId)
                    && v.verification().isPresent()
                    && v.verification().orElseThrow().verdict() == CapturedRepairVerification.Verdict.REJECTED) {
                throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
            }
            if (isProposalRejectedInRounds(v, proposalId)) {
                throw new Failure(409, "REPAIR_VERIFICATION_REJECTED");
            }
        }
        return Optional.empty();
    }

    private static boolean isProposalRejectedInRounds(RepairVerificationJobs.JobView view, ContentDigest proposalId) {
        var rounds = !view.rounds().isEmpty()
                ? view.rounds()
                : view.report().map(RepairVerificationReport.Report::rounds).orElse(List.of());
        for (var round : rounds) {
            if (proposalId.equals(round.proposalId()) && "REJECTED".equalsIgnoreCase(round.verdict())) {
                return true;
            }
        }
        return false;
    }

    private static String defaultAnalysisContext(KnowledgeGraph graph) {
        return graph.contexts().stream()
                .filter(c -> c.analysis().isPresent())
                .max(Comparator.<KnowledgeGraph.Context>comparingInt(c -> graph.nodes(c.identity()).size())
                        .thenComparing(Comparator.comparing(KnowledgeGraph.Context::identity).reversed()))
                .map(KnowledgeGraph.Context::identity)
                .orElse(graph.snapshot().value());
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid job UUID");
        }
    }

    private static void deleteRecursivelyQuietly(java.nio.file.Path dir) {
        try (var stream = java.nio.file.Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { java.nio.file.Files.deleteIfExists(p); } catch (java.io.IOException ignored) {}
            });
        } catch (java.io.IOException ignored) {}
    }
}
