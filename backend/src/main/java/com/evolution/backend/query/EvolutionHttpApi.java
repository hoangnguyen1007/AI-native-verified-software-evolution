package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.evolution.EvolutionSnapshot;
import com.evolution.analysis.evolution.SnapshotComparison.*;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.CanonicalRecordReader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/** Versioned HTTP transport over the same query and job contracts used by the CLI. */
final class EvolutionHttpApi {
    static final String PREFIX = "/api/v1/evolution/";
    private final EvolutionJobs jobs;
    private final EvolutionQueries queries;
    private final ApiLimits limits;

    EvolutionHttpApi(EvolutionJobs jobs, EvolutionQueries queries, ApiLimits limits) {
        this.jobs = jobs;
        this.queries = Objects.requireNonNull(queries);
        this.limits = limits;
    }

    ArchitectureQueryApi.Response handle(ArchitectureQueryApi api, String method, String path,
                                         Map<String,Object> input, BooleanSupplier stop) {
        var parts = path.substring(PREFIX.length()).split("/", -1);
        try {
            EvolutionSnapshot.check(stop);
            if (parts.length == 1 && parts[0].equals("jobs")) {
                if (!method.equals("POST")) return api.error(405, "METHOD_NOT_ALLOWED");
                if (jobs == null) return api.error(503, "EVOLUTION_JOBS_UNAVAILABLE");
                fields(input, "request", "idempotencyKey");
                var submission = jobs.submit(EvolutionRequest.decode(input.get("request")), optional(input, "idempotencyKey"));
                return response(submission.created() ? 202 : 200, submission.job());
            }
            if (parts.length < 2 || parts.length > 3) return api.error(404, "NOT_FOUND");
            var id = new ContentDigest(parts[1]);
            var action = parts.length == 2 ? "" : parts[2];
            if (parts[0].equals("jobs")) {
                if (jobs == null) return api.error(503, "EVOLUTION_JOBS_UNAVAILABLE");
                if (!Set.of("", "resume", "cancel").contains(action)) return api.error(404, "NOT_FOUND");
                if (!method.equals(action.isEmpty() ? "GET" : "POST")) return api.error(405, "METHOD_NOT_ALLOWED");
                fields(input);
                var job = switch (action) {
                    case "resume" -> jobs.resume(id);
                    case "cancel" -> jobs.cancel(id);
                    default -> jobs.get(id);
                };
                return job.isPresent() ? response(200, job.orElseThrow()) : api.error(404, "EVOLUTION_JOB_NOT_FOUND");
            }
            if (!parts[0].equals("results")) return api.error(404, "NOT_FOUND");
            if (action.isEmpty() || action.equals("export") && method.equals("GET")) {
                if (!method.equals("GET")) return api.error(405, "METHOD_NOT_ALLOWED");
                fields(input);
                return response(200, action.isEmpty() ? queries.summary(id, stop) :
                        queries.export(id,new EvolutionQueries.Query(Section.CONTEXTS,20,Optional.empty(),EvolutionQueries.Filter.empty()),stop));
            }
            if (!Set.of("query","export","graph","evidence").contains(action)) return api.error(404, "NOT_FOUND");
            if (!method.equals("POST")) return api.error(405, "METHOD_NOT_ALLOWED");
            return switch (action) {
                case "query" -> response(200, queries.page(id, query(input), stop));
                case "export" -> response(200, queries.export(id, query(input), stop));
                case "graph" -> {
                    fields(input,"side","context","edges","node","size","cursor");
                    if (!(input.getOrDefault("edges", false) instanceof Boolean edges)) throw new IllegalArgumentException("Invalid graph projection");
                    yield response(200, queries.sideGraph(id, side(input), GraphJson.string(input.get("context")), edges,
                            optional(input,"node"), size(input), optional(input,"cursor"), stop));
                }
                default -> {
                    fields(input,"side","identity");
                    yield response(200, queries.evidence(id, side(input), new ContentDigest(GraphJson.string(input.get("identity"))), stop));
                }
            };
        } catch (NoSuchFileException | FileNotFoundException missing) {
            return api.error(404, "EVOLUTION_ARTIFACT_NOT_FOUND");
        } catch (EvolutionJobs.Conflict conflict) {
            return api.error(409, "EVOLUTION_IDEMPOTENCY_CONFLICT");
        } catch (CancellationException cancelled) {
            return api.error(409, "REQUEST_CANCELLED");
        } catch (RejectedExecutionException busy) {
            return api.error(503, "EVOLUTION_WORKER_BUSY");
        } catch (EvolutionSnapshot.ReadFailure unavailable) {
            return switch (unavailable.reason()) {
                case MISSING -> api.error(404,"EVOLUTION_ARTIFACT_NOT_FOUND");
                case CORRUPT -> api.error(503,"EVOLUTION_ARTIFACT_CORRUPT");
                default -> api.error(503,"EVOLUTION_STORE_UNAVAILABLE");
            };
        } catch (IOException unavailable) {
            String reason = Objects.toString(unavailable.getMessage(), "");
            if (reason.equals("EVOLUTION_RESULT_CONFLICT")) return api.error(409,"EVOLUTION_RESULT_CONFLICT");
            boolean corrupt = reason.contains("CORRUPT") || reason.contains("CAS_UNAVAILABLE");
            return api.error(503, corrupt ? "EVOLUTION_ARTIFACT_CORRUPT" : "EVOLUTION_STORE_UNAVAILABLE");
        }
    }

    private ArchitectureQueryApi.Response response(int status, Object data) {
        byte[] canonical = BoundedJson.encode(data, limits.responseBytes());
        return new ArchitectureQueryApi.Response(status, BoundedJson.encode(Map.of(
                "apiVersion", EvolutionQueries.VERSION, "data", data, "resultIdentity", ContentDigest.sha256(canonical)),
                limits.responseBytes()));
    }

    private static void fields(Map<String,Object> input, String... names) {
        if (!Set.of(names).containsAll(input.keySet())) throw new IllegalArgumentException("Unknown comparison field");
    }

    private static Optional<String> optional(Map<String,Object> input, String name) {
        return Optional.ofNullable(input.get(name)).map(GraphJson::string);
    }

    private static Side side(Map<String,Object> input) { return Side.valueOf(GraphJson.string(input.get("side"))); }

    private static int size(Map<String,Object> input) {
        if (!input.containsKey("size")) return 20;
        if (!(input.get("size") instanceof java.math.BigDecimal number)) throw new IllegalArgumentException("Invalid page size");
        try { return number.intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("Invalid page size"); }
    }

    private static EvolutionQueries.Query query(Map<String,Object> input) {
        fields(input,"section","size","cursor","filter");
        var filter = input.get("filter") == null ? Map.<String,Object>of() : GraphJson.object(input.get("filter"));
        fields(filter,"side","kind","scope","subject");
        return new EvolutionQueries.Query(Section.valueOf(GraphJson.string(input.get("section"))), size(input),
                optional(input,"cursor"), new EvolutionQueries.Filter(optional(filter,"side").map(Side::valueOf),
                optional(filter,"kind"), optional(filter,"scope"), optional(filter,"subject")));
    }
}
