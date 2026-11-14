# M11.1 — Scope, contracts and comparison baseline

2026-11-14. **M11.1 design deliverables complete; implementation and G7 pending.** [M10 sign-off](../m10/01-release-sign-off.md) already authorizes Track B. The [evolution contract](../../architecture/m11-compatible-snapshot-evolution.md) and [ADR-010](../../decisions/ADR-010-compatible-snapshot-evolution.md) specify the additive design. The proposed public schema, CLI and HTTP interface are **PROVISIONAL** and will be reviewed before public adoption in M11.5. No unresolved decision blocks the internal M11.2 slice.

## Baseline reconciliation

| Baseline | Identity and availability |
|---|---|
| Frozen M9/M10 candidate | **CONFIRMED:** locally rehashed `evo-candidate.jar`, 46,217,387 bytes, `2b099a777de93dd976c58bdbb1be5e91c9e33d2dfdc119476c73440ec66de84b`. `candidate-registry-v6.json` also rehashed: `12ee8d305dac24acba9924b3e4bc14a9e6d4f3932553f4805d52a133f6fe4e87`. Registry reports source base `d4e8b3f355770d4764385c97c5e52e110c74805e`, runtime manifest `ddc4e412f5999659396535bcbd378944fb79f8037a80db33890c654c14a4cb09` (635 files), runtime dirty patch `3f1a7e33ed85327435ada834152b3514ba611949e3b4e54a0e65cee57ab0d43a`. The historical source manifest and release results were not reproduced in this task. |
| M11.1 development entry | **CONFIRMED:** `main`, HEAD `c5517c30e22f9e192f181c0135207828188d7259`, no staged changes; 55 modified and 11 untracked paths at entry. Release source base is an ancestor of HEAD. `m9_protocol.platform_identity()` returned runtime manifest `e800b2930b827ba5788e41d808bc3a437785762c892a844218d7d39d49d37107` (640 files) and tracked runtime patch `d932c271927956c372a8368cb81ffe55a70874e8efc752fe0c32da33f3e60596`. Manifest includes eligible untracked runtime files; patch alone does not. This is a fingerprint, not a rebuilt or archived M11 binary. The incoming graph/store/reuse/Workbench edits were preserved. |
| M11 evaluation candidate | **NOT RUN:** no packaged M11 candidate or pair outputs. Refresh the working source fingerprint when implementation begins; freeze exact runnable bytes, inputs and versions before scoring per [protocol](PROTOCOL.md). |
| Natural history inputs | **CONFIRMED:** five exact source revisions and three adjacent pairs in [cases.json](cases.json), checked from a bare Git object store. M9's [registered passive artifact](../m9/02-registration.json) for the latest revision remains historical: rehashed result and receipt match the registration and the run record identifies the same source commit. No matching M11-produced graph/report pair or verified native capture was established. The source anchors support only declared changes. |

The [M9.5 per-claim table](../m9/05-execution.md#4-full-17-claim-adjudication-table) is the basis for this reconciliation. The historical summary/sign-off's `14 PASS` count conflicts with the rows. Counting each claim once gives **13 PASS + 2 SCOPED_PASS_WITH_RESERVATIONS + 2 NOT_RUN_CAPTURE_UNAVAILABLE = 17**; none omitted. This reconciles the accounting, and leaves raw results, the release decision and its four reservations intact.

| M9 status | Claim IDs |
|---|---|
| `PASS` | I01, J01, J02, S01, L01, T01, G01, P01, M01, A01, X01, R01, E01 |
| `SCOPED_PASS_WITH_RESERVATIONS` | F01, U01 |
| `NOT_RUN_CAPTURE_UNAVAILABLE` | B01, D01 |

## Decisions and verification boundary

- The input matrix traces manifest, graph, census, Spring, polyglot/bridge, evidence and report fields to actual producers. Missing tool bytes/census/policies and bounded publication remain explicit. M5 compatibility preparation and Workbench reuse are not evolution engines.
- The design fixes internal comparison modes, scope dispositions, typed correspondence, non-circular hash preimages, proof of absence, common-domain/lifecycle semantics and persisted result/cursor boundaries. Canonical v1 uses same-repository scope and excludes operator mapping; unsupported or unproved correspondence remains explicit. Existing Track A IDs, score model and contracts stay in force.
- The [protocol](PROTOCOL.md) and [registry](cases.json) preregister all 18 families, the full 16-pair quantifier oracle, positive/degraded examples, the first Java structural slice and resource/replay accounting. No engine output was used to write the labels.
- **CONFIRMED / verification:** `Get-FileHash`, `m9_protocol.platform_identity()` and `git merge-base --is-ancestor` checked baseline identities. Read-only Python checks passed for 18 families, 42 named cases, 16 quantifier pairs, 4 region shapes, 2 payload examples and all 17 M9 claim rows. The independent Git-object check recomputed five complete SHA-256 inventories (1,004 revision/blob rows), verified three direct-parent edges and six source-anchor paths across both sides, and confirmed all three source-label groups from POM/YAML or exact changed-file sets. Local link/anchor checks and `git diff --check` passed on the affected documentation, including untracked files checked with `--no-index`.

**Limitations:** no production code changes, Maven/engine/native/browser campaign, or independent review. Native M9 B01/D01 remain unrun. No source disclosure, target execution, staging, commit or push. The initial development profile is inherited; G7 resource limits still need an M11 pilot and a candidate freeze. Public adoption is a later review, and G7 has no verdict yet.

**Exact next task:** M11.2, first slice: authenticate two Java structural publications with the same tool and policy profile, reconcile repository/MAIN/TEST dispositions, match scoped TYPE entities and prove one dependency addition. Verify the symmetric removal and the evidence-loss control, then prove canonical paging parity against the filesystem reader. Use the registered cases before implementing; do not start conditional/lifecycle or public transport work in this slice.
