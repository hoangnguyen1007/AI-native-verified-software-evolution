# Current State

## Phase

SE121 – Software Architecture Intelligence Platform.

## Architecture Status

Architecture proposed but not yet validated.

- Pipeline design: **PROVISIONAL** — see [architecture.md](architecture/architecture.md)
- Component boundaries: **PROVISIONAL**
- Knowledge Graph schema: **HYPOTHESIS** — requires R2 validation
- Module structure: **PROVISIONAL**

## Implementation Status

No production source code exists.

All production directories are empty:
`analyzer/`, `backend/`, `frontend/`, `benchmarks/`, `tests/`

## Technology Decisions

| Decision | Value | Status |
|---|---|---|
| Java version | Java 21 | **CONFIRMED** |
| Build system | Maven | **CONFIRMED** |
| Monorepo | Yes | **CONFIRMED** |
| Java parser | JavaParser + SymbolSolver | **PROVISIONAL** (via ADR-001) |
| Graph database | Neo4j Community | **PROVISIONAL** — R2 validation needed |
| Backend framework | Spring Boot 3.x | **PROVISIONAL** |
| Rule format | TBD | **HYPOTHESIS** |
| Frontend | Deferred | **CONFIRMED** (deferral) |

## Documentation Status

| Document | Status |
|---|---|
| AGENTS.md | ✅ Complete |
| docs/project-context.md | ✅ Created |
| docs/architecture/architecture.md | ✅ Created |
| docs/architecture/knowledge-graph.md | ✅ Created |
| docs/research/research-questions.md | ✅ Created (quarantine) |
| docs/current-state.md | ✅ This document |
| docs/roadmap.md | ✅ Enriched |
| .agents/ (portable AI config) | ✅ Ready (needs commit) |
| ADRs | ✅ ADR-001 (Parser) created |
| README.md | ❌ Not yet created |

## Completed

- Repository initialized
- GitHub repository created
- AI instruction layer created (AGENTS.md, agent definitions, rules, skills)
- Generic skills removed (11 removed, 4 project-specific retained)
- Foundation documentation created
- Epistemic classification system established
- Research questions quarantined
- **R1 Parser Evaluation**: Demonstrated JavaParser + SymbolSolver correctly resolving local project semantics and failing gracefully on incomplete classpaths.

## Blocking Decisions

| Decision | Blocked By | Next Step |
|---|---|---|
| Graph DB commitment | R2 validation not yet run | Run R2 after schema design review |
| Rule format | Design exploration needed | Research task 1.3 |

## Next Steps

1. Human reviews and commits foundation documents + R1 artifacts
2. Run R2 schema validation
3. Record ADR-002 (graph DB)
4. Begin Phase 2 implementation (after human approval)

## Known Risks

| Risk | Mitigation |
|---|---|
| JavaParser performance on massive monoliths | Unvalidated in R1; requires R3 scale evaluation |
| JavaParser generic/lambda resolution correctness | Unvalidated in R1; requires targeted testing |
| Neo4j schema may be incomplete | R2 validation; schema is explicitly HYPOTHESIS |
| No ground-truth dataset for benchmarks | Survey existing; annotate PetClinic if needed |
| Spring implicit wiring hard to model | R5 experiment; catalog patterns first |