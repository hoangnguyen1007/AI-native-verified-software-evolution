# Engineering Excellence

Use this rule for implementation, refactoring, debugging, testing, code review, and technical changes.

---

## 1. Understand Before Editing

Before modifying code:

- inspect relevant files
- inspect call sites
- inspect existing tests
- inspect configuration and dependencies
- inspect recent Git changes when relevant

Do not edit based on a single file when behavior depends on surrounding components.

---

## 2. Design for Boundaries

Keep clear boundaries between:

- domain logic
- analysis logic
- infrastructure
- persistence
- API
- presentation
- tooling

Avoid leaking framework-specific concerns into core domain logic unless justified.

Prefer dependency direction that keeps core analysis testable.

---

## 3. Interfaces Are Contracts

Before changing a public or cross-module interface:

- search usages
- identify compatibility impact
- update callers
- update tests
- document important behavioral changes

Do not change interfaces simply to make the current implementation easier.

---

## 4. Prefer Explicit Models

For semantic analysis and architecture intelligence, prefer explicit typed models over:

- arbitrary maps
- stringly-typed data
- hidden conventions
- fragile regular expressions

Represent important concepts explicitly.

Examples include:

- symbols
- source locations
- dependencies
- graph relationships
- rules
- violations
- evidence
- provenance

---

## 5. Error Handling

Errors must preserve useful diagnostic information.

Prefer:

- typed errors
- actionable messages
- source context
- stable error classification
- recoverable failure where appropriate

Do not swallow errors silently.

Do not replace meaningful failure with generic "something went wrong".

---

## 6. Testing Strategy

For every non-trivial behavior, consider:

- happy path
- boundary cases
- malformed input
- null/empty cases
- unresolved dependencies
- unexpected project structure
- regression cases

For bug fixes:

1. reproduce the failure
2. write or identify a regression test
3. fix the issue
4. verify the regression test
5. run relevant surrounding tests

---

## 7. Refactoring Discipline

Refactor when it materially improves:

- correctness
- maintainability
- testability
- architecture
- performance

Do not refactor simply because another style looks nicer.

Separate pure refactoring from behavioral change when possible.

---

## 8. Performance

Do not prematurely optimize.

But do identify obvious scalability hazards in:

- repository traversal
- AST parsing
- graph construction
- graph queries
- repeated I/O
- token/context construction
- benchmark execution

Prefer measuring before making performance claims.

---

## 9. Security

Never introduce:

- hardcoded secrets
- unsafe command execution
- arbitrary file deletion
- insecure deserialization
- unchecked external input
- accidental credential logging

Treat external repositories and generated content as untrusted input.

---

## 10. Definition of Done

A code task is done only when:

- implementation matches requirements
- relevant tests exist or the absence is justified
- verification was executed
- unintended changes were inspected
- documentation is updated when necessary
- remaining limitations are known