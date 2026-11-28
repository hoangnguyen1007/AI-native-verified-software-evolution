---
trigger: model_decision
description: Activate when citing, searching, comparing, or auditing SOTA research papers, DOIs, arXiv IDs, benchmark tables, docs/paper/, docs/research/sota-19-paper-deep-synthesis.md, or docs/evo-sota-superplan-v2.md.
---

# SOTA Paper Library Rule (On-Demand)

This repository maintains a curated 19-paper SOTA research corpus in [docs/paper/](../../docs/paper/) organized across four architectural pillars aligned with Evo.

## Lookup Order (Conserve Context — Never Dump Whole PDFs First)

1. **Metadata, metrics, visual-audit notes & page-jump map:** Query [docs/paper/catalog.json](../../docs/paper/catalog.json) first for `id`, `original_file` ↔ `canonical_file`, `pillar`, `doi`, `arxiv_id`, `sha256`, `version_type`, `section_map`, `key_tables_pages`, `algorithms_pages`, `key_benchmark_metrics`, `visual_audit_notes`, `evo_transfer_tags`, `evo_target_classes`, `deep_takeaway`, and `citation_ieee`.
2. **Human/agent overview & per-paper takeaways:** Read [docs/paper/README.md](../../docs/paper/README.md) for the master lookup table, verified visual-audit errata, and per-paper technical takeaways mapped to Evo classes.
3. **Cross-paper synthesis & Evo SuperPlan:** Consult [docs/research/sota-19-paper-deep-synthesis.md](../../docs/research/sota-19-paper-deep-synthesis.md) for full cross-paper technical comparisons/ablations and [docs/evo-sota-superplan-v2.md](../../docs/evo-sota-superplan-v2.md) for the prioritized Evo implementation roadmap before re-deriving SOTA designs.
4. **Raw PDF inspection only by exact page:** Open a PDF in `docs/paper/<canonical_file>` only when verifying an exact equation, algorithm, figure, or table cell—jump directly to the page number recorded in `catalog.json` (`section_map` / `key_tables_pages`).

## Quick CLI Lookup (PowerShell)

```powershell
# Resolve any original ID, citation key, or keyword to canonical file, sections, and table pages
python -c "import json; d=json.load(open('docs/paper/catalog.json',encoding='utf-8'))['papers']; q='3728896'; print(json.dumps([p for p in d if q.lower() in json.dumps(p).lower()], indent=2))"
```

## Canonical Citation Keys & Original-ID Map (19 Papers)

| Citation Key (`id`) | Original File | Canonical Filename (`docs/paper/...`) | Pillar | Venue / Year | DOI / arXiv |
|---|---|---|---|---|---|
| `schneider2025microsar` | `2412.08352v1.pdf` | `2025_EMSE_Schneider-MicroSAR_2412.08352.pdf` | P1 | EMSE 2025 | `10.1007/s10664-025-10686-2` / `arXiv:2412.08352v1` |
| `micans2025` | `3728896.pdf` | `2025_ISSTA_Micans_3728896.pdf` | P1 | ISSTA 2025 | `10.1145/3728896` |
| `rpcshield2026` | `3830418.3843878.pdf` | `2026_SOSP_RPCShield_3830418.3843878.pdf` | P1 | SOSP 2026 | `10.1145/3830418.3843878` |
| `semref2026` | `2607.23774v1.pdf` | `2026_ICSE_SemRef_2607.23774.pdf` | P1 | ICSE 2026 | `10.1145/3744916.3773114` / `arXiv:2607.23774v1` |
| `ssar2026` | `3744916.3773263.pdf` | `2026_ICSE_SSAR_3744916.3773263.pdf` | P1 | ICSE 2026 | `10.1145/3744916.3773263` |
| `ripple2025` | `2510.15490v2.pdf` | `2025_arXiv_Ripple_2510.15490.pdf` | P1 | arXiv 2025 (v2 2026) | `arXiv:2510.15490v2` |
| `jackee2020` | `3385412.3386026.pdf` | `2020_PLDI_JackEE_3385412.3386026.pdf` | P2 | PLDI 2020 | `10.1145/3385412.3386026` |
| `jasmine2022` | `3551349.3556910.pdf` | `2022_ASE_Jasmine_3551349.3556910.pdf` | P2 | ASE 2022 | `10.1145/3551349.3556910` |
| `mecheck2025` | `3715772.pdf` | `2025_FSE_MeCheck_3715772.pdf` | P2 | FSE 2025 | `10.1145/3715772` |
| `sundermann2026pbddnnf` | `2505.05976v1.pdf` | `2026_TSE_Sundermann-PB-dDNNF_2505.05976.pdf` | P2 | IEEE TSE 2026 | `arXiv:2505.05976v1` |
| `salt2027` | `2610.41617v1.pdf` | `2027_ICSE_PromptLessInferMore_2610.41617.pdf` | P2 | ICSE 2027 | `arXiv:2610.41617v1` |
| `repograph2025` | `2410.14684v2.pdf` | `2025_ICLR_RepoGraph_2410.14684.pdf` | P3 | ICLR 2025 | `arXiv:2410.14684v2` |
| `codexgraph2025` | `2408.03910v3.pdf` | `2025_NAACL_CodexGraph_2408.03910.pdf` | P3 | NAACL 2025 | `arXiv:2408.03910v3` |
| `chinthareddy2026dkb` | `2601.08773v1.pdf` | `2026_arXiv_Chinthareddy-DKB_2601.08773.pdf` | P3 | arXiv 2026 | `arXiv:2601.08773v1` |
| `repoprobe2026` | `2608.04783v2.pdf` | `2026_ASE_RepoProbe_2608.04783.pdf` | P3 | ASE 2026 | `10.1145/3832783.3837452` / `arXiv:2608.04783v2` |
| `praxis2027` | `2608.19784v2.pdf` | `2027_ICSE_PRAXIS_2608.19784.pdf` | P3 | ICSE 2027 | `arXiv:2608.19784v2` |
| `refagent2026` | `2511.03153v2.pdf` | `2026_ICSE_RefAgent_2511.03153.pdf` | P4 | ICSE 2026 | `arXiv:2511.03153v2` |
| `swerefactor2026` | `2602.03712v1.pdf` | `2026_arXiv_SWE-Refactor_2602.03712.pdf` | P4 | arXiv 2026 | `arXiv:2602.03712v1` |
| `causalrepair2026` | `2608.10613v1.pdf` | `2026_ISSTA_CausalRepair_2608.10613.pdf` | P4 | ISSTA 2026 | `10.1145/3832225` / `arXiv:2608.10613v1` |

## Citation & Integrity Rules

- Always copy `citation_ieee`, `doi`, and `arxiv_id` directly from `docs/paper/catalog.json`; never invent page numbers, DOIs, or venue strings (notably `causalrepair2026` confirmed DOI is `10.1145/3832225`, never `10.1145/3832252`).
- Distinguish camera-ready copies, accepted author copies, and arXiv preprints (`version_type`, `pdf_metadata_note`, and `visual_audit_notes` in `catalog.json`) whenever citing exact wording, equations, or table numbers.
- Heavy binary PDFs in `docs/paper/*.pdf` (`30.67 MB`) are `.gitignore`d locally; `docs/paper/catalog.json` and `docs/paper/README.md` are the Git-tracked source of truth.
- Never rename, delete, or add PDFs in `docs/paper/` without updating `docs/paper/catalog.json`, `docs/paper/README.md`, and this rule in the same change.
