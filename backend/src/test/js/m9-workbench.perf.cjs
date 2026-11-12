'use strict';

const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const {createReadStream} = require('node:fs');
const fs = require('node:fs/promises');
const path = require('node:path');
const {edge, startServer, until} = require('./m9-edge.cjs');

const WARMUP = 20;
const SAMPLES = 100;
const PROFILES = [[1440, 1000], [390, 844], [320, 844]];
const UI = ['search', 'focus', 'metric', 'finding', 'impact'];
const api = '/api/v1';
const request = (url, body) => ({url: api + url, body});

async function json(origin, {url, body}) {
  const response = await fetch(origin + url, body ? {
    method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body),
    signal: AbortSignal.timeout(5000)
  } : {signal: AbortSignal.timeout(5000)});
  assert.equal(response.status, 200, url);
  return (await response.json()).data;
}

async function workload(origin) {
  const history = await json(origin, request('/analysis/jobs/history', {size: 50}));
  const job = history.items.find(row => row.result?.graph && row.result.reports.length);
  assert.ok(job, 'Seed requires a completed authored job with published reports');
  const graph = job.result.graph;
  const prefix = '/graphs/' + graph;
  const discovery = await json(origin, request(prefix));
  const reports = [];
  for (const id of discovery.reports) reports.push(await json(origin, request(prefix + '/reports/' + id)));
  const report = reports.find(row => row.findingCount && row.hasAssessment);
  assert.ok(report, 'Seed requires an authored finding and assessment');
  const context = report.context;
  const base = {context, scope: 'TYPE', projection: 'BOUNDED_UNION', direction: 'OUTGOING',
    relationshipKinds: ['graph.depends-on']};
  const query = (operation, args, page, options) =>
    request(prefix + '/queries/' + operation, {request: {...base, ...options}, arguments: args, ...(page ? {page} : {})});
  const reportQuery = (operation, args = {}, page) =>
    request(prefix + '/reports/' + report.identity + '/' + operation, {arguments: args, ...(page ? {page} : {})});
  const search = query('entities', {search: 'Client'}, {size: 20}, {population: {nodeKinds: ['TYPE']}});
  const client = (await json(origin, search)).items.find(row => row.qualifiedName === 'example.Client');
  const store = (await json(origin, query('entities', {search: 'Store'}, {size: 20}))).items
    .find(row => row.qualifiedName === 'example.Store');
  assert.ok(client && store);
  const findings = reportQuery('findings', {}, {size: 20});
  const finding = (await json(origin, findings)).items[0];
  const partitions = request('/graph-pages/' + graph + '/partitions', {size: 20});
  const partition = (await json(origin, partitions)).page.items.find(row => row.context === context);
  const first = request('/graph-pages/' + graph + '/nodes', {size: 1, partition});
  const cursor = (await json(origin, first)).page.continuation;
  assert.ok(cursor, 'Raw paging seed must contain at least two rows');
  return {graph, context, report: report.identity, setup: job.setup, operations: {
    search: [search],
    children: [query('children', {identity: client.identity}, {size: 20}, {scope: 'ENTITY'})],
    focus: [query('visual-graph', {seed: client.identity, depth: 2})],
    path: [query('shortest-path', {source: client.identity, target: store.identity})],
    cycles: [query('cycles', {})],
    metric: [query('metric', {metric: 'metric.density', subject: context})],
    penalty: [reportQuery('assessment'), findings],
    evidence: [reportQuery('bundle', {identity: finding.bundleIdentity}),
      query('evidence', {reference: finding.result.bundle.sources[0].evidence})],
    impact: [query('impact', {identity: store.identity}, undefined, {direction: 'INCOMING'})],
    raw: [partitions, first, request(first.url.slice(api.length), {...first.body, cursor})]
  }};
}

function apiTrials(steps, rounds, byteLimit) {
  return (async () => {
    const observations = [];
    for (let trial = 0; trial < rounds; trial++) {
      const started = performance.now();
      const requests = [];
      for (const {url, body} of steps) {
        const before = performance.now();
        try {
          const response = await fetch(url, {
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body),
            signal: AbortSignal.timeout(5000)
          });
          const raw = await response.text();
          const received = performance.now();
          const envelope = JSON.parse(raw);
          const size = new TextEncoder().encode(raw).length;
          const data = envelope.data;
          const items = data?.page?.items ?? data?.items;
          requests.push({http: response.status, bytes: size, fetchMs: received - before,
            decodeMs: performance.now() - received, resultIdentity: envelope.resultIdentity,
            rows: items?.length, continuation: Boolean(data?.page?.continuation ?? data?.continuation),
            failed: !response.ok || Boolean(envelope.error) || size > byteLimit ||
              (body.page?.size != null && items?.length > body.page.size) ||
              (body.size != null && items?.length > body.size)});
        } catch (error) {
          requests.push({failed: true, error: error.name, elapsedMs: performance.now() - before});
        }
      }
      observations.push({wallMs: performance.now() - started, requests});
    }
    return observations;
  })();
}

