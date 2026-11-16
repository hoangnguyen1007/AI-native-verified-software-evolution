# Demo Step 2 — Repair proposal

Open `client.no-store` in Workbench, choose **Diagnose finding**, then **Review repair proposal**. Workbench shows the affected modeled dependency, the plan and a downloadable unified patch. The proposal removes `Store store` from the empty `Client` constructor. It preserves `@Profile("dev")`, the `Store` bean and every other source byte. [Diagnosis contract](demo-finding-diagnosis.md) · [ADR-011](../decisions/ADR-011-full-pipeline-demo.md)

## Inputs and outputs

`POST /api/v1/graphs/{graph}/reports/{report}/repair-proposal` accepts the same `identity`, `bundle` and `context` in `arguments` as `/diagnosis`, plus the exact `diagnosisInput` digest from that response. The backend regenerates and checks the diagnosis from the published artifacts. A mismatched digest returns `409 DIAGNOSIS_INPUT_MISMATCH`. Existing graph, report and finding checks still apply. Withheld diagnosis, unregistered fixture, missing source proof or an unavailable transformation returns `WITHHELD` with a reason and no patch.

The registered symbolic baseline fixes the snapshot, raw policy SHA-256, rule version, `MAY` classification and `spring.selected-binding` scope. Its old graph contains source descriptors without embedded source bytes. The proposal service admits a registered copy of the sole editable file after matching its digest and the exact constructor-parameter citation to the graph. The route has no source upload and accepts no path or recipe from the request.

The backend runs `DemoRepairRecipe` through the `8.87.7` OpenRewrite Java parser and recipe library on one in-memory source. It checks the input round trip and requires the printed result to equal the registered single edit before serializing the patch. This runs a trusted platform transformation over fixture text with no target build or target classpath. The proposal preserves both no-final-newline markers so applying the patch yields the declared candidate file digest. [OpenRewrite recipes](https://docs.openrewrite.org/concepts-and-explanations/recipes) · [OpenRewrite Java visitors](https://docs.openrewrite.org/concepts-and-explanations/visitors) · [Recipe source](../../backend/src/main/java/com/evolution/backend/query/DemoRepairRecipe.java)

The result carries `PROPOSED` and `verification: UNVERIFIED`. It binds the original snapshot, graph, report, finding and evidence bundle to the diagnosis input, modeled region, source citation, policy and recipe versions, before and after file digests, and patch digest. The proposal identity is SHA-256 over the version and canonical candidate. `expectedFindingChange: REMOVED` is the proposed outcome for the verifier. The verifier will need baseline and candidate evidence for the same policy and configuration scope and checks for `dev` enabled and disabled. The diagnosis retains its `LOCAL_REPLAY` label.

## Reproduction

The following trusted platform commands build the shaded CLI and use test-only `CapturedWorkbenchAcceptance` to serve the existing source-generated symbolic fixture. The browser verifies the entire diagnosis and proposal flow using that runtime. See Step 1 for how to obtain the graph and policy captures. [Fixture generation and hashes](demo-finding-diagnosis.md#verification-entry-points)

```powershell
.\mvnw.cmd -o -q -pl cli -am '-Dplatform.build.directory=target/demo-diagnosis' '-DskipTests' package
if ($LASTEXITCODE) { throw 'Packaging failed' }
$classpath = (Resolve-Path 'backend/target/demo-diagnosis/test-classes').Path + [System.IO.Path]::PathSeparator + (Resolve-Path 'cli/target/demo-diagnosis/evo.jar').Path
$java = (Get-Command java.exe).Source
$edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
$output = Join-Path ([System.IO.Path]::GetTempPath()) ('evo-repair-browser-' + [guid]::NewGuid())
$fixture = (Resolve-Path 'analyzer-javaparser/target/evo-build/workbench-fixtures').Path
node backend/src/test/js/diagnosis-workbench.browser.cjs $java $classpath $edge $output "$fixture/symbolic.graph.json" "$fixture/symbolic.policy.yaml"
```

**CONFIRMED / bounded run (2026-11-15).** The packaged CLI served the proposal through the Edge Workbench. A repeat request returned the same candidate and proposal identity. The downloaded `.patch` matched the API digest. `git apply --check` accepted it on the exact baseline and rejected it after adding a final newline to that source. The API rejected a stale diagnosis digest and withheld a proposal for a separate Java finding outside the fixture. `MavenBuildModelProviderTest` checks the Maven model provider with the resolved shared dependencies. The backend contract, OpenAPI and Workbench model checks passed.

| Recorded identity | SHA-256 |
|---|---|
| Proposal | `d759b5ee0b1cbc2811824fcc8f7d520ce14f85c84a7d1d8239c0835d1f50b224` |
| Patch | `1c82cb9f2d7310b826c04c84d72f88b21487b5a21a836e95d5e617d4580fc38d` |
| Baseline Client.java | `10026b4edd69da18adc2587c77db51b149a6ac597d1758681736a291e6ce3eaa` |
| Proposed Client.java | `011ce2d22e7bc8e4359940a80366be5d3df1021c0e20a96563333db200cf1073` |

This run covers the deterministic proposal. See [Step 3](demo-repair-verification.md) for isolated verification and [Step 4](demo-live-diagnosis.md) for local model citation selection.
