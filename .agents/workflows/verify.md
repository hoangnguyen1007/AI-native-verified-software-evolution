# Verify — Evidence for a Specific Claim

Use [verification-before-completion](../skills/verification-before-completion/SKILL.md). Identify requirements, affected inputs and the precise claim being checked.

- Select the cheapest sufficient test, contract/integration check, build or manual experiment.
- Inspect exit code, executed cases, skips, errors and output. Zero cases is not a passing behavioral check.
- Broaden only for changed consumers, a required gate or a concrete remaining risk.
- For experiments check pinned inputs, environment, identity, provenance, denominators and reproducibility.
- For governance check file/skill/link structure and representative agent behavior; neither proves universal compliance.
- Classify failures before choosing a remedy.

A verifier assigned independently remains read-only and returns findings. During implementation self-checks, the implementer may repair in-scope issues, then rerun affected checks.

Return commands/results, evidence locations, missing checks, limitations and acceptance against exit criteria. Incorporate these into the task's single [handoff](handoff.md).
