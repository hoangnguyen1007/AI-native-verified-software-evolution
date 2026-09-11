# M4-R0 research package

This standalone package is outside the Maven reactor. It contains authored microfixtures and an executable bounded semantics specification, not production Spring analysis. See the [contract](../../docs/architecture/m4-r0-semantics-gate.md), [assessment](../../docs/research/2026-09-11-m4-r0-spring-semantics.md) and [recorded verification](../../docs/reproducibility/m4-r0-2026-09-11/README.md).

## Inputs and evidence

| File | Responsibility |
|---|---|
| PROTOCOL.md | Preregistered experiments, denominator, limits, metrics and stopping criteria |
| sources.json / sources-acquired.lock.json | Official source URLs and acquired-byte identities; the original sources.lock.json retains the failed restricted attempt |
| artifacts.lock.json | Exact 26 JAR coordinate/URL/byte/SHA-256 entries; no transitive dependency discovery |
| mechanisms.json | Proposed 29-family spring-mechanisms:v2 catalog, target states and gap requirements |
| annotation-census.json | Every declared annotation class in the exact artifact set; no annotation-use or full-history claim |
| runtime-labels.json | 79 pre-execution labels for actual Spring fixtures, across three exact framework pairs |
| cases.json | 70 formal specification cases; author labels, not independent review |
| src/test/resources/fixtures | Small authored Java/XML inputs; no target repository code |
| oracle.py | Bounded three-valued model, finite-world witnesses, flat baseline and independent CNF strategies |
| gap-observations.tsv | Exact research provider observations, generated from known study gaps |
| GapExport.java | Constructs actual immutable M3 CapabilityGapRecord values under a separate research reason catalog |

## Reproduction

Use Python 3 with its standard library and the documented JDK 21. Run from the repository root. The fetch scripts are passive, bounded, explicit-origin downloads; the runner only compiles/executes authored fixtures and pinned Spring library code. They never invoke a target Maven/Gradle lifecycle or a target annotation processor. `.cache/` holds downloaded inputs and compiled fixture classes and is Git-ignored.

```powershell
python benchmarks/m4-r0/fetch_sources.py --lock sources-acquired.lock.json
python benchmarks/m4-r0/fetch_artifacts.py
python benchmarks/m4-r0/inventory_annotations.py
python benchmarks/m4-r0/run_runtime.py --output docs/reproducibility/m4-r0-new/runtime
python benchmarks/m4-r0/oracle.py --output docs/reproducibility/m4-r0-new/oracle
python benchmarks/m4-r0/prepare_gaps.py
javac -proc:none --release 21 -sourcepath analyzer/src/main/java -d benchmarks/m4-r0/.cache/gap-classes benchmarks/m4-r0/GapExport.java
java -cp benchmarks/m4-r0/.cache/gap-classes GapExport benchmarks/m4-r0 docs/reproducibility/m4-r0-new/gaps
```

Always choose a new output directory. Existing raw outputs and locks are not overwritten. Source locks intentionally fail comparison if a mutable source page or acquisition outcome changes; preserve the old evidence and pass a new lock filename. The runner verifies every JAR hash before execution. A failed retrieval or compile/runtime is recorded/reported, not scored as an empty successful result.

The exact gap snapshot includes study inputs plus neutral analyzer source hashes, so adding or changing study files changes its snapshot identity. It does not identify an external analyzed repository. Absolute paths and timings in runtime provenance are excluded from canonical semantic output; classpath order and exact artifact identities are retained. ASCII-only formal JSON is a research encoding, not proof of M1-compatible Java Unicode identities.

No full reactor test or real-repository benchmark is required to reproduce these isolated research results. Gate promotion still requires the independent/human review recorded in the assessment.
