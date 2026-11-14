# M8.1 — Local asynchronous analysis and workbench API

**M8.4 planning note (2026-10-17):** this records implemented M8.1 behavior. The [readiness contract](m8-repository-readiness.md) plans durable jobs, resumable resource waiting, full acquisition and isolated builds through new versioned contracts. Its plan does not claim the process-local limits below are already removed.

**CONFIRMED scope:** the owner requested completion of M8.1 after the recorded M7/G6 preparation checkpoint. The repository had no separate M8 task file. This slice implements M8's asynchronous cancellable backend foundation over the existing canonical query/report boundary. The complete visual workbench and its acceptance gate remain subsequent work. **PROVISIONAL external contract:** `architecture.jobs:m8.1-v1`, additive to `architecture.http:m7.1-v1` and `architecture.query:m7.2-v2`.

## Ownership and implementation choice

`AnalysisJobs` owns bounded admission, job state, cooperative cancellation, execution deadlines, publication coordination and process-local history. `LocalAnalysisRunner` composes existing passive filesystem/JDK/Maven/JavaParser/pipeline/graph/M6 services. `AnalysisJobApi` strictly decodes HTTP operands; `ArchitectureHttpServer` retains loopback, Host/Origin, media, payload and request-deadline controls. `WorkbenchApiMain` is the explicit local composition root. Domain facts, metrics, policies, scoring and identities stay in `analyzer`.

The existing JDK HTTP adapter is retained: job scheduling is separate from transport and introduces no new framework dependency. A Spring Boot adapter remains replaceable when authenticated remote deployment, application routing or measured operational requirements justify it. This reversible transport choice neither freezes a remote deployment model nor changes M1, graph, metric, policy or score schemas/versions.

The source-selection and passive Maven-composition helpers formerly internal to `cli` now live in `backend.analysis`, with unchanged selection/provider behavior and explicit CLI imports. Both composition roots use these helpers. Backend dependencies are the three existing adapters; no dependency points from domain code toward backend/CLI. No target lifecycle, plugin, script, application, shell operand or remote download executes.

## Registered inputs

The registration-only M8.1 adapter accepts at most 32 immutable `Registration(Setup, Runner)` values. Job submission still accepts only a setup ID and idempotency key, never a class name, command or arbitrary provider configuration. M8.2 adds an explicitly enabled, separate [loopback connection boundary](m8-workbench-ui.md) for local source paths/public Git URLs and optional policy bytes; existing API constructors leave it disabled. Dynamic registrations use the same setup ceiling and remain immutable. Public setup metadata contains repository identity, target-release fallback, test inclusion, optional policy-byte SHA-256 and optional exact configuration-space identity. Local-source VCS remains `NOT_INSPECTED`; the public-Git acquisition adapter records its actually acquired commit.

`LocalAnalysisRunner.Input` supplies explicit repository/source/JDK/cache inputs, caller revision/dirty assertions, exclusions, optional cloned policy bytes and optional exact Spring scope/space/baseline evidence. Maven declarations retain authority over fallback language/platform/UTF-8 defaults. Missing platform/dependency/Spring evidence retains upstream outcomes and typed gaps. Supplying a configuration space never invents scope/order/container proofs. The launcher uses conservative caller-dirty provenance, excludes a nested graph-output directory and requires an existing, separate output directory.

Input files are acquired anew for each distinct submission. A setup identifies a selection policy, not an immutable source snapshot. The actual acquired snapshot, ordered exact classpath, platform and analyzer contracts own canonical identities. Retrying the same idempotency key returns its original job; a fresh scan requires a fresh key. Reusing a key with a different setup returns 409.

## State, progress and cancellation

States are `QUEUED`, `RUNNING`, `CANCELLING`, `SUCCEEDED`, `PARTIAL`, `EMPTY`, `FAILED`, `CANCELLED` and `TIMED_OUT`. Terminal states never change. Accepted creation returns 202; an idempotent retry returns 200. Success is operational analysis completion, not a healthy architecture or passed gate. Canonical `TERMINAL_FAILURE` remains `FAILED` even if a useful diagnostic structural graph exists.

