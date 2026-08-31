# Implement

Description: Implement an approved technical change with controlled scope, continuous verification, and high-quality project hygiene.

---

# 1. CONTEXT LOAD

Read:

- AGENTS.md
- relevant Rules
- relevant Skills
- current-state
- architecture docs
- relevant ADR
- task requirements

Do not rely on conversation history alone.

---

# 2. REPOSITORY INSPECTION

Inspect:

- target files
- neighboring modules
- callers
- interfaces
- tests
- dependencies
- build configuration
- Git state

Identify existing patterns before introducing new ones.

---

# 3. IMPLEMENTATION CONTRACT

Write a concise internal contract:

## Goal
...

## Files
...

## Interfaces
...

## Behavior
...

## Tests
...

## Non-goals
...

---

# 4. PRE-CODE RISK CHECK

Check for:

- architecture boundary changes
- data model changes
- public API changes
- security implications
- migration requirements
- benchmark implications

If an unapproved consequential architecture decision is discovered:

call /architect.

Do not hide architecture work inside implementation.

---

# 5. TEST-FIRST WHERE VALUABLE

For behavior that can be specified clearly:

- add or update tests before implementation when practical

For bug fixes:

- reproduce
- create regression coverage
- fix
- verify

Do not create meaningless tests solely to increase coverage numbers.

---

# 6. IMPLEMENT MINIMAL COHERENT CHANGE

Do:

- preserve existing design where reasonable
- reuse established patterns
- add only necessary abstractions
- preserve diagnostics and evidence
- maintain source traceability

Do not:

- rewrite unrelated code
- upgrade dependencies opportunistically
- introduce speculative frameworks
- perform broad cleanup

---

# 7. CONTINUOUS VERIFICATION

After each meaningful implementation unit:

run the narrowest useful verification.

Examples:

- parser change → parser tests
- graph model → graph tests
- API → API tests
- architecture rule → positive/negative rule tests

Do not wait until the very end to discover obvious breakage.

---

# 8. FAILURE LOOP

When a check fails:

1. read the actual failure
2. classify it
3. identify root cause
4. fix root cause
5. rerun the failing check
6. rerun affected surrounding tests

Never blindly patch error messages.

---

# 9. FINAL VERIFICATION

Run the strongest practical checks.

At minimum where applicable:

- targeted tests
- broader tests
- build
- static analysis
- final diff review

Record exactly what ran.

---

# 10. DIFF AUDIT

Inspect:

- modified files
- added files
- deleted files
- accidental formatting
- generated files
- debug code
- secrets
- unrelated changes

---

# 11. DOCUMENTATION

Update only the durable knowledge affected by the implementation:

- current-state
- architecture docs
- ADRs
- benchmark methodology

Do not generate documentation noise.

---

# 12. COMPLETION REPORT

## Implemented
...

## Tests
...

## Verification
...

## Changed Files
...

## Known Limitations
...

## Remaining Risk
...