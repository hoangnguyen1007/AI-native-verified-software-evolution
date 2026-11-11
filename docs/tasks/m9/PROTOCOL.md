# M9.1 — Registered evaluation protocol v1

**QC amendment (2026-11-11):** evaluation criteria remain v1; the registry uses **`m9-evaluation-protocol-v2`** for portable input locations. This corrects the reproduced Windows PowerShell 5.1 `-File` default-binding defect and workstation-bound locators. Candidate executable, corpus/label expectations, evidence SHA-256, thresholds, denominators and trust requirements are unchanged. The prior v1 registry is retained outside Git with SHA-256 `eb17a7e88905a7efe92a7cbc7edd668601235aa01e3c425273c51903a8e66831`; only changed preflight/protocol/test pins are refreshed for this explicit registration revision. See the QC addendum in [execution record](01-execution.md).

Registered 2026-11-11 for M9 Tasks 2–5. **CONFIRMED / implementation verification:** Task 1 is complete within the registration scope below. This is a protocol freeze, not M9 acceptance, semantic certification, independent review or M10 approval. [Execution record](01-execution.md) records actual commands, results and limits. [registry.json](registry.json) owns machine-readable pins, claims, labels and corpus; its SHA-256 pins are authoritative over abbreviated names in this document.

The user authorized completing Task 1. Route: lead architect/benchmark engineer → implementer → verification self-check. No delegation, external messages, worker creation, commit or push. Existing workspace edits and historical raw captures are preserved.

## Baselines and compatibility

| Baseline | Identity and permitted use |
|---|---|
| M8 accepted checkpoint | Human closure 2026-11-10, [10-repository report](../../research/M8.4-Step6-Real-Repository-Scale-Evaluation-Report.md). Historical 98% descriptions remain historical. Missing exact executable/source/patch linkage prevents reuse as current candidate correctness/performance evidence. Human acceptance remains valid. |
| Post-update 13-repository checkpoint | Report identifies `b6846f8` + an incompletely identified patch and 46,225,543-byte JAR. Raw aggregate and individual receipt bytes are pinned in the registry as historical-only. No known historical JAR SHA-256 is invented. The 3 timeouts and 7 partials are neither discarded nor relabeled under 95%. |
| Pre-existing `results-m9-baseline` | Ten rows discovered on disk; aggregate pinned as `UNBOUND_PRIOR_OUTPUT_NOT_ACCEPTANCE`. Its name does not establish source/JAR/context lineage. No row reused for Task-1 candidate claims. |
| M9.1 candidate | Full HEAD, executable-source manifest digest, executable dirty patch SHA-256, packaged JAR SHA-256, pinned runtime/version/label files in the registry. Trusted platform dependency closure was built from source, not inferred from installed SNAPSHOT artifacts. CLI projection completion repair is part of this candidate. |

`runtimeSourceSha256` hashes the ordered `{path, sha256}` inventory of module `src/main`, platform POMs and `.mvn` files. It deliberately excludes documentation, tests, benchmark code and generated outputs; each relevant protocol/oracle/test/provider file is separately pinned. `runtimeDirtyPatchSha256` covers Git's binary HEAD diff on that executable selection, including staged and unstaged tracked changes. These are explicitly scoped identities, not a claim that HEAD alone describes a dirty workspace. `pinnedFiles` contains the benchmark/test/label versions; detailed corpus manifests stay outside Git. The registry cannot hash itself. A change to a pinned input creates a protocol/candidate revision; do not update a result's identity in place.

Reuse requires equality of result-affecting source bytes, selection, context, **ordered** dependency/platform bytes, generated/configuration evidence, producer/trust/method, rules/schema/metric/score/status policy and query semantics. Different inputs mean different runs. A shared test name, commit prefix, JAR size, timestamp, path or success code is insufficient. No historical native evidence is qualified by this registration. C/N lanes stay NOT RUN until exact compatible capture pins are registered before scoring.

## Frozen corpus and independent census

The registry freezes **16 external repositories**: the 13 development/regression cases in the post-update report and three holdouts. Development cases `agentscope`, `halo`, `fermata`, `newpipe` and `boutique` also form the scale stratum. The Java/Kotlin/microservices PetClinics have distinct IDs and are never pooled as one application.

