# ADR 001: Parser Technology Selection

**Status**: PROVISIONAL
**Date**: 2026-09-01
**Context**: SE121 - Software Architecture Intelligence Platform

## Context and Problem Statement

The platform requires a semantic parser to analyze Java 21 / Spring Boot repositories, extract a Software Knowledge Graph, and detect architecture violations. We need a parser that can accurately resolve project-local semantics and differentiate between resolvable types and missing external dependencies while preserving precise source location data (for evidence tracking).

## Decision Drivers

- **Semantic Fidelity**: Must resolve method calls, fields, constructor parameters, annotations, and inheritance.
- **Source Traceability**: Must preserve line/column information for evidence generation.
- **Ecosystem Compatibility**: Must handle modern Java (up to Java 21) and Spring Boot constructs.
- **Incomplete Classpath Tolerance**: Must fail gracefully when external libraries are missing.

## Decision

We will use **JavaParser + JavaSymbolSolver** as the semantic parsing engine for the analyzer.

## Epistemic Status

**PROVISIONAL**. This is not a confirmed technology decision. It is approved for the current phase based on initial PoC evidence, subject to future validation gates.

## Evidence

### Supporting Evidence (R1 PoC Evaluation)
- Achieved high resolution rates for local types and method calls under incomplete classpaths (Config A).
- Correctly parsed 30/30 Java files from Spring PetClinic without crashing on modern Java constructs.
- Safely degrades via `UnsolvedSymbolException` rather than crashing when external classes (like Spring annotations) are missing.
- When provided a complete classpath (Config B), successfully passed a 14-case manually verified ground-truth test encompassing Spring stereotypes, overloaded method calls, and constructor injection.
- Consistently preserves accurate AST node line/column mapping.

### Missing Evidence
- Performance characteristics and cyclic-dependency resolution stability on a massive monolithic codebase (>1,000 files) remain unvalidated.
- Complex generics and lambda expression target resolution were not thoroughly tested in the initial sample.

### Rejected Interpretation of "100% Accuracy"
While the Config B test resolved 100% of the ground truth cases, this must NOT be interpreted as "JavaParser is 100% accurate universally." The ground truth was a small, representative 14-case sample. Edge cases, complex generic inference, and deeply nested lambdas may still fail.

## Future Validation Gates

1. **R3 Scale Evaluation**: Must complete parsing and resolution of a large Spring Boot monolith within acceptable time limits (TBD) without memory exhaustion or infinite loops.
2. **Generic/Lambda Correctness**: Must correctly identify dependencies originating from Java functional constructs.

## Reversibility Strategy and Abstraction Requirement

To prevent the project from becoming inextricably coupled to JavaParser (and to allow fallback to Eclipse JDT or Spoon if JavaParser fails future validation gates):
- The parser implementation **MUST** be hidden behind a generic, analyzer-specific `Parser` interface.
- Core domain models (Knowledge Graph representation) must not expose JavaParser AST nodes or `ResolvedType` objects. 
- All interactions with JavaParser must be confined to a single, isolated module (`analyzer-javaparser-impl` or similar).
