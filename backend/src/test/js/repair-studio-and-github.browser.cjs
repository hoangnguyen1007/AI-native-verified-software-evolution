'use strict';

/**
 * Phase-2 Comprehensive Browser E2E Verification & Visual Screenshot Suite:
 * 1. Smart GitHub Intake (PR URL auto-detect, Auth Status Pill, RAM-only Session PAT dialog,
 *    Live Branch/Tag/PR selector, 1-click Compare PR Base vs Head + Review Comment confirmation).
 * 2. 4-Stage Sticky Pipeline Stepper + Partial-Gap Action Strip (closed denominator truthfulness).
 * 3. 5-Step Guided Repair & Sandbox Verification Studio inside Findings/Review:
 *    - Step 1: Evidence-grounded Diagnosis with resolved class names & clickable source citations
 *    - Step 2: Multi-file (CREATE/MODIFY/DELETE) Unified & Side-by-Side Diff viewer
 *    - Step 3: Async Isolated Sandbox Verification with live SSE progress
 *    - Step 4: Verification & Impact Report Card (Verdict, Trust Label, Build/Tests, Score v2 & D1-D7 deltas,
 *              Violations delta, Capability Gaps delta, stdout/stderr summary)
 *    - Step 5: Explicit Confirmation Gate:
 *              - REJECTED hard-block verification
 *              - Option A: Apply to New Git Branch (Worktree)
 *              - Option B: Apply Directly to Local Source + Rollback
 *              - Option C: Push & Open Verified GitHub PR (Prepare -> Edit -> Separate Remote Confirm -> Publish)
 * 4. AI Architecture Chat polish: Provider/model visibility, live /ai/providers/probe, contextual quick chips,
 *    clickable [E#] jumping directly to captured source modal.
 * 5. Zero console/page errors, responsive overflow audit (1440x900, 390x844, 320x844),
 *    automated WCAG AA >= 4.5:1 computed contrast checks, and Dark & Light theme screenshots.
 */

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {mkdir, readFile, writeFile} = require('node:fs/promises');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

async function capturePng(page, filePath) {
  const {data} = await page.send('Page.captureScreenshot', {format: 'png'});
  await writeFile(filePath, Buffer.from(data, 'base64'));
}

async function auditContrastAndOverflow(page, label) {
  return page.evaluate(`(() => {
    const parseRgb = str => {
      const m = String(str || '').match(/rgba?\\((\\d+(?:\\.\\d+)?),\\s*(\\d+(?:\\.\\d+)?),\\s*(\\d+(?:\\.\\d+)?)(?:,\\s*(\\d+(?:\\.\\d+)?))?\\)/);
      if (!m) return null;
      return { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]), a: m[4] == null ? 1 : Number(m[4]) };
    };
    const lum = c => {
      const f = v => { const s = v / 255; return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4); };
      return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
    };
    const blend = (fg, bg) => ({
      r: fg.r * fg.a + bg.r * (1 - fg.a),
      g: fg.g * fg.a + bg.g * (1 - fg.a),
      b: fg.b * fg.a + bg.b * (1 - fg.a),
      a: 1
    });
    const ratio = (c1, c2) => {
      const l1 = lum(c1), l2 = lum(c2);
      return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    };
    const effectiveBg = el => {
      let cur = el;
      const layers = [];
      while (cur && cur.nodeType === 1) {
        const bg = parseRgb(getComputedStyle(cur).backgroundColor);
        if (bg && bg.a > 0) layers.push(bg);
        cur = cur.parentElement;
      }
      const rootBg = parseRgb(getComputedStyle(document.documentElement).backgroundColor) || { r: 10, g: 14, b: 13, a: 1 };
      let acc = { r: rootBg.r, g: rootBg.g, b: rootBg.b, a: 1 };
      for (let i = layers.length - 1; i >= 0; i--) acc = blend(layers[i], acc);
      return acc;
    };
    const failures = [];
    const selectors = [
      '#repository-title', '#repository-description', '.gh-repo-pill', '.gh-auth-label',
      '.gh-stage-step', '.gh-gap-strip-copy strong', '.rs-studio-title', '.rs-verdict-headline',
      '.rs-kpi-value', '.rs-kpi-label', '.rs-mode-card strong', '.architecture-chat-model-state',
      '.rs-step-btn.is-current', '.badge'
    ];
    for (const sel of selectors) {
      for (const el of document.querySelectorAll(sel)) {
        const rect = el.getBoundingClientRect();
        if (!rect.width || !rect.height || getComputedStyle(el).visibility === 'hidden') continue;
        const cs = getComputedStyle(el);
        const fg = parseRgb(cs.color);
        if (!fg || fg.a === 0) continue;
        const bg = effectiveBg(el);
        const cr = ratio(fg.a < 1 ? blend(fg, bg) : fg, bg);
        if (cr < 4.5) {
          failures.push({ sel, text: el.textContent.trim().slice(0, 32), ratio: Number(cr.toFixed(2)), fg: cs.color, bg });
        }
      }
    }
    return {
      label: ${JSON.stringify(label)},
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      overflow: document.documentElement.scrollWidth > window.innerWidth + 1,
      contrastFailures: failures
    };
  })()`);
}

