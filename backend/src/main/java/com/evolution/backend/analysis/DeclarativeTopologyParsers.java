package com.evolution.backend.analysis;

import com.evolution.analysis.polyglot.HttpUriPattern;
import com.evolution.analysis.polyglot.SourceCoordinates;
import com.evolution.analysis.treesitter.TreeSitterSourceFrontend;
import java.io.IOException;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Passive, bounded parsers strictly for open industrial cloud-native schemas:
 * Docker Compose links/env, Kubernetes manifests (with Helm template guard),
 * Spring Boot/Cloud configuration YAML &amp; Properties (Gateway, Zuul, Eureka,
 * Config Server, Boot Admin, OAuth2, Consul, Datasource, Broker, Zipkin, SMTP,
 * Turbine, custom HTTP URL properties), Prometheus scrape_configs, Grafana
 * provisioning datasources, Apache HTTPD reverse proxy configs, Nginx reverse
 * proxy configs, Logback XML TCP/Socket appenders, and passive Maven/Gradle
 * Spring Boot/Cloud starter dependency descriptors.
 * <p>
 * No bespoke single-repo log DSLs (e.g. non-YAML custom formats) are parsed here.
 */
final class DeclarativeTopologyParsers {
    private static final Pattern COMPOSE_SERVICE_ENV_KEY = Pattern.compile(
            "(?i)^(?:.*(?:_HOST|_ADDR|_ADDRESS|_URL|_URI|_ENDPOINT|_SERVICE)"
                    + "|SPRING_DATASOURCE_URL|SPRING_RABBITMQ_HOST|SPRING_DATA_MONGODB_URI"
                    + "|SPRING_REDIS_HOST|SPRING_KAFKA_BOOTSTRAP_SERVERS|EUREKA_CLIENT_SERVICEURL_DEFAULTZONE)$");
    private static final Pattern K8S_DOC_BOUNDARY = Pattern.compile("(?m)^---\\s*$");
    private static final Pattern DNS_1123_NAME = Pattern.compile("^[a-z0-9]([-a-z0-9.]*[a-z0-9])?$");
    private static final Pattern PLACEHOLDER_DEFAULT = Pattern.compile("\\$\\{[^:}]+:([^}]+)}");
    private static final Pattern SSH_GIT_URI = Pattern.compile("^[^@\\s]+@([A-Za-z0-9._-]+):.*$");
    private static final Pattern PATH_PREDICATE = Pattern.compile("Path=(.+)");
    private static final Pattern STRIP_PREFIX = Pattern.compile("StripPrefix=(\\d+)");
    private static final Pattern REWRITE_PATH = Pattern.compile("RewritePath=([^,\\s]+)\\s*,\\s*([^\\s]+)");
    private static final Pattern NUMERIC_PORT = Pattern.compile("^\\d{1,5}$");

    private static final Pattern APACHE_PROXY_PASS = Pattern.compile(
            "(?i)^ProxyPass(?:Reverse)?\\s+(\\S+)\\s+(\\S+)");
    private static final Pattern APACHE_BALANCER_MEMBER = Pattern.compile(
            "(?i)^BalancerMember\\s+(\\S+)");
    private static final Pattern APACHE_PROXY_BLOCK = Pattern.compile(
            "(?i)^<Proxy\\s+[\"']?(balancer://[^\\s\"'>]+)[\"']?\\s*>");
    private static final Pattern APACHE_LISTEN = Pattern.compile(
            "(?i)^Listen\\s+(?:[^\\s:]+:)?(\\d{1,5})\\b");

    private static final Pattern NGINX_UPSTREAM_START = Pattern.compile(
            "(?i)^upstream\\s+([A-Za-z0-9_.-]+)\\s*\\{");
    private static final Pattern NGINX_UPSTREAM_SERVER = Pattern.compile(
            "(?i)\\bserver\\s+([^\\s;]+)");
    private static final Pattern NGINX_LOCATION_START = Pattern.compile(
            "(?i)^location\\s+(?:(?:=|~\\*|~|\\^~)\\s+)?(\\S+)\\s*\\{");
    private static final Pattern NGINX_PROXY_PASS = Pattern.compile(
            "(?i)\\bproxy_pass\\s+([^\\s;]+)\\s*;");
    private static final Pattern NGINX_LISTEN = Pattern.compile(
            "(?i)\\blisten\\s+(?:[^\\s:;]+:)?(\\d{1,5})\\b");

    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern LOGBACK_DESTINATION = Pattern.compile(
            "(?is)<destination\\b[^>]*>\\s*([^<]+?)\\s*</destination>");
    private static final Pattern LOGBACK_REMOTE_HOST = Pattern.compile(
            "(?is)<remoteHost\\b[^>]*>\\s*([^<]+?)\\s*</remoteHost>");
    private static final Pattern LOGBACK_PORT = Pattern.compile(
            "(?is)<port\\b[^>]*>\\s*([^<]+?)\\s*</port>");

    private static final Pattern POM_EXCLUDED_BLOCK = Pattern.compile(
            "(?is)<(dependencyManagement|pluginManagement|build|reporting|exclusions)\\b[^>]*>.*?</\\1>");
    private static final Pattern POM_DEPENDENCY_BLOCK = Pattern.compile(
            "(?is)<dependency\\b[^>]*>(.*?)</dependency>");
    private static final Pattern POM_ARTIFACT_ID = Pattern.compile(
            "(?is)<artifactId\\b[^>]*>\\s*([^<\\s]+)\\s*</artifactId>");
    private static final Pattern POM_GROUP_ID = Pattern.compile(
            "(?is)<groupId\\b[^>]*>\\s*([^<\\s]+)\\s*</groupId>");
    private static final Pattern POM_SCOPE = Pattern.compile(
            "(?is)<scope\\b[^>]*>\\s*([^<\\s]+)\\s*</scope>");

