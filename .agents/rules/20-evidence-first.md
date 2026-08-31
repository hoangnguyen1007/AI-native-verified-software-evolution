# Evidence-First Software Intelligence

Use this rule whenever working on:

- semantic analysis
- dependency analysis
- Software Knowledge Graph
- architecture rules
- architecture violations
- impact analysis
- benchmark evaluation
- analysis reports

---

## 1. Evidence Before Conclusions

Never treat a model inference as equivalent to source evidence.

Every important analysis conclusion should be traceable to repository evidence.

Prefer:

Observed evidence
→ semantic relationship
→ rule evaluation
→ violation

over:

LLM belief
→ violation

---

## 2. Preserve Source Traceability

When extracting semantic information, preserve source location whenever practical:

- file
- line
- column
- source span
- symbol

When the parser provides a stable identity, preserve it.

---

## 3. Graph Relationships Need Provenance

A graph relationship should preserve enough information to answer:

- what is the source node?
- what is the target node?
- why does this relationship exist?
- where in the source code is the evidence?
- was the relationship directly observed or inferred?

Do not fabricate relationships to make the graph look complete.

---

## 4. Distinguish Evidence Types

Prefer explicitly distinguishing:

### DIRECT
Directly observed from source semantics.

### DERIVED
Deterministically derived from known relationships.

### INFERRED
Produced through a heuristic or model inference.

### UNKNOWN
The available evidence is insufficient.

Do not silently convert INFERRED into DIRECT.

---

## 5. Architecture Violations

A violation should ideally contain:

- rule ID
- severity
- source span
- involved symbols
- dependency
- graph path
- evidence
- rule provenance
- diagnostic explanation

If one of these is unavailable, preserve the limitation.

---

## 6. Rule Semantics

Every architecture rule should define:

- intent
- scope
- detection condition
- forbidden condition
- evidence requirement
- severity
- known false-positive cases
- known false-negative cases
- test examples

A rule that cannot explain why it triggered is incomplete.

---

## 7. Explainable Analysis

Prefer results that can be independently inspected.

A good result should allow a developer to move from:

Violation
→ rule
→ graph relationship
→ source location
→ source code

without needing to trust an opaque model response.

---

## 8. Uncertainty

When semantic resolution is incomplete:

- preserve uncertainty
- record unresolved symbols
- avoid fabricated relationships
- expose confidence only when it has a defined interpretation

Never hide analysis incompleteness behind confident language.

---

## 9. Benchmark Integrity

Benchmark results must be reproducible.

Record when applicable:

- repository
- commit/version
- dataset
- analyzer version
- rule version
- configuration
- environment
- execution parameters
- result
- failures

Never manually modify generated benchmark results to improve numbers.

---

## 10. Research Claims

Do not claim:

- higher accuracy
- lower token cost
- better scalability
- fewer false positives
- superior architecture detection

unless there is supporting evidence.

Separate:

FACT
OBSERVATION
INFERENCE
HYPOTHESIS
```

---

# 5. Research + Architecture Rule

Đây là rule biến agent từ “coder” thành **technical researcher/architect**.

### `.agents/rules/30-research-and-architecture.md`

:::writing{variant="document" id="92754"}
# Research and Architecture Decision Protocol

Use this rule for:

- technology selection
- architecture design
- parser selection
- graph database decisions
- major dependency decisions
- schema design
- architectural refactoring
- research methodology
- benchmark design

---

## 1. Start With the Decision

State:

- what decision is being made
- why it matters
- what constraints apply
- what success looks like

---

## 2. Separate Facts From Judgment

For important decisions distinguish:

### FACT
Supported by repository evidence, documentation, experiment, or source.

### OBSERVATION
Directly observed behavior.

### INFERENCE
Reasoned conclusion from available evidence.

### HYPOTHESIS
A claim that requires validation.

Do not blur these categories.

---

## 3. Compare Alternatives

For consequential technical choices, consider at least two viable approaches when practical.

Compare using project-specific criteria.

Examples:

- semantic fidelity
- correctness
- performance
- implementation complexity
- ecosystem maturity
- maintainability
- testing
- reproducibility
- research value
- compatibility with future phases

Do not select technology solely because it is fashionable.

---

## 4. Architecture Evolution

Before introducing a major component ask:

- what problem does it solve?
- why is the current design insufficient?
- what complexity does it add?
- can the same result be achieved more simply?

Avoid premature:

- microservices
- distributed systems
- message queues
- orchestration platforms
- abstraction layers

Unless a real requirement justifies them.

---

## 5. Research-Oriented Experiments

When uncertainty cannot be resolved from reasoning:

1. formulate a small hypothesis
2. define the experiment
3. keep inputs controlled
4. measure the relevant output
5. record the result
6. make the decision from evidence

Prefer a small proof-of-concept over a large speculative implementation.

---

## 6. Architecture Decision Records

Important decisions must become durable project knowledge.

Record:

- Context
- Problem
- Options considered
- Decision
- Why
- Trade-offs
- Consequences
- Reversal conditions

Store significant decisions under:

docs/decisions/

---

## 7. Future-Phase Compatibility

The current phase must remain independent enough to support future work.

The repository roadmap includes later capabilities such as graph-grounded diagnosis, transformation, verification, sandboxing, evidence bundles, and CI/CD.

Do not implement these capabilities prematurely.

Instead, preserve clean interfaces and durable models so later phases can build on the current architecture.

---

## 8. Decision Quality

Prefer decisions that:

- are explainable
- are testable
- are reversible when possible
- preserve future options
- minimize accidental coupling

For irreversible decisions, increase the amount of investigation before implementation.
