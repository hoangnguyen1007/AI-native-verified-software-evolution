'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const {edge,key,startServer,until} = require('./m9-edge.cjs');
const run = promisify(execFile);

async function main([java, jar, executable, root, requestPath, checkHistory]) {
  assert.ok(requestPath, 'Usage: node evolution-workbench.browser.cjs <java> <jar> <chromium> <store> <pinned-request>');
  const directory = await fs.mkdtemp(path.join(os.tmpdir(),'evo-m115-browser-'));
  const source = path.join(directory,'source'), cache = path.join(directory,'artifacts');
  await fs.mkdir(source); await fs.mkdir(cache);
  const input = JSON.parse(await fs.readFile(requestPath,'utf8'));
  const server = await startServer(java,['-Xmx2g','-cp',jar,'com.evolution.backend.analysis.WorkbenchApiMain',
    source,'urn:fixture:m115-browser',path.dirname(path.dirname(java)),'17',cache,root,'0'],directory);
  let browser;
  try {
    const times = [];
    const call = async (suffix, body) => {
      const response = await fetch(server.origin + '/api/v1/evolution/' + suffix,body === undefined ? {} : {
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)
      });
      const payload = await response.json();
      assert.ok(response.ok,JSON.stringify(payload.error));
      return payload.data;
    };
    const submit = async request => {
      const started = performance.now();
      const accepted = await call('jobs',{request});
      const completed = await until('Comparison job terminal',async () => {
        const job = await call('jobs/' + accepted.job);
        assert.notEqual(job.state,'FAILED',job.reason);
        return job.result;
      },300_000);
      const result = await call('results/' + completed);
      times.push(Math.round(performance.now() - started));
      return result;
    };
    const missing = 'sha256:' + 'f'.repeat(64);
    const manual = async id => call('results/' + id);
    const [positive,degraded] = input.result && input.degraded ? await Promise.all([manual(input.result),manual(input.degraded)]) : [
      await submit(input),await submit({...input,comparison:{...input.comparison,right:missing},reports:[],bridges:[],history:null})];
    assert.equal(degraded.inputs[1].status,'MISSING');
    assert.notEqual(degraded.status,'COMPLETE');

    browser = await edge(executable,directory);
    await browser.send('Emulation.setDeviceMetricsOverride',{width:320,height:844,deviceScaleFactor:1,mobile:true});
    await browser.send('Emulation.setEmulatedMedia',{features:[{name:'prefers-reduced-motion',value:'reduce'}]});
    await browser.send('Page.navigate',{url:server.origin + '/evolution.html?result=' + positive.result});
    await until('Historical comparison displayed',() => browser.evaluate(
      `document.querySelector('#comparison-result-id')?.textContent === '${positive.result}' && !document.querySelector('#comparison-result').hidden`));
    assert.deepEqual(await browser.evaluate("Array.from(document.querySelectorAll('#comparison-pins article code'),x=>x.textContent)"),
      [input.comparison.left,input.comparison.right]);
    assert.equal(await browser.evaluate("document.querySelector('#comparison-outcome').textContent"),positive.status);
    assert.ok(await browser.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'));
    if (checkHistory === '--check-history') {
      const parityPage = async section => {
        const query = {section,size:12};
        const page = await call('results/' + positive.result + '/query',query);
        const exported = await call('results/' + positive.result + '/export',query);
        assert.deepEqual(exported.page,page);
        const inspected = await run(java,['-jar',jar,'evolution','inspect',positive.result,'--store',root,
          '--section',section,'--size','12'],{windowsHide:true,maxBuffer:1_048_576}).catch(error => {
            assert.equal(error.code,3,error.stderr);
            return error;
          });
        assert.deepEqual(JSON.parse(inspected.stdout).data,page);
        return page;
      };
      const regions = await parityPage('REGIONS');
      assert.ok(regions.items.some(row => row.shape === 'RESHAPED'));
      await browser.evaluate(`(() => {
        const section = document.querySelector('#comparison-section');
        section.value = 'REGIONS'; section.dispatchEvent(new Event('change'));
      })()`);
      await until('Reshaped region rendered',() => browser.evaluate(
        "Array.from(document.querySelectorAll('#comparison-rows summary')).some(x=>x.textContent.includes('RESHAPED'))"));
      const historyPage = await parityPage('HISTORY');
      const reintroduced = historyPage.items.find(row => row.transition === 'REINTRODUCED');
      assert.ok(reintroduced,'The authored finding must be reintroduced after the proved clean interval');
      for (const key of ['intervalProof','previousAbsenceProof','priorPresenceProof'])
        assert.match(reintroduced[key],/^sha256:[0-9a-f]{64}$/);
      await browser.evaluate(`(() => {
        const section = document.querySelector('#comparison-section');
        section.value = 'HISTORY'; section.dispatchEvent(new Event('change'));
      })()`);
      await until('Reintroduction rendered',() => browser.evaluate(
        "Array.from(document.querySelectorAll('#comparison-rows summary')).some(x=>x.textContent.includes('REINTRODUCED'))"));
      await browser.evaluate("Array.from(document.querySelectorAll('#comparison-rows summary')).find(x=>x.textContent.includes('REINTRODUCED')).click()");
      await until('Reintroduction proof visible',() => browser.evaluate(
        `document.querySelector('#comparison-rows').textContent.includes('${reintroduced.priorPresenceProof}')`));
    }
    const contexts = await call('results/' + positive.result + '/query',{section:'CONTEXTS',size:50});
    const scope = contexts.items.find(row => row.side === 'LEFT' && row.sourceSet === 'MAIN' && row.available && row.context);
    assert.ok(scope,'A captured historical MAIN context is required for the graph journey');
    await browser.evaluate(`(() => {
        const input = document.querySelector('#graph-context-LEFT');
        input.value = ${JSON.stringify(scope.context)}; input.form.requestSubmit();
      })()`);
    await until('Graph context rendered',() => browser.evaluate(
      "Boolean(document.querySelector('#comparison-graphs li button'))"));
    await browser.evaluate("document.querySelector('#comparison-graphs li button').click()");
    await until('Outgoing graph expansion rendered',() => browser.evaluate(
      "Boolean(document.querySelector('#comparison-graphs [data-projection=RAW_CONTEXT_OUTGOING]'))"));
    await browser.evaluate("document.querySelector('#comparison-section').focus()");
    await key(browser,'Tab',9);
    assert.equal(await browser.evaluate('document.activeElement.id'),'filter-side');
    await browser.evaluate(`(() => {
      const send = window.fetch.bind(window);
      window.fetch = async (url, init) => {
        if (String(url).endsWith('${positive.result}/query') && JSON.parse(init.body).section === 'EVENTS') {
          window.waitingForOldEvents = true;
          const response = await send(url,{...init,signal:undefined});
          await new Promise(resolve => window.releaseOldEvents = resolve);
          return response;
        }
        return send(url,init);
      };
      const select = document.querySelector('#comparison-section');
      select.value = 'EVENTS'; select.dispatchEvent(new Event('change'));
    })()`);
    await until('Old response withheld',() => browser.evaluate('Boolean(window.waitingForOldEvents && window.releaseOldEvents)'));
    await browser.evaluate(`document.querySelector('#saved-id').value = '${degraded.result}';
      document.querySelector('#reopen-form').requestSubmit(document.querySelector('button[value=result]'))`);
    await until('Degraded pair displayed',() => browser.evaluate(
      `document.querySelector('#comparison-result-id').textContent === '${degraded.result}' &&
       document.querySelector('#comparison-pins').textContent.includes('MISSING')`));
    await browser.evaluate('window.releaseOldEvents()');
    const expected = await call('results/' + degraded.result + '/query',{section:'EVENTS',size:12,filter:{}});
    await until('New page finishes',() => browser.evaluate("document.querySelector('#comparison-rows').getAttribute('aria-busy')==='false'"));
    assert.equal(await browser.evaluate("document.querySelectorAll('#comparison-rows .comparison-record').length"),expected.items.length);
    assert.equal(await browser.evaluate("document.querySelector('#comparison-result-id').textContent"),degraded.result);
    assert.ok(await browser.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'));
    assert.doesNotMatch(await browser.evaluate("document.querySelector('#comparison-status').textContent"),/no violations/i);
    const exported = await call('results/' + degraded.result + '/export',{section:'EVENTS',size:12,filter:{}});
    assert.deepEqual(exported.page,expected);
    for (const result of [positive,degraded]) {
      const cli = await run(java,['-jar',jar,'evolution','inspect',result.result,'--store',root],
        {windowsHide:true,maxBuffer:1_048_576}).catch(error => {
          assert.equal(error.code,3,error.stderr);
          return error;
        });
      assert.deepEqual(JSON.parse(cli.stdout).data,result);
    }
    if (input.result) {
      const pinnedFile = path.join(directory,'pinned-request.json');
      const {result: stored, degraded: negative, ...request} = input;
      await fs.writeFile(pinnedFile,JSON.stringify(request));
      const document = await browser.send('DOM.getDocument');
      const field = await browser.send('DOM.querySelector',{nodeId:document.root.nodeId,selector:'#request-file'});
      await browser.send('DOM.setFileInputFiles',{nodeId:field.nodeId,files:[pinnedFile]});
      await until('Pinned request selected',() => browser.evaluate(
        "document.querySelector('#comparison-status').textContent.startsWith('Pinned request loaded')"));
      await browser.evaluate("document.querySelector('#compare-submit').click()");
      await until('Pinned request submitted from Workbench',() => browser.evaluate(
        `!document.querySelector('#comparison-result').hidden && document.querySelector('#comparison-result-id').textContent === '${positive.result}'`),120_000);
    }
    console.log(JSON.stringify({browser:browser.version,history:positive.result,historyStatus:positive.status,
      degraded:degraded.result,degradedStatus:degraded.status,staleResponseRejected:true,keyboardReflow320:true,
      graphExpansion:true,cliHttpSummaryParity:true,workbenchSubmission:Boolean(input.result),
      historyAndRegionParity:checkHistory === '--check-history',comparisonMillis:times}));
  } finally {
    if (browser) await browser.close();
    server.close();
  }
}

main(process.argv.slice(2)).catch(error => {console.error(error);process.exitCode=1;});
