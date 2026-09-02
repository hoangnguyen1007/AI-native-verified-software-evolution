---
name: systematic-debugging
description: Investigate a bug, failing test, unexpected semantic result, or performance regression before choosing a fix. Separate code defects from environment, permissions, dependency, and specification failures.
---

# Systematic Debugging

Follow [AGENTS.md](../../../AGENTS.md). Gather enough evidence to identify the failing boundary before changing behavior.

## Investigate

1. Read the complete relevant error, source and expected contract. Reproduce with the smallest case; record command, configuration, input and actual outcome.
2. Check the diff and dependencies. Classify the failure: implementation, test, environment, permissions, dependency, flaky, specification or pre-existing.
3. Trace the failing input through component boundaries. Compare a working case. Use [root-cause-tracing.md](root-cause-tracing.md) for indirect failures.
4. State one falsifiable hypothesis. Change one relevant variable or collect one discriminating observation, then evaluate it.
5. Add a regression test using [test-driven-development](../test-driven-development/SKILL.md), implement the root-cause fix and use [verification-before-completion](../verification-before-completion/SKILL.md).

An obvious documented environment/configuration mismatch does not require a speculative architecture investigation. A permission denial is not a code bug; follow the host's approval flow, never bypass it.

If the failure is not reproducible, preserve the evidence, narrow uncertainty and collect missing diagnostics. Label hypotheses; do not invent a root cause. After repeated failed hypotheses, stop blind edits, reassess the model and seek human input only if a consequential decision or missing information blocks progress.

## Safe diagnostics

Log stage identifiers, status, counts, sanitized paths and selected non-sensitive fields. Never dump environment variables, credentials, full request bodies or raw secret-bearing exceptions.

PowerShell presence check (value must never be emitted):
```powershell
if ([string]::IsNullOrEmpty($env:DIAGNOSTIC_TOKEN)) { 'DIAGNOSTIC_TOKEN=UNSET' }
else { 'DIAGNOSTIC_TOKEN=SET' }
```

Read only the supporting technique needed:
- [defense-in-depth.md](defense-in-depth.md): validation across trust boundaries.
- [condition-based-waiting.md](condition-based-waiting.md): timing/flaky-test investigation.

Diagnostic instrumentation in reviewed code requires implementation authorization. Read-only review may run permitted checks but reports proposed repairs instead of silently editing.
