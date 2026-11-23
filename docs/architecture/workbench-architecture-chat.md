# Workbench architecture chat

Open a published analysis and select **Open architecture chat** from Overview. Choose a starter or open **Task and symbol focus** to switch between exploring, reviewing a design and planning a repair. Use the context selector to choose the repository, module, or Spring context available in the capture. Enter a qualified symbol name or identity to retrieve a direct dependency sample. Open a citation to jump to its record. A finding's **Ask architecture chat** action attaches its report, finding and evidence bundle. Select **Open repair workflow** and then **Create captured repair proposal** to review recipe and model options. A generated candidate carries its syntax results and remains `UNVERIFIED` pending independent checks.

Ask “Bạn cho tôi biết context hiện tại của dự án này” or “What is the current project context?” for a local overview built from the selected snapshot. It includes captured graph counts and examples, then job status, build discovery and common gap reasons when those records are available. At repository scope it also shows up to four analysis context summaries from the same snapshot, counting each context separately. The overview reports coverage limits and returns citations without asking for uploads or suggesting additional analysis. Questions requesting design, repair or next steps still use the configured model when available. The renderer supports pipe tables, headings, lists and code blocks in model replies and saved conversations. [GFM table syntax](https://github.github.com/gfm/#tables-extension-)

## Configure a model

Copy [`.env.example`](../../.env.example) to an operator-owned file outside any analyzed repository. Local folder acquisition can capture files ignored by Git. Keep provider credentials in that external file or in the environment of the Workbench process.

| Setting | Value |
|---|---|
| `EVO_ARCH_CHAT_ENDPOINT` | Chat Completions base URL or full endpoint. The adapter appends `/chat/completions` to a base URL and preserves its path prefix. OpenAI uses `https://api.openai.com/v1`. A local gateway can use `http://127.0.0.1:8081/v1`. |
| `EVO_ARCH_CHAT_MODEL` | Model ID from the provider. |
| `EVO_ARCH_CHAT_API_KEY` | Bearer key if required by the provider. |
| `EVO_ARCH_CHAT_ALLOW_REMOTE` | Set to `true` to enable an HTTPS destination outside literal loopback. Otherwise the feature stays in local evidence mode. Sending a question transmits the question, recent history and selected evidence to that destination. |
| `EVO_REPAIR_AI_*` | Optional independent settings for the existing model-authored Java patch stage. Existing `evo.repair.ai.*` JVM properties take precedence when present. |

Both `EVO_ARCH_CHAT_` and `EVO_REPAIR_AI_` accept these suffixes. Choose the settings for the specific model and gateway.

| Suffix | Setting |
|---|---|
| `REQUEST_BYTES` | Maximum serialized provider request in bytes, from 16,384 to 8,388,608. Default `1048576` (1 MiB). This is a wire limit. The provider determines token count and the model's context window. |
| `MAX_OUTPUT_TOKENS` | Requested generation limit from 256 to 65,536. Default `8192`. |
| `TOKEN_PARAMETER` | `max_completion_tokens` (chat default) or `max_tokens` (repair default). |
| `JSON_MODE` | `true` sends `response_format`. Chat defaults to `false`, allowing the provider to return free text. Repair defaults to `true` and always validates the edit schema locally. |

Providers differ in path, request parameters and model context capacity. Check the [OpenAI Chat Completions API](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create), [Gemini's compatibility API](https://ai.google.dev/gemini-api/docs/openai), or [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/) as applicable. The [captured-source repair contract](captured-repository-repair.md#configure-the-repair-model) describes patch inputs.

Workbench reads these settings when it starts. In PowerShell, import the simple `NAME=value` file into the current process, then start Workbench from the same shell. Keep values unquoted.

```powershell
Get-Content -LiteralPath 'C:\private\evo-workbench.env' | ForEach-Object {
    if ($_ -match '^((?:EVO_ARCH_CHAT|EVO_REPAIR_AI)_(?:ENDPOINT|MODEL|API_KEY|ALLOW_REMOTE|REQUEST_BYTES|MAX_OUTPUT_TOKENS|TOKEN_PARAMETER|JSON_MODE))=(.*)$') {
        [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2], 'Process')
    }
}
```

If starting from the [shaded build](../../cli/README.md#build-and-launch), use the Workbench entry point with your repository and an existing analysis storage directory.

```powershell
$evoRepository = 'C:\projects\service'
$evoSnapshots = 'C:\private\evo-snapshots'
New-Item -ItemType Directory -Path $evoSnapshots -Force | Out-Null
& "$env:JAVA_HOME\bin\java.exe" -cp 'cli/target/evo.jar' com.evolution.backend.analysis.WorkbenchApiMain `
    $evoRepository 'urn:local:service' $env:JAVA_HOME 21 "$env:USERPROFILE\.m2\repository" $evoSnapshots 8080
```

Open [the local Workbench](http://127.0.0.1:8080), run **Analyze**, then open the chat tab.

## Evidence and authority

- Every turn is pinned to one graph, snapshot, and context. A finding attachment additionally matches the published report, finding and bundle digests. The evidence packet has its own digest and retains visible status, gaps, bounded samples, finding classification and condition-region identity. Finding source coordinates come from the existing captured-evidence replay.
- The browser saves conversations in local storage under the graph, context and finding identities. The history selector reopens earlier conversations for that scope across view changes and page reloads. The model may provide a short conversation title with its response; if it does not, the first question is used as a fallback. Existing conversations remain available. Storage is specific to the browser and Workbench origin. When storage is unavailable or full, Workbench reports that the conversation is only in memory. Each model request sends up to seven prior exchanges within a 48 KB history budget, plus the current question and selected architecture evidence. With a finding attached, Workbench selects **Include captured source code for this finding** by default. Clear it to omit freshly retrieved source. The API requires `includeSource: true` to send source. Recent chat history can include text from earlier responses.
- Source selection follows the finding's validated citations. The service verifies each captured file's SHA-256. It sends complete cited files when they fit, then tries line-numbered excerpts around cited spans and reports missing or omitted source. The model receipt identifies the files actually offered. The normal chat response contains source coordinates and receipts without the raw source packet. The separate repair workflow authorizes the exact source-bearing offer before creating a candidate.
- When the Workbench supplies its analysis job, the backend checks that its graph and snapshot match, and includes completion, mode, separate gap totals, and an available published readiness overview. Chat prefers a published plan in the job's mode. A historical fallback reports the profile actually used for the overview. The overview retains total candidate roots and modules, reports how many lie in recognized installed Node dependency directories, and previews up to 24 shallow nondependency roots and modules. Pending requirements, inventory ownership and graph semantics measure different populations.
- The evidence packet includes up to 50 captured types. When the job ledger is available, chat counts gap reasons over the first 2,000 records and reports the counted denominator and ledger total alongside up to eight example gaps. The ledgers may cover several analysis contexts. The query API and MCP bridge provide paginated access to the individual records.
- The backend supplies a system prompt that limits answers to software architecture, treats repository text and earlier replies as data, and asks for traceable evidence. A small set of direct off-topic prompts is recognized locally. For other phrasing, the model can return `OUT_OF_SCOPE` using the validated response contract. Workbench supplies the redirect text and excludes that exchange from future model context. General scope recognition remains model-dependent.
- Without a configured model, chat uses local evidence queries. Chat accepts plain text, Markdown and JSON inside a single code fence. It keeps an answer without accepted citations as `UNVERIFIED_ADVICE`, drops references outside the offered evidence and labels truncated answers. Request and response digests bind the exchange. Citation validation establishes that the references were offered for this turn. Proposed changes still need code review and independent verification.
- After displaying a model response, Workbench requests a review with the same pinned graph, context, finding and conversation. Local overviews and scope redirects skip this request. The reviewer response and receipt appear separately when available. Both model calls use the same provider and model, and neither response verifies the other. If the reviewer fails, the primary response remains available.
- Chat reads authorized architecture metadata through Workbench's loopback API. The patch workflow opens on request and Java candidates are generated through the documented repair route.

## Read architecture from an MCP client

Start the Workbench first. An MCP client can launch the read-only stdio bridge from the same shaded jar. For example, with the jar at `D:\evo\evo.jar` and Workbench listening on port 8080, configure the client's MCP server using:

```json
{
  "mcpServers": {
    "evo-workbench": {
      "command": "java",
      "args": ["-cp", "D:\\evo\\evo.jar", "com.evolution.backend.query.WorkbenchMcpMain", "8080"]
    }
  }
}
```

The client must have Java 21 on its path, or use the absolute path to its Java executable. The bridge implements the 2025-11-25 MCP stdio initialization and tool subset. `jobs_list` discovers jobs, `graph_get` exposes the published contexts for a graph digest, and `graph_query` retrieves bounded summary, entities, dependencies, edges, gaps, provenance or evidence. For paginated queries, use the continuation returned by Workbench. Responses retain Workbench's status and uncertainty; they are not claims of complete repository coverage. The bridge only connects to the specified local loopback port, provides no mutation tools, and does not automatically connect Workbench chat to other MCP servers.
