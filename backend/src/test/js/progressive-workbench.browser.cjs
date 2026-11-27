'use strict';

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {mkdir, readFile} = require('node:fs/promises');
const path = require('node:path');
const {edge, until} = require('./m9-edge.cjs');

async function main([browser, output]) {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const mime = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'};
  const sockets = [];
  const sseClients = [];
  const submittedCompare = [];

  const date = new Date(0).toISOString();
  const prevGraph = 'sha256:' + '1'.repeat(64);
  const previewGraph = 'sha256:' + '2'.repeat(64);
  const finalGraph = 'sha256:' + '3'.repeat(64);
  const repoId = 'repo:sha256:' + '4'.repeat(64);
  const prevSnap = 'snapshot:sha256:' + '5'.repeat(64);
  const finalSnap = 'snapshot:sha256:' + '6'.repeat(64);

  const prevJob = {
    id: 'job-prev', setup: 'demo', state: 'SUCCEEDED', stage: 'FINISHED', revision: 5, mode: 'FULL',
    elapsedMillis: 12000, submittedAt: date, finishedAt: date, events: [{stage: 'FINISHED', at: date}],
    result: {graph: prevGraph, snapshot: prevSnap, contexts: [prevSnap], gapCount: 0, reports: []}
  };
  let currentJob = {
    id: 'job-live', setup: 'demo', state: 'RUNNING', stage: 'ANALYSIS', revision: 2, mode: 'FULL',
    elapsedMillis: 400, submittedAt: date, events: [{stage: 'QUEUED', at: date}, {stage: 'ANALYSIS', at: date}],
    publicationStage: 'NONE'
  };
  const incremental = {
    mode: 'INCREMENTAL',
    baseSnapshotId: prevSnap,
    baseGraphId: prevGraph,
    baseJobId: 'job-prev',
    addedFiles: 1,
    modifiedFiles: 2,
    deletedFiles: 0,
    reusedFiles: 48,
    abiEarlyCutoffCount: 3,
    impactedDependentsCount: 1,
    reusedModules: 1,
    recomputedModules: 1,
    timeSavedEstimateMs: 9500
  };
  const scoreV2 = {
    graph: finalGraph,
    context: finalSnap,
    scopeMode: 'WHOLE_REPOSITORY',
    formula: {id: 'architecture.score-formula', version: 'v2.0'},
    modelIdentity: 'sha256:' + '7'.repeat(64),
    calibration: 'EMPIRICALLY_GROUNDED_PROVISIONAL',
    status: 'PARTIAL',
    score: 78.4,
    conservativeLowerBound: 68.2,
    uncertaintyDelta: 10.2,
    healthBand: 'Healthy',
    applicableWeight: 100,
    dimensions: [
      {
        id: 'd1.acyclic-integrity', displayName: 'Acyclic Integrity', iso25010Mapping: 'Modularity', weight: 20,
        status: 'COMPLETE', score: 86.0, conservativeLowerBound: 86.0, uncertaintyDelta: 0,
        subMetrics: [{
          id: 'd1.pkg-rc', displayName: 'Package Relative Cyclicity', unit: 'ratio', subWeight: 1,
          status: 'COMPLETE', rawValue: 0.04, score: 86.0,
          curve: {direction: 'LOWER_IS_BETTER', ideal: 0, acceptable: 0.05, poor: 0.2, critical: 0.5},
          explanation: 'Low package-level cycle density.',
          remediationHint: 'Break the single remaining bidirectional package import.',
          witnesses: [{kind: 'TYPE', identity: 'entity:sha256:' + 'a'.repeat(64), label: 'com.demo.BillingService', detail: 'Participates in 2-class SCC', evidence: []}]
        }],
        reasons: [], gaps: []
      },
      {id: 'd2.modularity-cohesion', displayName: 'Modularity & Cohesion', iso25010Mapping: 'Modularity', weight: 18, status: 'COMPLETE', score: 81.5, conservativeLowerBound: 81.5, uncertaintyDelta: 0, subMetrics: [], reasons: [], gaps: []},
      {id: 'd3.coupling-propagation', displayName: 'Coupling & Propagation', iso25010Mapping: 'Modifiability', weight: 16, status: 'COMPLETE', score: 74.0, conservativeLowerBound: 74.0, uncertaintyDelta: 0, subMetrics: [], reasons: [], gaps: []},
      {id: 'd4.hierarchy-direction', displayName: 'Hierarchy & Direction', iso25010Mapping: 'Analysability', weight: 18, status: 'COMPLETE', score: 79.0, conservativeLowerBound: 79.0, uncertaintyDelta: 0, subMetrics: [], reasons: [], gaps: []},
      {id: 'd5.complexity-hotspots', displayName: 'Complexity & Hotspots', iso25010Mapping: 'Testability', weight: 10, status: 'COMPLETE', score: 72.0, conservativeLowerBound: 72.0, uncertaintyDelta: 0, subMetrics: [], reasons: [], gaps: []},
      {id: 'd6.framework-polyglot', displayName: 'Framework & Polyglot', iso25010Mapping: 'Reusability', weight: 10, status: 'COMPLETE', score: 84.0, conservativeLowerBound: 84.0, uncertaintyDelta: 0, subMetrics: [], reasons: [], gaps: []},
      {id: 'd7.policy-conformance', displayName: 'Policy Conformance', iso25010Mapping: 'Compliance', weight: 8, status: 'NOT_APPLICABLE', score: null, conservativeLowerBound: null, uncertaintyDelta: 0, subMetrics: [], reasons: ['NO_CUSTOM_POLICY'], gaps: []}
    ],
    confidence: {
      confidenceScore: 92, tier: 'HIGH', evidenceTier: 'BYTECODE_AND_SOURCE',
      parseCompleteness: 1.0, internalEdgeCompleteness: 0.96, overallSemanticResolution: 0.91, buildCompleteness: 1.0,
      springSpaceDefiniteness: 0.88, openGapCount: 2, gapBuckets: {}, limitations: []
    },
    inferredLayers: {style: 'HEXAGONAL', layerPackages: {domain: ['com.demo.domain'], application: ['com.demo.service'], adapter: ['com.demo.web']}},
    contextBreakdown: [
      {contextId: 'analysis:core', label: 'core / main', kind: 'JAVA', entityCount: 42, status: 'COMPLETE', score: 84.5, conservativeLowerBound: 84.5, confidenceTier: 'HIGH'},
      {contextId: 'analysis:api', label: 'api / main', kind: 'JAVA', entityCount: 18, status: 'COMPLETE', score: 71.0, conservativeLowerBound: 65.0, confidenceTier: 'MODERATE'}
    ],
    policyReport: null, gaps: [], reasons: []
  };

  let sseSeq = 0;
  function broadcastSse(event, payload) {
    sseSeq++;
    const frame = `id: ${sseSeq}\nevent: ${event}\ndata: ${JSON.stringify(payload)}\n\n`;
    for (const res of sseClients) {
      if (!res.writableEnded) res.write(frame);
    }
  }

  let submitted = false;
  const server = createServer(async (req, res) => {
    const route = req.url === '/' ? '/index.html' : req.url;
    try {
      if (!route.startsWith('/api/')) {
        res.setHeader('Content-Type', mime[path.extname(route)] || 'application/octet-stream');
        res.end(await readFile(path.join(directory, route.slice(1))));
        return;
      }
      if (route === '/api/v1/analysis/jobs/job-live/events') {
        res.writeHead(200, {'Content-Type': 'text/event-stream; charset=utf-8', 'Cache-Control': 'no-store'});
        sseClients.push(res);
        broadcastSse('snapshot', {
          jobId: 'job-live', setup: 'demo', state: 'RUNNING', stage: 'ANALYSIS', revision: 2,
          truthTier: 'NONE', previewGraphId: null, overallRatio: 0.28, stageDone: 12, stageTotal: 50,
          filesPerSecond: 42.5, etaSeconds: 80, etaStatus: 'AVAILABLE', elapsedMillis: 400, message: 'Scanning skeleton',
          modules: [{module: 'core', status: 'RUNNING', filesDone: 12, filesTotal: 30, elapsedMillis: 200}, {module: 'api', status: 'PENDING', filesDone: 0, filesTotal: 20, elapsedMillis: 0}]
        });
        req.on('close', () => {
          const i = sseClients.indexOf(res);
          if (i >= 0) sseClients.splice(i, 1);
        });
        return;
      }
      let body = '';
      for await (const chunk of req) body += chunk;
      const input = body ? JSON.parse(body) : null;
      let data;
      if (route === '/api/v1') {
        data = {
          analysisJobsEnabled: true, analysisJobEventsEnabled: true, architectureScoreV2Enabled: true,
          repositoryScoringEnabled: true, repositoryConnectionsEnabled: true, evolutionEnabled: true,
          architectureChatEnabled: true, analysisModes: ['FULL', 'FAST_ARCHITECTURE']
        };
      } else if (route.endsWith('/setups')) data = {items: [{id: 'demo', repository: repoId}]};
      else if (route.endsWith('/connections')) data = {items: [{setup: 'demo', label: 'Demo Shop', kind: 'LOCAL', repository: repoId}]};
      else if (route.endsWith('/history')) data = {items: submitted ? [currentJob, prevJob] : [prevJob]};
      else if (route === '/api/v1/analysis/jobs') { submitted = true; data = currentJob; }
      else if (route === '/api/v1/analysis/jobs/job-live') data = currentJob;
      else if (route === '/api/v1/analysis/jobs/job-live/progress') data = {jobId: 'job-live', state: currentJob.state, stage: currentJob.stage, truthTier: currentJob.publicationStage};
      else if (route === '/api/v1/analysis/jobs/job-live/telemetry') data = {totalElapsedMillis: 2400, stages: [{stage: 'ANALYSIS', durationMillis: 1900}], incremental, cacheMetrics: {frontendCacheHits: 12}};
      else if (route.endsWith('/repository-score')) {
        data = {status: 'COMPLETE', score: 75, checks: [
          {name: 'documentation', weight: 25, state: 'OBSERVED', criterion: 'README.md'},
          {name: 'tests', weight: 25, state: 'OBSERVED', criterion: 'src/test'},
          {name: 'automation', weight: 25, state: 'ABSENT', criterion: '.github/workflows'},
          {name: 'build', weight: 25, state: 'OBSERVED', criterion: 'pom.xml'}
        ]};
      } else if (route === '/api/v1/graphs/' + prevGraph) {
        data = {snapshot: prevSnap, contexts: [{identity: prevSnap, family: 'repository.snapshot'}]};
      } else if (route === '/api/v1/graphs/' + previewGraph) {
        data = {snapshot: finalSnap, contexts: [{identity: finalSnap, family: 'preliminary.context'}]};
      } else if (route === '/api/v1/graphs/' + finalGraph) {
        data = {snapshot: finalSnap, contexts: [
          {identity: finalSnap, family: 'repository.snapshot'},
          {identity: 'analysis:core', family: 'analysis.manifest', modulePath: 'core', sourceSet: 'MAIN', kind: 'java'},
          {identity: 'analysis:api', family: 'analysis.manifest', modulePath: 'api', sourceSet: 'MAIN', kind: 'java'}
        ]};
      } else if (route.endsWith('/architecture-score')) {
        data = scoreV2;
      } else if (route.endsWith('/queries/summary')) {
        data = {status: 'COMPLETE', items: [{nodeCounts: {TYPE: 60, MODULE: 2, SOURCE_DOCUMENT: 51}}]};
      } else if (route.endsWith('/queries/provenance')) {
        data = {items: [{family: 'analysis.manifest', metadata: {modulePath: 'core', sourceSet: 'MAIN'}}]};
      } else if (route === '/api/v1/evolution/jobs') {
        submittedCompare.push(input);
        data = {job: 'sha256:' + '9'.repeat(64), state: 'SUCCEEDED', result: 'sha256:' + '8'.repeat(64)};
      } else if (route.startsWith('/api/v1/evolution/results/')) {
        if (route.endsWith('/query')) data = {section: 'EVENTS', items: [], exactTotal: 0};
        else data = {
          result: 'sha256:' + '8'.repeat(64), status: 'COMPLETE',
          selection: {mode: 'STRICT', selection: {sourceSets: ['MAIN'], relationshipKinds: ['graph.depends-on']}},
          inputs: [
            {side: 'LEFT', status: 'AVAILABLE', graph: prevGraph, snapshot: prevSnap, revision: 'v1'},
            {side: 'RIGHT', status: 'AVAILABLE', graph: finalGraph, snapshot: finalSnap, revision: 'v2'}
          ]
        };
      } else {
        throw new Error('Unexpected mock route: ' + route);
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
  try {
    const outDir = path.resolve(output);
    await mkdir(outDir, {recursive: true});
    page = await edge(browser, outDir);
    const evaluate = expr => page.evaluate(expr);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1320, height: 900, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: `http://127.0.0.1:${server.address().port}`});

    await until('Initial previous job rendered with Architecture Health v2', () =>
      evaluate("document.querySelector('#panel-overview')?.innerText.includes('Architecture Health v2')"));
    assert.ok(await evaluate("document.querySelector('#panel-overview').innerText.includes('Repository Hygiene (4 File Checks)')"));

    // Start new analysis run -> SSE stream opens
    await evaluate("document.getElementById('analyze').click()");
    await until('SSE subscriber connected and live throughput visible', () =>
      evaluate("document.querySelector('#job-live-metrics')?.innerText.includes('42.5 files/s') && document.querySelector('#job-live-metrics')?.innerText.includes('ETA ~1m 20s')"));

    // Emit Stage 1 preview_ready while job is STILL RUNNING
    broadcastSse('preview_ready', {
      jobId: 'job-live', graphId: previewGraph, truthTier: 'PRELIMINARY_STRUCTURE',
      authoritative: false, completedContexts: 0, totalContexts: 2, pendingModules: ['core', 'api']
    });
    await until('Workspace unlocks at Stage 1 with PRELIMINARY STRUCTURE truth banner', () =>
      evaluate("!document.getElementById('analysis-workspace').hidden && document.getElementById('workspace-truth-banner').dataset.tier === 'PRELIMINARY_STRUCTURE' && document.getElementById('workspace-truth-banner').innerText.includes('PRELIMINARY STRUCTURE')"));

    // Verify Policy tab is truthfully disabled while preliminary + 4-stage stepper visible
    assert.equal(await evaluate("document.querySelectorAll('#job-stage-stepper .gh-stage-step').length"), 4);
    await evaluate("document.getElementById('tab-policy').click()");
    await until('Policy mutations disabled during preliminary preview', () =>
      evaluate("document.getElementById('panel-policy').innerText.includes('Policy evaluation & automated repair pending')"));

    // Open inspector dialog to prove non-disruptive Stage 3 atomic swap preserves open dialogs!
    await evaluate("document.getElementById('tab-overview').click()");
    await evaluate("document.getElementById('inspector').showModal()");
    assert.equal(await evaluate("document.getElementById('inspector').open"), true);

    // Emit incremental summary + terminal job_completed
    currentJob = {
      ...currentJob, state: 'SUCCEEDED', stage: 'FINISHED', revision: 6, finishedAt: date,
      publicationStage: 'DEEP_SEMANTIC_COMPLETE', incremental,
      result: {graph: finalGraph, snapshot: finalSnap, contexts: [finalSnap, 'analysis:core', 'analysis:api'], gapCount: 2, reports: []}
    };
    broadcastSse('incremental_summary', {jobId: 'job-live', incremental});
    broadcastSse('job_completed', {jobId: 'job-live', state: 'SUCCEEDED', truthTier: 'DEEP_SEMANTIC_COMPLETE', graphId: finalGraph});

    await until('Atomic promotion to DEEP SEMANTIC COMPLETE while keeping inspector open', () =>
      evaluate("document.getElementById('workspace-truth-banner').dataset.tier === 'DEEP_SEMANTIC_COMPLETE' && document.getElementById('inspector').open === true"));
    await evaluate("document.getElementById('inspector').close()");

    // Verify Architecture Health v2 Hero, Confidence panel, 7 dimensions, context breakdown, and incremental pills
    await until('Architecture Health v2 interval and Analysis Confidence rendered', () =>
      evaluate("document.querySelector('#panel-overview').innerText.includes('68.2–78.4 / 100') && document.querySelector('.evo-confidence-panel')?.innerText.includes('92%')"));
    assert.equal(await evaluate("document.querySelectorAll('.evo-v2-dim-details').length"), 7);
    assert.ok(await evaluate("document.getElementById('job-incremental-strip').innerText.includes('3 ABI early cutoffs')"));

    // Click 1-click Compare with Previous Snapshot -> auto-submits #compare
    await until('Compare with Previous Snapshot button visible', () =>
      evaluate("!document.getElementById('job-compare-prev').hidden"));
    await evaluate("document.getElementById('job-compare-prev').click()");
    await until('Compare tool opened and auto-submitted pinned pair', () =>
      evaluate("!document.getElementById('workbench-tool').hidden && document.getElementById('comparison-result') && !document.getElementById('comparison-result').hidden"));
    assert.equal(submittedCompare.length, 1);
    assert.equal(submittedCompare[0].request.comparison.left, prevGraph);
    assert.equal(submittedCompare[0].request.comparison.right, finalGraph);

    console.log('PASS Progressive SSE, Truthfulness Banner, Atomic Swap, Score v2 Hero, Incremental Telemetry, and 1-Click Evolution Compare');
  } finally {
    for (const c of sseClients) try { c.end(); } catch (_) {}
    await page?.close();
    sockets.forEach(s => s.destroy());
    server.close();
  }
}

main(process.argv.slice(2)).catch(error => { console.error(error); process.exitCode = 1; });
