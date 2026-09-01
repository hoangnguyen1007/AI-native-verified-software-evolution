# Research and Architecture Decision Rule

Use for consequential architecture, technology, semantic-model, graph-schema, policy, persistence, API, benchmark, or methodology decisions.

## Decision Contract

Define:

- decision and why it matters
- current phase and constraints
- required capabilities and success evidence
- strongest viable alternatives, including the simple baseline
- trade-offs, reversibility, and cost of being wrong

Classify important statements as fact, observation, inference, hypothesis, or open question.

## Evidence

Prefer repository evidence and focused experiments. For changing external technology, use official current documentation. For research claims, use reproducible evidence and appropriate primary literature. Popularity is not a decision criterion.

Do not invent numeric scores or thresholds. If a subjective comparison is useful, label it subjective. If reasoning is insufficient, run the smallest controlled experiment that can change the decision.

## Architecture Gate

Before implementation, define responsibilities, inputs/outputs, dependencies, owned data, invariants, failure boundaries, test strategy, and replacement strategy. Preserve parser and storage reversibility. Prefer a modular monolith.

Current pressure points that require explicit treatment are multi-module workspaces, safe build-model intelligence, content-addressed analysis identity, storage-neutral architecture query services, and selective bytecode validation only on demonstrated need.

## Durable Decision

Create an ADR only when an approved decision has meaningful alternatives and durable consequences. Include evidence, trade-offs, consequences, confidence, and reversal conditions. Do not create ADR bureaucracy for low-risk reversible details.
