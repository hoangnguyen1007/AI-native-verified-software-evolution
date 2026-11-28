package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Passive, import-verified Java source-syntax extractor for Spring Cloud HTTP clients
 * (@FeignClient, OAuth2RestTemplate, RestTemplate, RestClient, WebClient, DiscoveryClient,
 * LoadBalancerClient), Spring messaging (@RabbitListener, @KafkaListener, @StreamListener,
 * RabbitTemplate, KafkaTemplate), and Spring Cloud control-plane roles when compiling with
 * {@code --build=off} leaves dependencies outside exact semantic closure.
 */
public final class JavaServiceSyntaxExtractor {
    public static final String PROVIDER = "source.javaparser-service-syntax:m8.4-v1";

    private static final List<String> MARKERS = List.of(
            "FeignClient", "OAuth2RestTemplate", "OAuth2RestOperations",
            "RestClient", "RestTemplate", "RestOperations", "WebClient",
            "WebTarget", "ClientBuilder",
            "DiscoveryClient", "LoadBalancerClient",
            "RabbitListener", "KafkaListener", "StreamListener",
            "RabbitTemplate", "AmqpTemplate", "KafkaTemplate",
            "JavaMailSender",
            "EnableZuulProxy", "EnableEurekaServer", "EnableEurekaClient",
            "EnableDiscoveryClient", "EnableConfigServer", "EnableBinding",
            "EnableTurbineStream", "EnableTurbineAmqp", "EnableZipkinStreamServer",
            "EnableAdminServer", "EnableHystrixDashboard", "EnableTurbine",
            "EnableCircuitBreaker", "EnableHystrix",
            "EnableZipkinServer", "EnableResourceServer", "EnableAuthorizationServer",
            "EnableOAuth2Sso", "EnableOAuth2Resource");

    private static final Map<String, String> ANNOTATION_SPRING_ROLE = Map.ofEntries(
            Map.entry("EnableZuulProxy", "ZUUL_PROXY"),
            Map.entry("EnableEurekaServer", "EUREKA_SERVER"),
            Map.entry("EnableEurekaClient", "EUREKA_CLIENT"),
            Map.entry("EnableDiscoveryClient", "DISCOVERY_CLIENT"),
            Map.entry("EnableConfigServer", "CONFIG_SERVER"),
            Map.entry("EnableBinding", "STREAM_BINDING"),
            Map.entry("EnableTurbineStream", "TURBINE_STREAM"),
            Map.entry("EnableTurbineAmqp", "TURBINE_STREAM"),
            Map.entry("EnableZipkinStreamServer", "ZIPKIN_STREAM_SERVER"),
            Map.entry("EnableAdminServer", "ADMIN_SERVER"),
            Map.entry("EnableHystrixDashboard", "HYSTRIX_DASHBOARD"),
            Map.entry("EnableTurbine", "TURBINE"),
            Map.entry("EnableCircuitBreaker", "CIRCUIT_BREAKER"),
            Map.entry("EnableHystrix", "CIRCUIT_BREAKER"),
            Map.entry("EnableZipkinServer", "ZIPKIN_SERVER"),
            Map.entry("EnableResourceServer", "OAUTH2_RESOURCE"),
            Map.entry("EnableOAuth2Resource", "OAUTH2_RESOURCE"),
            Map.entry("EnableAuthorizationServer", "OAUTH2_AUTH_SERVER"),
            Map.entry("EnableOAuth2Sso", "OAUTH2_SSO"));

    private record ValueProperty(String propertyKey, String defaultValue) {}

    private record ClientBaseSpec(String baseUri, String propertyKey) {}

    private static final Map<String, String> MAPPING_HTTP_METHODS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH");

    private static final Set<String> REST_TEMPLATE_EXACT = Set.of(
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.client.RestOperations",
            "org.springframework.security.oauth2.client.OAuth2RestTemplate",
            "org.springframework.security.oauth2.client.OAuth2RestOperations");

    private static final Set<String> CLIENT_PREFIXES = Set.of(
            "org.springframework.web.client.",
            "org.springframework.web.reactive.function.client.",
            "org.springframework.security.oauth2.client.",
            "org.springframework.cloud.client.",
            "org.springframework.cloud.openfeign.",
            "org.springframework.cloud.netflix.");

    private static final Set<String> REST_CALL_METHODS = Set.of(
            "getForObject", "getForEntity", "postForObject", "postForEntity", "postForLocation",
            "put", "patchForObject", "delete", "headForHeaders", "optionsForAllow", "exchange", "uri");

    private static final Set<String> MESSAGING_ANNOTATION_PACKAGES = Set.of(
            "org.springframework.amqp.rabbit.annotation.",
            "org.springframework.kafka.annotation.",
            "org.springframework.cloud.stream.annotation.");

    private static final Set<String> MESSAGING_TEMPLATE_TYPES = Set.of(
            "org.springframework.amqp.rabbit.core.RabbitTemplate",
            "org.springframework.amqp.core.AmqpTemplate",
            "org.springframework.kafka.core.KafkaTemplate");

    private static final ParserConfiguration SHARED_PARSER_CONFIG = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
            .setStoreTokens(true)
            .setTabSize(1);

    private static final List<String> KNOWN_CLIENT_INITS = List.of(
            "RestClient", "WebClient", "RestTemplate", "RestOperations",
            "OAuth2RestTemplate", "OAuth2RestOperations",
            "WebTarget", "Client", "ClientBuilder");

    private static volatile String cachedToolIdentity;

