# Research Questions

## Status and Authority

The project owner authorized evidence-based selection and documentation of a stronger research direction on 2026-09-09. [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md) records staged conditional architecture semantics as the accepted architecture direction.

Acceptance of the direction is not empirical confirmation of its hypotheses. Prevalence, accuracy, practical cost, novelty and product benefit remain to be measured. The evidence synthesis and corrections to the original proposal are in the [conditional-architecture redirection review](2026-09-09-conditional-architecture-redirection-review.md).

## Source Hierarchy

1. Explicit human decisions
2. Official project/academic documents provided by the human
3. Verified repository evidence and reproducible experiments
4. Official technical documentation/specifications
5. Peer-reviewed research
6. AI-generated proposals and forecasts

No lower-authority proposal becomes a fact merely by entering the roadmap.

## Research Identity

Umbrella:

> **AI-Native Verified Software Evolution: Staged Conditional Architecture Semantics and Evidence-Driven Assurance for Framework-Managed Systems**

SE121 focus:

> **Configuration-Hidden Architecture Drift in Spring Boot: Phase/Order-Aware Conditional Semantics, Counterexample-Carrying Findings, and Empirical Evaluation**

The testable object is not “a graph with AI” or “using SAT.” It is whether versioned framework-aware conditional semantics can classify and reproduce architecture facts/findings that vary across a bounded configuration space without false universal claims.

## Primary Research Questions

### RQ1 — Prevalence and Taxonomy

> In registered Spring Boot corpora, how often and in what ways does analysis of one realized configuration misclassify, overstate or under-qualify framework-managed architecture facts and policy findings?

- **Phase:** SE121 Track A / M4E–M9
- **Independent variables:** repository/framework version, condition class, mechanism family, chosen realized configuration
- **Dependent variables:** missed conditional facts, false-unconditional warnings, false certainty, mechanism/reason distribution
- **Required evidence:** preregistered corpus, closed mechanism denominator, adjudicated fixtures/repository cases, failures included
- **Falsification/simplification:** if variation is rare in a representative corpus, narrow the domain and claim rather than extrapolating

### RQ2 — Semantic Effectiveness

> Does phase/order-aware conditional semantics improve fact, binding and architecture-finding classification over Java-static, one-realized-configuration and flat presence-condition baselines?

- **Phase:** SE121 Track A / M4E–M9
- **Independent variable:** analysis approach
- **Dependent variables:** precision, recall, F1, MUST/MAY/NEVER/UNKNOWN confusion, false-certainty rate
- **Required controls:** profile/property/classpath cases; ordered on-bean/missing-bean/single-candidate interactions; opaque/dynamic negatives; pinned framework versions
- **Simplification trigger:** if the flat model is equivalent on the registered supported fragment, prefer it and reduce staged machinery

### RQ3 — Witness Correctness and Practical Cost

> Can the analyzer generate deterministic, minimal and replayable witness/counter-witness configurations and affected regions for conditional facts/findings at practical cost?

- **Phase:** SE121 Track A+B / M4D–M9–M11
- **Dependent variables:** witness validity, minimality, affected-region precision/recall, time, memory, branch/solver exhaustion and digest agreement
- **Required baseline:** exhaustive enumeration on microfixtures/small spaces
- **Failure rule:** timeout/limit/opaque residue yields UNKNOWN, never a guessed witness or definite result

### RQ4 — Conditional Architecture Evolution

> How frequently do real Spring changes alter architecture conformance only within a subset of compatible modeled configurations, and can the platform reproduce the affected region and evidence delta?

- **Phase:** SE121 Track B / M11
- **Dependent variables:** detected/reproduced region changes, quantifier transitions, introduced/resolved classification correctness, evidence-drift errors
- **Required evidence:** labeled historical snapshot pairs with compatible build/configuration/framework semantics
- **Safety invariant:** evidence or configuration-space loss cannot count as a resolved violation

### RQ5 — Selective Evidence Acquisition

> Does architecture/witness-directed runtime or isolated evidence resolve uncertainty with fewer bounded context executions than exhaustive, random or covering-array selection?

- **Phase:** Candidate Track C/post-SE121
- **Status:** HYPOTHESIS, not core novelty
- **Promotion condition:** explicit provider authorization/sandbox contract plus statistically and practically meaningful reduction without lower answer quality
- **Kill condition:** if no useful reduction appears, retain runtime only as a benchmark oracle or diagnostic provider

### RQ6 — Evidence-Grounded AI Evolution

> Do deterministic facts, capability gaps, conditional regions and witnesses improve AI-generated architecture explanations/change plans and verified repair outcomes over repository-text-only context?

- **Phase:** Post-M12 verified-AI horizon
- **Status:** CANDIDATE
- **Required evidence before activation:** completed Track A+B authority, representative change benchmark, isolated transformation verification and explicit human phase approval
- **Boundary:** AI output is a proposal; deterministic facts and verification evidence remain authoritative

## Supporting Product Research Question

### RQ7 — Explainable Conditional Assessment

> Can a versioned combination of structural metrics and configuration-qualified policy findings provide a useful architecture health assessment without conflating repository health, modeled-space coverage and analyzer confidence?

- **Phase:** SE121 M5–M10
- **Required evidence:** precise metric/score semantics, labeled examples, conditional/unknown cases, sensitivity/stability analysis and user evaluation of explanation/witness workflows
- **Prohibited shortcut:** no polished 0–100 value without formula, region, evidence and withholding provenance

## Baseline Contract

Baselines are selected by comparable question, not reputation:

