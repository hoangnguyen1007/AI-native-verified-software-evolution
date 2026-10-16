package com.evolution.backend.query;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.graph.GraphJson;
import com.evolution.analysis.query.ArchitectureQuery;
import com.evolution.backend.analysis.AnalysisJobs;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Strict additive job routes; submissions select only operator-registered setups. */
final class AnalysisJobApi {
    private final AnalysisJobs jobs; private final ApiLimits limits;
    AnalysisJobApi(AnalysisJobs jobs, ApiLimits limits) { this.jobs = jobs; this.limits = limits; }
    AnalysisJobs.Limits limits() { return jobs.limits(); }
    private static final class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status, String code) { this.status = status; this.code = code; }
    }
    ArchitectureQueryApi.Response handle(String method, String path, String contentType, byte[] body, BooleanSupplier cancelled) {
        try {
            if (cancelled.getAsBoolean()) throw new Failure(409,"REQUEST_CANCELLED");
            Object data; int status = 200;
            if (path.equals("/api/v1/analysis/setups")) {
                method(method,"GET"); empty(body); data = Map.of("items",jobs.setups(),"versionControl","NOT_INSPECTED");
            } else if (path.equals("/api/v1/analysis/jobs")) {
                if (method.equals("GET")) { empty(body); data = jobs.history(20,Optional.empty()); }
                else {
                    method(method,"POST"); var input = decode(body,contentType,"setup","idempotencyKey");
                    var submission = jobs.submit(QueryJson.text(input,"setup"),QueryJson.text(input,"idempotencyKey"));
                    data = submission.job(); status = submission.created() ? 202 : 200;
                }
            } else if (path.equals("/api/v1/analysis/jobs/history")) {
                method(method,"POST"); var page = QueryJson.page(decode(body,contentType,"size","cursor"));
                data = jobs.history(page.size(),page.cursor());
            } else {
                var parts = path.split("/",-1);
                if (parts.length < 6 || parts.length > 7 || !parts[4].equals("jobs")) throw new Failure(404,"NOT_FOUND");
                var id = uuid(parts[5]);
                if (parts.length == 6) { method(method,"GET"); empty(body); data = jobs.get(id); }
                else if (parts[6].equals("cancel")) { method(method,"POST"); decode(body,contentType); var view = jobs.cancel(id); data = view; status = AnalysisJobs.terminal(view.state()) ? 200 : 202; }
                else if (parts[6].equals("gaps")) { method(method,"POST"); var page = QueryJson.page(decode(body,contentType,"size","cursor")); data = jobs.gaps(id,page.size(),page.cursor()); }
                else if (parts[6].equals("input-diagnostics")) { method(method,"POST"); var page = QueryJson.page(decode(body,contentType,"size","cursor")); data = jobs.inputProblems(id,page.size(),page.cursor()); }
                else throw new Failure(404,"NOT_FOUND");
            }
            return response(status,data);
        } catch (Failure failure) { return error(failure.status,failure.code); }
        catch (AnalysisJobs.Rejected rejected) {
            int status = switch (rejected.code()) { case "SETUP_NOT_FOUND","JOB_NOT_FOUND" -> 404; case "IDEMPOTENCY_CONFLICT","JOB_NOT_FINISHED" -> 409; default -> 503; };
            return error(status,rejected.code());
        } catch (BoundedJson.TooLarge large) { return error(422,"RESPONSE_TOO_LARGE"); }
        catch (GraphJson.LimitExceededException large) { return error(413,"JSON_LIMIT_EXCEEDED"); }
        catch (IllegalArgumentException invalid) { return error(400,"INVALID_REQUEST"); }
        catch (RuntimeException failure) { return error(500,"INTERNAL_ERROR"); }
    }
    private Map<String,Object> decode(byte[] body, String media, String... allowed) {
        if (!ArchitectureQueryApi.jsonMediaType(media)) throw new Failure(415,"UNSUPPORTED_MEDIA_TYPE");
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            return QueryJson.object(GraphJson.read(text,new GraphJson.Limits(limits.requestBytes(),limits.jsonDepth(),limits.jsonValues())),allowed);
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid UTF-8"); }
    }
    private ArchitectureQueryApi.Response response(int status,Object data) {
        var bytes = BoundedJson.encode(data,limits.responseBytes());
        return new ArchitectureQueryApi.Response(status,BoundedJson.encode(Map.of("apiVersion",ArchitectureQueryApi.VERSION,"queryVersion",ArchitectureQuery.VERSION,
                "jobVersion",AnalysisJobs.VERSION,"resultIdentity",ContentDigest.sha256(bytes),"data",data),limits.responseBytes()));
    }
    private ArchitectureQueryApi.Response error(int status,String code) {
        return new ArchitectureQueryApi.Response(status,BoundedJson.encode(Map.of("apiVersion",ArchitectureQueryApi.VERSION,"queryVersion",ArchitectureQuery.VERSION,
                "jobVersion",AnalysisJobs.VERSION,"error",Map.of("code",code,"message","The local analysis job request could not be completed.")),limits.responseBytes()));
    }
    private static void method(String actual,String expected) { if (!actual.equals(expected)) throw new Failure(405,"METHOD_NOT_ALLOWED"); }
    private static void empty(byte[] body) { if (body.length != 0) throw new IllegalArgumentException("Body not applicable"); }
    private static UUID uuid(String value) { var id = UUID.fromString(value); if (!id.toString().equals(value)) throw new IllegalArgumentException("Invalid job identity"); return id; }
}
