# M8.4.3 — Phase-2 implementation and verification obligations

Date: **2026-10-19**. **PROVISIONAL acceptance design; PARTIALLY EXECUTED, not accepted.** This is a companion checklist inside step 3 of the [six-step plan](04-comprehensive-repository-readiness.md), not another step/milestone. The [execution contract](../../architecture/m8-isolated-execution.md), [record catalog](../../architecture/m8-isolated-execution-schemas.md) and [structural schema](../../architecture/schemas/m8-execution-plan-v1.schema.json) define expected behavior. Phase 1 checked the design; Phase 2 implementation and bounded/native evidence are recorded in the [execution checkpoint](04-step3-phase2-execution.md). Passing a subset or a JUnit method does not close the complete positive/negative denominator of a row below; unexecuted cases remain open.

## Implementation order and ownership

| Slice within Phase 2 | Owned changes | Exit before proceeding |
|---|---|---|
| Domain and DAG | `analyzer.execution`: typed records, canonical codecs, reference validators, SCC/grouping, lowering and predicate registry | Schema/golden/mutation and graph controls D01–D18; unchanged M1 and step-2 consumers |
| Durable orchestration | `analyzer` journal port; filesystem adapter; scheduler/resource reservations | J01–J12 with real process/storage crash injection; no guest execution permission implied |
| Executor conformance | New execution adapter module, pinned guest/controller and real candidate VM | S01–S16 on actual configured backend before real repositories; no mock substitution |
| Broker and output import | Broker policy/cache, streamed material/output manifests, validators/parsers | B01–B10 and O01–O10, including live configured network and hostile exports |
| Native adapters and protected evidence | Maven/Gradle lowerers/capture parsers, fresh validator worker, promotion/reconciliation | N01–N12 and T01–T12 with actual guest builds and malicious plugin controls |
| Direct integration and handoff | Backend/CLI registered execution composition, v1 compatibility, bounded status pages | I01–I08; exact tool/profile pins and remaining limits recorded; step-3 exit assessed separately |

One implementer may own all slices. No delegation or independent review is claimed by role changes. Do not implement generated semantic closure or language engines under the pretext of testing their step-3 capture boundaries.

Test levels: **D** pure domain/specification; **J** durable/process integration; **V** actual disposable VM; **N** real native tool inside VM; **P** product/direct-consumer integration. Every row below requires positive/negative boundaries where applicable. Results record executed/not-run, exact input/tool/profile, assertion outcome and remaining obligation. A mock can test a caller's behavior but cannot pass a V/N row.

## Graph, schema and identity controls

| ID | Level | Input / counterexample | Required observable result |
|---|---|---|---|
| D01 | D | Every new record, old v1 plans/snapshots, unsupported schema/enum | Canonical round-trip; unknown versions fail explicitly; M1 golden bytes unchanged |
| D02 | D | Reordered set rows vs ordered classpath/argv/processorpath; duplicate JSON keys | Set permutations same identity; ordered mutations differ; duplicate semantic keys/JSON keys rejected |
| D03 | D | Missing/foreign root/context/task/output-role/reference, digest-shaped nonexistent content | No executable plan or false satisfaction; exact diagnostic retained |
| D04 | D | Isolated node, empty selected domain, chain, diamond, fan-in/fan-out | Correct prerequisite-first schedule; complete binding denominator; empty success only for empty selected work |
| D05 | D | Duplicate equal edge, conflicting edge condition, self-loop, disconnected cycle | Equal edge normalized; conflict/self-loop/cycle invalid; independent valid root retained |
| D06 | D | Cycle plus long downstream tail | SCC witness includes cycle only; tail blocked with causal references, not misreported cyclic |
| D07 | D | Module SCC allowed by native co-compile proof vs illegal Maven reactor cycle | Joint group only for proved compatible case; no classpath union or arbitrary cycle breaking |
| D08 | D | Missing module endpoint; parent vs aggregator vs dependency/plugin relation | Missing endpoint obligation retained; relation types not conflated; native ordering honored |
| D09 | D | Shared output/global plugin effect; contraction creates cycle | Conservative root scope or proved group, then revalidation; no unsafe module parallelism |
| D10 | D | Unknown model/dependencies that native run itself discovers | Bootstrap can gather evidence; acceptance still requires validated prerequisite closure; no dependency deadlock |
| D11 | D | Failed native task with COLLECT successor and semantic consumer | AFTER_TERMINATION collection permitted after quiescence; AFTER_VALIDATION consumer blocked |
| D12 | D | Multiple equivalent captures, contradictory captures, reversed arrival order | Semantic deduplication; conflict and same final projection independent of arrival order |
| D13 | D | ALL_OF missing one role; `(A or B) and C`; unavailable alternative vs contradictory answer | All roles required, equivalent alternatives allowed; unavailable provider does not erase accepted proof; substantive disagreement cannot win by first finish |
| D14 | D | Dynamic contexts, new generator, repeating unchanged discovery frontier | New immutable revision; preserve selected/unselected/unknown census; no-progress exits with continuation |
| D15 | D | Same captured inputs with changed attempt/time vs source/tool/predicate/variant | Operational changes excluded from semantic projection; result-affecting changes invalidate exact reuse |
| D16 | D | Counter/size near int32, 2^53 and signed-long boundary | Checked long/lossless API projections; no overflow, truncation or float-rounded identities |
| D17 | D/J | Deep graph beyond recursion limit; large sparse graph beyond configured working set | Iterative SCC/Kahn, external frontier, no node loss; explicit waiting if resources insufficient |
| D18 | D | Forged `completed` set, copied producer manifest into expected, unknown predicate | Production satisfaction accepts only verified ledger decisions; no caller-controlled truth shortcut |

