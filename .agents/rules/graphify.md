---
trigger: model_decision
description: Cross-module architecture, multi-hop callers, communities, or Markdown headings via graphify-out/graph.json.
---

# Graphify Routing

- **Exact symbol/regex lookup:** use `git grep` (`0.2 s`), not CLI `graphify` (`4-10 s` cold load).
- **Architecture/callers/sections:** run `graphify explain`, `graphify query`, or `graphify section` (CLI only; no MCP/`wiki/`).
- **Refresh:** run `graphify update .` only per batch/milestone, not per edit.
