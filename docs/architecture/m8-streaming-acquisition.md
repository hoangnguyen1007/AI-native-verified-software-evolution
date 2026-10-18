# M8.4.1 — Streaming acquisition and durable storage

Date: 2026-10-18. **Implementation contract for step 1**, under [ADR-007](../decisions/ADR-007-isolated-build-and-polyglot-readiness.md) and the [M8.4 plan](../tasks/m8/04-comprehensive-repository-readiness.md). This document does not certify steps 2–6 or a milestone gate. Verification and remaining qualifications are recorded in the step-1 execution section of the plan.

## Boundaries and identities

`analyzer.acquisition.streaming` owns content handles, raw logical paths, inventory rows/manifests, selection identity, cursor access and the operational journal. `analyzer-filesystem` implements disk CAS, SQLite indexing, local/Git/ZIP providers and brokered HTTPS. `backend.analysis.RepositoryIntake` composes these for the CLI and workbench. SQL, host paths and native process objects do not enter these domain ports.

| Contract | Meaning and compatibility |
|---|---|
| `ContentHandle` | SHA-256 over original bytes and a checked `long` size; streaming/range reads |
| `LogicalPath` | Canonical Base64 of original path bytes, with unsigned byte ordering; no case folding or Unicode normalization |
| `repository-selection:m8.4-v2` | Repository, caller revision/dirty provenance and explicit relative exclusions; no machine locator |
| `repository-inventory:m8.4-v2` | Raw-name/kind/mode/population/disposition/content/target/encoding/ownership records, sorted externally by raw path; exact entry/file/byte/exclusion counts |
| M1 snapshot | Preserved preimage and public records. An optional v1 identity is computed with canonical UTF-16 path ordering for representable regular-file inventories; never renamed to mean a v2 inventory |
| `compatibility.selection:m8.4-v2` | A bounded legacy semantic view, its selected snapshot, request, counts and parent inventory digest. Its file population is not the full-root denominator |
| `graph.partition-reference:m8.4-v2` | Additive segment/cursor/adjacency port. Physical graph segmentation and cross-context relation envelopes remain step 6; existing M5 snapshots/codecs stay readable |

Every file is inventoried regardless of extension or source set. Test, generated, vendor, cache, resource, documentation and unknown populations remain separately classified; this classification does not assert semantic correctness or charset. Ownership candidates may remain empty until step 2. A `.evo` directory is not universally discarded: only the configured output selection and provider VCS metadata are excluded, with selection identity and ledger rows. CLI `scan` retains its established configured workspace exclusion.

Raw names unsupported by the host are read directly from Git objects or archives, without checkout/extraction. UTF-8 and ZIP IBM437 metadata retain explicit encoding evidence. Local raw names use URI byte representation; a local symlink name whose original encoding cannot be established is held as an acquisition requirement rather than normalized. V1-inexpressible paths/kinds remain visible in v2 and cannot be silently fed into a v1 consumer.

## Publication, recovery and resources

CAS writes stream to private staging, hash incrementally, force the file, then publish with create-if-absent hard-link semantics on the same filesystem. Existing content is checked and never overwritten. An unsupported atomic-publication filesystem produces an explicit requirement. Failed writes do not return published handles. Range and legacy materialization bounds are independent of repository admission.

The authoritative journal consists of immutable sequence-numbered commit records and SHA-256-addressed operation bodies. Each commit binds the preceding digest. Publication precedes the derived SQLite transaction, allowing an interrupted transaction to replay exactly once. Index corruption is quarantined, including SQLite sidecars, and rebuilt from the original operations. Missing links in the commit sequence or corrupt authoritative content withhold recovery; they are not normalized away. Foreign/newer metadata databases are rejected without overwriting their schema.

SQLite metadata uses a single writer, WAL, `synchronous=FULL`, file-backed temporary operations and an 8 MiB index cache. Read-only WAL connections expose already committed inventory while capture runs. The metadata store is local; a network filesystem is outside this adapter's deployment contract. Process-crash recovery is tested separately from hardware/filesystem power-loss guarantees.

