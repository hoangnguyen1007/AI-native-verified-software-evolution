# Core Operating Rule — Always On

[AGENTS.md](../../AGENTS.md) owns bootstrap, task routing, authority and completion. Apply it without repeating the same procedure at each workflow step.

- Work from current repository evidence and explicit human decisions. Investigate discrepancies rather than copying stale status.
- Reconstruct the affected contract, implement the smallest coherent change, apply [lean verification](../../docs/testing.md#lean-verification-for-the-demo), and update durable state if it changed. Target execution stays inside the validated sandbox.
- Turn the request into a bounded outcome and observable exit criteria. Short prompts still require relevant contracts and instructions.
- Research only consequential uncertainty; do not reopen approved choices without new evidence.
- Exercise full technical autonomy for implementation details within approved scope. Do not block for routine decisions already authorized.
- Proactively handle real-world complexities (edge cases, degraded environments) through deterministic fallbacks and typed capability gaps rather than halting execution.
- Finish authorized implementation through verification and durable-state updates. A review-only request ends with supported findings, not silent repairs.
- Conserve context and execution budget: use targeted diffs and lean bootstrap for bounded code slices; do not ingest massive historical audits, raw megabyte benchmark logs, or distant-milestone research unless task-critical.
- Avoid redundant testing and quota burn:
  - Run the narrowest targeted test(s) covering modified behavior and directly affected contracts (e.g., `-pl .,backend "-Dtest=ChangedTest,ApiSpecificationTest" test`, `node --check` + targeted JS test). Do NOT rerun unchanged modules (`cli`, unaffected `analyzer-*`, or untouched Headless Edge suites) or full reactors unless a shared boundary changed or a concrete regression risk requires it.
  - After a small surgical fix, verify only the fixed path and affected contract; reuse still-valid live JVM/Docker/Edge evidence from unchanged layers instead of re-executing the entire end-to-end campaign.
  - Keep `/swarm` topologies lean (minimum non-overlapping authors + at most one combined red-team/verification pass) and avoid spawning extra prep/duplicate verifier workers when one focused pass suffices.
- Report blockers and limits honestly. Do not invent a passed gate, test run, native tool, or independent review.
