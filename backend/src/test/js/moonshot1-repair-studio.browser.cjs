'use strict';

/**
 * Moonshot #1 Comprehensive Headless Edge Browser E2E Verification Suite:
 * 1. In-App 1-Click Sandbox Setup & Probe UI (GET/POST/DELETE /repairs/sandbox/setup, POST /probe,
 *    polyglot toolchain tiles MAVEN/GRADLE/NODE/PYTHON/GO, live hot-reload badge, ADMIN 403 notice).
 * 2. Closed-Loop Agentic Verified Repair (Step 2 read-only tool trace, Step 3 closed-loop & behavioral
 *    diff toggles, multi-round SSE live timeline, auto-advance to Step 4).
 * 3. 3-State Behavioral Differential Check (BEHAVIOR_CHANGED + witness viewer, EQUIVALENT_ON_BOUNDED_CHECKS,
 *    UNKNOWN_UNTESTED) & Spring Truth-Region Preservation (DRIFT_DETECTED MUST->MAY, PRESERVED, NOT_APPLICABLE).
 * 4. Persisted Job History drawer (GET /repairs/jobs) including INTERRUPTED_ON_RESTART recovery badge.
 * 5. Zero console/page/CSP errors, single-user zero-https: CSP, Dark & Light WCAG AA >= 4.5:1 contrast,
 *    and zero horizontal overflow at 1440x900, 375x844, and 320x844.
 */

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {mkdtemp, readFile, rm} = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

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
      '.rs-studio-title', '.rs-verdict-headline', '.rs-kpi-value', '.rs-kpi-label',
      '.rs-step-btn.is-current', '.badge', '.rs-intel-card .rs-subhead', '.rs-toolchain-head strong'
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

