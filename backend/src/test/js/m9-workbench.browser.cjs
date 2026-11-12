'use strict';

const assert = require('node:assert/strict');
const {createHash} = require('node:crypto');
const {createReadStream} = require('node:fs');
const fs = require('node:fs/promises');
const path = require('node:path');
const {edge, startServer, until, key} = require('./m9-edge.cjs');

const PROFILES = [
  ['desktop', 1440, 1000], ['mobile', 390, 844], ['reflow', 320, 844]
];
const EXPLORE = 'com.evolution.backend.analysis.WorkbenchApiMain';
const CAPTURE = 'com.evolution.backend.query.CapturedWorkbenchAcceptance';
const RULES = [
  'schema: architecture-policy:v1', 'id: fixture.architecture', 'version: "1"', 'rules:',
  '  - {id: fixture.boundary, version: "1", kind: FORBIDDEN_DEPENDENCY, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], from: {names: [example.Client]}, to: {names: [example.Store]}}',
  '  - {id: fixture.cycles, version: "1", kind: ACYCLIC, severity: ERROR, scope: TYPE, relationships: [graph.depends-on], within: {origins: [PROJECT]}}',
  ''
].join('\n');

const query = (page, selector, expression) =>
  page.evaluate('((node) => node && (' + expression + '))(document.querySelector(' + JSON.stringify(selector) + '))');
const visible = (page, selector) => query(page, selector, 'node.getClientRects().length > 0 && !node.disabled');
const text = (page, selector) => query(page, selector, 'node.textContent');
const hasText = (page, selector, pattern) => until(selector + ' ' + pattern, async () =>
  pattern.test(await text(page, selector) || ''));
const click = (page, selector) => clickText(page, selector);

async function clickText(page, selector, exact) {
  const point = await until('Clickable ' + selector + ' ' + (exact || ''), () => page.evaluate(
    '(' + ((selector, exact) => {
      const target = [...document.querySelectorAll(selector)].find(node =>
        node.getClientRects().length && !node.disabled && (!exact || node.textContent.trim() === exact));
      if (!target) return null;
      target.scrollIntoView({block: 'center', inline: 'nearest'});
      const bounds = target.getBoundingClientRect();
      const x = Math.round(bounds.left + bounds.width / 2), y = Math.round(bounds.top + bounds.height / 2);
      return target.contains(document.elementFromPoint(x, y)) ? {x, y} : null;
    }).toString() + ')(' + JSON.stringify(selector) + ',' + JSON.stringify(exact ?? null) + ')'
  ));
  for (const type of ['mousePressed', 'mouseReleased']) {
    await page.send('Input.dispatchMouseEvent', {type, ...point, button: 'left', clickCount: 1});
  }
}

async function setValue(page, selector, value) {
  await until('Field ' + selector, () => visible(page, selector));
  await page.evaluate('(' + ((selector, value) => {
    const node = document.querySelector(selector);
    node.value = value;
    node.dispatchEvent(new Event('change', {bubbles: true}));
  }).toString() + ')(' + JSON.stringify(selector) + ',' + JSON.stringify(value) + ')');
}

async function choose(page, selector, index = 1) {
  const value = await until('Options ' + selector, () => query(page, selector, 'node.options[' + index + ']?.value'));
  await setValue(page, selector, value);
  return value;
}

async function open(page, origin) {
  await page.send('Page.navigate', {url: origin});
  await hasText(page, '#connection-status', /^Local server connected$/);
}

async function tab(page, name) {
  await click(page, '#tab-' + name);
  await until(name + ' view ready', () => query(page, '#panel-' + name,
    "!node.hidden && node.getAttribute('aria-busy') === 'false'"));
}

async function inspector(page, title) {
  await hasText(page, '#inspector-title', title);
  await until('Inspector data', () => visible(page, '#inspector[open] #download-details'));
}

async function inspectClose(page) {
  await key(page, 'Escape', 27);
  await until('Inspector closed', () => page.evaluate("!document.querySelector('#inspector').open"));
}

async function sidebar(page, profile, expanded) {
  if (profile === 'desktop') return;
  const actual = await query(page, '.app-shell', "node.classList.contains('mobile-sidebar-open')");
  if (actual !== expanded) await click(page, '#sidebar-toggle');
}

