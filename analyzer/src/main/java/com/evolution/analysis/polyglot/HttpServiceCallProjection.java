package com.evolution.analysis.polyglot;

import com.evolution.analysis.contract.common.*;
import com.evolution.analysis.contract.identity.SnapshotIdentity;
import com.evolution.analysis.evidence.*;
import com.evolution.analysis.graph.*;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Source URI joins expose possible relationships, preserving endpoint contexts
 * and unresolved routing/activation. They cannot establish a deployed service call.
 */
public final class HttpServiceCallProjection {
    public static final VersionedIdentifier PROVIDER = new VersionedIdentifier("polyglot.http-uri-candidates",
            "m8.4-spring-segments-v4");

    private record Endpoint(KnowledgeGraph.Node node, String path, Set<String> methods, ContentDigest proof,
            boolean recovered, boolean spring, String serviceName) {
    }

    private record GatewayRoute(KnowledgeGraph.Node node, String targetService, String pathPrefix, int stripPrefix,
            String rewriteFrom, String rewriteTo) {
    }

    private record ConfigProp(String key, String value, String target, String port) {
    }

    private HttpServiceCallProjection() {
    }

    public static void project(GraphProjectionStore store, String root, BooleanSupplier cancelled) throws IOException {
        project(store.valuesByKind("nodes", Set.of("ENDPOINT", "ROUTE_CANDIDATE"), GraphSnapshotCodec::node,
                KnowledgeGraph.Node::kind),
                store.valuesByKind("nodes", Set.of("SOURCE_HTTP_CALL_CANDIDATE"), GraphSnapshotCodec::node,
                        KnowledgeGraph.Node::kind),
                store.valuesByKind("nodes", Set.of("SOURCE_GATEWAY_ROUTE"), GraphSnapshotCodec::node,
                        KnowledgeGraph.Node::kind),
                store.valuesByKind("nodes",
                        Set.of("SOURCE_CONFIG_SERVICE_IDENTITY", "BUILD_COMPONENT", "SOURCE_CONFIG_PROPERTY",
                                "SOURCE_INFRA_PORT"),
                        GraphSnapshotCodec::node, KnowledgeGraph.Node::kind),
                store.records("nodes", GraphSnapshotCodec::node), store.records("edges", GraphSnapshotCodec::edge),
                store.records("evidence", GraphSnapshotCodec::evidence),
                store.records("http-projection-additions", GraphSnapshotCodec::node), root, cancelled);
    }

    public static void project(Map<String, KnowledgeGraph.Node> nodes, Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence, String root, BooleanSupplier cancelled) throws IOException {
        project(nodes, edges, evidence, new TreeMap<>(), root, cancelled);
    }

    public static void project(Map<String, KnowledgeGraph.Node> nodes, Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence, Map<String, KnowledgeGraph.Node> additions, String root,
            BooleanSupplier cancelled) throws IOException {
        project(nodes.values(), nodes.values(), nodes.values(), nodes.values(), nodes, edges, evidence, additions, root,
                cancelled);
    }

