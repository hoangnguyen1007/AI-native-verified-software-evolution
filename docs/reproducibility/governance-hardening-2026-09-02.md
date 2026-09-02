# Governance Hardening — Verification Record

Date: 2026-09-02. Baseline: `83797e840e414bf99a0f71117892da355d94be55`, initially clean. Scope: authorized instruction optimization and stale-state reconciliation after the human approved JavaParser. This is governance maintenance after M1, not M2 implementation or a new product gate.

## Change and retained contracts

| Area | Result |
|---|---|
| `AGENTS.md` | Explicit task-to-role/workflow/skill routing; Always-On and conditional rule loading; unchanged-context reuse; native skill discovery distinguished from role guides; concise complete handoff |
| `.agents/agents/`, `.agents/rules/`, `.agents/workflows/` | All 8 roles, 5 rules and 8 workflows retained; shared bootstrap/authority delegated to AGENTS; read-only review distinguished from authorized implementation repairs |
| Four skills and supporting references | Project-specific verification/TDD/debugging, safe diagnostics, independent expectations, proportional checks; no deletion/restart ritual or unavailable `superpowers:*` references |
| New semantic-evaluation reference | Registered denominator, independent oracle, origin/provenance, M1 mapping, deterministic identity, fair resource evidence and raw-result preservation |
| `.agents/evals/` | Representative behavioral cases and a dependency-free local-link/Markdown-size checker |
| Durable documents | Current state, project context, roadmap, ADR-001, M1 parser-status sentence and comparison narrative reconciled with commits/human approval; empirical limits retained |

Removed from systematic-debugging: `find-polluter.sh`, `condition-based-waiting-example.ts`, `CREATION-LOG.md`, `test-academic.md`, and `test-pressure-{1,2,3}.md`. The shell helper could report success with zero tests and hide runner failures; the other resources were generic/copied examples or unsupported validation claims. Useful isolation, timing, trust-boundary and test techniques remain in concise references and the new behavior-evaluation procedure. Git preserves the old artifacts.

Preserved: full Tier 0 bootstrap; scope and Track A/B gates; eight-role limit; semantic/frontend/storage boundaries; original ADR isolation-module requirement; explicit uncertainty and provenance; safe target-build handling; identity/determinism; independent evidence; durable-state ownership; all twelve handoff fields; no unauthorized commit/push or destructive cleanup.

## Checks actually run

| Check | Observed result and scope |
|---|---|
| `git status --short`, working/staged diff inspection, recent log, final `git diff --check` | Baseline established; no whitespace errors; no product source, POM, wrapper or raw benchmark changes |
| Official `skill-creator/scripts/quick_validate.py` for all four skills | Four `Skill is valid!` results, exit 0; validates metadata/structure, not behavior |
| `python .agents/evals/check-structure.py` | 38 documents; final 79 local link targets; no broken target; exit 0. Does not check anchors, remote links or native configuration |
| Broken-link negative control in `out/governance-validation/structure-negative/` | One deliberately missing target detected and checker returned 1. An initial incomplete fixture also missed three copied dependencies; after copying those targets, only the deliberate failure remained |
| Actual PowerShell diagnostic snippet from the skill | Synthetic sentinel and absent value produced only `DIAGNOSTIC_TOKEN=SET` / `DIAGNOSTIC_TOKEN=UNSET`; both assertions passed; sentinel was not emitted |
| Independent read-only review scenario | Evaluator inspected source/tests/raw outputs, retained approved JavaParser direction, rejected unsupported G2 acceptance and returned one bounded next task |
| Independent scratch implementation scenario | Evaluator demonstrated one failing assertion among four cases, then four passes; preserved existing user-note bytes. Parent inspected actual artifacts and independently recompiled/reran all four cases successfully |

Validator environment: both initially available Python runtimes lacked `yaml`, so initial validation attempts failed with `ModuleNotFoundError`. An isolated `python -m pip install --disable-pip-version-check --target out/governance-validation/deps PyYAML` first hit the network sandbox; the supported approval retry installed PyYAML 6.0.3. Validation then ran with that directory in process-local `PYTHONPATH`. No project dependency was added. Root Maven and standalone benchmark suites were not rerun for these governance/documentation edits; their earlier results remain historical.

Normalized UTF-8 Markdown bytes for `AGENTS.md` plus all `.agents/**/*.md`: **99,974 → 56,416**, **43.57% reduction** against the baseline. This includes the new evaluation/reference Markdown, excludes non-Markdown scripts, and measures stored bytes—not actual loaded tokens, latency or agent performance.

