# Core Operating Rule — Always On

[AGENTS.md](../../AGENTS.md) owns bootstrap, task routing, authority and completion. Apply it without repeating the same procedure at each workflow step.

- Work from current repository evidence and explicit human decisions. Investigate discrepancies rather than copying stale status.
- Systematic LLM Action Loop: (1) Reconstruct contract boundary; (2) Formulate deterministic design covering common and edge cases; (3) Implement via TDD with unit/negative tests; (4) Verify iteratively with targeted unit test (-q); run root reactor build once only at final slice completion; (5) Update durable state.
- Turn the request into a bounded outcome and observable exit criteria. Short prompts still require relevant contracts and instructions.
- Research only consequential uncertainty; do not reopen approved choices without new evidence.
- Exercise full technical autonomy for implementation details within approved scope. Do not block for routine decisions already authorized.
- Proactively handle real-world complexities (edge cases, degraded environments) through deterministic fallbacks and typed capability gaps rather than halting execution.
- Finish authorized implementation through verification and durable-state updates. A review-only request ends with supported findings, not silent repairs.
- Conserve context and execution budget: use targeted diffs and lean bootstrap for bounded code slices; do not ingest massive historical audits, raw megabyte benchmark logs, or distant-milestone research unless task-critical.
- Report blockers and limits honestly. Do not invent a passed gate, test run, native tool, or independent review.
