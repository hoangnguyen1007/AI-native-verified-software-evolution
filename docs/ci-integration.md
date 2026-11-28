# CI/CD Integration Guide (Online Evo Web App API)

Integrate **Evo Architecture Intelligence** into **GitHub Actions** and **GitLab CI** using the **Online Evo Web App HTTP API** (`evo-workbench-online:latest`) with **host-safe passive analysis (`--build=off` semantics)**.

Because Evo passively acquires source trees, Maven POM/BOM metadata, Gradle descriptors, Java 21 AST, Spring conditional SAT models, and 16 polyglot languages without executing untrusted target builds, CI jobs never run target Maven/Gradle plugins, npm lifecycle scripts, or compiler hooks on the runner.

---

## 1. Host-Safe Architecture & Projection Profiles

- **Zero Host Build Execution (`--build=off` / Online Mode):** Running `evo-workbench-online:latest` (`--online`, `EVO_ONLINE_MODE=true`) enforces passive acquisition and rejects local filesystem checkouts (`403 LOCAL_CHECKOUT_DISABLED_IN_ONLINE_MODE`). Target source is acquired read-only via HTTPS Git and hashed into content-addressed storage (SHA-256).
- **Epistemic Projection Profiles (`projectionProfile`):**
  - `"strict"` *(recommended for blocking CI gates)* — **Strict Canonical (Direct Evidence)** emits only directly resolved canonical edges and preserves unresolved obligations explicitly.
  - `"dfd-export"` *(optional for architecture/DFD visualization jobs)* — **DFD Compatibility (Derived Flows)** retains canonical truth while adding derived flow projections for DFD exports.
- **Closed Reporting Denominator:** Missing dependencies, ambiguous targets, and passive coverage gaps are reported explicitly in `result.gapCount` and `GET /api/v1/analysis/jobs/{id}/coverage` rather than silently dropped.

---

## 2. Online Evo API Workflow Reference

All JSON API responses follow the envelope `{ "apiVersion": "...", "data": { ... } }` (errors return `{ "error": { "code": "...", "message": "..." } }`). When `EVO_MULTI_USER=true`, pass `-H "Authorization: Bearer ${EVO_API_TOKEN}"` with an `api:write` PAT (for submitting connections/jobs) or `api:read` PAT (for read-only querying).

| Step | Method & Endpoint | Request / Purpose |
|---|---|---|
| 1. Health check | `GET /api/v1/health` | Confirm `data.status == "ok"` and `data.onlineMode == true`. |
| 2. Connect repository | `POST /api/v1/analysis/connections` | `{"source": "https://github.com/org/repo", "policy": "<optional architecture-policy:v1 YAML>"}` → returns `data.setup`. |
| 3. Submit analysis job | `POST /api/v1/analysis/jobs` | `{"setup": "<setup>", "idempotencyKey": "<uuid>", "mode": "FULL" \| "FAST_ARCHITECTURE", "projectionProfile": "strict" \| "dfd-export", "ref": "<branch-or-sha>"}` → returns `data.id`. |
| 4. Poll job status | `GET /api/v1/analysis/jobs/{jobId}` | Poll until `data.state` is terminal (`SUCCEEDED`, `PARTIAL`, `EMPTY`, `FAILED`, `CANCELLED`, `TIMED_OUT`). Read `data.result.graph`, `data.result.contexts`, `data.result.reports`, `data.result.gapCount`. |
| 5. Architecture Health Score v2 | `POST /api/v1/graphs/{graph}/architecture-score` | `{"context": "<context-or-snapshot>"}` → returns 7-dimension `score`, `healthBand`, and independent `confidence`. |
| 6. Architecture Insights | `POST /api/v1/graphs/{graph}/architecture-insights` | `{"request": {"context": "<ctx>", "scope": "TYPE", "projection": "BOUNDED_UNION", "direction": "OUTGOING", "relationshipKinds": ["graph.depends-on"]}, "arguments": {"topN": 20}}` → cycles, hotspots, SDP, Leiden communities. |
| 7. Policy Violations | `POST /api/v1/graphs/{graph}/reports/{report}/findings` | `{"arguments": {}, "page": {"size": 50}}` → itemized `MUST`/`MAY`/`UNKNOWN` violations with evidence bundles. |
| 8. Snapshot Diff (Evolution) | `POST /api/v1/evolution/jobs` → `GET /api/v1/evolution/results/{id}` | Submit `architecture.evolution.request:v1` comparing `left` (base graph) vs `right` (head graph) to inspect added/removed violations and dependency drift. |
| 9. Export SARIF 2.1.0 | `GET /api/v1/graphs/{graph}/reports/{report}/sarif?download=1` | Returns `application/sarif+json` with `X-Evo-Sarif-Digest` header for GitHub Code Scanning or GitLab security artifacts. |