Progress is a bounded ordered stage/event list: acquisition, platform, analysis, graph, optional policy, publication and finished. Stages are observations, not percentage estimates or ETA. Wall times are UTC instants; elapsed milliseconds use a monotonic clock from submission through completion, including queue time. Revisions describe state/stage transitions. UUIDs, events, durations and history cursors are operational metadata and never feed analysis, graph, finding, metric or score identities.

Queued cancellation removes admission without invoking the runner. Running cancellation sets a reason, interrupts the worker and remains `CANCELLING` until it acknowledges termination. Checkpoints surround acquisition/provider/pipeline/graph/policy stages; the frontend and domain query guards receive cancellation. Timeout begins when execution starts and uses the same mechanism with a distinct `DEADLINE_EXCEEDED` reason. Unexpected provider interruption is distinguished from service shutdown.

A provider that ignores interruption retains its worker slot and cannot publish a successful receipt or reports after cancellation. There is no hard preemption of arbitrary in-process provider code or fatal-JVM isolation guarantee. Shutdown rejects new jobs, cancels queued/running jobs and waits only its configured bound; an uncooperative worker may remain draining. The worker pool and deadline scheduler are owned by `AnalysisJobs`; the embedding owner must close both jobs and HTTP server. The launcher does so through its shutdown hook. Ordinary exceptions, linkage failures and assertion failures close jobs with sanitized codes; exception text/stack traces are not emitted by the job service.

## Publication and provenance

The job and query adapters must use the identical `GraphStore` and `PublishedReports` instances. Output validation rejects foreign graph/context/snapshot/analysis report provenance and foreign snapshot gaps. The runner preserves M6's emitted score/calibration/status without recomputing arithmetic in transport.

Verified immutable graph storage precedes report/receipt publication. Graph saves receive the cancellation token. Under the job monitor, a final cancellation check arbitrates against completion; all report rows are validated and capacity-checked before any member is added. Storage/publication failure cannot advertise a successful receipt. Cancellation is not a destructive rollback: a verified immutable content-addressed graph can remain in storage if cancellation wins after its save. No canceled job advertises that graph as its completed result and no report batch is partly published.

Receipts link exact graph/snapshot/analysis/context/report identities, acquisition and source-report hashes. `gapCount` is the unique union of graph gap identities and supplemental job/policy gap identities; `graphGapCount` and `jobGapCount` expose the overlapping populations separately. A declared COMPLETE output with any canonical or supplemental gap is downgraded to PARTIAL. Graph gaps and original payloads remain accessible through canonical context/evidence queries; supplemental full `CapabilityGapRecord` values are paginated separately. Policy/assessment gaps retain their own graph and upstream provenance even when they are produced after graph construction.

Acquisition may fail before any snapshot exists. Original typed acquisition problems retain provider/acquisition-digest references through `input-diagnostics`, with an exact `inputProblemCount`. No synthetic snapshot, semantic entity or `CapabilityGapRecord` with fabricated context is created. Once a real snapshot exists, existing normalizers supply the corresponding typed capability gaps. Empty gap pages on canceled/failed jobs cannot establish conformance; consumers must read the job state and artifact availability.

## Routes, bounds and replay

All paths use literal canonical lowercase UUIDs. Query strings/encoded aliases remain rejected. JSON is strict UTF-8 with duplicate/unknown keys, malformed types, overflowed/fractional pages, foreign cursors and unsupported media rejected. POST cancel requires `{}`; POST history/gaps/input-diagnostics accept the existing `{size, cursor}` page object. Successful job envelopes add `jobVersion` and SHA-256 of canonical `data`; this is a receipt digest, not a replacement semantic identity. Operational polls naturally change while work is active.

| Route under `/api/v1/analysis` | Method | Meaning |
|---|---|---|
| `/setups` | GET | Explicit registered selections |
| `/jobs` | POST / GET | Submit / first history page |
| `/jobs/history` | POST | Explicit paginated history |
| `/jobs/{job}` | GET | State, progress and canonical result references |
| `/jobs/{job}/cancel` | POST | Idempotent cancel; 202 while draining, 200 when terminal |
| `/jobs/{job}/gaps` | POST | Supplemental typed gaps after completion |
| `/jobs/{job}/input-diagnostics` | POST | Original typed acquisition problems after completion |