    private static final Pattern GRADLE_STRING_COORD = Pattern.compile(
            "['\"]([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+)(?::[^'\"]*)?['\"]");
    private static final Pattern GRADLE_MAP_GROUP = Pattern.compile(
            "\\bgroup\\s*[:=]\\s*['\"]([A-Za-z0-9_.-]+)['\"]");
    private static final Pattern GRADLE_MAP_NAME = Pattern.compile(
            "\\bname\\s*[:=]\\s*['\"]([A-Za-z0-9_.-]+)['\"]");
    private static final Pattern GRADLE_TEST_OR_EXCLUDE_LINE = Pattern.compile(
            "(?i)^(?:test|androidTest|integrationTest|functionalTest|jacocoTest)[A-Za-z0-9_]*\\b"
                    + "|^classpath\\b|^exclude\\b");

    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "0.0.0.0", "::1");

    private static final Set<String> SPRING_MANAGED_URL_PREFIXES = Set.of(
            "spring.cloud.", "spring.datasource.", "spring.r2dbc.", "spring.data.",
            "spring.zipkin.", "spring.boot.admin.", "spring.security.", "spring.config.",
            "eureka.", "zuul.", "management.", "security.oauth2.", "logging.", "server.");

    record HostPort(String target, String port) {}

    private DeclarativeTopologyParsers() {}

    static boolean isTestSourcePath(String path) {
        if (path == null || path.isEmpty()) return false;
        String norm = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return norm.contains("/src/test/")
                || norm.contains("/src/it/")
                || norm.contains("/src/integrationtest/")
                || norm.contains("/src/integration-test/");
    }

    static boolean isHelmTemplatePath(String path) {
        if (path == null || path.isEmpty()) return false;
        String norm = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return norm.contains("/templates/");
    }

    static boolean isValidDns1123Name(String name) {
        if (name == null || name.isEmpty() || name.length() > 253) return false;
        if (name.contains("{{") || name.contains("}}")) return false;
        return DNS_1123_NAME.matcher(name).matches();
    }

    private static final Set<String> NON_CONFIG_RESOURCE_SUBDIRS = Set.of(
            "meta-inf", "static", "public", "templates", "db", "migrations", "sql", "i18n");

    private static boolean isConfigModuleDir(String lowerDir) {
        int resIdx = lowerDir.indexOf("/src/main/resources");
        String modulePart = resIdx > 0 ? lowerDir.substring(0, resIdx) : lowerDir;
        return modulePart.equals("config")
                || modulePart.startsWith("config/")
                || modulePart.startsWith("config-")
                || modulePart.endsWith("/config")
                || modulePart.contains("/config/")
                || modulePart.contains("/config-")
                || modulePart.contains("-config")
                || modulePart.contains("configserver")
                || modulePart.contains("config_server");
    }

    static String configRepoFileStem(String path) {
        if (path == null || path.isEmpty()) return "";
        String norm = path.replace('\\', '/');
        while (norm.startsWith("/")) norm = norm.substring(1);
        int slash = norm.lastIndexOf('/');
        if (slash < 0) return "";
        String dir = norm.substring(0, slash);
        String base = norm.substring(slash + 1);
        String lowerDir = dir.toLowerCase(Locale.ROOT);
        String lowerBase = base.toLowerCase(Locale.ROOT);
        String stem = stripConfigExt(base);
        if (stem.isEmpty()) return "";
        String lowerStem = stem.toLowerCase(Locale.ROOT);
        boolean isSharedDir = lowerDir.equals("shared") || lowerDir.endsWith("/shared")
                || lowerDir.contains("/shared/");
        boolean isConfigRepoDir = lowerDir.equals("config-repo") || lowerDir.endsWith("/config-repo")
                || lowerDir.contains("/config-repo/");
        boolean isConfigLeafDir = lowerDir.equals("config") || lowerDir.endsWith("/config");
        boolean inResources = lowerDir.contains("/src/main/resources") || lowerDir.startsWith("src/main/resources");
        boolean inResourcesSubdir = false;
        int resSubIdx = lowerDir.indexOf("/src/main/resources/");
        String resSubTail = resSubIdx >= 0
                ? lowerDir.substring(resSubIdx + "/src/main/resources/".length())
                : (lowerDir.startsWith("src/main/resources/")
                        ? lowerDir.substring("src/main/resources/".length())
                        : "");
        if (!resSubTail.isEmpty()) {
            int subSlash = resSubTail.indexOf('/');
            String firstSubdir = subSlash >= 0 ? resSubTail.substring(0, subSlash) : resSubTail;
            inResourcesSubdir = !NON_CONFIG_RESOURCE_SUBDIRS.contains(firstSubdir);
        }
        boolean inConfigModule = isConfigModuleDir(lowerDir);
        boolean isDefaultAppOrBootstrap = lowerBase.startsWith("application") || lowerBase.startsWith("bootstrap");

        boolean match = isSharedDir || isConfigRepoDir
                || (inConfigModule && inResources && inResourcesSubdir)
                || (isConfigLeafDir && (!inResources || inConfigModule || !isDefaultAppOrBootstrap));
        if (!match) return "";
        if (lowerStem.equals("application") || lowerStem.startsWith("application-")) {
            return "application";
        }
        return stem;
    }

    private static String stripConfigExt(String base) {
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".properties")) return base.substring(0, base.length() - ".properties".length());
        if (lower.endsWith(".yaml")) return base.substring(0, base.length() - ".yaml".length());
        if (lower.endsWith(".yml")) return base.substring(0, base.length() - ".yml".length());
        return base;
    }

    static void parseDockerComposeEnvOrLinksLine(
            String section,
            String trimmed,
            int indent,
            int propIndent,
            int start,
            int contentEnd,
            String serviceSlot,
            SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out) {
        if (serviceSlot == null || serviceSlot.isEmpty() || trimmed.isEmpty()) return;
        if (("links".equals(section) || "external_links".equals(section)) && trimmed.startsWith("-")) {
            emitComposeLinkTarget(trimmed.substring(1).trim(), trimmed, start, contentEnd, serviceSlot, coords, out);
            return;
        }
        if ("environment".equals(section)) {
            String key = null;
            String val = null;
            if (trimmed.startsWith("-")) {
                String entry = unquote(trimmed.substring(1).trim());
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    key = unquote(entry.substring(0, eq).trim());
                    val = unquote(entry.substring(eq + 1).trim());
                }
            } else if (indent > propIndent) {
                int colon = trimmed.indexOf(':');
                if (colon > 0) {
                    key = unquote(trimmed.substring(0, colon).trim());
                    val = unquote(trimmed.substring(colon + 1).trim());
                }
            }
            if (key != null && val != null && !val.isEmpty() && COMPOSE_SERVICE_ENV_KEY.matcher(key).matches()) {
                Optional<String> targetOpt = extractHostTarget(val);
                if (targetOpt.isPresent()) {
                    String target = targetOpt.orElseThrow();
                    var span = coords.range(start, contentEnd, SourceCoordinates.Encoding.UTF16);
                    out.add(new TreeSitterSourceFrontend.Fact("INFRA_ENV_REF", "compose_env", target,
                            truncate(trimmed, 1024), span, serviceSlot, false,
                            Map.of("target", target, "envKey", key, "spec", "docker-compose")));
                }
            }
        }
    }

    static void emitComposeLinkTarget(
            String rawItem,
            String writtenLine,
            int start,
            int contentEnd,
            String serviceSlot,
            SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out) {
        String raw = unquote(rawItem.trim());
        int colon = raw.indexOf(':');
        String target = (colon > 0 ? raw.substring(0, colon) : raw).trim();
        if (!target.isEmpty() && target.length() <= 128) {
            var span = coords.range(start, Math.max(start + 1, contentEnd), SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_DEPENDENCY", "compose_link", target,
                    truncate(writtenLine, 1024), span, serviceSlot, false,
                    Map.of("target", target, "spec", "docker-compose")));
        }
    }

    static boolean looksLikeKubernetesYaml(String text) {
        return text.contains("apiVersion:") && text.contains("kind:")
                && (text.contains("Deployment") || text.contains("StatefulSet")
                        || text.contains("DaemonSet") || text.contains("Service"));
    }

    static void parseKubernetesManifest(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        parseKubernetesManifest("", text, coords, out, stats, cancelled);
    }

    static void parseKubernetesManifest(String path, String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        if (isHelmTemplatePath(path)) return;
        Matcher docMatcher = K8S_DOC_BOUNDARY.matcher(text);
        int pos = 0;
        while (pos <= text.length()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nextBoundary = docMatcher.find(pos) ? docMatcher.start() : text.length();
            String doc = text.substring(pos, nextBoundary);
            parseSingleK8sDoc(doc, pos, coords, out, stats);
            pos = docMatcher.hitEnd() ? text.length() + 1 : docMatcher.end();
        }
    }

    private static void parseSingleK8sDoc(String doc, int baseOffset, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats) {
        String kind = "";
        String metaName = "";
        boolean sawMetadataName = false;
        boolean invalidMetaName = false;
        boolean isDeletePatch = false;
        String serviceType = "";
        String selectorTarget = "";
        int metaIndent = -1;
        int metaChildIndent = -1;
        boolean inMetadata = false;
        boolean inSpec = false;
        boolean inSelector = false;
        int selectorIndent = -1;
        String currentEnvName = null;
        String ownerSlot = "";
        SourceCoordinates.Range declSpan = null;
        String declWritten = "";
        var docFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        List<int[]> pendingPortSpans = new ArrayList<>();
        List<String> pendingPortVals = new ArrayList<>();

        int len = doc.length();
        int offset = 0;
        while (offset < len) {
            int nl = doc.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && doc.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = doc.substring(offset, contentEnd);
            String cleaned = stripYamlComment(raw).trim();
            stats[0]++;
            if (!cleaned.isEmpty()) {
                int indent = leadingSpaces(raw);
                int start = baseOffset + offset + indent;
                int absEnd = baseOffset + contentEnd;
                String body = cleaned.startsWith("- ") ? cleaned.substring(2).trim() : cleaned;
                int colon = body.indexOf(':');
                if (colon > 0) {
                    String k = unquote(body.substring(0, colon).trim());
                    String v = unquote(body.substring(colon + 1).trim());
                    if (k.equals("$patch") && v.equalsIgnoreCase("delete")) {
                        isDeletePatch = true;
                        return;
                    }
                    if (inSelector && indent <= selectorIndent) {
                        inSelector = false;
                        selectorIndent = -1;
                    }
                    if (indent == 0 && k.equals("kind")) {
                        kind = v;
                        inMetadata = false;
                        inSpec = false;
                    } else if (indent == 0 && k.equals("metadata")) {
                        inMetadata = true;
                        inSpec = false;
                        metaIndent = indent;
                        metaChildIndent = -1;
                    } else if (indent == 0 && k.equals("spec")) {
                        inMetadata = false;
                        inSpec = true;
                    } else if (indent == 0) {
                        inMetadata = false;
                        inSpec = false;
                    } else if (inMetadata && indent > metaIndent) {
                        if (metaChildIndent < 0 || indent < metaChildIndent) {
                            metaChildIndent = indent;
                        }
                        if (indent == metaChildIndent && k.equals("name") && !sawMetadataName) {
                            sawMetadataName = true;
                            if (!isValidDns1123Name(v)) {
                                invalidMetaName = true;
                                return;
                            }
                            metaName = v;
                            if (Set.of("Deployment", "StatefulSet", "DaemonSet", "Service").contains(kind)) {
                                declSpan = coords.range(start, Math.max(start + 1, absEnd), SourceCoordinates.Encoding.UTF16);
                                ownerSlot = declSpan.start() + ":" + declSpan.end();
                                declWritten = truncate(cleaned, 1024);
                            }
                        }
                    } else if (!kind.isEmpty() && Set.of("Deployment", "StatefulSet", "DaemonSet", "Service").contains(kind)) {
                        if (kind.equals("Service") && inSpec && k.equals("selector") && v.isEmpty()) {
                            inSelector = true;
                            selectorIndent = indent;
                        } else if (inSelector && indent > selectorIndent) {
                            if ((k.equals("app") || k.equals("app.kubernetes.io/name") || k.equals("k8s-app")
                                    || (k.equals("name") && selectorTarget.isEmpty()))
                                    && isValidDns1123Name(v)) {
                                selectorTarget = v;
                            }
                        } else if (k.equals("type") && kind.equals("Service") && !v.isEmpty()) {
                            serviceType = v;
                        } else if (k.equals("image") && !v.isEmpty() && !v.contains("{{")) {
                            var span = coords.range(start, Math.max(start + 1, absEnd), SourceCoordinates.Encoding.UTF16);
                            docFacts.add(new TreeSitterSourceFrontend.Fact("INFRA_BASE_IMAGE", "k8s_image", v,
                                    truncate(cleaned, 1024), span, ownerSlot, false,
                                    Map.of("image", v, "spec", "kubernetes")));
                        } else if ((k.equals("containerPort") || k.equals("port")) && v.matches("\\d+")) {
                            pendingPortSpans.add(new int[]{start, Math.max(start + 1, absEnd)});
                            pendingPortVals.add(v);
                        } else if (k.equals("name") && cleaned.startsWith("-")) {
                            currentEnvName = v;
                        } else if (k.equals("name") && !cleaned.startsWith("-") && indent >= 8) {
                            currentEnvName = v;
                        } else if (k.equals("value") && currentEnvName != null) {
                            String envKey = currentEnvName;
                            currentEnvName = null;
                            if (COMPOSE_SERVICE_ENV_KEY.matcher(envKey).matches() && !v.isEmpty() && !v.contains("{{")) {
                                Optional<String> targetOpt = extractHostTarget(v);
                                if (targetOpt.isPresent()) {
                                    String target = targetOpt.orElseThrow();
                                    var span = coords.range(start, Math.max(start + 1, absEnd), SourceCoordinates.Encoding.UTF16);
                                    docFacts.add(new TreeSitterSourceFrontend.Fact("INFRA_ENV_REF", "k8s_env", target,
                                            truncate(envKey + "=" + v, 1024), span, ownerSlot, false,
                                            Map.of("target", target, "envKey", envKey, "spec", "kubernetes")));
                                }
                            }
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        if (isDeletePatch || invalidMetaName || metaName.isEmpty() || ownerSlot.isEmpty() || declSpan == null) {
            return;
        }
        var declAttrs = new TreeMap<String, String>();
        declAttrs.put("role", "service");
        declAttrs.put("kind", kind);
        declAttrs.put("spec", "kubernetes");
        if (!serviceType.isEmpty()) declAttrs.put("serviceType", serviceType);
        if (!selectorTarget.isEmpty()) declAttrs.put("selectorTarget", selectorTarget);
        out.add(new TreeSitterSourceFrontend.Fact("DECLARATION",
                "k8s_" + kind.toLowerCase(Locale.ROOT),
                metaName, declWritten, declSpan, "", false, declAttrs));
        out.addAll(docFacts);
        if (!pendingPortVals.isEmpty()) {
            boolean external = serviceType.equalsIgnoreCase("LoadBalancer") || serviceType.equalsIgnoreCase("NodePort");
            for (int i = 0; i < pendingPortVals.size(); i++) {
                int[] ps = pendingPortSpans.get(i);
                String pv = pendingPortVals.get(i);
                var span = coords.range(ps[0], ps[1], SourceCoordinates.Encoding.UTF16);
                out.add(new TreeSitterSourceFrontend.Fact("INFRA_PORT", "k8s_port", pv,
                        pv, span, ownerSlot, false,
                        Map.of("port", pv, "externalPort", Boolean.toString(external), "spec", "kubernetes")));
            }
        }
    }

    static boolean looksLikePrometheusYaml(String path, String text) {
        String lower = baseName(path).toLowerCase(Locale.ROOT);
        return (lower.contains("prometheus") || text.contains("scrape_configs:"))
                && text.contains("scrape_configs:") && text.contains("targets:");
    }

    static void parsePrometheusConfig(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        boolean inScrape = false;
        String currentJob = "";
        String currentMetrics = "/metrics";
        boolean inTargets = false;
        int offset = 0;
        int len = text.length();
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = text.substring(offset, contentEnd);
            String cleaned = stripYamlComment(raw).trim();
            stats[0]++;
            if (!cleaned.isEmpty()) {
                int indent = leadingSpaces(raw);
                int start = offset + indent;
                boolean isSeqItem = isYamlSeqItem(cleaned);
                if (indent == 0 && !isSeqItem) {
                    inScrape = cleaned.startsWith("scrape_configs:");
                    inTargets = false;
                } else if (inScrape) {
                    String body = isSeqItem ? cleaned.substring(1).trim() : cleaned;
                    if (body.startsWith("job_name:")) {
                        currentJob = unquote(body.substring("job_name:".length()).trim());
                        currentMetrics = "/metrics";
                        inTargets = false;
                    } else if (body.startsWith("metrics_path:")) {
                        currentMetrics = unquote(body.substring("metrics_path:".length()).trim());
                    } else if (body.startsWith("targets:")) {
                        inTargets = true;
                        String inline = body.substring("targets:".length()).trim();
                        if (inline.startsWith("[") && inline.endsWith("]")) {
                            emitPrometheusTargets(inline.substring(1, inline.length() - 1).split(","),
                                    currentJob, currentMetrics, start, contentEnd, coords, out);
                            inTargets = false;
                        }
                    } else if (inTargets && isSeqItem) {
                        emitPrometheusTargets(new String[]{body},
                                currentJob, currentMetrics, start, contentEnd, coords, out);
                    } else if (!isSeqItem) {
                        inTargets = false;
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
    }

    private static void emitPrometheusTargets(String[] rawTargets, String job, String metricsPath,
            int start, int contentEnd, SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        var span = coords.range(start, Math.max(start + 1, contentEnd), SourceCoordinates.Encoding.UTF16);
        for (String r : rawTargets) {
            String item = unquote(r.trim());
            if (item.isEmpty()) continue;
            int colon = item.indexOf(':');
            String host = (colon > 0 ? item.substring(0, colon) : item).trim().toLowerCase(Locale.ROOT);
            String port = colon > 0 ? item.substring(colon + 1).trim() : "";
            if (host.isEmpty() || LOOPBACK_HOSTS.contains(host)) continue;
            var attrs = new TreeMap<String, String>();
            attrs.put("target", host);
            attrs.put("job", job);
            attrs.put("metricsPath", metricsPath);
            if (!port.isEmpty()) attrs.put("port", port);
            attrs.put("spec", "prometheus");
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_SCRAPE_TARGET", "prometheus_target", host,
                    item, span, "", false, attrs));
        }
    }

    static boolean looksLikeGrafanaDatasources(String path, String text) {
        String lower = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        return (lower.contains("grafana") || lower.contains("datasource")) && text.contains("datasources:");
    }

    static void parseGrafanaDatasources(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        boolean inDs = false;
        String dsType = "";
        int offset = 0;
        int len = text.length();
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = text.substring(offset, contentEnd);
            String cleaned = stripYamlComment(raw).trim();
            stats[0]++;
            if (!cleaned.isEmpty()) {
                int indent = leadingSpaces(raw);
                int start = offset + indent;
                boolean isSeqItem = isYamlSeqItem(cleaned);
                String body = isSeqItem ? cleaned.substring(1).trim() : cleaned;
                if (indent == 0 && !isSeqItem) {
                    if (body.startsWith("datasources:")) {
                        inDs = true;
                        dsType = "";
                    } else if (!body.startsWith("apiVersion")) {
                        inDs = false;
                    }
                } else if (inDs) {
                    if (isSeqItem) {
                        dsType = "";
                    }
                    if (body.startsWith("type:")) {
                        dsType = unquote(body.substring("type:".length()).trim());
                    } else if (body.startsWith("url:")) {
                        String url = unquote(body.substring("url:".length()).trim());
                        String currentType = dsType;
                        extractHostAndPort(url, false).ifPresent(hp -> {
                            var span = coords.range(start, Math.max(start + 1, contentEnd), SourceCoordinates.Encoding.UTF16);
                            var attrs = new TreeMap<String, String>();
                            attrs.put("role", "grafana-datasource");
                            attrs.put("target", hp.target());
                            attrs.put("datasourceType", currentType);
                            if (!hp.port().isEmpty()) attrs.put("port", hp.port());
                            attrs.put("spec", "grafana");
                            out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "grafana_datasource", hp.target(),
                                    url, span, "", false, attrs));
                        });
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
    }

    static boolean looksLikeFilebeatYaml(String path, String text) {
        if (isTestSourcePath(path) || text == null || text.isBlank()) return false;
        String lower = baseName(path).toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) return false;
        boolean hasOutputSection = hasUncommentedKeyPrefix(text,
                "output.logstash", "output.elasticsearch", "output.kafka", "output:");
        if (lower.contains("filebeat")) {
            return hasOutputSection || hasUncommentedKeyPrefix(text, "filebeat.");
        }
        return hasUncommentedKeyPrefix(text, "filebeat.inputs", "filebeat.prospectors")
                && hasOutputSection;
    }

    private static boolean hasUncommentedKeyPrefix(String text, String... prefixes) {
        int len = text.length();
        int offset = 0;
        while (offset < len) {
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String cleaned = stripYamlComment(text.substring(offset, contentEnd)).trim();
            if (!cleaned.isEmpty()) {
                for (String prefix : prefixes) {
                    if (cleaned.startsWith(prefix)) return true;
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        return false;
    }

    static void parseFilebeatConfig(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        List<PropEntry> entries = flattenYamlProperties(text, stats, cancelled);
        if (entries.isEmpty()) return;
        var byRelaxed = new LinkedHashMap<String, List<PropEntry>>();
        for (PropEntry e : entries) {
            String baseRelaxed = stripIndexSuffix(e.relaxedKey());
            byRelaxed.computeIfAbsent(baseRelaxed, k -> new ArrayList<>()).add(e);
        }
        var seen = new HashSet<String>();
        emitFilebeatOutput(byRelaxed, "output.logstash", "logstash", "logstash", seen, coords, out);
        emitFilebeatOutput(byRelaxed, "output.elasticsearch", "elasticsearch", null, seen, coords, out);
        emitFilebeatOutput(byRelaxed, "output.kafka", "kafka", null, seen, coords, out);
    }

    private static void emitFilebeatOutput(
            Map<String, List<PropEntry>> byRelaxed,
            String sectionKey,
            String role,
            String fixedTarget,
            Set<String> seen,
            SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out) {
        PropEntry enabled = firstRelaxed(byRelaxed, sectionKey + ".enabled");
        if (enabled != null) {
            String enVal = resolvePlaceholderOrValue(enabled.value());
            if ("false".equalsIgnoreCase(enVal)) return;
        }
        List<PropEntry> hostEntries = allRelaxed(byRelaxed, sectionKey + ".hosts", sectionKey + ".host");
        for (PropEntry entry : hostEntries) {
            if (entry == null || entry.value().isBlank()) continue;
            String rawVal = stripInlineBrackets(entry.value());
            for (String part : rawVal.split(",")) {
                String cleanPart = unquote(part.trim());
                if (cleanPart.isEmpty()) continue;
                Optional<HostPort> hpOpt = extractHostAndPort(cleanPart, true);
                String target = null;
                String port = "";
                if (hpOpt.isPresent()) {
                    HostPort hp = hpOpt.orElseThrow();
                    target = fixedTarget != null ? fixedTarget : hp.target();
                    port = hp.port();
                } else if (fixedTarget != null && cleanPart.contains("${")) {
                    target = fixedTarget;
                    int colon = cleanPart.lastIndexOf(':');
                    if (colon > 0) {
                        String p = resolvePlaceholderOrValue(cleanPart.substring(colon + 1));
                        if (NUMERIC_PORT.matcher(p).matches()) port = p;
                    }
                }
                if (target == null || target.isEmpty()) continue;
                if (!seen.add(role + "|" + target + "|" + port)) continue;
                var span = coords.range(entry.start(), entry.end(), SourceCoordinates.Encoding.UTF16);
                var attrs = new TreeMap<String, String>();
                attrs.put("role", role);
                attrs.put("target", target);
                if (!port.isEmpty()) attrs.put("port", port);
                attrs.put("spec", "filebeat");
                out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "filebeat_output", target,
                        entry.key() + "=" + entry.value(), span, "", false, attrs));
            }
        }
    }

    static boolean looksLikeSpringConfig(String path, String text) {
        if (isTestSourcePath(path)) return false;
        String lower = baseName(path).toLowerCase(Locale.ROOT);
        String norm = "/" + path.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (lower.startsWith("application") || lower.startsWith("bootstrap")) return true;
        boolean inConfigRepo = norm.contains("/config/") || norm.contains("/shared/")
                || norm.contains("/config-repo/") || !configRepoFileStem(path).isEmpty();
        if (inConfigRepo
                && (text.contains("spring:") || text.contains("spring.") || text.contains("zuul:")
                        || text.contains("zuul.") || text.contains("eureka:") || text.contains("eureka.")
                        || text.contains("turbine:") || text.contains("turbine.")
                        || text.contains("server.port") || text.contains("server:") || text.contains("server.")
                        || text.contains("security:") || text.contains("security.")
                        || text.contains("management:") || text.contains("management.")
                        || text.contains("hystrix:") || text.contains("hystrix.")
                        || text.contains("feign:") || text.contains("feign.")
                        || text.contains("ribbon:") || text.contains("ribbon.")
                        || text.contains("datasource:") || text.contains("datasource.")
                        || text.contains("admin:") || text.contains("stream:"))) {
            return true;
        }
        return false;
    }

    private record PropEntry(String key, String relaxedKey, String value, int start, int end) {}

    static void parseSpringConfig(String path, String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        if (isTestSourcePath(path)) return;
        List<PropEntry> entries = path.toLowerCase(Locale.ROOT).endsWith(".properties")
                ? parseProperties(text, stats, cancelled)
                : flattenYamlProperties(text, stats, cancelled);
        if (entries.isEmpty()) return;

        String configRepoFile = configRepoFileStem(path);
        var kv = new LinkedHashMap<String, PropEntry>();
        var byRelaxed = new LinkedHashMap<String, List<PropEntry>>();
        for (PropEntry e : entries) {
            kv.put(e.key(), e);
            String baseRelaxed = stripIndexSuffix(e.relaxedKey());
            byRelaxed.computeIfAbsent(baseRelaxed, k -> new ArrayList<>()).add(e);
        }

        // 1. Service identity & server port
        PropEntry appName = firstRelaxed(byRelaxed, "spring.application.name");
        if (appName != null && !appName.value().isBlank()) {
            String svc = resolvePlaceholderOrValue(appName.value());
            if (!svc.isEmpty()) {
                var span = coords.range(appName.start(), appName.end(), SourceCoordinates.Encoding.UTF16);
                out.add(new TreeSitterSourceFrontend.Fact("CONFIG_SERVICE_IDENTITY", "spring_application_name", svc,
                        appName.key() + "=" + appName.value(), span, "", false,
                        withConfigRepo(Map.of("serviceName", svc, "spec", "spring-config"), configRepoFile)));
            }
        }
        PropEntry serverPort = firstRelaxed(byRelaxed, "server.port");
        if (serverPort != null) {
            String portVal = resolvePlaceholderOrValue(serverPort.value());
            if (NUMERIC_PORT.matcher(portVal).matches()) {
                var span = coords.range(serverPort.start(), serverPort.end(), SourceCoordinates.Encoding.UTF16);
                out.add(new TreeSitterSourceFrontend.Fact("INFRA_PORT", "spring_server_port", portVal,
                        serverPort.key() + "=" + serverPort.value(), span, "", false,
                        withConfigRepo(Map.of("port", portVal, "spec", "spring-config"), configRepoFile)));
            }
        }

        // 2. F3 Spring Cloud Gateway routes
        emitGatewayRoutes(entries, "spring.cloud.gateway.routes", configRepoFile, coords, out);
        emitGatewayRoutes(entries, "spring.cloud.gateway.mvc.routes", configRepoFile, coords, out);
        emitGatewayRoutes(entries, "spring.cloud.gateway.server.webflux.routes", configRepoFile, coords, out);

        // 3. F3 Netflix Zuul routes & ignored-services
        emitZuulRoutes(entries, configRepoFile, coords, out);
        PropEntry zuulIgnored = firstRelaxed(byRelaxed, "zuul.ignoredservices");
        if (zuulIgnored != null) {
            var span = coords.range(zuulIgnored.start(), zuulIgnored.end(), SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("CONFIG_PROPERTY", "zuul_ignored_services", zuulIgnored.value(),
                    zuulIgnored.key() + "=" + zuulIgnored.value(), span, "", false,
                    withConfigRepo(Map.of("key", "zuul.ignored-services", "value", zuulIgnored.value(),
                            "spec", "netflix-zuul"), configRepoFile)));
        }

        // 3b. Eureka client boolean flags (register-with-eureka, fetch-registry, enabled)
        emitFlagProperty(firstRelaxed(byRelaxed, "eureka.client.registerwitheureka"),
                "eureka.client.register-with-eureka", "eureka_client_flag", configRepoFile, coords, out);
        emitFlagProperty(firstRelaxed(byRelaxed, "eureka.client.fetchregistry"),
                "eureka.client.fetch-registry", "eureka_client_flag", configRepoFile, coords, out);
        emitFlagProperty(firstRelaxed(byRelaxed, "eureka.client.enabled"),
                "eureka.client.enabled", "eureka_client_flag", configRepoFile, coords, out);

        // 4. F4 Control-plane & Datastore refs (preserving localhost/127.0.0.1 + numeric port)
        var seenRefs = new HashSet<String>();
        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.cloud.config.uri"),
                "config-client", "", configRepoFile, seenRefs, coords, out);
        emitConfigRefsDirect(allRelaxed(byRelaxed, "spring.cloud.config.discovery.serviceid"),
                "config-client", "", configRepoFile, seenRefs, coords, out);
        for (PropEntry cfgImport : allRelaxed(byRelaxed, "spring.config.import")) {
            if (cfgImport.value().contains("configserver:")) {
                String raw = cfgImport.value().substring(
                        cfgImport.value().indexOf("configserver:") + "configserver:".length()).trim();
                extractHostAndPort(raw, true).ifPresent(hp -> {
                    String dedupKey = "config-client|" + hp.target() + "|" + hp.port();
                    if (!seenRefs.add(dedupKey)) return;
                    var span = coords.range(cfgImport.start(), cfgImport.end(), SourceCoordinates.Encoding.UTF16);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("role", "config-client");
                    attrs.put("target", hp.target());
                    if (!hp.port().isEmpty()) attrs.put("port", hp.port());
                    attrs.put("spec", "spring-cloud");
                    out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "spring_config_import", hp.target(),
                            cfgImport.value(), span, "", false, withConfigRepo(attrs, configRepoFile)));
                });
            }
        }

        // Config Server Git repository normalized to github-repository / gitlab-repository / git-repository
        // Suppress inactive git.uri when the same file explicitly activates the `native` profile with searchLocations.
        boolean activeNativeProfile = hasActiveProfileToken(allRelaxed(byRelaxed, "spring.profiles.active"), "native");
        boolean hasNativeSearchLocations = allRelaxed(byRelaxed,
                "spring.cloud.config.server.native.searchlocations").stream()
                .anyMatch(e -> e != null && !e.value().isBlank());
        if (!(activeNativeProfile && hasNativeSearchLocations)) {
            for (PropEntry gitUri : allRelaxed(byRelaxed, "spring.cloud.config.server.git.uri")) {
                if (!gitUri.value().isBlank()) {
                    String target = normalizeGitRepoTarget(gitUri.value());
                    String dedupKey = "config-git-repo|" + target + "|";
                    if (seenRefs.add(dedupKey)) {
                        var span = coords.range(gitUri.start(), gitUri.end(), SourceCoordinates.Encoding.UTF16);
                        out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "spring_config_git", target,
                                gitUri.value(), span, "", false,
                                withConfigRepo(Map.of("role", "config-git-repo", "target", target,
                                        "spec", "spring-cloud"), configRepoFile)));
                    }
                }
            }
        }

        emitConfigRefsIfHost(allRelaxed(byRelaxed, "eureka.client.serviceurl.defaultzone"),
                "eureka-client", "", configRepoFile, seenRefs, coords, out);

        // Datasource (JDBC & R2DBC, including localhost and embedded H2/HSQLDB/SQLite)
        for (PropEntry ds : allRelaxed(byRelaxed, "spring.datasource.url", "spring.r2dbc.url")) {
            emitDatasourceRef(ds, configRepoFile, seenRefs, coords, out);
        }

        String mongoPort = numericPropValue(firstRelaxed(byRelaxed, "spring.data.mongodb.port"));
        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.data.mongodb.uri"),
                "mongodb", "", configRepoFile, seenRefs, coords, out);
        emitConfigRefsDirect(allRelaxed(byRelaxed, "spring.data.mongodb.host"),
                "mongodb", mongoPort, configRepoFile, seenRefs, coords, out);

        String redisPort = numericPropValue(firstRelaxed(byRelaxed, "spring.data.redis.port", "spring.redis.port"));
        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.data.redis.url", "spring.redis.url"),
                "redis", "", configRepoFile, seenRefs, coords, out);
        emitConfigRefsDirect(allRelaxed(byRelaxed, "spring.data.redis.host", "spring.redis.host"),
                "redis", redisPort, configRepoFile, seenRefs, coords, out);

        String rabbitPort = numericPropValue(firstRelaxed(byRelaxed, "spring.rabbitmq.port"));
        emitConfigRefsDirect(allRelaxed(byRelaxed, "spring.rabbitmq.host"),
                "rabbitmq", rabbitPort, configRepoFile, seenRefs, coords, out);
        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.rabbitmq.addresses"),
                "rabbitmq", "", configRepoFile, seenRefs, coords, out);

        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.kafka.bootstrapservers",
                        "spring.cloud.stream.kafka.binder.brokers"),
                "kafka", "", configRepoFile, seenRefs, coords, out);

        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.zipkin.baseurl", "management.zipkin.tracing.endpoint"),
                "zipkin", "", configRepoFile, seenRefs, coords, out);

        String mailPort = numericPropValue(firstRelaxed(byRelaxed, "spring.mail.port"));
        emitConfigRefsDirect(allRelaxed(byRelaxed, "spring.mail.host"),
                "smtp", mailPort, configRepoFile, seenRefs, coords, out);

        // Spring Boot Admin client
        emitConfigRefsIfHost(allRelaxed(byRelaxed, "spring.boot.admin.url", "spring.boot.admin.client.url"),
                "admin-client", "", configRepoFile, seenRefs, coords, out);

        // Spring Cloud Consul
        String consulPort = numericPropValue(firstRelaxed(byRelaxed, "spring.cloud.consul.port"));
        emitConfigRefsDirect(allRelaxed(byRelaxed, "spring.cloud.consul.host"),
                "consul", consulPort, configRepoFile, seenRefs, coords, out);

        // Spring Security OAuth2 Resource Server / Client
        emitConfigRefsIfHost(allRelaxed(byRelaxed,
                        "security.oauth2.resource.userinfouri",
                        "security.oauth2.resource.tokeninfouri",
                        "security.oauth2.resource.jwt.keyuri",
                        "security.oauth2.resource.jwk.keyseturi",
                        "security.oauth2.client.accesstokenuri",
                        "security.oauth2.client.userauthorizationuri",
                        "spring.security.oauth2.resourceserver.jwt.issueruri",
                        "spring.security.oauth2.resourceserver.jwt.jwkseturi",
                        "spring.security.oauth2.resourceserver.opaquetoken.introspectionuri"),
                "oauth2-auth", "", configRepoFile, seenRefs, coords, out);

        // Turbine appConfig
        for (PropEntry turbine : allRelaxed(byRelaxed, "turbine.appconfig")) {
            if (!turbine.value().isBlank()) {
                var span = coords.range(turbine.start(), turbine.end(), SourceCoordinates.Encoding.UTF16);
                for (String app : stripInlineBrackets(turbine.value()).split(",")) {
                    String cleanApp = unquote(app.trim());
                    if (!cleanApp.isEmpty() && seenRefs.add("turbine-monitor|" + cleanApp + "|")) {
                        out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "turbine_app_config", cleanApp,
                                turbine.value(), span, "", false,
                                withConfigRepo(Map.of("role", "turbine-monitor", "target", cleanApp,
                                        "spec", "spring-cloud"), configRepoFile)));
                    }
                }
            }
        }

        // 4b. Custom *.url / *.uri HTTP properties -> CONFIG_PROPERTY(key, value, target, port)
        emitCustomHttpConfigProperties(entries, configRepoFile, coords, out);

        // 5. F5 Spring Cloud Stream bindings
        for (PropEntry e : entries) {
            if (e.key().startsWith("spring.cloud.stream.bindings.") && e.key().endsWith(".destination")) {
                String dest = resolvePlaceholderOrValue(e.value());
                if (dest.isEmpty()) continue;
                String channel = e.key().substring("spring.cloud.stream.bindings.".length(),
                        e.key().length() - ".destination".length());
                String lc = channel.toLowerCase(Locale.ROOT);
                boolean producer = lc.contains("out") || lc.contains("produc");
                var span = coords.range(e.start(), e.end(), SourceCoordinates.Encoding.UTF16);
                out.add(new TreeSitterSourceFrontend.Fact(
                        producer ? "EVENT_PRODUCER_CANDIDATE" : "EVENT_CONSUMER_CANDIDATE",
                        "spring_stream_binding",
                        "\"" + dest + "\"",
                        e.key() + "=" + dest,
                        span, "", false,
                        withConfigRepo(Map.of("role", producer ? "PRODUCER" : "CONSUMER",
                                "protocol", "SPRING_CLOUD_STREAM",
                                "channel", channel, "spec", "spring-cloud-stream"), configRepoFile)));
            }
        }
    }

    private static boolean hasActiveProfileToken(List<PropEntry> profileEntries, String wantedToken) {
        for (PropEntry entry : profileEntries) {
            if (entry == null || entry.value().isBlank()) continue;
            String resolved = resolvePlaceholderOrValue(entry.value());
            String raw = resolved.isEmpty() ? entry.value() : resolved;
            for (String part : stripInlineBrackets(raw).split("[,;\\s]+")) {
                String tok = unquote(part.trim());
                if (tok.equalsIgnoreCase(wantedToken)) return true;
            }
        }
        return false;
    }

    private static void emitFlagProperty(PropEntry entry, String canonicalKey, String nodeKind,
            String configRepoFile, SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        if (entry == null || entry.value().isBlank()) return;
        String val = resolvePlaceholderOrValue(entry.value());
        if (val.isEmpty()) return;
        var span = coords.range(entry.start(), entry.end(), SourceCoordinates.Encoding.UTF16);
        out.add(new TreeSitterSourceFrontend.Fact("CONFIG_PROPERTY", nodeKind, val,
                entry.key() + "=" + entry.value(), span, "", false,
                withConfigRepo(Map.of("key", canonicalKey, "value", val, "spec", "spring-cloud"), configRepoFile)));
    }

    private static void emitDatasourceRef(PropEntry entry, String configRepoFile, Set<String> seenRefs,
            SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        if (entry == null || entry.value().isBlank()) return;
        String val = resolvePlaceholderOrValue(entry.value()).trim();
        if (val.isEmpty()) return;
        String vendor = extractJdbcVendor(val);
        Optional<HostPort> hpOpt = extractHostAndPort(val, true);
        if (hpOpt.isPresent()) {
            HostPort hp = hpOpt.orElseThrow();
            if (!seenRefs.add("datasource|" + hp.target() + "|" + hp.port())) return;
            var span = coords.range(entry.start(), entry.end(), SourceCoordinates.Encoding.UTF16);
            var attrs = new TreeMap<String, String>();
            attrs.put("role", "datasource");
            attrs.put("target", hp.target());
            if (!hp.port().isEmpty()) attrs.put("port", hp.port());
            if (!vendor.isEmpty()) attrs.put("dbVendor", vendor);
            attrs.put("spec", "spring-config");
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "spring_config_ref", hp.target(),
                    entry.key() + "=" + entry.value(), span, "", false, withConfigRepo(attrs, configRepoFile)));
        } else if (!vendor.isEmpty() && Set.of("h2", "hsqldb", "sqlite", "derby").contains(vendor)) {
            if (!seenRefs.add("datasource|" + vendor + "|")) return;
            var span = coords.range(entry.start(), entry.end(), SourceCoordinates.Encoding.UTF16);
            var attrs = new TreeMap<String, String>();
            attrs.put("role", "datasource");
            attrs.put("target", vendor);
            attrs.put("dbVendor", vendor);
            attrs.put("spec", "spring-config");
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "spring_config_ref", vendor,
                    entry.key() + "=" + entry.value(), span, "", false, withConfigRepo(attrs, configRepoFile)));
        }
    }

    private static String extractJdbcVendor(String val) {
        String lower = val.toLowerCase(Locale.ROOT);
        String rest = null;
        if (lower.startsWith("jdbc:")) rest = lower.substring(5);
        else if (lower.startsWith("r2dbc:")) rest = lower.substring(6);
        if (rest == null) return "";
        int colon = rest.indexOf(':');
        if (colon <= 0) return "";
        String vendor = rest.substring(0, colon).trim();
        return vendor.matches("[a-z0-9_-]{1,32}") ? vendor : "";
    }

    private static void emitCustomHttpConfigProperties(List<PropEntry> all, String configRepoFile,
            SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        var seen = new HashSet<String>();
        for (PropEntry e : all) {
            String keyLower = e.key().toLowerCase(Locale.ROOT);
            boolean isSpringManaged = false;
            for (String p : SPRING_MANAGED_URL_PREFIXES) {
                if (keyLower.startsWith(p)) {
                    isSpringManaged = true;
                    break;
                }
            }
            if (isSpringManaged) continue;
            String val = resolvePlaceholderOrValue(e.value()).trim();
            if (val.isEmpty()) continue;
            String valLower = val.toLowerCase(Locale.ROOT);
            boolean isHttpScheme = valLower.startsWith("http://") || valLower.startsWith("https://")
                    || valLower.startsWith("lb://");
            boolean isUrlKey = keyLower.endsWith(".url") || keyLower.endsWith(".uri")
                    || keyLower.equals("url") || keyLower.equals("uri")
                    || keyLower.endsWith(".baseurl") || keyLower.endsWith(".base-url")
                    || keyLower.endsWith(".baseuri") || keyLower.endsWith(".base-uri")
                    || keyLower.endsWith(".endpoint");
            if (!isHttpScheme && !isUrlKey) continue;
            Optional<HostPort> hpOpt = extractHostAndPort(val, true);
            if (hpOpt.isEmpty() && !isHttpScheme) continue;
            if (!seen.add(e.key() + "|" + val)) continue;
            var attrs = new TreeMap<String, String>();
            attrs.put("key", e.key());
            attrs.put("value", val);
            if (hpOpt.isPresent()) {
                HostPort hp = hpOpt.orElseThrow();
                attrs.put("target", hp.target());
                if (!hp.port().isEmpty()) attrs.put("port", hp.port());
            }
            attrs.put("spec", "spring-config");
            var span = coords.range(e.start(), e.end(), SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("CONFIG_PROPERTY", "spring_http_property", e.key(),
                    e.key() + "=" + e.value(), span, "", false, withConfigRepo(attrs, configRepoFile)));
        }
    }

    static String normalizeGitRepoTarget(String rawUri) {
        if (rawUri == null || rawUri.isBlank()) return "git-repository";
        String resolved = resolvePlaceholderOrValue(rawUri);
        String candidate = resolved.isEmpty() ? rawUri.trim() : resolved.trim();
        String host = "";
        Optional<HostPort> hp = extractHostAndPort(candidate, true);
        if (hp.isPresent()) {
            host = hp.orElseThrow().target().toLowerCase(Locale.ROOT);
        } else {
            Matcher ssh = SSH_GIT_URI.matcher(candidate);
            if (ssh.matches()) {
                host = ssh.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (host.contains("github")) return "github-repository";
        if (host.contains("gitlab")) return "gitlab-repository";
        return "git-repository";
    }

    private static void emitGatewayRoutes(List<PropEntry> all, String prefix, String configRepoFile,
            SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        String dotPrefix = prefix + "[";
        var byIdx = new TreeMap<Integer, List<PropEntry>>();
        for (PropEntry e : all) {
            if (!e.key().startsWith(dotPrefix)) continue;
            int close = e.key().indexOf(']', dotPrefix.length());
            if (close < 0) continue;
            try {
                int idx = Integer.parseInt(e.key().substring(dotPrefix.length(), close));
                byIdx.computeIfAbsent(idx, k -> new ArrayList<>()).add(e);
            } catch (NumberFormatException ignored) {}
        }
        for (var group : byIdx.values()) {
            String routeId = "";
            String uri = "";
            String pathPrefix = "";
            String stripPrefix = "";
            String rewriteFrom = "";
            String rewriteTo = "";
            PropEntry anchor = group.getFirst();
            for (PropEntry e : group) {
                int close = e.key().indexOf(']', dotPrefix.length());
                String suffix = close + 2 <= e.key().length() ? e.key().substring(close + 2) : "";
                if (suffix.equals("id")) routeId = e.value();
                else if (suffix.equals("uri")) { uri = e.value(); anchor = e; }
                else if (suffix.startsWith("predicates")) {
                    Matcher pm = PATH_PREDICATE.matcher(e.value());
                    if (pm.find()) pathPrefix = pm.group(1).split(",")[0].trim();
                } else if (suffix.startsWith("filters")) {
                    Matcher sm = STRIP_PREFIX.matcher(e.value());
                    if (sm.find()) stripPrefix = sm.group(1);
                    Matcher rm = REWRITE_PATH.matcher(e.value());
                    if (rm.find()) {
                        rewriteFrom = rm.group(1);
                        rewriteTo = rm.group(2);
                    }
                }
            }
            if (uri.isEmpty()) continue;
            Optional<HostPort> hpOpt = extractHostAndPort(uri, true);
            String targetService = hpOpt.map(HostPort::target).orElse("");
            String port = hpOpt.map(HostPort::port).orElse("");
            var attrs = new TreeMap<String, String>();
            attrs.put("uri", uri);
            if (!routeId.isEmpty()) attrs.put("routeId", routeId);
            if (!targetService.isEmpty()) attrs.put("targetService", targetService);
            if (!port.isEmpty()) attrs.put("port", port);
            if (!pathPrefix.isEmpty()) attrs.put("pathPrefix", pathPrefix);
            if (!stripPrefix.isEmpty()) attrs.put("stripPrefix", stripPrefix);
            if (!rewriteFrom.isEmpty()) {
                attrs.put("rewriteFrom", rewriteFrom);
                attrs.put("rewriteTo", rewriteTo);
            }
            attrs.put("spec", "spring-cloud-gateway");
            var span = coords.range(anchor.start(), anchor.end(), SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("GATEWAY_ROUTE", "gateway_route",
                    targetService.isEmpty() ? routeId : targetService, uri, span, "", false,
                    withConfigRepo(attrs, configRepoFile)));
        }
    }

    private static void emitZuulRoutes(List<PropEntry> all, String configRepoFile, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out) {
        var fieldsById = new TreeMap<String, Map<String, PropEntry>>();
        for (PropEntry e : all) {
            if (!e.key().startsWith("zuul.routes.")) continue;
            String rest = e.key().substring("zuul.routes.".length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                if (!rest.equals("ignored-services")) {
                    fieldsById.computeIfAbsent(rest, k -> new LinkedHashMap<>()).put("_shorthand", e);
                }
            } else {
                String id = rest.substring(0, dot);
                String field = rest.substring(dot + 1);
                fieldsById.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(field, e);
            }
        }
        for (var entry : fieldsById.entrySet()) {
            String id = entry.getKey();
            var map = entry.getValue();
            PropEntry svc = map.get("serviceId");
            if (svc == null) svc = map.get("service-id");
            PropEntry url = map.get("url");
            PropEntry path = map.get("path");
            PropEntry strip = map.get("stripPrefix");
            if (strip == null) strip = map.get("strip-prefix");
            PropEntry shortPath = map.get("_shorthand");
            PropEntry anchor = svc != null ? svc : (url != null ? url : (path != null ? path : shortPath));
            if (anchor == null) continue;
            Optional<HostPort> urlHp = url != null ? extractHostAndPort(url.value(), true) : Optional.empty();
            String target = svc != null && !svc.value().isBlank() ? resolvePlaceholderOrValue(svc.value())
                    : (url != null ? urlHp.map(HostPort::target).orElse("") : id);
            if (target.isEmpty()) continue;
            String pPrefix = path != null ? path.value() : (shortPath != null ? shortPath.value() : "/" + id + "/**");
            var attrs = new TreeMap<String, String>();
            attrs.put("routeId", id);
            attrs.put("targetService", target);
            attrs.put("pathPrefix", pPrefix);
            if (urlHp.isPresent() && !urlHp.orElseThrow().port().isEmpty()) {
                attrs.put("port", urlHp.orElseThrow().port());
            }
            if (strip != null && strip.value().equalsIgnoreCase("true") || strip == null && pPrefix.contains("/**")) {
                boolean stripEnabled = strip == null || !strip.value().equalsIgnoreCase("false");
                if (stripEnabled) {
                    int segs = countPrefixSegments(pPrefix);
                    if (segs > 0) attrs.put("stripPrefix", Integer.toString(segs));
                }
            }
            if (url != null && !url.value().isEmpty()) attrs.put("uri", url.value());
            attrs.put("spec", "netflix-zuul");
            var span = coords.range(anchor.start(), anchor.end(), SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("GATEWAY_ROUTE", "zuul_route", target,
                    anchor.key() + "=" + anchor.value(), span, "", false,
                    withConfigRepo(attrs, configRepoFile)));
        }
    }

    // -------------------------------------------------------------------------
    // Standard Apache HTTPD & Nginx reverse-proxy parsers
    // -------------------------------------------------------------------------

    static void parseApacheHttpdConfig(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        var balancers = new LinkedHashMap<String, List<HostPort>>();
        String currentBalancer = null;
        int len = text.length();
        int offset = 0;
        // Pass 1: collect <Proxy balancer://name> BalancerMember targets
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String trimmed = stripHashComment(text.substring(offset, contentEnd)).trim();
            if (!trimmed.isEmpty()) {
                Matcher bm = APACHE_PROXY_BLOCK.matcher(trimmed);
                if (bm.find()) {
                    currentBalancer = bm.group(1).toLowerCase(Locale.ROOT);
                } else if (trimmed.equalsIgnoreCase("</Proxy>")) {
                    currentBalancer = null;
                } else if (currentBalancer != null) {
                    Matcher mem = APACHE_BALANCER_MEMBER.matcher(trimmed);
                    if (mem.find()) {
                        String url = unquote(mem.group(1));
                        String bal = currentBalancer;
                        extractHostAndPort(url, true).ifPresent(hp ->
                                balancers.computeIfAbsent(bal, k -> new ArrayList<>()).add(hp));
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }

        // Pass 2: collect ProxyPass / ProxyPassReverse and Listen directives
        var routeFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var portSpans = new ArrayList<int[]>();
        var portVals = new ArrayList<String>();
        var seenRoutes = new HashSet<String>();
        SourceCoordinates.Range firstDirectiveSpan = null;
        String ownerSlot = "";

        offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = text.substring(offset, contentEnd);
            String trimmed = stripHashComment(raw).trim();
            stats[0]++;
            if (!trimmed.isEmpty()) {
                int start = offset + leadingSpaces(raw);
                Matcher listenMatcher = APACHE_LISTEN.matcher(trimmed);
                if (listenMatcher.find()) {
                    portSpans.add(new int[]{start, Math.max(start + 1, contentEnd)});
                    portVals.add(listenMatcher.group(1));
                } else {
                    Matcher pm = APACHE_PROXY_PASS.matcher(trimmed);
                    if (pm.find()) {
                        String pathPrefix = unquote(pm.group(1));
                        String rawTarget = unquote(pm.group(2));
                        if (!"!".equals(rawTarget)) {
                            var span = coords.range(start, Math.max(start + 1, contentEnd), SourceCoordinates.Encoding.UTF16);
                            if (firstDirectiveSpan == null) {
                                firstDirectiveSpan = span;
                                ownerSlot = span.start() + ":" + span.end();
                            }
                            var targets = new ArrayList<HostPort>();
                            if (rawTarget.toLowerCase(Locale.ROOT).startsWith("balancer://")) {
                                String balKey = extractBalancerKey(rawTarget);
                                targets.addAll(balancers.getOrDefault(balKey, List.of()));
                            } else {
                                extractHostAndPort(rawTarget, true).ifPresent(targets::add);
                            }
                            for (HostPort hp : targets) {
                                if (!seenRoutes.add(pathPrefix + "|" + hp.target() + "|" + hp.port())) continue;
                                var attrs = new TreeMap<String, String>();
                                attrs.put("proxy", "apache");
                                attrs.put("targetService", hp.target());
                                attrs.put("target", hp.target());
                                attrs.put("uri", rawTarget);
                                if (pathPrefix.startsWith("/")) attrs.put("pathPrefix", pathPrefix);
                                if (!hp.port().isEmpty()) attrs.put("port", hp.port());
                                attrs.put("spec", "apache-httpd");
                                routeFacts.add(new TreeSitterSourceFrontend.Fact("GATEWAY_ROUTE", "apache_proxy_pass",
                                        hp.target(), truncate(trimmed, 1024), span, ownerSlot, false, attrs));
                            }
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        if (routeFacts.isEmpty() || firstDirectiveSpan == null) return;
        out.add(new TreeSitterSourceFrontend.Fact("DECLARATION", "apache_proxy", "apache", "apache",
                firstDirectiveSpan, "", false,
                Map.of("role", "reverse-proxy", "proxy", "apache", "spec", "apache-httpd")));
        out.addAll(routeFacts);
        for (int i = 0; i < portVals.size(); i++) {
            int[] ps = portSpans.get(i);
            String pv = portVals.get(i);
            var span = coords.range(ps[0], ps[1], SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_PORT", "apache_listen", pv, pv, span, ownerSlot, false,
                    Map.of("port", pv, "externalPort", "true", "spec", "apache-httpd")));
        }
    }

    private static String extractBalancerKey(String rawTarget) {
        String lower = rawTarget.toLowerCase(Locale.ROOT);
        int idx = lower.indexOf("balancer://");
        if (idx < 0) return lower;
        int after = idx + "balancer://".length();
        int slash = lower.indexOf('/', after);
        return slash > after ? lower.substring(idx, slash) : lower.substring(idx);
    }

    static boolean looksLikeNginxConfig(String text) {
        if (text == null || text.isBlank()) return false;
        return text.contains("proxy_pass") || text.contains("upstream ")
                || text.contains("server {") || text.contains("server{")
                || text.contains("location ");
    }

    static void parseNginxConfig(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        // Pass 1: parse upstream <name> { server host[:port]; }
        var upstreams = new LinkedHashMap<String, List<HostPort>>();
        String currentUpstream = null;
        int upstreamDepth = 0;
        int len = text.length();
        int offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String trimmed = stripHashComment(text.substring(offset, contentEnd)).trim();
            if (!trimmed.isEmpty()) {
                if (currentUpstream == null) {
                    Matcher um = NGINX_UPSTREAM_START.matcher(trimmed);
                    if (um.find()) {
                        currentUpstream = um.group(1).toLowerCase(Locale.ROOT);
                        upstreamDepth = braceDelta(trimmed);
                        collectNginxUpstreamServers(trimmed, currentUpstream, upstreams);
                        if (upstreamDepth <= 0) {
                            currentUpstream = null;
                            upstreamDepth = 0;
                        }
                    }
                } else {
                    collectNginxUpstreamServers(trimmed, currentUpstream, upstreams);
                    upstreamDepth += braceDelta(trimmed);
                    if (upstreamDepth <= 0) {
                        currentUpstream = null;
                        upstreamDepth = 0;
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }

        // Pass 2: parse location / proxy_pass / listen
        var routeFacts = new ArrayList<TreeSitterSourceFrontend.Fact>();
        var portSpans = new ArrayList<int[]>();
        var portVals = new ArrayList<String>();
        var seenRoutes = new HashSet<String>();
        SourceCoordinates.Range firstDirectiveSpan = null;
        String ownerSlot = "";
        String currentLocation = "";
        int locationDepth = 0;

        offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = text.substring(offset, contentEnd);
            String trimmed = stripHashComment(raw).trim();
            stats[0]++;
            if (!trimmed.isEmpty()) {
                int start = offset + leadingSpaces(raw);
                Matcher lm = NGINX_LOCATION_START.matcher(trimmed);
                if (lm.find()) {
                    currentLocation = unquote(lm.group(1));
                    locationDepth = braceDelta(trimmed);
                } else if (locationDepth > 0) {
                    locationDepth += braceDelta(trimmed);
                }
                Matcher listenMatcher = NGINX_LISTEN.matcher(trimmed);
                if (listenMatcher.find()) {
                    portSpans.add(new int[]{start, Math.max(start + 1, contentEnd)});
                    portVals.add(listenMatcher.group(1));
                }
                Matcher pm = NGINX_PROXY_PASS.matcher(trimmed);
                while (pm.find()) {
                    String rawUrl = unquote(pm.group(1));
                    var span = coords.range(start, Math.max(start + 1, contentEnd), SourceCoordinates.Encoding.UTF16);
                    if (firstDirectiveSpan == null) {
                        firstDirectiveSpan = span;
                        ownerSlot = span.start() + ":" + span.end();
                    }
                    var targets = new ArrayList<HostPort>();
                    Optional<HostPort> directHp = extractHostAndPort(rawUrl, true);
                    if (directHp.isPresent()) {
                        HostPort hp = directHp.orElseThrow();
                        List<HostPort> expanded = upstreams.get(hp.target());
                        if (expanded != null && !expanded.isEmpty()) {
                            targets.addAll(expanded);
                        } else {
                            targets.add(hp);
                        }
                    }
                    for (HostPort hp : targets) {
                        if (!seenRoutes.add(currentLocation + "|" + hp.target() + "|" + hp.port())) continue;
                        var attrs = new TreeMap<String, String>();
                        attrs.put("proxy", "nginx");
                        attrs.put("targetService", hp.target());
                        attrs.put("target", hp.target());
                        attrs.put("uri", rawUrl);
                        if (currentLocation.startsWith("/")) attrs.put("pathPrefix", currentLocation);
                        if (!hp.port().isEmpty()) attrs.put("port", hp.port());
                        attrs.put("spec", "nginx");
                        routeFacts.add(new TreeSitterSourceFrontend.Fact("GATEWAY_ROUTE", "nginx_proxy_pass",
                                hp.target(), truncate(trimmed, 1024), span, ownerSlot, false, attrs));
                    }
                }
                if (locationDepth <= 0) {
                    currentLocation = "";
                    locationDepth = 0;
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        if (routeFacts.isEmpty() || firstDirectiveSpan == null) return;
        out.add(new TreeSitterSourceFrontend.Fact("DECLARATION", "nginx_proxy", "nginx", "nginx",
                firstDirectiveSpan, "", false,
                Map.of("role", "reverse-proxy", "proxy", "nginx", "spec", "nginx")));
        out.addAll(routeFacts);
        for (int i = 0; i < portVals.size(); i++) {
            int[] ps = portSpans.get(i);
            String pv = portVals.get(i);
            var span = coords.range(ps[0], ps[1], SourceCoordinates.Encoding.UTF16);
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_PORT", "nginx_listen", pv, pv, span, ownerSlot, false,
                    Map.of("port", pv, "externalPort", "true", "spec", "nginx")));
        }
    }

    private static void collectNginxUpstreamServers(String line, String upstreamName,
            Map<String, List<HostPort>> upstreams) {
        Matcher sm = NGINX_UPSTREAM_SERVER.matcher(line);
        while (sm.find()) {
            String hostPortSpec = unquote(sm.group(1));
            extractHostAndPort(hostPortSpec, true).ifPresent(hp ->
                    upstreams.computeIfAbsent(upstreamName, k -> new ArrayList<>()).add(hp));
        }
    }

    private static int braceDelta(String s) {
        int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') d++;
            else if (c == '}') d--;
        }
        return d;
    }

    // -------------------------------------------------------------------------
    // Standard Logback XML parser (<destination> / <remoteHost>)
    // -------------------------------------------------------------------------

    static void parseLogbackXml(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
        String masked = maskMatchesWithSpaces(text, XML_COMMENT);
        stats[0]++;
        var seen = new HashSet<String>();
        boolean hasLogstashClass = masked.toLowerCase(Locale.ROOT).contains("logstash");

        Matcher dm = LOGBACK_DESTINATION.matcher(masked);
        while (dm.find()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            stats[0]++;
            String rawDest = dm.group(1).trim();
            var span = coords.range(dm.start(), dm.end(), SourceCoordinates.Encoding.UTF16);
            for (String part : rawDest.split(",")) {
                String item = part.trim();
                if (item.isEmpty()) continue;
                Optional<HostPort> hpOpt = extractHostAndPort(item, true);
                if (hpOpt.isPresent()) {
                    emitLogbackRef(hpOpt.orElseThrow(), dm.group(0), span, seen, out);
                } else if (hasLogstashClass && item.contains("${")) {
                    String port = "";
                    int colon = item.lastIndexOf(':');
                    if (colon > 0) {
                        String p = resolvePlaceholderOrValue(item.substring(colon + 1));
                        if (NUMERIC_PORT.matcher(p).matches()) port = p;
                    }
                    emitLogbackRef(new HostPort("logstash", port), dm.group(0), span, seen, out);
                }
            }
        }

        String defaultPort = "";
        Matcher pm = LOGBACK_PORT.matcher(masked);
        if (pm.find()) {
            String p = resolvePlaceholderOrValue(pm.group(1));
            if (NUMERIC_PORT.matcher(p).matches()) defaultPort = p;
        }

        Matcher rm = LOGBACK_REMOTE_HOST.matcher(masked);
        while (rm.find()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            stats[0]++;
            String rawHost = rm.group(1).trim();
            var span = coords.range(rm.start(), rm.end(), SourceCoordinates.Encoding.UTF16);
            Optional<HostPort> hpOpt = extractHostAndPort(rawHost, true);
            if (hpOpt.isPresent()) {
                HostPort hp = hpOpt.orElseThrow();
                String port = hp.port().isEmpty() ? defaultPort : hp.port();
                emitLogbackRef(new HostPort(hp.target(), port), rm.group(0), span, seen, out);
            } else if (hasLogstashClass && rawHost.contains("${")) {
                emitLogbackRef(new HostPort("logstash", defaultPort), rm.group(0), span, seen, out);
            }
        }
    }

    private static void emitLogbackRef(HostPort hp, String written, SourceCoordinates.Range span,
            Set<String> seen, List<TreeSitterSourceFrontend.Fact> out) {
        if (!seen.add(hp.target() + "|" + hp.port())) return;
        var attrs = new TreeMap<String, String>();
        attrs.put("role", "logstash");
        attrs.put("target", hp.target());
        if (!hp.port().isEmpty()) attrs.put("port", hp.port());
        attrs.put("spec", "logback");
        out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "logback_appender", hp.target(),
                truncate(written.trim(), 1024), span, "", false, attrs));
    }

    // -------------------------------------------------------------------------
    // Passive Maven pom.xml / Gradle build.gradle(.kts) starter dependency parser
    // -------------------------------------------------------------------------

    static void parseBuildDescriptorStarters(String path, String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        String lower = baseName(path).toLowerCase(Locale.ROOT);
        if (lower.equals("pom.xml")) {
            parseMavenPomStarters(text, coords, out, stats, cancelled);
        } else if (lower.equals("build.gradle") || lower.equals("build.gradle.kts")) {
            parseGradleStarters(text, coords, out, stats, cancelled);
        }
    }

    private static void parseMavenPomStarters(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        String masked = maskMatchesWithSpaces(text, XML_COMMENT);
        masked = maskMatchesWithSpaces(masked, POM_EXCLUDED_BLOCK);
        var seenStarters = new HashSet<String>();
        Matcher depMatcher = POM_DEPENDENCY_BLOCK.matcher(masked);
        while (depMatcher.find()) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            stats[0]++;
            String body = depMatcher.group(1);
            int bodyOffset = depMatcher.start(1);
            Matcher scopeMatcher = POM_SCOPE.matcher(body);
            if (scopeMatcher.find() && "test".equalsIgnoreCase(scopeMatcher.group(1).trim())) {
                continue;
            }
            Matcher artMatcher = POM_ARTIFACT_ID.matcher(body);
            if (!artMatcher.find()) continue;
            String artifactId = artMatcher.group(1).trim();
            Matcher grpMatcher = POM_GROUP_ID.matcher(body);
            String groupId = grpMatcher.find() ? grpMatcher.group(1).trim() : "";
            String starter = mapDependencyToStarter(groupId, artifactId);
            if (starter == null || !seenStarters.add(starter)) continue;
            int spanStart = bodyOffset + artMatcher.start();
            int spanEnd = bodyOffset + artMatcher.end();
            var span = coords.range(spanStart, Math.max(spanStart + 1, spanEnd), SourceCoordinates.Encoding.UTF16);
            var attrs = new TreeMap<String, String>();
            attrs.put("starter", starter);
            attrs.put("artifactId", artifactId);
            if (!groupId.isEmpty()) attrs.put("groupId", groupId);
            attrs.put("spec", "maven");
            String written = groupId.isEmpty() ? artifactId : groupId + ":" + artifactId;
            out.add(new TreeSitterSourceFrontend.Fact("INFRA_DEPENDENCY_STARTER", "maven_dependency", starter,
                    written, span, "", false, attrs));
        }
    }

    private static void parseGradleStarters(String text, SourceCoordinates coords,
            List<TreeSitterSourceFrontend.Fact> out, long[] stats, BooleanSupplier cancelled) throws IOException {
        String masked = maskGradleCommentsAndBuildscript(text);
        var seenStarters = new HashSet<String>();
        int len = masked.length();
        int offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = masked.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && masked.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = masked.substring(offset, contentEnd);
            String trimmed = raw.trim();
            stats[0]++;
            if (!trimmed.isEmpty() && !GRADLE_TEST_OR_EXCLUDE_LINE.matcher(trimmed).find()) {
                boolean matchedStringCoord = false;
                Matcher sm = GRADLE_STRING_COORD.matcher(raw);
                while (sm.find()) {
                    matchedStringCoord = true;
                    String groupId = sm.group(1);
                    String artifactId = sm.group(2);
                    emitGradleStarter(groupId, artifactId, offset + sm.start(), offset + sm.end(),
                            seenStarters, coords, out);
                }
                if (!matchedStringCoord) {
                    Matcher nm = GRADLE_MAP_NAME.matcher(raw);
                    if (nm.find()) {
                        String artifactId = nm.group(1);
                        Matcher gm = GRADLE_MAP_GROUP.matcher(raw);
                        String groupId = gm.find() ? gm.group(1) : "";
                        emitGradleStarter(groupId, artifactId, offset + nm.start(), offset + nm.end(),
                                seenStarters, coords, out);
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
    }

    private static void emitGradleStarter(String groupId, String artifactId, int start, int end,
            Set<String> seenStarters, SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        String starter = mapDependencyToStarter(groupId, artifactId);
        if (starter == null || !seenStarters.add(starter)) return;
        var span = coords.range(start, Math.max(start + 1, end), SourceCoordinates.Encoding.UTF16);
        var attrs = new TreeMap<String, String>();
        attrs.put("starter", starter);
        attrs.put("artifactId", artifactId);
        if (!groupId.isEmpty()) attrs.put("groupId", groupId);
        attrs.put("spec", "gradle");
        String written = groupId.isEmpty() ? artifactId : groupId + ":" + artifactId;
        out.add(new TreeSitterSourceFrontend.Fact("INFRA_DEPENDENCY_STARTER", "gradle_dependency", starter,
                written, span, "", false, attrs));
    }

    static String mapDependencyToStarter(String groupId, String artifactId) {
        if (artifactId == null || artifactId.isBlank()) return null;
        String art = artifactId.trim().toLowerCase(Locale.ROOT);
        String grp = groupId == null ? "" : groupId.trim().toLowerCase(Locale.ROOT);

        // Order matters: check server before client where prefixes overlap.
        if (art.equals("spring-cloud-starter-netflix-eureka-server")
                || art.equals("spring-cloud-starter-eureka-server")
                || art.equals("spring-cloud-netflix-eureka-server")) {
            return "eureka-server";
        }
        if (art.equals("spring-cloud-starter-netflix-eureka-client")
                || art.equals("spring-cloud-starter-eureka")
                || art.equals("spring-cloud-netflix-eureka-client")) {
            return "eureka-client";
        }
        if (art.equals("spring-cloud-config-server")
                || art.equals("spring-cloud-starter-config-server")) {
            return "config-server";
        }
        if (art.equals("spring-cloud-starter-config")
                || art.equals("spring-cloud-config-client")) {
            return "config-client";
        }
        if (art.equals("spring-cloud-starter-zipkin")
                || art.equals("spring-boot-starter-zipkin")
                || art.equals("spring-cloud-sleuth-zipkin")
                || art.equals("spring-cloud-starter-sleuth-zipkin")
                || art.equals("spring-cloud-sleuth-zipkin-stream")
                || art.equals("zipkin-server")
                || art.equals("zipkin-autoconfigure-ui")
                || art.equals("zipkin-reporter-brave")
                || art.equals("zipkin-reporter-metrics-micrometer")
                || art.equals("micrometer-tracing-reporter-brave")
                || art.equals("opentelemetry-exporter-zipkin")) {
            return "zipkin";
        }
        if (art.equals("spring-cloud-starter-sleuth")
                || art.equals("spring-cloud-sleuth-core")
                || art.equals("micrometer-tracing-bridge-brave")
                || art.equals("micrometer-tracing-bridge-otel")) {
            return "sleuth";
        }
        if (art.equals("spring-cloud-starter-bus-amqp")
                || art.equals("spring-cloud-bus-amqp")) {
            return "bus-amqp";
        }
        if (art.equals("spring-cloud-starter-stream-rabbit")
                || art.equals("spring-cloud-stream-binder-rabbit")
                || art.equals("spring-boot-starter-amqp")) {
            return "stream-rabbit";
        }
        if (art.equals("spring-cloud-starter-stream-kafka")
                || art.equals("spring-cloud-stream-binder-kafka")
                || art.equals("spring-cloud-starter-bus-kafka")
                || art.equals("spring-kafka")) {
            return "stream-kafka";
        }
        if (art.equals("spring-boot-admin-starter-server")
                || art.equals("spring-boot-admin-server")) {
            return "boot-admin-server";
        }
        if (art.equals("spring-boot-admin-starter-client")
                || art.equals("spring-boot-admin-client")) {
            return "boot-admin-client";
        }
        if (art.equals("spring-boot-starter-data-jpa")
                || art.equals("spring-boot-starter-jdbc")
                || art.equals("spring-boot-starter-data-jdbc")
                || art.equals("spring-boot-starter-data-r2dbc")) {
            return "data-jpa";
        }
        if (art.equals("mysql-connector-java")
                || art.equals("mysql-connector-j")
                || art.equals("mariadb-java-client")
                || art.equals("r2dbc-mysql")) {
            return "jdbc-mysql";
        }
        if ((art.equals("postgresql") && (grp.isEmpty() || grp.contains("postgres")))
                || art.equals("r2dbc-postgresql")) {
            return "jdbc-postgres";
        }
        if ((art.equals("h2") && (grp.isEmpty() || grp.equals("com.h2database")))
                || art.equals("r2dbc-h2")) {
            return "jdbc-h2";
        }
        if (art.equals("spring-boot-starter-data-mongodb")
                || art.equals("spring-boot-starter-data-mongodb-reactive")) {
            return "data-mongodb";
        }
        if (art.equals("spring-boot-starter-data-redis")
                || art.equals("spring-boot-starter-data-redis-reactive")) {
            return "data-redis";
        }
        if (art.equals("spring-boot-starter-mail")) {
            return "mail";
        }
        if (art.equals("micrometer-registry-prometheus")
                || art.equals("simpleclient_spring_boot")
                || art.equals("simpleclient_servlet")) {
            return "prometheus";
        }
        if (art.equals("spring-cloud-starter-consul-discovery")
                || art.equals("spring-cloud-starter-consul-config")
                || art.equals("spring-cloud-starter-consul-all")
                || art.equals("spring-cloud-starter-consul")) {
            return "consul";
        }
        if (art.equals("logstash-logback-encoder")) {
            return "logstash-logback";
        }
        if (art.equals("spring-boot-starter-oauth2-client")
                || art.equals("spring-security-oauth2-client")) {
            return "oauth2-client";
        }
        if (art.equals("spring-boot-starter-oauth2-resource-server")
                || art.equals("spring-security-oauth2-resource-server")
                || art.equals("spring-cloud-starter-oauth2")
                || art.equals("spring-security-oauth2")) {
            return "oauth2-resource";
        }
        if (art.equals("spring-cloud-starter-netflix-zuul")
                || art.equals("spring-cloud-starter-zuul")) {
            return "zuul";
        }
        if (art.equals("spring-cloud-starter-gateway")
                || art.equals("spring-cloud-starter-gateway-mvc")) {
            return "gateway";
        }
        if (art.equals("spring-cloud-starter-netflix-hystrix-dashboard")
                || art.equals("spring-cloud-starter-hystrix-dashboard")) {
            return "hystrix-dashboard";
        }
        if (art.equals("spring-cloud-netflix-hystrix-stream")
                || art.equals("spring-cloud-netflix-hystrix-amqp")
                || art.equals("spring-cloud-starter-hystrix-stream")
                || art.equals("spring-cloud-starter-netflix-hystrix-stream")
                || art.equals("spring-cloud-starter-hystrix-amqp")
                || art.equals("spring-cloud-starter-netflix-hystrix-amqp")) {
            return "hystrix-stream";
        }
        if (art.equals("spring-cloud-starter-turbine-stream")
                || art.equals("spring-cloud-starter-netflix-turbine-stream")
                || art.equals("spring-cloud-netflix-turbine-stream")
                || art.equals("spring-cloud-starter-turbine-amqp")
                || art.equals("spring-cloud-netflix-turbine-amqp")) {
            return "turbine-stream";
        }
        if (art.equals("spring-cloud-starter-netflix-turbine")
                || art.equals("spring-cloud-starter-turbine")
                || art.equals("spring-cloud-netflix-turbine")) {
            return "turbine";
        }
        if (art.equals("spring-cloud-starter-openfeign")
                || art.equals("spring-cloud-starter-feign")) {
            return "openfeign";
        }
        return null;
    }

    private static String maskGradleCommentsAndBuildscript(String text) {
        char[] chars = text.toCharArray();
        int n = chars.length;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (inSingle) {
                if (c == '\\' && i + 1 < n) { i++; continue; }
                if (c == '\'') inSingle = false;
            } else if (inDouble) {
                if (c == '\\' && i + 1 < n) { i++; continue; }
                if (c == '"') inDouble = false;
            } else {
                if (c == '/' && i + 1 < n && chars[i + 1] == '/') {
                    while (i < n && chars[i] != '\n' && chars[i] != '\r') {
                        chars[i++] = ' ';
                    }
                } else if (c == '/' && i + 1 < n && chars[i + 1] == '*') {
                    chars[i++] = ' ';
                    chars[i++] = ' ';
                    while (i < n) {
                        if (chars[i] == '*' && i + 1 < n && chars[i + 1] == '/') {
                            chars[i++] = ' ';
                            chars[i] = ' ';
                            break;
                        }
                        if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
                        i++;
                    }
                } else if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                }
            }
        }
        // Mask top-level buildscript { ... } block so Gradle plugin classpath deps are skipped.
        String noComments = new String(chars);
        Matcher bs = Pattern.compile("\\bbuildscript\\s*\\{").matcher(noComments);
        while (bs.find()) {
            int start = bs.start();
            int idx = bs.end();
            int depth = 1;
            while (idx < n && depth > 0) {
                if (chars[idx] == '{') depth++;
                else if (chars[idx] == '}') depth--;
                if (chars[idx] != '\n' && chars[idx] != '\r') chars[idx] = ' ';
                idx++;
            }
            for (int k = start; k < bs.end(); k++) {
                if (chars[k] != '\n' && chars[k] != '\r') chars[k] = ' ';
            }
        }
        return new String(chars);
    }

    private static String maskMatchesWithSpaces(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        if (!m.find()) return text;
        char[] chars = text.toCharArray();
        do {
            for (int i = m.start(); i < m.end(); i++) {
                if (chars[i] != '\n' && chars[i] != '\r') chars[i] = ' ';
            }
        } while (m.find());
        return new String(chars);
    }

    // -------------------------------------------------------------------------
    // Shared Spring config & host/port extraction helpers
    // -------------------------------------------------------------------------

    private static int countPrefixSegments(String antPath) {
        if (antPath == null || !antPath.startsWith("/")) return 0;
        String clean = antPath.endsWith("/**") ? antPath.substring(0, antPath.length() - 3)
                : (antPath.endsWith("/*") ? antPath.substring(0, antPath.length() - 2) : antPath);
        int count = 0;
        for (String seg : clean.split("/")) {
            if (!seg.isEmpty() && !seg.equals("*")) count++;
        }
        return count;
    }

    private static void emitConfigRefsIfHost(List<PropEntry> entries, String role, String defaultPort,
            String configRepoFile, Set<String> seenRefs,
            SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        for (PropEntry entry : entries) {
            if (entry == null || entry.value().isBlank()) continue;
            String rawVal = stripInlineBrackets(entry.value());
            for (String part : rawVal.split(",")) {
                String cleanPart = unquote(part.trim());
                extractHostAndPort(cleanPart, true).ifPresent(hp -> {
                    String port = hp.port().isEmpty() ? defaultPort : hp.port();
                    if (!seenRefs.add(role + "|" + hp.target() + "|" + port)) return;
                    var span = coords.range(entry.start(), entry.end(), SourceCoordinates.Encoding.UTF16);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("role", role);
                    attrs.put("target", hp.target());
                    if (!port.isEmpty()) attrs.put("port", port);
                    attrs.put("spec", "spring-config");
                    out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "spring_config_ref", hp.target(),
                            entry.key() + "=" + entry.value(), span, "", false,
                            withConfigRepo(attrs, configRepoFile)));
                });
            }
        }
    }

    private static void emitConfigRefsDirect(List<PropEntry> entries, String role, String defaultPort,
            String configRepoFile, Set<String> seenRefs,
            SourceCoordinates coords, List<TreeSitterSourceFrontend.Fact> out) {
        for (PropEntry entry : entries) {
            if (entry == null || entry.value().isBlank()) continue;
            String rawVal = stripInlineBrackets(entry.value());
            for (String part : rawVal.split(",")) {
                String cleanPart = unquote(part.trim());
                extractHostAndPort(cleanPart, true).ifPresent(hp -> {
                    String port = hp.port().isEmpty() ? defaultPort : hp.port();
                    if (!seenRefs.add(role + "|" + hp.target() + "|" + port)) return;
                    var span = coords.range(entry.start(), entry.end(), SourceCoordinates.Encoding.UTF16);
                    var attrs = new TreeMap<String, String>();
                    attrs.put("role", role);
                    attrs.put("target", hp.target());
                    if (!port.isEmpty()) attrs.put("port", port);
                    attrs.put("spec", "spring-config");
                    out.add(new TreeSitterSourceFrontend.Fact("INFRA_CONFIG_REF", "spring_config_ref", hp.target(),
                            entry.key() + "=" + entry.value(), span, "", false,
                            withConfigRepo(attrs, configRepoFile)));
                });
            }
        }
    }

    private static Map<String, String> withConfigRepo(Map<String, String> base, String configRepoFile) {
        if (configRepoFile == null || configRepoFile.isEmpty()) return base;
        var out = new TreeMap<String, String>(base);
        out.put("configRepoFile", configRepoFile);
        return out;
    }

    private static PropEntry firstRelaxed(Map<String, List<PropEntry>> byRelaxed, String... keys) {
        for (String k : keys) {
            List<PropEntry> list = byRelaxed.get(k);
            if (list != null && !list.isEmpty()) return list.getFirst();
        }
        return null;
    }

    private static List<PropEntry> allRelaxed(Map<String, List<PropEntry>> byRelaxed, String... keys) {
        var result = new ArrayList<PropEntry>();
        for (String k : keys) {
            List<PropEntry> list = byRelaxed.get(k);
            if (list != null) result.addAll(list);
        }
        return result;
    }

    private static String numericPropValue(PropEntry entry) {
        if (entry == null || entry.value().isBlank()) return "";
        String val = resolvePlaceholderOrValue(entry.value());
        return NUMERIC_PORT.matcher(val).matches() ? val : "";
    }

    private static String relaxedKey(String key) {
        var sb = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '-' || c == '_') continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String stripIndexSuffix(String key) {
        int bracket = key.indexOf('[');
        return bracket > 0 ? key.substring(0, bracket) : key;
    }

    private static String stripInlineBrackets(String v) {
        String t = v.trim();
        if (t.startsWith("[") && t.endsWith("]") && t.length() >= 2) {
            return t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static List<PropEntry> parseProperties(String text, long[] stats, BooleanSupplier cancelled) throws IOException {
        var out = new ArrayList<PropEntry>();
        int len = text.length();
        int offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = text.substring(offset, contentEnd);
            String trimmed = raw.trim();
            stats[0]++;
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("!")) {
                int sep = trimmed.indexOf('=');
                if (sep < 0) sep = trimmed.indexOf(':');
                if (sep > 0) {
                    String key = trimmed.substring(0, sep).trim();
                    String val = trimmed.substring(sep + 1).trim()
                            .replace("\\:", ":")
                            .replace("\\=", "=");
                    out.add(new PropEntry(key, relaxedKey(key), val, offset, Math.max(offset + 1, contentEnd)));
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        return out;
    }

    private record YamlFrame(int indent, String segment) {}

    private static String joinYamlFrames(List<YamlFrame> stack) {
        if (stack.isEmpty()) return "";
        var sb = new StringBuilder();
        for (YamlFrame f : stack) {
            if (f.segment().startsWith("[")) {
                sb.append(f.segment());
            } else {
                if (!sb.isEmpty()) sb.append('.');
                sb.append(f.segment());
            }
        }
        return sb.toString();
    }

    private static List<PropEntry> flattenYamlProperties(String text, long[] stats, BooleanSupplier cancelled) throws IOException {
        var out = new ArrayList<PropEntry>();
        var stack = new ArrayList<YamlFrame>();
        var listCounters = new HashMap<String, Integer>();
        int len = text.length();
        int offset = 0;
        while (offset < len) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("SYNTAX_CANCELLED");
            int nl = text.indexOf('\n', offset);
            int lineEnd = nl < 0 ? len : nl;
            int contentEnd = (lineEnd > offset && text.charAt(lineEnd - 1) == '\r') ? lineEnd - 1 : lineEnd;
            String raw = text.substring(offset, contentEnd);
            String cleaned = stripYamlComment(raw).trim();
            stats[0]++;
            if (!cleaned.isEmpty()) {
                if (cleaned.equals("---")) {
                    stack.clear();
                    listCounters.clear();
                    offset = nl < 0 ? len : nl + 1;
                    continue;
                }
                int indent = leadingSpaces(raw);
                boolean isListItem = isYamlSeqItem(cleaned);
                while (!stack.isEmpty()) {
                    YamlFrame top = stack.getLast();
                    boolean pop = isListItem
                            ? (top.segment().startsWith("[") ? indent <= top.indent() : indent < top.indent())
                            : indent <= top.indent();
                    if (!pop) break;
                    stack.removeLast();
                }
                String parentPrefix = joinYamlFrames(stack);
                if (isListItem) {
                    int idx = listCounters.merge(parentPrefix, 0, (old, one) -> old + 1);
                    String listSeg = "[" + idx + "]";
                    String itemPrefix = parentPrefix + listSeg;
                    String body = cleaned.length() > 1 ? cleaned.substring(1).trim() : "";
                    int colon = findYamlColon(body);
                    if (colon > 0) {
                        String k = unquote(body.substring(0, colon).trim());
                        String v = unquote(body.substring(colon + 1).trim());
                        String fullKey = itemPrefix + "." + k;
                        if (!v.isEmpty()) {
                            out.add(new PropEntry(fullKey, relaxedKey(fullKey), v,
                                    offset + indent, Math.max(offset + indent + 1, contentEnd)));
                        }
                        stack.add(new YamlFrame(indent, listSeg));
                        if (v.isEmpty()) {
                            listCounters.remove(fullKey);
                            stack.add(new YamlFrame(indent + 1, k));
                        }
                    } else if (!body.isEmpty()) {
                        out.add(new PropEntry(itemPrefix, relaxedKey(itemPrefix), unquote(body),
                                offset + indent, Math.max(offset + indent + 1, contentEnd)));
                    } else {
                        stack.add(new YamlFrame(indent, listSeg));
                    }
                } else {
                    int colon = findYamlColon(cleaned);
                    if (colon > 0) {
                        String k = unquote(cleaned.substring(0, colon).trim());
                        String v = unquote(cleaned.substring(colon + 1).trim());
                        String fullKey = parentPrefix.isEmpty() ? k : parentPrefix + "." + k;
                        if (v.isEmpty()) {
                            listCounters.remove(fullKey);
                            stack.add(new YamlFrame(indent, k));
                        } else {
                            out.add(new PropEntry(fullKey, relaxedKey(fullKey), v,
                                    offset + indent, Math.max(offset + indent + 1, contentEnd)));
                        }
                    }
                }
            }
            offset = nl < 0 ? len : nl + 1;
        }
        return out;
    }

    private static int findYamlColon(String s) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble && (i + 1 == s.length() || Character.isWhitespace(s.charAt(i + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isYamlSeqItem(String cleaned) {
        return cleaned.equals("-") || (cleaned.startsWith("-") && cleaned.length() > 1
                && Character.isWhitespace(cleaned.charAt(1)));
    }

    static Optional<String> extractHostTarget(String raw) {
        return extractHostAndPort(raw, false).map(HostPort::target);
    }

    static Optional<HostPort> extractHostAndPort(String raw, boolean allowLoopback) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String val = resolvePlaceholderOrValue(raw).trim();
        if (val.isEmpty()) return Optional.empty();
        String lowerVal = val.toLowerCase(Locale.ROOT);
        if (lowerVal.startsWith("jdbc:") || lowerVal.startsWith("r2dbc:")) {
            int schemeIdx = val.indexOf("://");
            if (schemeIdx > 0) {
                val = "http://" + val.substring(schemeIdx + 3);
            } else {
                int at = val.indexOf('@');
                if (at > 0 && at + 1 < val.length()) {
                    String afterAt = val.substring(at + 1);
                    if (afterAt.startsWith("//")) afterAt = afterAt.substring(2);
                    val = "http://" + afterAt;
                } else {
                    return Optional.empty();
                }
            }
        }
        int protoEnd = val.indexOf("://");
        if (protoEnd > 0) {
            String rest = stripUserInfo(val.substring(protoEnd + 3));
            String authority = extractAuthority(rest);
            return parseHostPortAuthority(authority, allowLoopback);
        }
        // Bare host or host:port
        String first = val.split(",")[0].trim();
        String authority = extractAuthority(first);
        return parseHostPortAuthority(authority, allowLoopback);
    }

    private static String extractAuthority(String rest) {
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == ';' || c == ',') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end).trim();
    }

    private static Optional<HostPort> parseHostPortAuthority(String authority, boolean allowLoopback) {
        if (authority.isEmpty()) return Optional.empty();
        String host;
        String port = "";
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close < 0) return Optional.empty();
            host = authority.substring(1, close).trim().toLowerCase(Locale.ROOT);
            if (close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
                String p = authority.substring(close + 2).trim();
                if (NUMERIC_PORT.matcher(p).matches()) port = p;
            }
        } else {
            int colon = authority.indexOf(':');
            if (colon >= 0) {
                host = authority.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String after = authority.substring(colon + 1).trim();
                int nextColon = after.indexOf(':');
                String candidatePort = nextColon > 0 ? after.substring(0, nextColon) : after;
                if (NUMERIC_PORT.matcher(candidatePort).matches()) {
                    port = candidatePort;
                }
            } else {
                host = authority.trim().toLowerCase(Locale.ROOT);
            }
        }
        if (host.isEmpty()) return Optional.empty();
        if (LOOPBACK_HOSTS.contains(host)) {
            if (!allowLoopback) return Optional.empty();
            return Optional.of(new HostPort("localhost", port));
        }
        if (Set.of("true", "false").contains(host)) return Optional.empty();
        if (host.matches("\\d+")) return Optional.empty();
        // Validate via HttpUriPattern if it looks like a standard hostname
        if (host.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
            Optional<String> verified = HttpUriPattern.host("http://" + host);
            if (verified.isPresent()) {
                return Optional.of(new HostPort(verified.orElseThrow(), port));
            }
            return Optional.of(new HostPort(host, port));
        }
        return Optional.empty();
    }

    private static String stripUserInfo(String rest) {
        int slash = rest.indexOf('/');
        String auth = slash >= 0 ? rest.substring(0, slash) : rest;
        int at = auth.lastIndexOf('@');
        return at >= 0 ? rest.substring(at + 1) : rest;
    }

    static String resolvePlaceholderOrValue(String value) {
        if (value == null) return "";
        Matcher m = PLACEHOLDER_DEFAULT.matcher(value);
        String resolved = m.replaceAll("$1");
        if (resolved.contains("${")) return "";
        return resolved.trim();
    }

    private static String baseName(String path) {
        String norm = path.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        return slash < 0 ? norm : norm.substring(slash + 1);
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return i;
    }

    private static String stripHashComment(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String stripYamlComment(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '#' && !inSingle && !inDouble && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