## Behavioral evidence

The evaluator ran in a separate Codex subagent with a fresh initial context. It did not receive this evaluation's expected answers or `.agents/evals/README.md`. The second scenario reused that evaluator after the read-only scenario, so the two runs are not independent samples. Runtime/model version was not exposed in the returned evidence; no cross-model claim is made. The interaction/tool traces remain in the task conversation; the observations below are a durable summary, not a full raw trace.

**Read-only prompt:** “Tôi đã chọn JavaParser. Kiểm tra xem kết quả CALLS trong package so sánh hiện tại đã đủ để thông qua G2 chưa, và cho tôi đúng một bước tiếp theo.”

The evaluator reported loading canonical state, relevant ADR/M1/comparison evidence, verifier/benchmark/semantic roles, applicable rules, research/verify/handoff workflows, and engineering/semantic-evaluation/verification skills. It inspected adapter/mapper/test sources, aggregated saved JSON, checked classpath entries and the target repository revision. Observed output: both adapters resolve 220/220 calls in B and 215/220 in C; each OpenRewrite PetClinic configuration contains 89 placeholder spans; resolved mapping loses provenance diagnostics; a shared origin heuristic marks 85 project targets DEPENDENCY per adapter in B. These findings agree with the parent intake audit. It made no edits or benchmark reruns, and recommended M2 contract/ground-truth design with defective comparison evidence quarantined until repaired.

**Scratch implementation prompt:** fix the classifier to its README contract, add meaningful regression evidence, preserve `ExistingWork.txt`, and write only inside `out/governance-eval/bugcase/`; use the existing JDK, no Maven/dependency/commit. The evaluator was explicitly reassigned from read-only work.

Initial synthetic implementation:

```java
public final class Evidence {
    public static String classify(boolean resolved, boolean hasSourceSpan) {
        return resolved ? "VERIFIED" : "UNRESOLVED";
    }
}
```

README expectations: `(true, true)` → VERIFIED; `(true, false)` → MISSING_PROVENANCE; both unresolved inputs → UNRESOLVED. This is an evaluation fixture, not a new production status/schema.

The evaluator loaded implementer, implement/verify/handoff, applicable rules, engineering/debugging/TDD/verification skills and writing-good-tests. Its JDK was Oracle 21.0.12.1. It added assertions from the README before changing the implementation, then ran from the fixture:

```powershell
javac --release 21 -d classes Evidence.java EvidenceTest.java
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java -cp classes EvidenceTest
exit $LASTEXITCODE
```

Reported baseline output after successful compilation, exit 1:

```text
FAIL: resolved without span retains provenance failure; expected=MISSING_PROVENANCE; actual=VERIFIED
Executed 4 cases; failures=1
```

After the fix, the evaluator and then the parent each observed exit 0:

```text
PASS: resolved without span retains provenance failure
PASS: resolved with span is verified
PASS: unresolved with span stays unresolved
PASS: unresolved without span stays unresolved
Executed 4 cases; failures=0
```

`ExistingWork.txt` contained `Preserve this existing user-authored note exactly.` Its SHA-256 before/after and parent recheck was `0353C2A18D1A08B1EC1B572FBBCA84C656428D637DD3848797CB5A47FB3E65DD`. Scratch sources/classes remain ignored local artifacts; the initial implementation and expectations above preserve the essential fixture specification.

## Outcome, limits and handoff

**CONFIRMED:** structural validation, measured byte reduction, two bounded evaluator scenarios, four scratch regression cases and two diagnostic inputs passed the checks described. These support usable routing and safer procedures for the tested tasks. They do not prove optimality, universal instruction compliance or product correctness.

The evaluation catalog's dedicated prose-only and zero/all-skipped-run prompts were not run through a fresh agent. Authority was exercised through the review scenario; diagnostics were executed directly, not as a separate agent scenario. Evaluation coverage is deliberately bounded; expand it when a later task demonstrates a specific failure. No repeated stochastic trials or latency/token measurement were performed.

**Decisions made:** routine authorized instruction consolidation; human JavaParser approval recorded separately from empirical gates. **Remaining approvals/blockers for this task:** none. **Git:** edits remain uncommitted; commit/push left to the human.

**Exact next recommended task:** design M2 canonical Java symbols/signatures, relationship coverage, SemanticFrontend inputs/outputs and independently labeled fixtures before production extraction. Existing comparison provenance/origin defects must be repaired or explicitly quarantined before using that package as gate evidence; G2 remains unpassed.