For small graphs, use independently enumerated topological validity/reachability/SCC equivalence as the oracle. Include all directed graphs on a small fixed vertex set and permutation metamorphisms; do not compare one production algorithm to a copy of itself. Large fixtures test storage/continuation and counts, not semantic coverage of arbitrary repositories.

## Journal, cancellation and resource controls

| ID | Level | Input / failure injection | Required observable result |
|---|---|---|---|
| J01 | J | Crash before launch intent, after intent, after VM launch before handle write | No untracked accepted attempt; idempotent launch/recovery finds owned namespace |
| J02 | J/V | Lease expires while worker alive; two controllers contend | Monotonic fence, one reservation/active publication; no duplicate conflicting launch before termination/quarantine |
| J03 | J | Late success, duplicate event, conflicting sequence, stale acceptance CAS | Late result cannot replace current state; equal duplicate idempotent; conflict diagnosed |
| J04 | J | Crash after CAS blob, after manifest, after validation, before/after acceptance pointer | Replay retains only complete durable commits; unreferenced bytes are not success |
| J05 | J | Disk full during each commit stage; corrupt tail/derived index | Last verified frontier recoverable; index rebuilt from verified source only; corrupt authoritative record fails closed |
| J06 | J/V | Cancel before launch/during compile/collection/validation/publication | Deterministic fence winner; committed evidence retained; uncommitted late success not published |
| J07 | J/V | Child processes/daemon outlive parent or controller restart | Whole owned VM/namespace quiescence proven; no orphan target work on host/other attempts |
| J08 | J | Small ready tasks beside oversized task, multiple jobs, resource overflow | Fair persisted queue and reservations; no starvation by endless small work; explicit WAITING without capacity purchase |
| J09 | J | Transient retry, permanent compiler failure, unchanged no-progress loop | Bounded retry by cause; new attempts with provenance; no unconditional infinite retry |
| J10 | J/V | Remote loss/partition during cancel/recovery | Old worker fenced, grants revoked; reservation/quarantine retained until authoritative disposition |
| J11 | J | Failed prerequisite with independent roots and collected prior stage | Only actual dependent acceptance blocked; completed independent evidence survives |
| J12 | J/V | Cleanup path/VM ownership mismatch, resource receipt lost | Refuse deletion of foreign/user resources; reconcile owned attempt only; explicit cleanup gap |

## Real sandbox controls

