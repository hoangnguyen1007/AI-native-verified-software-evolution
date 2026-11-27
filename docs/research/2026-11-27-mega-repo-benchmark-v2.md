# Moonshot #5: Pure-Java SCIP Semantic Capture & Reproducible Mega-Repo Benchmark v2

*2026-11-27 — Empirical Scale & SCIP Ingestion Report*

## 1. Executive Summary

**CONFIRMED (directly measured in-process via `ScipAndMegaRepoBenchmarkTest` on Java 21 / Windows 11, zero external Maven dependencies added).**

Moonshot #5 delivers:
1. **Clean-Room Pure-Java Protobuf v3 Wire Decoder (`ScipProtobufReader`):** Zero-dependency binary `index.scip` parser (`<= 64 MiB` input cap, bounded nesting depth `<= 16`, bounded element counts, strict UTF-8 and wire-type validation) supporting the Apache-2.0 `scip.proto` schema (`Index`, `Metadata`, `ToolInfo`, `Document`, `Occurrence`, `SymbolInformation`, `Relationship`).
2. **Hard-Sandboxed SCIP Semantic Capture Adapter (`ScipSemanticCaptureAdapter`):** Enforces `I1` (never executes `scip-typescript`, `scip-python`, `scip-go`, `scip-java`, or any external indexer on the host), `I2` (mandatory SHA-256 verification before decoding, throwing `SCIP_SHA256_MISMATCH` on mismatch), `I3` (explicit `AMBIGUOUS_MULTIPLE_DEFINITIONS` and `UNRESOLVED_EXTERNAL_SYMBOL` `ScipCoverageGap` records), and `I4` (deterministic two-pass merge + single-definition guard keeping lexicographically first `(relativePath, startLine)` and emitting bit-identical `CanonicalJson`).
3. **Reproducible Multi-Scale Benchmark v2 (100 to 10,000 Project Type Nodes + 500-Doc Polyglot SCIP Archive):** All core structural engines (`TarjanSccAnalyzer`, `GraphProjectionService.projectMultiScale(AUTO)`, `DsmMatrixBuilder`, `VerticalTraceEngine`, and `ScipSemanticCaptureAdapter`) executed deterministically in-process with zero `KnowledgeGraph` mutation and `< 50 KiB` (`50,367 B < 51,200 B`) `MACRO` Cytoscape JSON payloads even at `10,000` project types / `90,100` edges.

---

## 2. Reproducible Multi-Scale Engine Latency & Payload Telemetry

**Test Driver:** [ScipAndMegaRepoBenchmarkTest.java](../../analyzer-execution/src/test/java/com/evolution/analysis/execution/scip/ScipAndMegaRepoBenchmarkTest.java) (`reproducibleMegaRepoBenchmarkV2`, preceded by a 50-node warmup pass).

| Project Type Scale | Total Graph Nodes | Total Graph Edges | Tarjan SCC (ms) | SCC Blocks | Cyclic Nodes | `AUTO` Level | Projection (ms) | Projected Nodes / Edges | Cytoscape JSON Bytes | DSM `PACKAGE` (ms) | DSM Items / Cells | Levelization | Vertical Trace (ms) | Trace Hops / Layers |
| ---: | ---: | ---: | ---: | ---: | ---: | :---: | ---: | :---: | ---: | ---: | :---: | ---: | ---: | :---: |
| **100** | 118 | 420 | `0.743` | 7 | 100 | `MICRO` | `12.794` | `100 / 309` | `218,170 B` | `7.173` | `8 / 9` | `0.7778` | `9.470` | `60 / 6` |
| **1,000** | 1,030 | 4,036 | `4.020` | 15 | 1,000 | `MACRO` | `51.910` | `16 / 17` | `17,341 B` | `36.355` | `16 / 17` | `0.8824` | `50.060` | `60 / 6` |
| **5,000** | 5,054 | 35,068 | `23.648` | 31 | 5,000 | `MACRO` | `255.503` | `32 / 33` | `33,848 B` | `125.837` | `32 / 33` | `0.9394` | `177.388` | `60 / 6` |
| **10,000** | 10,078 | 90,100 | `228.641` | 47 | 10,000 | `MACRO` | `1455.199` | `48 / 49` | `50,367 B` | `786.400` | `48 / 49` | `0.9592` | `1203.271` | `60 / 6` |

