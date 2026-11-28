package com.evolution.backend.analysis;

import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeclarativeTopologyParsersTest {

    private static SourceCoordinates coords(String text) {
        return new SourceCoordinates(text.getBytes(StandardCharsets.UTF_8), "UTF-8");
    }

    @Test
    void dockerComposeParsesBuildDirContainerNameExternalLinksDockerSocketAndSkipsReservedSections() throws Exception {
        String compose = """
                version: '3.8'
                services:
                  api:
                    build: ./order-service
                    container_name: order-api
                    ports:
                      - "8080:8080"
                    external_links:
                      - legacy-db:db
                      - cache-redis
                  worker:
                    build:
                      context: ../worker-service
                      dockerfile: Dockerfile
                    links:
                      - broker:mq
                  inspector:
                    image: example/container-inspector:1.0
                    volumes:
                      - /var/run/docker.sock:/var/run/docker.sock:ro
                  networks:
                    internal:
                      driver: bridge
                  volumes:
                    pgdata: {}
                  x-common:
                    restart: always
                """;
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        long[] stats = {0L, 0L};
        StaticPolyglotScan.parseDockerCompose("deploy/docker-compose.yml", compose, coords(compose), facts, stats, () -> false);

        var decls = facts.stream().filter(f -> f.category().equals("DECLARATION")).toList();
        assertEquals(3, decls.size(), "Reserved networks/volumes/x-common under services must be ignored: " + decls);

        var api = decls.stream().filter(f -> f.name().equals("api")).findFirst().orElseThrow();
        assertEquals("deploy/order-service", api.attributes().get("buildDir"));
        assertEquals("order-api", api.attributes().get("containerName"));
        assertNull(api.attributes().get("dockerSocket"));

        var worker = decls.stream().filter(f -> f.name().equals("worker")).findFirst().orElseThrow();
        assertEquals("worker-service", worker.attributes().get("buildDir"));

        var inspector = decls.stream().filter(f -> f.name().equals("inspector")).findFirst().orElseThrow();
        assertEquals("true", inspector.attributes().get("dockerSocket"));

        var deps = facts.stream().filter(f -> f.category().equals("INFRA_DEPENDENCY"))
                .map(f -> f.attributes().get("target")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("legacy-db", "cache-redis", "broker"), deps);
    }

    @Test
    void dockerComposeV1WithoutServicesKeyParsesServicesAndBuildDir() throws Exception {
        String composeV1 = """
                config:
                  build: config
                  ports:
                    - 8888:8888
                gateway:
                  build: ./gateway
                  links:
                    - config
                  external_links:
                    - auth-service:auth
                """;
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        long[] stats = {0L, 0L};
        StaticPolyglotScan.parseDockerCompose("docker-compose.yml", composeV1, coords(composeV1), facts, stats, () -> false);

        var decls = facts.stream().filter(f -> f.category().equals("DECLARATION")).toList();
        assertEquals(2, decls.size());
        assertEquals("config", decls.stream().filter(f -> f.name().equals("config")).findFirst().orElseThrow().attributes().get("buildDir"));
        assertEquals("gateway", decls.stream().filter(f -> f.name().equals("gateway")).findFirst().orElseThrow().attributes().get("buildDir"));
        var deps = facts.stream().filter(f -> f.category().equals("INFRA_DEPENDENCY"))
                .map(f -> f.attributes().get("target")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("config", "auth-service"), deps);
    }

    @Test
    void kubernetesHelmTemplateGuardSkipsTemplatePathsAndNonDns1123Names() throws Exception {
        String helmDoc = """
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: {{ .Release.Name }}-orders
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          image: example/orders:1
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: INVALID_UPPER_NAME
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          image: example/bad:1
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: valid-billing.v1
                spec:
                  template:
                    spec:
                      containers:
                        - name: app
                          image: example/billing:1
                          ports:
                            - containerPort: 8080
                """;
        // Under /templates/ -> completely skipped
        var templateFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseKubernetesManifest("charts/shop/templates/deployment.yaml",
                helmDoc, coords(helmDoc), templateFacts, new long[2], () -> false);
        assertTrue(templateFacts.isEmpty(), "Files under /templates/ must emit zero K8s facts");

        // Outside /templates/ -> only valid DNS-1123 doc emitted; templated & invalid docs emit nothing (including no orphan image/port)
        var deployFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseKubernetesManifest("k8s/deployment.yaml",
                helmDoc, coords(helmDoc), deployFacts, new long[2], () -> false);
        var decls = deployFacts.stream().filter(f -> f.category().equals("DECLARATION")).toList();
        assertEquals(1, decls.size());
        assertEquals("valid-billing.v1", decls.getFirst().name());
        assertEquals(1, deployFacts.stream().filter(f -> f.category().equals("INFRA_BASE_IMAGE")).count());
        assertEquals(1, deployFacts.stream().filter(f -> f.category().equals("INFRA_PORT")).count());
    }

    @Test
    void kubernetesServiceExtractsSelectorTargetAndSkipsKustomizeDeletePatches() throws Exception {
        String k8sYaml = """
                apiVersion: v1
                kind: Service
                metadata:
                  name: storefront-public
                spec:
                  type: LoadBalancer
                  selector:
                    app: storefront
                  ports:
                    - port: 80
                      targetPort: 8080
                ---
                apiVersion: apps/v1
                kind: Deployment
                metadata:
                  name: synthetic-worker
                $patch: delete
                """;
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseKubernetesManifest("k8s/service.yaml",
                k8sYaml, coords(k8sYaml), facts, new long[2], () -> false);

        var decls = facts.stream().filter(f -> f.category().equals("DECLARATION")).toList();
        assertEquals(1, decls.size(), "Kustomize $patch: delete doc must emit zero facts: " + decls);
        var svc = decls.getFirst();
        assertEquals("k8s_service", svc.nodeKind());
        assertEquals("storefront-public", svc.name());
        assertEquals("storefront", svc.attributes().get("selectorTarget"));
        assertEquals("LoadBalancer", svc.attributes().get("serviceType"));
        var ports = facts.stream().filter(f -> f.category().equals("INFRA_PORT")).toList();
        assertEquals(1, ports.size());
        assertEquals("true", ports.getFirst().attributes().get("externalPort"));
    }

    @Test
    void prometheusAndGrafanaHandleIndentZeroSequenceItems() throws Exception {
        String prom = """
                global:
                  scrape_interval: 15s
                scrape_configs:
                - job_name: 'orders'
                  metrics_path: '/actuator/prometheus'
                  static_configs:
                  - targets: ['order-service:8080', 'billing-service:8081']
                - job_name: 'catalog'
                  static_configs:
                  - targets:
                    - 'catalog-service:8082'
                """;
        var promFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parsePrometheusConfig(prom, coords(prom), promFacts, new long[2], () -> false);
        assertEquals(3, promFacts.size(), "Indent-0 sequence items in Prometheus must not reset inScrape: " + promFacts);
        var targets = promFacts.stream().map(f -> f.attributes().get("target")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("order-service", "billing-service", "catalog-service"), targets);

        String grafana = """
                apiVersion: 1
                datasources:
                - name: Prometheus
                  type: prometheus
                  access: proxy
                  url: http://prometheus:9090
                - name: Elastic
                  type: elasticsearch
                  url: http://elasticsearch:9200
                """;
        var grafFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseGrafanaDatasources(grafana, coords(grafana), grafFacts, new long[2], () -> false);
        assertEquals(2, grafFacts.size(), "Indent-0 sequence items in Grafana must not reset inDs: " + grafFacts);
        var grafTargets = grafFacts.stream().map(f -> f.attributes().get("target")).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("prometheus", "elasticsearch"), grafTargets);
        assertEquals("9090", grafFacts.getFirst().attributes().get("port"));
    }

    @Test
    void springConfigPreservesLoopbackPortsExtractsStandardKeysAndNormalizesGitRepo() throws Exception {
        String yml = """
                spring:
                  application:
                    name: order-service
                  cloud:
                    config:
                      uri: http://localhost:8888
                      server:
                        git:
                          uri: https://github.com/example-org/config-repo.git
                    consul:
                      host: 127.0.0.1
                      port: 8500
                  boot:
                    admin:
                      client:
                        url:
                          - http://127.0.0.1:9090
                  datasource:
                    url: jdbc:mysql://localhost:3306/orders
                  data:
                    mongodb:
                      uri: mongodb://127.0.0.1:27017/orders
                  rabbitmq:
                    host: localhost
                    port: 5672
                  kafka:
                    bootstrap-servers: 127.0.0.1:9092
                  mail:
                    host: localhost
                    port: 1025
                management:
                  zipkin:
                    tracing:
                      endpoint: http://127.0.0.1:9411/api/v2/spans
                eureka:
                  client:
                    register-with-eureka: true
                    fetch-registry: false
                    serviceUrl:
                      defaultZone: http://127.0.0.1:8761/eureka/
                security:
                  oauth2:
                    resource:
                      user-info-uri: http://localhost:5000/uaa/users/current
                    client:
                      access-token-uri: http://auth-server:5000/uaa/oauth/token
                services:
                  inventory:
                    url: http://localhost:8085/api/inventory
                external:
                  rates:
                    uri: https://api.rates.example/v1/latest
                """;
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("order-service/src/main/resources/application.yml",
                yml, coords(yml), facts, new long[2], () -> false);

        var refs = facts.stream().filter(f -> f.category().equals("INFRA_CONFIG_REF")).toList();
        assertRef(refs, "config-client", "localhost", "8888");
        assertRef(refs, "config-git-repo", "github-repository", null);
        assertRef(refs, "consul", "localhost", "8500");
        assertRef(refs, "admin-client", "localhost", "9090");
        assertRef(refs, "datasource", "localhost", "3306");
        assertRef(refs, "mongodb", "localhost", "27017");
        assertRef(refs, "rabbitmq", "localhost", "5672");
        assertRef(refs, "kafka", "localhost", "9092");
        assertRef(refs, "smtp", "localhost", "1025");
        assertRef(refs, "zipkin", "localhost", "9411");
        assertRef(refs, "eureka-client", "localhost", "8761");
        assertRef(refs, "oauth2-auth", "localhost", "5000");
        assertRef(refs, "oauth2-auth", "auth-server", "5000");

        // Eureka flags & custom *.url/*.uri CONFIG_PROPERTY facts
        var props = facts.stream().filter(f -> f.category().equals("CONFIG_PROPERTY")).toList();
        assertTrue(props.stream().anyMatch(f -> "eureka.client.register-with-eureka".equals(f.attributes().get("key"))
                && "true".equals(f.attributes().get("value"))));
        assertTrue(props.stream().anyMatch(f -> "eureka.client.fetch-registry".equals(f.attributes().get("key"))
                && "false".equals(f.attributes().get("value"))));
        var invProp = props.stream().filter(f -> "services.inventory.url".equals(f.attributes().get("key")))
                .findFirst().orElseThrow();
        assertEquals("localhost", invProp.attributes().get("target"));
        assertEquals("8085", invProp.attributes().get("port"));
        assertEquals("http://localhost:8085/api/inventory", invProp.attributes().get("value"));
        var extProp = props.stream().filter(f -> "external.rates.uri".equals(f.attributes().get("key")))
                .findFirst().orElseThrow();
        assertEquals("api.rates.example", extProp.attributes().get("target"));

        // Git URI normalization variants
        assertEquals("github-repository", DeclarativeTopologyParsers.normalizeGitRepoTarget("git@github.com:org/repo.git"));
        assertEquals("gitlab-repository", DeclarativeTopologyParsers.normalizeGitRepoTarget("https://gitlab.example.com/org/cfg"));
        assertEquals("git-repository", DeclarativeTopologyParsers.normalizeGitRepoTarget("https://bitbucket.org/org/cfg"));
        assertEquals("git-repository", DeclarativeTopologyParsers.normalizeGitRepoTarget("file://${user.home}/config-repo"));
    }

    @Test
    void springConfigAttachesConfigRepoFileOnlyForConfigServerRepoDirsAndSkipsTestSources() throws Exception {
        String sharedApp = """
                spring:
                  rabbitmq:
                    host: rabbitmq
                eureka:
                  client:
                    serviceUrl:
                      defaultZone: http://registry:8761/eureka/
                """;
        var sharedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("config/src/main/resources/shared/application.yml",
                sharedApp, coords(sharedApp), sharedFacts, new long[2], () -> false);
        assertFalse(sharedFacts.isEmpty());
        assertTrue(sharedFacts.stream().allMatch(f -> "application".equals(f.attributes().get("configRepoFile"))));

        var svcFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("config/src/main/resources/shared/billing-service.yml",
                sharedApp, coords(sharedApp), svcFacts, new long[2], () -> false);
        assertTrue(svcFacts.stream().allMatch(f -> "billing-service".equals(f.attributes().get("configRepoFile"))));

        // Config server's own src/main/resources/application.yml must NOT get configRepoFile
        var ownCfgFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("config/src/main/resources/application.yml",
                sharedApp, coords(sharedApp), ownCfgFacts, new long[2], () -> false);
        assertFalse(ownCfgFacts.isEmpty());
        assertTrue(ownCfgFacts.stream().allMatch(f -> !f.attributes().containsKey("configRepoFile")));

        // src/test/resources/application.yml must be skipped completely
        var testFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("order-service/src/test/resources/application.yml",
                sharedApp, coords(sharedApp), testFacts, new long[2], () -> false);
        assertTrue(testFacts.isEmpty());
    }

    @Test
    void apacheAndNginxProxyParsersEmitProxyComponentAndRouteTargets() throws Exception {
        String apache = """
                Listen 80
                ProxyPass /orders http://order-service:8080/orders
                ProxyPassReverse /orders http://order-service:8080/orders
                ProxyPass /catalog http://localhost:8082/catalog
                ProxyPass /static !
                """;
        var apacheFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseApacheHttpdConfig(apache, coords(apache), apacheFacts, new long[2], () -> false);
        assertEquals(1, apacheFacts.stream().filter(f -> f.category().equals("DECLARATION")
                && f.nodeKind().equals("apache_proxy") && f.name().equals("apache")).count());
        var apacheRoutes = apacheFacts.stream().filter(f -> f.category().equals("GATEWAY_ROUTE")).toList();
        assertEquals(2, apacheRoutes.size(), "Duplicate ProxyPassReverse must deduplicate and '!' must be skipped");
        assertTrue(apacheRoutes.stream().anyMatch(f -> "order-service".equals(f.attributes().get("targetService"))
                && "8080".equals(f.attributes().get("port")) && "/orders".equals(f.attributes().get("pathPrefix"))));
        assertTrue(apacheRoutes.stream().anyMatch(f -> "localhost".equals(f.attributes().get("targetService"))
                && "8082".equals(f.attributes().get("port")) && "/catalog".equals(f.attributes().get("pathPrefix"))));

        String nginx = """
                upstream api_backend {
                    server orders:8080;
                    server catalog:8081;
                }
                server {
                    listen 80;
                    location /api/ {
                        proxy_pass http://api_backend/;
                    }
                    location /auth/ {
                        proxy_pass http://auth-service:9000;
                    }
                }
                """;
        var nginxFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseNginxConfig(nginx, coords(nginx), nginxFacts, new long[2], () -> false);
        assertEquals(1, nginxFacts.stream().filter(f -> f.category().equals("DECLARATION")
                && f.nodeKind().equals("nginx_proxy") && f.name().equals("nginx")).count());
        var nginxRoutes = nginxFacts.stream().filter(f -> f.category().equals("GATEWAY_ROUTE")).toList();
        assertEquals(3, nginxRoutes.size());
        var nginxTargets = nginxRoutes.stream().map(f -> f.attributes().get("targetService"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("orders", "catalog", "auth-service"), nginxTargets);
    }

    @Test
    void logbackXmlParsesDestinationAndRemoteHostIgnoringComments() throws Exception {
        String xml = """
                <configuration>
                  <!-- <destination>commented-out:5000</destination> -->
                  <appender name="LOGSTASH" class="net.logstash.logback.appender.LogstashTcpSocketAppender">
                    <destination>logstash:5000</destination>
                  </appender>
                  <appender name="SOCKET" class="net.logstash.logback.appender.LogstashSocketAppender">
                    <remoteHost>127.0.0.1</remoteHost>
                    <port>4560</port>
                  </appender>
                </configuration>
                """;
        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseLogbackXml(xml, coords(xml), facts, new long[2], () -> false);
        assertEquals(2, facts.size());
        assertRef(facts, "logstash", "logstash", "5000");
        assertRef(facts, "logstash", "localhost", "4560");
    }

    @Test
    void mavenAndGradleBuildDescriptorsEmitCanonicalStarterTokensAndSkipTestOrManagedDeps() throws Exception {
        String pom = """
                <project>
                  <dependencyManagement>
                    <dependencies>
                      <dependency>
                        <groupId>mysql</groupId>
                        <artifactId>mysql-connector-java</artifactId>
                        <version>8.0.33</version>
                      </dependency>
                    </dependencies>
                  </dependencyManagement>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-config</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-sleuth</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-zipkin</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.cloud</groupId>
                      <artifactId>spring-cloud-starter-bus-amqp</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-data-jpa</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>com.h2database</groupId>
                      <artifactId>h2</artifactId>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>net.logstash.logback</groupId>
                      <artifactId>logstash-logback-encoder</artifactId>
                    </dependency>
                    <dependency>
                      <groupId>de.codecentric</groupId>
                      <artifactId>spring-boot-admin-starter-client</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """;
        var pomFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorStarters("order-service/pom.xml",
                pom, coords(pom), pomFacts, new long[2], () -> false);
        var pomStarters = pomFacts.stream().map(f -> f.attributes().get("starter"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "eureka-client", "config-client", "sleuth", "zipkin", "bus-amqp",
                "data-jpa", "jdbc-postgres", "logstash-logback", "boot-admin-client"), pomStarters);
        assertFalse(pomStarters.contains("jdbc-mysql"), "dependencyManagement must not emit starter facts");
        assertFalse(pomStarters.contains("jdbc-h2"), "test-scoped h2 must not emit starter facts");

        String gradle = """
                buildscript {
                    dependencies {
                        classpath 'org.springframework.boot:spring-boot-starter-mail:2.7.0'
                    }
                }
                dependencies {
                    implementation 'org.springframework.cloud:spring-cloud-starter-gateway'
                    implementation("org.springframework.cloud:spring-cloud-starter-openfeign")
                    implementation 'org.springframework.boot:spring-boot-starter-data-mongodb'
                    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
                    implementation 'io.micrometer:micrometer-registry-prometheus'
                    implementation 'org.springframework.cloud:spring-cloud-starter-consul-discovery'
                    implementation 'org.springframework.boot:spring-boot-starter-oauth2-resource-server'
                    implementation 'org.springframework.kafka:spring-kafka'
                    runtimeOnly 'com.h2database:h2'
                    testImplementation 'mysql:mysql-connector-j'
                }
                """;
        var gradleFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorStarters("gateway/build.gradle.kts",
                gradle, coords(gradle), gradleFacts, new long[2], () -> false);
        var gradleStarters = gradleFacts.stream().map(f -> f.attributes().get("starter"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(
                "gateway", "openfeign", "data-mongodb", "data-redis",
                "prometheus", "consul", "oauth2-resource", "stream-kafka", "jdbc-h2"), gradleStarters);
        assertFalse(gradleStarters.contains("mail"), "buildscript classpath must be ignored");
        assertFalse(gradleStarters.contains("jdbc-mysql"), "testImplementation must be ignored");
    }

    @Test
    void filebeatYamlParsesLogstashElasticsearchAndKafkaOutputsRespectingEnabledFlag() throws Exception {
        String filebeat = """
                filebeat.prospectors:
                - input_type: log
                  paths:
                    - /var/log/app/*.log

                output.logstash:
                  enabled: true
                  hosts: ["elk:5044"]

                output.elasticsearch:
                  enabled: false
                  hosts: ["http://disabled-es:9200"]

                output.kafka:
                  hosts:
                    - kafka:9092
                """;
        assertTrue(DeclarativeTopologyParsers.looksLikeFilebeatYaml("orders/filebeat.yml", filebeat));
        assertFalse(DeclarativeTopologyParsers.looksLikeFilebeatYaml("orders/src/test/resources/filebeat.yml", filebeat));
        assertFalse(DeclarativeTopologyParsers.looksLikeFilebeatYaml("orders/application.yml", "spring:\n  application:\n    name: orders\n"));

        var facts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseFilebeatConfig(filebeat, coords(filebeat), facts, new long[2], () -> false);
        assertEquals(2, facts.size(), "Disabled elasticsearch output must be skipped: " + facts);
        assertRef(facts, "logstash", "logstash", "5044");
        assertRef(facts, "kafka", "kafka", "9092");
        assertTrue(facts.stream().allMatch(f -> "filebeat".equals(f.attributes().get("spec"))));

        String nestedFilebeat = """
                output:
                  elasticsearch:
                    hosts: ["http://elasticsearch:9200"]
                  logstash:
                    hosts: ["logstash-collector:5044"]
                """;
        assertTrue(DeclarativeTopologyParsers.looksLikeFilebeatYaml("logging/filebeat.yaml", nestedFilebeat));
        var nestedFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseFilebeatConfig(nestedFilebeat, coords(nestedFilebeat), nestedFacts, new long[2], () -> false);
        assertEquals(2, nestedFacts.size());
        assertRef(nestedFacts, "elasticsearch", "elasticsearch", "9200");
        assertRef(nestedFacts, "logstash", "logstash", "5044");
    }

    @Test
    void configServerResourceSubdirAndNativeProfileSuppressInactiveGitUri() throws Exception {
        // Subdirectories under <config-module>/src/main/resources/<subdir>/ return file stem
        assertEquals("mailer", DeclarativeTopologyParsers.configRepoFileStem(
                "config-server/src/main/resources/offline-repository/mailer.properties"));
        assertEquals("application", DeclarativeTopologyParsers.configRepoFileStem(
                "config-server/src/main/resources/config-data/application.yml"));
        assertEquals("application", DeclarativeTopologyParsers.configRepoFileStem(
                "config-server/src/main/resources/config-data/application-docker.properties"));
        assertEquals("", DeclarativeTopologyParsers.configRepoFileStem(
                "config-server/src/main/resources/application.yml"));
        assertEquals("", DeclarativeTopologyParsers.configRepoFileStem(
                "order-service/src/main/resources/offline-repository/order.properties"));

        String subdirProps = "spring.kafka.bootstrap-servers=kafka:9092\n";
        assertTrue(DeclarativeTopologyParsers.looksLikeSpringConfig(
                "config-server/src/main/resources/offline-repository/mailer.properties", subdirProps));
        var subdirFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig(
                "config-server/src/main/resources/offline-repository/mailer.properties",
                subdirProps, coords(subdirProps), subdirFacts, new long[2], () -> false);
        assertEquals(1, subdirFacts.size());
        assertEquals("mailer", subdirFacts.getFirst().attributes().get("configRepoFile"));
        assertEquals("kafka", subdirFacts.getFirst().attributes().get("role"));

        // Native profile + searchLocations suppresses inactive git.uri
        String nativeConfig = """
                spring:
                  profiles:
                    active: docker, native
                  cloud:
                    config:
                      server:
                        native:
                          searchLocations: classpath:/offline-repository
                        git:
                          uri: https://github.com/example-org/inactive-config-repo
                """;
        var nativeFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("config-server/src/main/resources/application.yml",
                nativeConfig, coords(nativeConfig), nativeFacts, new long[2], () -> false);
        assertTrue(nativeFacts.stream().noneMatch(f -> "config-git-repo".equals(f.attributes().get("role"))),
                "Inactive git.uri must be suppressed when native profile + searchLocations are active: " + nativeFacts);

        // Kebab-case search-locations also suppresses inactive git.uri
        String nativeKebabProps = """
                spring.profiles.active=native
                spring.cloud.config.server.native.search-locations=classpath:/repo
                spring.cloud.config.server.git.uri=https://github.com/example-org/inactive-repo
                """;
        var nativePropsFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("config-server/src/main/resources/application.properties",
                nativeKebabProps, coords(nativeKebabProps), nativePropsFacts, new long[2], () -> false);
        assertTrue(nativePropsFacts.stream().noneMatch(f -> "config-git-repo".equals(f.attributes().get("role"))));

        // Without native profile active, git.uri MUST still emit github-repository
        String gitActiveConfig = """
                spring:
                  profiles:
                    active: git
                  cloud:
                    config:
                      server:
                        native:
                          searchLocations: classpath:/offline-repository
                        git:
                          uri: https://github.com/example-org/active-config-repo
                """;
        var gitFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseSpringConfig("config-server/src/main/resources/application.yml",
                gitActiveConfig, coords(gitActiveConfig), gitFacts, new long[2], () -> false);
        assertRef(gitFacts, "config-git-repo", "github-repository", null);
    }

    @Test
    void starterDependencyMappingsCoverZipkinHystrixStreamAndDistinctTurbineStream() {
        assertEquals("zipkin", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.boot", "spring-boot-starter-zipkin"));
        assertEquals("zipkin", DeclarativeTopologyParsers.mapDependencyToStarter(
                "io.zipkin.reporter2", "zipkin-reporter-metrics-micrometer"));
        assertEquals("zipkin", DeclarativeTopologyParsers.mapDependencyToStarter(
                "io.micrometer", "micrometer-tracing-reporter-brave"));

        assertEquals("hystrix-stream", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-netflix-hystrix-stream"));
        assertEquals("hystrix-stream", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-netflix-hystrix-amqp"));

        assertEquals("turbine-stream", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-starter-turbine-stream"));
        assertEquals("turbine-stream", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-starter-netflix-turbine-stream"));
        assertEquals("turbine-stream", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-starter-turbine-amqp"));
        assertEquals("turbine-stream", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-netflix-turbine-amqp"));

        assertEquals("turbine", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-starter-turbine"));
        assertEquals("turbine", DeclarativeTopologyParsers.mapDependencyToStarter(
                "org.springframework.cloud", "spring-cloud-starter-netflix-turbine"));
    }

    @Test
    void buildDescriptorMetadataExtractsPackagingExecutableAndDotNetTestProjectFlags() throws Exception {
        String warPom = "<project><groupId>acme</groupId><artifactId>web</artifactId><packaging>war</packaging></project>";
        var warFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorMetadata("services/web/pom.xml", warPom, coords(warPom), warFacts, new long[2], () -> false);
        assertEquals(1, warFacts.size());
        assertEquals("war", warFacts.getFirst().attributes().get("value"));
        assertEquals("true", warFacts.getFirst().attributes().get("executable"));

        String libPom = "<project><groupId>acme</groupId><artifactId>common</artifactId></project>";
        var libFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorMetadata("utilities/common/pom.xml", libPom, coords(libPom), libFacts, new long[2], () -> false);
        assertEquals(1, libFacts.size());
        assertEquals("jar", libFacts.getFirst().attributes().get("value"));
        assertEquals("false", libFacts.getFirst().attributes().get("executable"));

        String bootPom = "<project><artifactId>api</artifactId><build><plugins><plugin><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build></project>";
        var bootFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorMetadata("services/api/pom.xml", bootPom, coords(bootPom), bootFacts, new long[2], () -> false);
        assertEquals("true", bootFacts.getFirst().attributes().get("executable"));

        String xunitCsproj = "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><PackageReference Include=\"xunit\" Version=\"2.7.0\"/></ItemGroup></Project>";
        var xunitFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorMetadata("svc/tests/Svc.Tests.csproj", xunitCsproj, coords(xunitCsproj), xunitFacts, new long[2], () -> false);
        assertEquals(1, xunitFacts.size());
        assertEquals("build.isTestProject", xunitFacts.getFirst().attributes().get("key"));
        assertEquals("true", xunitFacts.getFirst().attributes().get("value"));

        String nunitCsproj = "<Project Sdk=\"Microsoft.NET.Sdk\"><ItemGroup><PackageReference Include=\"NUnit\" Version=\"4.1.0\"/></ItemGroup></Project>";
        var nunitFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorMetadata("svc/spec/Svc.Spec.csproj", nunitCsproj, coords(nunitCsproj), nunitFacts, new long[2], () -> false);
        assertEquals(1, nunitFacts.size());
        assertEquals("true", nunitFacts.getFirst().attributes().get("value"));

        String normalCsproj = "<Project Sdk=\"Microsoft.NET.Sdk.Web\"></Project>";
        var normalFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        DeclarativeTopologyParsers.parseBuildDescriptorMetadata("svc/src/Svc.csproj", normalCsproj, coords(normalCsproj), normalFacts, new long[2], () -> false);
        assertTrue(normalFacts.isEmpty(), "Non-test .csproj must not inflate syntax parsed counts");
    }

    private static void assertRef(List<TreeSitterSourceFrontend.Fact> refs, String role, String target, String port) {
        boolean found = refs.stream().anyMatch(f ->
                role.equals(f.attributes().get("role"))
                        && target.equals(f.attributes().get("target"))
                        && (port == null ? !f.attributes().containsKey("port") : port.equals(f.attributes().get("port"))));
        assertTrue(found, () -> "Expected INFRA_CONFIG_REF(role=" + role + ", target=" + target + ", port=" + port
                + ") in " + refs);
    }
}
