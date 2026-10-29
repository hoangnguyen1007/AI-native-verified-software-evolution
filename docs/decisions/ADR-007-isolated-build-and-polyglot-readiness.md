# ADR-007 — Isolated build evidence and polyglot repository readiness

Date: **2026-10-17**. **Status: ACCEPTED HUMAN DIRECTION; Step 3 Core MVP accepted/implemented (2026-10-20); Step 5 / Task 1 CLOSED / CORE MVP (2026-10-29); broader M8.4 delivery status is in current state.**

## Human-approved Step-3 Core MVP amendment — 2026-10-20

**CONFIRMED direct human decision:** accept and close M8.4 Step 3 at Core MVP scope: the Hyper-V VM builds offline and exports generated code; wire the launcher into the pipeline and remove advanced security tests from this step's acceptance. This supersedes the original full-production Step-3 exit denominator. It creates no new task, milestone or security backlog, and does not relabel unexecuted controls as PASS.

The accepted implementation path is an explicit operator registration → pinned Hyper-V launcher → bounded offline Maven recipe inside a fresh worker → opaque export → SHA-256/CAS validation → durable publication/replay. Generated source bytes remain `PRODUCER_ASSERTION` evidence, distinct from independently validated semantic statements. Core MVP reports use their own scope/schema; they do not mint a full conformance token or open unrestricted repository execution. Host fallback, ambient credentials/source/home exposure, unbounded worker state, missing lineage and invented semantic success remain disallowed.

