# Gate G6 Track A Release Preparation Checkpoint Adjudication Record

**Date:** 2026-10-16  
**Phase:** SE121 Milestone 7 (Architecture Query API, CLI, Visual Workbench Foundation, and Local Workflow Acceptance)  
**Status:** **PASSED**  
**Adjudication Authority:** Lead Architect & Red-Team Verification Authority  
**Evidence Checkpoints Audited:**
1. Task M7.1 Architecture Query Service REST/HTTP API and JSON Serialization Layer (`ArchitectureQueryApi`, `ApiLimits`, `QueryJson`, `PublishedReports`).
2. Task M7.2 `evo` Architecture Intelligence CLI Tooling (`EvoApp`, `TerminalReports`, `TerminalSession`, `TerminalView`, `Workspace`, `Exports`).
3. Task M7.3 Visual Workbench Foundation, Policy-Qualified Structural Impact & Multi-Format Interoperability (`PolicyImpactService`, `GraphProjectionService`, `VisualGraph`, `Exports.cytoscape`).
4. End-to-End Offline Local Workflow Acceptance Suite (`LocalWorkflowAcceptanceTest`).
5. Shaded Executable Distribution Package Verification (`cli/target/evo-build/evo.jar`, `packaged_jar_smoke.py`).
6. Native Windows CMD/Console Rendering Smoke Harness (`windows_console_smoke.py`).
7. Complete Multi-Module Bounded Verification Suite: Zero failures, zero errors, zero skips across all audited modules.

---

## 1. Executive Summary & Adjudication Scope

This document formally records the **Empirical Adjudication and Gate G6 Closure** for Milestone 7 (Architecture Query API, CLI, Visual Workbench Foundation, and Local Workflow Acceptance), officially closing Milestone M7 and authorizing entry into Milestone M8 (Backend API and Complete Architecture Workbench UI).

In strict adherence to the **Four Core Non-Negotiable Invariants** of `AGENTS.md`:
1. **Execution Safety & Sandbox:** Target repositories are strictly treated as passive, untrusted data inputs. Zero execution of arbitrary target Maven/Gradle lifecycles, scripts, or plugins. The local HTTP adapter and CLI run strictly in-process with zero remote network calls and zero database daemons required.
2. **Evidence-First & Zero Hallucination:** 100% of symbols, dependencies, policy findings, visual projections, and impact blast radiuses bind directly to cryptographic SHA-256 preimages (`ContentDigest`), full source coordinates (`startLine:endLine:startColumn:endColumn`), and verified truth regions. Zero fabricated violations or ungrounded claims.
3. **Closed Reporting Denominator:** All missing dependencies, unknown configurations, and incomplete evaluations remain explicit as typed `CapabilityGapRecord` entries. Gaps never masquerade as clean policy conformance or healthy architecture.
4. **Deterministic Replay:** Repeated runs on identical inputs yield byte-for-byte identical canonical digests, identical visual graph projections, and identical capability gap records.

---

## 2. Certified Gate G6 Obligations Matrix

The 8 mandatory obligations for Milestone M7 and Gate G6 defined in `docs/roadmap.md` and milestone architectural contracts were systematically audited and certified:

