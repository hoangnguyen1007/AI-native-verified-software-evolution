# M2 independent JDK oracle pilot

**PROVISIONAL design evidence, not M2 completion or G2 acceptance.** This standalone package has no Maven, JavaParser or third-party dependencies. It runs trusted local fixtures through JDK 21 compiler APIs without executing fixture code, target builds or annotation processors.

The pilot addresses a few risks in the proposed [M2 design](../../docs/architecture/m2-semantic-frontend.md). It does not implement or approve the production `SemanticFrontend`, canonical Java naming grammar, M1 changes or D1–D3. The labels are assistant-authored from source/JLS reasoning before the first oracle execution. Independent human review is still needed before these become an accepted ground-truth corpus.

## Registered corpus and limits

`expected.json` freezes 34 call occurrence labels, 3 identifier-equality checks, and hashes of 6 UTF-8 source files. There are 7 isolated compilations:

| Group | Labels | Check |
|---|---:|---|
| identifiers | 6 | Composed/decomposed names remain distinct; U+200C ignorable and Unicode-escape aliases bind to the same declarations as plain spellings |
| bindings | 10 | Primitive/boxed/widening overload selection, generic chains, declaration erasure, varargs/array/object overloads; source declaration versus platform origin |
| offsets-lf | 7 | Duplicate expressions, tabs, multiline expressions, supplementary Unicode, escaped identifier and an escaped line terminator |
| offsets-crlf | 7 | Preregistered byte-for-byte LF-to-CRLF variant of the same fixture |
| ambiguous | 1 | Ambiguous invocation diagnostic; recovered compiler target is inadmissible |
| missing | 2 | Missing type diagnostic; both known and unresolved calls remain inadmissible |
| malformed | 1 | Syntax diagnostic; even an otherwise valid call is inadmissible |

Thirty labels adjudicate raw compiler method targets and source spans. Four labels check error handling only; they make no claim about a correct target. Each compilation with an ERROR diagnostic blocks **all** its bindings as oracles. This conservative pilot rule deliberately avoids pretending to identify a smaller unaffected region. Raw recovered elements and diagnostics remain visible.

All explicit method calls in these fixtures are registered; unexpected explicit calls and duplicate observations fail evaluation. Compiler-inserted calls without complete source positions remain in raw output, are separately counted, and never receive fabricated spans. Those are not a constructor category evaluation. Raw callers and declarations are retained, but the pilot does not score the full declaration/caller matrix, unrelated relationship categories, unsupported modern-Java syntax, external dependency origin, multi-module classpaths, performance or memory.

Origin is established from an inventoried source declaration or an actual compiler platform file/module lookup. `javax.lookalike.Bindings` is a project source fixture intentionally resembling a platform package prefix. The empty classpath/sourcepath/modulepath and JDK `release`, `lib/modules` and `lib/ct.sym` hashes pin the selected platform context. This is a JDK 21/current-release experiment, not proof of origin resolution across alternate releases or toolchains.

## Run

From the repository root in PowerShell:

```powershell
python.exe benchmarks/m2-ground-truth/run_oracle.py --jdk 'C:/Program Files/Java/jdk-21.0.12.1' --output-root out/m2-oracle-pilot-runs
```

Use an explicit JDK 21 home and a writable output parent. The runner creates a timestamp/UUID attempt directory with `exist_ok=False`; it never deletes old runs or reuses an existing attempt. Python uses only its standard library. No artifact downloads occur.

Every attempt validates the preregistered source hashes, snapshots the labels and input bytes before compilation, records selected environment/tool hashes and exact commands, then compiles the oracle and starts a separate JVM per group. Compiler settings are `--release 21 -proc:none -encoding UTF-8`; all resolution paths are explicitly empty except the selected JDK platform. Annotation processors are also set to an empty list. Ambient `CLASSPATH`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` values are removed from subprocess environments; only the names of removed keys are recorded. Subprocesses have a 60-second timeout.

Outputs include `manifest.json`, frozen `preregistration.json`, exact `inputs/`, `commands.json`, untouched subprocess stdout/stderr, parsed `*.raw.json` and derived `summary.json`. Failures are retained in the unique attempt; error or mismatch exits are nonzero. Empty corpora/groups are errors. A mismatch does not regenerate labels. Investigate the source, oracle and label independently; preserve the failed attempt when changing an instrument.

The runner matches by original file and UTF-16 start/end offsets, never printed expression text. Exact slices and preregistered line/column spans are checked separately. Original columns count tabs as one and astral code points as two UTF-16 units; ends are exclusive. Raw compiler line/column values are separate because compiler tab columns may be display-expanded. The CRLF transform is declared before execution and recorded in copied inputs and hashes.

## Sources and evidence interpretation

- [JLS 21 §3.3 and §3.8](https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html): Unicode translation and identifier equality rules motivate the labels.
- [JLS 21 §15.12](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.12): method invocation and overload rules motivate the binding fixtures.
- [JDK 21 SourcePositions](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/SourcePositions.html): raw tree source positions, including absent positions.
- [JDK 21 StandardJavaFileManager](https://docs.oracle.com/en/java/javase/21/docs/api/java.compiler/javax/tools/StandardJavaFileManager.html): explicit resolution locations and compiler file lookup.

This is an exploratory pilot, so verification uses preregistered experiments and runner negative controls under the repository's TDD exception for PoCs. No production behavior was added. A pass supports only these concrete fixtures on the recorded JDK. It cannot promote JavaParser, establish universal accuracy, repair historical comparator evidence or pass the M2/M3 gate.

The coordinator's integrated 2026-09-02 run passed all 34 occurrence labels and 3 identifier relations. [Archived summary](results/pilot-2026-09-02/summary.json) and [byte-copy receipt](results/pilot-2026-09-02/archive.json) identify the untouched raw stdout, manifests and commands. Thirty targets were adjudicated; four error-case labels checked withholding only. This does not add a full semantic-accuracy claim.