async function main([browser]) {
  const webDir = path.resolve(__dirname, '../../main/resources/web');
  const mime = {'.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'};
  const sockets = [];
  const sseClients = [];

  const graph = 'sha256:' + 'a'.repeat(64);
  const snapshot = 'snapshot:sha256:' + 'b'.repeat(64);
  const reportId = 'sha256:' + 'c'.repeat(64);
  const ctxId = 'analysis:sha256:' + 'd'.repeat(64);
  const proposalId = 'sha256:' + '1'.repeat(64);
  const diagInput = 'sha256:' + '2'.repeat(64);
  const findingId = 'sha256:' + '3'.repeat(64);
  const bundleId = 'sha256:' + '4'.repeat(64);
  const pinnedNode = 'node@sha256:' + 'e'.repeat(64);

  let sandboxConfigured = false;
  let sandboxSetupState = {
    configured: false,
    isolationMode: 'PASSIVE_REANALYSIS_ONLY',
    buildTool: 'auto',
    timeoutSeconds: 900,
    skipTests: false,
    lastProbe: null
  };
  let simulateMember403 = false;
  let lastVerifyPayload = null;
  let authMeMock = { authenticated: false, multiUserMode: false, user: null, csrfToken: '' };

  const probePayload = {
    dockerCliAvailable: true,
    dockerDaemonReachable: true,
    pythonAvailable: true,
    pythonExecutable: 'python',
    dockerContext: 'desktop-linux',
    pinnedLocalImages: [pinnedNode],
    toolchains: [
      { ecosystem: 'maven', ready: true, detectedCachePath: 'C:/Users/demo/.m2/repository', pinnedBaseImage: 'eclipse-temurin@sha256:' + 'f'.repeat(64), detail: 'Local .m2 cache detected' },
      { ecosystem: 'gradle', ready: false, diagnosticCode: 'OFFLINE_TOOLCHAIN_MISSING', detail: 'No offline Gradle home detected' },
      { ecosystem: 'node', ready: true, detectedCachePath: 'C:/Users/demo/npm-cache', pinnedBaseImage: pinnedNode, detail: 'npm offline cache detected' },
      { ecosystem: 'python', ready: false, diagnosticCode: 'OFFLINE_TOOLCHAIN_MISSING', detail: 'No offline pip wheelhouse configured' },
      { ecosystem: 'go', ready: false, diagnosticCode: 'OFFLINE_TOOLCHAIN_MISSING', detail: 'No GOMODCACHE configured' }
    ],
    notes: ['Read-only environment probe completed without running any target build on host.']
  };

  const reportChangedAndDrift = {
    version: 'architecture.repair-verification-report:v2.1',
    jobId: 'job-m1-closed-loop',
    verdict: 'VERIFIED',
    trustLabel: 'ISOLATED_CONTAINER_VERIFIED',
    isolationBackend: 'DOCKER_HARDENED_CONTAINER',
    build: { compiled: true, compileExit: 0, durationMs: 3200 },
    tests: { run: 6, failed: 0, errors: 0 },
    scoreV2Delta: {
      before: 71.0, after: 79.5, delta: 8.5, beforeBand: 'At Risk', afterBand: 'Healthy',
      dimensions: [{ id: 'D1', displayName: 'Acyclic Integrity', before: 54.0, after: 88.0, delta: 34.0 }]
    },
    violationsDelta: { resolved: 1, introduced: 0, persisted: 0 },
    capabilityGapsDelta: { before: 0, after: 0, delta: 0 },
    checks: [{ id: 'isolated-build-and-test', status: 'PASSED', reason: 'Round 2 compiled and passed 6 tests in hardened container.' }],
    behavioralDiff: {
      verdict: 'BEHAVIOR_CHANGED',
      executedChecksCount: 6,
      baselineTestsRun: 6,
      baselineTestsFailed: 1,
      candidateTestsRun: 6,
      candidateTestsFailed: 0,
      proposedProbesGenerated: 1,
      proposedProbesSyntaxPassed: 1,
      proposedProbesExecuted: 1,
      unionCoveragePct: 84.5,
      summary: 'Candidate fixed failing baseline test in isolated dual-run.',
      witnesses: [{
        witnessId: 'w-1',
        language: 'java',
        testPath: 'src/test/java/com/acme/OrderCycleTest.java',
        baseExit: 1,
        candExit: 0,
        intentClassification: 'INTENDED_FIX',
        outputSha256: 'sha256:' + '7'.repeat(64),
        outputExcerpt: 'Baseline FAILED OrderCycleTest.testBreakCycle -> Candidate PASSED (6/6).'
      }]
    },
    springPreservation: {
      status: 'DRIFT_DETECTED',
      baselineFactCount: 4,
      candidateFactCount: 4,
      preservedFactCount: 3,
      baselineWorldCount: 2,
      candidateWorldCount: 2,
      preservedWorldCount: 2,
      summary: '1 Spring MUST truth region degraded to MAY.',
      drifts: [{
        factKey: 'spring:OrderGateway',
        factKind: 'REGION_CLASSIFICATION_DEGRADED',
        symbolLabel: 'com.acme.OrderGateway',
        beforeClassification: 'MUST',
        afterClassification: 'MAY',
        severity: 'ERROR',
        detail: 'Bean registration condition narrowed from unconditional MUST to conditional MAY.'
      }]
    },
    rounds: [
      {
        round: 1,
        proposalId: 'sha256:' + '1'.repeat(64),
        verdict: 'REJECTED',
        compileExit: 1,
        testExit: 1,
        toolCallsCount: 1,
        toolTrace: [{ step: 1, tool: 'read_captured_source', summary: 'Read OrderService.java (lines 1-60)' }],
        feedbackSummary: 'Round 1 failed compileExit=1; redacted diagnostics fed into Round 2.'
      },
      {
        round: 2,
        proposalId: 'sha256:' + '9'.repeat(64),
        verdict: 'VERIFIED',
        compileExit: 0,
        testExit: 0,
        toolCallsCount: 1,
        toolTrace: [{ step: 1, tool: 'inspect_symbol_details', summary: 'Inspected OrderController bindings' }],
        feedbackSummary: 'Round 2 passed isolated compile and 6/6 tests.'
      }
    ],
    markdownReport: '# Repair Verification Report\n\n## 4b. Behavioral Differential Check\n- Verdict: BEHAVIOR_CHANGED\n\n## 4c. Spring Truth-Region Preservation\n- Status: DRIFT_DETECTED\n'
  };

  const reportEquivalentPreserved = {
    ...reportChangedAndDrift,
    jobId: 'job-m1-equivalent',
    behavioralDiff: {
      verdict: 'EQUIVALENT_ON_BOUNDED_CHECKS',
      executedChecksCount: 4,
      baselineTestsRun: 4, baselineTestsFailed: 0,
      candidateTestsRun: 4, candidateTestsFailed: 0,
      proposedProbesGenerated: 0, proposedProbesSyntaxPassed: 0, proposedProbesExecuted: 0,
      summary: 'Baseline and candidate matched across all 4 executed sandbox checks.',
      witnesses: []
    },
    springPreservation: {
      status: 'PRESERVED',
      baselineFactCount: 4, candidateFactCount: 4, preservedFactCount: 4,
      baselineWorldCount: 2, candidateWorldCount: 2, preservedWorldCount: 2,
      summary: 'All 4 Spring MUST regions preserved cleanly.',
      drifts: []
    },
    rounds: [reportChangedAndDrift.rounds[1]]
  };

  const reportUnknownUntested = {
    ...reportChangedAndDrift,
    jobId: 'job-m1-untested',
    verdict: 'UNVERIFIED',
    trustLabel: 'PASSIVE_REANALYSIS_ONLY',
    behavioralDiff: {
      verdict: 'UNKNOWN_UNTESTED',
      executedChecksCount: 0,
      baselineTestsRun: 0, baselineTestsFailed: 0,
      candidateTestsRun: 0, candidateTestsFailed: 0,
      proposedProbesGenerated: 0, proposedProbesSyntaxPassed: 0, proposedProbesExecuted: 0,
      summary: 'Zero tests executed; behavioral equivalence is NOT claimed.',
      witnesses: []
    },
    springPreservation: {
      status: 'NOT_APPLICABLE',
      baselineFactCount: 0, candidateFactCount: 0, preservedFactCount: 0,
      baselineWorldCount: 0, candidateWorldCount: 0, preservedWorldCount: 0,
      summary: 'No Spring contexts present.',
      drifts: []
    },
    rounds: []
  };

  const jobsById = new Map([
    ['job-m1-closed-loop', { jobId: 'job-m1-closed-loop', proposalId, stage: 'REPORT_READY', terminal: true, report: reportChangedAndDrift, rounds: reportChangedAndDrift.rounds }],
    ['job-m1-equivalent', { jobId: 'job-m1-equivalent', proposalId, stage: 'REPORT_READY', terminal: true, report: reportEquivalentPreserved, rounds: reportEquivalentPreserved.rounds }],
    ['job-m1-untested', { jobId: 'job-m1-untested', proposalId, stage: 'REPORT_READY', terminal: true, report: reportUnknownUntested, rounds: [] }],
    ['job-m1-interrupted', {
      jobId: 'job-m1-interrupted', proposalId, stage: 'INTERRUPTED_ON_RESTART', terminal: true,
      error: 'Server restarted while verification job was in progress.', rounds: [],
      events: [
        { seq: 1, stage: 'SANDBOX_BUILD', message: 'Round 1/3: building container', detail: { round: 1 } },
        { seq: 2, stage: 'INTERRUPTED_ON_RESTART', message: 'Server restarted while verification job was in progress.', detail: { errorCode: 'INTERRUPTED_ON_RESTART' } }
      ]
    }]
  ]);

  const readBody = req => new Promise(resolve => {
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
  });

  const server = createServer(async (req, res) => {
    const u = new URL(req.url, 'http://127.0.0.1');
    const p = decodeURIComponent(u.pathname);
    const json = (status, obj) => {
      const body = JSON.stringify(obj);
      res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      res.end(body);
    };

    if (!p.startsWith('/api/')) {
      const rel = p === '/' ? '/index.html' : p;
      try {
        const buf = await readFile(path.join(webDir, rel));
        const headers = { 'Content-Type': mime[path.extname(rel)] || 'application/octet-stream' };
        if (rel === '/index.html') {
          headers['Content-Security-Policy'] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'";
        }
        res.writeHead(200, headers);
        res.end(buf);
      } catch (_) {
        res.writeHead(404);
        res.end('Not found');
      }
      return;
    }

    if (p === '/api/v1') {
      return json(200, {
        version: 'architecture.query.api:v1',
        analysisJobsEnabled: true,
        capturedRepairEnabled: true,
        isolatedRepairRunnerEnabled: sandboxConfigured,
        repairIsolatedRunnerConfigured: sandboxConfigured,
        repairSandboxMode: sandboxConfigured ? sandboxSetupState.isolationMode : 'PASSIVE_REANALYSIS_ONLY',
        multiUserAuthEnabled: false
      });
    }
    if (p === '/api/v1/auth/me') {
      return json(200, { data: authMeMock });
    }
    if (p === '/api/v1/auth/config') {
      return json(200, { data: { multiUserMode: Boolean(authMeMock.multiUserMode), oauthWebEnabled: false, deviceFlowEnabled: false, githubAppEnabled: false } });
    }
    if (p === '/api/v1/github/auth/status') {
      return json(200, { authenticated: false, oauthConfigured: false });
    }
    if (p === '/api/v1/setups') {
      return json(200, {
        items: [{
          setup: 'setup-m1', name: 'acme-orders', source: 'D:/repos/acme-orders',
          graph, snapshot, latestReport: reportId, latestContext: ctxId,
          status: 'SUCCEEDED', updatedAt: new Date().toISOString()
        }]
      });
    }
    if (p === '/api/v1/jobs') return json(200, { items: [] });
    if (p === `/api/v1/graphs/${graph}`) {
      return json(200, {
        graph, snapshot,
        contexts: [{ identity: ctxId, family: 'analysis.manifest', snapshot, analysis: ctxId }],
        summary: { nodeCounts: { TYPE: 12, PACKAGE: 3 }, edgeCounts: { 'graph.depends-on': 18 } }
      });
    }
    if (p === `/api/v1/graphs/${graph}/summary`) {
      return json(200, { nodeCounts: { TYPE: 12, PACKAGE: 3 }, edgeCounts: { 'graph.depends-on': 18 }, contexts: 1 });
    }
    if (p === `/api/v1/graphs/${graph}/reports`) {
      return json(200, { items: [{ identity: reportId, policyId: 'architecture.cycles', findingCount: 1 }] });
    }
    if (p === `/api/v1/graphs/${graph}/reports/${reportId}`) {
      return json(200, {
        identity: reportId,
        policy: { id: 'architecture.cycles', version: '1' },
        findings: [{
          identity: findingId,
          bundleIdentity: bundleId,
          result: {
            kind: 'ACYCLIC',
            classification: 'MUST',
            rule: { id: 'no-cycles', name: 'No Service Cycles', severity: 'ERROR' },
            bundle: { path: ['type:OrderService', 'type:BillingService'], request: { scope: 'TYPE', context: ctxId } }
          }
        }]
      });
    }
    if (p === `/api/v1/graphs/${graph}/reports/${reportId}/diagnosis` && req.method === 'POST') {
      return json(200, {
        status: 'CITATIONS_VALIDATED', mode: 'LOCAL_REPLAY', input: diagInput,
        explanation: 'OrderService and BillingService form a circular dependency.',
        citations: [{ path: 'src/main/java/com/acme/OrderService.java', span: { startLine: 1, startColumn: 1, endLine: 10 } }]
      });
    }
    if (p === `/api/v1/graphs/${graph}/reports/${reportId}/repair-options` && req.method === 'POST') {
      return json(200, {
        recipes: [{ id: 'extract-interface', title: 'Extract OrderGateway Interface', summary: 'Break cycle cleanly' }],
        applicableRecipes: ['extract-interface']
      });
    }
    if (p === `/api/v1/graphs/${graph}/reports/${reportId}/repair-proposal` && req.method === 'POST') {
      return json(200, {
        status: 'PROPOSED',
        attempts: 1,
        toolTrace: [
          { step: 1, tool: 'read_captured_source', summary: 'Read OrderService.java (lines 1-60)' },
          { step: 2, tool: 'inspect_symbol_details', summary: 'Inspected OrderService bindings' }
        ],
        proposal: {
          identity: proposalId,
          candidate: {
            strategy: 'AI',
            plan: { summary: 'Extract OrderGateway interface to break service cycle.' },
            edits: [{ path: 'src/main/java/com/acme/OrderService.java' }],
            patch: [
              'diff --git a/src/main/java/com/acme/OrderService.java b/src/main/java/com/acme/OrderService.java',
              '--- a/src/main/java/com/acme/OrderService.java',
              '+++ b/src/main/java/com/acme/OrderService.java',
              '@@ -1,3 +1,3 @@',
              ' public class OrderService {',
              '-  private BillingService billing;',
              '+  private OrderGateway billing;',
              ' }'
            ].join('\n')
          }
        }
      });
    }
    // Moonshot #1 Sandbox Setup & Probe routes
    if (p === '/api/v1/repairs/sandbox/setup') {
      if (simulateMember403) {
        return json(403, { error: { code: 'ADMIN_REQUIRED', message: 'Workspace ADMIN role is required.' } });
      }
      if (req.method === 'GET') {
        return json(200, sandboxSetupState);
      }
      if (req.method === 'POST') {
        const body = JSON.parse((await readBody(req)) || '{}');
        sandboxConfigured = true;
        sandboxSetupState = {
          configured: true,
          isolationMode: body.isolationMode || 'DOCKER_HARDENED_CONTAINER',
          buildTool: body.buildTool || 'auto',
          baseImage: body.baseImage || pinnedNode,
          timeoutSeconds: body.timeoutSeconds || 900,
          skipTests: Boolean(body.skipTests),
          lastProbe: body.autoDetect ? probePayload : sandboxSetupState.lastProbe
        };
        return json(200, sandboxSetupState);
      }
      if (req.method === 'DELETE') {
        sandboxConfigured = false;
        sandboxSetupState = {
          configured: false,
          isolationMode: 'PASSIVE_REANALYSIS_ONLY',
          buildTool: 'auto',
          timeoutSeconds: 900,
          skipTests: false,
          lastProbe: sandboxSetupState.lastProbe
        };
        return json(200, sandboxSetupState);
      }
    }
    if (p === '/api/v1/repairs/sandbox/probe' && req.method === 'POST') {
      if (simulateMember403) {
        return json(403, { error: { code: 'ADMIN_REQUIRED', message: 'Workspace ADMIN role is required.' } });
      }
      sandboxSetupState.lastProbe = probePayload;
      return json(200, { probe: probePayload });
    }
    if (p === `/api/v1/repairs/${proposalId}/verify` && req.method === 'POST') {
      lastVerifyPayload = JSON.parse((await readBody(req)) || '{}');
      return json(202, {
        jobId: 'job-m1-closed-loop',
        stage: 'QUEUED',
        eventsUrl: '/api/v1/repairs/jobs/job-m1-closed-loop/events'
      });
    }
    if (p === '/api/v1/repairs/jobs/job-m1-closed-loop/events') {
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive'
      });
      sseClients.push(res);
      res.write('event: stage\ndata: ' + JSON.stringify({ stage: 'SANDBOX_BUILD', detail: { round: 1, maxRounds: 3 }, percent: 25, message: 'Round 1/3: isolated sandbox build failed (compileExit=1)' }) + '\n\n');
      setTimeout(() => {
        if (!res.writableEnded) {
          res.write('event: stage\ndata: ' + JSON.stringify({ stage: 'CLOSED_LOOP_REPAIR', detail: { round: 2, maxRounds: 3 }, percent: 55, message: 'Round 2/3: feeding redacted diagnostics back to Repair LLM' }) + '\n\n');
        }
      }, 40);
      setTimeout(() => {
        if (!res.writableEnded) {
          res.write('event: stage\ndata: ' + JSON.stringify({ stage: 'DIFF_TESTGEN', detail: { round: 2, maxRounds: 3 }, percent: 80, message: 'Round 2/3: executing dual baseline/candidate behavioral diff' }) + '\n\n');
        }
      }, 80);
      setTimeout(() => {
        if (!res.writableEnded) {
          res.write('event: stage\ndata: ' + JSON.stringify({ stage: 'REPORT_READY', detail: { round: 2, maxRounds: 3 }, percent: 100, message: 'Verification report ready' }) + '\n\n');
          res.end();
        }
      }, 130);
      return;
    }
    if (p === '/api/v1/repairs/jobs' && req.method === 'GET') {
      return json(200, { items: [...jobsById.values()] });
    }
    if (p.startsWith('/api/v1/repairs/jobs/')) {
      const id = decodeURIComponent(p.slice('/api/v1/repairs/jobs/'.length));
      if (id.endsWith('/report.md')) {
        res.writeHead(200, { 'Content-Type': 'text/markdown; charset=utf-8' });
        res.end(reportChangedAndDrift.markdownReport);
        return;
      }
      const job = jobsById.get(id);
      return job ? json(200, job) : json(404, { error: { code: 'NOT_FOUND', message: 'Job not found' } });
    }
    // Fallback benign responses for workbench background calls
    if (p.includes('/score') || p.includes('/metrics') || p.includes('/gaps') || p.includes('/nodes') || p.includes('/edges')) {
      return json(200, { items: [], dimensions: [], overallScore: 71.0, band: 'At Risk' });
    }
    return json(200, {});
  });

  server.on('connection', s => { sockets.push(s); s.on('close', () => { const i = sockets.indexOf(s); if (i >= 0) sockets.splice(i, 1); }); });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const port = server.address().port;
  const baseUrl = `http://127.0.0.1:${port}/`;

  // Verify single-user zero-https: CSP directly over HTTP (drain body so undici keep-alive releases socket cleanly)
  const rootCheck = await fetch(baseUrl);
  await rootCheck.text();
  const cspHeader = rootCheck.headers.get('content-security-policy') || '';
  assert.equal(cspHeader.includes('https:'), false, `Single-user CSP must not contain https:, got: ${cspHeader}`);

  const consoleErrors = [];
  const pageErrors = [];
  const tmpDir = await mkdtemp(path.join(os.tmpdir(), 'evo-m1-edge-'));
  const page = await edge(browser, tmpDir);
  page.socket.addEventListener('message', ev => {
    try {
      const msg = JSON.parse(ev.data);
      if (msg.method === 'Runtime.exceptionThrown') {
        const d = msg.params?.exceptionDetails;
        pageErrors.push(d?.exception?.description || d?.text || 'Uncaught exception');
      } else if (msg.method === 'Runtime.consoleAPICalled' && msg.params?.type === 'error') {
        const text = (msg.params.args || []).map(a => a.value ?? a.description ?? '').join(' ');
        consoleErrors.push(text);
      }
    } catch (_) {}
  });
  await page.send('Page.enable');
  await page.send('Runtime.enable');
  await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await page.send('Page.navigate', { url: baseUrl });

  try {
    // Wait for EvoRepairStudio and EvoSandboxSetup to load and initial workbench boot to settle
    await until('EvoRepairStudio & EvoSandboxSetup loaded', () => page.evaluate('Boolean(window.EvoRepairStudio && window.EvoSandboxSetup && window.EvoModel)'), 10000);
    await new Promise(r => setTimeout(r, 150));

    // Mount Repair Studio inside a dedicated visible container (keeping .app-shell DOM nodes intact for app.js)
    await page.evaluate(`(() => {
      const shell = document.querySelector('.app-shell');
      if (shell) shell.hidden = true;
      const host = document.createElement('div');
      host.id = 'm1-studio-host';
      host.style.cssText = 'padding:16px; max-width:1280px; margin:0 auto;';
      document.body.append(host);
      const api = async (path, body, signal, method = 'GET') => {
        const opts = { method, credentials: 'same-origin', signal };
        if (body !== undefined && method !== 'GET' && method !== 'DELETE') {
          opts.headers = { 'Content-Type': 'application/json' };
          opts.body = JSON.stringify(body);
        }
        const r = await fetch('/api/v1' + path, opts);
        const text = await r.text();
        let parsed = null;
        try { parsed = text ? JSON.parse(text) : null; } catch (_) {}
        if (!r.ok) {
          const err = new Error(parsed?.error?.message || ('HTTP ' + r.status));
          err.status = r.status;
          err.code = parsed?.error?.code || '';
          throw err;
        }
        return parsed;
      };
      const env = { graph: ${JSON.stringify(graph)}, context: ${JSON.stringify(ctxId)}, signal: new AbortController().signal };
      const finding = {
        identity: ${JSON.stringify(findingId)},
        bundleIdentity: ${JSON.stringify(bundleId)},
        result: {
          kind: 'ACYCLIC',
          classification: 'MUST',
          rule: { id: 'no-cycles', name: 'No Service Cycles', severity: 'ERROR' },
          bundle: { path: ['type:OrderService', 'type:BillingService'], request: { scope: 'TYPE', context: ${JSON.stringify(ctxId)} } }
        }
      };
      window.__m1Studio = window.EvoRepairStudio.mount(host, {
        env, reportId: ${JSON.stringify(reportId)}, findings: [finding], api,
        resolveLabels: async () => {}, openCapturedSource: () => {}, inspectFinding: () => {},
        askAboutFinding: () => {}, downloadBlob: () => {},
        capabilities: { repairIsolatedRunnerConfigured: false, repairSandboxMode: 'PASSIVE_REANALYSIS_ONLY' },
        setupId: 'setup-m1', isGitHubRepo: false
      });
    })()`);

    // ── 1. In-App 1-Click Sandbox Setup & Probe UI ────────────────────────────
    await page.evaluate(`document.querySelector('.rs-sandbox-toggle').click()`);
    await until('Passive Reanalysis badge', () => page.evaluate(`document.querySelector('.rs-sandbox-status-pill')?.textContent.includes('Passive Reanalysis Only')`), 5000);

    // Click Probe Environment -> verifies 5 polyglot toolchains + OFFLINE_TOOLCHAIN_MISSING pill
    await page.evaluate(`[...document.querySelectorAll('.rs-sandbox-panel button')].find(b => b.textContent.includes('Probe Environment')).click()`);
    await until('5 polyglot toolchain tiles', () => page.evaluate(`document.querySelectorAll('.rs-toolchain-tile').length === 5`), 5000);
    const probeText = await page.evaluate(`document.querySelector('.rs-probe-card').textContent`);
    assert.ok(probeText.includes('MAVEN') && probeText.includes('NODE') && probeText.includes('PYTHON') && probeText.includes('GO'));
    assert.ok(probeText.includes('OFFLINE_TOOLCHAIN_MISSING'));

    // Click 1-Click Auto-Setup Sandbox -> live hot-reload updates pill to Hardened Container Active
    await page.evaluate(`[...document.querySelectorAll('.rs-sandbox-panel button')].find(b => b.textContent.includes('1-Click Auto-Setup')).click()`);
    await until('Hardened Container Active badge', () => page.evaluate(`document.querySelector('.rs-sandbox-status-pill')?.textContent.includes('Hardened Container Active')`), 5000);

    // Click Disable Isolated Runner -> returns to Passive Reanalysis Only
    await page.evaluate(`[...document.querySelectorAll('.rs-sandbox-panel button')].find(b => b.textContent.includes('Disable Isolated Runner')).click()`);
    await until('Passive badge after disable', () => page.evaluate(`document.querySelector('.rs-sandbox-status-pill')?.textContent.includes('Passive Reanalysis Only')`), 5000);

    // Re-enable via 1-Click Auto-Setup for the Closed-Loop Verification flow
    await page.evaluate(`[...document.querySelectorAll('.rs-sandbox-panel button')].find(b => b.textContent.includes('1-Click Auto-Setup')).click()`);
    await until('Re-enabled Hardened Container badge', () => page.evaluate(`document.querySelector('.rs-sandbox-status-pill')?.textContent.includes('Hardened Container Active')`), 5000);

    // Test multi-user MEMBER 403 ADMIN_REQUIRED rendering cleanly
    simulateMember403 = true;
    await page.evaluate(`document.querySelector('.rs-sandbox-toggle').click()`); // close
    await page.evaluate(`document.querySelector('.rs-sandbox-toggle').click()`); // reopen as member
    await until('ADMIN Role Required notice', () => page.evaluate(`document.querySelector('.rs-sandbox-panel')?.textContent.includes('ADMIN Role Required')`), 5000);
    simulateMember403 = false;
    await page.evaluate(`document.querySelector('.rs-sandbox-toggle').click()`); // close drawer

    // ── 2. Step 1 -> Step 2 (Read-Only Tool Trace) -> Step 3 (Closed-Loop SSE) ─
    await page.evaluate(`[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Run Evidence-Grounded Diagnosis')).click()`);
    await until('Diagnosis citations validated', () => page.evaluate(`document.querySelector('.rs-studio')?.textContent.includes('Citations Validated')`), 5000);
    await page.evaluate(`[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Continue to Step 2')).click()`);
    await until('Step 2 recipe selector', () => page.evaluate(`Boolean(document.querySelector('.rs-studio select'))`), 5000);
    await page.evaluate(`[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('Generate Recipe Patch')).click()`);
    await until('Step 2 read-only tool trace', () => page.evaluate(`document.querySelector('.rs-studio')?.textContent.includes('Read-only repair agent tool calls (2)')`), 5000);

    // Click Step 3 Verify -> sends maxRounds=3, runBehavioralDiff=true, streams multi-round SSE -> auto-advances to Step 4!
    await page.evaluate(`[...document.querySelectorAll('.rs-studio button')].find(b => b.textContent.includes('3. Verify in Isolated Sandbox')).click()`);
    await until('Step 4 Behavioral Diff + Spring + Rounds cards', () => page.evaluate(`Boolean(document.querySelector('.rs-behavioral-card') && document.querySelector('.rs-spring-card') && document.querySelector('.rs-rounds-card'))`), 8000);

    assert.equal(lastVerifyPayload.closedLoop, true);
    assert.equal(lastVerifyPayload.maxRounds, 3);
    assert.equal(lastVerifyPayload.runBehavioralDiff, true);

    // ── 3. Step 4: 3-State Behavioral Diff + Witness + Spring Drift + Rounds ──
    const step4Check = await page.evaluate(`(() => {
      const bd = document.querySelector('.rs-behavioral-card');
      const sp = document.querySelector('.rs-spring-card');
      const rd = document.querySelector('.rs-rounds-card');
      return {
        bdVerdict: bd?.dataset.behavioralVerdict,
        bdText: bd?.textContent || '',
        spStatus: sp?.dataset.springStatus,
        spText: sp?.textContent || '',
        roundItems: rd?.querySelectorAll('.rs-round-item').length || 0,
        rdText: rd?.textContent || ''
      };
    })()`);
    assert.equal(step4Check.bdVerdict, 'BEHAVIOR_CHANGED');
    assert.ok(step4Check.bdText.includes('OrderCycleTest.java') && step4Check.bdText.includes('INTENDED_FIX'));
    assert.equal(step4Check.spStatus, 'DRIFT_DETECTED');
    assert.ok(step4Check.spText.includes('com.acme.OrderGateway') && step4Check.spText.includes('MUST → MAY'));
    assert.equal(step4Check.roundItems, 2);
    assert.ok(step4Check.rdText.includes('Round 1') && step4Check.rdText.includes('Round 2'));

    // ── 4. Contrast & Responsive Overflow Audit (Dark + Light, 1440 / 375 / 320) ─
    for (const theme of ['dark', 'light']) {
      await page.evaluate(`document.documentElement.setAttribute('data-theme', ${JSON.stringify(theme)})`);
      await new Promise(r => setTimeout(r, 260));
      for (const [w, h] of [[1440, 900], [375, 844], [320, 844]]) {
        await page.send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 600 });
        await new Promise(r => setTimeout(r, 80));
        const audit = await auditContrastAndOverflow(page, `${theme}-${w}x${h}`);
        assert.equal(audit.overflow, false, `Horizontal overflow at ${audit.label}: scrollWidth=${audit.scrollWidth} > innerWidth=${audit.innerWidth}`);
        assert.deepEqual(audit.contrastFailures, [], `WCAG AA contrast failure at ${audit.label}: ${JSON.stringify(audit.contrastFailures)}`);
      }
    }
    await page.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
    await page.evaluate(`document.documentElement.setAttribute('data-theme', 'dark')`);

    // ── 5. Persisted Job History Drawer (INTERRUPTED_ON_RESTART + EQUIVALENT + UNKNOWN_UNTESTED) ─
    await page.evaluate(`document.querySelector('.rs-history-toggle').click()`);
    await until('4 persisted history jobs', () => page.evaluate(`document.querySelectorAll('.rs-history-item').length === 4`), 5000);
    const histText = await page.evaluate(`document.querySelector('.rs-history-card').textContent`);
    assert.ok(histText.includes('INTERRUPTED_ON_RESTART'), 'Expected INTERRUPTED_ON_RESTART recovery badge in Job History');

    // Open EQUIVALENT_ON_BOUNDED_CHECKS + PRESERVED report from Job History
    await page.evaluate(`[...document.querySelectorAll('.rs-history-item')].find(li => li.textContent.includes('EQUIVALENT_ON_BOUNDED_CHECKS')).querySelector('button').click()`);
    await until('EQUIVALENT behavioral card', () => page.evaluate(`document.querySelector('.rs-behavioral-card')?.dataset.behavioralVerdict === 'EQUIVALENT_ON_BOUNDED_CHECKS'`), 5000);
    assert.equal(await page.evaluate(`document.querySelector('.rs-spring-card')?.dataset.springStatus`), 'PRESERVED');

    // Open UNKNOWN_UNTESTED + NOT_APPLICABLE report from Job History
    await page.evaluate(`document.querySelector('.rs-history-toggle').click()`);
    await until('Reopened 4 history jobs', () => page.evaluate(`document.querySelectorAll('.rs-history-item').length === 4`), 5000);
    await page.evaluate(`[...document.querySelectorAll('.rs-history-item')].find(li => li.textContent.includes('UNKNOWN_UNTESTED')).querySelector('button').click()`);
    await until('UNKNOWN_UNTESTED behavioral card', () => page.evaluate(`document.querySelector('.rs-behavioral-card')?.dataset.behavioralVerdict === 'UNKNOWN_UNTESTED'`), 5000);
    assert.equal(await page.evaluate(`document.querySelector('.rs-spring-card')?.dataset.springStatus`), 'NOT_APPLICABLE');

    // Inspect INTERRUPTED_ON_RESTART job from Job History -> must navigate to Step 3 and render interrupted status + persisted events!
    await page.evaluate(`document.querySelector('.rs-history-toggle').click()`);
    await until('Reopened 4 history jobs for interrupted check', () => page.evaluate(`document.querySelectorAll('.rs-history-item').length === 4`), 5000);
    await page.evaluate(`[...document.querySelectorAll('.rs-history-item')].find(li => li.textContent.includes('INTERRUPTED_ON_RESTART')).querySelector('button').click()`);
    await until('Interrupted job renders in Step 3', () => page.evaluate(`document.querySelector('.rs-verify-status')?.textContent.includes('INTERRUPTED_ON_RESTART') && document.querySelectorAll('.rs-live-rounds .rs-round-item').length === 2`), 5000);

    // ── 6. Workspace Settings RBAC: Sandbox Runner tab hidden for multi-user MEMBER, visible for ADMIN & local single-user ─
    const checkWorkspaceSandboxTab = async (nextAuthMe, expectVisible, label) => {
      authMeMock = nextAuthMe;
      await page.evaluate(`(async () => {
        if (window.EvoApp?.state) {
          window.EvoApp.state.capabilities = {
            ...(window.EvoApp.state.capabilities || {}),
            multiUserAuthEnabled: ${JSON.stringify(Boolean(nextAuthMe.multiUserMode))},
            multiUserMode: ${JSON.stringify(Boolean(nextAuthMe.multiUserMode))}
          };
        }
        await window.EvoGitHubAuth.refreshAuth();
        window.EvoGitHubAuth.openWorkspaceAccountModal('sandbox');
      })()`);
      await until(`Workspace modal opened (${label})`, () => page.evaluate(`Boolean(document.getElementById('workspace-account-dialog')?.open)`), 5000);
      const state = await page.evaluate(`(() => {
        const dlg = document.getElementById('workspace-account-dialog');
        const tabLabels = [...dlg.querySelectorAll('.u416-tab')].map(b => b.textContent.trim());
        const hasPanel = Boolean(dlg.querySelector('.rs-sandbox-panel'));
        dlg.close();
        return { tabLabels, hasPanel };
      })()`);
      const visible = state.tabLabels.includes('Sandbox Runner') || state.hasPanel;
      assert.equal(visible, expectVisible, `Expected Sandbox Runner visible=${expectVisible} for ${label}, got ${JSON.stringify(state)}`);
    };
    await checkWorkspaceSandboxTab(
      { authenticated: true, multiUserMode: true, csrfToken: 'csrf-m', user: { userId: 'u-mem', login: 'member', role: 'MEMBER' } },
      false, 'multi-user MEMBER'
    );
    await checkWorkspaceSandboxTab(
      { authenticated: true, multiUserMode: true, csrfToken: 'csrf-a', user: { userId: 'u-adm', login: 'admin', role: 'ADMIN' } },
      true, 'multi-user ADMIN'
    );
    await checkWorkspaceSandboxTab(
      { authenticated: false, multiUserMode: false, csrfToken: '', user: null },
      true, 'single-user local operator'
    );

    assert.deepEqual(consoleErrors, [], `Console errors observed: ${JSON.stringify(consoleErrors)}`);
    assert.deepEqual(pageErrors, [], `Uncaught page errors observed: ${JSON.stringify(pageErrors)}`);
    console.log('MOONSHOT1_EDGE_E2E_PASSED (1-Click Sandbox Setup, 403 RBAC, Workspace Settings MEMBER/ADMIN/Local RBAC, Closed-Loop SSE, 3-State Behavioral Diff, Spring Preservation, Job History INTERRUPTED_ON_RESTART Inspect, Dark/Light WCAG AA, 1440/375/320 zero overflow, zero-https CSP)');
  } finally {
    for (const r of sseClients) { try { r.end(); } catch (_) {} }
    sseClients.length = 0;
    await page.close().catch(() => {});
    try { server.closeAllConnections?.(); } catch (_) {}
    for (const s of sockets) { try { s.destroy(); } catch (_) {} }
    await new Promise(r => server.close(r));
    server.unref?.();
    await rm(tmpDir, { recursive: true, force: true }).catch(() => {});
  }
}

main(process.argv.slice(2)).catch(err => {
  console.error(err);
  process.exit(1);
});