async function repository(page, profile, index) {
  await sidebar(page, profile, true);
  await click(page, '#repositories button:nth-of-type(' + index + ')');
}

async function waitForResult(page) {
  await until('Published analysis result', () => visible(page, '#analysis-workspace #context'));
  return choose(page, '#context');
}

async function lifecycle(page, profile, source) {
  await sidebar(page, profile, true);
  await click(page, '.sidebar [data-action=connect]');
  await setValue(page, '#repository-source', source);
  await click(page, '#policy-settings summary');
  await setValue(page, '#repository-policy', RULES);
  await click(page, '#connect-submit');
  await hasText(page, '#repository-title', /authored-source/);
  await click(page, '#analyze');
  await hasText(page, '#job-state', /^(QUEUED|RUNNING)$/);
  await click(page, '#cancel-job');
  await hasText(page, '#job-state', /^CANCELLED$/);
  assert.equal(await visible(page, '#analysis-workspace #context'), false);
  await click(page, '#analyze');
  await waitForResult(page);
  await hasText(page, '#job-state', /^(SUCCEEDED|PARTIAL)$/);
  await sidebar(page, profile, true);
  const cancelled = await until('Cancelled history entry', () => page.evaluate(
    "[...document.querySelectorAll('#history button')].findIndex(node => node.textContent.includes('CANCELLED')) + 1"
  ));
  await click(page, '#history button:nth-of-type(' + cancelled + ')');
  await hasText(page, '#job-state', /^CANCELLED$/);
  assert.equal(await visible(page, '#analysis-workspace #context'), false);
  await click(page, '#history button:first-of-type');
  await waitForResult(page);
  await sidebar(page, profile, false);
}

async function overview(page) {
  await tab(page, 'overview');
  await hasText(page, '#panel-overview', /Architecture assessment/);
  await until('Evidence tier availability', () => visible(page, '#job-evidence-tier'));
  await click(page, '#job-receipt');
  await inspector(page, /Analysis receipt/);
  const raw = await text(page, '#inspector-body pre');
  const receipt = JSON.parse(raw);
  assert.ok(receipt.result.graph.startsWith('sha256:'));
  assert.ok(receipt.result.gapCount >= Math.max(receipt.result.graphGapCount, receipt.result.jobGapCount));
  assert.ok(receipt.result.gapCount <= receipt.result.graphGapCount + receipt.result.jobGapCount);
  await inspectClose(page);
  assert.equal(await page.evaluate('document.activeElement.id'), 'job-receipt');
  await tab(page, 'gaps');
  await hasText(page, '#panel-gaps', /limitations|gap/i);
}

async function evidence(page, downloads) {
  await tab(page, 'metrics');
  await hasText(page, '#panel-metrics', /Architecture metrics/);
  await tab(page, 'policy');
  await clickText(page, '#panel-policy button.entity-row strong', 'fixture.boundary');
  await inspector(page, /fixture.boundary/);
  await clickText(page, '#inspector-body button', 'Finding path');
  await inspector(page, /Finding path/);
  assert.equal(JSON.parse(await text(page, '#inspector-body pre')).items[0].vertices.length, 2);
  await inspectClose(page);
  await clickText(page, '#panel-policy button.entity-row strong', 'fixture.boundary');
  await clickText(page, '#inspector-body button', 'Exact source evidence');
  await inspector(page, /Finding sources/);
  const source = JSON.parse(await text(page, '#inspector-body pre')).items[0];
  assert.equal(source.span.startLine, 1);
  assert.equal(source.span.startColumn, 33);
  assert.equal(source.span.endColumn, 38);
  assert.match(source.sourceDigest, /^sha256:[0-9a-f]{64}$/);
  await click(page, '#download-details');
  const exported = await until('Evidence JSON download', async () =>
    (await fs.readdir(downloads)).find(name => /^evo-evidence.*\.json$/.test(name)));
  assert.deepEqual(JSON.parse(await fs.readFile(path.join(downloads, exported), 'utf8')).items[0], source);
  await inspectClose(page);
  await click(page, '#provenance');
  await inspector(page, /Analysis provenance/);
  assert.ok(JSON.parse(await text(page, '#inspector-body pre')).items.length);
  await inspectClose(page);
}