    private static void project(Iterable<KnowledgeGraph.Node> endpointCandidates,
            Iterable<KnowledgeGraph.Node> callCandidates,
            Iterable<KnowledgeGraph.Node> gatewayNodes, Iterable<KnowledgeGraph.Node> contextNodes,
            Map<String, KnowledgeGraph.Node> nodes, Map<String, KnowledgeGraph.Edge> edges,
            Map<String, GraphEvidence> evidence, Map<String, KnowledgeGraph.Node> additions, String root,
            BooleanSupplier cancelled) throws IOException {
        check(cancelled);

        // Pass 1: collect BUILD_COMPONENT directories so nested multi-module paths use longest-prefix matching.
        var buildDirFallbackNames = new TreeMap<String, String>();
        var deferredIdentities = new ArrayList<KnowledgeGraph.Node>();
        var deferredPorts = new ArrayList<KnowledgeGraph.Node>();
        var deferredProps = new ArrayList<KnowledgeGraph.Node>();
        for (var node : contextNodes) {
            check(cancelled);
            switch (node.kind()) {
                case "BUILD_COMPONENT" -> extractBuildComponentDir(node, evidence, buildDirFallbackNames);
                case "SOURCE_CONFIG_SERVICE_IDENTITY" -> deferredIdentities.add(node);
                case "SOURCE_INFRA_PORT" -> deferredPorts.add(node);
                case "SOURCE_CONFIG_PROPERTY" -> deferredProps.add(node);
                default -> {}
            }
        }
        var sortedBuildDirs = buildDirFallbackNames.keySet().stream()
                .filter(d -> !d.isEmpty() && !".".equals(d))
                .sorted(Comparator.comparingInt(String::length).reversed().thenComparing(Comparator.naturalOrder()))
                .toList();

        var docServiceNames = new HashMap<String, String>();
        var dirServiceNames = new HashMap<String, String>();
        var dirServicePriority = new HashMap<String, Integer>();
        for (var node : deferredIdentities) {
            check(cancelled);
            extractServiceIdentity(node, evidence, sortedBuildDirs, docServiceNames, dirServiceNames, dirServicePriority);
        }
        for (var entry : buildDirFallbackNames.entrySet()) {
            if (!entry.getKey().isEmpty() && !entry.getValue().isEmpty()) {
                dirServiceNames.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        // Index server.port -> unique serviceName for localhost:<port> resolution.
        var portToServices = new HashMap<String, Set<String>>();
        for (var node : deferredPorts) {
            check(cancelled);
            extractServerPort(node, evidence, sortedBuildDirs, dirServiceNames, portToServices);
        }
        var uniquePortToService = new HashMap<String, String>();
        for (var entry : portToServices.entrySet()) {
            if (entry.getValue().size() == 1) {
                uniquePortToService.put(entry.getKey(), entry.getValue().iterator().next());
            }
        }

        // Index CONFIG_PROPERTY facts for @Value("${prop.key}") resolution.
        var dirConfigProps = new HashMap<String, Map<String, ConfigProp>>();
        var repoFileConfigProps = new HashMap<String, Map<String, ConfigProp>>();
        var globalConfigProps = new HashMap<String, List<ConfigProp>>();
        for (var node : deferredProps) {
            check(cancelled);
            extractConfigProperty(node, evidence, sortedBuildDirs, dirConfigProps, repoFileConfigProps, globalConfigProps);
        }

        var gateways = new ArrayList<GatewayRoute>();
        for (var node : gatewayNodes) {
            check(cancelled);
            if (!node.kind().equals("SOURCE_GATEWAY_ROUTE"))
                continue;
            extractGatewayRoute(node, evidence).ifPresent(gateways::add);
        }
        var providers = new TreeMap<String, List<Endpoint>>();
        // Only URI endpoint descriptors are retained, never the complete syntax population.
        for (var node : endpointCandidates) {
            check(cancelled);
            if (!Set.of("ENDPOINT", "ROUTE_CANDIDATE").contains(node.kind()))
                continue;
            var endpoint = descriptor(node, evidence, sortedBuildDirs, dirServiceNames, uniquePortToService,
                    dirConfigProps, repoFileConfigProps, globalConfigProps);
            if (endpoint.isEmpty())
                continue;
            var value = endpoint.orElseThrow();
            providers.computeIfAbsent(bucket(value.path()), key -> new ArrayList<>()).add(value);
        }
        // Delay added nodes until iteration finishes (in-memory maps also remain valid).
        for (var node : callCandidates) {
            check(cancelled);
            if (!node.kind().equals("SOURCE_HTTP_CALL_CANDIDATE"))
                continue;
            var source = descriptor(node, evidence, sortedBuildDirs, dirServiceNames, uniquePortToService,
                    dirConfigProps, repoFileConfigProps, globalConfigProps);
            if (source.isEmpty())
                continue;
            var caller = source.orElseThrow();
            String from = node.identity();
            if (!node.context().equals(root)) {
                from = KnowledgeGraph.derivedIdentity("http-call-reference", List.of(node.context(), node.identity()));
                var reference = GraphEvidence.capture("polyglot.http-call-reference",
                        Map.of("context", node.context(), "call", node.identity(), "callEvidence", caller.proof()));
                evidence.putIfAbsent(reference.identity().value(), reference);
                additions.put(root + "|" + from, new KnowledgeGraph.Node(from, root, "HTTP_CALL_REFERENCE",
                        List.of(reference.identity(), caller.proof())));
            }
            var candidates = new TreeMap<String, Endpoint>();
            long unsupportedPatterns = 0;
            Optional<String> requestedHost = HttpUriPattern.host(caller.path());
            if (requestedHost.isEmpty() && !caller.serviceName().isEmpty()) {
                requestedHost = Optional.of(caller.serviceName());
            }
            if (requestedHost.isEmpty()) {
                requestedHost = loopbackPort(caller.path())
                        .map(uniquePortToService::get)
                        .filter(s -> s != null && !s.isBlank());
            }
            String matchedGatewayRoute = "";
            String rewrittenGatewayPath = "";
            var path = HttpUriPattern.candidatePath(caller.path());
            if (path.isPresent() && !caller.recovered()) {
                matchEndpoints(path.orElseThrow(), requestedHost, caller, node, providers, candidates, false,
                        cancelled);
                // F3: Gateway StripPrefix / RewritePath matching when direct request hits gateway route
                for (GatewayRoute gw : gateways) {
                    Optional<String> rewritten = rewriteWithGateway(path.orElseThrow(), gw);
                    if (rewritten.isPresent()) {
                        Optional<String> gwHost = gw.targetService().isEmpty() ? Optional.empty()
                                : Optional.of(normalizeSvc(gw.targetService()));
                        int before = candidates.size();
                        matchEndpoints(rewritten.orElseThrow(), gwHost, caller, node, providers, candidates, false,
                                cancelled);
                        if (candidates.size() > before) {
                            matchedGatewayRoute = gw.targetService();
                            rewrittenGatewayPath = rewritten.orElseThrow();
                        }
                    }
                }
                if (candidates.isEmpty()) {
                    // Check for unsupported patterns accurately for diagnostics
                    unsupportedPatterns = matchEndpoints(path.orElseThrow(), requestedHost, caller, node, providers,
                            candidates, true, cancelled);
                }
            }
            var targets = new ArrayList<String>();
            var supports = new TreeSet<ContentDigest>();
            supports.add(caller.proof());
            for (var target : candidates.values()) {
                String id = target.node().identity();
                if (!target.node().context().equals(root)) {
                    id = KnowledgeGraph.derivedIdentity("http-endpoint-reference",
                            List.of(target.node().context(), id));
                    var reference = GraphEvidence.capture("polyglot.http-endpoint-reference",
                            Map.of("context", target.node().context(), "endpoint", target.node().identity(),
                                    "declarationEvidence", target.proof()));
                    evidence.putIfAbsent(reference.identity().value(), reference);
                    additions.put(root + "|" + id, new KnowledgeGraph.Node(id, root, "ENDPOINT_REFERENCE",
                            List.of(reference.identity(), target.proof())));
                }
                targets.add(id);
                supports.add(target.proof());
            }
            String reason = path.isEmpty() || caller.recovered() ? "HTTP_URI_UNSUPPORTED"
                    : unsupportedPatterns > 0 ? "HTTP_ROUTE_PATTERN_UNSUPPORTED"
                            : targets.isEmpty() ? "HTTP_URI_NO_CANDIDATE" : "HTTP_ROUTING_EVIDENCE_MISSING";
            var proofEntries = new LinkedHashMap<String, Object>();
            proofEntries.put("provider", PROVIDER);
            proofEntries.put("source", caller.node().identity());
            proofEntries.put("sourceContext", caller.node().context());
            proofEntries.put("path", caller.path());
            proofEntries.put("methods", new TreeSet<>(caller.methods()));
            proofEntries.put("candidates", List.copyOf(candidates.keySet()));
            proofEntries.put("sourceEvidence", List.copyOf(supports));
            proofEntries.put("certainty", "CANDIDATE");
            proofEntries.put("runtimeObserved", false);
            proofEntries.put("routingEstablished", false);
            proofEntries.put("unsupportedProviderPatterns", unsupportedPatterns);
            proofEntries.put("outcome", reason);
            if (!matchedGatewayRoute.isEmpty()) {
                proofEntries.put("gatewayRoute", matchedGatewayRoute);
                proofEntries.put("rewrittenPath", rewrittenGatewayPath);
            }
            var proof = GraphEvidence.capture("polyglot.http-uri-match", proofEntries);
            evidence.putIfAbsent(proof.identity().value(), proof);
            supports.add(proof.identity());
            if (!targets.isEmpty()) {
                var target = new KnowledgeGraph.Target(targets.size() == 1 ? KnowledgeGraph.TargetState.RESOLVED
                        : KnowledgeGraph.TargetState.CANDIDATES, targets, Optional.empty());
                String id = KnowledgeGraph.derivedIdentity("http-service-call-candidate",
                        List.of(PROVIDER, root, node.identity(), target, proof.identity()));
                edges.put(root + "|" + id, new KnowledgeGraph.Edge(id, root, "polyglot.service-call.candidate", from,
                        target, List.copyOf(supports), Optional.empty()));
            }
            var subject = new EvidenceSubject(EvidenceSubject.Kind.OBSERVATION, proof.identity().value());
            var requirement = new EvidenceRequirement(EvidenceRequirement.Kind.CONFIGURATION, "polyglot.http-routing",
                    List.of(subject), EvidenceRequirement.AuthorizationClass.PASSIVE,
                    List.of("Acquire exact host/base URL, context path, gateway rewrite, route predicates and activation before establishing a service-call binding."));
            var gap = CapabilityGapRecord.create(EvidenceContext.forSnapshot(new SnapshotIdentity(root)), PROVIDER,
                    "polyglot.service-call", reason, subject, List.of(),
                    List.of(ProviderObservationReference.create(PROVIDER, "polyglot.http-uri-match", caller.proof(),
                            proof.identity())),
                    List.of(requirement), List.of(),
                    List.of(new AffectedOutput(AffectedOutput.Kind.ANALYSIS_COVERAGE, "polyglot.service-call")),
                    List.of(), List.of(),
                    List.of("URI matches enumerate candidates; absence is not proof that a service is unreachable."));
            var gapProof = GraphEvidence.capture("evidence.gap", gap);
            evidence.putIfAbsent(gapProof.identity().value(), gapProof);
            additions.put(root + "|" + gap.gapIdentity().value(), new KnowledgeGraph.Node(gap.gapIdentity().value(),
                    root, "CAPABILITY_GAP", List.of(gapProof.identity())));
        }
        nodes.putAll(additions);
    }

    private static long matchEndpoints(String path, Optional<String> requestedHost, Endpoint caller,
            KnowledgeGraph.Node node,
            Map<String, List<Endpoint>> providers, Map<String, Endpoint> candidates, boolean countUnsupportedOnly,
            BooleanSupplier cancelled) throws IOException {
        var group = new ArrayList<Endpoint>(providers.getOrDefault(bucket(path), List.of()));
        if (!bucket(path).equals("*"))
            group.addAll(providers.getOrDefault("*", List.of()));
        else
            for (var entry : providers.entrySet())
                if (!entry.getKey().equals("*"))
                    group.addAll(entry.getValue());
        List<Endpoint> matched = new ArrayList<>();
        long unsupportedPatterns = 0;
        for (var target : group) {
            check(cancelled);
            if (target.recovered() || target.node().identity().equals(node.identity()))
                continue;
            if (!caller.methods().isEmpty() && !target.methods().isEmpty()
                    && Collections.disjoint(caller.methods(), target.methods()))
                continue;
            var match = target.spring() ? HttpUriPattern.springCandidateMatch(path, target.path())
                    : HttpUriPattern.candidateMatch(path, target.path());
            if (match == HttpUriPattern.Match.MATCH)
                matched.add(target);
            else if (match == HttpUriPattern.Match.UNSUPPORTED)
                unsupportedPatterns++;
        }
        if (countUnsupportedOnly || matched.isEmpty())
            return unsupportedPatterns;
        if (requestedHost.isPresent()) {
            String wanted = normalizeSvc(requestedHost.orElseThrow());
            var hostFiltered = matched.stream()
                    .filter(t -> !t.serviceName().isEmpty() && normalizeSvc(t.serviceName()).equals(wanted)).toList();
            if (!hostFiltered.isEmpty())
                matched = hostFiltered;
        }
        for (var target : matched)
            candidates.put(target.node().context() + "|" + target.node().identity(), target);
        return unsupportedPatterns;
    }

    private static Optional<String> rewriteWithGateway(String requestPath, GatewayRoute gw) {
        if (!gw.pathPrefix().isEmpty()) {
            String prefix = gw.pathPrefix();
            if (prefix.endsWith("/**"))
                prefix = prefix.substring(0, prefix.length() - 3);
            else if (prefix.endsWith("/*"))
                prefix = prefix.substring(0, prefix.length() - 2);
            if (!prefix.startsWith("/"))
                prefix = "/" + prefix;
            if (!(requestPath.equals(prefix) || requestPath.startsWith(prefix + "/") || prefix.equals("/"))) {
                return Optional.empty();
            }
            if (gw.stripPrefix() > 0) {
                String[] segs = requestPath.split("/", -1);
                if (segs.length - 1 > gw.stripPrefix()) {
                    var sb = new StringBuilder();
                    for (int i = gw.stripPrefix() + 1; i < segs.length; i++) {
                        sb.append('/').append(segs[i]);
                    }
                    return Optional.of(sb.toString());
                }
                return Optional.of("/");
            }
        }
        if (!gw.rewriteFrom().isEmpty() && !gw.rewriteTo().isEmpty()
                && gw.rewriteFrom().length() <= 128 && gw.rewriteTo().length() <= 128) {
            try {
                String replacement = gw.rewriteTo().replaceAll("\\$\\\\?\\{[^}]+}", "\\$1");
                String rewritten = requestPath.replaceFirst(gw.rewriteFrom(), replacement);
                if (!rewritten.equals(requestPath) && rewritten.startsWith("/")) {
                    return Optional.of(rewritten);
                }
            } catch (RuntimeException ignored) {
                // Invalid gateway regex stays safely ignored rather than failing scan projection.
            }
        }
        return gw.pathPrefix().isEmpty() ? Optional.empty() : Optional.of(requestPath);
    }

    private static void extractBuildComponentDir(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            Map<String, String> buildDirFallbackNames) {
        for (var id : node.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null || !proof.family().equals("polyglot.build-component"))
                continue;
            var fields = GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults()));
            String rawDir = fields.get("directory") instanceof String s ? s : "";
            String norm = normDir(rawDir);
            String compId = fields.get("id") instanceof String s ? s : norm;
            if (!norm.isEmpty()) {
                buildDirFallbackNames.putIfAbsent(norm, baseOrDirName(norm, compId));
            }
        }
    }

    private static void extractServiceIdentity(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            List<String> sortedBuildDirs, Map<String, String> docServiceNames, Map<String, String> dirServiceNames,
            Map<String, Integer> dirServicePriority) {
        for (var id : node.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax"))
                continue;
            var fields = GraphJson.object(
                    GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            String path = decodePath(fields.get("path"));
            if (isTestSource(attrs, path) || !str(attrs.get("configRepoFile")).isEmpty())
                continue;
            String svc = attrs.get("serviceName") instanceof String s && !s.isBlank()
                    ? s.trim() : GraphJson.string(fact.get("name")).trim();
            if (svc.isEmpty() || svc.contains("${"))
                continue;
            String rootDir = resolveServiceDir(path, sortedBuildDirs);
            int priority = configIdentityPriority(path);
            if (!rootDir.isEmpty()) {
                int existingPrio = dirServicePriority.getOrDefault(rootDir, Integer.MIN_VALUE);
                if (priority > existingPrio) {
                    dirServiceNames.put(rootDir, svc);
                    dirServicePriority.put(rootDir, priority);
                }
            }
            if (fields.get("document") instanceof String doc)
                docServiceNames.putIfAbsent(doc, svc);
        }
    }

    private static void extractServerPort(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            List<String> sortedBuildDirs, Map<String, String> dirServiceNames,
            Map<String, Set<String>> portToServices) {
        for (var id : node.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax"))
                continue;
            var fields = GraphJson.object(
                    GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            String nodeKind = str(fact.get("nodeKind"));
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            if (!"spring_server_port".equals(nodeKind) && !"spring-config".equals(attrs.get("spec")))
                continue;
            String path = decodePath(fields.get("path"));
            if (isTestSource(attrs, path))
                continue;
            String port = str(attrs.get("port"));
            if (port.isEmpty()) port = str(fact.get("name"));
            if (!port.matches("\\d+")) continue;
            String configRepoFile = str(attrs.get("configRepoFile"));
            String svcName;
            if (!configRepoFile.isEmpty()) {
                if ("application".equalsIgnoreCase(configRepoFile)
                        || configRepoFile.toLowerCase(Locale.ROOT).startsWith("application-")) {
                    continue;
                }
                svcName = configRepoFile;
            } else {
                String dir = resolveServiceDir(path, sortedBuildDirs);
                svcName = dirServiceNames.getOrDefault(dir, "");
            }
            if (!svcName.isEmpty()) {
                portToServices.computeIfAbsent(port, k -> new TreeSet<>()).add(normalizeSvc(svcName));
            }
        }
    }

    private static void extractConfigProperty(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            List<String> sortedBuildDirs, Map<String, Map<String, ConfigProp>> dirConfigProps,
            Map<String, Map<String, ConfigProp>> repoFileConfigProps,
            Map<String, List<ConfigProp>> globalConfigProps) {
        for (var id : node.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax"))
                continue;
            var fields = GraphJson.object(
                    GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
            String path = decodePath(fields.get("path"));
            if (isTestSource(attrs, path))
                continue;
            String key = str(attrs.get("key"));
            String value = str(attrs.get("value"));
            String target = str(attrs.get("target"));
            String port = str(attrs.get("port"));
            if (key.isEmpty() || (value.isEmpty() && target.isEmpty()))
                continue;
            var cp = new ConfigProp(key, value, target, port);
            String configRepoFile = str(attrs.get("configRepoFile"));
            if (!configRepoFile.isEmpty()) {
                repoFileConfigProps.computeIfAbsent(normalizeSvc(configRepoFile), k -> new TreeMap<>())
                        .putIfAbsent(key, cp);
            } else {
                String dir = resolveServiceDir(path, sortedBuildDirs);
                if (!dir.isEmpty()) {
                    dirConfigProps.computeIfAbsent(dir, k -> new TreeMap<>()).putIfAbsent(key, cp);
                }
            }
            globalConfigProps.computeIfAbsent(key, k -> new ArrayList<>()).add(cp);
        }
    }

    private static Optional<GatewayRoute> extractGatewayRoute(KnowledgeGraph.Node node,
            Map<String, GraphEvidence> evidence) {
        for (var id : node.evidence()) {
            var proof = evidence.get(id.value());
            if (proof == null || !proof.family().equals("polyglot.source-syntax"))
                continue;
            var fields = GraphJson.object(
                    GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
            var fact = GraphJson.object(fields.get("fact"));
            if (!(fact.get("attributes") instanceof Map<?, ?> rawAttrs))
                continue;
            var attrs = GraphJson.object(rawAttrs);
            String path = decodePath(fields.get("path"));
            if (isTestSource(attrs, path))
                continue;
            String targetSvc = attrs.get("targetService") instanceof String s ? s : "";
            String pathPrefix = attrs.get("pathPrefix") instanceof String s ? s : "";
            int strip = 0;
            if (attrs.get("stripPrefix") instanceof String s) {
                try {
                    strip = Integer.parseInt(s);
                } catch (NumberFormatException ignored) {
                }
            }
            String rewriteFrom = attrs.get("rewriteFrom") instanceof String s ? s : "";
            String rewriteTo = attrs.get("rewriteTo") instanceof String s ? s : "";
            if (!targetSvc.isEmpty() || !pathPrefix.isEmpty()) {
                return Optional.of(new GatewayRoute(node, targetSvc, pathPrefix, strip, rewriteFrom, rewriteTo));
            }
        }
        return Optional.empty();
    }

    private static Optional<Endpoint> descriptor(KnowledgeGraph.Node node, Map<String, GraphEvidence> evidence,
            List<String> sortedBuildDirs, Map<String, String> dirServiceNames,
            Map<String, String> uniquePortToService,
            Map<String, Map<String, ConfigProp>> dirConfigProps,
            Map<String, Map<String, ConfigProp>> repoFileConfigProps,
            Map<String, List<ConfigProp>> globalConfigProps) {
        for (var support : node.evidence()) {
            var proof = evidence.get(support.value());
            if (proof == null)
                continue;
            if (proof.family().equals("java.http-client-call")) {
                var call = GraphJson.object(GraphJson
                        .object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("call"));
                var methods = new TreeSet<String>();
                GraphJson.array(call.get("methods")).forEach(m -> methods.add(GraphJson.string(m)));
                return Optional
                        .of(new Endpoint(node, GraphJson.string(call.get("uri")), methods, support, false, false, ""));
            }
            if (node.kind().equals("ENDPOINT")
                    && Set.of("spring.endpoint-declaration", "jaxrs.endpoint-declaration",
                            "servlet.endpoint-declaration").contains(proof.family())) {
                var raw = GraphJson.object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults()));
                var route = GraphJson.object(raw.get("route"));
                var methods = new TreeSet<String>();
                GraphJson.array(route.get("methods")).forEach(m -> methods.add(GraphJson.string(m)));
                if (Boolean.TRUE.equals(route.get("implicitHead")))
                    methods.add("HEAD");
                String svcName = "";
                if (route.get("module") instanceof String mod && !mod.isBlank())
                    svcName = mod;
                else if (raw.get("module") instanceof String topMod && !topMod.isBlank())
                    svcName = topMod;
                return Optional.of(new Endpoint(node, GraphJson.string(route.get("path")), methods, support, false,
                        true, svcName));
            }
            if (proof.family().equals("polyglot.source-syntax")) {
                var fields = GraphJson.object(GraphJson
                        .object(GraphJson.read(proof.canonicalJson(), GraphJson.Limits.defaults())).get("fields"));
                var fact = GraphJson.object(fields.get("fact"));
                var attrs = fact.get("attributes") instanceof Map<?, ?> m ? GraphJson.object(m) : Map.<String, Object>of();
                String normPath = decodePath(fields.get("path"));
                if (isTestSource(attrs, normPath))
                    return Optional.empty();
                String raw = GraphJson.string(fact.get("name"));
                if (raw.length() < 2 || raw.indexOf('\\') >= 0 || raw.charAt(raw.length() - 1) != raw.charAt(0)
                        || raw.charAt(0) != '\'' && raw.charAt(0) != '"' && raw.charAt(0) != '`')
                    continue;
                String uri = raw.substring(1, raw.length() - 1);
                String method = attrs.get("method") instanceof String text ? text : "UNKNOWN";
                String svcName = attrs.get("targetService") instanceof String svc ? svc : "";
                String propertyKey = str(attrs.get("propertyKey"));
                String serviceDir = resolveServiceDir(normPath, sortedBuildDirs);
                if (node.kind().equals("SOURCE_HTTP_CALL_CANDIDATE") && !propertyKey.isEmpty()) {
                    String callerSvc = dirServiceNames.getOrDefault(serviceDir, "");
                    ConfigProp prop = resolveConfigProperty(propertyKey, serviceDir, callerSvc,
                            dirConfigProps, repoFileConfigProps, globalConfigProps);
                    if (prop != null) {
                        String propVal = prop.value().trim();
                        if (!propVal.isEmpty()) {
                            if (uri.isEmpty()) {
                                uri = propVal;
                            } else if (!hasUriScheme(uri)) {
                                uri = joinBaseAndPath(propVal, uri);
                            }
                        }
                        if (svcName.isEmpty()) {
                            Optional<String> h = HttpUriPattern.host(uri);
                            if (h.isPresent()) {
                                svcName = h.orElseThrow();
                            } else if (!prop.target().isEmpty() && !isLoopback(prop.target())) {
                                svcName = prop.target();
                            } else {
                                String port = loopbackPort(uri).orElse(prop.port());
                                if (!port.isEmpty()) {
                                    String resolved = uniquePortToService.get(port);
                                    if (resolved != null) svcName = resolved;
                                }
                            }
                        }
                    }
                }
                if (svcName.isEmpty() && node.kind().equals("SOURCE_HTTP_CALL_CANDIDATE")) {
                    Optional<String> port = loopbackPort(uri);
                    if (port.isPresent()) {
                        String resolved = uniquePortToService.get(port.orElseThrow());
                        if (resolved != null) svcName = resolved;
                    }
                }
                if (svcName.isEmpty() && !node.kind().equals("SOURCE_HTTP_CALL_CANDIDATE")) {
                    svcName = dirServiceNames.getOrDefault(serviceDir, "");
                }
                return Optional.of(new Endpoint(node, uri,
                        method.equals("UNKNOWN") ? Set.of() : Set.of(method), support,
                        Boolean.TRUE.equals(fact.get("recovered")), false, svcName));
            }
        }
        return Optional.empty();
    }

    private static ConfigProp resolveConfigProperty(String propertyKey, String serviceDir, String callerSvc,
            Map<String, Map<String, ConfigProp>> dirConfigProps,
            Map<String, Map<String, ConfigProp>> repoFileConfigProps,
            Map<String, List<ConfigProp>> globalConfigProps) {
        if (!serviceDir.isEmpty()) {
            ConfigProp local = dirConfigProps.getOrDefault(serviceDir, Map.of()).get(propertyKey);
            if (local != null) return local;
        }
        if (!callerSvc.isEmpty()) {
            ConfigProp fromSvcRepo = repoFileConfigProps.getOrDefault(normalizeSvc(callerSvc), Map.of()).get(propertyKey);
            if (fromSvcRepo != null) return fromSvcRepo;
        }
        ConfigProp fromAppRepo = repoFileConfigProps.getOrDefault("application", Map.of()).get(propertyKey);
        if (fromAppRepo != null) return fromAppRepo;
        List<ConfigProp> all = globalConfigProps.getOrDefault(propertyKey, List.of());
        if (all.size() == 1) return all.getFirst();
        if (all.size() > 1) {
            String firstVal = all.getFirst().value();
            if (all.stream().allMatch(c -> c.value().equals(firstVal))) {
                return all.getFirst();
            }
        }
        return null;
    }

    private static Optional<String> loopbackPort(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) return Optional.empty();
        try {
            var uri = URI.create(rawUri.replace("{", "%7B").replace("}", "%7D"));
            if (!uri.isAbsolute() || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                return Optional.empty();
            }
            String host = uri.getHost();
            if (host == null || !isLoopback(host)) return Optional.empty();
            int port = uri.getPort();
            return port > 0 ? Optional.of(Integer.toString(port)) : Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT).trim();
        return Set.of("localhost", "127.0.0.1", "0.0.0.0").contains(lower);
    }

    private static boolean hasUriScheme(String uri) {
        String lower = uri.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("lb://");
    }

    private static String joinBaseAndPath(String base, String rel) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String r = rel.startsWith("/") ? rel : "/" + rel;
        return b + r;
    }

    private static int configIdentityPriority(String path) {
        String lower = path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean mainRes = lower.contains("/src/main/resources/") || lower.startsWith("src/main/resources/");
        int slash = lower.lastIndexOf('/');
        String base = slash >= 0 ? lower.substring(slash + 1) : lower;
        boolean baseFile = base.equals("bootstrap.yml") || base.equals("bootstrap.yaml")
                || base.equals("bootstrap.properties") || base.equals("application.yml")
                || base.equals("application.yaml") || base.equals("application.properties");
        return (mainRes ? 10 : 0) + (baseFile ? 5 : 0);
    }

    private static boolean isTestSource(Map<String, Object> attrs, String normPath) {
        if ("true".equalsIgnoreCase(str(attrs.get("testSource"))))
            return true;
        if (normPath == null || normPath.isEmpty())
            return false;
        String lower = normPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        return lower.startsWith("src/test/") || lower.contains("/src/test/");
    }

    private static String str(Object raw) {
        return raw instanceof String s ? s.trim() : "";
    }

    private static String decodePath(Object raw) {
        if (!(raw instanceof String encoded) || encoded.isBlank())
            return "";
        try {
            return new String(Base64.getDecoder().decode(encoded), java.nio.charset.StandardCharsets.UTF_8)
                    .replace('\\', '/');
        } catch (IllegalArgumentException plain) {
            return encoded.replace('\\', '/');
        }
    }

    private static String resolveServiceDir(String normPath, List<String> sortedBuildDirs) {
        if (normPath == null || normPath.isBlank())
            return "";
        String clean = normPath.replace('\\', '/');
        while (clean.startsWith("./")) clean = clean.substring(2);
        for (String bdir : sortedBuildDirs) {
            if (clean.equals(bdir) || clean.startsWith(bdir + "/")) {
                return bdir;
            }
        }
        return topServiceDir(clean);
    }

    private static String topServiceDir(String normPath) {
        if (normPath == null || normPath.isBlank())
            return "";
        int slash = normPath.indexOf('/');
        if (slash <= 0)
            return "";
        String first = normPath.substring(0, slash);
        if (Set.of("src", "services", "apps", "packages", "modules", "microservices")
                .contains(first.toLowerCase(Locale.ROOT))) {
            int next = normPath.indexOf('/', slash + 1);
            return next > slash ? normPath.substring(0, next) : first;
        }
        return first;
    }

    private static String normDir(String dir) {
        if (dir == null) return "";
        String norm = dir.replace('\\', '/').trim();
        while (norm.startsWith("./")) norm = norm.substring(2);
        while (norm.endsWith("/") && norm.length() > 1) norm = norm.substring(0, norm.length() - 1);
        return norm.equals(".") ? "" : norm;
    }

    private static String baseOrDirName(String dir, String fallbackId) {
        String norm = normDir(dir);
        if (!norm.isEmpty()) {
            int slash = norm.lastIndexOf('/');
            return slash >= 0 ? norm.substring(slash + 1) : norm;
        }
        if (fallbackId == null) return "";
        int colon = fallbackId.lastIndexOf(':');
        return colon >= 0 ? fallbackId.substring(colon + 1) : fallbackId;
    }

    private static String normalizeSvc(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replace("_", "-").trim();
    }

    private static String bucket(String path) {
        String[] parts = path.split("/", -1);
        String first = parts.length > 1 ? parts[1] : "";
        return first.startsWith("{") || first.startsWith(":") || first.contains("*") ? "*" : first;
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("HTTP_PROJECTION_CANCELLED");
    }
}
