'use strict';

const assert = require('node:assert/strict');
const {createServer} = require('node:http');
const {readFile} = require('node:fs/promises');
const path = require('node:path');
const {setTimeout: delay} = require('node:timers/promises');
const {edge, until, key} = require('./m9-edge.cjs');

async function main([browser, output]) {
  const directory = path.resolve(__dirname, '../../main/resources/web');
  const mime = {'.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.woff2': 'font/woff2'};
  const sockets = [];
  const calls = [];
  let submission;
  const date = new Date(0).toISOString();
  const graph = 'sha256:' + 'a'.repeat(64);
  const queued = {id: 'job', setup: 'sample', state: 'RUNNING', stage: 'ACQUISITION', revision: 2, mode: 'FAST_ARCHITECTURE',
    elapsedMillis: 200, submittedAt: date, events: [{stage: 'QUEUED', at: date}, {stage: 'ACQUISITION', at: date}]};
  const running = {...queued, stage: 'ANALYSIS', revision: 3, elapsedMillis: 900,
    events: [...queued.events, {stage: 'ANALYSIS', at: new Date(800).toISOString()}]};
  const job = {...running, state: 'PARTIAL', stage: 'FINISHED', revision: 4,
    elapsedMillis: 31, submittedAt: date, finishedAt: date, events: [{stage: 'ANALYSIS', at: date}],
    result: {graph, snapshot: 'snapshot:sample', contexts: ['snapshot:sample', 'analysis:sample'], gapCount: 1, reports: []}};
  let live = queued, polls = 0, blockRenderer = true;
  let releaseType;
  const typeResponse = new Promise(resolve => { releaseType = resolve; });
  const server = createServer(async (req, res) => {
    const route = req.url === '/' ? '/index.html' : req.url;
    calls.push(route);
    try {
      if (!route.startsWith('/api/')) {
        assert.ok(['/index.html', '/styles.css', '/model.js', '/app.js', '/ux.js', '/logo.svg',
          '/bridges.html', '/bridges.css', '/bridge-model.js', '/bridges.js',
          '/vendor/cytoscape-3.30.2.min.js', '/fonts/newsreader-latin.woff2',
          '/fonts/source-sans-3-latin.woff2'].includes(route));
        if (route.includes('cytoscape') && blockRenderer) {
          blockRenderer = false;
          res.writeHead(503);
          res.end();
          return;
        }
        res.setHeader('Content-Type', mime[path.extname(route)]);
        res.setHeader('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:");
        res.end(await readFile(path.join(directory, route.slice(1))));
        return;
      }
      let json = '';
      for await (const chunk of req) json += chunk;
      const input = json ? JSON.parse(json) : null;
      let data;
      if (route === '/api/v1') data = {analysisJobsEnabled: true, analysisModes: ['FULL', 'FAST_ARCHITECTURE'],
        repositoryConnectionsEnabled: true, architectureInsightsEnabled: true, architectureChatEnabled: true, bridgesEnabled: true};
      else if (route.endsWith('/setups')) data = {items: [{id: 'sample'}]};
      else if (route.endsWith('/connections')) data = {items: [{setup: 'sample',
        label: 'AI-native-verified-software-evolution-long-repository-name', kind: 'LOCAL'}]};
      else if (route.endsWith('/history')) data = {items: submission ? [live] : []};
      else if (route.endsWith('/jobs')) { submission = input; data = live; }
      else if (route.endsWith('/jobs/job')) {
        if (++polls === 2 || polls === 3) {
          res.writeHead(503, {'Content-Type': 'application/json'});
          res.end(JSON.stringify({error: {code: 'BUSY'}}));
          return;
        }
        data = live;
      }
      else if (route.endsWith('/telemetry')) data = {totalElapsedMillis: 31, stages: [{stage: 'ANALYSIS', durationMillis: 20}]};
      else if (route.endsWith('/input-diagnostics')) {
        data = {items: [{problem: {reason: 'ROOT_NOT_FOUND'}}]};
      }
      else if (route.endsWith(graph)) {
        data = {snapshot: 'snapshot:sample', contexts: [{identity: 'analysis:sample', analysis: true},
          {identity: 'snapshot:sample', analysis: null}]};
      }
      else if (route.endsWith('/provenance')) data = {items: [{family: 'analysis.manifest', metadata: {modulePath: 'sample'}}]};
      else if (route.endsWith('/summary')) data = {status: 'PARTIAL', items: [{nodeCounts: {TYPE: 2, CAPABILITY_GAP: 1}}]};
      else if (route.endsWith('/visual-graph')) {
        data = input.request.context === 'snapshot:sample'
          ? {nodes: [], edges: [], metadata: {status: 'PARTIAL'}}
          : {nodes: [{id: 'first', label: 'sample.First', kind: 'TYPE'}, {id: 'second', label: 'sample.Second', kind: 'TYPE'}],
            edges: [{id: 'link', source: 'first', target: 'second', classification: 'MUST'}], metadata: {status: 'PARTIAL'}};
      }
      else if (route.endsWith('/relationships')) {
        assert.equal(input.request.context, 'snapshot:sample');
        assert.equal(input.request.scope, 'ENTITY');
        data = {status: 'PARTIAL', items: [{identity: 'link', kind: 'polyglot.service-call.candidate',
          source: 'first', targetState: 'AMBIGUOUS', targets: ['second'], evidence: []}]};
      }
      else if (route.endsWith('/bridges/catalog')) data = {items: [{scope: graph, name: 'Sample capture'}]};
      else if (route.endsWith('/bridges/page')) {
        assert.equal(input.section, 'PARTITIONS');
        data = {items: [{key: graph, fields: {language: 'java', module: 'Sample module', status: 'PARTIAL'}}],
          summary: {partitions: '1', endpoints: '2', gaps: '500', missingCensus: '10', outcomes: {BOUND: '1'}}};
      }
      else if (route.endsWith('/architecture-insights')) {
        const sc = input.request.scope;
        calls.push('insights:' + sc);
        if (sc === 'TYPE') await typeResponse;
        data = {status: 'PARTIAL', operation: 'COMPLETED', summary: {analyzedEntities: sc === 'TYPE' ? 14 : 2,
          internalDependencies: 1, tangleCount: 0, maxBlastRadius: 1}, tangles: [],
          hotspots: [{label: 'First', identity: 'first', hotspotScore: 5, transitiveDependents: 1, fanIn: 1, fanOut: 0}],
          couplingMatrix: {cells: []}, markdownReport: '# Insights'};
      } else throw new Error('Unexpected request ' + route);
      res.setHeader('Content-Type', 'application/json');
      res.end(JSON.stringify({data}));
    } catch (error) {
      res.writeHead(500, {'Content-Type': 'application/json'});
      res.end(JSON.stringify({error: {message: error.message}}));
    }
  });
  server.on('connection', socket => { sockets.push(socket); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  let page;
  try {
    page = await edge(browser, output);
    const evaluate = expression => page.evaluate(expression);
    const click = id => evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 1280, height: 840, deviceScaleFactor: 1, mobile: false});
    await page.send('Page.navigate', {url: `http://127.0.0.1:${server.address().port}`});
    await until('Analysis controls', () => evaluate("document.querySelector('#analyze')?.offsetWidth && !document.querySelector('#analyze').disabled"));
    assert.equal(await evaluate(`(() => {
      const row = document.querySelector('.repository-row'), name = row.querySelector('.repo-name');
      return row.scrollWidth <= row.clientWidth && name.scrollWidth <= name.clientWidth;
    })()`), true);
    await evaluate("document.querySelector('#run-options summary').click()");
    await click('disconnect-repo');
    assert.equal(await evaluate("document.querySelector('#disconnect-dialog').open"), true);
    await key(page, 'Escape', 27);
    assert.equal(await evaluate("document.querySelector('#disconnect-dialog').open"), false);
    await click('sidebar-toggle');
    assert.equal(await evaluate("document.getElementById('sidebar-toggle').getAttribute('aria-expanded')"), 'false');
    await click('sidebar-toggle');
    await evaluate(`(() => {
      const select = document.querySelector('#analysis-mode');
      select.value = 'FAST_ARCHITECTURE';
      select.dispatchEvent(new Event('change'));
    })()`);
    assert.equal(await evaluate("document.querySelector('#analysis-mode-note').hidden"), false);
    await click('analyze');
    await until('Live activity can be opened while running', () => evaluate("document.querySelector('#job-description').textContent.includes('Capturing repository') && !document.querySelector('#run-details').hidden"));
    await evaluate("document.querySelector('#run-details summary').click()");
    await evaluate("document.querySelector('#job-events details[data-stage=ACQUISITION] summary').click()");
    live = running;
    await until('Two failed updates show the last confirmed status', () => evaluate(`(() => {
      const status = document.querySelector('#job-connection');
      return !status.hidden && status.textContent.includes('Last confirmed')
        && document.querySelector('#job-elapsed').textContent.startsWith('≈ ');
    })()`));
    await until('Activity continues after the connection recovers', () => evaluate("Boolean(document.querySelector('#job-events details[data-stage=ANALYSIS]'))"));
    assert.ok(polls > 3);
    assert.equal(await evaluate("document.querySelector('#job-connection').hidden"), true);
    assert.match(await evaluate("document.querySelector('#job-elapsed').textContent"), /^\d+ s$/);
    assert.equal(await evaluate("document.querySelector('#job-events details[data-stage=ACQUISITION]').open"), true);
    live = {...job, events: [...running.events, {stage: 'FINISHED', at: new Date(1000).toISOString()}]};
    await until('Overview published', () => evaluate("document.querySelector('#panel-overview').textContent.includes('Architecture assessment')"));
    assert.equal(calls.some(route => route.endsWith(graph) || route.endsWith('/summary')), false);
    assert.equal(await evaluate("document.querySelector('#run-details').open"), true);
    await evaluate("document.querySelector('#panel-overview .inventory-details summary').click()");
    await until('Architecture counts open on demand', () => evaluate("document.querySelector('#panel-overview .summary-row')?.innerText.includes('Types & references')"));
    assert.doesNotMatch(await evaluate("document.querySelector('#repository-workspace').innerText"), /incomplete evidence|some evidence is missing|limitations|recorded gaps/i);
    assert.equal(await evaluate("Boolean(document.getElementById('tab-gaps'))"), false);
    assert.deepEqual(await evaluate("document.fonts.ready.then(() => [...document.fonts].filter(face => face.status === 'loaded').map(face => face.family))"),
      ['Newsreader', 'Source Sans 3']);
    assert.equal(submission.mode, 'FAST_ARCHITECTURE');
    assert.match(await evaluate("document.querySelector('#job-mode').textContent"), /reduced Java detail/);
    assert.equal(calls.filter(route => route.endsWith('/provenance')).length, 0);
    assert.equal(calls.some(route => route.includes('cytoscape') || route.endsWith('/telemetry') || route.includes('/bridges/')), false);
    await evaluate("document.querySelector('#job-timing summary').click()");
    await until('Timing details', () => evaluate("document.querySelector('#job-telemetry-bar').textContent.includes('20 ms')"));

    await evaluate("document.querySelector('#tab-graph').click()");
    await until('Captured nodes remain visible when the renderer fails to load', () =>
      evaluate("document.querySelector('#panel-graph .graph-node-list')?.innerText.includes('sample.First')"));
    await evaluate("[...document.querySelectorAll('#panel-graph button')].find(button => button.textContent === 'Retry graph').click()");
    await until('Default whole-repository view opens the captured dependency map', () =>
      evaluate("window.EvoApp.state.cy?.edges().length === 1 && document.querySelector('#context').value === 'snapshot:sample'"));
    assert.equal(await evaluate("document.querySelector('[aria-label=\"Graph context\"]').value"), 'analysis:sample');
    await evaluate("document.querySelector('.graph-fullscreen-button').click()");
    await until('Expanded view contains the live renderer', () =>
      evaluate("Boolean(document.querySelector('dialog.map-fullscreen[open] .graph-canvas canvas'))"));
    await key(page, 'Escape', 27);
    await until('Escape restores the same renderer to the page', () =>
      evaluate("Boolean(document.querySelector('#panel-graph > .dependency-map .graph-result > .graph-board canvas')) && !document.querySelector('dialog.map-fullscreen').open"));
    await evaluate("document.querySelector('[aria-label=\"Graph context\"]').value = 'snapshot:sample'; document.querySelector('[aria-label=\"Graph context\"]').dispatchEvent(new Event('change'))");
    await until('An explicitly selected empty scope has a path back to structure', () =>
      evaluate("document.querySelector('#panel-graph .graph-result').innerText.includes('Browse structure')"));
    assert.equal(await evaluate("window.EvoApp.state.cy === null"), true);
    await evaluate("document.querySelector('#tab-graph').click()");
    await until('Return to the default context restores the canvas', () => evaluate("window.EvoApp.state.cy?.edges().length === 1"));
    await evaluate("document.querySelector('#tab-overview').click()");

    assert.deepEqual(await evaluate("[...document.querySelectorAll('#tabs [role=tab]:not([hidden])')].map(tab => tab.id)"),
      ['tab-overview', 'tab-graph', 'tab-policy', 'tab-architecture-chat']);
    assert.equal(await evaluate("document.querySelector('#analysis-tools') === null"), true);
    await evaluate("document.querySelector('#tab-overview').focus()");
    await key(page, 'ArrowRight', 39);
    await until('Keyboard focus moves to Architecture', () => evaluate("document.activeElement.id === 'tab-graph'"));
    await key(page, 'End', 35);
    await until('Keyboard can reach Chat', () => evaluate("document.activeElement.id === 'tab-architecture-chat'"));
    await evaluate("document.querySelector('#tab-policy').click()");
    await evaluate("document.querySelector('[data-open-tab=insights]').click()");
    await until('Initial Insights request', () => calls.includes('insights:TYPE'));
    await evaluate(`(() => {
      const select = document.querySelector('[aria-label="Insights scope"]');
      select.value = 'PACKAGE';
      select.dispatchEvent(new Event('change'));
    })()`);
    await until('Selected Insights result', () => evaluate("document.querySelector('#panel-insights .stat strong')?.textContent === '2'"));
    assert.doesNotMatch(await evaluate("document.querySelector('#panel-insights').innerText"), /limited result|view limitations/i);
    releaseType();
    await delay(60);
    assert.equal(await evaluate("document.querySelector('#panel-insights .stat strong').textContent"), '2');

    await page.send('Emulation.setDeviceMetricsOverride', {width: 375, height: 800, deviceScaleFactor: 1, mobile: true});
    await click('sidebar-toggle');
    assert.equal(await evaluate("document.querySelector('#sidebar-toggle').getAttribute('aria-expanded')"), 'true');
    assert.equal(await evaluate("document.querySelector('#sidebar').contains(document.activeElement)"), true);
    await key(page, 'Escape', 27);
    assert.equal(await evaluate("document.querySelector('#sidebar-toggle').getAttribute('aria-expanded')"), 'false');
    assert.equal(await evaluate('document.documentElement.scrollWidth <= window.innerWidth'), true);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 320, height: 568, deviceScaleFactor: 1, mobile: true});
    assert.equal(await evaluate("document.querySelector('#main').scrollWidth <= document.querySelector('#main').clientWidth"), true);
    assert.equal(await evaluate(`(() => {
      const {left, right, width} = document.querySelector('#review-views .view-links').getBoundingClientRect();
      return width > 0 && left >= 0 && right <= innerWidth;
    })()`), true);
    await evaluate("document.querySelector('#panel-insights table button').click()");
    await until('Graph rendered on demand', () => evaluate("Boolean(window.EvoApp.state.cy && !document.querySelector('#panel-graph').hidden)"));
    assert.equal(calls.filter(route => route.includes('cytoscape')).length, 2);
    assert.equal(await evaluate("document.querySelector('#panel-graph .graph-board').getBoundingClientRect().right <= innerWidth"), true);
    await evaluate("document.querySelector('#view-connections').click()");
    await until('Connection candidates in Dependencies', () => evaluate("document.querySelector('#panel-connections .entity-row')?.textContent.includes('HTTP route candidate')"));
    assert.equal(calls.some(route => route.includes('/bridges/')), false);
    await evaluate("document.querySelector('#panel-connections [href=\"#bridge-evidence\"]').click()");
    await until('Bridge catalog in the same shell', () => evaluate("!document.querySelector('#workbench-tool').hidden && document.querySelector('#bridge-rows')?.innerText.includes('Sample module')"));
    assert.doesNotMatch(await evaluate("document.querySelector('#workbench-tool').innerText"), /partial|gaps|untracked|\b500\b/i);
    await evaluate("document.querySelector('#workbench-tool > a').click()");
    await until('Return to Connections', () => evaluate("!document.querySelector('#repository-workspace').hidden && !document.querySelector('#panel-connections').hidden"));

    live = {...queued, id: 'failed', state: 'FAILED', stage: 'FINISHED', error: 'ANALYSIS_FAILED',
      elapsedMillis: 800, finishedAt: new Date(800).toISOString(), result: {inputProblemCount: 1}};
    await click('analyze');
    await until('Acquisition failure explains how to recover', () =>
      evaluate("document.querySelector('#job-failure').innerText.includes('Reconnect using its current path')"));
    assert.equal(await evaluate("document.getElementById('analyze').disabled"), false);
    console.log('PASS Workbench live activity, retry recovery, immediate overview, Insights, Connections, and failure diagnostics');
  } finally {
    releaseType();
    await page?.close();
    sockets.forEach(socket => socket.destroy());
    server.close();
  }
}

main(process.argv.slice(2)).catch(error => { console.error(error); process.exitCode = 1; });
