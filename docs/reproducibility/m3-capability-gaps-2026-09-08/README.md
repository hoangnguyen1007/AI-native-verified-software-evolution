# M3.6 Normalized Capability-Gap Core — Verification Record

Date: 2026-09-08

Scope: M3.6 core only; this is not the representative real-repository checkpoint or a Gate G2 report.

## Implemented boundary

Neutral contracts in `analyzer` add content-addressed provider-observation references, typed evidence subjects and requirements, normalized capability gaps, bounded acquisition attempts, provider conflicts, additive gap resolutions and a deterministic aggregate ledger. Stable identifiers and schemas are:

- `capability-gap-record-v1` with catalog `evidence.capability-gap-catalog:m3.6-v1`;
- `acquisition-attempt-record-v1`;
- `provider-conflict-record-v1`;
- `gap-resolution-record-v1`;
- `evidence-acquisition-ledger-v1`; and
- normalizer `evidence.gap-normalizer:m3.6`.

`CapabilityGapNormalizer` consumes only supplied immutable M2/M3 outputs. It has no filesystem, network, process, build-execution or provider-selection capability. It preserves exact provider-result and payload digests, exhaustively maps the current registered degraded enums, keeps semantic attribution/origin/provenance/unmapped dimensions separate, and leaves candidate-provider lists empty unless a caller explicitly supplies an advisory candidate.

Gap identity uses the contract/catalog version, snapshot plus optional already-derived M1 analysis identity, detecting provider/version, mechanism/reason, typed subject, real source anchors and normalized evidence requirements. Runtime messages, candidate providers and attempt history are additive provenance rather than identity inputs. Build-stage gaps use snapshot-only context, avoiding a fabricated or circular analysis identity.

## TDD and verification

The first sandboxed wrapper invocation failed before compilation because the wrapper tried to create `C:\.m2`; it established no behavior. The supported project configuration below then reached the intended red state: `CapabilityGapContractTest` failed compilation because the M3.6 contracts did not yet exist.

All reported checks used Windows 11 amd64, Oracle JDK 21.0.12.1, pinned Maven 3.9.16, `MAVEN_USER_HOME=C:\Users\Admin\.m2`, the explicitly selected local repository `C:/Users/Admin/.m2/repository`, and offline mode. No target-repository lifecycle or network operation was run.

```powershell
$env:MAVEN_USER_HOME='C:\Users\Admin\.m2'
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer -am '-Dtest=CapabilityGapContractTest,SourceDecoderTest,FrontendInputAssemblerTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' -pl analyzer -am test
.\mvnw.cmd -o -B -ntp '-Dmaven.repo.local=C:/Users/Admin/.m2/repository' verify
```

| Check | Result |
|---|---|
| Initial M3.6 specification against absent production types | Expected test-compilation failure for missing `com.evolution.analysis.evidence` contracts |
| Focused contract + decoder + assembler verification | 19 tests, 0 failures, 0 errors, 0 skips |
| Complete `analyzer` test suite | 73 tests, 0 failures, 0 errors, 0 skips |
| Final root reactor `verify` | 184 tests: analyzer 73, analyzer-maven 41, analyzer-filesystem 11, analyzer-javaparser 58, backend 1; 0 failures, 0 errors, 0 skips; all six reactor projects succeeded |

The contract tests include build-level and real-source-span golden identities/canonical-serialization digests; identity sensitivity to provider/reason/subject; deterministic duplicate-gap history merging; denial and attempt invariants; order-neutral provider conflicts; additive successful resolution with rejection of denied resolution; exhaustive current catalog coverage; combined repository/ownership/classpath/platform normalization; frontend run/source/semantic/origin/unmapped normalization; and context mismatch rejection. Existing decoder and assembler tests exercise normalization on their real degraded outputs.

## Limits and next task

This is implementer self-review and reactor verification, not independent acceptance. It does not run a representative external repository, adjudicate semantic correctness, generate category/reason tables, validate query/UI projections, add a generated-source/reactor-output provider, or pass G2. Older provider result schemas sometimes expose a content-addressed request identity rather than expanded permission/timing/limit fields; the normalizer preserves that exact input reference and records permission as `NOT_RECORDED` instead of inventing authorization.

Exact next task: run the pinned representative real-repository pipeline and produce the category/reason-level Checkpoint G2 evidence and Gate report, including independent semantic acceptance. Do not promote G2 unless every registered criterion is supported by preserved evidence.