function stats(rows) {
  const times = rows.map(row => row.wallMs).sort((a, b) => a - b);
  return {count: rows.length, medianMs: (times[49] + times[50]) / 2, p95Ms: times[94],
    minMs: times[0], maxMs: times.at(-1), failures: rows.filter(row => row.requests.some(r => r.failed)).length,
    maxBytes: Math.max(0, ...rows.flatMap(row => row.requests.map(r => r.bytes || 0)))};
}

async function uiTrials(operation, rounds) {
  const get = selector => document.querySelector(selector);
  const press = selector => get(selector).click();
  const button = (selector, label) => [...document.querySelectorAll(selector)]
    .find(node => node.textContent.trim() === label);
  const paint = () => new Promise(resolve => requestAnimationFrame(resolve));
  const ready = async (predicate) => {
    const deadline = performance.now() + 5000;
    while (!predicate()) {
      if (performance.now() > deadline) throw new Error('View timed out');
      await paint();
    }
  };
  async function tab(name) {
    press('#tab-' + name);
    await ready(() => get('#panel-' + name).getAttribute('aria-busy') === 'false' && !get('#panel-' + name).hidden);
  }
  async function inspect() {
    await ready(() => get('#inspector').open && !get('#download-details').disabled);
    press('#inspector [data-close]');
  }
  const actions = {
    search: async () => {
      get('#search').value = 'Client';
      press('#search-form button');
      await ready(() => get('#panel-structure .entity-row')?.textContent.includes('example.Client'));
    },
    focus: async () => {
      await tab('graph');
      await ready(() => get('#panel-graph [aria-label="Graph node"]'));
      const scope = get('#panel-graph [aria-label="Graph scope"]');
      scope.value = 'TYPE';
      scope.dispatchEvent(new Event('change'));
      await ready(() => get('#panel-graph [aria-label="Graph node"]'));
      button('#panel-graph button', 'Focus & expand').click();
      await ready(() => get('#panel-graph .graph-canvas'));
    },
    metric: async () => {
      await tab('metrics');
      button('#panel-metrics button', 'Inputs, distribution & evidence').click();
      await inspect();
    },
    finding: async () => {
      await tab('policy');
      press('#panel-policy .entity-row');
      await ready(() => button('#inspector-body button', 'Exact source evidence'));
      button('#inspector-body button', 'Exact source evidence').click();
      await ready(() => get('#inspector-title').textContent === 'Finding sources');
      await inspect();
    },
    impact: async () => {
      await tab('impact');
      get('[aria-label="Symbol for impact analysis"]').value = 'Store';
      press('#panel-impact form button');
      await ready(() => get('#panel-impact .entity-row'));
      press('#panel-impact .entity-row');
      await inspect();
    }
  };
  const rows = [];
  for (let index = 0; index < rounds; index++) {
    performance.clearResourceTimings();
    const started = performance.now();
    let error;
    try {
      await actions[operation]();
      await paint();
      await paint();
    } catch (failure) {
      error = failure.message;
      if (get('#inspector')?.open) press('#inspector [data-close]');
    }
    const end = performance.now();
    const resources = performance.getEntriesByType('resource').filter(entry => entry.startTime >= started
      && new URL(entry.name).pathname.startsWith('/api/v1/'));
    const requests = resources.map(entry => ({fetchMs: entry.responseEnd - entry.startTime,
      http: entry.responseStatus, bytes: entry.decodedBodySize,
      failed: entry.responseStatus >= 400 || entry.decodedBodySize > 2_097_152}));
    if (error) requests.push({failed: true, error});
    const postResponseMs = resources.length ? end - Math.max(...resources.map(entry => entry.responseEnd)) : undefined;
    rows.push({wallMs: end - started, postResponseMs, requests});
  }
  return rows;
}

async function sha(file) {
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(file)) hash.update(bytes);
  return hash.digest('hex');
}