Alternatives: retain all 98 original acceptance obligations (superseded by the human's narrower delivery decision); claim they passed without observations (rejected); or accept the bounded demonstrated Core MVP with an explicit scope (accepted). Existing tests/evidence remain intact for history and regression; advanced campaigns are not Step-3 closure conditions. The broader M8.4 acquisition/language/coverage mission and existing Steps 4–6 are unchanged. This decision is delivery acceptance, not empirical proof of every sandbox threat or native build family.

## Human-approved Step-5 Task-1 Core MVP amendment — 2026-10-29

**CONFIRMED direct human decision:** in response to the explicit scope question, the owner selected **“Chấp nhận Core MVP và đóng Task 1”**. Accept and close M8.4 Step 5 / Bước 1 at the demonstrated Core MVP scope: 18 implemented adapters with protected Java and existing scoped native non-Java journeys; explicit unsupported/partial outcomes; durable replay; embedded-source and cross-provider paths; and tested authentication/publication mechanisms.

Full A01–A12 pack/method qualification and production validation-method registration are removed from **Task 1's closure criteria**. This supersedes the original broader Task-1 exit, creates no replacement task and does not convert category controls, test signers or unexecuted cases into production certification. The accepted path consumes captured/registered contexts; automatic ecosystem execution/model capture, general transformed/multilayer source maps and broader semantic capability remain recorded limitations, not newly certified features. `REQUIRED_UNQUALIFIED`, `PRODUCER_ASSERTION`, `qualified=false`, current gaps and live revocation/promotion guards retain their actual meanings.

Alternatives: retain the full original Task-1 exit (not selected); claim qualification without evidence (rejected); or accept the demonstrated bounded implementation with explicit limits (selected by the human). No new code, VM campaign, certificate or unrestricted executor is required for this acceptance. Step 5 remains in progress; Bước 2–3 and later milestone gates retain their scopes. The comprehensive coverage mandate and execution safety remain in force. [Execution record](../tasks/m8/04-step5-step1-execution.md) owns the evidence; [current state](../current-state.md) owns the next task.

## Authority and problem

The owner explicitly requested M8.4 planning, replacement of the blanket target-build prohibition with a prohibition on direct host execution, removal of artificial repository acquisition ceilings, comprehensive module/generated-code handling, and maximum evidence coverage. In the follow-up choice the owner explicitly selected **semantic engines for non-Java languages in M8.4**, rather than inventory-only polyglot support.

This supersedes the 2026-09-23 Java/Spring-plus-polyglot-inventory delivery boundary and the ASSESS/HOLD status of target build execution for this milestone. It does not retroactively change the behavior or acceptance evidence of M1–M8.3.

Source inspection found coupled acquisition, file-selection, memory, graph-store and job-duration constraints; raising the Git limit alone cannot meet the request. Native build execution is necessary for some generated sources, exact dynamic build models and compiler-backed language evidence, but build success cannot certify semantic correctness.

## Decision

1. Target build scripts, plugins, processors, package hooks and execution-capable model/index requests may run only through an isolated executor with explicit input, resource, network, side-effect and provenance policy. They must never run directly on the developer/controller host. Static-first readiness and build evidence are both first-class paths. Comprehensive mode requires native build validation for selected buildable contexts or a compatible validated capture; static success alone does not waive it. A missing/unverified sandbox cannot fall back to host execution.
2. Comprehensive mode has no fixed total repository-size, file-count or total elapsed-time eligibility ceiling. Streaming content storage, durable discovery/work queues, per-module/context scheduling, backpressure and resumable resource waiting replace early global rejection. Per-worker isolation and bounded API/IPC/solver controls remain necessary.
3. SE121/M8.4 now includes language-specific semantic engines and evidence-backed cross-language architecture relationships. Inventory and syntax recovery are not substitutes for semantic deliverables. JavaParser remains the approved primary Java frontend; new language providers preserve neutral boundaries and receive their own catalog/version/oracle checks.
4. Full selected-snapshot accounting covers all components/source sets and build roots. Generated outputs, binaries, schemas, resources and non-source files retain separate classification and provenance. No metrics denominator may shrink silently to improve completeness or health.
5. Implement through the [six-step M8.4 plan](../tasks/m8/04-comprehensive-repository-readiness.md) and [architecture contract](../architecture/m8-repository-readiness.md). Durable CAS/journal/partitioned graph, disposable full VM baseline, native build/compiler adapters and SCIP-family interchange are **provisional technical choices**, not human-certified technology or accuracy claims.

## Alternatives and consequences

| Alternative | Disposition |
|---|---|
| Continue passive-only forever | Rejected as a ceiling; retained as baseline/fallback when build evidence is unnecessary or unavailable |
| Build directly on host after static inspection | Rejected; inspection does not prove arbitrary code safe |
| Always build everything first | Rejected as default; progressive evidence and dependency-aware recipes reduce work, root builds remain available when required |
| Remove all resource controls | Rejected; causes exhaustion instead of coverage; move controls to bounded workers and resumable scheduling |
| Inventory-only non-Java | Superseded by explicit human scope choice |
| One generic parser for all semantics | Rejected; use native engines, qualified bridges and explicit dynamic uncertainty |

This is a substantial cross-module capability expansion. Completion requires implementing engines and end-to-end storage/execution paths, not editing constants or labeling unknowns complete. No finite test campaign establishes every future language, inaccessible repository or unlimited physical resource. Published coverage claims remain scoped to demonstrated capabilities and closed denominators; the extension architecture must not turn the initial language catalog into a permanent whitelist.

The original M1 Java identities, accepted metrics, historical snapshots and adjudication records remain valid within their original scopes. New inventory/language/graph/job contracts require explicit versioning and compatibility tests; no silent cross-context merge or numerical score change follows from this ADR. Build reproducibility and deterministic analysis replay are separate obligations.

## Supersession and gates

ADR-003's progressive evidence architecture remains in force; its blanket normal-analysis execution restriction is superseded for the authorized isolated provider. ADR-005's no-target-execution/no-extra-language wording remains historical to M4 Universal v2. ADR-006's three-host/bounded public connection is the implemented M8.2 baseline to be expanded, not the final product limit.

The planning request authorizes governance/plan changes now. No production code, sandbox provisioning, target build, source upload, cloud spend, commit or push occurs in this planning task. Future implementation uses standing scope authorization; actual execution still requires a validated executor and operator-configured allocation/access policy, not repeated per-module permission questions.

Targeted readiness checkpoints and repeated controlled trials are permitted during implementation. Full acceptance uses a preregistered campaign with sufficient repetitions and immutable results. No one-shot restriction may invalidate determinism/performance evidence. M9/M10 acceptance and the human Track B gate remain intact.

Replacement triggers: VM operational cost/boot overhead measured material; gVisor/native-tool incompatibility; metadata-store contention; indexer category/version gaps; unstable compiler API; incorrect canonical mappings; unresolved unbounded-memory consumers. Replacements must preserve evidence and pass equivalent controls. Scope decision is confirmed; isolation, performance, semantic accuracy and worldwide coverage are not certified by approval.

Sources and unresolved implementation validations: [research record](../research/2026-10-17-m8-readiness-design.md).
