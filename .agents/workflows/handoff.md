---
description: 
---

# Handoff — One Continuable Task Result

Cover every field below, combining related/empty fields in concise prose or a table. Do not emit twelve headings or repeat this handoff for every workflow step.

| Field | Required content |
|---|---|
| STATE BEFORE | Milestone, task, Git state and relevant approvals |
| WORK COMPLETED | Actual artifacts/actions, not intentions |
| FILES CHANGED | Paths or clearly identified groups; link a full change record when useful |
| TESTS / COMMANDS ACTUALLY RUN | Commands/checks, scope and evidence locations |
| RESULTS | Exit outcomes, executed cases, failures and skips |
| NEW EVIDENCE | Newly established facts and their boundaries |
| DECISIONS MADE | Human-approved or routine reversible choices, distinguished |
| DECISIONS STILL REQUIRING HUMAN APPROVAL | Only unresolved consequential choices; none when applicable |
| LIMITATIONS | Unsupported or unverified behavior |
| BLOCKERS | Concrete impediments; none when applicable |
| DURABLE STATE FILES UPDATED | Files and why; none for read-only review |
| EXACT NEXT RECOMMENDED TASK | One bounded action with relevant entry conditions |

## Adaptive Closeout

Do not run the full project closeout protocol for every task.

Choose closeout depth by task impact.

### LIGHT closeout

Use for:

* read-only investigation;
* documentation-only edits;
* small test/fixture changes;
* failed or interrupted implementation with no production-state change;
* intermediate vertical-slice work that does not change a milestone/gate/contract.

Required:

* `git status --short`
* targeted `git diff -- <changed files>`
* run only the directly relevant verification
* concise result + blocker/next action

Do NOT reread all canonical state files, handoff docs, reproducibility manifests, process lists, or unrelated output directories unless needed to resolve uncertainty.

Do NOT update `docs/current-state.md` merely because an intermediate attempt occurred.

### STANDARD closeout

Use for:

* meaningful production implementation;
* bounded feature completion;
* contract-test changes;
* accepted vertical slice.

Required:

* inspect changed diff;
* targeted tests;
* relevant integration/build verification;
* update `docs/current-state.md` only if operational project state changed;
* concise handoff.

### FULL closeout

Use only for:

* milestone completion;
* gate promotion/change;
* ADR or architecture contract change;
* technology decision;
* major benchmark/evidence conclusion;
* substantial multi-agent campaign;
* work immediately preceding a human commit.

FULL closeout may reread canonical state, roadmap/gates, reproducibility evidence, worker status and broader Git state.

Default to the cheapest closeout level that still preserves correctness.

Do not perform ceremonial reads or duplicate checks when the same facts were verified earlier in the current task and have not changed.

Ensure claims match the final affected state and AGENTS.md's document ownership. Distinguish fresh verification from historical evidence. Leave commit/push to the human unless explicitly requested.

