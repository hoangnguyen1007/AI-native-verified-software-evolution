# ADR-010 — Compare immutable snapshots through explicit correspondence

Date: 2026-11-14. **PROVISIONAL — M11.1 design baseline and proposed public contract.** [M10](../tasks/m10/01-release-sign-off.md) authorizes Track B. That approval does not ratify a new public wire schema or pass G7.

Two published graphs are exact artifacts, not directly comparable logical populations. Source edits change analysis and finding keys; the same source can produce different observations when tools, policies or evidence change. A disappeared observation cannot establish a fix. The design must preserve protected Track A artifacts, explain those distinctions, and compare large inputs without loading both graphs into memory.

| Alternative | Decision and tradeoff |
|---|---|
| Diff graph/JSON IDs and payloads | Retain as an integrity and self-comparison control. It confounds logical edits with snapshot-scoped IDs, changed evidence and missing observations. |
| Immutable inputs + scoped correspondence + capability-specific proofs | Adopt for implementation design. Keep existing IDs, store correspondence and evolution separately, and compare only the scopes supported by authenticated inputs. Costs include a census, additional indexes and explicit proof bookkeeping. |
| Reanalyze both revisions under one toolchain | Optional controlled experiment for drift. It can establish a new comparable pair when inputs exist, but produces new evidence revisions and can require isolated execution. |
| Temporal graph with inferred rename and history | Defer. It changes persistence and migration costs, encourages unsupported continuity and is unnecessary for explicit finite history. |

The [contract](../architecture/m11-compatible-snapshot-evolution.md) owns proposed preimages, semantic rules and consumer interfaces. The v1 design supports same-repository comparison and authenticated producer correspondence. Operator mappings are excluded from canonical input in v1: review annotations may be kept separately, and cannot make a finding certain. A future authenticated mapping producer would need a versioned authority and conflict contract. Definite configuration comparison starts with exact typed correspondence; common-domain extension requires a proof over assignments, feasibility and order. Unproved assignments stay in the census.

Implement behind the analyzer domain and reader ports using existing filesystem/backend/CLI boundaries. No changes to M1/M5/M6 IDs, graph/report formats, score model, release reservations or isolated execution authority are needed. Rolling back the evolution consumer leaves all Track A inputs readable. M11.2 can implement and test the internal design; the additive public schema/CLI/HTTP proposal remains provisional until reviewed for public adoption in M11.5. No new remote access, execution permissions or cross-repository trust model is granted.

Revisit exact matching if labeled misses demonstrate a justified evidence source; revisit reasoner or external indexing only after measured constraints and equivalence checks. Require [registered positive and negative controls](../tasks/m11/PROTOCOL.md), natural-history evidence and independent review before G7. This record describes a design decision and its tradeoffs, not measured engine behavior.
