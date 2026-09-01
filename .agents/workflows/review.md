# Review Workflow

Purpose: independently attack correctness, architecture, security, scope, tests, data quality, and research claims.

## Bootstrap Context

Run Tier 0. Read requirements, diff, relevant source/tests/fixtures, architecture, ADRs, and verification artifacts. Do not rely on the implementer's summary.

## Procedure

1. Attack assumptions and missing edge cases.
2. Check responsibility and dependency boundaries.
3. Check semantic status, identity, evidence, graph invariants, and false relationships where relevant.
4. Check untrusted input, paths, commands, secrets, and sensitive logs.
5. Check tests for false reassurance and missing negative cases.
6. Check benchmark fairness, raw evidence, denominators, leakage, and claim strength.
7. Check scope for later-phase or unrelated additions.
8. Rank findings P0-P3; do not elevate style preferences.

## Verification

Reproduce blocking findings when practical and cite exact locations/criteria.

## State Update

Remain read-only. Identify durable state that would become inaccurate if findings are accepted.

## Output and Handoff

Return blocking findings first, non-blocking findings, verified strengths, missing verification, verdict, remediation, and mandatory handoff.
