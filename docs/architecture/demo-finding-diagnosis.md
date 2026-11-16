# Demo Step 1 — Finding diagnosis

Open a published finding in Workbench, choose **Diagnose finding**, and follow a validated citation to the original evidence. This completes the first action in the full-pipeline demo under ADR-011. The provider uses authored local replay and labels its output accordingly. [Demo roadmap](../roadmap.md#full-pipeline-demo) · [ADR-011](../decisions/ADR-011-full-pipeline-demo.md)

## Contract

`POST /api/v1/graphs/{graph}/reports/{report}/diagnosis` takes `{"arguments":{"identity":"<finding digest>","bundle":"<bundle digest>","context":"<context identity>"}}`. The server resolves these pins against the published report and graph manifest and derives the snapshot and analysis identity from canonical data. Missing graphs, reports or findings return `404`. Invalid requests return `400`. A finding paired with a different context or evidence bundle returns `409 DIAGNOSIS_SCOPE_MISMATCH`.

The service reads one finding through the existing architecture queries. This version supports one direct forbidden dependency, classified as `MUST` or `MAY` in the modeled scope. It matches the path and predicate against the canonical logical edge, recomputes the joint region, and replays the witness against the captured model. For `MAY`, it also replays the counterexample. Each citation needs an evidence record in the finding context or its linked analysis, an exact span on that record, and an independently resolved source path and digest for the same document. Diagnosis retrieval uses limits of 16 source spans, 64 source lookups, and 32 KiB of model input, as well as the canonical graph query budgets and the local API deadline.

The provider receives typed facts with a versioned SHA-256 input identity. It may select citation keys for that input. It has no interface for submitting prose, arbitrary URLs, commands or modifications. The backend validates the input identity, citation membership and uniqueness before rendering the typed explanation. The result includes the original pins and evidence status. `WITHHELD` carries a reason but no explanation or citations. The canonical finding, gaps, report and score stay under the existing analysis services. Opening a citation uses the pinned graph and citation context on the existing `evidence` route. Source locations are one-based with an exclusive end.

## Design choice and limits

The typed model adapter and authored replay exercise the handoff and verification locally. Validating a citation ID establishes its provenance, so the service renders the claim from typed facts it has checked against the graph. The dialog announces its state and result for assistive technology. [NIST AI 600-1](https://nvlpubs.nist.gov/nistpubs/AI/NIST.AI.600-1.pdf) · [W3C status messages](https://www.w3.org/WAI/WCAG22/Understanding/status-messages.html)

[Step 4](demo-live-diagnosis.md) adds the opt-in local model for one pinned fixture input. An external provider still needs separate disclosure authorization and proof. Broader claim types or retrieval beyond a single dependency need their own evidence contracts.

## Verification entry points

- Run `LocalAnalysisRunnerTest#diagnosisRoundTripsToCapturedSourceAndRejectsAnotherBundle` and `ApiSpecificationTest` in `backend` with current workspace dependencies. The positive calls the real passive analysis pipeline and evidence API. Controls remove source evidence, change its digest, use a different context or bundle, and return a citation outside the model input.
- Reuse the symbolic profile-binding graph and policy exported by `M6PolicyIntegrationTest`. Run `diagnosis-workbench.browser.cjs` with Java, the backend test runtime classpath, Edge, a new output directory, and the symbolic graph and policy files. `CapturedWorkbenchAcceptance` verifies the graph and republishes findings through the policy services. The browser activates diagnosis and citations by keyboard. [Fixture producer](../../analyzer-javaparser/src/test/java/com/evolution/analysis/javaparser/M6PolicyIntegrationTest.java) · [M8.3 protocol](../tasks/m8/03-structure-configuration-and-workbench-acceptance.md) · [Browser runner](../../backend/src/test/js/diagnosis-workbench.browser.cjs) · [Server harness](../../backend/src/test/java/com/evolution/backend/query/CapturedWorkbenchAcceptance.java)

From the repository root with JDK 21, current platform dependencies in the local Maven cache, and the previously exported symbolic fixture:

```powershell
.\mvnw.cmd -o -q -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dplatform.build.directory=target/demo-diagnosis' '-Dtest=LocalAnalysisRunnerTest#diagnosisRoundTripsToCapturedSourceAndRejectsAnotherBundle,ApiSpecificationTest' test
if ($LASTEXITCODE) { throw 'Backend verification failed' }
[xml]$run = Get-Content -Raw 'backend/target/demo-diagnosis/surefire-reports/TEST-com.evolution.backend.query.LocalAnalysisRunnerTest.xml'
$classpath = ($run.testsuite.properties.property | Where-Object name -eq 'java.class.path').value
$java = (Get-Command java.exe).Source
$edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$output = Join-Path ([System.IO.Path]::GetTempPath()) ('evo-diagnosis-browser-' + [guid]::NewGuid())
$fixture = (Resolve-Path 'analyzer-javaparser/target/evo-build/workbench-fixtures').Path
node backend/src/test/js/diagnosis-workbench.browser.cjs $java $classpath $edge $output "$fixture/symbolic.graph.json" "$fixture/symbolic.policy.yaml"
```

**CONFIRMED / bounded run (2026-11-15).** Two JUnit cases and eleven Workbench model tests passed. Edge `157.0.4341.46` at 390 CSS pixels opened the `MAY` finding `client.no-store`, then opened the original `src/main/java/app/Client.java` evidence at `1:140–1:151`, the `Store store` parameter. Escape closed the dialog and returned focus to the finding. The finding evidence kept its original status and gaps. The browser receipt pinned diagnosis input `sha256:dbd0bd11ea18505eb3a331cad79853bcba7be7c94d87d4d0b285debff879fa2d`.

| Captured input | File SHA-256 |
|---|---|
| `symbolic.graph.json` | `8d16392736e5df5bf8df5804f8405da357a3dec88f98b92ecc3198f850712aa5` |
| `symbolic.policy.yaml` | `67ca6fce9a53127603f0906e73b7b2a33db089d24a2a3df13deeb6f2215e8232` |

This run covers authored replay. Later evidence is recorded in [repair proposal](demo-repair-proposal.md), [repair verification](demo-repair-verification.md) and [live diagnosis](demo-live-diagnosis.md).
