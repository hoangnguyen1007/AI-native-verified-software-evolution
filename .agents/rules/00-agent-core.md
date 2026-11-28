---
trigger: always_on
---

# Core Operating Rule

Specializes [AGENTS.md](../../AGENTS.md); do not repeat bootstrap per step.

- Exercise full technical autonomy within approved scope; handle edge cases via deterministic fallbacks or typed capability gaps.
- Review-only tasks end with findings (no silent edits); authorized edits finish through verification and durable-state updates.
- Keep `/swarm` minimal (non-overlapping authors + <=1 verification pass); reuse still-valid evidence across unchanged layers.
- **Fast Verification (`T0-T4`, [fast-verification](../skills/fast-verification/SKILL.md), [docs/testing.md](../../docs/testing.md#lean-verification-for-the-demo)):** use narrowest tier; on PS 5.1 multi-module Maven run `.\mvnw.cmd -o -B -ntp -pl .,... '-Dtest=A,B' '-Dtest.failIfNoTests=false' '-Dtest.failIfNoSpecifiedTests=false' test` (default `target/`, `git diff --name-only` before skipping `-am`, `Tests run: 0` = HARD FAIL).
- Report blockers/limits honestly; never invent passed gates or reviews.
