package com.evolution.analysis.javaparser;

import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import com.evolution.analysis.polyglot.AmqpBindingFact;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Passive, import-verified Java source-syntax extractor for Spring Cloud HTTP clients
 * (@FeignClient, OAuth2RestTemplate, RestTemplate, RestClient, WebClient, DiscoveryClient,
 * LoadBalancerClient), Spring Cloud Gateway Java Fluent DSL (RouteLocatorBuilder,
 * GatewayRouterFunctions), Spring messaging (@RabbitListener, @KafkaListener, @StreamListener,
 * RabbitTemplate, KafkaTemplate, AMQP @Bean Queue/Exchange/Binding declarations),
 * bounded k-callsite wrapper folding, and Spring Cloud control-plane roles when compiling
 * with {@code --build=off} leaves dependencies outside exact semantic closure.
 */
public final class JavaServiceSyntaxExtractor {
    public static final String PROVIDER = "source.javaparser-service-syntax:m8.4-v1";

    private static final List<String> MARKERS = List.of(
            "FeignClient", "OAuth2RestTemplate", "OAuth2RestOperations",
            "RestClient", "RestTemplate", "RestOperations", "WebClient",
            "WebTarget", "ClientBuilder",
            "DiscoveryClient", "LoadBalancerClient",
            "RouteLocatorBuilder", "RouteLocator", "GatewayRouterFunctions",
            "RabbitListener", "KafkaListener", "StreamListener",
            "RabbitTemplate", "AmqpTemplate", "KafkaTemplate",
            "BindingBuilder", "TopicExchange", "DirectExchange", "FanoutExchange",
            "HeadersExchange", "CustomExchange", "QueueBuilder", "Declarables",
            "org.springframework.amqp.core.",
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

    private record ClientBaseSpec(String baseUri, String propertyKey, String spelRaw) {
        ClientBaseSpec(String baseUri, String propertyKey) {
            this(baseUri, propertyKey, "");
        }
    }

    private record AmqpExchangeDecl(String exchangeName, String exchangeType, String beanName, List<String> aliases) {}

    private record AmqpQueueDecl(String queueName, String beanName, List<String> aliases) {}

    private record AmqpBindingEmission(
            Node anchor,
            AmqpBindingFact fact,
            String queueBeanRef,
            String exchangeBeanRef,
            String exchangeBeanType,
            boolean dynamicRoutingKey,
            String rawRoutingKeyExpr) implements Comparable<AmqpBindingEmission> {
        @Override
        public int compareTo(AmqpBindingEmission other) {
            int cmp = this.fact.compareTo(other.fact);
            if (cmp != 0) return cmp;
            cmp = this.queueBeanRef.compareTo(other.queueBeanRef);
            if (cmp != 0) return cmp;
            cmp = this.exchangeBeanRef.compareTo(other.exchangeBeanRef);
            if (cmp != 0) return cmp;
            return this.rawRoutingKeyExpr.compareTo(other.rawRoutingKeyExpr);
        }
    }

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

    private static final Set<String> AMQP_EXCHANGE_SIMPLE_TYPES = Set.of(
            "Exchange", "AbstractExchange", "TopicExchange", "DirectExchange",
            "FanoutExchange", "HeadersExchange", "CustomExchange");

    private static final Set<String> GATEWAY_MVC_VERBS = Set.of(
            "GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS");

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
        HttpClientConstants.WrapperFoldingIndex wrapperIndex = HttpClientConstants.WrapperFoldingIndex.fromUnits(
                Map.of("unit.java", unit));
        HttpClientConstants.EnumConstantMap enumConstants = wrapperIndex.enumConstants();
        long[] counts = { 0L, 0L };
        var emittedCallSpans = new HashSet<String>();
        var methodCallsByRange = new HashMap<String, MethodCallExpr>();

        for (var typeDecl : unit.findAll(ClassOrInterfaceDeclaration.class)) {
            check(cancelled);
            String ownerSlot = slot(typeDecl, coordinates);
            Map<String, String> typeConstants = collectTypeConstants(typeDecl, imports, enumConstants);
            Map<String, String> typeSpelFields = collectSpelValueFields(typeDecl, typeConstants, imports, enumConstants);
            Map<String, ValueProperty> typeValueProps = collectValueProperties(typeDecl, typeConstants, imports, enumConstants);
            Map<String, String> typePropKeys = new LinkedHashMap<>();
            typeValueProps.forEach((fieldName, vp) -> {
                typePropKeys.put(fieldName, vp.propertyKey());
                if (!vp.defaultValue().isEmpty()) {
                    typeConstants.putIfAbsent(fieldName, vp.defaultValue());
                }
            });
            Map<String, ClientBaseSpec> typeClientBases = collectTypeClientBases(
                    typeDecl, typeConstants, typePropKeys, typeSpelFields, imports, enumConstants);
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

            // 1c. Passive AMQP/RabbitMQ @Bean Queue/Exchange/Binding declarations ([DiInj-Param])
            extractAmqpBeans(typeDecl, typeConstants, imports, enumConstants, ownerSlot,
                    coordinates, recovered, sink, counts, cancelled);

            // 2. @FeignClient interface & method mappings (with multi-profile ${key:default} & SpEL detection)
            Optional<AnnotationExpr> feignAnn = findSpringAnnotation(typeDecl.getAnnotations(), "FeignClient",
                    imports, Set.of("org.springframework.cloud.openfeign.", "org.springframework.cloud.netflix.feign."));
            if (feignAnn.isPresent()) {
                extractFeignClient(typeDecl, feignAnn.orElseThrow(), typeConstants, enumConstants,
                        imports, coordinates, recovered, sink, counts);
            }

            // 3. Method / constructor / initializer bodies
            for (MethodDeclaration method : typeDecl.getMethods()) {
                check(cancelled);
                String methodSlot = slot(method, coordinates);
                String activeSlot = methodSlot.isEmpty() ? ownerSlot : methodSlot;
                extractMessagingAnnotations(method.getAnnotations(), typeConstants, enumConstants, imports, activeSlot,
                        coordinates, recovered, sink, counts);
                if (method.getBody().isPresent()) {
                    extractBodyCalls(method.getBody().orElseThrow(), typeDecl, method.getParameters(),
                            typeConstants, typePropKeys, typeSpelFields, typeClientBases, typeFieldVarTypes,
                            imports, enumConstants, activeSlot, coordinates, recovered, sink, counts,
                            emittedCallSpans, methodCallsByRange, cancelled);
                }
            }
            for (ConstructorDeclaration ctor : typeDecl.getConstructors()) {
                check(cancelled);
                String activeSlot = slot(ctor, coordinates);
                extractBodyCalls(ctor.getBody(), typeDecl, ctor.getParameters(),
                        typeConstants, typePropKeys, typeSpelFields, typeClientBases, typeFieldVarTypes,
                        imports, enumConstants, activeSlot.isEmpty() ? ownerSlot : activeSlot,
                        coordinates, recovered, sink, counts, emittedCallSpans, methodCallsByRange, cancelled);
            }
        }

        // 4. Emit bounded k=2 callsite-sensitive folded wrapper calls (zero Cartesian mixing across callsites)
        emitFoldedWrapperCalls(wrapperIndex, methodCallsByRange, emittedCallSpans, coordinates, recovered, sink, counts);

        return new JavaGrpcSourceFrontend.Summary(unit.stream().count(), counts[1], parsed.getProblems().size(),
                PROVIDER, toolIdentity());
    }

