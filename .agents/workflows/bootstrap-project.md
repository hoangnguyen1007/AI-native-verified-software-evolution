# Bootstrap Project

Description: Initialize or re-evaluate the project's engineering and architecture foundation before substantial implementation begins.

## Steps

### 1. Establish Context

Read:

- AGENTS.md
- docs/project-context.md
- docs/current-state.md
- docs/roadmap.md
- docs/decisions/
- README.md

Do not modify production code yet.

### 2. Inspect Repository

Inspect:

- repository structure
- Git status
- existing source
- tests
- build configuration
- dependency manifests
- environment configuration

### 3. Identify Unknowns

List:

- architectural unknowns
- technology decisions
- missing tooling
- missing documentation
- risks
- assumptions

Separate facts from assumptions.

### 4. Architecture Discovery

Determine the minimal architecture required by the current project phase.

Do not introduce later-phase implementation.

### 5. Research

For consequential unknowns:

- research authoritative sources
- compare alternatives
- run focused experiments when useful

Use /research when a dedicated research investigation is required.

### 6. Architecture Decision

Use /architect for major architectural decisions.

### 7. Produce Durable State

Update when justified:

- docs/architecture/
- docs/decisions/
- docs/roadmap.md
- docs/current-state.md

### 8. Verification

Run basic repository validation.

Confirm:

- project builds when buildable
- configuration is coherent
- documentation reflects current decisions
- Git working tree changes are understood

### 9. Stop Condition

Do not start feature implementation automatically.

End with:

- current architecture
- confirmed decisions
- unresolved decisions
- risks
- recommended first implementation task