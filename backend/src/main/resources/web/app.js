/* API-backed presentation only. No synthetic repositories, findings or scores. */
(() => {
  'use strict';
  const M = window.EvoModel, $ = id => document.getElementById(id);
  const state = {setups: [], connections: [], jobs: [], labels: new Map(), setup: null, job: null, resultJob: null, graph: null, contexts: [], reports: [], context: '', tab: 'overview', analysisMode: 'FULL', telemetryByJob: new Map(), epoch: 0, pollGeneration: 0, historyLimit: 50, cy: null, online: false, retry: null, submitting: null};
  const el = (tag, cls, text) => { const n = document.createElement(tag); if (cls) n.className = cls; if (text != null) n.textContent = String(text); return n; };
  const append = (parent, ...children) => { children.filter(Boolean).forEach(child => parent.append(child)); return parent; };
  const safe = async action => { try { await action(); } catch (error) { if (error.name !== 'AbortError') notice(error.message, true); } };
  const btn = (text, action, cls = 'button') => { const n = el('button', cls, text); n.type = 'button'; n.addEventListener('click', () => safe(action)); return n; };
  const badge = text => el('span', 'badge' + (/FAILED|CANCELLED|TIMED_OUT|ERROR/i.test(text) ? ' bad' : /PARTIAL|UNKNOWN|MAY|WITHHELD|UNRESOLVED|CANDIDATE/i.test(text) ? ' warn' : /COMPLETE|SUCCEEDED|MUST|RESOLVED|SAT/i.test(text) ? ' good' : ''), text);
  const empty = (title, text) => append(el('div', 'empty'), el('h3', '', title), el('p', '', text));
  const card = (title, hint) => append(el('div', 'card'), append(el('div', 'card-heading'), append(el('div'), el('h2', '', title), el('p', 'muted', hint))));
  function selectControl(label, options, value) {
    const select = el('select'); select.setAttribute('aria-label', label);
    for (const [id, text] of options) { const option = el('option', '', text); option.value = id; select.append(option); }
    if (value != null) select.value = value; return select;
  }
  const field = (label, control) => append(el('label', 'control-field'), el('span', 'small muted', label), control);
  const relationshipKinds = scope => scope==='SPRING_COMPONENT'?['spring.selected-binding']:scope==='ENTITY'?['java.calls','java.constructor-calls','java.reads-field','java.writes-field','java.type-uses','java.returns','java.field-type','java.parameter-type','java.throws','java.extends','java.implements','java.method-references','java.type-parameter-bound','java.type-argument']:['graph.depends-on'];
  function digestChip(value) {
    const str = String(value ?? '');
    if (!/^sha256:[0-9a-f]{16,}$/i.test(str)) return el('span', '', str);
    const chip = btn(M.shortDigest(str), async () => {
      try { await navigator.clipboard.writeText(str); chip.textContent = 'Copied!'; setTimeout(() => { chip.textContent = M.shortDigest(str); }, 1200); } catch (_) {}
    }, 'digest-chip');
    chip.title = str + ' (Click to copy full SHA-256 digest)';
    return chip;
  }
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
  async function api(path, body, signal, method = (body === undefined ? 'GET' : 'POST')) {
    const timeout = AbortSignal.timeout(180000), combined = signal ? AbortSignal.any([signal, timeout]) : timeout;
    let response;
    try { response = await fetch((path.startsWith('/api/') ? '' : '/api/v1') + path, {method, headers: {Accept: 'application/json', ...(body === undefined ? {} : {'Content-Type': 'application/json'})}, ...(body === undefined ? {} : {body: JSON.stringify(body)}), signal: combined}); }
    catch (error) { if (signal?.aborted) throw error; throw new Error(error.name === 'TimeoutError' ? 'The server took too long. Retry or narrow the request.' : 'Cannot reach the analysis server. Check that it is running, then refresh.'); }
    const envelope = await response.json();
    if (!response.ok || envelope.error) throw new Error(envelope.error?.message || M.apiError(envelope.error?.code));
    return envelope.data;
  }
  const repoLabel = id => {
    const conn = state.connections.find(c => c.setup === id);
    const setup = state.setups.find(s => s.id === id);
    return cleanRepoName(id, conn, setup);
  };
  function invalidate() { state.epoch++; state.view?.abort(); state.cy?.destroy(); state.cy = null; closeInspector(); $('view-status').textContent = ''; }
  function clearReadiness() {
    state.readiness?.abort(); state.readiness = null;
    $('readiness-overview').hidden = true; $('readiness-overview').replaceChildren();
  }
  function resetResult() { state.discovery?.abort(); state.publication?.abort(); clearReadiness(); invalidate(); state.graphFocus = null; state.explorer = null; state.graph = null; state.resultJob = null; state.context = ''; state.contexts = []; state.reports = []; $('analysis-workspace').hidden = true; $('welcome').hidden = false; updateResultStatus(); }
  function updateResultStatus() {
    const job = state.job, displayed = state.resultJob;
    const checking = (state.submitting && state.submitting === state.retry) ||
      (job && !M.terminal(job) && ['QUEUED', 'ACQUISITION', 'PLATFORM'].includes(job.stage));
    let message = '';
    if (displayed && (checking || displayed.id !== job?.id)) {
      message = checking ? 'Checking current inputs. Showing a previous analysis until verification finishes.'
        : 'Showing a previous analysis. Its results are not verified against the current inputs.';
    } else if (job?.reuse) {
      message = 'Matching inputs verified. Reused the analysis from ' + new Date(job.reuse.sourceFinishedAt).toLocaleString() + '.';
    } else if (checking) {
      message = 'Checking current inputs…';
    }
    if ($('result-status').textContent !== message) $('result-status').textContent = message;
  }
  function syncRunControls() {
    const busy = state.submitting || (state.job && !M.terminal(state.job));
    const unavailable = !state.online || !state.setup || Boolean(busy);
    $('analyze').disabled = unavailable;
    $('analyze').textContent = state.retry && !state.submitting ? 'Retry submission' : 'Run analysis ↗';
    $('analyze-force').hidden = !state.capabilities?.analysisResultReuseEnabled ||
      !(state.job?.result?.graph || state.resultJob?.setup === state.setup && state.resultJob?.result?.graph);
    $('analyze-force').disabled = unavailable || Boolean(state.retry);
  }
  function cleanRepoName(setupId, conn, setup) {
    let name = conn?.label || '';
    if (!name || name === 'Registered local source' || name === 'Public Git repository' || name === 'LOCAL' || name === 'GIT_HTTPS' || name === 'GIT_SSH') {
      const coord = conn?.repository?.canonicalCoordinate || setup?.repository?.canonicalCoordinate || conn?.repository || setup?.repository || '';
      if (typeof coord === 'string' && (coord.startsWith('http://') || coord.startsWith('https://'))) {
        name = coord.replace(/^https?:\/\//, '').replace(/^github\.com\//, '').replace(/\.git$/, '');
      } else if (typeof coord === 'string' && coord.startsWith('file:///')) {
        const parts = coord.replace('file:///', '').replace(/\/$/, '').split(/[\/\\]/);
        name = parts[parts.length - 1] || setupId;
      } else if (typeof coord === 'string' && (coord.includes('/') || coord.includes('\\'))) {
        const parts = coord.replace(/\/$/, '').split(/[\/\\]/);
        name = parts[parts.length - 1] || setupId;
      } else {
        name = setupId;
      }
    }
    return String(name).replace(/^https?:\/\//, '').replace(/^github\.com\//, '').replace(/\.git$/, '').trim();
  }
  function repoIcon(isGit) {
    const span = el('span', 'repo-icon');
    span.innerHTML = isGit
      ? `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="6" y1="3" x2="6" y2="15"></line><circle cx="18" cy="6" r="3"></circle><circle cx="6" cy="18" r="3"></circle><path d="M18 9a9 9 0 0 1-9 9"></path></svg>`
      : `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path></svg>`;
    return span;
  }
  function renderRepoManager() {
    const list = $('repo-mgr-list');
    if (!list) return;
    list.replaceChildren();
    if (!state.setups.length) {
      list.append(empty('No repositories connected', 'Connect a local directory or Git repository to get started.'));
      return;
    }
    for (const setup of state.setups) {
      const conn = state.connections.find(c => c.setup === setup.id);
      const isGit = conn?.kind === 'GIT_HTTPS' || conn?.kind === 'GIT_SSH' || (conn?.repository?.canonicalCoordinate || '').startsWith('http');
      const name = cleanRepoName(setup.id, conn, setup);
      const row = el('div', 'repo-mgr-row');
      const info = el('div', 'repo-mgr-info');
      append(info,
        el('strong', 'repo-mgr-name', name),
        el('small', 'repo-mgr-meta', (isGit ? 'Git repository' : 'Local source') + (setup.release ? ` · Java ${setup.release}` : '') + (setup.policy ? ' · Policy configured' : ''))
      );
      const actions = el('div', 'repo-mgr-actions');
      const selectBtn = btn('Open', () => {
        $('repo-manager-dialog')?.close();
        selectRepo(setup.id);
      }, 'btn ghost btn-sm');
      const discBtn = btn('Disconnect', () => {
        $('repo-manager-dialog')?.close();
        promptDisconnect(setup.id);
      }, 'btn danger btn-sm');
      append(actions, selectBtn, discBtn);
      append(row, info, actions);
      list.append(row);
    }
  }
  function renderSidebar() {
    const repos = $('repositories'); repos.replaceChildren();
    const capEl = $('repo-capacity');
    if (capEl) {
      const count = state.setups.length;
      capEl.textContent = `${count}/32`;
      capEl.title = `${count} of 32 repository slots used`;
      capEl.classList.toggle('warning', count >= 28);
    }
    if (!state.setups.length) repos.append(el('p', 'muted small', state.online ? 'No repositories connected yet.' : 'Server unavailable. Refresh to retry.'));
    for (const setup of state.setups) {
      const conn = state.connections.find(c => c.setup === setup.id);
      const isGit = conn?.kind === 'GIT_HTTPS' || conn?.kind === 'GIT_SSH' || (conn?.repository?.canonicalCoordinate || '').startsWith('http');
      const shortName = cleanRepoName(setup.id, conn, setup);
      const row = btn('', () => selectRepo(setup.id), 'sidebar-item' + (state.setup === setup.id ? ' active' : ''));
      row.title = `${shortName} (${isGit ? 'Git repository' : 'Local source'})`;
      const head = el('div', 'sidebar-item-head');
      const delBtn = btn('×', (e) => {
        e.stopPropagation();
        promptDisconnect(setup.id);
      }, 'repo-disconnect-btn');
      delBtn.title = `Disconnect ${shortName}`;
      delBtn.setAttribute('aria-label', `Disconnect ${shortName}`);
      append(head, repoIcon(isGit), el('strong', 'repo-name', shortName), delBtn);
      const sourceKind = (isGit ? 'Git repo' : 'Local folder') + (setup.release ? ` · Java ${setup.release}` : '') + (setup.policy ? ' · Policy active' : '');
      append(row, head, el('small', 'repo-meta', sourceKind));
      repos.append(row);
    }
    const history = $('history'); history.replaceChildren();
    const jobs = state.jobs.filter(job => !state.setup || job.setup === state.setup);
    if (!jobs.length) history.append(el('p', 'muted small', 'No analyses in this session.'));
    for (const job of jobs.slice(0, state.historyLimit)) {
      const row = btn('', () => selectJob(job), 'sidebar-item' + (state.job?.id === job.id ? ' active' : ''));
      const conn = state.connections.find(c => c.setup === job.setup);
      const setup = state.setups.find(s => s.id === job.setup);
      const jobRepo = cleanRepoName(job.setup, conn, setup);
      const head = el('div', 'sidebar-item-head');
      append(head, el('strong', 'repo-name', jobRepo), badge(job.state));
      const meta = new Date(job.submittedAt).toLocaleString([], {month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit'}) + (job.elapsedMillis ? ` · ${(job.elapsedMillis/1000).toFixed(1)}s` : '');
      append(row, head, el('small', 'repo-meta', meta));
      history.append(row);
    }
    $('history-more').hidden = jobs.length <= state.historyLimit;
  }
  async function refreshHistory(more = false) {
    if (!state.online || !state.capabilities?.analysisJobsEnabled) return;
    if (more) { state.historyLimit += 50; renderSidebar(); return; }
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
      syncRunControls();
      if (!state.capabilities.analysisJobsEnabled) notice('This server is read-only. Start the workbench server to connect repositories and run analyses.');
    } catch (error) { state.online = false; $('connection-status').textContent = 'Server unavailable'; $('connection-dot').classList.remove('online'); syncRunControls(); renderSidebar(); notice(error.message, true); }
  }
  async function selectRepo(id) {
    state.setup = id; state.job = null; state.retry = null; state.pollGeneration++; resetResult(); $('job-panel').hidden = true;
    try { localStorage.setItem('evo_repository', id); } catch (_) {}
    $('search').value = ''; state.tab = 'overview'; state.historyLimit = 50;
    $('welcome').querySelector('h2').textContent = 'Ready to inspect your code';
    $('welcome').querySelector('p').textContent = 'Run an analysis to capture the current source, then explore its architecture and evidence.';
    $('welcome').querySelector('button').textContent = 'Connect another repository';
    $('repository-title').textContent = repoLabel(id); $('repository-description').textContent = 'Run an analysis to inspect this repository. Results stay linked to their captured source.';
    syncRunControls();
    closeMobileSidebar(); const latest = state.jobs.find(job => job.setup === id); renderSidebar();
    if (latest) await selectJob(latest, true);
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
  let pendingDisconnectSetup = null;
  function promptDisconnect(setupId) {
    const conn = state.connections.find(c => c.setup === setupId);
    const setup = state.setups.find(s => s.id === setupId);
    const isGit = conn?.kind === 'GIT_HTTPS' || conn?.kind === 'GIT_SSH';
    let rawName = conn?.label || setupId;
    let shortName = rawName.replace(/^https?:\/\//, '').replace(/^github\.com\//, '');
    pendingDisconnectSetup = setupId;

    const nameEl = $('disconnect-repo-name');
    if (nameEl) nameEl.textContent = shortName;
    const metaEl = $('disconnect-repo-meta');
    if (metaEl) metaEl.textContent = (isGit ? 'Git repository' : 'Local folder') + (setup?.release ? ` · Java ${setup.release}` : '');
    const iconEl = $('disconnect-repo-icon');
    if (iconEl) iconEl.textContent = isGit ? '⌥' : '📁';

    const errEl = $('disconnect-error');
    if (errEl) errEl.hidden = true;
    const confirmBtn = $('disconnect-confirm');
    if (confirmBtn) confirmBtn.disabled = false;

    const dialog = $('disconnect-dialog');
    if (dialog?.showModal) dialog.showModal();
  }
  async function disconnectRepository(event) {
    if (event) event.preventDefault();
    const setupId = pendingDisconnectSetup;
    if (!setupId) return;
    const confirmBtn = $('disconnect-confirm');
    if (confirmBtn) confirmBtn.disabled = true;
    const errEl = $('disconnect-error');
    if (errEl) errEl.hidden = true;
    try {
      await api('/analysis/connections/' + encodeURIComponent(setupId), undefined, undefined, 'DELETE');
      state.connections = state.connections.filter(c => c.setup !== setupId);
      state.setups = state.setups.filter(s => s.id !== setupId);

      const dialog = $('disconnect-dialog');
      if (dialog?.close) dialog.close();

      notice('Repository disconnected. Active slot freed.');

      if (state.setup === setupId) {
        if (state.setups.length > 0) {
          await selectRepo(state.setups[0].id);
        } else {
          state.setup = null;
          state.job = null;
          resetResult();
          $('job-panel').hidden = true;
          $('repository-title').textContent = 'Explore your architecture';
          $('repository-description').textContent = 'Connect a repository, run an analysis, then follow the evidence.';
          syncRunControls();
          renderSidebar();
        }
      } else {
        renderSidebar();
      }
    } catch (error) {
      if (errEl) {
        errEl.textContent = error.message || 'Failed to disconnect repository';
        errEl.hidden = false;
      }
    } finally {
      if (confirmBtn) confirmBtn.disabled = false;
    }
  }
  async function submit(force = false) {
    if (!state.setup || state.submitting || (state.job && !M.terminal(state.job))) return;
    notice('');
    const setup = state.setup, generation = state.pollGeneration;
    const mode = $('analysis-mode')?.value || state.analysisMode || 'FULL';
    const request = state.retry ||= {setup, idempotencyKey: crypto.randomUUID(), ...(force ? {force: true} : {}), ...(mode === 'FAST_ARCHITECTURE' ? {mode: 'FAST_ARCHITECTURE'} : {})};
    state.submitting = request; syncRunControls(); updateResultStatus();
    try {
      const job = await api('/analysis/jobs', request);
      if (state.retry === request) state.retry = null;
      if (state.setup === setup && state.pollGeneration === generation) await selectJob(job, true);
      await refreshHistory();
    } finally {
      if (state.submitting === request) state.submitting = null;
      syncRunControls(); updateResultStatus();
    }
  }
  function renderTelemetryBar(job, telemetry) {
    const bar = $('job-telemetry-bar');
    if (!bar) return;
    const stages = [];
    if (telemetry?.stages?.length) {
      for (const st of telemetry.stages) stages.push({stage: st.stage, ms: Number(st.durationMillis) || 0});
    } else if (Array.isArray(job?.events) && job.events.length > 0) {
      for (let i = 0; i < job.events.length; i++) {
        const cur = job.events[i];
        if (cur.stage === 'FINISHED') continue;
        const nextAt = job.events[i + 1]?.at || job.finishedAt;
        const ms = nextAt ? Math.max(0, Date.parse(nextAt) - Date.parse(cur.at)) : 0;
        stages.push({stage: cur.stage, ms});
      }
    }
    if (!stages.length) { bar.hidden = true; return; }
    bar.hidden = false;
    bar.replaceChildren();
    const head = el('div', 'telemetry-head');
    const modeLabel = telemetry?.mode === 'FAST_ARCHITECTURE' ? '⚡ FAST_ARCHITECTURE' : 'FULL EVIDENCE';
    const reuseBadge = (telemetry?.reused || job?.reuse) ? badge('REUSED CACHE HIT') : null;
    append(head,
      el('span', 'telemetry-mode-pill' + (telemetry?.mode === 'FAST_ARCHITECTURE' ? ' fast' : ''), modeLabel),
      reuseBadge,
      el('span', 'muted small mono', `Total: ${telemetry?.totalElapsedMillis ?? job.elapsedMillis ?? 0} ms`)
    );
    const chips = el('div', 'telemetry-chips');
    for (const s of stages) {
      append(chips, append(el('span', 'telemetry-chip'), el('small', '', s.stage.toLowerCase()), el('strong', 'mono', `${s.ms}ms`)));
    }
    append(bar, head, chips);
  }
  function renderJob(job) {
    $('job-panel').hidden = false; $('welcome').hidden = true;
    $('job-state').replaceWith(Object.assign(badge(job.state), {id: 'job-state'})); $('job-title').textContent = M.terminal(job) ? 'Analysis result' : 'Analysis in progress';
    const messages = {PARTIAL: 'Some evidence is missing. Inspect limitations before drawing conclusions.', FAILED: 'Analysis failed. Open input diagnostics and the receipt for the recorded reason.', EMPTY: 'No analyzable Java source was found in this selection.', CANCELLED: 'Analysis was cancelled. No completed result is claimed.', TIMED_OUT: 'Analysis reached its execution limit.', SUCCEEDED: 'Analysis completed. Completion does not mean the architecture has no issues.'};
    $('job-description').textContent = (messages[job.state] || (job.state === 'CANCELLING' ? 'Cancellation requested. Waiting for the worker to stop.' : 'Current step: ' + job.stage.toLowerCase() + '.')) + ' · ' + (job.elapsedMillis / 1000).toFixed(1) + ' s' + (job.result ? ' · ' + job.result.gapCount + ' recorded limitations' : '');
    $('job-evidence-tier').hidden = !job.result?.graph;
    if (job.result?.graph) $('repository-description').textContent = 'Captured ' + new Date(job.reuse?.sourceFinishedAt || job.finishedAt || job.submittedAt).toLocaleString() + ' · Run a new analysis after changing the source.';
    $('cancel-job').hidden = M.terminal(job); $('cancel-job').disabled = job.state === 'CANCELLING'; syncRunControls(); updateResultStatus();
    $('job-diagnostics').disabled = !M.terminal(job); $('job-gaps').disabled = !M.terminal(job);
    $('job-events').replaceChildren(...job.events.map(event => el('li', '', event.stage.toLowerCase())));
    renderTelemetryBar(job, state.telemetryByJob.get(job.id));
  }
  async function selectJob(job, keepPrevious = false) {
    state.job = job; state.setup = job.setup; state.retry = null; state.pollGeneration++;
    state.discovery?.abort();
    clearReadiness();
    if (!keepPrevious || state.resultJob?.setup !== job.setup) resetResult();
    $('repository-title').textContent = repoLabel(job.setup); renderJob(job); renderSidebar();
    const generation = state.pollGeneration;
    const previous = keepPrevious && !state.graph && state.jobs.find(item => item.setup === job.setup && item.id !== job.id && item.result?.graph);
    if (!M.terminal(job)) poll(job.id, generation);
    requestReadiness(job, generation);
    try {
      if (job.result?.graph) await loadGraph(job, job.id, generation);
      else if (previous) await loadGraph(previous, job.id, generation);
    } catch (error) { if (error.name !== 'AbortError') throw error; }
  }
  const selectedJob = (id, generation) => state.job?.id === id && state.pollGeneration === generation;
  function requestReadiness(job, generation) {
    if (state.readiness || M.terminal(job) || !['PLATFORM','ANALYSIS','GRAPH','POLICY','PUBLICATION'].includes(job.stage)
        || !state.capabilities?.acquisitionRoutes?.some(route => route.endsWith('/overview'))) return;
    const controller = new AbortController(); state.readiness = controller;
    api('/api/v2/acquisitions/' + job.id + '/overview', {}, controller.signal).then(view => {
      if (!selectedJob(job.id, generation) || M.terminal(state.job) || controller.signal.aborted) return;
      const panel = $('readiness-overview');
      const heading = el('h2', '', 'Build structure preview');
      const qualification = el('p', 'muted small', 'From the captured build descriptors. Semantic relationships, variants and policy results are still being checked.');
      const totals = el('p', 'small', view.rootCount + ' candidate roots · ' + view.moduleCount + ' candidate modules · ' +
        view.coverage.selected + ' selected contexts · ' + view.coverage.unknownDimensions + ' unresolved configuration dimensions');
      const details = append(el('details'), el('summary', '', 'Candidate modules'), el('p', 'muted small', 'Declared module information for this captured input.'));
      const modules = el('ul');
      for (const module of view.modules) modules.append(el('li', '', module.directory + ' · ' + module.target +
        (module.languages.length ? ' · ' + module.languages.join(', ') : '')));
      details.append(modules);
      if (view.modules.length < view.moduleCount) details.append(el('p', 'muted small', 'Showing ' + view.modules.length + ' of ' + view.moduleCount + ' modules in this preview.'));
      panel.replaceChildren(heading, qualification, totals, details);
      panel.hidden = false;
    }).catch(() => { /* An unpublished preview does not affect the running analysis. */ });
  }
  async function poll(id, generation, since = performance.now()) {
    if (!selectedJob(id, generation)) return;
    const next = () => setTimeout(() => safe(() => poll(id, generation, since)), performance.now() - since < 2000 ? 250 : 1200);
    try {
      const job = await api('/analysis/jobs/' + id); if (!selectedJob(id, generation)) return;
      if (job.revision < state.job.revision) { next(); return; }
      state.job = job; state.jobs = state.jobs.map(item => item.id === id ? job : item); renderJob(job); renderSidebar();
      if (M.terminal(job)) {
        clearReadiness();
        if (job.result?.graph) await loadGraph(job, id, generation);
        if (selectedJob(id, generation)) await refreshHistory();
      }
      else { requestReadiness(job, generation); next(); }
    } catch (error) { if (selectedJob(id, generation) && error.name !== 'AbortError') { notice(error.message + ' Use Refresh history to reconnect.', true); syncRunControls(); } }
  }
  async function loadGraph(publication, jobId, generation) {
    const graph = publication.result.graph;
    const displayed = state.resultJob;
    if (state.graph === graph && publication.reuse && displayed &&
        publication.reuse.sourceJob === (displayed.reuse?.sourceJob ?? displayed.id)) {
      state.resultJob = publication; updateResultStatus(); return;
    }
    state.discovery?.abort();
    const controller = new AbortController(); state.discovery = controller;
    const {signal} = controller;
    const current = () => state.discovery === controller && selectedJob(jobId, generation);
    const discovery = await api('/graphs/' + graph, undefined, signal); if (!current()) return;
    state.publication?.abort(); state.publication = new AbortController();
    state.graph = graph; state.resultJob = publication; state.contexts = discovery.contexts; state.reports = []; updateResultStatus();
    state.reportIds = publication.result.reports ?? discovery.reports ?? [];
    state.reportLoad = null;
    state.moduleLabels = new Map(); state.contextKinds = new Map();
    $('analysis-workspace').hidden = false; $('welcome').hidden = true;
    $('context').replaceChildren(el('option', '', 'Choose a context…')); $('context').firstChild.value = ''; let index = 0;
    for (const context of state.contexts) { const option = el('option', '', context.analysis ? 'Analysis context ' + (++index) : 'Repository / acquisition'); option.value = context.identity; option.title = context.identity; $('context').append(option); }
    state.context = state.contexts.length ? (state.context && state.contexts.some(c => c.identity === state.context) ? state.context : state.contexts[0].identity) : ''; $('context').value = state.context;
    state.contexts.forEach(c => labelContext(c.identity));
    if (M.terminal(publication) && !state.telemetryByJob.has(publication.id)) {
      api('/analysis/jobs/' + publication.id + '/telemetry', undefined, signal).then(tel => {
        state.telemetryByJob.set(publication.id, tel);
        if (state.job?.id === publication.id) renderTelemetryBar(state.job, tel);
      }).catch(() => {});
    }
    await loadTab(state.tab);
  }
  // Label only the selected context, from recorded provenance.
  async function labelContext(identity) {
    const graph = state.graph, publication = state.publication;
    if (!identity || !publication || state.contextKinds.has(identity)) return;
    try {
        const value = await api('/graphs/' + graph + '/queries/provenance', {request: M.request(identity), arguments: {}}, publication.signal);
        if (state.publication !== publication || publication.signal.aborted) return;
        const evidence=value.items?.[0],build = evidence?.metadata?.build?.moduleSourceSet;
        if(evidence?.metadata?.module&&evidence.metadata.modulePath)state.moduleLabels.set(evidence.metadata.module,evidence.metadata.modulePath);
        state.contextKinds.set(identity,evidence?.family);
        const option = [...$('context').options].find(n => n.value === identity);
        if(option&&build){const module=state.moduleLabels.get(build.module)||(build.module?.includes('sha256:')?null:build.module);option.textContent=[evidence.metadata.modulePath||module||(evidence.family==='spring.context'?'Spring configuration':'Java analysis'),evidence.family==='spring.context'&&module?'Spring':null,build.sourceSet?.toLowerCase()].filter(Boolean).join(' / ');}
        else if(option&&evidence?.family==='spring.context')option.textContent='Spring configuration';
        else if(option&&evidence?.metadata?.modulePath)option.textContent=evidence.metadata.modulePath+' / '+(evidence.metadata.sourceSet?.toLowerCase()||'Java');
    } catch (_) { /* Generic explicit context labels remain available if metadata cannot be read. */ }
  }
  async function loadReports() {
    if (!state.reportIds?.length || state.reportLoad) return state.reportLoad;
    const graph = state.graph, ids = state.reportIds, signal = state.publication?.signal;
    const load = (async () => {
      const reports = [];
      for (let from = 0; from < ids.length; from += 4) {
        const results = await Promise.allSettled(ids.slice(from, from + 4)
          .map(id => api('/graphs/' + graph + '/reports/' + id, undefined, signal)));
        if (signal?.aborted || graph !== state.graph) return;
        for (const result of results) {
          if (result.status === 'rejected') throw result.reason;
          reports.push(result.value);
        }
      }
      if (!signal?.aborted && graph === state.graph) state.reports = reports;
    })();
    state.reportLoad = load;
    load.catch(() => { if (state.reportLoad === load) state.reportLoad = null; });
    return load;
  }
  function envFor(signal) {
    const graph = state.graph, context = state.context, request = M.request(context), reportIds = state.reports.filter(r => r.context === context);
    return {graph, context, request, reportIds, signal, jobId: state.resultJob?.id,springContext:state.contextKinds?.get(context)==='spring.context',
      query: (op, args = {}, page, overrides = {}) => api('/graphs/' + graph + '/queries/' + op, {request: {...request, ...overrides}, arguments: args, ...(page ? {page} : {})}, signal),
      report: (id, op, args = {}, page) => api('/graphs/' + graph + '/reports/' + id + '/' + op, {arguments: args, ...(page ? {page} : {})}, signal)};
  }
  async function loadTab(tab) {
    invalidate(); state.tab = tab; const epoch = state.epoch;
    document.querySelectorAll('[role=tab]').forEach(n => { const selected = n.dataset.tab === tab; n.setAttribute('aria-selected', selected); n.tabIndex = selected ? 0 : -1; });
    document.querySelectorAll('[role=tabpanel]').forEach(n => n.hidden = n.id !== 'panel-' + tab); const panel = $('panel-' + tab);
    if (panel) panel.hidden = false;

    // Synchronize workspace view sections so the parent is never display:none
    const tabToSection = {
      overview: 'view-overview',
      structure: 'view-overview',
      graph: 'view-architecture',
      insights: 'view-insights',
      policy: 'view-remediation',
      gaps: 'view-evidence'
    };
    const targetSectionId = tabToSection[tab];
    if (targetSectionId) {
      document.querySelectorAll('.section').forEach(s => {
        s.classList.toggle('active', s.id === targetSectionId);
      });
      document.querySelectorAll('.nav[data-view]').forEach(nav => {
        const active = nav.dataset.tab === tab || nav.dataset.view === targetSectionId.replace('view-', '');
        nav.classList.toggle('active', active);
        nav.setAttribute('aria-current', active ? 'page' : 'false');
      });
    }
    if (!state.context) { if (panel) { panel.setAttribute('aria-busy','false');panel.replaceChildren(empty('Choose an analysis context', 'Select the module or repository context above. Results are scoped to that selection.')); } return; }
    state.view = new AbortController(); const signal = state.view.signal; if (panel) { panel.setAttribute('aria-busy','true');panel.replaceChildren(empty('Loading…', 'Reading the captured analysis.')); }
    const tabName = document.querySelector('[data-tab="'+tab+'"]')?.textContent || tab;
    $('view-status').textContent = 'Loading ' + tabName + '…';
    try {
      const reports = ['overview','policy','metrics','insights'].includes(tab) ? loadReports() : null;
      const label = labelContext(state.context);
      if (tab !== 'overview') await label;
      await reports;
      if (epoch !== state.epoch) return;
      const view = await views[tab](envFor(signal)); if (epoch !== state.epoch) return; panel.replaceChildren(view.node || view); panel.setAttribute('aria-busy','false');$('view-status').textContent = view.status || 'View loaded for the selected analysis context.'; view.after?.();
    }
    catch (error) { if (epoch === state.epoch && error.name !== 'AbortError') { panel.setAttribute('aria-busy','false');panel.replaceChildren(empty('Unable to load this view', error.message), btn('Retry', () => loadTab(tab))); $('view-status').textContent = 'Request failed. Retry to read this analysis.'; } }
  }
  function row(item, action, subtitle) {
    if (item.identity) state.labels.set(item.identity, M.label(item));
    const moduleName = item.module && !item.module.includes('sha256:') ? item.module : state.moduleLabels?.get(item.module) || null;
    const rawLabel = M.label(item);
    const displayLabel = /^sha256:[0-9a-f]{16,}$/i.test(rawLabel) ? M.shortDigest(rawLabel) : rawLabel;
    const node = append(btn('', action, 'entity-row'), append(el('div'), el('strong', '', displayLabel), el('p', '', subtitle || [moduleName, item.origin === 'PROJECT' ? 'Project source' : item.origin, item.sourceSet === 'MAIN' ? 'Main' : item.sourceSet].filter(Boolean).join(' · '))), badge((item.kind || 'Details').toLowerCase().replaceAll('_', ' ')));
    if (displayLabel !== rawLabel) node.title = rawLabel;
    return node;
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
  async function inspect(title, kind, loader, actions = [], previousItems = [], cursor, render) {
    state.inspector?.abort(); state.inspector = new AbortController(); const controller = state.inspector;
    state.details = null;
    const heading = $('inspector-title');
    heading.textContent = /^sha256:[0-9a-f]{16,}$/i.test(String(title)) ? M.shortDigest(String(title)) : title;
    const loading = el('p', 'muted', 'Loading evidence…'); loading.setAttribute('role', 'status');
    $('inspector-kind').textContent = kind; $('inspector-body').replaceChildren(loading); $('download-details').disabled = true;
    if (!$('inspector').open) $('inspector').showModal();
    if (render) { heading.tabIndex = -1; heading.focus(); }
    const contextEnv = state.graph && state.context ? envFor(controller.signal) : null;
    try { const page = typeof loader === 'function' ? await loader(controller.signal, cursor) : loader; if (controller.signal.aborted) return;
      const value = page?.items && previousItems.length ? {...page, items: [...previousItems, ...page.items]} : page;
      state.details = value; const body = $('inspector-body'); body.replaceChildren();
      const finish = () => {
        if (actions.length) body.append(append(el('div','detail-actions'),...actions.map(action => btn(action.label,action.run,'button subtle'))));
        body.append(append(el('details'),el('summary','','Recorded data and source coordinates (Raw JSON)'),el('pre','',JSON.stringify(value,null,2))));
        $('download-details').disabled = false;
      };
      if (render) { render(body,value); finish(); return; }
      const firstGap = value?.reasonCode ? value : Array.isArray(value?.items) && value.items.length === 1 && value.items[0]?.reasonCode ? value.items[0] : null;
      if (firstGap?.reasonCode) {
        const info = M.humanReason(firstGap.reasonCode);
        const guide = el('div', 'human-explain-banner');
        append(guide, append(el('div', 'human-explain-header'), el('strong', '', info.title), badge(info.code)), el('p', '', info.explanation), el('p', 'human-action-hint', 'Recommended next step: ' + info.action));
        body.append(guide);
      }
      const fields = el('dl', 'data-fields');
      for (const [key, val] of Object.entries(value || {})) if (val != null && typeof val !== 'object' && !['query','identity','id','setup','graph','snapshot','acquisition','sourceReport','continuation'].includes(key)) {
        const dd = el('dd');
        if (typeof val === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(val)) dd.append(digestChip(val));
        else dd.textContent = String(val);
        append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), dd));
      }
      body.append(fields);
      if (Array.isArray(value?.items)) {
        body.append(el('p', 'muted small', M.pageInfo(value)));
        for (const [index, item] of value.items.entries()) {
          const section = el(item.reasonCode ? 'details' : 'div', 'evidence-item');
          const gapInfo = item.reasonCode ? M.humanReason(item.reasonCode) : null;
          const itemTitle = gapInfo ? gapInfo.title + ' (' + item.reasonCode + ') · ' + (item.mechanismCategory || 'GAP') : item.vertices ? 'Dependency path ' + (index + 1) : item.family || item.kind || 'Evidence ' + (index + 1);
          section.append(el(item.reasonCode ? 'summary' : 'h3', '', itemTitle));
          if (gapInfo) section.append(el('p', 'small muted', gapInfo.explanation + ' — ' + gapInfo.action));
          if (item.vertices && contextEnv) {
            const feasText = item.feasibility === 'SAT' ? 'Feasible in modeled configurations (SAT)' : item.feasibility === 'UNSAT' ? 'Infeasible / contradictory conditions (UNSAT)' : 'Configuration feasibility: ' + item.feasibility;
            section.append(el('p', 'muted small', item.edges.length + ' dependency steps · ' + feasText));
            const path = el('ol', 'dependency-path');
            item.vertices.forEach((identity, step) => path.append(append(el('li'), btn(state.labels.get(identity) || 'Inspect symbol ' + (step + 1) + ' (' + M.shortDigest(identity) + ')', async () => {
              const entity = await contextEnv.query('entity', {identity});
              if (controller.signal.aborted) return;
              if (entity.items[0]) return inspectEntity(entity.items[0], contextEnv);
              return inspect('Symbol evidence', 'ENTITY', entity);
            }, 'text-button')))); section.append(path);
          } else if(item.span&&item.sourceDigest){
            const span=item.span;section.append(el('p','source-coord-pill', (state.labels.get(span.document)||M.shortDigest(span.document)||'Captured source')+' · Line '+span.startLine+':'+span.startColumn+' → '+span.endLine+':'+span.endColumn));
            const detail=append(el('details'),el('summary','','Source identities & coordinates'));renderFields(detail,item,0);section.append(detail);
          } else renderFields(section, item.metadata || item, 0);
          body.append(section);
        }
        if (!value.items.length) body.append(empty('No records returned', 'Read the status and limitations before interpreting an empty result.'));
        if (value.continuation && typeof loader === 'function') body.append(btn('Load more evidence', () => inspect(title, kind, loader, actions, value.items, value.continuation)));
      } else renderFields(body, value, 0, true);
      finish();
    } catch (error) { if (!controller.signal.aborted) $('inspector-body').replaceChildren(empty('Details unavailable', error.message)); }
  }
  function renderFields(parent, value, depth, objectsOnly = false) {
    if (!value || typeof value !== 'object') return;
    const fields = el('dl', 'data-fields');
    for (const [key, val] of Object.entries(value)) {
      if (val == null || objectsOnly && typeof val !== 'object') continue;
      if (typeof val !== 'object') {
        const resolved = ['source','target'].includes(key) ? state.labels.get(val) || val : val;
        const dd = el('dd');
        if (typeof resolved === 'string' && /^sha256:[0-9a-f]{16,}$/i.test(resolved)) dd.append(digestChip(resolved));
        else dd.textContent = String(resolved);
        append(fields, append(el('div', 'data-field'), el('dt', '', key.replace(/([A-Z])/g, ' $1')), dd));
      } else if (depth < 3 && Object.keys(val).length) {
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
      {label: 'Dependencies (Outgoing)', run: () => inspect(M.label(item), 'DEPENDENCIES', (signal, cursor) => query('dependencies', {identity: item.identity}, signal, {scope,relationshipKinds:relationshipKinds(scope)}, cursor))},
      {label: 'Dependents (Incoming)', run: () => inspect(M.label(item), 'INCOMING DEPENDENCIES', (signal, cursor) => query('dependencies', {identity: item.identity}, signal, {scope,relationshipKinds:relationshipKinds(scope), direction: 'INCOMING'}, cursor))},
      {label: 'Blast radius / Impact', run: () => inspect(M.label(item), 'POTENTIAL STRUCTURAL IMPACT', signal => query('impact', {identity: item.identity}, signal, {scope,relationshipKinds:relationshipKinds(scope), direction: 'INCOMING'}))},
      {label: 'Open in graph', run: async () => { state.graphFocus = {seed: item.identity, scope}; closeInspector(); await loadTab('graph'); }},
      {label: 'Metric details', run: async () => { state.metricSubject = item; closeInspector(); await loadTab('metrics'); }}
    ] : [];
    return inspect(M.label(item), item.kind || 'ENTITY', (signal, cursor) => query('explain', {identity: item.identity}, signal, {}, cursor), actions);
  }
  function downloadBlob(blob,name){const url=URL.createObjectURL(blob),link=el('a');link.href=url;link.download=name;document.body.append(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),10000);}
  function download(value, name) { downloadBlob(new Blob([JSON.stringify(value, null, 2)], {type: 'application/json'}),name); }
  async function inspectFinding(item, reportId, env) {
    const finding=item.result,bundle=finding.bundle,rule=finding.rule?.header||finding.rule,actions=[];
    if(bundle){
      if (item.bundleIdentity) actions.push({label:'Diagnose finding',run:()=>inspectDiagnosis(item,reportId,env)});
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
  const diagnosisReasons = Object.freeze({
    UNSUPPORTED_FINDING: 'Diagnosis requires verified dependency path and supported rule kinds (FORBIDDEN_DEPENDENCY, ACYCLIC, BOUNDARY).',
    MISSING_SOURCE_EVIDENCE: 'The finding has missing or unresolvable source evidence in the captured classpath.',
    GRAPH_PROOF_MISMATCH: 'The recorded graph evidence does not match the canonical dependency path.',
    QUERY_LIMIT: 'The server could not complete the evidence check within its execution limits.',
    MODEL_SCOPE_NOT_REGISTERED: 'Diagnosis model is running in deterministic local replay mode.',
    MODEL_UNAVAILABLE: 'The diagnosis provider did not return a result.',
    INVALID_MODEL_CITATIONS: 'The diagnosis provider returned citations that could not be verified for this finding.'
  });
  function inspectDiagnosis(item, reportId, env, previous) {
    const args = {identity:item.identity,bundle:item.bundleIdentity,context:env.context};
    const loader = previous ?? (signal => api('/graphs/'+env.graph+'/reports/'+reportId+'/diagnosis',{arguments:args},signal));
    const back = {label:'Back to finding',run:()=>inspectFinding(item,reportId,env)};
    return inspect('Finding diagnosis','AI DIAGNOSIS',loader,[back],[],undefined,(body, diagnosis) => {
      const status = el('div','notice'); status.setAttribute('role','status');
      const local = diagnosis.mode === 'LOCAL_REPLAY';
      const validated = diagnosis.status === 'CITATIONS_VALIDATED';
      append(status, el('strong','', (local ? 'Local replay' : 'Live model') + ' · ' + (validated ? 'Citations validated' : 'Diagnosis withheld')),
        el('p','small', local ? 'Uses an authored response. No live AI model was called.'
          : 'The local model selects citations. The explanation comes from checked graph evidence.'));
      body.append(status);
      if (diagnosis.evidenceStatus !== 'COMPLETE') body.append(el('p','muted small','Finding evidence status: '+diagnosis.evidenceStatus
        +' · Recorded gaps: '+diagnosis.gaps.length+'.'));
      if (!validated) {
        body.append(empty('No supported diagnosis', diagnosisReasons[diagnosis.reason] || 'The evidence check did not support a diagnosis.'));
        const f = item.result || {};
        if (f.kind === 'ACYCLIC' && f.bundle?.path) {
          const cycleCard = el('div', 'notice');
          cycleCard.style.marginTop = '12px';
          append(cycleCard,
            el('strong', '', 'Circular Dependency Structure'),
            el('p', 'small', `This finding represents a circular dependency cycle across ${f.bundle.path.length} classes: ${f.bundle.path.join(' → ')}.`),
            el('p', 'small', 'Resolving cycles requires multi-class architectural decoupling (such as extracting an interface or mediating service). Single-patch automated recipes apply to direct forbidden dependencies (e.g. Layering rules).'),
            btn('View cycle path in Graph ↗', () => {
              state.graphFocus = {seed: f.bundle.path[0], scope: f.bundle.request?.scope || 'TYPE'};
              closeInspector();
              return loadTab('graph');
            }, 'button subtle')
          );
          body.append(cycleCard);
        }
        return;
      }
      body.append(el('p','',diagnosis.explanation), el('h3','','Source citations'));
      const list = el('ol','dependency-path');
      for (const citation of diagnosis.citations) {
        const s = citation.span;
        const label = citation.path+' · '+s.startLine+':'+s.startColumn+'–'+s.endLine+':'+s.endColumn;
        const open = () => inspect('Cited source evidence','SOURCE EVIDENCE',async signal => {
          const evidence = await api('/graphs/'+env.graph+'/queries/evidence',{
            request:{...M.request(citation.context),scope:'ENTITY'},arguments:{reference:citation.evidence}
          },signal);
          const record = evidence.items.find(row => row.identity === citation.evidence && row.family === citation.family);
          if (!record) throw new Error('The cited evidence is no longer available in this snapshot.');
          return {citation,record};
        },[{label:'Back to diagnosis',run:()=>inspectDiagnosis(item,reportId,env,diagnosis)}],[],undefined,(detail,data) => {
          detail.append(el('p','source-coord-pill diagnosis-coordinate',label));
          renderFields(detail,data,0);
        });
        list.append(append(el('li'),btn(label,open,'text-button')));
      }
      body.append(list);
      body.append(btn('Review repair proposal', () => inspectRepairProposal(item,reportId,env,diagnosis,args), 'button primary'));
    });
  }
  const repairReasons = Object.freeze({
    DIAGNOSIS_WITHHELD: 'Run diagnosis again to establish the evidence for this finding.',
    FIXTURE_NOT_REGISTERED: 'No automated single-step AST recipe matches this pattern. Review the decoupling blueprint below.',
    SOURCE_MISMATCH: 'The evidence no longer matches the registered source bytes and span.',
    TRANSFORMATION_UNAVAILABLE: 'AST transformation recipe could not be executed on this syntax tree.',
    COMPILER_UNAVAILABLE: 'Compiler syntax validation service is unavailable.',
    CANDIDATE_SYNTAX_REJECTED: 'Candidate syntax rejected by compiler.',
    RECIPE_NOT_APPLICABLE: 'No matching AST modification points found in cited files.',
    AMBIGUOUS_RECIPE_TARGET: 'Multiple conflicting refactoring targets identified.',
    QUERY_LIMIT: 'The proposal request exceeded its work limit.'
  });
  function inspectRepairProposal(item, reportId, env, diagnosis, diagnosisArguments) {
    const request = {arguments:{...diagnosisArguments,diagnosisInput:diagnosis.input}};
    const load = signal => api('/graphs/'+env.graph+'/reports/'+reportId+'/repair-proposal',request,signal);
    const back = {label:'Back to diagnosis',run:()=>inspectDiagnosis(item,reportId,env,diagnosis)};
    return inspect('Repair proposal','PROPOSED CHANGE',load,[back],[],undefined,(body,outcome) => {
      if (outcome.status !== 'PROPOSED') {
        const message = empty('Proposal withheld',repairReasons[outcome.reason] || 'The source change could not be proposed.');
        message.setAttribute('role','status'); body.append(message);
        return;
      }
      const {plan,affectedScope,edit,engine,patch} = outcome.proposal.candidate;
      const status = el('div','notice'); status.setAttribute('role','status');
      append(status,el('strong','','Unverified repair proposal'),el('p','small','OpenRewrite '+engine.version));
      if (diagnosis.mode === 'LIVE') status.append(el('p','small','The recipe rechecks the same graph and source evidence independently.'));
      body.append(status,el('p','',plan.summary),
        el('p','muted small',affectedScope.rule.id+' · '+affectedScope.classification+' · '+affectedScope.relationships.join(', ')));
      const steps = el('ol','dependency-path');
      for (const step of plan.steps) steps.append(el('li','',step));
      body.append(steps,el('h3','',edit.path));
      const diff = el('pre','repair-patch',patch);
      diff.setAttribute('aria-label','Unified patch for '+edit.path);
      body.append(diff,btn('Download patch',() => downloadBlob(new Blob([patch],{type:'text/x-diff'}),'repair.patch')));
      body.append(btn('View verification',() => inspectRepairVerification(item,reportId,env,diagnosis,diagnosisArguments,outcome),'button primary'));
      const checks = el('ul','dependency-path');
      for (const check of plan.verificationChecks) checks.append(el('li','',check));
      body.append(el('h3','','Planned verification'),checks);
    });
  }
  const repairCheckNames = {
    'fixture-inputs':'Baseline and patch', 'candidate-finding':'Candidate finding',
    'mutation-control':'Negative control', 'isolated-runtime':'Isolated build and Spring checks'
  };
  function inspectRepairVerification(item,reportId,env,diagnosis,diagnosisArguments,outcome,operands = {}) {
    const proposal = outcome.proposal;
    const argumentsForCheck = {...diagnosisArguments,diagnosisInput:diagnosis.input,proposal:proposal.identity,...operands};
    const load = signal => api('/graphs/'+env.graph+'/reports/'+reportId+'/repair-verification',{arguments:argumentsForCheck},signal);
    const back = {label:'Back to proposal',run:()=>inspectRepairProposal(item,reportId,env,diagnosis,diagnosisArguments)};
    return inspect('Local PR preview','REPAIR VERIFICATION',load,[back],[],undefined,(body,result) => {
      const status = el('div','notice'); status.setAttribute('role','status');
      const verdict = {VERIFIED:'Verified repair',VERIFIED_DEMO:'Verified in the demo VM',UNVERIFIED:'Unverified repair',REJECTED:'Repair rejected'};
      append(status,el('strong','',verdict[result.verdict]), el('p','small',proposal.candidate.plan.summary));
      body.append(status,el('h3','','Verification checks'));
      const list = el('ul','dependency-path');
      for (const check of result.checks) {
        const label = repairCheckNames[check.id] || check.id;
        const states = {PASSED:'Passed',FAILED:'Failed',NOT_RUN:'Not run',INCONCLUSIVE:'Inconclusive'};
        const explanation = {
          REGISTERED_WORKER_EVIDENCE_REQUIRED:'A protected worker result is required.',
          PROTECTED_RECEIPT_MISSING:'The worker receipt is missing.',
          REANALYSIS_REPORT_REQUIRED:'Add the published report for this snapshot.',
          REANALYSIS_SNAPSHOT_MISMATCH:'This report belongs to different source bytes.',
          FINDING_RESOLVED_IN_DEMO_SCOPE:'The source census and strict comparison prove this finding is resolved for the demo profile scope.',
          FINDING_TRANSITION_UNPROVED:'The comparison could not prove a finding transition.'
        }[check.reason];
        list.append(append(el('li'),el('strong','',label+' · '+states[check.status]),explanation && el('div','muted small',explanation)));
      }
      body.append(list);
      if (result.demoProof) {
        body.append(el('h3','','Spring dependency by profile'),el('p','muted small','The demo VM checked the sealed fixture with dev off and on. The canonical snapshot comparison keeps its own verdict.'));
        const table = el('table','comparison-table');
        const heading = append(el('tr'),...['Profile','Baseline','Candidate','Negative control'].map(label => el('th','',label)));
        for (const cell of heading.children) cell.scope = 'col';
        table.append(append(el('thead'),heading));
        const rows = el('tbody');
        for (const cell of result.demoProof.cells) {
          const label = el('th','',cell.dev ? 'dev on' : 'dev off'); label.scope = 'row';
          const row = append(el('tr'),label);
          for (const value of [cell.baseline,cell.candidate,cell.control]) row.append(el('td','',value ? 'Present' : 'Absent'));
          rows.append(row);
        }
        body.append(append(el('div','table-scroll'),append(table,rows)));
      }
      const form = el('form');
      const fields = ['candidateReport','controlReport'].map((name,index) => {
        const input = el('input'); input.name = name; input.value = operands[name] || '';
        input.pattern = 'sha256:[0-9a-f]{64}'; input.maxLength = 71; input.spellcheck = false;
        form.append(append(el('label'),el('p','small',index === 0 ? 'Candidate report digest' : 'Negative control report digest'),input));
        return input;
      });
      const submit = el('button','button','Check published reports'); submit.type = 'submit'; form.append(submit);
      form.addEventListener('submit',event => {
        event.preventDefault();
        const reports = Object.fromEntries(fields.filter(input=>input.value).map(input=>[input.name,input.value]));
        return safe(()=>inspectRepairVerification(item,reportId,env,diagnosis,diagnosisArguments,outcome,reports));
      });
      body.append(append(el('details'),el('summary','','Attach published comparison inputs'),form),
        el('h3','',proposal.candidate.edit.path),el('pre','repair-patch',result.patch));
    });
  }
  async function inspectScoreDimension(assessment, dimension, reportId, env) {
    const needsFindings = dimension.contributions.some(contribution => contribution.unit === 'potential-root');
    const page = needsFindings ? await env.report(reportId, 'findings', {}, {size: 50}) : null;
    if (env.signal.aborted) return;
    const evidence = M.scoreEvidence(assessment, dimension, page?.items);
    const actions = evidence.findings.map(item => ({
      label: `Open ${item.result.rule.id} finding (${M.shortDigest(item.identity)})`,
      run: () => inspectFinding(item, reportId, env)
    }));
    if (evidence.metric) actions.push({label: 'Inspect metric operand', run: () => inspect('Score metric operand', 'CANONICAL METRIC', evidence.metric)});
    return inspect('Score dimension', 'ASSESSMENT EVIDENCE', dimension, actions);
  }
  function layoutConfig(name) {
    if (name === 'concentric') return {name: 'concentric', padding: 36, animate: false, minNodeSpacing: 28, concentric: node => node.degree(), levelWidth: () => 2};
    if (name === 'circle') return {name: 'circle', padding: 36, animate: false, spacingFactor: 1.2};
    if (name === 'grid') return {name: 'grid', padding: 36, animate: false, condense: true, spacingFactor: 1.25};
    return {name: 'breadthfirst', directed: true, padding: 35, spacingFactor: 1.4, animate: false};
  }

  /* ═══════════════════════════════════════════════════════════════════════════
     REMEDIATION & EVOLUTION PIPELINE (4-Stage Deterministic Workflow)
     ═══════════════════════════════════════════════════════════════════════════ */
  function selectRemediationFinding(item, reportId, env) {
    state.selectedFinding = item;
    state.selectedReportId = reportId;
    state.selectedEnv = env;

    // Highlight row in list
    document.querySelectorAll('#panel-policy .entity-row').forEach(row => {
      row.classList.toggle('active', row.dataset.findingId === item.identity);
    });

    const emptyState = $('remediation-empty-state');
    const activeWorkflow = $('remediation-active-workflow');
    if (emptyState) emptyState.hidden = true;
    if (activeWorkflow) activeWorkflow.hidden = false;

    const finding = item.result || {};
    const bundle = finding.bundle || {};
    const rule = finding.rule?.header || finding.rule || {};

    // Update Header
    const badgeEl = $('remedy-finding-badge');
    if (badgeEl) {
      badgeEl.textContent = finding.classification || 'MUST';
      badgeEl.className = 'badge ' + (finding.severity === 'ERROR' ? 'bad' : 'warn');
    }
    const titleEl = $('remedy-finding-title');
    if (titleEl) {
      titleEl.textContent = rule.name || rule.id || finding.kind || 'Finding';
    }

    // Reset stages
    const stageDiag = $('stage-diagnosis');
    const stageProp = $('stage-proposal');
    const stageVerif = $('stage-verification');
    if (stageDiag) stageDiag.hidden = true;
    if (stageProp) stageProp.hidden = true;
    if (stageVerif) stageVerif.hidden = true;

    const proposeBtn = $('remedy-propose-btn');
    const verifyBtn = $('remedy-verify-btn');
    if (proposeBtn) proposeBtn.hidden = true;
    if (verifyBtn) verifyBtn.hidden = true;

    // ── STAGE 1: Render Violation Evidence ──
    const evidenceBody = $('remedy-evidence-body');
    if (evidenceBody) {
      evidenceBody.replaceChildren();

      // Meta info
      const metaBox = el('div', 'remedy-meta-grid');
      const kind = finding.kind || 'RULE';
      const severity = rule.severity || 'ERROR';
      const scope = bundle.request?.scope || 'TYPE';

      append(metaBox,
        el('div', 'meta-item', `Rule: ${rule.id || 'N/A'}`),
        el('div', 'meta-item', `Kind: ${kind}`),
        el('div', 'meta-item', `Severity: ${severity}`),
        el('div', 'meta-item', `Scope: ${scope}`)
      );
      evidenceBody.append(metaBox);

      // Path / Cycle Chain
      if (bundle.path && bundle.path.length) {
        const pathBox = el('div', 'remedy-path-chain');
        pathBox.append(el('span', 'remedy-chain-label', kind === 'ACYCLIC' ? 'Circular Dependency Path:' : 'Violation Path:'));
        const chainWrap = el('div', 'chain-nodes');
        bundle.path.forEach((v, idx) => {
          if (idx > 0) chainWrap.append(el('span', 'chain-arrow', '→'));
          const nodeBtn = btn(v, () => {
            state.graphFocus = {seed: v, scope: bundle.request?.scope || 'TYPE'};
            closeInspector();
            loadTab('graph');
          }, 'chain-node-pill');
          chainWrap.append(nodeBtn);
        });
        pathBox.append(chainWrap);
        evidenceBody.append(pathBox);
      }

      // Action toolbar for Stage 1
      const actionRow = el('div', 'remedy-stage-actions');
      const coordsBtn = btn('📄 Source Coordinates', () => {
        let coordsCard = $('remedy-coords-card');
        if (coordsCard) { coordsCard.remove(); return; }
        coordsCard = el('div', 'card remedy-card-inner');
        coordsCard.id = 'remedy-coords-card';
        coordsCard.style.marginTop = '10px';
        coordsCard.append(el('strong', '', 'Source Locations (' + (bundle.sources?.length || 0) + ')'));
        if (bundle.sources && bundle.sources.length) {
          const sList = el('ul', 'remedy-source-list');
          bundle.sources.forEach(s => {
            const span = s.span || {};
            const doc = state.labels?.get(span.document) || span.document || 'source';
            sList.append(el('li', 'mono small', `${doc} [L${span.startLine}:${span.startColumn} - L${span.endLine}:${span.endColumn}]`));
          });
          coordsCard.append(sList);
        } else {
          coordsCard.append(el('p', 'small muted', 'No explicit source span captured for derived relationship.'));
        }
        evidenceBody.append(coordsCard);
      }, 'btn ghost btn-sm');

      const replayBtn = btn('🔄 Replay Witness', async () => {
        let replayCard = $('remedy-replay-card');
        if (replayCard) { replayCard.remove(); return; }
        replayCard = el('div', 'card remedy-card-inner');
        replayCard.id = 'remedy-replay-card';
        replayCard.style.marginTop = '10px';
        replayCard.append(el('p', 'small muted', 'Verifying witness condition against the captured model…'));
        evidenceBody.append(replayCard);
        try {
          const replay = await api('/graphs/' + env.graph + '/reports/' + reportId + '/replay', {arguments: {identity: item.identity, counterWitness: false}});
          replayCard.replaceChildren();
          const matched = replay.items?.[0]?.matched;
          replayCard.append(
            el('strong', '', matched ? '✔ Witness Matches Model' : 'Witness Check Complete'),
            el('p', 'small', 'The recorded rule predicate was evaluated deterministically at its captured configuration state without executing host code.')
          );
        } catch (e) {
          replayCard.replaceChildren(el('p', 'small text-error', 'Replay check unavailable: ' + e.message));
        }
      }, 'btn ghost btn-sm');

      const graphLinkBtn = btn('🗺 View in Graph ↗', () => {
        if (bundle.path && bundle.path.length) {
          state.graphFocus = {seed: bundle.path[0], scope: bundle.request?.scope || 'TYPE'};
        }
        closeInspector();
        loadTab('graph');
      }, 'btn ghost btn-sm');

      append(actionRow, coordsBtn, replayBtn, graphLinkBtn);
      evidenceBody.append(actionRow);
    }

    // Wire Topbar Actions
    const diagBtn = $('remedy-diagnose-btn');
    if (diagBtn) {
      diagBtn.disabled = false;
      diagBtn.textContent = 'Run AI Diagnosis';
      diagBtn.onclick = () => runDiagnosisStep(item, reportId, env);
    }

    const graphBtn = $('remedy-graph-btn');
    if (graphBtn) {
      graphBtn.onclick = () => {
        if (bundle.path && bundle.path.length) {
          state.graphFocus = {seed: bundle.path[0], scope: bundle.request?.scope || 'TYPE'};
        }
        closeInspector();
        loadTab('graph');
      };
    }
  }

  async function runDiagnosisStep(item, reportId, env) {
    const diagBtn = $('remedy-diagnose-btn');
    if (diagBtn) {
      diagBtn.disabled = true;
      diagBtn.textContent = 'Running Diagnosis…';
    }

    const stageDiag = $('stage-diagnosis');
    const diagBody = $('remedy-diagnosis-body');
    const diagMode = $('remedy-diagnosis-mode');
    if (stageDiag) stageDiag.hidden = false;
    if (diagBody) diagBody.replaceChildren(empty('Analyzing citations…', 'Retrieving evidence-grounded diagnosis from canonical knowledge graph.'));

    try {
      const args = {identity: item.identity, bundle: item.bundleIdentity, context: env.context};
      const diagnosis = await api('/graphs/' + env.graph + '/reports/' + reportId + '/diagnosis', {arguments: args});

      const validated = diagnosis.status === 'CITATIONS_VALIDATED';
      if (diagMode) {
        diagMode.textContent = validated ? 'Citations Validated' : 'Diagnosis Withheld';
        diagMode.className = 'badge ' + (validated ? 'good' : 'warn');
      }

      diagBody.replaceChildren();

      // Explanation card
      const explBox = el('div', 'remedy-explanation-box');
      append(explBox,
        el('strong', '', validated ? 'AI & Graph Grounded Diagnosis' : 'Diagnosis Assessment'),
        el('p', '', diagnosis.explanation || diagnosisReasons[diagnosis.reason] || 'Diagnosis completed.')
      );
      diagBody.append(explBox);

      // Citations
      if (diagnosis.citations && diagnosis.citations.length) {
        const citeBox = el('div', 'remedy-citations-box');
        citeBox.append(el('h4', 'remedy-subhead', `Source Citations (${diagnosis.citations.length})`));
        const list = el('ol', 'dependency-path');
        diagnosis.citations.forEach(cit => {
          const s = cit.span || {};
          const label = `${cit.path} · L${s.startLine}:${s.startColumn}–L${s.endLine}:${s.endColumn}`;
          const li = el('li');
          const citeLink = btn(label, () => {
            inspect('Cited source evidence', 'SOURCE EVIDENCE', async signal => {
              const evidence = await api('/graphs/' + env.graph + '/queries/evidence', {
                request: {...M.request(cit.context), scope: 'ENTITY'}, arguments: {reference: cit.evidence}
              }, signal);
              const record = evidence.items.find(r => r.identity === cit.evidence && r.family === cit.family);
              return {citation: cit, record};
            });
          }, 'textlink mono small');
          li.append(citeLink);
          list.append(li);
        });
        citeBox.append(list);
        diagBody.append(citeBox);
      }

      // Next Action: Enable Generate Patch
      const propBtn = $('remedy-propose-btn');
      if (propBtn) {
        propBtn.hidden = false;
        propBtn.disabled = false;
        propBtn.textContent = 'Generate Patch →';
        propBtn.onclick = () => runProposalStep(item, reportId, env, diagnosis);
      }

      // If cycle finding, also provide Decoupling Guidance
      const f = item.result || {};
      if (f.kind === 'ACYCLIC' && f.bundle?.path) {
        const decouplingCard = el('div', 'card remedy-decoupling-card');
        decouplingCard.style.marginTop = '12px';
        append(decouplingCard,
          el('strong', '', 'Architectural Decoupling Blueprint'),
          el('p', 'small', `Circular cycle spans ${f.bundle.path.length} classes: ${f.bundle.path.join(' → ')}. To cleanly break this cycle without regressions, introduce an abstraction (Interface Extraction or Event-driven mediation) at the lowest-affinity dependency link.`),
          el('pre', 'repair-patch mono small', `// Recommended Decoupling Strategy:
// 1. Extract interface for ${f.bundle.path[1] || 'Dependency'}
// 2. Invert constructor injection in ${f.bundle.path[0] || 'Client'}
// 3. Register bean as implementation interface`)
        );
        diagBody.append(decouplingCard);
      }
    } catch (e) {
      diagBody.replaceChildren(empty('Diagnosis unavailable', e.message));
    } finally {
      if (diagBtn) {
        diagBtn.disabled = false;
        diagBtn.textContent = 'Run AI Diagnosis';
      }
    }
  }

  function renderProposalResult(item, reportId, env, diagnosis, options, proposal, propBody) {
    propBody.replaceChildren();

    const controls = el('div', 'remedy-patch-actions');
    const recipeSelect = document.createElement('select');
    recipeSelect.className = 'input';
    (options.recipes || []).forEach(r => {
      const opt = document.createElement('option');
      opt.value = r.id;
      opt.textContent = r.title || r.id;
      recipeSelect.append(opt);
    });
    const runRecipeBtn = btn('Run Recipe', () => executeProposalChoice(item, reportId, env, diagnosis, options, {
      strategy: 'RECIPE',
      recipe: recipeSelect.value
    }, propBody), 'btn ghost btn-sm');
    runRecipeBtn.disabled = !(options.recipes && options.recipes.length);
    controls.append(recipeSelect, runRecipeBtn);
    if (options.ai && options.ai.input) {
      const aiBtn = btn(`Propose with AI (${options.ai.provider?.model || 'configured'})`, () => executeProposalChoice(item, reportId, env, diagnosis, options, {
        strategy: 'AI',
        modelInput: options.ai.input
      }, propBody), 'btn primary btn-sm');
      controls.append(aiBtn);
    }
    propBody.append(controls);

    if (proposal.status === 'PROPOSED') {
      const cand = proposal.proposal?.candidate || {};
      const patch = cand.patch || '';
      const targetList = cand.edits?.length ? cand.edits.map(e => e.path).join(', ') : (cand.edit?.path || 'source');

      const infoRow = el('div', 'remedy-patch-header');
      append(infoRow,
        el('span', 'badge good', 'Compiler Syntax PASSED'),
        el('span', 'small muted', `Target: ${targetList} · Strategy: ${cand.strategy || cand.engine?.version || 'RECIPE'}`)
      );
      propBody.append(infoRow);

      if (cand.recipe?.title || cand.plan?.summary) {
        propBody.append(el('p', 'small', cand.recipe?.title || cand.plan.summary));
      }

      const diffBox = el('div', 'diff-viewer');
      const diffPre = el('pre', 'diff-code');
      patch.split('\n').forEach(line => {
        const lineEl = el('div', 'diff-line');
        if (line.startsWith('+') && !line.startsWith('+++')) lineEl.className += ' diff-add';
        else if (line.startsWith('-') && !line.startsWith('---')) lineEl.className += ' diff-del';
        else if (line.startsWith('@@')) lineEl.className += ' diff-chunk';
        lineEl.textContent = line;
        diffPre.append(lineEl);
      });
      diffBox.append(diffPre);
      propBody.append(diffBox);

      const actionRow = el('div', 'remedy-patch-actions');
      const copyBtn = btn('Copy Patch', async () => {
        try {
          await navigator.clipboard.writeText(patch);
          copyBtn.textContent = 'Copied!';
          setTimeout(() => { copyBtn.textContent = 'Copy Patch'; }, 1500);
        } catch (_) {}
      }, 'btn ghost btn-sm');

      const dlBtn = btn('Download .patch', () => {
        downloadBlob(new Blob([patch], {type: 'text/x-diff'}), 'evolution-repair.patch');
      }, 'btn ghost btn-sm');

      const dlProposalBtn = btn('Download proposal.json', () => {
        downloadBlob(new Blob([JSON.stringify(proposal, null, 2)], {type: 'application/json'}), 'repair-proposal.json');
      }, 'btn ghost btn-sm');

      append(actionRow, copyBtn, dlBtn, dlProposalBtn);
      propBody.append(actionRow);

      const topDlBtn = $('remedy-download-btn');
      if (topDlBtn) {
        topDlBtn.onclick = () => downloadBlob(new Blob([patch], {type: 'text/x-diff'}), 'evolution-repair.patch');
      }

      const verifBtn = $('remedy-verify-btn');
      if (verifBtn) {
        verifBtn.hidden = false;
        verifBtn.disabled = false;
        verifBtn.textContent = 'Verify Candidate →';
        verifBtn.onclick = () => runVerificationStep(item, reportId, env, diagnosis, proposal);
      }
    } else {
      const msg = el('div', 'notice');
      append(msg,
        el('strong', '', 'Repair Proposal Withheld'),
        el('p', 'small', repairReasons[proposal.reason] || proposal.reason || 'Select a recipe above or configure an AI repair model for this finding.'),
        el('p', 'small muted', 'Available registered recipes: ' + (options.recipes?.map(r => r.id).join(', ') || 'none'))
      );
      propBody.append(msg);

      const verifBtn = $('remedy-verify-btn');
      if (verifBtn) {
        verifBtn.hidden = true;
        verifBtn.disabled = true;
      }
    }
  }

  async function executeProposalChoice(item, reportId, env, diagnosis, options, extraArgs, propBody) {
    propBody.replaceChildren(empty('Generating proposal…', 'Running compiler syntax verification…'));
    try {
      const args = Object.assign(
        {identity: item.identity, bundle: item.bundleIdentity, context: env.context, diagnosisInput: diagnosis.input},
        extraArgs
      );
      const proposal = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', {arguments: args});
      renderProposalResult(item, reportId, env, diagnosis, options, proposal, propBody);
    } catch (e) {
      propBody.replaceChildren(empty('Proposal unavailable', e.message));
    }
  }

  async function runProposalStep(item, reportId, env, diagnosis) {
    const propBtn = $('remedy-propose-btn');
    if (propBtn) {
      propBtn.disabled = true;
      propBtn.textContent = 'Generating Patch…';
    }

    const stageProp = $('stage-proposal');
    const propBody = $('remedy-proposal-body');
    if (stageProp) stageProp.hidden = false;
    if (propBody) propBody.replaceChildren(empty('Generating proposal…', 'Checking repair options and compiler syntax…'));

    try {
      const baseArgs = {identity: item.identity, bundle: item.bundleIdentity, context: env.context, diagnosisInput: diagnosis.input};
      const options = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-options', {arguments: baseArgs});
      let proposal = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', {arguments: baseArgs});

      if (proposal.status !== 'PROPOSED' && options.recipes?.length) {
        for (const r of options.recipes) {
          const candidate = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-proposal', {
            arguments: Object.assign({}, baseArgs, {strategy: 'RECIPE', recipe: r.id})
          });
          if (candidate.status === 'PROPOSED') {
            proposal = candidate;
            break;
          }
        }
      }
      renderProposalResult(item, reportId, env, diagnosis, options, proposal, propBody);
    } catch (e) {
      propBody.replaceChildren(empty('Proposal unavailable', e.message));
    } finally {
      if (propBtn) {
        propBtn.disabled = false;
        propBtn.textContent = 'Generate Patch';
      }
    }
  }

  async function runVerificationStep(item, reportId, env, diagnosis, proposal, operands = {}) {
    const verifBtn = $('remedy-verify-btn');
    if (verifBtn) {
      verifBtn.disabled = true;
      verifBtn.textContent = 'Verifying…';
    }

    const stageVerif = $('stage-verification');
    const verifBody = $('remedy-verification-body');
    const verifBadge = $('remedy-verification-verdict');
    if (stageVerif) stageVerif.hidden = false;
    if (verifBody) verifBody.replaceChildren(empty('Verifying proposal…', 'Checking server-side baseline pins, reanalysis comparisons, and isolated VM proof.'));

    try {
      const propId = proposal?.proposal?.identity;
      if (!propId) {
        throw new Error('Verification requires a compiler-checked proposal.');
      }
      const args = {
        identity: item.identity,
        bundle: item.bundleIdentity,
        context: env.context,
        diagnosisInput: diagnosis.input,
        proposal: propId
      };
      if (operands.candidateReport) args.candidateReport = operands.candidateReport;
      if (operands.controlReport) args.controlReport = operands.controlReport;

      const outcome = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-verification', {arguments: args});
      const verified = outcome.verdict === 'VERIFIED' || outcome.verdict === 'VERIFIED_DEMO';
      const rejected = outcome.verdict === 'REJECTED';

      if (verifBadge) {
        verifBadge.textContent = outcome.verdict || 'UNVERIFIED';
        verifBadge.className = 'badge ' + (verified ? 'good' : (rejected ? 'bad' : 'warn'));
      }

      verifBody.replaceChildren();

      const actionRow = el('div', 'remedy-patch-actions');
      if (state.capabilities?.repairReanalysisEnabled) {
        const reanalyzeBtn = btn('Run Candidate Reanalysis + Control', async () => {
          reanalyzeBtn.disabled = true;
          try {
            reanalyzeBtn.textContent = 'Reanalyzing candidate + control in parallel…';
            const reResult = await api('/graphs/' + env.graph + '/reports/' + reportId + '/repair-reanalysis', {arguments: Object.assign({}, args, {includeControl: true})});
            await runVerificationStep(item, reportId, env, diagnosis, proposal, {
              candidateReport: reResult.candidateReport || operands.candidateReport,
              controlReport: reResult.controlReport || operands.controlReport
            });
          } catch (err) {
            verifBody.prepend(el('p', 'small bad', 'Reanalysis error: ' + err.message));
            reanalyzeBtn.disabled = false;
            reanalyzeBtn.textContent = 'Run Candidate Reanalysis + Control';
          }
        }, 'btn primary btn-sm');
        actionRow.append(reanalyzeBtn);
      }
      const refreshBtn = btn('Refresh Verification', () => runVerificationStep(item, reportId, env, diagnosis, proposal, operands), 'btn ghost btn-sm');
      actionRow.append(refreshBtn);
      verifBody.append(actionRow);

      const checkCard = el('div', 'card remedy-verif-card');
      checkCard.append(el('strong', '', 'Verification Checks'));
      const list = el('ul', 'remedy-check-list');
      (outcome.checks || []).forEach(c => {
        const passed = c.status === 'PASSED';
        const failed = c.status === 'FAILED';
        const icon = passed ? '✔' : (failed ? '✘' : '•');
        const li = el('li', 'check-item');
        append(li,
          el('span', 'check-icon', icon),
          append(el('div', 'check-text'),
            el('strong', '', `${c.id} (${c.status})`),
            el('p', 'small muted', c.reason + (c.evidence ? ` · ${M.shortDigest(c.evidence)}` : '')))
        );
        list.append(li);
      });
      checkCard.append(list);
      verifBody.append(checkCard);

      if (outcome.candidateComparison) {
        const cc = outcome.candidateComparison;
        const cmpCard = el('div', 'card remedy-verif-card');
        append(cmpCard,
          el('strong', '', 'Candidate Snapshot Comparison'),
          el('p', 'small', `Status: ${cc.status} · Transitions: ${(cc.transitions || []).join(', ') || 'none'} · Regression: ${cc.regressionDetected ? 'YES' : 'NO'}`),
          el('p', 'small muted', `Reasons: ${(cc.reasons || []).join(', ') || 'none'}`)
        );
        verifBody.append(cmpCard);
      }

      if (outcome.isolatedProof) {
        const vm = outcome.isolatedProof;
        const vmCard = el('div', 'card remedy-verif-card');
        append(vmCard,
          el('strong', '', 'Isolated QEMU VM Build & Test Proof'),
          el('p', 'small', `Tool: ${vm.buildTool} · Modules: ${(vm.modules || []).join(', ')} · Compile exit: ${vm.compileExit} · Test exit: ${vm.testExit}`),
          el('p', 'small', `Tests run: ${vm.testsRun}, failed: ${vm.testsFailed}, errors: ${vm.testsErrors} · Bytecode classes: ${vm.classCount}`),
          el('p', 'small mono muted', `Capture: ${M.shortDigest(vm.capture)} · Census: ${M.shortDigest(vm.classCensusDigest)}`)
        );
        verifBody.append(vmCard);
      }

      const prCard = el('div', 'remedy-pr-box');
      if (verified) {
        append(prCard,
          el('strong', '', 'Verified Patch Receipt'),
          el('p', 'small mono', `Plan: ${M.shortDigest(outcome.planIdentity)} · Proposal: ${M.shortDigest(propId)}`),
          el('p', 'small', 'Server-verified: all required checks passed and original policy finding is resolved.')
        );
      } else {
        append(prCard,
          el('strong', '', rejected ? 'Verification Rejected' : 'Verification Incomplete'),
          el('p', 'small mono', `Plan: ${M.shortDigest(outcome.planIdentity)} · Proposal: ${M.shortDigest(propId)}`),
          el('p', 'small', rejected
            ? 'One or more verification checks failed. Inspect failed checks above before using this patch.'
            : 'Run candidate reanalysis and configure an isolated VM capture (capture.json) to complete verification.')
        );
      }
      verifBody.append(prCard);
    } catch (e) {
      if (verifBadge) {
        verifBadge.textContent = 'UNVERIFIED';
        verifBadge.className = 'badge warn';
      }
      verifBody.replaceChildren(empty('Verification unavailable', e.message));
    } finally {
      if (verifBtn) {
        verifBtn.disabled = false;
        verifBtn.textContent = 'Verify Candidate →';
      }
    }
  }

  const views = {
    async overview(env) {
      const summary = await env.query('summary'), node = el('div'), counts = summary.items?.[0], stats = el('div', 'stats');
      for (const card of M.summaryCards(summary)) {
        const stat = btn('', () => {
          if (card.tab === 'structure') { state.structureFocus = card.kind; $('search').value = ''; }
          else state.gapFocus = card.kind;
          return loadTab(card.tab);
        }, 'stat');
        append(stat, el('span', '', card.label), el('strong', '', card.count ?? '—'), el('small', '', card.observed ? 'Observed · click to inspect' : 'In the selected context')); stats.append(stat);
      }
      node.append(stats); const grid = el('div', 'content-grid'); node.append(grid);
      const health = card('Architecture assessment', 'Based on the selected policy and the evidence available for this analysis.'); grid.append(health);
      if (env.reportIds.length !== 1) health.append(empty(env.reportIds.length ? 'Select a policy report' : 'Score unavailable — no rules configured', env.reportIds.length ? 'Open Metrics & score to choose an exact report.' : 'A score needs explicit architecture rules and enough evidence. Structural metrics are available without a policy.'),btn(env.reportIds.length?'Open Metrics & score':'Configure rules & scoring',()=>env.reportIds.length?loadTab('metrics'):configureRules(),'button primary'));
      else {
        try {
          const assessment = await env.report(env.reportIds[0].identity, 'assessment');
          health.append(el('div', 'score', M.assessment(assessment)), badge(assessment.status));
          if (assessment.status === 'WITHHELD') {
            const banner = el('div', 'assessment-withheld-banner');
            const gapCount = state.resultJob?.result?.gapCount || 'unresolved';
            append(banner,
              el('strong', '', 'Score Withheld: Incomplete Input Evidence'),
              el('p', '', `The overall architecture score cannot be computed because this offline analysis recorded ${gapCount} capability limitations (such as missing external JARs or dynamic Spring profiles).`),
              el('p', '', 'All structural facts, dependency graphs, and individual code metrics remain 100% verified and available.')
            );
            health.append(banner);
          } else if (assessment.confidence) {
            health.append(el('p', 'muted small', assessment.confidence.sufficient ? 'Required assessment evidence is sufficient.' : 'Required assessment evidence is incomplete.'));
          }
          const DIM_NAMES = {
            'health:boundaries': 'Package Boundaries',
            'health:coupling': 'Coupling & Modularity',
            'health:cyclicity': 'Dependency Acyclicity',
            'health:hotspots': 'Structural Hotspots',
            'health:policy': 'Policy Conformance'
          };
          const dimWrap = el('div', 'dimensions-table');
          dimWrap.style.margin = '10px 0';
          for (const dimension of assessment.dimensions || []) {
            const row = el('div', 'dimension-row');
            const dName = DIM_NAMES[dimension.id] || dimension.id;
            const dVal = dimension.score != null ? `${dimension.score} / 100` : 'Pending complete evidence';
            append(row,
              el('span', 'dimension-name', dName),
              el('strong', 'dimension-val' + (dimension.score == null ? ' dim-withheld' : ''), dVal)
            );
            dimWrap.append(row);
          }
          health.append(dimWrap);
          health.append(btn('View score breakdown & metrics', () => loadTab('metrics'), 'text-button'));
        } catch (error) { health.append(empty('Assessment unavailable', error.message)); }
      }
      const guide = card('Follow the evidence', 'Jump directly to the workflow that matches your architectural question.'); grid.append(guide);
      for (const [label, sub, tab] of [
        ['Open Refactoring & Cycle Advisor →', 'Tarjan SCC tangles, minimal cycle-breakers, blast radius, DSM & Markdown export', 'insights'],
        ['Browse captured structure →', 'Navigate modules, packages, classes, methods and source files', 'structure'],
        ['Explore dependency graph →', 'Interactive canvas with layout switcher, ego-spotlight and shortest path', 'graph'],
        ['Compare modeled configurations →', 'Diff required (MUST) vs conditional (MAY) dependencies across profiles', 'configurations'],
        ['Inspect coupling & hotspots →', 'Measure incoming/outgoing coupling (Ca/Ce), fan-in/fan-out and LOC', 'metrics'],
        ['Review rules and findings →', 'Check cyclic dependencies and policy violations with source witnesses', 'policy'],
        ['Inspect limitations & gaps →', 'Plain-English root-cause breakdown of unresolved or dynamic boundaries', 'gaps']
      ]) guide.append(row({label, kind: tab}, () => loadTab(tab), sub));
      const inventory = card('Captured inventory — visual distribution & counts', 'All discovered AST entities, source documents, and dependencies in this context.');
      const nodeEntries = Object.entries(counts?.nodeCounts || {}).sort((a, b) => b[1] - a[1]);
      const maxCount = Math.max(1, ...nodeEntries.map(([, c]) => Number(c) || 0));
      const invGrid = el('div', 'dist-list');
      for (const [kind, count] of nodeEntries) {
        const pct = Math.max(2, Math.round((Number(count) || 0) * 100 / maxCount));
        const bar = append(el('div', 'dist-bar'), el('span', 'dist-fill'));
        bar.firstChild.style.width = pct + '%';
        invGrid.append(append(el('div', 'dist-row'), append(el('div', 'dist-meta'), el('span', '', kind.toLowerCase().replaceAll('_', ' ')), el('strong', 'mono', count)), bar));
      }
      inventory.append(invGrid);
      inventory.style.marginTop = '20px'; node.append(inventory); return {node, status: summary.status === 'COMPLETE' ? 'Selected context · Evidence complete' : 'Selected context · Incomplete evidence — review limitations'};
    },
    async structure(env) {
      const node = card('Repository structure', 'Open a module, package or class to browse its recorded contents. Use Details for source locations, dependencies and impact.');
      const levelOptions = [['MODULE','Modules'],['PACKAGE','Packages'],['TYPE','Classes'],['MEMBER','Members'],['SOURCE_DOCUMENT','Source files'],['SOURCE_ROOT','Source roots']];
      const focus = state.structureFocus; state.structureFocus = null;
      const level = selectControl('Browse level', levelOptions, focus || ($('search').value.trim() ? 'TYPE' : 'MODULE'));
      const population = selectControl('Symbol population', [['all','All captured origins'],['PROJECT','Project code'],['JDK','JDK types'],['DEPENDENCY','External dependencies'],['SYNTHETIC','Synthetic evidence']]);
      const sourceSet = selectControl('Source set', [['','All source sets'],['MAIN','Main'],['TEST','Test'],['GENERATED_MAIN','Generated main'],['GENERATED_TEST','Generated test']]);
      const quickPills = el('div', 'chip-bar');
      const syncPills = () => { [...quickPills.children].forEach(c => c.classList.toggle('active', c.dataset.level === level.value)); };
      for (const [val, text] of levelOptions) {
        const pill = btn(text, () => { level.value = val; trail = []; syncPills(); return load(); }, 'chip');
        pill.dataset.level = val; quickPills.append(pill);
      }
      const breadcrumbs = el('nav', 'breadcrumbs'); breadcrumbs.setAttribute('aria-label','Structure location');
      const selection = el('div', 'selection-bar'), results = el('div');
      node.append(quickPills, append(el('div','toolbar'),field('Browse',level),field('Origin',population),field('Source set',sourceSet)),breadcrumbs,selection,results);
      let version = 0, trail = !focus && state.explorer?.context === env.context ? state.explorer.trail : [];
      const memberKinds = ['METHOD','CONSTRUCTOR','FIELD','PARAMETER','TYPE_PARAMETER','RECORD_COMPONENT','INITIALIZER','LAMBDA'];
      async function load() {
        syncPills();
        const current = ++version, search = $('search').value.trim(), target = el('div'), parent = search ? null : trail.at(-1);
        state.explorer = {context:env.context,trail}; breadcrumbs.replaceChildren(btn('Repository',()=>{trail=[];$('search').value='';return load();},'text-button'));
        trail.forEach((item,index)=>breadcrumbs.append(el('span','','/'),btn(M.label(item),()=>{trail=trail.slice(0,index+1);$('search').value='';return load();},'text-button')));
        selection.replaceChildren(); if(parent)selection.append(el('strong','',M.label(parent)),btn('Details',()=>inspectEntity(parent,env)),btn('Back',()=>{trail.pop();return load();}));
        if(search) selection.append(el('p','muted small','Search results across the selected context: '+search),btn('Clear search',()=>{$('search').value='';return load();}));
        results.replaceChildren(empty('Loading…', 'Reading matching symbols.'));
        const overrides = {scope: parent ? 'ENTITY' : ['MODULE','PACKAGE','TYPE'].includes(level.value) ? level.value : 'ENTITY',
          population:{nodeKinds:parent?[]:level.value==='MEMBER'?memberKinds:[level.value],origins:population.value==='all'?[]:[population.value],sourceSets:sourceSet.value?[sourceSet.value]:[]}};
        try { await pagedList(target, env, cursor => env.query(parent?'children':'entities', parent?{identity:parent.identity}:{search}, {size:50,...(cursor?{cursor}:{})}, overrides), item => {
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
      const focus = state.gapFocus; state.gapFocus = null;
      const node = card('Analysis limitations & capability boundaries', 'Missing or unsupported evidence is recorded explicitly with plain-English explanations so partial coverage is never mistaken for clean architecture.');
      const labels = new Map(), rawSupplemental = [];
      if (env.jobId) {
        try { const supplemental = await api('/analysis/jobs/' + env.jobId + '/gaps', {size: 50}, env.signal);
          supplemental.items.forEach(gap => {
            rawSupplemental.push(gap);
            const info = M.humanReason(gap.reasonCode);
            labels.set(gap.gapIdentity, info.title + ' (' + gap.reasonCode + ') · ' + gap.mechanismCategory);
          });
        } catch (error) { if (env.signal.aborted) throw error; /* Exact graph evidence remains accessible even without supplemental labels. */ }
      }
      if (rawSupplemental.length) {
        const grouped = M.summarizeGaps(rawSupplemental);
        const maxGap = Math.max(1, ...grouped.map(g => g.count));
        const breakdown = append(el('div', 'gap-summary-box'), el('h3', '', 'Top observed limitation categories (sample of ' + rawSupplemental.length + ')'));
        const list = el('div', 'dist-list');
        for (const group of grouped.slice(0, 6)) {
          const pct = Math.max(4, Math.round(group.count * 100 / maxGap));
          const bar = append(el('div', 'dist-bar'), el('span', 'dist-fill warn'));
          bar.firstChild.style.width = pct + '%';
          list.append(append(el('div', 'gap-group-card'),
            append(el('div', 'dist-meta'), el('strong', '', group.info.title), badge(group.code + ' · ' + group.count)),
            bar,
            el('p', 'small muted', group.info.explanation),
            el('p', 'small human-action-hint', 'Fix / Action: ' + group.info.action)
          ));
        }
        breakdown.append(list);
        node.append(breakdown);
      }
      await pagedList(node, env, cursor => env.query('gaps', {}, {size: 50, ...(cursor ? {cursor} : {})},
        focus ? {population: {nodeKinds: [focus]}} : {}), item => {
        const text = labels.get(item.identity) || M.label(item);
        return row({...item, label: text}, () => inspectEntity(item, env), 'Click to inspect exact source coordinates & diagnostic trail');
      }, 'No gap entries returned', 'Read the result status and input diagnostics as well. An empty page alone does not prove complete analysis.'); return node;
    },
    async policy(env) {
      const node = card('Rules & findings', 'Inspect the evaluated rules and their evidence. An absent report is not a clean architecture verdict.');
      if (!env.reportIds.length) { node.append(empty('No policy report for this context', 'Add architecture policy YAML when connecting the repository, then run a new analysis.'), btn('Configure rules & scoring', configureRules, 'button primary')); return node; }
      const toolbar = el('div', 'toolbar'), reports = el('select'), kinds = el('select'); reports.setAttribute('aria-label', 'Policy report'); kinds.setAttribute('aria-label', 'Rules or findings');
      env.reportIds.forEach((report, index) => { const option = el('option', '', 'Policy report ' + (index + 1)); option.value = report.identity; reports.append(option); });
      for (const op of ['findings', 'rules']) { const option = el('option', '', op === 'rules' ? 'Evaluated rules' : 'Observed findings'); option.value = op; kinds.append(option); }
      toolbar.append(reports, kinds); node.append(toolbar); const results = el('div'); node.append(results); let version = 0;
      async function load() {
        const current = ++version, id = reports.value, op = kinds.value, target = el('div');
        results.replaceChildren(empty('Loading…', 'Reading the selected report.'));
        try { await pagedList(target, env, cursor => env.report(id, op, {}, {size: 50, ...(cursor ? {cursor} : {})}), item => {
          const finding = item.result, rule = finding.rule?.header || finding.rule;
          const label = rule?.name || rule?.id || finding.kind || 'Evaluated rule';
          const r = row({label, kind: finding.classification || finding.status}, () => {
            if (op === 'findings') {
              selectRemediationFinding(item, id, env);
            } else {
              inspect(label, 'POLICY EVIDENCE', finding);
            }
          }, [rule?.severity, finding.status].filter(Boolean).join(' · '));
          r.dataset.findingId = item.identity;
          return r;
        }, op === 'findings' ? 'No observed findings' : 'No evaluated rules', 'Review the report status and rule denominator before interpreting this result.');
        if (!env.signal.aborted && current === version) {
          results.replaceChildren(target);
          if (op === 'findings') {
            const firstRow = target.querySelector('.entity-row');
            if (firstRow && !state.selectedFinding) {
              firstRow.click();
            }
          }
        }
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
        try { await pagedList(target, env, cursor => env.query(category === 'configurations' ? 'configurations' : 'spring', category === 'configurations' ? {} : {kinds: category === 'endpoints' ? ['ENDPOINT'] : category==='registration'?['REGISTRATION_EVENT','REGISTRATION_TRANSITION']:['BEAN_PRODUCER', 'BEAN_DEFINITION_CANDIDATE', 'INJECTION_POINT', 'INJECTION_USE']}, {size: 50, ...(cursor ? {cursor} : {})}), item => row(item, () => inspectEntity(item, env)), 'No evidence available in this context', 'Try another context or inspect limitations.');
          if (!env.signal.aborted && current === version) results.replaceChildren(target);
        } catch (error) { if (!env.signal.aborted && current === version) results.replaceChildren(empty('Spring evidence unavailable', error.message), btn('Retry', load)); }
      }
      select.addEventListener('change', () => safe(load)); await load(); return node;
    },
    async configurations(env) {
      const node=card('Compare configurations','Compare dependencies within this captured analysis. Recorded configurations and modeled examples are distinct from a running application.');
      node.append(el('p','notice small','“All observed” can include alternatives that never coexist. “Required” keeps dependencies present in every modeled configuration. Missing evidence remains unknown.'));
      const options=[['MUST','Required in every configuration (MUST)'],['MAY','Conditional dependencies (MAY)'],['UNKNOWN_OVERLAY','Uncertain dependencies (UNKNOWN)'],['BOUNDED_UNION','All observed dependencies (UNION)']];
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
            for(const value of rows){const edge=value.edge;body.append(append(el('tr'),append(el('td'),btn((state.labels.get(edge.source)||M.shortDigest(edge.source))+' → '+(state.labels.get(edge.target)||M.shortDigest(edge.target)),()=>inspect('Dependency evidence','DEPENDENCY',edge,[{label:'Source details',run:async()=>{const value=await env.query('entity',{identity:edge.source});if(!env.signal.aborted&&value.items[0])return inspectEntity(value.items[0],env);}},{label:'Target details',run:async()=>{const value=await env.query('entity',{identity:edge.target});if(!env.signal.aborted&&value.items[0])return inspectEntity(value.items[0],env);}}]),'text-button')),el('td','',badge(value.a)),el('td','',badge(value.b))));}
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
        async function score(){const current=++version, reportId=reportSelect.value;detail.replaceChildren(empty('Loading assessment…','Reading the published score and explanation.'));
          try{const value=await env.report(reportId,'assessment');if(env.signal.aborted||current!==version)return;
            detail.replaceChildren(append(el('div','score-heading'),el('strong','score',M.assessment(value)),badge(value.status)));
            detail.append(el('p','muted',value.confidence?.sufficient?'The model’s required evidence is sufficient.':'Required evidence is incomplete. Review the recorded reasons before interpreting this assessment.'));
            for(const dimension of value.dimensions||[])detail.append(append(el('div','dimension'),btn(dimension.id.replace(/^health\./,'').replaceAll('-',' '),()=>inspectScoreDimension(value,dimension,reportId,env),'text-button'),el('strong','',dimension.score??'Unavailable')));
            detail.append(append(el('div','toolbar'),btn('Explain score & withheld reasons',()=>inspect('Architecture assessment','ASSESSMENT',value,[{label:'Review findings',run:()=>{closeInspector();return loadTab('policy');}}]),'text-button'),btn('Policy metrics',()=>inspect('Policy metrics','METRICS',signal=>api('/graphs/'+env.graph+'/reports/'+reportSelect.value+'/policy-metrics',{arguments:{}},signal)),'text-button')));
          }catch(error){if(!env.signal.aborted&&current===version)detail.replaceChildren(empty('Assessment unavailable',error.message));}}
        reportSelect.addEventListener('change',()=>safe(score));await score();
      }
      const catalog=await env.query('metric-catalog',{}, {size:50}),metricCard=card('Architecture metrics','Choose a metric and scope. Values, definitions, units, limitations and supporting evidence come from the canonical backend.');metricCard.style.marginTop='20px';node.append(metricCard);
      const metricNames={'metric.ca':'Incoming coupling (Ca)','metric.ce':'Outgoing coupling (Ce)','metric.scc':'Connected components (SCC)','metric.physical-loc':'Physical lines of code (LOC)','metric.quality':'Evidence quality','metric.inventory':'Captured inventory','metric.fan-in':'Incoming dependencies (Fan-in)','metric.fan-out':'Outgoing dependencies (Fan-out)','metric.hotspots':'Dependency hotspots','metric.crossings':'Layer boundary crossings','metric.operational':'Measured runtime','metric.spring':'Spring inventory'};
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
            result.append(el('h3','','Ranked dependencies & coupling hotspots'),el('p','muted small',rankings.length+' rows shown'+(data.details.rankingPopulation==null?'':' from '+data.details.rankingPopulation+' ranked entities')+(data.details.rankingTruncated?' · Ranking is bounded':'')));
            const maxDeg = Math.max(1, ...rankings.map(r => (Number(r.fanIn) || 0) + (Number(r.fanOut) || 0)));
            for(const value of rankings){
              const rNode = row({identity:value.identity,label:state.labels.get(value.identity)||M.shortDigest(value.identity),kind:'Hotspot'},async()=>{const entity=await env.query('entity',{identity:value.identity});if(!env.signal.aborted&&entity.items[0])return inspectEntity(entity.items[0],env);return inspect('Ranked metric entry','METRIC EVIDENCE',value);},value.fanIn+' incoming (fan-in) · '+value.fanOut+' outgoing (fan-out)');
              const barWrap = el('div', 'coupling-bars'), inBar = el('span', 'coupling-in'), outBar = el('span', 'coupling-out');
              inBar.style.width = Math.max(2, Math.round((Number(value.fanIn) || 0) * 100 / maxDeg)) + '%';
              outBar.style.width = Math.max(2, Math.round((Number(value.fanOut) || 0) * 100 / maxDeg)) + '%';
              barWrap.append(inBar, outBar);
              rNode.firstChild.append(barWrap);
              result.append(rNode);
            }
          }
        }catch(error){if(!env.signal.aborted&&current===version)result.replaceChildren(empty('Metric unavailable',error.message),btn('Retry',evaluate));}}
      toolbar.append(btn('Read metric',evaluate,'button primary'),btn('Use whole context',()=>{selectedSubject=env.context;subjectLabel.textContent='Subject: the complete selected analysis context.';return evaluate();}));
      metric.addEventListener('change',()=>safe(evaluate));scope.addEventListener('change',()=>safe(evaluate));await evaluate();return node;
    },
    async impact(env) {
      const node = card('Change impact (Blast Radius)', 'Find a class or component, then inspect the incoming dependency paths that may be affected if you modify it. This is potential structural impact, not a runtime simulation.');
      const form = el('form', 'toolbar'), input = el('input'), submit = el('button', 'button primary', 'Find symbol'); input.type = 'search'; input.placeholder = 'Class name or exact symbol identity (e.g. OrderService)'; input.setAttribute('aria-label', 'Symbol for impact analysis'); input.required = true; submit.type = 'submit'; form.append(input, submit); node.append(form);
      const results = empty('Choose a symbol', 'Search by class name, then select the exact symbol to calculate its upstream dependents and blast radius.'); node.append(results); let version = 0;
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
      const node = card('Interactive dependency graph', 'Click any node to spotlight its 1-hop neighborhood and reduce visual clutter. Switch layouts or filter by certainty layer.');
      let focus = state.graphFocus || {};
      const toolbar = el('div', 'toolbar'), scope = el('select'), projection = el('select'), layoutSelect = selectControl('Graph layout', [['breadthfirst', 'Hierarchy (Directed)'], ['concentric', 'Concentric (Centrality)'], ['circle', 'Circular (Ring)'], ['grid', 'Grid (Matrix)']], 'breadthfirst');
      scope.setAttribute('aria-label', 'Graph scope'); projection.setAttribute('aria-label', 'Dependency view');
      for (const [value, label] of [['MODULE', 'Modules'], ['PACKAGE', 'Packages'], ['TYPE', 'Classes'],['SPRING_COMPONENT','Spring components'],['ENTITY','Members & entities']]) { const option = el('option', '', label); option.value = value; scope.append(option); }
      for (const [value, label] of [['BOUNDED_UNION', 'All observed (UNION)'], ['MUST', 'Required in all configs (MUST)'], ['MAY', 'Conditional (MAY)'], ['UNKNOWN_OVERLAY', 'Uncertain (UNKNOWN)']]) { const option = el('option', '', label); option.value = value; projection.append(option); }
      if(focus.realization){const option=el('option','','Selected recorded configuration');option.value='REALIZED';projection.append(option);}
      scope.value=focus.scope||(env.springContext?'SPRING_COMPONENT':(env.context?.startsWith('analysis:')?'TYPE':'MODULE'));projection.value=focus.projection||'BOUNDED_UNION';
      const depth=selectControl('Neighborhood depth',[['1','1 hop (direct)'],['2','2 hops'],['3','3 hops']]);depth.hidden=!focus.seed;
      const clear=btn('Reset to full view',()=>{focus={...focus,seed:null};depth.hidden=true;clear.hidden=true;return load();});clear.hidden=!focus.seed;
      toolbar.append(field('Scope',scope),field('Dependency view',projection),field('Layout',layoutSelect),depth,clear); node.append(toolbar); const content = el('div'); node.append(content); let version = 0;
      async function load() {
        const current = ++version; content.replaceChildren(empty('Loading graph…', 'Reading bounded canonical dependencies.'));
        try {
          const graph = await env.query('visual-graph', focus.seed?{seed:focus.seed,depth:Number(depth.value)}:{}, undefined, {scope: scope.value,relationshipKinds:focus.relationshipKinds||relationshipKinds(scope.value), projection: projection.value,...(projection.value==='REALIZED'?{realization:focus.realization}:{})});
          if (env.signal.aborted || current !== version) return;
          state.cy?.destroy(); state.cy = null; content.replaceChildren();
          content.append(el('p', 'muted small', graph.nodes.length + ' displayed nodes · ' + graph.edges.length + ' displayed edges · '+(graph.metadata.status||'Observed projection') + (graph.metadata.isTruncated ? ' · Result limited — narrow the scope' : '')));
          if (!graph.nodes.length) { content.append(empty('No dependency nodes available', 'Choose another scope or context. An empty graph does not establish an absence of dependencies.')); return; }
          if (!window.cytoscape) { content.append(empty('Graph renderer unavailable', 'The local graph library could not be loaded. You can still download the canonical projection.'), btn('Download graph JSON', () => download(graph, 'dependency-graph.json'))); return; }
          const filterBar = el('div', 'graph-filter-bar'), canvasFilter = el('input');
          canvasFilter.type = 'search'; canvasFilter.placeholder = 'Filter & spotlight nodes on canvas (e.g. Service, Controller)…'; canvasFilter.setAttribute('aria-label', 'Filter canvas nodes');
          const lensGroup = el('div', 'chip-bar');
          const edgeCounts = {ALL: graph.edges.length, MUST: 0, MAY: 0, UNKNOWN: 0};
          graph.edges.forEach(e => { const c = e.classification || 'MUST'; if (edgeCounts[c] != null) edgeCounts[c]++; });
          let activeLens = 'ALL';
          function applyCanvasFilters() {
            if (!state.cy) return;
            const q = canvasFilter.value.trim().toLowerCase();
            state.cy.elements().removeClass('dimmed ego-highlight');
            state.cy.edges().forEach(edge => {
              const cls = edge.data('classification') || 'MUST';
              const matchLens = activeLens === 'ALL' || cls === activeLens;
              if (!matchLens) edge.addClass('dimmed');
            });
            if (q) {
              const matched = state.cy.nodes().filter(n => !n.id().startsWith('compound:') && String(n.data('label') || '').toLowerCase().includes(q));
              const neighborhood = matched.union(matched.connectedEdges()).union(matched.neighborhood('node'));
              state.cy.elements().not(neighborhood).not(':parent').addClass('dimmed');
              matched.addClass('ego-highlight');
            }
          }
          for (const [lensId, lensLabel] of [['ALL', 'All edges (' + edgeCounts.ALL + ')'], ['MUST', 'Required (' + edgeCounts.MUST + ')'], ['MAY', 'Conditional (' + edgeCounts.MAY + ')'], ['UNKNOWN', 'Uncertain (' + edgeCounts.UNKNOWN + ')']]) {
            const pill = btn(lensLabel, () => { activeLens = lensId; [...lensGroup.children].forEach(c => c.classList.toggle('active', c.dataset.lens === lensId)); applyCanvasFilters(); }, 'chip' + (lensId === 'ALL' ? ' active' : ''));
            pill.dataset.lens = lensId; lensGroup.append(pill);
          }
          canvasFilter.addEventListener('input', applyCanvasFilters);
          filterBar.append(canvasFilter, lensGroup);
          const controls = el('div', 'toolbar'), canvas = el('div', 'graph-canvas'), spotlightBar = el('div', 'node-spotlight-bar');
          spotlightBar.hidden = true;
          canvas.setAttribute('aria-label', 'Interactive dependency graph'); canvas.tabIndex = 0;
          controls.append(btn('Zoom in', () => state.cy.zoom(state.cy.zoom() * 1.25)), btn('Zoom out', () => state.cy.zoom(state.cy.zoom() / 1.25)), btn('Fit graph', () => state.cy.fit(undefined, 36)), btn('Reset highlight', () => { canvasFilter.value = ''; activeLens = 'ALL'; [...lensGroup.children].forEach(c => c.classList.toggle('active', c.dataset.lens === 'ALL')); state.cy.elements().removeClass('dimmed ego-highlight path-highlight'); spotlightBar.hidden = true; }), btn('Projection limits & evidence',()=>inspect('Graph projection','PROJECTION EVIDENCE',graph)), btn('Download graph JSON', () => download(graph, 'dependency-graph.json')),btn('Save graph image',async()=>{
            const blob=await state.cy.png({output:'blob-promise',full:true,scale:1,maxWidth:3000,maxHeight:3000,bg: getComputedStyle(document.documentElement).getPropertyValue('--surface').trim()});downloadBlob(blob,'dependency-graph.png');
          })); content.append(filterBar, controls, spotlightBar, canvas);
          const displayNode=item=>{const module=item.id.startsWith('compound:module:')?state.moduleLabels?.get(item.id.slice('compound:module:'.length)):null;return {...item,label:module==='.'?'Root module':module||(!item.label&&item.kind==='PACKAGE'?'(default package)':item.label),activationStatus:item.properties?.activationStatus,parent:item.parent||undefined};};
          const elements = [...graph.nodes.map(item => ({data: displayNode(item)})), ...graph.edges.map(item => ({data: item}))], colors = getComputedStyle(document.documentElement);
          graph.nodes.forEach(item => state.labels.set(item.id, displayNode(item).label));
          state.cy = cytoscape({container: canvas, elements, layout: layoutConfig(layoutSelect.value), minZoom: .05, maxZoom: 3, wheelSensitivity: .2, style: [
            {selector: 'node', style: {'background-color': colors.getPropertyValue('--accent').trim(), color: colors.getPropertyValue('--text').trim(), label: 'data(label)', 'font-size': 10, 'text-valign': 'bottom', 'text-margin-y': 8, 'text-wrap': 'ellipsis', 'text-max-width': 140, width: 22, height: 22}},
            {selector: ':parent', style: {'background-opacity': .08, 'border-width': 1, 'border-color': colors.getPropertyValue('--muted').trim(), padding: 20, 'text-valign': 'top'}},
            {selector: 'edge', style: {'line-color': colors.getPropertyValue('--muted').trim(), 'target-arrow-color': colors.getPropertyValue('--muted').trim(), 'target-arrow-shape': 'triangle', 'curve-style': 'bezier', width: 1.3, opacity: .65}},
            {selector: 'edge[classification="MAY"]',style:{'line-style':'dashed','line-color':colors.getPropertyValue('--cyan').trim(),'target-arrow-color':colors.getPropertyValue('--cyan').trim()}},
            {selector: 'edge[classification="UNKNOWN"]',style:{'line-style':'dotted','line-color':colors.getPropertyValue('--warning').trim(),'target-arrow-color':colors.getPropertyValue('--warning').trim()}},
            {selector: 'node[activationStatus="UNKNOWN"]',style:{shape:'diamond','border-width':2,'border-style':'dashed','border-color':colors.getPropertyValue('--warning').trim()}},
            {selector: ':selected', style: {'border-width': 3, 'border-color': colors.getPropertyValue('--warning').trim()}},
            {selector: '.ego-highlight', style: {'border-width': 3, 'border-color': colors.getPropertyValue('--cyan').trim(), opacity: 1}},
            {selector: 'edge.ego-highlight', style: {width: 2.5, opacity: 1, 'line-color': colors.getPropertyValue('--accent').trim(), 'target-arrow-color': colors.getPropertyValue('--accent').trim()}},
            {selector: '.dimmed', style: {opacity: 0.14}},
            {selector: '.path-highlight',style:{'line-color':colors.getPropertyValue('--warning').trim(),'target-arrow-color':colors.getPropertyValue('--warning').trim(),'border-color':colors.getPropertyValue('--warning').trim(),'border-width':3,width:3,opacity:1}}
          ]});
          const actualNodes=graph.nodes.filter(item=>!item.id.startsWith('compound:'));
          const selected=selectControl('Graph node',actualNodes.map(item=>[item.id,displayNode(item).label]));
          controls.append(field('Node',selected),btn('Node details',()=>inspectEntity({...graph.nodes.find(item=>item.id===selected.value),identity:selected.value},env)),btn('Focus & expand',()=>{focus={...focus,seed:selected.value};depth.hidden=false;clear.hidden=false;return load();}));
          const destination=selectControl('Path target',actualNodes.map(item=>[item.id,displayNode(item).label]));
          const pathStatus=el('p','small muted');content.append(append(el('div','toolbar'),field('Path target',destination),btn('Find shortest path',async()=>{
            const result=await env.query('shortest-path',{source:selected.value,target:destination.value},undefined,{scope:scope.value,relationshipKinds:focus.relationshipKinds||relationshipKinds(scope.value),projection:projection.value,...(projection.value==='REALIZED'?{realization:focus.realization}:{})});
            if(env.signal.aborted||current!==version)return;state.cy.elements().removeClass('path-highlight dimmed ego-highlight');
            const path=result.items[0];if(path){state.cy.elements().not(':parent').addClass('dimmed');state.cy.nodes().filter(node=>path.vertices.includes(node.id())).removeClass('dimmed').addClass('path-highlight');state.cy.edges().filter(edge=>path.edges.some(item=>item.source===edge.source().id()&&item.target===edge.target().id())).removeClass('dimmed').addClass('path-highlight');}
            pathStatus.textContent=path?'Path highlighted where present in this bounded graph. '+M.pageInfo(result):'No path returned. '+M.pageInfo(result);
            await inspect('Shortest dependency path','PATH EVIDENCE',result);
          })),pathStatus);
          function renderGraphOverview() {
            const titleEl = $('node-title'), descEl = $('node-desc'), detailsEl = $('node-details');
            if (!titleEl || !detailsEl) return;
            titleEl.textContent = 'Graph Overview';
            if (descEl) descEl.textContent = `${graph.nodes.length} nodes · ${graph.edges.length} dependencies in active context.`;
            detailsEl.replaceChildren();

            const summary = el('div', 'graph-summary-card');
            append(summary,
              el('p', 'small muted', `Active layout: ${layoutSelect.value} · Edge projection: ${projection.value}`),
              el('p', 'small', 'Click any node on canvas to spotlight 1-hop blast radius, or pick a top connected hub below:')
            );
            detailsEl.append(summary);

            if (state.cy) {
              const hubs = state.cy.nodes().filter(n => !n.id().startsWith('compound:'))
                .map(n => ({node: n, degree: n.degree(false), label: n.data('label') || n.id(), kind: n.data('kind')}))
                .sort((a, b) => b.degree - a.degree)
                .slice(0, 6);
              if (hubs.length) {
                const hubBox = el('div', 'graph-hubs-box');
                hubBox.style.marginTop = '12px';
                hubBox.append(el('p', 'eyebrow-mini', 'TOP CONNECTED HUBS'));
                for (const hub of hubs) {
                  const chip = btn('', () => spotlightNode(hub.node), 'graph-hub-chip');
                  append(chip, el('strong', '', hub.label), el('small', 'muted', ` (${hub.degree} links)`));
                  hubBox.append(chip);
                }
                detailsEl.append(hubBox);
              }
            }
          }
          function spotlightNode(cyNode) {
            const item = cyNode.data();
            if (item.id.startsWith('compound:')) return inspect(item.label, item.kind, item);
            selected.value = item.id;
            state.cy.elements().removeClass('dimmed ego-highlight');
            const ego = cyNode.closedNeighborhood();
            state.cy.elements().not(ego).not(':parent').addClass('dimmed');
            ego.addClass('ego-highlight');
            const inDeg = cyNode.indegree(false), outDeg = cyNode.outdegree(false);
            spotlightBar.hidden = false;
            spotlightBar.replaceChildren(
              append(el('div', 'spotlight-info'), el('strong', '', item.label), badge((item.kind || 'NODE').toLowerCase()), el('span', 'small muted', inDeg + ' incoming · ' + outDeg + ' outgoing on canvas')),
              append(el('div', 'spotlight-actions'),
                btn('Inspect details', () => inspectEntity({...item, identity: item.id}, env), 'button primary'),
                btn('Isolate 1-hop subgraph', () => { focus = {...focus, seed: item.id}; depth.value = '1'; depth.hidden = false; clear.hidden = false; return load(); }, 'button subtle'),
                btn('Set as path target', () => { destination.value = item.id; }, 'button subtle'),
                btn('Clear spotlight', () => { state.cy.elements().removeClass('dimmed ego-highlight'); spotlightBar.hidden = true; renderGraphOverview(); }, 'text-button')
              )
            );
            const titleEl = $('node-title'), descEl = $('node-desc'), detailsEl = $('node-details');
            if (titleEl && detailsEl) {
              titleEl.textContent = item.label;
              if (descEl) descEl.textContent = `${item.kind || 'Component'} · ${inDeg} incoming, ${outDeg} outgoing relationships`;
              detailsEl.replaceChildren();
              const actions = el('div', 'arch-inspector-actions');
              actions.style.display = 'flex'; actions.style.flexDirection = 'column'; actions.style.gap = '8px'; actions.style.marginTop = '10px';
              append(actions,
                btn('Inspect entity details & source ↗', () => inspectEntity({...item, identity: item.id}, env), 'button primary'),
                btn('Isolate 1-hop neighborhood', () => { focus = {...focus, seed: item.id}; depth.value = '1'; depth.hidden = false; clear.hidden = false; return load(); }, 'button subtle'),
                btn('Reset to graph overview', () => { state.cy.elements().removeClass('dimmed ego-highlight'); spotlightBar.hidden = true; renderGraphOverview(); }, 'text-button')
              );
              detailsEl.append(actions);
            }
          }
          renderGraphOverview();
          state.cy.on('tap', 'node', event => safe(() => spotlightNode(event.target)));
          state.cy.on('tap','edge',event=>safe(()=>{
            const item=event.target.data(),actions=[];
            for(const reference of item.properties?.evidence||[])actions.push({label:'Open source evidence',run:()=>inspect('Dependency source','SOURCE EVIDENCE',signal=>api('/graphs/'+env.graph+'/queries/evidence',{request:env.request,arguments:{reference}},signal))});
            return inspect('Dependency evidence','DEPENDENCY',item,actions);
          }));
          canvas.addEventListener('keydown', event => { if (event.key === '+' || event.key === '=') state.cy.zoom(state.cy.zoom() * 1.2); else if (event.key === '-') state.cy.zoom(state.cy.zoom() / 1.2); else if (event.key === '0') state.cy.fit(undefined, 36); });
          const legend = append(el('div', 'graph-legend'),
            el('span', 'legend-must', 'Solid: Required (MUST)'),
            el('span', 'legend-may', 'Dashed: Conditional (MAY)'),
            el('span', 'legend-unknown', 'Dotted: Uncertain (UNKNOWN)'),
            el('span', 'legend-tip', 'Tip: Click any node to spotlight its 1-hop neighborhood · Scroll to zoom · Drag to pan')
          );
          content.append(legend);
        } catch (error) { if (!env.signal.aborted && current === version) content.replaceChildren(empty('Graph unavailable', error.message), btn('Retry graph', load)); }
      }
      layoutSelect.addEventListener('change', () => { if (state.cy) state.cy.layout(layoutConfig(layoutSelect.value)).run(); });
      scope.addEventListener('change', () => {focus={...focus,seed:null,relationshipKinds:null};depth.hidden=true;clear.hidden=true;safe(load);}); projection.addEventListener('change', () => safe(load));depth.addEventListener('change',()=>safe(load)); return {node, after: () => safe(load)};
    },
    async ai(env) {
      // AI Pipeline view — surfaces the pipeline panel from the HTML.
      // The actual diagnosis/repair content is triggered from the Rules tab
      // via "Diagnose finding" actions; this view just presents the shell.
      const node = document.createElement('div');
      const pipelineContent = document.getElementById('ai-pipeline-content');
      if (pipelineContent) {
        // Clone the current pipeline content so it shows in the tab panel
        node.appendChild(pipelineContent.cloneNode(true));
      }
      // Show a contextual guide if no diagnosis is in progress
      const existing = node.querySelector('.empty');
      if (!existing) {
        node.append(
          (() => { const d = document.createElement('div'); d.className = 'empty'; d.innerHTML = '<h3>No finding selected</h3><p>Open the <strong>Rules</strong> tab, inspect a violation finding, and choose <em>Diagnose finding</em> to start the AI pipeline.</p>'; return d; })()
        );
      }
      return { node, status: 'AI Pipeline — Diagnosis · Repair · Verification' };
    },
    async insights(env) {
      const root = el('div');
      const scopeSel = selectControl('Analysis scope', env.springContext
        ? [['SPRING_COMPONENT', 'Spring Components']]
        : [['TYPE', 'Classes / Types'], ['PACKAGE', 'Packages'], ['MODULE', 'Modules']], env.springContext ? 'SPRING_COMPONENT' : 'TYPE');
      const toolbar = el('div', 'toolbar');
      const exportBar = el('div', 'toolbar-actions');
      const body = el('div');
      append(toolbar, field('Advisor scope', scopeSel), exportBar);
      append(root, toolbar, body);

      async function loadInsights() {
        const sc = scopeSel.value;
        body.replaceChildren(empty('Computing architectural insights…', 'Running Tarjan SCC cycle detection, cut-edge simulation, and condensation-DAG blast radius.'));
        exportBar.replaceChildren();
        try {
          const req = {...env.request, scope: sc, relationshipKinds: relationshipKinds(sc)};
          const args = {topN: 15, ...(env.reportIds?.[0]?.identity ? {report: env.reportIds[0].identity} : {})};
          const data = await api('/graphs/' + env.graph + '/architecture-insights', {request: req, arguments: args}, env.signal);
          if (env.signal.aborted) return;
          body.replaceChildren();

          const copyMdBtn = btn('📋 Copy Markdown Report', async () => {
            try {
              await navigator.clipboard.writeText(data.markdownReport || '');
              copyMdBtn.textContent = '✔ Copied Markdown!';
              setTimeout(() => { copyMdBtn.textContent = '📋 Copy Markdown Report'; }, 1600);
            } catch (_) {}
          }, 'btn ghost btn-sm');
          const dlMdBtn = btn('⬇ Download Executive Report (.md)', () => {
            downloadBlob(new Blob([data.markdownReport || ''], {type: 'text/markdown;charset=utf-8'}), 'architecture-executive-report.md');
          }, 'btn primary btn-sm');
          append(exportBar, copyMdBtn, dlMdBtn);

          const s = data.summary || {};
          const kpis = el('div', 'stats');
          for (const [label, val, sub] of [
            ['Project Entities', s.analyzedEntities ?? 0, `${sc.toLowerCase()} scope`],
            ['Internal Edges', s.internalDependencies ?? 0, `${s.externalDependencies ?? 0} external`],
            ['Cyclic Tangles (SCC)', s.tangleCount ?? 0, `${s.tangledEntityCount ?? 0} tangled (${((Number(s.tangledEntityRatio) || 0) * 100).toFixed(1)}%)`],
            ['Avg Instability (I)', Number(s.averageInstability ?? 0).toFixed(2), `${s.sdpViolationCount ?? 0} SDP warnings`],
            ['Max Blast Radius', s.maxBlastRadius ?? 0, 'Transitive dependents'],
            ['Policy Violations', s.policyFindingCount ?? 0, data.status || 'EVALUATED']
          ]) {
            const st = el('div', 'stat');
            append(st, el('span', '', label), el('strong', '', val), el('small', '', sub));
            kpis.append(st);
          }
          body.append(kpis);

          // 1. Cycle & Tangle Detector + Minimal Cut-Edge Advisor
          const tangleCard = card('1. Cycle & Tangle Detector (Tarjan SCC + Verified Minimal Cut-Edges)',
            'Exact strongly connected components ranked by blast impact, with simulated single-edge cut candidates backed by canonical edge evidence.');
          if (!data.tangles?.length) {
            tangleCard.append(empty('Zero Dependency Cycles (Clean DAG)', 'No strongly connected component cycles were observed in this scope.'));
          } else {
            for (const [idx, t] of data.tangles.entries()) {
              const box = el('div', 'tangle-card');
              const head = el('div', 'tangle-head');
              const focusBtn = btn('Highlight in Graph ↗', () => {
                state.graphFocus = {seed: t.members[0], scope: sc, relationshipKinds: relationshipKinds(sc)};
                return loadTab('graph');
              }, 'btn ghost btn-sm');
              append(head,
                append(el('div'),
                  el('strong', '', `Tangle #${idx + 1} · ${t.memberCount} components · ${t.internalEdgeCount} internal edges`),
                  el('p', 'small muted', t.memberLabels.slice(0, 10).join(' ⇄ ') + (t.memberLabels.length > 10 ? ' …' : ''))
                ),
                append(el('div', 'tangle-badges'), badge(t.severity), focusBtn)
              );
              box.append(head);
              if (t.breakCandidates?.length) {
                const cutList = el('div', 'cut-list');
                for (const c of t.breakCandidates) {
                  const cutRow = el('div', 'cut-candidate');
                  append(cutRow,
                    append(el('div'),
                      el('strong', 'mono small', `✂ Cut: ${c.sourceLabel} → ${c.targetLabel}`),
                      el('p', 'small', c.rationale),
                      el('p', 'small muted mono', `Strategy: ${c.recommendedStrategy} · Supports: ${c.supportCount} · Edge: ${M.shortDigest(c.edgeIdentity)}`)
                    ),
                    badge(c.breaksEntireTangle ? 'SINGLE-CUT DAG' : `MAX SCC → ${c.remainingLargestComponentSize}`)
                  );
                  cutList.append(cutRow);
                }
                box.append(cutList);
              }
              tangleCard.append(box);
            }
          }
          tangleCard.style.marginBottom = '16px';
          body.append(tangleCard);

          // 2. Architectural Hotspots & Blast-Radius Table
          const hotCard = card('2. Architectural Hotspots, Instability (I) & Transitive Blast Radius',
            'Ranked by structural risk: hub bottlenecks, Stable Dependencies Principle (SDP) violations, cycle membership, and condensation-DAG reachability.');
          if (!data.hotspots?.length) {
            hotCard.append(empty('No hotspots available', 'Select an analysis context with project entities.'));
          } else {
            const tbl = el('table', 'comparison-table dsm-table');
            tbl.append(append(el('thead'), append(el('tr'),
              ...['Component', 'Archetype', 'Risk', 'Fan-In (Ca)', 'Fan-Out (Ce)', 'Instability (I)', 'Blast Radius', 'Flags'].map(h => el('th', '', h)))));
            const tb = el('tbody');
            for (const h of data.hotspots) {
              const tr = el('tr');
              const nameBtn = btn(h.label, () => {
                state.graphFocus = {seed: h.identity, scope: sc, relationshipKinds: relationshipKinds(sc)};
                return loadTab('graph');
              }, 'text-button mono');
              const flags = [];
              if (h.inCycle) flags.push(badge('IN CYCLE'));
              if (h.sdpViolation) flags.push(badge('SDP WARN'));
              if (h.policyViolations > 0) flags.push(badge(`${h.policyViolations} VIOLATIONS`));
              append(tr,
                append(el('td'), nameBtn),
                el('td', 'small', h.archetype.replaceAll('_', ' ')),
                append(el('td'), el('strong', 'mono', h.hotspotScore)),
                el('td', 'mono', h.fanIn),
                el('td', 'mono', h.fanOut),
                el('td', 'mono', Number(h.instability).toFixed(2)),
                el('td', 'mono', h.transitiveDependents),
                append(el('td'), ...(flags.length ? flags : [el('span', 'muted small', 'OK')]))
              );
              tb.append(tr);
            }
            tbl.append(tb);
            hotCard.append(append(el('div', 'table-scroll'), tbl));
          }
          hotCard.style.marginBottom = '16px';
          body.append(hotCard);

          // 3. Cross-Layer / Package Coupling Matrix (DSM)
          const dsm = data.couplingMatrix || {};
          const dsmCard = card('3. Cross-Layer / Package Coupling Matrix (DSM)',
            dsm.orderedGroups?.length ? 'Ordered Consumer → Foundation: ' + dsm.orderedGroups.join(' → ') : 'Cross-package/module coupling flows.');
          if (!dsm.cells?.length) {
            dsmCard.append(empty('No cross-group coupling', 'All observed dependencies stay within a single package/module group.'));
          } else {
            const tbl = el('table', 'comparison-table dsm-table');
            tbl.append(append(el('thead'), append(el('tr'),
              ...['From Layer / Group', 'To Layer / Group', 'Distinct Edges', 'Support Weight', 'Layering Status'].map(h => el('th', '', h)))));
            const tb = el('tbody');
            for (const cell of dsm.cells.slice(0, 20)) {
              const statusBadges = [];
              if (cell.bidirectional) statusBadges.push(badge('BIDIRECTIONAL TANGLE'));
              if (cell.backEdge) statusBadges.push(badge('UPWARD BACK-EDGE'));
              if (!statusBadges.length) statusBadges.push(badge('CLEAN FLOW'));
              append(tb, append(el('tr'),
                el('td', 'mono', cell.fromGroup),
                el('td', 'mono', cell.toGroup),
                el('td', 'mono', cell.edgeCount),
                el('td', 'mono', cell.supportWeight),
                append(el('td'), ...statusBadges)
              ));
            }
            tbl.append(tb);
            dsmCard.append(append(el('div', 'table-scroll'), tbl));
          }
          body.append(dsmCard);
        } catch (err) {
          if (!env.signal.aborted) body.replaceChildren(empty('Insights unavailable', err.message));
        }
      }

      scopeSel.addEventListener('change', () => safe(loadInsights));
      await loadInsights();
      return {node: root, status: 'Architecture Insights & Refactoring Advisor loaded.'};
    }
  };
  const presetBox = $('policy-presets');
  if (presetBox && Array.isArray(M.policyTemplates)) {
    for (const preset of M.policyTemplates) {
      const chip = btn(preset.label, () => { $('repository-policy').value = preset.yaml; $('connect-error').hidden = true; }, 'chip');
      chip.title = preset.description;
      presetBox.append(chip);
    }
  }
  document.querySelectorAll('[data-action=connect]').forEach(n => n.addEventListener('click', () => { $('connect-title').textContent='Connect your code'; $('connect-error').hidden = true; $('connect-dialog').showModal(); }));
  $('policy-example').addEventListener('click',()=>{if($('repository-policy').value.trim()){$('connect-error').textContent='The policy field already contains rules. Clear it before inserting an example.';$('connect-error').hidden=false;return;}$('repository-policy').value='schema: architecture-policy:v1\nid: project.cycles\nversion: "1"\nrules:\n  - id: project.no-cycles\n    version: "1"\n    kind: ACYCLIC\n    severity: ERROR\n    scope: TYPE\n    relationships: [graph.depends-on]\n    within: {origins: [PROJECT]}\n';});
  document.querySelectorAll('[data-action=refresh]').forEach(n => n.addEventListener('click', () => safe(async () => {
    await connectServer();
    if (!state.job) return;
    const id = state.job.id, generation = state.pollGeneration, latest = await api('/analysis/jobs/' + id);
    if (selectedJob(id, generation) && latest.revision >= state.job.revision) await selectJob(latest, true);
  })));
  document.querySelectorAll('[data-close]').forEach(n => n.addEventListener('click', () => n.dataset.close === 'inspector' ? closeInspector() : $(n.dataset.close).close()));
  $('inspector').addEventListener('cancel', closeInspector);
  $('connect-form').addEventListener('submit', connectRepository);
  $('disconnect-form')?.addEventListener('submit', disconnectRepository);
  $('analysis-mode')?.addEventListener('change', e => { state.analysisMode = e.target.value || 'FULL'; state.retry = null; syncRunControls(); });
  $('analyze').addEventListener('click', () => safe(() => submit()));
  $('analyze-force').addEventListener('click', () => safe(() => submit(true)));
  $('cancel-job').addEventListener('click', () => safe(async () => {
    const id = state.job?.id, generation = state.pollGeneration; if (!id) return;
    const job = await api('/analysis/jobs/' + id + '/cancel', {});
    if (selectedJob(id, generation) && job.revision >= state.job.revision) { state.job = job; renderJob(job); }
  }));
  $('history-more').addEventListener('click', () => safe(() => refreshHistory(true)));
  $('context').addEventListener('change', () => { state.graphFocus=null;state.explorer=null;state.metricSubject=null;state.context = $('context').value; safe(() => loadTab(state.tab)); });
  $('tabs').addEventListener('click', event => { const tab = event.target.closest('[data-tab]'); if (tab) safe(() => loadTab(tab.dataset.tab)); });
  $('tabs').addEventListener('keydown', event => { if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return; event.preventDefault(); const tabs = [...document.querySelectorAll('[role=tab]')], index = tabs.indexOf(document.activeElement), next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;tabs.forEach((tab,i)=>tab.tabIndex=i===next?0:-1); tabs[next].focus(); });
  $('search-form').addEventListener('submit', event => { event.preventDefault(); safe(() => loadTab('structure')); });
  window.addEventListener('keydown', event => {
    const tag = document.activeElement?.tagName;
    const inInput = ['INPUT', 'TEXTAREA', 'SELECT'].includes(tag) || document.activeElement?.isContentEditable;
    if ((event.key === '/' && !inInput && !event.ctrlKey && !event.metaKey) || ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k')) {
      if (!$('analysis-workspace').hidden && !$('connect-dialog').open && !$('inspector').open) {
        event.preventDefault();
        $('search').focus();
        $('search').select();
      }
    }
  });
  $('provenance').addEventListener('click', () => safe(() => { if (!state.context) return notice('Choose an analysis context first.'); const env = envFor(); return inspect('Analysis provenance', 'PROVENANCE', signal => api('/graphs/' + env.graph + '/queries/provenance', {request: env.request, arguments: {}}, signal)); }));
  $('job-receipt').addEventListener('click', () => safe(() => inspect('Analysis receipt', 'ANALYSIS', state.job)));
  for (const [id, op, label] of [['job-diagnostics', 'input-diagnostics', 'Input diagnostics'], ['job-gaps', 'gaps', 'Supplemental analysis limitations']]) $(id).addEventListener('click', () => safe(async () => {
    const job = state.job;
    return inspect(label, 'ANALYSIS EVIDENCE', (signal, cursor) => api('/analysis/jobs/' + job.id + '/' + op, {size: 20, ...(cursor ? {cursor} : {})}, signal), [{label: 'View graph limitations', run: async () => { closeInspector(); if (state.resultJob?.id === job.id) await loadTab('gaps'); else notice('The displayed graph does not belong to this analysis.'); }}]);
  }));
  $('download-details').addEventListener('click', () => download(state.details, 'evo-evidence.json'));
  $('open-repo-manager')?.addEventListener('click', () => {
    renderRepoManager();
    $('repo-manager-dialog')?.showModal();
  });
  function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    const label = 'Switch to ' + (theme === 'dark' ? 'light' : 'dark') + ' theme';
    const paths = theme === 'dark' ? '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>' : '<path d="M20 15.5A8 8 0 0 1 8.5 4 8 8 0 1 0 20 15.5Z"/>';
    $('theme-toggle').innerHTML = '<svg viewBox="0 0 24 24" aria-hidden="true">' + paths + '</svg>';
    $('theme-toggle').className = 'icon-button';
    $('theme-toggle').setAttribute('aria-label', label);
    $('theme-toggle').setAttribute('data-tooltip', '');
    try { localStorage.setItem('evo_theme', theme); } catch (_) {}
  }
  let theme = 'dark'; try { theme = localStorage.getItem('evo_theme') === 'light' ? 'light' : 'dark'; } catch (_) {} applyTheme(theme);
  $('theme-toggle').addEventListener('click', () => { applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'); if (state.tab === 'graph' && state.context) safe(() => loadTab('graph')); });
  function closeMobileSidebar() {
    const shell = document.querySelector('.app-shell');
    if (!shell.classList.contains('mobile-sidebar-open')) return;
    const restoreFocus = $('sidebar').contains(document.activeElement);
    shell.classList.remove('mobile-sidebar-open');
    syncSidebarState();
    if (restoreFocus) $('sidebar-toggle').focus();
  }
  $('sidebar-toggle').addEventListener('click', () => {
    const shell = document.querySelector('.app-shell'), mobile = window.matchMedia('(max-width:760px)').matches;
    const expanded = shell.classList.toggle(mobile ? 'mobile-sidebar-open' : 'sidebar-collapsed');
    syncSidebarState();
    if (mobile && expanded) $('sidebar').querySelector('a').focus();
    state.cy?.resize();
  });
  $('sidebar').addEventListener('keydown', event => { if (event.key === 'Escape') closeMobileSidebar(); });
  document.addEventListener('focusin', event => {
    if (!$('sidebar').contains(event.target) && event.target !== $('sidebar-toggle') && !event.target.closest('dialog')) closeMobileSidebar();
  });
  function syncSidebarState() { const shell = document.querySelector('.app-shell'); $('sidebar-toggle').setAttribute('aria-expanded', window.matchMedia('(max-width:760px)').matches ? shell.classList.contains('mobile-sidebar-open') : !shell.classList.contains('sidebar-collapsed')); }
  window.addEventListener('resize', () => { state.cy?.resize(); syncSidebarState(); });
  window.EvoApp = { state, loadTab, selectRepo, selectJob, configureRules, api };
  syncSidebarState();
  window.EvoApp = { state, api, loadTab, inspectFinding, inspectDiagnosis, inspectRepairProposal, inspectRepairVerification, selectRemediationFinding };
  safe(connectServer);
})();
