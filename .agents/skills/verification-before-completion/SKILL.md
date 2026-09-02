---
name: verification-before-completion
description: Verify a final implementation, fix, benchmark claim, or milestone result before reporting success. Match evidence to the claim and affected inputs; report missing checks without inventing confidence.
---

# Verification Before Completion

A completion claim needs evidence from the relevant final state, not confidence or another agent's summary.

1. Identify the claim and the check that can establish it.
2. Run the narrowest sufficient check; broaden for affected integration/contracts or remaining risk.
3. Inspect exit code, executed test count, failures, errors, skips and relevant output. Zero/all-skipped tests do not establish behavior.
4. Inspect the final diff and compare against exit criteria.
5. Report passes, failures and checks not run, with scope and limitations. Update durable state under [AGENTS.md](../../../AGENTS.md).

## Match evidence to claims

| Claim | Required evidence |
|---|---|
| Regression fixed | Original failing behavior passes; demonstrate baseline failure when feasible |
| Tests pass | Relevant suite actually executed against the final affected state |
| Build succeeds | Build command completes successfully; call it clean only after a clean build |
| Semantic accuracy | Independent labeled expectations and explicit denominator/outcomes |
| Reproducible experiment | Pinned inputs, environment, commands and preserved raw outputs |
| Agent completed | Actual artifacts/diff plus relevant checks |
| Governance improved | Structural checks plus representative behavior evaluation; no claim of universal compliance |

A command run earlier in the same task remains valid if its inputs, configuration and toolchain have not changed. Re-run affected checks after relevant edits. Never describe historical results as freshly executed; unrelated documentation changes do not require repeating an unchanged build.

For a read-only review, findings and missing verification are valid outputs. For implementation self-checks, repair supported in-scope failures and reverify. Do not revert shared/user code solely to demonstrate red; use an isolated baseline when needed.
