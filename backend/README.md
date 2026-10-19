# Local architecture workbench API

M8.1 adds asynchronous analysis to the existing query/report API. JDK 21 is required. Analysis reads an explicit source directory, configured JDK and Maven2 cache through passive providers; it never executes the target build or application.

Build the standalone runtime using the trusted-module commands in the [CLI build guide](../cli/README.md#build-and-launch). Its shaded JAR also contains the backend launcher and all required adapters. With an existing output directory:

```powershell
java -Xmx2g -cp cli/target/evo.jar com.evolution.backend.analysis.WorkbenchApiMain C:/sources/application https://example.org/team/application.git "C:/Program Files/Java/jdk-21" 21 C:/Users/me/.m2/repository C:/analysis/graphs 8080 C:/policies/architecture.yaml
```

The final policy argument is optional (supply the port before it). A missing cache yields explicit dependency gaps. The output directory must exist and differ from the source root; a nested output directory is excluded from source capture. VCS is not inspected and the launcher records conservative caller-dirty provenance. The server binds only `127.0.0.1` and registers one setup named `local`. Ctrl+C closes server/job resources. Embeddings can register up to 32 named setups, exact Spring configuration evidence and alternative trusted runners.

```powershell
$base = 'http://127.0.0.1:8080/api/v1'
Invoke-RestMethod "$base/analysis/setups"
$job = Invoke-RestMethod "$base/analysis/jobs" -Method Post -ContentType 'application/json' -Body '{"setup":"local","idempotencyKey":"scan-001"}'
Invoke-RestMethod "$base/analysis/jobs/$($job.data.id)"
Invoke-RestMethod "$base/analysis/jobs/$($job.data.id)/cancel" -Method Post -ContentType 'application/json' -Body '{}'
Invoke-RestMethod "$base/analysis/jobs/history" -Method Post -ContentType 'application/json' -Body '{"size":10}'
```

Poll until the job reaches a terminal state. `CANCELLING` means the worker is still draining; it retains its slot. Retry the same key to recover the same job after an interrupted HTTP request; use a new key to capture changed source inputs. `PARTIAL`, `FAILED`, `CANCELLED` and `TIMED_OUT` cannot be interpreted as clean architecture. Even a `FAILED` analysis may expose a useful diagnostic graph with explicit gaps.

When `data.result.graph` is present, `GET /api/v1/graphs/{graph}` supplies the exact contexts and published report IDs. Use the [existing query/report calls](../docs/architecture/m7-query-http-api.md) for inventory, provenance, metrics, findings, witnesses, assessment, visual graphs and policy impact. Supplemental job gaps and original acquisition problems are available with POST `{}` to `/analysis/jobs/{id}/gaps` and `/analysis/jobs/{id}/input-diagnostics`. These pages retain explicit totals/continuations. No policy produces no assessment artifact, rather than a fabricated healthy score.

The API accepts JSON with strict finite limits and same-origin/Host controls. It has no remote authentication or arbitrary path/upload endpoint. Jobs, retry keys and M6 reports last for this process; the configured canonical graph files persist. History holds 64 jobs by default without silent eviction; a full queue/history returns 503. See the [M8.1 contract](../docs/architecture/m8-analysis-jobs.md) and packaged [OpenAPI](src/main/resources/api/openapi.yaml) for states, provenance, cancellation boundaries and exact limits.

The original `com.evolution.backend.query.QueryApiMain <snapshot.json> [port]` remains available for a read-only snapshot server with jobs explicitly disabled.

## Browser workbench

The [M8.3 workbench](../docs/architecture/m8-structure-and-configurations.md) adds Structure breadcrumbs/Back and member/source navigation; Configurations compares captured views with explicit unknown/partial states; Metrics & score exposes canonical definitions, supported scopes and score reasons. Missing policy offers **Configure rules & scoring**, including an editable cycle-check example. Insert an example only into an empty policy field, review it, connect and run again; it is not a complete architecture policy. Missing evidence can still withhold a score.

Recorded Spring worlds/solver examples require an operator-registered `LocalAnalysisRunner.SpringInput`; ordinary local/Git connections do not manufacture configuration-space evidence. Finding actions replay the captured Boolean/world model, not a running Spring application. Graph node/path controls provide keyboard alternatives to the canvas and export exact JSON or a PNG. New generic queries are `children`, `realizations`, `metric-definition`; published findings support `replay` with `identity` and optional Boolean `counterWitness`. Query version is `architecture.query:m8.3-v1`.

Open the server root, for example [http://127.0.0.1:8080](http://127.0.0.1:8080). The English workbench reads actual API results and has no demo fallback. Select a registered repository or use **Connect repository** with an absolute local folder or an HTTPS repository URL allowed by the endpoint policy. Optional policy YAML enables actual findings/assessment; without a policy no score is displayed. Then choose **Run analysis**. Several contexts require an explicit selection before viewing results.

The launcher explicitly enables the opt-in `/api/v1/analysis/connections` adapter; existing embedding constructors keep it disabled. Git 2.53+ captures the pinned full tree into streaming CAS without checkout or target execution. Public HTTPS hosts use checked/pinned endpoints and TLS verification; private access and SSH require explicit operator credentials/host keys. Submodule/LFS closure and typed acquisition requirements remain visible. Total repository size, file count and elapsed time are not admission ceilings. Native configuration/helpers/hooks/proxies/ambient authentication are disabled. See the [M8.4.1 acquisition contract](../docs/architecture/m8-streaming-acquisition.md) for recovery, limits and verification qualifications.

All navigation, graph zoom/fit/export, canonical search/pagination, evidence dialogs, theme and cancellation use real state. Missing Spring evidence, failed/partial analysis and withheld assessments remain visible. The launcher persists job history, connection selections, reports, inventory and graph files. Interrupted accepted work resumes when its setup is restored. Inventory pages and explicit continuation are available through the additive v2 routes in the acquisition contract; the existing visual views still consume the bounded Java semantic projection. Build the latest backend and shaded CLI using the module commands above before launching, so the JAR contains the repaired UI and vendored graph asset.

`POST /api/v2/acquisitions/{revision}/readiness` accepts `{section,size,cursor}` for the persisted passive readiness plan. It exposes roots/modules, candidate contexts, pending requirements, recipes, joint compilation groups, selection and unknown dimensions in bounded pages. Analysis binds the readiness digest, retains nested independent roots and preserves healthy-root source results beside a malformed independent POM. Native validation remains pending until the step-3 isolated executor is qualified. See the [M8.4.2 contract](../docs/architecture/m8-build-readiness-planner.md).