| ID | Level | Authored adversarial / environment fixture | Required observable result |
|---|---|---|---|
| S01 | V | Missing/unqualified VM; Docker/WSL command exists | Target lane stays closed; typed unavailable/unqualified result; no host subprocess fallback |
| S02 | V | Host sentinel reads, writable source/cache/home probes | Sentinel unavailable, host contents unchanged; private working-copy writes captured |
| S03 | V | Host socket/agent/device/shared-folder/clipboard/integration-channel probes | No unauthorized access or side effect; channels and resource identities included in evidence |
| S04 | V | Metadata/private/loopback/IPv6/alternate IP/DNS-rebind egress | External route blocks by policy; denial ledger retained |
| S05 | V | Fork/thread/daemon bomb | Guest process policy and external VM limits protect controller; termination proof and explicit failure |
| S06 | V | Memory/CPU exhaustion, native allocation beyond JVM heap | Enforced VM resource bounds; coordinator/other jobs continue |
| S07 | V | Disk/log/sparse-file/archive expansion bomb | Bounded attempt storage/log channel; preserved overflow disposition/frontier; no host-volume exhaustion |
| S08 | V | Fake heartbeat/log progress from hung process | Work/idle policy stops or checkpoints attempt; spam cannot keep unlimited lease |
| S09 | V | Cancel with surviving children; kill controller mid-run | Fresh controller discovers and fences/terminates exact worker; no late active publication |
| S10 | V | Guest attempts to launch another job/change image/grant | Control-plane capability rejected, no execution or policy expansion |
| S11 | V | Host driver argument/shell injection via paths, target names and env | Host sees typed arguments only; no target-controlled host execution |
| S12 | V | Guest binary/capture-agent modification, forged receipt | No trusted promotion; receipt validation identifies mismatch/forgery |
| S13 | V | Image/driver/enforcement-policy or trust-root change | Existing qualification not silently reused; exact matching requalification required |
| S14 | V | OS/arch/SDK mismatch; unavailable licensed tool | Correct runner requirement; no guessed compatibility or host fallback |
| S15 | V | Attempt A poisons local cache; attempt B same coordinate | Private state isolated; immutable cache namespace/content checks prevent contamination |
| S16 | V | Remote backend without configured disclosure/allocation; stale grant | No upload/start; specific configuration requirement retained |

S02–S16 are registered attack probes, not a proof that every possible VM escape is impossible. Actual findings must be repaired and the affected controls rerun before admitting repositories. Infrastructure absence is NOT_RUN, never a passing skip.

## Broker and hostile output controls

| ID | Level | Case | Required observable result |
|---|---|---|---|
| B01 | J/V | Approved artifact + denied arbitrary upload/CONNECT | Acquire exact artifact; refuse source exfiltration and unrestricted proxying |
| B02 | J/V | Redirect chains, DNS change, TLS failure, credentials on redirect | Revalidate each hop/address; no credential leak or silent TLS downgrade |
| B03 | N | Wrapper/plugin/custom registry download | All materials brokered/policy-accounted, including bootstrap tools |
| B04 | N | Dynamic/range/SNAPSHOT/relocation/variant selection | Native resolver result frozen to exact metadata/bytes; no substituted version |
| B05 | J/N | Same coordinate/different bytes; checksum mismatch; corrupt cache | Distinct immutable identities/conflict or quarantine; no overwrite |
| B06 | N | Offline replay missing one plugin/metadata/artifact | Closed failure/wait identifying exact missing material; no hidden network |
| B07 | N | Reuse complete closure; mutate lock/source/toolchain/policy | Valid exact hit; changed input invalidates affected reuse |
| B08 | J/V | Credential absence/expiry, private endpoint explicitly configured | Scoped acquisition or typed access requirement; no ambient home/helper secrets |
| B09 | J/V | Reset/stall/rate/quota exhaustion and restart | Valid bytes retained, request census closed, retry bounded, continuation preserved |
| B10 | J | Redacted logs/API vs protected raw transfer ledger | No token/secret/source leakage; raw content unchanged with access policy |
| O01 | J/V | `../`, absolute/drive/UNC/ADS/device paths, NUL, invalid encodings | Reject unsafe materialization; preserve original raw path/disposition |
| O02 | J/V | Case/Unicode collision, non-UTF8, Windows-invalid valid guest path | No alias overwrite/normalization; exact logical identity or compatible-worker requirement |
| O03 | J/V | Link/hardlink escape/cycle; output replaced between stat/read | No-follow/frozen handle validation; reject containment/TOCTOU violations |
| O04 | J | Archive traversal/nesting/bomb, sparse lengths, huge single entry | Streamed verified accounting; bounded worker or wait; no silent drop |
| O05 | J | Directory/class-directory output with missing entry/empty directory | Exact tree hash/census; incomplete export cannot seal overlay |
| O06 | J | Truncated digest/size, malformed JSON/XML, duplicate keys, unknown enum | Typed parser/validation gap and raw digest; XXE/DTD inert |
| O07 | J | FIFO/socket/device/unexpected executable entry | No host load/execute/blocking read; explicit rejected/special disposition |
| O08 | N | Stale target/build outputs captured in source snapshot | Keep inventory lineage; never treat as new native output without proof |
| O09 | N | Declared output absent, undeclared generated root, failed-stage partial output | Every expected/observed output has disposition; valid partial artifacts retained with correct completeness |
| O10 | J | Very large manifest across pages/segments; interrupted export | Counts/digest stable, resume exactly, bounded memory; no complete flag with open frontier |

