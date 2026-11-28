# Evo MCP Ecosystem Quickstart (`Streamable HTTP · 2025-11-25`)

Connect AI coding agents (**Claude Code**, **Cursor**, **Antigravity**, **Codex**, **Windsurf**, or any generic MCP client) directly to the **Online Evo Architecture Workbench** over **Streamable HTTP (`POST /mcp`)**.

Evo's MCP server is strictly **read-only**, exposes **21 architecture intelligence tools** and **6 `evo://` resources**, enforces `Host`/`Origin` DNS-rebinding guards, scopes authentication via SHA-256-hashed Personal Access Tokens (PATs), and applies **Shannon-entropy secret redaction** before returning any captured source or search snippet.

---

## 1. Launch the Online Evo Web App in One Command

### Option A — Docker Compose (Recommended)

From the repository root (uses [docker-compose.yml](../docker-compose.yml) and [Dockerfile](../Dockerfile)):

```bash
docker compose up -d
```

### Option B — Standalone `docker run`

```bash
docker run -d \
  --name evo-workbench-online \
  -p 127.0.0.1:8080:8080 \
  -e EVO_ONLINE_MODE=true \
  -v evo_online_state:/data/evo-store \
  evo-workbench-online:latest
```

> **Host Safety Guarantee:** The container runs as non-root UID/GID `10001:10001` with `EVO_ONLINE_MODE=true` (`--online`). Local filesystem checkouts are disabled (`403 LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE`), and untrusted target builds are **never** executed on the host.

Verify the server and MCP metadata are healthy:

```bash
curl -fsS http://127.0.0.1:8080/api/v1/health
```

The Workbench UI is now live at `http://127.0.0.1:8080`. You can also click the **MCP · AI Agent** button in the Workbench header at any time to copy pre-populated configs or mint a scoped `mcp:read` PAT.

---

## 2. Connect Your AI Coding Agent (`POST /mcp`)

Default endpoint: `http://127.0.0.1:8080/mcp` (or `https://<your-evo-host>/mcp` behind TLS reverse proxy).
Protocol version: `2025-11-25` (Streamable HTTP JSON-RPC 2.0 over `POST /mcp`, with optional `DELETE /mcp` session teardown).

> **Multi-User / Remote Deployment Note:** When `EVO_MULTI_USER=true`, include `Authorization: Bearer ${EVO_MCP_TOKEN}` using a read-only `mcp:read` PAT minted in **Workspace Settings → API & MCP Tokens** (or `POST /api/v1/auth/tokens`). In default single-user loopback mode (`EVO_MULTI_USER=false`), the `headers` block may be omitted.

### Claude Code (`.mcp.json` or CLI)

Add `.mcp.json` to your project root:

```json
{
  "mcpServers": {
    "evo-workbench": {
      "type": "http",
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${EVO_MCP_TOKEN}"
      }
    }
  }
}
```

Or register via the Claude Code CLI:

```bash
claude mcp add --transport http evo-workbench http://127.0.0.1:8080/mcp
```

### Cursor (`.cursor/mcp.json`)

```json
{
  "mcpServers": {
    "evo-workbench": {
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${EVO_MCP_TOKEN}"
      }
    }
  }
}
```

### Antigravity (`~/.gemini/antigravity/mcp_config.json`)

```json
{
  "mcpServers": {
    "evo-workbench": {
      "serverUrl": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${EVO_MCP_TOKEN}"
      }
    }
  }
}
```

### OpenAI Codex (`~/.codex/config.toml`)

```toml
[mcp_servers.evo_workbench]
url = "http://127.0.0.1:8080/mcp"
# Uncomment when EVO_MULTI_USER=true:
# bearer_token_env_var = "EVO_MCP_TOKEN"
```

### Windsurf (`~/.codeium/windsurf/mcp_config.json`)

```json
{
  "mcpServers": {
    "evo-workbench": {
      "serverUrl": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${EVO_MCP_TOKEN}"
      }
    }
  }
}
```

### Generic Streamable HTTP (`2025-11-25`) Handshake via `curl`

