'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {edge, key, startServer, until} = require('./m9-edge.cjs');

// Focused Workbench journey. Pass the built application JAR or the verified test classpath.
async function main([java, classpath, executable]) {
  assert.ok(java && classpath && executable, 'Usage: node workbench-reuse.browser.cjs <java> <classpath> <edge>');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'evo-workbench-reuse-'));
  const source = path.join(directory, 'source'), artifacts = path.join(directory, 'artifacts'), store = path.join(directory, 'store');
  await Promise.all([source, artifacts, store].map(item => fs.mkdir(item)));
  const packageDirectory = path.join(source, 'src', 'main', 'java', 'sample');
  await fs.mkdir(packageDirectory, {recursive: true});
  await fs.writeFile(path.join(packageDirectory, 'Example.java'), 'package sample; public class Example { public void work() {} }\n');
  await fs.writeFile(path.join(source, 'pom.xml'), [
    '<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>example</artifactId><version>1</version>',
    '<properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>',
    '</project>'
  ].join(''));
  const server = await startServer(java, ['-Xmx512m', '-cp', classpath, 'com.evolution.backend.analysis.WorkbenchApiMain',
    source, 'urn:test:workbench-reuse', path.dirname(path.dirname(java)), '17', artifacts, store, '0'], directory);
  let page;
  try {
    page = await edge(executable, directory);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 320, height: 844, deviceScaleFactor: 1, mobile: true});
    await page.send('Page.navigate', {url: server.origin});
    const ready = selector => until(selector + ' enabled', () => page.evaluate(
      'Boolean(document.querySelector(' + JSON.stringify(selector) + ')?.matches(":enabled:not([hidden])"))'));
    await ready('#analyze');
    await page.evaluate("(() => { const send = window.fetch.bind(window); window.previewRequests = 0; window.fetch = async (input, init) => { const url = String(input); if (url.endsWith('/overview')) window.previewRequests++; const response = await send(input, init); if (!window.previewRequests && url.includes('/analysis/jobs/')) { const envelope = await response.clone().json(); if (['SUCCEEDED','PARTIAL'].includes(envelope.data?.state)) { envelope.data = {...envelope.data, state: 'RUNNING', stage: 'ANALYSIS', result: null}; return new Response(JSON.stringify(envelope), {status: 200, headers: {'Content-Type': 'application/json'}}); } } return response; }; })()");
    const firstStart = performance.now();
    await page.evaluate("document.querySelector('#analyze').click()");
    await until('Published build structure is shown with its qualification', () => page.evaluate(
      "!document.querySelector('#readiness-overview').hidden && document.querySelector('#readiness-overview').textContent.includes('candidate modules')"));
    await until('Graph ready', () => page.evaluate("!document.querySelector('#analysis-workspace').hidden"));
    await page.evaluate(`(() => {
      const contexts = document.querySelector('#context');
      if (!contexts.value) {
        contexts.selectedIndex = 1;
        contexts.dispatchEvent(new Event('change', {bubbles: true}));
      }
    })()`);
    await until('Overview usable', () => page.evaluate(`
      Boolean(document.querySelector('#context').value)
        && document.querySelector('#panel-overview').getAttribute('aria-busy') === 'false'
        && !document.querySelector('#panel-overview').textContent.includes('Unable to load this view')
    `));
    const firstMs = performance.now() - firstStart;
    await ready('#analyze');

    // Hold only the next submission so the previous-result UI is observable even for a very fast hit.
    await page.evaluate(`(() => {
      const fetchNow = window.fetch.bind(window);
      window.reuseDiscoveryReads = 0;
      window.fetch = async (input, init) => {
        const url = String(input);
        if (/\\/graphs\\/sha256:[0-9a-f]{64}$/.test(url)) window.reuseDiscoveryReads++;
        if (url.endsWith('/analysis/jobs') && init?.method === 'POST' && !window.releaseAnalysis) {
          await new Promise(resolve => { window.releaseAnalysis = resolve; });
        }
        return fetchNow(input, init);
      };
    })()`);
    await page.evaluate("document.querySelector('#analyze').click()");
    await until('Previous result shown while inputs are unverified', () => page.evaluate(`
      typeof window.releaseAnalysis === 'function'
        && document.querySelector('#result-status').textContent.includes('previous analysis')
        && !document.querySelector('#analysis-workspace').hidden
        && document.querySelector('#analyze-force').disabled
    `));
    const repeatStart = performance.now();
    await page.evaluate('window.releaseAnalysis()');
    await until('Verified reuse in the Workbench', () => page.evaluate(`
      document.querySelector('#result-status').textContent.includes('Matching inputs verified')
        && [...document.querySelectorAll('#job-events li')].some(node => node.textContent === 'reused')
        && !document.querySelector('#analysis-workspace').hidden
    `));
    const repeatMs = performance.now() - repeatStart;
    assert.equal(await page.evaluate('window.reuseDiscoveryReads'), 0);
    assert.ok(await page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1'));

    await ready('#analyze-force');
    await page.evaluate("document.querySelector('#analyze-force').focus()");
    assert.equal(await page.evaluate('document.activeElement.id'), 'analyze-force');
    await key(page, 'Enter', 13, '\r');
    await until('Full recomputation through the keyboard action', () => page.evaluate(`
      [...document.querySelectorAll('#job-events li')].map(node => node.textContent).includes('analysis')
        && [...document.querySelectorAll('#job-events li')].at(-1)?.textContent === 'finished'
        && !document.querySelector('#result-status').textContent.includes('Matching inputs verified')
    `));
    console.log('PASS: 320px build preview, previous-result view, verified reuse with no graph discovery, and keyboard recompute.');
    console.log('Authored browser fixture: initial click to Overview ' + Math.round(firstMs)
      + ' ms; repeat from submission release to verified result ' + Math.round(repeatMs) + ' ms.');
  } finally {
    await page?.close();
    server.close();
  }
}

main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
