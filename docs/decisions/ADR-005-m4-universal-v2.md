# ADR-005: M4 Universal v2 — active evidence closure before M4E

- Date: 2026-09-23; compact three-task plan finalized 2026-09-26.
- **CONFIRMED:** owner requested a new M4 Universal v2 task and detailed supporting plans to minimize UNKNOWN/UNRESOLVED across repository shapes.
- **PROVISIONAL:** the technical design is the adopted planning baseline, pending contract tests and empirical validation. This ADR does not claim that the owner reviewed every proposed schema or threshold.
- Scope: task sequencing and passive evidence expansion inside SE121 Java/Spring. The owner explicitly confirmed Java/Spring semantic depth with polyglot inventory/boundaries. G2 history and G3 acceptance are unchanged.

## Problem

Universal v1 delivers useful providers but still requires supplied exact classpaths, lacks complete generated/library evidence acquisition and automatic source-to-M4C normalization, and does not coordinate all evidence requirements to closure. Merely adding supported syntax or increasing solver limits cannot repair those integration gaps.

## Decision

Add M4-UNIVERSAL-V2 between delivered Universal v1 and M4E. Use a passive, requirement-driven coordinator over the existing evidence ledger, scoped failure recovery, exact build contexts, generated/artifact evidence imports and versioned Spring semantic packs. Preserve structural results while exact semantic prerequisites are acquired. A gap closes through satisfaction evidence, never relabeling or omission.

The [architecture contract](../architecture/m4-universal-v2.md) owns behavior; the [task index](../tasks/m4-universal-v2/README.md) owns sequencing; the [evaluation protocol](../research/m4-universal-v2-evaluation.md) owns metrics and empirical gates. Following the owner's explicit request for fewer tasks, v2 contains exactly three tasks: two implementation tasks and one integration/acceptance task. Internal checklists retain coverage and verification without creating more tasks. The former two-slice implementation limit describes v1 delivery; its historical records remain intact.

## Alternatives and cost of error

| Alternative | Benefit | Reason for selection/rejection |
|---|---|---|
| More regex/heuristics and higher limits in existing pipeline | Small initial change | Cannot establish exact dynamic build semantics, lineage or version correctness; risks false certainty |
| Run every target build/application automatically | Can expose generated and runtime evidence | Rejected: untrusted execution, nondeterminism and side effects violate the operating contract |
| Replace JavaParser immediately | Might address some frontend gaps | Not justified by current evidence; ADR-001 replacement triggers still apply |
| Passive evidence coordinator with qualified imported outputs | Addresses upstream missing evidence and integration while preserving contracts | Selected planning direction; costs explicit provenance, invalidation and broader fixture maintenance |
| Implement every language/framework in M4 | Broad marketing surface | No approved phase change; inventory polyglot input now, add semantic engines through explicit future scope |

## Compatibility and reversibility

M1 identity preimages, M3 exact manifests, M4B/C/D semantics and immutable historical evidence remain intact. New envelope versions are additive. Old v1 entry points keep their meaning. Provider replacement is local; results from different inputs/versions cannot be silently merged. A version pack can be disabled when its oracle or corpus evidence contradicts its claims.

Imported bytecode/generated/config/runtime artifacts are data; their acquisition does not execute producers. Target lifecycles, plugins, annotation processors, applications and remote authenticated access are not authorized by this ADR. A future execution provider still needs its own security contract and authorization under ADR-003.

## Consequences and unresolved gates

The next task changes from M4E to V2.1. M4E follows v2 integration and retains independent labels, fair baselines and G3 adjudication. Existing global 98–99% and infinite/millisecond SAT wording becomes a scoped empirical target, not a correctness premise. Missing private/generated/runtime inputs remain possible; v2 must exhaust applicable permitted evidence and expose their actual architectural effect.

No production code or benchmark is delivered by this decision record. Alternate primary frontend, new analyzed languages and target execution remain separate consequential decisions only if implementation later proposes them.
