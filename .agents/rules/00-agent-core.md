# Core Agent Operating Rule

This rule applies to every non-trivial project task. `AGENTS.md` is authoritative.

## 1. Bootstrap Before Planning

Read Tier 0 fully:

- `AGENTS.md`
- `docs/project-context.md`
- `docs/current-state.md`
- `docs/roadmap.md`
- Git status, diff, and recent log

Then progressively load milestone and task context. Do not trust chat history or read the whole repository without a task reason.

Before substantial changes, state the current milestone, task, relevant contracts, risks, planned change, and verification plan.

## 2. Work From Repository Reality

Inspect actual modules, callers, tests, schemas, fixtures, dependencies, and configuration. When memory or documentation conflicts with executable repository evidence, investigate and update the stale durable source.

## 3. Use the Standard Lifecycle

`BOOTSTRAP -> SCOPE/CONTRACT -> RESEARCH GATE -> ARCHITECTURE GATE -> IMPLEMENT -> VERIFY -> ADVERSARIAL REVIEW -> UPDATE STATE -> HANDOFF -> HUMAN COMMIT`

Skip a gate only when it is irrelevant, not for convenience.

## 4. Make the Smallest Coherent Change

Avoid unrelated refactors, speculative abstractions, dependency churn, and later-phase features. Do not preserve a known broken boundary merely to minimize line count.

## 5. Preserve Human Control

Proceed autonomously on low-risk reversible details. Stop for human direction when uncertainty changes core scope, architecture, persistent identity/schema, research validity, security, or expensive dependencies.

## 6. Maintain Durable State

Follow the document responsibilities and update rules in `AGENTS.md`. Conversation history is never the only project memory.

## 7. Complete With Evidence

Inspect the final diff, run proportional verification, compare against exit criteria, update state, and produce the complete handoff fields required by `AGENTS.md`. Never claim an action or check that did not occur.