```bash
# 1. Initialize and capture the Mcp-Session-Id response header
SESSION_ID=$(curl -si -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-11-25",
      "capabilities": {},
      "clientInfo": { "name": "curl-client", "version": "1.0.0" }
    }
  }' | awk -F': ' 'tolower($1)=="mcp-session-id"{gsub(/\r/,"",$2); print $2}')

# 2. List all 21 registered read-only tools
curl -fsS -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 3. Call architecture_brief (auto-binds latest analyzed graph & context)
curl -fsS -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"architecture_brief","arguments":{}}}'

# 4. Read an evo:// resource
curl -fsS -X POST http://127.0.0.1:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  -H "Mcp-Session-Id: ${SESSION_ID}" \
  -d '{"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"evo://score-v2"}}'

# 5. Terminate session cleanly
curl -fsS -X DELETE http://127.0.0.1:8080/mcp \
  -H "Mcp-Session-Id: ${SESSION_ID}"
```

> You can also fetch machine-generated client snippets directly from `GET /api/v1/mcp/setup`.

---

## 3. Registered Read-Only MCP Tools (`21` Tools)

All 21 tools are registered canonically in [McpToolDispatcher.java](../backend/src/main/java/com/evolution/backend/query/mcp/McpToolDispatcher.java). When a default graph is auto-bound in the MCP session, `graph` and `context` arguments are optional.

| # | Tool Name | Purpose |
|---|---|---|
| 1 | `jobs_list` | List recent analysis jobs with graph digest, snapshot ID, and completion state. |
| 2 | `graph_get` | Read published graph contexts and policy report identities for a graph SHA-256. |
| 3 | `graph_query` | Query bounded architecture evidence (`entities`, `edges`, `dependencies`, `impact`, `cycles`, `paths`, `shortest-path`, `spring`, `realizations`, `artifacts`, `gaps`, `metrics`, `visual-graph` with optional `groupBy=COMMUNITY`). |
| 4 | `architecture_insights` | Compute Tarjan SCC dependency cycles, minimal cycle-breaking edges, Martin instability/abstractness/SDP, blast radius, hotspots, Leiden communities, misplaced types, and cross-community bridges. |
| 5 | `get_architecture_score` | Compute the 7-dimension Architecture Health Score v2 with independent Analysis Confidence and evidence witnesses. |
| 6 | `architecture_brief` | One-call compressed executive overview: Score v2, confidence tier, Leiden emergent communities, misplaced types, tangles, hotspots, and quarantined advisory link counts. |
| 7 | `policy_findings` | Read published architecture policy rules, `MUST`/`MAY`/`UNKNOWN` findings, and assessment scores (`summary`, `rules`, `findings`, `assessment`). |
| 8 | `job_coverage` | Read completion state, obligation breakdown (passive vs isolated execution), and top capability-gap reasons for an analysis job. |
| 9 | `repository_score` | Read the repository engineering practices rubric score and evidence paths for a graph or job. |
| 10 | `list_captured_files` | List captured files across Java and polyglot languages with optional path glob/substring and language filters. |
| 11 | `read_captured_source` | Read up to 250 line-numbered, secret-redacted lines from a captured repository file. |
| 12 | `search_captured_source` | Pre-redacted SQLite FTS5 BM25 (or streaming regex) search across captured text files returning `file:line` snippets with provenance, `omittedFiles`, and `redactedSecretCount`. |
| 13 | `read_markdown_section` | List Markdown headings or read one exact heading section on demand with `startLine..endLine`, SHA-256 provenance, and secret redaction. |
| 14 | `inspect_advisory_links` | Inspect quarantined `advisory.doc-link:v1` links (Markdown/ADR, SQL DDL ↔ JPA, Terraform, JS workspace aliases, MCP configs). Strictly `ADVISORY_UNVERIFIED`. |
| 15 | `inspect_codeowners` | Inspect passive `CODEOWNERS` rules from the captured snapshot and resolve owners for a path. |
| 16 | `repo_map` | Compute a deterministic Hybrid PPR + BM25 + Trigram RRF (`ppr-bm25-trigram-rrf-v2`) repository map with declarations, architectural edges, endpoints, and capability gaps. |
| 17 | `suggest_policy` | Auto-detect Clean, Hexagonal, or Layered architecture rules and return compiled `architecture-policy:v1` YAML. |
| 18 | `repair_options` | Inspect applicable deterministic repair recipes, captured source eligibility, and isolated runner readiness for a diagnosed finding. |
| 19 | `connections` | Inspect cross-service HTTP, gRPC, and async event bridge relationships in the selected snapshot or context. |
| 20 | `vertical_trace` | Trace full-stack execution and data flow across 8 architectural layers (Frontend, API Route, Spring Controller, Spring DI, Service, JPA Entity, plus quarantined `ADVISORY_UNVERIFIED` SQL Table and Terraform Resource overlays). |
| 21 | `trace_slice` | Compute a lazy, pre-redacted intra-method CFG/DFG/CDG program slice for a captured Java file at a 1-based line number (`SOURCE_SYNTAX`, zero secret leakage). |

