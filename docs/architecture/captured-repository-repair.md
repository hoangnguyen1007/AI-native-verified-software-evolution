# Repair proposals for analyzed repositories

The backend can propose a repair from the captured sources of a published finding. This path has no repository, snapshot, or rule allowlist. The first compiler adapter supports Java source files; a finding without editable captured bytes or a supported compiler gets an explicit outcome. The original [fixture proposal](demo-repair-proposal.md) and its isolated verification replay remain available for historical reproduction.

## Request a proposal

Use the local Workbench API. Let the report prefix be `/api/v1/graphs/{graph}/reports/{report}`. First call `POST {prefix}/diagnosis` with JSON `{"arguments":{"identity":"<finding digest>","bundle":"<bundle digest>","context":"<context identity>"}}`. Proceed when diagnosis returns `data.status: CITATIONS_VALIDATED`. Add its exact `data.input` as `diagnosisInput` to the same arguments for both operations below.

1. `POST {prefix}/repair-options` lists registered recipes, the cited files and any source eligibility gaps. If an AI provider is configured, `data.ai` lists the destination, model, exact input digest and files whose captured contents or excerpts would be sent. This operation does not contact the model.
2. `POST {prefix}/repair-proposal` with one of these choices appended to `arguments`:

   | Choice | Additional arguments |
   |---|---|
   | Built-in recipe | `"strategy":"RECIPE", "recipe":"java.remove-unused-private-field"` |
   | Model suggestion | `"strategy":"AI", "modelInput":"<data.ai.input from repair-options>"` |

A deterministic recipe must match a cited declaration exactly enough to identify one edit:

| Recipe | Preconditions |
|---|---|
| `java.remove-unused-private-field` | One unannotated private field without an initializer or references in the compilation unit. Multiple declarations in one statement are excluded. |
| `java.remove-empty-constructor-parameter` | One unannotated parameter in the class's only constructor, whose body is empty. Review callers, dependency injection and reflective construction before using the patch. |
| `java.remove-unused-import` | One explicitly named import whose name is not mentioned elsewhere in the compilation unit. Wildcards are excluded. |

An inapplicable or ambiguous recipe produces `WITHHELD`; a successful recipe remains a candidate. The live model can propose multiple changes in the offered files, including changes for which there is no built-in recipe.

## Configure the repair model

At Workbench startup use the JVM properties below or set `EVO_REPAIR_AI_ENDPOINT`, `EVO_REPAIR_AI_MODEL` and `EVO_REPAIR_AI_ALLOW_REMOTE` as shown in the [environment template](../../.env.example). Explicit JVM properties take precedence. These settings are independent of the chat and demo citation models. The [Workbench setup guide](workbench-architecture-chat.md#configure-a-model) shows how to import the environment.

| Setting | Purpose |
|---|---|
| `-Devo.repair.ai.endpoint=http://127.0.0.1:8081/v1` | Chat Completions base URL or full endpoint. |
| `-Devo.repair.ai.model=<model-id>` | Configured model ID; the completion must report the same ID. |
| `EVO_REPAIR_AI_API_KEY` | Optional environment variable for a bearer token. Never supply it in a request or put it in the endpoint URL. |
| `-Devo.repair.ai.allowRemote=true` | Additional operator opt-in for an HTTPS destination outside literal loopback. |

The request digest binds the exact provider payload and configured recipient to the report and diagnosis pins. Workbench shows the files before approval. The provider receives captured source or excerpts, the finding's scope and relationship, and relevant spans. The operator configures the destination and credentials at startup. The client disables redirects and proxies and treats repository text as input data.

The versioned prompt asks for minimal edits as `{file,before,oldText,newText}` records with optional `startLine` for a repeated anchor. A single Markdown JSON fence is accepted. Each edit resolves to exact offsets in the offered original file. Whole-line anchors can differ in indentation and line endings when they identify one location. Text blocks and Unicode escapes require exact anchors when indentation could affect interpretation. The backend validates file number, before digest, offered range and nonoverlap, and requires at least one edit to overlap cited evidence. Clients submit only proposal selection and approval pins. A provider may still fail to identify a correct repair.

## Use the result

The backend uses the [JDK compiler parse phase](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html#parse()) on each changed file before rendering a Git patch. It checks the matching manifest's captured Java syntax level when available; otherwise the receipt records the backend JDK default. The compiler parses in-memory text without annotation processors, target plugins, classpath attribution, bytecode generation or repository execution. Failed syntax returns diagnostics by code, line and column and no patch.

`data.proposal.candidate` contains the patch and SHA-256, per-file before and after digests, source evidence, finding pins and syntax receipts. AI candidates also carry the prompt, input and response digests; repeated live calls may produce different candidates. Fetch and compare the exact **before** hashes from your checkout, then run `git apply --check` for the [Git application check](https://git-scm.com/docs/git-apply). The check alone cannot detect all unrelated checkout drift. The backend does not change the checkout.

`PROPOSED / UNVERIFIED` means the changed files passed the syntax gate only. Build/type checks, tests, behavior, and removal of the original finding require isolated verification and reanalysis. The existing `repair-verification` route covers the sealed fixture; generic candidate verification is not wired to it. Workbench's existing fixture panel remains unchanged; callers can use the new choices through the backend API.

The edit boundary admits existing Java files up to 1 MiB in UTF-8 or ASCII without BOM from the validated finding's evidence. Generated, missing, incompatible or oversized files retain typed source decisions and capability gaps. The model request defaults to 1 MiB of serialized JSON. Increase or decrease the budget in [provider settings](workbench-architecture-chat.md#configure-a-model). When cited full files exceed the budget, the offer selects excerpts around cited lines and can omit files that cannot fit. Edits stay inside the source actually offered. Each stored proposal fits the existing 2 MiB record budget. These limits apply to the proposal workflow independently of repository acquisition.

## Verification

The focused selection ran six tests with no failures, errors or skips:

~~~powershell
.\mvnw.cmd -o -q -pl backend '-Denforcer.skipRules=reactorModuleConvergence' '-Dplatform.build.directory=target/demo-diagnosis' '-Dtest=LocalAnalysisRunnerTest#diagnosisRoundTripsToCapturedSourceAndRejectsAnotherBundle,CapturedRepairTest,ApiSpecificationTest,DemoRepairCaptureTest' test
~~~

The packaged Workbench analyzed a separate authored Java repository and served an applicable recipe patch. Git reproduced the expected bytes on a copy. A controlled HTTP endpoint supplied one valid and two invalid model replies; the backend withheld both invalid candidates. The pinned local Qwen 2.5 0.5B model returned [NO_EDIT](../current-state.md) on the same authored repository. The pinned historical HTTP and CLI journey retained its original patch digest and verified verdict using the existing isolated capture.
