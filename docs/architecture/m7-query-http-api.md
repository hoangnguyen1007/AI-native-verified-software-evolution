# M7.1 — Architecture query HTTP and JSON adapter

**CONFIRMED scope:** the G5 adjudication authorizes M7.1, the query REST/HTTP API and JSON serialization layer. **CONFIRMED implementation:** a local, read-only adapter in `backend` delegates to canonical M5 services and reads explicitly published M6 results. The external wire contract `architecture.http:m7.1-v1` is **PROVISIONAL** pending consumer acceptance. M1 identities, graph snapshot schema, query version `architecture.query:m5.3-v1`, metric formulas and assessment semantics remain unchanged.

## Boundary and implementation choice

`ArchitectureQueryApi` owns strict request decoding, operation dispatch and transport envelopes. `ArchitectureHttpServer` owns loopback binding, admission, headers, request deadlines and lifecycle. `QueryJson` reconstructs immutable analyzer requests; `CanonicalJson` remains the sole JSON byte-format authority. `PublishedReports` accepts immutable, provenance-compatible M6 artifacts supplied by an embedding application. It does not execute policies or analyze repositories over HTTP.

The existing backend was an empty Java JAR. The smallest implementation uses JDK 21 `HttpServer`, adding no production dependency. Spring Boot remains a viable replacement transport at M8; a new framework/runtime dependency and asynchronous job infrastructure are unnecessary for this slice. Replace this adapter when M8 needs authenticated remote deployment, job management, routing/static UI integration or measured operational requirements. No domain behavior moves into transport or storage.

M7.1 implements neither CLI/export formats nor an asynchronous analysis/job API, UI, runtime-impact guarantee or snapshot evolution. M7.2 owns CLI work; M8 retains the complete backend/workbench contract. Structural impact is the existing incoming potential-dependency traversal.

## Routes and discovery

The packaged [OpenAPI description](../../backend/src/main/resources/api/openapi.yaml) covers all five route templates. URLs use literal canonical `sha256:<64 lowercase hex>` digests. Query strings and encoded route aliases are rejected. Only the named HTTP methods are accepted.

| Route | Method | Data |
|---|---|---|
| `/api/v1` | GET | Versions, supported operations, transport limits and canonical query defaults |
| `/api/v1/graphs/{graph}` | GET | Graph/snapshot identities, exact contexts and explicitly published report IDs |
| `/api/v1/graphs/{graph}/queries/{operation}` | POST | Canonical M5 query result |
| `/api/v1/graphs/{graph}/reports/{report}` | GET | Evaluation identity/context/status/gaps, observed counts and available artifacts |
| `/api/v1/graphs/{graph}/reports/{report}/{operation}` | POST | Canonical supplied M6 artifact or bounded observed-row page |

POST queries are read-only: structured populations, realizations and limits are body data; there is no graph mutation, upload, filesystem path selection or target execution endpoint. A caller must know a graph identity, or obtain it from the local launcher. There is no ambient repository/graph discovery.

## Query input

The body is `{ "request": {...}, "arguments": {...}, "page": {...} }`. `request` is required; the other fields are optional only where applicable. Unknown fields, duplicate JSON keys/set members, wrong types, invalid UTF-8/surrogates and fractional/overflowed integer limits are rejected. No polymorphic class loading occurs.

Required request fields: `context`, `scope`, `projection`, `direction`, `relationshipKinds` (nonempty). Optional `graph` must equal the route identity. Defaults are `Population.all()`, `includeMust=false`, absent realization and canonical `Limits.defaults()`. A partial `limits` object inherits each omitted canonical default. `REALIZED` requires exactly one explicit world or nonempty Boolean assignment; all other projections require absent/null realization. Symbolic keys are upstream opaque variable IDs. Assignments do not expose configuration values.

Scope, direction and the five projections retain the M5 contract. Pages default to size 50; request `maxResults` may lower that. Cursors bind graph/context, query version, normalized filters, operation arguments and effective page size. They cannot continue a changed projection or search. Optional world/cursor/realization may be JSON null as in canonical M5 serialization. Empty search is allowed; omitted search means empty search. Other named string arguments must be nonblank.

| Operations | Allowed/required arguments | Page |
|---|---|---|
| `summary`, `provenance` | none | no |
| `entities` | optional `search` | yes |
| `entity`, `expand`, `reachability`, `impact` | `identity` | no |
| `edges`, `relationships`, `gaps`, `configurations`, `metric-catalog` | none | yes |
| `dependencies`, `explain` | `identity` | yes |
| `paths`, `shortest-path` | `source`, `target` | no |
| `cycles` | none | no |
| `evidence` | `reference` digest | no |
| `spring` | nonempty `kinds` set from the M5 Spring inventory allowlist | yes |
| `artifacts` | nonempty `families` set | yes |
| `metric` | `metric`, `subject` | no |
| `comparison-preparation` | other `graph` digest and `context` | no |

`impact` requires INCOMING. Metrics use the real `ArchitectureMetricsService` port with its own defensive limits; HTTP never computes a metric. Internal full-scope metric projections and policy predicates are not exposed as unbounded raw graph endpoints. Comparison preparation remains compatibility metadata, not a delta or Track B authorization.

Example query after obtaining the context from graph discovery:

```json
{
  "request": {
    "context": "<exact discovered context identity>",
    "scope": "TYPE",
    "projection": "BOUNDED_UNION",
    "direction": "OUTGOING",
    "relationshipKinds": ["graph.depends-on"],
    "limits": {"maxResults": 10}
  },
  "arguments": {"search": "Controller"},
  "page": {"size": 10}
}
```

## M6 reports and evidence

