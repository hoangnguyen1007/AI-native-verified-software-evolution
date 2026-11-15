# Verify — Evidence for a Specific Claim

Use [verification-before-completion](../skills/verification-before-completion/SKILL.md). Identify requirements, affected inputs and the precise claim being checked.

- Use [lean verification](../../docs/testing.md#lean-verification-for-the-demo): inspect docs without application tests, run one focused behavior check where sufficient, and add only the materially affected consumer or demo journey. Reuse a check at handoff when its inputs did not change.
- Keep evidence compact; no new formal package or raw dump for routine work or demo closeout.
- Inspect exit code, executed cases, skips, errors and output. Zero cases is not a passing behavioral check.
- Broaden only for a material risk or an explicit claim not supported by narrower evidence. Demo delivery does not reopen historical gates.
- For experiments check pinned inputs, environment, identity, provenance, denominators and reproducibility.
- For governance check links, precedence and representative decision paths. Reserve live agent evaluation for a material uncertainty about behavior.
- Classify failures before choosing a remedy.

A verifier assigned independently remains read-only and returns findings. During implementation self-checks, the implementer may repair in-scope issues, then rerun affected checks.

Return commands/results, evidence locations, missing checks, limitations and acceptance against exit criteria. Incorporate these into the task's single [handoff](handoff.md).
