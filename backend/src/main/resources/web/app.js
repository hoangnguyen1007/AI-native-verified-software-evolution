/* API-backed presentation only. No synthetic repositories, findings or scores. */
(() => {
  'use strict';
  const M = window.EvoModel, $ = id => document.getElementById(id);
  const state = {setups: [], connections: [], jobs: [], labels: new Map(), setup: null, job: null, graph: null, contexts: [], reports: [], context: '', tab: 'overview', epoch: 0, pollGeneration: 0, historyLimit: 20, cy: null, online: false, retry: null};
  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = String(text); return n; };
  const append = (parent, ...children) => { children.filter(Boolean).forEach(child => parent.append(child)); return parent; };
  const safe = async action => { try { await action(); } catch (error) { if (error.name !== 'AbortError') notice(error.message, true); } };
  const btn = (text, action, cls = 'button') => { const n = el('button', cls, text); n.type = 'button'; n.addEventListener('click', () => safe(action)); return n; };
  const badge = text => el('span', 'badge' + (/FAILED|CANCELLED|TIMED_OUT/i.test(text) ? ' bad' : /PARTIAL|UNKNOWN|MAY|WITHHELD/i.test(text) ? ' warn' : /COMPLETE|SUCCEEDED|MUST/i.test(text) ? ' good' : ''), text);
  const empty = (title, text) => append(el('div', 'empty'), el('h3', '', title), el('p', '', text));
  const card = (title, hint) => append(el('div', 'card'), append(el('div', 'card-heading'), append(el('div'), el('h2', '', title), el('p', 'muted', hint))));
  function selectControl(label, options, value) {
    const select = el('select'); select.setAttribute('aria-label', label);
    for (const [id, text] of options) { const option = el('option', '', text); option.value = id; select.append(option); }
    if (value != null) select.value = value; return select;
  }
  const field = (label, control) => append(el('label', 'control-field'), el('span', 'small muted', label), control);
  const relationshipKinds = scope => scope==='SPRING_COMPONENT'?['spring.selected-binding']:scope==='ENTITY'?['java.calls','java.constructor-calls','java.reads-field','java.writes-field','java.type-uses','java.returns','java.field-type','java.parameter-type','java.throws','java.extends','java.implements','java.method-references','java.type-parameter-bound','java.type-argument']:['graph.depends-on'];
  async function resolveLabels(identities,env){
    const pending=[...new Set(identities)].filter(id=>!state.labels.has(id));
    for(let offset=0;offset<pending.length&&!env.signal.aborted;offset+=3){
      await Promise.allSettled(pending.slice(offset,offset+3).map(async identity=>{const result=await env.query('entity',{identity});const value=result.items[0];if(value&&!env.signal.aborted)state.labels.set(identity,M.label(value));}));
    }
  }
  function configureRules() {
    const connection = state.connections.find(c => c.setup === state.setup);
    $('repository-source').value = connection?.kind === 'GIT_HTTPS' ? 'https://' + repoLabel(state.setup) : '';
    $('connect-title').textContent = 'Configure rules & scoring'; $('policy-settings').open = true;
    $('connect-error').hidden = true; $('connect-dialog').showModal();
  }
  function notice(message, error = false) { $('notice').hidden = !message; $('notice').textContent = message || ''; $('notice').classList.toggle('error', error); }
  async function api(path, body, signal) {
    const timeout = AbortSignal.timeout(30000), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let response;
    try { response = await fetch('/api/v1' + path, {method: body === undefined ? 'GET' : 'POST', headers: {Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, ...(body === undefined ? {} : {body: JSON.stringify(body)}), signal: combined}); }
    catch (error) { if (signal?.aborted) throw error; throw new Error(error.name === 'TimeoutError' ? 'The server took too long. Retry or narrow the request.' : 'Cannot reach the analysis server. Check that it is running, then refresh.'); }
    const envelope = await response.json();
    if (!response.ok || envelope.error) throw new Error(M.apiError(envelope.error?.code));
    return envelope.data;
  }
  const repoLabel = id => state.connections.find(c => c.setup === id)?.label || id;
  function invalidate() { state.epoch++; state.view?.abort(); state.cy?.destroy(); state.cy = null; closeInspector(); $('view-status').textContent = ''; }
  function resetResult() { invalidate(); state.graphFocus = null; state.explorer = null; state.graph = null; state.context = ''; state.contexts = []; state.reports = []; $('analysis-workspace').hidden = true; $('welcome').hidden = false; }
  function renderSidebar() {
    const repos = $('repositories'); repos.replaceChildren();
    if (!state.setups.length) repos.append(el('p', 'muted small', state.online ? 'No repositories connected yet.' : 'Server unavailable. Refresh to retry.'));
    for (const setup of state.setups) {
      const row = btn('', () => selectRepo(setup.id), 'sidebar-item' + (state.setup === setup.id ? ' active' : ''));
      const sourceKind = state.connections.find(c => c.setup === setup.id)?.kind === 'GIT_HTTPS' ? 'Public Git repository' : 'Registered local source';
      append(row, el('strong', '', repoLabel(setup.id)), el('small', '', sourceKind + (setup.policy ? ' · Rules configured' : ''))); repos.append(row);
    }
    const history = $('history'); history.replaceChildren();
    const jobs = state.jobs.filter(job => !state.setup || job.setup === state.setup);
    if (!jobs.length) history.append(el('p', 'muted small', 'No analyses in this session.'));
    for (const job of jobs.slice(0, state.historyLimit)) {
      const row = btn('', () => selectJob(job), 'sidebar-item' + (state.job?.id === job.id ? ' active' : ''));
      append(row, el('strong', '', repoLabel(job.setup)), el('small', '', new Date(job.submittedAt).toLocaleString([], {month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'})), badge(job.state)); history.append(row);
    }
    $('history-more').hidden = jobs.length <= state.historyLimit;
  }
  async function refreshHistory(more = false) {
    if (!state.online || !state.capabilities?.analysisJobsEnabled) return;
    if (more) { state.historyLimit += 20; renderSidebar(); return; }
    let cursor, jobs = [];
    do { const page = await api('/analysis/jobs/history', {size: 50, ...(cursor ? {cursor} : {})}); jobs.push(...page.items); cursor = page.continuation; } while (cursor);
    state.jobs = jobs;
    state.jobs.sort((a, b) => b.submittedAt.localeCompare(a.submittedAt));
    renderSidebar();
  }
  async function connectServer() {
    notice('');
    try {
      state.capabilities = await api(''); state.online = true;
      $('connection-status').textContent = 'Local server connected'; $('connection-dot').classList.add('online');
      state.setups = state.capabilities.analysisJobsEnabled ? (await api('/analysis/setups')).items : [];
      state.connections = state.capabilities.repositoryConnectionsEnabled ? (await api('/analysis/connections')).items : [];
      document.querySelectorAll('[data-action=connect]').forEach(n => { n.disabled = !state.capabilities.repositoryConnectionsEnabled; n.title = n.disabled ? 'Repository connections are not enabled by this server.' : ''; });
      await refreshHistory();
      if (state.setup && !state.setups.some(setup => setup.id === state.setup)) {
        state.setup = null; state.job = null; resetResult(); $('job-panel').hidden = true;
        $('repository-title').textContent = 'Explore your architecture'; $('repository-description').textContent = 'Connect a repository, run an analysis, then follow the evidence.';
      } else if (state.job && !state.jobs.some(job => job.id === state.job.id)) {
        await selectRepo(state.setup);
      }
      let saved; try { saved = localStorage.getItem('evo_repository'); } catch (_) {}
      if (!state.setup && state.setups.some(setup => setup.id === saved)) await selectRepo(saved);
      else if (!state.setup && state.setups.length === 1) await selectRepo(state.setups[0].id); else renderSidebar();
      if (!state.capabilities.analysisJobsEnabled) notice('This server is read-only. Start the workbench server to connect repositories and run analyses.');
    } catch (error) { state.online = false; $('connection-status').textContent = 'Server unavailable'; $('connection-dot').classList.remove('online'); $('analyze').disabled = true; renderSidebar(); notice(error.message, true); }
  }
  async function selectRepo(id) {
    state.setup = id; state.job = null; state.retry = null; state.pollGeneration++; resetResult(); $('job-panel').hidden = true;
    try { localStorage.setItem('evo_repository', id); } catch (_) {}
    $('search').value = ''; state.tab = 'overview'; state.historyLimit = 20;
    $('welcome').querySelector('h2').textContent = 'Ready to inspect your code';
    $('welcome').querySelector('p').textContent = 'Run an analysis to capture the current source, then explore its architecture and evidence.';
    $('welcome').querySelector('button').textContent = 'Connect another repository';
    $('repository-title').textContent = repoLabel(id); $('repository-description').textContent = 'Run an analysis to inspect this repository. Results stay linked to their captured source.';
    $('analyze').disabled = !state.online; $('analyze').textContent = 'Run analysis ↗';
    const latest = state.jobs.find(job => job.setup === id); renderSidebar(); document.querySelector('.app-shell').classList.remove('mobile-sidebar-open');
    if (latest) await selectJob(latest);
  }
  async function connectRepository(event) {
    event.preventDefault(); $('connect-error').hidden = true; $('connect-submit').disabled = true;
    try {
      const policy = $('repository-policy').value;
      const connection = await api('/analysis/connections', {source: $('repository-source').value.trim(), ...(policy.trim() ? {policy} : {})});
      state.connections = (await api('/analysis/connections')).items; state.setups = (await api('/analysis/setups')).items;
      $('connect-dialog').close(); await selectRepo(connection.setup); notice('Repository connected. Run an analysis to capture its current source.');
    } catch (error) { $('connect-error').textContent = error.message; $('connect-error').hidden = false; $('repository-source').focus(); }
    finally { $('connect-submit').disabled = false; }
  }
  async function submit() {
    if (!state.setup) return;
    $('analyze').disabled = true; notice(''); const setup = state.setup;
    state.retry ||= {setup, idempotencyKey: crypto.randomUUID()};
    try { const job = await api('/analysis/jobs', state.retry); state.retry = null; if (state.setup === setup) await selectJob(job); await refreshHistory(); }
    catch (error) { if (state.setup === setup) $('analyze').textContent = 'Retry submission'; throw error; }
    finally { if (!state.job || M.terminal(state.job)) $('analyze').disabled = !state.online; }
  }
  function renderJob(job) {
    $('job-panel').hidden = false; $('welcome').hidden = true;
    $('job-state').replaceWith(Object.assign(badge(job.state), {id: 'job-state'})); $('job-title').textContent = M.terminal(job) ? 'Analysis result' : 'Analysis in progress';
    const messages = {PARTIAL: 'Some evidence is missing. Inspect limitations before drawing conclusions.', FAILED: 'Analysis failed. Open input diagnostics and the receipt for the recorded reason.', EMPTY: 'No analyzable Java source was found in this selection.', CANCELLED: 'Analysis was cancelled. No completed result is claimed.', TIMED_OUT: 'Analysis reached its execution limit.', SUCCEEDED: 'Analysis completed. Completion does not mean the architecture has no issues.'};
    $('job-description').textContent = (messages[job.state] || (job.state === 'CANCELLING' ? 'Cancellation requested. Waiting for the worker to stop.' : 'Current step: ' + job.stage.toLowerCase() + '.')) + ' · ' + (job.elapsedMillis / 1000).toFixed(1) + ' s' + (job.result ? ' · ' + job.result.gapCount + ' recorded limitations' : '');
    if (job.result?.graph) $('repository-description').textContent = 'Captured ' + new Date(job.finishedAt || job.submittedAt).toLocaleString() + ' · Run a new analysis after changing the source.';
    $('cancel-job').hidden = M.terminal(job); $('cancel-job').disabled = job.state === 'CANCELLING'; $('analyze').disabled = !M.terminal(job);
    $('job-diagnostics').disabled = !M.terminal(job); $('job-gaps').disabled = !M.terminal(job);
    $('job-events').replaceChildren(...job.events.map(event => el('li', '', event.stage.toLowerCase())));
  }
  async function selectJob(job) {
    state.job = job; state.setup = job.setup; state.retry = null; state.pollGeneration++; resetResult(); $('repository-title').textContent = repoLabel(job.setup); renderJob(job); renderSidebar();
    if (job.result?.graph) await loadGraph(job.result.graph, job.id);
    if (!M.terminal(job)) poll(job.id, state.pollGeneration);
  }
  async function poll(id, generation) {
    if (state.job?.id !== id || generation !== state.pollGeneration) return;
    try {
      const job = await api('/analysis/jobs/' + id); if (state.job?.id !== id || generation !== state.pollGeneration) return;
      state.job = job; state.jobs = state.jobs.map(item => item.id === id ? job : item); renderJob(job); renderSidebar();
      if (M.terminal(job)) { if (job.result?.graph) await loadGraph(job.result.graph, id); await refreshHistory(); }
      else setTimeout(() => safe(() => poll(id, generation)), 1200);
    } catch (error) { if (state.job?.id === id && generation === state.pollGeneration) { notice(error.message + ' Use Refresh history to reconnect.', true); $('analyze').disabled = true; } }
  }
  async function loadGraph(graph, jobId) {
    const discovery = await api('/graphs/' + graph); if (state.job?.id !== jobId) return;
    state.graph = graph; state.contexts = discovery.contexts; state.reports = [];
    for (const id of discovery.reports) {
      const report = await api('/graphs/' + graph + '/reports/' + id);
      if (state.job?.id !== jobId) return;
      state.reports.push(report);
    }
    $('analysis-workspace').hidden = false; $('welcome').hidden = true;
    $('context').replaceChildren(el('option', '', 'Choose a context…')); $('context').firstChild.value = ''; let index = 0;
    for (const context of state.contexts) { const option = el('option', '', context.analysis ? 'Java analysis ' + (++index) : 'Repository / acquisition'); option.value = context.identity; option.title = context.identity; $('context').append(option); }
    state.context = state.contexts.length === 1 ? state.contexts[0].identity : ''; $('context').value = state.context;
    await loadTab(state.tab);
    // Optional human-readable labels come only from recorded canonical provenance.
    for (const context of state.contexts) {
      try {
        const value = await api('/graphs/' + graph + '/queries/provenance', {request: M.request(context.identity), arguments: {}});
        if (state.job?.id !== jobId || state.graph !== graph) return;
        const evidence=value.items?.[0],build = evidence?.metadata?.build?.moduleSourceSet;
        state.moduleLabels ||= new Map();if(evidence?.metadata?.module&&evidence.metadata.modulePath)state.moduleLabels.set(evidence.metadata.module,evidence.metadata.modulePath);
        state.contextKinds ||= new Map();state.contextKinds.set(context.identity,evidence?.family);
        const option = [...$('context').options].find(n => n.value === context.identity);
        if(option&&build){const module=state.moduleLabels.get(build.module)||(build.module?.includes('sha256:')?null:build.module);option.textContent=[evidence.metadata.modulePath||module||(evidence.family==='spring.context'?'Spring configuration':'Java analysis'),evidence.family==='spring.context'&&module?'Spring':null,build.sourceSet?.toLowerCase()].filter(Boolean).join(' / ');}
        else if(option&&evidence?.metadata?.modulePath)option.textContent=evidence.metadata.modulePath+' / '+(evidence.metadata.sourceSet?.toLowerCase()||'Java');
      } catch (_) { /* Generic explicit context labels remain available if metadata cannot be read. */ }
    }
  }
  function envFor(signal) {
    const graph = state.graph, context = state.context, request = M.request(context), reportIds = state.reports.filter(r => r.context === context);
    return {graph, context, request, reportIds, signal, jobId: state.job?.id,springContext:state.contextKinds?.get(context)==='spring.context',
      query: (op, args = {}, page, overrides = {}) => api('/graphs/' + graph + '/queries/' + op, {request: {...request, ...overrides}, arguments: args, ...(page ? {page} : {})}, signal),
      report: (id, op, args = {}, page) => api('/graphs/' + graph + '/reports/' + id + '/' + op, {arguments: args, ...(page ? {page} : {})}, signal)};
  }
  async function loadTab(tab) {
    invalidate(); state.tab = tab; const epoch = state.epoch;
    document.querySelectorAll('[role=tab]').forEach(n => { const selected = n.dataset.tab === tab; n.setAttribute('aria-selected', selected); n.tabIndex = selected ? 0 : -1; });
    document.querySelectorAll('[role=tabpanel]').forEach(n => n.hidden = n.id !== 'panel-' + tab); const panel = $('panel-' + tab);
    if (!state.context) { panel.replaceChildren(empty('Choose an analysis context', 'Select the module or repository context above. Results are scoped to that selection.')); return; }
    state.view = new AbortController(); const env = envFor(state.view.signal); panel.setAttribute('aria-busy','true');$('view-status').textContent='Loading '+document.querySelector('[data-tab="'+tab+'"]').textContent+'…';panel.replaceChildren(empty('Loading…', 'Reading the captured analysis.'));
    try { const view = await views[tab](env); if (epoch !== state.epoch) return; panel.replaceChildren(view.node || view); panel.setAttribute('aria-busy','false');$('view-status').textContent = view.status || 'View loaded for the selected analysis context.'; view.after?.(); }
    catch (error) { if (epoch === state.epoch && error.name !== 'AbortError') { panel.setAttribute('aria-busy','false');panel.replaceChildren(empty('Unable to load this view', error.message), btn('Retry', () => loadTab(tab))); $('view-status').textContent = 'Request failed. Retry to read this analysis.'; } }
  }
  function row(item, action, subtitle) {
    if (item.identity) state.labels.set(item.identity, M.label(item));
    const moduleName = item.module && !item.module.includes('sha256:') ? item.module : null;
    return append(btn('', action, 'entity-row'), append(el('div'), el('strong', '', M.label(item)), el('p', '', subtitle || [moduleName, item.origin === 'PROJECT' ? 'Project source' : item.origin, item.sourceSet === 'MAIN' ? 'Main' : item.sourceSet].filter(Boolean).join(' · '))), badge((item.kind || 'Details').toLowerCase().replaceAll('_', ' ')));
  }
  function pagedList(container, env, fetchPage, onRow, title, hint) {
    const list = el('div', 'list'), footer = el('div', 'page-footer');footer.setAttribute('role','status');footer.setAttribute('aria-live','polite');let next, items = [], busy = false; container.append(list, footer);
    const more = btn('Load more', () => load(true));
    async function load(continuing = false) {
      if (busy) return; busy = true; more.disabled = true;
      try { const result = await fetchPage(continuing ? next : null); if (env.signal.aborted) return;
        if (!continuing) { items = []; list.replaceChildren(); } items.push(...result.items); next = result.continuation;
        if (!items.length) list.append(empty(title || 'No results available', hint || 'Check the selected context and analysis limitations.')); else result.items.forEach(item => list.append(onRow(item)));
        footer.replaceChildren(el('span', '', M.pageInfo({...result, items}))); if (next) footer.append(more);
      } finally { busy = false; more.disabled = false; }
    }
    return load();
  }
  async function inspect(title, kind, loader, actions = [], previousItems = [], cursor) {
    state.inspector?.abort(); state.inspector = new AbortController(); const controller = state.inspector;
    state.details = null;
    $('inspector-title').textContent = title; $('inspector-kind').textContent = kind; $('inspector-body').replaceChildren(el('p', 'muted', 'Loading evidence…')); $('download-details').disabled = true;
    if (!$('inspector').open) $('inspector').showModal();
    const contextEnv = state.graph && state.context ? envFor(controller.signal) : null;
    try { const page = typeof loader === 'function' ? await loader(controller.signal, cursor) : loader; if (controller.signal.aborted) return;
      const value = page?.items && previousItems.length ? {...page, items: [...previousItems, ...page.items]} : page;
      state.details = value; const body = $('inspector-body'); body.replaceChildren(); const fields = el('dl', 'data-fields');
      for (const [key, val] of Object.entries(value || {})) if (val != null && typeof val !== 'object' && !['query','identity','id','setup','graph','snapshot','acquisition','sourceReport','continuation'].includes(key)) append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), el('dd', '', val)));
      body.append(fields);
      if (Array.isArray(value?.items)) {
        body.append(el('p', 'muted small', M.pageInfo(value)));
        for (const [index, item] of value.items.entries()) {
          const section = el(item.reasonCode ? 'details' : 'div', 'evidence-item');
          const title = item.reasonCode ? item.reasonCode.toLowerCase().replaceAll('_', ' ') + ' · ' + item.mechanismCategory : item.vertices ? 'Dependency path ' + (index + 1) : item.family || item.kind || 'Evidence ' + (index + 1);
          section.append(el(item.reasonCode ? 'summary' : 'h3', '', title));
          if (item.vertices && contextEnv) {
            section.append(el('p', 'muted small', item.edges.length + ' dependency steps · ' + (item.feasibility === 'SAT' ? 'Feasible in the modeled configuration space' : 'Configuration feasibility: ' + item.feasibility)));
            const path = el('ol', 'dependency-path');
            item.vertices.forEach((identity, step) => path.append(append(el('li'), btn(state.labels.get(identity) || 'Inspect symbol ' + (step + 1), async () => {
              const entity = await contextEnv.query('entity', {identity});
              if (controller.signal.aborted) return;
              if (entity.items[0]) return inspectEntity(entity.items[0], contextEnv);
              return inspect('Symbol evidence', 'ENTITY', entity);
            }, 'text-button')))); section.append(path);
          } else if(item.span&&item.sourceDigest){
            const span=item.span;section.append(el('p','', (state.labels.get(span.document)||'Captured source')+' · '+span.startLine+':'+span.startColumn+' → '+span.endLine+':'+span.endColumn));
            const detail=append(el('details'),el('summary','','Source identities & coordinates'));renderFields(detail,item,0);section.append(detail);
          } else renderFields(section, item.metadata || item, 0);
          body.append(section);
        }
        if (!value.items.length) body.append(empty('No records returned', 'Read the status and limitations before interpreting an empty result.'));
        if (value.continuation && typeof loader === 'function') body.append(btn('Load more evidence', () => inspect(title, kind, loader, actions, value.items, value.continuation)));
      } else renderFields(body, value, 0, true);
      if (actions.length) body.append(append(el('div', 'detail-actions'), ...actions.map(action => btn(action.label, action.run, 'text-button'))));
      body.append(append(el('details'), el('summary', '', 'Recorded data and source coordinates'), el('pre', '', JSON.stringify(value, null, 2)))); $('download-details').disabled = false;
    } catch (error) { if (!controller.signal.aborted) $('inspector-body').replaceChildren(empty('Details unavailable', error.message)); }
  }
  function renderFields(parent, value, depth, objectsOnly = false) {
    if (!value || typeof value !== 'object') return;
    const fields = el('dl', 'data-fields');
    for (const [key, val] of Object.entries(value)) {
      if (val == null || objectsOnly && typeof val !== 'object') continue;
      if (typeof val !== 'object') append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), el('dd', '', ['source','target'].includes(key) ? state.labels.get(val) || val : val)));
      else if (depth < 3 && Object.keys(val).length) {
        const detail = append(el('details'), el('summary', '', key.replace(/([A-Z])/g, ' $1')));
        if (['entity', 'span', 'sourceSpan', 'declaration', 'path', 'confidence', 'dimensions'].includes(key)) detail.open = true;
        renderFields(detail, val, depth + 1); fields.append(detail);
      } else if (Object.keys(val).length) fields.append(append(el('details'), el('summary', '', key), el('pre', '', JSON.stringify(val, null, 2))));
    }
    parent.append(fields);
  }
  function closeInspector() { state.inspector?.abort(); state.inspector = null; if ($('inspector').open) $('inspector').close(); }
  function inspectEntity(item, env) {
    const query = (op, args, signal, override = {}, cursor) => api('/graphs/' + env.graph + '/queries/' + op, {request: {...env.request, ...override}, arguments: args, ...(['explain', 'dependencies'].includes(op) ? {page: {size: 20, ...(cursor ? {cursor} : {})}} : {})}, signal);
    const scope = ['TYPE', 'MODULE', 'PACKAGE'].includes(item.kind) ? item.kind : item.kind==='BEAN_DEFINITION_CANDIDATE'?'SPRING_COMPONENT':['METHOD','CONSTRUCTOR','FIELD','PARAMETER','RECORD_COMPONENT'].includes(item.kind) ? 'ENTITY' : null;
    const actions = scope ? [
      {label: 'Dependencies', run: () => inspect(M.label(item), 'DEPENDENCIES', (signal, cursor) => query('dependencies', {identity: item.identity}, signal, {scope,relationshipKinds:relationshipKinds(scope)}, cursor))},
      {label: 'Dependents', run: () => inspect(M.label(item), 'INCOMING DEPENDENCIES', (signal, cursor) => query('dependencies', {identity: item.identity}, signal, {scope,relationshipKinds:relationshipKinds(scope), direction: 'INCOMING'}, cursor))},
      {label: 'Change impact', run: () => inspect(M.label(item), 'POTENTIAL STRUCTURAL IMPACT', signal => query('impact', {identity: item.identity}, signal, {scope,relationshipKinds:relationshipKinds(scope), direction: 'INCOMING'}))},
      {label: 'Focused graph', run: async () => { state.graphFocus = {seed: item.identity, scope}; closeInspector(); await loadTab('graph'); }},
      {label: 'Metric details', run: async () => { state.metricSubject = item; closeInspector(); await loadTab('metrics'); }}
    ] : [];
    return inspect(M.label(item), item.kind || 'ENTITY', (signal, cursor) => query('explain', {identity: item.identity}, signal, {}, cursor), actions);
  }
  function downloadBlob(blob,name){const url=URL.createObjectURL(blob),link=el('a');link.href=url;link.download=name;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);}
  function download(value, name) { downloadBlob(new Blob([JSON.stringify(value, null, 2)], {type: 'application/json'}),name); }
  async function inspectFinding(item, reportId, env) {
    const finding=item.result,bundle=finding.bundle,rule=finding.rule?.header||finding.rule,actions=[];
    if(bundle){
      actions.push({label:'Exact source evidence',run:async()=>{
        const sourceContext=bundle.analysis&&state.contexts.some(context=>context.identity===bundle.analysis)?bundle.analysis:env.context;
        await resolveLabels(bundle.sources.map(source=>source.span.document),{...env,query:(op,args,page,overrides)=>env.query(op,args,page,{...overrides,context:sourceContext})});
        if(!env.signal.aborted)return inspect('Finding sources','SOURCE EVIDENCE',{items:bundle.sources,status:bundle.status});
      }});
      for(const [counter,label] of [[false,'Replay finding witness'],[true,'Replay counterexample']]){
        if(!(counter?bundle.region.counterWitness:bundle.region.witness))continue;
        actions.push({label,run:async()=>{
          await inspect(label,'MODEL REPLAY',signal=>api('/graphs/'+env.graph+'/reports/'+reportId+'/replay',{arguments:{identity:item.identity,counterWitness:counter}},signal));
          if(!state.inspector?.signal.aborted&&state.details?.items?.[0]?.actualTruth){
            const value=state.details?.items?.[0];
            $('inspector-body').prepend(append(el('div','notice'),el('strong','',value?.matched?'Witness matches the captured model':value?.actualTruth==='UNKNOWN'?'Replay could not establish the result':'Witness does not match'),el('p','small','Checks the stored rule predicate at its recorded configuration. This does not start Spring or execute repository code.')));
          }
        }});
      }
      actions.push({label:'Finding path',run:()=>inspect('Finding path','DEPENDENCY EVIDENCE',{items:[{vertices:bundle.path,edges:bundle.relationships,feasibility:bundle.region.classification==='UNKNOWN'?'UNKNOWN':'SAT'}],status:bundle.status})});
      if(bundle.path.length)actions.push({label:'Show finding graph',run:()=>{state.graphFocus={seed:bundle.path[0],scope:bundle.request.scope,relationshipKinds:bundle.request.relationshipKinds};closeInspector();return loadTab('graph');}});
    }
    return inspect(rule?.name||rule?.id||'Rule evidence','POLICY EVIDENCE',finding,actions);
  }
  const views = {
    async overview(env) {
      const summary = await env.query('summary'), node = el('div'), counts = summary.items?.[0], stats = el('div', 'stats');
      for (const [label, kind] of [['Types & references', 'TYPE'], ['Modules', 'MODULE'], ['Source documents', 'SOURCE_DOCUMENT'], ['Recorded gaps', 'CAPABILITY_GAP']]) {
        const stat = btn('', () => loadTab(label === 'Recorded gaps' ? 'gaps' : 'structure'), 'stat');
        append(stat, el('span', '', label), el('strong', '', counts?.nodeCounts?.[kind] ?? (counts ? 0 : '—')), el('small', '', summary.status === 'COMPLETE' ? 'In the selected context' : 'Observed · incomplete evidence')); stats.append(stat);
      }
      node.append(stats); const grid = el('div', 'content-grid'); node.append(grid);
      const health = card('Architecture assessment', 'Based on the selected policy and the evidence available for this analysis.'); grid.append(health);
      if (env.reportIds.length !== 1) health.append(empty(env.reportIds.length ? 'Select a policy report' : 'Score unavailable — no rules configured', env.reportIds.length ? 'Open Metrics & score to choose an exact report.' : 'A score needs explicit architecture rules and enough evidence. Structural metrics are available without a policy.'),btn(env.reportIds.length?'Open Metrics & score':'Configure rules & scoring',()=>env.reportIds.length?loadTab('metrics'):configureRules()));
      else {
        try { const assessment = await env.report(env.reportIds[0].identity, 'assessment'); health.append(el('div', 'score', M.assessment(assessment)), badge(assessment.status));
          if (assessment.confidence) health.append(el('p', 'muted small', assessment.confidence.sufficient ? 'Required assessment evidence is sufficient.' : 'Required assessment evidence is incomplete.'));
          for (const dimension of assessment.dimensions || []) health.append(append(el('div', 'dimension'), el('span', '', dimension.id), el('strong', '', dimension.score ?? 'Unavailable')));
          health.append(btn('View score breakdown', () => loadTab('metrics'), 'text-button'));
        } catch (error) { health.append(empty('Assessment unavailable', error.message)); }
      }
      const guide = card('Follow the evidence', 'Start with a class, inspect its dependencies, then review limitations.'); grid.append(guide);
      for (const [label, tab] of [['Browse captured structure →', 'structure'], ['Explore dependency graph →', 'graph'], ['Compare modeled configurations →','configurations'],['Inspect metrics & score →','metrics'], ['Review rules and findings →', 'policy'], ['Inspect missing evidence →', 'gaps']]) guide.append(btn(label, () => loadTab(tab), 'entity-row'));
      const inventory = append(el('details', 'card'), el('summary', '', 'Captured inventory — view all counts'));
      for (const [kind, count] of Object.entries(counts?.nodeCounts || {})) inventory.append(append(el('div', 'dimension'), el('span', '', kind.toLowerCase().replaceAll('_', ' ')), el('strong', '', count)));
      inventory.style.marginTop = '20px'; node.append(inventory); return {node, status: summary.status === 'COMPLETE' ? 'Selected context · Evidence complete' : 'Selected context · Incomplete evidence — review limitations'};
    },
    async structure(env) {
      const node = card('Repository structure', 'Open a module, package or class to browse its recorded contents. Use Details for source locations, dependencies and impact.');
      const level = selectControl('Browse level', [['MODULE','Modules'],['PACKAGE','Packages'],['TYPE','Classes'],['MEMBER','Members'],['SOURCE_DOCUMENT','Source files'],['SOURCE_ROOT','Source roots']], $('search').value.trim() ? 'TYPE' : 'MODULE');
      const population = selectControl('Symbol population', [['all','All captured origins'],['PROJECT','Project code'],['JDK','JDK types'],['DEPENDENCY','External dependencies'],['SYNTHETIC','Synthetic evidence']]);
      const sourceSet = selectControl('Source set', [['','All source sets'],['MAIN','Main'],['TEST','Test'],['GENERATED_MAIN','Generated main'],['GENERATED_TEST','Generated test']]);
      const breadcrumbs = el('nav', 'breadcrumbs'); breadcrumbs.setAttribute('aria-label','Structure location');
      const selection = el('div', 'selection-bar'), results = el('div');
      node.append(append(el('div','toolbar'),field('Browse',level),field('Origin',population),field('Source set',sourceSet)),breadcrumbs,selection,results);
      let version = 0, trail = state.explorer?.context === env.context ? state.explorer.trail : [];
      const memberKinds = ['METHOD','CONSTRUCTOR','FIELD','PARAMETER','TYPE_PARAMETER','RECORD_COMPONENT','INITIALIZER','LAMBDA'];
      async function load() {
        const current = ++version, search = $('search').value.trim(), target = el('div'), parent = search ? null : trail.at(-1);
        state.explorer = {context:env.context,trail}; breadcrumbs.replaceChildren(btn('Repository',()=>{trail=[];$('search').value='';return load();},'text-button'));
        trail.forEach((item,index)=>breadcrumbs.append(el('span','','/'),btn(M.label(item),()=>{trail=trail.slice(0,index+1);$('search').value='';return load();},'text-button')));
        selection.replaceChildren(); if(parent)selection.append(el('strong','',M.label(parent)),btn('Details',()=>inspectEntity(parent,env)),btn('Back',()=>{trail.pop();return load();}));
        if(search) selection.append(el('p','muted small','Search results across the selected context: '+search),btn('Clear search',()=>{$('search').value='';return load();}));
        results.replaceChildren(empty('Loading…', 'Reading matching symbols.'));
        const overrides = {scope: parent ? 'ENTITY' : ['MODULE','PACKAGE','TYPE'].includes(level.value) ? level.value : 'ENTITY',
          population:{nodeKinds:parent?[]:level.value==='MEMBER'?memberKinds:[level.value],origins:population.value==='all'?[]:[population.value],sourceSets:sourceSet.value?[sourceSet.value]:[]}};
        try { await pagedList(target, env, cursor => env.query(parent?'children':'entities', parent?{identity:parent.identity}:{search}, {size:20,...(cursor?{cursor}:{})}, overrides), item => {
          const entry=el('div','explorer-row');const navigable=['MODULE','PACKAGE','TYPE','METHOD','CONSTRUCTOR','SOURCE_ROOT'].includes(item.kind);
          entry.append(row(item,()=>{if(navigable){trail.push(item);$('search').value='';return load();}return inspectEntity(item,env);}));
          entry.append(btn('Details',()=>inspectEntity(item,env),'button subtle'));return entry;
        }, 'No matching contents', 'Try another origin, source set or browse level. Missing evidence is listed under Limitations.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Symbols unavailable', error.message), btn('Retry', load)); }
      }
      level.addEventListener('change',()=>{trail=[];safe(load);});population.addEventListener('change',()=>safe(load));sourceSet.addEventListener('change',()=>safe(load)); await load(); return node;
    },
    async gaps(env) {
      const node = card('Analysis limitations', 'Missing or unsupported evidence is recorded explicitly. These entries explain what the analysis cannot establish.');
      const labels = new Map();
      if (env.jobId) {
        try { const supplemental = await api('/analysis/jobs/' + env.jobId + '/gaps', {size: 50}, env.signal);
          supplemental.items.forEach(gap => labels.set(gap.gapIdentity, gap.reasonCode.toLowerCase().replaceAll('_', ' ') + ' · ' + gap.mechanismCategory));
        } catch (error) { if (env.signal.aborted) throw error; /* Exact graph evidence remains accessible even without supplemental labels. */ }
      }
      await pagedList(node, env, cursor => env.query('gaps', {}, {size: 20, ...(cursor ? {cursor} : {})}), item => row({...item, label: labels.get(item.identity)}, () => inspectEntity(item, env)), 'No gap entries returned', 'Read the result status and input diagnostics as well. An empty page alone does not prove complete analysis.'); return node;
    },
    async policy(env) {
      const node = card('Rules & findings', 'Inspect the evaluated rules and their evidence. An absent report is not a clean architecture verdict.');
      if (!env.reportIds.length) { node.append(empty('No policy report for this context', 'Add architecture policy YAML when connecting the repository, then run a new analysis.')); return node; }
      const toolbar = el('div', 'toolbar'), reports = el('select'), kinds = el('select'); reports.setAttribute('aria-label', 'Policy report'); kinds.setAttribute('aria-label', 'Rules or findings');
      env.reportIds.forEach((report, index) => { const option = el('option', '', 'Policy report ' + (index + 1)); option.value = report.identity; reports.append(option); });
      for (const op of ['findings', 'rules']) { const option = el('option', '', op === 'rules' ? 'Evaluated rules' : 'Observed findings'); option.value = op; kinds.append(option); }
      toolbar.append(reports, kinds); node.append(toolbar); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, id = reports.value, op = kinds.value, target = el('div');
        results.replaceChildren(empty('Loading…', 'Reading the selected report.'));
        try { await pagedList(target, env, cursor => env.report(id, op, {}, {size: 20, ...(cursor ? {cursor} : {})}), item => {
          const finding = item.result, rule = finding.rule?.header || finding.rule;
          return row({label: rule?.name || rule?.id || finding.kind || 'Evaluated rule', kind: finding.classification || finding.status}, () => op==='findings'?inspectFinding(item,id,env):inspect(rule?.name || rule?.id || 'Rule evidence','POLICY EVIDENCE',finding), [rule?.severity, finding.status].filter(Boolean).join(' · '));
        }, op === 'findings' ? 'No observed findings' : 'No evaluated rules', 'Review the report status and rule denominator before interpreting this result.');
        if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Report unavailable', error.message), btn('Retry', load)); }
      }
      reports.addEventListener('change', () => safe(load)); kinds.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async spring(env) {
      const node = card('Spring components & configuration', 'Explore detected components, injection points and HTTP endpoints for the selected context.');
      const select = el('select'); select.setAttribute('aria-label', 'Spring evidence category');
      for (const [value, title] of [['components', 'Components and injection'], ['endpoints', 'HTTP endpoints'], ['registration','Registration decisions'],['configurations', 'Conditions, regions & witnesses']]) { const option = el('option', '', title); option.value = value; select.append(option); }
      node.append(btn('Compare configurations →',()=>loadTab('configurations'),'text-button'));
      node.append(append(el('div', 'toolbar'), select)); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, category = select.value, target = el('div');
        results.replaceChildren(empty('Loading…', 'Reading the selected Spring evidence.'));
        try { await pagedList(target, env, cursor => env.query(category === 'configurations' ? 'configurations' : 'spring', category === 'configurations' ? {} : {kinds: category === 'endpoints' ? ['ENDPOINT'] : category==='registration'?['REGISTRATION_EVENT','REGISTRATION_TRANSITION']:['BEAN_PRODUCER', 'BEAN_DEFINITION_CANDIDATE', 'INJECTION_POINT', 'INJECTION_USE']}, {size: 20, ...(cursor ? {cursor} : {})}), item => row(item, () => inspectEntity(item, env)), 'No evidence available in this context', 'Try another context or inspect limitations.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Spring evidence unavailable', error.message), btn('Retry', load)); }
      }
      select.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async configurations(env) {
      const node=card('Compare configurations','Compare dependencies within this captured analysis. Recorded configurations and modeled examples are distinct from a running application.');
      node.append(el('p','notice small','“All observed” can include alternatives that never coexist. “Required” keeps dependencies present in every modeled configuration. Missing evidence remains unknown.'));
      const options=[['MUST','Required in every configuration'],['MAY','Conditional dependencies'],['UNKNOWN_OVERLAY','Uncertain dependencies'],['BOUNDED_UNION','All observed dependencies']];
      const saved=state.comparison?.graph===env.graph&&state.comparison?.context===env.context?state.comparison:null;
      const a=selectControl('View A',options,'MUST'),b=selectControl('View B',options,'MAY'),scope=selectControl('Comparison scope',[['TYPE','Classes'],['PACKAGE','Packages'],['MODULE','Modules'],['SPRING_COMPONENT','Spring components']],saved?.scope||(env.springContext?'SPRING_COMPONENT':'TYPE'));
      const toolbar=append(el('div','toolbar'),field('View A',a),field('View B',b),field('Scope',scope));node.append(toolbar);
      let choices=[],cursor,version=0;const choiceStatus=el('p','small muted'),results=el('div');node.append(choiceStatus,results);
      const more=btn('Load more configurations',()=>loadChoices(true));
      async function loadChoices(continuing=false){
        more.disabled=true;
        try{const page=await env.query('realizations',{}, {size:20,...(continuing&&cursor?{cursor}:{})});if(env.signal.aborted)return;
          choices.push(...page.items);cursor=page.continuation;
          for(const select of [a,b])for(const [index,item] of page.items.entries()){
            const settings=Object.entries(item.profiles||{}).map(([name,active])=>name+': '+(active?'on':'off')).join(', ');
            const option=el('option','',(item.kind==='RECORDED_WORLD'?'Recorded configuration ':'Modeled example ')+(choices.length-page.items.length+index+1)+(settings?' · baseline '+settings:''));option.value=item.identity;select.append(option);
          }
          choiceStatus.textContent=choices.length?choices.length+' choices loaded. Profile labels show baseline settings; precedence and profile groups can affect activation. Examples do not enumerate the complete space.':'No selectable configuration was recorded in this context. Choose a Spring context or inspect missing configuration evidence.';
          more.hidden=!cursor;
        }finally{more.disabled=false;}
      }
      function override(value){return options.some(([id])=>id===value)?{projection:value}:M.realizationRequest(choices.find(c=>c.identity===value));}
      async function compare(){
        const current=++version,left=override(a.value),right=override(b.value),selectedScope=scope.value,labels=[a.selectedOptions[0].textContent,b.selectedOptions[0].textContent];
        state.comparison={graph:env.graph,context:env.context,a:a.value,b:b.value,scope:selectedScope};
        results.replaceChildren(empty('Comparing…','Reading two bounded canonical dependency views.'));
        try{
          const values=await Promise.all([env.query('edges',{}, {size:20},{scope:selectedScope,relationshipKinds:relationshipKinds(selectedScope),...left}),env.query('edges',{}, {size:20},{scope:selectedScope,relationshipKinds:relationshipKinds(selectedScope),...right})]);
          await resolveLabels(values.flatMap(value=>value.items.flatMap(edge=>[edge.source,edge.target])),env);
          if(env.signal.aborted||current!==version)return;
          const shown=[...values];
          function render(){
            results.replaceChildren();const summary=el('div','comparison-summary');results.append(summary);
            for(const [index,value] of shown.entries())summary.append(append(el('div','surface'),el('h3','',index?'View B':'View A'),el('p','small muted',labels[index]),el('p','small',M.pageInfo(value)),btn('Inspect view',()=>inspect(index?'View B':'View A','CONFIGURATION PROJECTION',value),'text-button')));
            const table=el('table','comparison-table'),head=append(el('tr'),el('th','','Dependency'),el('th','','View A'),el('th','','View B')),body=el('tbody');table.append(el('caption','','Dependencies in the two selected views'),append(el('thead'),head),body);head.querySelectorAll('th').forEach(cell=>cell.scope='col');results.append(append(el('div','table-scroll'),table));
            const rows=M.compareEdges(shown[0],shown[1]);
            for(const value of rows){const edge=value.edge;body.append(append(el('tr'),append(el('td'),btn((state.labels.get(edge.source)||'Source symbol')+' → '+(state.labels.get(edge.target)||'Target symbol'),()=>inspect('Dependency evidence','DEPENDENCY',edge,[{label:'Source details',run:async()=>{const value=await env.query('entity',{identity:edge.source});if(!env.signal.aborted&&value.items[0])return inspectEntity(value.items[0],env);}},{label:'Target details',run:async()=>{const value=await env.query('entity',{identity:edge.target});if(!env.signal.aborted&&value.items[0])return inspectEntity(value.items[0],env);}}]),'text-button')),el('td','',value.a),el('td','',value.b)));}
            if(!rows.length)results.append(empty('No dependency rows returned','Inspect each view’s status. Empty partial results do not establish an absence of dependencies.'));
            const controls=el('div','toolbar');results.append(controls);
            for(const index of [0,1])if(shown[index].continuation)controls.append(btn('Load more from '+(index?'B':'A'),async()=>{
              const next=await env.query('edges',{}, {size:20,cursor:shown[index].continuation},{scope:selectedScope,relationshipKinds:relationshipKinds(selectedScope),...(index?right:left)});
              await resolveLabels(next.items.flatMap(edge=>[edge.source,edge.target]),env);
              if(env.signal.aborted||current!==version)return;
              shown[index]={...next,items:[...shown[index].items,...next.items]};render();
            }));
            controls.append(btn('Download comparison JSON',()=>download({scope:selectedScope,viewA:{request:left,result:shown[0]},viewB:{request:right,result:shown[1]}},'configuration-views.json')));
            for(const [index,selection] of [left,right].entries())controls.append(btn('Open '+(index?'B':'A')+' graph',()=>{state.graphFocus={scope:selectedScope,...selection};return loadTab('graph');}));
            results.append(el('p','muted small','Only loaded rows are compared. “Not established” means evidence or remaining pages prevent an absence claim. This compares configurations of one snapshot, not repository history.'));
          }render();
        }catch(error){if(!env.signal.aborted&&current===version)results.replaceChildren(empty('Comparison unavailable',error.message),btn('Retry',compare));}
      }
      toolbar.append(btn('Compare views',compare,'button primary'),more,btn('Configuration evidence',()=>inspect('Configuration evidence','CONFIGURATION', (signal,cursor)=>api('/graphs/'+env.graph+'/queries/configurations',{request:env.request,arguments:{},page:{size:20,...(cursor?{cursor}:{})}},signal))));
      await loadChoices();if(saved){for(const [select,value] of [[a,saved.a],[b,saved.b]])if([...select.options].some(option=>option.value===value))select.value=value;}await compare();return node;
    },
    async metrics(env){
      const node=el('div'),assessmentCard=card('Architecture score','A score evaluates explicit architecture rules. Evidence sufficiency is reported separately from architecture health.');node.append(assessmentCard);
      if(!env.reportIds.length)assessmentCard.append(empty('Score unavailable — no policy report','Connect this repository with architecture rules, then run a new analysis. Structural metrics below do not require a policy.'),btn('Configure rules & scoring',configureRules,'button primary'));
      else{
        const reportSelect=selectControl('Assessment report',env.reportIds.map((report,index)=>[report.identity,'Policy report '+(index+1)]));assessmentCard.append(field('Report',reportSelect));const detail=el('div');assessmentCard.append(detail);let version=0;
        async function score(){const current=++version;detail.replaceChildren(empty('Loading assessment…','Reading the published score and explanation.'));
          try{const value=await env.report(reportSelect.value,'assessment');if(env.signal.aborted||current!==version)return;
            detail.replaceChildren(append(el('div','score-heading'),el('strong','score',M.assessment(value)),badge(value.status)));
            detail.append(el('p','muted',value.confidence?.sufficient?'The model’s required evidence is sufficient.':'Required evidence is incomplete. Review the recorded reasons before interpreting this assessment.'));
            for(const dimension of value.dimensions||[])detail.append(append(el('div','dimension'),btn(dimension.id.replace(/^health\./,'').replaceAll('-',' '),()=>inspect('Score dimension','ASSESSMENT EVIDENCE',dimension,[{label:'Review contributing findings',run:()=>{closeInspector();return loadTab('policy');}}]),'text-button'),el('strong','',dimension.score??'Unavailable')));
            detail.append(append(el('div','toolbar'),btn('Explain score & withheld reasons',()=>inspect('Architecture assessment','ASSESSMENT',value,[{label:'Review findings',run:()=>{closeInspector();return loadTab('policy');}}]),'text-button'),btn('Policy metrics',()=>inspect('Policy metrics','METRICS',signal=>api('/graphs/'+env.graph+'/reports/'+reportSelect.value+'/policy-metrics',{arguments:{}},signal)),'text-button')));
          }catch(error){if(!env.signal.aborted&&current===version)detail.replaceChildren(empty('Assessment unavailable',error.message));}}
        reportSelect.addEventListener('change',()=>safe(score));await score();
      }
      const catalog=await env.query('metric-catalog',{}, {size:50}),metricCard=card('Architecture metrics','Choose a metric and scope. Values, definitions, units, limitations and supporting evidence come from the canonical backend.');metricCard.style.marginTop='20px';node.append(metricCard);
      const metricNames={'metric.ca':'Incoming coupling (Ca)','metric.ce':'Outgoing coupling (Ce)','metric.scc':'Connected components','metric.physical-loc':'Physical lines of code','metric.quality':'Evidence quality','metric.inventory':'Captured inventory','metric.fan-in':'Incoming dependencies','metric.fan-out':'Outgoing dependencies','metric.hotspots':'Dependency hotspots','metric.crossings':'Layer boundary crossings','metric.operational':'Measured runtime','metric.spring':'Spring inventory'};
      const metric=selectControl('Metric',catalog.items.map(id=>[id,metricNames[id]||id.replace(/^metric\./,'').replaceAll('-',' ').replace(/^./,letter=>letter.toUpperCase())]),env.springContext?'metric.spring':'metric.inventory');
      const subject=state.metricSubject;state.metricSubject=null;
      const scope=selectControl('Metric scope',[['TYPE','Classes'],['PACKAGE','Packages'],['MODULE','Modules'],['SPRING_COMPONENT','Spring components'],['ENTITY','All entities']],subject?(['TYPE','PACKAGE','MODULE'].includes(subject.kind)?subject.kind:'ENTITY'):(env.springContext?'SPRING_COMPONENT':'TYPE'));
      const subjectLabel=el('p','small muted',subject?'Selected subject: '+M.label(subject):'Subject: the complete selected analysis context. Choose an entity’s Metric details to drill down.');metricCard.append(subjectLabel);
      let selectedSubject=subject?.identity||env.context,version=0;
      const toolbar=append(el('div','toolbar'),field('Metric',metric),field('Scope',scope)),definition=el('div'),result=el('div');metricCard.append(toolbar,definition,result);
      async function evaluate(){const current=++version;result.replaceChildren(empty('Reading metric…','Computing the bounded metric in the canonical service.'));
        try{const spec=await env.query('metric-definition',{metric:metric.value});if(env.signal.aborted||current!==version)return;
          const entry=spec.items[0];if(entry){for(const option of scope.options)option.disabled=!entry.scopes.includes(option.value);
            if(!entry.scopes.includes(scope.value)){scope.value=[...scope.options].find(option=>!option.disabled).value;selectedSubject=env.context;subjectLabel.textContent='Using the selected context at a scope supported by this metric.';}}
          const value=await env.query('metric',{metric:metric.value,subject:selectedSubject},undefined,{scope:scope.value,relationshipKinds:relationshipKinds(scope.value)});if(env.signal.aborted||current!==version)return;
          const data=value.items[0];definition.replaceChildren();if(entry)definition.append(el('p','muted',entry.meaning),append(el('details'),el('summary','','Definition, formula & population'),el('p','',entry.formula),el('p','muted small',entry.population),btn('Full definition',()=>inspect('Metric definition','METRIC DEFINITION',entry),'text-button')));
          result.replaceChildren();if(!data){result.append(empty('Metric unavailable','The backend did not publish a value.'));return;}
          result.append(append(el('div','metric-result'),el('strong','metric-value',data.value==null?'Unavailable':data.value.amount+' '+data.value.unit),badge(data.status)));
          if(data.status!=='COMPLETE')result.append(el('p','small muted',data.status==='PARTIAL'?'Observed value with incomplete evidence. Open inputs for the recorded limitations.':data.status==='NOT_APPLICABLE'?'This metric does not apply to this population or has a zero denominator. Open inputs for the exact reason.':'The required evidence is insufficient to publish a value. Open inputs for the recorded reasons.'));
          result.append(append(el('div','toolbar'),btn('Inputs, distribution & evidence',()=>inspect('Metric details','CANONICAL METRIC',value),'text-button'),btn('Download metric JSON',()=>download(value,'architecture-metric.json'),'text-button')));
          const rankings=data.details?.ranking||data.details?.hotspots;if(Array.isArray(rankings)){
            await resolveLabels(rankings.map(value=>value.identity),env);if(env.signal.aborted||current!==version)return;
            result.append(el('h3','','Ranked dependencies'),el('p','muted small',rankings.length+' rows shown'+(data.details.rankingPopulation==null?'':' from '+data.details.rankingPopulation+' ranked entities')+(data.details.rankingTruncated?' · Ranking is bounded':'')));
            for(const value of rankings)result.append(row({identity:value.identity,label:state.labels.get(value.identity)||'Ranked entity',kind:'Dependency counts'},async()=>{const entity=await env.query('entity',{identity:value.identity});if(!env.signal.aborted&&entity.items[0])return inspectEntity(entity.items[0],env);return inspect('Ranked metric entry','METRIC EVIDENCE',value);},value.fanIn+' incoming · '+value.fanOut+' outgoing'));
          }
        }catch(error){if(!env.signal.aborted&&current===version)result.replaceChildren(empty('Metric unavailable',error.message),btn('Retry',evaluate));}}
      toolbar.append(btn('Read metric',evaluate,'button primary'),btn('Use whole context',()=>{selectedSubject=env.context;subjectLabel.textContent='Subject: the complete selected analysis context.';return evaluate();}));
      metric.addEventListener('change',()=>safe(evaluate));scope.addEventListener('change',()=>safe(evaluate));await evaluate();return node;
    },
    async impact(env) {
      const node = card('Change impact', 'Find a class, then inspect the incoming dependency paths that may be affected. This is potential structural impact, not a runtime simulation.');
      const form = el('form', 'toolbar'), input = el('input'), submit = el('button', 'button primary', 'Find symbol'); input.type = 'search'; input.placeholder = 'Class name or exact symbol identity'; input.setAttribute('aria-label', 'Symbol for impact analysis'); input.required = true; submit.type = 'submit'; form.append(input, submit); node.append(form);
      const results = empty('Choose a symbol', 'Search by class name, then select the exact symbol to calculate its potential impact.'); node.append(results); let version = 0;
      form.addEventListener('submit', event => { event.preventDefault(); safe(async () => {
        const current = ++version; submit.disabled = true; results.replaceChildren(empty('Searching…', 'Reading matching symbols.'));
        try { const target = el('div'); await pagedList(target, env, cursor => env.query('entities', {search: input.value.trim()}, {size: 20, ...(cursor ? {cursor} : {})}), item => row(item, async () => {
          const paths = await env.query('impact', {identity: item.identity}, undefined, {direction: 'INCOMING'}); if (!env.signal.aborted) return inspect(M.label(item), 'POTENTIAL STRUCTURAL IMPACT', paths);
        }), 'No matching symbols', 'Try a class name from the Structure view.'); if (current === version && !env.signal.aborted) results.replaceChildren(target); }
        catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Search unavailable', error.message)); }
        finally { submit.disabled = false; }
      }); }); return node;
    },
    async graph(env) {
      const node = card('Dependency graph', 'Select a node for its evidence. All observed dependencies may include alternatives that do not coexist in one runtime configuration.');
      let focus = state.graphFocus || {};
      const toolbar = el('div', 'toolbar'), scope = el('select'), projection = el('select'); scope.setAttribute('aria-label', 'Graph scope'); projection.setAttribute('aria-label', 'Dependency view');
      for (const [value, label] of [['MODULE', 'Modules'], ['PACKAGE', 'Packages'], ['TYPE', 'Classes'],['SPRING_COMPONENT','Spring components'],['ENTITY','Members & entities']]) { const option = el('option', '', label); option.value = value; scope.append(option); }
      for (const [value, label] of [['BOUNDED_UNION', 'All observed dependencies'], ['MUST', 'Required in every modeled configuration'], ['MAY', 'Conditional dependencies'], ['UNKNOWN_OVERLAY', 'Uncertain dependencies']]) { const option = el('option', '', label); option.value = value; projection.append(option); }
      if(focus.realization){const option=el('option','','Selected recorded configuration');option.value='REALIZED';projection.append(option);}
      scope.value=focus.scope||(env.springContext?'SPRING_COMPONENT':'MODULE');projection.value=focus.projection||'BOUNDED_UNION';
      const depth=selectControl('Neighborhood depth',[['1','1 dependency step'],['2','2 dependency steps'],['3','3 dependency steps']]);depth.hidden=!focus.seed;
      const clear=btn('Show complete bounded view',()=>{focus={...focus,seed:null};depth.hidden=true;return load();});clear.hidden=!focus.seed;
      toolbar.append(field('Scope',scope),field('Dependency view',projection),depth,clear); node.append(toolbar); const content = el('div'); node.append(content); let version = 0;
      async function load() {
        const current = ++version; content.replaceChildren(empty('Loading graph…', 'Reading bounded canonical dependencies.'));
        try {
          const graph = await env.query('visual-graph', focus.seed?{seed:focus.seed,depth:Number(depth.value)}:{}, undefined, {scope: scope.value,relationshipKinds:focus.relationshipKinds||relationshipKinds(scope.value), projection: projection.value,...(projection.value==='REALIZED'?{realization:focus.realization}:{}), limits: {maxVertices: 1500, maxEdges: 4000, maxAuxiliaryStates: 20000}});
          if (env.signal.aborted || current !== version) return;
          state.cy?.destroy(); state.cy = null; content.replaceChildren();
          content.append(el('p', 'muted small', graph.nodes.length + ' displayed nodes · ' + graph.edges.length + ' displayed edges · '+(graph.metadata.status||'Observed projection') + (graph.metadata.isTruncated ? ' · Result limited — narrow the scope' : '')));
          if (!graph.nodes.length) { content.append(empty('No dependency nodes available', 'Choose another scope or context. An empty graph does not establish an absence of dependencies.')); return; }
          if (!window.cytoscape) { content.append(empty('Graph renderer unavailable', 'The local graph library could not be loaded. You can still download the canonical projection.'), btn('Download graph JSON', () => download(graph, 'dependency-graph.json'))); return; }
          const controls = el('div', 'toolbar'), canvas = el('div', 'graph-canvas'); canvas.setAttribute('aria-label', 'Interactive dependency graph'); canvas.tabIndex = 0;
          controls.append(btn('Zoom in', () => state.cy.zoom(state.cy.zoom() * 1.25)), btn('Zoom out', () => state.cy.zoom(state.cy.zoom() / 1.25)), btn('Fit graph', () => state.cy.fit(undefined, 36)),btn('Projection limits & evidence',()=>inspect('Graph projection','PROJECTION EVIDENCE',graph)), btn('Download graph JSON', () => download(graph, 'dependency-graph.json')),btn('Save graph image',async()=>{
            const blob=await state.cy.png({output:'blob-promise',full:true,scale:1,maxWidth:3000,maxHeight:3000,bg: getComputedStyle(document.documentElement).getPropertyValue('--surface').trim()});downloadBlob(blob,'dependency-graph.png');
          })); content.append(controls, canvas);
          const displayNode=item=>{const module=item.id.startsWith('compound:module:')?state.moduleLabels?.get(item.id.slice('compound:module:'.length)):null;return {...item,label:module==='.'?'Root module':module||(!item.label&&item.kind==='PACKAGE'?'(default package)':item.label),activationStatus:item.properties?.activationStatus,parent:item.parent||undefined};};
          const elements = [...graph.nodes.map(item => ({data: displayNode(item)})), ...graph.edges.map(item => ({data: item}))], colors = getComputedStyle(document.documentElement);
          graph.nodes.forEach(item => state.labels.set(item.id, displayNode(item).label));
          state.cy = cytoscape({container: canvas, elements, layout: {name: 'breadthfirst', directed: true, padding: 35, spacingFactor: 1.4, animate: false}, minZoom: .05, maxZoom: 3, wheelSensitivity: .2, style: [
            {selector: 'node', style: {'background-color': colors.getPropertyValue('--accent').trim(), color: colors.getPropertyValue('--text').trim(), label: 'data(label)', 'font-size': 10, 'text-valign': 'bottom', 'text-margin-y': 8, 'text-wrap': 'ellipsis', 'text-max-width': 140, width: 22, height: 22}},
            {selector: ':parent', style: {'background-opacity': .08, 'border-width': 1, 'border-color': colors.getPropertyValue('--muted').trim(), padding: 20, 'text-valign': 'top'}},
            {selector: 'edge', style: {'line-color': colors.getPropertyValue('--muted').trim(), 'target-arrow-color': colors.getPropertyValue('--muted').trim(), 'target-arrow-shape': 'triangle', 'curve-style': 'bezier', width: 1, opacity: .6}},
            {selector: 'edge[classification="MAY"]',style:{'line-style':'dashed'}},
            {selector: 'edge[classification="UNKNOWN"]',style:{'line-style':'dotted','line-color':colors.getPropertyValue('--warning').trim()}},
            {selector: 'node[activationStatus="UNKNOWN"]',style:{shape:'diamond','border-width':2,'border-style':'dashed','border-color':colors.getPropertyValue('--warning').trim()}},
            {selector: ':selected', style: {'border-width': 3, 'border-color': colors.getPropertyValue('--warning').trim()}},
            {selector: '.path-highlight',style:{'line-color':colors.getPropertyValue('--warning').trim(),'target-arrow-color':colors.getPropertyValue('--warning').trim(),'border-color':colors.getPropertyValue('--warning').trim(),'border-width':3,width:3,opacity:1}}
          ]});
          const actualNodes=graph.nodes.filter(item=>!item.id.startsWith('compound:'));
          const selected=selectControl('Graph node',actualNodes.map(item=>[item.id,displayNode(item).label]));
          controls.append(field('Node',selected),btn('Node details',()=>inspectEntity({...graph.nodes.find(item=>item.id===selected.value),identity:selected.value},env)),btn('Focus & expand',()=>{focus={...focus,seed:selected.value};depth.hidden=false;clear.hidden=false;return load();}));
          const destination=selectControl('Path target',actualNodes.map(item=>[item.id,displayNode(item).label]));
          const pathStatus=el('p','small muted');content.append(append(el('div','toolbar'),field('Path target',destination),btn('Find shortest path',async()=>{
            const result=await env.query('shortest-path',{source:selected.value,target:destination.value},undefined,{scope:scope.value,relationshipKinds:focus.relationshipKinds||relationshipKinds(scope.value),projection:projection.value,...(projection.value==='REALIZED'?{realization:focus.realization}:{})});
            if(env.signal.aborted||current!==version)return;state.cy.elements().removeClass('path-highlight');
            const path=result.items[0];if(path){state.cy.nodes().filter(node=>path.vertices.includes(node.id())).addClass('path-highlight');state.cy.edges().filter(edge=>path.edges.some(item=>item.source===edge.source().id()&&item.target===edge.target().id())).addClass('path-highlight');}
            pathStatus.textContent=path?'Path highlighted where present in this bounded graph. '+M.pageInfo(result):'No path returned. '+M.pageInfo(result);
            await inspect('Shortest dependency path','PATH EVIDENCE',result);
          })),pathStatus);
          state.cy.on('tap', 'node', event => { const item = event.target.data(); if(!item.id.startsWith('compound:'))selected.value=item.id; safe(() => item.id.startsWith('compound:') ? inspect(item.label, item.kind, item) : inspectEntity({...item, identity: item.id}, env)); });
          state.cy.on('tap','edge',event=>safe(()=>{
            const item=event.target.data(),actions=[];
            for(const reference of item.properties?.evidence||[])actions.push({label:'Open source evidence',run:()=>inspect('Dependency source','SOURCE EVIDENCE',signal=>api('/graphs/'+env.graph+'/queries/evidence',{request:env.request,arguments:{reference}},signal))});
            return inspect('Dependency evidence','DEPENDENCY',item,actions);
          }));
          canvas.addEventListener('keydown', event => { if (event.key === '+' || event.key === '=') state.cy.zoom(state.cy.zoom() * 1.2); else if (event.key === '-') state.cy.zoom(state.cy.zoom() / 1.2); else if (event.key === '0') state.cy.fit(undefined, 36); });
          content.append(el('p', 'muted small', 'Arrows show dependency direction. Solid: required · Dashed: conditional · Dotted: uncertain. Diamond: uncertain Spring definition. Drag to pan · Scroll to zoom · Keyboard: + / − / 0. Node controls provide a keyboard alternative.'));
        } catch (error) { if (!env.signal.aborted && current === version) content.replaceChildren(empty('Graph unavailable', error.message), btn('Retry graph', load)); }
      }
      scope.addEventListener('change', () => {focus={...focus,seed:null,relationshipKinds:null};depth.hidden=true;clear.hidden=true;safe(load);}); projection.addEventListener('change', () => safe(load));depth.addEventListener('change',()=>safe(load)); return {node, after: () => safe(load)};
    }
  };
  document.querySelectorAll('[data-action=connect]').forEach(n => n.addEventListener('click', () => { $('connect-title').textContent='Connect your code'; $('connect-error').hidden = true; $('connect-dialog').showModal(); }));
  $('policy-example').addEventListener('click',()=>{if($('repository-policy').value.trim()){$('connect-error').textContent='The policy field already contains rules. Clear it before inserting an example.';$('connect-error').hidden=false;return;}$('repository-policy').value='schema: architecture-policy:v1\nid: project.cycles\nversion: "1"\nrules:\n  - id: project.no-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: TYPE\n    relationships: [graph.depends-on]\n    within: {origins: [PROJECT]}\n';});
  document.querySelectorAll('[data-action=refresh]').forEach(n => n.addEventListener('click', () => safe(async () => { await connectServer(); if (state.job) { const id = state.job.id, latest = await api('/analysis/jobs/' + id); if (state.job?.id === id) await selectJob(latest); } })));
  document.querySelectorAll('[data-close]').forEach(n => n.addEventListener('click', () => n.dataset.close === 'inspector' ? closeInspector() : $(n.dataset.close).close()));
  $('inspector').addEventListener('cancel', closeInspector);
  $('connect-form').addEventListener('submit', connectRepository);
  $('analyze').addEventListener('click', () => safe(submit));
  $('cancel-job').addEventListener('click', () => safe(async () => { const id = state.job?.id; if (!id) return; const job = await api('/analysis/jobs/' + id + '/cancel', {}); if (state.job?.id === id) { state.job = job; renderJob(job); } }));
  $('history-more').addEventListener('click', () => safe(() => refreshHistory(true)));
  $('context').addEventListener('change', () => { state.graphFocus=null;state.explorer=null;state.metricSubject=null;state.context = $('context').value; safe(() => loadTab(state.tab)); });
  $('tabs').addEventListener('click', event => { const tab = event.target.closest('[data-tab]'); if (tab) safe(() => loadTab(tab.dataset.tab)); });
  $('tabs').addEventListener('keydown', event => { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); const tabs = [...document.querySelectorAll('[role=tab]')], index = tabs.indexOf(document.activeElement), next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;tabs.forEach((tab,i)=>tab.tabIndex=i===next?0:-1); tabs[next].focus(); });
  $('search-form').addEventListener('submit', event => { event.preventDefault(); safe(() => loadTab('structure')); });
  $('provenance').addEventListener('click', () => safe(() => { if (!state.context) return notice('Choose an analysis context first.'); const env = envFor(); return inspect('Analysis provenance', 'PROVENANCE', signal => api('/graphs/' + env.graph + '/queries/provenance', {request: env.request, arguments: {}}, signal)); }));
  $('job-receipt').addEventListener('click', () => safe(() => inspect('Analysis receipt', 'ANALYSIS', state.job)));
  for (const [id, op, label] of [['job-diagnostics', 'input-diagnostics', 'Input diagnostics'], ['job-gaps', 'gaps', 'Supplemental analysis limitations']]) $(id).addEventListener('click', () => safe(async () => {
    const job = state.job;
    return inspect(label, 'ANALYSIS EVIDENCE', (signal, cursor) => api('/analysis/jobs/' + job.id + '/' + op, {size: 20, ...(cursor ? {cursor} : {})}, signal), [{label: 'View graph limitations', run: async () => { closeInspector(); if (state.graph) await loadTab('gaps'); else notice('No graph was published for this analysis. Input diagnostics retain acquisition failures.'); }}]);
  }));
  $('download-details').addEventListener('click', () => download(state.details, 'evo-evidence.json'));
  function applyTheme(theme) { document.documentElement.dataset.theme = theme; $('theme-toggle').textContent = theme === 'dark' ? 'Light theme' : 'Dark theme'; $('theme-toggle').setAttribute('aria-label', 'Switch to ' + (theme === 'dark' ? 'light' : 'dark') + ' theme'); try { localStorage.setItem('evo_theme', theme); } catch (_) {} }
  let theme = 'dark'; try { theme = localStorage.getItem('evo_theme') === 'light' ? 'light' : 'dark'; } catch (_) {} applyTheme(theme);
  $('theme-toggle').addEventListener('click', () => { applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'); if (state.tab === 'graph' && state.context) safe(() => loadTab('graph')); });
  $('sidebar-toggle').addEventListener('click', () => { const shell = document.querySelector('.app-shell'), mobile = window.matchMedia('(max-width:760px)').matches; shell.classList.toggle(mobile ? 'mobile-sidebar-open' : 'sidebar-collapsed'); $('sidebar-toggle').setAttribute('aria-expanded', mobile ? shell.classList.contains('mobile-sidebar-open') : !shell.classList.contains('sidebar-collapsed')); state.cy?.resize(); });
  function syncSidebarState() { const shell = document.querySelector('.app-shell'); $('sidebar-toggle').setAttribute('aria-expanded', window.matchMedia('(max-width:760px)').matches ? shell.classList.contains('mobile-sidebar-open') : !shell.classList.contains('sidebar-collapsed')); }
  window.addEventListener('resize', () => { state.cy?.resize(); syncSidebarState(); });
  syncSidebarState();
  safe(connectServer);
})();
