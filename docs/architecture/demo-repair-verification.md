# Demo Step 3 — Repair verification

From the pinned `client.no-store` proposal, **View verification** opens a local PR preview with the patch, affected region and check results. Workbench and `evo verify-repair` use the same verifier. Step 3 is complete for the sealed demo fixture and its two `dev` configurations. [Repair proposal](demo-repair-proposal.md) · [Demo scope](../decisions/ADR-011-full-pipeline-demo.md)

## Evidence and verdict

`POST /api/v1/graphs/{graph}/reports/{report}/repair-verification` takes the finding `identity`, `bundle`, `context`, `diagnosisInput`, and `proposal` digest in `arguments`. The API regenerates the proposal from canonical findings and returns `409` for a stale scope, diagnosis or proposal. Two optional operands, `candidateReport` and `controlReport`, identify published reports in the same Workbench store. The request accepts identifiers only.

| Check | Pass condition |
|---|---|
| Baseline and patch | The registered four-file inventory derives the exact baseline snapshot. The patch hashes to the proposal's digest. The plan derives the candidate and control snapshot identities by changing only `Client.java`. |
| Candidate finding | The published report belongs to the exact candidate snapshot and matches the pinned policy compilation. Strict snapshot comparison proves `RESOLVED` for the pinned finding over its modeled region. For the sealed fixture, the verifier can add the independently authenticated [binding census](demo-finding-transition.md#closed-census-for-the-pinned-repair) as the scoped negative operand. |
| Negative control | The variant keeps `Store store` and adds an unrelated comment. Its report belongs to the expected control snapshot. A positive replayed witness for the same policy finding rejects the mutation. A proved `PERSISTED` transition also establishes this check. |
| Isolated build and Spring checks | A registered operator adapter supplies a protected `EvidenceVerifier.VerifiedClaim` for this proposal and plan. The exact check set covers build, `dev` off, and `dev` on for baseline, candidate and control. |

All checks must pass for `VERIFIED`. A positive candidate finding witness, a proved persistence transition or a mismatched operand can produce `REJECTED`. The witness proves presence in its own captured model. Proving that the finding is gone requires the closed comparison across snapshots. Missing, partial or incompatible evidence yields `UNVERIFIED` unless another check already rejects the proposal. The result carries canonical comparison identities and bounded reason codes with a truncation flag.

The plan identifies the immutable inputs and required checks for an isolated worker. The general `WorkerEvidence` integration accepts a typed claim issued through the existing receipt verifier and binds it to the proposal, plan and scoped method. An HTTP request cannot register an authority, provide a command, or schedule a build. [Isolation contract](m8-isolated-execution.md)

The isolated test recipe must open the Spring context with `dev` off and on for all three trees. With `dev` off, `Client` stays inactive. With `dev` on, the baseline and negative control create `Client` through the `Store` dependency. The candidate must create `Client` while retaining the `Store` bean and removing that constructor dependency. The protected receipt needs the test tool identity and evidence for the nine required builds and profile checks. Native compilation by itself cannot issue this result.

## Reproduction

1. Use `M6PolicyIntegrationTest#repairVariantsRetainTheirExactChangedSourceInPassiveGraphs` with `-Devo.repair.fixture.output=<new scratch directory>` to create the baseline, candidate and control graphs with the current Java frontend. It uses the same authored fixture and preserves the source digests. Use the registered demo policy v2, which matches the exact bean names `client` and `store` within each snapshot. This keeps the policy compilation identical across reanalysis as snapshot-specific bean IDs change. Policy v1 remains available for replay of the earlier captured proposal. [Policy fixture](../../backend/src/test/resources/demo-client-no-store.yaml) · [Prior capture](demo-finding-diagnosis.md#verification-entry-points)
2. Build the packaged CLI and run the browser command from the repair proposal record. Pass the newly generated baseline graph as the baseline argument, then pass the candidate and control graph files after the policy argument. The test launcher publishes reports with the same policy. The browser verifies the preview, then exercises `evo verify-repair` with the same evidence. [Build and browser commands](demo-repair-proposal.md#reproduction)
3. In a local Workbench, download the **repair proposal** JSON. Use the verification command below on the same server and supply `--candidate-report` and `--control-report` with published report digests when available. With `--json`, `data.verdict` holds the status and `resultIdentity` binds the canonical JSON payload. Exit `0` requires `VERIFIED_DEMO` or `VERIFIED`, `3` denotes `UNVERIFIED`, `4` denotes `REJECTED`, and `2` denotes invalid input.

```powershell
java -jar cli/target/demo-diagnosis/evo.jar verify-repair proposal.json --port 8080 --json
```

The current JavaParser v13 frontend changed duplicate-declaration handling. Spring constructor ingestion now recognizes this specific provider identity through a versioned mapping while retaining the existing constructor and source evidence checks. The focused regression preserves the `MAY` profile binding and tests the positive and negative source variants. Older capture providers use their original interpretation version.

## Observed checkpoint

**CONFIRMED / 2026-11-15.** The published reanalysis accepted the exact baseline snapshot, patch and both authored variant snapshots. The passive graphs preserved the selected binding in the baseline and comment-only mutation and removed that edge from the proposed variant. Strict comparison aligned the registered `dev` configuration inputs. The finding comparison stayed `WITHHELD` with `UNKNOWN_FEASIBILITY`, `ROUTE_TRUTH_UNPROVED`, `ROUTE_REGION_UNPROVED` and `RULE_COVERAGE_UNPROVED`. The absence of a binding edge did not certify that the candidate resolved the finding over the modeled region.

The packaged Edge journey passed the original source citation, patch download, local PR preview and CLI parity. The negative control passed by replaying a positive witness for the retained dependency (`CONTROL_FINDING_WITNESS`, `sha256:9e57d5def9d89a5667a90434eef407fb9f0d162c8fe30bd25d20ef8fc31adfec`). The verified input pins include plan `sha256:7893d13c0db9ca0742df6152df152242ea27a92a03ce9d3f225ec7ba15f126a2` and patch `sha256:1c82cb9f2d7310b826c04c84d72f88b21487b5a21a836e95d5e617d4580fc38d`. The backend API and Java/Spring selections ran 8 JUnit cases with zero failures, errors or skips. The Workbench model passed 11 cases. CI returned `3` for the unverified candidate, `4` for a stale candidate snapshot and `2` for tampered proposal bytes.

## Isolated demo run

The demo runner uses a disposable QEMU guest with a Linux kernel, one vCPU, 1 GiB of RAM, bounded temporary filesystems and no NIC or attached disks. Docker Desktop hosts the emulator in a networkless, read-only container with CPU, memory, process and log limits. The controller verifies the actual container settings before starting the guest and observes exit before collecting the serial output. It bundles only the sealed four-file trees, pinned Spring 6.2.0 jars and platform-owned profile oracle. `javac -proc:none` and Spring execute inside the guest. The launch accepts no arbitrary repository path, command or fixture variation. [QEMU direct Linux boot](https://www.qemu.org/docs/master/system/linuxboot.html) · [Docker runtime constraints](https://docs.docker.com/engine/containers/run/) · [Demo runner](../../analyzer-execution/src/main/resources/demo-repair/run.py)

**CONFIRMED / 2026-11-15.** All nine build and runtime checks passed. The runtime oracle compared actual Spring dependencies and verified that `Store` still exists and `Client` follows `@Profile("dev")`.

| Profile | Baseline has dependency | Candidate has dependency | Negative control has dependency |
|---|---|---|---|
| `dev` off | No | No | No |
| `dev` on | Yes | No | Yes |

The operator pins the immutable capture digest at Workbench startup. The reader verifies the plan, full input inventory, packaged control source digests and serial transcript. `VERIFIED_DEMO` requires the two observed Spring profile cells, the compatible pinned reanalysis inputs, and the reproduced negative control. This verdict is scoped native evidence for the fixture and does not issue generic `VERIFIED` or qualify the general sandbox adapter. The historical M11 result above reported `UNKNOWN_FEASIBILITY`. [Step 5](demo-finding-transition.md) records the subsequent comparison and remaining coverage requirement.

With Docker Desktop running, use the proposal JSON exported by the demo browser journey and a fresh output folder:

```powershell
$vmRun = Join-Path ([System.IO.Path]::GetTempPath()) ('evo-repair-vm-' + [guid]::NewGuid())
python analyzer-execution/src/main/resources/demo-repair/run.py proposal.json $vmRun --base eclipse-temurin@sha256:82801f9dcdcbad5a05082aba8c86d1da5b72ccb498e71318b1f91b5a477887b3
```

Pass `$vmRun/capture.json` as the last argument to the packaged browser journey after the candidate and control graphs. The launcher uses `-Devo.demo.repair.capture` and `-Devo.demo.repair.capture.pin` to enable this demo evidence. The packaged Edge run returned `VERIFIED_DEMO` and CI exit `0`. The wrong snapshot and tampered proposal still returned `4` and `2`. The capture SHA-256 was `469abe255a6e0aa31a5bac344d718e29a6d6475e6edbd8d725fe217668e9e32f`. The older run without a capture remains the missing-worker control.