async function keyboardAndProjection(page, width) {
  await tab(page, 'overview');
  await page.evaluate("document.querySelector('#tab-overview').focus()");
  await key(page, 'ArrowRight', 39);
  assert.equal(await page.evaluate('document.activeElement.id'), 'tab-structure');
  assert.ok(await page.evaluate("document.activeElement.matches(':focus-visible')"));
  await key(page, 'Enter', 13, '\r');
  await until('Keyboard activates Structure', () => query(page, '#tab-structure', "node.getAttribute('aria-selected') === 'true'"));
  await key(page, '/', 191, '/');
  assert.equal(await page.evaluate('document.activeElement.id'), 'search');
  await setValue(page, '#search', 'Client');
  await key(page, 'Enter', 13, '\r');
  await hasText(page, '#panel-structure', /example\.Client/);
  await tab(page, 'graph');
  await setValue(page, '#panel-graph select[aria-label="Graph scope"]', 'TYPE');
  const nodes = await until('Captured project nodes', async () => {
    const options = await query(page, '[aria-label="Graph node"]', '[...node.options].map(option => option.textContent)');
    return options?.some(name => name.includes('Client')) && options.some(name => name.includes('Store')) && options;
  });
  assert.ok(nodes.length <= 1500);
  await clickText(page, '#panel-graph button', 'Focus & expand');
  await until('Neighborhood depth control', () => visible(page, '[aria-label="Neighborhood depth"]'));
  await tab(page, 'impact');
  await setValue(page, '[aria-label="Symbol for impact analysis"]', 'Store');
  await clickText(page, '#panel-impact button', 'Find symbol');
  await clickText(page, '#panel-impact button.entity-row strong', 'example.Store');
  await inspector(page, /example\.Store/);
  await inspectClose(page);
  const overflow = await page.evaluate('document.documentElement.scrollWidth - document.documentElement.clientWidth');
  assert.ok(overflow <= 1, 'Horizontal document overflow at ' + width + 'px: ' + overflow + 'px');
  const accessibility = await page.send('Accessibility.getFullAXTree');
  assert.ok(accessibility.nodes.some(node => node.role?.value === 'tab' && node.name?.value === 'Change impact'));
  for (const name of ['Architecture Workbench', 'Polyglot Bridges']) {
    assert.ok(accessibility.nodes.some(node => node.role?.value === 'link' && node.name?.value === name));
  }
  for (const theme of ['dark', 'light']) {
    await until(theme + ' theme', () => page.evaluate('document.documentElement.dataset.theme === ' + JSON.stringify(theme)));
    await page.evaluate("Promise.allSettled(document.querySelector('#analyze').getAnimations().map(animation => animation.finished))");
    const ratios = await page.evaluate('(' + (() => {
      const rgba = value => value.match(/[\d.]+/g).map(Number);
      const luminance = rgb => rgb.slice(0, 3).map(component => component / 255)
        .map(channel => channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4)
        .reduce((sum, channel, index) => sum + channel * [0.2126, 0.7152, 0.0722][index], 0);
      function background(node) {
        if (!node) return [255, 255, 255];
        const color = rgba(getComputedStyle(node).backgroundColor);
        const alpha = color[3] ?? 1;
        if (alpha === 1) return color;
        const under = background(node.parentElement);
        return color.slice(0, 3).map((component, index) => component * alpha + under[index] * (1 - alpha));
      }
      return ['#analyze', '#job-state', '#job-description'].map(selector => {
        const node = document.querySelector(selector);
        const foreground = luminance(rgba(getComputedStyle(node).color));
        const surface = luminance(background(node));
        return (Math.max(foreground, surface) + 0.05) / (Math.min(foreground, surface) + 0.05);
      });
    }).toString() + ')()');
    assert.ok(ratios.every(ratio => ratio >= 4.5), theme + ' text contrast: ' + ratios.join(', '));
    await click(page, '#theme-toggle');
  }
}

