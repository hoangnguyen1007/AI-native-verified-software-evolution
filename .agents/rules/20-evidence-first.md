---
trigger: always_on
---

# Evidence-First Rule

Complements `I2-I5` in `AGENTS.md`; procedures in [semantic evaluation](../skills/se-project-engineering/semantic-evaluation.md).

- Trace `source/build -> semantic fact -> graph -> rule -> violation`; preserve symbols, full spans, origin, status, derivation, uncertainty, diagnostics, and provenance.
- Never invent coordinates or hide provenance failures; prohibited uncertainty cannot support a certain violation.
- Regenerate summaries from raw artifacts, never hand-edit; report `Cold`, `Warm NO_CHANGE`, and `Warm 1-File Edit` (SHA-256 parity) separately and verify live `HEAD` before citing masterplan claims.