### Key Quantitative Observations
- **Sub-50 KiB `MACRO` Wire Budget at 1k–10k Nodes:** Once project entity count exceeds `GraphProjectionService.AUTO_MACRO_THRESHOLD` (`250`), `AUTO` automatically switches to `MACRO` community super-nodes: `17,341 B` at `1k`, `33,848 B` at `5k`, and `50,367 B` (`49.19 KiB < 50 KiB`) at `10k` nodes (`90,100` edges), avoiding `16 MiB` payload blowup and preserving bit-identical 2-run `CanonicalJson` output.
- **Iterative O(V + E) Tarjan SCC:** Non-recursive primitive-stack Tarjan SCC completes `10,000` nodes / `90,100` edges in `228.641 ms` without `StackOverflowError`.
- **Full-Stack 6-Layer Vertical Trace:** Even on a `10,078`-node / `90,100`-edge multi-context `KnowledgeGraph`, `VerticalTraceEngine.trace` traverses all 6 canonical swimlanes (`FRONTEND`, `API_ROUTE`, `SPRING_CONTROLLER`, `SPRING_DI`, `SERVICE`, `JPA_ENTITY`) bounded cleanly at `60` hops in `1,203.271 ms` while leaving `KnowledgeGraph.identity()` bit-identical.

---

## 3. 500-Document Multi-Language SCIP Archive Benchmark

| Metric | Measured Value |
| :--- | :--- |
| **Archive Scope** | `500` documents (`125` TypeScript, `125` Python, `125` Go, `125` Java) |
| **Binary `index.scip` Payload Size** | `360,378 B` (`351.93 KiB`) |
| **Defined Symbols Upgraded (`SOURCE_SYNTAX` &rarr; `COMPILER`)** | `2,000 / 2,000` (`boundCallTargets = true`) |
| **Total Occurrences Decoded** | `4,000` |
| **Ambiguous Definitions** | `0` |
| **Explicit Coverage Gaps Preserved (`UNRESOLVED_EXTERNAL_SYMBOL`)** | `1` (`scip-ext npm shared-sdk 1.0.0 Client#`) |
| **SHA-256 + Protobuf Decode + 2-Pass Merge Latency** | `70.151 ms` |
| **Throughput (Occurrences / sec)** | `57,020 occ/s` |
| **`CanonicalJson.write(result.toView())` Latency** | `0.357 ms` |

---

## 4. Honest Scope & Limitations

1. **Synthetic Deterministic Workload vs. Live External OSS Git Clones:** Benchmark v2 measures real in-process platform engines (`TarjanSccAnalyzer`, `GraphProjectionService`, `DsmMatrixBuilder`, `VerticalTraceEngine`, `ScipProtobufReader`, `ScipSemanticCaptureAdapter`) over deterministically generated multi-module Java+Spring+Polyglot `KnowledgeGraph` and binary `index.scip` payloads (`100` to `10,000` types, `500` SCIP docs). It does **not** clone external OSS repositories or run live `scip-*` indexers on the host (explicitly prohibited on host by `AGENTS.md` `I1`).
2. **SCIP Generation Boundary:** Real-world `index.scip` files must continue to be produced inside isolated worker containers/VMs or CI captures and verified via `expectedSha256` before ingestion.
3. **Leiden Cohesion & Super-Node Count Sensitivity:** In `GraphProjectionService`, if a large repository exhibits very sparse sub-clusters (`cohesion < 0.05` at `>= 50` members), `LeidenCommunityDetector` recursively splits communities (up to depth 3), which increases `MACRO` node/edge count and JSON byte size proportionally.

---

## 5. Verification Command & Outcome

```powershell
.\mvnw.cmd -o -B -ntp -pl .,analyzer-execution "-Dplatform.build.directory=target/m5-w1" "-Dtest=ScipAndMegaRepoBenchmarkTest" test
```

- **Result:** `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` (exit code `0`).