1. Java static dependency graph without Spring enrichment;
2. Spring analysis at one realized configuration;
3. flat presence-condition semantics without ordered bean state;
4. ArchUnit for equivalent bytecode/static rules;
5. Spring Modulith for equivalent module-structure rules;
6. Jasmine when its artifact, supported framework version and evaluation can be reproduced;
7. the proposed phase/order-aware model.

ArchUnit and Spring Modulith are not described as inherently single-ApplicationContext analyzers. Measure both missed qualification and over-approximation.

## Evaluation Denominators and Metrics

Required denominator dimensions:

- mechanism catalog row and condition class;
- framework/tool version;
- exact build context and modeled configuration space;
- true/false/unknown region;
- attempted, correct, incorrect, unresolved, unsupported, omitted and operational-error outcomes;
- source/provenance completeness;
- repository and fixture inclusion/exclusion with reasons.

Primary metrics:

- fact/binding/finding precision, recall and F1;
- false-certainty and false-unconditional-warning rates;
- region-quantifier confusion matrix;
- witness validity/minimality;
- affected-region precision/recall;
- evidence-acquisition reduction for RQ5;
- time, memory, limits and deterministic digest agreement.

Coverage is not correctness. A provider can increase resolved output and still reduce precision; both are reported.

## Benchmark Program

Provisional design target:

- 40–80 exact microfixtures across the accepted M4 fragment;
- interaction fixtures spanning property/profile/classpath/bean-state/order;
- 5–8 pinned real repositories selected through published inclusion criteria;
- historical pairs and controlled mutations with expected configuration regions;
- at least two relevant stable Spring Boot lines when the corpus is frozen.

These counts are planning ranges, not validated sample-size claims. Corpus, labels, baselines, primary metrics and exclusion rules are registered before observing comparative results.

## Open Engineering Questions

| ID | Question | Priority | Resolution gate |
|---|---|---|---|
| OQ-1 | Is JavaParser sufficiently correct for the approved production semantic denominator? | P0 | G2 independent review/corpus evidence |
| OQ-2 | Is the proposed storage-neutral graph schema sufficient for all conditional SE121 rule/query types? | P0 | M5 R2 experiment and G4 |
| OQ-3 | What are the exact producer/candidate/injection/condition/configuration-space identities? | P0 | M4-R0 |
| OQ-4 | Which Spring Boot/Framework/Lombok/Spring Data versions and condition fragment form v1? | P0 | M4-R0 |
| OQ-5 | How are property-source imports/precedence, MISSING and sound OTHER domains represented? | P0 | M4-R0 |
| OQ-6 | Which ordered/partial-order registration semantics are supported, and where must results become UNKNOWN? | P0 | M4-R0 |
| OQ-7 | Does exhaustive bounded enumeration suffice, or does a SAT/BDD backend win on registered spaces? | P1 | M4-R0 benchmark |
| OQ-8 | Which architecture policies form the first configuration-qualified policy denominator? | P0 | M6 design gate |
| OQ-9 | Which real repositories and historical changes provide lawful, reproducible ground truth? | P1 | M4E/M9 protocol |
| OQ-10 | Which metric definitions and inclusion rules are defensible at each scope/region? | P0 | M5 golden fixtures |
| OQ-11 | Which score dimensions/weights/caps/withholding rules remain stable under conditional and unknown regions? | P0 | M6/M9 sensitivity |
| OQ-12 | What graph/query/UI budgets support realized, region and witness interactions? | P1 | M8/M9 |
| OQ-13 | Which frontend/design-system/graph stack satisfies accessibility and scale? | P1 | M8 technology evaluation |
| OQ-14 | How should provider conflicts and evidence deltas affect conditional region compatibility? | P0 | M4/M11 contract tests |
| OQ-15 | When do generated-source, bytecode, isolated build, AOT or runtime providers become gate-critical? | P1 | Gap prevalence and provider-value evidence |

## Claims Explicitly Not Established

| Claim | Current status |
|---|---|
| “Most Spring Boot repositories use 3–15 condition variables” | Rejected as unsupported |
| “Lombok/Spring Data conventions occur in more than 90% of repositories” | Rejected as unsupported |
| “ArchUnit/Spring Modulith only inspect one active test context” | Rejected as technically inaccurate |
| Any universal Spring-container equivalence | Not claimed |
| Any target precision/recall, runtime or repository count as achieved | Unvalidated |
| SAT/BDD or knowledge graphs are the novelty | Rejected |
| Phase/order-aware conditional semantics is novel | Hypothesis pending systematic literature review and empirical comparison |
| AI improves architecture evolution | Candidate RQ6, outside SE121 |

## Promotion Log

- **2026-09-09 — research direction selected**
  - Decision: adopt staged conditional architecture assurance for M4+ and conditional architecture delta for Track B.
  - Authority: user request authorizing evidence-based document update; recorded in ADR-004.
  - Evidence: repository contract inspection, official framework documentation/source and 2022–2026 literature synthesis.
  - Remaining gate: no empirical RQ is confirmed; M4-R0 must refine the fragment/protocol before production implementation.

## Related Documents

- [Conditional Architecture Redirection Review](2026-09-09-conditional-architecture-redirection-review.md)
- [ADR-004](../decisions/ADR-004-staged-conditional-architecture-semantics.md)
- [Conditional Architecture Semantics](../architecture/conditional-architecture-semantics.md)
- [M4 Spring Intelligence](../architecture/m4-spring-intelligence.md)
- [Roadmap](../roadmap.md)
