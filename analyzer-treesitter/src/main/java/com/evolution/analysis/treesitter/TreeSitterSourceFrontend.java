package com.evolution.analysis.treesitter;

import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.contract.common.ContentDigest;
import com.evolution.analysis.contract.serialization.CanonicalJson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.treesitter.*;

/**
 * Neutral, data-only syntax acquisition. A written call is not a bound call
 * target.
 */
public final class TreeSitterSourceFrontend {
    public static final String PROVIDER = "source.tree-sitter:m8.4-semantic-wrapper-v6";

    /**
     * Read the loaded adapter version at runtime, avoiding consumer constant
     * inlining.
     */
    public static String provider() {
        return PROVIDER;
    }

    private record Grammar(String library, String binding, String version) {
    }

    private static final Map<String, Grammar> GRAMMARS = Map.ofEntries(
            entry("go", "go", "Go", "0.25.0"), entry("python", "python", "Python", "0.25.0"),
            entry("csharp", "c-sharp", "CSharp", "0.23.1"),
            entry("javascript", "javascript", "Javascript", "0.25.0"),
            entry("typescript", "typescript", "Typescript", "0.23.2"), entry("tsx", "tsx", "Tsx", "0.23.2"),
            entry("kotlin", "kotlin", "Kotlin", "0.3.8.1"), entry("scala", "scala", "Scala", "0.24.0"),
            entry("rust", "rust", "Rust", "0.24.0"),
            entry("c", "c", "C", "0.24.1"), entry("cpp", "cpp", "Cpp", "0.23.4"), entry("php", "php", "Php", "0.24.2"),
            entry("ruby", "ruby", "Ruby", "0.23.1"),
            entry("dart", "dart", "Dart", "master-a"), entry("swift", "swift", "Swift", "0.5.0"),
            entry("objectivec", "objc", "Objc", "main-a"));

    private static Map.Entry<String, Grammar> entry(String language, String library, String binding, String version) {
        return Map.entry(language,
                new Grammar("tree-sitter-" + library, "org.treesitter.TreeSitter" + binding, version));
    }

    public static Set<String> languages() {
        var languages = new TreeSet<>(GRAMMARS.keySet());
        languages.add("protobuf");
        return Collections.unmodifiableSet(languages);
    }

    private static final Set<String> CALLS = Set.of("call_expression", "invocation_expression",
            "function_call_expression", "call", "method_invocation", "object_creation_expression",
            "method_call_expression", "scoped_call_expression", "member_call_expression",
            "macro_invocation", "constructor_invocation", "new_expression");
    private static final Set<String> ROUTES = Set.of("get", "post", "put", "patch", "delete", "options", "head",
            "route", "all", "HandleFunc", "Handle", "GET", "POST", "PUT", "PATCH", "DELETE", "Get", "Post", "Put",
            "Patch", "Delete", "Head", "Options", "All", "MapGet", "MapPost", "MapPut", "MapDelete", "MapMethods",
            "HttpGet", "HttpPost", "HttpPut", "HttpDelete", "Route", "RequestMapping", "GetMapping", "PostMapping",
            "PutMapping", "PatchMapping", "DeleteMapping", "api_route", "add_api_route", "websocket", "MethodFunc",
            "path", "re_path");

    private record Frame(TSNode node, String owner) {
    }

    public record Fact(String category, String nodeKind, String name, String written, SourceCoordinates.Range span,
            String owner, boolean recovered, Map<String, String> attributes) {
        public Fact {
            attributes = Map.copyOf(attributes);
        }

        public Fact(String category, String nodeKind, String name, String written, SourceCoordinates.Range span,
                String owner, boolean recovered) {
            this(category, nodeKind, name, written, span, owner, recovered, Map.of());
        }

        public Map<String, Object> toMap() {
            var attrs = new TreeMap<String, Object>(attributes);
            var spanMap = new TreeMap<String, Object>();
            spanMap.put("end", java.math.BigDecimal.valueOf(span.end()));
            spanMap.put("endColumn", java.math.BigDecimal.valueOf(span.endColumn()));
            spanMap.put("endLine", java.math.BigDecimal.valueOf(span.endLine()));
            spanMap.put("start", java.math.BigDecimal.valueOf(span.start()));
            spanMap.put("startColumn", java.math.BigDecimal.valueOf(span.startColumn()));
            spanMap.put("startLine", java.math.BigDecimal.valueOf(span.startLine()));
            var map = new TreeMap<String, Object>();
            map.put("attributes", attrs);
            map.put("category", category);
            map.put("name", name);
            map.put("nodeKind", nodeKind);
            map.put("owner", owner);
            map.put("recovered", recovered);
            map.put("span", spanMap);
            map.put("written", written);
            return map;
        }
    }

    public record Summary(long nodes, long facts, long errors, String grammar, String toolIdentity) {
        public Map<String, Object> toMap() {
            var map = new TreeMap<String, Object>();
            map.put("errors", java.math.BigDecimal.valueOf(errors));
            map.put("facts", java.math.BigDecimal.valueOf(facts));
            map.put("grammar", grammar);
            map.put("nodes", java.math.BigDecimal.valueOf(nodes));
            map.put("toolIdentity", toolIdentity);
            return map;
        }
    }

    @FunctionalInterface
    public interface Sink {
        void accept(Fact fact) throws IOException;
    }