> **Using the CLI directly in air-gapped runners?** If you run the standalone JAR instead of the Online Web App API, always pass `--build=off` so no target build lifecycle executes on the CI host (for example: `--mode=fast --spring-sat --build=off`). For Online Web App deployments, `--online` guarantees host-safe passive analysis automatically.

---

## 3. GitHub Actions Turnkey Workflow

Save as `.github/workflows/evo-online-architecture-gate.yml`.

This workflow launches `evo-workbench-online:latest` as an isolated service container (or set `EVO_SERVER_URL` + `EVO_API_TOKEN` repository secrets to call your team's persistent Online Evo server) and runs the full **Connect → Analyze (`strict`) → Poll → Score / Violations / Diff / SARIF → Code Scanning Upload** pipeline without executing any target build on the runner.

```yaml
name: Evo Online Architecture Gate

on:
  push:
    branches: [ "main" ]
  pull_request:
    branches: [ "main" ]

permissions:
  contents: read
  security-events: write

concurrency:
  group: evo-online-${{ github.ref }}
  cancel-in-progress: true

jobs:
  evo-architecture-gate:
    name: Passive Architecture Analysis & SARIF Gate
    runs-on: ubuntu-latest

    # Remove this `services:` block if targeting a persistent remote EVO_SERVER_URL.
    services:
      evo-web:
        image: evo-workbench-online:latest
        ports:
          - 8080:8080
        env:
          EVO_ONLINE_MODE: "true"
          EVO_BIND_HOST: "0.0.0.0"
          EVO_PORT: "8080"
          EVO_GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
        options: >-
          --health-cmd "curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 12

    env:
      EVO_URL: ${{ vars.EVO_SERVER_URL || 'http://127.0.0.1:8080' }}
      EVO_API_TOKEN: ${{ secrets.EVO_API_TOKEN }}
      EVO_PROJECTION_PROFILE: "strict"
      EVO_ANALYSIS_MODE: "FULL"
      EVO_MIN_SCORE: "70"
      EVO_FAIL_ON_MUST_ERRORS: "true"

    steps:
      - name: Run Online Evo Analysis, Query Score/Violations/Diff & Export SARIF
        id: evo
        shell: bash
        run: |
          set -euo pipefail
          mkdir -p .evo-ci

          AUTH_HEADER=()
          if [ -n "${EVO_API_TOKEN:-}" ]; then
            AUTH_HEADER=(-H "Authorization: Bearer ${EVO_API_TOKEN}")
          fi

          # 1. Wait for Online Evo health
          for i in $(seq 1 30); do
            if curl -fsS "${EVO_URL}/api/v1/health" | jq -e '.data.status == "ok"' >/dev/null 2>&1; then
              break
            fi
            sleep 2
          done

          # 2. Connect repository (passive online acquisition; zero host build execution)
          REPO_URL="${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}"
          POLICY_YAML=$(cat <<'EOF'
          schema: architecture-policy:v1
          id: ci.architecture.gate
          version: "1"
          rules:
            - id: project.no-cycles
              version: "1"
              kind: ACYCLIC
              severity: ERROR
              scope: TYPE
              relationships: [graph.depends-on]
              within: {origins: [PROJECT]}
          EOF
          )
          CONNECT_PAYLOAD=$(jq -n --arg src "$REPO_URL" --arg pol "$POLICY_YAML" '{source: $src, policy: $pol}')
          SETUP_ID=$(curl -fsS -X POST "${EVO_URL}/api/v1/analysis/connections" \
            -H 'Content-Type: application/json' -H 'Accept: application/json' \
            "${AUTH_HEADER[@]}" \
            -d "$CONNECT_PAYLOAD" | jq -r '.data.setup')
          echo "Connected setup: ${SETUP_ID}"

          # 3. Submit analysis job with explicit epistemic projection profile
          TARGET_REF="${GITHUB_HEAD_REF:-${GITHUB_REF_NAME}}"
          IDEM_KEY=$(cat /proc/sys/kernel/random/uuid)
          JOB_PAYLOAD=$(jq -n \
            --arg setup "$SETUP_ID" \
            --arg key "$IDEM_KEY" \
            --arg mode "$EVO_ANALYSIS_MODE" \
            --arg profile "$EVO_PROJECTION_PROFILE" \
            --arg ref "$TARGET_REF" \
            '{setup: $setup, idempotencyKey: $key, mode: $mode, projectionProfile: $profile, ref: $ref}')
          JOB_ID=$(curl -fsS -X POST "${EVO_URL}/api/v1/analysis/jobs" \
            -H 'Content-Type: application/json' -H 'Accept: application/json' \
            "${AUTH_HEADER[@]}" \
            -d "$JOB_PAYLOAD" | jq -r '.data.id')
          echo "Submitted job: ${JOB_ID} (profile=${EVO_PROJECTION_PROFILE})"

          # 4. Poll until terminal state
          STATE=""
          for i in $(seq 1 180); do
            JOB_JSON=$(curl -fsS "${EVO_URL}/api/v1/analysis/jobs/${JOB_ID}" \
              -H 'Accept: application/json' "${AUTH_HEADER[@]}")
            STATE=$(echo "$JOB_JSON" | jq -r '.data.state')
            STAGE=$(echo "$JOB_JSON" | jq -r '.data.stage')
            echo "[poll ${i}] state=${STATE} stage=${STAGE}"
            case "$STATE" in
              SUCCEEDED|PARTIAL|EMPTY) break ;;
              FAILED|CANCELLED|TIMED_OUT)
                echo "::error::Evo analysis job ended in state=${STATE}"
                echo "$JOB_JSON" | jq '.data'
                exit 1 ;;
            esac
            sleep 3
          done
          echo "$JOB_JSON" > .evo-ci/job.json

          GRAPH_ID=$(echo "$JOB_JSON" | jq -r '.data.result.graph')
          CONTEXT_ID=$(echo "$JOB_JSON" | jq -r '.data.result.contexts[] | select(startswith("analysis:"))' | head -n1)
          CONTEXT_ID="${CONTEXT_ID:-$(echo "$JOB_JSON" | jq -r '.data.result.snapshot')}"
          REPORT_ID=$(echo "$JOB_JSON" | jq -r '.data.result.reports[0] // empty')
          GAP_COUNT=$(echo "$JOB_JSON" | jq -r '.data.result.gapCount // 0')
          echo "Graph=${GRAPH_ID} Context=${CONTEXT_ID} Report=${REPORT_ID:-none} Gaps=${GAP_COUNT}"

          # 5. Query Architecture Health Score v2
          curl -fsS -X POST "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/architecture-score" \
            -H 'Content-Type: application/json' -H 'Accept: application/json' \
            "${AUTH_HEADER[@]}" \
            -d "$(jq -n --arg ctx "$CONTEXT_ID" '{context: $ctx}')" \
            > .evo-ci/score-v2.json
          SCORE=$(jq -r '.data.score // 0' .evo-ci/score-v2.json)
          BAND=$(jq -r '.data.healthBand // "UNKNOWN"' .evo-ci/score-v2.json)
          echo "Architecture Health Score v2: ${SCORE} (${BAND})"

          # 6. Query Policy Violations & Export SARIF 2.1.0
          MUST_ERRORS=0
          if [ -n "$REPORT_ID" ]; then
            curl -fsS -X POST "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/reports/${REPORT_ID}/findings" \
              -H 'Content-Type: application/json' -H 'Accept: application/json' \
              "${AUTH_HEADER[@]}" \
              -d '{"arguments":{},"page":{"size":50}}' \
              > .evo-ci/findings.json
            MUST_ERRORS=$(jq '[.data.items[]? | select(.result.classification=="MUST" and .result.rule.header.severity=="ERROR")] | length' .evo-ci/findings.json)

            curl -fsS "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/reports/${REPORT_ID}/sarif?download=1" \
              "${AUTH_HEADER[@]}" \
              -o .evo-ci/evo-architecture.sarif
            echo "Exported SARIF (.evo-ci/evo-architecture.sarif), MUST ERROR findings: ${MUST_ERRORS}"
          fi

          # 7. Optional Snapshot Diff against previous analyzed graph in history
          PREV_GRAPH=$(curl -fsS -X POST "${EVO_URL}/api/v1/analysis/jobs/history" \
            -H 'Content-Type: application/json' -H 'Accept: application/json' \
            "${AUTH_HEADER[@]}" \
            -d '{"size":10}' | jq -r --arg cur "$GRAPH_ID" --arg setup "$SETUP_ID" \
            '.data.items[] | select(.setup==$setup and .result.graph != null and .result.graph != $cur) | .result.graph' | head -n1)
          if [ -n "$PREV_GRAPH" ]; then
            EVO_DIFF_REQ=$(jq -n --arg left "$PREV_GRAPH" --arg right "$GRAPH_ID" '{
              request: {
                schema: "architecture.evolution.request:v1",
                comparison: {
                  left: $left,
                  right: $right,
                  selection: {
                    sourceSets: ["MAIN"],
                    nodeKinds: ["TYPE"],
                    relationshipKinds: ["graph.depends-on"],
                    findings: false,
                    evidence: false
                  }
                }
              }
            }')
            curl -fsS -X POST "${EVO_URL}/api/v1/evolution/jobs" \
              -H 'Content-Type: application/json' -H 'Accept: application/json' \
              "${AUTH_HEADER[@]}" \
              -d "$EVO_DIFF_REQ" > .evo-ci/evolution-job.json || true
          fi

          # 8. Quality Gate Enforcement
          if [ "$EVO_FAIL_ON_MUST_ERRORS" = "true" ] && [ "$MUST_ERRORS" -gt 0 ]; then
            echo "::error::Quality gate failed: ${MUST_ERRORS} MUST ERROR architecture violation(s) found."
            exit 1
          fi
          awk -v s="$SCORE" -v m="$EVO_MIN_SCORE" 'BEGIN { exit (s + 0 >= m + 0) ? 0 : 1 }' \
            || { echo "::error::Quality gate failed: Score v2 (${SCORE}) < threshold (${EVO_MIN_SCORE})."; exit 1; }

      - name: Upload SARIF to GitHub Code Scanning
        if: always() && hashFiles('.evo-ci/evo-architecture.sarif') != ''
        uses: github/codeql-action/upload-sarif@v3
        with:
          sarif_file: .evo-ci/evo-architecture.sarif
          category: evo-architecture-intelligence

      - name: Archive Evo Evidence Artifacts
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: evo-ci-evidence
          path: .evo-ci/
```

---

## 4. GitLab CI Turnkey Pipeline

Add to `.gitlab-ci.yml`:

```yaml
stages:
  - architecture

evo_online_architecture_gate:
  stage: architecture
  image: alpine:3.20
  # Remove `services:` when calling a shared remote $EVO_SERVER_URL
  services:
    - name: evo-workbench-online:latest
      alias: evo-web
  variables:
    EVO_ONLINE_MODE: "true"
    EVO_BIND_HOST: "0.0.0.0"
    EVO_PORT: "8080"
    EVO_URL: "http://evo-web:8080"
    EVO_PROJECTION_PROFILE: "strict"
    EVO_ANALYSIS_MODE: "FULL"
    EVO_MIN_SCORE: "70"
  before_script:
    - apk add --no-cache curl jq util-linux
  script:
    - mkdir -p .evo-ci
    - |
      set -euo pipefail
      AUTH_OPT=""
      if [ -n "${EVO_API_TOKEN:-}" ]; then
        AUTH_OPT="Authorization: Bearer ${EVO_API_TOKEN}"
      fi
      api_curl() {
        if [ -n "$AUTH_OPT" ]; then
          curl -fsS -H "$AUTH_OPT" "$@"
        else
          curl -fsS "$@"
        fi
      }

      # 1. Wait for Online Evo health
      for i in $(seq 1 30); do
        if curl -fsS "${EVO_URL}/api/v1/health" | jq -e '.data.status == "ok"' >/dev/null 2>&1; then
          break
        fi
        sleep 2
      done

      # 2. Connect repository (passive online acquisition; zero target build execution)
      CONNECT_BODY=$(jq -n --arg src "$CI_PROJECT_URL" '{source: $src}')
      SETUP_ID=$(api_curl -X POST "${EVO_URL}/api/v1/analysis/connections" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d "$CONNECT_BODY" | jq -r '.data.setup')

      # 3. Submit analysis job (strict canonical profile)
      JOB_BODY=$(jq -n \
        --arg setup "$SETUP_ID" \
        --arg key "$(uuidgen)" \
        --arg mode "$EVO_ANALYSIS_MODE" \
        --arg profile "$EVO_PROJECTION_PROFILE" \
        --arg ref "$CI_COMMIT_SHA" \
        '{setup: $setup, idempotencyKey: $key, mode: $mode, projectionProfile: $profile, ref: $ref}')
      JOB_ID=$(api_curl -X POST "${EVO_URL}/api/v1/analysis/jobs" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d "$JOB_BODY" | jq -r '.data.id')

      # 4. Poll until terminal
      for i in $(seq 1 180); do
        JOB_JSON=$(api_curl -H 'Accept: application/json' "${EVO_URL}/api/v1/analysis/jobs/${JOB_ID}")
        STATE=$(echo "$JOB_JSON" | jq -r '.data.state')
        case "$STATE" in
          SUCCEEDED|PARTIAL|EMPTY) break ;;
          FAILED|CANCELLED|TIMED_OUT) echo "Job failed: $STATE"; exit 1 ;;
        esac
        sleep 3
      done
      echo "$JOB_JSON" > .evo-ci/job.json

      GRAPH_ID=$(echo "$JOB_JSON" | jq -r '.data.result.graph')
      CONTEXT_ID=$(echo "$JOB_JSON" | jq -r '.data.result.contexts[] | select(startswith("analysis:"))' | head -n1)
      CONTEXT_ID="${CONTEXT_ID:-$(echo "$JOB_JSON" | jq -r '.data.result.snapshot')}"
      REPORT_ID=$(echo "$JOB_JSON" | jq -r '.data.result.reports[0] // empty')

      # 5. Query Score v2, Insights, Violations, and SARIF
      api_curl -X POST "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/architecture-score" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d "$(jq -n --arg ctx "$CONTEXT_ID" '{context: $ctx}')" > .evo-ci/score-v2.json

      api_curl -X POST "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/architecture-insights" \
        -H 'Content-Type: application/json' -H 'Accept: application/json' \
        -d "$(jq -n --arg ctx "$CONTEXT_ID" '{request:{context:$ctx,scope:"TYPE",projection:"BOUNDED_UNION",direction:"OUTGOING",relationshipKinds:["graph.depends-on"]},arguments:{topN:20}}')" \
        > .evo-ci/insights.json

      if [ -n "$REPORT_ID" ]; then
        api_curl -X POST "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/reports/${REPORT_ID}/findings" \
          -H 'Content-Type: application/json' -H 'Accept: application/json' \
          -d '{"arguments":{},"page":{"size":50}}' > .evo-ci/findings.json
        api_curl "${EVO_URL}/api/v1/graphs/${GRAPH_ID}/reports/${REPORT_ID}/sarif?download=1" \
          -o .evo-ci/evo-architecture.sarif
      fi

      SCORE=$(jq -r '.data.score // 0' .evo-ci/score-v2.json)
      echo "Score v2: ${SCORE} (threshold: ${EVO_MIN_SCORE})"
      awk -v s="$SCORE" -v m="$EVO_MIN_SCORE" 'BEGIN { exit (s + 0 >= m + 0) ? 0 : 1 }'
  artifacts:
    when: always
    expire_in: 14 days
    paths:
      - .evo-ci/
```

---

## 5. CI Security & Operational Best Practices

1. **Least-Privilege PATs:** Mint a dedicated `api:write` token for trusted branch CI runners and `api:read` (or `mcp:read`) tokens for read-only reporting or AI review bots. See [MCP Ecosystem Quickstart](mcp-quickstart.md#5-security-posture-pat-scopes--origin-protection) for exact scope boundaries.
2. **Untrusted Fork PRs:** Never expose `EVO_API_TOKEN` (`api:write`) to untrusted fork pull requests; run fork checks against an ephemeral `evo-workbench-online:latest` service container with read-only `GITHUB_TOKEN`.
3. **Deterministic Content-Addressed Replay:** Store `.evo-ci/job.json` and `result.graph` (`sha256:...`) as CI artifacts so subsequent PR comparisons can pass the exact base graph digest to `POST /api/v1/evolution/jobs`.
4. **Honest Partial Outcomes:** A `PARTIAL` job state indicates passive analysis succeeded while one or more explicit capability gaps remain (`result.gapCount > 0`). Inspect `GET /api/v1/analysis/jobs/{jobId}/coverage` rather than ignoring missing dependency or dynamic-config obligations.
