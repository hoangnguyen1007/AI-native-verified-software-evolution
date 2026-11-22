# Workbench repository scoring

**CONFIRMED human direction, 2026-11-21.** Workbench assigns a default score without requiring a policy and lets users define repository-specific rules.

## Repository score

`workbench.repository-score:v1` evaluates file presence in the immutable inventory sealed for the analysis job. It can display a result before semantic analysis finishes. The job route uses the inventory reader directly. The graph route can replay projected inventory evidence and retains qualified positive evidence from older graphs with a limited capture.

| Check | Weight | Evidence |
|---|---:|---|
| Documentation | 25 | Root README |
| Tests | 30 | A file matching a recognized test naming or folder convention |
| Automation | 20 | A recognized CI configuration |
| Build or package setup | 25 | A recognized build or package manifest |

The score is 100 times the observed weight divided by the applicable weight, rounded to the nearest integer. An inventory with a root README and only recognized documentation and repository metadata excludes unconfigured test and build checks. Applicable weights stay in the denominator when evidence is missing. An incomplete inventory returns lower and upper bounds, with no point score. Git administration, generated, vendored and cached paths are outside the default rubric. Bundled assistant tools require an explicit path override to contribute. Each observed check identifies its captured path and evidence digest.

Users can replace the matching convention for any check with one exact repository-relative path. Workbench keeps those preferences in browser storage per repository connection. Both score routes support `GET` for the defaults and `POST` with a `paths` object. The result includes the applied paths for replay. For example, `{ "paths": { "tests": "qa/smoke.sh" } }` uses `qa/smoke.sh` as the sole test evidence. File presence does not measure test success, coverage, build repeatability or architecture health. The weights are a provisional product rubric.

## Architecture assessment

**Score & metrics** shows the M6 assessment for a selected Java or Spring context. When the context lacks a published policy assessment, the Workbench evaluates a default dependency cycle rule for that context. Supported contexts use project type dependencies or Spring selected bindings. Users can replace the rule with typed YAML and evaluate it against the saved graph without rereading or executing the repository. Compilation diagnostics retain their original locations, and successful compilation publishes an immutable report for the findings and assessment views. Custom policies are saved by repository connection and context family in the browser.

`POST /api/v1/graphs/{graph}/policy-assessment` accepts `context` and optional `policy`. The [M6 compiler and assessment](m6-policy-metrics-and-assessment.md) own the architecture score, confidence and typed gaps. An incomplete architecture projection stays withheld where required by M6. The repository score is separate from M6 and does not enter snapshot-evolution health comparisons.
