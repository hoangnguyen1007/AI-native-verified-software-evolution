# Architecture Overview

> All architecture elements are marked with epistemic status.
> Nothing here is final until validated by experiment and human approval.

## System Purpose

Analyze Java/Spring Boot repositories to build a Software Knowledge Graph,
detect architecture violations, and produce evidence-backed reports.

**[CONFIRMED]** — from AGENTS.md and project-context.md.

## Architecture Style

**Modular monolith.** [CONFIRMED — human decision]

- Single Maven multi-module build
- No microservices
- Boundaries enforced by Java packages and Maven modules
- Dependency direction: core analysis independent from infrastructure and presentation

## Pipeline

```
Source Repository (Java/Spring Boot)
  │
  ▼
Source Ingestion ──────── discover .java files
  │
  ▼
Java Parser ───────────── parse → resolved AST       [HYPOTHESIS: JavaParser + SymbolSolver]
  │
  ▼
Semantic Extractor ────── AST → typed domain model
  │
  ▼
Spring Inference ──────── annotations → DI wiring
  │
  ▼
Graph Builder ─────────── SemanticModel → graph ops
  │
  ▼
Graph Store ───────────── persist & query graph       [PROVISIONAL: Neo4j Community]
  │
  ├──► Rule Engine ────── evaluate rules → violations with evidence  [HYPOTHESIS: format TBD]
  │
  ├──► Impact Analysis ── graph traversal for change impact
  │
  └──► API Layer ──────── REST endpoints              [DEFERRED]
         │
         ▼
       Frontend ────────── visualization              [DEFERRED]
```

## Component Map

| Component | Responsibility | Maven Module | Status |
|---|---|---|---|
| Source Ingestion | Discover `.java` files from repository path | `analyzer` | **PROVISIONAL** |
| Parser | Parse Java source → resolved AST | `analyzer` | **HYPOTHESIS** (behind interface) |
| Semantic Extractor | AST → typed semantic domain model | `analyzer` | **PROVISIONAL** |
| Spring Inference | Spring annotations → DI wiring inference | `analyzer` | **PROVISIONAL** |
| Graph Builder | SemanticModel → graph write operations | `analyzer` | **PROVISIONAL** |
| Graph Store | Persist & query Knowledge Graph | `backend` | **PROVISIONAL** |
| Rule Engine | Evaluate architecture rules → violations | `analyzer` | **HYPOTHESIS** |
| Impact Analyzer | Graph traversal for change impact | `analyzer` | **PROVISIONAL** |
| API Layer | REST endpoints for frontend | `backend` | **DEFERRED** |
| Frontend | Visualization UI | `frontend` | **DEFERRED** |

## Module Structure [PROVISIONAL]

```
analyzer/                        # Core analysis engine
  src/main/java/
    com/evolution/
      ingestion/                 # Source file discovery
      parser/                    # Java parser abstraction + implementation
      semantic/                  # Semantic domain model
      spring/                    # Spring-specific inference
      graph/                     # Graph construction
      rules/                     # Architecture rule engine
      impact/                    # Impact analysis
  src/test/java/

backend/                         # API & graph persistence
  src/main/java/
    com/evolution/
      graph/                     # Neo4j adapter
      api/                       # REST endpoints (deferred)
  src/test/java/

frontend/                        # Visualization (deferred)
```

> [!NOTE]
> Module paths and package names are **PROVISIONAL**. They may change based on
> implementation experience. The key invariant is separation of concerns.

## Dependency Direction

```
analyzer (core)
  ↑ depends on nothing outside JDK + parser library
  │
backend
  ↑ depends on analyzer (domain model) + graph DB driver
  │
frontend
  ↑ depends on backend API contract only
```

Core analysis logic must remain independent from:
- Graph database implementation
- HTTP framework
- Frontend framework
- Deployment infrastructure

**[CONFIRMED]** — from AGENTS.md Engineering Principle #4.

## Evidence Model

Every architecture violation should preserve:

| Evidence Element | Required | Source |
|---|---|---|
| Source file | Yes | Parser |
| Source span (line, column) | Yes | Parser |
| Symbol | Yes | Semantic model |
| Dependency | Yes | Semantic model / graph |
| Graph path | Yes | Graph traversal |
| Violated rule | Yes | Rule engine |
| Rule provenance | Yes | Rule definition |
| Analysis evidence | Yes | Combined |

**[CONFIRMED]** — from AGENTS.md "Evidence First" section.

## Extension Points (Future Phases)

The architecture should preserve clean extension points for later phases,
but must NOT implement them now:

- Graph-guided RAG context selection (SE122)
- AI diagnosis pipeline (SE122)
- Transformation engine integration (SE122/KLTN)
- Sandbox verification (KLTN)
- CI/CD pipeline integration (KLTN)

## Related Documents

- [Project Context](../project-context.md)
- [Knowledge Graph Schema](knowledge-graph.md)
- [Current State](../current-state.md)