Existing graph discovery and all 23 query/eight report operations remain available unchanged. The snapshot-only M7 launcher explicitly advertises jobs disabled; job routes return 503 until an embedding application configures them. The packaged [OpenAPI](../../backend/src/main/resources/api/openapi.yaml) covers all twelve route templates.

**PROVISIONAL operational defaults:** one worker, four queued jobs, 64 retained job records, 15-minute execution timeout and one-second shutdown wait. Configured ceilings are four workers, 32 queued, 256 history records, one-hour execution timeout and five-second shutdown wait. Page sizes are 1–50; no accepted history is silently evicted. Queue/history exhaustion is explicit 503. Idempotency keys consume no additional history on retries. M7 transport/query/metric and provider limits still apply; these bounds are not a performance SLA.

History membership freezes at an initial high-water mark in submission order. Later submissions cannot cause duplicate/skipped members; each member's state remains live. Cursors bind process session, operation/job, page size, high-water mark and offset. Supplemental gap/input cursors cannot continue another job or population.

Jobs, retry keys, events and supplied M6 reports are process-local. File-store graph snapshots persist and remain independently queryable by their verified identities; restart/resume of jobs and durable report/history indexing are not implemented here. Concurrent source mutation, unavailable exact inputs and provider limits keep their original bounded outcomes. This slice makes no complete UI, remote-authentication, reference-hardware latency, external-corpus, independent-review, M8-closeout or Track A release claim.

Verification commands/results and remaining work are recorded in [M8.1 execution](../tasks/m8/01-analysis-jobs-and-backend.md). [Backend guide](../../backend/README.md) provides a runnable local workflow.

## Durable passive Workbench result reuse

This additive path applies to the durable Workbench after normal acquisition and passive preparation. The idempotency key still identifies an HTTP submission. A separate lookup key binds the registered setup and verified inputs: sealed inventory and source selection, platform and ordered classpath bytes, effective model and evidence inputs, Spring configuration, rules, analysis limits and the running application classpath. Git jobs still acquire the current pin and verify the selected capture before lookup. Native capture runners retain their existing validation and replay contracts.

A hit checks the original finished job and its canonical graph, reports and ledgers before completing a new job with a `REUSED` event. The original graph and report identities, diagnostics and completion state are preserved. Only named input-derived gaps are eligible for passive `PARTIAL` reuse; uncaptured input, unavailable dependencies and provider failures remain retryable. Conflicting published outputs for one input key disable that key and retain both receipts. The result index is written after the job receipt; authenticated older history remains readable without rewriting it.

The job submission accepts optional Boolean `force`. Retry keys bind the setup and that option. Receipts expose `reuseDecision` and, on a hit, the source job and verification time under `reuse`. A miss runs the ordinary pipeline. This cache replays an equal verified input; it does not update facts across different snapshots.

## Workbench publication and passive preview

The Workbench job commits the canonical graph file, policy reports and job ledgers. A raw page request starts one background preparation if the derived index is missing or needs cold verification. A missing index uses ordered typed rows and the existing adjacency indexes when the verified graph is in memory. The cold path stream-imports the canonical file or verifies the existing index. `PARTITION_INDEX_PREPARING` and `PARTITION_INDEX_BUSY` are retryable HTTP 503 outcomes. Preparation continues beyond the requesting connection's deadline, and pages are served once the index is verified. Raw reads authenticate pages, references and ordering under the existing partition contract. Reuse verifies the canonical graph, reports and job-ledger bytes.

The v4 job storage envelope references a compressed gap pack and a range index. Both are durable before the terminal receipt. Fixed row groups bound pagination work, and each range has its own SHA-256; full reuse also checks the pack and the reconstructed canonical gap-ledger digest. Gap identities, ordering, counts, and the distinction between job and graph gaps are preserved. v2/v3 ledgers remain readable without rewriting history; their pages stream the legacy ledger and retain only selected rows. Preparation of large ledger objects is cancellable outside the job-state lock. `FINISHED` marks the terminal receipt transition and its time still excludes the final journal commit.

`POST /api/v2/acquisitions/{revision}/overview` reads an already persisted passive plan for the sealed inventory. It returns bounded candidate root/module rows, their totals, and the observed selection/unknown/pending counts. This can render during analysis without reopening the inventory writer, selecting a build variant, or claiming semantic coverage. The full readiness plan and final graph remain distinct.