## Native observation, promotion and integration controls

| ID | Level | Case | Required observable result |
|---|---|---|---|
| N01 | N | Actual Maven multi-module generator → consumer | Build in guest; model/generation/compile and per-target outputs captured with exact lineage |
| N02 | N | Parent differs from aggregator; plugin/extension reactor dependency | Native ordering preserved; no per-folder build assumption |
| N03 | N | Main/test/custom source sets, active profile creates modules | Exact source/context census; no default-context universal claim |
| N04 | N | One broken Maven sibling under qualified continuation | Successful stages independently evidenced; failed/not-reached rows retained |
| N05 | N | Maven cycle, duplicate coordinates, incompatible toolchain | Native failures with exact causes; no forced SCC co-compile or version repair |
| N06 | N | Gradle included builds, buildSrc, substitution, custom variant/generator | Configuration and tasks execute inside guest; actual selected variant and compiler inputs retained |
| N07 | N | Gradle configuration failure before task graph complete | Unknown/not-reached targets visible; `--continue` does not claim nonexistent closure |
| N08 | N | Wrapper checksum/version mismatch, toolchain download or unavailable SDK | Validate tool bytes or keep exact requirement; no `latest`/host fallback |
| N09 | N | Aggregator/no-source module; script no compile phase | Closed zero-output manifest accepted as artifact; build N/A needs proof; native semantic validation remains required |
| N10 | N | Actual ordered classpath/modulepath/processorpath, argfiles mutated by hook | Exact immutable invocation capture or qualified unknown; model/dependency tree not promoted as invocation |
| N11 | N | Native parser failing on future version/truncated record | Version/parse gap with raw evidence, no false exact context |
| N12 | N | Export after executor/controller restart and offline rerun | Same captured hash/context restored; byte drift across rebuild retained, not normalized |
| T01 | D/V/N | Guest emits VALIDATED_OBSERVATION flag and exit zero | Cannot satisfy anything without independent promotion decision |
| T02 | V/N | Malicious plugin rewrites classpath/index/capture agent then exits zero | Bytes may be captured; forged facts cannot close requirement or enter trusted semantic cache |
| T03 | V/N | In-process compiler hook alters behavior despite protected argv trace | Only evidenced launch fact accepted; unproved internal semantic/model claim remains assertion |
| T04 | V/N | Fresh validator attempts class-load/plugin execution | Target code remains inert; executable need rerouted to execution lane |
| T05 | N | Wrong source hash/order/provider/toolchain/variant/selection/receipt | Exact mismatch negative; healthy unrelated evidence retained |
| T06 | D/N | Empty-but-successful model/index drops one target/document | Independent census catches missing denominator; no semantic completion |
| T07 | D/N | Conflicting source/binary/index captures; duplicate equivalent evidence | Conflict retains both and reopens dependents; equal proof deduplicates |
| T08 | D/N | Gap satisfied, later contradiction or qualification revoked | Append decision; active view pending/conflicted; history still auditable |
| T09 | N | Controlled replay with changed processor/JDK/flag | New qualified recipe variant; cannot certify original invocation |
| T10 | D/N | Signed receipt valid but wrong attempt/export/context, stale fence/revoked key | Receipt verification fails association/current admission; no trust promotion |
| T11 | D/N | Root model/dependency evidence vs single compiled source set | Root requirements close only under their own closed census/predicate |
| T12 | D/N | Historical gaps plus newly satisfied current requirements | Current completeness uses active obligations; history neither erased nor counted forever as unmet |
| I01 | P | Existing passive `readiness` and scans with no executor | Existing behavior/identities preserved; explicit unavailable execution lane |
| I02 | P | API arbitrary argv/env/path/image/broker override | Request rejected; registered plan/policy IDs only; no target command injection |
| I03 | P | Static scan zero gaps for selected buildable context | Native validation obligation still present |
| I04 | P | CLI/API status, root/source-set pages, cursor after replan | Same canonical results; foreign cursor rejected; selected/unknown denominator visible |
| I05 | P/J | Backend restart, duplicate submit, cancel and late success | Durable idempotent receipt and correct fenced state, no phantom complete |
| I06 | P | Partial readiness with valid source facts and no compiled evidence | UI/API/CLI keep facts and precise limitations; no fabricated score/compile success |
| I07 | P | M1/v1 snapshot/graph, provider-version contracts, direct consumers | Golden identity and old decode parity; additive execution references only |
| I08 | P/N | Registered source → readiness → native build → validated import journey | Correct scope/lineage and unmet requirements; step-4/5 semantic closure not silently claimed |

