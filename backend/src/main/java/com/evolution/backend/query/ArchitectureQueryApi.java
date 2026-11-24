package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.graph.*;
import com.evolution.analysis.metrics.*;
import com.evolution.analysis.policy.PolicyEvaluation;
import com.evolution.analysis.policy.PolicyImpactService;
import com.evolution.analysis.query.*;
import com.evolution.analysis.query.projection.GraphProjectionService;
import com.evolution.backend.analysis.AnalysisJobs;
import com.evolution.backend.analysis.RepositoryConnections;
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

    public record Response(int status, byte[] body) {
        public Response {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    private static final class Failure extends RuntimeException {
        final int status;
        final String code;

        Failure(int status, String code) {
            this.status = status;
            this.code = code;
        }
    }

    private final GraphStore store;
    private com.evolution.analysis.execution.RegisteredExecutionPort executions;
    private final PublishedReports reports;
    private final ApiLimits limits;
    private final BiFunction<KnowledgeGraph, Supplier<StopReason>, ArchitectureQueryService> factory;
    private AnalysisJobApi jobs;
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
    private CapturedRepairRunner capturedRepairRunner;
    private java.util.Map<com.evolution.analysis.contract.identity.SnapshotIdentity, ContentDigest> reanalyzedGraphs = java.util.Collections
            .synchronizedMap(new java.util.LinkedHashMap<>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<com.evolution.analysis.contract.identity.SnapshotIdentity, ContentDigest> eldest) {
                    return size() > 128;
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
        jobs = existing.jobs;
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
        reanalyzedGraphs = existing.reanalyzedGraphs;
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
        return api;
    }

    public static ArchitectureQueryApi withConfiguredCapturedRepairRunner(ArchitectureQueryApi existing) {
        var config = CapturedRepairRunner.fromSystemProperties();
        if (config.isEmpty())
            return existing;
        return withCapturedRepairRunner(existing, new CapturedRepairRunner(config.orElseThrow()));
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

    /** Job and query adapters share the same canonical publication stores. */
    public ArchitectureQueryApi(GraphStore store, PublishedReports reports, ApiLimits limits, AnalysisJobs jobs) {
        this(store, reports, limits);
        if (!Objects.requireNonNull(jobs).uses(store, reports))
            throw new IllegalArgumentException("Job and query publication stores differ");
        this.jobs = new AnalysisJobApi(Objects.requireNonNull(jobs), limits);
    }

    public ArchitectureQueryApi(GraphStore store, PublishedReports reports, ApiLimits limits, AnalysisJobs jobs,
            RepositoryConnections connections) {
        this(store, reports, limits, jobs);
        this.connections = Objects.requireNonNull(connections);
        this.jobs = new AnalysisJobApi(jobs, limits, connections);
    }

    public Response handle(String method, String rawPath, String contentType, byte[] body, BooleanSupplier cancelled) {
        try {
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
        } catch (Failure failure) {
            return error(failure.status, failure.code);
        } catch (ArchitectureInsightsAdvisor.CalculationInterrupted stopped) {
            return stopped.reason == StopReason.CANCELLED
                    ? error(409, "REQUEST_CANCELLED")
                    : error(503, "INSIGHTS_RESOURCE_LIMIT");
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

    static String allowedMethods(String path) {
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
        if (path.equals("/api/v1/analysis/jobs/history") || path.startsWith("/api/v1/analysis/jobs/")
                && (path.endsWith("/cancel") || path.endsWith("/gaps") || path.endsWith("/input-diagnostics")))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/architecture-insights"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/architecture-chat"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/architecture-chat/stream"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/policy-assessment"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/suggested-policy"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/captured-files"))
            return "GET, POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/captured-source"))
            return "POST";
        if (path.startsWith("/api/v1/graphs/") && path.endsWith("/repo-map"))
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
            case "POLICY_SCOPE_UNAVAILABLE" ->
                "Select a Java or Spring analysis context to evaluate architecture rules.";
            case "SCORING_RESOURCE_LIMIT" -> "Scoring reached the workbench resource limit. Try again shortly.";
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
            case "ISOLATED_RUNNER_REPO_UNAVAILABLE" ->
                "No local repository checkout is available for isolated repair execution.";
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
            if (architectureChat != null)
                capabilities.put("architectureChatProvider", architectureChat.provider());
            capabilities.put("repositoryScoringEnabled", true);
            capabilities.put("suggestedPolicyEnabled", true);
            capabilities.put("capturedFilesEnabled", true);
            capabilities.put("capturedSourceEnabled", true);
            capabilities.put("repoMapEnabled", true);
            capabilities.put("repairReanalysisEnabled", repairReanalysis != null);
            capabilities.put("repairModelConfigured", repairModel != null);
            capabilities.put("isolatedRepairRunnerEnabled", capturedRepairRunner != null);
            capabilities.put("repairRecipeCount", JavaRepairRecipes.catalog().size());
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
            var fast = readCachedManifest(graphId);
            if (fast.isPresent()) {
                var m = fast.orElseThrow();
                return Map.of("graph", graphId, "snapshot", m.snapshot().value(),
                        "contexts", m.contexts(), "reports", reports.forGraph(graphId));
            }
            var graph = load(graphId);
            return Map.of("graph", graphId, "snapshot", graph.snapshot().value(),
                    "contexts", graph.contexts(), "reports", reports.forGraph(graphId));
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
        if (parts.length == 6 && parts[5].equals("captured-files")) {
            String query = "";
            String language = "";
            int limit = 100;
            int offset = 0;
            if (method.equals("GET")) {
                requireEmpty(body);
            } else {
                requireMethod(method, "POST");
                var input = QueryJson.object(decode(body, contentType), "query", "language", "limit", "offset");
                if (input.get("query") != null) query = GraphJson.string(input.get("query")).strip();
                if (input.get("language") != null) language = GraphJson.string(input.get("language")).strip();
                limit = exactBoundedInt(input, "limit", 100, 1, 500);
                offset = exactBoundedInt(input, "offset", 0, 0, 1_000_000);
            }
            var graph = load(graphId);
            var list = CapturedFileIndex.forGraph(graph).list(query, language, offset, limit);
            var data = new LinkedHashMap<String, Object>();
            data.put("graph", graphId);
            data.put("totalFiles", list.totalFiles());
            data.put("matchedFiles", list.matchedFiles());
            data.put("offset", list.offset());
            data.put("limit", list.limit());
            data.put("truncated", list.truncated());
            data.put("languages", list.languages());
            data.put("files", list.files().stream().map(CapturedFileIndex.Entry::toView).toList());
            return Collections.unmodifiableMap(data);
        }
        if (parts.length == 6 && parts[5].equals("captured-source")) {
            requireMethod(method, "POST");
            var input = QueryJson.object(decode(body, contentType), "path", "startLine", "endLine");
            String targetPath = QueryJson.text(input, "path");
            int startLine = exactBoundedInt(input, "startLine", 1, 1, 1_000_000);
            int endLine = input.containsKey("endLine")
                    ? exactBoundedInt(input, "endLine", startLine + CapturedFileIndex.MAX_LINES_PER_READ - 1, startLine, 1_000_000)
                    : startLine + CapturedFileIndex.MAX_LINES_PER_READ - 1;
            var graph = load(graphId);
            var read = CapturedFileIndex.forGraph(graph).read(graph, targetPath, startLine, endLine);
            var data = new LinkedHashMap<String, Object>();
            data.put("graph", graphId);
            data.put("found", read.found());
            data.put("ambiguous", read.ambiguous());
            data.put("path", read.path());
            data.put("language", read.language());
            data.put("digest", read.digest().orElse(null));
            data.put("evidenceId", read.evidenceId().orElse(null));
            data.put("startLine", read.startLine());
            data.put("endLine", read.endLine());
            data.put("totalLines", read.totalLines());
            data.put("truncated", read.truncated());
            data.put("content", read.content());
            data.put("numberedSource", read.numberedSource());
            data.put("candidates", read.candidates());
            return Collections.unmodifiableMap(data);
        }
        if (parts.length == 6 && parts[5].equals("repo-map")) {
            Optional<String> requestedContext = Optional.empty();
            var seeds = new ArrayList<String>();
            int maxFiles = 25;
            int maxTokens = 4000;
            if (method.equals("GET")) {
                requireEmpty(body);
            } else {
                requireMethod(method, "POST");
                var input = QueryJson.object(decode(body, contentType), "context", "seeds", "maxFiles", "maxTokens");
                requestedContext = QueryJson.optionalText(input, "context");
                if (input.get("seeds") != null) {
                    var rawSeeds = GraphJson.array(input.get("seeds"));
                    if (rawSeeds.size() > 50) throw new IllegalArgumentException("Too many seeds");
                    for (Object s : rawSeeds) {
                        String text = GraphJson.string(s).strip();
                        if (text.isEmpty() || text.length() > 512) throw new IllegalArgumentException("Invalid seed");
                        seeds.add(text);
                    }
                }
                maxFiles = exactBoundedInt(input, "maxFiles", 25, 1, 100);
                maxTokens = exactBoundedInt(input, "maxTokens", 4000, 500, 24_000);
            }
            var graph = load(graphId);
            if (requestedContext.isPresent()) {
                String ctx = requestedContext.orElseThrow();
                if (graph.contexts().stream().noneMatch(c -> c.identity().equals(ctx))) {
                    throw new Failure(404, "CONTEXT_NOT_FOUND");
                }
            }
            var guard = ArchitectureQueryService.operationalGuard(cancelled,
                    System.nanoTime() + limits.queryTimeoutMillis() * 1_000_000, 8_000_000);
            scoringCheckpoint(guard);
            return RepoMapBuilder.build(graph, requestedContext, seeds, maxFiles, maxTokens, guard);
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
            var contexts = readCachedManifest(graphId).map(GraphManifest::contexts)
                    .orElseGet(() -> load(graphId).contexts());
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

    private ArchitectureChat.Result runArchitectureChat(ContentDigest graphId, ArchitectureChat.Input input,
            BooleanSupplier cancelled,
            ArchitectureAgent.ProgressListener listener) {
        var model = architectureChat;
        boolean offTopic = ArchitectureChat.directlyOutOfScope(input);
        boolean overviewQuestion = ArchitectureChatOverview.matches(input.question());
        if (!offTopic && !overviewQuestion && !input.evidenceOnly() && model != null && model.provider().remote()
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
        if (overviewQuestion)
            return ArchitectureChat.Result.overview(context,
                    ArchitectureChatOverview.render(input.question(), context));
        if (model == null || input.evidenceOnly())
            return ArchitectureChat.Result.local(context, model != null);
        try {
            if (input.agentic() && !model.settings().jsonMode()) {
                var agentResult = ArchitectureAgent.run(model, input, context, queries, reports,
                        Optional.ofNullable(jobs), guard, listener);
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
        return store.load(identity).orElseThrow(() -> new Failure(404, "GRAPH_NOT_FOUND"));
    }

    private Optional<GraphManifest> readCachedManifest(ContentDigest identity) {
        if (store instanceof com.evolution.analysis.filesystem.FileGraphStore fgs) {
            return fgs.readManifest(identity);
        }
        return Optional.empty();
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
            case "visual-graph" -> QueryJson.object(a, "seed", "depth");
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
                int depth = visualDepth(a);
                if (a.containsKey("seed")) {
                    yield projection.focusedSubgraph(r, QueryJson.text(a, "seed"), depth, Optional.empty());
                }
                yield projection.project(r, Optional.empty());
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
            QueryJson.object(args, "seed", "depth", "violationsOnly");
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
                            capturedRepairRunner != null, finding.bundle().path());
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
                        if (maxRepairAttempts < 1 || maxRepairAttempts > 3)
                            throw new IllegalArgumentException("maxRepairAttempts must be between 1 and 3");
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
                if (runIsolated && capturedRepairRunner == null) {
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
                            : CapturedRepairVerification.repository(service.graph()).flatMap(connections::localCheckout);
                    if (repoRoot.isEmpty()) {
                        throw new Failure(422, "ISOLATED_RUNNER_REPO_UNAVAILABLE");
                    }
                    try {
                        var syntheticOutcome = new CapturedRepair.Outcome(
                                CapturedRepair.VERSION, "PROPOSED", Optional.empty(), List.of(), List.of(),
                                List.of(), Optional.of(capturedPinned), 1);
                        var inv = capturedRepairRunner.run(repoRoot.orElseThrow(), syntheticOutcome, result,
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
                var req = new Request(e.graph(), e.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                        Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(),
                        Limits.defaults());
                if (Boolean.TRUE.equals(args.get("violationsOnly"))) {
                    yield projection.violationsSubgraph(req, e);
                }
                if (args.containsKey("seed")) {
                    yield projection.focusedSubgraph(req, QueryJson.text(args, "seed"), depth, Optional.of(e));
                }
                yield projection.project(req, Optional.of(e));
            }
            default -> throw new Failure(404, "NOT_FOUND");
        };
    }

    private static int exactBoundedInt(Map<String, Object> input, String key, int defaultValue, int min, int max) {
        if (!input.containsKey(key) || input.get(key) == null) return defaultValue;
        if (!(input.get(key) instanceof java.math.BigDecimal bd))
            throw new IllegalArgumentException("Invalid integer " + key);
        int value;
        try {
            value = bd.intValueExact();
        } catch (ArithmeticException invalid) {
            throw new IllegalArgumentException("Invalid integer " + key);
        }
        if (value < min || value > max)
            throw new IllegalArgumentException("Out-of-range integer " + key);
        return value;
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
}
