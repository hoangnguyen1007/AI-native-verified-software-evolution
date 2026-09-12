# M4-R0 research package

This standalone package is outside the Maven reactor. It contains authored microfixtures and an executable bounded semantics specification, not production Spring analysis. See the [contract](../../docs/architecture/m4-r0-semantics-gate.md), [assessment](../../docs/research/2026-09-11-m4-r0-spring-semantics.md) and [recorded verification](../../docs/reproducibility/m4-r0-2026-09-11/README.md).

M4-R0 was human-accepted on 2026-09-11. That decision accepts this bounded baseline; it does not erase its recorded gaps or promote the standalone oracle/runtime harness into production semantics.

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

Checked-in package verification is self-contained and performs no download, compilation or execution:

```powershell
python benchmarks/m4-r0/test_integrity.py -v
python benchmarks/m4-r0/verify_package.py
python docs/reproducibility/m4-r0-2026-09-11/verify_manifest.py
```

The optional `python benchmarks/m4-r0/verify_package.py --require-cache` layer verifies all 26 cached JARs and 34 acquired source bytes after the explicit fetch steps. A clean clone reports all absent cache inputs as typed `UNAVAILABLE` with exit code 2 instead of throwing or silently weakening the check. The former `.cache/before` dependency has been removed; current local links are checked directly.

The exact gap snapshot includes study inputs plus neutral analyzer source hashes, so adding or changing study files changes its snapshot identity. It does not identify an external analyzed repository. Absolute paths and timings in runtime provenance are excluded from canonical semantic output; classpath order and exact artifact identities are retained. ASCII-only formal JSON is a research encoding, not proof of M1-compatible Java Unicode identities.

No full reactor test or real-repository benchmark is required to reproduce these isolated research results. The later human gate acceptance is recorded in the assessment; it does not convert this author-built harness into independent empirical evidence.

The runtime denominator covers only the three exact Framework/Boot pairs in `runtime-labels.json`; 13 `VERSION_FRAGMENT_NOT_VALIDATED` gap records keep older eras and unexecuted patches explicit. The formal binding function receives an already type/qualifier-filtered, complete, single-context candidate set. Its eight cases cover only the normalized primary/fallback/name/priority choice. Type assignability, generic containers/providers, composed qualifier discovery and production binding remain outside this oracle and cannot be inferred from the 70/70 total.

The historical package remains deliberately outside Maven because it compiles and executes pinned framework microfixtures. Production M4A.1 code and its passive evidence boundaries are covered by the root reactor; the research harness is verified by the explicit commands above. Integrating runtime execution into the normal reactor would broaden the trusted-execution boundary and is not part of this hardening.