async function degradedAndStale(page, profile) {
  // Keep the final real report response pending across a refresh of the same job.
  await click(page, '#job-receipt');
  await inspector(page, /Analysis receipt/);
  const reports = JSON.parse(await text(page, '#inspector-body pre')).result.reports;
  assert.ok(reports.length);
  await inspectClose(page);
  const suffix = '/reports/' + reports.at(-1);
  await page.evaluate('(' + (suffix => {
    const original = window.fetch;
    window.fetch = async (...args) => {
      const response = await original(...args);
      if (args[0].endsWith(suffix) && args[1].method === 'GET') {
        window.fetch = original;
        const payload = await response.json();
        await new Promise(resolve => { window.releaseOldDiscovery = resolve; });
        return {ok: response.ok, json: async () => payload};
      }
      return response;
    };
  }).toString() + ')(' + JSON.stringify(suffix) + ')');
  await sidebar(page, profile, true);
  await click(page, '#history button:first-of-type');
  await until('Delayed discovery response', () => page.evaluate('typeof window.releaseOldDiscovery === "function"'));
  await click(page, '[data-action=refresh]');
  await sidebar(page, profile, false);
  const context = await waitForResult(page);
  await tab(page, 'overview');
  await page.evaluate('window.releaseOldDiscovery()');
  await page.evaluate('new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))');
  assert.equal(await query(page, '#context', 'node.value'), context, 'A delayed discovery cleared the current context');
  await tab(page, 'policy');
  // Control an ordinary transient failure without changing the captured graph.
  await page.evaluate('(' + (() => {
    const original = window.fetch;
    window.fetch = (...args) => {
      if (args[0].endsWith('/queries/summary')) {
        window.fetch = original;
        return Promise.resolve(new Response('{"error":{"code":"QUERY_FAILED"}}', {status: 503}));
      }
      return original(...args);
    };
  }).toString() + ')()');
  await tab(page, 'overview');
  await hasText(page, '#panel-overview', /Unable to load this view/);
  await clickText(page, '#panel-overview button', 'Retry');
  await hasText(page, '#panel-overview', /Architecture assessment/);

  await repository(page, profile, 1);
  await hasText(page, '#repository-title', /missing-policy/);
  if (profile !== 'desktop') {
    assert.equal(await query(page, '#sidebar-toggle', "node.getAttribute('aria-expanded')"), 'false');
  }
  await click(page, '#analyze');
  await waitForResult(page);
  await tab(page, 'policy');
  await hasText(page, '#panel-policy', /No policy report for this context/);
  await setValue(page, '#search', 'DefinitelyAbsentInThisFixture');
  await click(page, '#search-form button');
  await hasText(page, '#panel-structure', /No matching contents/);
  await repository(page, profile, 2);
}

async function configuration(page, origin) {
  await open(page, origin);
  await click(page, '#analyze');
  const context = await until('Spring context from provenance', () => query(page, '#context',
    "[...node.options].find(option => option.textContent.includes('Spring'))?.value"));
  await setValue(page, '#context', context);
  await tab(page, 'configurations');
  const a = '#panel-configurations [aria-label="View A"]';
  await choose(page, a, 4);
  await setValue(page, '#panel-configurations [aria-label="Comparison scope"]', 'SPRING_COMPONENT');
  await clickText(page, '#panel-configurations button', 'Compare views');
  await hasText(page, '#panel-configurations .comparison-summary', /baseline dev:/);
  await choose(page, a, 2);
  await clickText(page, '#panel-configurations button', 'Compare views');
  await hasText(page, '#panel-configurations .comparison-summary', /Uncertain dependencies/);
  await tab(page, 'policy');
  await click(page, '#panel-policy button.entity-row');
  await clickText(page, '#inspector-body button', 'Replay finding witness');
  await inspector(page, /Replay finding witness/);
  const replay = JSON.parse(await text(page, '#inspector-body pre')).items[0];
  assert.equal(replay.matched, true);
  assert.equal(replay.formulaReplayed, true);
  assert.equal(replay.semanticReplayed, false);
  await inspectClose(page);
}

async function sha(file) {
  const hash = createHash('sha256');
  for await (const bytes of createReadStream(file)) hash.update(bytes);
  return hash.digest('hex');
}

