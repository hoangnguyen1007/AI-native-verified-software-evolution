'use strict';

const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const {createHash} = require('node:crypto');
const fs = require('node:fs/promises');
const path = require('node:path');
const {edge, startServer, until, key} = require('./m9-edge.cjs');

const digest = value => 'sha256:' + createHash('sha256').update(value).digest('hex');

async function main([java, classpath, browser, output, graph, policy, candidateGraph, controlGraph, capture, modelPort, modelAlias, diagnosisInput]) {
  const live = Boolean(modelPort && modelAlias && diagnosisInput);
  if (!policy || Boolean(candidateGraph)!==Boolean(controlGraph) || (modelPort || modelAlias || diagnosisInput) && !live)
    throw new Error('Usage: node diagnosis-workbench.browser.cjs JAVA CLASSPATH EDGE NEW_OUTPUT GRAPH POLICY [CANDIDATE CONTROL [CAPTURE [MODEL_PORT MODEL_ALIAS DIAGNOSIS_INPUT]]]');
  await fs.mkdir(output);
  const captureOptions = capture ? ['-Devo.demo.repair.capture='+capture,'-Devo.demo.repair.capture.pin='+digest(await fs.readFile(capture))] : [];
  const modelOptions = live ? [
    '-Devo.demo.diagnosis.port='+modelPort,'-Devo.demo.diagnosis.model='+modelAlias,'-Devo.demo.diagnosis.input='+diagnosisInput
  ] : [];
  const server = await startServer(java, ['-Xmx2g', ...captureOptions, ...modelOptions, '-cp', classpath,
    'com.evolution.backend.query.CapturedWorkbenchAcceptance', graph, policy, '0', ...(candidateGraph?[candidateGraph,controlGraph]:[])], output);
  let page;
  try {
    page = await edge(browser, output);
    await page.send('Emulation.setDeviceMetricsOverride', {width: 390, height: 844, deviceScaleFactor: 1, mobile: true});
    await page.send('Page.navigate', {url: server.origin});
    const evalJs = expression => page.evaluate(expression);
    const button = async (selector, label, keyboard = false) => {
      await until('Button: ' + label, () => evalJs(`(() => {
        const target = [...document.querySelectorAll(${JSON.stringify(selector)})]
          .find(button => button.getClientRects().length && !button.disabled && button.textContent.includes(${JSON.stringify(label)}));
        if (!target) return false;
        target.focus();
        if (!${keyboard}) { target.click(); return true; }
        return document.activeElement === target;
      })()`));
      if (keyboard) {
        await page.send('Input.dispatchKeyEvent', {type:'keyDown',key:'Enter',code:'Enter',text:'\r',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13});
        await page.send('Input.dispatchKeyEvent', {type:'keyUp',key:'Enter',code:'Enter',windowsVirtualKeyCode:13,nativeVirtualKeyCode:13});
      }
    };
    let evidenceSequence = 0;
    const data = async title => {
      await until(title, () => evalJs(`(() => {
        const download = document.querySelector('#download-details');
        if (document.querySelector('#inspector-title')?.textContent !== ${JSON.stringify(title)} || download.disabled) return false;
        download.click();
        return true;
      })()`));
      const downloaded = path.join(output,'evo-evidence.json');
      const contents = await until('Downloaded ' + title, () => fs.readFile(downloaded,'utf8').catch(error => {
        if (error.code === 'ENOENT' || error.code === 'EBUSY') return null;
        throw error;
      }));
      await fs.rename(downloaded,path.join(output,`evidence-${++evidenceSequence}.json`));
      return JSON.parse(contents);
    };
    await button('#analyze', 'Run analysis');
    const contexts = await until('Published analysis contexts', () => evalJs(`(() => {
      const options = [...(document.querySelector('#context')?.options || [])].map(option => option.value).filter(Boolean);
      return options.length > 0 && options;
    })()`));
    await page.send('Page.setDownloadBehavior', {behavior:'allow',downloadPath:output});
    const spring = contexts.find(context => context.startsWith('spring-context:'));
    assert.ok(spring, 'No Spring context was published for the pinned fixture');
    await evalJs(`(() => {
      const select = document.querySelector('#context');
      select.value = ${JSON.stringify(spring)};
      select.dispatchEvent(new Event('change', {bubbles: true}));
    })()`);
    await button('#tab-policy', 'Review');
    await button('#panel-policy button.entity-row', 'client.no-store');
    await button('#inspector-body button', 'Diagnose finding', true);
    const diagnosis = await data('Finding diagnosis');
    assert.equal(diagnosis.mode, live ? 'LIVE' : 'LOCAL_REPLAY');
    assert.equal(diagnosis.status, 'CITATIONS_VALIDATED', diagnosis.reason);
    if (live) {
      assert.equal(diagnosis.input, diagnosisInput);
      assert.deepEqual(diagnosis.model, {id:'diagnosis.loopback-chat',version:modelAlias});
    }
    assert.equal(diagnosis.proof.classification, 'MAY');
    assert.match(await evalJs("document.querySelector('#inspector-body').innerText"), live ? /Live model/ : /Local replay/);
    assert.match(await evalJs("document.querySelector('#inspector-body').innerText"), /some modeled configurations/);
    const citation = diagnosis.citations.find(source => source.path.endsWith('/Client.java'));
    assert.ok(citation, 'The decoded fixture must cite the Client source');
    assert.equal(citation.span.startLine, 1);
    assert.equal(citation.span.endLine, 1);
    assert.equal(citation.span.startColumn, 140);
    assert.equal(citation.span.endColumn, 151);
    await button('#inspector-body button', citation.path, true);
    const evidence = await data('Cited source evidence');
    assert.equal(evidence.citation.evidence, citation.evidence);
    assert.equal(evidence.record.identity, citation.evidence);
    assert.deepEqual(evidence.citation.span, citation.span);
    await button('#inspector-body button', 'Back to diagnosis', true);
    await data('Finding diagnosis');
    await button('#inspector-body button', 'Review repair proposal', true);
    const outcome = await data('Repair proposal');
    assert.equal(outcome.status, 'PROPOSED', outcome.reason);
    const {identity,candidate} = outcome.proposal;
    assert.deepEqual(candidate.baseline, diagnosis.pin);
    assert.deepEqual(candidate.affectedScope, diagnosis.proof);
    assert.deepEqual(candidate.citation, citation);
    assert.equal(candidate.diagnosisInput, diagnosis.input);
    assert.equal(candidate.diagnosisMode, 'LOCAL_REPLAY');
    assert.equal(candidate.verification, 'UNVERIFIED');
    assert.equal(candidate.plan.expectedFindingChange, 'REMOVED');
    assert.equal(candidate.engine.id, 'openrewrite.java');
    assert.equal(candidate.patchDigest, digest(candidate.patch));
    assert.equal(await evalJs("document.querySelector('.repair-patch').getAttribute('aria-label')"),
      'Unified patch for ' + candidate.edit.path);
    assert.match(await evalJs("document.querySelector('#inspector-body').innerText"), /Unverified repair proposal/);
    await button('#inspector-body button', 'Download patch', true);
    const patchPath = path.join(output,'repair.patch');
    const downloaded = await until('Downloaded patch', async () => fs.readFile(patchPath).catch(error => {
      if (error.code === 'ENOENT') return null;
      throw error;
    }));
    assert.equal(digest(downloaded),candidate.patchDigest);
    await checkGitPatch(candidate, output, patchPath);

    const endpoint = `${server.origin}/api/v1/graphs/${diagnosis.pin.graph}/reports/${diagnosis.pin.report}/repair-proposal`;
    const post = diagnosisInput => fetch(endpoint,{
      method:'POST', headers:{'Content-Type':'application/json'}, signal:AbortSignal.timeout(30_000),
      body:JSON.stringify({arguments:{identity:diagnosis.pin.finding,bundle:diagnosis.pin.bundle,context:diagnosis.pin.context,diagnosisInput}})
    });
    const replay = await post(diagnosis.input);
    assert.equal(replay.status, 200);
    assert.deepEqual((await replay.json()).data, outcome);
    const stale = await post(diagnosis.pin.report);
    assert.equal(stale.status, 409);
    assert.equal((await stale.json()).error.code, 'DIAGNOSIS_INPUT_MISMATCH');
    let verification;
    if (candidateGraph) {
      await button('#inspector-body button', 'View verification', true);
      const initial = await data('Local PR preview');
      assert.equal(initial.verdict,'UNVERIFIED');
      assert.equal(initial.plan.baseline.snapshot,diagnosis.pin.snapshot);
      assert.equal(initial.checks.find(check=>check.id==='fixture-inputs').status,'PASSED');
      assert.equal(initial.checks.find(check=>check.id==='isolated-runtime').status,capture?'PASSED':'NOT_RUN');
      assert.equal(initial.patch,candidate.patch);
      const candidateReport = await springReport(server.origin,candidateGraph);
      const controlReport = await springReport(server.origin,controlGraph);
      await evalJs(`(() => {
        const form = document.querySelector('#inspector-body form');
        form.closest('details').open = true;
        form.elements.candidateReport.value = ${JSON.stringify(candidateReport)};
        form.elements.controlReport.value = ${JSON.stringify(controlReport)};
        form.requestSubmit();
      })()`);
      verification = await data('Local PR preview');
      assert.equal(verification.verdict,capture?'VERIFIED_DEMO':'UNVERIFIED');
      assert.notEqual(verification.plan.candidate.snapshot,initial.plan.baseline.snapshot);
      assert.equal(verification.candidateComparison.snapshot,verification.plan.candidate.snapshot);
      assert.equal(verification.controlComparison.snapshot,verification.plan.control.snapshot);
      const negative = verification.checks.find(check=>check.id==='mutation-control');
      assert.equal(negative.status,'PASSED');
      assert.equal(negative.reason,'CONTROL_FINDING_WITNESS');
      if (capture) {
        const finding = verification.checks.find(check=>check.id==='candidate-finding');
        assert.equal(finding.status,'PASSED',finding.reason);
        assert.equal(finding.reason,'FINDING_RESOLVED_IN_DEMO_SCOPE');
        assert.deepEqual(verification.candidateComparison.transitions,['CLEAN','RESOLVED']);
        assert.deepEqual(verification.controlComparison.transitions,['CLEAN','PERSISTED']);
        assert.equal(verification.candidateComparison.bindingCensus.snapshot,verification.plan.candidate.snapshot);
        assert.equal(verification.candidateComparison.bindingCensus.injectionSites,0);
        assert.deepEqual(verification.candidateComparison.bindingCensus.files,verification.plan.candidate.files);
        assert.equal(verification.demoProof.checks.length,9);
        assert.deepEqual(verification.demoProof.cells,[
          {dev:false,baseline:false,candidate:false,control:false,change:'CLEAN'},
          {dev:true,baseline:true,candidate:false,control:true,change:'RESOLVED'}
        ]);
        assert.match(await evalJs("document.querySelector('#inspector-body').innerText"),/Verified in the demo VM/);
      }
      const cli = classpath.split(path.delimiter).find(entry=>path.basename(entry)==='evo.jar');
      assert.ok(cli,'Pass the packaged evo.jar to verify the CLI');
      const proposalFile = path.join(output,'proposal.json');
      await fs.writeFile(proposalFile,JSON.stringify(outcome));
      const run = (target,control,file = proposalFile) => spawnSync(java,['-jar',cli,'verify-repair',file,'--port',new URL(server.origin).port,
        '--candidate-report',target,'--control-report',control,'--json'],{cwd:output,encoding:'utf8',windowsHide:true,timeout:45_000});
      const pending = run(candidateReport,controlReport);
      assert.equal(pending.status,capture?0:3,pending.error?.message || pending.stderr || pending.stdout.slice(0,250));
      assert.deepEqual(JSON.parse(pending.stdout).data,verification);
      const bad = run(diagnosis.pin.report,controlReport);
      assert.equal(bad.status,4,bad.error?.message || bad.stderr || bad.stdout.slice(0,250));
      const rejection = JSON.parse(bad.stdout).data;
      assert.equal(rejection.verdict,'REJECTED');
      assert.equal(rejection.checks.find(check=>check.id==='candidate-finding').reason,'REANALYSIS_SNAPSHOT_MISMATCH');
      const tamperedFile = path.join(output,'tampered-proposal.json');
      await fs.writeFile(tamperedFile,JSON.stringify({...outcome,proposal:{identity,candidate:{...candidate,patch:candidate.patch+'\n'}}}));
      const malformed = run(candidateReport,controlReport,tamperedFile);
      assert.equal(malformed.status,2,malformed.error?.message || malformed.stderr || malformed.stdout.slice(0,250));
    }
    assert.ok(await evalJs('document.documentElement.scrollWidth <= innerWidth + 1'));
    await key(page, 'Escape', 27);
    assert.equal(await evalJs("document.querySelector('#inspector').open"), false);
    assert.equal(await evalJs("document.activeElement.matches('#panel-policy button.entity-row')"), true);
    console.log(JSON.stringify({status:'PASS',browser:page.version,diagnosis:diagnosis.input,mode:diagnosis.mode,model:diagnosis.model,
      citations:diagnosis.citations.map(({identity})=>identity),classification:diagnosis.proof.classification,
      proposal:identity,patch:candidate.patchDigest,edit:candidate.edit,
      verification:verification?.verdict || candidate.verification,plan:verification?.planIdentity,
      checks:verification?.checks,candidate:verification?.candidateComparison,control:verification?.controlComparison}));
  } catch (error) {
    const summary = await page?.evaluate(`(() => {
      const text = id => document.querySelector(id)?.textContent?.trim();
      return {connection:text('#connection-status'),job:text('#job-status'),notice:text('#notice'),
        contexts:[...(document.querySelector('#context')?.options || [])].map(option => option.textContent),
        dialog:text('#inspector-title')};
    })()`).catch(() => undefined);
    if (summary) console.error(JSON.stringify(summary));
    throw error;
  } finally {
    await page?.close();
    server.close();
  }
}

