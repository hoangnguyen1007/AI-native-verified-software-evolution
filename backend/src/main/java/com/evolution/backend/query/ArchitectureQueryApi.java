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

/** Replaceable HTTP/JSON adapter. Domain queries and all evidence arithmetic remain in analyzer. */
public final class ArchitectureQueryApi {
    public static final String VERSION = "architecture.http:m7.1-v1";
    public static final List<String> OPERATIONS = List.of("summary","provenance","entities","entity","edges","dependencies",
            "relationships","expand","paths","shortest-path","cycles","reachability","impact","evidence","explain","gaps",
            "spring","configurations","artifacts","metric-catalog","metric","comparison-preparation","visual-graph","children","realizations","metric-definition");
    public static final List<String> REPORT_OPERATIONS = List.of("rules","findings","finding","bundle","policy-metrics","assessment","policy-impact","visual-graph","replay");
    public record Response(int status, byte[] body) {
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
    }
    private static final class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code) { this.status=status; this.code=code; }
    }
    private final GraphStore store;
    private com.evolution.analysis.execution.RegisteredExecutionPort executions;
    private final PublishedReports reports;
    private final ApiLimits limits;
    private final BiFunction<KnowledgeGraph,Supplier<StopReason>,ArchitectureQueryService> factory;
    private AnalysisJobApi jobs;
    private RepositoryConnections connections;
    public ArchitectureQueryApi(GraphStore store) { this(store,new PublishedReports(),ApiLimits.defaults()); }
    public ArchitectureQueryApi(GraphStore store,PublishedReports reports,ApiLimits limits) {
        this(store,reports,limits,(graph,guard) -> new ArchitectureMetricsService(graph,ArchitectureMetric.Limits.defaults(),guard,Optional.empty(),List.of()).queries());
    }
    /** Provider injection supports embedding and explicit absent-provider/cancellation contract checks. */
    public ArchitectureQueryApi(GraphStore store,PublishedReports reports,ApiLimits limits,
                                BiFunction<KnowledgeGraph,Supplier<StopReason>,ArchitectureQueryService> factory) {
        this.store=Objects.requireNonNull(store); this.reports=Objects.requireNonNull(reports);
        this.limits=Objects.requireNonNull(limits); this.factory=Objects.requireNonNull(factory);
    }
    public ApiLimits limits() { return limits; }
    /** Operator composition only. Request JSON cannot register an executor or launch policy. */
    public static ArchitectureQueryApi withExecutions(GraphStore store,PublishedReports reports,ApiLimits limits,
            com.evolution.analysis.execution.RegisteredExecutionPort executions){
        return withExecutions(new ArchitectureQueryApi(store,reports,limits),executions);
    }
    /** Preserve existing analysis jobs, connections and query factory when adding execution access. */
    public static ArchitectureQueryApi withExecutions(ArchitectureQueryApi existing,
            com.evolution.analysis.execution.RegisteredExecutionPort executions){
        Objects.requireNonNull(existing);
        if(existing.executions!=null)throw new IllegalArgumentException("EXECUTION_PORT_ALREADY_CONFIGURED");
        var api=new ArchitectureQueryApi(existing.store,existing.reports,existing.limits,existing.factory);
        api.jobs=existing.jobs;api.connections=existing.connections;api.executions=Objects.requireNonNull(executions);return api;
    }
    /** Job and query adapters share the same canonical publication stores. */
    public ArchitectureQueryApi(GraphStore store,PublishedReports reports,ApiLimits limits,AnalysisJobs jobs) {
        this(store,reports,limits);
        if (!Objects.requireNonNull(jobs).uses(store,reports)) throw new IllegalArgumentException("Job and query publication stores differ");
        this.jobs = new AnalysisJobApi(Objects.requireNonNull(jobs),limits);
    }
    public ArchitectureQueryApi(GraphStore store,PublishedReports reports,ApiLimits limits,AnalysisJobs jobs,RepositoryConnections connections) {
        this(store,reports,limits,jobs);
        this.connections=Objects.requireNonNull(connections);
        this.jobs=new AnalysisJobApi(jobs,limits,connections);
    }
    public Response handle(String method,String rawPath,String contentType,byte[] body,BooleanSupplier cancelled) {
        try {
            if (body.length > limits.requestBytes()) throw new Failure(413,"REQUEST_TOO_LARGE");
            if(rawPath.startsWith("/api/v2/executions/"))return execution(method,rawPath,contentType,body,cancelled);
            if(rawPath.startsWith("/api/v2/analysis/jobs/"))return jobs==null?error(503,"JOBS_UNAVAILABLE"):jobs.handle(method,rawPath,contentType,body,cancelled);
            if(rawPath.startsWith("/api/v2/acquisitions/")){
                if(connections==null)throw new Failure(503,"CONNECTIONS_UNAVAILABLE");if(!method.equals("POST"))throw new Failure(405,"METHOD_NOT_ALLOWED");
                var input=decode(body,contentType);Object data;
                try{
                    if(rawPath.equals("/api/v2/acquisitions/history")){
                        if(!Set.of("after","size").containsAll(input.keySet()))throw new IllegalArgumentException();
                        long after=input.containsKey("after")?new java.math.BigDecimal(input.get("after").toString()).longValueExact():0;int size=input.containsKey("size")?new java.math.BigDecimal(input.get("size").toString()).intValueExact():20;
                        data=connections.acquisitionHistory(after,size);
                    }else if(rawPath.endsWith("/readiness")){
                        if(!Set.of("section","size","cursor").containsAll(input.keySet()))throw new IllegalArgumentException();
                        var paging=new TreeMap<String,Object>(input);Object section=paging.remove("section");var page=QueryJson.page(paging);
                        if(section!=null&&!(section instanceof String))throw new IllegalArgumentException();
                        data=connections.readiness(rawPath.substring("/api/v2/acquisitions/".length(),rawPath.length()-"/readiness".length()),section==null?"requirements":(String)section,page.size(),page.cursor());
                    }else{
                        if(!Set.of("size","cursor").containsAll(input.keySet()))throw new IllegalArgumentException();var page=QueryJson.page(input);
                        data=connections.acquisition(rawPath.substring("/api/v2/acquisitions/".length()),page.size(),page.cursor());
                    }
                }catch(java.io.IOException unavailable){throw new Failure(503,"ACQUISITION_STORE_UNAVAILABLE");}
                byte[] encoded=BoundedJson.encode(data,limits.responseBytes());return new Response(200,BoundedJson.encode(Map.of("apiVersion","repository.acquisition-api:m8.4-v2","data",data,"resultIdentity",ContentDigest.sha256(encoded)),limits.responseBytes()));
            }
            if (rawPath.equals("/api/v1/analysis") || rawPath.startsWith("/api/v1/analysis/"))
                return jobs == null ? error(503,"JOBS_UNAVAILABLE") : jobs.handle(method,rawPath,contentType,body,cancelled);
            Object data = route(method,rawPath,contentType,body,cancelled);
            byte[] canonicalData = BoundedJson.encode(data,limits.responseBytes());
            return new Response(200,BoundedJson.encode(Map.of("apiVersion",VERSION,"queryVersion",ArchitectureQuery.VERSION,
                    "resultIdentity",ContentDigest.sha256(canonicalData),"data",data),limits.responseBytes()));
        } catch (Failure failure) { return error(failure.status,failure.code); }
        catch (BoundedJson.TooLarge large) { return error(422,"RESPONSE_TOO_LARGE"); }
        catch (GraphJson.LimitExceededException large) { return error(413,"JSON_LIMIT_EXCEEDED"); }
        catch (GraphStoreException unavailable) { return error(503,"GRAPH_STORE_UNAVAILABLE"); }
        catch (IllegalArgumentException invalid) { return error(400,"INVALID_REQUEST"); }
        catch (RuntimeException failed) { return error(500,"INTERNAL_ERROR"); }
    }
    public Response error(int status,String code) {
        return new Response(status,CanonicalJson.write(Map.of("apiVersion",VERSION,"queryVersion",ArchitectureQuery.VERSION,
                "error",Map.of("code",code,"message",message(code)))).getBytes(StandardCharsets.UTF_8));
    }
    private Response execution(String method,String path,String contentType,byte[] body,BooleanSupplier cancelled){
        if(!method.equals("POST"))throw new Failure(405,"METHOD_NOT_ALLOWED");
        var operation=switch(path){
            case "/api/v2/executions/submit" -> com.evolution.analysis.execution.RegisteredExecutionPort.Operation.SUBMIT;
            case "/api/v2/executions/status" -> com.evolution.analysis.execution.RegisteredExecutionPort.Operation.STATUS;
            case "/api/v2/executions/cancel" -> com.evolution.analysis.execution.RegisteredExecutionPort.Operation.CANCEL;
            case "/api/v2/executions/recover" -> com.evolution.analysis.execution.RegisteredExecutionPort.Operation.RECOVER;
            default -> throw new Failure(404,"NOT_FOUND");
        };
        var input=decode(body,contentType);
        if(!input.keySet().equals(Set.of("registration"))||!(input.get("registration") instanceof String id))throw new Failure(400,"INVALID_REQUEST");
        var registration=new ContentDigest(id);
        if(cancelled.getAsBoolean())throw new Failure(409,"REQUEST_CANCELLED");
        if(executions==null)throw new Failure(503,"EXECUTIONS_UNAVAILABLE");
        try{
            var data=executions.execute(operation,registration);
            if(!data.registration().equals(registration))throw new Failure(503,"EXECUTION_SCOPE_MISMATCH");
            var canonical=BoundedJson.encode(data,limits.responseBytes());
            return new Response(200,BoundedJson.encode(Map.of("apiVersion",com.evolution.analysis.execution.RegisteredExecutionPort.VERSION,
                    "resultIdentity",ContentDigest.sha256(canonical),"data",data),limits.responseBytes()));
        }catch(com.evolution.analysis.execution.RegisteredExecutionPort.Rejected rejected){
            throw new Failure(rejected.failure()==com.evolution.analysis.execution.RegisteredExecutionPort.Failure.SCOPE_CONFLICT?409:404,"EXECUTION_"+rejected.failure().name());
        }catch(java.io.IOException unavailable){throw new Failure(503,"EXECUTIONS_UNAVAILABLE");}
    }
    static String allowedMethods(String path) {
        if(path.startsWith("/api/v2/executions/"))return "POST";
        if (path.equals("/api/v1/analysis/connections")) return "GET, POST";
        if (path.equals("/api/v1/analysis/jobs")) return "GET, POST";
        if (path.equals("/api/v1/analysis/jobs/history") || path.startsWith("/api/v1/analysis/jobs/") && (path.endsWith("/cancel") || path.endsWith("/gaps") || path.endsWith("/input-diagnostics"))) return "POST";
        return path.contains("/queries/") || path.split("/",-1).length == 8 ? "POST" : "GET";
    }
    private static String message(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "The request does not satisfy the API contract.";
            case "GRAPH_NOT_FOUND" -> "The requested graph is unavailable.";
            case "REPORT_NOT_FOUND", "REPORT_ARTIFACT_UNAVAILABLE" -> "The requested canonical report artifact is unavailable.";
            case "NOT_FOUND" -> "The requested API operation is unavailable.";
            case "METHOD_NOT_ALLOWED" -> "This route does not support the requested HTTP method.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "Use application/json with UTF-8 and no content encoding.";
            case "NOT_ACCEPTABLE" -> "The API produces application/json.";
            case "REQUEST_TOO_LARGE", "JSON_LIMIT_EXCEEDED" -> "The request exceeds the configured transport limit.";
            case "RESPONSE_TOO_LARGE" -> "The complete response exceeds the transport limit; narrow the query or page.";
            case "FORBIDDEN_ORIGIN" -> "The local API accepts only same-origin requests.";
            case "BUSY" -> "The local API is at its concurrent request limit.";
            case "GRAPH_STORE_UNAVAILABLE" -> "Graph storage could not supply a verified snapshot.";
            case "JOBS_UNAVAILABLE" -> "Analysis jobs are not configured in this local server.";
            default -> "The request could not be completed.";
        };
    }
    private Object route(String method,String path,String contentType,byte[] body,BooleanSupplier cancelled) {
        if (path.equals("/api/v1")) {
            requireMethod(method,"GET"); requireEmpty(body);
            var capabilities = new TreeMap<String,Object>();
            capabilities.putAll(Map.of("operations",OPERATIONS,"reportOperations",REPORT_OPERATIONS,"transportLimits",limits,
                    "queryLimits",Limits.defaults(),"graphSchema",KnowledgeGraph.SCHEMA));
            capabilities.put("jobVersion",AnalysisJobs.VERSION); capabilities.put("analysisJobsEnabled",jobs != null);
            capabilities.put("repositoryConnectionsEnabled",connections != null);
            capabilities.put("acquisitionVersion","repository.acquisition-api:m8.4-v2");
            capabilities.put("readinessVersion",com.evolution.analysis.readiness.ReadinessPlan.SCHEMA);
            capabilities.put("acquisitionRoutes",List.of("/api/v2/acquisitions/history","/api/v2/acquisitions/{revision}","/api/v2/acquisitions/{revision}/readiness","/api/v2/analysis/jobs/{id}/resume"));
            if (jobs != null) capabilities.put("jobLimits",jobs.limits());
            return capabilities;
        }
        String[] parts=path.split("/",-1);
        if (parts.length<5 || !parts[0].isEmpty() || !parts[1].equals("api") || !parts[2].equals("v1") || !parts[3].equals("graphs"))
            throw new Failure(404,"NOT_FOUND");
        var graphId=new ContentDigest(parts[4]);
        if (parts.length==5) {
            requireMethod(method,"GET"); requireEmpty(body);
            var graph=load(graphId);
            return Map.of("graph",graph.identity(),"snapshot",graph.snapshot(),"contexts",graph.contexts(),"reports",reports.forGraph(graphId));
        }
        if (parts.length==7 && parts[5].equals("queries") && OPERATIONS.contains(parts[6])) {
            requireMethod(method,"POST");
            var input=decode(body,contentType);
            QueryJson.object(input,"request","arguments","page");
            if (input.containsKey("page") && input.get("page")==null) throw new IllegalArgumentException("Page object required");
            var request=QueryJson.request(graphId,input.get("request"));
            var page=QueryJson.page(input.get("page"));
            var args=input.containsKey("arguments") ? GraphJson.object(input.get("arguments")) : Map.<String,Object>of();
            long deadline=System.nanoTime()+limits.queryTimeoutMillis()*1_000_000;
            var guard=ArchitectureQueryService.operationalGuard(cancelled,deadline,8_000_000);
            var service=factory.apply(load(graphId),guard);
            return query(parts[6],service,request,page,args,input.containsKey("page"));
        }
        if ((parts.length==7 || parts.length==8) && parts[5].equals("reports")) {
            var reportId=new ContentDigest(parts[6]);
            if (parts.length==7) { requireMethod(method,"GET"); requireEmpty(body); }
            else if (REPORT_OPERATIONS.contains(parts[7])) requireMethod(method,"POST");
            else throw new Failure(404,"NOT_FOUND");
            var graph=load(graphId);
            var report=reports.get(reportId).filter(r -> r.evaluation().graph().equals(graphId)).orElseThrow(() -> new Failure(404,"REPORT_NOT_FOUND"));
            var partition=graph.contexts().stream().filter(c -> c.identity().equals(report.evaluation().context())).findFirst()
                    .orElseThrow(() -> new Failure(404,"REPORT_NOT_FOUND"));
            if (report.metrics().isPresent() && (!report.metrics().orElseThrow().context().snapshot().equals(partition.snapshot())
                    || !report.metrics().orElseThrow().context().analysis().equals(partition.analysis())))
                throw new Failure(404,"REPORT_NOT_FOUND");
            if (report.evaluation().findings().stream().anyMatch(f -> !f.bundle().snapshot().equals(partition.snapshot())
                    || !f.bundle().analysis().equals(partition.analysis()))) throw new Failure(404,"REPORT_NOT_FOUND");
            if (parts.length==7) return Map.of("identity",reportId,"context",report.evaluation().context(),"evaluation",report.evaluation().identity(),
                    "status",report.evaluation().status(),"gaps",report.evaluation().gaps(),"ruleCount",report.evaluation().rules().size(),
                    "findingCount",report.evaluation().findings().size(),"hasMetrics",report.metrics().isPresent(),"hasAssessment",report.assessment().isPresent());
            var input=decode(body,contentType);
            QueryJson.object(input,"arguments","page");
            if (input.containsKey("page") && input.get("page")==null) throw new IllegalArgumentException("Page object required");
            var args=input.containsKey("arguments") ? GraphJson.object(input.get("arguments")) : Map.<String,Object>of();
            long deadline=System.nanoTime()+limits.queryTimeoutMillis()*1_000_000;
            var guard=ArchitectureQueryService.operationalGuard(cancelled,deadline,8_000_000);
            var service=factory.apply(graph,guard);
            return report(parts[7],reportId,report,args,QueryJson.page(input.get("page")),input.containsKey("page"),service);
        }
        throw new Failure(404,"NOT_FOUND");
    }
    private KnowledgeGraph load(ContentDigest identity) { return store.load(identity).orElseThrow(() -> new Failure(404,"GRAPH_NOT_FOUND")); }
    private Object query(String op,ArchitectureQueryService q,Request r,Page p,Map<String,Object> a,boolean hasPage) {
        boolean paged=Set.of("entities","edges","dependencies","relationships","explain","gaps","spring","configurations","artifacts","metric-catalog","children","realizations").contains(op);
        if (hasPage && !paged) throw new IllegalArgumentException("Page not applicable");
        switch (op) {
            case "entities" -> QueryJson.object(a,"search");
            case "entity","dependencies","expand","reachability","impact","explain","children" -> QueryJson.object(a,"identity");
            case "paths","shortest-path" -> QueryJson.object(a,"source","target");
            case "evidence" -> QueryJson.object(a,"reference");
            case "spring" -> QueryJson.object(a,"kinds");
            case "artifacts" -> QueryJson.object(a,"families");
            case "metric" -> QueryJson.object(a,"metric","subject");
            case "metric-definition" -> QueryJson.object(a,"metric");
            case "comparison-preparation" -> QueryJson.object(a,"graph","context");
            case "visual-graph" -> QueryJson.object(a,"seed","depth");
            default -> QueryJson.object(a);
        }
        return switch (op) {
            case "summary" -> q.summary(r);
            case "provenance" -> q.provenance(r);
            case "entities" -> q.entities(r,a.containsKey("search") ? GraphJson.string(a.get("search")) : "",p);
            case "entity" -> q.entity(r,QueryJson.text(a,"identity"));
            case "children" -> q.children(r,QueryJson.text(a,"identity"),p);
            case "realizations" -> q.realizations(r,p);
            case "edges" -> q.edges(r,p);
            case "dependencies" -> q.dependencies(r,QueryJson.text(a,"identity"),p);
            case "relationships" -> q.relationships(r,p);
            case "expand" -> q.expand(r,QueryJson.text(a,"identity"));
            case "paths" -> q.findPaths(r,QueryJson.text(a,"source"),QueryJson.text(a,"target"));
            case "shortest-path" -> q.shortestPath(r,QueryJson.text(a,"source"),QueryJson.text(a,"target"));
            case "cycles" -> q.findCycles(r);
            case "reachability" -> q.reachability(r,QueryJson.text(a,"identity"));
            case "impact" -> q.impact(r,QueryJson.text(a,"identity"));
            case "evidence" -> q.evidence(r,new ContentDigest(QueryJson.text(a,"reference")));
            case "explain" -> q.explain(r,QueryJson.text(a,"identity"),p);
            case "gaps" -> q.gaps(r,p);
            case "spring" -> q.spring(r,QueryJson.stringsRequired(a,"kinds"),p);
            case "configurations" -> q.configurations(r,p);
            case "artifacts" -> q.artifacts(r,QueryJson.stringsRequired(a,"families"),p);
            case "metric-catalog" -> q.metricCatalog(r,p);
            case "metric-definition" -> q.metricDefinition(r,QueryJson.text(a,"metric"));
            case "metric" -> q.metric(r,QueryJson.text(a,"metric"),QueryJson.text(a,"subject"));
            case "comparison-preparation" -> q.comparisonPreparation(r,load(new ContentDigest(QueryJson.text(a,"graph"))),QueryJson.text(a,"context"));
            case "visual-graph" -> {
                var projection = new GraphProjectionService(q);
                int depth=visualDepth(a);
                if (a.containsKey("seed")) {
                    yield projection.focusedSubgraph(r, QueryJson.text(a, "seed"), depth, Optional.empty());
                }
                yield projection.project(r, Optional.empty());
            }
            default -> throw new Failure(404,"NOT_FOUND");
        };
    }
    private Object report(String op,ContentDigest id,PublishedReports.Report report,Map<String,Object> args,Page page,boolean hasPage,ArchitectureQueryService service) {
        boolean paged=op.equals("rules") || op.equals("findings");
        if (hasPage && !paged) throw new IllegalArgumentException("Page not applicable");
        if (op.equals("finding") || op.equals("bundle") || op.equals("policy-impact")) {
            QueryJson.object(args, "identity");
        } else if (op.equals("replay")) {
            QueryJson.object(args,"identity","counterWitness");
            if(!(args.get("counterWitness") instanceof Boolean))throw new IllegalArgumentException("Explicit witness kind required");
        } else if (op.equals("visual-graph")) {
            QueryJson.object(args, "seed", "depth", "violationsOnly");
            if(args.containsKey("violationsOnly")&&!(args.get("violationsOnly") instanceof Boolean))throw new IllegalArgumentException("Boolean required");
            if(Boolean.TRUE.equals(args.get("violationsOnly"))&&args.containsKey("seed"))throw new IllegalArgumentException("Choose one graph selection");
        } else {
            QueryJson.object(args);
        }
        var e=report.evaluation();
        return switch (op) {
            case "rules" -> reportPage(id,op,page,e.rules().stream().sorted(Comparator.comparing(r -> r.identity())).map(r -> (Object)Map.of("identity",r.identity(),"result",r)).toList(),e);
            case "findings" -> reportPage(id,op,page,e.findings().stream().sorted(Comparator.comparing(PolicyEvaluation.Finding::identity)).map(f -> (Object)Map.of("identity",f.identity(),"key",f.key(),"bundleIdentity",f.bundle().identity(),"result",f)).toList(),e);
            case "finding","bundle" -> {
                var target=new ContentDigest(QueryJson.text(args,"identity"));
                var finding=e.findings().stream().filter(f -> (op.equals("finding") ? f.identity() : f.bundle().identity()).equals(target)).findFirst()
                        .orElseThrow(() -> new Failure(404,"REPORT_ARTIFACT_UNAVAILABLE"));
                yield op.equals("finding") ? finding : finding.bundle();
            }
            case "policy-metrics" -> report.metrics().orElseThrow(() -> new Failure(404,"REPORT_ARTIFACT_UNAVAILABLE"));
            case "replay" -> {
                var target=new ContentDigest(QueryJson.text(args,"identity"));boolean counter=Boolean.TRUE.equals(args.get("counterWitness"));
                var bundle=e.findings().stream().filter(f->f.identity().equals(target)).findFirst()
                        .orElseThrow(()->new Failure(404,"REPORT_ARTIFACT_UNAVAILABLE")).bundle();
                var witness=(counter?bundle.region().counterWitness():bundle.region().witness())
                        .orElseThrow(()->new Failure(404,"REPORT_ARTIFACT_UNAVAILABLE"));
                yield service.replayRuleWitness(bundle.request(),bundle.predicate(),witness,counter);
            }
            case "assessment" -> report.assessment().orElseThrow(() -> new Failure(404,"REPORT_ARTIFACT_UNAVAILABLE"));
            case "policy-impact" -> {
                var target = QueryJson.text(args, "identity");
                var req = new Request(e.graph(), e.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                        Direction.INCOMING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                yield new PolicyImpactService(service).evaluate(req, e, target);
            }
            case "visual-graph" -> {
                var projection = new GraphProjectionService(service);
                int depth=visualDepth(args);
                var req = new Request(e.graph(), e.context(), Scope.TYPE, Projection.BOUNDED_UNION,
                        Direction.OUTGOING, Set.of("graph.depends-on"), Population.all(), false, Optional.empty(), Limits.defaults());
                if (Boolean.TRUE.equals(args.get("violationsOnly"))) {
                    yield projection.violationsSubgraph(req, e);
                }
                if (args.containsKey("seed")) {
                    yield projection.focusedSubgraph(req, QueryJson.text(args, "seed"), depth, Optional.of(e));
                }
                yield projection.project(req, Optional.of(e));
            }
            default -> throw new Failure(404,"NOT_FOUND");
        };
    }
    private static int visualDepth(Map<String,Object> args){
        if(!args.containsKey("depth"))return 1;
        if(!args.containsKey("seed")||!(args.get("depth") instanceof java.math.BigDecimal value))throw new IllegalArgumentException("Depth requires a seed and exact integer");
        int depth;try{depth=value.intValueExact();}catch(ArithmeticException invalid){throw new IllegalArgumentException("Invalid graph depth");}
        if(depth<1||depth>19)throw new IllegalArgumentException("Invalid dependency-step depth");return depth;
    }
    private Object reportPage(ContentDigest report,String op,Page page,List<Object> rows,PolicyEvaluation.Result evaluation) {
        var query=ContentDigest.sha256Utf8(CanonicalJson.write(List.of(VERSION,report,op,page.size())));
        int start=0;
        if (page.cursor().isPresent()) {
            String decoded=new String(Base64.getUrlDecoder().decode(page.cursor().orElseThrow()),StandardCharsets.UTF_8);
            String prefix=query.value()+"|";
            if (!decoded.startsWith(prefix)) throw new IllegalArgumentException("Foreign report cursor");
            start=Integer.parseInt(decoded.substring(prefix.length()));
            if (start<1 || start>=rows.size() || start%page.size()!=0) throw new IllegalArgumentException("Invalid report cursor");
        }
        int end=Math.min(rows.size(),start+page.size());
        Optional<String> cursor=end<rows.size() ? Optional.of(Base64.getUrlEncoder().withoutPadding().encodeToString((query.value()+"|"+end).getBytes(StandardCharsets.UTF_8))) : Optional.empty();
        return Map.of("query",query,"items",rows.subList(start,end),"continuation",cursor,"returnedTotal",rows.size(),
                "evaluationStatus",evaluation.status(),"gaps",evaluation.gaps(),"truncated",cursor.isPresent(),
                "denominatorMeaning","Published observed rows; evaluation gaps may withhold completeness.");
    }
    private Map<String,Object> decode(byte[] bytes,String contentType) {
        if (!jsonMediaType(contentType)) throw new Failure(415,"UNSUPPORTED_MEDIA_TYPE");
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return GraphJson.object(GraphJson.read(text,new GraphJson.Limits(limits.requestBytes(),limits.jsonDepth(),limits.jsonValues())));
        } catch (CharacterCodingException invalid) { throw new IllegalArgumentException("Invalid UTF-8"); }
    }
    static boolean jsonMediaType(String value) {
        if (value==null) return false;
        String normalized=value.toLowerCase(Locale.ROOT).replace(" ","");
        return normalized.equals("application/json") || normalized.equals("application/json;charset=utf-8");
    }
    private static void requireMethod(String actual,String required) { if (!actual.equals(required)) throw new Failure(405,"METHOD_NOT_ALLOWED"); }
    private static void requireEmpty(byte[] bytes) { if (bytes.length!=0) throw new IllegalArgumentException("Body not applicable"); }
}
