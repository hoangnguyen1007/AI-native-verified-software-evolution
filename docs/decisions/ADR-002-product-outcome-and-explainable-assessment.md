# ADR-002: Complete Visual Product and Explainable Architecture Assessment

- **Status:** ACCEPTED DIRECTION; detailed formulas and technology choices remain PROVISIONAL
- **Date:** 2026-09-01
- **Decision authority:** Human project owner

## Context

The technical roadmap already required semantic analysis, a Software Knowledge Graph, evidence-backed violations, APIs, and an architecture workbench. It did not explicitly require a detailed inventory dashboard, a governed metric catalog, an explainable architecture score, or measurable graph/user-experience acceptance criteria.

Without a stronger product contract, the project could satisfy its technical milestones while producing only a minimal visualization or an opaque score that is not defensible.

## Decision

SE121 will deliver a complete visual architecture-intelligence platform, not only an analyzer or CLI.

The product must include:

- a detailed repository and analysis dashboard;
- architecture metrics with definitions, versions, scopes, provenance, denominators, and uncertainty;
- a transparent architecture health score with dimension breakdown and evidence;
- a separate analysis-confidence assessment;
- interactive focused architecture graphs;
- evidence-first violation exploration;
- Spring and bounded-impact views;
- compatible snapshot comparison in Track B; and
- reproducible end-to-end product demonstrations.

The canonical contract is [Product Outcome, Metrics, Scoring, and Workbench Contract](../architecture/product-outcome.md).

## Key Safeguard

Architecture health and analyzer confidence are separate. Incomplete evidence may qualify or withhold a score; it must never make a repository appear healthier by hiding dependencies or violations.

## Consequences

### Positive

- Product completion becomes testable rather than subjective.
- Metrics and scores become explainable and reproducible.
- The graph UI is designed around user tasks and bounded projections.
- CLI, API, exports, and UI share stable query contracts.
- Track B comparison inherits compatible metric and score identities.

### Costs

- M1, M5, M6, M8, and M9 gain explicit product and evaluation work.
- Score design requires labeled examples, sensitivity analysis, and human review.
- UI scale, accessibility, and usability require measured verification.

## Rejected Alternatives

### Analyzer and CLI only

Rejected because it does not satisfy the confirmed end-product objective.

### One opaque 0–100 score

Rejected because it creates false authority, hides uncertainty, and is difficult to validate.

### Render the complete raw graph by default

Rejected because non-trivial repositories become unreadable and can overwhelm the browser.

### Mix resolution coverage into architecture health

Rejected because analyzer limitations and repository quality are different concepts.

## Follow-Up Decisions

The human must later approve:

- the metric catalog baseline;
- score formula, weights, thresholds, caps, and comparison rules;
- registered UI/query performance budgets;
- frontend and graph-visualization technologies after bounded evaluation.

