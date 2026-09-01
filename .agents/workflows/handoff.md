# Handoff Workflow

Purpose: make a completed or blocked task continuable without hidden conversation context.

## Bootstrap Context

Run Tier 0 and inspect the current diff, recent commits, relevant contracts, test evidence, and task exit criteria.

## Required Record

### STATE BEFORE

Milestone, task, repository state, and relevant decisions at task start.

### WORK COMPLETED

Only work present in the repository or verified external artifacts.

### FILES CHANGED

Exact changed, added, and deleted files.

### TESTS / COMMANDS ACTUALLY RUN

Exact commands/checks; never infer unexecuted verification.

### RESULTS

Passes, failures, exit codes, and key observations.

### NEW EVIDENCE

Newly established facts and artifact locations.

### DECISIONS MADE

Approved or low-risk reversible decisions, with rationale.

### DECISIONS STILL REQUIRING HUMAN APPROVAL

Small explicit decision set.

### LIMITATIONS

Known unsupported or unverified behavior.

### BLOCKERS

Concrete conditions preventing progress; `none` if there are none.

### DURABLE STATE FILES UPDATED

List and explain why each was updated.

### EXACT NEXT RECOMMENDED TASK

One bounded next action with its entry condition.

## Verification and State Update

Ensure `docs/current-state.md` matches the handoff. Update other durable files only under `AGENTS.md` rules. Confirm no claim depends on chat history.
