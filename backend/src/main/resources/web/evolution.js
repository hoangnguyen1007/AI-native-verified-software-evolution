function mountEvolution(root) {
  'use strict';
  const $ = id => root.querySelector('#' + id);
  const state = {controller: null, page: null, result: null, job: null, pageController: null,
    cursor: null, previous: [], history: new Map(), historyCursor: null, request: null};
  const idPattern = /^sha256:[0-9a-f]{64}$/;
  const label = side => side === 'LEFT' ? 'Baseline' : 'Target';
  const short = value => typeof value === 'string' && value.length > 24 ? value.slice(0, 20) + '…' : value;
  const strong = (tag, text) => {
    const node = document.createElement(tag);
    if (text != null) node.textContent = String(text);
    return node;
  };
  const control = (id, visible) => { $(id).hidden = !visible; };
  const display = value => value == null ? 'Unavailable' : typeof value === 'object' ? JSON.stringify(value, null, 2) : String(value);
  const status = message => { $('comparison-status').textContent = message; };
  const apiRoot = '/api/v1/evolution/';
  const errors = {
    EVOLUTION_ARTIFACT_NOT_FOUND: 'The pinned artifact is missing or unavailable.',
    EVOLUTION_ARTIFACT_CORRUPT: 'The artifact failed integrity verification.',
    EVOLUTION_STORE_UNAVAILABLE: 'The comparison store is unavailable.',
    EVOLUTION_SERVICE_UNAVAILABLE: 'This server has not configured snapshot comparison.',
    EVOLUTION_IDEMPOTENCY_CONFLICT: 'The job key belongs to a different request.',
    EVOLUTION_RESULT_CONFLICT: 'The store retained conflicting results for these realized inputs.',
    EVOLUTION_WORKER_BUSY: 'The queue is busy. Retry the submission or reopen its job.',
    EVOLUTION_JOB_NOT_FOUND: 'The comparison job was not found.',
    INVALID_REQUEST: 'The request, selection or continuation is invalid.',
    RESPONSE_TOO_LARGE: 'The response is oversized. Narrow the selected page or use the local export.'
  };
  async function envelope(path, body, signal) {
    const response = await fetch(path, {method: body === undefined ? 'GET' : 'POST',
      ...(body === undefined ? {} : {headers:{'Content-Type':'application/json'}, body:JSON.stringify(body)}),
      signal: signal ? AbortSignal.any([signal,AbortSignal.timeout(180000)]) : AbortSignal.timeout(180000)});
    const payload = await response.json();
    if (!response.ok || payload.error) throw new Error(errors[payload.error?.code] || 'The local API could not complete this request.');
    return payload;
  }
  async function api(path, body, signal) { return (await envelope(apiRoot + path,body,signal)).data; }
  function begin(message) {
    state.controller?.abort();
    state.pageController?.abort();
    state.controller = new AbortController();
    state.result = state.job = state.page = null;
    state.cursor = null;
    state.previous = [];
    for (const id of ['comparison-result','cancel-comparison','resume-comparison']) control(id,false);
    if ($('evolution-detail').open) $('evolution-detail').close();
    $('comparison-job-id').textContent = '';
    status(message);
    return state.controller;
  }
  async function guarded(message, perform) {
    const token = begin(message);
    try { await perform(token); }
    catch (error) { if (!token.signal.aborted) status(error.message || 'Comparison unavailable.'); }
  }
  function listenPage() { synchronizePage().catch(error => { if (error.name !== 'AbortError') $('comparison-count').textContent = error.message; }); }

  async function catalog() {
    $('more-analyses').disabled = true;
    try {
      const [setups, page] = await Promise.all([
        envelope('/api/v1/analysis/setups'),
        envelope('/api/v1/analysis/jobs/history',{size:30,...(state.historyCursor ? {cursor:state.historyCursor} : {})})
      ]);
      const repositories = new Map(setups.data.items.map(item => [item.id,item.repository]));
      for (const entry of page.data.items) {
        if (!entry.result?.graph || !repositories.has(entry.setup) || state.history.has(entry.id)) continue;
        state.history.set(entry.id,{...entry,repo:repositories.get(entry.setup)});
        const title = [entry.setup,new Date(entry.submittedAt).toLocaleString(),short(entry.result.graph)].join(' · ');
        for (const select of [$('baseline'),$('target')]) select.append(new Option(title,entry.id));
      }
      state.historyCursor = page.data.continuation;
      control('more-analyses',Boolean(state.historyCursor));
      $('catalog-status').textContent = state.history.size ? '' : 'No published graphs are listed on this page.';
    } catch (_) { $('catalog-status').textContent = 'Analysis history is unavailable. You can enter pinned graph IDs.'; }
    finally { $('more-analyses').disabled = false; }
  }
  function inputChanged() {
    state.request = null;
    begin('Selection changed. Run a comparison for this pair.');
  }
  function selectionChanged(side) {
    inputChanged();
    const chosen = state.history.get($(side).value);
    $(side + '-id').value = chosen?.result.graph || '';
    if (chosen) $('repository-id').value = chosen.repo;
  }
  async function capturedReports(entry, side, signal) {
    if (!entry?.result?.reports?.length) return [];
    const graph = entry.result.graph;
    const result = await Promise.all(entry.result.reports.map(async identity => {
      const response = await envelope('/api/v1/graphs/' + graph + '/reports/' + identity,undefined,signal);
      const context = response.data.context;
      if (!context) throw new Error('A saved report lacks an exact context. Load a request with explicit report pins.');
      return {side,identity,context};
    }));
    return result;
  }
  async function request(signal) {
    if (state.request) return state.request;
    const comparison = {repository:$('repository-id').value.trim(),left:$('baseline-id').value.trim(),right:$('target-id').value.trim(),mode:$('mode').value};
    if (!idPattern.test(comparison.left) || !idPattern.test(comparison.right) || !comparison.repository)
      throw new Error('Choose two analyses or enter the repository identity and both graph IDs.');
    const a = state.history.get($('baseline').value), b = state.history.get($('target').value);
    if (a && b && a.repo !== b.repo) throw new Error('Choose two analyses from the same repository.');
    const [leftReports,rightReports] = await Promise.all([
      capturedReports(a?.result.graph === comparison.left ? a : null,'LEFT',signal),
      capturedReports(b?.result.graph === comparison.right ? b : null,'RIGHT',signal)
    ]);
    const reports = [...leftReports,...rightReports];
    comparison.selection = {sourceSets:['MAIN'],nodeKinds:['TYPE'],relationshipKinds:['graph.depends-on'],
      conditional:true,evidence:true,findings:Boolean(reports.length),metrics:Boolean(reports.length),score:Boolean(reports.length)};
    return {schema:'architecture.evolution.request:v1',comparison,reports};
  }
  async function openResult(id, token) {
    const result = await api('results/' + id,undefined,token.signal);
    if (token.signal.aborted) return;
    state.result = result;
    $('saved-id').value = result.result;
    $('comparison-result-id').textContent = result.result;
    const selection = result.selection?.selection;
    $('comparison-selection').textContent = selection ? [
      result.selection.mode, 'Source sets ' + (selection.sourceSets?.join(', ') || 'all'),
      ...(selection.relationshipKinds?.length ? ['Relationships ' + selection.relationshipKinds.join(', ')] : [])
    ].join(' · ') : '';
    $('comparison-outcome').hidden = result.status !== 'WITHHELD';
    $('comparison-outcome').textContent = result.status === 'WITHHELD' ? 'Comparison unavailable' : '';
    const pins = result.inputs.map(input => {
      const card = strong('article');
      card.className = 'comparison-pin';
      card.append(strong('h3', label(input.side)),
        strong('p','Revision ' + (typeof input.revision === 'string' ? input.revision : 'unavailable')),
        strong('code',input.graph),strong('p','Snapshot ' + (input.snapshot || 'unavailable')));
      return card;
    });
    $('comparison-pins').replaceChildren(...pins);
    $('comparison-graphs').replaceChildren(...result.inputs.map(input => graphBoard(input,token)));
    control('comparison-result', true);
    status('');
    $('result-title').focus();
    await synchronizePage();
  }
  async function monitor(job,token) {
    for (;;) {
      if (token.signal.aborted) return;
      state.job = job;
      $('comparison-job-id').textContent = job.job;
      if (job.result) {
        control('cancel-comparison',false);
        control('resume-comparison',false);
        return openResult(job.result,token);
      }
      const active = ['QUEUED','RUNNING'].includes(job.state);
      control('cancel-comparison',active || job.state === 'WAITING_FOR_RESOURCE');
      control('resume-comparison',active || job.state === 'WAITING_FOR_RESOURCE');
      const stage = {COMPARING:'Comparing pinned inputs',INDEX_PREPARING:'Preparing the immutable graph index',
        VERIFYING_PUBLICATION:'Verifying immutable result',RESUMED:'Resuming comparison',
        SCOPE_CHECKPOINT:'Comparing scopes from a durable checkpoint',RESUMING_SCOPE:'Resuming at the saved scope boundary',
        STORAGE_RESOURCE_LIMIT:'Waiting for storage resources',RESULT_CONFLICT:'Conflicting results were retained',
        COMPARISON_STORAGE_UNAVAILABLE:'Comparison storage unavailable'};
      status([job.state,stage[job.reason],job.resumed ? 'Resumed' : null].filter(Boolean).join(' · '));
      if (!active) return;
      await new Promise(resolve => setTimeout(resolve,350));
      if (token.signal.aborted) return;
      job = await api('jobs/' + job.job,undefined,token.signal);
    }
  }
  function filter() {
    return Object.fromEntries([['side','filter-side'],['kind','filter-kind'],['scope','filter-scope'],['subject','filter-subject']]
      .map(([key,id]) => [key,$(id).value.trim()]).filter(([,value]) => value));
  }
  function pageRequest(cursor) {return {section:$('comparison-section').value,size:12,filter:filter(),...(cursor ? {cursor} : {})};}
  async function synchronizePage(cursor = null, navigation = 0) {
    if (!state.result) return;
    state.pageController?.abort();
    const token = new AbortController();
    const selection = state.controller, result = state.result;
    state.pageController = token;
    const current = () => !token.signal.aborted && !selection.signal.aborted && state.result === result;
    $('comparison-rows').setAttribute('aria-busy','true');
    for (const id of ['comparison-more','comparison-back','comparison-export']) $(id).disabled = true;
    $('comparison-count').textContent = 'Loading comparison rows…';
    try {
      const query = navigation ? {...state.activeQuery,...(cursor ? {cursor} : {})} : pageRequest(cursor);
      delete query.cursor;
      if (cursor) query.cursor = cursor;
      const page = await api('results/' + result.result + '/query',query,AbortSignal.any([token.signal,selection.signal]));
      if (!current()) return;
      if (navigation === 1) state.previous.push(state.cursor);
      else if (navigation === -1) state.previous.pop();
      else state.previous = [];
      state.cursor = cursor;
      state.activeQuery = {...query};
      delete state.activeQuery.cursor;
      state.page = page;
      $('comparison-rows').replaceChildren(...page.items.map(row => record(row,selection)));
      $('comparison-count').textContent = page.exactTotal == null ? page.items.length + ' rows shown' + (page.continuation ? ' · More available' : '') :
        String(page.exactTotal) === '0' ? (page.section === 'EVENTS' && result.status === 'COMPLETE' && !Object.keys(query.filter).length ?
          'No events in the selected comparison.' : 'No matching rows in this section.') : page.exactTotal + ' matching rows.';
      $('comparison-more').disabled = !page.continuation;
      $('comparison-back').disabled = !state.previous.length;
      $('comparison-export').disabled = false;
    } catch (error) { if (current()) $('comparison-count').textContent = error.message; }
    finally { if (current()) $('comparison-rows').setAttribute('aria-busy','false'); }
  }
  function button(text, run) {
    const node = strong('button',text);
    node.className = 'button'; node.type = 'button';
    node.addEventListener('click',run);
    return node;
  }
  function describe(row) {
    const info = [row.kind || row.capability || row.metric || row.family || row.descriptorStatus,
      row.transition || row.verdict || row.disposition || row.status || row.shape,
      row.side ? label(row.side) : null].filter(Boolean);
    return info.join(' · ') || short(row.identity || row.subject) || 'Comparison row';
  }
  function record(row, token) {
    const detail = strong('details');
    detail.className = 'comparison-record';
    const heading = strong('summary',describe(row));
    detail.append(heading);
    detail.addEventListener('toggle',() => {
      if (!detail.open || detail.childNodes.length > 1) return;
      for (const key of ['scope','transition','delta','nativeClassification','commonClassification']) {
        if (key in row) detail.append(strong('p',key + ' ' + display(row[key])));
      }
      if (row.context && row.side && typeof row.context === 'string') {
        detail.append(button('View ' + label(row.side).toLowerCase() + ' graph context',() => {
          const input = $('graph-context-' + row.side);
          if (input) {
            input.value = row.context;
            input.focus();
            input.form.requestSubmit();
          }
        }));
      }
      const references = [];
      for (const side of ['LEFT','RIGHT']) {
        const direct = row.side === side ? row : row[side.toLowerCase()] || row[side === 'LEFT' ? 'before' : 'after'];
        collectEvidence(direct,side,references);
      }
      if (references.length) {
        const actions = strong('div'); actions.className = 'compare-actions';
        for (const {side,id} of references.slice(0,12))
          actions.append(button(label(side) + ' evidence ' + short(id),() => evidence(side,id,token)));
        detail.append(actions);
      }
    });
    return detail;
  }
  function collectEvidence(value, side, references, depth = 0) {
    if (!value || typeof value !== 'object' || depth > 5 || references.length >= 12) return;
    if (Array.isArray(value)) {
      for (const entry of value) collectEvidence(entry,side,references,depth+1);
      return;
    }
    for (const [key, candidate] of Object.entries(value)) {
      if (['evidence','worldEvidence','evidenceReferences'].includes(key)) {
        for (const id of Array.isArray(candidate) ? candidate : [candidate])
          if (idPattern.test(id) && !references.some(item => item.side === side && item.id === id)) references.push({side,id});
      } else collectEvidence(candidate,side,references,depth+1);
    }
  }
  async function evidence(side,id,token) {
    const dialog = $('evolution-detail'), body = $('evolution-detail-body');
    const selected = Symbol();
    state.detail = selected;
    $('evolution-detail-title').textContent = label(side) + ' captured evidence';
    body.textContent = 'Loading evidence…';
    if (!dialog.open) dialog.showModal();
    try {
      const data = await api('results/' + state.result.result + '/evidence',{side,identity:id},token.signal);
      if (!token.signal.aborted && state.detail === selected && dialog.open) body.replaceChildren(strong('pre',display(data)));
    } catch (error) { if (!token.signal.aborted && state.detail === selected) body.textContent = error.message; }
  }
  function graphBoard(input,token) {
    const side = input.side, panel = strong('div');
    panel.className = 'comparison-graph-side';
    panel.append(strong('h3',label(side)));
    if (input.status !== 'AVAILABLE') { panel.append(strong('p',input.status)); return panel; }
    const form = strong('form'), field = strong('label','Context ID'), context = strong('input');
    context.id = 'graph-context-' + side; context.required = true; context.maxLength = 512; context.spellcheck = false;
    field.append(context);
    const view = strong('label','Projection'), type = strong('select');
    type.append(new Option('Nodes','nodes'),new Option('Directed edges','edges')); view.append(type);
    const submit = strong('button','Load context'), list = strong('ol'), note = strong('p');
    note.setAttribute('role','status'); submit.className = 'button';
    const more = button('Show more',() => load(true));
    more.hidden = true;
    form.append(field,view,submit); panel.append(form,note,list,more);
    let cursor, generation = 0, requestContext, expandNode;
    async function load(append) {
      const sequence = ++generation;
      if (!append) { cursor = null; requestContext = context.value.trim(); list.replaceChildren(); }
      more.hidden = true;
      note.textContent = 'Loading captured graph rows…';
      try {
        const page = await api('results/' + state.result.result + '/graph',
          {side,context:requestContext,edges:type.value === 'edges',size:12,...(cursor ? {cursor} : {}),
            ...(expandNode ? {node:expandNode} : {})},token.signal);
        if (token.signal.aborted || sequence !== generation) return;
        const rows = page.items.map(row => {
          const li = strong('li');
          li.append(record({...row,side},token));
          if (type.value === 'nodes' && typeof row.identity === 'string') li.append(button('Show outgoing edges from ' + short(row.identity),() => {
            expandNode = row.identity;
            type.value = 'edges';
            type.focus();
            load(false);
          }));
          return li;
        });
        if (append) list.replaceChildren(...rows);
        else list.append(...rows);
        cursor = page.continuation;
        more.hidden = !cursor;
        const projection = page.projection === 'RAW_CONTEXT_OUTGOING' ? 'Outgoing edges' :
          type.value === 'edges' ? 'Directed edges' : 'Nodes';
        note.dataset.projection = page.projection;
        note.textContent = projection + ' · ' + (rows.length ? rows.length + ' captured rows on this page.' : 'No captured rows in this view.');
      } catch (error) { if (!token.signal.aborted && sequence === generation) note.textContent = error.message; }
    }
    form.addEventListener('submit',event => {event.preventDefault(); expandNode = null; load(false);});
    type.addEventListener('change',() => { expandNode = null; if (context.value.trim()) load(false); });
    context.addEventListener('input',() => { generation++; cursor=null; expandNode=null; list.replaceChildren(); more.hidden=true; note.textContent='Load this context to show captured rows.'; });
    return panel;
  }
  $('compare-form').addEventListener('submit',event => {
    event.preventDefault();
    guarded('Submitting comparison…', async token => {
      const pinned = await request(token.signal);
      if (token.signal.aborted) return;
      const canonical = JSON.stringify(pinned);
      if (state.pendingSubmission?.request !== canonical)
        state.pendingSubmission = {request:canonical,key:crypto.randomUUID()};
      const pending = state.pendingSubmission;
      const job = await api('jobs',{request:pinned,idempotencyKey:pending.key},token.signal);
      if (state.pendingSubmission === pending) state.pendingSubmission = null;
      await monitor(job,token);
    });
  });
  $('reopen-form').addEventListener('submit',event => {
    event.preventDefault();
    guarded('Opening saved comparison…', async token => {
      const id = $('saved-id').value.trim();
      if (!idPattern.test(id)) throw new Error('Enter a full SHA-256 result or job ID.');
      if (event.submitter?.value === 'job') await monitor(await api('jobs/' + id,undefined,token.signal),token);
      else await openResult(id,token);
    });
  });
  for (const action of ['cancel','resume']) $(action + '-comparison').addEventListener('click',() => {
    const id = state.job?.job;
    if (id) guarded(action === 'cancel' ? 'Cancelling comparison…' : 'Resuming comparison…',
      async token => monitor(await api('jobs/' + id + '/' + action,{},token.signal),token));
  });
  for (const side of ['baseline','target']) $(side).addEventListener('change',() => selectionChanged(side));
  $('mode').addEventListener('change',inputChanged);
  for (const id of ['baseline-id','target-id','repository-id']) $(id).addEventListener('input',inputChanged);
  $('swap').addEventListener('click',() => {
    const a = $('baseline'), b = $('target'), c = $('baseline-id'), d = $('target-id');
    [a.value,b.value] = [b.value,a.value]; [c.value,d.value] = [d.value,c.value];
    const pinned = state.request;
    inputChanged();
    if (pinned) {
      state.request = {...pinned,comparison:{...pinned.comparison,left:pinned.comparison.right,right:pinned.comparison.left},
        reports:(pinned.reports || []).map(ref => ({...ref,side:ref.side === 'LEFT' ? 'RIGHT' : 'LEFT'})),
        bridges:(pinned.bridges || []).map(ref => ({...ref,side:ref.side === 'LEFT' ? 'RIGHT' : 'LEFT'})),history:null};
      if (pinned.history) status('Pair reversed. The declared forward history was cleared.');
    }
  });
  $('request-file').addEventListener('change',async () => {
    const file = $('request-file').files[0];
    if (!file) return;
    const token = begin('Reading pinned request…');
    try {
      if (file.size > 65_536) throw new Error('The request file exceeds 64 KiB.');
      const value = JSON.parse(await file.text());
      if (token.signal.aborted) return;
      if (value.schema !== 'architecture.evolution.request:v1' || !value.comparison)
        throw new Error('Choose a version 1 comparison request.');
      state.request = value;
      $('baseline').value = $('target').value = '';
      $('baseline-id').value = value.comparison.left;
      $('target-id').value = value.comparison.right;
      $('repository-id').value = value.comparison.repository;
      $('mode').value = value.comparison.mode;
      status('Pinned request loaded. Run comparison to verify the inputs.');
    } catch (error) { if (!token.signal.aborted) status(error.message); }
  });
  $('more-analyses').addEventListener('click',catalog);
  $('comparison-section').addEventListener('change',listenPage);
  $('comparison-filters').addEventListener('submit',event => {event.preventDefault();listenPage();});
  $('comparison-more').addEventListener('click',() => synchronizePage(state.page.continuation,1));
  $('comparison-back').addEventListener('click',() => synchronizePage(state.previous.at(-1),-1));
  $('comparison-export').addEventListener('click',async () => {
    const selected = state.result, token = state.controller, page = state.pageController;
    try {
      const query = {...state.activeQuery,...(state.cursor ? {cursor:state.cursor} : {})};
      const payload = await envelope(apiRoot + 'results/' + selected.result + '/export',query,token.signal);
      if (token.signal.aborted || page !== state.pageController) return;
      const url = URL.createObjectURL(new Blob([JSON.stringify(payload)],{type:'application/json'}));
      const link = strong('a'); link.href = url; link.download = selected.result.slice(7,19) + '-comparison.json'; link.click();
      setTimeout(() => URL.revokeObjectURL(url),0);
    } catch (error) { if (!token.signal.aborted) status(error.message); }
  });
  $('compare-theme')?.addEventListener('click',() => {
    const theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = theme;
    $('compare-theme').textContent = theme === 'dark' ? 'Light theme' : 'Dark theme';
  });
  catalog();
  const saved = new URLSearchParams(window.location.search).get('result');
  if (saved && idPattern.test(saved)) guarded('Opening saved comparison…', token => openResult(saved,token));
}
window.mountEvoEvolution = mountEvolution;
if (document.body.classList.contains('evolution-page')) mountEvolution(document);
