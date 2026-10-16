# M7.3 — Visual Workbench Foundation, Policy-Qualified Impact, and Interoperability

**Status:** CONFIRMED by implementation and complete bounded test suites in `analyzer`, `backend`, and `cli`.

## Mission and Architecture Invariants

Milestone M7 completes Track A release readiness:
1. **Visual Workbench Graph Projections (`GraphProjectionService`):** Deterministic extraction of hierarchical compound graphs (Module $\rightarrow$ Package $\rightarrow$ Type), cycle participant badges, and configuration-qualified policy violation badges.
2. **Policy-Qualified Structural Impact Analysis (`PolicyImpactService`):** Associates structural reachability (incoming dependents) with architecture rules and Evidence Bundles, quantifying direct vs. transitive blast radiuses, worst severities, and truth region feasibility.
3. **Multi-Format Interoperability (`Exports`):** Standards-compliant Cytoscape.js (`format: "cytoscape.js"` with nested compound elements), GraphML (`evo.canonical` payload attribute), SARIF 2.1.0, and canonical JSON.
4. **Offline Local Workflow Acceptance:** Zero external network operations, zero database daemons, and zero background services required.

---

## 1. Visual Graph Model (`VisualGraph`)

The canonical visual projection model lives strictly in `analyzer` (`com.evolution.analysis.query.projection.VisualGraph`):

- **`VisualGraph`:**
  - `id`: ContentDigest (SHA-256 over canonical serialization).
  - `nodes`: List of `VisualNode`.
  - `edges`: List of `VisualEdge`.
  - `metadata`: Projections, scopes, total counts, compound node counts.

- **`VisualNode`:**
  - `id`: Canonical entity identity (`(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}`).
  - `label`: Human-readable identifier or canonical name.
  - `kind`: `MODULE`, `PACKAGE`, `TYPE`, `SPRING_COMPONENT`, etc.
  - `scope`: Requested entity scope.
  - `parent`: Optional parent node ID representing compound containment (e.g., Type $\in$ Package $\in$ Module).
  - `origin`, `sourceSet`: Partition metadata.
  - `violationCount`: Integer count of architectural policy findings associated with this vertex.
  - `worstSeverity`: Optional highest violation severity (`ERROR`, `WARNING`, `INFO`).
  - `isCycleParticipant`: Boolean flag set when the vertex participates in at least one feasible dependency cycle.
  - `properties`: Redacted metadata.

- **`VisualEdge`:**
  - `id`: Canonical edge identity.
  - `source`, `target`: Node identities.
  - `kind`: Relationship kind (`graph.depends-on`, etc.).
  - `classification`: Quantifier (`MUST`, `MAY`, `UNKNOWN`).
  - `isViolation`: True if the edge directly forms part of a policy violation Evidence Bundle.
  - `isCycle`: True if the edge participates in a detected dependency cycle.
  - `supportCount`: Count of supporting declarations/usages.
  - `supportsTruncated`: Boolean bounds flag.

### Compound Hierarchy Construction

When projecting at `TYPE` scope, `GraphProjectionService` queries `queries.entity()` and `queries.edges()` across the declared query budget, then deterministically derives:
- Virtual `MODULE` nodes for distinct module coordinates.
- Virtual `PACKAGE` nodes parented to their respective `MODULE` nodes.
- Concrete `TYPE` nodes parented to their containing `PACKAGE` nodes.

Cyclic participants are tagged via `queries.findCycles()`. Policy violations are projected from `PolicyEvaluation.Result` when supplied.

---

## 2. Policy-Qualified Structural Impact Analysis (`PolicyImpactService`)

Ordinary structural impact identifies incoming reachability: *what symbols structurally depend on the changed symbol?*
Policy-qualified impact answers: *which architectural policy rules and certified findings are triggered or affected by this blast radius?*

### Calculation Algorithm

1. **Existence Verification:** Resolves `changedEntity` against the query service. If absent or ambiguous, returns `PARTIAL` status with explicit `CapabilityGapRecord`.
2. **Incoming Reachability:** Traverses incoming dependencies via `queries.impact(incomingRequest, changedEntity)`. Collects the set of all reachable vertices ($V_{\text{affected}}$) and paths ($P$).
3. **Intersection with Policy Evidence Bundles:**
   For every finding $f \in \text{evaluation.findings()}$:
   - Computes vertex set of $f$'s Evidence Bundle ($V_{\text{finding}}$ from paths, relationships, and supports).
   - If $V_{\text{finding}} \cap V_{\text{affected}} \neq \emptyset$:
     - Tagged as **DIRECT** if $V_{\text{finding}} \cap \{\text{changedEntity}\} \neq \emptyset$.
     - Tagged as **TRANSITIVE** if $V_{\text{finding}} \cap (V_{\text{affected}} \setminus \{\text{changedEntity}\}) \neq \emptyset$.
4. **Summary & Quantifiers:**
   - Summarizes impacted rules: direct count, transitive count, rule kind, severity.
   - Computes `worstSeverity` and `highestQuantifier` (`MUST` > `MAY` > `UNKNOWN`).
   - Retains witness/counter-witness feasibility from the original Evidence Bundles.

---

## 3. Query API & CLI Contract

### Operations Registered in `ArchitectureQueryApi`

- **Queries (`/api/v1/graphs/{id}/queries/`):**
  - `"visual-graph"`: Accepts optional arguments `seed` (String) and `depth` (int). Returns unbadged structural hierarchy and cycle markers.
- **Reports (`/api/v1/graphs/{id}/reports/{reportId}/`):**
  - `"policy-impact"`: Accepts argument `identity` (String). Evaluates policy-qualified impact and returns `PolicyImpactRecord.Result`.
  - `"visual-graph"`: Accepts optional arguments `seed`, `depth`, and `violationsOnly` (boolean). Returns visual workbench graph enriched with violation badges and finding counts.

### CLI Commands (`evo`)

- `evo visual [symbol] [--depth N] [--policy FILE] [--json]`
  Projects visual graph. In terminal mode, prints node counts, compound breakdown, cycle badges, violation totals, and export hints.
- `evo impact <symbol> [--policy FILE] [--json]`
  When `--policy` is passed, automatically routes to `/reports/{id}/policy-impact` and displays reachable affected entities, impacted rules, severities, and finding details.
- `evo export cytoscape [-o FILE] [--policy FILE]`
  Serializes the visual workbench graph or edge projection into Cytoscape.js JSON.
- `evo export graphml [-o FILE]`
  Serializes edge queries into standards-compliant GraphML with embedded canonical evidence metadata.
- `evo export sarif [-o FILE] [--policy FILE]`
  Exports policy evaluation findings into OASIS SARIF 2.1.0 format.

---

## 4. Closed Reporting Denominator & Determinism

- All identities are cryptographically grounded with SHA-256 (`ContentDigest`).
- Partial or withheld evaluations are never converted to fabricated numbers or false negative claims.
- Exports reject data truncation: output exceeding bounds thresholds throws explicit, typed bounds errors rather than corrupting graphs.