Holdout choice was based on absent archetypes, before any analyzer execution: [Quarkus quickstarts](https://github.com/quarkusio/quarkus-quickstarts) for alternative Java frameworks/multiple build roots, [GORM](https://github.com/go-gorm/gorm) for a Go library, and [create-vue](https://github.com/vuejs/create-vue) for frontend templates/tooling. Their public source descriptions were checked; source acquisition/census does not score analyzer quality. No prior evaluation of these repository coordinates was found in the checked-in evidence. This is a purposive, finite selection, not random sampling or proof of universal coverage. Any holdout used to debug becomes regression in the next immutable revision; keep its first outcome and do not silently replace it with an easier repository.

Each repository has a full 40-character commit, actual checkout manifest SHA-256 (including dirtiness), physical entry count, language/source-set counts, external JSONL inventory hash and descriptor-context index hash. Every file is SHA-256 hashed from bytes; same-size/mtime changes invalidate the manifest. Symlinks are counted by target text without traversal; inaccessible inputs fail freeze/preflight rather than disappearing. Root `.git` administrative data is the only census exclusion. Binary/unknown files, test/vendor/generated files and nonconventional layouts remain counted.

The census is independent of EVO/JavaParser/build-model output. It labels nearest **descriptor ownership**, main/test/generated/vendor/unclassified paths and detected languages. These labels are mechanical census classifications, **not proof of effective modules, source roots, variant counts or generator closure**. All descriptor owners, plus files with no owner, have explicit passive/unanalysed dispositions and open effective-context/generator obligations. During M9.2, compare realized roots/modules/source sets/variants to this independent index and review declarative custom roots/generators; record unresolved dynamic contexts rather than counting one descriptor as one proven build context. Duplicate physical files across contexts count once in physical inventory and once per eligible context in semantics. Submodules/LFS/symlink targets retain separate acquisition obligations; an unpopulated submodule is not a fully acquired dependency.

Standard Java PetClinic at the historical G2 pin `818c4136ea971c21674525f9053de0d9c7ad8cfe` and ThingsBoard in the original M8 cohort are mapped in `historicalCases`, separate from the 16 current external cells. They are historical controls, not missing current holdouts or silently included current runs. Their exact currently compatible source/capture is unavailable to this registration; no current score is assigned. Scale micrographs reuse `PartitionedGraphStoreTest`'s generator: `indexSpansMultipleDirectoryLevelsWithoutARepositoryRowCeiling`, 33,000 TYPE nodes, one context, no edges, deterministic `large-partition` index identity, no RNG. Existing small directed/path/cycle fixtures supply dense/SCC correctness controls; larger graph shapes require a pinned generator revision before scale scoring, not a duplicate generator now.

## Three evidence lanes and selections

| Lane | Fixed inputs and trust |
|---|---|
| P passive/offline | `--offline --build=off --all-files --include-tests --release=21`, explicit empty dependency cache and external workspace, pinned JDK. No target execution or network dependency retrieval. Preserve actual selected-context and ordered classpath manifests from each run. Empty native dependency input is explicit, not a claim of exact dependency closure. |
| C captured | Explicit authenticated immutable worker capture + CAS + labels + source/context/tool/image/transport pins; no newest-directory lookup. Existing M8 captures may be reused only after compatibility checks. Missing/unqualified evidence remains visible; no production qualification is inferred. |
| N new native | Only when the changed capability cannot be established by existing compatible capture. Preflight registered private worker, pinned image/transport/tool, resource/egress policy and output lineage; no host fallback. No new worker or native campaign required by Task 1. |

Generated sources/artifacts enter semantics only with origin and lineage. Inventory can count observed generated-path files while their authenticity remains unknown. No handwritten stub closes native obligations. P/C/N are paired only on the same source/context and with an explicit changed-input list; never average their trust tiers together. In P, source-test/vendor/unclassified semantic applicability is reported from the actual analyzer selection, not assumed from inventory inclusion. Any unselected component has a reason and remains in inventory accounting.

## Oracle, labels and claim matrix

The **17 claims in the registry** own oracle, denominator, case references, decision criterion and next-task ownership. `labelSets` pins existing independently authored expectations, source fixtures and concrete test methods before M9 scoring. It includes M2 source/span/binding labels, M4 formal/runtime/mechanism labels, Java recovery/build inputs, graph/policy/metric/score goldens, CLI/API/JS and storage controls. Inline JUnit expectations are source-authored goldens, not a compiler/runtime oracle or independent human review. The evaluated adapter must never generate expected labels for itself.

Positive, negative and degraded controls are mandatory per applicable claim: exact target ↔ lookalike/missing/shadowed target; feasible path ↔ mutually infeasible path; known condition/order ↔ opaque/missing order; byte-identical capture ↔ altered/missing capture; correct operands ↔ missing operand; normal page ↔ corruption/cancel/reopen; usable UI ↔ stale/empty/degraded state. A claim without an implemented control stays NOT RUN, not PASS. New interactions, robustness perturbations, UI scripts or external semantic samples must have an immutable source-anchored expected-outcome registration **before** their first scoring run; the claim criteria and selection cannot be tuned from observed holdout results.

Disagreement procedure: retain candidate and oracle outcomes, original source/full span/context and diagnostics; classify wrong binding, unresolved, ambiguous, omitted, unsupported, error and provenance failure separately. Recheck independent source/build evidence, compile context and framework/version/order applicability; compiler recovery after diagnostics is not a valid binding oracle. A reviewer adjudicates with rationale or leaves UNADJUDICATED. Tool majority never settles truth. Fixing a label/specification defect creates a revision and invalidates only affected comparisons; original results remain immutable. Independent reviewer availability is pending M9.5; self-review does not satisfy that claim.

## Denominators and frozen decisions

- Inventory accounting = entries with disposition / independent physical census; separately report acquired bytes and effective-context reconciliation. Target: **100% accounted**, zero silent omission. Unsupported is an honesty pass, not semantic-completeness pass.
- Resolution coverage = resolved / independent eligible obligations per repository × context × language × category × tier. Attempted count is separate; omitted remains in the denominator. Empty population = N/A. Structure, usage, syntax parsing, native bindings and runtime observations stay separate.
- Semantic precision = correct certain targets / all adjudicated certain targets; recall = correct eligible targets / all labeled eligible targets, including omitted/unresolved as misses. Report TP/FP/FN, unresolved/ambiguous/error/unsupported/provenance failure and label coverage. Unlabeled external recall/correctness = **N/A**, not analyzer resolution coverage. No minimum whole-world recall is invented from a finite registry.
- Exact supported microfixture goldens: **100% agreement**, zero wrong certain target/origin/span, false unconditional warning, invalid witness or false feasible path. Negative/degraded controls must retain typed gaps/statuses. Report unlabeled/unadjudicated cases, not only passing labels.
- Conditional region precision/recall is over registered feasible assignments (or equivalent disjoint labeled regions), with infeasible worlds excluded by the preregistered model. Witness validity = valid witnessed facts / all emitted witnesses; require 100% on labeled bounded fragment. Zero feasible worlds = invalid model, not universal truth.
- Metric/score operands, statuses, canonical identity and golden rational values must agree exactly. G4's 19-metric baseline and G5's formula, 25/20/20/25/10 weights, 2/10/25 penalties and missing-evidence safeguards remain human-approved. `ArchitectureScoreModel.CALIBRATION="PROVISIONAL"` is still emitted and hashed by code; it is not an unapproved formula or permission to rewrite artifact identity.
- Passive completion is the approved **950‰** predicate, with separate positive JVM structure/hierarchy denominators, optional syntax population and non-JVM safeguards. Rejected/failed units, compatibility-worker and missing RPC evidence block promotion. Final graph projection gaps are included in CLI completion checks. `COMPLETE` recovery retains all gaps and cannot grant `VERIFIED_EXACT`, semantic correctness or a health score.
- Report per-repository/per-stratum first. Micro aggregates sum compatible numerators/denominators; macro averages include only applicable metrics with explicit eligible/excluded/unavailable counts. All failed, partial, canceled, timed-out and unavailable rows remain in the selected-run population. Never average successful latency and call it whole-cohort latency.

## Reference environment, pilot and budgets

Reference environment is pinned in `environment`: Windows 11 amd64, Intel i5-14400F, 16 logical processors, 34,138,931,200 bytes RAM; Oracle JDK 21.0.12.1+1-LTS-4; Maven Wrapper 3.9.16; explicit Python/Node; Edge 157.0.4341.42 executable. No browser journey was run in Task 1. Auto-updated browser/tool bytes require a recorded environment revision before M9.4 comparisons. Use 1440×1000, 390×844 and 320-px reflow; browser/device emulation is not a real mobile-device claim.

**CONFIRMED development pilot:** one passive `realworld-go` scan with initial packaged runtime, empty artifact/analysis caches, OS cache uncontrolled, 2-GiB heap, exit 0, wall **9.922 s**. It is not holdout, repeated determinism, peak-memory, query/UI or native evidence. Its initial JAR differs from the frozen candidate after the discovered CLI guard repair; `pilot` pins both the actual pilot JAR hash and receipt. It informs allocation only. No new acceptance result is inferred from it.

Frozen v1 measurement rules (experimental allocations, not new public product SLAs):

| Cell | Protocol |
|---|---|
| Passive medium/development | 2-GiB Java heap, single job, external disk. 300-s observation allocation, approximately 30× the small development pilot. This headroom is a pilot-informed allocation, not linear extrapolation or a 300-s product admission ceiling. |
| Five scale representatives | 8-GiB heap (less than 1/4 host RAM), 12-GiB process working-set stop, 8-GiB scratch allocation per sequential job, 1,800-s observation allocation. These are finite workstation allocations informed by historical 600-s censored rows; unmeasured scale capability remains unmeasured. |
| Repeatability | On `realworld-go`, `petclinic-kotlin`, `boutique`, `agentscope` and the 33k-node generator: three independent cold-process/empty-analysis-cache runs and three warm-analysis-cache runs, identical input/candidate/resource profile. Artifact cache fixed. OS page cache is explicitly uncontrolled. Cache setup and repeated no-change environmental failures are not extra trials. |
| Query/UI | Same immutable registered graph and operands: search, structure children, focused graph, path/cycles, metric/penalty/finding → evidence, impact, paged raw graph. Twenty warm-up interactions, then 100 measured requests per operation per viewport; report all observations/median/range and empirical p95 with sample size, never a stable population p99 claim. Baseline JAR and final candidate are paired sequentially on the same data and environment. Freeze **candidate median ≤ 2× baseline median** for each applicable cell; zero new timeout/error/oversized payload. Record absolute times as measured; no unsupported absolute latency SLA. This ratio is an engineering regression tolerance, not a pilot-measured UI budget. |
| Bounded projections | Preserve G4 depth ≤20, work ≤100,000, cycle witnesses ≤50; requested page ≤50. Verify actual endpoint payload caps and frontiers, with all returned bytes measured. Product API limits remain per-request and never become total-repository limits. |
| UI/user outcomes | Six journeys from M9 plan, all applicable desktop/mobile/reflow/degraded cells complete; zero blocking keyboard/focus/stale-response/evidence-navigation defect. Three independent code-reading users, same scripts; report individual completion/errors/assistance and recovery/exact/unknown interpretation. Missing users = pending independent-usability claim, not fabricated success. |

Measure phase wall clock separately (acquisition, readiness, semantic, Spring/reasoner, publication, query, render and interaction-ready); process working set at ≤100-ms intervals and process-tree peak where execution actually has children; heap maximum is a limit, not measured memory. Record sampling interval, missed samples/children, temporary disk delta/peak, page/byte/payload counts and context/graph sizes. Method gaps stay explicit. A three-run range is not p95/p99. For final performance comparisons first execute the pinned Task-1 baseline against the registered workload, then candidate; missing baseline comparison is NOT RUN. Baseline generation is not permission to change selection/budgets after viewing candidate scores.

Stop at allocation exhaustion, cancellation, no-progress watchdog or integrity failure; preserve checkpoint, stderr category and right-censored elapsed time. Do not convert timeout to PARTIAL/COMPLETE or drop it. No score on unfinished output. If allocation cannot answer a claim, report failure/unavailable and a prospective protocol revision before further attempts. Live retries/download timing remain operational; captured replay identity cannot normalize away changed native output.

## Execution and revision rules

The existing G2 runner owns the entry point. `m9_protocol.py` adds data-only registration/preflight; it does not run a second analyzer, build repositories, load target scripts or replace G2/M2/M4 oracles. `freeze` requires a new external output directory and refuses overwrite; registry/indices are small, detailed censuses/raw output/cache stay outside Git. Preflight rehashes candidate, labels and external inventories and optionally all source bytes. Missing or changed inputs return nonzero with a bounded report; it never downloads or starts a worker.

```powershell
python -m unittest discover -s benchmarks/g2-pipeline -p test_m9_protocol.py -q
python benchmarks/g2-pipeline/m9_protocol.py preflight --verify-sources
# Equivalent existing entry point, no JDK/build/native setup required:
& benchmarks/run-g2-benchmark.ps1 -M9Preflight
# Windows PowerShell 5.1 / CI boundary (also tested):
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks\run-g2-benchmark.ps1 -M9Preflight
```

### Relocating a checkout, evidence or tools

v2 input references use `root:relative/path`, for example `repo:benchmarks/m4-r0/cases.json`, `evidence:evo-baseline-r2.jar`, `corpus:halo` and `jdk:jmods/java.base.jmod`. `repo` always binds to the actual checkout containing the helper, independent of working directory, username or drive. External roots (`corpus`, `evidence`, `jdk`, `node`, `browser`, `authored`) retain original workstation paths in `pathRoots.*.recordedPath` as provenance/defaults. They can be explicitly rebound using a **local, uncommitted JSON file** such as `.evo/m9-roots.local.json`:

```json
{
  "corpus": "E:/ci/evo-benchmark-cohort",
  "evidence": "E:/ci/m9-inputs-v1-final",
  "jdk": "E:/ci/pinned-jdk-21.0.12.1",
  "node": "E:/ci/pinned-node",
  "browser": "E:/ci/pinned-edge/Application"
}
```

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File benchmarks\run-g2-benchmark.ps1 -M9Preflight -M9RootsFile E:\ci\m9-roots.json
python benchmarks/g2-pipeline/m9_protocol.py preflight --verify-sources --roots-file E:/ci/m9-roots.json
python -m unittest discover -s benchmarks/g2-pipeline -p 'test_m9_*.py' -q
```

Bindings must name registered external roots and absolute local directories; omitted bindings use recorded defaults. Repository overrides, unknown roots, absolute/traversing relative paths and resolved symlink/junction escapes are rejected. Locators in command templates are data: resolve them into arguments before execution; never pass the locator text as a literal filesystem path or evaluate it as shell code. Legacy v1 absolute registrations remain readable within their original environment.

Relocation preserves SHA-256 checks for **every** candidate, census, source and tool/label pin. Missing input or mismatched bytes is still a failure. CI must provision the same registered inputs and byte-identical Windows tool artifacts, or register a different environment prospectively; this patch does not certify cross-OS executables or silently accept a newer JDK/browser. Preflight never downloads tools or executes target builds. Checking Windows PowerShell `-File` on another OS is explicitly NOT RUN; its Python resolver contracts can still run there. The actual `-File` regression was established experimentally; [Microsoft's automatic-variable documentation](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_automatic_variables?view=powershell-5.1) describes the executing-script root, and [Python's path documentation](https://docs.python.org/3.12/library/pathlib.html) distinguishes lexical paths from resolved paths used for containment.

Future freeze: `python benchmarks/g2-pipeline/m9_protocol.py freeze --corpus-root <explicit-root> --jar <verified-jar> --output <new-external-directory>`. Review/copy the resulting compact registry as a new revision; never overwrite the registered file automatically. Pin new native inputs and independent labels before scoring their claims. Preserve every original failing/deviating run, record reason/result-affecting inputs and the exact rerun subset. No ceremonial milestone reproducibility package is produced for this task.

**Open consequential acceptance decisions: none for Task 1.** Existing 95% and score approvals are applied. Missing native compatibility, future source-anchored interaction/UI labels, human usability participants and independent review are explicit execution prerequisites of their owning claims, not completed evidence. M9.2–M9.5 must not mark a prerequisite or claim PASS without its actual artifact. Exact next task: **M9.2**, starting with J01/J02 attribution and independent oracle checks against this frozen candidate, registering any new labels before scoring.