async function main([browser, output, screenshotDir]) {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const mime = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'};
  const sockets = [];
  const repairSseClients = [];

  const date = new Date(0).toISOString();
  const graph = 'sha256:' + 'a'.repeat(64);
  const snapshot = 'snapshot:sha256:' + 'b'.repeat(64);
  const reportId = 'sha256:' + 'c'.repeat(64);
  const findingId = 'sha256:' + 'd'.repeat(64);
  const bundleId = 'sha256:' + 'e'.repeat(64);
  const proposalId = 'sha256:' + 'f'.repeat(64);
  const entityA = 'sha256:' + '1'.repeat(64);
  const entityB = 'sha256:' + '2'.repeat(64);

  let ghAuth = {authenticated: false, source: 'NONE', rateLimitRemaining: 60, rateLimitLimit: 60};
  let nextVerifyVerdict = 'VERIFIED';
  const recordedApiCalls = [];

  const multiFilePatch = [
    'diff --git a/src/main/java/com/acme/OrderGateway.java b/src/main/java/com/acme/OrderGateway.java',
    'new file mode 100644',
    '--- /dev/null',
    '+++ b/src/main/java/com/acme/OrderGateway.java',
    '@@ -0,0 +1,4 @@',
    '+package com.acme;',
    '+public interface OrderGateway {',
    '+  void recordOrder(String id);',
    '+}',
    'diff --git a/src/main/java/com/acme/OrderController.java b/src/main/java/com/acme/OrderController.java',
    '--- a/src/main/java/com/acme/OrderController.java',
    '+++ b/src/main/java/com/acme/OrderController.java',
    '@@ -10,4 +10,4 @@ public class OrderController {',
    '   private final String scope;',
    '-  private final OrderRepository repo;',
    '+  private final OrderGateway gateway;',
    '   public void place() {}',
    '\\ No newline at end of file',
    'diff --git a/src/main/java/com/acme/LegacyBridge.java b/src/main/java/com/acme/LegacyBridge.java',
    'deleted file mode 100644',
    '--- a/src/main/java/com/acme/LegacyBridge.java',
    '+++ /dev/null',
    '@@ -1,2 +0,0 @@',
    '-package com.acme;',
    '-class LegacyBridge {}'
  ].join('\n');

  const job = {
    id: 'job-gh-1', setup: 'acme-shop', state: 'PARTIAL', stage: 'FINISHED', revision: 4, mode: 'FULL',
    submittedAt: date, finishedAt: date, elapsedMillis: 4200,
    events: [{stage: 'QUEUED', at: date}, {stage: 'ACQUISITION', at: date}, {stage: 'ANALYSIS', at: date}, {stage: 'FINISHED', at: date}],
    result: {graph, snapshot, contexts: [snapshot, 'analysis:main'], reports: [reportId], completion: 'PARTIAL', gapCount: 3}
  };

  const finding = {
    identity: findingId, bundleIdentity: bundleId,
    result: {
      classification: 'MUST', kind: 'FORBIDDEN_DEPENDENCY', status: 'VIOLATION',
      rule: {id: 'web.no-repository', name: 'Web controllers must not access repositories directly', severity: 'ERROR'},
      bundle: {
        path: [entityA, entityB],
        relationships: ['graph.depends-on'],
        sources: [{span: {document: 'src/main/java/com/acme/OrderController.java', startLine: 11, startColumn: 3, endLine: 11, endColumn: 36}}],
        region: {classification: 'MUST'},
        request: {scope: 'TYPE', context: 'analysis:main', relationshipKinds: ['graph.depends-on']}
      }
    }
  };

  const scoreV2 = {
    graph, context: snapshot, scopeMode: 'WHOLE_REPOSITORY',
    formula: {id: 'architecture.score-formula', version: 'v2.0'},
    status: 'PARTIAL', score: 71.4, conservativeLowerBound: 66.0, uncertaintyDelta: 5.4, healthBand: 'Moderate',
    dimensions: [
      {id: 'd1.acyclic-integrity', displayName: 'Acyclic Integrity', iso25010Mapping: 'Modularity', weight: 20, status: 'COMPLETE', score: 82.0, subMetrics: []},
      {id: 'd7.policy-conformance', displayName: 'Policy Conformance', iso25010Mapping: 'Compliance', weight: 8, status: 'COMPLETE', score: 54.0, subMetrics: []}
    ],
    confidence: {confidenceScore: 88, tier: 'HIGH', evidenceTier: 'BYTECODE_AND_SOURCE', parseCompleteness: 1.0, internalEdgeCompleteness: 0.92, overallSemanticResolution: 0.89, buildCompleteness: 0.95, openGapCount: 3}
  };

  function verifyReportPayload(verdict) {
    const rejected = verdict === 'REJECTED';
    return {
      jobId: 'vjob-1',
      status: rejected ? 'REJECTED' : 'COMPLETED',
      stage: 'COMPLETED',
      verdict,
      trustLabel: rejected ? 'REJECTED_VERIFICATION' : 'VERIFIED_ISOLATED_SANDBOX',
      isolationBackend: 'docker-isolated-jdk21',
      build: {compiled: !rejected, durationMs: 3800},
      tests: {run: 48, failed: rejected ? 2 : 0, errors: 0},
      scoreV2Delta: {
        before: 71.4, after: rejected ? 69.2 : 79.6, delta: rejected ? -2.2 : 8.2,
        beforeBand: 'Moderate', afterBand: rejected ? 'At Risk' : 'Healthy',
        dimensions: [
          {id: 'd1', displayName: 'Acyclic Integrity', before: 82.0, after: 86.0, delta: 4.0},
          {id: 'd4', displayName: 'Hierarchy & Direction', before: 65.0, after: 81.5, delta: 16.5},
          {id: 'd7', displayName: 'Policy Conformance', before: 54.0, after: rejected ? 48.0 : 92.0, delta: rejected ? -6.0 : 38.0}
        ]
      },
      violationsDelta: {resolved: rejected ? 0 : 1, introduced: rejected ? 1 : 0, persisted: 0},
      capabilityGapsDelta: {before: 3, after: 3, delta: 0},
      checks: [
        {id: 'sandbox-build', status: rejected ? 'FAILED' : 'PASSED', reason: rejected ? 'Compilation failed in isolated container' : 'Maven compile & 48 unit tests passed in isolated Docker sandbox'},
        {id: 'candidate-finding', status: rejected ? 'FAILED' : 'PASSED', reason: rejected ? 'Original violation persisted' : 'web.no-repository resolved with 0 regressions'}
      ],
      logTail: '[docker-sandbox] mvn -q test\n[INFO] Tests run: 48, Failures: 0, Errors: 0\n[INFO] BUILD SUCCESS',
      provenance: {baselineGraph: graph, candidateGraph: 'sha256:' + '9'.repeat(64), patchDigest: proposalId},
      markdownReport: '# Repair Verification Report\n\n- Verdict: ' + verdict + '\n- Score v2: 71.4 -> 79.6 (+8.2)\n'
    };
  }

  const server = createServer(async (req, res) => {
    const urlObj = new URL(req.url, 'http://127.0.0.1');
    const route = urlObj.pathname === '/' ? '/index.html' : urlObj.pathname;
    try {
      if (!route.startsWith('/api/')) {
        res.setHeader('Content-Type', mime[path.extname(route)] || 'application/octet-stream');
        res.end(await readFile(path.join(directory, route.slice(1))));
        return;
      }
      let body = '';
      for await (const chunk of req) body += chunk;
      const input = body ? JSON.parse(body) : null;
      recordedApiCalls.push({method: req.method, route, query: urlObj.search, input});

      if (route === '/api/v1/repairs/jobs/vjob-1/events') {
        res.writeHead(200, {'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store'});
        repairSseClients.push(res);
        res.write(`event: stage\ndata: ${JSON.stringify({stage: 'SANDBOX_BUILD', message: 'Compiling & running unit tests in isolated Docker container…', percent: 55})}\n\n`);
        res.write(`event: log\ndata: ${JSON.stringify({line: '[docker-sandbox] mvn -q test — 48/48 passed'})}\n\n`);
        setTimeout(() => {
          if (!res.writableEnded) {
            res.write(`event: completed\ndata: ${JSON.stringify(verifyReportPayload(nextVerifyVerdict))}\n\n`);
            res.end();
          }
        }, 80);
        return;
      }

      let data;
      if (route === '/api/v1') {
        data = {
          analysisJobsEnabled: true, analysisCoverageEnabled: true, repositoryConnectionsEnabled: true,
          architectureScoreV2Enabled: true, architectureChatEnabled: true, architectureAgentEnabled: true,
          githubIntegrationEnabled: true, repairSandboxMode: 'DOCKER_ISOLATED', repairIsolatedRunnerConfigured: true,
          architectureChatProvider: {model: 'qwen2.5-coder:14b', remote: false, endpoint: 'http://127.0.0.1:11434'}
        };
      } else if (route === '/api/v1/analysis/setups') {
        data = {items: [{id: 'acme-shop', repository: 'https://github.com/acme/shop-backend'}]};
      } else if (route === '/api/v1/analysis/connections') {
        data = {items: [{setup: 'acme-shop', label: 'acme/shop-backend', kind: 'GIT_HTTPS', url: 'https://github.com/acme/shop-backend'}]};
      } else if (route === '/api/v1/analysis/jobs/history') {
        data = {items: [job]};
      } else if (route === '/api/v1/analysis/jobs/job-gh-1') {
        data = job;
      } else if (route === '/api/v1/analysis/jobs/job-gh-1/coverage') {
        data = {completion: 'PARTIAL', totalGaps: 3, ledgerTotal: 3, counted: 3, obligations: {environment: 2, source: 1},
          topReasons: [{reason: 'MISSING_POM', count: 2}, {reason: 'OPAQUE_CONDITION', count: 1}]};
      } else if (route === '/api/v1/github/auth/status') {
        data = ghAuth;
      } else if (route === '/api/v1/github/auth/session' && req.method === 'POST') {
        assert.ok(input?.token && input.token.length > 5);
        ghAuth = {authenticated: true, source: 'SESSION_PAT', login: 'hoang-dev', rateLimitRemaining: 4990, rateLimitLimit: 5000};
        data = ghAuth;
      } else if (route === '/api/v1/github/auth/session' && req.method === 'DELETE') {
        ghAuth = {authenticated: false, source: 'NONE', rateLimitRemaining: 60, rateLimitLimit: 60};
        data = ghAuth;
      } else if (route === '/api/v1/github/refs') {
        data = {
          defaultBranch: 'main',
          branches: [{name: 'main'}, {name: 'feat/decouple-orders'}],
          tags: [{name: 'v1.4.0'}],
          pulls: [{number: 42, title: 'Decouple OrderController from OrderRepository', headRef: 'feat/decouple-orders', baseRef: 'main', ref: 'pull/42/head'}]
        };
      } else if (route === '/api/v1/github/connections/acme-shop/metadata') {
        data = {fullName: 'acme/shop-backend', defaultBranch: 'main', private: false, primaryLanguage: 'Java'};
      } else if (route === '/api/v1/github/connections/acme-shop/branches') {
        data = {items: [{name: 'main'}, {name: 'feat/decouple-orders'}]};
      } else if (route === '/api/v1/github/connections/acme-shop/tags') {
        data = {items: [{name: 'v1.4.0'}]};
      } else if (route === '/api/v1/github/connections/acme-shop/pulls') {
        data = {items: [{number: 42, title: 'Decouple OrderController from OrderRepository', headRef: 'feat/decouple-orders', baseRef: 'main', ref: 'pull/42/head'}]};
      } else if (route === '/api/v1/github/connections/acme-shop/compare') {
        data = {
          diffId: 'diff-pr-42', status: 'COMPLETED', verdict: 'IMPROVED', trustLabel: 'VERIFIED_SNAPSHOT_COMPARE',
          baseRef: 'main', headRef: 'feat/decouple-orders',
          scoreV2Delta: {before: 71.4, after: 79.6, delta: 8.2, beforeBand: 'Moderate', afterBand: 'Healthy'},
          violationsDelta: {resolved: 1, introduced: 0},
          capabilityGapsDelta: {before: 3, after: 3, delta: 0},
          summary: 'PR #42 removes the direct Web -> Repository violation and raises Health Score v2 by +8.2.'
        };
      } else if (route === '/api/v1/github/connections/acme-shop/pulls/42/checks') {
        data = {items: [{name: 'CI / maven-test', conclusion: 'success'}]};
      } else if (route === '/api/v1/github/connections/acme-shop/pulls/42/review-comment') {
        assert.equal(input.confirmedByUser, true);
        data = {url: 'https://github.com/acme/shop-backend/pull/42#issuecomment-999'};
      } else if (route === '/api/v1/graphs/' + graph) {
        data = {graph, snapshot, contexts: [{identity: snapshot, family: 'repository.snapshot'}, {identity: 'analysis:main', family: 'analysis.manifest', modulePath: 'shop-core', sourceSet: 'MAIN', kind: 'java'}], reports: [reportId]};
      } else if (route === '/api/v1/graphs/' + graph + '/architecture-score') {
        data = scoreV2;
      } else if (route === '/api/v1/graphs/' + graph + '/queries/provenance') {
        data = {items: [{family: 'analysis.manifest', metadata: {modulePath: 'shop-core', sourceSet: 'MAIN'}}]};
      } else if (route === '/api/v1/graphs/' + graph + '/queries/entity') {
        const id = input?.arguments?.identity;
        const qn = id === entityA ? 'com.acme.OrderController' : 'com.acme.OrderRepository';
        data = {items: [{identity: id, kind: 'TYPE', qualifiedName: qn}]};
      } else if (route === '/api/v1/graphs/' + graph + '/reports/' + reportId) {
        data = {identity: reportId, context: 'analysis:main', findingCount: 1};
      } else if (route === '/api/v1/graphs/' + graph + '/reports/' + reportId + '/findings') {
        data = {items: [finding]};
      } else if (route === '/api/v1/graphs/' + graph + '/reports/' + reportId + '/diagnosis') {
        data = {
          status: 'CITATIONS_VALIDATED', mode: 'LIVE', input: 'diag-pin-1',
          explanation: 'OrderController directly injects OrderRepository, violating the 3-tier Web -> Service -> Repository boundary.',
          citations: [{path: 'src/main/java/com/acme/OrderController.java', span: {startLine: 11, startColumn: 3, endLine: 11, endColumn: 36}, context: 'analysis:main', evidence: 'ev-1', family: 'java.field'}]
        };
      } else if (route === '/api/v1/graphs/' + graph + '/reports/' + reportId + '/repair-options') {
        data = {
          recipes: [{id: 'java.extract-gateway-interface', title: 'Extract OrderGateway interface', summary: 'Introduces OrderGateway interface to decouple Web from Repository.'}],
          applicableRecipes: ['java.extract-gateway-interface'],
          ai: {input: 'ai-pin-1', provider: {model: 'qwen2.5-coder:14b', endpoint: 'http://127.0.0.1:11434'}, files: [{path: 'src/main/java/com/acme/OrderController.java'}]}
        };
      } else if (route === '/api/v1/graphs/' + graph + '/reports/' + reportId + '/repair-proposal') {
        data = {
          status: 'PROPOSED', attempts: 2,
          proposal: {
            identity: proposalId,
            candidate: {
              strategy: 'AI',
              edits: [{path: 'src/main/java/com/acme/OrderGateway.java'}, {path: 'src/main/java/com/acme/OrderController.java'}, {path: 'src/main/java/com/acme/LegacyBridge.java'}],
              plan: {summary: 'Extract OrderGateway interface and decouple OrderController.'},
              patch: multiFilePatch
            }
          }
        };
      } else if (route === '/api/v1/repairs/' + encodeURIComponent(proposalId) + '/verify') {
        data = {jobId: 'vjob-1', status: 'RUNNING', eventsUrl: '/api/v1/repairs/jobs/vjob-1/events', reportUrl: '/api/v1/repairs/jobs/vjob-1/report.md'};
      } else if (route === '/api/v1/repairs/jobs/vjob-1') {
        data = verifyReportPayload(nextVerifyVerdict);
      } else if (route === '/api/v1/repairs/' + encodeURIComponent(proposalId) + '/apply/preview') {
        data = {
          beforeHashesValid: true, gitStatus: 'CLEAN', currentBranch: 'main',
          conflicts: [], warnings: [], verdict: nextVerifyVerdict,
          trustLabel: nextVerifyVerdict === 'REJECTED' ? 'REJECTED_VERIFICATION' : 'VERIFIED_ISOLATED_SANDBOX',
          suggestedBranch: 'evo/fix-web-no-repository', approvalToken: 'tok-apply-123',
          expiresAt: new Date(Date.now() + 300_000).toISOString()
        };
      } else if (route === '/api/v1/repairs/' + encodeURIComponent(proposalId) + '/apply/confirm') {
        assert.equal(input.userConfirmed, true);
        assert.equal(input.approvalToken, 'tok-apply-123');
        data = {applyId: 'apply-77', mode: input.mode, branchName: input.branchName || 'main', commitSha: '9f8e7d6c5b4a'};
      } else if (route === '/api/v1/repairs/applies/apply-77/rollback') {
        assert.equal(input?.confirmedByUser, true, 'Rollback must always send explicit confirmedByUser: true');
        data = {rolledBack: true, applyId: 'apply-77'};
      } else if (route === '/api/v1/github/repairs/prepare') {
        assert.equal(typeof input?.allowUnverified, 'boolean', 'Prepare must always send explicit boolean allowUnverified');
        assert.equal(input.allowUnverified, false, 'Verified patch prepare must not default allowUnverified to true');
        data = {
          publishPlanDigest: 'sha256:' + '7'.repeat(64),
          confirmationToken: 'gh-confirm-456',
          baseBranch: 'main', headBranch: 'evo/fix-web-no-repository', draft: false,
          title: 'fix(architecture): decouple OrderController via OrderGateway',
          bodyMarkdown: '## Verified Architecture Repair\n\n- **Score v2:** 71.4 -> 79.6 (+8.2)\n- **Violations:** 1 resolved, 0 introduced\n',
          trustBanner: 'VERIFIED IN ISOLATED DOCKER SANDBOX — 48/48 unit tests passed.'
        };
      } else if (route === '/api/v1/github/repairs/publish') {
        assert.equal(input.confirmedByUser, true);
        assert.equal(input.confirmationToken, 'gh-confirm-456');
        data = {number: 43, headBranch: 'evo/fix-web-no-repository', url: 'https://github.com/acme/shop-backend/pull/43'};
      } else if (route === '/api/v1/ai/providers/probe') {
        data = {ok: true, reachable: true, model: 'qwen2.5-coder:14b', latencyMs: 42};
      } else if (route === '/api/v1/graphs/' + graph + '/architecture-chat') {
        data = {
          status: 'ADVICE',
          pin: {graph, snapshot, context: input.context, finding: input.finding || null},
          evidenceIdentity: graph,
          conversationTitle: 'OrderController decoupling',
          answer: 'Review `OrderController` in [E1] — extracting `OrderGateway` breaks the Web -> Repository violation.',
          citations: ['E1'],
          limitations: [],
          evidence: [{id: 'E1', kind: 'SOURCE', label: 'OrderController.java:10-14', data: {path: 'src/main/java/com/acme/OrderController.java', startLine: 10, endLine: 14}}]
        };
      } else if (route === '/api/v1/graphs/' + graph + '/captured-source') {
        data = {
          found: true, path: input.path, language: 'java', startLine: 1, endLine: 14, totalLines: 14,
          digest: 'sha256:' + '5'.repeat(64),
          content: 'package com.acme;\n\npublic class OrderController {\n  private final OrderRepository repo;\n}\n'
        };
      } else {
        throw new Error('Unexpected mock route: ' + req.method + ' ' + route);
      }

      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({data}));
    } catch (error) {
      res.writeHead(500, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({error: {message: error.message}}));
    }
  });

  server.on('connection', socket => sockets.push(socket));
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));

  let page;
  const consoleErrors = [];
  const pageErrors = [];
  try {
    const outDir = path.resolve(output);
    await mkdir(outDir, {recursive: true});
    if (screenshotDir) await mkdir(path.resolve(screenshotDir), {recursive: true});
    page = await edge(browser, outDir);
    await page.send('Runtime.enable');
    page.on?.('Runtime.exceptionThrown', params => pageErrors.push(params.exceptionDetails?.text || 'exception'));
    page.on?.('Runtime.consoleAPICalled', params => {
      if (params.type === 'error') consoleErrors.push(params.args?.map(a => a.value || a.description).join(' '));
    });

    const evalJs = expr => page.evaluate(expr);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1440, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: `http://127.0.0.1:${server.address().port}`});

    // 1. Verify GitHub Hub Bar visible and removed stepper/gap-strip absent
    await until('GitHub Hub Bar visible', () => evalJs(`(() => {
      const bar = document.getElementById('github-hub-bar');
      return bar && !bar.hidden && bar.innerText.includes('acme/shop-backend')
        && document.getElementById('job-stage-stepper') === null
        && document.getElementById('job-gap-action-strip') === null;
    })()`));

    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'workbench-overview-github-dark.png'));
    }

    // 2. Test Smart Connect Dialog (GitHub PR URL auto-detect + RAM-only Session PAT dialog)
    await evalJs("document.querySelector('[data-action=connect]').click()");
    await until('Connect dialog open with auth pill', () => evalJs("document.getElementById('connect-dialog').open && !document.getElementById('connect-github-auth').hidden"));
    await evalJs(`(() => {
      const inp = document.getElementById('repository-source');
      inp.value = 'https://github.com/acme/shop-backend/pull/42';
      inp.dispatchEvent(new Event('input', {bubbles: true}));
    })()`);
    await until('Smart Intake detected GitHub PR #42 and populated live refs dropdown', () => evalJs(`(() => {
      const prev = document.getElementById('connect-smart-preview');
      const refSel = document.getElementById('connect-ref-select');
      return !prev.hidden && prev.innerText.includes('PR #42')
        && !document.getElementById('connect-ref-box').hidden
        && refSel.value === 'pull/42/head'
        && !document.getElementById('connect-pr-compare-wrap').hidden;
    })()`));

    // Activate RAM-only Session PAT (never persisted to localStorage)
    await evalJs("document.querySelector('#connect-github-auth .gh-auth-action').click()");
    await until('RAM-only Session PAT dialog open', () => evalJs("document.getElementById('github-session-dialog').open && document.getElementById('github-session-dialog').innerText.includes('RAM only')"));
    await evalJs("document.getElementById('github-session-pat-input').value = 'ghp_test_token_123456'; document.getElementById('github-session-pat-form').requestSubmit();");
    await until('Authenticated as @hoang-dev via RAM-only Session PAT', () => evalJs("document.getElementById('connect-github-auth').innerText.includes('@hoang-dev')"));
    assert.equal(await evalJs("Object.values(localStorage).join(' ').includes('ghp_test_token')"), false, 'Token must NEVER be stored in localStorage');
    await evalJs("document.getElementById('connect-dialog').close()");

    // 3. Test 1-Click Compare PR (Base vs Head) + Confirmed Review Comment
    await evalJs("document.getElementById('github-compare-pr-btn').click()");
    await until('PR #42 comparison card rendered with Score v2 delta', () => evalJs("document.getElementById('inspector').open && document.getElementById('inspector-body').innerText.includes('71.4 → 79.6 (+8.2)')"));
    assert.equal(await evalJs("[...document.querySelectorAll('#inspector-body button')].find(b => b.textContent.includes('Confirm & Post Comment')).disabled"), true);
    await evalJs("document.querySelector('#inspector-body input[type=checkbox]').click()");
    await evalJs("[...document.querySelectorAll('#inspector-body button')].find(b => b.textContent.includes('Confirm & Post Comment')).click()");
    await until('PR review comment posted', () => evalJs("document.getElementById('inspector-body').innerText.includes('issuecomment-999')"));
    await evalJs("document.getElementById('inspector').close()");

    // 4. Test 4-Step Code Repair & Sandbox Studio
    await evalJs("document.getElementById('context').value = 'analysis:main'; document.getElementById('context').dispatchEvent(new Event('change'));");
    await evalJs("document.getElementById('tab-policy').click()");
    await until('Repair Studio mounted with resolved class names', () => evalJs("document.querySelector('.rs-studio') && document.querySelector('.rs-studio').innerText.includes('com.acme.OrderController')"));
    assert.equal(await evalJs("/ad-hoc/i.test(document.querySelector('.rs-studio').innerText)"), false);

    // Step 1 -> Diagnosis
    await evalJs("[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Diagnose Issue')).click()");
    await until('Citations validated & clickable source coordinate shown', () => evalJs("document.querySelector('.rs-studio').innerText.includes('Citations Validated') && document.querySelector('.rs-citation-btn')?.textContent.includes('OrderController.java')"));
    await evalJs("[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Continue to Patch')).click()");

    // Step 2 -> Generate multi-file patch & toggle Unified / Side-by-Side
    await until('Applicable recipe loaded', () => evalJs("document.querySelector('.rs-studio select')?.innerText.includes('Extract OrderGateway')"));
    await evalJs("[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Generate Patch')).click()");
    await until('Multi-file diff rendered with CREATE, MODIFY, DELETE tabs', () => evalJs("document.querySelectorAll('.rs-file-tab').length === 3 && document.querySelector('.rs-file-list').innerText.includes('CREATE') && document.querySelector('.rs-file-list').innerText.includes('DELETE')"));
    await evalJs("[...document.querySelectorAll('.rs-mode-toggle button')].find(b => b.textContent === 'Side-by-side').click()");
    await until('Side-by-side split table rendered', () => evalJs("Boolean(document.querySelector('.rs-split-table'))"));

    if (screenshotDir) {
      await evalJs("document.querySelector('.rs-studio')?.scrollIntoView({block: 'start'})");
      await capturePng(page, path.join(screenshotDir, 'repair-studio-diff-dark.png'));
    }

    // Step 3 -> Async Isolated Sandbox Verification (SSE) -> Renders Report Card in Step 3
    await evalJs("[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Verify in Sandbox')).click()");
    await until('Step 3 Verification & Impact Report Card rendered with Score v2 +8.2 and D1-D7 breakdown', () => evalJs(`(() => {
      const card = document.querySelector('.rs-report-card');
      return card && card.innerText.includes('VERIFIED')
        && card.innerText.includes('Isolated Sandbox Verified')
        && card.innerText.includes('71.4 → 79.6 (+8.2)')
        && card.innerText.includes('1 resolved · 0 introduced')
        && card.textContent.includes('Hierarchy & Direction');
    })()`));

    if (screenshotDir) {
      await evalJs("document.querySelector('.rs-studio')?.scrollIntoView({block: 'start'})");
      await capturePng(page, path.join(screenshotDir, 'repair-impact-report-dark.png'));
    }

    const darkAudit = await auditContrastAndOverflow(page, 'dark-1440-impact-report');
    assert.equal(darkAudit.overflow, false, 'Dark desktop horizontal overflow: ' + JSON.stringify(darkAudit));
    assert.deepEqual(darkAudit.contrastFailures, [], 'Dark theme WCAG AA contrast failures: ' + JSON.stringify(darkAudit.contrastFailures));

    // Step 4 -> Explicit Confirmation Gate: New Branch, Direct Local, GitHub PR
    await evalJs("[...document.querySelectorAll('.rs-report-card button')].find(b => b.textContent.includes('Continue to Apply')).click()");
    await until('Step 4 Confirmation Gate loaded with Before-Hashes Verified', () => evalJs("document.querySelector('.rs-gate-card')?.innerText.includes('Before-Hashes Verified')"));

    // New Branch button MUST be disabled until safety checkbox checked
    assert.equal(await evalJs("[...document.querySelectorAll('.rs-gate-form button')].find(b => b.textContent.includes('Confirm & Apply to New Branch')).disabled"), true);
    await evalJs("document.querySelector('.rs-gate-form input[type=checkbox]').click()");
    assert.equal(await evalJs("[...document.querySelectorAll('.rs-gate-form button')].find(b => b.textContent.includes('Confirm & Apply to New Branch')).disabled"), false);
    await evalJs("[...document.querySelectorAll('.rs-gate-form button')].find(b => b.textContent.includes('Confirm & Apply to New Branch')).click()");
    await until('Apply receipt + Rollback button rendered', () => evalJs("document.querySelector('.rs-gate-card').innerText.includes('Apply ID: apply-77') && [...document.querySelectorAll('.rs-gate-card button')].some(b => b.textContent.includes('Rollback Applied Patch'))"));

    // Test Rollback
    await evalJs("[...document.querySelectorAll('.rs-gate-card button')].find(b => b.textContent.includes('Rollback Applied Patch')).click()");
    await until('Rollback completed cleanly', () => evalJs("document.querySelector('.rs-gate-card').innerText.includes('Rollback complete')"));

    // Option C: Push & Open Verified GitHub PR (Prepare -> Separate Remote Confirmation -> Publish)
    await evalJs("[...document.querySelectorAll('.rs-mode-card')].find(b => b.dataset.mode === 'GITHUB_PR').click()");
    await evalJs("[...document.querySelectorAll('.rs-gate-form button')].find(b => b.textContent.includes('Prepare GitHub PR Plan')).click()");
    await until('PR Plan loaded with editable title, trust banner, and disabled Publish button', () => evalJs("document.querySelector('.rs-gate-form').innerText.includes('VERIFIED IN ISOLATED DOCKER SANDBOX') && [...document.querySelectorAll('.rs-gate-form button')].find(b => b.textContent.includes('Confirm Push & Open Verified GitHub PR')).disabled === true"));

    if (screenshotDir) {
      await evalJs("document.querySelector('.rs-studio')?.scrollIntoView({block: 'start'})");
      await capturePng(page, path.join(screenshotDir, 'repair-confirmation-gate-dark.png'));
    }

    await evalJs("[...document.querySelectorAll('.rs-gate-form input[type=checkbox]')].at(-1).click()");
    await evalJs("[...document.querySelectorAll('.rs-gate-form button')].find(b => b.textContent.includes('Confirm Push & Open Verified GitHub PR')).click()");
    await until('Verified GitHub PR #43 published', () => evalJs("document.querySelector('.rs-gate-form').innerText.includes('Verified GitHub PR Opened · #43')"));

    // 5. Switch to Light Theme & capture Light Theme screenshot + run WCAG AA contrast audit!
    await evalJs("document.getElementById('theme-toggle').click()");
    assert.equal(await evalJs("document.documentElement.dataset.theme"), 'light');
    await evalJs("[...document.querySelectorAll('.rs-step-btn')].find(b => b.textContent.includes('3. Verify')).click()");
    await until('Light theme transitions settled and Step 4 rendered', () => evalJs(`(() => {
      const card = document.querySelector('.rs-report-card');
      const veil = document.querySelector('.u219-veil');
      const wrap = document.querySelector('.rs-finding-item-wrap') || document.querySelector('.rs-kpi-tile');
      const wrapBg = wrap ? getComputedStyle(wrap).backgroundColor : 'rgb(238, 241, 234)';
      return Boolean(card) && !veil && wrapBg === 'rgb(238, 241, 234)';
    })()`));
    const lightAudit = await auditContrastAndOverflow(page, 'light-1440-impact-report');
    assert.equal(lightAudit.overflow, false, 'Light desktop horizontal overflow: ' + JSON.stringify(lightAudit));
    assert.deepEqual(lightAudit.contrastFailures, [], 'Light theme WCAG AA contrast failures: ' + JSON.stringify(lightAudit.contrastFailures));
    if (screenshotDir) {
      await evalJs("document.querySelector('.rs-studio')?.scrollIntoView({block: 'start'})");
      await capturePng(page, path.join(screenshotDir, 'repair-impact-report-light.png'));
    }

    // 6. Test AI Architecture Chat: Provider/model visibility, live probe, contextual chips, clickable [E1] -> captured source modal
    await evalJs("document.getElementById('tab-architecture-chat').click()");
    await until('Chat mounted with model name, probe button, and quick-action chips', () => evalJs(`(() => {
      const chat = document.querySelector('.architecture-chat');
      return chat && chat.innerText.includes('qwen2.5-coder:14b')
        && Boolean(chat.querySelector('.architecture-chat-probe-btn'))
        && chat.querySelectorAll('.architecture-chat-quick-chips button').length >= 2;
    })()`));
    await evalJs("document.querySelector('.architecture-chat-probe-btn').click()");
    await until('Live provider probe reports Ready (42 ms)', () => evalJs("document.querySelector('.architecture-chat-model-state').innerText.includes('Ready (42 ms)')"));
    await evalJs("document.querySelector('.architecture-chat-quick-chips button').click()");
    await evalJs("document.querySelector('.architecture-chat button[type=submit]').click()");
    await until('Inline [E1] citation rendered', () => evalJs("Boolean(document.querySelector('.architecture-chat-inline-cite'))"));
    await evalJs("document.querySelector('.architecture-chat-inline-cite').click()");
    await until('Clicking [E1] SOURCE citation opens Captured Source modal directly', () => evalJs("document.getElementById('inspector').open && document.getElementById('inspector-kind').textContent === 'CAPTURED SOURCE' && document.getElementById('inspector-body').innerText.includes('OrderRepository')"));
    await evalJs("document.getElementById('inspector').close()");

    // 7. Mobile responsive audit (390x844 and 320x844) + Mobile screenshot
    await evalJs("document.getElementById('tab-policy').click()");
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await until('Mobile 390px layout settles', () => evalJs("window.innerWidth === 390"));
    const mobile390 = await auditContrastAndOverflow(page, 'light-390-mobile');
    assert.equal(mobile390.overflow, false, 'Mobile 390px overflow: ' + JSON.stringify(mobile390));
    if (screenshotDir) {
      await capturePng(page, path.join(screenshotDir, 'repair-studio-mobile-light.png'));
    }
    await page.send('Emulation.setDeviceMetricsOverride', {width: 320, height: 844, deviceScaleFactor: 1, mobile: true});
    await until('Mobile 320px layout settles', () => evalJs("window.innerWidth === 320"));
    const mobile320 = await auditContrastAndOverflow(page, 'light-320-mobile');
    assert.equal(mobile320.overflow, false, 'Mobile 320px overflow: ' + JSON.stringify(mobile320));

    assert.deepEqual(consoleErrors, [], 'Console errors occurred: ' + JSON.stringify(consoleErrors));
    assert.deepEqual(pageErrors, [], 'Uncaught page errors occurred: ' + JSON.stringify(pageErrors));

    console.log('PASS Phase-2 GitHub Hub, Smart Intake, RAM-only PAT, 1-Click PR Compare, 5-Step Repair & Sandbox Studio, Explicit Confirmation Gate, AI Chat Probe + [E#] Source Jump, WCAG AA Dark/Light Contrast & 320/390/1440 Responsive Audit');
  } finally {
    for (const c of repairSseClients) try { c.end(); } catch (_) {}
    await page?.close();
    sockets.forEach(s => s.destroy());
    server.close();
  }
}

main(process.argv.slice(2)).catch(error => { console.error(error); process.exitCode = 1; });
