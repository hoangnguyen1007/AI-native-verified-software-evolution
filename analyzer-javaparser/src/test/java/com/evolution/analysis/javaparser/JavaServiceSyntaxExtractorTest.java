package com.evolution.analysis.javaparser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaServiceSyntaxExtractorTest {
    @Test
    void extractsFeignOAuth2RestDiscoveryControlPlaneAndMessagingCandidatesUnderImports() throws Exception {
        String source = """
                package demo;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.cloud.client.discovery.DiscoveryClient;
                import org.springframework.cloud.client.loadbalancer.EnableDiscoveryClient;
                import org.springframework.cloud.netflix.zuul.EnableZuulProxy;
                import org.springframework.security.oauth2.client.OAuth2RestTemplate;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.kafka.core.KafkaTemplate;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.PathVariable;

                @FeignClient(name = "account-service", path = "/accounts")
                interface AccountClient {
                    @GetMapping("/{name}")
                    String getAccount(@PathVariable("name") String name);
                }

                @EnableZuulProxy
                @EnableDiscoveryClient
                class GatewayWorker {
                    private final String base = "http://statistics-service/stats";
                    OAuth2RestTemplate oauth2;
                    DiscoveryClient discovery;
                    RabbitTemplate rabbit;
                    KafkaTemplate<String, String> kafka;

                    void invoke(String id) {
                        oauth2.postForObject(String.format("%s/%s", base, id), null, String.class);
                        discovery.getInstances("notification-service");
                        rabbit.convertAndSend("email.notifications", "payload");
                        kafka.send("orders.created", "payload");
                    }

                    @RabbitListener(queues = "email.notifications")
                    void onMail(String body) {}

                    @KafkaListener(topics = {"orders.created", "audit.log"})
                    void onOrder(String body) {}
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://account-service/accounts/{name}\"")
                && "GET".equals(f.attributes().get("method"))
                && "account-service".equals(f.attributes().get("targetService"))), () -> "Missing Feign call: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://statistics-service/stats/{param}\"")
                && "POST".equals(f.attributes().get("method"))
                && "statistics-service".equals(f.attributes().get("targetService"))), () -> "Missing OAuth2RestTemplate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"lb://notification-service/\"")
                && "notification-service".equals(f.attributes().get("targetService"))));
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("SPRING_CLOUD_ROLE") && f.name().equals("ZUUL_PROXY")));
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"email.notifications\"") && "RABBITMQ".equals(f.attributes().get("protocol"))));
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"email.notifications\"") && "RABBITMQ".equals(f.attributes().get("protocol"))));
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"orders.created\"") && "KAFKA".equals(f.attributes().get("protocol"))));
        assertEquals(2, facts.stream().filter(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && "KAFKA".equals(f.attributes().get("protocol"))).count());
    }

    @Test
    void unimportedCustomAnnotationsDoNotProduceSpringServiceOrEventFacts() throws Exception {
        String source = """
                package custom;
                @interface FeignClient { String name(); }
                @interface KafkaListener { String topics(); }
                @FeignClient(name = "fake")
                interface FakeClient { @KafkaListener(topics = "fake.topic") void call(); }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);
        assertTrue(facts.isEmpty(), () -> "Unimported annotations must not fabricate Spring facts: " + facts);
    }

    @Test
    void extractsJaxRsWebTargetAndClientBuilderChainsOnlyWhenImported() throws Exception {
        String jaxRsSource = """
                package demo;
                import javax.ws.rs.client.ClientBuilder;
                import javax.ws.rs.client.WebTarget;
                import javax.ws.rs.core.MediaType;

                class InventoryBridge {
                    private final WebTarget base = ClientBuilder.newClient().target("http://inventory-service:8080/rest");

                    String loadItem(String itemId) {
                        WebTarget items = base.path("items");
                        return items.path(itemId).request(MediaType.APPLICATION_JSON).get(String.class);
                    }

                    void createDirect() {
                        jakarta.ws.rs.client.ClientBuilder.newClient()
                                .target("http://orders-service:8080/api")
                                .path("orders")
                                .request()
                                .post(null);
                    }
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(jaxRsSource.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(jaxRsSource.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://inventory-service:8080/rest/items/{param}\"")
                && "GET".equals(f.attributes().get("method"))
                && "JAX_RS_CLIENT".equals(f.attributes().get("clientKind"))
                && "inventory-service".equals(f.attributes().get("targetService"))), () -> "Missing WebTarget call: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://orders-service:8080/api/orders\"")
                && "POST".equals(f.attributes().get("method"))
                && "orders-service".equals(f.attributes().get("targetService"))), () -> "Missing jakarta WebTarget call: " + facts);

        // Negative anti-false-positive check: unimported local WebTarget class must emit zero facts
        String fakeSource = """
                package fake;
                class ClientBuilder { static ClientBuilder newClient() { return new ClientBuilder(); } ClientBuilder target(String u) { return this; } ClientBuilder request() { return this; } void get() {} }
                class Caller { void call() { ClientBuilder.newClient().target("http://fake/x").request().get(); } }
                """;
        var fakeFacts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        new JavaServiceSyntaxExtractor().parse(fakeSource.getBytes(StandardCharsets.UTF_8), fakeFacts::add, () -> false);
        assertTrue(fakeFacts.isEmpty(), () -> "Unimported custom WebTarget/ClientBuilder must not emit HTTP facts: " + fakeFacts);
    }

    @Test
    void foldsDynamicUriPrefixConcatenationsAndNestedPlusChainsWithoutAffectingNonUriStrings() throws Exception {
        String source = """
                package demo;
                import org.springframework.cloud.client.discovery.DiscoveryClient;
                import org.springframework.web.client.RestTemplate;
                import org.springframework.kafka.core.KafkaTemplate;

                class ConcatClient {
                    private static final String BILLING_BASE = "http://billing-service";
                    RestTemplate rest;
                    DiscoveryClient discovery;
                    KafkaTemplate<String, String> kafka;

                    void invoke(String id, String tenant, String sku, String region) {
                        rest.getForObject("http://svc/items/" + id + "/details", String.class);
                        rest.getForObject("https://api.example.com/v1/" + tenant + "/orders/" + id, String.class);
                        rest.getForObject("lb://inventory-service/stock/" + sku, String.class);
                        rest.postForObject("/api/orders/" + id + "/cancel", null, String.class);
                        rest.getForObject(BILLING_BASE + "/invoices/" + id + "/pdf", String.class);
                        discovery.getInstances("orders-" + region);
                        kafka.send("orders." + region, "payload");
                    }
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://svc/items/{param}/details\"")
                && "GET".equals(f.attributes().get("method"))
                && "svc".equals(f.attributes().get("targetService"))), () -> "Missing nested http + chain: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"https://api.example.com/v1/{param}/orders/{param}\"")
                && "api.example.com".equals(f.attributes().get("targetService"))), () -> "Missing https + chain: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"lb://inventory-service/stock/{param}\"")
                && "inventory-service".equals(f.attributes().get("targetService"))), () -> "Missing lb + chain: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"/api/orders/{param}/cancel\"")
                && "POST".equals(f.attributes().get("method"))), () -> "Missing relative path + chain: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://billing-service/invoices/{param}/pdf\"")
                && "billing-service".equals(f.attributes().get("targetService"))), () -> "Missing constant-base + chain: " + facts);
        assertTrue(facts.stream().noneMatch(f -> "SERVICE_DISCOVERY".equals(f.attributes().get("clientKind"))),
                () -> "Non-URI dynamic concatenation must not fold to {param}: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && "KAFKA".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))
                && !f.name().contains("{param}")),
                () -> "Dynamic Kafka topic should fall back to symbolicTopic without folding {param}: " + facts);
    }

    @Test
    void extractsRestOperationsOAuth2RestOperationsControlPlaneRolesAndJavaMailSender() throws Exception {
        String source = """
                package demo;
                import de.codecentric.boot.admin.server.config.EnableAdminServer;
                import org.springframework.boot.autoconfigure.security.oauth2.client.EnableOAuth2Sso;
                import org.springframework.cloud.netflix.hystrix.dashboard.EnableHystrixDashboard;
                import org.springframework.cloud.netflix.turbine.EnableTurbine;
                import org.springframework.cloud.security.oauth2.resource.EnableOAuth2Resource;
                import org.springframework.mail.javamail.JavaMailSender;
                import org.springframework.security.oauth2.client.OAuth2RestOperations;
                import org.springframework.security.oauth2.config.annotation.web.configuration.EnableAuthorizationServer;
                import org.springframework.security.oauth2.config.annotation.web.configuration.EnableResourceServer;
                import org.springframework.web.client.RestOperations;
                import zipkin.server.EnableZipkinServer;

                @EnableAdminServer
                @EnableHystrixDashboard
                @EnableTurbine
                @EnableZipkinServer
                @EnableResourceServer
                @EnableAuthorizationServer
                @EnableOAuth2Sso
                @EnableOAuth2Resource
                class PlatformServices {
                    private final RestOperations restOps;
                    private final JavaMailSender mailSender;

                    PlatformServices(RestOperations restOps, JavaMailSender mailSender) {
                        this.restOps = restOps;
                        this.mailSender = mailSender;
                    }

                    void call(OAuth2RestOperations oauthOps, String id) {
                        restOps.getForObject("http://catalog-service/items/" + id, String.class);
                        oauthOps.postForObject("http://account-service/users/" + id, null, String.class);
                    }
                }

                class ParamOnlyMailService {
                    void notifyUser(JavaMailSender sender) {}
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        for (String role : java.util.List.of("ADMIN_SERVER", "HYSTRIX_DASHBOARD", "TURBINE", "ZIPKIN_SERVER",
                "OAUTH2_RESOURCE", "OAUTH2_AUTH_SERVER", "OAUTH2_SSO")) {
            assertTrue(facts.stream().anyMatch(f -> f.category().equals("SPRING_CLOUD_ROLE") && f.name().equals(role)),
                    () -> "Missing role " + role + ": " + facts);
        }
        assertEquals(2, facts.stream().filter(f -> f.category().equals("SPRING_CLOUD_ROLE")
                && f.name().equals("OAUTH2_RESOURCE")).count());
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://catalog-service/items/{param}\"")
                && "catalog-service".equals(f.attributes().get("targetService"))), () -> "Missing RestOperations call: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://account-service/users/{param}\"")
                && "account-service".equals(f.attributes().get("targetService"))), () -> "Missing OAuth2RestOperations call: " + facts);
        assertEquals(2, facts.stream().filter(f -> f.category().equals("INFRA_CONFIG_REF")
                && f.nodeKind().equals("mail-sender")
                && f.name().equals("smtp")
                && "smtp".equals(f.attributes().get("role"))
                && "smtp".equals(f.attributes().get("target"))
                && "mail-sender".equals(f.attributes().get("kind"))).count(),
                () -> "Expected 2 mail-sender INFRA_CONFIG_REF facts: " + facts);

        // Individual marker gating checks for newly added markers
        for (String marker : java.util.List.of("RestOperations", "OAuth2RestOperations", "JavaMailSender",
                "EnableAdminServer", "EnableHystrixDashboard", "EnableTurbine", "EnableZipkinServer",
                "EnableResourceServer", "EnableAuthorizationServer", "EnableOAuth2Sso", "EnableOAuth2Resource")) {
            assertTrue(JavaServiceSyntaxExtractor.candidate(("class X { /* " + marker + " */ }").getBytes(StandardCharsets.UTF_8)),
                    () -> "Marker should trigger candidate(): " + marker);
        }

        // Negative anti-FP check: unimported local types/annotations must not fabricate facts
        String fakeSource = """
                package fake;
                @interface EnableAdminServer {}
                @interface EnableZipkinServer {}
                @interface EnableResourceServer {}
                class JavaMailSender {}
                interface RestOperations { String getForObject(String u, Class<?> c); }
                @EnableAdminServer @EnableZipkinServer @EnableResourceServer
                class Fake {
                    JavaMailSender mail;
                    RestOperations ops;
                    void run() { ops.getForObject("http://fake/x", String.class); }
                }
                """;
        var fakeFacts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        new JavaServiceSyntaxExtractor().parse(fakeSource.getBytes(StandardCharsets.UTF_8), fakeFacts::add, () -> false);
        assertTrue(fakeFacts.isEmpty(), () -> "Unimported custom roles/mail/RestOperations must emit zero facts: " + fakeFacts);
    }

    @Test
    void capturesSpringValuePlaceholderFieldsOnHttpClientCalls() throws Exception {
        String source = """
                package demo;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.security.oauth2.client.OAuth2RestOperations;
                import org.springframework.web.client.RestClient;
                import org.springframework.web.client.RestOperations;
                import org.springframework.web.client.RestTemplate;
                import org.springframework.web.reactive.function.client.WebClient;

                class ConfiguredClients {
                    @Value("${orders.service.url}")
                    private String ordersUrl;

                    @Value("${catalog.service.url:http://catalog-service:8080}")
                    String catalogUrl;

                    @Value("${shipping.service.url}")
                    private String shippingUrl;

                    @Value("${billing.service.url:http://billing-service}")
                    private String billingUrl;

                    @Value("${app.api.version}")
                    private String apiVersion;

                    private final RestTemplate restTemplate;
                    private final RestOperations restOps;
                    private final OAuth2RestOperations oauthOps;
                    private final WebClient shippingClient;
                    private final RestClient billingClient;

                    ConfiguredClients(RestTemplate restTemplate, RestOperations restOps,
                            OAuth2RestOperations oauthOps, WebClient.Builder webBuilder,
                            RestClient.Builder restBuilder) {
                        this.restTemplate = restTemplate;
                        this.restOps = restOps;
                        this.oauthOps = oauthOps;
                        this.shippingClient = webBuilder.baseUrl(shippingUrl).build();
                        this.billingClient = restBuilder.baseUrl(billingUrl).build();
                    }

                    void invoke(String id) {
                        restTemplate.getForObject(ordersUrl, String.class);
                        restOps.postForObject(ordersUrl + "/items/" + id, null, String.class);
                        oauthOps.getForObject(catalogUrl + "/products/" + id, String.class);
                        shippingClient.get().uri("/shipments/" + id);
                        billingClient.post().uri("/charge");
                        WebClient.create(ordersUrl).get().uri(ordersUrl);
                        restTemplate.getForObject("http://fixed-service/v/" + apiVersion, String.class);
                    }
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"\"")
                && "GET".equals(f.attributes().get("method"))
                && "orders.service.url".equals(f.attributes().get("propertyKey"))),
                () -> "Missing direct @Value without default: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"\"")
                && "POST".equals(f.attributes().get("method"))
                && "orders.service.url".equals(f.attributes().get("propertyKey"))),
                () -> "Missing concatenated @Value without default: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://catalog-service:8080/products/{param}\"")
                && "catalog.service.url".equals(f.attributes().get("propertyKey"))
                && "catalog-service".equals(f.attributes().get("targetService"))),
                () -> "Missing @Value with default URI: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"/shipments/{param}\"")
                && "GET".equals(f.attributes().get("method"))
                && "shipping.service.url".equals(f.attributes().get("propertyKey"))),
                () -> "Missing WebClient baseUrl @Value without default: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://billing-service/charge\"")
                && "POST".equals(f.attributes().get("method"))
                && "billing.service.url".equals(f.attributes().get("propertyKey"))
                && "billing-service".equals(f.attributes().get("targetService"))),
                () -> "Missing RestClient baseUrl @Value with default: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"\"")
                && "REST_OR_WEB_CLIENT".equals(f.attributes().get("clientKind"))
                && "orders.service.url".equals(f.attributes().get("propertyKey"))),
                () -> "Missing WebClient direct @Value without default: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.name().equals("\"http://fixed-service/v/{param}\"")
                && "fixed-service".equals(f.attributes().get("targetService"))
                && !f.attributes().containsKey("propertyKey")),
                () -> "Path-segment @Value on concrete host URL must not attach propertyKey: " + facts);

        // Negative anti-FP check: unimported @Value must not attach propertyKey
        String unimportedValue = """
                package demo;
                import org.springframework.web.client.RestTemplate;
                @interface Value { String value(); }
                class CustomValueClient {
                    @Value("${custom.url}")
                    private String url;
                    RestTemplate rest;
                    void run() { rest.getForObject(url, String.class); }
                }
                """;
        var customFacts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        new JavaServiceSyntaxExtractor().parse(unimportedValue.getBytes(StandardCharsets.UTF_8), customFacts::add, () -> false);
        assertTrue(customFacts.isEmpty(), () -> "Unimported @Value must not produce propertyKey HTTP facts: " + customFacts);
    }

    @Test
    void extractsTurbineAmqpCircuitBreakerAndHystrixRolesWithImportVerification() throws Exception {
        String source = """
                package demo;
                import org.springframework.cloud.client.circuitbreaker.EnableCircuitBreaker;
                import org.springframework.cloud.netflix.hystrix.EnableHystrix;
                import org.springframework.cloud.netflix.turbine.amqp.EnableTurbineAmqp;

                @EnableTurbineAmqp
                @EnableCircuitBreaker
                @EnableHystrix
                class ResilienceApp {}
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("SPRING_CLOUD_ROLE")
                && f.name().equals("TURBINE_STREAM")
                && "EnableTurbineAmqp".equals(f.attributes().get("annotation"))),
                () -> "Missing TURBINE_STREAM from @EnableTurbineAmqp: " + facts);
        assertEquals(2, facts.stream().filter(f -> f.category().equals("SPRING_CLOUD_ROLE")
                && f.name().equals("CIRCUIT_BREAKER")).count(),
                () -> "Expected 2 CIRCUIT_BREAKER facts from @EnableCircuitBreaker/@EnableHystrix: " + facts);

        for (String marker : java.util.List.of("EnableTurbineAmqp", "EnableCircuitBreaker", "EnableHystrix")) {
            assertTrue(JavaServiceSyntaxExtractor.candidate(("class X { /* " + marker + " */ }").getBytes(StandardCharsets.UTF_8)),
                    () -> "Marker should trigger candidate(): " + marker);
        }

        String fakeSource = """
                package fake;
                @interface EnableTurbineAmqp {}
                @interface EnableCircuitBreaker {}
                @interface EnableHystrix {}
                @EnableTurbineAmqp @EnableCircuitBreaker @EnableHystrix
                class FakeResilience {}
                """;
        var fakeFacts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        new JavaServiceSyntaxExtractor().parse(fakeSource.getBytes(StandardCharsets.UTF_8), fakeFacts::add, () -> false);
        assertTrue(fakeFacts.isEmpty(), () -> "Unimported circuit-breaker/turbine annotations must emit zero facts: " + fakeFacts);
    }

    @Test
    void extractsFeignClientPlaceholderUrlPropertyKeyAndDefaultUrl() throws Exception {
        String source = """
                package demo;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;

                @FeignClient(url = "${rates.url}")
                interface ExchangeRatesClient {
                    @GetMapping("/latest")
                    String latestRates();
                }

                @FeignClient(name = "quotes-client", url = "${quotes.api.url}")
                interface QuotesClient {
                    @RequestMapping(method = RequestMethod.GET, value = "/v1/quotes")
                    String getQuotes();
                }

                @FeignClient(url = "${external.prices.url:https://api.example.com}")
                interface DefaultUrlClient {
                    @GetMapping("/prices")
                    String getPrices();
                }

                @FeignClient(url = "${marker.only.url}")
                interface InterfaceOnlyFeignClient {
                    String unannotatedMethod();
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.nodeKind().equals("feign_method")
                && f.name().equals("\"/latest\"")
                && "GET".equals(f.attributes().get("method"))
                && "rates.url".equals(f.attributes().get("propertyKey"))
                && "${rates.url}".equals(f.attributes().get("urlExpr"))),
                () -> "Missing @FeignClient(url=\"${rates.url}\") method candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.nodeKind().equals("feign_method")
                && "GET".equals(f.attributes().get("method"))
                && "quotes.api.url".equals(f.attributes().get("propertyKey"))
                && "quotes-client".equals(f.attributes().get("targetService"))),
                () -> "Missing named @FeignClient placeholder URL candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.nodeKind().equals("feign_method")
                && f.name().equals("\"https://api.example.com/prices\"")
                && "external.prices.url".equals(f.attributes().get("propertyKey"))
                && "api.example.com".equals(f.attributes().get("targetService"))),
                () -> "Missing @FeignClient placeholder URL with default: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && f.nodeKind().equals("feign_interface")
                && f.name().equals("\"\"")
                && "UNKNOWN".equals(f.attributes().get("method"))
                && "marker.only.url".equals(f.attributes().get("propertyKey"))),
                () -> "Missing interface-only @FeignClient placeholder URL candidate: " + facts);
    }

    @Test
    void emitsSymbolicTopicFallbackForUnresolvedMessagingAnnotationsAndTemplateCalls() throws Exception {
        String longExpr = "Constants." + "VERY_LONG_QUEUE_IDENTIFIER_".repeat(6) + "NAME";
        String source = """
                package demo;
                import org.springframework.amqp.rabbit.annotation.Queue;
                import org.springframework.amqp.rabbit.annotation.RabbitListener;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.cloud.stream.annotation.StreamListener;
                import org.springframework.kafka.annotation.KafkaListener;
                import org.springframework.kafka.core.KafkaTemplate;

                class SymbolicMessagingWorker {
                    RabbitTemplate rabbit;
                    KafkaTemplate<String, String> kafka;

                    @RabbitListener(queues = SomeBean.queueName)
                    void onRabbitSymbolic(String msg) {}

                    @RabbitListener(queuesToDeclare = @Queue(name = SomeBean.declaredQueue))
                    void onRabbitDeclaredSymbolic(String msg) {}

                    @KafkaListener(topics = {ExternalTopics.ORDERS, "literal.audit"})
                    void onKafkaMixed(String msg) {}

                    @StreamListener(Sink.INPUT)
                    void onStreamSymbolic(String msg) {}

                    @RabbitListener(queues = "")
                    void onBlankQueue(String msg) {}

                    void publish(Object payload) {
                        rabbit.convertAndSend(SomeBean.exchangeName, SomeBean.routingKey, payload);
                        rabbit.convertAndSend("", SomeBean.directQueue, payload);
                        kafka.send(ExternalTopics.EVENTS, payload);
                        kafka.send(null, payload);
                        kafka.send(%s, payload);
                    }
                }
                """.formatted(longExpr);
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"SomeBean.queueName\"")
                && "RABBITMQ".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing symbolic @RabbitListener candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"SomeBean.declaredQueue\"")
                && "RABBITMQ".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing symbolic @RabbitListener(queuesToDeclare=@Queue) candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"ExternalTopics.ORDERS\"")
                && "KAFKA".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing symbolic @KafkaListener candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"literal.audit\"")
                && "KAFKA".equals(f.attributes().get("protocol"))
                && !f.attributes().containsKey("symbolicTopic")),
                () -> "Literal @KafkaListener topic must not be flagged symbolicTopic: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_CONSUMER_CANDIDATE")
                && f.name().equals("\"Sink.INPUT\"")
                && "SPRING_CLOUD_STREAM".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing symbolic @StreamListener candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"SomeBean.exchangeName\"")
                && "RABBITMQ".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing symbolic RabbitTemplate.convertAndSend candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"SomeBean.directQueue\"")
                && "RABBITMQ".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing default-exchange symbolic RabbitTemplate.convertAndSend candidate: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && f.name().equals("\"ExternalTopics.EVENTS\"")
                && "KAFKA".equals(f.attributes().get("protocol"))
                && "true".equals(f.attributes().get("symbolicTopic"))),
                () -> "Missing symbolic KafkaTemplate.send candidate: " + facts);
        assertTrue(facts.stream().noneMatch(f -> f.name().contains("VERY_LONG_QUEUE_IDENTIFIER") || f.name().equals("\"null\"") || f.name().equals("\"\"")),
                () -> "Blank, null, and >128-char symbolic expressions must be excluded: " + facts);
    }

    @Test
    void extractsAmqpBeanQueuesExchangesAndBindingsViaDiParamMappingAndConstructor() throws Exception {
        String source = """
                package demo;
                import org.springframework.amqp.core.Binding;
                import org.springframework.amqp.core.BindingBuilder;
                import org.springframework.amqp.core.DirectExchange;
                import org.springframework.amqp.core.FanoutExchange;
                import org.springframework.amqp.core.Queue;
                import org.springframework.amqp.core.QueueBuilder;
                import org.springframework.amqp.core.TopicExchange;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                class AmqpTopologyConfig {
                    private static final String TOPIC_KEY = "order.*.created";

                    @Bean("ordersCreatedQueue")
                    Queue ordersCreatedQueue() {
                        return QueueBuilder.durable("orders.created.q").build();
                    }

                    @Bean
                    Queue billingQueue() {
                        return new Queue("billing.paid.q", true);
                    }

                    @Bean
                    Queue fanoutQueue() {
                        return new Queue("notify.all.q");
                    }

                    @Bean
                    TopicExchange ordersExchange() {
                        return new TopicExchange("orders.topic.ex");
                    }

                    @Bean
                    DirectExchange billingExchange() {
                        return new DirectExchange("billing.direct.ex");
                    }

                    @Bean
                    FanoutExchange notifyExchange() {
                        return new FanoutExchange("notify.fanout.ex");
                    }

                    @Bean
                    Binding topicBinding(@Qualifier("ordersCreatedQueue") Queue q, TopicExchange ex) {
                        return BindingBuilder.bind(q).to(ex).with(TOPIC_KEY);
                    }

                    @Bean
                    Binding directCtorBinding(Queue billingQueue, DirectExchange billingExchange) {
                        return new Binding(billingQueue.getName(), Binding.DestinationType.QUEUE,
                                billingExchange.getName(), "invoice.paid", null);
                    }

                    @Bean
                    Binding fanoutBinding() {
                        return BindingBuilder.bind(fanoutQueue()).to(notifyExchange());
                    }

                    @Bean
                    Binding dynamicBinding(Queue billingQueue, TopicExchange ordersExchange, String runtimeKey) {
                        return BindingBuilder.bind(billingQueue).to(ordersExchange).with(runtimeKey);
                    }

                    void publish(RabbitTemplate rabbit, String dynKey, Object payload) {
                        rabbit.convertAndSend("orders.topic.ex", "order.us.created", payload);
                        rabbit.convertAndSend("orders.topic.ex", dynKey, payload);
                    }
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("AMQP_BINDING")
                && "orders.topic.ex".equals(f.attributes().get("exchangeName"))
                && "order.*.created".equals(f.attributes().get("routingKeyPattern"))
                && "orders.created.q".equals(f.attributes().get("queueName"))
                && "TOPIC".equals(f.attributes().get("exchangeType"))),
                () -> "Missing fluent [DiInj-Param] topic binding: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("AMQP_BINDING")
                && "billing.direct.ex".equals(f.attributes().get("exchangeName"))
                && "invoice.paid".equals(f.attributes().get("routingKeyPattern"))
                && "billing.paid.q".equals(f.attributes().get("queueName"))
                && "DIRECT".equals(f.attributes().get("exchangeType"))),
                () -> "Missing new Binding(...) direct binding: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("AMQP_BINDING")
                && "notify.fanout.ex".equals(f.attributes().get("exchangeName"))
                && "notify.all.q".equals(f.attributes().get("queueName"))
                && "FANOUT".equals(f.attributes().get("exchangeType"))),
                () -> "Missing fanout sibling-bean binding: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("AMQP_BINDING")
                && "orders.topic.ex".equals(f.attributes().get("exchangeName"))
                && "true".equals(f.attributes().get("dynamicRoutingKey"))),
                () -> "Missing dynamic routing key flag on AMQP binding: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && "RABBITMQ".equals(f.attributes().get("protocol"))
                && "orders.topic.ex".equals(f.attributes().get("exchange"))
                && "order.us.created".equals(f.attributes().get("routingKey"))),
                () -> "Missing exchange+routingKey on RabbitTemplate.convertAndSend: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                && "RABBITMQ".equals(f.attributes().get("protocol"))
                && "orders.topic.ex".equals(f.attributes().get("exchange"))
                && "true".equals(f.attributes().get("dynamicRoutingKey"))),
                () -> "Missing dynamicRoutingKey on dynamic RabbitTemplate.convertAndSend: " + facts);

        // Negative anti-FP check: unimported local BindingBuilder/Queue must emit zero facts
        String fakeAmqp = """
                package fake;
                @interface Bean {}
                class Queue { Queue(String n) {} }
                class TopicExchange { TopicExchange(String n) {} }
                class Binding {}
                class BindingBuilder {
                    static BindingBuilder bind(Queue q) { return new BindingBuilder(); }
                    BindingBuilder to(TopicExchange e) { return this; }
                    Binding with(String k) { return new Binding(); }
                }
                class FakeAmqpConfig {
                    @Bean Queue q() { return new Queue("fake.q"); }
                    @Bean TopicExchange e() { return new TopicExchange("fake.ex"); }
                    @Bean Binding b(Queue q, TopicExchange e) { return BindingBuilder.bind(q).to(e).with("a.*"); }
                }
                """;
        var fakeFacts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        new JavaServiceSyntaxExtractor().parse(fakeAmqp.getBytes(StandardCharsets.UTF_8), fakeFacts::add, () -> false);
        assertTrue(fakeFacts.isEmpty(), () -> "Unimported custom AMQP types must not emit facts: " + fakeFacts);
    }

    @Test
    void extractsSpringCloudGatewayJavaFluentDslAndGatewayRouterFunctionsAndSpelFeign() throws Exception {
        String source = """
                package demo;
                import org.springframework.cloud.gateway.route.RouteLocator;
                import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
                import org.springframework.cloud.gateway.server.mvc.filter.FilterFunctions;
                import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
                import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
                import org.springframework.cloud.openfeign.FeignClient;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.bind.annotation.GetMapping;

                @Configuration
                class GatewayRoutesConfig {
                    @Bean
                    RouteLocator routes(RouteLocatorBuilder builder) {
                        return builder.routes()
                                .route("orders-route", r -> r.path("/api/orders/**")
                                        .filters(f -> f.stripPrefix(1))
                                        .uri("lb://order-service"))
                                .route("catalog-route", r -> r.path("/v1/catalog/**")
                                        .filters(f -> f.rewritePath("/v1/catalog/(?<seg>.*)", "/catalog/${seg}"))
                                        .uri("http://catalog-service:8080"))
                                .route("dynamic-spel", r -> r.path("/dyn/**")
                                        .uri("#{@routeResolver.uri}"))
                                .build();
                    }

                    @Bean
                    Object mvcRoute() {
                        return GatewayRouterFunctions.route("mvc-billing")
                                .GET("/api/billing/**", HandlerFunctions.http("http://billing-service:8081"))
                                .filter(FilterFunctions.stripPrefix(1))
                                .build();
                    }
                }

                @FeignClient(name = "${inventory.svc:inventory-default}", url = "${inventory.url:http://inventory-default:8080}")
                interface InventoryClient {
                    @GetMapping("/stock")
                    String stock();
                }

                @FeignClient(url = "#{@dynamicEndpoint.url}")
                interface SpelClient {
                    @GetMapping("/items")
                    String items();
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        assertTrue(facts.stream().anyMatch(f -> f.category().equals("GATEWAY_ROUTE")
                && f.nodeKind().equals("gateway_java_dsl")
                && "order-service".equals(f.attributes().get("targetService"))
                && "/api/orders/**".equals(f.attributes().get("pathPrefix"))
                && "1".equals(f.attributes().get("stripPrefix"))
                && "lb://order-service".equals(f.attributes().get("uri"))),
                () -> "Missing RouteLocatorBuilder stripPrefix route: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("GATEWAY_ROUTE")
                && f.nodeKind().equals("gateway_java_dsl")
                && "catalog-service".equals(f.attributes().get("targetService"))
                && "/v1/catalog/(?<seg>.*)".equals(f.attributes().get("rewriteFrom"))
                && "/catalog/${seg}".equals(f.attributes().get("rewriteTo"))),
                () -> "Missing RouteLocatorBuilder rewritePath route: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("GATEWAY_ROUTE")
                && "true".equals(f.attributes().get("spelExpression"))
                && "#{@routeResolver.uri}".equals(f.attributes().get("spelRaw"))),
                () -> "Missing SpEL Gateway route detection: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("GATEWAY_ROUTE")
                && f.nodeKind().equals("gateway_router_function")
                && "billing-service".equals(f.attributes().get("targetService"))
                && "/api/billing/**".equals(f.attributes().get("pathPrefix"))
                && "1".equals(f.attributes().get("stripPrefix"))),
                () -> "Missing GatewayRouterFunctions route: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && "inventory.url".equals(f.attributes().get("propertyKey"))
                && "inventory.svc".equals(f.attributes().get("servicePropertyKey"))
                && "inventory-default".equals(f.attributes().get("targetService"))),
                () -> "Missing multi-profile @FeignClient placeholder metadata: " + facts);
        assertTrue(facts.stream().anyMatch(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                && "true".equals(f.attributes().get("spelExpression"))
                && "#{@dynamicEndpoint.url}".equals(f.attributes().get("spelRaw"))),
                () -> "Missing SpEL @FeignClient detection: " + facts);
    }

    @Test
    void boundedKCallsiteWrapperFoldingWithEnumConstantsPreventsCartesianExplosion() throws Exception {
        String source = """
                package demo;
                import org.springframework.amqp.rabbit.core.RabbitTemplate;
                import org.springframework.web.client.RestTemplate;

                enum BackendTarget {
                    CATALOG("catalog-service"),
                    BILLING("billing-service");
                    private final String serviceHost;
                    BackendTarget(String serviceHost) { this.serviceHost = serviceHost; }
                    String getServiceHost() { return this.serviceHost; }
                }

                class WrapperClients {
                    private final RestTemplate rest;
                    private final RabbitTemplate rabbit;

                    WrapperClients(RestTemplate rest, RabbitTemplate rabbit) {
                        this.rest = rest;
                        this.rabbit = rabbit;
                    }

                    private String invokeHttp(BackendTarget target, String path) {
                        return rest.getForObject("http://" + target.getServiceHost() + path, String.class);
                    }

                    private String fetchViaFacade(BackendTarget target, String path) {
                        return invokeHttp(target, path);
                    }

                    private void publishEvent(String exchange, String key, Object msg) {
                        rabbit.convertAndSend(exchange, key, msg);
                    }

                    void orchestrate(String runtimeKey, Object msg) {
                        fetchViaFacade(BackendTarget.CATALOG, "/items");
                        fetchViaFacade(BackendTarget.BILLING, "/invoices");
                        publishEvent("ex.alpha", "k.alpha", msg);
                        publishEvent("ex.beta", "k.beta", msg);
                        publishEvent("ex.gamma", runtimeKey, msg);
                    }
                }
                """;
        var facts = new ArrayList<JavaGrpcSourceFrontend.Fact>();
        assertTrue(JavaServiceSyntaxExtractor.candidate(source.getBytes(StandardCharsets.UTF_8)));
        new JavaServiceSyntaxExtractor().parse(source.getBytes(StandardCharsets.UTF_8), facts::add, () -> false);

        var wrapperHttp = facts.stream()
                .filter(f -> f.category().equals("HTTP_CALL_CANDIDATE")
                        && "WRAPPER_FOLDED".equals(f.attributes().get("clientKind")))
                .toList();
        assertEquals(2, wrapperHttp.size(), () -> "Must fold exactly 2 callsite-scoped HTTP wrapper calls without Cartesian mix: " + wrapperHttp);
        assertTrue(wrapperHttp.stream().anyMatch(f -> f.name().equals("\"http://catalog-service/items\"")
                && "catalog-service".equals(f.attributes().get("targetService"))));
        assertTrue(wrapperHttp.stream().anyMatch(f -> f.name().equals("\"http://billing-service/invoices\"")
                && "billing-service".equals(f.attributes().get("targetService"))));
        // Verify ZERO Cartesian cross-product (catalog-service/invoices or billing-service/items)
        assertTrue(wrapperHttp.stream().noneMatch(f -> f.name().contains("catalog-service/invoices")
                || f.name().contains("billing-service/items")));

        var wrapperAmqp = facts.stream()
                .filter(f -> f.category().equals("EVENT_PRODUCER_CANDIDATE")
                        && "wrapper_call".equals(f.nodeKind())
                        && "RABBITMQ".equals(f.attributes().get("protocol")))
                .toList();
        assertEquals(3, wrapperAmqp.size(), () -> "Expected 2 concrete AMQP wrapper pairs + 1 dynamic unresolved: " + wrapperAmqp);
        assertTrue(wrapperAmqp.stream().anyMatch(f -> "ex.alpha".equals(f.attributes().get("exchange"))
                && "k.alpha".equals(f.attributes().get("routingKey"))));
        assertTrue(wrapperAmqp.stream().anyMatch(f -> "ex.beta".equals(f.attributes().get("exchange"))
                && "k.beta".equals(f.attributes().get("routingKey"))));
        // Strictly ZERO Cartesian mix between distinct callsites!
        assertTrue(wrapperAmqp.stream().noneMatch(f -> ("ex.alpha".equals(f.attributes().get("exchange"))
                        && "k.beta".equals(f.attributes().get("routingKey")))
                || ("ex.beta".equals(f.attributes().get("exchange"))
                        && "k.alpha".equals(f.attributes().get("routingKey")))));
        assertTrue(wrapperAmqp.stream().anyMatch(f -> "ex.gamma".equals(f.attributes().get("exchange"))
                && "true".equals(f.attributes().get("dynamicRoutingKey"))));
    }
}
