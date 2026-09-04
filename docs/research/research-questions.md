# Research Questions

## Source Hierarchy

All research questions are classified by source authority:

1. Explicit human decisions
2. Official project/academic documents provided by the human
3. Verified official technical documentation and reproducible evidence
4. Research literature
5. AI-generated design proposals

Nothing may be promoted to CONFIRMED without evidence from a
higher-priority source or human approval.

## Confirmed Research Questions

_None confirmed yet._

To confirm a candidate research question:
1. Refine to a testable, measurable form
2. Define required evidence and measurable variables
3. Obtain human approval

---

## Candidate Research Questions

### CRQ-1: Knowledge Graph for Architecture Analysis

- **Origin:** AI-generated DOCX (RQ1)
- **Status:** CANDIDATE
- **Phase relevance:** SE121 — potentially relevant to current foundation
- **Rough formulation:** Can a software knowledge graph built from static
  semantic analysis of Java/Spring Boot projects effectively detect
  architecture violations with evidence-backed precision?
- **Motivation:** Core question for the SE121 platform capability
- **Required evidence to confirm:**
  - Testable, measurable refinement of the question
  - Defined dependent/independent variables
  - Defined success criteria
  - Human approval
- **Possible measurable variables:**
  - Precision of architecture violation detection
  - Recall of architecture violation detection
  - Symbol resolution rate
  - Graph construction completeness
  - Source traceability coverage
- **Unresolved concerns:**
  - Needs precise formulation that is falsifiable
  - Scope may be too broad — may need decomposition
  - Ground-truth dataset required for measurement

### CRQ-2: AI-Guided Diagnosis via Graph

- **Origin:** AI-generated DOCX (RQ2)
- **Status:** CANDIDATE
- **Phase relevance:** SE122 — AI diagnosis/RAG phase
- **Notes:** Out of SE121 scope. Parked for future consideration.
- **Required evidence to confirm:** SE122 phase planning

### CRQ-3: Verified Transformation/Evolution

- **Origin:** AI-generated DOCX (RQ3)
- **Status:** CANDIDATE
- **Phase relevance:** KLTN — verification phase
- **Notes:** Out of SE121 scope. Parked for future consideration.
- **Required evidence to confirm:** KLTN phase planning

### CRQ-4: System Optimization/Scalability

- **Origin:** AI-generated DOCX (RQ4)
- **Status:** CANDIDATE
- **Phase relevance:** Future research direction
- **Notes:** Out of SE121 scope. Parked for future consideration.
- **Required evidence to confirm:** Future phase planning

### CRQ-5: Explainable Architecture Assessment

- **Origin:** Human-confirmed SE121 product requirement
- **Status:** CANDIDATE
- **Phase relevance:** SE121 — M5/M6/M9
- **Rough formulation:** Can a versioned, evidence-backed combination of structural metrics and policy findings provide a useful architecture health assessment without conflating repository health with analyzer confidence?
- **Required evidence to confirm:**
  - approved metric definitions and score dimensions;
  - labeled repository/micrograph examples;
  - missing-evidence and adversarial cases;
  - sensitivity and stability analysis across reasonable weights/thresholds;
  - human review of usefulness and explanation quality.
- **Prohibited shortcut:** A visually plausible 0–100 number without formula provenance, confidence separation, and validation is not evidence.

---

## Open Questions (Engineering)

These are engineering uncertainties identified during bootstrap analysis:

| ID | Question | Priority | Status |
|---|---|---|---|
| OQ-1 | Is JavaParser sufficiently correct for the approved production semantic denominator? | P0 | R1 supports PROVISIONAL viability; broader gate pending |
| OQ-2 | Is the proposed Neo4j schema sufficient for all SE121 rule types? | P0 | R2 experiment planned |
| OQ-3 | How should Spring implicit wiring be modeled in the graph? | P0 | [Provisional producer/candidate/injection-point/condition model](../architecture/m4-spring-intelligence.md) and `spring-mechanisms:v1` denominator drafted; M4 identity, fixtures and G3 validation pending |
| OQ-4 | What architecture rules should the MVP detect? | P1 | Research task 1.3 |
| OQ-5 | How should symbol identity work across incremental analysis? | P1 | Design task |
| OQ-6 | What ground-truth datasets exist for architecture violation detection? | P1 | Research task 1.4 |
| OQ-7 | How does JavaParser handle unresolvable dependencies? | P0 | R1 bounded evidence complete; expanded corpus pending |
| OQ-8 | What is the performance envelope for parsing + graph construction? | P2 | R3 experiment planned |
| OQ-9 | Which 2–4 additional Spring Boot repos for validation? | P1 | Research task 1.4 |
| OQ-10 | Which metric definitions and inclusion rules are defensible at repository/module/package/type scopes? | P0 | M1/M5 contract and golden-fixture task |
| OQ-11 | Which score dimensions, weights, caps, and withholding thresholds are stable and explainable? | P0 | M6/M9 labeled examples and sensitivity task |
| OQ-12 | What focused-graph, query, and UI performance budgets are achievable on the registered corpus/reference environments? | P1 | M8/M9 benchmark task |
| OQ-13 | Which frontend/design-system/graph stack best satisfies focused graph, accessibility, and scale requirements? | P1 | Bounded M8 technology evaluation |
| OQ-14 | How should capability gaps, acquisition attempts and conflicting provider observations be versioned, reconciled and projected? | P0 | [Provisional M3+ contract](../architecture/evidence-acquisition.md) drafted; implementation shape and provider-policy evidence pending |

---

## Unvalidated Numeric Claims (from DOCX)

These numbers appeared in the AI-generated DOCX and must NOT be treated
as established facts:

| Claim | Classification | Notes |
|---|---|---|
| 80% symbol resolution target | CANDIDATE — proposed target | Needs R1 PoC to establish actual baseline |
| <3 min Fast Gate | CANDIDATE — experiment parameter | Not part of SE121 scope |
| <8 min Full Gate | CANDIDATE — experiment parameter | Not part of SE121 scope |
| 20–30 repositories | CANDIDATE — proposed scope | Human confirmed 2–4 for initial validation |
| Specific mutation operators | CANDIDATE — future phase | KLTN scope |
| Model routing improvements | CANDIDATE — future phase | SE122+ scope |

---

## Promotion Log

_No promotions yet._

Record format:
```
- [date] CRQ-N promoted from CANDIDATE to HYPOTHESIS
  - Evidence: ...
  - Approved by: ...
```
