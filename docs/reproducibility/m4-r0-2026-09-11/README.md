# M4-R0 research gate handoff — 2026-09-11

**Result: research candidate and bounded evidence delivered; M4-R0 REVIEW_REQUIRED.** G3 remains NOT STARTED. This is author self-verification, not independent semantic adjudication or human gate acceptance.

## Scope and changes

The task began in SE121 Track A+B with G2 recorded PASSED and M4-R0 listed as next. The owner requested historical breadth from Spring 1.x XML through Boot 3.4.x, annotations and every phase/order domain. The working tree already contained substantial M3/code/governance/document changes, untracked evidence and deleted older checkpoint files. Those pre-existing changes were preserved. No commit, push, deletion or target lifecycle was performed.

| Handoff field | Result |
|---|---|
| Work completed | Official-source research, historical/version matrix, exact identity and relationship proposals, closed 29-family catalog, configuration/phase/order/UNKNOWN contract, authored runtime/formal fixtures, annotation census, actual typed gaps, solver/baseline protocol and bounded experiments |
| New architecture/research | [R0 contract](../../architecture/m4-r0-semantics-gate.md), [assessment](../../research/2026-09-11-m4-r0-spring-semantics.md), [standalone package](../../../benchmarks/m4-r0/README.md) |
| Existing documentation changed | current-state, project-context, roadmap, M4 Spring contract, conditional architecture semantics, research-questions; reconciled obsolete G2 blockers and linked the R0 candidate |
| Production files changed | None by this task; existing analyzer changes belong to the initial working tree |
| Decisions made | Human historical research scope retained; reversible research harness choices; nested event/ordering and additive identity/catalog proposals remain PROVISIONAL |
| Decisions requiring review | Exact identity preimages, 29-row catalog/XML/catch-all, supported first fragment/patch matrix, numerical budgets and source/fixture adjudication |
| Limitations | No full historical annotation-use census, independent review, Java M4 schema implementation, production extraction, Config Data oracle, full partial-order/correlated opaque evaluation, Spring SAT encoding, Java SAT/BDD performance comparison or real-repository Spring evaluation |
| Blockers | No remaining execution-permission blocker; independent/human R0 adjudication and acceptance still required |
| Durable state | current-state now names R0 REVIEW_REQUIRED and exact adjudication task; roadmap preserves G2 acceptance and gates production M4A |
| Exact next task | Review actual contract/labels/results/gaps, accept or amend the R0 candidate; after acceptance implement M4A.1 evidence-only mechanism inventory and gap normalization |

## Executed checks

All commands below ran from the repository root. No root reactor, target Maven/Gradle lifecycle, external application benchmark or production test suite ran; this change is a standalone research/contract package. `javac` compiled only authored fixture code or the existing neutral contract classes needed to validate gap construction.

| Command / experiment | Observed result |
|---|---|
| `python benchmarks/m4-r0/fetch_sources.py` | Initial restricted attempt: 37 URLError outcomes retained in sources.lock.json |
| Same fetcher with `--lock sources-acquired.lock.json`, supported host escalation | 34/37 acquired; 5,086,990 bytes; S34/S35/S37 unavailable and explicitly recorded |
| `python benchmarks/m4-r0/fetch_artifacts.py`, supported host escalation | 26 exact JARs, 24,472,132 bytes; no transitive resolution, redirects or target lifecycle |
| `python benchmarks/m4-r0/inventory_annotations.py` | 15,594 classfiles; 402 annotation declarations; 192 without nominated family; all retained for semantic review |
| `python benchmarks/m4-r0/run_runtime.py --output docs/reproducibility/m4-r0-2026-09-11/runtime-run-2` and `runtime-run-3` | 79/79 framework observations pass per run; canonical outputs byte-identical |
| `python benchmarks/m4-r0/oracle.py --output docs/reproducibility/m4-r0-2026-09-11/oracle-run-3` and `oracle-run-4` | 70/70 formal cases and 64/64 CNF comparisons pass per run; semantic outputs byte-identical, including replay with `PYTHONHASHSEED=7` |
| Flat registration baseline in oracle | 2/4 disagreements on registered order controls; retained as negative baseline evidence |
| `python benchmarks/m4-r0/prepare_gaps.py` | 452 stable research provider observations |
| `javac -proc:none --release 21 -sourcepath analyzer/src/main/java -d benchmarks/m4-r0/.cache/gap-classes benchmarks/m4-r0/GapExport.java` | Success; actual current CapabilityGapRecord constructor/serializer compiled from source |
| `java -cp benchmarks/m4-r0/.cache/gap-classes GapExport benchmarks/m4-r0 docs/reproducibility/m4-r0-2026-09-11/gap-run-2` and `gap-run-3` | 452 valid typed records per run; identical bytes and study snapshot identities |
| `python benchmarks/m4-r0/verify_package.py` | Offline source/JAR/input hashes, catalog/census denominators, result counts, byte-identical replays, gap references/snapshot and introduced local links checked |

The final integrity check passed: 26 artifact hashes, 34 source hashes, 29 catalog rows, 402 annotation declarations, 79 runtime cases, 70 formal cases, 64 CNF cases, 452 typed gaps and 37 introduced local links. `git diff --check` passed for the changed existing documentation. [documentation-changes.diff](documentation-changes.diff) isolates this task's edits from the pre-existing working tree; [initial-git-status.txt](initial-git-status.txt) records its starting state. The deliverable [manifest](manifest.json) binds the final documents, scripts and saved results; verify it with `python docs/reproducibility/m4-r0-2026-09-11/verify_manifest.py`.

Runtime details, exact ordered classpaths and JDK/Python versions are in each runtime environment.json. Runtime fixtures disable ambient Spring system-property/environment sources. The formal oracle uses explicit inputs only. Timing files remain separate from canonical semantic bytes; no performance ranking is claimed.

## Canonical evidence digests

| Artifact (matching replay) | SHA-256 |
|---|---|
| runtime-run-2/runtime-results.json (runtime-run-3) | `49b95da878a980886e372837950804a188c487dc9a734c1e4d750f7d4b863139` |
| oracle-run-3/results.json (oracle-run-4) | `830942c429b7db42d8ba147047b05785c64956ee0c3fafb8292cf7c92c258892` |
| gap-run-2/capability-gaps.json (gap-run-3) | `e9b1f0e78083f884e84e21d0e5657dacb6f5597c7d00f8d76b3e36cce8a3e22a` |
| Gap study snapshot identity | `snapshot:sha256:9f6626f282e3dc35d9a3ff6386eec3cc676a2b8c1ec2b4c98515f1e79d7cd14e` |

## Preserved failures and earlier runs

`oracle-run-1` preserves the original 65/66 result, original fixture labels and oracle source. The ambiguity control accidentally supplied an exact bean-name match; the correct negative input uses `consumer`, and a new name-match control expects `b`. `oracle-run-2` records the subsequent 67/67 result. Final self-review added three limit/minimization cases, bringing the formal denominator to 70. No old result was hand-edited.

`runtime-run-1` preserves the first 79/79 observations and its exact earlier runner bytes, verified against the recorded input digest. Later runner hardening removes host-path-dependent failure text; successful outcomes are unchanged. `gap-run-1` records the earlier study snapshot before package completion; only final gap-run-2/3 are the replay claim. Additional study files legitimately change study identity.

The [research assessment](../../research/2026-09-11-m4-r0-spring-semantics.md) describes all source failures, deviations, shared-author limits and unexecuted categories. These results justify a concrete review checkpoint, not a claim that every annotation/version or container behavior is implemented.