async function main([java, jar, executable, output, springClasses, springGraph, springPolicy]) {
  assert.ok(path.isAbsolute(output) && path.relative(path.resolve(__dirname, '../../../..'), output).startsWith('..'),
    'Use a new external output directory');
  await fs.mkdir(output);
  const source = path.join(output, 'authored-source');
  const missingPolicy = path.join(output, 'missing-policy');
  const policy = path.join(output, 'policy.yaml');
  const artifacts = path.join(output, 'empty-artifact-cache');
  await fs.mkdir(source);
  await fs.mkdir(missingPolicy);
  await fs.mkdir(artifacts);
  await fs.writeFile(path.join(source, 'Client.java'), 'package example; class Client { Store store; }\n');
  await fs.writeFile(path.join(source, 'Store.java'), 'package example; class Store {}\n');
  await fs.writeFile(path.join(missingPolicy, 'Missing.java'), 'package missing; class Missing { Unavailable value; }\n');
  await fs.writeFile(policy, RULES);
  const pins = Object.fromEntries(await Promise.all(
    [java, jar, executable, process.execPath, policy, __filename, path.join(__dirname, 'm9-edge.cjs'),
      path.resolve(__dirname, '../../../../docs/tasks/m9/04-cases.md'),
      path.join(source, 'Client.java'), path.join(source, 'Store.java'), path.join(missingPolicy, 'Missing.java'),
      springGraph, springPolicy].filter(Boolean).map(async file => [file, await sha(file)])));
  const chosen = process.env.M9_WORKBENCH_PROFILE;
  const focused = process.env.M9_WORKBENCH_FOCUS;
  const profiles = PROFILES.filter(([profile]) => !chosen || profile === chosen);
  assert.ok(profiles.length, 'Unknown M9_WORKBENCH_PROFILE');
  assert.ok(!focused || /^U[2-6]$/.test(focused), 'Unknown M9_WORKBENCH_FOCUS');
  await fs.writeFile(path.join(output, 'inputs.json'), JSON.stringify({pins, profiles, focused}, null, 2), {flag: 'wx'});

  const results = [];
  for (const [profile, width, height] of profiles) {
    const run = path.join(output, profile);
    await fs.mkdir(run);
    const store = path.join(run, 'store');
    const downloads = path.join(run, 'downloads');
    await fs.mkdir(store);
    await fs.mkdir(downloads);
    const server = await startServer(java, ['-Xmx2g', '-cp', jar, EXPLORE, missingPolicy, 'urn:evo:m9.4:missing',
      path.dirname(path.dirname(java)), '21', artifacts, store, '0'], run);
    let page;
    let conditional;
    async function record(id, action) {
      if (focused && id !== 'U1' && id !== focused) return false;
      const start = performance.now();
      const row = {profile, id};
      try {
        if (action) { await action(); row.outcome = 'PASS'; }
        else row.outcome = 'NOT_RUN';
      } catch (error) {
        row.outcome = 'FAIL';
        row.reason = error.message;
        await page?.evaluate("document.querySelector('dialog[open]')?.close()").catch(() => {});
      }
      row.wallSeconds = (performance.now() - start) / 1000;
      results.push(row);
      await fs.appendFile(path.join(output, 'journeys.jsonl'), JSON.stringify(row) + '\n');
      console.log(profile, id, row.outcome, row.reason || '');
      return row.outcome === 'PASS';
    }
    try {
      page = await edge(executable, run);
      await page.send('Emulation.setDeviceMetricsOverride', {width, height, deviceScaleFactor: 1, mobile: profile !== 'desktop'});
      await page.send('Emulation.setEmulatedMedia', {features: [{name: 'prefers-reduced-motion', value: 'reduce'}]});
      await page.send('Page.setDownloadBehavior', {behavior: 'allow', downloadPath: downloads});
      await fs.writeFile(path.join(run, 'browser.txt'), page.version, {flag: 'wx'});
      let published = false;
      if (focused !== 'U4') {
        await open(page, server.origin);
        published = await record('U1', () => lifecycle(page, profile, source));
      }
      await record('U2', published && (() => overview(page)));
      await record('U3', published && (() => evidence(page, downloads)));
      await record('U5', published && (() => keyboardAndProjection(page, width)));
      await record('U6', published && (() => degradedAndStale(page, profile)));
      await record('U4', springGraph && (async () => {
        const fixture = path.join(run, 'spring');
        await fs.mkdir(fixture);
        conditional = await startServer(java, ['-Xmx2g', '-cp', springClasses + path.delimiter + jar,
          CAPTURE, springGraph, springPolicy, '0'], fixture);
        await configuration(page, conditional.origin);
      }));
    } finally {
      await page?.close();
      conditional?.close();
      server.close();
    }
  }
  process.exitCode = results.every(row => row.outcome === 'PASS') ? 0 : 1;
}

if (require.main === module) main(process.argv.slice(2)).catch(error => {
  console.error(error.message);
  process.exitCode = 2;
});
