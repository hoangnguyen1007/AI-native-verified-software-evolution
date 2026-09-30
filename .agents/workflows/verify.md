# Verify — Evidence for a Specific Claim

Use [verification-before-completion](../skills/verification-before-completion/SKILL.md). Identify requirements, affected inputs and the precise claim being checked.

- Select the cheapest sufficient test, contract/integration check, build or manual experiment. Follow AGENTS.md's two-tier targeted verification: method/small-class inner loops, then a bounded pre-handoff set covering changed behavior and direct consumers across all affected modules, in quiet mode.
- Never generate ceremonial `reproducibility/` folders, multi-thousand-line hash manifests, or raw output JSON dumps for intermediate slices.
- Inspect exit code, executed cases, skips, errors and output. Zero cases is not a passing behavioral check.
- Broaden to full reactor or formal reproducibility packages ONLY for milestone completion gates or explicitly authorized benchmark campaigns.
- For experiments check pinned inputs, environment, identity, provenance, denominators and reproducibility.
- For governance check file/skill/link structure and representative agent behavior; neither proves universal compliance.
- Classify failures before choosing a remedy.

A verifier assigned independently remains read-only and returns findings. During implementation self-checks, the implementer may repair in-scope issues, then rerun affected checks.

Return commands/results, evidence locations, missing checks, limitations and acceptance against exit criteria. Incorporate these into the task's single [handoff](handoff.md).