Pin: **`org.xerial:sqlite-jdbc:3.53.4.0`**, Apache-2.0 driver, SQLite public-domain engine. Acquired JAR SHA-256: **`bcb1f51e36f940867e83342f9efbf5968ac44a6bef4d397bb4af7b17b45cd2fb`**. The POM pins the version; this digest records the tested binary. See the [upstream release](https://github.com/xerial/sqlite-jdbc/releases/tag/3.53.4.0), [license](https://github.com/xerial/sqlite-jdbc/blob/3.53.4.0/LICENSE), [WAL contract](https://www.sqlite.org/wal.html) and [corruption cautions](https://www.sqlite.org/howtocorrupt.html).

A root seal requires a durable `FRONTIER_CLOSED` transition, no pending/gap row, verified content, and successful canonical inventory/count reduction. Interrupted acquisition has an inventory revision and frontier, not an invented `SnapshotIdentity`. A crash after seal but before the job-complete receipt resumes publication. A repeated completed acquisition rechecks its seal/content; unavailable or corrupt evidence produces `SEALED_CONTENT_UNAVAILABLE` rather than a new complete claim. Historical seals remain immutable; corrupt authoritative bytes require recovery of the exact original content, not hand-editing evidence.

There is no total-repository byte/count/depth/elapsed eligibility ceiling in this lane. Per-page/message/metadata and legacy-worker bounds remain explicit. Disk reserve can be configured on CAS; disk pressure retains durable work and reports resource waiting. Local traversal is iterative and streams directory entries, with file/directory mutation checks and a final reconciliation pass. It does not claim an atomic OS filesystem snapshot. Hardlink paths are counted independently and may share content. Links are captured as links, with contained-target/alias checks and cycle obligations; long alias reasoning has a separate worker requirement.

Cancellation retains acquisition continuation. A user's analysis cancellation remains distinct from a resumable acquisition checkpoint. Running work retains its worker slot until acknowledgement; late output cannot publish a job success after cancellation.

## Git, authentication, LFS and archives

Native Git plumbing requires Git 2.53+ in this adapter baseline. The tested host uses `2.53.0.windows.3`. Requests record the actual version, repository/ref/selection and configured credential reference. Fetch tries a shallow selected ref, then full selected-ref fallback/retry; it does not fetch all historical revisions. After pinning, the entire selected tree is hydrated through `ls-tree`/`cat-file`, including raw names, modes and gitlinks. Native object checks and independent Git-object/CAS checksums protect blob capture. There is no checkout or target build execution.

Connection admission is based on transport and endpoint policy, not a hosting-vendor list. Every resolved address is checked; HTTPS connects use pinned addresses with hostname/TLS verification. Loopback/private/link-local/metadata endpoints are denied by default; internal hosts/ports and CA material require operator configuration. Redirects are rejected. Native global/system configuration, helpers, hooks, proxies and ambient authentication are disabled. Read-only credential references supply scoped headers through the broker, not URLs or process arguments. SSH requires configured identity/known-host files, strict host-key checking, pinned host addressing and no ambient agent or target-provided ProxyCommand. Actual SSH-server qualification remains separately identified in verification; missing configuration never falls back to an ambient identity.

V2 connections preserve the requested remote path rather than appending a hosting-specific `.git` suffix. Explicit `.git` URLs retain that path. Operator-configured HTTPS ports pass through the same endpoint policy instead of the former public-connection restriction. These selected coordinates are not a rewrite of previously published v1 repository/snapshot identities.

Retries have finite attempts, connect/idle controls and backoff. Progress can continue beyond the former 120-second ceiling. A native Git HTTP resume is not trusted as a byte-offset guarantee: interrupted `.temp`/`.prev` objects are preserved in an owned quarantine before retry; verified objects remain reusable. This is necessary because the [Git HTTP implementation](https://raw.githubusercontent.com/git/git/v2.53.0/http.c) can resume incomplete objects. Operational timing/retry order is separate from canonical inventory identity.

LFS pointers retain their raw blob reference. Actual object acquisition uses the standard HTTPS batch/download protocol, checks every endpoint and verifies declared size plus SHA-256 before attaching content. Validated CAS objects are reusable without a network call. Missing/invalid objects leave the pointer and obligation visible. Submodules retain exact gitlink commits and separately sealed child inventories; successful closure is projected under its original path with a closure reference. Cycles, missing configuration/commits and denied endpoints cannot close the parent frontier.

ZIP/ZIP64 intake stores the original archive and streams entries into CAS, without host extraction or an executable archive tool. The central directory, local entry census, sizes and CRCs must reconcile. Duplicate/traversing names, encryption, unsupported methods, truncation, bad links and malformed envelopes remain requirements. Other archive formats are retained input obligations, not complete empty snapshots. This does not certify arbitrary generator/compiler output; native build evidence starts at step 3.

## Product composition and migration

The workbench launcher enables durable job receipts, connection selections and M6 reports. Only active workers/queued execution slots remain in memory; additional accepted jobs stay on disk. Completion receipts are evicted from the active cache only after durable publication. History pages freeze membership through their high-water mark, survive restart and retain ordinary retry-key meaning. Service-interrupted work resumes when its registered setup is restored. Explicit v2 resume keeps the job/acquisition identity; an already published successful result is immutable. The older embedding constructor retains its bounded process-local contract for compatibility.

Report batches publish in one index transaction after CAS bodies are prepared. Typed reports are reconstructed through an embedding-selected record schema, constructors and canonical checksums; JSON cannot select a Java class. Decimal scale is canonically normalized as in M1. The legacy per-graph report-list API remains bounded and fails explicitly when a paged v2 consumer is required; persisted history is not evicted to satisfy that bound.

CLI:

```text
evo acquire DIR_OR_HTTPS_OR_ZIP --resume ACQUISITION_ID --json
evo inventory ACQUISITION_ID --limit 20 --json
evo inventory ACQUISITION_ID --limit 20 --cursor TOKEN --json
```

Omit `--resume` to allocate a new revision. Supply the same input/selection when resuming. `--exclude` and `--ref` bind the requested acquisition. `acquire` reports `NOT_EVALUATED` semantic coverage. `scan` first captures full inventory and then creates its separately identified legacy Java view. A view beyond its worker budget returns a continuation requirement, not a smaller root snapshot. Multi-root semantic planning remains step 2 and partitioned semantic/product scaling remains step 6.

Additive HTTP routes (same loopback transport safeguards):

- `POST /api/v2/acquisitions/{revision}`: manifest, operational receipt and bounded inventory page.
- `POST /api/v2/acquisitions/history`: live operational journal, 64-bit counts/sequence positions.
- `POST /api/v2/analysis/jobs/{id}/resume`: explicit failed/interrupted-job continuation.

Existing v1 graph/query identities, metrics and score formulas are unchanged. V1 job states describe the bounded semantic consumer; the v2 inventory receipt describes full selected-input acquisition. A complete inventory is not complete semantics, a build result or Track A acceptance.
