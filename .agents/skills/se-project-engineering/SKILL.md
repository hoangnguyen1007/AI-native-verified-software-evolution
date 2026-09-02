---
name: se-project-engineering
description: Route non-trivial work in this SE121 repository to applicable roles, workflows, rules, and verification. Use for engineering, research, reviews, benchmarks, and governance changes; skip simple prose edits.
---

# SE Project Engineering

Use the bootstrap, routing table, authority and completion contract in [AGENTS.md](../../../AGENTS.md). Read the selected role/workflow/rules once per task; refresh changed context rather than loading every file.

## Make the task executable

Identify the requested outcome, current gate, approved decisions, affected contracts, non-goals, likely files, and observable exit criteria. Select a route from AGENTS.md without requiring the user to name it.

- Ordinary implementation: preserve approved boundaries and choose reversible details autonomously.
- Consequential uncertainty: define the question and collect only evidence that can change the decision.
- Review: inspect actual artifacts and remain read-only.
- Implementation self-review: fix supported defects inside the authorized scope and reverify.
- Documentation/configuration: validate links, structure, effective behavior or rendered output as relevant; do not invent production tests for prose.

For Java/Spring extraction, ground truth, or semantic benchmark claims, read [semantic-evaluation.md](semantic-evaluation.md). It defines evidence checks, not a replacement for milestone contracts.

## Verification selection

Use this repository's [README](../../../README.md) and the affected module POM/runner to choose commands. Root verification does not cover standalone benchmarks.

Windows examples, after confirming a JDK 21 runtime:
- `.\mvnw.cmd --version`
- `.\mvnw.cmd -B -ntp -pl analyzer test` for analyzer-only behavior.
- `.\mvnw.cmd -B -ntp verify` for reactor integration; use `clean verify` for a clean-build claim.

On POSIX use `sh ./mvnw` with the same arguments. Inspect the actual runner before executing a standalone experiment: tests may regenerate evidence or require separately built artifacts/classpaths.

Use focused checks while iterating. Before handoff verify the final relevant state, inspect the diff, and report what was and was not tested. Avoid reopening a passed check without a changed input or concrete remaining risk.
