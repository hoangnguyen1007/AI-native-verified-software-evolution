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
  function resetResult() { invalidate(); state.graph = null; state.context = ''; state.contexts = []; state.reports = []; $('analysis-workspace').hidden = true; $('welcome').hidden = false; }
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
        const build = value.items?.[0]?.metadata?.build?.moduleSourceSet;
        if (build) { const option = [...$('context').options].find(n => n.value === context.identity); if (option) option.textContent = [build.module, build.sourceSet].filter(Boolean).join(' / ') || option.textContent; }
      } catch (_) { /* Generic explicit context labels remain available if metadata cannot be read. */ }
    }
  }
  function envFor(signal) {
    const graph = state.graph, context = state.context, request = M.request(context), reportIds = state.reports.filter(r => r.context === context);
    return {graph, context, request, reportIds, signal, jobId: state.job?.id,
      query: (op, args = {}, page, overrides = {}) => api('/graphs/' + graph + '/queries/' + op, {request: {...request, ...overrides}, arguments: args, ...(page ? {page} : {})}, signal),
      report: (id, op, args = {}, page) => api('/graphs/' + graph + '/reports/' + id + '/' + op, {arguments: args, ...(page ? {page} : {})}, signal)};
  }
  async function loadTab(tab) {
    invalidate(); state.tab = tab; const epoch = state.epoch;
    document.querySelectorAll('[role=tab]').forEach(n => { const selected = n.dataset.tab === tab; n.setAttribute('aria-selected', selected); n.tabIndex = selected ? 0 : -1; });
    document.querySelectorAll('[role=tabpanel]').forEach(n => n.hidden = n.id !== 'panel-' + tab); const panel = $('panel-' + tab);
    if (!state.context) { panel.replaceChildren(empty('Choose an analysis context', 'Select the module or repository context above. Results are scoped to that selection.')); return; }
    state.view = new AbortController(); const env = envFor(state.view.signal); panel.replaceChildren(empty('Loading…', 'Reading the captured analysis.'));
    try { const view = await views[tab](env); if (epoch !== state.epoch) return; panel.replaceChildren(view.node || view); $('view-status').textContent = view.status || ''; view.after?.(); }
    catch (error) { if (epoch === state.epoch && error.name !== 'AbortError') { panel.replaceChildren(empty('Unable to load this view', error.message), btn('Retry', () => loadTab(tab))); $('view-status').textContent = 'Request failed. Retry to read this analysis.'; } }
  }
  function row(item, action, subtitle) {
    if (item.identity) state.labels.set(item.identity, M.label(item));
    const moduleName = item.module && !item.module.includes('sha256:') ? item.module : null;
    return append(btn('', action, 'entity-row'), append(el('div'), el('strong', '', M.label(item)), el('p', '', subtitle || [moduleName, item.origin === 'PROJECT' ? 'Project source' : item.origin, item.sourceSet === 'MAIN' ? 'Main' : item.sourceSet].filter(Boolean).join(' · '))), badge((item.kind || 'Details').toLowerCase().replaceAll('_', ' ')));
  }
  function pagedList(container, env, fetchPage, onRow, title, hint) {
    const list = el('div', 'list'), footer = el('div', 'page-footer'); let next, items = [], busy = false; container.append(list, footer);
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
    const scope = ['TYPE', 'MODULE', 'PACKAGE'].includes(item.kind) ? item.kind : null;
    const actions = scope ? [
      {label: 'Dependencies', run: () => inspect(M.label(item), 'DEPENDENCIES', (signal, cursor) => query('dependencies', {identity: item.identity}, signal, {scope}, cursor))},
      {label: 'Change impact', run: () => inspect(M.label(item), 'POTENTIAL STRUCTURAL IMPACT', signal => query('impact', {identity: item.identity}, signal, {scope, direction: 'INCOMING'}))}
    ] : [];
    return inspect(M.label(item), item.kind || 'ENTITY', (signal, cursor) => query('explain', {identity: item.identity}, signal, {}, cursor), actions);
  }
  function download(value, name) { const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], {type: 'application/json'})); const link = el('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }
  const views = {
    async overview(env) {
      const summary = await env.query('summary'), node = el('div'), counts = summary.items?.[0], stats = el('div', 'stats');
      for (const [label, kind] of [['Types & references', 'TYPE'], ['Modules', 'MODULE'], ['Source documents', 'SOURCE_DOCUMENT'], ['Recorded gaps', 'CAPABILITY_GAP']]) {
        const stat = btn('', () => loadTab(label === 'Recorded gaps' ? 'gaps' : 'structure'), 'stat');
        append(stat, el('span', '', label), el('strong', '', counts?.nodeCounts?.[kind] ?? (counts ? 0 : '—')), el('small', '', summary.status === 'COMPLETE' ? 'In the selected context' : 'Observed · incomplete evidence')); stats.append(stat);
      }
      node.append(stats); const grid = el('div', 'content-grid'); node.append(grid);
      const health = card('Architecture assessment', 'Based on the selected policy and the evidence available for this analysis.'); grid.append(health);
      if (env.reportIds.length !== 1) health.append(empty(env.reportIds.length ? 'Select a policy report' : 'No policy assessment', env.reportIds.length ? 'Open Rules & findings to choose an exact report. No reports are combined into one score.' : 'Connect this repository with architecture rules and run a new analysis to evaluate it.'));
      else {
        try { const assessment = await env.report(env.reportIds[0].identity, 'assessment'); health.append(el('div', 'score', M.assessment(assessment)), badge(assessment.status));
          if (assessment.confidence) health.append(el('p', 'muted small', assessment.confidence.sufficient ? 'Required assessment evidence is sufficient.' : 'Required assessment evidence is incomplete.'));
          for (const dimension of assessment.dimensions || []) health.append(append(el('div', 'dimension'), el('span', '', dimension.id), el('strong', '', dimension.score ?? 'Unavailable')));
          health.append(btn('Explain assessment', () => inspect('Architecture assessment', 'ASSESSMENT', assessment), 'text-button'));
        } catch (error) { health.append(empty('Assessment unavailable', error.message)); }
      }
      const guide = card('Follow the evidence', 'Start with a class, inspect its dependencies, then review limitations.'); grid.append(guide);
      for (const [label, tab] of [['Browse captured structure →', 'structure'], ['Explore dependency graph →', 'graph'], ['Review rules and findings →', 'policy'], ['Inspect missing evidence →', 'gaps']]) guide.append(btn(label, () => loadTab(tab), 'entity-row'));
      const inventory = append(el('details', 'card'), el('summary', '', 'Captured inventory — view all counts'));
      for (const [kind, count] of Object.entries(counts?.nodeCounts || {})) inventory.append(append(el('div', 'dimension'), el('span', '', kind.toLowerCase().replaceAll('_', ' ')), el('strong', '', count)));
      inventory.style.marginTop = '20px'; node.append(inventory); return {node, status: summary.status === 'COMPLETE' ? 'Selected context · Evidence complete' : 'Selected context · Incomplete evidence — review limitations'};
    },
    async structure(env) {
      const node = card('Repository structure', 'Search captured classes and symbols. Select an entry to inspect source evidence and dependencies.');
      const population = el('select'); population.setAttribute('aria-label', 'Symbol population');
      for (const [value, label] of [['project', 'Project types'], ['all', 'All referenced types']]) { const option = el('option', '', label); option.value = value; population.append(option); }
      node.append(append(el('div', 'toolbar'), population)); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, selected = population.value, search = $('search').value.trim(), target = el('div');
        results.replaceChildren(empty('Loading…', 'Reading matching symbols.'));
        try { await pagedList(target, env, cursor => env.query('entities', {search}, {size: 20, ...(cursor ? {cursor} : {})}, {population: {origins: selected === 'project' ? ['PROJECT'] : []}}), item => row(item, () => inspectEntity(item, env)), 'No matching symbols', 'Try another search, show all referenced types, or choose a different context.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Symbols unavailable', error.message), btn('Retry', load)); }
      }
      population.addEventListener('change', () => safe(load)); await load(); return node;
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
          return row({label: rule?.name || rule?.id || finding.kind || 'Evaluated rule', kind: finding.classification || finding.status}, () => inspect(rule?.name || rule?.id || 'Rule evidence', 'POLICY EVIDENCE', finding), [rule?.severity, finding.status].filter(Boolean).join(' · '));
        }, op === 'findings' ? 'No observed findings' : 'No evaluated rules', 'Review the report status and rule denominator before interpreting this result.');
        if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Report unavailable', error.message), btn('Retry', load)); }
      }
      reports.addEventListener('change', () => safe(load)); kinds.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async spring(env) {
      const node = card('Spring components & configuration', 'Explore detected components, injection points and HTTP endpoints for the selected context.');
      const select = el('select'); select.setAttribute('aria-label', 'Spring evidence category');
      for (const [value, title] of [['components', 'Components and injection'], ['endpoints', 'HTTP endpoints'], ['configurations', 'Configuration evidence']]) { const option = el('option', '', title); option.value = value; select.append(option); }
      node.append(append(el('div', 'toolbar'), select)); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, category = select.value, target = el('div');
        results.replaceChildren(empty('Loading…', 'Reading the selected Spring evidence.'));
        try { await pagedList(target, env, cursor => env.query(category === 'configurations' ? 'configurations' : 'spring', category === 'configurations' ? {} : {kinds: category === 'endpoints' ? ['ENDPOINT'] : ['BEAN_PRODUCER', 'BEAN_DEFINITION_CANDIDATE', 'INJECTION_POINT', 'INJECTION_USE']}, {size: 20, ...(cursor ? {cursor} : {})}), item => row(item, () => inspectEntity(item, env)), 'No evidence available in this context', 'Try another context or inspect limitations.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Spring evidence unavailable', error.message), btn('Retry', load)); }
      }
      select.addEventListener('change', () => safe(load)); await load(); return node;
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
      const toolbar = el('div', 'toolbar'), scope = el('select'), projection = el('select'); scope.setAttribute('aria-label', 'Graph scope'); projection.setAttribute('aria-label', 'Dependency view');
      for (const [value, label] of [['MODULE', 'Modules'], ['PACKAGE', 'Packages'], ['TYPE', 'Classes']]) { const option = el('option', '', label); option.value = value; scope.append(option); }
      for (const [value, label] of [['BOUNDED_UNION', 'All observed dependencies'], ['MUST', 'Required in every modeled configuration'], ['MAY', 'Conditional dependencies'], ['UNKNOWN_OVERLAY', 'Uncertain dependencies']]) { const option = el('option', '', label); option.value = value; projection.append(option); }
      toolbar.append(scope, projection); node.append(toolbar); const content = el('div'); node.append(content); let version = 0;
      async function load() {
        const current = ++version; content.replaceChildren(empty('Loading graph…', 'Reading bounded canonical dependencies.'));
        try {
          const graph = await env.query('visual-graph', {}, undefined, {scope: scope.value, projection: projection.value, limits: {maxVertices: 1500, maxEdges: 4000, maxAuxiliaryStates: 20000}});
          if (env.signal.aborted || current !== version) return;
          state.cy?.destroy(); state.cy = null; content.replaceChildren();
          content.append(el('p', 'muted small', graph.nodes.length + ' displayed nodes · ' + graph.edges.length + ' displayed edges' + (graph.metadata.isTruncated ? ' · Result limited — narrow the scope' : '')));
          if (!graph.nodes.length) { content.append(empty('No dependency nodes available', 'Choose another scope or context. An empty graph does not establish an absence of dependencies.')); return; }
          if (!window.cytoscape) { content.append(empty('Graph renderer unavailable', 'The local graph library could not be loaded. You can still download the canonical projection.'), btn('Download graph JSON', () => download(graph, 'dependency-graph.json'))); return; }
          const controls = el('div', 'toolbar'), canvas = el('div', 'graph-canvas'); canvas.setAttribute('aria-label', 'Interactive dependency graph'); canvas.tabIndex = 0;
          controls.append(btn('Zoom in', () => state.cy.zoom(state.cy.zoom() * 1.25)), btn('Zoom out', () => state.cy.zoom(state.cy.zoom() / 1.25)), btn('Fit graph', () => state.cy.fit(undefined, 36)), btn('Download graph JSON', () => download(graph, 'dependency-graph.json'))); content.append(controls, canvas);
          const elements = [...graph.nodes.map(item => ({data: {...item, parent: item.parent || undefined}})), ...graph.edges.map(item => ({data: item}))], colors = getComputedStyle(document.documentElement);
          graph.nodes.forEach(item => state.labels.set(item.id, item.label));
          state.cy = cytoscape({container: canvas, elements, layout: {name: 'breadthfirst', directed: true, padding: 35, spacingFactor: 1.4, animate: false}, minZoom: .05, maxZoom: 3, wheelSensitivity: .2, style: [
            {selector: 'node', style: {'background-color': colors.getPropertyValue('--accent').trim(), color: colors.getPropertyValue('--text').trim(), label: 'data(label)', 'font-size': 10, 'text-valign': 'bottom', 'text-margin-y': 8, 'text-wrap': 'ellipsis', 'text-max-width': 140, width: 22, height: 22}},
            {selector: ':parent', style: {'background-opacity': .08, 'border-width': 1, 'border-color': colors.getPropertyValue('--muted').trim(), padding: 20, 'text-valign': 'top'}},
            {selector: 'edge', style: {'line-color': colors.getPropertyValue('--muted').trim(), 'target-arrow-color': colors.getPropertyValue('--muted').trim(), 'target-arrow-shape': 'triangle', 'curve-style': 'bezier', width: 1, opacity: .6}},
            {selector: ':selected', style: {'border-width': 3, 'border-color': colors.getPropertyValue('--warning').trim()}}
          ]});
          state.cy.on('tap', 'node', event => { const item = event.target.data(); safe(() => item.id.startsWith('compound:') ? inspect(item.label, item.kind, item) : inspectEntity({...item, identity: item.id}, env)); });
          canvas.addEventListener('keydown', event => { if (event.key === '+' || event.key === '=') state.cy.zoom(state.cy.zoom() * 1.2); else if (event.key === '-') state.cy.zoom(state.cy.zoom() / 1.2); else if (event.key === '0') state.cy.fit(undefined, 36); });
          content.append(el('p', 'muted small', 'Drag to pan · Scroll to zoom · Keyboard: + / − / 0. Use Structure for keyboard-accessible entity details.'));
        } catch (error) { if (!env.signal.aborted && current === version) content.replaceChildren(empty('Graph unavailable', error.message), btn('Retry graph', load)); }
      }
      scope.addEventListener('change', () => safe(load)); projection.addEventListener('change', () => safe(load)); return {node, after: () => safe(load)};
    }
  };
  document.querySelectorAll('[data-action=connect]').forEach(n => n.addEventListener('click', () => { $('connect-error').hidden = true; $('connect-dialog').showModal(); }));
  document.querySelectorAll('[data-action=refresh]').forEach(n => n.addEventListener('click', () => safe(async () => { await connectServer(); if (state.job) { const id = state.job.id, latest = await api('/analysis/jobs/' + id); if (state.job?.id === id) await selectJob(latest); } })));
  document.querySelectorAll('[data-close]').forEach(n => n.addEventListener('click', () => n.dataset.close === 'inspector' ? closeInspector() : $(n.dataset.close).close()));
  $('inspector').addEventListener('cancel', closeInspector);
  $('connect-form').addEventListener('submit', connectRepository);
  $('analyze').addEventListener('click', () => safe(submit));
  $('cancel-job').addEventListener('click', () => safe(async () => { const id = state.job?.id; if (!id) return; const job = await api('/analysis/jobs/' + id + '/cancel', {}); if (state.job?.id === id) { state.job = job; renderJob(job); } }));
  $('history-more').addEventListener('click', () => safe(() => refreshHistory(true)));
  $('context').addEventListener('change', () => { state.context = $('context').value; safe(() => loadTab(state.tab)); });
  $('tabs').addEventListener('click', event => { const tab = event.target.closest('[data-tab]'); if (tab) safe(() => loadTab(tab.dataset.tab)); });
  $('tabs').addEventListener('keydown', event => { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); const tabs = [...document.querySelectorAll('[role=tab]')], index = tabs.indexOf(document.activeElement), next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length; tabs[next].focus(); safe(() => loadTab(tabs[next].dataset.tab)); });
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
