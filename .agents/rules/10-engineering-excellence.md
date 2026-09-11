# Engineering Rule — Technical Changes and Reviews

- Dependencies point toward stable domain contracts. Keep parser, build, Spring, storage, policy, query and presentation responsibilities distinct.
- Inspect callers, contracts, failure modes and tests before changing behavior. Add an abstraction only for an actual boundary or justified replacement.
- Prefer immutable typed values; preserve diagnostics, uncertainty, source evidence and deterministic ordering.
- Test behavior/invariants comprehensively across common and pathological edge cases: negative inputs, malformed structures, cyclic dependencies, missing artifacts, and timeout scenarios. Semantic/rule changes need false-positive controls; cross-module changes need contract checks.
- Handle real-world variation proactively: design deterministic fallback algorithms or typed capability gap mappings rather than discarding valid partial evidence.
- Derive expected results independently. Do not substitute successful parsing/resolution or a mock assertion for domain correctness.
- For a bug: reproduce, identify the root cause, add regression evidence, fix, and verify affected consumers.
- Do not add unneeded concurrency, speculative bytecode analysis or heavy infrastructure without a demonstrated need and relevant correctness checks.
- Use the affected build/runner's documented commands. A root reactor build does not cover standalone benchmark projects.
- Keep changes coherent and proportionate; no unrelated cleanup or dependency upgrades.