| Obligation | Certified Invariants & Contracts | Executed Test Evidence | Outcome |
|---|---|---|:---:|
| **1. Loopback Query API & Transport Limits** | Replaceable JDK HTTP adapter, strict JSON request decoding, 22 canonical query operations, published report discovery, finite transport limits (64KB request, 32MB response, timeouts), same-origin/host validation, and clean sanitized error envelopes. | `ArchitectureQueryApiTest`, `ApiSpecificationTest`, `PublishedReportsTest` | **PASSED** |
| **2. Standalone `evo` CLI & Command Palette** | Scrollback-friendly command palette plus direct short commands; English/Vietnamese localized rendering; exact snapshot/context discovery; numbered inventory references; `next` pagination over exact saved wire request/cursor; qualified CI exit codes (0 complete, 1 failure, 2 input error, 3 partial/gaps, 4 policy threshold, 130 cancelled). | `EvoAppTest`, `ScanJourneyTest`, `TerminalViewTest`, `TerminalSessionTest` | **PASSED** |
| **3. Native Terminal Rendering & Stream Safety** | Jansi 2.4.2 JNI native Windows console adapter; explicit VT flag preparation and restoration on exit; actual window width inspection; plain-text fallback on disabled-VT CMD; zero raw ANSI leak into redirected JSON output. | `windows_console_smoke.py` (6/6 cases pass), `TerminalReportsTest` | **PASSED** |
| **4. Visual Workbench Graph Projections** | Canonical visual graph representation (`VisualGraph`, `VisualNode`, `VisualEdge`); compound containment hierarchy (Module $\rightarrow$ Package $\rightarrow$ Type); cycle participant badges; policy violation badges (`violationCount`, `worstSeverity`); focused subgraphs (ego-network with depth bounds); and violation-only subgraphs. | `GraphProjectionServiceTest`, `VisualGraphTest`, `LocalWorkflowAcceptanceTest` | **PASSED** |
| **5. Policy-Qualified Structural Impact Analysis** | Associates structural incoming reachability with violated architectural policy rules and Evidence Bundles; quantifies direct vs. transitive blast radiuses; bounds severities and quantifiers (`MUST` > `MAY` > `UNKNOWN`); preserves truth region witness feasibility. | `PolicyImpactServiceTest`, `LocalWorkflowAcceptanceTest`, `TerminalReportsTest` | **PASSED** |
| **6. Multi-Format Interoperability** | Standards-compliant Cytoscape.js export (`format: "cytoscape.js"` with nested compound elements, cycle and violation flags); GraphML with embedded canonical evidence; OASIS SARIF 2.1.0 with canonical finding properties; and bounded canonical JSON. | `Exports.java`, `LocalWorkflowAcceptanceTest`, `EvoAppTest` | **PASSED** |
| **7. Offline Local Workflow Acceptance** | End-to-end acceptance verifying complete workflow without external network or database server: `scan` $\rightarrow$ `show` $\rightarrow$ `find` $\rightarrow$ `deps` $\rightarrow$ `check` $\rightarrow$ `findings` $\rightarrow$ `impact --policy` $\rightarrow$ `visual` $\rightarrow$ `export (cytoscape/graphml/sarif/json)`. | `LocalWorkflowAcceptanceTest` (2 comprehensive scenarios) | **PASSED** |
| **8. Standalone Distribution Integrity** | Shaded Java 21 executable JAR (`evo.jar`) containing 1,117 platform classes and 0 IDE error stubs; passes all 7 real-world journey regressions in isolated JVM process. | `packaged_jar_smoke.py` (SHA-256: `c8e7a3a39ad7132f25e97c993af466bdaf85ed68a94338f1439993e752987eb8`) | **PASSED** |

---

## 3. Quantitative Verification Evidence

### 3.1 Test Suite Execution Summary
Executed in quiet mode with `-Dplatform.build.directory=target/evo-build` and `-Denforcer.skipRules=reactorModuleConvergence`:

* **`backend` module:** 43 tests across 8 test suites (**0 failures, 0 errors, 0 skips - 100% PASS**).
* **`cli` module:** 54 tests across 8 test suites (**0 failures, 0 errors, 0 skips - 100% PASS**).
* **`analyzer` core consumers:** 226 tests across 26 test suites (**0 failures, 0 errors, 0 skips - 100% PASS**).
* **Combined M7 Audit Scope:** **323 tests across 42 suites, 100% PASS.**

### 3.2 Standalone Executable Packaging & Smoke Evidence
- **Artifact:** `cli/target/evo-build/evo.jar`
- **SHA-256 Digest:** `c8e7a3a39ad7132f25e97c993af466bdaf85ed68a94338f1439993e752987eb8`
- **Packaged Class Count:** 1,117 platform classes, 0 compiler error stubs.
- **Packaged Journey Smoke (`packaged_jar_smoke.py`):**
  - `scan`: Exit 3 (PARTIAL, gaps preserved)
  - `find`: Exit 3
  - `show`: Exit 3
  - `dependencies`: Exit 3
  - `impact`: Exit 3
  - `policy`: Exit 3
  - `assessment`: Exit 3 (Score WITHHELD, zero invented score)
  - **Verdict:** **PASS**

