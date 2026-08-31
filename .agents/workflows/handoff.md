# Handoff

Description: Produce durable, machine-independent project state so another agent, machine, or coding tool can continue without relying on conversation history.

---

# 1. Inspect Reality

Read:

- Git status
- current diff
- recent commits
- current-state
- roadmap
- relevant architecture
- relevant ADRs

---

# 2. Capture Context

Record:

## Objective
What was being solved?

## Phase
Which project phase?

## Current State
What is complete?

## Active Task
What remains?

---

# 3. Capture Decisions

Record:

- important technical decisions
- architecture decisions
- rejected alternatives
- assumptions
- unresolved questions

---

# 4. Capture Evidence

Record:

- tests run
- benchmark results
- important observations
- known failures
- important source/documentation references

---

# 5. Capture Files

Record:

- changed files
- relevant modules
- files that must be inspected next

---

# 6. Capture Risks

Record:

- known bugs
- verification gaps
- architectural risks
- research uncertainty
- environment-specific issues

---

# 7. Define Next Action

Choose the single highest-value next action.

Do not create a giant ambiguous TODO list.

---

# 8. Persist

Update:

docs/current-state.md

Update architecture docs or ADRs when needed.

---

# 9. Cross-Agent Compatibility

The handoff must be understandable by:

- Antigravity
- Claude Code
- Codex
- Gemini
- another human developer

Do not rely on proprietary conversation terminology.

---

# 10. Verification

Ensure the handoff matches repository reality.

Never record "implemented" unless the repository contains the implementation.

---

# FINAL

## Objective
...

## Completed
...

## Decisions
...

## Evidence
...

## Files
...

## Known Issues
...

## Risks
...

## Next Action
...