---

## 4. Registered `evo://` MCP Resources (`6` Resources)

| URI | Name | MIME Type | Description |
|---|---|---|---|
| `evo://score-v2` | Architecture Health Score v2 | `application/json` | 7-dimension Architecture Health Score v2 with independent Analysis Confidence. |
| `evo://report` | Architecture Insights Report | `text/markdown` | Executive Markdown report covering tangles, hotspots, SDP, and Leiden communities. |
| `evo://gaps` | Capability Gaps | `application/json` | Active capability gaps and evidence obligations for the default graph. |
| `evo://hotspots` | Architecture Hotspots | `application/json` | Top blast-radius and complexity hotspots plus misplaced types. |
| `evo://cycles` | Dependency Cycles & Tangles | `application/json` | Tarjan SCC dependency cycles and minimal cycle-breaking edge candidates. |
| `evo://spring-summary` | Spring Wiring Summary | `application/json` | Spring bean candidates, producers, endpoints, and conditional realizations. |

---

## 5. Security Posture, PAT Scopes & `Origin` Protection

### Scoped Personal Access Tokens (PATs)

Enforced in [ArchitectureQueryApi.java](../backend/src/main/java/com/evolution/backend/query/ArchitectureQueryApi.java) (`isPatAuthorized`) and stored as **SHA-256 hashes only** (plaintext is revealed once at mint time and cleared when the modal closes):

| Scope | Allowed Surface | Recommended Usage |
|---|---|---|
| `mcp:read` | `POST`/`DELETE /mcp`, `GET /api/v1/mcp/setup`, `GET /api/v1/auth/me`, and explicit read-only graph, insights, score, policy-findings, coverage, source-read, repo-map, and SARIF endpoints. Zero mutations and zero live-LLM egress. | **All AI coding agents** (Claude Code, Cursor, Antigravity, Codex, Windsurf). |
| `api:read` | Everything in `mcp:read`, plus read-only REST/SSE routes (`/api/v1/analysis/connections`, `/api/v1/analysis/jobs/{id}/events`, `/api/v1/evolution/*`, `/api/v1/repairs/jobs/*`). Zero mutations. | Dashboards, audit scripts, and read-only CI reporting jobs. |
| `api:write` | All authenticated API endpoints (user role & tenant ownership checks still apply), including connecting repositories and submitting analysis jobs. | Trusted CI/CD runners only (see [CI/CD Integration Guide](ci-integration.md)). |

### `Origin` & DNS-Rebinding Protection

Enforced in [ArchitectureHttpServer.java](../backend/src/main/java/com/evolution/backend/query/ArchitectureHttpServer.java) (`isAllowedMcpOrigin`):

- Every `/mcp` request must present a single valid `Host` header matching the server's bound host/port (or wildcard container bind).
- Native IDE/CLI MCP clients that omit the browser `Origin` header are accepted.
- Browser-originated requests that send an `Origin` header are accepted **only** for loopback HTTP origins (`http://127.0.0.1[:port]`, `http://localhost[:port]`, `http://[::1][:port]`).
- Any untrusted cross-origin or multi-`Origin` request is rejected immediately with **HTTP `403 FORBIDDEN_ORIGIN`** before JSON-RPC dispatch.
- Sessions are bounded (`MAX_SESSIONS = 256`, `4`-hour TTL, LRU eviction) and pinned to the authenticated `ownerUserId` in multi-user mode.

### Shannon-Entropy Secret Redaction & Epistemic Boundaries

- **Pre-Redacted Source & Search:** `read_captured_source`, `search_captured_source`, `read_markdown_section`, and `trace_slice` pass through `LlmTransport` secret redaction (config/YAML secret scrubbing, 11 standard secret patterns, and deterministic **Shannon entropy `>= 4.2` bits/char** detection across `24–512` character mixed-class tokens).
- **Strict vs Advisory Separation:** Canonical graph facts are SHA-256 content-addressed and strictly separated from quarantined `ADVISORY_UNVERIFIED` links (`inspect_advisory_links`, SQL/Terraform overlays).
- **Zero Host Execution:** Repository files are treated as untrusted data; MCP queries never execute target builds, scripts, or compiler hooks.
