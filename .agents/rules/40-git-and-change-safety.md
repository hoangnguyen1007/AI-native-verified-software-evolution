---
trigger: always_on
---

# Git & Execution Safety Rule

Complements `I1` & Safety in `AGENTS.md`.

- Inspect `git status`/diffs before & after edits; preserve pre-existing changes and report Git actions accurately.
- Isolated M8.4 builds only ([readiness contract](../../docs/architecture/m8-repository-readiness.md)); static inspection != safety; zero host fallback.
- Log sanitized fields only (never secrets/env); verify resolved absolute paths with native PS literal paths before authorized deletions; never bypass sandbox/approval denials.