    private static void emitFoldedWrapperCalls(
            HttpClientConstants.WrapperFoldingIndex wrapperIndex,
            Map<String, MethodCallExpr> methodCallsByRange,
            Set<String> emittedCallSpans,
            SourceCoordinates coordinates,
            boolean recovered,
            JavaGrpcSourceFrontend.Sink sink,
            long[] counts) throws IOException {
        for (var folded : wrapperIndex.foldCallSites("unit.java")) {
            String rangeKey = folded.startLine() + ":" + folded.startColumn() + ":"
                    + folded.endLine() + ":" + folded.endColumn();
            if (emittedCallSpans.contains(rangeKey)) continue;
            MethodCallExpr call = methodCallsByRange.get(rangeKey);
            if (call == null) continue;
            String ownerSlot = call.findAncestor(CallableDeclaration.class)
                    .map(c -> slot(c, coordinates))
                    .orElse("");
            switch (folded.primaryKind()) {
                case HTTP_URI, HTTP_HOST -> {
                    if (folded.dynamicUnresolved() || folded.uri().isEmpty()) continue;
                    var attrs = new TreeMap<String, String>();
                    attrs.put("method", folded.httpMethod().isEmpty() ? "UNKNOWN" : folded.httpMethod());
                    attrs.put("clientKind", "WRAPPER_FOLDED");
                    attrs.put("callSiteKey", folded.callSiteKey().callSiteId() + "->" + folded.callSiteKey().targetMethodId());
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    if (!folded.host().isEmpty()) {
                        attrs.put("targetService", folded.host());
                    } else {
                        extractUriHost(folded.uri()).ifPresent(h -> attrs.put("targetService", h));
                    }
                    emit(call, "HTTP_CALL_CANDIDATE", "wrapper_call", "\"" + folded.uri() + "\"",
                            ownerSlot, attrs, coordinates, recovered, sink, counts);
                }
                case AMQP_EXCHANGE, AMQP_KEY -> {
                    var attrs = new TreeMap<String, String>();
                    attrs.put("protocol", "RABBITMQ");
                    attrs.put("role", "PRODUCER");
                    attrs.put("callSiteKey", folded.callSiteKey().callSiteId() + "->" + folded.callSiteKey().targetMethodId());
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    if (!folded.exchange().isEmpty()) attrs.put("exchange", folded.exchange());
                    if (folded.dynamicUnresolved()) {
                        attrs.put("dynamicRoutingKey", "true");
                        attrs.put("symbolicTopic", "true");
                        if (!folded.rawUnresolvedExpr().isEmpty()) {
                            attrs.put("rawRoutingKeyExpr", folded.rawUnresolvedExpr());
                        }
                        String label = !folded.exchange().isEmpty() ? folded.exchange() : folded.rawUnresolvedExpr();
                        emit(call, "EVENT_PRODUCER_CANDIDATE", "wrapper_call", "\"" + label + "\"",
                                ownerSlot, attrs, coordinates, recovered, sink, counts);
                    } else if (!folded.routingKey().isEmpty() || !folded.exchange().isEmpty()) {
                        if (!folded.routingKey().isEmpty()) attrs.put("routingKey", folded.routingKey());
                        String label = !folded.exchange().isEmpty() ? folded.exchange() : folded.routingKey();
                        emit(call, "EVENT_PRODUCER_CANDIDATE", "wrapper_call", "\"" + label + "\"",
                                ownerSlot, attrs, coordinates, recovered, sink, counts);
                    }
                }
                case KAFKA_TOPIC -> {
                    if (folded.dynamicUnresolved() || folded.kafkaTopic().isEmpty()) continue;
                    var attrs = new TreeMap<String, String>();
                    attrs.put("protocol", "KAFKA");
                    attrs.put("role", "PRODUCER");
                    attrs.put("callSiteKey", folded.callSiteKey().callSiteId() + "->" + folded.callSiteKey().targetMethodId());
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    emit(call, "EVENT_PRODUCER_CANDIDATE", "wrapper_call", "\"" + folded.kafkaTopic() + "\"",
                            ownerSlot, attrs, coordinates, recovered, sink, counts);
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Passive AMQP/RabbitMQ @Bean Queue / Exchange / Binding Extractor ([DiInj-Param])
    // -------------------------------------------------------------------------

    private static void extractAmqpBeans(
            ClassOrInterfaceDeclaration typeDecl,
            Map<String, String> typeConstants,
            ImportIndex imports,
            HttpClientConstants.EnumConstantMap enumConstants,
            String ownerSlot,
            SourceCoordinates coordinates,
            boolean recovered,
            JavaGrpcSourceFrontend.Sink sink,
            long[] counts,
            BooleanSupplier cancelled) throws IOException {
        boolean hasAmqpImport = imports.explicit.values().stream().anyMatch(v -> v.startsWith("org.springframework.amqp.core."))
                || imports.wildcards.contains("org.springframework.amqp.core.");
        boolean hasExplicitAmqpFqn = typeDecl.toString().contains("org.springframework.amqp.core.");
        if (!hasAmqpImport && !hasExplicitAmqpFqn) {
            return;
        }

        var queuesByBeanName = new TreeMap<String, AmqpQueueDecl>();
        var queuesAll = new ArrayList<AmqpQueueDecl>();
        var exchangesByBeanName = new TreeMap<String, AmqpExchangeDecl>();
        var exchangesByType = new TreeMap<String, List<AmqpExchangeDecl>>();
        var exchangesAll = new ArrayList<AmqpExchangeDecl>();
        var bindingMethods = new ArrayList<MethodDeclaration>();

        for (MethodDeclaration method : typeDecl.getMethods()) {
            check(cancelled);
            Optional<AnnotationExpr> beanAnn = findSpringAnnotation(method.getAnnotations(), "Bean",
                    imports, Set.of("org.springframework.context.annotation."));
            if (beanAnn.isEmpty() || method.getBody().isEmpty()) continue;
            String retType = resolveImportedSpringType(method.getType().asString(), imports);
            if (!retType.startsWith("org.springframework.amqp.core.")) continue;
            String retSimple = simpleName(retType);
            List<String> beanNames = extractBeanNames(beanAnn.orElseThrow(), method.getNameAsString(), typeConstants, enumConstants);
            String primaryBeanName = beanNames.getFirst();

            if ("Queue".equals(retSimple)) {
                String qName = extractQueueNameFromBody(method.getBody().orElseThrow(), typeConstants, enumConstants);
                if (!qName.isEmpty()) {
                    var decl = new AmqpQueueDecl(qName, primaryBeanName, beanNames);
                    queuesAll.add(decl);
                    for (String bn : beanNames) queuesByBeanName.putIfAbsent(bn, decl);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("beanName", primaryBeanName);
                    if (beanNames.size() > 1) attrs.put("beanAliases", String.join(",", beanNames));
                    attrs.put("queueName", qName);
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    emit(method, "AMQP_QUEUE", "amqp_queue_bean", "\"" + qName + "\"", ownerSlot,
                            attrs, coordinates, recovered, sink, counts);
                }
            } else if (AMQP_EXCHANGE_SIMPLE_TYPES.contains(retSimple)) {
                String exName = extractExchangeNameFromBody(method.getBody().orElseThrow(), typeConstants, enumConstants);
                String exType = extractExchangeTypeFromBody(retSimple, method.getBody().orElseThrow());
                if (!exName.isEmpty()) {
                    var decl = new AmqpExchangeDecl(exName, exType, primaryBeanName, beanNames);
                    exchangesAll.add(decl);
                    exchangesByType.computeIfAbsent(exType, k -> new ArrayList<>()).add(decl);
                    for (String bn : beanNames) exchangesByBeanName.putIfAbsent(bn, decl);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("beanName", primaryBeanName);
                    if (beanNames.size() > 1) attrs.put("beanAliases", String.join(",", beanNames));
                    attrs.put("exchangeName", exName);
                    attrs.put("exchangeType", exType);
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    emit(method, "AMQP_EXCHANGE", "amqp_exchange_bean", "\"" + exName + "\"", ownerSlot,
                            attrs, coordinates, recovered, sink, counts);
                }
            } else if ("Binding".equals(retSimple) || "Declarables".equals(retSimple)) {
                bindingMethods.add(method);
            }
        }

        var bindingEmissions = new TreeSet<AmqpBindingEmission>();
        for (MethodDeclaration method : bindingMethods) {
            check(cancelled);
            BlockStmt body = method.getBody().orElseThrow();
            Map<String, String> localConsts = new LinkedHashMap<>(typeConstants);
            collectLocalConstants(body, localConsts, new LinkedHashMap<>(), enumConstants);

            // [DiInj-Param] parameter resolution: @Qualifier -> parameter name -> unique bean of matching type
            var paramQueueNames = new HashMap<String, String>();
            var paramQueueRefs = new HashMap<String, String>();
            var paramExchanges = new HashMap<String, AmqpExchangeDecl>();
            var paramExchangeRefs = new HashMap<String, String>();
            var paramExchangeTypes = new HashMap<String, String>();

            for (Parameter p : method.getParameters()) {
                String pName = p.getNameAsString();
                String pType = resolveImportedSpringType(p.getType().asString(), imports);
                String pSimple = simpleName(pType);
                String qualifier = extractQualifier(p, imports, localConsts, enumConstants);
                String lookupKey = !qualifier.isEmpty() ? qualifier : pName;
                if ("org.springframework.amqp.core.Queue".equals(pType)) {
                    AmqpQueueDecl matched = queuesByBeanName.get(lookupKey);
                    if (matched == null && queuesAll.size() == 1) matched = queuesAll.getFirst();
                    if (matched != null) {
                        paramQueueNames.put(pName, matched.queueName());
                    } else {
                        paramQueueRefs.put(pName, lookupKey);
                    }
                } else if (pType.startsWith("org.springframework.amqp.core.") && AMQP_EXCHANGE_SIMPLE_TYPES.contains(pSimple)) {
                    String wantType = amqpExchangeTypeFromSimpleName(pSimple);
                    AmqpExchangeDecl matched = exchangesByBeanName.get(lookupKey);
                    if (matched == null && !wantType.isEmpty()) {
                        var byType = exchangesByType.getOrDefault(wantType, List.of());
                        if (byType.size() == 1) matched = byType.getFirst();
                    }
                    if (matched == null && exchangesAll.size() == 1) {
                        matched = exchangesAll.getFirst();
                    }
                    if (matched != null) {
                        paramExchanges.put(pName, matched);
                    } else {
                        paramExchangeRefs.put(pName, lookupKey);
                        if (!wantType.isEmpty()) paramExchangeTypes.put(pName, wantType);
                    }
                }
            }

            // Local Queue / Exchange variables inside the @Bean Binding / Declarables method
            for (VariableDeclarator vd : body.findAll(VariableDeclarator.class)) {
                if (vd.getInitializer().isEmpty()) continue;
                String vType = resolveImportedSpringType(vd.getType().asString(), imports);
                String vSimple = simpleName(vType);
                Expression init = vd.getInitializer().orElseThrow();
                if ("org.springframework.amqp.core.Queue".equals(vType)) {
                    String qn = extractQueueNameFromExpr(init, localConsts, enumConstants);
                    if (!qn.isEmpty()) paramQueueNames.put(vd.getNameAsString(), qn);
                } else if (vType.startsWith("org.springframework.amqp.core.") && AMQP_EXCHANGE_SIMPLE_TYPES.contains(vSimple)) {
                    String en = extractExchangeNameFromExpr(init, localConsts, enumConstants);
                    String et = extractExchangeTypeFromExpr(vSimple, init);
                    if (!en.isEmpty()) {
                        paramExchanges.put(vd.getNameAsString(), new AmqpExchangeDecl(en, et, vd.getNameAsString(), List.of()));
                    }
                }
            }

            // Pattern A: Fluent BindingBuilder.bind(q).to(e)[.with(key) | .withQueueName()][.noargs()]
            for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
                if (!"bind".equals(call.getNameAsString()) || call.getArguments().size() != 1) continue;
                boolean isBindingBuilder = call.getScope().map(s -> {
                    String resolved = resolveImportedSpringType(s.toString(), imports);
                    return resolved.equals("org.springframework.amqp.core.BindingBuilder");
                }).orElse(imports.staticImportedNames.contains("bind"));
                if (!isBindingBuilder) continue;

                Expression queueExpr = call.getArgument(0);
                Expression exchangeExpr = null;
                Expression routingKeyExpr = null;
                boolean withQueueName = false;
                Node outerAnchor = call;

                Node cur = call;
                for (int step = 0; step < 8; step++) {
                    Node parent = cur.getParentNode().orElse(null);
                    if (!(parent instanceof MethodCallExpr parentCall)) break;
                    Node expected = cur;
                    if (parentCall.getScope().filter(s -> s == expected).isEmpty()) break;
                    String pn = parentCall.getNameAsString();
                    if ("to".equals(pn) && !parentCall.getArguments().isEmpty()) {
                        exchangeExpr = parentCall.getArgument(0);
                        outerAnchor = parentCall;
                    } else if ("with".equals(pn) && !parentCall.getArguments().isEmpty()) {
                        routingKeyExpr = parentCall.getArgument(0);
                        outerAnchor = parentCall;
                    } else if ("withQueueName".equals(pn)) {
                        withQueueName = true;
                        outerAnchor = parentCall;
                    } else if ("noargs".equals(pn) || "and".equals(pn)) {
                        outerAnchor = parentCall;
                    }
                    cur = parentCall;
                }
                if (exchangeExpr == null) continue;

                String queueName = resolveAmqpQueueExpr(queueExpr, paramQueueNames, queuesByBeanName, localConsts, enumConstants);
                String queueBeanRef = queueName.isEmpty() ? resolveBeanRefName(queueExpr, paramQueueRefs) : "";
                AmqpExchangeDecl exDecl = resolveAmqpExchangeExpr(exchangeExpr, paramExchanges, exchangesByBeanName,
                        localConsts, enumConstants, imports);
                String exName = exDecl != null ? exDecl.exchangeName() : "";
                String exType = exDecl != null ? exDecl.exchangeType() : "";
                String exBeanRef = exDecl == null ? resolveBeanRefName(exchangeExpr, paramExchangeRefs) : "";
                String exBeanType = exDecl == null ? resolveExchangeBeanTypeHint(exchangeExpr, paramExchangeTypes) : exType;

                String routingKeyPattern = "";
                boolean dynamicKey = false;
                String rawKeyExpr = "";
                if (withQueueName) {
                    routingKeyPattern = queueName;
                } else if (routingKeyExpr != null) {
                    String foldedKey = HttpClientConstants.syntaxUri(routingKeyExpr, localConsts, enumConstants).trim();
                    if (!foldedKey.isEmpty()) {
                        routingKeyPattern = foldedKey;
                    } else {
                        dynamicKey = true;
                        rawKeyExpr = symbolicTopicExpr(routingKeyExpr);
                    }
                } else if (AmqpBindingFact.TYPE_FANOUT.equals(exType) || AmqpBindingFact.TYPE_FANOUT.equals(exBeanType)) {
                    routingKeyPattern = "#";
                    exType = AmqpBindingFact.TYPE_FANOUT;
                }

                String spanStr = slot(outerAnchor, coordinates);
                var fact = new AmqpBindingFact(exName, routingKeyPattern, queueName,
                        exType.isEmpty() ? exBeanType : exType, spanStr);
                bindingEmissions.add(new AmqpBindingEmission(outerAnchor, fact, queueBeanRef, exBeanRef,
                        exBeanType, dynamicKey, rawKeyExpr));
            }

            // Pattern B: Constructor new Binding(destination, DestinationType.QUEUE, exchange, routingKey, args)
            for (ObjectCreationExpr oce : body.findAll(ObjectCreationExpr.class)) {
                String createdType = resolveImportedSpringType(oce.getType().asString(), imports);
                if (!"org.springframework.amqp.core.Binding".equals(createdType)) continue;
                if (oce.getArguments().size() < 4) continue;
                String destTypeText = oce.getArgument(1).toString();
                if (destTypeText.contains("EXCHANGE") && !destTypeText.contains("QUEUE")) continue;

                String queueName = resolveAmqpStringOrBeanGetter(oce.getArgument(0), paramQueueNames,
                        queuesByBeanName, localConsts, enumConstants);
                String queueBeanRef = queueName.isEmpty() ? resolveBeanRefName(oce.getArgument(0), paramQueueRefs) : "";
                String exName = resolveAmqpExchangeNameOrBeanGetter(oce.getArgument(2), paramExchanges,
                        exchangesByBeanName, localConsts, enumConstants);
                String exType = inferExchangeTypeForConstructorArg(oce.getArgument(2), paramExchanges, exchangesByBeanName);
                String exBeanRef = exName.isEmpty() ? resolveBeanRefName(oce.getArgument(2), paramExchangeRefs) : "";
                String exBeanType = exType.isEmpty() ? resolveExchangeBeanTypeHint(oce.getArgument(2), paramExchangeTypes) : exType;

                Expression keyArg = oce.getArgument(3);
                String routingKeyPattern = HttpClientConstants.syntaxUri(keyArg, localConsts, enumConstants).trim();
                boolean dynamicKey = false;
                String rawKeyExpr = "";
                if (routingKeyPattern.isEmpty()) {
                    boolean explicitEmpty = keyArg instanceof StringLiteralExpr lit && lit.asString().isEmpty();
                    if (AmqpBindingFact.TYPE_FANOUT.equals(exType) || explicitEmpty) {
                        routingKeyPattern = AmqpBindingFact.TYPE_FANOUT.equals(exType) ? "#" : "";
                    } else if (!(keyArg instanceof NullLiteralExpr)) {
                        dynamicKey = true;
                        rawKeyExpr = symbolicTopicExpr(keyArg);
                    }
                }
                String effectiveType = !exType.isEmpty() ? exType
                        : (routingKeyPattern.contains("*") || routingKeyPattern.contains("#")
                                ? AmqpBindingFact.TYPE_TOPIC : AmqpBindingFact.TYPE_DIRECT);
                String spanStr = slot(oce, coordinates);
                var fact = new AmqpBindingFact(exName, routingKeyPattern, queueName, effectiveType, spanStr);
                bindingEmissions.add(new AmqpBindingEmission(oce, fact, queueBeanRef, exBeanRef,
                        exBeanType, dynamicKey, rawKeyExpr));
            }
        }

        // Emit sorted deterministically via TreeSet<AmqpBindingEmission>
        for (AmqpBindingEmission em : bindingEmissions) {
            AmqpBindingFact f = em.fact();
            var attrs = new TreeMap<String, String>();
            if (!f.exchangeName().isEmpty()) attrs.put("exchangeName", f.exchangeName());
            attrs.put("routingKeyPattern", f.routingKeyPattern());
            if (!f.queueName().isEmpty()) attrs.put("queueName", f.queueName());
            attrs.put("exchangeType", f.exchangeType());
            if (!f.sourceSpan().isEmpty()) attrs.put("sourceSpan", f.sourceSpan());
            if (!em.queueBeanRef().isEmpty()) attrs.put("queueBeanRef", em.queueBeanRef());
            if (!em.exchangeBeanRef().isEmpty()) attrs.put("exchangeBeanRef", em.exchangeBeanRef());
            if (!em.exchangeBeanType().isEmpty()) attrs.put("exchangeBeanType", em.exchangeBeanType());
            if (em.dynamicRoutingKey()) {
                attrs.put("dynamicRoutingKey", "true");
                if (!em.rawRoutingKeyExpr().isEmpty()) attrs.put("rawRoutingKeyExpr", em.rawRoutingKeyExpr());
            }
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            String name = "\"" + f.exchangeName() + "|" + f.routingKeyPattern() + "|" + f.queueName() + "\"";
            emit(em.anchor(), "AMQP_BINDING", "amqp_binding_bean", name, ownerSlot,
                    attrs, coordinates, recovered, sink, counts);
        }
    }

    private static List<String> extractBeanNames(AnnotationExpr beanAnn, String methodName,
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants) {
        var names = new LinkedHashSet<String>();
        for (String v : attrStrings(beanAnn, "name", constants, enumConstants)) {
            if (!v.isBlank()) names.add(v.trim());
        }
        for (String v : attrStrings(beanAnn, "value", constants, enumConstants)) {
            if (!v.isBlank()) names.add(v.trim());
        }
        names.add(methodName);
        return List.copyOf(names);
    }

    private static String extractQualifier(Parameter param, ImportIndex imports,
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants) {
        Optional<AnnotationExpr> qual = findSpringAnnotation(param.getAnnotations(), "Qualifier",
                imports, Set.of("org.springframework.beans.factory.annotation."));
        if (qual.isEmpty()) return "";
        return attrString(qual.orElseThrow(), "value", constants, enumConstants).trim();
    }

    private static String extractQueueNameFromBody(BlockStmt body, Map<String, String> typeConstants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        var consts = new LinkedHashMap<>(typeConstants);
        collectLocalConstants(body, consts, new LinkedHashMap<>(), enumConstants);
        for (ReturnStmt ret : body.findAll(ReturnStmt.class)) {
            if (ret.getExpression().isEmpty()) continue;
            Expression expr = ret.getExpression().orElseThrow();
            String direct = extractQueueNameFromExpr(expr, consts, enumConstants);
            if (!direct.isEmpty()) return direct;
            if (expr instanceof NameExpr ne) {
                for (VariableDeclarator vd : body.findAll(VariableDeclarator.class)) {
                    if (vd.getNameAsString().equals(ne.getNameAsString()) && vd.getInitializer().isPresent()) {
                        String fromVar = extractQueueNameFromExpr(vd.getInitializer().orElseThrow(), consts, enumConstants);
                        if (!fromVar.isEmpty()) return fromVar;
                    }
                }
            }
        }
        return "";
    }

    private static String extractQueueNameFromExpr(Expression expr, Map<String, String> consts,
            HttpClientConstants.EnumConstantMap enumConstants) {
        if (expr instanceof ObjectCreationExpr oce && "Queue".equals(simpleName(oce.getType().asString()))
                && !oce.getArguments().isEmpty()) {
            return HttpClientConstants.syntaxUri(oce.getArgument(0), consts, enumConstants).trim();
        }
        Expression cur = expr;
        int steps = 0;
        while (cur instanceof MethodCallExpr mc && steps++ < 8) {
            if (Set.of("durable", "nonDurable").contains(mc.getNameAsString()) && !mc.getArguments().isEmpty()) {
                return HttpClientConstants.syntaxUri(mc.getArgument(0), consts, enumConstants).trim();
            }
            cur = mc.getScope().orElse(null);
        }
        return "";
    }

    private static String extractExchangeNameFromBody(BlockStmt body, Map<String, String> typeConstants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        var consts = new LinkedHashMap<>(typeConstants);
        collectLocalConstants(body, consts, new LinkedHashMap<>(), enumConstants);
        for (ReturnStmt ret : body.findAll(ReturnStmt.class)) {
            if (ret.getExpression().isEmpty()) continue;
            Expression expr = ret.getExpression().orElseThrow();
            String direct = extractExchangeNameFromExpr(expr, consts, enumConstants);
            if (!direct.isEmpty()) return direct;
            if (expr instanceof NameExpr ne) {
                for (VariableDeclarator vd : body.findAll(VariableDeclarator.class)) {
                    if (vd.getNameAsString().equals(ne.getNameAsString()) && vd.getInitializer().isPresent()) {
                        String fromVar = extractExchangeNameFromExpr(vd.getInitializer().orElseThrow(), consts, enumConstants);
                        if (!fromVar.isEmpty()) return fromVar;
                    }
                }
            }
        }
        return "";
    }

    private static String extractExchangeNameFromExpr(Expression expr, Map<String, String> consts,
            HttpClientConstants.EnumConstantMap enumConstants) {
        if (expr instanceof ObjectCreationExpr oce && AMQP_EXCHANGE_SIMPLE_TYPES.contains(simpleName(oce.getType().asString()))
                && !oce.getArguments().isEmpty()) {
            return HttpClientConstants.syntaxUri(oce.getArgument(0), consts, enumConstants).trim();
        }
        Expression cur = expr;
        int steps = 0;
        while (cur instanceof MethodCallExpr mc && steps++ < 8) {
            if (Set.of("topicExchange", "directExchange", "fanoutExchange", "headersExchange")
                    .contains(mc.getNameAsString()) && !mc.getArguments().isEmpty()) {
                return HttpClientConstants.syntaxUri(mc.getArgument(0), consts, enumConstants).trim();
            }
            cur = mc.getScope().orElse(null);
        }
        return "";
    }

    private static String extractExchangeTypeFromBody(String retSimple, BlockStmt body) {
        String byRet = amqpExchangeTypeFromSimpleName(retSimple);
        if (!byRet.isEmpty()) return byRet;
        for (ReturnStmt ret : body.findAll(ReturnStmt.class)) {
            if (ret.getExpression().isEmpty()) continue;
            String byExpr = extractExchangeTypeFromExpr(retSimple, ret.getExpression().orElseThrow());
            if (!byExpr.isEmpty()) return byExpr;
        }
        return AmqpBindingFact.TYPE_TOPIC;
    }

    private static String extractExchangeTypeFromExpr(String fallbackSimple, Expression expr) {
        if (expr instanceof ObjectCreationExpr oce) {
            String byType = amqpExchangeTypeFromSimpleName(simpleName(oce.getType().asString()));
            if (!byType.isEmpty()) return byType;
        }
        String text = expr.toString();
        if (text.contains("fanoutExchange") || text.contains("FanoutExchange")) return AmqpBindingFact.TYPE_FANOUT;
        if (text.contains("directExchange") || text.contains("DirectExchange")) return AmqpBindingFact.TYPE_DIRECT;
        if (text.contains("headersExchange") || text.contains("HeadersExchange")) return AmqpBindingFact.TYPE_HEADERS;
        if (text.contains("topicExchange") || text.contains("TopicExchange")) return AmqpBindingFact.TYPE_TOPIC;
        String byFallback = amqpExchangeTypeFromSimpleName(fallbackSimple);
        return byFallback.isEmpty() ? AmqpBindingFact.TYPE_TOPIC : byFallback;
    }

    private static String amqpExchangeTypeFromSimpleName(String simple) {
        return switch (simple) {
            case "TopicExchange" -> AmqpBindingFact.TYPE_TOPIC;
            case "DirectExchange" -> AmqpBindingFact.TYPE_DIRECT;
            case "FanoutExchange" -> AmqpBindingFact.TYPE_FANOUT;
            case "HeadersExchange" -> AmqpBindingFact.TYPE_HEADERS;
            case "CustomExchange" -> AmqpBindingFact.TYPE_CUSTOM;
            default -> "";
        };
    }

    private static String resolveAmqpQueueExpr(Expression expr, Map<String, String> paramQueueNames,
            Map<String, AmqpQueueDecl> queuesByBeanName, Map<String, String> consts,
            HttpClientConstants.EnumConstantMap enumConstants) {
        if (expr instanceof NameExpr ne) {
            String p = paramQueueNames.get(ne.getNameAsString());
            if (p != null) return p;
            AmqpQueueDecl bean = queuesByBeanName.get(ne.getNameAsString());
            if (bean != null) return bean.queueName();
        }
        if (expr instanceof MethodCallExpr mc && mc.getArguments().isEmpty()) {
            AmqpQueueDecl bean = queuesByBeanName.get(mc.getNameAsString());
            if (bean != null) return bean.queueName();
        }
        return extractQueueNameFromExpr(expr, consts, enumConstants);
    }

    private static AmqpExchangeDecl resolveAmqpExchangeExpr(Expression expr, Map<String, AmqpExchangeDecl> paramExchanges,
            Map<String, AmqpExchangeDecl> exchangesByBeanName, Map<String, String> consts,
            HttpClientConstants.EnumConstantMap enumConstants, ImportIndex imports) {
        if (expr instanceof NameExpr ne) {
            AmqpExchangeDecl p = paramExchanges.get(ne.getNameAsString());
            if (p != null) return p;
            AmqpExchangeDecl bean = exchangesByBeanName.get(ne.getNameAsString());
            if (bean != null) return bean;
        }
        if (expr instanceof MethodCallExpr mc && mc.getArguments().isEmpty()) {
            AmqpExchangeDecl bean = exchangesByBeanName.get(mc.getNameAsString());
            if (bean != null) return bean;
        }
        String inlineName = extractExchangeNameFromExpr(expr, consts, enumConstants);
        if (!inlineName.isEmpty()) {
            return new AmqpExchangeDecl(inlineName, extractExchangeTypeFromExpr("", expr), "", List.of());
        }
        return null;
    }

    private static String resolveAmqpStringOrBeanGetter(Expression expr, Map<String, String> paramQueueNames,
            Map<String, AmqpQueueDecl> queuesByBeanName, Map<String, String> consts,
            HttpClientConstants.EnumConstantMap enumConstants) {
        String folded = HttpClientConstants.syntaxUri(expr, consts, enumConstants).trim();
        if (!folded.isEmpty()) return folded;
        if (expr instanceof MethodCallExpr mc && "getName".equals(mc.getNameAsString()) && mc.getScope().isPresent()) {
            return resolveAmqpQueueExpr(mc.getScope().orElseThrow(), paramQueueNames, queuesByBeanName, consts, enumConstants);
        }
        return "";
    }

    private static String resolveAmqpExchangeNameOrBeanGetter(Expression expr,
            Map<String, AmqpExchangeDecl> paramExchanges, Map<String, AmqpExchangeDecl> exchangesByBeanName,
            Map<String, String> consts, HttpClientConstants.EnumConstantMap enumConstants) {
        String folded = HttpClientConstants.syntaxUri(expr, consts, enumConstants).trim();
        if (!folded.isEmpty()) return folded;
        if (expr instanceof MethodCallExpr mc && "getName".equals(mc.getNameAsString()) && mc.getScope().isPresent()) {
            Expression sc = mc.getScope().orElseThrow();
            if (sc instanceof NameExpr ne) {
                AmqpExchangeDecl d = paramExchanges.getOrDefault(ne.getNameAsString(), exchangesByBeanName.get(ne.getNameAsString()));
                if (d != null) return d.exchangeName();
            } else if (sc instanceof MethodCallExpr smc && smc.getArguments().isEmpty()) {
                AmqpExchangeDecl d = exchangesByBeanName.get(smc.getNameAsString());
                if (d != null) return d.exchangeName();
            }
        }
        return "";
    }

    private static String inferExchangeTypeForConstructorArg(Expression expr,
            Map<String, AmqpExchangeDecl> paramExchanges, Map<String, AmqpExchangeDecl> exchangesByBeanName) {
        if (expr instanceof MethodCallExpr mc && "getName".equals(mc.getNameAsString()) && mc.getScope().isPresent()) {
            Expression sc = mc.getScope().orElseThrow();
            if (sc instanceof NameExpr ne) {
                AmqpExchangeDecl d = paramExchanges.getOrDefault(ne.getNameAsString(), exchangesByBeanName.get(ne.getNameAsString()));
                if (d != null) return d.exchangeType();
            } else if (sc instanceof MethodCallExpr smc && smc.getArguments().isEmpty()) {
                AmqpExchangeDecl d = exchangesByBeanName.get(smc.getNameAsString());
                if (d != null) return d.exchangeType();
            }
        }
        return "";
    }

    private static String resolveBeanRefName(Expression expr, Map<String, String> paramRefs) {
        if (expr instanceof NameExpr ne) {
            return paramRefs.getOrDefault(ne.getNameAsString(), ne.getNameAsString());
        }
        if (expr instanceof MethodCallExpr mc) {
            if ("getName".equals(mc.getNameAsString()) && mc.getScope().isPresent()) {
                return resolveBeanRefName(mc.getScope().orElseThrow(), paramRefs);
            }
            if (mc.getArguments().isEmpty()) return mc.getNameAsString();
        }
        return "";
    }

    private static String resolveExchangeBeanTypeHint(Expression expr, Map<String, String> paramTypes) {
        if (expr instanceof NameExpr ne) {
            return paramTypes.getOrDefault(ne.getNameAsString(), "");
        }
        if (expr instanceof MethodCallExpr mc && "getName".equals(mc.getNameAsString()) && mc.getScope().isPresent()) {
            return resolveExchangeBeanTypeHint(mc.getScope().orElseThrow(), paramTypes);
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // @FeignClient (Multi-profile ${key:default} & SpEL #{...})
    // -------------------------------------------------------------------------

    private static void extractFeignClient(ClassOrInterfaceDeclaration iface, AnnotationExpr feignAnn,
            Map<String, String> typeConstants, HttpClientConstants.EnumConstantMap enumConstants,
            ImportIndex imports, SourceCoordinates coordinates,
            boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        String serviceName = firstNonBlank(
                attrString(feignAnn, "name", typeConstants, enumConstants),
                attrString(feignAnn, "value", typeConstants, enumConstants));
        String urlExpr = attrString(feignAnn, "url", typeConstants, enumConstants);
        String classPath = attrString(feignAnn, "path", typeConstants, enumConstants);
        if (classPath.isEmpty()) {
            Optional<AnnotationExpr> classReq = findSpringAnnotation(iface.getAnnotations(), "RequestMapping",
                    imports, Set.of("org.springframework.web.bind.annotation."));
            if (classReq.isPresent()) {
                classPath = attrString(classReq.orElseThrow(), "value", typeConstants, enumConstants);
                if (classPath.isEmpty()) classPath = attrString(classReq.orElseThrow(), "path", typeConstants, enumConstants);
            }
        }
        String declaredProfile = extractDeclaredProfile(iface.getAnnotations(), imports, typeConstants, enumConstants);
        String spelRaw = firstSpel(urlExpr, serviceName, classPath);
        boolean isSpel = !spelRaw.isEmpty();

        String cleanSvc = isSpel ? "" : normalizePlaceholderOrHost(serviceName);
        String cleanUrl = isSpel ? "" : normalizeUrlBase(urlExpr);
        String propertyKey = "";
        String servicePropertyKey = "";
        if (!isSpel && serviceName.contains("${")) {
            Optional<ValueProperty> svcProp = parseValuePlaceholder(serviceName);
            if (svcProp.isPresent()) {
                servicePropertyKey = svcProp.orElseThrow().propertyKey();
                if (cleanSvc.isEmpty() && !svcProp.orElseThrow().defaultValue().isEmpty()) {
                    cleanSvc = normalizePlaceholderOrHost(svcProp.orElseThrow().defaultValue());
                }
            }
        }
        if (!isSpel && urlExpr.contains("${")) {
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
                        attrString(ann, "value", typeConstants, enumConstants),
                        attrString(ann, "path", typeConstants, enumConstants));
                String combinedPath = combinePaths(classPath, methodPath);
                String fullUri = isSpel ? "" : (!baseTarget.isEmpty() ? appendPath(baseTarget, combinedPath) : combinedPath);
                if (!fullUri.isEmpty() || !propertyKey.isEmpty() || !servicePropertyKey.isEmpty() || isSpel) {
                    var attrs = new TreeMap<String, String>();
                    attrs.put("method", httpMethod);
                    attrs.put("clientKind", "FEIGN_CLIENT");
                    attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
                    if (!cleanSvc.isEmpty()) {
                        attrs.put("targetService", cleanSvc);
                    } else if (!isSpel) {
                        extractUriHost(fullUri).ifPresent(host -> attrs.put("targetService", host));
                    }
                    if (!urlExpr.isEmpty()) attrs.put("urlExpr", urlExpr);
                    if (!propertyKey.isEmpty()) attrs.put("propertyKey", propertyKey);
                    if (!servicePropertyKey.isEmpty()) attrs.put("servicePropertyKey", servicePropertyKey);
                    if (!declaredProfile.isEmpty()) attrs.put("profile", declaredProfile);
                    if (isSpel) {
                        attrs.put("spelExpression", "true");
                        attrs.put("spelRaw", spelRaw);
                    }
                    emit(m, "HTTP_CALL_CANDIDATE", "feign_method", "\"" + fullUri + "\"",
                            slot(iface, coordinates), attrs, coordinates, recovered, sink, counts);
                    emittedMethod = true;
                }
            }
        }
        if (!emittedMethod && (!baseTarget.isEmpty() || !propertyKey.isEmpty() || !servicePropertyKey.isEmpty() || isSpel)) {
            var attrs = new TreeMap<String, String>();
            attrs.put("method", "UNKNOWN");
            attrs.put("clientKind", "FEIGN_CLIENT");
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            String ifaceUri = isSpel ? ""
                    : (!baseTarget.isEmpty()
                            ? appendPath(baseTarget, classPath.isEmpty() ? "/" : classPath)
                            : (classPath.isEmpty() ? "" : combinePaths(classPath, "")));
            if (!cleanSvc.isEmpty()) {
                attrs.put("targetService", cleanSvc);
            } else if (!isSpel) {
                extractUriHost(ifaceUri).ifPresent(host -> attrs.put("targetService", host));
            }
            if (!urlExpr.isEmpty()) attrs.put("urlExpr", urlExpr);
            if (!propertyKey.isEmpty()) attrs.put("propertyKey", propertyKey);
            if (!servicePropertyKey.isEmpty()) attrs.put("servicePropertyKey", servicePropertyKey);
            if (!declaredProfile.isEmpty()) attrs.put("profile", declaredProfile);
            if (isSpel) {
                attrs.put("spelExpression", "true");
                attrs.put("spelRaw", spelRaw);
            }
            emit(iface, "HTTP_CALL_CANDIDATE", "feign_interface", "\"" + ifaceUri + "\"",
                    slot(iface, coordinates), attrs, coordinates, recovered, sink, counts);
        }
    }

    private static void extractMessagingAnnotations(NodeList<AnnotationExpr> annotations,
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants,
            ImportIndex imports, String ownerSlot,
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
            collectAnnotationTopics(ann, keys, constants, enumConstants, destinations);
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
            Map<String, String> typePropKeys, Map<String, String> typeSpelFields,
            Map<String, ClientBaseSpec> typeClientBases,
            Map<String, String> typeFieldVarTypes, ImportIndex imports,
            HttpClientConstants.EnumConstantMap enumConstants, String ownerSlot,
            SourceCoordinates coordinates, boolean recovered, JavaGrpcSourceFrontend.Sink sink,
            long[] counts, Set<String> emittedCallSpans,
            Map<String, MethodCallExpr> methodCallsByRange, BooleanSupplier cancelled) throws IOException {
        var methodCalls = new ArrayList<MethodCallExpr>();
        var varDecls = new ArrayList<VariableDeclarationExpr>();
        var varDeclarators = new ArrayList<VariableDeclarator>();
        var reassigned = new HashSet<String>();
        body.walk(Node.TreeTraversal.PREORDER, node -> {
            if (node instanceof MethodCallExpr mc) {
                methodCalls.add(mc);
                mc.getRange().ifPresent(r -> methodCallsByRange.putIfAbsent(
                        r.begin.line + ":" + r.begin.column + ":" + r.end.line + ":" + r.end.column, mc));
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
        Map<String, String> spelVars = new LinkedHashMap<>(typeSpelFields);
        Map<String, String> varTypes = new HashMap<>(typeFieldVarTypes);
        for (Parameter p : parameters) {
            String paramName = p.getNameAsString();
            constants.remove(paramName);
            propKeys.remove(paramName);
            spelVars.remove(paramName);
            String rawType = p.getType().asString();
            String springType = resolveImportedSpringType(rawType, imports);
            if (!springType.isEmpty()) {
                varTypes.put(paramName, springType);
            }
            if (isStringType(rawType)) {
                Optional<AnnotationExpr> valAnn = findSpringAnnotation(p.getAnnotations(), "Value",
                        imports, Set.of("org.springframework.beans.factory.annotation."));
                if (valAnn.isPresent()) {
                    String rawVal = attrString(valAnn.orElseThrow(), "value", typeConstants, enumConstants);
                    if (isSpelExpression(rawVal)) {
                        spelVars.put(paramName, rawVal);
                    } else {
                        parseValuePlaceholder(rawVal).ifPresent(vp -> {
                            propKeys.put(paramName, vp.propertyKey());
                            if (!vp.defaultValue().isEmpty()) {
                                constants.put(paramName, vp.defaultValue());
                            }
                        });
                    }
                }
            }
        }
        for (VariableDeclarationExpr local : varDecls) {
            if (!isStringType(local.getCommonType().asString())) continue;
            for (VariableDeclarator v : local.getVariables()) {
                String varName = v.getNameAsString();
                constants.remove(varName);
                propKeys.remove(varName);
                spelVars.remove(varName);
                if (!local.isFinal() && reassigned.contains(varName)) continue;
                v.getInitializer().ifPresent(init -> {
                    String s = HttpClientConstants.syntaxUri(init, constants, enumConstants);
                    if (!s.isEmpty()) constants.put(varName, s);
                    String pk = extractPropertyKey(init, constants, propKeys, enumConstants, 0);
                    if (!pk.isEmpty()) propKeys.put(varName, pk);
                    String sp = extractSpelExpr(init, constants, spelVars, enumConstants, 0);
                    if (!sp.isEmpty()) spelVars.put(varName, sp);
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
                ClientBaseSpec spec = extractFluentBaseSpec(v.getInitializer().orElseThrow(), constants, propKeys,
                        spelVars, clientBases, imports, enumConstants);
                if (spec != null) {
                    clientBases.put(v.getNameAsString(), spec);
                }
                if (localDeclaratorsByName == null) localDeclaratorsByName = new HashMap<>();
                localDeclaratorsByName.computeIfAbsent(v.getNameAsString(), k -> new ArrayList<>()).add(v);
            }
        }
        final Map<String, List<VariableDeclarator>> indexedDeclarators = localDeclaratorsByName == null ? Map.of() : localDeclaratorsByName;

        // 0. Spring Cloud Gateway Java Fluent DSL (RouteLocatorBuilder & GatewayRouterFunctions)
        extractGatewayJavaDsl(methodCalls, varTypes, constants, propKeys, spelVars, imports, enumConstants,
                ownerSlot, coordinates, recovered, sink, counts, emittedCallSpans, cancelled);

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
                    String svc = HttpClientConstants.syntaxUri(call.getArgument(0), constants, enumConstants).trim();
                    if (!svc.isEmpty()) {
                        markEmitted(call, emittedCallSpans);
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
                    markEmitted(call, emittedCallSpans);
                    emitRabbitProducer(call, constants, enumConstants, ownerSlot, coordinates, recovered, sink, counts);
                }
                continue;
            }
            if (name.equals("send") && isMessagingTemplate(scopeType, "KafkaTemplate", "KafkaTemplate")) {
                if (!call.getArguments().isEmpty()) {
                    markEmitted(call, emittedCallSpans);
                    emitMessagingProducer(call, call.getArgument(0), "KAFKA", constants, enumConstants, ownerSlot,
                            coordinates, recovered, sink, counts);
                }
                continue;
            }

            // 3. Standard JAX-RS client WebTarget/ClientBuilder calls (when JARs absent under --build=off)
            if (name.equals("request") && isJaxRsWebTargetChain(call, varTypes, imports)) {
                String jaxRsUri = resolveJaxRsWebTargetUri(call, ownerType, indexedDeclarators, constants, enumConstants);
                if (!jaxRsUri.isEmpty()) {
                    markEmitted(call, emittedCallSpans);
                    String jaxRsVerb = jaxRsInvocationVerb(call, constants, enumConstants);
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
            String uri = urlArg == null ? "" : HttpClientConstants.syntaxUri(urlArg, constants, enumConstants).trim();
            String propertyKey = urlArg == null ? "" : extractPropertyKey(urlArg, constants, propKeys, enumConstants, 0);
            String spelRaw = urlArg == null ? "" : extractSpelExpr(urlArg, constants, spelVars, enumConstants, 0);
            if (!propertyKey.isEmpty() && uri.startsWith("{param}")) {
                uri = "";
            }
            if (isFluent) {
                ClientBaseSpec base = extractFluentBaseSpec(call.getScope().orElse(null), constants, propKeys,
                        spelVars, clientBases, imports, enumConstants);
                if (base != null) {
                    if (propertyKey.isEmpty() && !base.propertyKey().isEmpty()) {
                        propertyKey = base.propertyKey();
                    }
                    if (spelRaw.isEmpty() && !base.spelRaw().isEmpty()) {
                        spelRaw = base.spelRaw();
                    }
                    if (!base.baseUri().isEmpty() && !hasUriScheme(uri)) {
                        uri = uri.isEmpty() ? base.baseUri() : appendPath(base.baseUri(), uri);
                    }
                }
            }
            if (spelRaw.isEmpty() && isSpelExpression(uri)) {
                spelRaw = uri;
            }
            if (uri.isEmpty() && propertyKey.isEmpty() && spelRaw.isEmpty()) continue;
            markEmitted(call, emittedCallSpans);
            String verb = isFluent ? fluentVerb(call) : restVerb(name);
            var attrs = new TreeMap<String, String>();
            attrs.put("method", verb.isEmpty() ? "UNKNOWN" : verb);
            attrs.put("clientKind", isFluent ? "REST_OR_WEB_CLIENT" : "REST_TEMPLATE");
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            if (!propertyKey.isEmpty()) attrs.put("propertyKey", propertyKey);
            if (!spelRaw.isEmpty()) {
                attrs.put("spelExpression", "true");
                attrs.put("spelRaw", spelRaw);
                uri = "";
            } else {
                extractUriHost(uri).ifPresent(host -> attrs.put("targetService", host));
            }
            emit(call, "HTTP_CALL_CANDIDATE", "method_call", "\"" + uri + "\"", ownerSlot,
                    attrs, coordinates, recovered, sink, counts);
        }
    }

    // -------------------------------------------------------------------------
    // Spring Cloud Gateway Java Fluent DSL (RouteLocatorBuilder & GatewayRouterFunctions)
    // -------------------------------------------------------------------------

    private static void extractGatewayJavaDsl(
            List<MethodCallExpr> methodCalls,
            Map<String, String> varTypes,
            Map<String, String> constants,
            Map<String, String> propKeys,
            Map<String, String> spelVars,
            ImportIndex imports,
            HttpClientConstants.EnumConstantMap enumConstants,
            String ownerSlot,
            SourceCoordinates coordinates,
            boolean recovered,
            JavaGrpcSourceFrontend.Sink sink,
            long[] counts,
            Set<String> emittedCallSpans,
            BooleanSupplier cancelled) throws IOException {
        boolean hasGatewayImport = imports.explicit.values().stream().anyMatch(v -> v.startsWith("org.springframework.cloud.gateway."))
                || imports.wildcards.stream().anyMatch(w -> w.startsWith("org.springframework.cloud.gateway."));
        boolean hasGatewayVar = varTypes.values().stream().anyMatch(v -> v.startsWith("org.springframework.cloud.gateway."));
        if (!hasGatewayImport && !hasGatewayVar) {
            return;
        }

        // Form A: RouteLocatorBuilder.routes().route([id,] r -> r.path(...).filters(...).uri(...))
        for (MethodCallExpr call : methodCalls) {
            check(cancelled);
            if (!"route".equals(call.getNameAsString()) || call.getArguments().isEmpty()) continue;
            if (!isRouteLocatorBuilderChain(call, varTypes, imports)) continue;

            String routeId = "";
            LambdaExpr routeLambda = null;
            if (call.getArguments().size() == 1 && call.getArgument(0) instanceof LambdaExpr le) {
                routeLambda = le;
            } else if (call.getArguments().size() >= 2 && call.getArgument(1) instanceof LambdaExpr le) {
                routeId = HttpClientConstants.syntaxUri(call.getArgument(0), constants, enumConstants).trim();
                routeLambda = le;
            }
            if (routeLambda == null) continue;

            var paths = new ArrayList<String>();
            String stripPrefix = "";
            String rewriteFrom = "";
            String rewriteTo = "";
            String uri = "";
            String propKey = "";
            String spelRaw = "";

            for (MethodCallExpr inner : routeLambda.getBody().findAll(MethodCallExpr.class)) {
                String inName = inner.getNameAsString();
                if ("path".equals(inName) && !inner.getArguments().isEmpty()) {
                    for (Expression arg : inner.getArguments()) {
                        String p = HttpClientConstants.syntaxUri(arg, constants, enumConstants).trim();
                        if (!p.isEmpty()) paths.add(p);
                    }
                } else if ("stripPrefix".equals(inName) && inner.getArguments().size() == 1) {
                    String n = extractIntLiteral(inner.getArgument(0));
                    if (!n.isEmpty()) stripPrefix = n;
                } else if ("rewritePath".equals(inName) && inner.getArguments().size() >= 2) {
                    rewriteFrom = HttpClientConstants.syntaxUri(inner.getArgument(0), constants, enumConstants).trim();
                    rewriteTo = HttpClientConstants.syntaxUri(inner.getArgument(1), constants, enumConstants).trim();
                } else if ("uri".equals(inName) && inner.getArguments().size() == 1) {
                    Expression uriArg = inner.getArgument(0);
                    uri = HttpClientConstants.syntaxUri(uriArg, constants, enumConstants).trim();
                    propKey = extractPropertyKey(uriArg, constants, propKeys, enumConstants, 0);
                    spelRaw = extractSpelExpr(uriArg, constants, spelVars, enumConstants, 0);
                    markEmitted(inner, emittedCallSpans);
                }
            }
            if (spelRaw.isEmpty() && isSpelExpression(uri)) {
                spelRaw = uri;
            }
            if (propKey.isEmpty() && uri.contains("${")) {
                var vp = parseValuePlaceholder(uri);
                if (vp.isPresent()) {
                    propKey = vp.orElseThrow().propertyKey();
                    uri = vp.orElseThrow().defaultValue();
                }
            }
            if (uri.isEmpty() && propKey.isEmpty() && spelRaw.isEmpty() && paths.isEmpty()) continue;
            markEmitted(call, emittedCallSpans);

            List<String> emitPaths = paths.isEmpty() ? List.of("") : paths;
            for (String pathPrefix : emitPaths) {
                emitGatewayRouteFact(call, "gateway_java_dsl", "spring-cloud-gateway-java-dsl",
                        routeId, uri, pathPrefix, stripPrefix, rewriteFrom, rewriteTo, propKey, spelRaw,
                        ownerSlot, coordinates, recovered, sink, counts);
            }
        }

        // Form B: GatewayRouterFunctions.route([id]).GET(path, HandlerFunctions.http(uri)).filter(...).build()
        var visitedRoots = Collections.newSetFromMap(new IdentityHashMap<MethodCallExpr, Boolean>());
        for (MethodCallExpr call : methodCalls) {
            check(cancelled);
            if (!"route".equals(call.getNameAsString())) continue;
            boolean isGrf = call.getScope().map(s -> {
                String resolved = resolveImportedSpringType(s.toString(), imports);
                return resolved.equals("org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions");
            }).orElse(imports.staticImportedNames.contains("route") && hasGatewayImport);
            if (!isGrf || !visitedRoots.add(call)) continue;

            String routeId = call.getArguments().size() == 1
                    ? HttpClientConstants.syntaxUri(call.getArgument(0), constants, enumConstants).trim()
                    : "";
            var paths = new ArrayList<String>();
            String uri = "";
            String propKey = "";
            String spelRaw = "";
            String stripPrefix = "";
            String rewriteFrom = "";
            String rewriteTo = "";
            Node outerAnchor = call;

            // Walk upward along the fluent builder chain (.GET/.POST/.route/.before/.filter/.build)
            Node cur = call;
            for (int step = 0; step < 16; step++) {
                Node parent = cur.getParentNode().orElse(null);
                if (!(parent instanceof MethodCallExpr parentCall)) break;
                Node expected = cur;
                if (parentCall.getScope().filter(s -> s == expected).isEmpty()) break;
                outerAnchor = parentCall;
                String pn = parentCall.getNameAsString();
                if (GATEWAY_MVC_VERBS.contains(pn) && parentCall.getArguments().size() >= 2) {
                    String p = HttpClientConstants.syntaxUri(parentCall.getArgument(0), constants, enumConstants).trim();
                    if (!p.isEmpty()) paths.add(p);
                    String[] extracted = extractGatewayMvcHandlerUri(parentCall.getArgument(1), constants, propKeys, spelVars, enumConstants);
                    if (!extracted[0].isEmpty()) uri = extracted[0];
                    if (!extracted[1].isEmpty()) propKey = extracted[1];
                    if (!extracted[2].isEmpty()) spelRaw = extracted[2];
                } else if ("route".equals(pn) && parentCall.getArguments().size() >= 2) {
                    String p = extractGatewayPredicatePath(parentCall.getArgument(0), constants, enumConstants);
                    if (!p.isEmpty()) paths.add(p);
                    String[] extracted = extractGatewayMvcHandlerUri(parentCall.getArgument(1), constants, propKeys, spelVars, enumConstants);
                    if (!extracted[0].isEmpty()) uri = extracted[0];
                    if (!extracted[1].isEmpty()) propKey = extracted[1];
                    if (!extracted[2].isEmpty()) spelRaw = extracted[2];
                } else if (("before".equals(pn) || "filter".equals(pn)) && !parentCall.getArguments().isEmpty()) {
                    for (MethodCallExpr fCall : parentCall.getArgument(0).findAll(MethodCallExpr.class)) {
                        String fn = fCall.getNameAsString();
                        if ("uri".equals(fn) && fCall.getArguments().size() == 1) {
                            uri = HttpClientConstants.syntaxUri(fCall.getArgument(0), constants, enumConstants).trim();
                            propKey = extractPropertyKey(fCall.getArgument(0), constants, propKeys, enumConstants, 0);
                            spelRaw = extractSpelExpr(fCall.getArgument(0), constants, spelVars, enumConstants, 0);
                        } else if ("lb".equals(fn) && fCall.getArguments().size() == 1) {
                            String svc = HttpClientConstants.syntaxUri(fCall.getArgument(0), constants, enumConstants).trim();
                            if (!svc.isEmpty()) uri = "lb://" + svc;
                        } else if ("stripPrefix".equals(fn) && fCall.getArguments().size() == 1) {
                            String n = extractIntLiteral(fCall.getArgument(0));
                            if (!n.isEmpty()) stripPrefix = n;
                        } else if ("rewritePath".equals(fn) && fCall.getArguments().size() >= 2) {
                            rewriteFrom = HttpClientConstants.syntaxUri(fCall.getArgument(0), constants, enumConstants).trim();
                            rewriteTo = HttpClientConstants.syntaxUri(fCall.getArgument(1), constants, enumConstants).trim();
                        }
                    }
                }
                cur = parentCall;
            }
            if (spelRaw.isEmpty() && isSpelExpression(uri)) {
                spelRaw = uri;
            }
            if (propKey.isEmpty() && uri.contains("${")) {
                var vp = parseValuePlaceholder(uri);
                if (vp.isPresent()) {
                    propKey = vp.orElseThrow().propertyKey();
                    uri = vp.orElseThrow().defaultValue();
                }
            }
            if (uri.isEmpty() && propKey.isEmpty() && spelRaw.isEmpty() && paths.isEmpty()) continue;
            markEmitted(call, emittedCallSpans);
            List<String> emitPaths = paths.isEmpty() ? List.of("") : paths;
            for (String pathPrefix : emitPaths) {
                emitGatewayRouteFact(outerAnchor, "gateway_router_function", "spring-cloud-gateway-mvc",
                        routeId, uri, pathPrefix, stripPrefix, rewriteFrom, rewriteTo, propKey, spelRaw,
                        ownerSlot, coordinates, recovered, sink, counts);
            }
        }
    }

    private static boolean isRouteLocatorBuilderChain(MethodCallExpr routeCall, Map<String, String> varTypes,
            ImportIndex imports) {
        Expression cur = routeCall.getScope().orElse(null);
        int steps = 0;
        while (cur instanceof MethodCallExpr mc && steps++ < 16) {
            if ("routes".equals(mc.getNameAsString())) {
                String root = rootIdentifier(mc.getScope().orElse(null));
                String t = varTypes.getOrDefault(root, "");
                if (t.isEmpty() && !root.isEmpty()) t = resolveImportedSpringType(root, imports);
                if (t.startsWith("org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder")) return true;
            }
            cur = mc.getScope().orElse(null);
        }
        String root = rootIdentifier(cur);
        String t = varTypes.getOrDefault(root, "");
        return t.startsWith("org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder");
    }

    private static String[] extractGatewayMvcHandlerUri(Expression handlerExpr, Map<String, String> constants,
            Map<String, String> propKeys, Map<String, String> spelVars,
            HttpClientConstants.EnumConstantMap enumConstants) {
        for (MethodCallExpr mc : handlerExpr.findAll(MethodCallExpr.class)) {
            if ("http".equals(mc.getNameAsString()) && mc.getArguments().size() == 1) {
                Expression arg = mc.getArgument(0);
                String u = HttpClientConstants.syntaxUri(arg, constants, enumConstants).trim();
                String pk = extractPropertyKey(arg, constants, propKeys, enumConstants, 0);
                String sp = extractSpelExpr(arg, constants, spelVars, enumConstants, 0);
                return new String[] { u, pk, sp };
            }
        }
        return new String[] { "", "", "" };
    }

    private static String extractGatewayPredicatePath(Expression predExpr, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        String direct = HttpClientConstants.syntaxUri(predExpr, constants, enumConstants).trim();
        if (!direct.isEmpty()) return direct;
        for (MethodCallExpr mc : predExpr.findAll(MethodCallExpr.class)) {
            if ("path".equals(mc.getNameAsString()) && !mc.getArguments().isEmpty()) {
                return HttpClientConstants.syntaxUri(mc.getArgument(0), constants, enumConstants).trim();
            }
        }
        return "";
    }

    private static void emitGatewayRouteFact(
            Node anchor,
            String nodeKind,
            String spec,
            String routeId,
            String uri,
            String pathPrefix,
            String stripPrefix,
            String rewriteFrom,
            String rewriteTo,
            String propKey,
            String spelRaw,
            String ownerSlot,
            SourceCoordinates coordinates,
            boolean recovered,
            JavaGrpcSourceFrontend.Sink sink,
            long[] counts) throws IOException {
        boolean isSpel = !spelRaw.isEmpty();
        String targetService = isSpel ? "" : extractUriHost(uri).orElse("");
        var attrs = new TreeMap<String, String>();
        if (!uri.isEmpty()) attrs.put("uri", uri);
        if (!routeId.isEmpty()) attrs.put("routeId", routeId);
        if (!targetService.isEmpty()) attrs.put("targetService", targetService);
        if (!pathPrefix.isEmpty()) attrs.put("pathPrefix", pathPrefix);
        if (!stripPrefix.isEmpty()) attrs.put("stripPrefix", stripPrefix);
        if (!rewriteFrom.isEmpty()) {
            attrs.put("rewriteFrom", rewriteFrom);
            attrs.put("rewriteTo", rewriteTo);
        }
        if (!propKey.isEmpty()) attrs.put("propertyKey", propKey);
        if (isSpel) {
            attrs.put("spelExpression", "true");
            attrs.put("spelRaw", spelRaw);
        }
        attrs.put("spec", spec);
        attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
        String factName = !targetService.isEmpty() ? targetService
                : (!routeId.isEmpty() ? routeId : (!uri.isEmpty() ? uri : pathPrefix));
        emit(anchor, "GATEWAY_ROUTE", nodeKind, factName, ownerSlot, attrs, coordinates, recovered, sink, counts);
    }

    private static String extractIntLiteral(Expression expr) {
        Expression cur = expr;
        while (cur instanceof EnclosedExpr e) cur = e.getInner();
        if (cur instanceof IntegerLiteralExpr il) return il.getValue();
        return "";
    }

    private static void emitRabbitProducer(MethodCallExpr call, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants, String ownerSlot,
            SourceCoordinates coordinates, boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        if (call.getArguments().size() >= 3) {
            Expression arg0 = call.getArgument(0);
            boolean emptyDefaultExchange = arg0 instanceof StringLiteralExpr lit && lit.asString().isBlank();
            if (emptyDefaultExchange) {
                emitRabbitDefaultExchangeProducer(call, call.getArgument(1), constants, enumConstants,
                        ownerSlot, coordinates, recovered, sink, counts);
                return;
            }
            String exch = HttpClientConstants.syntaxUri(arg0, constants, enumConstants).trim();
            String routingKey = HttpClientConstants.syntaxUri(call.getArgument(1), constants, enumConstants).trim();
            var attrs = new TreeMap<String, String>();
            attrs.put("protocol", "RABBITMQ");
            attrs.put("role", "PRODUCER");
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            if (!exch.isEmpty()) {
                attrs.put("exchange", exch);
            }
            if (!routingKey.isEmpty()) {
                attrs.put("routingKey", routingKey);
            } else {
                attrs.put("dynamicRoutingKey", "true");
                String rawKey = symbolicTopicExpr(call.getArgument(1));
                if (!rawKey.isEmpty()) attrs.put("rawRoutingKeyExpr", rawKey);
            }
            if (!exch.isEmpty()) {
                emit(call, "EVENT_PRODUCER_CANDIDATE", "method_call", "\"" + exch + "\"", ownerSlot,
                        attrs, coordinates, recovered, sink, counts);
                return;
            }
            String symbolic = symbolicTopicExpr(arg0);
            if (!symbolic.isEmpty()) {
                attrs.put("symbolicTopic", "true");
                emit(call, "EVENT_PRODUCER_CANDIDATE", "method_call", "\"" + symbolic + "\"", ownerSlot,
                        attrs, coordinates, recovered, sink, counts);
            }
            return;
        }
        emitRabbitDefaultExchangeProducer(call, call.getArgument(0), constants, enumConstants,
                ownerSlot, coordinates, recovered, sink, counts);
    }

    private static void emitRabbitDefaultExchangeProducer(MethodCallExpr call, Expression keyArg,
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants, String ownerSlot,
            SourceCoordinates coordinates, boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        String topic = HttpClientConstants.syntaxUri(keyArg, constants, enumConstants).trim();
        if (!topic.isEmpty()) {
            var attrs = new TreeMap<String, String>();
            attrs.put("role", "PRODUCER");
            attrs.put("protocol", "RABBITMQ");
            attrs.put("routingKey", topic);
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            emit(call, "EVENT_PRODUCER_CANDIDATE", "method_call", "\"" + topic + "\"", ownerSlot,
                    attrs, coordinates, recovered, sink, counts);
            return;
        }
        String symbolic = symbolicTopicExpr(keyArg);
        if (!symbolic.isEmpty()) {
            var attrs = new TreeMap<String, String>();
            attrs.put("protocol", "RABBITMQ");
            attrs.put("role", "PRODUCER");
            attrs.put("symbolicTopic", "true");
            attrs.put("dynamicRoutingKey", "true");
            attrs.put("rawRoutingKeyExpr", symbolic);
            attrs.put("trigger", "JAVA_SOURCE_SYNTAX");
            emit(call, "EVENT_PRODUCER_CANDIDATE", "method_call", "\"" + symbolic + "\"", ownerSlot,
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
                        "JavaMailSender", "JavaMailSenderImpl",
                        "Queue", "Exchange", "AbstractExchange", "TopicExchange", "DirectExchange",
                        "FanoutExchange", "HeadersExchange", "CustomExchange",
                        "Binding", "BindingBuilder", "QueueBuilder", "ExchangeBuilder", "Declarables",
                        "RouteLocatorBuilder", "RouteLocator", "GatewayRouterFunctions",
                        "HandlerFunctions", "BeforeFilterFunctions", "FilterFunctions", "LoadBalancerFilterFunctions")
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
            Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        var pathSegments = new ArrayList<String>();
        String baseUri = "";
        Expression cur = requestCall.getScope().orElse(null);
        var visitedVars = new HashSet<String>();
        int steps = 0;
        while (cur != null && steps++ < 16) {
            if (cur instanceof MethodCallExpr mc) {
                String n = mc.getNameAsString();
                if (n.equals("path") && !mc.getArguments().isEmpty()) {
                    String seg = HttpClientConstants.syntaxUri(mc.getArgument(0), constants, enumConstants).trim();
                    pathSegments.addFirst(seg.isEmpty() ? "{param}" : seg);
                    cur = mc.getScope().orElse(null);
                    continue;
                }
                if (n.equals("target") && !mc.getArguments().isEmpty()) {
                    baseUri = HttpClientConstants.syntaxUri(mc.getArgument(0), constants, enumConstants).trim();
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

    private static String jaxRsInvocationVerb(MethodCallExpr requestCall, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants) {
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
                String v = HttpClientConstants.syntaxUri(parentCall.getArgument(0), constants, enumConstants)
                        .trim().toUpperCase(Locale.ROOT);
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
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants,
            String ownerSlot, SourceCoordinates coordinates,
            boolean recovered, JavaGrpcSourceFrontend.Sink sink, long[] counts) throws IOException {
        String topic = HttpClientConstants.syntaxUri(topicArg, constants, enumConstants).trim();
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
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants,
            Map<String, Boolean> out) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            collectExprTopics(single.getMemberValue(), constants, enumConstants, out);
            return;
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (keys.contains(pair.getNameAsString())) {
                    collectExprTopics(pair.getValue(), constants, enumConstants, out);
                }
            }
        }
    }

    private static void collectExprTopics(Expression expr, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants, Map<String, Boolean> out) {
        if (expr instanceof ArrayInitializerExpr array) {
            for (var item : array.getValues()) {
                collectSingleTopic(item, constants, enumConstants, out);
            }
            return;
        }
        collectSingleTopic(expr, constants, enumConstants, out);
    }

    private static void collectSingleTopic(Expression expr, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants, Map<String, Boolean> out) {
        if (expr instanceof AnnotationExpr nested) {
            if (nested instanceof SingleMemberAnnotationExpr single) {
                collectSingleTopic(single.getMemberValue(), constants, enumConstants, out);
                return;
            }
            if (nested instanceof NormalAnnotationExpr normal) {
                for (var pair : normal.getPairs()) {
                    if (Set.of("value", "name").contains(pair.getNameAsString())) {
                        collectSingleTopic(pair.getValue(), constants, enumConstants, out);
                    }
                }
            }
            return;
        }
        String resolved = HttpClientConstants.syntaxUri(expr, constants, enumConstants).trim();
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

    private static Map<String, String> collectTypeConstants(ClassOrInterfaceDeclaration type, ImportIndex imports,
            HttpClientConstants.EnumConstantMap enumConstants) {
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
                    String s = HttpClientConstants.syntaxUri(init, constants, enumConstants);
                    if (!s.isEmpty()) constants.put(v.getNameAsString(), s);
                });
            }
        }
        return constants;
    }

    private static Map<String, String> collectSpelValueFields(ClassOrInterfaceDeclaration type,
            Map<String, String> typeConstants, ImportIndex imports,
            HttpClientConstants.EnumConstantMap enumConstants) {
        var spel = new LinkedHashMap<String, String>();
        for (FieldDeclaration f : type.getFields()) {
            if (!isStringType(f.getCommonType().asString())) continue;
            Optional<AnnotationExpr> valAnn = findSpringAnnotation(f.getAnnotations(), "Value",
                    imports, Set.of("org.springframework.beans.factory.annotation."));
            if (valAnn.isEmpty()) continue;
            String raw = attrString(valAnn.orElseThrow(), "value", typeConstants, enumConstants);
            if (isSpelExpression(raw)) {
                for (VariableDeclarator v : f.getVariables()) {
                    spel.put(v.getNameAsString(), raw);
                }
            }
        }
        return spel;
    }

    private static Map<String, ValueProperty> collectValueProperties(ClassOrInterfaceDeclaration type,
            Map<String, String> typeConstants, ImportIndex imports,
            HttpClientConstants.EnumConstantMap enumConstants) {
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
            Optional<ValueProperty> parsed = parseValuePlaceholder(attrString(valAnn.orElseThrow(), "value", typeConstants, enumConstants));
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
                parseValuePlaceholder(attrString(valAnn.orElseThrow(), "value", typeConstants, enumConstants))
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
            Map<String, String> typeConstants, Map<String, String> typePropKeys, Map<String, String> typeSpelFields,
            ImportIndex imports, HttpClientConstants.EnumConstantMap enumConstants) {
        var bases = new LinkedHashMap<String, ClientBaseSpec>();
        for (FieldDeclaration f : type.getFields()) {
            String resolved = resolveImportedSpringType(f.getCommonType().asString(), imports);
            if (!isFluentClientType(resolved)) continue;
            for (VariableDeclarator v : f.getVariables()) {
                v.getInitializer().ifPresent(init -> {
                    ClientBaseSpec spec = extractFluentBaseSpec(init, typeConstants, typePropKeys,
                            typeSpelFields, bases, imports, enumConstants);
                    if (spec != null) bases.put(v.getNameAsString(), spec);
                });
            }
        }
        for (ConstructorDeclaration ctor : type.getConstructors()) {
            var ctorConstants = new LinkedHashMap<>(typeConstants);
            var ctorPropKeys = new LinkedHashMap<>(typePropKeys);
            var ctorSpel = new LinkedHashMap<>(typeSpelFields);
            for (Parameter p : ctor.getParameters()) {
                String paramName = p.getNameAsString();
                ctorConstants.remove(paramName);
                ctorPropKeys.remove(paramName);
                ctorSpel.remove(paramName);
                if (!isStringType(p.getType().asString())) continue;
                Optional<AnnotationExpr> valAnn = findSpringAnnotation(p.getAnnotations(), "Value",
                        imports, Set.of("org.springframework.beans.factory.annotation."));
                if (valAnn.isPresent()) {
                    String rawVal = attrString(valAnn.orElseThrow(), "value", typeConstants, enumConstants);
                    if (isSpelExpression(rawVal)) {
                        ctorSpel.put(paramName, rawVal);
                    } else {
                        parseValuePlaceholder(rawVal).ifPresent(vp -> {
                            ctorPropKeys.put(paramName, vp.propertyKey());
                            if (!vp.defaultValue().isEmpty()) ctorConstants.put(paramName, vp.defaultValue());
                        });
                    }
                }
            }
            collectLocalConstants(ctor.getBody(), ctorConstants, ctorPropKeys, enumConstants);
            for (AssignExpr assign : ctor.getBody().findAll(AssignExpr.class)) {
                if (assign.getOperator() != AssignExpr.Operator.ASSIGN) continue;
                String fieldName = assignedFieldName(assign.getTarget());
                if (fieldName.isEmpty()) continue;
                ClientBaseSpec spec = extractFluentBaseSpec(assign.getValue(), ctorConstants, ctorPropKeys,
                        ctorSpel, bases, imports, enumConstants);
                if (spec != null) bases.putIfAbsent(fieldName, spec);
            }
        }
        return bases;
    }

    private static ClientBaseSpec extractFluentBaseSpec(Expression expr, Map<String, String> constants,
            Map<String, String> propKeys, Map<String, String> spelVars, Map<String, ClientBaseSpec> knownBases,
            ImportIndex imports, HttpClientConstants.EnumConstantMap enumConstants) {
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
                        String baseUri = HttpClientConstants.syntaxUri(arg, constants, enumConstants).trim();
                        String propKey = extractPropertyKey(arg, constants, propKeys, enumConstants, 0);
                        String spelRaw = extractSpelExpr(arg, constants, spelVars, enumConstants, 0);
                        if (!propKey.isEmpty() && baseUri.startsWith("{param}")) {
                            baseUri = "";
                        }
                        if (!baseUri.isEmpty() || !propKey.isEmpty() || !spelRaw.isEmpty()) {
                            return new ClientBaseSpec(baseUri, propKey, spelRaw);
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
            Map<String, String> propKeys, HttpClientConstants.EnumConstantMap enumConstants) {
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
                    String s = HttpClientConstants.syntaxUri(init, constants, enumConstants);
                    if (!s.isEmpty()) constants.put(varName, s);
                    String pk = extractPropertyKey(init, constants, propKeys, enumConstants, 0);
                    if (!pk.isEmpty()) propKeys.put(varName, pk);
                });
            }
        }
    }

    private static String extractSpelExpr(Expression expr, Map<String, String> constants,
            Map<String, String> spelVars, HttpClientConstants.EnumConstantMap enumConstants, int depth) {
        if (expr == null || depth > 16) return "";
        if (expr instanceof EnclosedExpr enclosed) {
            return extractSpelExpr(enclosed.getInner(), constants, spelVars, enumConstants, depth + 1);
        }
        if (expr instanceof StringLiteralExpr lit && isSpelExpression(lit.asString())) {
            return lit.asString().trim();
        }
        if (expr instanceof NameExpr name) {
            return spelVars.getOrDefault(name.getNameAsString(), "");
        }
        if (expr instanceof FieldAccessExpr field) {
            if (field.getScope() instanceof ThisExpr) {
                return spelVars.getOrDefault(field.getNameAsString(), "");
            }
            return spelVars.getOrDefault(field.toString(), "");
        }
        if (expr instanceof BinaryExpr bin && bin.getOperator() == BinaryExpr.Operator.PLUS) {
            String left = extractSpelExpr(bin.getLeft(), constants, spelVars, enumConstants, depth + 1);
            if (!left.isEmpty()) return left;
            return extractSpelExpr(bin.getRight(), constants, spelVars, enumConstants, depth + 1);
        }
        return "";
    }

    private static String extractPropertyKey(Expression expr, Map<String, String> constants,
            Map<String, String> propKeys, HttpClientConstants.EnumConstantMap enumConstants, int depth) {
        if (expr == null || depth > 32 || propKeys.isEmpty()) return "";
        if (expr instanceof EnclosedExpr enclosed) {
            return extractPropertyKey(enclosed.getInner(), constants, propKeys, enumConstants, depth + 1);
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
            String leftKey = extractPropertyKey(binary.getLeft(), constants, propKeys, enumConstants, depth + 1);
            if (!leftKey.isEmpty()) return leftKey;
            String leftVal = HttpClientConstants.syntaxUri(binary.getLeft(), constants, enumConstants).trim();
            if (leftVal.isEmpty() || isSchemeOnlyPrefix(leftVal)) {
                return extractPropertyKey(binary.getRight(), constants, propKeys, enumConstants, depth + 1);
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
                        String template = HttpClientConstants.syntaxUri(call.getArgument(templateIdx), constants, enumConstants).trim();
                        int pct = template.indexOf('%');
                        if (pct >= 0) {
                            String prefix = template.substring(0, pct);
                            if (prefix.isEmpty() || isSchemeOnlyPrefix(prefix)) {
                                return extractPropertyKey(call.getArgument(templateIdx + 1), constants, propKeys, enumConstants, depth + 1);
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
                    return extractPropertyKey(call.getArgument(0), constants, propKeys, enumConstants, depth + 1);
                }
            }
        }
        return "";
    }

    private static String extractDeclaredProfile(NodeList<AnnotationExpr> annotations, ImportIndex imports,
            Map<String, String> constants, HttpClientConstants.EnumConstantMap enumConstants) {
        Optional<AnnotationExpr> profileAnn = findSpringAnnotation(annotations, "Profile",
                imports, Set.of("org.springframework.context.annotation."));
        if (profileAnn.isEmpty()) return "";
        var vals = attrStrings(profileAnn.orElseThrow(), "value", constants, enumConstants);
        return vals.isEmpty() ? "" : String.join(",", vals);
    }

    private static boolean isSpelExpression(String raw) {
        if (raw == null || raw.isEmpty()) return false;
        int start = raw.indexOf("#{");
        return start >= 0 && raw.indexOf('}', start + 2) > start + 1;
    }

    private static String firstSpel(String... values) {
        for (String v : values) {
            if (isSpelExpression(v)) return v.trim();
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

    private static void markEmitted(MethodCallExpr call, Set<String> emittedCallSpans) {
        call.getRange().ifPresent(r -> emittedCallSpans.add(
                r.begin.line + ":" + r.begin.column + ":" + r.end.line + ":" + r.end.column));
    }

    private record ImportIndex(Map<String, String> explicit, Set<String> wildcards, Set<String> staticImportedNames) {
        static ImportIndex from(CompilationUnit cu) {
            var explicit = new HashMap<String, String>();
            var wildcards = new LinkedHashSet<String>();
            var staticNames = new HashSet<String>();
            for (ImportDeclaration id : cu.getImports()) {
                String q = id.getNameAsString();
                if (id.isStatic()) {
                    if (!id.isAsterisk()) {
                        int dot = q.lastIndexOf('.');
                        staticNames.add(dot >= 0 ? q.substring(dot + 1) : q);
                    }
                    continue;
                }
                if (id.isAsterisk()) wildcards.add(q + ".");
                else {
                    int dot = q.lastIndexOf('.');
                    explicit.put(dot >= 0 ? q.substring(dot + 1) : q, q);
                }
            }
            return new ImportIndex(explicit, wildcards, staticNames);
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

    private static String attrString(AnnotationExpr ann, String key, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        var list = attrStrings(ann, key, constants, enumConstants);
        return list.isEmpty() ? "" : list.getFirst();
    }

    private static List<String> attrStrings(AnnotationExpr ann, String key, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        if (ann instanceof SingleMemberAnnotationExpr single && key.equals("value")) {
            return exprStrings(single.getMemberValue(), constants, enumConstants);
        }
        if (ann instanceof NormalAnnotationExpr normal) {
            for (var pair : normal.getPairs()) {
                if (pair.getNameAsString().equals(key)) return exprStrings(pair.getValue(), constants, enumConstants);
            }
        }
        return List.of();
    }

    private static List<String> exprStrings(Expression expr, Map<String, String> constants,
            HttpClientConstants.EnumConstantMap enumConstants) {
        if (expr instanceof ArrayInitializerExpr array) {
            var out = new ArrayList<String>();
            for (var item : array.getValues()) {
                String v = HttpClientConstants.syntaxUri(item, constants, enumConstants).trim();
                if (!v.isEmpty()) out.add(v);
            }
            return out;
        }
        String v = HttpClientConstants.syntaxUri(expr, constants, enumConstants).trim();
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
