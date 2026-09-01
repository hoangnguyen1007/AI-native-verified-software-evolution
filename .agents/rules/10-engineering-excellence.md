# Engineering Excellence Rule

Use this rule for implementation, refactoring, debugging, testing, infrastructure, and technical review.

## Boundaries

- Keep domain, semantic adapters, Spring enrichment, graph construction, storage, query services, API, and presentation distinct.
- Core contracts must not depend on JavaParser, Neo4j, Spring Boot, or UI types.
- Backend/UI domain behavior goes through stable query services.
- Add an abstraction only when it protects a real boundary or expected replacement.

## Implementation

- Inspect callers, tests, dependencies, and failure modes before editing.
- Prefer explicit immutable domain models to maps and string conventions.
- Preserve typed diagnostics, source evidence, uncertainty, and provenance.
- Treat external repositories and build files as untrusted input.
- Do not execute arbitrary build lifecycle code.
- Do not add caching, concurrency, or bytecode analysis without measured need and correctness tests.

## Testing

For meaningful behavior cover the normal path, negative path, boundaries, malformed input, unresolved/ambiguous cases, and regressions. Semantic/rule behavior requires positive and negative fixtures. Cross-module contracts require contract tests.

For a bug fix: reproduce, add regression evidence, fix the root cause, rerun the failure, then run affected broader checks.

## Definition of Done

The implementation matches the approved contract; relevant tests exist or their absence is justified; verification ran; diff and security were inspected; durable documentation is current; limitations and risks are explicit.