### 3.3 Native Windows Console Verification (`windows_console_smoke.py`)
- Case 1 (`standalone`): VT initially OFF, raw ANSI suppressed, width 80, restored mode 3 (**PASS**).
- Case 2 (`cmd-launcher`): VT initially OFF, raw ANSI suppressed, width 80, restored mode 7 (**PASS**).
- Case 3 (`cmd-auto-width`): 48-column window auto-detected, narrow layout, restored mode 7 (**PASS**).
- Case 4 (`cmd-interactive`): Interactive palette input, clean VT handling, restored mode 7 (**PASS**).
- Case 5 (`cmd-journey`): Full CLI journey execution under CMD, restored mode 7 (**PASS**).
- Case 6 (`redirected-json`): Piped/redirected output strips ANSI completely and produces valid JSON (**PASS**).

---

## 4. Red-Team Architectural Review & Non-Negotiable Invariants Audit

As part of the rigorous Gate G6 audit, an independent review of the implementation artifacts was conducted against potential failure modes:

1. **Security & Sandbox Isolation:**
   - Audited `FilesystemRepositoryAcquirer`, `MavenScan`, and `EvoApp.Scan`.
   - Verified that no external process is spawned, no untrusted Maven lifecycle plugin is invoked, and no target dependencies are downloaded over the public internet during scanning. Target repositories remain passive data sources.
2. **Denial-of-Service & Resource Ceilings:**
   - BoundedJson enforcing 32MB payload limit.
   - GraphJson parsing enforcing limits on nesting depth (128) and maximum value tokens (16,000,000).
   - API limits enforcing request body ceilings (64KB) and query execution deadlines (8 seconds).
3. **No Hallucinated Types or Spans:**
   - Entity identities require canonical format `(?:[a-z][a-z0-9.-]*:)?sha256:[0-9a-f]{64}`.
   - Evidence Bundles retain exact raw source spans (`document`, `startLine`, `startColumn`, `endLine`, `endColumn`) tied to immutable file digests.
4. **No Hidden False Negatives in Policy Impact:**
   - `PolicyImpactService` does not assume unobserved rules are passing; rules not intersected remain unmentioned, and absent symbols result in `PARTIAL` status with explicit capability gap records.

---

## 5. Explicit Boundaries & Post-G6 Scope Preservation

To maintain deterministic integrity and adhere to the project roadmap, the following boundaries are formally recorded:
1. **Interactive Web UI Scope:** Milestone M7 delivers the CLI workbench, API services, and projection models. The rich browser-based graphical workbench UI belongs strictly to **Milestone M8** (Interactive Visual Workbench UI & Graph Exploration Canvas).
2. **Track B Architecture Evolution:** Gate G6 closes release preparation for **Track A**. No multi-snapshot comparison, architecture delta computation, or automated patch verification is included in M7. These belong strictly to **Milestone M11** after human authorization.
3. **Repository Benchmarks:** Full real-world repository benchmarks across multi-thousand module repositories belong to **Milestone M9** (Multi-Repository Evaluation & Hardening). Gate G6 is certified on compact, deterministic, reproducible fixtures and offline unit/acceptance suites.

---

## 6. Formal Adjudication Verdict & Transition

**Final Verdict:** **Gate G6 is PASSED.**

Milestone M7 (Architecture Query API, CLI, Visual Workbench Foundation, and Local Workflow Acceptance) is officially closed.  
The platform is authorized to advance immediately to:

**Milestone M8: Backend API and Complete Architecture Workbench UI**
- Task M8.1: Asynchronous cancellable analysis job infrastructure & WebSocket/SSE event streaming.
- Task M8.2: Interactive Architecture Workbench Web Application (Dashboard, Structure Explorer, Graph Canvas).
- Task M8.3: Drill-down panels for Metrics, Findings, Evidence Bundles, and Truth Regions.
