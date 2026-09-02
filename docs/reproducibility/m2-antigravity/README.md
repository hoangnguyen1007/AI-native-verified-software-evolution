# M2 Antigravity Dispatch Package

Status: **INACTIVE — human discontinued Antigravity on 2026-09-02.** Retain this package as historical preflight material; do not execute its dispatch order or refresh its obsolete read manifest. Continue with Codex only according to the [pause handoff](../m2-pause-2026-09-02/README.md). Operational state belongs in [current state](../../current-state.md).

After the initial rejection described below, the human explicitly authorized M2 repository content excluding secrets/credentials. A session initialized with observed model `gemini-3.1-pro-high`, conversation `55fc2d8f-394f-49c6-a2ed-9890b9ee0ab2`; the CLI denied `read_file` for `analyzer/pom.xml`, and the terminal result was `CANCELED` with an empty response. No successful nonce round-trip, implementation or review resulted. The task-started CLI server was stopped at pause. The remaining sections describe the earlier preparation stage, not current prerequisites.

## Observed host capability

- User-supplied CLI: `C:/Users/Admin/AppData/Local/agy/bin/agy.exe`; local version `1.1.24`.
- Local help confirms `--model`, `--effort low|medium|high`, `--mode plan`, `--output-format stream-json`, `--conversation` and `--print-timeout`.
- `agy models` returned `gemini-3.1-pro-high`, `gemini-3.7-flash-high` and other models. This confirms available selectors, not a successful model run or quota balance.
- The IDE is a separate installation (IDE 2.5.5/editor 1.107.0); its chat launcher does not establish CLI lifecycle control.
- A normal sandbox call initially failed creating the CLI's own data directory. The supported escalation allowed model discovery. The subsequent repo-reading handshake was rejected by automatic approval review because repository documents/diffs would be processed by the external Gemini service without payload-specific consent. The user was asked for that consent; no alternative route was used.

## Model selection

Request Gemini 3.1 Pro High with effort `high` for the design/identity challenge, based on its documented complex-reasoning and planning role. Gemini 3.7 Flash High is an available candidate for a separate oracle/coding audit; its documentation reports improved coding/tool-use capability. Neither source establishes a universal ranking for this project's Java semantics. Pin and inspect each run's harness metadata rather than claiming that a prompt selected the best model. [3.1 Pro capability](https://www.antigravity.google/blog/gemini-3-1-pro-in-google-antigravity), [3.7 Flash capability](https://www.antigravity.google/blog/gemini-3-7-flash-in-google-antigravity).

Do not purchase credits, switch billing paths or accounts, bypass permission checks, invoke an unverified native team mode or treat model self-identification as evidence. Account quota/reset is currently unknown.

## Dispatch order

1. Obtain the required repository-payload permission through the supported approval path. The pending request covers project instructions/docs, M2-relevant source/tests, Git diffs and repository metadata; credentials/secrets remain excluded.
2. Refresh the [required-read manifest](required-read-manifest.json) against the exact authorized baseline. The captured dirty baseline and patches are under `out/m2-design-20260902/control/`; a HEAD-only checkout is insufficient.
3. Run a fresh bounded read-only nonce handshake. The rejected attempt's prompt is preserved as `out/m2-design-20260902/control/handshake.txt`. Inspect returned nonce, actual workspace/HEAD, read receipt, hashes, session/model metadata and unchanged Git state before upgrading the operating mode to DIRECT.
4. Dispatch [M2-AG-01 design challenge](design-review.txt). The port and identity draft is a proposal, not human-approved schema. Read-only stdout output makes this task independent of repository writers.
5. After the oracle pilot and design are frozen, dispatch [M2-AG-02 oracle challenge](oracle-review.txt) in a fresh conversation. Do not send another reviewer’s verdict as the expected answer.
6. Only after reviewing artifacts, resolving consequential human decisions and verifying lifecycle controls, prepare bounded implementation packets with isolated snapshots. No production writer is authorized by these two review packets.

For every actual run, retain exact argv/cwd, requested and observed model/effort, conversation ID, stdout events, sanitized stderr, process exit and terminal envelope. Use an explicit conversation ID for continuation. Headless timeout is not proof the worker stopped. Observe termination or use the supported cancel path before releasing any write ownership. Until cancellation and result collection are verified, modifying dispatch stays unavailable. [Official headless lifecycle](https://www.antigravity.google/docs/cli/headless/).

The manifest is a hash pin, not a comprehension certificate. Workers must read full applicable files, recover truncation and explain how the contracts constrain their answer. A changed file invalidates its old hash; refresh a future attempt without rewriting old raw run evidence.

## Scope and acceptance

Codex owns integration, queue/state and human decision requests. Review workers own no production files and may not spawn children. Their output must include exact evidence locations, counterexamples, commands actually run, unresolved questions and a clear bounded verdict. Agreement between agents is not ground truth.

The pilot and these packets cannot pass M2/G2. M2 needs the complete registered frontend evidence; G2 also needs M3's safe multi-module and real-repository build model. Preserve the legacy comparison as quarantined historical evidence.