    public static boolean candidate(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        String text = new String(bytes, 0, Math.min(bytes.length, 512_000), StandardCharsets.ISO_8859_1);
        for (String marker : MARKERS) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    public JavaGrpcSourceFrontend.Summary parse(byte[] bytes, JavaGrpcSourceFrontend.Sink sink,
            BooleanSupplier cancelled) throws IOException {
        check(cancelled);
        String charset = bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254 ? "UTF-16LE"
                : bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255 ? "UTF-16BE" : "UTF-8";
        var coordinates = new SourceCoordinates(bytes, charset);
        String text = coordinates.text();
        if (text.contains("\\u")) {
            return new JavaGrpcSourceFrontend.Summary(0, 0, 0, PROVIDER, toolIdentity());
        }
        String input = text.startsWith("\ufeff") ? " " + text.substring(1) : text;
        var parser = new JavaParser(SHARED_PARSER_CONFIG);
        var parsed = parser.parse(input);
        if (parsed.getResult().isEmpty()) {
            return new JavaGrpcSourceFrontend.Summary(0, 0, parsed.getProblems().size(), PROVIDER, toolIdentity());
        }
        CompilationUnit unit = parsed.getResult().orElseThrow();
        boolean recovered = !parsed.isSuccessful();
        ImportIndex imports = ImportIndex.from(unit);
        long[] counts = { 0L, 0L };

        for (var typeDecl : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            check(cancelled);
            String ownerSlot = slot(typeDecl, coordinates);
            Map<String, String> typeConstants = collectTypeConstants(typeDecl, imports);
            Map<String, ValueProperty> typeValueProps = collectValueProperties(typeDecl, typeConstants, imports);
            Map<String, String> typePropKeys = new LinkedHashMap<>();
            typeValueProps.forEach((fieldName, vp) -> {
                typePropKeys.put(fieldName, vp.propertyKey());
                if (!vp.defaultValue().isEmpty()) {
                    typeConstants.putIfAbsent(fieldName, vp.defaultValue());
                }
            });
            Map<String, ClientBaseSpec> typeClientBases = collectTypeClientBases(
                    typeDecl, typeConstants, typePropKeys, imports);
            Map<String, String> typeFieldVarTypes = collectTypeFieldVarTypes(typeDecl, imports);

            // 1. Control-plane role annotations
            for (AnnotationExpr ann : typeDecl.getAnnotations()) {
                String simple = simpleName(ann.getNameAsString());
                String role = ANNOTATION_SPRING_ROLE.get(simple);
                if (role != null && isSpringAnnotation(ann, imports, allowedRoleAnnotationPrefixes(simple))) {
                    emit(ann, "SPRING_CLOUD_ROLE", "annotation", role, ownerSlot,
                            Map.of("role", role, "annotation", ann.getNameAsString(), "spec", "spring-cloud"),
                            coordinates, recovered, sink, counts);
                }
            }

            // 1b. Standard Spring JavaMailSender SMTP infrastructure reference
            extractMailSenderRefs(typeDecl, imports, ownerSlot, coordinates, recovered, sink, counts);

            // 2. @FeignClient interface & method mappings
            Optional<AnnotationExpr> feignAnn = findSpringAnnotation(typeDecl.getAnnotations(), "FeignClient",
                    imports, Set.of("org.springframework.cloud.openfeign.", "org.springframework.cloud.netflix.feign."));
            if (feignAnn.isPresent()) {
                extractFeignClient(typeDecl, feignAnn.orElseThrow(), typeConstants, imports, coordinates, recovered, sink, counts);
            }

            // 3. Method / constructor / initializer bodies
            for (MethodDeclaration method : typeDecl.getMethods()) {
                check(cancelled);
                String methodSlot = slot(method, coordinates);
                String activeSlot = methodSlot.isEmpty() ? ownerSlot : methodSlot;
                // Messaging listener annotations on methods
                extractMessagingAnnotations(method.getAnnotations(), typeConstants, imports, activeSlot,
                        coordinates, recovered, sink, counts);
                if (method.getBody().isPresent()) {
                    extractBodyCalls(method.getBody().orElseThrow(), typeDecl, method.getParameters(),
                            typeConstants, typePropKeys, typeClientBases, typeFieldVarTypes, imports,
                            activeSlot, coordinates, recovered, sink, counts, cancelled);
                }
            }
            for (ConstructorDeclaration ctor : typeDecl.getConstructors()) {
                check(cancelled);
                String activeSlot = slot(ctor, coordinates);
                extractBodyCalls(ctor.getBody(), typeDecl, ctor.getParameters(),
                        typeConstants, typePropKeys, typeClientBases, typeFieldVarTypes, imports,
                        activeSlot.isEmpty() ? ownerSlot : activeSlot, coordinates, recovered, sink, counts, cancelled);
            }
        }
        return new JavaGrpcSourceFrontend.Summary(unit.stream().count(), counts[1], parsed.getProblems().size(),
                PROVIDER, toolIdentity());
    }

    private static void extractFeignClient(ClassOrInterfaceDeclaration iface, AnnotationExpr feignAnn,
            Map<String, String> typeConstants, ImportIndex imports, SourceCoordinates coordinates,
            boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        String serviceName = firstNonBlank(
                attrString(feignAnn, "name", typeConstants),
                attrString(feignAnn, "value", typeConstants));
        String urlExpr = attrString(feignAnn, "url", typeConstants);
        String classPath = attrString(feignAnn, "path", typeConstants);
        if (classPath.isEmpty()) {
            Optional<AnnotationExpr> classReq = findSpringAnnotation(iface.getAnnotations(), "RequestMapping",
                    imports, Set.of("org.springframework.web.bind.annotation."));
            if (classReq.isPresent()) {
                classPath = attrString(classReq.orElseThrow(), "value", typeConstants);
                if (classPath.isEmpty()) classPath = attrString(classReq.orElseThrow(), "path", typeConstants);
            }
        }
        String cleanSvc = normalizePlaceholderOrHost(serviceName);
        String cleanUrl = normalizeUrlBase(urlExpr);
        String propertyKey = "";
        if (urlExpr.contains("${")) {
            Optional<ValueProperty> urlProp = parseValuePlaceholder(urlExpr);
            if (urlProp.isPresent()) {
                propertyKey = urlProp.orElseThrow().propertyKey();
                if (cleanUrl.isEmpty()) {
                    String defUrl = normalizeUrlBase(urlProp.orElseThrow().defaultValue());
                    if (!defUrl.isEmpty()) {
                        cleanUrl = defUrl;
                    }
                }
            }
        }
        String baseTarget = !cleanUrl.isEmpty() ? cleanUrl
                : (!cleanSvc.isEmpty() ? "http://" + cleanSvc : "");

        boolean emittedMethod = false;
        for (MethodDeclaration m : iface.getMethods()) {
            for (AnnotationExpr ann : m.getAnnotations()) {
                String sn = simpleName(ann.getNameAsString());
                if (!MAPPING_HTTP_METHODS.containsKey(sn) && !sn.equals("RequestMapping")) continue;
                if (!isSpringAnnotation(ann, imports, Set.of("org.springframework.web.bind.annotation."))) continue;
                String httpMethod = MAPPING_HTTP_METHODS.getOrDefault(sn, extractRequestMethod(ann));
                String methodPath = firstNonBlank(
                        attrString(ann, "value", typeConstants),
                        attrString(ann, "path", typeConstants));
                String combinedPath = combinePaths(classPath, methodPath);
                String fullUri = !baseTarget.isEmpty() ? appendPath(baseTarget, combinedPath) : combinedPath;
                if (!fullUri.isEmpty() || !propertyKey.isEmpty()) {
                    var attrs = new TreeMap<String, String>();
                    attrs.put("method", httpMethod);
                    attrs.put("clientKind", "FEIGN_CLIENT");
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    if (!cleanSvc.isEmpty()) {
                        attrs.put("targetService", cleanSvc);
                    } else {
                        extractUriHost(fullUri).ifPresent(host -> attrs.put("targetService", host));
                    }
                    if (!urlExpr.isEmpty()) attrs.put("urlExpr", urlExpr);
                    if (!propertyKey.isEmpty()) attrs.put("propertyKey", propertyKey);
                    emit(m, "HTTP_CALL_CANDIDATE", "feign_method", "\"" + fullUri + "\"",
                            slot(iface, coordinates), attrs, coordinates, recovered, sink, counts);
                    emittedMethod = true;
                }
            }
        }
        if (!emittedMethod && (!baseTarget.isEmpty() || !propertyKey.isEmpty())) {
            var attrs = new TreeMap<String, String>();
            attrs.put("method", "UNKNOWN");
            attrs.put("clientKind", "FEIGN_CLIENT");
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            String ifaceUri = !baseTarget.isEmpty()
                    ? appendPath(baseTarget, classPath.isEmpty() ? "/" : classPath)
                    : (classPath.isEmpty() ? "" : combinePaths(classPath, ""));
            if (!cleanSvc.isEmpty()) {
                attrs.put("targetService", cleanSvc);
            } else {
                extractUriHost(ifaceUri).ifPresent(host -> attrs.put("targetService", host));
            }
            if (!urlExpr.isEmpty()) attrs.put("urlExpr", urlExpr);
            if (!propertyKey.isEmpty()) attrs.put("propertyKey", propertyKey);
            emit(iface, "HTTP_CALL_CANDIDATE", "feign_interface", "\"" + ifaceUri + "\"",
                    slot(iface, coordinates), attrs, coordinates, recovered, sink, counts);
        }
    }

    private static void extractMessagingAnnotations(NodeList<AnnotationExpr> annotations,
            Map<String, String> constants, ImportIndex imports, String ownerSlot,
            SourceCoordinates coordinates, boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        for (AnnotationExpr ann : annotations) {
            String sn = simpleName(ann.getNameAsString());
            if (!Set.of("RabbitListener", "KafkaListener", "StreamListener").contains(sn)) continue;
            if (!isSpringAnnotation(ann, imports, MESSAGING_ANNOTATION_PACKAGES)) continue;
            String protocol = switch (sn) {
                case "RabbitListener" -> "RABBITMQ";
                case "KafkaListener" -> "KAFKA";
                default -> "SPRING_CLOUD_STREAM";
            };
            List<String> keys = switch (sn) {
                case "RabbitListener" -> List.of("queues", "queuesToDeclare", "value");
                case "KafkaListener" -> List.of("topics", "value");
                default -> List.of("value", "target");
            };
            var destinations = new LinkedHashMap<String, Boolean>();
            collectAnnotationTopics(ann, keys, constants, destinations);
            for (var entry : destinations.entrySet()) {
                var attrs = new TreeMap<String, String>();
                attrs.put("protocol", protocol);
                attrs.put("role", "CONSUMER");
                if (Boolean.TRUE.equals(entry.getValue())) {
                    attrs.put("symbolicTopic", "true");
                }
                attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                emit(ann, "EVENT_CONSUMER_CANDIDATE", "annotation", "\"" + entry.getKey() + "\"", ownerSlot,
                        attrs, coordinates, recovered, sink, counts);
            }
        }
    }

    private static void extractBodyCalls(BlockStmt body, ClassOrInterfaceDeclaration ownerType,
            NodeList<Parameter> parameters, Map<String, String> typeConstants,
            Map<String, String> typePropKeys, Map<String, ClientBaseSpec> typeClientBases,
            Map<String, String> typeFieldVarTypes, ImportIndex imports, String ownerSlot,
            SourceCoordinates coordinates, boolean recovered, JavaGrpcSourceFrontend.Sink sink,
            long[] counts, BooleanSupplier cancelled) throws IOException {
        // Single-pass AST walk over method body (replaces 6+ separate body.findAll calls).
        var methodCalls = new ArrayList<MethodCallExpr>();
        var varDecls = new ArrayList<VariableDeclarationExpr>();
        var varDeclarators = new ArrayList<VariableDeclarator>();
        var reassigned = new HashSet<String>();
        body.walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof MethodCallExpr mc) {
                methodCalls.add(mc);
            } else if (node instanceof VariableDeclarationExpr vde) {
                varDecls.add(vde);
            } else if (node instanceof VariableDeclarator vd) {
                varDeclarators.add(vd);
            } else if (node instanceof AssignExpr a && a.getTarget() instanceof NameExpr n) {
                reassigned.add(n.getNameAsString());
            } else if (node instanceof UnaryExpr u && u.getExpression() instanceof NameExpr n) {
                reassigned.add(n.getNameAsString());
            }
        });
        if (methodCalls.isEmpty()) {
            return;
        }
        Map<String, String> constants = new LinkedHashMap<>(typeConstants);
        Map<String, String> propKeys = new LinkedHashMap<>(typePropKeys);
        Map<String, String> varTypes = new HashMap<>(typeFieldVarTypes);
        for (Parameter p : parameters) {
            String paramName = p.getNameAsString();
            constants.remove(paramName);
            propKeys.remove(paramName);
            String rawType = p.getType().asString();
            String springType = resolveImportedSpringType(rawType, imports);
            if (!springType.isEmpty()) {
                varTypes.put(paramName, springType);
            }
            if (isStringType(rawType)) {
                Optional<AnnotationExpr> valAnn = findSpringAnnotation(p.getAnnotations(), "Value",
                        imports, Set.of("org.springframework.beans.factory.annotation."));
                if (valAnn.isPresent()) {
                    parseValuePlaceholder(attrString(valAnn.orElseThrow(), "value", typeConstants))
                            .ifPresent(vp -> {
                                propKeys.put(paramName, vp.propertyKey());
                                if (!vp.defaultValue().isEmpty()) {
                                    constants.put(paramName, vp.defaultValue());
                                }
                            });
                }
            }
        }
        for (VariableDeclarationExpr local : varDecls) {
            if (!isStringType(local.getCommonType().asString())) continue;
            for (VariableDeclarator v : local.getVariables()) {
                String varName = v.getNameAsString();
                constants.remove(varName);
                propKeys.remove(varName);
                if (!local.isFinal() && reassigned.contains(varName)) continue;
                v.getInitializer().ifPresent(init -> {
                    String s = HttpClientConstants.syntaxUri(init, constants);
                    if (!s.isEmpty()) constants.put(varName, s);
                    String pk = extractPropertyKey(init, constants, propKeys, 0);
                    if (!pk.isEmpty()) propKeys.put(varName, pk);
                });
            }
        }
        Map<String, ClientBaseSpec> clientBases = new LinkedHashMap<>(typeClientBases);
        Map<String, List<VariableDeclarator>> localDeclaratorsByName = null;
        for (VariableDeclarator v : varDeclarators) {
            String t = resolveImportedSpringType(v.getType().asString(), imports);
            if (!t.isEmpty()) {
                varTypes.put(v.getNameAsString(), t);
            } else if (v.getInitializer().isPresent()) {
                String init = v.getInitializer().orElseThrow().toString();
                for (String known : KNOWN_CLIENT_INITS) {
                    if ((init.startsWith(known + ".") || init.startsWith("new " + known + "("))) {
                        String resolvedKnown = resolveImportedSpringType(known, imports);
                        if (!resolvedKnown.isEmpty()) {
                            varTypes.put(v.getNameAsString(), resolvedKnown);
                            break;
                        }
                    }
                }
            }
            if (v.getInitializer().isPresent()) {
                ClientBaseSpec spec = extractFluentBaseSpec(v.getInitializer().orElseThrow(), constants, propKeys, clientBases, imports);
                if (spec != null) {
                    clientBases.put(v.getNameAsString(), spec);
                }
                if (localDeclaratorsByName == null) localDeclaratorsByName = new HashMap<>();
                localDeclaratorsByName.computeIfAbsent(v.getNameAsString(), k -> new ArrayList<>()).add(v);
            }
        }
        final Map<String, List<VariableDeclarator>> indexedDeclarators = localDeclaratorsByName == null ? Map.of() : localDeclaratorsByName;

        for (MethodCallExpr call : methodCalls) {
            check(cancelled);
            String name = call.getNameAsString();
            String scopeRoot = rootIdentifier(call.getScope().orElse(null));
            String scopeType = varTypes.getOrDefault(scopeRoot, "");
            if (scopeType.isEmpty() && !scopeRoot.isEmpty()) {
                scopeType = resolveImportedSpringType(scopeRoot, imports);
            }

            // 1. DiscoveryClient.getInstances("svc") / LoadBalancerClient.choose("svc")
            if ((name.equals("getInstances") && isType(scopeType, "DiscoveryClient", imports, "org.springframework.cloud.client.discovery.DiscoveryClient"))
                    || (name.equals("choose") && isType(scopeType, "LoadBalancerClient", imports, "org.springframework.cloud.client.loadbalancer.LoadBalancerClient"))) {
                if (!call.getArguments().isEmpty()) {
                    String svc = HttpClientConstants.syntaxUri(call.getArgument(0), constants).trim();
                    if (!svc.isEmpty()) {
                        emit(call, "HTTP_CALL_CANDIDATE", "method_call", "\"lb://" + svc + "/\"", ownerSlot,
                                Map.of("method", "UNKNOWN", "targetService", svc, "clientKind", "SERVICE_DISCOVERY",
                                        "trigger", "JAVA_SOURCE_SYNTAX"),
                                coordinates, recovered, sink, counts);
                    }
                }
                continue;
            }

            // 2. RabbitTemplate.convertAndSend / KafkaTemplate.send
            if (name.equals("convertAndSend") && isMessagingTemplate(scopeType, "RabbitTemplate", "AmqpTemplate")) {
                if (!call.getArguments().isEmpty()) {
                    Expression topicArg = call.getArgument(0);
                    if (topicArg instanceof StringLiteralExpr lit && lit.asString().isBlank() && call.getArguments().size() >= 2) {
                        topicArg = call.getArgument(1);
                    }
                    emitMessagingProducer(call, topicArg, "RABBITMQ", constants, ownerSlot,
                            coordinates, recovered, sink, counts);
                }
                continue;
            }
            if (name.equals("send") && isMessagingTemplate(scopeType, "KafkaTemplate", "KafkaTemplate")) {
                if (!call.getArguments().isEmpty()) {
                    emitMessagingProducer(call, call.getArgument(0), "KAFKA", constants, ownerSlot,
                            coordinates, recovered, sink, counts);
                }
                continue;
            }

            // 3. Standard JAX-RS client WebTarget/ClientBuilder calls (when JARs absent under --build=off)
            if (name.equals("request") && isJaxRsWebTargetChain(call, varTypes, imports)) {
                String jaxRsUri = resolveJaxRsWebTargetUri(call, ownerType, indexedDeclarators, constants);
                if (!jaxRsUri.isEmpty()) {
                    String jaxRsVerb = jaxRsInvocationVerb(call, constants);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("method", jaxRsVerb.isEmpty() ? "UNKNOWN" : jaxRsVerb);
                    attrs.put("clientKind", "JAX_RS_CLIENT");
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    extractUriHost(jaxRsUri).ifPresent(host -> attrs.put("targetService", host));
                    emit(call, "HTTP_CALL_CANDIDATE", "method_call", "\"" + jaxRsUri + "\"", ownerSlot,
                            attrs, coordinates, recovered, sink, counts);
                }
                continue;
            }

            // 4. RestTemplate / RestOperations / OAuth2RestTemplate / OAuth2RestOperations / RestClient / WebClient HTTP calls
            if (!REST_CALL_METHODS.contains(name)) continue;
            boolean isRest = !name.equals("uri") && (isRestTemplateType(scopeType)
                    || looksLikeChainedBuilder(call, varTypes, imports,
                            "RestTemplate", "RestOperations", "OAuth2RestTemplate", "OAuth2RestOperations"));
            boolean isFluent = name.equals("uri") && (isFluentClientType(scopeType)
                    || looksLikeChainedBuilder(call, varTypes, imports, "RestClient", "WebClient"));
            if (!isRest && !isFluent) continue;

            Expression urlArg = call.getArguments().isEmpty() ? null : call.getArgument(0);
            String uri = urlArg == null ? "" : HttpClientConstants.syntaxUri(urlArg, constants).trim();
            String propertyKey = urlArg == null ? "" : extractPropertyKey(urlArg, constants, propKeys, 0);
            if (!propertyKey.isEmpty() && uri.startsWith("{param}")) {
                uri = "";
            }
            if (isFluent) {
                ClientBaseSpec base = extractFluentBaseSpec(call.getScope().orElse(null), constants, propKeys, clientBases, imports);
                if (base != null) {
                    if (propertyKey.isEmpty() && !base.propertyKey().isEmpty()) {
                        propertyKey = base.propertyKey();
                    }
                    if (!base.baseUri().isEmpty() && !hasUriScheme(uri)) {
                        uri = uri.isEmpty() ? base.baseUri() : appendPath(base.baseUri(), uri);
                    }
                }
            }
            if (uri.isEmpty() && propertyKey.isEmpty()) continue;
            String verb = isFluent ? fluentVerb(call) : restVerb(name);
            var attrs = new TreeMap<String, String>();
            attrs.put("method", verb.isEmpty() ? "UNKNOWN" : verb);
            attrs.put("clientKind", isFluent ? "REST_OR_WEB_CLIENT" : "REST_TEMPLATE");
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            if (!propertyKey.isEmpty()) attrs.put("propertyKey", propertyKey);
            extractUriHost(uri).ifPresent(host -> attrs.put("targetService", host));
            emit(call, "HTTP_CALL_CANDIDATE", "method_call", "\"" + uri + "\"", ownerSlot,
                    attrs, coordinates, recovered, sink, counts);
        }
    }

    private static Map<String, String> collectTypeFieldVarTypes(ClassOrInterfaceDeclaration ownerType,
            ImportIndex imports) {
        var map = new HashMap<String, String>();
        for (FieldDeclaration f : ownerType.getFields()) {
            String t = resolveImportedSpringType(f.getCommonType().asString(), imports);
            if (!t.isEmpty()) f.getVariables().forEach(v -> map.put(v.getNameAsString(), t));
        }
        return map;
    }

    private static String resolveImportedSpringType(String writtenType, ImportIndex imports) {
        if (writtenType == null || writtenType.isEmpty()) return "";
        int lt = writtenType.indexOf('<');
        String base = (lt > 0 ? writtenType.substring(0, lt) : writtenType).trim();
        if (base.startsWith("org.springframework.") || base.startsWith("javax.ws.rs.client.")
                || base.startsWith("jakarta.ws.rs.client.")) return base;
        String direct = imports.explicit.get(base);
        if (direct != null && (direct.startsWith("org.springframework.")
                || direct.startsWith("javax.ws.rs.client.")
                || direct.startsWith("jakarta.ws.rs.client."))) return direct;
        int dot = base.indexOf('.');
        if (dot > 0) {
            String outer = base.substring(0, dot);
            String outerResolved = resolveImportedSpringType(outer, imports);
            if (!outerResolved.isEmpty()) {
                return outerResolved + base.substring(dot);
            }
        }
        for (String wc : imports.wildcards) {
            if (wc.startsWith("org.springframework.")) {
                if (Set.of("RestTemplate", "RestOperations", "OAuth2RestTemplate", "OAuth2RestOperations",
                        "RestClient", "WebClient",
                        "DiscoveryClient", "LoadBalancerClient", "RabbitTemplate", "AmqpTemplate", "KafkaTemplate",
                        "JavaMailSender", "JavaMailSenderImpl")
                        .contains(base)) {
                    return wc + base;
                }
            } else if (wc.equals("javax.ws.rs.client.") || wc.equals("jakarta.ws.rs.client.")) {
                if (Set.of("ClientBuilder", "Client", "WebTarget", "Invocation").contains(base)) {
                    return wc + base;
                }
            }
        }
        return "";
    }

    private static boolean isJaxRsWebTargetChain(MethodCallExpr requestCall, Map<String, String> varTypes,
            ImportIndex imports) {
        Expression cur = requestCall.getScope().orElse(null);
        int steps = 0;
        while (cur instanceof MethodCallExpr mc && steps++ < 16) {
            if (mc.getNameAsString().equals("target") && !mc.getArguments().isEmpty()) {
                Expression targetScope = mc.getScope().orElse(null);
                if (targetScope instanceof MethodCallExpr m2
                        && m2.getScope().filter(s -> (s instanceof NameExpr || s instanceof FieldAccessExpr)
                                && !resolveImportedSpringType(s.toString(), imports).isEmpty()).isPresent()) {
                    return true;
                }
                String root = rootIdentifier(targetScope);
                String t = varTypes.getOrDefault(root, "");
                if (t.startsWith("javax.ws.rs.client.") || t.startsWith("jakarta.ws.rs.client.")) return true;
            }
            cur = mc.getScope().orElse(null);
        }
        String root = rootIdentifier(cur);
        String t = varTypes.getOrDefault(root, "");
        return t.equals("javax.ws.rs.client.WebTarget") || t.equals("jakarta.ws.rs.client.WebTarget");
    }

    private static String resolveJaxRsWebTargetUri(MethodCallExpr requestCall,
            ClassOrInterfaceDeclaration ownerType,
            Map<String, List<VariableDeclarator>> indexedDeclarators,
            Map<String, String> constants) {
        var pathSegments = new ArrayList<String>();
        String baseUri = "";
        Expression cur = requestCall.getScope().orElse(null);
        var visitedVars = new HashSet<String>();
        int steps = 0;
        while (cur != null && steps++ < 16) {
            if (cur instanceof MethodCallExpr mc) {
                String n = mc.getNameAsString();
                if (n.equals("path") && !mc.getArguments().isEmpty()) {
                    String seg = HttpClientConstants.syntaxUri(mc.getArgument(0), constants).trim();
                    pathSegments.addFirst(seg.isEmpty() ? "{param}" : seg);
                    cur = mc.getScope().orElse(null);
                    continue;
                }
                if (n.equals("target") && !mc.getArguments().isEmpty()) {
                    baseUri = HttpClientConstants.syntaxUri(mc.getArgument(0), constants).trim();
                    break;
                }
                cur = mc.getScope().orElse(null);
                continue;
            }
            if (cur instanceof NameExpr n && visitedVars.add(n.getNameAsString())) {
                cur = findTargetInitializer(requestCall, ownerType, indexedDeclarators, n.getNameAsString()).orElse(null);
                continue;
            }
            if (cur instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr
                    && visitedVars.add(f.getNameAsString())) {
                cur = findTargetInitializer(requestCall, ownerType, indexedDeclarators, f.getNameAsString()).orElse(null);
                continue;
            }
            break;
        }
        if (baseUri.isEmpty() && pathSegments.isEmpty()) return "";
        String uri = baseUri;
        for (String seg : pathSegments) {
            String cleanSeg = seg.startsWith("/") ? seg : "/" + seg;
            uri = uri.isEmpty() ? cleanSeg : appendPath(uri, cleanSeg);
        }
        return uri;
    }

    private static Optional<Expression> findTargetInitializer(MethodCallExpr from,
            ClassOrInterfaceDeclaration ownerType,
            Map<String, List<VariableDeclarator>> indexedDeclarators,
            String varName) {
        for (VariableDeclarator vd : indexedDeclarators.getOrDefault(varName, List.of())) {
            if (vd.getInitializer().isPresent()
                    && from.getRange().map(fr -> vd.getRange().map(vr -> vr.isBefore(fr.begin)).orElse(true))
                            .orElse(true)) {
                return vd.getInitializer();
            }
        }
        for (FieldDeclaration fd : ownerType.getFields()) {
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getNameAsString().equals(varName) && vd.getInitializer().isPresent()) {
                    return vd.getInitializer();
                }
            }
        }
        return Optional.empty();
    }

    private static String jaxRsInvocationVerb(MethodCallExpr requestCall, Map<String, String> constants) {
        Node cur = requestCall;
        for (int depth = 0; depth < 6; depth++) {
            Node parent = cur.getParentNode().orElse(null);
            if (!(parent instanceof MethodCallExpr parentCall)) break;
            Node expectedScope = cur;
            if (parentCall.getScope().filter(s -> s == expectedScope).isEmpty()) break;
            String n = parentCall.getNameAsString();
            if (Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(n)) {
                return n.toUpperCase(Locale.ROOT);
            }
            if (Set.of("buildGet", "buildPost", "buildPut", "buildDelete").contains(n)) {
                return n.substring(5).toUpperCase(Locale.ROOT);
            }
            if (n.equals("method") && !parentCall.getArguments().isEmpty()) {
                String v = HttpClientConstants.syntaxUri(parentCall.getArgument(0), constants).trim().toUpperCase(Locale.ROOT);
                if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(v)) {
                    return v;
                }
            }
            cur = parentCall;
        }
        return "";
    }

    private static boolean isRestTemplateType(String t) {
        return REST_TEMPLATE_EXACT.contains(t)
                || t.endsWith(".RestTemplate")
                || t.endsWith(".RestOperations")
                || t.endsWith(".OAuth2RestTemplate")
                || t.endsWith(".OAuth2RestOperations");
    }

    private static boolean isFluentClientType(String t) {
        return t.startsWith("org.springframework.web.client.RestClient")
                || t.startsWith("org.springframework.web.reactive.function.client.WebClient");
    }

    private static boolean isJavaMailSenderType(String t) {
        return t.equals("org.springframework.mail.javamail.JavaMailSender")
                || t.equals("org.springframework.mail.javamail.JavaMailSenderImpl");
    }

    private static Set<String> allowedRoleAnnotationPrefixes(String simpleName) {
        return switch (simpleName) {
            case "EnableAdminServer" -> Set.of("de.codecentric.boot.admin.");
            case "EnableZipkinServer" -> Set.of("zipkin.server.", "zipkin2.server.");
            case "EnableResourceServer", "EnableAuthorizationServer" -> Set.of("org.springframework.security.");
            case "EnableOAuth2Sso", "EnableOAuth2Resource" -> Set.of(
                    "org.springframework.boot.", "org.springframework.cloud.", "org.springframework.security.");
            case "EnableTurbineAmqp", "EnableCircuitBreaker", "EnableHystrix" -> Set.of("org.springframework.cloud.");
            default -> Set.of("org.springframework.cloud.", "org.springframework.boot.");
        };
    }

    private static void emitMessagingProducer(MethodCallExpr call, Expression topicArg, String protocol,
            Map<String, String> constants, String ownerSlot, SourceCoordinates coordinates,
            boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        String topic = HttpClientConstants.syntaxUri(topicArg, constants).trim();
        if (!topic.isEmpty()) {
            emit(call, "EVENT_PRODUCER_CANDIDATE", "method_call", "\"" + topic + "\"", ownerSlot,
                    Map.of("role", "PRODUCER", "protocol", protocol, "trigger", "JAVA_SOURCE_SYNTAX"),
                    coordinates, recovered, sink, counts);
            return;
        }
        String symbolic = symbolicTopicExpr(topicArg);
        if (!symbolic.isEmpty()) {
            var attrs = new TreeMap<String, String>();
            attrs.put("protocol", protocol);
            attrs.put("role", "PRODUCER");
            attrs.put("symbolicTopic", "true");
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            emit(call, "EVENT_PRODUCER_CANDIDATE", "method_call", "\"" + symbolic + "\"", ownerSlot,
                    attrs, coordinates, recovered, sink, counts);
        }
    }

    private static void collectAnnotationTopics(AnnotationExpr ann, List<String> keys,
            Map<String, String> constants, Map<String, Boolean> out) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            collectExprTopics(single.getMemberValue(), constants, out);
            return;
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (keys.contains(pair.getNameAsString())) {
                    collectExprTopics(pair.getValue(), constants, out);
                }
            }
        }
    }

    private static void collectExprTopics(Expression expr, Map<String, String> constants, Map<String, Boolean> out) {
        if (expr instanceof ArrayInitializerExpr array) {
            for (var item : array.getValues()) {
                collectSingleTopic(item, constants, out);
            }
            return;
        }
        collectSingleTopic(expr, constants, out);
    }

    private static void collectSingleTopic(Expression expr, Map<String, String> constants, Map<String, Boolean> out) {
        if (expr instanceof AnnotationExpr nested) {
            if (nested instanceof SingleMemberAnnotationExpr single) {
                collectSingleTopic(single.getMemberValue(), constants, out);
                return;
            }
            if (nested instanceof NormalAnnotationExpr normal) {
                for (var pair : normal.getPairs()) {
                    if (Set.of("value", "name").contains(pair.getNameAsString())) {
                        collectSingleTopic(pair.getValue(), constants, out);
                    }
                }
            }
            return;
        }
        String resolved = HttpClientConstants.syntaxUri(expr, constants).trim();
        if (!resolved.isEmpty()) {
            out.put(resolved, Boolean.FALSE);
            return;
        }
        String symbolic = symbolicTopicExpr(expr);
        if (!symbolic.isEmpty()) {
            out.putIfAbsent(symbolic, Boolean.TRUE);
        }
    }

    private static String symbolicTopicExpr(Expression expr) {
        if (expr == null || expr instanceof NullLiteralExpr) return "";
        if (expr instanceof StringLiteralExpr lit && lit.asString().isBlank()) return "";
        if (expr instanceof TextBlockLiteralExpr lit && lit.asString().isBlank()) return "";
        String raw = expr.toString().replaceAll("\\s+", " ").trim();
        if (raw.isEmpty() || raw.length() > 128 || raw.indexOf('\\') >= 0) return "";
        return raw;
    }

    private static void extractMailSenderRefs(ClassOrInterfaceDeclaration typeDecl, ImportIndex imports,
            String ownerSlot, SourceCoordinates coordinates, boolean recovered,
            JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        for (FieldDeclaration f : typeDecl.getFields()) {
            if (isJavaMailSenderType(resolveImportedSpringType(f.getCommonType().asString(), imports))) {
                emitMailSenderRef(f, ownerSlot, coordinates, recovered, sink, counts);
                return;
            }
        }
        for (ConstructorDeclaration ctor : typeDecl.getConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                if (isJavaMailSenderType(resolveImportedSpringType(p.getType().asString(), imports))) {
                    emitMailSenderRef(p, ownerSlot, coordinates, recovered, sink, counts);
                    return;
                }
            }
        }
        for (MethodDeclaration method : typeDecl.getMethods()) {
            for (Parameter p : method.getParameters()) {
                if (isJavaMailSenderType(resolveImportedSpringType(p.getType().asString(), imports))) {
                    emitMailSenderRef(p, ownerSlot, coordinates, recovered, sink, counts);
                    return;
                }
            }
            if (isJavaMailSenderType(resolveImportedSpringType(method.getType().asString(), imports))) {
                emitMailSenderRef(method, ownerSlot, coordinates, recovered, sink, counts);
                return;
            }
        }
    }

    private static void emitMailSenderRef(Node node, String ownerSlot, SourceCoordinates coordinates,
            boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        var attrs = new TreeMap<String, String>();
        attrs.put("kind", "mail-sender");
        attrs.put("role", "smtp");
        attrs.put("spec", "spring-mail");
        attrs.put("target", "smtp");
        attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
        emit(node, "INFRA_CONFIG_REF", "mail-sender", "smtp", ownerSlot,
                attrs, coordinates, recovered, sink, counts);
    }

    private static boolean isMessagingTemplate(String t, String exactSimple, String altSimple) {
        if (MESSAGING_TEMPLATE_TYPES.contains(t)) return true;
        return t.startsWith("org.springframework.") && (t.endsWith("." + exactSimple) || t.endsWith("." + altSimple));
    }

    private static boolean isType(String scopeType, String simple, ImportIndex imports, String fqcn) {
        if (fqcn.equals(scopeType)) return true;
        return fqcn.equals(imports.explicit.get(simple))
                && scopeType.endsWith("." + simple);
    }

    private static boolean looksLikeChainedBuilder(MethodCallExpr call, Map<String, String> varTypes,
            ImportIndex imports, String... simpleNames) {
        Expression cur = call.getScope().orElse(null);
        int steps = 0;
        while (cur instanceof MethodCallExpr mc && steps++ < 8) {
            if (mc.getScope().isEmpty()) break;
            cur = mc.getScope().orElseThrow();
        }
        String root = rootIdentifier(cur);
        String t = varTypes.getOrDefault(root, "");
        if (t.isEmpty() && !root.isEmpty()) {
            t = resolveImportedSpringType(root, imports);
        }
        for (String name : simpleNames) {
            if (t.endsWith("." + name) || t.contains("." + name + ".")) return true;
        }
        return false;
    }

    private static String rootIdentifier(Expression expr) {
        Expression cur = expr;
        while (cur != null) {
            if (cur instanceof NameExpr n) return n.getNameAsString();
            if (cur instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) return f.getNameAsString();
            if (cur instanceof FieldAccessExpr f) {
                String full = f.toString();
                if (full.startsWith("org.springframework.") || full.startsWith("javax.ws.rs.client.")
                        || full.startsWith("jakarta.ws.rs.client.")) {
                    return full;
                }
                cur = f.getScope();
            } else if (cur instanceof MethodCallExpr mc) cur = mc.getScope().orElse(null);
            else if (cur instanceof ObjectCreationExpr oc) return oc.getType().asString();
            else break;
        }
        return "";
    }

    private static String fluentVerb(MethodCallExpr uriCall) {
        Expression scope = uriCall.getScope().orElse(null);
        while (scope instanceof MethodCallExpr mc) {
            String n = mc.getNameAsString();
            if (Set.of("get", "post", "put", "patch", "delete", "head", "options").contains(n)) {
                return n.toUpperCase(Locale.ROOT);
            }
            scope = mc.getScope().orElse(null);
        }
        return "";
    }

    private static String restVerb(String method) {
        return method.startsWith("getFor") ? "GET"
                : method.startsWith("postFor") ? "POST"
                : method.equals("put") ? "PUT"
                : method.equals("patchForObject") ? "PATCH"
                : method.equals("delete") ? "DELETE"
                : method.equals("headForHeaders") ? "HEAD"
                : method.equals("optionsForAllow") ? "OPTIONS" : "";
    }

    private static Map<String, String> collectTypeConstants(ClassOrInterfaceDeclaration type, ImportIndex imports) {
        var constants = new LinkedHashMap<String, String>();
        var reassigned = new HashSet<String>();
        for (AssignExpr a : type.findAll(AssignExpr.class)) {
            if (a.getTarget() instanceof NameExpr n) reassigned.add(n.getNameAsString());
            else if (a.getTarget() instanceof FieldAccessExpr f) reassigned.add(f.getNameAsString());
        }
        for (FieldDeclaration f : type.getFields()) {
            if (!isStringType(f.getCommonType().asString())) continue;
            if (findSpringAnnotation(f.getAnnotations(), "Value", imports,
                    Set.of("org.springframework.beans.factory.annotation.")).isPresent()) {
                continue;
            }
            if (!f.isFinal() && !f.isPrivate() && !type.isInterface()) continue;
            for (VariableDeclarator v : f.getVariables()) {
                if (!f.isFinal() && !type.isInterface() && reassigned.contains(v.getNameAsString())) continue;
                v.getInitializer().ifPresent(init -> {
                    String s = HttpClientConstants.syntaxUri(init, constants);
                    if (!s.isEmpty()) constants.put(v.getNameAsString(), s);
                });
            }
        }
        return constants;
    }

    private static Map<String, ValueProperty> collectValueProperties(ClassOrInterfaceDeclaration type,
            Map<String, String> typeConstants, ImportIndex imports) {
        var valueProps = new LinkedHashMap<String, ValueProperty>();
        var stringFields = new HashSet<String>();
        for (FieldDeclaration f : type.getFields()) {
            if (!isStringType(f.getCommonType().asString())) continue;
            for (VariableDeclarator v : f.getVariables()) {
                stringFields.add(v.getNameAsString());
            }
            Optional<AnnotationExpr> valAnn = findSpringAnnotation(f.getAnnotations(), "Value",
                    imports, Set.of("org.springframework.beans.factory.annotation."));
            if (valAnn.isEmpty()) continue;
            Optional<ValueProperty> parsed = parseValuePlaceholder(attrString(valAnn.orElseThrow(), "value", typeConstants));
            if (parsed.isEmpty()) continue;
            for (VariableDeclarator v : f.getVariables()) {
                valueProps.put(v.getNameAsString(), parsed.orElseThrow());
            }
        }
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            var ctorParams = new HashMap<String, ValueProperty>();
            for (Parameter p : ctor.getParameters()) {
                if (!isStringType(p.getType().asString())) continue;
                Optional<AnnotationExpr> valAnn = findSpringAnnotation(p.getAnnotations(), "Value",
                        imports, Set.of("org.springframework.beans.factory.annotation."));
                if (valAnn.isEmpty()) continue;
                parseValuePlaceholder(attrString(valAnn.orElseThrow(), "value", typeConstants))
                        .ifPresent(vp -> ctorParams.put(p.getNameAsString(), vp));
            }
            if (ctorParams.isEmpty()) continue;
            for (AssignExpr assign : ctor.getBody().findAll(AssignExpr.class)) {
                if (assign.getOperator() != AssignExpr.Operator.ASSIGN) continue;
                if (!(assign.getValue() instanceof NameExpr rhs)) continue;
                ValueProperty vp = ctorParams.get(rhs.getNameAsString());
                if (vp == null) continue;
                String fieldName = assignedFieldName(assign.getTarget());
                if (!fieldName.isEmpty() && stringFields.contains(fieldName)) {
                    valueProps.putIfAbsent(fieldName, vp);
                }
            }
        }
        return valueProps;
    }

    private static Map<String, ClientBaseSpec> collectTypeClientBases(ClassOrInterfaceDeclaration type,
            Map<String, String> typeConstants, Map<String, String> typePropKeys, ImportIndex imports) {
        var bases = new LinkedHashMap<String, ClientBaseSpec>();
        for (FieldDeclaration f : type.getFields()) {
            String resolved = resolveImportedSpringType(f.getCommonType().asString(), imports);
            if (!isFluentClientType(resolved)) continue;
            for (VariableDeclarator v : f.getVariables()) {
                v.getInitializer().ifPresent(init -> {
                    ClientBaseSpec spec = extractFluentBaseSpec(init, typeConstants, typePropKeys, bases, imports);
                    if (spec != null) bases.put(v.getNameAsString(), spec);
                });
            }
        }
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            var ctorConstants = new LinkedHashMap<>(typeConstants);
            var ctorPropKeys = new LinkedHashMap<>(typePropKeys);
            for (Parameter p : ctor.getParameters()) {
                String paramName = p.getNameAsString();
                ctorConstants.remove(paramName);
                ctorPropKeys.remove(paramName);
                if (!isStringType(p.getType().asString())) continue;
                Optional<AnnotationExpr> valAnn = findSpringAnnotation(p.getAnnotations(), "Value",
                        imports, Set.of("org.springframework.beans.factory.annotation."));
                if (valAnn.isPresent()) {
                    parseValuePlaceholder(attrString(valAnn.orElseThrow(), "value", typeConstants))
                            .ifPresent(vp -> {
                                ctorPropKeys.put(paramName, vp.propertyKey());
                                if (!vp.defaultValue().isEmpty()) ctorConstants.put(paramName, vp.defaultValue());
                            });
                }
            }
            collectLocalConstants(ctor.getBody(), ctorConstants, ctorPropKeys);
            for (AssignExpr assign : ctor.getBody().findAll(AssignExpr.class)) {
                if (assign.getOperator() != AssignExpr.Operator.ASSIGN) continue;
                String fieldName = assignedFieldName(assign.getTarget());
                if (fieldName.isEmpty()) continue;
                ClientBaseSpec spec = extractFluentBaseSpec(assign.getValue(), ctorConstants, ctorPropKeys, bases, imports);
                if (spec != null) bases.putIfAbsent(fieldName, spec);
            }
        }
        return bases;
    }

    private static void collectLocalClientBases(BlockStmt body, Map<String, String> constants,
            Map<String, String> propKeys, Map<String, ClientBaseSpec> clientBases, ImportIndex imports) {
        for (VariableDeclarator v : body.findAll(VariableDeclarator.class)) {
            if (v.getInitializer().isEmpty()) continue;
            ClientBaseSpec spec = extractFluentBaseSpec(v.getInitializer().orElseThrow(), constants, propKeys, clientBases, imports);
            if (spec != null) {
                clientBases.put(v.getNameAsString(), spec);
            }
        }
    }

    private static ClientBaseSpec extractFluentBaseSpec(Expression expr, Map<String, String> constants,
            Map<String, String> propKeys, Map<String, ClientBaseSpec> knownBases, ImportIndex imports) {
        Expression cur = expr;
        int steps = 0;
        while (cur != null && steps++ < 12) {
            if (cur instanceof EnclosedExpr enclosed) {
                cur = enclosed.getInner();
                continue;
            }
            if (cur instanceof MethodCallExpr mc) {
                String n = mc.getNameAsString();
                if ((n.equals("baseUrl") || n.equals("create")) && mc.getArguments().size() == 1) {
                    if (n.equals("baseUrl") || isFluentClientScope(mc.getScope().orElse(null), imports)) {
                        Expression arg = mc.getArgument(0);
                        String baseUri = HttpClientConstants.syntaxUri(arg, constants).trim();
                        String propKey = extractPropertyKey(arg, constants, propKeys, 0);
                        if (!propKey.isEmpty() && baseUri.startsWith("{param}")) {
                            baseUri = "";
                        }
                        if (!baseUri.isEmpty() || !propKey.isEmpty()) {
                            return new ClientBaseSpec(baseUri, propKey);
                        }
                    }
                }
                cur = mc.getScope().orElse(null);
                continue;
            }
            if (cur instanceof NameExpr n) {
                return knownBases.get(n.getNameAsString());
            }
            if (cur instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) {
                return knownBases.get(f.getNameAsString());
            }
            break;
        }
        return null;
    }

    private static boolean isFluentClientScope(Expression scope, ImportIndex imports) {
        if (scope == null) return false;
        String root = rootIdentifier(scope);
        String resolved = resolveImportedSpringType(root, imports);
        return isFluentClientType(resolved);
    }

    private static void collectLocalConstants(BlockStmt body, Map<String, String> constants,
            Map<String, String> propKeys) {
        var reassigned = new HashSet<String>();
        for (AssignExpr a : body.findAll(AssignExpr.class)) {
            if (a.getTarget() instanceof NameExpr n) reassigned.add(n.getNameAsString());
        }
        for (UnaryExpr u : body.findAll(UnaryExpr.class)) {
            if (u.getExpression() instanceof NameExpr n) reassigned.add(n.getNameAsString());
        }
        for (VariableDeclarationExpr local : body.findAll(VariableDeclarationExpr.class)) {
            if (!isStringType(local.getCommonType().asString())) continue;
            for (VariableDeclarator v : local.getVariables()) {
                String varName = v.getNameAsString();
                constants.remove(varName);
                propKeys.remove(varName);
                if (!local.isFinal() && reassigned.contains(varName)) continue;
                v.getInitializer().ifPresent(init -> {
                    String s = HttpClientConstants.syntaxUri(init, constants);
                    if (!s.isEmpty()) constants.put(varName, s);
                    String pk = extractPropertyKey(init, constants, propKeys, 0);
                    if (!pk.isEmpty()) propKeys.put(varName, pk);
                });
            }
        }
    }

    private static String extractPropertyKey(Expression expr, Map<String, String> constants,
            Map<String, String> propKeys, int depth) {
        if (expr == null || depth > 32 || propKeys.isEmpty()) return "";
        if (expr instanceof EnclosedExpr enclosed) {
            return extractPropertyKey(enclosed.getInner(), constants, propKeys, depth + 1);
        }
        if (expr instanceof NameExpr name) {
            return propKeys.getOrDefault(name.getNameAsString(), "");
        }
        if (expr instanceof FieldAccessExpr field) {
            if (field.getScope() instanceof ThisExpr) {
                return propKeys.getOrDefault(field.getNameAsString(), "");
            }
            return propKeys.getOrDefault(field.toString(), "");
        }
        if (expr instanceof BinaryExpr binary && binary.getOperator() == BinaryExpr.Operator.PLUS) {
            String leftKey = extractPropertyKey(binary.getLeft(), constants, propKeys, depth + 1);
            if (!leftKey.isEmpty()) return leftKey;
            String leftVal = HttpClientConstants.syntaxUri(binary.getLeft(), constants).trim();
            if (leftVal.isEmpty() || isSchemeOnlyPrefix(leftVal)) {
                return extractPropertyKey(binary.getRight(), constants, propKeys, depth + 1);
            }
            return "";
        }
        if (expr instanceof MethodCallExpr call) {
            String n = call.getNameAsString();
            if (n.equals("format") && call.getArguments().size() >= 2) {
                boolean stringScope = call.getScope().map(s -> {
                    String t = s.toString();
                    return t.equals("String") || t.equals("java.lang.String");
                }).orElse(true);
                if (stringScope) {
                    int templateIdx = 0;
                    String first = call.getArgument(0).toString();
                    if (first.startsWith("Locale.") || first.startsWith("java.util.Locale.")) {
                        templateIdx = 1;
                    }
                    if (templateIdx + 1 < call.getArguments().size()) {
                        String template = HttpClientConstants.syntaxUri(call.getArgument(templateIdx), constants).trim();
                        int pct = template.indexOf('%');
                        if (pct >= 0) {
                            String prefix = template.substring(0, pct);
                            if (prefix.isEmpty() || isSchemeOnlyPrefix(prefix)) {
                                return extractPropertyKey(call.getArgument(templateIdx + 1), constants, propKeys, depth + 1);
                            }
                        }
                    }
                }
            } else if (n.equals("create") && call.getArguments().size() == 1) {
                boolean uriScope = call.getScope().map(s -> {
                    String t = s.toString();
                    return t.equals("URI") || t.equals("java.net.URI");
                }).orElse(false);
                if (uriScope) {
                    return extractPropertyKey(call.getArgument(0), constants, propKeys, depth + 1);
                }
            }
        }
        return "";
    }

    private static Optional<ValueProperty> parseValuePlaceholder(String raw) {
        if (raw == null) return Optional.empty();
        String t = raw.trim();
        if (t.isEmpty() || t.indexOf("${") < 0) return Optional.empty();
        var sb = new StringBuilder(t.length());
        String firstKey = "";
        boolean allHaveDefault = true;
        int cursor = 0;
        while (cursor < t.length()) {
            int start = t.indexOf("${", cursor);
            if (start < 0) {
                sb.append(t, cursor, t.length());
                break;
            }
            sb.append(t, cursor, start);
            int end = t.indexOf('}', start + 2);
            if (end < 0) return Optional.empty();
            String inner = t.substring(start + 2, end);
            if (inner.contains("${") || inner.contains("{")) return Optional.empty();
            int colon = inner.indexOf(':');
            String key = (colon >= 0 ? inner.substring(0, colon) : inner).trim();
            if (!isValidPropertyKey(key)) return Optional.empty();
            if (firstKey.isEmpty()) firstKey = key;
            if (colon >= 0) {
                String def = inner.substring(colon + 1).trim();
                if (def.isEmpty()) {
                    allHaveDefault = false;
                } else {
                    sb.append(def);
                }
            } else {
                allHaveDefault = false;
            }
            cursor = end + 1;
        }
        if (firstKey.isEmpty()) return Optional.empty();
        String defaultValue = allHaveDefault ? sb.toString().trim() : "";
        return Optional.of(new ValueProperty(firstKey, defaultValue));
    }

    private static boolean isValidPropertyKey(String key) {
        if (key.isEmpty() || key.length() > 256) return false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' || c == '[' || c == ']')) {
                return false;
            }
        }
        return true;
    }

    private static boolean isStringType(String typeName) {
        return "String".equals(typeName) || "java.lang.String".equals(typeName);
    }

    private static String assignedFieldName(Expression target) {
        if (target instanceof NameExpr n) return n.getNameAsString();
        if (target instanceof FieldAccessExpr f && f.getScope() instanceof ThisExpr) return f.getNameAsString();
        return "";
    }

    private static boolean isSchemeOnlyPrefix(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("http://") || lower.equals("https://") || lower.equals("lb://");
    }

    private static boolean hasUriScheme(String value) {
        if (value == null || value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("lb://");
    }

    private record ImportIndex(Map<String, String> explicit, Set<String> wildcards) {
        static ImportIndex from(CompilationUnit cu) {
            var explicit = new HashMap<String, String>();
            var wildcards = new LinkedHashSet<String>();
            for (ImportDeclaration id : cu.getImports()) {
                if (id.isStatic()) continue;
                String q = id.getNameAsString();
                if (id.isAsterisk()) wildcards.add(q + ".");
                else {
                    int dot = q.lastIndexOf('.');
                    explicit.put(dot >= 0 ? q.substring(dot + 1) : q, q);
                }
            }
            return new ImportIndex(explicit, wildcards);
        }
    }

    private static Optional<AnnotationExpr> findSpringAnnotation(NodeList<AnnotationExpr> annotations,
            String simple, ImportIndex imports, Set<String> allowedPrefixes) {
        for (AnnotationExpr ann : annotations) {
            if (simpleName(ann.getNameAsString()).equals(simple) && isSpringAnnotation(ann, imports, allowedPrefixes)) {
                return Optional.of(ann);
            }
        }
        return Optional.empty();
    }

    private static boolean isSpringAnnotation(AnnotationExpr ann, ImportIndex imports, Set<String> allowedPrefixes) {
        String raw = ann.getNameAsString();
        if (raw.contains(".")) {
            return allowedPrefixes.stream().anyMatch(raw::startsWith);
        }
        String fqcn = imports.explicit.get(raw);
        if (fqcn != null) {
            return allowedPrefixes.stream().anyMatch(fqcn::startsWith);
        }
        for (String wc : imports.wildcards) {
            if (allowedPrefixes.stream().anyMatch(wc::startsWith)) return true;
        }
        return false;
    }

    private static String attrString(AnnotationExpr ann, String key, Map<String, String> constants) {
        var list = attrStrings(ann, key, constants);
        return list.isEmpty() ? "" : list.getFirst();
    }

    private static List<String> attrStrings(AnnotationExpr ann, String key, Map<String, String> constants) {
        if (ann instanceof SingleMemberAnnotationExpr single && key.equals("value")) {
            return exprStrings(single.getMemberValue(), constants);
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(key)) return exprStrings(pair.getValue(), constants);
            }
        }
        return List.of();
    }

    private static List<String> exprStrings(Expression expr, Map<String, String> constants) {
        if (expr instanceof ArrayInitializerExpr array) {
            var out = new ArrayList<String>();
            for (var item : array.getValues()) {
                String v = HttpClientConstants.syntaxUri(item, constants).trim();
                if (!v.isEmpty()) out.add(v);
            }
            return out;
        }
        String v = HttpClientConstants.syntaxUri(expr, constants).trim();
        return v.isEmpty() ? List.of() : List.of(v);
    }

    private static String extractRequestMethod(AnnotationExpr ann) {
        if (!(ann instanceof NormalAnnotationExpr normal)) return "UNKNOWN";
        for (var pair : normal.getPairs()) {
            if (pair.getNameAsString().equals("method")) {
                String txt = pair.getValue().toString();
                for (String m : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS")) {
                    if (txt.contains(m)) return m;
                }
            }
        }
        return "UNKNOWN";
    }

    private static String combinePaths(String a, String b) {
        String p1 = a == null ? "" : a.trim();
        String p2 = b == null ? "" : b.trim();
        if (!p1.isEmpty() && !p1.startsWith("/")) p1 = "/" + p1;
        if (p1.endsWith("/") && p1.length() > 1) p1 = p1.substring(0, p1.length() - 1);
        if (!p2.isEmpty() && !p2.startsWith("/")) p2 = "/" + p2;
        if (p1.isEmpty()) return p2.isEmpty() ? "/" : p2;
        if (p2.isEmpty() || p2.equals("/")) return p1;
        return p1 + p2;
    }

    private static String appendPath(String base, String path) {
        if (base == null || base.isEmpty()) return path;
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = (path == null || path.isEmpty()) ? "/" : (path.startsWith("/") ? path : "/" + path);
        return b + p;
    }

    private static String normalizePlaceholderOrHost(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.startsWith("${") && t.endsWith("}")) {
            int colon = t.indexOf(':');
            if (colon > 2) t = t.substring(colon + 1, t.length() - 1).trim();
            else return "";
        }
        return t.replaceAll("^https?://", "").replaceAll("/.*$", "");
    }

    private static String normalizeUrlBase(String urlExpr) {
        if (urlExpr == null || urlExpr.isBlank()) return "";
        String t = urlExpr.trim();
        if (t.startsWith("${") && t.endsWith("}")) {
            int colon = t.indexOf(':');
            if (colon > 2) t = t.substring(colon + 1, t.length() - 1).trim();
            else return "";
        }
        return (t.startsWith("http://") || t.startsWith("https://")) ? t : "";
    }

    private static Optional<String> extractUriHost(String uri) {
        return com.evolution.analysis.polyglot.HttpUriPattern.host(uri);
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null && !b.isBlank() ? b : "");
    }

    private static String simpleName(String raw) {
        int dot = raw.lastIndexOf('.');
        return dot >= 0 ? raw.substring(dot + 1) : raw;
    }

    private static SourceCoordinates.Range range(Node node, SourceCoordinates coordinates) {
        var r = node.getRange().orElseThrow();
        int begin = coordinates.at(r.begin.line, r.begin.column).offset();
        int end = coordinates.at(r.end.line, r.end.column).offset() + 1;
        return coordinates.range(begin, end, SourceCoordinates.Encoding.UTF16);
    }

    private static String slot(Node node, SourceCoordinates coordinates) {
        if (node.getRange().isEmpty()) return "";
        var span = range(node, coordinates);
        return span.start() + ":" + span.end();
    }

    private static void emit(Node node, String category, String kind, String name, String owner,
            Map<String, String> attributes, SourceCoordinates coordinates, boolean recovered,
            JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        if (node.getRange().isEmpty()) return;
        var span = range(node, coordinates);
        String written = coordinates.text().substring(span.start(), Math.min(span.end(), span.start() + 4096));
        sink.accept(new JavaGrpcSourceFrontend.Fact(category, kind, name, written, span, "", recovered, attributes));
        counts[0]++;
        counts[1]++;
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new IOException("SYNTAX_CANCELLED");
        }
    }

    private static String toolIdentity() throws IOException {
        String existing = cachedToolIdentity;
        if (existing != null) return existing;
        try (var own = JavaServiceSyntaxExtractor.class.getResourceAsStream("JavaServiceSyntaxExtractor.class");
             var core = JavaParser.class.getResourceAsStream("JavaParser.class")) {
            if (own == null || core == null) throw new IOException("JAVA_SERVICE_SYNTAX_TOOL_BYTES_UNAVAILABLE");
            String computed = CanonicalJson.sha256(List.of(
                    PROVIDER,
                    "javaparser:3.28.2",
                    ContentDigest.sha256(own.readAllBytes()),
                    ContentDigest.sha256(core.readAllBytes()))).value();
            cachedToolIdentity = computed;
            return computed;
        }
    }
}
