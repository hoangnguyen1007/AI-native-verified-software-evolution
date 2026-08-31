# Execute

Description: Adaptive high-agency engineering workflow. Analyze the task, choose the minimum rigor required, parallelize independent work when beneficial, implement with controlled scope, verify iteratively, perform adversarial review, and persist durable project state.

---

# Mission

Maximize correct project progress per unit of time and context.

Be high-agency, but never trade correctness, project integrity, research validity, or security for speed.

Do not perform unnecessary ceremony.

Do not skip necessary reasoning.

---

# PHASE 0 — PRE-FLIGHT

Before acting:

1. Read AGENTS.md.
2. Inspect Git status.
3. Read docs/current-state.md.
4. Read docs/roadmap.md.
5. Read relevant architecture documentation.
6. Read relevant ADRs.
7. Inspect the actual files involved in the task.

Determine:

- current branch
- uncommitted changes
- task scope
- affected modules
- existing tests
- relevant constraints

Never assume the repository is clean.

Never overwrite unrelated user work.

---

# PHASE 1 — CLASSIFY THE TASK

Classify into:

## L0 — Trivial

Examples:

- typo
- documentation wording
- obvious one-line correction
- harmless local change

Path:

inspect → implement → verify.

## L1 — Bounded

Examples:

- isolated bug fix
- small function
- focused test
- small UI adjustment
- localized refactor

Path:

inspect → brief plan → implement → verify → review.

## L2 — Feature

Examples:

- new analyzer capability
- new API
- graph feature
- new architecture rule
- new module

Path:

inspect → plan → implement → verify → review.

## L3 — Architectural / Research

Examples:

- parser selection
- graph schema
- module boundary changes
- major dependency
- benchmark methodology
- technology selection
- persistent data model change
- architecture redesign

Path:

research → architecture → implementation → verification → adversarial review.

---

# PHASE 2 — SCOPE GATE

Before implementation, verify that the task belongs to the current project phase.

Current SE121 scope:

- semantic source analysis
- Software Knowledge Graph
- dependency modeling
- architecture rules
- architecture violation detection
- evidence/provenance
- basic impact analysis
- visualization
- benchmarking

Do NOT silently implement later-phase capabilities:

- AI diagnosis
- graph-grounded RAG
- automated refactoring
- patch generation
- OpenRewrite transformation
- sandbox verification
- differential testing
- mutation testing
- CI/CD verification
- Verified Pull Requests

If a later-phase dependency is required:

- identify it
- isolate the prerequisite
- implement only the minimum prerequisite necessary for the current task

---

# PHASE 3 — WORKSPACE STRATEGY

Choose the safest useful workspace mode.

Use local workspace for:

- small tasks
- interactive changes
- tasks unlikely to conflict

Prefer isolated Git worktree for:

- complex features
- speculative implementations
- parallel agents
- multi-file refactoring
- risky architectural experiments

Never allow independent agents to edit the same files simultaneously unless explicitly coordinated.

---

# PHASE 4 — INTELLIGENT DECOMPOSITION

For L2/L3 tasks:

Break the problem into independent workstreams.

Possible parallel work:

- repository exploration
- technology research
- test strategy
- architecture critique
- performance analysis
- security review
- benchmark design

Delegate only genuinely independent work.

Do not spawn agents merely to increase agent count.

Each delegated task must have:

- clear objective
- limited scope
- relevant files/context
- expected deliverable
- explicit non-goals
- read/write boundary

Prefer 2–4 useful parallel investigations over many shallow agents.

---

# PHASE 5 — RESEARCH GATE

For L3 tasks:

Call /research.

Research must produce:

- decision question
- constraints
- evidence
- alternatives
- experiments when necessary
- recommendation
- confidence
- unresolved uncertainty

Do not implement before the important uncertainty has been sufficiently reduced.

---

# PHASE 6 — ARCHITECTURE GATE

For architectural decisions:

Call /architect.

The result must identify:

- problem
- constraints
- options
- selected design
- trade-offs
- consequences
- reversibility
- affected modules
- test strategy

Persist consequential decisions as ADRs.

---

# PHASE 7 — IMPLEMENTATION

Call /implement.

Before coding:

- inspect existing implementation
- inspect tests
- inspect callers
- inspect dependencies
- identify smallest coherent change

Implement only the approved scope.

Do not perform unrelated cleanup.

Do not introduce infrastructure without a demonstrated need.

---

# PHASE 8 — VERIFICATION LOOP

Call /verify.

Verification must include the strongest practical checks:

- targeted tests
- integration tests when relevant
- build
- static analysis when available
- behavioral verification
- final diff inspection

If verification fails:

1. classify the failure
2. identify root cause
3. apply the smallest correct fix
4. rerun verification

Repeat only while meaningful progress is occurring.

If repeated attempts fail or the root cause remains uncertain:

- stop
- preserve the current state
- report the blocker

Do not enter an infinite repair loop.

---

# PHASE 9 — ADVERSARIAL REVIEW

Call /review for L1+ tasks.

The reviewer must independently inspect:

- requirements
- implementation
- architecture
- tests
- security
- scope
- evidence
- research validity where relevant

Do not rely solely on the implementation agent's summary.

---

# PHASE 10 — REVIEW FEEDBACK LOOP

If blocking issues exist:

1. return to implementation
2. fix blocking issues
3. rerun verification
4. rerun review

Do not declare completion while blocking findings remain unresolved.

Non-blocking improvements may be recorded for follow-up rather than expanding the current scope.

---

# PHASE 11 — PROJECT MEMORY

Update when necessary:

- docs/current-state.md
- docs/roadmap.md
- docs/architecture/
- docs/decisions/

Conversation history is not durable project state.

---

# PHASE 12 — FINAL QUALITY GATE

Before declaring success verify:

- requirements satisfied
- scope respected
- tests actually executed
- build status known
- final diff inspected
- no secrets introduced
- no unrelated files modified
- documentation updated when necessary
- known limitations reported

Never claim successful verification that did not occur.

---

# FINAL REPORT

Return:

## Result
What was accomplished.

## Verification
What was actually executed.

## Review
Important findings.

## Evidence
Relevant technical evidence.

## Remaining
Known unfinished work.

## Risk
Anything the next engineer should know.

## Next
The single highest-value next step.