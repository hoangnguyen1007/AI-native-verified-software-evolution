# ADR-006 — Opt-in loopback repository connections

Date: 2026-10-16. **CONFIRMED human direction:** repair M8.2 into an operational workbench, support both local repositories and Git URLs, use English and remove fabricated product data. **PROVISIONAL implementation choice:** the bounded connection adapter below; this does not approve authenticated remote deployment or claim support for every Git hosting/authentication scheme.

## Problem and decision

The original workbench changed hard-coded repository labels and displayed invented findings, Spring annotations, endpoints, scores and history. M8.1 already provided real passive analysis, but accepted only pre-registered setups. A functional repository picker needs an explicit input boundary.

Add `RepositoryConnections` only to the local `WorkbenchApiMain` composition. GET/POST `/api/v1/analysis/connections` creates immutable setup selections; job submission, cancellation and canonical publication remain in `AnalysisJobs`. Existing embedding constructors keep connections disabled. Local absolute folders use passive providers. Credential-free HTTPS GitHub, GitLab and Bitbucket URLs create fresh shallow default-branch checkouts in an operator-selected output directory, recording the acquired commit and source hashes. Network acquisition runs in the cancellable job, not the HTTP request thread. No target build, hook, filter, submodule, application or script is executed.

The adapter isolates Git from ambient configuration, credentials and proxies, disables hooks/templates/redirects, permits HTTPS transport only, retains TLS verification, monitors time/input size and kills its own subprocess tree on cancellation. Before a source snapshot exists, failure produces typed acquisition diagnostics and a deterministic acquisition digest, never an invented snapshot or semantic gap context. This is bounded passive input acquisition, not an OS resource sandbox.

## Alternatives and consequences

| Alternative | Benefit | Disposition |
|---|---|---|
| Keep only startup registrations | Smallest attack surface | Retained for existing embeddings; insufficient for the requested UI connection workflow |
| Public archive downloads per hosting service | No Git executable | Requires host-specific branch/revision/archive-mode handling; replacement option if Git acquisition becomes unreliable |
| New JGit dependency | In-process Git API | Adds a production dependency and requires equivalent transport, configuration and resource isolation; not needed for this repair |
| Native Git with isolated, bounded arguments/environment | Existing tool, preserves exact checkout commit | Selected reversible adapter; Git must be installed and large/private/unsupported-host repositories use local checkout |
| Arbitrary URLs, SSH and ambient credential helpers | Broad reach | Deferred: requires a separately designed authentication/network boundary |

Connection labels and job UUIDs are operational presentation metadata; M1 identity preimages, graph schema, provider semantics, policy and score formulas are unchanged. The additive connection wire contract is `architecture.connections:m8.2-v1`; public Git acquisition is `source.public-git:m8.2-v1`.

## Verification and replacement triggers

Git command/environment behavior was checked against the official [clone reference](https://git-scm.com/docs/git-clone) and [environment/configuration reference](https://git-scm.com/docs/git). These document tool semantics; the repository tests and actual journeys establish only the implemented bounded behavior.

Offline controls cover local selection/reuse, forbidden URLs, absent Git, deterministic failed-acquisition receipts, strict API decoding, disabled embeddings, path redaction and static-origin controls. Browser journeys separately exercise actual local source analysis and the owner's public MiniChatServer repository; these are functional checks, not corpus benchmarks. Replace or extend the adapter when private authentication, other hosts, user-selected revisions, submodule acquisition, durable indexing or measured resource requirements are approved. Full M8/Track A acceptance remains open.
