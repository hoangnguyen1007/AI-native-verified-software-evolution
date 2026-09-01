# Verify Workflow

Purpose: independently test whether a change meets its requirements and exit criteria.

## Bootstrap Context

Run Tier 0. Read task requirements, diff, changed files, relevant contracts/ADRs, source, tests, schemas, fixtures, and claimed evidence directly.

## Procedure

1. Establish baseline and expected behavior.
2. Run the cheapest high-signal check first.
3. Progress through syntax/compile, targeted tests, contract/integration tests, broader suite, static/build checks, and behavioral validation as relevant.
4. Test high-risk negative/adversarial cases.
5. Classify every failure: implementation, test, environment, dependency, flaky, specification, or pre-existing.
6. Verify final diff scope, generated files, secrets, and durable-state accuracy.

## Research and Data Checks

For semantic/benchmark work verify identities, source evidence, denominators, uncertainty, metric computation, raw results, determinism, and reproducibility.

## State Update

Do not modify reviewed production files. Report evidence that requires the owner to update current state or another durable document. If explicitly assigned the documentation update, record only verified facts.

## Output and Handoff

Return exact checks, pass/fail evidence, root causes, gaps, regression risk, confidence, verdict, and mandatory handoff.