## Acceptance predicates to implement first

| Requirement kind | Satisfaction evidence | Critical negative |
|---|---|---|
| DISCOVERY / OWNERSHIP | Closed target/context/source census with disposition, original inventory and selection retained | New executable-discovered source set omitted |
| MODEL | Qualified root-native model and version, model census, captured configuration inputs | Single module success presented as whole-root model |
| DEPENDENCIES | Sealed material/metadata closure and exact selected dependency relations | Empty cache or coordinate list mistaken for exact bytes |
| TOOLCHAIN / PLATFORM | Actual build/compiler/platform binary and configuration identity | Host JDK assumed to be target JDK; wrapper properties treated as distribution pin |
| GENERATION | Generator applicability/lineage + output census including proved no-generator case | No files found treated as generator N/A without proof |
| BUILD_VALIDATION | Applicable compile stages validated for exact context, with accepted prerequisites | Root exit zero but test/custom task missing; stale outputs |
| SEMANTIC_VALIDATION | Required native validator completed on exact inputs with category/census proof | Script has no compile phase, so semantic validation falsely marked N/A |

For a non-compiling context, only compilation is NOT_APPLICABLE. Its separate semantic-validation requirement must be SATISFIED with evidence, or remain pending. No blanket propagation of NOT_APPLICABLE across kinds.

## Step-3 checklist traceability

| Original step-3 work/exit item | Design owner | Required controls |
|---|---|---|
| Disposable VM and pinned backend | Contract §2, §6; SandboxProfile/Qualification | S01, S13–S16, J01–J03 |
| Immutable inputs/private state/resources/cancel | Contract §5–6; LaunchRequest/ResourcePolicy | S02–S09, J02–J12 |
| Broker, cache, online capture/offline replay | Contract §3, §6; Broker/Material records | B01–B10, N08, N12 |
| Maven/Gradle/native recipes | Contract §4, §8; LoweredRecipe | D07–D10, N01–N11 |
| Actual manifests/outputs and failed-build recovery | Contract §7; CompilerObservation/OutputManifest | N03–N04, N09–N12, O05–O10 |
| Optional backend/OS fallback qualification | Contract §1, §6 | S13–S16; no host fallback |
| Hostile output/forgery/cache poisoning | Contract §6–7 | O01–O10, S12, S15, T01–T05, T10 |
| Trust promotion/fresh worker | Contract §7; PromotionDecision | T01–T12, D18 |
| Real adversarial executor before repository | Contract §6, §9 | S01–S16; mandatory real T02 |
| Positive native build, restart and offline execution | Contract §4–8 | N01, N06, N12, B06–B07, I08 |
| Static success/cache invalidation/partial preservation | Contract §3–5, §9 | D15, I01, I03, I06, B07, T12 |
| Reject host/arbitrary API execution; VM absent blocks completion | Contract §2, §6, §9 | S01, S11, S16, I02 |

## Verification commands and completion boundary

Phase 2 uses method/small-class quiet Maven loops first, then the bounded affected suite and direct consumers. Preserve the trusted-platform build directory convention `"-Dplatform.build.directory=target/m84"` and quote PowerShell `-D` arguments; only skip the standalone-reactor membership Enforcer rule when applicable. New test names are selected after implementation, not claimed here as existing classes.

Existing regressions to include where affected: analyzer `ReadinessPlanTest`, `CapturedBuildEvidenceTest`, `IdentityAndSourceContractTest`, `CanonicalSerializationTest`; Maven `MavenReadinessDiscoveryTest`; filesystem journal/CAS/crash tests; backend `ReadinessReportsTest`, `DurableAnalysisJobsTest`, readiness/acquisition API tests; CLI readiness and packaged-journey checks if composition changes. Inspect actual selectors and dependencies before invocation. A platform Maven test is not authorization to run a target build on the host.

Real executor evidence records actual backend/image/tool digests, attack assertions, input/output manifests and raw protected run artifacts through the product evidence path. No ceremonial milestone `reproducibility/` package is created for these intermediate slices. Summaries are compact; never ingest megabytes of raw guest logs. Keep raw generated evidence immutable.

Step 3 can be marked complete only after all applicable required controls have results, required real VM/native controls pass without skips, missing-provider/context dispositions remain honest, and direct consumers pass. Deferred optional backend controls are explicitly N/A to the unselected backend, never silently called qualified. Absent VM blocks executor acceptance, not separable domain/TDD progress. Whole-repository worldwide completeness, step-4/5 engines, step-6 scale and M10 approval remain separate.
