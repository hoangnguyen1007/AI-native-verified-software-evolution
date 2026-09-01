# R1 Parser Viability Evaluation

**Epistemic Status**: PROVISIONAL

This document summarizes the experiment evaluating JavaParser + SymbolSolver for building the SE121 Software Knowledge Graph.

## Environment & Methodology

- **Target Repository**: Spring PetClinic (Commit `818c4136ea971c21674525f9053de0d9c7ad8cfe`)
- **Runtime JDK**: Java 17.0.12 (Build execution)
- **Compiler Release**: 21
- **Parser Language Level**: `ParserConfiguration.LanguageLevel.JAVA_21`
- **JavaParser Version**: 3.26.1
- **Configurations**:
  - **Config A**: Source root + JDK only (incomplete classpath)
  - **Config B**: Source root + JDK + Maven dependencies (complete classpath)

### Experiment Procedure
1. A baseline of manually verified ground-truth relationships (14 distinct semantic cases) was established.
2. A custom parser CLI (PoC) was executed under both Config A and Config B.
3. The PoC traversed `.java` files, attempting to resolve types, method calls, constructor parameters, and annotations, comparing outcomes against the manual ground truth to distinguish resolution coverage from resolution correctness.

## Ground-Truth Verification

| ID | Category | Expected Config A | Expected Config B | Actual (Config B) | Correctness |
|---|---|---|---|---|---|
| GT-STEREOTYPE-CONTROLLER | ANNOTATED_WITH | UNRESOLVED | RESOLVED | RESOLVED | PASS |
| GT-STEREOTYPE-COMPONENT | ANNOTATED_WITH | UNRESOLVED | RESOLVED | RESOLVED | PASS |
| GT-CONSTRUCTOR-INJECTION-EVIDENCE | CONSTRUCTOR_PARAMETER | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-LOCAL-METHOD-CALL | CALLS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-LOCAL-REPOSITORY-CALL | CALLS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-OVERLOAD-STRING-BOOLEAN | CALLS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-OVERLOAD-INTEGER | CALLS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-COLLISION-OWNER-CONTROLLER | RETURNS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-COLLISION-PET-CONTROLLER | RETURNS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-INHERITANCE-LOCAL | EXTENDS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-INTERFACE-JDK | IMPLEMENTS | RESOLVED | RESOLVED | RESOLVED | PASS |
| GT-INTERFACE-EXTERNAL | IMPLEMENTS | UNRESOLVED | RESOLVED | RESOLVED | PASS |
| GT-EXTERNAL-CALL | CALLS | UNRESOLVED | RESOLVED | RESOLVED | PASS |
| GT-EXTERNAL-PARAMETER-ANNOTATION | ANNOTATED_WITH | UNRESOLVED | RESOLVED | RESOLVED | PASS |

*(All 14 ground truth semantic cases resolved as expected under Configuration B, preserving required line/column spans.)*

## Raw Result Summary

| Metric | Config A (Source + JDK) | Config B (Source + Dependencies) |
|---|---|---|
| Files Parsed Successfully | 30 | 30 |
| Local Type Resolved | 86 | 98 |
| Local Type Failed | 12 | 0 |
| Local Method Resolved | 116 | 220 |
| Local Method Failed | 104 | 0 |
| Constructor Param Resolved| 6 | 6 |
| Constructor Param Failed | 0 | 0 |
| Annotation Resolved | 1 | 30 |
| Annotation Failed | 29 | 0 |

## Findings

### Empirical Correctness
Under **Config B** (with a complete classpath), JavaParser and SymbolSolver correctly matched the ground-truth verification sample, accurately differentiating overloads and scope collisions (e.g., `findOwner` in different controllers). It perfectly preserved source AST node line/column mappings required for SE121's evidence-first mandate.

### Incomplete Classpath Behavior (Config A)
Under **Config A**, the parser remains stable and does not crash, failing gracefully via `UnsolvedSymbolException`. Notably, local types and methods can still largely be resolved, but external method calls, external interfaces, and Spring annotations correctly fail to resolve because the Spring Framework classes are entirely absent. This confirms JavaParser is highly tolerant of missing external dependencies but explicitly identifies them as unresolved, preserving data integrity.

### Systematic Failure Modes / Limitations
- **External Bounds**: If the analyzer requires full Spring stereotype detection and external method-call resolution, it *must* have access to the project's compiled dependencies. Source-only analysis will systematically leave external boundaries as `UNRESOLVED`.
- **"100% Accuracy" Caveat**: While the Config B test correctly resolved 100% of the ground truth cases, this must NOT be interpreted as "JavaParser is universally accurate." The ground truth was a small, representative 14-case sample.

### Threats to Validity
PetClinic is a small repository (30 Java files). The performance footprint and cyclic-dependency handling of SymbolSolver on a massive monolith (1,000+ files) remains untested in this experiment and will require future validation.

## Recommendation

**Promote JavaParser + SymbolSolver to PROVISIONAL.**
JavaParser accurately resolves required Spring Boot Java constructs and local dependencies with precise source traceability. Its failure modes under incomplete classpaths are safe (exceptions rather than silent misattribution). It is adopted provisionally under ADR-001, subject to the architectural mandate that it be shielded behind an interface to ensure reversibility.
