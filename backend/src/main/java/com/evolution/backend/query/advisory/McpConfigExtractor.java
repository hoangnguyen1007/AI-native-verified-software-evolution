package com.evolution.backend.query.advisory;

import com.evolution.analysis.graph.GraphJson;
import com.evolution.backend.analysis.JsWorkspaceAliasResolver;
import com.evolution.backend.query.llm.LlmTransport;

import java.util.*;

/**
 * Deterministic {@code .mcp.json} / {@code mcp.json} extractor for the quarantined
 * {@code advisory.doc-link:v1} overlay.
 *
 * <p>Security invariant: extracts {@code MCP_SERVER}, {@code MCP_COMMAND} (redacted), and
 * {@code MCP_ENV_VAR} recording <strong>environment variable NAMES ONLY — NEVER values</strong>.
 */
public final class McpConfigExtractor {
    private static final GraphJson.Limits LIMITS = new GraphJson.Limits(512_000, 16, 4_096);

    public record ExtractionOutput(List<AdvisoryOverlay.AdvisoryNode> nodes,
                                   List<AdvisoryOverlay.AdvisoryLink> links,
                                   int redactedSecretsCount) {}

    private McpConfigExtractor() {}

    public static boolean isMcpConfigPath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase(Locale.ROOT);
        String name = lower.substring(lower.lastIndexOf('/') + 1);
        return name.equals(".mcp.json") || name.equals("mcp.json") || name.equals("mcp_config.json");
    }

    public static ExtractionOutput extract(String path, String contentSha256, String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return new ExtractionOutput(List.of(), List.of(), 0);
        }
        try {
            var root = GraphJson.object(GraphJson.read(JsWorkspaceAliasResolver.stripJsonc(rawJson), LIMITS));
            Object serversObj = root.containsKey("mcpServers") ? root.get("mcpServers") : root.get("servers");
            if (!(serversObj instanceof Map<?, ?> serversMap) || serversMap.isEmpty()) {
                return new ExtractionOutput(List.of(), List.of(), 0);
            }

            var nodes = new ArrayList<AdvisoryOverlay.AdvisoryNode>();
            var links = new ArrayList<AdvisoryOverlay.AdvisoryLink>();
            int redactedCount = 0;

            var sortedNames = new ArrayList<String>();
            for (Object k : serversMap.keySet()) {
                if (k instanceof String s && !s.isBlank()) sortedNames.add(s);
            }
            Collections.sort(sortedNames);

            int totalLines = rawJson.split("\\R", -1).length;
            for (String serverName : sortedNames) {
                if (!(serversMap.get(serverName) instanceof Map<?, ?> cfg)) continue;
                int line = findKeyLine(rawJson, serverName);
                String serverId = MarkdownAdrExtractor.advisoryId("MCP_SERVER", path + ":" + serverName, line);
                var meta = new LinkedHashMap<String, Object>();
                meta.put("serverName", serverName);
                if (cfg.get("transport") instanceof String tr && !tr.isBlank()) {
                    meta.put("transport", tr);
                } else if (cfg.get("url") instanceof String url && !url.isBlank()) {
                    var redUrl = LlmTransport.redactSecrets(url);
                    redactedCount += redUrl.redactedCount();
                    meta.put("transport", "http");
                    meta.put("endpoint", redUrl.text());
                } else {
                    meta.put("transport", "stdio");
                }

                nodes.add(new AdvisoryOverlay.AdvisoryNode(
                        serverId, "MCP_SERVER", serverName, path, line, Math.min(totalLines, line + 1), contentSha256, meta));

                // MCP_COMMAND
                if (cfg.get("command") instanceof String cmd && !cmd.isBlank()) {
                    var redCmd = LlmTransport.redactSecrets(cmd);
                    redactedCount += redCmd.redactedCount();
                    String cmdId = MarkdownAdrExtractor.advisoryId("MCP_COMMAND", path + ":" + serverName + ":" + redCmd.text(), line);
                    var cmdMeta = new LinkedHashMap<String, Object>();
                    cmdMeta.put("command", redCmd.text());
                    if (cfg.get("args") instanceof List<?> argsList) {
                        var safeArgs = new ArrayList<String>();
                        for (Object a : argsList) {
                            if (a instanceof String as) {
                                var redArg = LlmTransport.redactSecrets(as);
                                redactedCount += redArg.redactedCount();
                                safeArgs.add(redArg.text());
                            }
                        }
                        cmdMeta.put("argCount", safeArgs.size());
                    }
                    nodes.add(new AdvisoryOverlay.AdvisoryNode(
                            cmdId, "MCP_COMMAND", redCmd.text(), path, line, line, contentSha256, cmdMeta));
                    links.add(new AdvisoryOverlay.AdvisoryLink(
                            MarkdownAdrExtractor.advisoryId("MCP_RUNS_COMMAND", serverId + "->" + cmdId, line),
                            "MCP_RUNS_COMMAND", serverId, serverName, cmdId, redCmd.text(), "MCP_COMMAND",
                            path, line, line, contentSha256, "mcp-config-command"));
                }

                // MCP_ENV_VAR: strictly record environment variable NAMES ONLY — NEVER values!
                if (cfg.get("env") instanceof Map<?, ?> envMap && !envMap.isEmpty()) {
                    var envNames = new ArrayList<String>();
                    for (Object k : envMap.keySet()) {
                        if (k instanceof String name && !name.isBlank()) envNames.add(name.strip());
                    }
                    Collections.sort(envNames);
                    for (String envName : envNames) {
                        Object rawVal = envMap.get(envName);
                        if (rawVal instanceof String s && !s.isBlank()) {
                            // Count if a non-empty literal value was present and withheld
                            if (!s.startsWith("${") && !s.startsWith("$")) {
                                redactedCount++;
                            }
                        }
                        String envId = MarkdownAdrExtractor.advisoryId("MCP_ENV_VAR", path + ":" + serverName + ":" + envName, line);
                        nodes.add(new AdvisoryOverlay.AdvisoryNode(
                                envId, "MCP_ENV_VAR", envName, path, line, line, contentSha256,
                                Map.of("nameOnly", true, "valueWithheld", true)));
                        links.add(new AdvisoryOverlay.AdvisoryLink(
                                MarkdownAdrExtractor.advisoryId("MCP_REQUIRES_ENV", serverId + "->" + envId, line),
                                "MCP_REQUIRES_ENV", serverId, serverName, envId, envName, "MCP_ENV_VAR",
                                path, line, line, contentSha256, "mcp-config-env-name-only"));
                    }
                }
            }
            return new ExtractionOutput(List.copyOf(nodes), List.copyOf(links), redactedCount);
        } catch (RuntimeException invalid) {
            return new ExtractionOutput(List.of(), List.of(), 0);
        }
    }

    private static int findKeyLine(String text, String key) {
        String needle = "\"" + key + "\"";
        int idx = text.indexOf(needle);
        if (idx < 0) return 1;
        int line = 1;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