async function springReport(origin,file) {
  const {identity} = JSON.parse(await fs.readFile(file,'utf8'));
  const get = async url => {
    const response = await fetch(url,{signal:AbortSignal.timeout(5000)});
    assert.equal(response.status,200);
    return (await response.json()).data;
  };
  const url = `${origin}/api/v1/graphs/${identity}`;
  const graph = await get(url);
  for (const report of graph.reports) {
    const item = await get(`${url}/reports/${report}`);
    if (item.context.startsWith('spring-context:')) return report;
  }
  throw new Error('Expected a published Spring report');
}

async function checkGitPatch(candidate, output, patchPath) {
  const sourcePath = 'src/main/java/app/Client.java';
  assert.equal(candidate.edit.path, sourcePath);
  const lines = candidate.patch.split('\n');
  const before = lines.find(line => line.startsWith('-package app;')).slice(1);
  const after = lines.find(line => line.startsWith('+package app;')).slice(1);
  assert.equal(digest(before), candidate.citation.sourceDigest);
  assert.equal(digest(before), candidate.edit.before);
  assert.equal(digest(after), candidate.edit.after);
  assert.match(before, /public Client\(Store store\)\{\}/);
  assert.equal(after, before.replace('Client(Store store)', 'Client()'));
  const workspace = path.join(output,'git-check');
  const source = path.join(workspace,sourcePath);
  await fs.mkdir(path.dirname(source),{recursive:true});
  await fs.writeFile(source,before);
  const check = () => spawnSync('git',['apply','--check',patchPath],{cwd:workspace,encoding:'utf8',windowsHide:true});
  const accepted = check();
  assert.equal(accepted.status,0,accepted.error?.message || accepted.stderr);
  await fs.writeFile(source,before+'\n');
  const rejected = check();
  assert.equal(rejected.status,1,rejected.error?.message || rejected.stderr);
}

main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
