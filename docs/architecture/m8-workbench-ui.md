# M8.2 — Live architecture workbench

Historical M8.2 boundary. [M8.3](m8-structure-and-configurations.md) extends it with direct structure ownership, configuration comparison, model replay, metric/score drill-down and graph controls. M8.2 omissions below describe that earlier slice.

[M8.4 readiness](m8-repository-readiness.md), planned 2026-10-17, supersedes these bounds as product requirements: full acquisition with continuation, broader policy-controlled hosts/authentication, isolated builds and per-language semantic coverage. The existing adapter behavior below remains unchanged until implementation.

**CONFIRMED requested scope:** operational English UI over real repositories, removal of fabricated/decorative product controls, responsive layout repairs and short user guidance. The workbench consumes existing canonical query/report services. It does not recompute metrics, policy verdicts or health scores.

## Input and connection boundary

`WorkbenchApiMain` explicitly enables `RepositoryConnections`; other API constructors preserve pre-registered-only behavior. Discovery advertises `repositoryConnectionsEnabled`. GET `/api/v1/analysis/connections` returns connection labels, setup IDs and the public host allowlist. POST accepts only `source` and optional policy YAML, returning a registered selection. The text decoder limits policy to 8,192 characters; request-byte limits still apply. Repeated source/policy selections reuse a setup. At most 32 setups and the existing bounded job/history limits apply. The provisional additive contract is `architecture.connections:m8.2-v1` ([ADR-006](../decisions/ADR-006-loopback-repository-connections.md)).

Local sources must be existing absolute directories; UNC, relative and symbolic-link ancestor inputs are rejected. Local identity derives from the explicit file URI, with conservative dirty/unspecified-revision provenance. Public sources accept repository-only credential-free HTTPS URLs on `github.com`, `gitlab.com` and `bitbucket.org`; ports, queries, fragments, encoded paths, traversal, SSH, credentials and arbitrary hosts are rejected. Private and other-host repositories can be cloned by the operator and selected locally.

Each public-Git job creates a fresh shallow default-branch checkout under the configured output directory. Git receives fixed argument arrays, an isolated environment, no ambient config/credentials/proxy, an empty template/hook directory, HTTPS-only protocol and redirect prohibition. TLS remains enabled. Target scripts/builds/submodules/LFS smudge filters never run. Actual `.git/HEAD` revision and acquired file hashes feed the passive runner. Symlinks retain link semantics and existing filesystem acquisition qualifications, rather than becoming invented regular Java files. Git-unavailable/download/checkout failures, two-minute timeout and monitored 128 MB/20,000-file limits produce explicit pre-snapshot acquisition diagnostics. Cancellation terminates the owned process tree. Monitoring can overshoot between checks; it is not a disk quota or OS sandbox. Checkouts are retained for inspection; no destructive cleanup is performed. Local analysis retains M8.1 source/provider bounds and typed gaps.

## UI behavior

- Actual connection metadata and paginated process-local job history populate the sidebar. A submission retains its idempotency key for a transport retry; a fresh accepted run uses a fresh key. Progress uses observed stages, with no estimated completion percentage. Cancelling remains visible until acknowledged.
- Result references discover exact graph contexts and report summaries. Several contexts require explicit user selection; scopes are never silently merged. Human-readable context labels use available canonical provenance only.
- Overview displays canonical observed inventory, separate assessment/evidence status and honest absent/withheld scores. Multiple reports are not merged. Structure defaults to explicit project-type population; referenced types are available through a labeled filter. Search and pagination remain canonical.
- Dependency graph uses locally vendored Cytoscape.js 3.30.2, bounded scope/projection requests, real node selection, zoom/fit and JSON export of the exact displayed projection. Union, conditional and uncertain dependencies are described without runtime certainty claims. Structure supplies keyboard-accessible details as a canvas alternative.
- Rules/findings read published reports and their actual classification/severity/status/bundles. Spring inventories and configuration evidence use the backend's exact categories. No bean annotations, profiles, endpoints or finding counts are synthesized.
- Impact first resolves exact candidate symbols, then queries incoming structural paths. It never claims a mutation/runtime simulation. Limitations, acquisition problems, provenance, spans, witness data and receipts remain inspectable and exportable as actual JSON. Bounded evidence inventories retain continuations.
- API failure displays a retry/error state; unavailable data never falls back to fixtures. Abort controllers, view generations and poll generations prevent stale results after tab/context/repository changes. Data enters the DOM via `textContent`, never untrusted HTML.

## Layout, dependencies and delivery

Semantic tab panels use `hidden` with an overriding CSS rule; panels stay in normal document flow. Wrapping flex/grid layouts, `min-width:0` and breakable labels prevent badges/long names from covering text. Native modal dialogs supply focus containment, Escape handling and return focus. Navigation supports arrow/Home/End keys, visible focus, skip link, reduced motion and responsive sidebar/theme states.

No CDN, external font request, fake social/docs/download link, account/PRO UI, automatic finder, AI prompt or simulated number remains. System fonts provide offline fallback. Cytoscape's license and SHA-256 pin are recorded in the vendor notice. Static CSP limits scripts and connections to self; inline style permission supports canvas renderer styles. Existing loopback/Host/Origin, request/response, time and admission protections apply to connection routes too.

Implementation verification is self-review. The task record owns exact commands/browser evidence. This repair does not close M8, certify all accessibility/performance targets, add durable job/report history, fetch dependency artifacts or implement private authentication, arbitrary Git hosts/revisions, witnessed configuration comparison or Track B.