The embedding application constructs a `PublishedReports.Report(evaluation, metrics, assessment)` and calls `publish`. Metrics/assessment are optional; their graph/context/compilation/mapping/evaluation links must agree. Assessment requires its matching metrics. Finding bundles retain exact graph/context/provenance. Publication is idempotent, limited to 32 reports, 256 rule rows, 50 findings and a bounded canonical report payload. Retrieval additionally checks the selected graph partition and snapshot/analysis provenance. A report is explicitly supplied, not inferred from graph presence.

Report operations `rules` and `findings` accept only optional pages. `finding` and `bundle` require `arguments.identity`; `policy-metrics` and `assessment` accept no arguments/page. Results retain rule severity, quantifier, source spans/hashes, affected regions, supports, policy/evaluation identities, witnesses/counter-witnesses, gaps and score explanation. No source/configuration literals are copied by the M6 bundle contract. `NEVER` rule rows remain in the declared denominator; UNKNOWN cannot become conformance. Published row pages state `returnedTotal`, `evaluationStatus`, `gaps`, `truncated` and denominator meaning: observed rows do not establish an exact total of all possible findings when evaluation is incomplete. Cursors bind report content, operation and page size.

Report discovery advertises artifact availability. A missing artifact is an explicit 404, not a healthy score or empty finding list. The graph-only launcher publishes no M6 reports; an embedding application must supply them. The adapter preserves M6's emitted calibration/status, including its existing `PROVISIONAL` calibration marker; human approval of the baseline does not authorize the adapter to rewrite domain results.

## Output and failure semantics

Successful responses contain `apiVersion`, `queryVersion`, `resultIdentity`, `data`. `resultIdentity` is SHA-256 of the exact canonical UTF-8 JSON of `data`. For M5 `Result` this equals `Result.identity()`. Domain-specific versioned M6 identities remain inside the result or artifact listing; the transport digest does not replace them. Optional values are JSON null, identifiers are scalar strings, object keys/set values are canonically ordered, decimals use normalized exact notation. No response timestamp/random ID alters deterministic bodies.

Query `data` is the unchanged canonical `Result`: `status`, `operation`, `items`, `evidence`, typed `gaps`, `bounds`, `continuation`, `exactTotal`. Semantic PARTIAL/WITHHELD and operational CANCELLED/RESOURCE_LIMIT remain HTTP 200 domain outcomes. Exact totals never become page lengths. Found witnesses remain available after a canonical guard stops traversal. HTTP response size failure sends an error before publishing any successful JSON; it does not cut lists or evidence.

Errors contain only versions and `error: {code,message}`. They never echo request bodies, exception text, stack traces, host paths or secret values.

| HTTP | Codes / meaning |
|---|---|
| 400 | `INVALID_REQUEST`: malformed schema/filter/cursor/context/arguments |
| 403 | `FORBIDDEN_ORIGIN`: invalid Host or foreign Origin |
| 404 | `NOT_FOUND`, `GRAPH_NOT_FOUND`, `REPORT_NOT_FOUND`, `REPORT_ARTIFACT_UNAVAILABLE` |
| 405 | `METHOD_NOT_ALLOWED`, with Allow header |
| 406 / 415 | `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` |
| 413 | `REQUEST_TOO_LARGE`, `JSON_LIMIT_EXCEEDED` |
| 422 | `RESPONSE_TOO_LARGE`: narrow query/page |
| 500 / 503 | `INTERNAL_ERROR` / `GRAPH_STORE_UNAVAILABLE`, `BUSY` |

## Safety and budgets

G4 canonical ceilings are preserved: depth 20, 100,000 traversal steps, 50 results/witnesses, 100,000 vertices, 500,000 edges, 10,000,000 solver steps, 100,000 formula nodes and 500,000 auxiliary units. Defaults retain the lower canonical solver/auxiliary values. Transport cannot raise them. Existing query guards produce typed partial outcomes; preprocessing and metric budgets remain distinct.

**PROVISIONAL transport defaults:** 65,536 request bytes; 2,097,152 response bytes; JSON depth 32 and 10,000 values; four admitted requests, four queued tasks and bounded listener backlog; five-second operational deadline. A canonical preflight bounds serialization allocation; an exact final UTF-8 limit runs before headers. The server binds only `127.0.0.1`, checks Host and exact same Origin, enables no CORS, rejects content encodings, and sets JSON/nosniff/no-store/CSP headers. External deployment/authentication is outside this local adapter contract.

The deadline closes stalled body/read/write exchanges and supplies cancellation to cooperative domain work. Application shutdown stops the server, worker executor and deadline scheduler. HTTP client disconnects cannot always be detected before writing; the operational deadline still applies. A transport interruption or JDK HTTP-parser rejection may occur before a JSON envelope can be delivered. It is never a complete canonical result. There is no hard preemption of arbitrary embedding-provider code or global latency/memory SLA claim.

## Local use and verification

After the documented targeted build, a graph snapshot produced by `GraphSnapshotCodec` can be served on Windows:

```powershell
java -cp "backend/target/classes;analyzer/target/classes" com.evolution.backend.query.QueryApiMain C:/explicit/path/graph.json 8080
```

The launcher bounds/validates the snapshot, prints its canonical discovery URL, and stops with Ctrl+C. Invalid usage/startup exits nonzero with sanitized output. It never starts a target build/application. The in-process API/server also support dependency injection for future CLI/application consumers.

See the [M7.1 execution record](../tasks/m7/01-query-http-api.md) for actual checks and limits. Verification covers canonical parity for all 22 operations, explicit incompatible-world paths, pagination/reload, source redaction, M6 positive/negative/unknown rows, withheld assessment, strict input/caps, real HTTP origin/media/method handling, stalled bodies and lifecycle. Microfixture timings are not external-corpus or reference-hardware performance certification.