    private static final Map<String, java.lang.reflect.Constructor<?>> GRAMMAR_CTORS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_POOLED_PARSERS_PER_LANGUAGE = Math.max(2,
            Math.min(8, Runtime.getRuntime().availableProcessors()));
    private static final Map<String, java.util.concurrent.ConcurrentLinkedDeque<TSParser>> PARSER_POOL = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(TreeSitterSourceFrontend::closePooledParsers,
                "evo-treesitter-pool-shutdown"));
    }

    /** Releases any idle pooled native TSParser instances (safe to invoke between runs or on shutdown). */
    public static void closePooledParsers() {
        for (var deque : PARSER_POOL.values()) {
            TSParser p;
            while ((p = deque.pollFirst()) != null) {
                try {
                    p.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static TSParser borrowParser(String language, Grammar grammar)
            throws IOException, ReflectiveOperationException {
        var deque = PARSER_POOL.computeIfAbsent(language, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        TSParser parser = deque.pollFirst();
        if (parser != null) {
            return parser;
        }
        parser = new TSParser();
        try {
            var ctor = GRAMMAR_CTORS.get(grammar.binding());
            if (ctor == null) {
                ctor = Class.forName(grammar.binding()).getConstructor();
                GRAMMAR_CTORS.put(grammar.binding(), ctor);
            }
            var binding = (TSLanguage) ctor.newInstance();
            if (!parser.setLanguage(binding)) {
                throw new IOException("SYNTAX_GRAMMAR_ABI_MISMATCH");
            }
            return parser;
        } catch (IOException | ReflectiveOperationException | RuntimeException | Error failure) {
            try {
                parser.close();
            } catch (Exception ignored) {
            }
            throw failure;
        }
    }

    private static void releaseParser(String language, TSParser parser, boolean healthy) {
        if (parser == null) {
            return;
        }
        if (healthy) {
            var deque = PARSER_POOL.computeIfAbsent(language, k -> new java.util.concurrent.ConcurrentLinkedDeque<>());
            if (deque.size() < MAX_POOLED_PARSERS_PER_LANGUAGE) {
                deque.offerFirst(parser);
                return;
            }
        }
        try {
            parser.close();
        } catch (Exception ignored) {
        }
    }
    private static final java.util.regex.Pattern PYTHON_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:requests|httpx|session|client|aiohttp|urllib\\.request)\\.(?:get|post|put|patch|delete|head|options|urlopen|request)");
    private static final java.util.regex.Pattern GO_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_]*(?:http|Http|client|Client|resty)[A-Za-z0-9_]*)\\.(?:Get|Post|Put|Patch|Delete|Head|R\\(\\)\\.Get|R\\(\\)\\.Post)");
    private static final java.util.regex.Pattern CSHARP_HTTP_CLIENT = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_.]*\\.(?:GetAsync|PostAsync|PutAsync|PatchAsync|DeleteAsync|GetStringAsync|GetByteArrayAsync)");
    private static final java.util.regex.Pattern JS_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:axios|http|this\\.http|httpClient|this\\.httpClient|api|apiClient|client|ky|got|ofetch|\\$fetch|superagent)\\.(?:get|post|put|patch|delete|head|options|request)");
    private static final java.util.regex.Pattern RUST_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:reqwest|client|http_client|httpClient)\\.(?:get|post|put|patch|delete|head|request)");
    private static final java.util.regex.Pattern DART_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:dio|http|client)\\.(?:get|post|put|patch|delete|head)");
    private static final java.util.regex.Pattern KOTLIN_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:[A-Za-z_][A-Za-z0-9_.]*)?(?:httpClient|ktor|client|okhttp|webClient|restClient|restTemplate)\\.(?:get|post|put|patch|delete|head|getForObject|postForObject|getForEntity|postForEntity)");
    private static final java.util.regex.Pattern PHP_HTTP_CLIENT = java.util.regex.Pattern.compile("(?i)(?:(?:\\$this->)?\\$?(?:client|guzzle|http|httpClient|apiClient|guzzleClient)->(?:get|post|put|patch|delete|head|options|request)|(?:\\\\?Illuminate\\\\Support\\\\Facades\\\\)?Http::(?:get|post|put|patch|delete|head|options))");
    private static final java.util.regex.Pattern RUBY_HTTP_CLIENT = java.util.regex.Pattern.compile("(?:Faraday|HTTParty|RestClient|Excon)\\.(?:get|post|put|patch|delete|head|options)");
    private static final java.util.regex.Pattern EVENT_PRODUCER_CALL = java.util.regex.Pattern.compile("(?i)(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?(?:kafka|kafkaTemplate|kafkaProducer|producer|eventBus|eventPublisher|applicationEventPublisher|emitter|broker|nats|nc|amqp|channel|rabbitTemplate|sqs|sns|pubsub|redis)\\.(?:send|produce|publish|publishEvent|basic_publish|emit|post|convertAndSend|Publish)");
    private static final java.util.regex.Pattern EVENT_CONSUMER_CALL = java.util.regex.Pattern.compile("(?i)(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?(?:kafka|kafkaConsumer|consumer|eventBus|emitter|broker|nats|nc|amqp|channel|pubsub|redis)\\.(?:subscribe|consume|basic_consume|on|Subscribe|QueueSubscribe)");
    private static final java.util.regex.Pattern MASSTRANSIT_PRODUCER_CALL = java.util.regex.Pattern.compile("(?i)(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?(?:_?publishEndpoint|_?sendEndpoint|_?bus|_?busControl)\\.(?:Publish|Send)(?:<[^>]+>)?");
    private static final java.util.regex.Pattern MASSTRANSIT_ICONSUMER_PATTERN = java.util.regex.Pattern.compile("\\bIConsumer<\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*>");
    private static final java.util.regex.Pattern GENERIC_ARG_PATTERN = java.util.regex.Pattern.compile("<\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*>");
    private static final Set<String> EVENT_CONSUMER_ANNOTATIONS = Set.of("KafkaListener","RabbitListener","EventPattern","MessagePattern","SqsListener");
    private static final Set<String> HTTP_VERBS = Set.of("get","post","put","patch","delete","head","options","request",
            "Get","Post","Put","Patch","Delete","Head","Options","GetAsync","PostAsync","PutAsync","PatchAsync","DeleteAsync","GetStringAsync","GetByteArrayAsync");
    private static final Set<String> NESTJS_ROUTE_DECORATORS = Set.of("Get", "Post", "Put", "Patch", "Delete", "Head", "Options", "All");
    private static final Set<String> NEXTJS_ROUTE_EXPORTS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> REFIT_HTTP_ATTRIBUTES = Set.of("Get", "Post", "Put", "Patch", "Delete", "Head", "Options");
    private static final Set<String> EVENT_PRODUCER_VERBS = Set.of("send","produce","publish","publishEvent","basic_publish","emit","convertAndSend","Publish","Send");
    private static final Set<String> EVENT_CONSUMER_VERBS = Set.of("subscribe","consume","basic_consume","on","Subscribe","QueueSubscribe");
    private static final java.util.regex.Pattern HTTP_METHOD_PREFIX = java.util.regex.Pattern.compile("^(?:Map|Http)");
    private static final java.util.regex.Pattern HTTP_METHOD_SUFFIX = java.util.regex.Pattern
            .compile("(?:Async|Mapping|ForObject|ForEntity)$");
    private static final java.util.regex.Pattern PY_AS_PATTERN = java.util.regex.Pattern.compile("(?:import|from\\s+[A-Za-z0-9_.]+\\s+import)\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final java.util.regex.Pattern JS_DEF_PATTERN = java.util.regex.Pattern.compile("import\\s+(?:\\*\\s+as\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s+from\\s+['\"]([^'\"]+)['\"]");
    private static final java.util.regex.Pattern JS_NAMED_PATTERN = java.util.regex.Pattern.compile("([A-Za-z_$][A-Za-z0-9_$]*)\\s+as\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final java.util.regex.Pattern GO_IMP_PATTERN = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s+\"([^\"]+)\"");
    private static final java.util.regex.Pattern RS_USE_PATTERN = java.util.regex.Pattern.compile("([A-Za-z_][A-Za-z0-9_:]*)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)");
    private static final java.util.regex.Pattern DECL_ASSIGN_LHS_PATTERN = java.util.regex.Pattern.compile("^(?:const|let|var|val|final|private\\s+final|private\\s+static\\s+final|static\\s+readonly)?\\s*(?:[A-Za-z0-9_<>.\\[\\]]+\\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\\s*(?::?=)\\s*");
    private static final java.util.regex.Pattern HTTP_FACTORY_TYPE_PATTERN = java.util.regex.Pattern.compile("\\b(?:AxiosInstance|HttpClient|RestTemplate|WebClient|RestClient|GuzzleHttp\\\\Client)\\b");
    private static final java.util.regex.Pattern KAFKA_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:KafkaProducer|KafkaConsumer|KafkaTemplate|EventProducer|EventConsumer|MessageProducer|MessageConsumer)\\b");
    private static final java.util.regex.Pattern AMQP_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:RabbitTemplate|ConnectionFactory|amqp\\.Channel)\\b");
    private static final java.util.regex.Pattern NATS_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:NatsConnection|nats\\.Conn)\\b");
    private static final java.util.regex.Pattern AWS_MSG_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:SqsClient|SnsClient)\\b");
    private static final java.util.regex.Pattern REDIS_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:RedisTemplate|StrictRedis)\\b");
    private static final java.util.regex.Pattern EVENT_BUS_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:EventEmitter|EventBus|ApplicationEventPublisher)\\b");
    private static final java.util.regex.Pattern MASSTRANSIT_FACTORY_PATTERN = java.util.regex.Pattern.compile("\\b(?:IPublishEndpoint|ISendEndpoint|ISendEndpointProvider|IBus|IBusControl)\\b");
    private static final java.util.regex.Pattern BASE_URL_LITERAL_PATTERN = java.util.regex.Pattern.compile("(?:baseURL|baseUrl|base_url|prefixUrl|base_uri|url)['\"]?\\s*(?:=>|[:=])\\s*(['\"])([^'\"\\\\]+)\\1");
    private static final java.util.regex.Pattern ROUTER_PREFIX_PATTERN = java.util.regex.Pattern.compile("(?:prefix|url_prefix)\\s*=\\s*(['\"])([^'\"\\\\]+)\\1");
    private static final java.util.regex.Pattern CELERY_NAME_ARG_PATTERN = java.util.regex.Pattern.compile("\\bname\\s*=\\s*(['\"])([^'\"\\\\]+)\\1");
    private static final java.util.regex.Pattern CONTROLLER_PATH_OBJ_PATTERN = java.util.regex.Pattern.compile("\\bpath\\s*:\\s*(['\"])([^'\"\\\\]+)\\1");
    private static final java.util.regex.Pattern CSHARP_PARAM_TYPE_NAME_PATTERN = java.util.regex.Pattern.compile("\\b(?:IPublishEndpoint|ISendEndpoint|ISendEndpointProvider|IBus|IBusControl)\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");
    private static final int MAX_REGEX_NODE_LENGTH = 1024;

    public static Optional<String> toolIdentity(String language) {
        if (language == null) return Optional.empty();
        if (language.equals("protobuf")) {
            try { return Optional.of(ProtobufSourceFrontend.parse(new byte[0], f -> {}, () -> false).toolIdentity()); }
            catch (IOException | RuntimeException e) { return Optional.empty(); }
        }
        var grammar = GRAMMARS.get(language);
        if (grammar == null) return Optional.empty();
        try { return Optional.of(toolIdentity(grammar)); }
        catch (IOException | RuntimeException e) { return Optional.empty(); }
    }

    public Summary analyze(String language, byte[] bytes, Sink sink, BooleanSupplier cancelled) throws IOException {
        return parse(language, bytes, sink, cancelled, false);
    }

    public Summary analyze(String language, byte[] bytes, Sink sink, BooleanSupplier cancelled,
            boolean fastArchitecture) throws IOException {
        return parse(language, bytes, sink, cancelled, fastArchitecture);
    }

    public Summary parse(String language, byte[] bytes, Sink sink, BooleanSupplier cancelled) throws IOException {
        return parse(language, bytes, sink, cancelled, false);
    }

    public Summary parse(String language, byte[] bytes, Sink sink, BooleanSupplier cancelled, boolean fastArchitecture)
            throws IOException {
        if (language.equals("protobuf"))
            return ProtobufSourceFrontend.parse(bytes, sink, cancelled);
        if (System.getProperty("tree-sitter-lib") != null)
            throw new IOException("SYNTAX_EXTERNAL_LIBRARY_OVERRIDE_REJECTED");
        var grammar = GRAMMARS.get(language);
        if (grammar == null)
            throw new IOException("SYNTAX_GRAMMAR_UNAVAILABLE");
        check(cancelled);
        var encoding = charset(bytes);
        var positions = new SourceCoordinates(bytes, encoding);
        // SourceCoordinates measures UTF-8 offsets over the original decoded text.
        // UTF-8 inputs have already been validated and can go straight to the parser.
        byte[] utf8 = encoding.equals("UTF-8") ? bytes : positions.text().getBytes(StandardCharsets.UTF_8);
        String tool = toolIdentity(grammar);
        long nodes = 0, facts = 0, errors = 0;
        TSParser parser = null;
        boolean healthy = false;
        try {
            parser = borrowParser(language, grammar);
            try (var tree = parser.parseWithOptions(new byte[8192], null, (buffer, offset, point) -> {
                if(offset>=utf8.length)return 0;int count=Math.min(buffer.length,utf8.length-offset);System.arraycopy(utf8,offset,buffer,0,count);return count;
            },TSInputEncoding.TSInputEncodingUTF8,state->cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())) {
                check(cancelled);var root=tree.getRootNode();boolean recovered=root.hasError();
                var scope = collectFileScope(language, root, utf8, positions, cancelled);
                var pending = new ArrayDeque<Frame>();
                pending.push(new Frame(root, ""));
                while (!pending.isEmpty()) {
                    check(cancelled);
                    var frame = pending.pop();
                    var node = frame.node();
                    nodes++;
                    String kind = node.getType(), category = category(kind);
                    String owner = frame.owner();
                    if (fastArchitecture && category.equals("TYPE_SYNTAX"))
                        category = "";
                    if (language.equals("dart") && kind.equals("selector") && node.getNamedChildCount() > 0
                            && node.getNamedChild(0).getType().equals("argument_part"))
                        category = "CALL";
                    if (language.equals("python") && kind.equals("argument_list") && !node.getParent().isNull()
                            && node.getParent().getType().equals("class_definition"))
                        category = "HERITAGE";
                    if (node.isError() || node.isMissing()) {
                        errors++;
                        category = "ERROR";
                    }
                    if (!category.isEmpty() && node.getEndByte() > node.getStartByte()) {
                        var span = positions.range(node.getStartByte(), node.getEndByte(),
                                SourceCoordinates.Encoding.UTF8);
                        var nameNode = name(node);
                        String name = text(nameNode, utf8);
                        String written = text(head(node), utf8);
                        if (category.equals("DECLARATION")) {
                            if (nameNode != null)
                                written = name;
                            else {
                                String fallbackName = declarationNameFromChild(node, utf8);
                                if (!fallbackName.isEmpty()) {
                                    name = fallbackName;
                                    written = fallbackName;
                                }
                            }
                        } else if (category.equals("HERITAGE") || category.equals("IMPORT")) {
                            written = text(node, utf8);
                        }
                        if (language.equals("dart") && category.equals("CALL") && kind.equals("selector")) {
                            var parent = node.getParent();
                            if (parent != null && !parent.isNull() && node.getStartByte() >= parent.getStartByte()
                                    && node.getStartByte() <= utf8.length) {
                                written = new String(utf8, parent.getStartByte(),
                                        node.getStartByte() - parent.getStartByte(), StandardCharsets.UTF_8);
                            }
                        } else if (language.equals("php") && category.equals("CALL")) {
                            if (kind.equals("scoped_call_expression")) {
                                var scopeNode = field(node, "scope");
                                var callName = field(node, "name");
                                if (scopeNode != null && callName != null) {
                                    written = text(scopeNode, utf8) + "::" + text(callName, utf8);
                                }
                            } else if (kind.equals("member_call_expression")) {
                                var objNode = field(node, "object");
                                var callName = field(node, "name");
                                if (objNode != null && callName != null) {
                                    written = text(objNode, utf8) + "->" + text(callName, utf8);
                                }
                            }
                        } else if (language.equals("ruby") && category.equals("CALL") && kind.equals("call")) {
                            var recvNode = field(node, "receiver");
                            var methodNode = field(node, "method");
                            if (recvNode != null && methodNode != null) {
                                written = text(recvNode, utf8) + "." + text(methodNode, utf8);
                            }
                        }
                        Map<String, String> factAttrs = Map.of();
                        if (category.equals("CALL")) {
                            factAttrs = callAttributes(node, utf8, scope, frame.owner());
                        }
                        sink.accept(
                                new Fact(category, kind, name, excerpt(written), span, owner, recovered, factAttrs));
                        facts++;
                        if (category.equals("DECLARATION")) {
                            owner = span.start() + ":" + span.end();
                            facts += emitNextJsRouteExport(language, kind, node, name, written, utf8, span,
                                    frame.owner(), recovered, sink);
                        }
                        if (language.equals("csharp") && category.equals("HERITAGE")) {
                            facts += emitMassTransitConsumerHeritage(kind, written, span, frame.owner(), recovered,
                                    sink);
                        }
                        if (language.equals("python") && kind.equals("decorator")) {
                            facts += emitCeleryTaskDecorator(kind, node, written, utf8, span, frame.owner(), recovered,
                                    scope, sink);
                        }
                        if (category.equals("CALL") || kind.equals("attribute") || kind.equals("annotation")) {
                            String tail = tailName(written);
                            boolean refitClient = emitCSharpRefitAttribute(language, kind, node, written, tail, utf8,
                                    span, frame.owner(), recovered, scope, sink);
                            if (refitClient) {
                                facts++;
                            } else {
                                facts += emitRouteCandidate(language, category, kind, node, written, tail, utf8, span,
                                        frame.owner(), recovered, scope, sink);
                            }
                            if (category.equals("CALL")) {
                                facts += emitHttpCallOrWrapper(language, kind, node, written, tail, utf8, span,
                                        frame.owner(), recovered, scope, sink);
                            }
                            facts += emitEventCandidate(language, category, kind, node, written, tail, utf8, span,
                                    frame.owner(), recovered, scope, sink);
                        }
                    }
                    for (int i = node.getNamedChildCount() - 1; i >= 0; i--)
                        pending.push(new Frame(node.getNamedChild(i), owner));
                }
                facts += GrpcSourceSyntax.acquire(language, root, utf8, positions, sink, cancelled);
                if (recovered && errors == 0)
                    errors = 1;
            }
            healthy = true;
        } catch (ReflectiveOperationException failure) {
            throw new IOException("SYNTAX_BUNDLED_GRAMMAR_UNAVAILABLE", failure);
        } finally {
            releaseParser(language, parser, healthy);
        }
        return new Summary(nodes, facts, errors, grammar.library() + ":" + grammar.version(), tool);
    }

    private record ClientBinding(String kind, String baseUrl) {
    }

    private record ResolvedString(String rawQuoted, String unquoted) {
    }

    private static final class FileScope {
        final Map<String, String> importAliases = new HashMap<>();
        final Map<String, String> stringConstants = new HashMap<>();
        final Map<String, ClientBinding> httpClients = new HashMap<>();
        final Map<String, String> eventClients = new HashMap<>();
        final Map<String, List<String>> functionParams = new HashMap<>();
        final Map<String, String> functionNames = new HashMap<>();
        final Map<String, String> routePrefixes = new HashMap<>();
        final Map<String, String> classRoutePrefixes = new HashMap<>();
        final Map<String, String> enclosingClassSpan = new HashMap<>();
        final Set<String> interfaceSpans = new HashSet<>();
        final Map<String, String> celeryTaskNames = new HashMap<>();
        boolean hasRefitImport;
    }

    private static FileScope collectFileScope(String language, TSNode root, byte[] utf8, SourceCoordinates positions,
            BooleanSupplier cancelled) throws IOException {
        var scope = new FileScope();
        var pending = new ArrayDeque<Frame>();
        pending.push(new Frame(root, ""));
        while (!pending.isEmpty()) {
            check(cancelled);
            var frame = pending.pop();
            var node = frame.node();
            String kind = node.getType(), category = category(kind);
            String owner = frame.owner();
            if (category.equals("IMPORT")) {
                if (node.getEndByte() - node.getStartByte() <= MAX_REGEX_NODE_LENGTH) {
                    String stmt = text(node, utf8);
                    collectImportAlias(language, stmt, scope.importAliases);
                    if (language.equals("csharp") && stmt.contains("Refit")) {
                        scope.hasRefitImport = true;
                    }
                }
            } else if (kind.equals("variable_declarator") || kind.equals("assignment")
                    || kind.equals("assignment_expression")
                    || kind.equals("short_var_declaration") || kind.equals("var_spec")
                    || kind.equals("const_spec") || kind.equals("const_item")
                    || kind.equals("property_declaration") || kind.equals("field_declaration")
                    || kind.equals("let_declaration")) {
                collectBindingFromDeclaration(language, node, utf8, scope);
            } else if (language.equals("csharp") && kind.equals("parameter")
                    && node.getEndByte() - node.getStartByte() <= MAX_REGEX_NODE_LENGTH) {
                var matcher = CSHARP_PARAM_TYPE_NAME_PATTERN.matcher(text(node, utf8));
                if (matcher.find()) {
                    scope.eventClients.put(matcher.group(1), "MASSTRANSIT");
                }
            } else if (language.equals("python") && kind.equals("decorated_definition")) {
                collectPythonCeleryTaskMapping(node, utf8, scope);
            } else if (Set.of("javascript", "typescript", "tsx").contains(language)
                    && kind.equals("call_expression")) {
                collectExpressRouterMount(node, utf8, scope);
            }
            if (category.equals("DECLARATION") && node.getEndByte() > node.getStartByte()) {
                var span = positions.range(node.getStartByte(), node.getEndByte(), SourceCoordinates.Encoding.UTF8);
                String declSpan = span.start() + ":" + span.end();
                String declName = text(name(node), utf8);
                if (declName.isEmpty())
                    declName = declarationNameFromChild(node, utf8);
                if (!declName.isEmpty()) {
                    var params = extractParameterNames(language, node, utf8);
                    scope.functionNames.put(declSpan, declName);
                    if (!params.isEmpty())
                        scope.functionParams.put(declSpan, params);
                }
                if (!owner.isEmpty()) {
                    String outerClass = scope.enclosingClassSpan.getOrDefault(owner,
                            scope.classRoutePrefixes.containsKey(owner) || scope.interfaceSpans.contains(owner)
                                    ? owner : "");
                    if (!outerClass.isEmpty()) {
                        scope.enclosingClassSpan.put(declSpan, outerClass);
                    }
                }
                if (kind.equals("interface_declaration")) {
                    scope.interfaceSpans.add(declSpan);
                }
                if (kind.equals("class_declaration") || kind.equals("abstract_class_declaration")) {
                    String prefix = extractClassRoutePrefix(language, node, utf8, scope);
                    if (prefix != null && !prefix.isEmpty()) {
                        scope.classRoutePrefixes.put(declSpan, prefix);
                    }
                }
                owner = declSpan;
            }
            for (int i = node.getNamedChildCount() - 1; i >= 0; i--) {
                pending.push(new Frame(node.getNamedChild(i), owner));
            }
        }
        return scope;
    }

    private static String declarationNameFromChild(TSNode node, byte[] utf8) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            var child = node.getNamedChild(i);
            String ck = child.getType();
            if (ck.equals("variable_declarator") || ck.equals("function_declarator") || ck.equals("simple_identifier")
                    || ck.equals("identifier")) {
                var n = field(child, "name");
                if (n != null)
                    return text(n, utf8);
                if (child.getNamedChildCount() > 0 && child.getNamedChild(0).getType().contains("identifier")) {
                    return text(child.getNamedChild(0), utf8);
                }
                if (ck.contains("identifier"))
                    return text(child, utf8);
            }
        }
        return "";
    }

    private static List<String> extractParameterNames(String language, TSNode declNode, byte[] utf8) {
        TSNode paramsNode = findParamsNode(declNode);
        if (paramsNode == null)
            return List.of();
        var result = new ArrayList<String>();
        for (int i = 0; i < paramsNode.getNamedChildCount(); i++) {
            var p = paramsNode.getNamedChild(i);
            String pname = extractSingleParamName(p, utf8);
            if (pname == null || pname.isBlank())
                continue;
            if (language.equals("python") && result.isEmpty() && (pname.equals("self") || pname.equals("cls")))
                continue;
            result.add(pname);
        }
        return List.copyOf(result);
    }

    private static TSNode findParamsNode(TSNode node) {
        var direct = field(node, "parameters");
        if (direct != null)
            return direct;
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            var c = node.getNamedChild(i);
            String ck = c.getType();
            if (Set.of("parameters", "formal_parameters", "parameter_list", "function_value_parameters").contains(ck)) {
                return c;
            }
            if (ck.equals("variable_declarator")) {
                var val = field(c, "value");
                if (val != null) {
                    var nested = findParamsNode(val);
                    if (nested != null)
                        return nested;
                }
            }
        }
        return null;
    }

    private static String extractSingleParamName(TSNode param, byte[] utf8) {
        String pk = param.getType();
        if (pk.equals("identifier") || pk.equals("simple_identifier"))
            return text(param, utf8);
        var n = field(param, "name");
        if (n != null)
            return text(n, utf8);
        var pat = field(param, "pattern");
        if (pat != null && (pat.getType().equals("identifier") || pat.getType().equals("simple_identifier")))
            return text(pat, utf8);
        for (int i = 0; i < param.getNamedChildCount(); i++) {
            var c = param.getNamedChild(i);
            if (Set.of("identifier", "simple_identifier").contains(c.getType()))
                return text(c, utf8);
        }
        return null;
    }

    private static void collectImportAlias(String language, String stmt, Map<String, String> out) {
        if (stmt == null || stmt.isBlank() || stmt.length() > MAX_REGEX_NODE_LENGTH)
            return;
        String clean = stmt.replace('\n', ' ').replace('\r', ' ').trim();
        if (clean.length() > MAX_REGEX_NODE_LENGTH)
            return;
        var pyAs = PY_AS_PATTERN.matcher(clean);
        while (pyAs.find())
            out.putIfAbsent(pyAs.group(2), pyAs.group(1));
        var jsDef = JS_DEF_PATTERN.matcher(clean);
        while (jsDef.find())
            out.putIfAbsent(jsDef.group(1), jsDef.group(2));
        if (clean.contains("{")) {
            var jsNamed = JS_NAMED_PATTERN.matcher(clean);
            while (jsNamed.find())
                out.putIfAbsent(jsNamed.group(2), jsNamed.group(1));
        }
        if (language.equals("go")) {
            var goImp = GO_IMP_PATTERN.matcher(clean);
            while (goImp.find()) {
                String alias = goImp.group(1), pkg = goImp.group(2);
                int sl = pkg.lastIndexOf('/');
                out.putIfAbsent(alias, sl >= 0 ? pkg.substring(sl + 1) : pkg);
            }
        }
        if (language.equals("rust")) {
            var rsUse = RS_USE_PATTERN.matcher(clean);
            while (rsUse.find())
                out.putIfAbsent(rsUse.group(2), rsUse.group(1));
        }
    }

    private static void collectBindingFromDeclaration(String language, TSNode node, byte[] utf8, FileScope scope) {
        TSNode lhs = null, rhs = null;
        String kind = node.getType();
        if (kind.equals("variable_declarator") || kind.equals("assignment") || kind.equals("assignment_expression")) {
            lhs = field(node, "name");
            if (lhs == null)
                lhs = field(node, "left");
            rhs = field(node, "value");
            if (rhs == null)
                rhs = field(node, "right");
        }
        if (lhs == null && node.getNamedChildCount() >= 2) {
            lhs = node.getNamedChild(0);
            rhs = node.getNamedChild(node.getNamedChildCount() - 1);
        }
        int nodeBytes = node.getEndByte() - node.getStartByte();
        String rawNodeText = nodeBytes <= MAX_REGEX_NODE_LENGTH ? text(node, utf8) : "";
        String varName = lhs != null
                && (lhs.getType().equals("identifier") || lhs.getType().equals("simple_identifier")
                        || lhs.getType().equals("variable_name"))
                        ? text(lhs, utf8)
                        : null;
        if (varName == null && !rawNodeText.isEmpty() && rawNodeText.length() <= MAX_REGEX_NODE_LENGTH) {
            var eq = DECL_ASSIGN_LHS_PATTERN.matcher(rawNodeText.trim());
            if (eq.find())
                varName = eq.group(1);
        }
        if (varName == null || varName.isBlank())
            return;
        if (rhs != null) {
            var res = resolveStaticString(rhs, utf8, scope, 0);
            if (res != null) {
                scope.stringConstants.put(varName, res.unquoted());
                return;
            }
        }
        int rhsBytes = rhs != null ? rhs.getEndByte() - rhs.getStartByte() : nodeBytes;
        String rhsText = rhs != null
                ? (rhsBytes <= MAX_REGEX_NODE_LENGTH ? text(rhs, utf8) : "")
                : rawNodeText;
        String resolvedRhs = resolveAliasPrefix(rhsText, scope.importAliases);
        if (language.equals("python") && rhsText.length() <= MAX_REGEX_NODE_LENGTH
                && (resolvedRhs.contains("APIRouter(") || resolvedRhs.contains("Blueprint("))) {
            String prefix = extractRouterPrefix(rhs, rhsText, utf8, scope);
            if (prefix != null && !prefix.isEmpty()) {
                scope.routePrefixes.put(varName, prefix);
            }
            return;
        }
        if (language.equals("go") && rhs != null && rhsText.length() <= MAX_REGEX_NODE_LENGTH) {
            TSNode callNode = rhs;
            if (callNode.getType().equals("expression_list") && callNode.getNamedChildCount() >= 1) {
                callNode = callNode.getNamedChild(0);
            }
            if (CALLS.contains(callNode.getType())) {
                String callee = text(head(callNode), utf8);
                if (tailName(callee).equals("Group")) {
                    var args = argumentsNode(callNode);
                    if (args != null && args.getNamedChildCount() >= 1) {
                        var resolved = resolveStaticString(unwrapArgument(args.getNamedChild(0)), utf8, scope, 0);
                        if (resolved != null && !resolved.unquoted().isBlank()) {
                            String parentPrefix = scope.routePrefixes.getOrDefault(receiverName(callee), "");
                            String combined = parentPrefix.isEmpty()
                                    ? normalizeLeadingSlash(resolved.unquoted().trim())
                                    : joinBaseAndPath(normalizeLeadingSlash(parentPrefix), resolved.unquoted().trim());
                            scope.routePrefixes.put(varName, combined);
                            return;
                        }
                    }
                }
            }
        }
        if (looksLikeHttpClientFactory(resolvedRhs) || looksLikeHttpClientFactory(rawNodeText)) {
            String baseUrl = extractBaseUrlLiteral(rhsText);
            scope.httpClients.put(varName, new ClientBinding("FACTORY", baseUrl == null ? "" : baseUrl));
            return;
        }
        String eventProto = detectEventClientFactory(resolvedRhs);
        if (eventProto == null)
            eventProto = detectEventClientFactory(rawNodeText);
        if (eventProto != null) {
            scope.eventClients.put(varName, eventProto);
        }
    }

    private static boolean looksLikeHttpClientFactory(String expr) {
        if (expr == null || expr.isEmpty() || expr.length() > MAX_REGEX_NODE_LENGTH)
            return false;
        return expr.contains("axios.create(") || expr.contains("ky.create(") || expr.contains("got.extend(")
                || expr.contains("ofetch.create(") || expr.contains("httpx.Client(")
                || expr.contains("httpx.AsyncClient(")
                || expr.contains("requests.Session(") || expr.contains("aiohttp.ClientSession(")
                || expr.contains("http.Client{") || expr.contains("resty.New(")
                || expr.contains("new HttpClient(") || expr.contains("new RestClient(")
                || expr.contains("WebClient.builder(") || expr.contains("RestClient.builder(")
                || expr.contains("new RestTemplate(")
                || expr.contains("OkHttpClient(") || expr.contains("reqwest::Client")
                || expr.contains("Dio(")
                || expr.contains("Faraday.new(") || expr.contains("GuzzleHttp\\Client(")
                || expr.contains("new Client(")
                || HTTP_FACTORY_TYPE_PATTERN.matcher(expr).find();
    }

    private static String detectEventClientFactory(String expr) {
        if (expr == null || expr.isEmpty() || expr.length() > MAX_REGEX_NODE_LENGTH)
            return null;
        if (KAFKA_FACTORY_PATTERN.matcher(expr).find())
            return "KAFKA";
        if (AMQP_FACTORY_PATTERN.matcher(expr).find())
            return "AMQP";
        if (NATS_FACTORY_PATTERN.matcher(expr).find())
            return "NATS";
        if (AWS_MSG_FACTORY_PATTERN.matcher(expr).find())
            return "AWS_MESSAGING";
        if (REDIS_FACTORY_PATTERN.matcher(expr).find())
            return "REDIS_PUBSUB";
        if (EVENT_BUS_FACTORY_PATTERN.matcher(expr).find())
            return "EVENT_BUS";
        if (MASSTRANSIT_FACTORY_PATTERN.matcher(expr).find())
            return "MASSTRANSIT";
        return null;
    }

    private static String extractBaseUrlLiteral(String expr) {
        if (expr == null || expr.isEmpty() || expr.length() > MAX_REGEX_NODE_LENGTH)
            return "";
        var m = BASE_URL_LITERAL_PATTERN.matcher(expr);
        return m.find() ? m.group(2).trim() : "";
    }

    private static String extractRouterPrefix(TSNode rhs, String rhsText, byte[] utf8, FileScope scope) {
        if (rhsText != null && rhsText.length() <= MAX_REGEX_NODE_LENGTH) {
            var m = ROUTER_PREFIX_PATTERN.matcher(rhsText);
            if (m.find()) {
                return m.group(2).trim();
            }
        }
        if (rhs != null) {
            var args = argumentsNode(rhs);
            if (args != null) {
                for (int i = 0; i < args.getNamedChildCount(); i++) {
                    var arg = args.getNamedChild(i);
                    if (arg.getType().equals("keyword_argument")) {
                        var k = field(arg, "name");
                        var v = field(arg, "value");
                        String key = text(k, utf8);
                        if ((key.equals("prefix") || key.equals("url_prefix")) && v != null) {
                            var resolved = resolveStaticString(v, utf8, scope, 0);
                            if (resolved != null) {
                                return resolved.unquoted().trim();
                            }
                        }
                    }
                }
            }
        }
        return "";
    }

    private static void collectExpressRouterMount(TSNode call, byte[] utf8, FileScope scope) {
        String callee = text(head(call), utf8);
        if (!tailName(callee).equals("use")) return;
        var args = argumentsNode(call);
        if (args == null || args.getNamedChildCount() < 2) return;
        var prefixRes = resolveStaticString(unwrapArgument(args.getNamedChild(0)), utf8, scope, 0);
        if (prefixRes == null || prefixRes.unquoted().isBlank() || prefixRes.unquoted().indexOf('/') < 0) return;
        String parentPrefix = scope.routePrefixes.getOrDefault(receiverName(callee), "");
        String mountPrefix = parentPrefix.isEmpty()
                ? normalizeLeadingSlash(prefixRes.unquoted().trim())
                : joinBaseAndPath(normalizeLeadingSlash(parentPrefix), prefixRes.unquoted().trim());
        for (int i = 1; i < args.getNamedChildCount(); i++) {
            var target = unwrapArgument(args.getNamedChild(i));
            if (target.getType().equals("identifier")) {
                String routerVar = text(target, utf8);
                if (!routerVar.isBlank()) {
                    String existing = scope.routePrefixes.getOrDefault(routerVar, "");
                    scope.routePrefixes.put(routerVar, existing.isEmpty()
                            ? mountPrefix
                            : joinBaseAndPath(mountPrefix, existing));
                }
            }
        }
    }

    private static void collectPythonCeleryTaskMapping(TSNode decoratedDef, byte[] utf8, FileScope scope) {
        String funcName = "";
        String explicitTaskName = "";
        for (int i = 0; i < decoratedDef.getNamedChildCount(); i++) {
            var child = decoratedDef.getNamedChild(i);
            String ck = child.getType();
            if (ck.equals("function_definition")) {
                funcName = text(name(child), utf8);
            } else if (ck.equals("decorator")) {
                String candidate = extractCeleryDecoratorTaskName(child, utf8, scope);
                if (candidate != null && !candidate.isEmpty()) {
                    explicitTaskName = candidate;
                }
            }
        }
        if (!funcName.isEmpty() && !explicitTaskName.isEmpty()) {
            scope.celeryTaskNames.put(funcName, explicitTaskName);
        }
    }

    private static String extractClassRoutePrefix(String language, TSNode classNode, byte[] utf8, FileScope scope) {
        if (Set.of("typescript", "tsx", "javascript").contains(language)) {
            String fromSelf = findNestControllerPrefix(classNode, utf8, scope);
            if (fromSelf != null) return fromSelf;
            var parent = classNode.getParent();
            if (parent != null && !parent.isNull() && parent.getType().equals("export_statement")) {
                return findNestControllerPrefix(parent, utf8, scope);
            }
        } else if (language.equals("csharp")) {
            for (int i = 0; i < classNode.getNamedChildCount(); i++) {
                var child = classNode.getNamedChild(i);
                if (!child.getType().equals("attribute_list")) continue;
                for (int j = 0; j < child.getNamedChildCount(); j++) {
                    var attr = child.getNamedChild(j);
                    if (!attr.getType().equals("attribute")) continue;
                    String attrName = tailName(text(head(attr), utf8));
                    if (attrName.equals("Route")) {
                        var args = argumentsNode(attr);
                        if (args != null && args.getNamedChildCount() > 0) {
                            var resolved = resolveStaticString(unwrapArgument(args.getNamedChild(0)), utf8, scope, 0);
                            if (resolved != null && !resolved.unquoted().isBlank()) {
                                return normalizeLeadingSlash(resolved.unquoted());
                            }
                        }
                    }
                }
            }
        } else if (language.equals("kotlin")) {
            for (int i = 0; i < classNode.getNamedChildCount(); i++) {
                var child = classNode.getNamedChild(i);
                if (!child.getType().equals("modifiers")) continue;
                for (int j = 0; j < child.getNamedChildCount(); j++) {
                    var ann = child.getNamedChild(j);
                    if (!ann.getType().equals("annotation")) continue;
                    String annText = text(ann, utf8);
                    if (!annText.contains("RequestMapping")) continue;
                    var args = argumentsNode(ann);
                    if (args != null && args.getNamedChildCount() > 0) {
                        var resolved = resolveStaticString(unwrapArgument(args.getNamedChild(0)), utf8, scope, 0);
                        if (resolved != null && !resolved.unquoted().isBlank()) {
                            return normalizeLeadingSlash(resolved.unquoted().trim());
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String findNestControllerPrefix(TSNode container, byte[] utf8, FileScope scope) {
        for (int i = 0; i < container.getNamedChildCount(); i++) {
            var child = container.getNamedChild(i);
            if (!child.getType().equals("decorator") || child.getNamedChildCount() == 0) continue;
            var inner = child.getNamedChild(0);
            if (!CALLS.contains(inner.getType())) continue;
            String callee = tailName(text(head(inner), utf8));
            if (!callee.equals("Controller")) continue;
            var args = argumentsNode(inner);
            if (args == null || args.getNamedChildCount() == 0) return "";
            var arg0 = unwrapArgument(args.getNamedChild(0));
            var resolved = resolveStaticString(arg0, utf8, scope, 0);
            if (resolved != null && !resolved.unquoted().isBlank()) {
                return normalizeLeadingSlash(resolved.unquoted());
            }
            if (arg0.getEndByte() - arg0.getStartByte() <= MAX_REGEX_NODE_LENGTH) {
                var m = CONTROLLER_PATH_OBJ_PATTERN.matcher(text(arg0, utf8));
                if (m.find() && !m.group(2).isBlank()) {
                    return normalizeLeadingSlash(m.group(2).trim());
                }
            }
        }
        return null;
    }

    private static String normalizeLeadingSlash(String path) {
        if (path == null || path.isEmpty()) return "";
        if (path.startsWith("/") || path.startsWith("http://") || path.startsWith("https://")) return path;
        return "/" + path;
    }

    private static String resolveAliasPrefix(String written, Map<String, String> aliases) {
        if (aliases.isEmpty() || written == null || written.isEmpty())
            return written;
        int sep = -1;
        int dot = written.indexOf('.');
        int colon = written.indexOf("::");
        if (dot > 0 && (colon < 0 || dot < colon))
            sep = dot;
        else if (colon > 0)
            sep = colon;
        String head = sep > 0 ? written.substring(0, sep) : written;
        String target = aliases.get(head);
        if (target == null)
            return written;
        return sep > 0 ? target + written.substring(sep) : target;
    }

    private static Map<String, String> callAttributes(TSNode call, byte[] utf8, FileScope scope, String ownerSpan) {
        var args = argumentsNode(call);
        if (args == null || args.getNamedChildCount() == 0)
            return Map.of();
        var attrs = new TreeMap<String, String>();
        var ownerParams = ownerSpan.isEmpty() ? List.<String>of()
                : scope.functionParams.getOrDefault(ownerSpan, List.of());
        int litCount = Math.min(3, args.getNamedChildCount());
        for (int i = 0; i < litCount; i++) {
            var argExpr = unwrapArgument(args.getNamedChild(i));
            var resolved = resolveStaticString(argExpr, utf8, scope, 0);
            if (resolved != null && resolved.unquoted().length() <= 512 && resolved.unquoted().indexOf('\\') < 0) {
                attrs.put("litArg" + i, resolved.unquoted());
            } else if (!ownerParams.isEmpty()
                    && Set.of("identifier", "simple_identifier").contains(argExpr.getType())) {
                int pIdx = ownerParams.indexOf(text(argExpr, utf8));
                if (pIdx >= 0 && i < 2) {
                    attrs.put("forwardParam" + i, String.valueOf(pIdx));
                }
            }
        }
        return attrs.isEmpty() ? Map.of() : Map.copyOf(attrs);
    }

    private static TSNode unwrapArgument(TSNode arg) {
        if (Set.of("argument", "value_argument", "attribute_argument").contains(arg.getType())
                && arg.getNamedChildCount() >= 1) {
            return arg.getNamedChild(arg.getNamedChildCount() - 1);
        }
        return arg;
    }

    private static ResolvedString resolveStaticString(TSNode expr, byte[] utf8, FileScope scope, int depth) {
        if (expr == null || depth > 8)
            return null;
        String kind = expr.getType();
        if (Set.of("string", "string_literal", "line_string_literal", "multi_line_string_literal",
                "interpreted_string_literal", "template_string", "encapsed_string").contains(kind)) {
            if (interpolated(expr))
                return null;
            if (kind.equals("encapsed_string")) {
                for (int i = 0; i < expr.getNamedChildCount(); i++) {
                    if (!expr.getNamedChild(i).getType().equals("string_content"))
                        return null;
                }
            }
            String raw = text(expr, utf8);
            if (raw.indexOf('\\') >= 0 || raw.length() < 2)
                return null;
            char q = raw.charAt(0);
            if ((q == '\'' || q == '"' || q == '`') && raw.charAt(raw.length() - 1) == q) {
                return new ResolvedString(raw, raw.substring(1, raw.length() - 1));
            }
            return null;
        }
        if (Set.of("identifier", "simple_identifier").contains(kind)) {
            String val = scope.stringConstants.get(text(expr, utf8));
            return val != null ? new ResolvedString(null, val) : null;
        }
        if (Set.of("binary_expression", "binary_operator", "additive_expression").contains(kind)) {
            if (expr.getNamedChildCount() < 2)
                return null;
            boolean hasPlusOperator = false;
            for (int i = 0; i < expr.getChildCount(); i++) {
                var child = expr.getChild(i);
                if (!child.isNamed() && "+".equals(child.getType())) {
                    hasPlusOperator = true;
                    break;
                }
            }
            if (!hasPlusOperator)
                return null;
            var sb = new StringBuilder();
            for (int i = 0; i < expr.getNamedChildCount(); i++) {
                var part = resolveStaticString(expr.getNamedChild(i), utf8, scope, depth + 1);
                if (part == null)
                    return null;
                sb.append(part.unquoted());
            }
            return new ResolvedString(null, sb.toString());
        }
        if (kind.equals("parenthesized_expression") && expr.getNamedChildCount() == 1) {
            return resolveStaticString(expr.getNamedChild(0), utf8, scope, depth + 1);
        }
        return null;
    }

    private static String tailName(String written) {
        if (written == null || written.isEmpty())
            return "";
        int lt = written.indexOf('<');
        String base = lt > 0 ? written.substring(0, lt) : written;
        int dot = base.lastIndexOf('.');
        int colon = base.lastIndexOf(':');
        int arrow = base.lastIndexOf("->");
        int sepEnd = Math.max(dot + 1, Math.max(colon + 1, arrow >= 0 ? arrow + 2 : 0));
        return base.substring(sepEnd).trim();
    }

    private static String receiverName(String written) {
        if (written == null)
            return "";
        int lt = written.indexOf('<');
        String base = lt > 0 ? written.substring(0, lt) : written;
        int lastSep = Math.max(base.lastIndexOf('.'),
                Math.max(base.lastIndexOf("::"), base.lastIndexOf("->")));
        return lastSep > 0 ? base.substring(0, lastSep).trim() : "";
    }

    private static boolean httpClient(String language, String written, FileScope scope) {
        String resolved = resolveAliasPrefix(written, scope.importAliases);
        if (httpClientRegexMatch(language, resolved) || httpClientRegexMatch(language, written))
            return true;
        String recv = receiverName(written);
        String tail = tailName(written);
        return !recv.isEmpty() && scope.httpClients.containsKey(recv) && HTTP_VERBS.contains(tail);
    }

    private static boolean httpClientRegexMatch(String language, String written) {
        if (written == null || written.length() > MAX_REGEX_NODE_LENGTH)
            return false;
        if (language.equals("python"))
            return PYTHON_HTTP_CLIENT.matcher(written).matches();
        if (language.equals("go"))
            return Set.of("http.Get", "http.Post", "http.Head", "http.NewRequest", "http.NewRequestWithContext")
                    .contains(written) || GO_HTTP_CLIENT.matcher(written).matches();
        if (language.equals("csharp"))
            return CSHARP_HTTP_CLIENT.matcher(written).matches();
        if (language.equals("rust"))
            return written.equals("reqwest::get") || RUST_HTTP_CLIENT.matcher(written).matches();
        if (language.equals("dart"))
            return DART_HTTP_CLIENT.matcher(written).matches();
        if (Set.of("kotlin", "scala").contains(language))
            return KOTLIN_HTTP_CLIENT.matcher(written).matches();
        if (language.equals("php"))
            return PHP_HTTP_CLIENT.matcher(written).matches();
        if (language.equals("ruby"))
            return RUBY_HTTP_CLIENT.matcher(written).matches();
        if (!Set.of("javascript", "typescript", "tsx").contains(language))
            return false;
        return Set
                .of("fetch", "window.fetch", "globalThis.fetch", "$fetch", "useFetch", "useSWR", "got", "ky", "ofetch")
                .contains(written)
                || JS_HTTP_CLIENT.matcher(written).matches();
    }

    private static int emitHttpCallOrWrapper(String language, String kind, TSNode node, String written, String tail,
            byte[] utf8, SourceCoordinates.Range span, String owner, boolean recovered,
            FileScope scope, Sink sink) throws IOException {
        if (!httpClient(language, written, scope))
            return 0;
        var args = argumentsNode(node);
        if (args == null || args.getNamedChildCount() == 0)
            return 0;
        int urlIdx = 0;
        if (language.equals("go")) {
            if (written.endsWith("NewRequest") && args.getNamedChildCount() >= 2)
                urlIdx = 1;
            else if (written.endsWith("NewRequestWithContext") && args.getNamedChildCount() >= 3)
                urlIdx = 2;
        } else if ((language.equals("php") || language.equals("python"))
                && tail.equalsIgnoreCase("request") && args.getNamedChildCount() >= 2) {
            urlIdx = 1;
        }
        if (urlIdx >= args.getNamedChildCount())
            return 0;
        TSNode urlExpr = unwrapArgument(args.getNamedChild(urlIdx));
        String method = resolveClientMethod(language, node, written, tail, utf8, args, urlIdx);
        String baseUrl = "";
        String recv = receiverName(written);
        if (!recv.isEmpty() && scope.httpClients.containsKey(recv)) {
            baseUrl = scope.httpClients.get(recv).baseUrl();
        }
        var resolvedUrl = resolveStaticString(urlExpr, utf8, scope, 0);
        if (resolvedUrl != null) {
            String unquoted = resolvedUrl.unquoted();
            String joined = joinBaseAndPath(baseUrl, unquoted);
            if (joined.indexOf('/') < 0)
                return 0;
            String quoted = (baseUrl.isEmpty() && resolvedUrl.rawQuoted() != null)
                    ? resolvedUrl.rawQuoted()
                    : "\"" + joined + "\"";
            if (quoted.length() <= 4096) {
                sink.accept(new Fact("HTTP_CALL_CANDIDATE", kind, quoted, excerpt(written), span, owner, recovered,
                        Map.of("method", method)));
                return 1;
            }
            return 0;
        }
        if (!owner.isEmpty() && Set.of("identifier", "simple_identifier").contains(urlExpr.getType())) {
            var params = scope.functionParams.getOrDefault(owner, List.of());
            String wrapperName = scope.functionNames.getOrDefault(owner, "");
            int pIdx = params.indexOf(text(urlExpr, utf8));
            if (pIdx >= 0 && !wrapperName.isBlank()) {
                var attrs = new TreeMap<String, String>();
                attrs.put("wrapperName", wrapperName);
                attrs.put("paramIndex", String.valueOf(pIdx));
                attrs.put("method", method);
                if (!baseUrl.isEmpty())
                    attrs.put("basePath", baseUrl);
                sink.accept(
                        new Fact("HTTP_WRAPPER", kind, wrapperName, excerpt(written), span, owner, recovered, attrs));
                return 1;
            }
        }
        return 0;
    }

    private static String joinBaseAndPath(String baseUrl, String path) {
        if (baseUrl == null || baseUrl.isEmpty())
            return path;
        if (path == null || path.isEmpty())
            return baseUrl;
        if (path.startsWith("http://") || path.startsWith("https://"))
            return path;
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    private static String resolveClientMethod(String language, TSNode call, String written, String tail,
            byte[] bytes, TSNode args, int urlIdx) {
        if ((language.equals("go") || language.equals("php") || language.equals("python")) && urlIdx > 0) {
            var mTok = firstQuotedAt(args, urlIdx - 1, bytes);
            if (mTok != null && mTok.length() >= 3) {
                String candidate = mTok.substring(1, mTok.length() - 1).toUpperCase(Locale.ROOT);
                if (Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE").contains(candidate))
                    return candidate;
            }
        }
        return clientMethod(call, written, bytes);
    }

    private static String firstQuotedAt(TSNode args, int index, byte[] bytes) {
        if (args == null || index < 0 || index >= args.getNamedChildCount())
            return null;
        var node = unwrapArgument(args.getNamedChild(index));
        String raw = text(node, bytes);
        if (!Set.of("string", "string_literal", "line_string_literal", "multi_line_string_literal",
                "interpreted_string_literal", "template_string").contains(node.getType()) || interpolated(node)
                || raw.indexOf('\\') >= 0 || raw.length() < 3)
            return null;
        char q = raw.charAt(0);
        return (q == '\'' || q == '"' || q == '`') && raw.charAt(raw.length() - 1) == q ? raw : null;
    }

    private static String httpMethod(String tail) {
        String method = HTTP_METHOD_SUFFIX.matcher(HTTP_METHOD_PREFIX.matcher(tail).replaceFirst("")).replaceFirst("")
                .toUpperCase(Locale.ROOT);
        if (Set.of("GETSTRING", "GETBYTEARRAY").contains(method))
            method = "GET";
        if (Set.of("GETFOROBJECT", "GETFORENTITY", "URLOPEN").contains(method))
            method = "GET";
        if (Set.of("POSTFOROBJECT", "POSTFORENTITY").contains(method))
            method = "POST";
        return Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE").contains(method) ? method
                : "UNKNOWN";
    }

    private static String firstLiteral(TSNode call, byte[] bytes) {
        String raw = firstStringToken(call, bytes);
        return raw != null && raw.indexOf('/') >= 0 ? raw : null;
    }

    private static String firstStringToken(TSNode call, byte[] bytes) {
        var args = argumentsNode(call);
        if (args == null || args.getNamedChildCount() == 0)
            return null;
        return firstQuotedAt(args, 0, bytes);
    }

    private static TSNode argumentsNode(TSNode node) {
        var args = field(node, "arguments");
        if (args != null)
            return args;
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            var c = node.getNamedChild(i);
            if (Set.of("arguments", "value_arguments", "argument_list", "attribute_argument_list").contains(c.getType()))
                return c;
            if (c.getType().equals("call_suffix")) {
                for (int j = 0; j < c.getNamedChildCount(); j++) {
                    var nested = c.getNamedChild(j);
                    if (Set.of("arguments", "value_arguments", "argument_list").contains(nested.getType()))
                        return nested;
                }
            }
        }
        return null;
    }

    private static int emitNextJsRouteExport(String language, String kind, TSNode node, String name, String written,
            byte[] utf8, SourceCoordinates.Range span, String owner, boolean recovered, Sink sink) throws IOException {
        if (!Set.of("javascript", "typescript", "tsx").contains(language))
            return 0;
        var parent = node.getParent();
        if (parent == null || parent.isNull() || !parent.getType().equals("export_statement"))
            return 0;
        if (kind.equals("function_declaration") && NEXTJS_ROUTE_EXPORTS.contains(name)) {
            sink.accept(new Fact("ROUTE_CANDIDATE", kind, "\"/\"", excerpt(written), span, owner, recovered,
                    Map.of("method", name, "framework", "nextjs-app-router")));
            return 1;
        }
        if (kind.equals("lexical_declaration")) {
            int emitted = 0;
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                var child = node.getNamedChild(i);
                if (!child.getType().equals("variable_declarator")) continue;
                String varName = text(field(child, "name"), utf8);
                var val = field(child, "value");
                if (NEXTJS_ROUTE_EXPORTS.contains(varName) && val != null
                        && Set.of("arrow_function", "function_expression", "function").contains(val.getType())) {
                    sink.accept(new Fact("ROUTE_CANDIDATE", child.getType(), "\"/\"", excerpt(varName), span, owner,
                            recovered, Map.of("method", varName, "framework", "nextjs-app-router")));
                    emitted++;
                }
            }
            return emitted;
        }
        return 0;
    }

    private static boolean emitCSharpRefitAttribute(String language, String kind, TSNode node, String written,
            String tail, byte[] utf8, SourceCoordinates.Range span, String owner, boolean recovered,
            FileScope scope, Sink sink) throws IOException {
        if (!language.equals("csharp") || !kind.equals("attribute") || !REFIT_HTTP_ATTRIBUTES.contains(tail))
            return false;
        boolean inInterface = scope.interfaceSpans.contains(owner)
                || scope.interfaceSpans.contains(scope.enclosingClassSpan.getOrDefault(owner, ""));
        if (!inInterface && !scope.hasRefitImport)
            return false;
        String literal = routeLiteral(node, utf8, scope);
        if (literal == null || literal.length() > 4096)
            return false;
        sink.accept(new Fact("HTTP_CALL_CANDIDATE", kind, literal, excerpt(written), span, owner, recovered,
                Map.of("method", httpMethod(tail), "framework", "refit")));
        return true;
    }

    private static int emitRouteCandidate(String language, String category, String kind, TSNode node, String written,
            String tail, byte[] utf8, SourceCoordinates.Range span, String owner, boolean recovered,
            FileScope scope, Sink sink) throws IOException {
        if (httpClient(language, written, scope))
            return 0;
        String recv = receiverName(written);
        if (language.equals("ruby") && category.equals("CALL") && recv.isEmpty()) {
            if (tail.equals("resources") || tail.equals("resource")) {
                var args = argumentsNode(node);
                if (args != null && args.getNamedChildCount() > 0) {
                    var arg0 = unwrapArgument(args.getNamedChild(0));
                    String sym = text(arg0, utf8).trim();
                    if (sym.startsWith(":") && sym.length() > 1 && sym.length() <= 256) {
                        String path = "\"/" + sym.substring(1) + "\"";
                        sink.accept(new Fact("ROUTE_CANDIDATE", kind, path, excerpt(written), span, owner, recovered,
                                Map.of("method", "UNKNOWN")));
                        return 1;
                    }
                }
                return 0;
            }
            if (tail.equals("match")) {
                String literal = routeLiteral(node, utf8, scope);
                if (literal != null && literal.length() <= 4096) {
                    sink.accept(new Fact("ROUTE_CANDIDATE", kind, literal, excerpt(written), span, owner, recovered,
                            Map.of("method", "UNKNOWN")));
                    return 1;
                }
                return 0;
            }
        }
        boolean laravelAny = language.equals("php") && tail.equals("any")
                && (written.equals("Route::any") || written.endsWith("\\Route::any"));
        if (!ROUTES.contains(tail) && !laravelAny)
            return 0;
        String prefix = "";
        if (!recv.isEmpty() && scope.routePrefixes.containsKey(recv)) {
            prefix = scope.routePrefixes.get(recv);
        } else if ((Set.of("typescript", "tsx", "javascript").contains(language) && NESTJS_ROUTE_DECORATORS.contains(tail))
                || (language.equals("csharp") && kind.equals("attribute") && tail.startsWith("Http"))
                || (language.equals("kotlin") && kind.equals("annotation") && tail.endsWith("Mapping"))) {
            String classSpan = scope.classRoutePrefixes.containsKey(owner)
                    ? owner : scope.enclosingClassSpan.getOrDefault(owner, "");
            if (!classSpan.isEmpty()) {
                prefix = scope.classRoutePrefixes.getOrDefault(classSpan, "");
            }
        }
        String literal = prefixedRouteLiteral(node, utf8, scope, prefix);
        if (literal != null && literal.length() <= 4096) {
            sink.accept(new Fact("ROUTE_CANDIDATE", kind, literal, excerpt(written), span, owner, recovered,
                    Map.of("method", httpMethod(tail))));
            return 1;
        }
        return 0;
    }

    private static String prefixedRouteLiteral(TSNode call, byte[] bytes, FileScope scope, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return routeLiteral(call, bytes, scope);
        }
        var arguments = argumentsNode(call);
        if (arguments == null || arguments.getNamedChildCount() == 0) {
            String normalized = normalizeLeadingSlash(prefix);
            return normalized.indexOf('/') >= 0 ? "\"" + normalized + "\"" : null;
        }
        var arg0 = unwrapArgument(arguments.getNamedChild(0));
        var resolved = resolveStaticString(arg0, bytes, scope, 0);
        if (resolved == null)
            return null;
        String joined = joinBaseAndPath(normalizeLeadingSlash(prefix), resolved.unquoted());
        return joined.indexOf('/') >= 0 ? "\"" + joined + "\"" : null;
    }

    private static int emitMassTransitConsumerHeritage(String kind, String written, SourceCoordinates.Range span,
            String owner, boolean recovered, Sink sink) throws IOException {
        if (written == null || written.length() > MAX_REGEX_NODE_LENGTH)
            return 0;
        var matcher = MASSTRANSIT_ICONSUMER_PATTERN.matcher(written);
        int emitted = 0;
        while (matcher.find()) {
            String messageType = matcher.group(1);
            if (!messageType.isBlank() && messageType.length() <= 256) {
                sink.accept(new Fact("EVENT_CONSUMER_CANDIDATE", kind, "\"" + messageType + "\"", excerpt(written),
                        span, owner, recovered, Map.of("role", "CONSUMER", "protocol", "MASSTRANSIT")));
                emitted++;
            }
        }
        return emitted;
    }

    private static int emitCeleryTaskDecorator(String kind, TSNode decoratorNode, String written, byte[] utf8,
            SourceCoordinates.Range span, String owner, boolean recovered, FileScope scope, Sink sink)
            throws IOException {
        if (!isCeleryTaskDecorator(decoratorNode, utf8))
            return 0;
        String taskName = extractCeleryDecoratorTaskName(decoratorNode, utf8, scope);
        if (taskName == null || taskName.isBlank()) {
            var parent = decoratorNode.getParent();
            if (parent != null && !parent.isNull() && parent.getType().equals("decorated_definition")) {
                for (int i = 0; i < parent.getNamedChildCount(); i++) {
                    var child = parent.getNamedChild(i);
                    if (child.getType().equals("function_definition")) {
                        taskName = text(name(child), utf8);
                        break;
                    }
                }
            }
        }
        if (taskName == null || taskName.isBlank() || taskName.length() > 256)
            return 0;
        sink.accept(new Fact("EVENT_CONSUMER_CANDIDATE", kind, "\"" + taskName + "\"", excerpt(written), span, owner,
                recovered, Map.of("role", "CONSUMER", "protocol", "CELERY")));
        return 1;
    }

    private static boolean isCeleryTaskDecorator(TSNode decoratorNode, byte[] utf8) {
        if (decoratorNode == null || decoratorNode.getNamedChildCount() == 0)
            return false;
        var inner = decoratorNode.getNamedChild(0);
        String expr = CALLS.contains(inner.getType()) ? text(head(inner), utf8) : text(inner, utf8);
        String tail = tailName(expr);
        return tail.equals("shared_task") || (tail.equals("task") && expr.contains("."));
    }

    private static String extractCeleryDecoratorTaskName(TSNode decoratorNode, byte[] utf8, FileScope scope) {
        if (!isCeleryTaskDecorator(decoratorNode, utf8))
            return null;
        var inner = decoratorNode.getNamedChild(0);
        if (!CALLS.contains(inner.getType()))
            return null;
        var args = argumentsNode(inner);
        if (args == null)
            return null;
        for (int i = 0; i < args.getNamedChildCount(); i++) {
            var arg = args.getNamedChild(i);
            if (arg.getType().equals("keyword_argument")) {
                if ("name".equals(text(field(arg, "name"), utf8))) {
                    var resolved = resolveStaticString(field(arg, "value"), utf8, scope, 0);
                    if (resolved != null && !resolved.unquoted().isBlank())
                        return resolved.unquoted();
                }
            } else if (i == 0) {
                var resolved = resolveStaticString(unwrapArgument(arg), utf8, scope, 0);
                if (resolved != null && !resolved.unquoted().isBlank())
                    return resolved.unquoted();
            }
        }
        if (inner.getEndByte() - inner.getStartByte() <= MAX_REGEX_NODE_LENGTH) {
            var m = CELERY_NAME_ARG_PATTERN.matcher(text(inner, utf8));
            if (m.find() && !m.group(2).isBlank())
                return m.group(2);
        }
        return null;
    }

    private static int emitEventCandidate(String language, String category, String kind, TSNode node, String written,
            String tail, byte[] utf8, SourceCoordinates.Range span, String owner, boolean recovered, FileScope scope,
            Sink sink) throws IOException {
        String resolvedWritten = resolveAliasPrefix(written, scope.importAliases);
        String recv = receiverName(written);
        String boundProtocol = scope.eventClients.get(recv);
        if (language.equals("python") && category.equals("CALL")) {
            if ((tail.equals("delay") || tail.equals("apply_async")) && !recv.isEmpty()) {
                String funcKey = tailName(recv);
                if (!funcKey.isBlank() && funcKey.length() <= 256) {
                    String taskName = scope.celeryTaskNames.getOrDefault(funcKey, funcKey);
                    sink.accept(new Fact("EVENT_PRODUCER_CANDIDATE", kind, "\"" + taskName + "\"", excerpt(written),
                            span, owner, recovered, Map.of("role", "PRODUCER", "protocol", "CELERY")));
                    return 1;
                }
            }
            if (tail.equals("send_task")) {
                var args = argumentsNode(node);
                if (args != null && args.getNamedChildCount() > 0) {
                    var resolvedTask = resolveStaticString(unwrapArgument(args.getNamedChild(0)), utf8, scope, 0);
                    if (resolvedTask != null && !resolvedTask.unquoted().isBlank() && resolvedTask.unquoted().length() <= 256) {
                        String topic = resolvedTask.rawQuoted() != null ? resolvedTask.rawQuoted()
                                : "\"" + resolvedTask.unquoted() + "\"";
                        sink.accept(new Fact("EVENT_PRODUCER_CANDIDATE", kind, topic, excerpt(written), span, owner,
                                recovered, Map.of("role", "PRODUCER", "protocol", "CELERY")));
                        return 1;
                    }
                }
            }
        }
        if (language.equals("csharp") && category.equals("CALL")
                && (tail.startsWith("Publish") || tail.startsWith("Send"))) {
            boolean massTransit = "MASSTRANSIT".equals(boundProtocol)
                    || (resolvedWritten.length() <= MAX_REGEX_NODE_LENGTH
                            && MASSTRANSIT_PRODUCER_CALL.matcher(resolvedWritten).matches());
            if (massTransit) {
                String messageType = extractCSharpMessageType(node, written, utf8, scope);
                if (messageType != null && !messageType.isBlank() && messageType.length() <= 256) {
                    sink.accept(new Fact("EVENT_PRODUCER_CANDIDATE", kind, "\"" + messageType + "\"", excerpt(written),
                            span, owner, recovered, Map.of("role", "PRODUCER", "protocol", "MASSTRANSIT")));
                    return 1;
                }
            }
        }
        boolean producer = category.equals("CALL")
                && ((resolvedWritten.length() <= MAX_REGEX_NODE_LENGTH && EVENT_PRODUCER_CALL.matcher(resolvedWritten).matches())
                        || (boundProtocol != null && EVENT_PRODUCER_VERBS.contains(tail)));
        boolean consumer = (category.equals("CALL")
                && ((resolvedWritten.length() <= MAX_REGEX_NODE_LENGTH && EVENT_CONSUMER_CALL.matcher(resolvedWritten).matches())
                        || (boundProtocol != null && EVENT_CONSUMER_VERBS.contains(tail))))
                || EVENT_CONSUMER_ANNOTATIONS.contains(tail);
        if (!producer && !consumer)
            return 0;
        if (producer && consumer) {
            if (!category.equals("CALL") || EVENT_CONSUMER_ANNOTATIONS.contains(tail)) {
                producer = false;
            } else if (EVENT_CONSUMER_VERBS.contains(tail) && !EVENT_PRODUCER_VERBS.contains(tail)) {
                producer = false;
            } else if (EVENT_PRODUCER_VERBS.contains(tail) && !EVENT_CONSUMER_VERBS.contains(tail)) {
                consumer = false;
            } else {
                String recvLower = recv.toLowerCase(Locale.ROOT);
                if (recvLower.contains("consumer") || recvLower.contains("listener") || recvLower.contains("subscriber")) {
                    producer = false;
                } else {
                    consumer = false;
                }
            }
        }
        var args = argumentsNode(node);
        if (args == null || args.getNamedChildCount() == 0)
            return 0;
        TSNode topicExpr = unwrapArgument(args.getNamedChild(0));
        String lower = resolvedWritten.toLowerCase(Locale.ROOT);
        String protocol = boundProtocol != null ? boundProtocol
                : lower.contains("kafka") ? "KAFKA"
                        : (lower.contains("rabbit") || lower.contains("amqp")) ? "AMQP"
                                : lower.contains("nats") || lower.contains("nc.") ? "NATS"
                                        : lower.contains("sqs") || lower.contains("sns") ? "AWS_MESSAGING"
                                                : lower.contains("redis") ? "REDIS_PUBSUB" : "EVENT_BUS";
        var resolvedTopic = resolveStaticString(topicExpr, utf8, scope, 0);
        if (resolvedTopic != null) {
            if (resolvedTopic.unquoted().isBlank())
                return 0;
            String topic = resolvedTopic.rawQuoted() != null ? resolvedTopic.rawQuoted()
                    : "\"" + resolvedTopic.unquoted() + "\"";
            if (topic.length() <= 258) {
                sink.accept(new Fact(producer ? "EVENT_PRODUCER_CANDIDATE" : "EVENT_CONSUMER_CANDIDATE", kind, topic,
                        excerpt(written), span, owner, recovered,
                        Map.of("role", producer ? "PRODUCER" : "CONSUMER", "protocol", protocol)));
                return 1;
            }
            return 0;
        }
        if (category.equals("CALL") && !owner.isEmpty()
                && Set.of("identifier", "simple_identifier").contains(topicExpr.getType())) {
            var params = scope.functionParams.getOrDefault(owner, List.of());
            String wrapperName = scope.functionNames.getOrDefault(owner, "");
            int pIdx = params.indexOf(text(topicExpr, utf8));
            if (pIdx >= 0 && !wrapperName.isBlank()) {
                sink.accept(new Fact("EVENT_WRAPPER", kind, wrapperName, excerpt(written), span, owner, recovered,
                        Map.of("wrapperName", wrapperName, "paramIndex", String.valueOf(pIdx),
                                "role", producer ? "PRODUCER" : "CONSUMER", "protocol", protocol)));
                return 1;
            }
        }
        return 0;
    }

    private static String extractCSharpMessageType(TSNode call, String written, byte[] utf8, FileScope scope) {
        if (written != null && written.length() <= MAX_REGEX_NODE_LENGTH) {
            var m = GENERIC_ARG_PATTERN.matcher(written);
            if (m.find()) {
                return m.group(1);
            }
        }
        var args = argumentsNode(call);
        if (args == null || args.getNamedChildCount() == 0)
            return null;
        var arg0 = unwrapArgument(args.getNamedChild(0));
        if (arg0.getType().equals("object_creation_expression")) {
            var typeNode = field(arg0, "type");
            if (typeNode != null) {
                return text(typeNode, utf8).trim();
            }
        }
        var resolved = resolveStaticString(arg0, utf8, scope, 0);
        return resolved != null ? resolved.unquoted() : null;
    }

    private static String clientMethod(TSNode call, String written, byte[] bytes) {
        if (!written.endsWith("fetch"))
            return httpMethod(tailName(written));
        var args = argumentsNode(call);
        if (args == null)
            return "UNKNOWN";
        if (args.getNamedChildCount() == 1)
            return "GET";
        if (args.getNamedChildCount() != 2)
            return "UNKNOWN";
        var options = args.getNamedChild(1);
        if (!options.getType().equals("object"))
            return "UNKNOWN";
        String method = "GET";
        boolean seen = false;
        for (int i = 0; i < options.getNamedChildCount(); i++) {
            var pair = options.getNamedChild(i);
            if (!pair.getType().equals("pair"))
                return "UNKNOWN";
            var key = field(pair, "key");
            String name = text(key, bytes);
            if (name.equals("method") || name.equals("'method'") || name.equals("\"method\"")) {
                if (seen)
                    return "UNKNOWN";
                seen = true;
                var value = field(pair, "value");
                String raw = text(value, bytes);
                if (value == null || !value.getType().equals("string") || raw.indexOf('\\') >= 0 || raw.length() < 2)
                    return "UNKNOWN";
                method = raw.substring(1, raw.length() - 1);
                if (!method.equals(httpMethod(method)))
                    return "UNKNOWN";
            } else if (key == null || !Set.of("property_identifier", "string", "number").contains(key.getType()))
                return "UNKNOWN";
        }
        return method;
    }

    private static String category(String kind) {
        if (CALLS.contains(kind))
            return "CALL";
        if (kind.contains("import") || Set.of("use_declaration", "using_declaration", "using_directive",
                "preproc_include", "namespace_use_declaration").contains(kind))
            return "IMPORT";
        if (kind.endsWith("_declaration") || kind.endsWith("_definition")
                || Set.of("class_specifier", "struct_specifier", "function_signature", "function_item", "struct_item",
                        "enum_item", "trait_item", "impl_item", "method", "singleton_method", "class", "module")
                        .contains(kind))
            return "DECLARATION";
        if (Set.of("annotation", "marker_annotation", "attribute", "decorator").contains(kind))
            return "ANNOTATION";
        if (Set.of("base_list", "superclass", "super_interfaces", "extends_clause", "implements_clause",
                "delegation_specifier", "inheritance_clause").contains(kind))
            return "HERITAGE";
        if (kind.endsWith("_type")
                || Set.of("type_identifier", "type_annotation", "scoped_type_identifier").contains(kind))
            return "TYPE_SYNTAX";
        return "";
    }

    private static TSNode name(TSNode node) {
        var named = field(node, "name");
        if (named != null)
            return named;
        var declaration = field(node, "declarator");
        while (declaration != null) {
            var child = field(declaration, "declarator");
            if (child == null)
                return declaration;
            declaration = child;
        }
        return null;
    }

    private static TSNode field(TSNode node, String name) {
        var child = node.getChildByFieldName(name);
        return child == null || child.isNull() ? null : child;
    }

    private static TSNode head(TSNode node) {
        for (String name : List.of("function", "name", "method", "constructor", "type")) {
            var child = field(node, name);
            if (child != null)
                return child;
        }
        return node.getNamedChildCount() > 0 ? node.getNamedChild(0) : node;
    }

    private static String text(TSNode node, byte[] bytes) {
        return node == null ? ""
                : new String(bytes, node.getStartByte(), node.getEndByte() - node.getStartByte(),
                        StandardCharsets.UTF_8);
    }

    private static String excerpt(String value) {
        if (value.length() <= 4096)
            return value;
        return value.substring(0, Character.isHighSurrogate(value.charAt(4095)) ? 4095 : 4096);
    }

    private static String routeLiteral(TSNode call, byte[] bytes, FileScope scope) {
        var arguments = argumentsNode(call);
        if (arguments == null)
            return null;
        if (arguments.getNamedChildCount() == 0)
            return null;
        var arg0 = unwrapArgument(arguments.getNamedChild(0));
        var resolved = resolveStaticString(arg0, bytes, scope, 0);
        if (resolved != null && resolved.unquoted().indexOf('/') >= 0) {
            return resolved.rawQuoted() != null ? resolved.rawQuoted() : "\"" + resolved.unquoted() + "\"";
        }
        var pending = new ArrayDeque<TSNode>();
        pending.add(arguments.getNamedChild(0));
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            String kind = node.getType();
            if (kind.contains("string")) {
                if (interpolated(node))
                    continue;
                if (!kind.contains("interpolat") && text(node, bytes).indexOf('/') >= 0)
                    return text(node, bytes);
            }
            if (!CALLS.contains(kind)
                    && !Set.of("binary_expression", "binary_operator", "additive_expression",
                            "interpolated_string_expression", "template_string").contains(kind))
                for (int i = 0; i < node.getNamedChildCount(); i++)
                    pending.addLast(node.getNamedChild(i));
        }
        return null;
    }

    private static boolean interpolated(TSNode root) {
        var pending = new ArrayDeque<TSNode>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var node = pending.removeFirst();
            String kind = node.getType();
            if (kind.contains("interpolat") || kind.equals("template_substitution") || kind.equals("format_expression"))
                return true;
            for (int i = 0; i < node.getNamedChildCount(); i++)
                pending.addLast(node.getNamedChild(i));
        }
        return false;
    }

    private static String charset(byte[] bytes) {
        if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 254)
            return "UTF-16LE";
        if (bytes.length >= 2 && (bytes[0] & 255) == 254 && (bytes[1] & 255) == 255)
            return "UTF-16BE";
        return "UTF-8";
    }

    private static final Map<String, String> TOOLS = new java.util.concurrent.ConcurrentHashMap<>();

    private static String toolIdentity(Grammar grammar) throws IOException {
        String existing = TOOLS.get(grammar.library());
        if (existing != null)
            return existing;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT),
                arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = arch.contains("amd64") || arch.contains("x86_64") ? "x86_64"
                : arch.contains("aarch64") ? "aarch64" : "unsupported";
        String extension = os.contains("windows") ? "dll" : os.contains("mac") ? "dylib" : "so";
        platform += "-" + (os.contains("windows") ? "windows" : os.contains("mac") ? "macos" : "linux-gnu");
        String identity = CanonicalJson
                .sha256(List.of(PROVIDER, "tree-sitter:0.26.6", grammar.library(), grammar.version(),
                        resource("lib/" + platform + "-tree-sitter." + extension),
                        resource("lib/" + platform + "-" + grammar.library() + "." + extension),
                        resource(grammar.binding().replace('.', '/') + ".class"),
                        resource("com/evolution/analysis/treesitter/TreeSitterSourceFrontend.class"),
                        resource("com/evolution/analysis/treesitter/GrpcSourceSyntax.class")))
                .value();
        TOOLS.put(grammar.library(), identity);
        return identity;
    }

    private static ContentDigest resource(String name) throws IOException {
        try (var input = TreeSitterSourceFrontend.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null)
                throw new IOException("SYNTAX_BUNDLED_TOOL_BYTES_UNAVAILABLE");
            var digest = MessageDigest.getInstance("SHA-256");
            input.transferTo(new DigestOutputStream(OutputStream.nullOutputStream(), digest));
            return new ContentDigest("sha256:" + HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void check(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new IOException("SYNTAX_CANCELLED");
    }
}
