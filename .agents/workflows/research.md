# Research

Description: Evidence-driven technical research and decision workflow for reducing consequential project uncertainty.

---

# 1. Define the Decision

Write:

- Decision question
- Why it matters
- Current phase
- Constraints
- Success criteria
- Cost of making the wrong decision
- Reversibility

If the question is too broad, narrow it before researching.

---

# 2. Repository Grounding

Inspect:

- current architecture
- relevant source
- tests
- dependencies
- ADRs
- roadmap
- current-state

Research must answer a project problem, not an abstract technology question.

---

# 3. Build the Candidate Set

Identify:

- simplest viable baseline
- preferred candidate
- strongest alternative

Do not create long technology lists without purpose.

---

# 4. Evidence Collection

Prefer:

1. official documentation
2. official specification
3. official source repository
4. maintainer material
5. reproducible experiment
6. high-quality technical literature

For fast-changing technologies, verify current information.

Classify every important statement:

FACT
OBSERVATION
INFERENCE
HYPOTHESIS

---

# 5. Evaluate

Use project-specific criteria.

Examples:

- correctness
- semantic fidelity
- performance
- memory
- implementation complexity
- maintainability
- ecosystem maturity
- testability
- reproducibility
- research value
- future compatibility
- reversibility

Do not invent precision that the evidence does not support.

---

# 6. EXPERIMENT GATE

If reasoning cannot resolve the decision:

design the smallest possible experiment.

The experiment must define:

- hypothesis
- inputs
- environment
- procedure
- metrics
- expected interpretation

Prefer a focused PoC over speculative full implementation.

---

# 7. Parallel Research

When useful, delegate independent investigations.

Example:

Agent A:
Parser semantic fidelity

Agent B:
Parser performance

Agent C:
Spring-specific capabilities

Agent D:
Migration/future compatibility

Then synthesize results.

Do not send duplicate research tasks.

---

# 8. STOP CONDITION

Stop researching when:

- the decision is sufficiently supported
- the remaining uncertainty is low-impact
- additional research is unlikely to change the decision

Do not continue research indefinitely.

---

# 9. Decision Output

Return:

## Decision Question
...

## Evidence
...

## Alternatives
...

## Experiment
...

## Results
...

## Trade-offs
...

## Recommendation
...

## Confidence
HIGH / MEDIUM / LOW

## Remaining Unknowns
...

---

# 10. Durable State

For consequential decisions:

Create an ADR under:

docs/decisions/

Update research documentation when useful.

Do not rely on chat history for important conclusions.