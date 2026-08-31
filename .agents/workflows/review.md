# Review

Description: Perform an independent adversarial review intended to discover correctness, architecture, security, testing, scope, and research-integrity failures.

---

# 1. Assume Defects Exist

Do not begin from the assumption that the implementation is correct.

Independently inspect:

- task
- diff
- changed files
- relevant code
- tests
- architecture
- ADRs

---

# 2. Correctness Attack

Look for:

- incorrect assumptions
- missing edge cases
- hidden state bugs
- incorrect error handling
- incorrect null/empty handling
- broken caller behavior
- regressions

---

# 3. Architecture Attack

Look for:

- boundary violations
- unexpected coupling
- dependency inversion failures
- misplaced responsibilities
- unnecessary abstractions
- premature infrastructure
- duplicated logic

---

# 4. Semantic Analysis Attack

For analyzer/graph/rule changes inspect:

- semantic completeness
- source traceability
- unresolved symbols
- direct vs inferred relationships
- graph consistency
- evidence preservation
- false positives
- false negatives

---

# 5. Test Attack

Ask:

- What important behavior is not tested?
- Can the implementation pass tests while still being wrong?
- Are tests too tightly coupled to implementation details?
- Are negative cases tested?
- Are regression cases present?

---

# 6. Security Attack

Check:

- secret exposure
- unsafe input
- path traversal
- command injection
- dependency risks
- logging sensitive information
- unsafe repository handling

Treat external repositories as untrusted input.

---

# 7. Research Attack

For research/benchmark code:

- Is the metric valid?
- Is the baseline fair?
- Is the dataset contaminated?
- Are unfavorable results preserved?
- Are claims stronger than evidence?
- Is the experiment reproducible?

---

# 8. Scope Attack

Check whether implementation silently added:

- unrelated refactoring
- infrastructure
- dependency upgrades
- later-phase functionality
- unnecessary abstractions

Reject scope creep.

---

# 9. Severity

P0 — catastrophic

P1 — serious correctness/security/architecture issue

P2 — meaningful problem

P3 — improvement

Do not label style preference as a defect.

---

# 10. Verdict

APPROVE

or

CHANGES REQUIRED

or

BLOCKED

---

# 11. Review Report

## Blocking Findings
...

## Non-Blocking Findings
...

## Verified Strengths
...

## Missing Verification
...

## Verdict
...