async function main([java, reference, candidate, browser, seed, output]) {
  const root = path.resolve(__dirname, '../../../..');
  assert.ok(path.isAbsolute(output) && path.relative(root, output).startsWith('..'), 'Use a new external output directory');
  const only = process.env.M9_TIMING_CELL;
  const [viewport, operation] = only ? only.split('/') : [];
  const profiles = PROFILES.filter(([width]) => !only || String(width) === viewport);
  assert.ok(profiles.length && (!only || operation), 'Use M9_TIMING_CELL as viewport/operation');
  await fs.mkdir(output);
  const pins = {};
  for (const file of [java, reference, candidate, browser, process.execPath, __filename, path.join(__dirname, 'm9-edge.cjs')]) {
    pins[file] = await sha(file);
  }
  await fs.writeFile(path.join(output, 'registration.json'), JSON.stringify({pins, seed, profiles, operation,
    warmup: WARMUP, samples: SAMPLES, responseByteLimit: 2_097_152}, null, 2), {flag: 'wx'});
  let original;
  const cells = new Map();
  for (const [role, jar] of [['baseline', reference], ['candidate', candidate]]) {
    for (const [width, height] of profiles) {
      const run = path.join(output, role + '-' + width);
      await fs.mkdir(run);
      const store = path.join(run, 'store');
      await fs.cp(path.join(seed, 'store'), store, {recursive: true, errorOnExist: true, force: false});
      const source = path.join(path.dirname(seed), 'missing-policy');
      const cache = path.join(run, 'empty-artifact-cache');
      await fs.mkdir(cache);
      const server = await startServer(java, ['-Xmx2g', '-cp', jar, 'com.evolution.backend.analysis.WorkbenchApiMain',
        source, 'urn:evo:m9.4:missing', path.dirname(path.dirname(java)), '21', cache, store, '0'], run);
      let page;
      try {
        const plan = await workload(server.origin);
        if (!original) {
          original = plan;
          await fs.writeFile(path.join(output, 'workload.json'), JSON.stringify(plan, null, 2), {flag: 'wx'});
        } else assert.deepEqual(plan, original, 'Baseline and candidate must query the same stored graph and operands');
        page = await edge(browser, run);
        await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: width < 760});
        await page.send('Page.navigate', {url: server.origin});
        await until('Browser document', () => page.evaluate("document.readyState === 'complete' && !!window.EvoModel"));
        await fs.writeFile(path.join(run, 'browser.txt'), page.version, {flag: 'wx'});
        async function series(id, measure) {
          const warmup = await measure(WARMUP);
          const rows = [];
          for (let batch = 0; batch < SAMPLES / 20; batch++) rows.push(...await measure(20));
          await fs.writeFile(path.join(run, id + '.jsonl'), [...warmup, ...rows]
            .map((row, index) => JSON.stringify({warmup: index < WARMUP, ...row})).join('\n') + '\n', {flag: 'wx'});
          const cell = {...stats(rows), warmupFailures: warmup.filter(row => row.requests.some(r => r.failed)).length};
          if (!id.startsWith('ui-')) {
            cell.resultSequences = [...new Set(rows.map(row => row.requests.map(r => r.resultIdentity).join(',')))];
          }
          const key = width + '/' + id;
          if (!cells.has(key)) cells.set(key, {});
          cells.get(key)[role] = cell;
          console.log(role, key, JSON.stringify(cell));
        }
        assert.ok(!only || plan.operations[operation] || UI.some(id => operation === 'ui-' + id), 'Unknown operation');
        for (const [id, steps] of Object.entries(plan.operations)) {
          if (only && operation !== id) continue;
          await series(id, rounds => page.evaluate('(' + apiTrials.toString() + ')(' +
            JSON.stringify(steps) + ',' + rounds + ',2097152)'));
        }
        if (only && !operation.startsWith('ui-')) continue;
        await until('Repository choices', () => page.evaluate("document.querySelectorAll('#repositories button').length >= 2"));
        await page.evaluate("document.querySelector('#repositories button:nth-of-type(2)').click()");
        await until('Captured context selector', () => page.evaluate("!document.querySelector('#analysis-workspace').hidden"));
        await page.evaluate('(' + (context => {
          const select = document.querySelector('#context');
          select.value = context;
          select.dispatchEvent(new Event('change', {bubbles: true}));
        }).toString() + ')(' + JSON.stringify(plan.context) + ')');
        for (const id of UI) {
          if (only && operation !== 'ui-' + id) continue;
          await series('ui-' + id, rounds => page.evaluate('(' + uiTrials.toString() + ')(' +
            JSON.stringify(id) + ',' + rounds + ')'));
        }
      } finally {
        await page?.close();
        server.close();
      }
    }
  }
  const comparison = [...cells].map(([key, {baseline, candidate}]) => {
    const ratio = candidate.medianMs / baseline.medianMs;
    const identicalResults = baseline.resultSequences && JSON.stringify(baseline.resultSequences) === JSON.stringify(candidate.resultSequences);
    const pass = ratio <= 2 && candidate.failures === 0 && candidate.warmupFailures === 0 &&
      baseline.failures === 0 && baseline.warmupFailures === 0;
    return {key, baseline, candidate, ratio, identicalResults, pass};
  });
  await fs.writeFile(path.join(output, 'comparison.json'), JSON.stringify(comparison, null, 2), {flag: 'wx'});
  process.exitCode = comparison.every(cell => cell.pass) ? 0 : 1;
}

main(process.argv.slice(2)).catch(error => {
  console.error(error.message);
  process.exitCode = 2;
});
