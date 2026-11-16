# Demo Step 4 — Live diagnosis

The Workbench can ask a local language model to select source citations for one pinned finding. The model receives the rule, scope, classification, relationship kinds, and numbered citation candidates with relative paths and spans. It receives no source text, repository URL or credentials. It returns citation numbers only. Workbench maps them back to canonical citation identities and renders the explanation from the independently checked graph and witness. [Diagnosis contract](demo-finding-diagnosis.md) · [Demo roadmap](../roadmap.md#full-pipeline-demo)

## Operator setup and failure behavior

The default is `LOCAL_REPLAY`. Start Workbench with all three JVM properties before `-jar` to enable `LIVE` for the registered input:

| JVM property | Value |
|---|---|
| `evo.demo.diagnosis.port` | Local chat server port on `127.0.0.1` |
| `evo.demo.diagnosis.model` | Alias of the local model, including the model digest for the registered run |
| `evo.demo.diagnosis.input` | Exact `input` SHA-256 from the authored replay of the fixture |

The input digest includes the snapshot, graph, report, context, finding, evidence bundle and typed diagnosis data. A mismatch withholds the diagnosis before making a model request. The fixed HTTP route is `/v1/chat/completions`; the client disables proxies and redirects. It bounds both payloads to 32 KiB, requests at most 96 output tokens, and gives the call up to 20 seconds within the report query deadline. Malformed output, foreign or duplicate citations, and unavailable responses are withheld. There is no automatic substitution of replay when live diagnosis fails. Setting a model alias identifies the operator configuration; it is not a server attestation of the weights.

The generation schema enumerates the offered citation numbers. The backend still validates the response independently. In the first live run the model returned a number outside the three offered options and the backend withheld the diagnosis. The bounded schema addressed that failure without changing the citation checks.

Repair proposal and verification recheck the same pinned input from the deterministic evidence path. The recipe chooses the exact registered edit independently of the model's selection. The proposal retains `diagnosisMode: LOCAL_REPLAY` and the original identity, so the Step 3 capture and CI entry point can be reused without a model call.

## Reproduce

1. Use the exact baseline, candidate and control graphs, policy and sandbox capture from [Step 3](demo-repair-verification.md#reproduction). Package the platform and set `$java`, `$classpath` and `$edge` as in the [Step 2 browser commands](demo-repair-proposal.md#reproduction). For a different authorized fixture capture, obtain its input digest using the same Workbench run with no live settings.
2. Download the [llama.cpp b12428 Windows x64 CPU release](https://github.com/ggml-org/llama.cpp/releases/tag/b12428) and the [Qwen 2.5 0.5B Instruct Q4_K_M GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q4_k_m.gguf) outside the repository. Verify SHA-256 before unpacking or loading either artifact.

| Artifact | SHA-256 |
|---|---|
| `llama-b12428-bin-win-cpu-x64.zip` | `4ebc7fb961cf707e3b61396806e62c9e4065e42990cfc31f238eb48264a465b2` |
| `qwen2.5-0.5b-instruct-q4_k_m.gguf` | `74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db` |

3. In a separate terminal, run the local server against the checked `$weights` file. Assign the same `$model` and `$port` in the browser runner terminal.

```powershell
$port = 18091
$model = 'qwen2.5-0.5b-instruct-q4-k-m@sha256:74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db'
& $llamaServer -m $weights --offline --host 127.0.0.1 --port $port --alias $model --no-webui --ctx-size 8192 --parallel 1 --threads 6
```

4. After the local server is healthy, run the browser journey with the paths to the prior snapshots, policy, and capture. It opens the actual model-selected citation and then checks patch download, the PR preview, and CLI outcomes using the existing verification evidence.

```powershell
$output = Join-Path ([System.IO.Path]::GetTempPath()) ('evo-live-browser-' + [guid]::NewGuid())
$inputPin = 'sha256:6634b51796142b55a7bddcecafafb0d7e0bf94fdf26ad55a3da635304f80a126'
node backend/src/test/js/diagnosis-workbench.browser.cjs $java $classpath $edge $output "$snapshots/baseline.graph.json" $policy "$snapshots/candidate.graph.json" "$snapshots/control.graph.json" $capture $port $model $inputPin
```

## Observed run

**CONFIRMED / 2026-11-16.** The checked local Qwen weights ran on llama.cpp build 12428 (commit `b4c0e00a4`). The model server reported 245 prompt tokens and 27 generated tokens for the diagnosis call. Edge `157.0.4341.46` showed `LIVE / CITATIONS_VALIDATED` with three accepted citations and opened `Client.java` at `1:140–1:151`. The backend used the checked `MAY` region and the original evidence and gaps. The selected set matched the replay set for this input.

| Run input or result | SHA-256 |
|---|---|
| Baseline graph file | `c3d236fc24f639fbd9733e2e1ee2ea9a16123ae323bc476982a71305f2a02bc7` |
| Candidate graph file | `54bdaabdc92d0d1831b2b8f5ae89c726dba100364cf58f99b0dc1318ba759f` |
| Negative control graph file | `f401fe956b386f3fe43a2e875e822598b5f64d68d29c807fb1f7a859e4630e6a` |
| Policy v2 file | `8f301531495bb8b68637768aac4e5d4e6883aca2cd919001f2849436268a3a58` |
| Opened `Client.java` citation identity | `6688d380b828ccfa22864b0379a8d1ea6e41d5199a4a7c5d42bdccf38b70af5d` |
| Proposal identity | `411ac3499ed5f23a6f9db85affa48b7bf059692730b8532a77e2336ebbddb07b` |
| Patch file | `1c82cb9f2d7310b826c04c84d72f88b21487b5a21a836e95d5e617d4580fc38d` |
| Reused VM capture file | `469abe255a6e0aa31a5bac344d718e29a6d6475e6edbd8d725fe217668e9e32f` |

The same browser run downloaded and checked the patch, opened the local PR preview, and confirmed CLI parity: `VERIFIED_DEMO` exited `0`, the wrong candidate snapshot exited `4`, and a tampered proposal exited `2`. It reused the authenticated two-profile VM capture from Step 3. Strict M11 comparison still withheld the candidate transition with `UNKNOWN_FEASIBILITY`. This run establishes live citation selection on one fixture and its local handoff. It does not evaluate general model accuracy or use an external provider.

The [Step 5 closure run](demo-finding-transition.md#closure-run) repeats this journey with the checked candidate source census and proves the strict transition for both registered profiles.

Three backend JUnit cases passed with no failures, errors or skips. The diagnosis case exercised the real passive graph and a controlled loopback server, covering the registered input, an unregistered input with no model call, a foreign ordinal, duplicates, extra output, a redirect, an oversized result and an unavailable server. The browser run above used actual model inference.

```powershell
.\mvnw.cmd -o -q -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dplatform.build.directory=target/demo-diagnosis' '-Dtest=LocalAnalysisRunnerTest#diagnosisRoundTripsToCapturedSourceAndRejectsAnotherBundle,ApiSpecificationTest,DemoRepairCaptureTest' test
```
