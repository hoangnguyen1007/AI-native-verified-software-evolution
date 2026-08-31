# Git and Change Safety

## 1. Always Know Repository State

Before substantial changes, inspect:

- current branch
- working tree
- staged changes
- relevant recent commits

Do not assume the repository is clean.

---

## 2. Preserve User Work

Never overwrite, discard, reset, or revert user changes unless explicitly requested.

If unrelated modifications already exist:

- preserve them
- isolate your own changes
- avoid formatting entire files unnecessarily

---

## 3. Git Discipline

Prefer small meaningful commits.

Commit messages should describe intent.

Examples:

- feat: add Java symbol extraction
- fix: preserve source span for nested classes
- test: add circular dependency regression cases
- refactor: isolate graph persistence adapter
- docs: record parser evaluation decision

---

## 4. Dangerous Operations

Do not perform without explicit approval:

- git reset --hard
- force push
- rewriting history
- mass deletion
- destructive repository cleanup
- deleting branches with potentially useful work

---

## 5. Generated Files

Do not commit generated artifacts unless the repository explicitly treats them as source-controlled artifacts.

Understand their source before modifying them.

---

## 6. Secrets

Never commit:

- API keys
- passwords
- private tokens
- credentials
- private certificates
- local secret files

If a secret is accidentally discovered:

1. do not expose it
2. do not copy it into logs
3. recommend rotation when appropriate
4. prevent it from entering Git

---

## 7. Diff Review

Before considering a substantial task complete:

- inspect git diff
- inspect changed files
- verify no unrelated modifications
- verify no secrets
- verify no accidental generated files
- verify tests correspond to the intended change

---

## 8. Commit Boundaries

Do not mix unrelated work into one commit.

Prefer:

feature
→ tests
→ documentation

or

fix
→ regression test
→ fix

A commit should represent a coherent unit of reasoning.

---

## 9. Never Claim Git Actions You Did Not Perform

Do not state that code was committed, pushed, merged, or deployed unless the corresponding action actually occurred.